// End to end, on real temp folders and a real library: a Keepfin folder with items, a backup copy added through the host
// (check, preview, confirm), synced in its own worker thread; a file edited on the main folder keeps its old bytes in the
// backup's versions; a two-way copy edited on the drive comes back as a revision; the drive is recognised after a path change.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {createHash, randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import type {CopiesState, CopyDraft, CopyFolderCheck, CopyPreview} from '../shared/copies.js'
import {CopiesHost} from './copiesHost.js'
import {createKeepfinFolder} from './store/keepfinFolder.js'
import {BUILTIN_SPACE_IDS} from './store/mapping.js'
import {StoreLibrary} from './store/persistence.js'

const sha = (text: string) => `sha256:${createHash('sha256').update(text).digest('hex')}`
const ACTOR = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'

async function world() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-copies-app-'))
  const profile = path.join(dir, 'profile')
  const folder = await createKeepfinFolder(path.join(dir, 'Keepfin'), {libraryId: randomUUID(), replicaId: randomUUID(), now: new Date().toISOString(), hide: () => undefined})
  const library = new StoreLibrary({deviceDir: path.join(dir, 'AppData'), profileDir: profile, newId: randomUUID, now: Date.now, store: {watch: false, flushIdleMs: 20}})
  await library.create(folder, ACTOR)
  await library.open(folder, ACTOR)
  const session = library.persistence
  const items = new Map<string, string>()
  /** A file in the Music space as a library item with its first revision. */
  const addItem = async (name: string, text: string) => {
    const item = randomUUID()
    const rel = `Music/${name}`
    await fs.writeFile(path.join(folder.root, 'Music', name), text)
    await session.store.apply([
      {kind: 'item.add', body: {item, fields: {media: 'audio', space: BUILTIN_SPACE_IDS.music, title: name}}},
      {kind: 'item.place', body: {item, place: folder.replicaId, path: rel}},
      {kind: 'item.revise', body: {item, content: sha(text), size: Buffer.byteLength(text), parents: []}},
    ])
    items.set(name, item)
    return item
  }
  const revise = async (name: string, text: string) => {
    await fs.writeFile(path.join(folder.root, 'Music', name), text)
    const item = items.get(name)!
    const observed = await session.store.observed({items: [item]})
    await session.store.apply([{kind: 'item.revise', body: {item, content: sha(text), size: Buffer.byteLength(text), parents: observed.items[item].heads}}])
  }
  const pushes: CopiesState[] = []
  const host = new CopiesHost({
    profileDir: profile,
    mainRoot: folder.root,
    mainReplica: folder.replicaId,
    libraryId: folder.libraryId,
    dbPath: path.join(profile, 'state', 'library-state.v1.db'),
    store: () => session.store,
    newId: randomUUID,
    now: Date.now,
    device: 'test-device',
    push: (event) => pushes.push(event.state),
    pickFolder: async () => undefined,
    watch: false,
    drives: async () => [],
    // the Recycle Bin: a folder moves to <dir>/Recycle Bin
    trash: async (target) => {
      await fs.mkdir(path.join(dir, 'Recycle Bin'), {recursive: true})
      await fs.rename(target, path.join(dir, 'Recycle Bin', path.basename(target)))
    },
  })
  await host.start()
  return {
    dir,
    folder,
    session,
    host,
    pushes,
    addItem,
    revise,
    async cleanup() {
      await host.close()
      await library.close()
      await fs.rm(dir, {recursive: true, force: true})
    },
  }
}

const everything = (mode: CopyDraft['mode'], target: string): CopyDraft => ({
  path: target,
  name: mode === 'backup' ? 'Backup drive' : 'Laptop',
  mode,
  holds: {all: true, spaces: [], playlists: [], favourites: false},
  schedule: {everyHours: null, auto: false},
})

