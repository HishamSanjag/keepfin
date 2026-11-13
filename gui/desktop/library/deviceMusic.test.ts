import {test} from 'node:test'
import assert from 'node:assert/strict'
import {spawnSync} from 'node:child_process'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {setTimeout as delay} from 'node:timers/promises'
import ffmpegPath from 'ffmpeg-static'
import {deviceMusicIdOf} from '../../shared/core/deviceMusic.js'
import {defaultLibrarySettings, DEVICE_SPACE_ID, type Collection} from '../../shared/library.js'
import {handleLibraryRequest, type LibraryDeps} from './ops.js'
import {LibraryIndex, type LibraryPersistence, type PersistedLibrary} from './index.js'
import {itemIdFor, keyOf} from './parts/sanitize.js'

type Setup = Awaited<ReturnType<typeof setup>>

async function song(file: string, seconds: number, tags: {title?: string; artist?: string; album?: string} = {}) {
  await fs.mkdir(path.dirname(file), {recursive: true})
  const args = ['-hide_banner', '-nostdin', '-y', '-f', 'lavfi', '-i', `sine=frequency=440:sample_rate=8000:duration=${seconds}`]
  for (const [key, value] of Object.entries(tags)) args.push('-metadata', `${key}=${value}`)
  const made = spawnSync(ffmpegPath as string, [...args, '-c:a', 'libmp3lame', '-b:a', '16k', file])
  assert.equal(made.status, 0, 'the test tone was made')
}

/** A library with a Music folder on this PC (`pc`), as Windows has it: two albums, a short clip and a Ringtones folder. */
async function setup(options: {watch?: boolean; persistence?: LibraryPersistence; folders?: string[]} = {}) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-device-'))
  const pc = path.join(dir, 'pc')
  const settings = defaultLibrarySettings({downloads: path.join(dir, 'Keepfin', 'Downloads'), music: path.join(dir, 'Keepfin', 'Music'), videos: path.join(dir, 'Keepfin', 'Videos')})
  for (const space of settings.spaces.filter((item) => item.type === 'folder')) await fs.mkdir(space.folder, {recursive: true})
  const make = () =>
    new LibraryIndex({
      file: path.join(dir, 'data', 'library.json'),
      artDir: path.join(dir, 'art'),
      ffmpeg: () => undefined,
      saveDelay: 10,
      pushDelay: 5,
      watchDelay: 30,
      watch: options.watch ?? false,
      analyse: false,
      persistence: options.persistence,
      device: {folders: () => options.folders ?? [pc]},
    })
  const index = make()
  await index.init()
  index.configure(settings)
  return {dir, pc, settings, make, index, cache: path.join(dir, 'data', 'device-music.json')}
}
const cleanup = async (...all: Setup[]) => {
  for (const one of all) await one.index.close()
  for (const one of all) await fs.rm(one.dir, {recursive: true, force: true})
}
const listed = (index: LibraryIndex) => index.all().filter((entry) => entry.space === DEVICE_SPACE_ID)
const fill = async (pc: string) => {
  await song(path.join(pc, 'Rock', 'one.mp3'), 31, {title: 'One', artist: 'Band', album: 'First'})
  await song(path.join(pc, 'Rock', 'two.mp3'), 31, {title: 'Two', artist: 'Band', album: 'First'})
  await song(path.join(pc, 'Pop', 'three.mp3'), 31, {title: 'Three', artist: 'Solo'})
  await song(path.join(pc, 'Pop', 'clip.mp3'), 10, {title: 'Clip'})
  await song(path.join(pc, 'Ringtones', 'ring.mp3'), 31, {title: 'Ring'})
  await fs.writeFile(path.join(pc, 'Pop', 'notes.txt'), 'not audio')
}
const turnOn = async (one: Setup) => {
  await one.index.device.setEnabled(true)
  await one.index.settled()
}

test('Music on this PC lists the songs by the shared rules: music only, in place', async () => {
  const one = await setup()
  await fill(one.pc)
  await turnOn(one)
  assert.deepEqual(listed(one.index).map((entry) => entry.title).sort(), ['One', 'Three', 'Two'])
  const state = one.index.snapshot().deviceMusic!
  assert.deepEqual({enabled: state.enabled, count: state.count, scanning: state.scanning}, {enabled: true, count: 3, scanning: false})
  const one3 = listed(one.index)[0]
  assert.equal(one3.managed, false)
  assert.match(one3.id, /^dm-pc-[A-Za-z0-9_-]+$/)
  // the short clip is remembered as skipped so it is not read again
  await delay(100)
  const saved = JSON.parse(await fs.readFile(one.cache, 'utf8')) as {items: Record<string, {skipped?: boolean}>}
  assert.equal(Object.values(saved.items).filter((item) => item.skipped).length, 1)
  assert.equal(one.index.snapshot().items.length, 3, 'the window sees the songs of the device space only while it is on')
  await cleanup(one)
})

