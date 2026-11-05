// "Where your files are": the counts read from the placement registers (where.ts). The small tests run on a real library through the
// store worker; the scale test fills the state tables of a 200,000-item library directly and times the counting.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {createHash, randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {SqlState} from '../sqlstate/sqlstate.js'
import {createKeepfinFolder} from './keepfinFolder.js'
import {BUILTIN_SPACE_IDS} from './mapping.js'
import {StoreLibrary} from './persistence.js'
import {whereCounts, whereItem} from './where.js'

const ACTOR = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const PHONE = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const sha = (text: string) => `sha256:${createHash('sha256').update(text).digest('hex')}`

async function world() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-where-'))
  const folder = await createKeepfinFolder(path.join(dir, 'Keepfin'), {libraryId: randomUUID(), replicaId: randomUUID(), now: new Date().toISOString(), hide: () => undefined})
  const library = new StoreLibrary({deviceDir: path.join(dir, 'AppData'), profileDir: path.join(dir, 'profile'), newId: randomUUID, now: Date.now, store: {watch: false, flushIdleMs: 20}})
  await library.create(folder, ACTOR)
  await library.open(folder, ACTOR)
  const store = library.persistence.store
  /** An item with a first revision of [size] bytes and a path on each replica given ([null] = the replica has no file). */
  const add = async (media: 'audio' | 'video' | 'image', space: string, size: number, where: Record<string, string | null>) => {
    const item = randomUUID()
    await store.apply([
      {kind: 'item.add', body: {item, fields: {media, space, title: item.slice(0, 6)}}},
      ...Object.entries(where).map(([place, p]) => ({kind: 'item.place', body: {item, place, path: p}})),
      {kind: 'item.revise', body: {item, content: sha(item), size, parents: []}},
    ])
    return item
  }
  return {
    dir,
    folder,
    store,
    main: folder.replicaId,
    add,
    async cleanup() {
      await library.close()
      await fs.rm(dir, {recursive: true, force: true})
    },
  }
}

const MUSIC = BUILTIN_SPACE_IDS.music
const VIDEOS = BUILTIN_SPACE_IDS.downloads
const group = (counts: Awaited<ReturnType<typeof whereCounts>>, replica: string, media: string) => counts.solos.filter((g) => g.replica === replica && g.media === media).reduce((n, g) => n + g.count, 0)

test('files only one replica holds are counted per kind, and a replica that lost its file does not count', async () => {
  const w = await world()
  try {
    const songBoth = await w.add('audio', MUSIC, 1000, {[w.main]: 'Music/both.mp3', [PHONE]: 'Music/both.mp3'})
    await w.add('audio', MUSIC, 2000, {[w.main]: 'Music/here.mp3'})
    await w.add('audio', MUSIC, 3000, {[w.main]: 'Music/here2.mp3'})
    await w.add('video', VIDEOS, 50_000, {[w.main]: 'Videos/clip.mp4'})
    await w.add('video', VIDEOS, 70_000, {[PHONE]: 'Videos/only-phone.mp4'})
    await w.add('image', VIDEOS, 10, {[w.main]: 'Images/a.jpg', [PHONE]: null}) // the phone's file was removed: only this PC has it
    await w.store.flush()

    const counts = await w.store.whereCounts(w.main)
    assert.equal(counts.items, 6)
    const main = counts.replicas.find((r) => r.replica === w.main)!
    const phone = counts.replicas.find((r) => r.replica === PHONE)!
    assert.deepEqual([main.items, main.bytes], [5, 1000 + 2000 + 3000 + 50_000 + 10])
    assert.deepEqual([phone.items, phone.bytes], [2, 1000 + 70_000])
    assert.deepEqual(main.actors, [ACTOR])
    assert.equal(group(counts, w.main, 'audio'), 2)
    assert.equal(group(counts, w.main, 'video'), 1)
    assert.equal(group(counts, w.main, 'image'), 1)
    assert.equal(group(counts, PHONE, 'video'), 1)
    assert.equal(group(counts, PHONE, 'audio'), 0)
    // the groups say the space too
    assert.deepEqual(counts.solos.find((g) => g.replica === w.main && g.media === 'video')?.space, VIDEOS)

    // whereItem: the replicas with a file for one item, and what a copy would select it by
    const facts = await w.store.whereItem(songBoth)
    assert.deepEqual(facts.replicas.map((r) => r.replica).sort(), [w.main, PHONE].sort())
    assert.equal(facts.replicas.find((r) => r.replica === w.main)?.actor, ACTOR)
    assert.equal(facts.space, MUSIC)
    assert.equal(facts.favourite, false)
    assert.deepEqual(facts.playlists, [])
  } finally {
    await w.cleanup()
  }
})

