// With a Keepfin folder open, the download manager's settings must hold the store's spaces (absolute folders inside the
// folder), not the folders from before it: downloads and "Save to" read the settings.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {DownloadManager} from '../manager.js'
import {FakeEngine} from '../fake-engine.js'
import {LibraryIndex} from '../library/index.js'
import {createKeepfinFolder, dataDir} from './keepfinFolder.js'
import {StoreLibrary} from './persistence.js'

// Setup and migration end with a relaunch, so the first download after setup runs through the same start as any later one.
for (const variant of ['a new install (no queue.json)', 'an upgraded profile whose queue.json still holds the old folders'] as const)
test(`settings spaces follow the open Keepfin folder, not the folders from before it: ${variant}`, async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-mirror-'))
  const actor = randomUUID()
  const folder = await createKeepfinFolder(path.join(dir, 'Keepfin'), {libraryId: randomUUID(), replicaId: randomUUID(), now: new Date().toISOString(), hide: () => undefined})
  const store = new StoreLibrary({deviceDir: path.join(dir, 'AppData'), newId: randomUUID, now: Date.now, store: {watch: false, flushIdleMs: 20}})
  await store.create(folder, actor)
  await store.open(folder, actor)
  const index = new LibraryIndex({
    file: path.join(dir, 'unused', 'library.json'), artDir: path.join(dataDir(folder.root), 'art'), ffmpeg: () => undefined,
    persistence: store.persistence, newId: randomUUID, saveDelay: 10, pushDelay: 5, watch: false, analyse: false,
  })
  await index.init()
  const options = {
    store: path.join(dir, 'AppData', 'queue.json'),
    defaultOutDir: path.join(dir, 'old', 'Downloads', 'Keepfin'),
    libraryFolders: {music: path.join(dir, 'old', 'Music', 'Keepfin'), videos: path.join(dir, 'old', 'Videos', 'Keepfin')},
    engine: new FakeEngine(),
    snapshotDelay: 5,
    progressInterval: 20,
  }
  if (variant.startsWith('an upgraded')) {
    // the earlier version's saved settings: the old absolute folders
    const before = new DownloadManager(options)
    await before.init()
    await before.updateSettings({outDir: options.defaultOutDir})
    await before.close()
    const saved = JSON.parse(await fs.readFile(options.store, 'utf8')).settings.library.spaces
    assert.ok(saved.some((space: {folder?: string}) => space.folder?.includes(`${path.sep}old${path.sep}`)))
  }
  const manager = new DownloadManager({...options, library: index})
  try {
    await manager.init() // settings start from the old absolute folders
    const stored = index.storeSpaces()!.spaces.filter((space) => space.type !== 'smart')
    assert.ok(stored.length >= 3, 'the folder has its spaces')
    const mine = manager.getSettings().library.spaces.filter((space) => space.type !== 'smart')
    assert.deepEqual(mine.map((space) => [space.id, space.folder]), stored.map((space) => [space.id, space.folder]))
    for (const space of mine) assert.ok(space.folder.startsWith(folder.root), `${space.id} is inside the Keepfin folder: ${space.folder}`)
    assert.equal(manager.getSettings().outDir, mine.find((space) => space.id === 'downloads')!.folder, 'the download folder from before spaces follows Downloads')
  } finally {
    await manager.close()
    await index.close()
    await store.close()
    await fs.rm(dir, {recursive: true, force: true})
  }
})

// The Keepfin folder moved and the old place stayed (Keep the old place as a backup): the download history still names the old
// files. They must follow to the new place before the library reads the history, or every song is added a second time.
test('after a move, finished downloads follow their files to the new place and the old ones are not added again', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-follow-'))
  const actor = randomUUID()
  const folder = await createKeepfinFolder(path.join(dir, 'Keepfin'), {libraryId: randomUUID(), replicaId: randomUUID(), now: new Date().toISOString(), hide: () => undefined})
  const store = new StoreLibrary({deviceDir: path.join(dir, 'AppData'), newId: randomUUID, now: Date.now, store: {watch: false, flushIdleMs: 20}})
  await store.create(folder, actor)
  await store.open(folder, actor)
  const index = new LibraryIndex({
    file: path.join(dir, 'unused', 'library.json'), artDir: path.join(dataDir(folder.root), 'art'), ffmpeg: () => undefined,
    persistence: store.persistence, newId: randomUUID, saveDelay: 10, pushDelay: 5, watch: false, analyse: false,
  })
  await index.init()
  const old = path.join(dir, 'Old', 'Keepfin')
  const options = {
    store: path.join(dir, 'AppData', 'queue.json'), defaultOutDir: path.join(old, 'Downloads'),
    libraryFolders: {music: path.join(old, 'Music'), videos: path.join(old, 'Videos')},
    engine: new FakeEngine(), snapshotDelay: 5, progressInterval: 20,
  }
  // the history and settings saved before the move: everything in the old place, which is still there
  const before = new DownloadManager(options)
  await before.init()
  await before.close()
  const oldSong = path.join(old, 'Music', 'rr', 'Dune.mp3')
  const newSong = path.join(folder.root, 'Music', 'rr', 'Dune.mp3')
  for (const file of [oldSong, newSong]) {
    await fs.mkdir(path.dirname(file), {recursive: true})
    await fs.writeFile(file, 'song')
  }
  const saved = JSON.parse(await fs.readFile(options.store, 'utf8'))
  const job = {id: 'j1', url: 'https://music.youtube.com/watch?v=a', title: 'Dune', status: 'completed', createdAt: 1, completedAt: 2, format: {type: 'audio', target: 'mp3', quality: 'best'}, file: oldSong, outputTemplate: path.join(old, 'Music', 'rr', 'Dune.%(ext)s')}
  await fs.writeFile(options.store, JSON.stringify({...saved, jobs: [job]}))

  const manager = new DownloadManager({...options, library: index})
  try {
    await manager.init()
    const history = JSON.parse(await fs.readFile(options.store, 'utf8')).jobs[0]
    assert.equal(history.file, newSong, 'the finished download points at the new place')
    assert.equal(history.outputTemplate, path.join(folder.root, 'Music', 'rr', 'Dune.%(ext)s'))
    await index.scan()
    assert.deepEqual(index.snapshot().items.filter((item) => item.path.startsWith(old)), [], 'nothing from the old place is added')
  } finally {
    await manager.close()
    await index.close()
    await store.close()
    await fs.rm(dir, {recursive: true, force: true})
  }
})