test('nothing is listed until it is switched on, and switching off takes the songs away', async () => {
  const one = await setup()
  await fill(one.pc)
  assert.equal(listed(one.index).length, 0)
  assert.equal(one.index.snapshot().deviceMusic?.enabled, false)
  await turnOn(one)
  assert.equal(listed(one.index).length, 3)
  await one.index.device.setEnabled(false)
  assert.equal(one.index.snapshot().items.length, 0)
  assert.equal(listed(one.index).length, 0)
  await cleanup(one)
})

test('a restart with unchanged files reads no tags (the cache answers), a changed file is read again', async () => {
  const one = await setup()
  await fill(one.pc)
  await turnOn(one)
  await one.index.close()
  const cached = JSON.parse(await fs.readFile(one.cache, 'utf8'))
  const key = keyOf(path.join(one.pc, 'Rock', 'one.mp3'))
  cached.items[key].title = 'Cached title'
  await fs.writeFile(one.cache, JSON.stringify(cached))
  const again = one.make()
  await again.init()
  again.configure(one.settings)
  assert.equal(listed(again).find((entry) => entry.path.endsWith('one.mp3'))?.title, 'Cached title', 'listed from the cache before any scan')
  await again.settled()
  assert.equal(listed(again).find((entry) => entry.path.endsWith('one.mp3'))?.title, 'Cached title', 'size and time are the same: tags are not read')
  // a changed file (new time) is read again
  const later = new Date(Date.now() + 60_000)
  await fs.utimes(path.join(one.pc, 'Rock', 'one.mp3'), later, later)
  await again.device.scan()
  assert.equal(listed(again).find((entry) => entry.path.endsWith('one.mp3'))?.title, 'One')
  await again.close()
  await fs.rm(one.dir, {recursive: true, force: true})
})

test('ids are stable across restarts and safe for the media protocol', async () => {
  const one = await setup()
  await fill(one.pc)
  await turnOn(one)
  const ids = listed(one.index).map((entry) => entry.id).sort()
  const expected = ['Rock/one.mp3', 'Rock/two.mp3', 'Pop/three.mp3'].map((name) => deviceMusicIdOf('pc', itemIdFor(path.join(one.pc, name)))).sort()
  assert.deepEqual(ids, expected)
  for (const id of ids) assert.match(id, /^[A-Za-z0-9_-]{1,100}$/)
  await one.index.close()
  const again = one.make()
  await again.init()
  again.configure(one.settings)
  await again.settled()
  assert.deepEqual(listed(again).map((entry) => entry.id).sort(), ids)
  await again.close()
  await fs.rm(one.dir, {recursive: true, force: true})
})

test('removed songs stay hidden until they are shown again', async () => {
  const one = await setup()
  await fill(one.pc)
  await turnOn(one)
  const target = listed(one.index).find((entry) => entry.title === 'Two')!
  assert.equal(one.index.device.hide([target.id]), 1)
  assert.equal(listed(one.index).length, 2)
  await one.index.device.scan()
  assert.equal(listed(one.index).length, 2, 'a scan does not bring it back')
  assert.ok(await fs.stat(target.path), 'the file is untouched')
  one.index.device.unhide()
  await one.index.settled()
  assert.equal(listed(one.index).length, 3)
  await cleanup(one)
})

test('files Keepfin owns are not listed twice', async () => {
  const one = await setup({folders: []})
  // the folder on this PC contains the Keepfin folders, and an ordinary folder
  one.index.device.addFolder(one.dir)
  await song(path.join(one.dir, 'pc', 'mine.mp3'), 31, {title: 'Mine'})
  await song(path.join(one.settings.spaces[1].folder, 'owned.mp3'), 31, {title: 'Owned'})
  await song(path.join(one.dir, 'Keepfin', 'Other', 'inroot.mp3'), 31, {title: 'In root'})
  await turnOn(one)
  await one.index.device.scan()
  assert.ok(listed(one.index).some((entry) => entry.title === 'Mine'))
  assert.ok(!listed(one.index).some((entry) => entry.title === 'Owned'), 'a file in a Keepfin space')
  // a file Keepfin already indexed is not listed again either
  const known = await one.index.addFile(path.join(one.dir, 'pc', 'mine.mp3'), {space: 'music', managed: true})
  assert.equal(listed(one.index).filter((entry) => entry.title === 'Mine').length, 0, 'now Keepfin has it')
  assert.ok(known)
  await cleanup(one)
})

