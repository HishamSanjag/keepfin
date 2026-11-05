// A song you already have, downloaded again as a video: the video joins that song (dev-docs/plans/MUSIC-VIDEOS.md, "Owner's rules").
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {setTimeout as delay} from 'node:timers/promises'
import {defaultLibrarySettings} from '../shared/library.js'
import type {FormatRequest} from '../shared/contracts.js'
import {LibraryIndex} from './library/index.js'
import {DownloadManager} from './manager.js'
import {FakeEngine, fakeMedia} from './fake-engine.js'

const audio: FormatRequest = {type: 'audio', target: 'mp3', quality: 'best'}
const video: FormatRequest = {type: 'video', codec: 'auto', container: 'mp4'}
const songUrl = 'https://music.youtube.com/watch?v=songSONG123'
const watchUrl = 'https://www.youtube.com/watch?v=songSONG123'

async function setup() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-songvideo-'))
  const settings = defaultLibrarySettings({downloads: path.join(dir, 'Downloads'), music: path.join(dir, 'Music'), videos: path.join(dir, 'Videos')})
  const engine = new FakeEngine()
  let manager: DownloadManager | undefined
  const library = new LibraryIndex({
    file: path.join(dir, 'data', 'library.json'),
    artDir: path.join(dir, 'art'),
    ffmpeg: () => undefined,
    jobs: () => manager?.completedJobs() ?? [],
    songPending: (id) => manager?.isPending(id) ?? false,
    saveDelay: 10, pushDelay: 5, watchDelay: 30, watch: false, analyse: false,
  })
  await library.init()
  library.configure(settings)
  manager = new DownloadManager({
    store: path.join(dir, 'queue.json'),
    defaultOutDir: path.join(dir, 'downloads'),
    libraryFolders: {music: path.join(dir, 'Music'), videos: path.join(dir, 'Videos')},
    engine,
    library,
    snapshotDelay: 5,
    progressInterval: 20,
  })
  await manager.init()
  engine.script = async (task) => {
    const file = path.join(task.folder, `${task.stem}.${task.format.type === 'audio' ? 'mp3' : 'mp4'}`)
    await fs.mkdir(task.folder, {recursive: true})
    await fs.writeFile(file, task.format.type === 'audio' ? 'song' : 'video')
    return {file, files: []}
  }
  engine.inspectResult = (url) => ({type: 'media', media: fakeMedia(url, {title: 'Song', music: {artist: 'Artist', track: 'Song'}, duration: 200})})
  const m = manager
  return {dir, engine, manager: m, library, cleanup: async () => {
    await m.close()
    await library.close()
    await fs.rm(dir, {recursive: true, force: true})
  }}
}
type Fixture = Awaited<ReturnType<typeof setup>>
const until = async (check: () => boolean, label: string) => {
  for (let i = 0; i < 400; i++) {
    if (check()) return
    await delay(10)
  }
  throw new Error(`Timed out waiting for ${label}`)
}
const exists = (file: string) => fs.access(file).then(() => true, () => false)

/** The song downloaded as audio into [space], finished and in the library. */
async function haveSong(f: Fixture, space?: string) {
  await f.manager.inspect(songUrl)
  await f.manager.enqueue(songUrl, audio, undefined, space)
  await until(() => f.manager.snapshot().jobs[0]?.status === 'completed', 'the song')
  await until(() => f.library.snapshot().items.length === 1, 'the song in the library')
  return f.library.snapshot().items[0]
}

test('the song you have, downloaded as video in its own space, becomes its music video', async () => {
  const f = await setup()
  try {
    const song = await haveSong(f)
    await f.manager.inspect(songUrl)
    await f.manager.enqueue(songUrl, video)
    await until(() => f.manager.snapshot().jobs[1]?.status === 'completed', 'the video job')
    await until(() => f.library.snapshot().items[0]?.musicVideo === true, 'the video attached to the song')
    assert.equal(f.engine.downloads.length, 2, 'the video was really downloaded')
    assert.equal(f.library.snapshot().items.length, 1, 'no second item')
    assert.equal(f.library.snapshot().items[0].videoSize, 5, "the video's own size is kept for its row")
    assert.equal(await exists(f.library.lookup(song.id)!.path.replace(/\.mp3$/, '.video.mp4')), true)
  } finally {
    await f.cleanup()
  }
})

test('the song is in another space than the video would go to: the video still joins it', async () => {
  const f = await setup()
  try {
    const song = await haveSong(f, 'music')
    await f.manager.inspect(watchUrl)
    await f.manager.enqueue(watchUrl, video, undefined, 'videos')
    await until(() => f.library.snapshot().items[0]?.musicVideo === true, 'the video attached to the song')
    assert.equal(f.library.snapshot().items.length, 1, 'nothing landed in Videos')
    assert.equal(f.library.snapshot().items[0].id, song.id)
    assert.equal(f.manager.snapshot().jobs[1].space, 'music', 'the video job saves beside the song')
  } finally {
    await f.cleanup()
  }
})

