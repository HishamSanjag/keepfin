// The library's existing flows (gui/desktop/library.test.ts) against a LibraryIndex that persists through the Keepfin store.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {setTimeout as delay} from 'node:timers/promises'
import {defaultLibrarySettings, type LibrarySettings} from '../../shared/library.js'
import {LibraryIndex, type PersistedLibrary} from '../library/index.js'
import {deleteItems, importFiles, moveItems, renameItem, restoreTrashed, type TrashRecord} from '../library/files.js'
import {createKeepfinFolder, dataDir, type KeepfinFolder} from './keepfinFolder.js'
import {StoreLibrary} from './persistence.js'

const A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const B = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'

type Device = {name: string; actor: string; library: StoreLibrary; index?: LibraryIndex; settings?: LibrarySettings; artDir?: string}

async function env() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-libstore-'))
  const folder = await createKeepfinFolder(path.join(dir, 'Keepfin'), {libraryId: randomUUID(), replicaId: randomUUID(), now: new Date().toISOString(), hide: () => undefined})
  const devices: Device[] = []
  const trashed: string[] = []
  const deps = {
    trash: async (target: string) => {
      trashed.push(target)
      await fs.rm(target, {force: true})
    },
  }
  const library = (name: string, actor: string, store: Record<string, unknown> = {}) =>
    new StoreLibrary({deviceDir: path.join(dir, name), newId: randomUUID, now: Date.now, store: {watch: false, flushIdleMs: 20, ...store}})
  const start = async (device: Device) => {
    await device.library.open(folder, device.actor)
    const persistence = device.library.persistence
    const index = new LibraryIndex({
      file: path.join(dir, 'unused', 'library.json'),
      artDir: device.artDir ?? path.join(dataDir(folder.root), 'art'),
      ffmpeg: () => undefined,
      persistence,
      newId: randomUUID,
      saveDelay: 10,
      pushDelay: 5,
      watchDelay: 30,
      watch: false,
      analyse: false,
    })
    await index.init()
    const loaded = await persistence.reload()
    const settings: LibrarySettings = {...defaultLibrarySettings({downloads: '', music: '', videos: ''}), spaces: loaded.spaces!, routes: loaded.routes!}
    index.configure(settings)
    device.index = index
    device.settings = settings
    return device
  }
  const device = async (name = 'AppData', actor = A, store: Record<string, unknown> = {}) => {
    const made: Device = {name, actor, library: library(name, actor, store)}
    if (!devices.length) await made.library.create(folder, actor)
    devices.push(made)
    return start(made)
  }
  /** Closes everything and opens it again from disk (what a restart does). */
  const restart = async (made: Device) => {
    await made.index!.close()
    await made.library.close()
    made.library = library(made.name, made.actor)
    return start(made)
  }
  return {
    dir,
    folder,
    deps,
    trashed,
    device,
    restart,
    spaceDir: (made: Device, id: string) => made.settings!.spaces.find((space) => space.id === id)!.folder,
    cleanup: async () => {
      for (const made of devices) {
        await made.index?.close().catch(() => undefined)
        await made.library.close().catch(() => undefined)
      }
      await fs.rm(dir, {recursive: true, force: true})
    },
  }
}

const write = async (file: string, text = 'x') => {
  await fs.mkdir(path.dirname(file), {recursive: true})
  await fs.writeFile(file, text)
  return file
}
async function until(check: () => boolean, label: string, ms = 8000) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    if (check()) return
    await delay(10)
  }
  throw new Error(`Timed out waiting for ${label}`)
}
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

