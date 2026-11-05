// "Add to playlist" from the download review: a finished download's library item joins the playlist chosen there.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {setTimeout as delay} from 'node:timers/promises'
import type {FormatRequest} from '../shared/contracts.js'
import {DownloadManager} from './manager.js'
import {FakeEngine, finishImmediately, hangUntilAborted} from './fake-engine.js'
import {LibraryIndex} from './library/index.js'

const video: FormatRequest = {type: 'video', codec: 'auto', container: 'mp4'}
const audio: FormatRequest = {type: 'audio', target: 'mp3', quality: 'best'}

async function setup() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-playlist-'))
  const library = new LibraryIndex({
    file: path.join(dir, 'library.json'), artDir: path.join(dir, 'art'), ffmpeg: () => undefined, saveDelay: 10, pushDelay: 5, watch: false, analyse: false,
  })
  await library.init()
  const open = async (engine: FakeEngine) => {
    const manager = new DownloadManager({
      store: path.join(dir, 'queue.json'), defaultOutDir: path.join(dir, 'downloads'),
      libraryFolders: {music: path.join(dir, 'music'), videos: path.join(dir, 'videos')}, engine, library, snapshotDelay: 5, progressInterval: 20,
    })
    await manager.init()
    return manager
  }
  return {dir, library, open, cleanup: async () => { await library.close(); await fs.rm(dir, {recursive: true, force: true}) }}
}
async function waitFor(check: () => boolean, label: string) {
  for (let i = 0; i < 300; i++) {
    if (check()) return
    await delay(10)
  }
  throw new Error(`Timed out waiting for ${label}`)
}
const members = (library: LibraryIndex, id: string) => library.snapshot().collections.find((collection) => collection.id === id)!.itemIds

test('a finished download joins the chosen playlist; a repeat, a cancelled download and a deleted playlist do not break it', async () => {
  const f = await setup()
  const engine = new FakeEngine()
  const manager = await f.open(engine)
  try {
    const playlist = f.library.saveCollection({name: 'Road'})
    const url = 'https://vimeo.com/101'
    await manager.inspect(url)
    await manager.enqueue(url, video, undefined, 'videos', undefined, playlist.id)
    await waitFor(() => manager.snapshot().jobs[0]?.status === 'completed', 'completion')
    await waitFor(() => members(f.library, playlist.id).length === 1, 'joined')
    const item = f.library.snapshot().items[0]
    assert.deepEqual(members(f.library, playlist.id), [item.id])

    // a song already in the library: the repeat adds nothing new, and the recording that is there joins another playlist
    const song = 'https://music.youtube.com/watch?v=abc123DEF45'
    const other = f.library.saveCollection({name: 'Study'})
    await manager.inspect(song)
    await manager.enqueue(song, audio, undefined, undefined, undefined, playlist.id)
    await waitFor(() => manager.snapshot().jobs[1]?.status === 'completed', 'song completion')
    await waitFor(() => members(f.library, playlist.id).length === 2, 'song joined')
    const songId = members(f.library, playlist.id)[1]
    await manager.enqueue(song, audio, undefined, undefined, undefined, other.id)
    await waitFor(() => manager.snapshot().jobs[2]?.status === 'completed', 'repeat completion')
    assert.ok(manager.snapshot().jobs[2].existing, 'the song was already there')
    await waitFor(() => members(f.library, other.id).length === 1, 'existing joined')
    assert.deepEqual(members(f.library, other.id), [songId])
    assert.equal(f.library.snapshot().items.length, 2)
    await manager.enqueue(song, audio, undefined, undefined, undefined, other.id)
    await waitFor(() => manager.snapshot().jobs[3]?.status === 'completed', 'second repeat')
    await f.library.settled()
    assert.deepEqual(members(f.library, other.id), [songId], 'adding twice does not duplicate')

    // a download that is cancelled adds nothing
    engine.script = hangUntilAborted()
    const broken = 'https://vimeo.com/102'
    await manager.inspect(broken)
    await manager.enqueue(broken, video, undefined, 'videos', undefined, playlist.id)
    await waitFor(() => manager.snapshot().jobs[4]?.status === 'downloading', 'running before cancel')
    await manager.cancel(manager.snapshot().jobs[4].id)
    await waitFor(() => manager.snapshot().jobs[4]?.status === 'cancelled', 'cancellation')
    assert.equal(members(f.library, playlist.id).length, 2)

    // a playlist deleted before the download finished is skipped without an error
    engine.script = hangUntilAborted()
    const late = 'https://vimeo.com/103'
    await manager.inspect(late)
    const doomed = f.library.saveCollection({name: 'Gone'})
    await manager.enqueue(late, video, undefined, 'videos', undefined, doomed.id)
    await waitFor(() => manager.snapshot().jobs[5]?.status === 'downloading', 'running')
    f.library.deleteCollection(doomed.id)
    engine.script = finishImmediately
    await manager.cancel(manager.snapshot().jobs[5].id)
    await manager.retry(manager.snapshot().jobs[5].id)
    await waitFor(() => manager.snapshot().jobs[5]?.status === 'completed', 'late completion')
    await f.library.settled()
    assert.equal(f.library.snapshot().collections.some((collection) => collection.id === doomed.id), false)
  } finally {
    await manager.close()
    await f.cleanup()
  }
})

test('the playlist choice survives a restart', async () => {
  const f = await setup()
  const hanging = new FakeEngine()
  hanging.script = hangUntilAborted()
  let manager = await f.open(hanging)
  try {
    const playlist = f.library.saveCollection({name: 'Gym'})
    const url = 'https://vimeo.com/201'
    await manager.inspect(url)
    await manager.enqueue(url, video, undefined, 'videos', undefined, playlist.id)
    await waitFor(() => manager.snapshot().jobs[0]?.status === 'downloading', 'running')
    await manager.close()
    const saved = await fs.readFile(path.join(f.dir, 'queue.json'), 'utf8')
    assert.equal(saved.includes(playlist.id), true, 'queue.json keeps the choice')

    manager = await f.open(new FakeEngine())
    assert.equal(manager.snapshot().jobs[0].playlist, playlist.id)
    await manager.resume(manager.snapshot().jobs[0].id)
    await waitFor(() => manager.snapshot().jobs[0]?.status === 'completed', 'resumed completion')
    await waitFor(() => members(f.library, playlist.id).length === 1, 'joined after restart')
  } finally {
    await manager.close()
    await f.cleanup()
  }
})
