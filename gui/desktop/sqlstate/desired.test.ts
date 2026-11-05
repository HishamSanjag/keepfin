// The desired set read from the SQL tables against a reference port of the core's `desiredSetFrom` over the in-memory
// log's views (the core does not export it to JavaScript), on random histories with spaces, placements, favourites,
// playlists, deletes and content conflicts. Also: the place registers and the sort order.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {companionCandidates, conflictPath, fastKey, keepCompanions, type DesiredEntry} from '../../shared/core/copies.js'
import {makeOp, SyncLog, type SyncJson, type SyncViews} from '../../shared/core/sync.js'
import {history, prng, shuffled, uuid} from './history.js'
import {deletedItems, desiredEntries, desiredSorted, holdsKey, placeConfigFrom, readPlaces, type Holds} from './desired.js'
import {SqlState} from './sqlstate.js'

/** The core's `desiredSetFrom`, over the views of the in-memory log. */
function reference(views: SyncViews, mainReplica: string, holds: Holds, companions = true): DesiredEntry[] {
  const favourites = new Set(holds.favourites ? views.sets.favourites ?? [] : [])
  const inPlaylists = new Set<string>()
  for (const pl of views.playlists) if (pl.present && holds.playlists.includes(pl.id)) for (const s of pl.songs) inPlaylists.add(s.item)
  const out: DesiredEntry[] = []
  const candidates: DesiredEntry[] = []
  for (const item of views.items) {
    if (item.status !== 'present') continue
    const space = item.fields.space
    const selected = holds.all || (typeof space === 'string' && holds.spaces.includes(space)) || favourites.has(item.id) || inPlaylists.has(item.id)
    if (!selected) continue
    const path = views.placements[item.id]?.[mainReplica]
    if (typeof path !== 'string') continue
    item.heads.forEach((h, i) => out.push({path: i === 0 ? path : conflictPath(path, h.content), itemId: item.id, revisionOp: h.op, contentId: h.content, bytes: h.size}))
    if (companions) candidates.push(...companionCandidates(item.id, path, item.heads.length, JSON.stringify(item.fields)))
  }
  const mediaKeys = new Set(out.map((e) => fastKey('ascii', e.path)))
  return [...out, ...keepCompanions(candidates, (key) => mediaKeys.has(key))]
}

const byPath = (a: DesiredEntry, b: DesiredEntry) => (a.path < b.path ? -1 : a.path > b.path ? 1 : a.itemId < b.itemId ? -1 : a.itemId > b.itemId ? 1 : a.contentId < b.contentId ? -1 : 1)

function randomOps(seed: number) {
  const rnd = prng(seed)
  const pick = <T>(l: T[]): T => l[Math.floor(rnd() * l.length)]
  const actors = [uuid(rnd), uuid(rnd), uuid(rnd)]
  const items = Array.from({length: 10}, () => uuid(rnd))
  const playlists = Array.from({length: 3}, () => uuid(rnd))
  const spaces = Array.from({length: 3}, () => uuid(rnd))
  const places = [uuid(rnd), uuid(rnd)]
  const seqs = new Map<string, number>()
  const adds = new Map<string, string[]>()
  const revs = new Map<string, string[]>()
  const ops: string[] = []
  const names = ['Music/a.mp3', 'Music/B.mp3', 'Videos/c.mp4', 'Music/d (1).mp3', 'x/é.mp3']
  for (let n = 0; n < 160; n++) {
    const actor = pick(actors)
    const seq = (seqs.get(actor) ?? 0) + 1
    seqs.set(actor, seq)
    const id = `${actor}:${seq}`
    const hlc = `${String(1_700_000_000_000 + n).padStart(13, '0')}.${String(0).padStart(6, '0')}.${actor}`
    const item = pick(items)
    const roll = Math.floor(rnd() * 100)
    let kind: string
    let body: Record<string, SyncJson>
    const note = (m: Map<string, string[]>, k: string) => m.set(k, [...(m.get(k) ?? []), id])
    if (roll < 16) {
      kind = 'item.add'
      body = {item, fields: {media: 'audio', space: pick(spaces)}}
      note(adds, `i${item}`)
    } else if (roll < 22) {
      kind = 'item.set'
      // a companion record (also removed, malformed or under a name that is no companion), or the space
      body =
        rnd() < 0.6
          ? {item, field: `companion:${pick(['.lrc', '.LRC', '.en.srt', '.info.json', '.mp3', 'x.lrc'])}`, value: pick<SyncJson>([{content: 'sha256:' + Math.floor(rnd() * 4).toString(16).repeat(64), size: Math.floor(rnd() * 90)}, null, 'text', {content: 'nope', size: 1}])}
          : {item, field: 'space', value: pick(spaces)}
    } else if (roll < 28) {
      kind = 'item.delete'
      body = {item, observedTags: (adds.get(`i${item}`) ?? []).filter(() => rnd() < 0.8), observedHeads: (revs.get(item) ?? []).filter(() => rnd() < 0.8)}
    } else if (roll < 48) {
      kind = 'item.revise'
      body = {item, content: 'sha256:' + Math.floor(rnd() * 4).toString(16).repeat(64), size: Math.floor(rnd() * 900), parents: (revs.get(item) ?? []).filter(() => rnd() < 0.5)}
      note(revs, item)
    } else if (roll < 66) {
      kind = 'item.place'
      body = {item, place: pick(places), path: rnd() < 0.1 ? null : pick(names)}
    } else if (roll < 74) {
      kind = 'set.add'
      body = {set: 'favourites', element: item}
      note(adds, `s${item}`)
    } else if (roll < 78) {
      kind = 'set.remove'
      body = {set: 'favourites', element: item, observedTags: (adds.get(`s${item}`) ?? []).filter(() => rnd() < 0.8)}
    } else if (roll < 84) {
      const playlist = pick(playlists)
      kind = 'playlist.add'
      body = {playlist}
      note(adds, `p${playlist}`)
    } else if (roll < 86) {
      const playlist = pick(playlists)
      kind = 'playlist.delete'
      body = {playlist, observedTags: (adds.get(`p${playlist}`) ?? []).filter(() => rnd() < 0.8)}
    } else if (roll < 96) {
      kind = 'entry.insert'
      body = {playlist: pick(playlists), entry: uuid(rnd), item, key: pick(['a0', 'a1', 'b00', 'Zz'])}
    } else {
      kind = 'entry.remove'
      body = {entry: uuid(rnd)}
    }
    ops.push(makeOp(actor, seq, hlc, kind, body))
  }
  return {ops, spaces, playlists, places, items}
}