async function settle(host: CopiesHost, placeId: string, until: (s: CopiesState) => boolean, ms = 40_000): Promise<CopiesState> {
  const end = Date.now() + ms
  let state = await host.state()
  while (Date.now() < end) {
    state = await host.state()
    const place = state.places.find((p) => p.id === placeId)
    if (place && place.status !== 'syncing' && until(state)) return state
    await new Promise((r) => setTimeout(r, 150))
  }
  throw new Error(`The copy did not settle: ${JSON.stringify(state.places.map((p) => [p.name, p.status, p.kept, p.waiting]))}`)
}

const read = (...parts: string[]) => fs.readFile(path.join(...parts), 'utf8')
const walk = async (root: string): Promise<string[]> => {
  const out: string[] = []
  for (const e of await fs.readdir(root, {withFileTypes: true}).catch(() => [])) {
    const p = path.join(root, e.name)
    if (e.isDirectory()) out.push(...(await walk(p)))
    else out.push(p)
  }
  return out
}

test('a backup copy: check, preview, confirm, sync; an edit on main keeps the old bytes in versions; the library knows it is kept', async () => {
  const w = await world()
  try {
    await w.addItem('one.mp3', 'ONE-v1')
    await w.addItem('two.mp3', 'TWO-v1')
    await w.host.refreshSnapshot()
    const target = path.join(w.dir, 'BackupDrive')
    await fs.mkdir(target)

    const check = (await w.host.handle('copiesCheckFolder', {path: target})) as {ok: boolean; existingFiles: number}
    assert.equal(check.ok, true)
    assert.equal(check.existingFiles, 0)
    const nested = (await w.host.handle('copiesCheckFolder', {path: path.join(w.folder.root, 'Music')})) as {ok: boolean; problem?: string}
    assert.equal(nested.ok, false)
    assert.match(nested.problem ?? '', /inside your Keepfin folder/)

    const preview = (await w.host.handle('copiesPreview', {draft: everything('backup', target)})) as CopyPreview
    assert.equal(preview.status, 'ready')
    assert.equal(preview.additiveOnly, true)
    assert.equal(preview.copyFiles, 2)
    assert.equal(preview.destructive, 0)
    assert.equal(preview.enough, true)
    // a preview changes nothing on the drive but the Keepfin folder marker
    assert.deepEqual((await walk(target)).map((f) => path.relative(target, f)).filter((f) => !f.startsWith('.keepfin')), [])

    const added = (await w.host.handle('copiesAdd', {placeId: preview.placeId})) as CopiesState
    assert.equal(added.places.length, 1)
    const state = await settle(w.host, preview.placeId, (s) => s.places[0].kept === 2 && s.places[0].waiting === 0)
    const place = state.places[0]
    assert.equal(place.status, 'upToDate')
    assert.equal(place.mode, 'backup')
    assert.equal(place.cells[`space:${BUILTIN_SPACE_IDS.music}`], 'kept')
    assert.equal(place.cells[`space:${BUILTIN_SPACE_IDS.videos}`], 'kept')
    assert.equal(await read(target, 'Music', 'one.mp3'), 'ONE-v1')
    assert.equal(await read(target, 'Music', 'two.mp3'), 'TWO-v1')
    assert.equal(JSON.parse(await read(target, '.keepfin', 'folder.json')).libraryId, w.folder.libraryId)
    assert.ok((await walk(path.join(w.folder.root, '.keepfin', 'places'))).some((f) => f.endsWith('.jsonl')), 'the coverage manifest is in the main folder too')
    assert.ok((await walk(path.join(target, '.keepfin', 'ops'))).length > 0, 'the library metadata is on the copy')

    // an edit on main (a new revision): the backup copies the new bytes and keeps the old ones as a version
    await w.revise('one.mp3', 'ONE-v2 is longer')
    await w.host.refreshSnapshot()
    assert.equal((await w.host.state()).places[0].waiting, 1)
    const run = await w.host.syncPlace(preview.placeId, {})
    assert.equal(run?.status, 'done')
    assert.equal(await read(target, 'Music', 'one.mp3'), 'ONE-v2 is longer')
    const kept = await Promise.all((await walk(path.join(target, '.keepfin', 'versions'))).filter((f) => !f.endsWith('.jsonl')).map((f) => read(f)))
    assert.deepEqual(kept, ['ONE-v1'], 'the old bytes are in versions')
    const after = await settle(w.host, preview.placeId, (s) => s.places[0].waiting === 0)
    assert.equal(after.places[0].versions?.files, 1)
    assert.ok(w.pushes.length > 0, 'the renderer is pushed state while copies change')

    // Restore brings the old version back as a new file; Clean up leaves replaced versions inside their retention
    const versions = (await w.host.handle('copiesVersions', {placeId: preview.placeId})) as {id: string; name: string}[]
    assert.equal(versions.length, 1)
    await w.host.handle('copiesRestore', {placeId: preview.placeId, entryId: versions[0].id})
    assert.equal(await read(w.folder.root, 'Restored from backup', 'one.mp3'), 'ONE-v1')
    await w.host.handle('copiesCleanup', {placeId: preview.placeId})
    assert.equal((await w.host.handle('copiesVersions', {placeId: preview.placeId}) as unknown[]).length, 1)
  } finally {
    await w.cleanup()
  }
})

