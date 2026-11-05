import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {defaultLibrarySettings, type Collection} from '../shared/library.js'
import {LibraryIndex, sanitizeCollection} from './library/index.js'
import {copyItems, moveItems} from './library/files.js'
import {handleLibraryRequest, type LibraryDeps} from './library/ops.js'
import {UndoShelf} from './library/undo.js'

async function setup(options: {jobs?: () => unknown[]} = {}) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-collections-'))
  const settings = defaultLibrarySettings({downloads: path.join(dir, 'Downloads'), music: path.join(dir, 'Music'), videos: path.join(dir, 'Videos')})
  for (const space of settings.spaces.filter((item) => item.type === 'folder')) await fs.mkdir(space.folder, {recursive: true})
  const file = path.join(dir, 'data', 'library.json')
  const make = () =>
    new LibraryIndex({file, artDir: path.join(dir, 'art'), ffmpeg: () => undefined, jobs: () => (options.jobs?.() ?? []) as never, saveDelay: 10, pushDelay: 5, watch: false, analyse: false})
  const index = make()
  await index.init()
  index.configure(settings)
  const trashed: string[] = []
  const deps = {trash: async (target: string) => void trashed.push(target)}
  return {dir, settings, file, index, make, deps, trashed, cleanup: async () => {
    await index.close()
    await fs.rm(dir, {recursive: true, force: true})
  }}
}
const write = async (file: string, text = 'x') => {
  await fs.mkdir(path.dirname(file), {recursive: true})
  await fs.writeFile(file, text)
  return file
}
const song = async (f: Awaited<ReturnType<typeof setup>>, name: string, space = 'music') => {
  const folder = f.settings.spaces.find((entry) => entry.id === space)!.folder
  return (await f.index.addFile(await write(path.join(folder, name)), {space, managed: true}))!
}

test('every collection field survives a save and reload unchanged', async () => {
  const f = await setup()
  try {
    const a = await song(f, 'a.mp3')
    const b = await song(f, 'b.mp3')
    const full: Collection = {
      id: 'col-1',
      name: 'Gym Mix',
      kind: 'playlist',
      space: 'music',
      itemIds: [b.id, a.id],
      coverItemId: a.id,
      source: {url: 'https://www.youtube.com/playlist?list=PL1', provider: 'YouTube', id: 'PL1'},
      follow: {mode: 'auto', intervalHours: 12, wifiOnly: false, notify: false, confirmAbove: 7, lastCheckedAt: 1234, lastError: 'Could not reach the service.', pending: {count: 9, at: 55}},
      seen: ['youtube:aaaaaaaaaaa', 'youtube:bbbbbbbbbbb', 'youtube:ccccccccccc'],
      newItemIds: [a.id],
      removedItemIds: [b.id],
      createdAt: 10,
      updatedAt: 20,
    }
    assert.deepEqual(sanitizeCollection(JSON.parse(JSON.stringify(full))), full)
  } finally {
    await f.cleanup()
  }
})

test('an index file written by the index round-trips collections exactly', async () => {
  const f = await setup()
  try {
    const a = await song(f, 'a.mp3')
    const col = f.index.ensureCollection({url: 'https://music.youtube.com/playlist?list=PLx&si=1', name: 'Mix', kind: 'playlist', space: 'music'})
    f.index.noteSeen(col.id, ['youtube:aaaaaaaaaaa', 'youtube:bbbbbbbbbbb'])
    f.index.addToCollection(col.id, [a.id])
    f.index.updateCollection(col.id, (c) => {
      c.follow = {...c.follow!, lastCheckedAt: 99, lastError: 'Nope', pending: {count: 30, at: 98}}
      c.newItemIds = [a.id]
      c.removedItemIds = [a.id]
    })
    const own = f.index.saveCollection({name: 'Own', itemIds: [a.id]})
    const before = f.index.snapshot().collections
    await f.index.flush()
    const again = f.make()
    await again.init()
    again.configure(f.settings)
    assert.deepEqual(again.snapshot().collections, before)
    assert.equal(again.collection(own.id).follow, undefined)
    await again.close()
  } finally {
    await f.cleanup()
  }
})