test('a folder that is not there keeps its songs, marked missing, and they return with it', async () => {
  const one = await setup()
  await fill(one.pc)
  await turnOn(one)
  const away = `${one.pc}-away`
  await fs.rename(one.pc, away)
  await one.index.device.scan()
  assert.equal(listed(one.index).length, 3, 'the songs are kept')
  assert.ok(listed(one.index).every((entry) => entry.missing))
  await fs.rename(away, one.pc)
  await one.index.device.scan()
  assert.ok(listed(one.index).every((entry) => !entry.missing))
  await cleanup(one)
})

test('new and removed songs show up on their own (the folder watcher)', async () => {
  const one = await setup({watch: true})
  await fill(one.pc)
  await turnOn(one)
  await song(path.join(one.pc, 'Pop', 'fresh.mp3'), 31, {title: 'Fresh'})
  for (let tries = 0; tries < 100 && !listed(one.index).some((entry) => entry.title === 'Fresh'); tries++) await delay(50)
  assert.ok(listed(one.index).some((entry) => entry.title === 'Fresh'))
  await fs.rm(path.join(one.pc, 'Pop', 'fresh.mp3'))
  for (let tries = 0; tries < 100 && listed(one.index).some((entry) => entry.title === 'Fresh'); tries++) await delay(50)
  assert.ok(!listed(one.index).some((entry) => entry.title === 'Fresh'))
  await cleanup(one)
})

test('favorites, plays, resume points and playlist entries stay on this device', async () => {
  const one = await setup()
  await fill(one.pc)
  await turnOn(one)
  const [first, second] = listed(one.index)
  one.index.setFavorite([first.id], true)
  one.index.recordPlayback(first.id, 45, false)
  const playlist = one.index.saveCollection({name: 'Mixed'})
  one.index.device.addToPlaylist(playlist.id, [first.id, second.id])
  assert.deepEqual(one.index.snapshot().collections.find((entry) => entry.id === playlist.id)!.itemIds, [first.id, second.id])
  one.index.device.reorderPlaylist(playlist.id, [second.id, first.id])
  one.index.device.removeFromPlaylist(playlist.id, [])
  await one.index.close()
  const library = JSON.parse(await fs.readFile(path.join(one.dir, 'data', 'library.json'), 'utf8')) as {items: {id: string}[]; collections: Collection[]}
  assert.ok(!library.items.some((entry) => entry.id.startsWith('dm-')), 'no device song in the library file')
  assert.deepEqual(library.collections.find((entry) => entry.id === playlist.id)!.itemIds, [], 'the saved playlist never names them')
  const again = one.make()
  await again.init()
  again.configure(one.settings)
  await again.settled()
  const back = listed(again).find((entry) => entry.id === first.id)!
  assert.equal(back.favorite, true)
  assert.equal(back.plays, 1)
  assert.equal(back.position, 45)
  assert.deepEqual(again.snapshot().collections.find((entry) => entry.id === playlist.id)!.itemIds, [second.id, first.id])
  assert.ok(again.snapshot().items.some((entry) => entry.favorite && entry.id === first.id), 'Favorites (a smart space) finds it')
  await again.close()
  await fs.rm(one.dir, {recursive: true, force: true})
})

test('a playlist saved with device songs keeps them on this device only', async () => {
  const one = await setup()
  await fill(one.pc)
  await turnOn(one)
  const [first] = listed(one.index)
  const real = await one.index.addFile(path.join(one.settings.spaces[1].folder, 'kept.mp3'), {space: 'music', managed: true}).catch(() => undefined)
  assert.equal(real, undefined)
  const saved = one.index.saveCollection({name: 'List', itemIds: [first.id]})
  assert.deepEqual(saved.itemIds, [], 'the synced collection never takes a device id')
  await cleanup(one)
})

test('nothing of Music on this PC reaches the synced store', async () => {
  const saves: PersistedLibrary[] = []
  const seen: string[] = []
  const persistence: LibraryPersistence = {
    load: async () => ({items: [], collections: []}),
    fetchRemote: async () => undefined,
    applyRemote: () => ({items: [], collections: []}),
    onRemoteChange: () => () => {},
    save: async (data) => {
      saves.push(data)
      for (const entry of data.items) seen.push(entry.id, entry.space)
      for (const entry of data.touched?.all() ?? []) seen.push(entry.id, entry.space)
      for (const id of data.touched?.ids ?? []) seen.push(id, data.touched?.entry(id)?.space ?? '')
      for (const space of data.spaces ?? []) seen.push(space.id)
    },
  }
  const one = await setup({persistence})
  await fill(one.pc)
  await turnOn(one)
  const [first] = listed(one.index)
  one.index.setFavorite([first.id], true)
  one.index.recordPlayback(first.id, 40, true)
  // an ordinary change after the device songs, so the store really is saved
  await song(path.join(one.settings.spaces[1].folder, 'kept.mp3'), 31, {title: 'Kept'})
  await one.index.addFile(path.join(one.settings.spaces[1].folder, 'kept.mp3'), {space: 'music', managed: true})
  await one.index.flush()
  assert.ok(saves.length > 0, 'the store was saved')
  assert.ok(!seen.some((value) => value.startsWith('dm-') || value === DEVICE_SPACE_ID), 'no device song or space was handed to the store')
  assert.ok(!one.index.storeSpaces()?.spaces.some((space) => space.id === DEVICE_SPACE_ID))
  assert.ok(!(await fs.readFile(path.join(one.dir, 'data', 'device-music.json'), 'utf8').then((text) => text.length === 0)))
  await cleanup(one)
})