test('a save that changes the spaces writes the edits, additions, removals and a placement that only the new space folders changed', async () => {
  const e = await env()
  const library = new StoreLibrary({deviceDir: path.join(e.dir, 'Direct'), newId: randomUUID, now: Date.now, store: {watch: false, flushIdleMs: 20}})
  try {
    await library.create(e.folder, A)
    await library.open(e.folder, A)
    const session = library.persistence
    const seed = await session.reload()
    const music = seed.spaces!.find((space) => space.id === 'music')!.folder
    const outside = path.join(e.dir, 'Pods')
    const entry = (title: string, file: string) => ({id: randomUUID(), space: 'music', path: file, folder: '', kind: 'audio', title, size: 1, modifiedAt: 1, addedAt: 1, hasArt: false, managed: true}) as unknown as PersistedLibrary['items'][number]
    const a = entry('A', path.join(music, 'a.mp3'))
    const b = entry('B', path.join(music, 'b.mp3'))
    const c = entry('C', path.join(outside, 'c.mp3'))
    await session.save({items: [a, b, c], collections: [], spaces: seed.spaces!, routes: seed.routes!})
    // A space is added whose folder holds c's file (its path does not change, its placement does), a is renamed and b leaves.
    const pods = {...seed.spaces!.find((space) => space.id === 'music')!, id: randomUUID(), name: 'Pods', folder: outside}
    const d = entry('D', path.join(music, 'd.mp3'))
    a.title = 'A2'
    a.favorite = true
    await session.save({items: [a, c, d], collections: [], spaces: [...seed.spaces!, pods], routes: seed.routes!})
    await session.idle()
    const again = await session.reload()
    assert.deepEqual(new Set(again.items.map((item) => item.id)), new Set([a.id, c.id, d.id]))
    const byId = new Map(again.items.map((item) => [item.id, item]))
    assert.equal(byId.get(a.id)!.title, 'A2')
    assert.equal(byId.get(a.id)!.favorite, true)
    assert.equal(byId.get(c.id)!.path, path.join(outside, 'c.mp3'))
    assert.equal(byId.get(d.id)!.path, path.join(music, 'd.mp3'))
    assert.equal(again.spaces!.length, seed.spaces!.length + 1)
    // Nothing is left to write: the same model saves as no operations.
    const before = library.persistence.store.last.get(A)
    await session.save({items: again.items, collections: again.collections, spaces: again.spaces!, routes: again.routes!})
    await session.idle()
    assert.equal(library.persistence.store.last.get(A), before)
  } finally {
    await library.close().catch(() => undefined)
    await e.cleanup()
  }
})
test('store mode: add a file, favourite, tag, make a playlist, restart: everything is back under the same UUIDs', async () => {
  const e = await env()
  try {
    const d = await e.device()
    const index = d.index!
    const music = e.spaceDir(d, 'music')
    assert.equal(d.settings!.spaces.length, 4, 'Downloads, Music, Videos and Favorites')
    assert.ok(music.startsWith(e.folder.root))
    const entry = (await index.addFile(await write(path.join(music, 'Album', 'a.mp3'), 'audio-bytes'), {space: 'music', managed: true}))!
    await write(path.join(music, 'stranger.mp3'))
    assert.match(entry.id, UUID)
    await index.settled()
    const snapshot = index.snapshot()
    assert.equal(snapshot.items.length, 1)
    assert.equal(snapshot.items[0].folder, 'Album')
    assert.ok(!('managed' in snapshot.items[0]) && !('sig' in snapshot.items[0]))
    index.setFavorite([entry.id], true)
    index.setUserTags([entry.id], ['Rock', 'chill'], [])
    index.recordPlayback(entry.id, 40, false)
    const playlist = index.saveCollection({name: 'Mix', itemIds: [entry.id, 'unknown']})
    await index.flush()
    const again = await e.restart(d)
    const reloaded = again.index!.snapshot()
    assert.equal(reloaded.items.length, 1)
    assert.equal(reloaded.items[0].id, entry.id)
    assert.equal(reloaded.items[0].path, path.join(music, 'Album', 'a.mp3'))
    assert.equal(reloaded.items[0].favorite, true)
    assert.deepEqual(reloaded.items[0].userTags, ['chill', 'rock'])
    assert.equal(reloaded.items[0].plays, 1)
    assert.equal(reloaded.items[0].lastPlayedAt !== undefined, true)
    assert.equal(reloaded.collections.length, 1)
    assert.equal(reloaded.collections[0].id, playlist.id)
    assert.deepEqual(reloaded.collections[0].itemIds, [entry.id])
    assert.equal(reloaded.collections[0].kind, 'own')
    // library.json is never written in store mode.
    assert.equal(await fs.stat(path.join(e.dir, 'unused', 'library.json')).catch(() => undefined), undefined)
  } finally {
    await e.cleanup()
  }
})