test('a deleted item counts for nobody', async () => {
  const w = await world()
  try {
    const gone = await w.add('audio', MUSIC, 100, {[w.main]: 'Music/gone.mp3'})
    await w.add('audio', MUSIC, 200, {[w.main]: 'Music/kept.mp3'})
    await w.store.flush()
    const observed = await w.store.observed({items: [gone]})
    await w.store.apply([{kind: 'item.delete', body: {item: gone, observedTags: observed.items[gone].tags, observedHeads: observed.items[gone].heads}}])
    const counts = await w.store.whereCounts(w.main)
    assert.equal(counts.items, 1)
    assert.equal(counts.replicas[0].items, 1)
    assert.equal(group(counts, w.main, 'audio'), 1)
  } finally {
    await w.cleanup()
  }
})

test('a file the main replica has and a complete copy also holds is not a single; a copy by spaces or everything is counted from the spaces', async () => {
  const w = await world()
  try {
    await w.add('audio', MUSIC, 1000, {[w.main]: 'Music/a.mp3'})
    await w.add('audio', MUSIC, 2000, {[w.main]: 'Music/b.mp3'})
    await w.add('video', VIDEOS, 9000, {[w.main]: 'Videos/c.mp4'})
    await w.add('video', VIDEOS, 7000, {[PHONE]: 'Videos/phone-only.mp4'})
    await w.store.flush()

    const music = await w.store.whereCounts(w.main, [{id: 'drive', holds: {all: false, spaces: [MUSIC], playlists: [], favourites: false}}])
    assert.deepEqual(music.copies.drive, {items: 2, bytes: 3000})
    assert.equal(group(music, w.main, 'audio'), 0) // both songs are on the drive too
    assert.equal(group(music, w.main, 'video'), 1) // the video is not
    assert.equal(group(music, PHONE, 'video'), 1) // a copy takes this PC's files: the phone's own video is still only there

    const everything = await w.store.whereCounts(w.main, [{id: 'cloud', holds: {all: true, spaces: [], playlists: [], favourites: false}}])
    assert.deepEqual(everything.copies.cloud, {items: 3, bytes: 12_000})
    assert.equal(group(everything, w.main, 'audio') + group(everything, w.main, 'video'), 0)
    assert.equal(group(everything, PHONE, 'video'), 1)

    // no complete copy: nothing is counted for a copy, and every file of this PC stays a single
    const none = await w.store.whereCounts(w.main, [])
    assert.deepEqual(none.copies, {})
    assert.equal(group(none, w.main, 'audio'), 2)
  } finally {
    await w.cleanup()
  }
})

test('a copy that holds favourites or a playlist is looked at item by item and counts exactly its items', async () => {
  const w = await world()
  try {
    const fav = await w.add('audio', MUSIC, 100, {[w.main]: 'Music/fav.mp3'})
    const listed = await w.add('audio', MUSIC, 200, {[w.main]: 'Music/listed.mp3'})
    const other = await w.add('audio', MUSIC, 400, {[w.main]: 'Music/other.mp3'})
    const playlist = randomUUID()
    await w.store.apply([
      {kind: 'set.add', body: {set: 'favourites', element: fav}},
      {kind: 'playlist.add', body: {playlist, fields: {kind: 'playlist', name: 'Road trip', space: MUSIC}}},
      {kind: 'entry.insert', body: {playlist, entry: randomUUID(), item: listed, key: 'a0'}},
    ])
    await w.store.flush()
    const counts = await w.store.whereCounts(w.main, [
      {id: 'favs', holds: {all: false, spaces: [], playlists: [], favourites: true}},
      {id: 'trip', holds: {all: false, spaces: [], playlists: [playlist], favourites: false}},
    ])
    assert.deepEqual(counts.copies.favs, {items: 1, bytes: 100})
    assert.deepEqual(counts.copies.trip, {items: 1, bytes: 200})
    // two of the three are on a copy; the third is a single
    assert.equal(group(counts, w.main, 'audio'), 1)
    assert.ok(other)
    // the same facts for one item: favourite, and in the playlist
    assert.deepEqual(await w.store.whereItem(fav).then((f) => [f.favourite, f.playlists]), [true, []])
    assert.deepEqual(await w.store.whereItem(listed).then((f) => [f.favourite, f.playlists]), [false, [playlist]])
  } finally {
    await w.cleanup()
  }
})