test('the song already has its video: say so and download nothing', async () => {
  const f = await setup()
  try {
    await haveSong(f)
    await f.manager.inspect(songUrl)
    await f.manager.enqueue(songUrl, video)
    await until(() => f.library.snapshot().items[0]?.musicVideo === true, 'the first video')
    await f.manager.inspect(songUrl)
    await assert.rejects(f.manager.enqueue(songUrl, video), /already has its music video/)
    assert.equal(f.engine.downloads.length, 2, 'no third download')
  } finally {
    await f.cleanup()
  }
})

test('a video that matches no song stays an ordinary video download', async () => {
  const f = await setup()
  try {
    f.engine.inspectResult = (url) => ({type: 'media', media: fakeMedia(url, {title: 'Holiday clip', duration: 30})})
    const clip = 'https://www.youtube.com/watch?v=clipCLIP123'
    await f.manager.inspect(clip)
    await f.manager.enqueue(clip, video)
    await until(() => f.library.snapshot().items.length === 1, 'the clip in the library')
    assert.equal(f.library.snapshot().items[0].kind, 'video')
    assert.equal(f.manager.snapshot().jobs[0].attachVideo, undefined)
  } finally {
    await f.cleanup()
  }
})

test('Capture asks which song a link is, for the line on the Video tab', async () => {
  const f = await setup()
  try {
    await f.manager.inspect(songUrl)
    assert.equal(f.manager.songOfInspected(songUrl), null, 'no song yet')
    const song = await haveSong(f)
    assert.deepEqual(f.manager.songOfInspected(songUrl), {itemId: song.id, space: 'music', hasVideo: false})
    await f.manager.enqueue(songUrl, video)
    await until(() => f.library.snapshot().items[0]?.musicVideo === true, 'the video attached')
    assert.equal(f.manager.songOfInspected(songUrl)?.hasVideo, true)
    f.engine.inspectResult = (url) => ({type: 'media', media: fakeMedia(url, {title: 'Holiday clip', duration: 30})})
    await f.manager.inspect('https://www.youtube.com/watch?v=clipCLIP123')
    assert.equal(f.manager.songOfInspected('https://www.youtube.com/watch?v=clipCLIP123'), null, 'a clip is no song')
  } finally {
    await f.cleanup()
  }
})

test('a YouTube link kept as audio can also keep the link itself as its video; no search, and only its own id', async () => {
  const f = await setup()
  try {
    f.engine.inspectResult = (url) => ({type: 'media', media: fakeMedia(url, {title: 'Song', webpageUrl: url, music: {artist: 'Artist', track: 'Song'}, duration: 200})})
    await f.manager.inspect(watchUrl)
    await assert.rejects(f.manager.enqueue(watchUrl, audio, undefined, 'music', 'someOtherId'), /goes with a song downloaded as audio/)
    await f.manager.enqueue(watchUrl, audio, undefined, 'music', 'songSONG123')
    const [song, link] = f.manager.snapshot().jobs
    assert.equal(link.url, watchUrl, 'the link itself, not a music.youtube.com address')
    assert.deepEqual(link.attachVideo, {jobId: song.id})
    await until(() => f.library.snapshot().items[0]?.musicVideo === true, 'song and video joined')
    assert.equal(f.library.snapshot().items.length, 1)
  } finally {
    await f.cleanup()
  }
})

test('Find lyrics: lyrics are stored through the engine and the library reads them; none and busy are worded apart', async () => {
  const f = await setup()
  try {
    const song = await haveSong(f)
    assert.equal(song.hasLyrics, undefined)
    let answer: 'found' | 'none' | 'busy' = 'none'
    f.engine.findLyrics = async (file) => {
      if (answer === 'found') await fs.writeFile(file.replace(/\.mp3$/, '.lrc'), '[00:01.00] a\n')
      return answer
    }
    assert.deepEqual(await f.manager.findLyrics(song.id), {outcome: 'none', message: 'No lyrics found'})
    answer = 'busy'
    assert.deepEqual(await f.manager.findLyrics(song.id), {outcome: 'busy', message: 'Could not reach the lyrics service. Try again later.'})
    answer = 'found'
    assert.equal((await f.manager.findLyrics(song.id)).outcome, 'found')
    await until(() => f.library.snapshot().items[0]?.hasLyrics === true, 'the library reads the lyrics')
    assert.equal((await f.manager.findLyrics(song.id)).outcome, 'found', 'a song that has lyrics now asks nothing more')
    await assert.rejects(f.manager.findLyrics('nope'), /Choose a song/)
  } finally {
    await f.cleanup()
  }
})