test('store mode: relocate keeps the item id and everything attached to it', async () => {
  const e = await env()
  try {
    const d = await e.device()
    const index = d.index!
    const videos = e.spaceDir(d, 'videos')
    const original = await write(path.join(videos, 'clip.mp4'), 'video-bytes')
    const entry = (await index.addFile(original, {space: 'videos', managed: true}))!
    await index.settled()
    const id = entry.id
    index.setFavorite([id], true)
    const list = index.saveCollection({name: 'P', itemIds: [id]})
    const renamed = path.join(videos, 'Shows', 'renamed.mp4')
    await fs.mkdir(path.dirname(renamed), {recursive: true})
    await fs.rename(original, renamed)
    const moved = index.relocate(id, renamed, await fs.stat(renamed))
    assert.equal(moved.id, id)
    assert.equal(index.item(id).path, renamed)
    assert.equal(index.item(id).folder, 'Shows')
    assert.deepEqual(index.collection(list.id).itemIds, [id])
    await index.flush()
    const again = await e.restart(d)
    const item = again.index!.item(id)
    assert.equal(item.path, renamed)
    assert.equal(item.folder, 'Shows')
    assert.equal(item.favorite, true)
    assert.deepEqual(again.index!.collection(list.id).itemIds, [id])
  } finally {
    await e.cleanup()
  }
})

test('store mode: import, skip duplicates, unique names, move between spaces, rename', async () => {
  const e = await env()
  try {
    const d = await e.device()
    const index = d.index!
    const source = path.join(e.dir, 'incoming')
    const a = await write(path.join(source, 'a.mp3'), 'AAAA')
    await write(path.join(source, 'sub', 'b.mp3'), 'BBBB')
    const first = await importFiles(index, {paths: [a, path.join(source, 'sub')], space: 'music', mode: 'copy'}, e.deps)
    assert.equal(first.added.length, 2)
    assert.ok(first.added.every((id) => UUID.test(id)))
    const again = await importFiles(index, {paths: [a], space: 'music', mode: 'copy'}, e.deps)
    assert.equal(again.added.length, 0)
    assert.equal(again.duplicates.length, 1)
    const clash = await write(path.join(source, 'x', 'a.mp3'), 'DIFFERENT')
    const named = await importFiles(index, {paths: [clash], space: 'music', mode: 'move'}, e.deps)
    assert.ok(index.item(named.added[0]).path.endsWith('a (2).mp3'))
    const result = await moveItems(index, {ids: first.added, space: 'videos'}, e.deps)
    assert.equal(result.moved.length, 2)
    assert.deepEqual([...result.moved].sort(), [...first.added].sort(), 'moving keeps the ids')
    for (const id of result.moved) {
      const item = index.item(id)
      assert.equal(item.space, 'videos')
      assert.ok(item.path.startsWith(e.spaceDir(d, 'videos')))
    }
    const target = first.added[0]
    const renamed = await renameItem(index, target, 'Better')
    assert.ok(renamed.path.endsWith('Better.mp3'))
    assert.equal(renamed.id, target)
    await index.flush()
    const restarted = await e.restart(d)
    assert.equal(restarted.index!.itemsOf('music').length, 1)
    assert.equal(restarted.index!.itemsOf('videos').length, 2)
    assert.equal(restarted.index!.item(target).title, 'Better')
    assert.ok(restarted.index!.item(target).path.endsWith('Better.mp3'))
  } finally {
    await e.cleanup()
  }
})

test('store mode: deleting items prunes playlists, writes deletes, and a restart shows nothing', async () => {
  const e = await env()
  try {
    const d = await e.device()
    const index = d.index!
    const music = e.spaceDir(d, 'music')
    const ids: string[] = []
    for (let n = 0; n < 30; n++) ids.push((await index.addFile(await write(path.join(music, `s${n}.mp3`)), {space: 'music', managed: true}))!.id)
    index.setUserTags(ids.slice(0, 5), ['mine'], [])
    index.setFavorite(ids.slice(0, 5), true)
    const playlist = index.saveCollection({name: 'All', itemIds: ids})
    await index.flush()
    const keep = ids.slice(0, 3)
    const result = await deleteItems(index, ids.slice(3), e.deps)
    assert.equal(result.deleted.length, 27)
    assert.deepEqual(index.collection(playlist.id).itemIds, keep)
    await index.flush()
    let restarted = await e.restart(d)
    assert.deepEqual(restarted.index!.snapshot().items.map((item) => item.id).sort(), [...keep].sort())
    assert.deepEqual(restarted.index!.collection(playlist.id).itemIds, keep)
    await deleteItems(restarted.index!, keep, e.deps)
    // Un-favouriting and untagging must work after a restart (observed tags are learned from the segments).
    restarted.index!.deleteCollection(playlist.id)
    await restarted.index!.flush()
    restarted = await e.restart(d)
    assert.equal(restarted.index!.snapshot().items.length, 0)
    assert.equal(restarted.index!.snapshot().collections.length, 0)
    assert.deepEqual(await restarted.library.persistence.store.findings(), [])
  } finally {
    await e.cleanup()
  }
})

