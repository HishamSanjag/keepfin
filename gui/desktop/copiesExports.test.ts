// Derived exports of a copy on a folder (copies spec 8), through the real host and worker: playlist files with relative paths, a flat folder
// and MP3 versions of audio that is not MP3 (made once, cached by content id), none of them imported back, all removed when turned off.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import {spawnSync} from 'node:child_process'
import {createHash, randomUUID} from 'node:crypto'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import ffmpegPath from 'ffmpeg-static'
import type {CopiesState, CopyDraft, CopyPreview} from '../shared/copies.js'
import {CopiesHost} from './copiesHost.js'
import {createKeepfinFolder} from './store/keepfinFolder.js'
import {BUILTIN_SPACE_IDS} from './store/mapping.js'
import {StoreLibrary} from './store/persistence.js'

const sha = (text: string | Buffer) => `sha256:${createHash('sha256').update(text).digest('hex')}`
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
  const addPlaylist = async (name: string, files: string[]) => {
    const playlist = randomUUID()
    await session.store.apply([{kind: 'playlist.add', body: {playlist, fields: {name}}}])
    for (const [at, file] of files.entries()) await session.store.apply([{kind: 'entry.insert', body: {playlist, entry: randomUUID(), item: items.get(file)!, key: `a${at}`}}])
    return playlist
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
    ffmpeg: () => ffmpegPath as string,
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
    items,
    addPlaylist,
    async cleanup() {
      await host.close()
      await library.close()
      await fs.rm(dir, {recursive: true, force: true})
    },
  }
}


const draft = (target: string): CopyDraft => ({path: target, name: 'Player', mode: 'backup', holds: {all: true, spaces: [], playlists: [], favourites: false}, schedule: {everyHours: null, auto: false}})
async function settle(host: CopiesHost, placeId: string, until: (s: CopiesState) => boolean, ms = 40_000): Promise<CopiesState> {
  const end = Date.now() + ms
  let state = await host.state()
  while (Date.now() < end) {
    state = await host.state()
    const place = state.places.find((p) => p.id === placeId)
    if (place && place.status !== 'syncing' && until(state)) return state
    await new Promise((r) => setTimeout(r, 150))
  }
  throw new Error('The copy did not settle')
}
const exists = (p: string) => fs.stat(p).then(() => true, () => false)

test('exports: playlist files, a flat folder and MP3 versions are made, never imported back, converted once, and removed when turned off', async () => {
  const w = await world()
  try {
    // a real FLAC and a real MP3 as library items
    const flac = path.join(w.folder.root, 'Music', 'song.flac')
    const made = spawnSync(ffmpegPath as string, ['-y', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=1', flac], {windowsHide: true})
    assert.equal(made.status, 0, String(made.stderr))
    const flacBytes = await fs.readFile(flac)
    const flacItem = randomUUID()
    await w.session.store.apply([
      {kind: 'item.add', body: {item: flacItem, fields: {media: 'audio', space: BUILTIN_SPACE_IDS.music, title: 'Song'}}},
      {kind: 'item.place', body: {item: flacItem, place: w.folder.replicaId, path: 'Music/song.flac'}},
      {kind: 'item.revise', body: {item: flacItem, content: sha(flacBytes), size: flacBytes.length, parents: []}},
    ])
    w.items.set('song.flac', flacItem)
    await w.addItem('plain.mp3', 'PLAIN-MP3')
    await w.host.refreshSnapshot()
    const target = path.join(w.dir, 'Player')
    await fs.mkdir(target)
    const preview = (await w.host.handle('copiesPreview', {draft: draft(target)})) as CopyPreview
    await w.host.handle('copiesAdd', {placeId: preview.placeId})
    await settle(w.host, preview.placeId, (s) => s.places[0]!.kept === 2 && s.places[0]!.waiting === 0)
    assert.equal(await exists(path.join(target, 'Playlists')), false, 'nothing is exported until it is turned on')

    const edited = (await w.host.handle('copiesEdit', {placeId: preview.placeId, exports: {playlistFiles: true, flat: true, mp3: true}})) as CopiesState
    assert.equal(edited.places[0]!.exports?.mp3, true)
    await w.host.syncPlace(preview.placeId, {})
    await settle(w.host, preview.placeId, (s) => s.places[0]!.waiting === 0)
    const mp3 = path.join(target, 'Keepfin Exports', 'MP3', 'Music', 'song.mp3')
    assert.equal(await exists(mp3), true, 'the FLAC has an MP3 version')
    assert.equal(await exists(path.join(target, 'Keepfin Exports', 'MP3', 'Music', 'plain.mp3')), false, 'an MP3 is not converted')
    assert.equal(await exists(path.join(target, 'Keepfin Exports', 'Flat', 'song.flac')), true)
    assert.equal(await exists(path.join(target, 'Keepfin Exports', 'Flat', 'plain.mp3')), true)
    const first = (await fs.stat(mp3)).mtimeMs

    // a playlist appears in the library: its file is written with paths relative to the Playlists folder, in playlist order
    await w.addPlaylist('Road trip', ['plain.mp3', 'song.flac'])
    await w.host.refreshSnapshot()
    await w.host.syncPlace(preview.placeId, {})
    const state = await settle(w.host, preview.placeId, (s) => s.places[0]!.waiting === 0)
    const m3u = await fs.readFile(path.join(target, 'Playlists', 'Road trip.m3u8'), 'utf8')
    assert.deepEqual(m3u.split('\n').filter((l) => l.startsWith('../')), ['../Music/plain.mp3', '../Music/song.flac'])
    assert.equal((await fs.stat(mp3)).mtimeMs, first, 'the MP3 was made once and kept')

    // nothing derived came back into the library
    assert.deepEqual((await fs.readdir(path.join(w.folder.root, 'Music'))).sort(), ['plain.mp3', 'song.flac'])
    assert.equal(state.places[0]!.review, 0)
    assert.equal(await exists(path.join(w.folder.root, 'Playlists')), false)

    // turned off: everything derived is removed, the library's files stay
    await w.host.handle('copiesEdit', {placeId: preview.placeId, exports: {playlistFiles: false, flat: false, mp3: false}})
    await w.host.syncPlace(preview.placeId, {})
    await settle(w.host, preview.placeId, (s) => s.places[0]!.waiting === 0)
    assert.equal(await exists(mp3), false)
    assert.equal(await exists(path.join(target, 'Playlists')), false)
    assert.equal(await exists(path.join(target, 'Keepfin Exports')), false)
    assert.equal(await exists(path.join(target, 'Music', 'song.flac')), true)
  } finally {
    await w.cleanup()
  }
})