test('playlists from before collections become collections of kind own, in order', async () => {
  const f = await setup()
  try {
    const a = await song(f, 'a.mp3')
    const b = await song(f, 'b.mp3')
    await f.index.flush()
    const stored = JSON.parse(await fs.readFile(f.file, 'utf8'))
    delete stored.collections
    stored.version = 1
    stored.playlists = [{id: 'pl-1', name: 'Night drive', itemIds: [b.id, a.id, 'gone'], createdAt: 5, updatedAt: 9}]
    await f.index.close()
    await fs.writeFile(f.file, JSON.stringify(stored))
    const again = f.make()
    await again.init()
    again.configure(f.settings)
    assert.deepEqual(again.snapshot().collections, [
      {id: 'pl-1', name: 'Night drive', kind: 'own', space: 'music', itemIds: [b.id, a.id], seen: [], createdAt: 5, updatedAt: 9},
    ])
    await again.flush()
    const written = JSON.parse(await fs.readFile(f.file, 'utf8'))
    assert.equal(written.version, 2)
    assert.equal(written.playlists, undefined)
    await again.close()
  } finally {
    await f.cleanup()
  }
})

test('the interim per-item collection becomes a downloaded collection in the same order and learns its source', async () => {
  const jobs: unknown[] = []
  const f = await setup({jobs: () => jobs})
  try {
    const a = await song(f, 'a.mp3')
    const b = await song(f, 'b.mp3')
    const c = await song(f, 'c.mp3')
    await f.index.flush()
    const stored = JSON.parse(await fs.readFile(f.file, 'utf8'))
    stored.collections = undefined
    const group = {id: 'grp-1', title: 'Night drive'}
    // positions 0..2 are c, a, b; each item records the interim shape and a source id
    const position: Record<string, number> = {[c.id]: 0, [a.id]: 1, [b.id]: 2}
    const ids: Record<string, string> = {[a.id]: 'youtube:aaaaaaaaaaa', [b.id]: 'youtube:bbbbbbbbbbb', [c.id]: 'youtube:ccccccccccc'}
    stored.items = stored.items.map((entry: Record<string, unknown>) => ({...entry, sourceId: ids[entry.id as string], jobId: `job-${entry.id}`, collection: {...group, position: position[entry.id as string]}}))
    await f.index.close()
    await fs.writeFile(f.file, JSON.stringify(stored))
    for (const entry of [a, b, c])
      jobs.push({id: `job-${entry.id}`, url: `https://www.youtube.com/watch?v=${ids[entry.id].slice(8)}`, title: entry.title, status: 'completed', file: entry.path, createdAt: 1, group: {...group, url: 'https://music.youtube.com/playlist?list=PLold&si=x', kind: 'playlist'}, kind: 'audio', format: {type: 'audio'}, options: {}, label: 'MP3', percent: 100})
    const again = f.make()
    await again.init()
    again.configure(f.settings)
    const [collection] = again.snapshot().collections
    assert.deepEqual([collection.id, collection.name, collection.kind, collection.space, collection.itemIds], ['grp-1', 'Night drive', 'playlist', 'music', [c.id, a.id, b.id]])
    assert.deepEqual([...collection.seen].sort(), Object.values(ids).sort())
    assert.ok(again.snapshot().items.every((entry) => !('collection' in entry)), 'the per-item field is gone')
    // the source comes from the job that downloaded it; following stays off because skipped items were never recorded
    await again.scan()
    const learned = again.collection('grp-1')
    assert.deepEqual(learned.source, {url: 'https://www.youtube.com/playlist?list=PLold', provider: 'YouTube Music', id: 'PLold'})
    assert.equal(learned.follow?.mode, 'off')
    await again.close()
  } finally {
    await f.cleanup()
  }
})