test('a copy holds everything: the lyrics beside a song go too, a lyrics edit is kept as a version, and a folder that already had them settles', async () => {
  const w = await world()
  try {
    const item = await w.addItem('song.mp3', 'SONG')
    const lyrics = async (text: string) => {
      await fs.writeFile(path.join(w.folder.root, 'Music', 'song.lrc'), text)
      await w.session.store.apply([{kind: 'item.set', body: {item, field: 'companion:.lrc', value: {content: sha(text), size: Buffer.byteLength(text)}}}])
    }
    await lyrics('[00:01.00]la')
    await w.host.refreshSnapshot()
    // the owner's case: the folder already holds the song and its lyrics (an earlier Keepfin folder), and one file of its own
    const target = path.join(w.dir, 'OldPlace')
    await fs.mkdir(path.join(target, 'Music'), {recursive: true})
    await fs.writeFile(path.join(target, 'Music', 'song.mp3'), 'SONG')
    await fs.writeFile(path.join(target, 'Music', 'song.lrc'), '[00:01.00]la')
    await fs.writeFile(path.join(target, 'Music', 'other.lrc'), 'not from this library')
    const preview = (await w.host.handle('copiesPreview', {draft: everything('backup', target)})) as CopyPreview
    assert.equal(preview.status, 'ready')
    await w.host.handle('copiesAdd', {placeId: preview.placeId})
    const state = await settle(w.host, preview.placeId, (s) => s.places[0].kept === 2 && s.places[0].waiting === 0)
    const unknown = state.review.filter((r) => r.kind === 'unknownFile').map((r) => r.path)
    assert.deepEqual(unknown, ['Music/other.lrc'], 'the lyrics that belong to the song are no longer a file Keepfin did not put there')

    // new lyrics on main: the backup takes them and keeps the earlier ones as a version; the song is not touched
    await lyrics('[00:01.00]la la, longer')
    await w.host.refreshSnapshot()
    const run = await w.host.syncPlace(preview.placeId, {})
    assert.equal(run?.status, 'done')
    assert.equal(await read(target, 'Music', 'song.lrc'), '[00:01.00]la la, longer')
    assert.equal(await read(target, 'Music', 'song.mp3'), 'SONG')
    const kept = await Promise.all((await walk(path.join(target, '.keepfin', 'versions'))).filter((f) => !f.endsWith('.jsonl')).map((f) => read(f)))
    assert.deepEqual(kept, ['[00:01.00]la'])
    const observed = await w.session.store.viewsFor({all: false, items: [item], playlists: [], spaces: [], sets: [], places: [], profile: false, findings: false})
    assert.deepEqual(observed.items[0].heads.map((h) => h.content), [sha('SONG')], 'the song keeps its own content')

    // Restore puts the earlier lyrics back beside the song, as its lyrics (not a new item under "Restored from backup")
    const versions = (await w.host.handle('copiesVersions', {placeId: preview.placeId})) as {id: string; name: string}[]
    assert.deepEqual(versions.map((v) => v.name), ['song.lrc'])
    await w.host.handle('copiesRestore', {placeId: preview.placeId, entryId: versions[0].id})
    assert.equal(await read(w.folder.root, 'Music', 'song.lrc'), '[00:01.00]la')
    const restored = (await w.session.store.viewsFor({all: false, items: [item], playlists: [], spaces: [], sets: [], places: [], profile: false, findings: false})).items[0]
    assert.deepEqual(restored.fields['companion:.lrc'], {content: sha('[00:01.00]la'), size: 12})
    assert.equal(await fs.stat(path.join(w.folder.root, 'Restored from backup')).then(() => true, () => false), false)

    // left out: turning companions off keeps the lyrics already on the copy
    await w.host.handle('copiesEdit', {placeId: preview.placeId, companions: false})
    assert.equal((await w.host.state()).places[0].companions, false)
    await w.host.syncPlace(preview.placeId, {})
    assert.equal(await read(target, 'Music', 'song.lrc'), '[00:01.00]la la, longer')
  } finally {
    await w.cleanup()
  }
})