test('desired set: the SQL reader equals the reference on random histories, for every kind of holds', () => {
  let nonEmpty = 0
  let conflicts = 0
  let companions = 0
  for (let seed = 1; seed <= 60; seed++) {
    const rnd = prng(seed * 31)
    const {ops, spaces, playlists, places} = randomOps(seed)
    const noise = history(seed + 500, 40).ops
    const order = shuffled([...ops, ...noise, ...ops.filter(() => rnd() < 0.1)], rnd)
    const log = SyncLog.create()
    const db = new SqlState(':memory:')
    for (const op of order) log.admit(op)
    db.admit(order)
    const views = log.views()
    const main = places[0]
    const holdsList: Holds[] = [
      {all: true, spaces: [], playlists: [], favourites: false, smart: []},
      {all: false, spaces: [spaces[0]], playlists: [], favourites: false, smart: []},
      {all: false, spaces: [spaces[1], spaces[2]], playlists: [], favourites: false, smart: []},
      {all: false, spaces: [], playlists: [playlists[0], playlists[1]], favourites: false, smart: []},
      {all: false, spaces: [], playlists: [], favourites: true, smart: []},
      {all: false, spaces: [spaces[0]], playlists: [playlists[2]], favourites: true, smart: []},
      {all: false, spaces: [], playlists: [], favourites: false, smart: []},
    ]
    for (const [n, holds] of holdsList.entries()) {
      const want = reference(views, main, holds).sort(byPath)
      const got = [...desiredEntries(db.db, main, holds)].sort(byPath)
      assert.deepEqual(got, want, `seed ${seed}, holds ${n}`)
      if (want.length) nonEmpty++
      if (want.some((e) => e.path.includes('(conflict '))) conflicts++
      if (want.some((e) => e.revisionOp?.startsWith('companion:'))) companions++
      assert.deepEqual([...desiredEntries(db.db, main, holds, false)].sort(byPath), reference(views, main, holds, false).sort(byPath), `seed ${seed}, holds ${n}, without companions`)
      // the sorted form is the same set in the planner's order
      const sorted = desiredSorted(db.db, main, holds, 'ascii')
      assert.deepEqual([...sorted].sort(byPath), want)
      for (let i = 1; i < sorted.length; i++) {
        const a = fastKey('ascii', sorted[i - 1].path)
        const b = fastKey('ascii', sorted[i].path)
        assert.ok(a < b || (a === b && sorted[i - 1].path <= sorted[i].path), `seed ${seed}: order`)
      }
    }
    // the deleted list is the items the log calls deleted
    assert.deepEqual(deletedItems(db.db), views.items.filter((i) => i.status === 'deleted').map((i) => i.id).sort())
    db.close()
  }
  assert.ok(nonEmpty > 100, `the histories select something (${nonEmpty})`)
  assert.ok(conflicts > 0, 'some history has a content conflict at a conflict path')
  assert.ok(companions > 20, `some histories carry lyrics, subtitles or info files (${companions})`)
})