test('store mode: removing a favourite or a tag after a restart really removes it', async () => {
  const e = await env()
  try {
    let d = await e.device()
    const music = e.spaceDir(d, 'music')
    const entry = (await d.index!.addFile(await write(path.join(music, 'a.mp3')), {space: 'music', managed: true}))!
    d.index!.setFavorite([entry.id], true)
    d.index!.setUserTags([entry.id], ['rock', 'chill'], [])
    await d.index!.flush()
    d = await e.restart(d)
    d.index!.setFavorite([entry.id], false)
    d.index!.setUserTags([entry.id], [], ['rock'])
    await d.index!.flush()
    d = await e.restart(d)
    const item = d.index!.item(entry.id)
    assert.equal(item.favorite, undefined)
    assert.deepEqual(item.userTags, ['chill'])
  } finally {
    await e.cleanup()
  }
})

test('store mode: a cover this device saved in its own art folder shows after a restart, and folder art is served when the device has none', async () => {
  const e = await env()
  try {
    let d = await e.device()
    const artDir = path.join(e.dir, 'device-art')
    d.artDir = artDir
    d = await e.restart(d)
    const music = e.spaceDir(d, 'music')
    const own = (await d.index!.addFile(await write(path.join(music, 'own.mp3')), {space: 'music', managed: true}))!
    const shared = (await d.index!.addFile(await write(path.join(music, 'shared.mp3')), {space: 'music', managed: true}))!
    await d.index!.flush()
    // what a download's thumbnail or a combined library leaves: art named by the item id, outside .keepfin/art
    const ownArt = await write(path.join(artDir, `${own.id}.jpg`), 'jpeg')
    const sharedArt = await write(path.join(dataDir(e.folder.root), 'art', `${shared.id}.jpg`), 'jpeg')
    d = await e.restart(d)
    assert.equal(d.index!.item(own.id).hasArt, true)
    assert.equal(d.index!.item(shared.id).hasArt, true)
    assert.equal(await d.index!.artPath(own.id), ownArt)
    assert.equal(await d.index!.artPath(shared.id), sharedArt)
  } finally {
    await e.cleanup()
  }
})

test('store mode: a photo shows as its own art after a restart', async () => {
  const e = await env()
  try {
    let d = await e.device()
    const photos = e.spaceDir(d, 'music')
    const photo = (await d.index!.addFile(await write(path.join(photos, 'post 1.jpg'), 'jpeg'), {space: 'music', managed: true}))!
    await d.index!.flush()
    d = await e.restart(d)
    assert.equal(d.index!.item(photo.id).hasArt, true)
    assert.equal(await d.index!.artPath(photo.id), photo.path)
  } finally {
    await e.cleanup()
  }
})

test('store mode: a playlist reorder after a restart keeps the new order', async () => {
  const e = await env()
  try {
    let d = await e.device()
    const music = e.spaceDir(d, 'music')
    const ids: string[] = []
    for (let n = 0; n < 8; n++) ids.push((await d.index!.addFile(await write(path.join(music, `s${n}.mp3`)), {space: 'music', managed: true}))!.id)
    const list = d.index!.saveCollection({name: 'L', itemIds: ids})
    await d.index!.flush()
    d = await e.restart(d)
    const order = [ids[7], ...ids.slice(0, 7)]
    d.index!.reorderCollection(list.id, order)
    d.index!.removeFromCollection(list.id, [ids[3]])
    d.index!.addToCollection(list.id, [ids[3]])
    await d.index!.flush()
    d = await e.restart(d)
    assert.deepEqual(d.index!.collection(list.id).itemIds, [...order.filter((id) => id !== ids[3]), ids[3]])
  } finally {
    await e.cleanup()
  }
})