test('operations: the switch, hiding, and no file moves for device songs', async () => {
  const one = await setup()
  await fill(one.pc)
  const deps = {index: one.index, manager: {}, pickFiles: async () => [one.pc]} as unknown as LibraryDeps
  const state = (await handleLibraryRequest({op: 'deviceMusicEnable', enabled: true}, deps)) as {enabled: boolean}
  assert.equal(state.enabled, true)
  await one.index.settled()
  const [first] = listed(one.index)
  await assert.rejects(handleLibraryRequest({op: 'libraryMove', ids: [first.id], space: 'music'}, deps), /stay where they are/)
  await assert.rejects(handleLibraryRequest({op: 'libraryRename', id: first.id, text: 'x'}, deps), /stay where they are/)
  await assert.rejects(handleLibraryRequest({op: 'deviceMusicFolders', action: 'add', text: 'relative/path'}, deps), /exists/)
  assert.deepEqual(await handleLibraryRequest({op: 'libraryHide', ids: [first.id]}, deps), {hidden: 1})
  assert.equal(listed(one.index).length, 2)
  await handleLibraryRequest({op: 'deviceMusicUnhide'}, deps)
  await one.index.settled()
  assert.equal(listed(one.index).length, 3)
  await cleanup(one)
})

test('delete: a device song goes to the system Recycle Bin, never the Keepfin trash', async () => {
  const one = await setup()
  await fill(one.pc)
  await turnOn(one)
  const {deleteItems} = await import('./files.js')
  const trashed: string[] = []
  const target = listed(one.index)[0]
  const result = await deleteItems(one.index, [target.id], {trash: async (file) => void (trashed.push(file), await fs.rm(file))})
  assert.deepEqual(result.deleted, [target.id])
  assert.deepEqual(trashed, [target.path])
  assert.equal(result.undoable, undefined, 'no Keepfin trash, so no Undo')
  assert.equal(listed(one.index).length, 2)
  await cleanup(one)
})

test('scale: 20,000 cached songs list and merge without lag', async () => {
  const count = Number(process.env.KEEPFIN_SCALE ?? 20_000)
  const one = await setup()
  const away = path.join(one.dir, 'gone-drive')
  const items: Record<string, unknown> = {}
  for (let n = 0; n < count; n++) {
    const file = path.join(away, `Album ${n % 400}`, `Song ${n}.mp3`)
    items[keyOf(file)] = {path: file, id: deviceMusicIdOf('pc', itemIdFor(file)), size: 1000 + n, mtimeMs: 1_700_000_000_000 + n, title: `Song ${n}`, artist: `Artist ${n % 150}`, album: `Album ${n % 400}`, duration: 200, hasArt: true}
  }
  await one.index.close()
  await fs.mkdir(path.dirname(one.cache), {recursive: true})
  await fs.writeFile(one.cache, JSON.stringify({version: 1, enabled: true, folders: [away], excluded: [], hidden: [], items, playlists: {}}))
  const again = new LibraryIndex({file: path.join(one.dir, 'data', 'library.json'), artDir: path.join(one.dir, 'art'), ffmpeg: () => undefined, saveDelay: 10, pushDelay: 5, watch: false, analyse: false, device: {folders: () => []}})
  let started = performance.now()
  await again.init()
  const loadMs = performance.now() - started
  again.configure(one.settings)
  assert.equal(listed(again).length, count)
  started = performance.now()
  await again.settled()
  const scanMs = performance.now() - started
  assert.equal(listed(again).length, count, 'an unavailable folder keeps its songs')
  started = performance.now()
  const snapshot = again.snapshot()
  const snapshotMs = performance.now() - started
  assert.equal(snapshot.items.length, count)
  console.log(`device music scale (${count}): load ${Math.round(loadMs)} ms, scan of the unavailable folder ${Math.round(scanMs)} ms, snapshot ${Math.round(snapshotMs)} ms`)
  assert.ok(loadMs < 3000 && scanMs < 3000 && snapshotMs < 1500, 'within the budget')
  await again.close()
  await fs.rm(one.dir, {recursive: true, force: true})
})