test('desired set at scale: lyrics on every other song cost little next to the songs (5,000 items; KEEPFIN_SCALE=200000 for the budget)', () => {
  const n = Number(process.env.KEEPFIN_SCALE ?? 5_000)
  const rnd = prng(11)
  const actor = uuid(rnd)
  const main = uuid(rnd)
  let seq = 0
  const op = (kind: string, body: Record<string, SyncJson>) => makeOp(actor, ++seq, `${String(1_700_000_000_000 + seq).padStart(13, '0')}.000000.${actor}`, kind, body)
  const db = new SqlState(':memory:')
  let batch: string[] = []
  for (let i = 0; i < n; i++) {
    const item = uuid(rnd)
    const path = `Music/Artist ${i % 500}/Track ${i}.mp3`
    batch.push(op('item.add', {item, fields: {media: 'audio', space: 'music', title: `Track ${i}`}}))
    batch.push(op('item.place', {item, place: main, path}))
    batch.push(op('item.revise', {item, content: 'sha256:' + (i % 16).toString(16).repeat(64), size: 1000 + i, parents: []}))
    if (i % 2 === 0) batch.push(op('item.set', {item, field: 'companion:.lrc', value: {content: 'sha256:' + ((i + 1) % 16).toString(16).repeat(64), size: 900}}))
    if (batch.length >= 4000) {
      db.admit(batch)
      batch = []
    }
  }
  db.admit(batch)
  const holds: Holds = {all: true, spaces: [], playlists: [], favourites: false, smart: []}
  const time = (companions: boolean) => {
    const t = performance.now()
    let count = 0
    for (const _ of desiredEntries(db.db, main, holds, companions)) count++
    return {ms: performance.now() - t, count}
  }
  time(true) // warm the statement cache
  const without = time(false)
  const withLyrics = time(true)
  db.close()
  assert.equal(without.count, n)
  assert.equal(withLyrics.count, n + Math.ceil(n / 2))
  console.log(`desired set of ${n} items: ${without.ms.toFixed(0)} ms, with lyrics on every other one ${withLyrics.ms.toFixed(0)} ms`)
  // measured 2026-10-24: 0.75 s without, 1.4 s with 100,000 lyrics files
  if (n >= 200_000) assert.ok(withLyrics.ms <= 3_000, `the desired set with companions took ${withLyrics.ms.toFixed(0)} ms (budget 3 s)`)
})

test('place registers: mode, holds, options and schedule read like the core; a missing or unknown mode is no place', () => {
  const rnd = prng(7)
  const actor = uuid(rnd)
  const place = uuid(rnd)
  const other = uuid(rnd)
  let seq = 0
  const put = (p: string, field: string, value: SyncJson) => makeOp(actor, ++seq, `${String(1_700_000_000_000 + seq).padStart(13, '0')}.000000.${actor}`, 'place.set', {place: p, field, value})
  const db = new SqlState(':memory:')
  db.admit([
    put(place, 'kind', 'folder'),
    put(place, 'name', 'Backup drive'),
    put(place, 'replica', 'r1'),
    put(place, 'mode', 'backup'),
    put(place, 'holds', {all: false, spaces: ['s1'], playlists: ['p1'], favourites: true}),
    put(place, 'options', {versionsKeepDays: 30, flat: true}),
    put(place, 'schedule', {everyHours: 24, auto: false}),
    put(other, 'name', 'No mode'),
  ])
  const places = readPlaces(db.db)
  assert.equal(places.length, 1)
  assert.deepEqual(places[0], {
    id: place,
    kind: 'folder',
    name: 'Backup drive',
    replica: 'r1',
    mode: 'backup',
    holds: {all: false, spaces: ['s1'], playlists: ['p1'], favourites: true, smart: []},
    options: {playlistFiles: false, flat: true, mp3: false, companions: true, versionsKeepDays: 30, deleteGuardPercent: 10},
    schedule: {everyHours: 24, auto: false},
    removed: false,
    verified: null,
  })
  assert.equal(placeConfigFrom('x', {mode: 'sideways'}), null)
  assert.equal(holdsKey(places[0].holds), holdsKey({...places[0].holds, spaces: ['s1']}))
  db.close()
})