test('a two-way copy: lyrics edited on the copy come back as the song\'s lyrics, never as a new version of the song', async () => {
  const w = await world()
  try {
    const item = await w.addItem('song.mp3', 'SONG')
    await fs.writeFile(path.join(w.folder.root, 'Music', 'song.lrc'), 'v1')
    await w.session.store.apply([{kind: 'item.set', body: {item, field: 'companion:.lrc', value: {content: sha('v1'), size: 2}}}])
    await w.host.refreshSnapshot()
    const target = path.join(w.dir, 'Laptop')
    await fs.mkdir(target)
    const preview = (await w.host.handle('copiesPreview', {draft: everything('twoWay', target)})) as CopyPreview
    await w.host.handle('copiesAdd', {placeId: preview.placeId})
    await settle(w.host, preview.placeId, (s) => s.places[0].kept === 2)
    assert.equal(await read(target, 'Music', 'song.lrc'), 'v1')
    // edited on the laptop (a different size and time)
    await new Promise((r) => setTimeout(r, 1100))
    await fs.writeFile(path.join(target, 'Music', 'song.lrc'), 'v2 from the laptop')
    const run = await w.host.syncPlace(preview.placeId, {})
    assert.equal(run?.status, 'done', JSON.stringify(run?.failed))
    assert.equal(await read(w.folder.root, 'Music', 'song.lrc'), 'v2 from the laptop')
    const view = (await w.session.store.viewsFor({all: false, items: [item], playlists: [], spaces: [], sets: [], places: [], profile: false, findings: false})).items[0]
    assert.deepEqual(view.heads.map((h) => h.content), [sha('SONG')])
    assert.deepEqual(view.fields['companion:.lrc'], {content: sha('v2 from the laptop'), size: 18})
  } finally {
    await w.cleanup()
  }
})

test('a cancelled preview leaves the drive as it was; a copy removed stops syncing and keeps its files', async () => {
  const w = await world()
  try {
    await w.addItem('a.mp3', 'AAA')
    await w.host.refreshSnapshot()
    const target = path.join(w.dir, 'Stick')
    await fs.mkdir(target)
    const preview = (await w.host.handle('copiesPreview', {draft: everything('mirror', target)})) as CopyPreview
    assert.equal(preview.status, 'ready')
    assert.ok((await fs.readdir(target)).includes('.keepfin'))
    await w.host.handle('copiesCancelPreview', {placeId: preview.placeId})
    assert.deepEqual(await fs.readdir(target), [])

    // the Keepfin folder the wizard makes inside a picked folder: checked before it exists, named after its parent, gone again on Cancel
    const inside = path.join(target, 'Keepfin')
    const check = (await w.host.handle('copiesCheckFolder', {path: inside})) as CopyFolderCheck
    assert.equal(check.ok, true, check.problem ?? '')
    assert.equal(check.willCreate, true)
    assert.equal(check.suggestedName, 'Stick')
    const made = (await w.host.handle('copiesPreview', {draft: everything('mirror', inside)})) as CopyPreview
    assert.equal(made.status, 'ready')
    assert.ok((await fs.readdir(inside)).includes('.keepfin'))
    await w.host.handle('copiesCancelPreview', {placeId: made.placeId})
    assert.deepEqual(await fs.readdir(target), [])

    const again = (await w.host.handle('copiesPreview', {draft: everything('mirror', target)})) as CopyPreview
    await w.host.handle('copiesAdd', {placeId: again.placeId})
    await settle(w.host, again.placeId, (s) => s.places[0].kept === 1)
    const removed = (await w.host.handle('copiesRemove', {placeId: again.placeId})) as CopiesState
    assert.equal(removed.places.length, 0)
    assert.equal(await read(target, 'Music', 'a.mp3'), 'AAA', 'removing a copy never deletes its files')
  } finally {
    await w.cleanup()
  }
})