test('store mode: device-only flags survive a restart and never become operations', async () => {
  const e = await env()
  try {
    let d = await e.device()
    const music = e.spaceDir(d, 'music')
    const entry = (await d.index!.addFile(await write(path.join(music, 'a.mp3'), 'x'), {space: 'music', managed: true}))!
    await d.index!.settled()
    entry.sig = 'cached-sig'
    entry.analysed = true
    entry.size = 12345
    d.index!.changed()
    await d.index!.flush()
    const store = d.library.persistence.store
    await store.flush()
    const before = await store.views()
    d = await e.restart(d)
    const again = d.index!.item(entry.id)
    assert.equal(again.sig, 'cached-sig')
    assert.equal(again.analysed, true)
    assert.equal(again.size, 12345)
    assert.deepEqual((await d.library.persistence.store.views()).items.map((item) => item.fields), before.items.map((item) => item.fields))
    const raw = JSON.stringify(await d.library.persistence.store.views())
    assert.ok(!raw.includes('cached-sig') && !raw.includes('12345'))
  } finally {
    await e.cleanup()
  }
})

test('store mode: another device’s changes replace the model, and local edits made at that moment are kept', async () => {
  const e = await env()
  try {
    const a = await e.device('devA', A)
    const b = await e.device('devB', B)
    const music = e.spaceDir(a, 'music')
    const fromA = (await a.index!.addFile(await write(path.join(music, 'fromA.mp3')), {space: 'music', managed: true}))!
    a.index!.setFavorite([fromA.id], true)
    const fromB = (await b.index!.addFile(await write(path.join(music, 'fromB.mp3')), {space: 'music', managed: true}))!
    await a.index!.flush()
    await b.index!.flush()
    // B has an edit that is not saved yet when A's segment arrives.
    b.index!.setUserTags([fromB.id], ['local'], [])
    await a.library.persistence.store.flush()
    await b.library.persistence.store.rescan()
    await until(() => b.index!.all().length === 2, 'the remote item')
    const item = b.index!.item(fromA.id)
    assert.equal(item.favorite, true)
    assert.deepEqual(b.index!.item(fromB.id).userTags, ['local'])
    await b.index!.flush()
    await b.library.persistence.store.flush()
    await a.library.persistence.store.rescan()
    await until(() => a.index!.all().length === 2, 'B’s item on A')
    assert.deepEqual(a.index!.item(fromB.id).userTags, ['local'])
    // A concurrent play on each side adds up.
    a.index!.recordPlayback(fromA.id, 40, false)
    b.index!.recordPlayback(fromA.id, 40, false)
    await a.index!.flush()
    await b.index!.flush()
    await a.library.persistence.store.flush()
    await b.library.persistence.store.flush()
    await a.library.persistence.store.rescan()
    await b.library.persistence.store.rescan()
    await until(() => a.index!.item(fromA.id).plays === 2 && b.index!.item(fromA.id).plays === 2, 'plays to add up')
  } finally {
    await e.cleanup()
  }
})

test('store mode: remote changes are applied by key (favourite off, tag, delete, playlist reorder, rename) without a full projection', async () => {
  const e = await env()
  try {
    const a = await e.device('devA', A)
    const b = await e.device('devB', B)
    const music = e.spaceDir(a, 'music')
    const ids: string[] = []
    for (let n = 0; n < 6; n++) ids.push((await a.index!.addFile(await write(path.join(music, `s${n}.mp3`)), {space: 'music', managed: true}))!.id)
    a.index!.setFavorite([ids[0], ids[1]], true)
    a.index!.setUserTags([ids[2]], ['old'], [])
    const list = a.index!.saveCollection({name: 'L', itemIds: ids})
    await a.index!.flush()
    await a.library.persistence.store.flush()
    const bStore = b.library.persistence.store
    await bStore.rescan()
    await until(() => b.index!.all().length === 6 && b.index!.collectionList().length === 1, 'the first sync')
    // From here only deltas: count full reloads on B.
    const session = b.library.persistence
    let full = 0
    const projectAll = session.store.projectAll.bind(session.store)
    session.store.projectAll = (...args) => {
      full++
      return projectAll(...args)
    }

    a.index!.setFavorite([ids[0]], false)
    a.index!.setUserTags([ids[2]], ['new'], ['old'])
    a.index!.reorderCollection(list.id, [ids[5], ...ids.slice(0, 5)])
    a.index!.relocate(ids[3], path.join(music, 'renamed.mp3'), {size: 1, mtimeMs: 1})
    await deleteItems(a.index!, [ids[4]], e.deps)
    await a.index!.flush()
    await a.library.persistence.store.flush()
    await bStore.rescan()
    await until(() => b.index!.all().length === 5, 'the delete to arrive')
    const view = (id: string) => b.index!.item(id)
    assert.equal(view(ids[0]).favorite, undefined)
    assert.equal(view(ids[1]).favorite, true)
    assert.deepEqual(view(ids[2]).userTags, ['new'])
    assert.equal(view(ids[3]).path, path.join(music, 'renamed.mp3'))
    assert.deepEqual(b.index!.collection(list.id).itemIds, [ids[5], ids[0], ids[1], ids[2], ids[3]])
    assert.equal(full, 0, 'no full projection was needed')
    // The saved copies follow: B saving now writes nothing for these entries.
    const before = await bStore.pendingOps()
    await b.index!.flush()
    assert.equal(await bStore.pendingOps(), before)
  } finally {
    await e.cleanup()
  }
})