test('collection operations: own lists reorder and edit by reference, deleting never touches files', async () => {
  const f = await setup()
  try {
    const a = await song(f, 'a.mp3')
    const b = await song(f, 'b.mp3')
    const v = await song(f, 'clip.mp4', 'videos')
    const own = f.index.saveCollection({name: '  Gym   mix ', itemIds: [a.id, 'nope', a.id]})
    assert.equal(own.name, 'Gym mix')
    assert.deepEqual([own.kind, own.space, own.itemIds], ['own', 'music', [a.id]])
    f.index.addToCollection(own.id, [b.id, a.id, v.id])
    assert.deepEqual(f.index.collection(own.id).itemIds, [a.id, b.id, v.id], 'items from any space can be added')
    f.index.reorderCollection(own.id, [v.id, a.id])
    assert.deepEqual(f.index.collection(own.id).itemIds, [v.id, a.id, b.id], 'a member the order leaves out keeps its place at the end')
    f.index.removeFromCollection(own.id, [a.id])
    assert.deepEqual(f.index.collection(own.id).itemIds, [v.id, b.id])
    assert.equal(f.index.saveCollection({id: own.id, name: 'Renamed'}).itemIds.length, 2)
    assert.throws(() => f.index.saveCollection({name: ' '}), /name/)
    assert.throws(() => f.index.saveCollection({id: 'missing', name: 'x'}), /no longer exists/)
    // the items stay in the library and on disk when the collection goes
    f.index.deleteCollection(own.id)
    assert.equal(f.index.snapshot().collections.length, 0)
    assert.equal(f.index.snapshot().items.length, 3)
    assert.ok(await fs.stat(a.path))
    assert.throws(() => f.index.deleteCollection(own.id), /no longer exists/)
    assert.equal(f.trashed.length, 0)
  } finally {
    await f.cleanup()
  }
})

test('follow options are validated; own lists and radio mixes cannot be followed', async () => {
  const f = await setup()
  try {
    const col = f.index.ensureCollection({url: 'https://www.youtube.com/playlist?list=PL1', name: 'P', kind: 'playlist', space: 'music'})
    // Following is opt-in: a collection from a link starts off.
    assert.deepEqual(f.index.collection(col.id).follow, {mode: 'off', intervalHours: 6, wifiOnly: true, notify: true, confirmAbove: 20, lastCheckedAt: f.index.collection(col.id).follow!.lastCheckedAt})
    const set = f.index.setFollow(col.id, {mode: 'manual', intervalHours: 24, notify: false, confirmAbove: 5})
    assert.deepEqual([set.follow!.mode, set.follow!.intervalHours, set.follow!.notify, set.follow!.confirmAbove], ['manual', 24, false, 5])
    assert.throws(() => f.index.setFollow(col.id, {mode: 'sometimes'}), /how to follow/)
    assert.throws(() => f.index.setFollow(col.id, {intervalHours: 5}), /how often/)
    assert.throws(() => f.index.setFollow(col.id, {confirmAbove: 0}), /between 1 and 500/)
    assert.throws(() => f.index.setFollow(col.id, {wifiOnly: 'yes'}), /Invalid follow/)
    const own = f.index.saveCollection({name: 'Own'})
    assert.throws(() => f.index.setFollow(own.id, {mode: 'auto'}), /downloaded from a link/)
    // Keep in sync when downloading: a new playlist follows at that interval, an album or a mix does not, and asking
    // again for an existing collection turns it on without ever turning anything off.
    const synced = f.index.ensureCollection({url: 'https://www.youtube.com/playlist?list=PL2', name: 'S', kind: 'playlist', space: 'music', keepInSyncHours: 12})
    assert.deepEqual([synced.follow!.mode, synced.follow!.intervalHours], ['auto', 12])
    const reused = f.index.ensureCollection({url: 'https://www.youtube.com/playlist?list=PL1', name: 'P', kind: 'playlist', space: 'music', keepInSyncHours: 24})
    assert.deepEqual([reused.id, reused.follow!.mode, reused.follow!.intervalHours], [col.id, 'auto', 24])
    assert.equal(f.index.ensureCollection({url: 'https://www.youtube.com/playlist?list=PL2', name: 'S', kind: 'playlist', space: 'music'}).follow!.mode, 'auto', 'not asking never turns it off')
    assert.equal(f.index.ensureCollection({url: 'https://open.spotify.com/album/ZZZ', name: 'A', kind: 'album', space: 'music', keepInSyncHours: 6}).follow!.mode, 'off')
    const mix = f.index.ensureCollection({url: 'https://www.youtube.com/watch?v=aaaaaaaaaaa&list=RDaaaaaaaaaaa', name: 'Mix', kind: 'playlist', space: 'music'})
    assert.equal(mix.follow!.mode, 'off', 'a radio mix never follows by default')
    assert.throws(() => f.index.setFollow(mix.id, {mode: 'auto'}), /never end/)
    assert.equal(f.index.setFollow(mix.id, {mode: 'manual'}).follow!.mode, 'manual')
    const album = f.index.ensureCollection({url: 'https://open.spotify.com/album/1A2B3C4D5E6F', name: 'Album', kind: 'album', space: 'music'})
    assert.equal(album.follow!.mode, 'off')
  } finally {
    await f.cleanup()
  }
})