test('Remove copy with its files moves the folder to the Recycle Bin; without, the files stay', async () => {
  const w = await world()
  try {
    await w.addItem('a.mp3', 'AAA')
    await w.host.refreshSnapshot()
    const add = async (name: string) => {
      const target = path.join(w.dir, name)
      await fs.mkdir(target)
      const preview = (await w.host.handle('copiesPreview', {draft: everything('backup', target)})) as CopyPreview
      await w.host.handle('copiesAdd', {placeId: preview.placeId})
      await settle(w.host, preview.placeId, (s) => s.places.find((p) => p.id === preview.placeId)?.kept === 1)
      return {target, id: preview.placeId}
    }
    const keep = await add('Kept')
    const gone = await add('Gone')
    await w.host.handle('copiesRemove', {placeId: keep.id})
    assert.equal(await read(keep.target, 'Music', 'a.mp3'), 'AAA', 'removing a copy keeps its files unless asked')
    const state = (await w.host.handle('copiesRemove', {placeId: gone.id, deleteFiles: true})) as CopiesState
    assert.equal(state.places.length, 0)
    assert.equal(await fs.stat(gone.target).catch(() => undefined), undefined, 'the folder left its place')
    assert.equal(await read(w.dir, 'Recycle Bin', 'Gone', 'Music', 'a.mp3'), 'AAA', 'and is in the Recycle Bin')
  } finally {
    await w.cleanup()
  }
})

test('files Keepfin did not put on a copy: leaving them settles each one for good, one after another and after the next sync', async () => {
  const w = await world()
  try {
    await w.addItem('a.mp3', 'AAA')
    await w.host.refreshSnapshot()
    const target = path.join(w.dir, 'Old place')
    await fs.mkdir(path.join(target, 'Music'), {recursive: true})
    await fs.writeFile(path.join(target, 'Music', 'x.lrc'), 'x')
    await fs.writeFile(path.join(target, 'Music', 'y.lrc'), 'y')
    const preview = (await w.host.handle('copiesPreview', {draft: everything('backup', target)})) as CopyPreview
    await w.host.handle('copiesAdd', {placeId: preview.placeId})
    const unknown = (s: CopiesState) => s.review.filter((r) => r.kind === 'unknownFile')
    const synced = await settle(w.host, preview.placeId, (s) => s.places[0].kept === 1 && unknown(s).length === 2)
    let state = synced
    for (const item of unknown(synced)) state = (await w.host.handle('copiesResolve', {reviewId: item.id, action: 'dismiss'})) as CopiesState
    assert.deepEqual(unknown(state), [], 'both are settled, not only the last one')
    await w.host.syncPlace(preview.placeId, {})
    const after = await settle(w.host, preview.placeId, (s) => s.places[0].waiting === 0)
    assert.deepEqual(unknown(after), [], 'the next sync does not bring them back')
    assert.equal(await read(target, 'Music', 'x.lrc'), 'x', 'leaving them never touches them')
  } finally {
    await w.cleanup()
  }
})