/** An index on a StoreLibrary opened with the projection cache (the store opens in the background). */
async function fastOpen(e: Awaited<ReturnType<typeof env>>, name: string, actor: string) {
  const phases: string[] = []
  const library = new StoreLibrary({
    deviceDir: path.join(e.dir, name),
    newId: randomUUID,
    now: Date.now,
    store: {watch: false, flushIdleMs: 20},
    deferStore: true,
    onProgress: (progress) => {
      if (!phases.includes(progress.phase)) phases.push(progress.phase)
    },
  })
  await library.open(e.folder, actor)
  const persistence = library.persistence
  let loaded: PersistedLibrary | undefined
  const load = persistence.load.bind(persistence)
  persistence.load = async () => (loaded = await load())
  const index = new LibraryIndex({file: path.join(e.dir, 'unused', 'library.json'), artDir: path.join(dataDir(e.folder.root), 'art'), ffmpeg: () => undefined, persistence, newId: randomUUID, saveDelay: 10, pushDelay: 5, watch: false, analyse: false})
  await index.init()
  index.configure({...defaultLibrarySettings({downloads: '', music: '', videos: ''}), spaces: loaded!.spaces!, routes: loaded!.routes!})
  return {library, index, phases}
}

test('projection cache: a warm open shows the library before the store is open, edits survive through the journal, and a stale or damaged cache is ignored', async () => {
  const e = await env()
  try {
    const a = await e.device('Cache', A)
    const music = e.spaceDir(a, 'music')
    const ids: string[] = []
    for (let n = 0; n < 12; n++) ids.push((await a.index!.addFile(await write(path.join(music, `s${n}.mp3`)), {space: 'music', managed: true}))!.id)
    a.index!.setFavorite([ids[0]], true)
    a.index!.setUserTags([ids[1]], ['x'], [])
    const list = a.index!.saveCollection({name: 'L', itemIds: ids.slice(0, 5)})
    await a.index!.flush()
    await a.index!.close()
    await a.library.close()
    const cacheDir = path.join(e.dir, 'Cache', 'actors', A, 'cache', 'library-cache.v1')

    // The session that built the library already wrote the cache (a base at its cold open, then journal lines).
    assert.ok((await fs.readdir(cacheDir)).some((name) => name.startsWith('base.')), 'the base was written')
    let opened = await fastOpen(e, 'Cache', A)
    assert.ok(opened.phases.includes('cache'))
    await opened.library.persistence.ready
    await opened.index.close()
    await opened.library.close()
    assert.ok((await fs.readdir(cacheDir)).some((name) => name.startsWith('base.')), 'the base was written')

    // Warm: the library is there while the store is still opening.
    opened = await fastOpen(e, 'Cache', A)
    assert.ok(opened.phases.includes('cache'), 'the cache was used: ' + opened.phases.join())
    assert.equal(opened.index.itemsOf('music').length, 12)
    assert.equal(opened.index.item(ids[0]).favorite, true)
    assert.deepEqual(opened.index.item(ids[1]).userTags, ['x'])
    assert.deepEqual(opened.index.collection(list.id).itemIds, ids.slice(0, 5))
    // An edit made before the store is open waits for it and is saved.
    opened.index.setFavorite([ids[2]], true)
    opened.index.addToCollection(list.id, [ids[7]])
    await opened.index.flush()
    await opened.library.persistence.ready
    await opened.index.close()
    await opened.library.close()
    assert.ok((await fs.readdir(cacheDir)).some((name) => name.startsWith('journal.')), 'a journal was written')

    // Warm again: base plus journal, and it agrees with a full projection of the store.
    opened = await fastOpen(e, 'Cache', A)
    assert.ok(opened.phases.includes('cache'))
    assert.equal(opened.index.item(ids[2]).favorite, true)
    assert.deepEqual(opened.index.collection(list.id).itemIds, [...ids.slice(0, 5), ids[7]])
    await opened.library.persistence.ready
    const full = await opened.library.persistence.model()
    assert.deepEqual(full.items.map((item) => [item.id, item.favorite ?? false, item.title]).sort(), opened.index.all().map((item) => [item.id, item.favorite ?? false, item.title]).sort())
    assert.deepEqual(full.collections.map((c) => [c.id, c.itemIds]), opened.index.collectionList().map((c) => [c.id, c.itemIds]))
    await opened.index.close()
    await opened.library.close()

    // Another device wrote while this one was closed: the cache is stale and is not used.
    const b = new StoreLibrary({deviceDir: path.join(e.dir, 'DevB'), newId: randomUUID, now: Date.now, store: {watch: false, flushIdleMs: 20}})
    await b.open(e.folder, B)
    await b.persistence.store.apply([{kind: 'item.set', body: {item: ids[3], field: 'title', value: 'Changed elsewhere'}}])
    await b.close()
    opened = await fastOpen(e, 'Cache', A)
    assert.ok(!opened.phases.includes('cache'), 'a stale cache is skipped')
    assert.equal(opened.index.item(ids[3]).title, 'Changed elsewhere')
    await opened.index.close()
    await opened.library.close()

    // A damaged cache is ignored as well.
    await fs.writeFile(path.join(cacheDir, 'meta.json'), '{ nope')
    opened = await fastOpen(e, 'Cache', A)
    assert.ok(!opened.phases.includes('cache'))
    assert.equal(opened.index.item(ids[3]).title, 'Changed elsewhere')
    await opened.index.close()
    await opened.library.close()
  } finally {
    await e.cleanup()
  }
})