test('seen keeps every discovered id, and a deleted item stays seen so it is never downloaded again', async () => {
  const jobs: unknown[] = []
  const f = await setup({jobs: () => jobs})
  try {
    const col = f.index.ensureCollection({url: 'https://www.youtube.com/playlist?list=PL1', name: 'P', kind: 'playlist', space: 'music'})
    f.index.noteSeen(col.id, ['youtube:aaaaaaaaaaa', 'youtube:bbbbbbbbbbb', 'youtube:aaaaaaaaaaa'])
    const file = await write(path.join(f.settings.spaces[1].folder, 'a.mp3'))
    const job = {id: 'j1', url: 'https://www.youtube.com/watch?v=aaaaaaaaaaa', title: 'A', status: 'completed', file, createdAt: 1, completedAt: 2, space: 'music', collectionId: col.id, collectionIndex: 0, followed: true, kind: 'audio', format: {type: 'audio'}, options: {}, label: 'x', percent: 100}
    await f.index.adoptJob(job as never)
    const item = f.index.snapshot().items[0]
    assert.deepEqual(f.index.collection(col.id).itemIds, [item.id])
    assert.deepEqual(f.index.collection(col.id).newItemIds, [item.id], 'items from a follow check are marked new')
    f.index.markOpened(col.id)
    assert.equal(f.index.collection(col.id).newItemIds, undefined)
    f.index.drop([item.id], {forget: true})
    assert.deepEqual(f.index.collection(col.id).itemIds, [])
    assert.deepEqual(f.index.collection(col.id).seen, ['youtube:aaaaaaaaaaa', 'youtube:bbbbbbbbbbb'])
  } finally {
    await f.cleanup()
  }
})

test('tags: imported from the job, capped and normalised, off when importTags is off; user tags are separate', async () => {
  const f = await setup()
  try {
    const job = (id: string, name: string, tags: string[]) => ({id, url: `https://www.youtube.com/watch?v=${id.padEnd(11, 'x')}`, title: name, status: 'completed', file: path.join(f.settings.spaces[1].folder, `${name}.mp3`), createdAt: 1, completedAt: 2, space: 'music', tags, kind: 'audio', format: {type: 'audio'}, options: {}, label: 'x', percent: 100}) as never
    await write(path.join(f.settings.spaces[1].folder, 'one.mp3'))
    await write(path.join(f.settings.spaces[1].folder, 'two.mp3'))
    await f.index.adoptJob(job('one', 'one', ['Lo-Fi', 'lo-fi', ...Array.from({length: 20}, (_, n) => `t${n}`)]))
    const one = f.index.snapshot().items.find((item) => item.title === 'one')!
    assert.equal(one.tags!.length, 15)
    assert.equal(one.tags![0], 'lo-fi')
    f.index.configure({...f.settings, importTags: false})
    await f.index.adoptJob(job('two', 'two', ['jazz']))
    assert.equal(f.index.snapshot().items.find((item) => item.title === 'two')!.tags, undefined)
    f.index.setUserTags([one.id], ['Mine', ' mine ', 'x'.repeat(31)], [])
    assert.deepEqual(f.index.item(one.id).userTags, ['mine'])
    assert.equal(f.index.item(one.id).tags![0], 'lo-fi', 'source tags are untouched')
    f.index.setUserTags([one.id], ['second'], ['mine'])
    assert.deepEqual(f.index.item(one.id).userTags, ['second'])
    f.index.setUserTags([one.id], [], ['second'])
    assert.equal(f.index.item(one.id).userTags, undefined)
    assert.throws(() => f.index.setUserTags([one.id], [], []), /Type a tag/)
    assert.throws(() => f.index.setUserTags(['unknown'], ['x'], []), /no longer in your library/)
  } finally {
    await f.cleanup()
  }
})

test('smart spaces hold no files: they are not move, copy or import targets', async () => {
  const f = await setup()
  try {
    const a = await song(f, 'a.mp3')
    const withSmart = {...f.settings, spaces: [...f.settings.spaces, {id: 'lofi', name: 'Lo-fi', kind: 'mixed' as const, folder: '', layout: 'flat' as const, naming: 'title' as const, skipExisting: false, type: 'smart' as const, rules: {match: 'any' as const, rules: []}}]}
    f.index.configure(withSmart)
    assert.equal(f.index.space('lofi'), undefined)
    assert.deepEqual(f.index.spaces().map((space) => space.id), ['downloads', 'music', 'videos'])
    await assert.rejects(moveItems(f.index, {ids: [a.id], space: 'lofi'}, f.deps), /Choose a space/)
    await assert.rejects(copyItems(f.index, {ids: [a.id], space: 'lofi'}), /Choose a space/)
  } finally {
    await f.cleanup()
  }
})