test('a two-way copy: an edit made on the drive comes back as a new revision, and the old bytes stay in the Keepfin trash', async () => {
  const w = await world()
  try {
    const item = await w.addItem('song.mp3', 'SONG-v1')
    await w.host.refreshSnapshot()
    const target = path.join(w.dir, 'Laptop')
    await fs.mkdir(target)
    const preview = (await w.host.handle('copiesPreview', {draft: everything('twoWay', target)})) as CopyPreview
    await w.host.handle('copiesAdd', {placeId: preview.placeId})
    await settle(w.host, preview.placeId, (s) => s.places[0].kept === 1)

    const file = path.join(target, 'Music', 'song.mp3')
    await fs.writeFile(file, 'SONG-v2 edited on the laptop')
    const later = new Date(Date.now() + 60_000)
    await fs.utimes(file, later, later)
    const run = await w.host.syncPlace(preview.placeId, {})
    assert.equal(run?.status, 'done')
    assert.equal(await read(w.folder.root, 'Music', 'song.mp3'), 'SONG-v2 edited on the laptop')
    const views = await w.session.store.views()
    const heads = views.items.find((i) => i.id === item)!.heads
    assert.equal(heads.length, 1)
    assert.equal(heads[0].content, sha('SONG-v2 edited on the laptop'))
    const trashed = await Promise.all((await walk(path.join(w.folder.root, '.keepfin', 'trash', 'copies'))).map((f) => read(f)))
    assert.deepEqual(trashed, ['SONG-v1'], 'the bytes the revision replaced are kept')
    await settle(w.host, preview.placeId, (s) => s.places[0].waiting === 0)
  } finally {
    await w.cleanup()
  }
})

test('a drive that comes back on another path is recognised by its replica id', async () => {
  const w = await world()
  try {
    await w.addItem('a.mp3', 'AAA')
    await w.host.refreshSnapshot()
    const target = path.join(w.dir, 'DriveE')
    await fs.mkdir(target)
    const preview = (await w.host.handle('copiesPreview', {draft: everything('backup', target)})) as CopyPreview
    await w.host.handle('copiesAdd', {placeId: preview.placeId})
    await settle(w.host, preview.placeId, (s) => s.places[0].kept === 1)

    // the drive is unplugged and plugged in elsewhere
    const moved = path.join(w.dir, 'DriveF')
    await fs.rename(target, moved)
    const offline = await settle(w.host, preview.placeId, () => true)
    await (w.host as unknown as {tick(): Promise<void>}).tick()
    void offline
    const gone = (await w.host.state()).places[0]
    assert.equal(gone.connected, false)
    assert.equal(gone.status, 'disconnected')
    const withDrives = new CopiesHost({
      profileDir: path.join(w.dir, 'profile'),
      mainRoot: w.folder.root,
      mainReplica: w.folder.replicaId,
      libraryId: w.folder.libraryId,
      dbPath: path.join(w.dir, 'profile', 'state', 'library-state.v1.db'),
      store: () => w.session.store,
      newId: randomUUID,
      now: Date.now,
      device: 'test-device',
      push: () => undefined,
      pickFolder: async () => undefined,
      watch: false,
      drives: async () => [w.dir],
    })
    await withDrives.start()
    await (withDrives as unknown as {tick(): Promise<void>}).tick()
    const back = (await withDrives.state()).places[0]
    assert.equal(back.path, moved)
    assert.equal(back.connected, true)
    await withDrives.close()
  } finally {
    await w.cleanup()
  }
})