test('store thread: a crash is survived (the outbox is replayed, later edits go through, nothing durable is lost)', async () => {
  const e = await env()
  try {
    let d = await e.device('Crash', A)
    const music = e.spaceDir(d, 'music')
    const ids: string[] = []
    for (let n = 0; n < 4; n++) ids.push((await d.index!.addFile(await write(path.join(music, `s${n}.mp3`)), {space: 'music', managed: true}))!.id)
    d.index!.setFavorite([ids[0]], true)
    await d.index!.flush() // durable: the outbox holds it, the worker has answered
    const store = d.library.persistence.store
    let restarted = false
    store.onEvent((event) => {
      if (event.name === 'restarted') restarted = true
    })
    void store.crashForTest()
    // A save that was in flight when the worker died fails; the session then compares everything again.
    d.index!.setFavorite([ids[1]], true)
    await d.index!.flush().catch(() => undefined)
    await until(() => restarted, 'the restart')
    await d.index!.flush()
    assert.equal(d.index!.item(ids[0]).favorite, true)
    assert.equal(d.index!.item(ids[1]).favorite, true)
    d.index!.setUserTags([ids[2]], ['after'], [])
    await d.index!.flush()
    d = await e.restart(d)
    assert.equal(d.index!.all().length, 4)
    assert.equal(d.index!.item(ids[0]).favorite, true)
    assert.equal(d.index!.item(ids[1]).favorite, true)
    assert.deepEqual(d.index!.item(ids[2]).userTags, ['after'])
    assert.deepEqual(await d.library.persistence.store.findings(), [])
  } finally {
    await e.cleanup()
  }
})

test('store thread: a whole projection arrives in bounded batches', async () => {
  const e = await env()
  try {
    const d = await e.device('Batches', A)
    const music = e.spaceDir(d, 'music')
    for (let n = 0; n < 25; n++) await d.index!.addFile(await write(path.join(music, `b${n}.mp3`)), {space: 'music', managed: true})
    await d.index!.flush()
    const sizes: number[] = []
    const projected = await d.library.persistence.store.projectAll({replicaId: e.folder.replicaId, root: e.folder.root, linked: {}, batch: 10}, (kind, count) => {
      if (kind === 'items') sizes.push(count)
    })
    assert.equal(projected.items.length, 25)
    assert.deepEqual(sizes, [10, 10, 5])
  } finally {
    await e.cleanup()
  }
})