test('a big library (200,000 items with KEEPFIN_SCALE): the counting is one pass over the placement registers and stays well inside its budget', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-where-scale-'))
  const sql = new SqlState(path.join(dir, 'state.db'))
  try {
    // `npm run check` runs every test file at once, so it uses 20,000 items; the 200,000-item budget runs alone:
    // $env:KEEPFIN_SCALE='200000'; npx tsx --test gui/desktop/store/where.test.ts (about 1.2 s / 2.8 s on 2026-10-22)
    const N = Number(process.env.KEEPFIN_SCALE ?? 20_000)
    const MAIN = 'main-replica'
    const insertItem = sql.db.prepare("INSERT INTO item_view (id, status, fields, heads, plays, conflict, dch) VALUES (?, 'present', ?, ?, 0, 0, '[]')")
    const insertReg = sql.db.prepare("INSERT INTO regs (scope, k1, k2, wall, counter, hactor, oactor, oseq, value) VALUES ('placement', ?, ?, 1, 0, ?, ?, ?, ?)")
    const media = ['audio', 'audio', 'audio', 'video', 'image']
    sql.db.exec('BEGIN')
    for (let n = 0; n < N; n++) {
      const id = `${(n * 2654435761 >>> 0).toString(16).padStart(8, '0')}-0000-4000-8000-${String(n).padStart(12, '0')}`
      insertItem.run(id, JSON.stringify({media: media[n % 5], space: n % 5 < 3 ? 'music' : 'videos', title: `t${n}`}), JSON.stringify([{op: `o${n}`, content: `c${n}`, size: 3_000_000 + n}]))
      insertReg.run(id, MAIN, 'actorA', 'actorA', n, JSON.stringify(`Music/${n}.mp3`))
      if (n % 3 === 0) insertReg.run(id, 'phone-replica', 'actorB', 'actorB', n, JSON.stringify(`Music/${n}.mp3`))
      if (n % 10 === 0) insertReg.run(id, 'old-replica', 'actorC', 'actorC', n, 'null')
    }
    sql.db.exec('COMMIT')

    const times: number[] = []
    let counts = whereCounts(sql, MAIN, [])
    // best of 5: `npm run check` runs every test file at once, and one busy moment must not fail a budget
    for (let run = 0; run < 5; run++) {
      const started = performance.now()
      counts = whereCounts(sql, MAIN, [])
      times.push(performance.now() - started)
    }
    const best = Math.min(...times)
    // with a copy that holds a playlist the main replica's files are read one by one once more
    const startedItems = performance.now()
    const withCopy = whereCounts(sql, MAIN, [{id: 'drive', holds: {all: false, spaces: ['music'], playlists: [], favourites: true}}])
    const itemLevel = performance.now() - startedItems
    console.log(`where: ${N.toLocaleString('en')} items: counts ${best.toFixed(0)} ms (runs ${times.map((t) => t.toFixed(0)).join(', ')} ms); with a copy that holds favourites ${itemLevel.toFixed(0)} ms`)

    assert.equal(counts.items, N)
    const main = counts.replicas.find((r) => r.replica === MAIN)!
    const phone = counts.replicas.find((r) => r.replica === 'phone-replica')!
    assert.equal(main.items, N)
    assert.equal(phone.items, Math.ceil(N / 3))
    assert.equal(counts.replicas.some((r) => r.replica === 'old-replica'), false) // null placements hold nothing
    const mainSolos = counts.solos.filter((g) => g.replica === MAIN).reduce((total, g) => total + g.count, 0)
    assert.equal(mainSolos, N - Math.ceil(N / 3))
    assert.ok(withCopy.copies.drive.items > 0)
    // about 1.2 s alone at 200,000 (2026-10-22), in the store worker, cached between library changes
    const budget = 2000 * (N / 200_000) + 100
    assert.ok(best < budget, `counting took ${best.toFixed(0)} ms, budget ${budget.toFixed(0)} ms`)
    assert.ok(itemLevel < budget * 2.5, `the item-by-item pass took ${itemLevel.toFixed(0)} ms`)
  } finally {
    sql.close()
    await fs.rm(dir, {recursive: true, force: true})
  }
})

test('whereItem is empty for an item nobody has a file for', () => {
  const sql = new SqlState(':memory:')
  try {
    assert.deepEqual(whereItem(sql, 'nope'), {replicas: [], space: '', favourite: false, playlists: []})
  } finally {
    sql.close()
  }
})