test('files that could not be copied are a problem with a plain reason, not a decision to review', async () => {
  const w = await world()
  try {
    await w.addItem('a.mp3', 'AAA')
    await w.host.refreshSnapshot()
    const target = path.join(w.dir, 'Odd drive')
    const preview = (await w.host.handle('copiesPreview', {draft: everything('mirror', target)})) as CopyPreview
    await w.host.handle('copiesAdd', {placeId: preview.placeId})
    await settle(w.host, preview.placeId, (s) => s.places[0].kept === 1 && s.places[0].waiting === 0)
    // the last sync could not write two files: one on a drive that cannot link (Google Drive), one on a full drive
    const failed = [
      {kind: 'CopyTo', path: 'Music/Abyss.lrc', reason: "EISDIR: illegal operation on a directory, link 'G:\\x\\Abyss.lrc.kftmp-rmvmd78pajh14.1-1' -> 'G:\\x\\Abyss.lrc'"},
      {kind: 'CopyTo', path: 'Music/Glass.mp3', reason: 'EISDIR: illegal operation on a directory, link'},
      {kind: 'CopyTo', path: 'Music/Rain.mp3', reason: 'ENOSPC: no space left on device, write'},
    ]
    const host = w.host as unknown as {runtime(id: string): {last: {failed: unknown[]; failedCount: number}}}
    const last = host.runtime(preview.placeId).last
    last.failed = failed
    last.failedCount = 3
    await w.host.refreshSnapshot()
    const state = await w.host.state()
    const place = state.places[0]
    assert.equal(place.status, 'problem')
    assert.equal(place.statusText, '3 files not copied')
    assert.equal(place.review, 0, 'a failure is not a decision')
    assert.deepEqual(state.review.filter((r) => r.placeId === place.id), [])
    assert.deepEqual(place.problem, {
      count: 3,
      reasons: [
        {text: 'This drive does not support how Keepfin saves files.', count: 2},
        {text: 'The drive is full.', count: 1},
      ],
      files: ['Music/Abyss.lrc', 'Music/Glass.mp3', 'Music/Rain.mp3'],
      more: 0,
    })
    assert.doesNotMatch(JSON.stringify(place.problem?.reasons), /EISDIR|ENOSPC|kftmp/, 'no codes and no temporary names reach the screen')
    // the next clean sync clears it
    last.failed = []
    last.failedCount = 0
    const clear = (await w.host.state()).places[0]
    assert.equal(clear.problem, undefined)
    assert.notEqual(clear.status, 'problem')
  } finally {
    await w.cleanup()
  }
})
test('Music videos is its own option: a copy of everything carries the video, a part copy does not until turned on, and the choice is stored', async () => {
  const w = await world()
  try {
    const item = await w.addItem('song.mp3', 'SONG')
    await fs.writeFile(path.join(w.folder.root, 'Music', 'song.video.mp4'), 'VIDEO')
    await w.session.store.apply([{kind: 'item.set', body: {item, field: 'companion:.video.mp4', value: {content: sha('VIDEO'), size: 5}}}])
    await w.host.refreshSnapshot()
    const target = path.join(w.dir, 'Everything')
    await fs.mkdir(target)
    const preview = (await w.host.handle('copiesPreview', {draft: everything('backup', target)})) as CopyPreview
    await w.host.handle('copiesAdd', {placeId: preview.placeId})
    await settle(w.host, preview.placeId, (s) => s.places[0].kept === 2 && s.places[0].waiting === 0)
    assert.equal(await read(target, 'Music', 'song.video.mp4'), 'VIDEO')
    assert.equal((await w.host.state()).places[0].musicVideos, true, 'by the holds, until chosen')
    // chosen off: the copy keeps the video it has, and says off
    await w.host.handle('copiesEdit', {placeId: preview.placeId, musicVideos: false})
    assert.equal((await w.host.state()).places[0].musicVideos, false)
    await w.host.syncPlace(preview.placeId, {})
    assert.equal(await read(target, 'Music', 'song.video.mp4'), 'VIDEO', 'turning it off never deletes')
    // a part copy added with the option on carries the video
    const part = path.join(w.dir, 'Part')
    await fs.mkdir(part)
    const draft = {...everything('backup', part), holds: {all: false, spaces: [BUILTIN_SPACE_IDS.music], playlists: [], favourites: false}, musicVideos: true}
    const second = (await w.host.handle('copiesPreview', {draft})) as CopyPreview
    await w.host.handle('copiesAdd', {placeId: second.placeId})
    await settle(w.host, second.placeId, (s) => s.places.find((p) => p.id === second.placeId)?.waiting === 0 && (s.places.find((p) => p.id === second.placeId)?.kept ?? 0) >= 2)
    assert.equal(await read(part, 'Music', 'song.video.mp4'), 'VIDEO')
    assert.equal((await w.host.state()).places.find((p) => p.id === second.placeId)?.musicVideos, true)
  } finally {
    await w.cleanup()
  }
})