test('store mode: without persistence the library.json path is unchanged', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-legacy-'))
  try {
    const settings = defaultLibrarySettings({downloads: path.join(dir, 'Downloads'), music: path.join(dir, 'Music'), videos: path.join(dir, 'Videos')})
    const file = path.join(dir, 'data', 'library.json')
    const index = new LibraryIndex({file, artDir: path.join(dir, 'art'), ffmpeg: () => undefined, saveDelay: 10, watch: false, analyse: false})
    await index.init()
    index.configure(settings)
    const entry = (await index.addFile(await write(path.join(settings.spaces[1].folder, 'a.mp3')), {space: 'music', managed: true}))!
    assert.match(entry.id, /^[0-9a-f]{20}$/)
    await index.close()
    assert.ok((await fs.stat(file)).isFile())
  } finally {
    await fs.rm(dir, {recursive: true, force: true})
  }
})

test('store mode: Undo of Delete puts the file back and re-adds the item under the same id, with tags, favourite and its place in the collection, and a restart keeps it', async () => {
  const e = await env()
  try {
    const d = await e.device()
    const music = e.spaceDir(d, 'music')
    assert.ok(music.startsWith(e.folder.root), 'the music space is inside the Keepfin folder')
    const add = async (name: string) => (await d.index!.addFile(await write(path.join(music, name), name), {space: 'music', managed: true}))!
    const a = await add('Album/a.mp3')
    const b = await add('b.mp3')
    const c = await add('c.mp3')
    d.index!.setUserTags([a.id], ['keep'], [])
    d.index!.setFavorite([a.id], true)
    const playlist = d.index!.saveCollection({name: 'Mix', itemIds: [b.id, a.id, c.id]})
    await d.index!.flush()
    const deps = {...e.deps, keepfinRoot: () => e.folder.root}
    let record: TrashRecord | undefined
    const deleted = await deleteItems(d.index!, [a.id], deps, (trash) => (record = trash))
    assert.deepEqual(deleted.undoable, [a.id])
    assert.equal(e.trashed.length, 0, 'not the Recycle Bin')
    assert.equal(await fs.readFile(path.join(e.folder.root, '.keepfin', 'trash', a.id, 'a.mp3'), 'utf8'), 'Album/a.mp3')
    await assert.rejects(fs.access(path.join(music, 'Album', 'a.mp3')))
    await d.index!.flush()
    // the delete is in the store: after a restart the item is gone
    let again = await e.restart(d)
    assert.equal(again.index!.lookup(a.id), undefined)
    assert.deepEqual(again.index!.collection(playlist.id).itemIds, [b.id, c.id])
    // Undo, after the restart
    const back = await restoreTrashed(again.index!, record!, deps)
    assert.deepEqual([back.done, back.failed], [1, 0], back.message ?? '')
    assert.equal(await fs.readFile(path.join(music, 'Album', 'a.mp3'), 'utf8'), 'Album/a.mp3')
    await again.index!.flush()
    // and the store keeps it: a second restart still has the item, its tags, favourite and place
    again = await e.restart(again)
    const item = again.index!.item(a.id)
    assert.equal(item.path, path.join(music, 'Album', 'a.mp3'))
    assert.deepEqual(item.userTags, ['keep'])
    assert.equal(item.favorite, true)
    assert.deepEqual(again.index!.collection(playlist.id).itemIds, [b.id, a.id, c.id])
    assert.deepEqual(await again.library.persistence.store.findings(), [])
  } finally {
    await e.cleanup()
  }
})

test('store mode: Undo of Delete collection brings it back under the same id, with its songs in order, and a restart keeps it', async () => {
  const e = await env()
  try {
    const d = await e.device()
    const music = e.spaceDir(d, 'music')
    const ids: string[] = []
    for (const name of ['a', 'b', 'c']) ids.push((await d.index!.addFile(await write(path.join(music, name + '.mp3')), {space: 'music', managed: true}))!.id)
    const playlist = d.index!.saveCollection({name: 'Road', itemIds: [ids[2], ids[0], ids[1]]})
    await d.index!.flush()
    const saved = structuredClone(d.index!.collection(playlist.id))
    d.index!.deleteCollection(playlist.id)
    await d.index!.flush()
    let again = await e.restart(d)
    assert.throws(() => again.index!.collection(playlist.id), /no longer exists/)
    again.index!.restoreCollection(saved)
    await again.index!.flush()
    again = await e.restart(again)
    const back = again.index!.collection(playlist.id)
    assert.equal(back.name, 'Road')
    assert.deepEqual(back.itemIds, [ids[2], ids[0], ids[1]])
    assert.deepEqual(await again.library.persistence.store.findings(), [])
  } finally {
    await e.cleanup()
  }
})