test('add from library: move relocates the files, copy duplicates them with their tags, collections expand to their items', async () => {
  const f = await setup()
  try {
    const a = await song(f, 'a.mp3')
    const b = await song(f, 'b.mp3')
    f.index.setUserTags([a.id], ['keeper'], [])
    f.index.setFavorite([a.id], true)
    const col = f.index.saveCollection({name: 'Pair', itemIds: [a.id, b.id]})
    const originalB = b.path
    // Pushes are debounced, so a push may land after a short operation ends. Look at the snapshot while the
    // operation is really running (a copy adds its file, a move relocates it) instead of waiting for a push.
    const progress: string[] = []
    const seen = () => {
      const kind = f.index.snapshot().operation?.kind
      if (kind) progress.push(kind)
    }
    const addFile = f.index.addFile.bind(f.index)
    f.index.addFile = (async (...args: Parameters<typeof addFile>) => (seen(), addFile(...args))) as typeof f.index.addFile
    const relocate = f.index.relocate.bind(f.index)
    f.index.relocate = ((...args: Parameters<typeof relocate>) => (seen(), relocate(...args))) as typeof f.index.relocate
    const deps = {trash: f.deps.trash, follow: undefined, index: f.index, manager: undefined, cache: undefined, undo: new UndoShelf()} as unknown as LibraryDeps
    const copied = (await handleLibraryRequest({op: 'libraryAddFrom', collectionIds: [col.id], space: 'videos', addMode: 'copy'}, deps)) as {moved: string[]; failed: unknown[]}
    assert.equal(copied.moved.length, 2)
    assert.equal(copied.failed.length, 0)
    const copies = f.index.snapshot().items.filter((item) => item.space === 'videos')
    assert.equal(copies.length, 2)
    assert.ok(await fs.stat(a.path), 'the original is still there')
    const copyOfA = copies.find((item) => item.title === 'a')!
    assert.deepEqual([copyOfA.userTags, copyOfA.favorite], [['keeper'], true])
    assert.ok(progress.includes('copy'), 'copy reports progress')
    // moving the original: the file leaves its space and the collection follows the new id
    const moved = (await handleLibraryRequest({op: 'libraryAddFrom', ids: [b.id], space: 'downloads'}, deps)) as {moved: string[]}
    assert.equal(moved.moved.length, 1)
    assert.ok(progress.includes('move'))
    await assert.rejects(fs.stat(originalB))
    assert.ok(f.index.collection(col.id).itemIds.includes(moved.moved[0]), 'collections keep a moved item')
    assert.equal(f.index.collection(col.id).itemIds.length, 2)
    // same-space copy does nothing
    const again = (await handleLibraryRequest({op: 'libraryAddFrom', ids: [a.id], space: 'music', addMode: 'copy'}, deps)) as {moved: string[]}
    assert.equal(again.moved.length, 0)
  } finally {
    await f.cleanup()
  }
})

test('queue history keeps the collection a job feeds, its place in the source and its tags', async () => {
  const { migrateJob } = await import('./migrate.js')
  const stored = migrateJob({
    id: 'j1', url: 'https://www.youtube.com/watch?v=aaaaaaaaaaa', title: 'A', status: 'completed', percent: 100, label: 'MP3',
    format: {type: 'audio', target: 'mp3', quality: 'best'}, options: {}, createdAt: 1,
    collectionId: 'col-1', collectionIndex: 4, tags: ['lofi', 3, 'chill'], followed: true,
  })
  assert.deepEqual([stored?.collectionId, stored?.collectionIndex, stored?.tags, stored?.followed], ['col-1', 4, ['lofi', 'chill'], true])
  const plain = migrateJob({id: 'j2', url: 'https://vimeo.com/1', title: 'B', status: 'queued', format: {type: 'video', codec: 'auto', container: 'mp4'}, options: {}, createdAt: 1})
  assert.ok(plain && !('collectionId' in plain) && !('tags' in plain) && !('followed' in plain))
})
