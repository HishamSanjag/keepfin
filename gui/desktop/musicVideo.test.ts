// A song's music video (dev-docs/plans/MUSIC-VIDEOS.md Part B): the file beside the song, kept with it, never an item of its own.
import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {setTimeout as delay} from 'node:timers/promises'
import {defaultLibrarySettings} from '../shared/library.js'
import {makeOp, type SyncJson} from '../shared/core/sync.js'
import {LibraryIndex} from './library/index.js'
import {deleteItems, importFiles, moveItems, removeMusicVideo, renameItem} from './library/files.js'
import {isMusicVideoFile, musicVideoNames, sidecarsFrom} from './library/tags.js'
import {findMusicVideo} from './engine/music.js'
import {DownloadManager} from './manager.js'
import {FakeEngine, fakeMedia, type DownloadScript} from './fake-engine.js'
import type {FormatRequest, Job} from '../shared/contracts.js'
import type {MusicVideoFound} from '../shared/library.js'
import {migrateJob} from './migrate.js'
import {parseMediaUrl, respondToMedia} from './library/protocol.js'
import {desiredEntries, optionsKey, placeConfigFrom, placeMusicVideos, type Holds} from './sqlstate/desired.js'
import {SqlState} from './sqlstate/sqlstate.js'
import {prng, uuid} from './sqlstate/history.js'

async function setup() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-musicvideo-'))
  const settings = defaultLibrarySettings({downloads: path.join(dir, 'Downloads'), music: path.join(dir, 'Music'), videos: path.join(dir, 'Videos')})
  for (const space of settings.spaces.filter((item) => item.type === 'folder')) await fs.mkdir(space.folder, {recursive: true})
  const index = new LibraryIndex({file: path.join(dir, 'data', 'library.json'), artDir: path.join(dir, 'art'), ffmpeg: () => undefined, saveDelay: 10, pushDelay: 5, watchDelay: 30, watch: false, analyse: false})
  await index.init()
  index.configure(settings)
  const relocated: [string, string][] = []
  index.hooks = {relocated: (from, to) => relocated.push([from, to]), forgotten() {}}
  const trashed: string[] = []
  const deps = {
    trash: async (target: string) => {
      trashed.push(target)
      await fs.rm(target, {force: true})
    },
  }
  const music = settings.spaces.find((space) => space.id === 'music')!.folder
  return {dir, settings, index, deps, trashed, relocated, music, cleanup: async () => {
    await index.close()
    await fs.rm(dir, {recursive: true, force: true})
  }}
}
const write = async (file: string, text = 'x') => {
  await fs.mkdir(path.dirname(file), {recursive: true})
  await fs.writeFile(file, text)
  return file
}
const exists = (file: string) => fs.access(file).then(() => true, () => false)

test('sidecarsFrom finds the music video of a song and no one else\'s', () => {
  const found = sidecarsFrom(path.join('M', 'Song.mp3'), ['Song.mp3', 'Song.lrc', 'Song.video.mp4', 'Other.video.mp4', 'Song.video.txt', 'Song.en.srt'])
  assert.equal(found.video, path.join('M', 'Song.video.mp4'))
  assert.equal(found.lrc, path.join('M', 'Song.lrc'))
  assert.deepEqual(found.subtitles.map((s) => s.label), ['en'], 'the video is not taken for a subtitle language')
  assert.equal(sidecarsFrom(path.join('M', 'Song.mp3'), ['Song.mp3', 'Song.video.mkv']).video, undefined, 'only the supported containers')
  assert.equal(sidecarsFrom(path.join('M', 'song.mp3'), ['Song.VIDEO.MP4']).video, path.join('M', 'Song.VIDEO.MP4'), 'case does not matter')
})

test('a file named like a music video is not an item when its song is beside it', async () => {
  assert.deepEqual([...musicVideoNames(['Song.mp3', 'Song.video.mp4', 'Alone.video.mp4', 'Pic.jpg', 'Pic.video.mp4'])], ['song.video.mp4'])
  const f = await setup()
  try {
    const song = await write(path.join(f.music, 'Song.mp3'))
    const video = await write(path.join(f.music, 'Song.video.mp4'))
    const alone = await write(path.join(f.music, 'Alone.video.mp4'))
    assert.equal(await isMusicVideoFile(video), true)
    assert.equal(await isMusicVideoFile(alone), false)
    assert.equal(await f.index.addFile(video, {space: 'music', managed: true}), undefined)
    assert.ok(await f.index.addFile(alone, {space: 'music', managed: true}), 'with no song beside it, it is an ordinary file')
    // a folder import: the song and the lone file come in, the song's video does not become a second item
    const src = path.join(f.dir, 'incoming')
    await write(path.join(src, 'Track.mp3'), 'track audio')
    await write(path.join(src, 'Track.video.mp4'), 'track video')
    await write(path.join(src, 'Solo.video.mp4'), 'solo video')
    const result = await importFiles(f.index, {paths: [src], space: 'music', mode: 'copy'}, f.deps)
    assert.equal(result.added.length, 2)
    await f.index.settled()
    const track = f.index.snapshot().items.filter((item) => /Track/.test(item.path))
    assert.equal(track.length, 1)
    assert.equal(await fs.readFile(path.join(f.music, 'incoming', 'Track.video.mp4'), 'utf8'), 'track video', 'the song brings its video along')
    assert.equal(track[0].musicVideo, true)
    void song
  } finally {
    await f.cleanup()
  }
})

test('attachMusicVideo keeps the file beside the song, and never loses or overwrites one', async () => {
  const f = await setup()
  try {
    const entry = (await f.index.addFile(await write(path.join(f.music, 'Song.mp3')), {space: 'music', managed: true}))!
    await f.index.settled()
    assert.equal(f.index.snapshot().items.find((item) => item.id === entry.id)?.musicVideo, undefined)
    const download = await write(path.join(f.dir, 'Downloads', 'Clip.mp4'), 'video-1')
    assert.equal(await f.index.attachMusicVideo(entry.id, download), true)
    const kept = path.join(f.music, 'Song.video.mp4')
    assert.equal(await fs.readFile(kept, 'utf8'), 'video-1')
    assert.equal(await exists(download), false, 'the download moved')
    assert.deepEqual(f.relocated, [[download, kept]], 'job history follows the file')
    assert.equal(f.index.snapshot().items.find((item) => item.id === entry.id)?.musicVideo, true)
    assert.equal(f.index.videoFile(entry.id), kept)
    assert.equal(f.index.snapshot().items.length, 1, 'still one item')
    // a second video, another container, an unsupported one or a missing song change nothing
    const second = await write(path.join(f.dir, 'Downloads', 'Two.webm'), 'video-2')
    assert.equal(await f.index.attachMusicVideo(entry.id, second), false)
    assert.equal(await fs.readFile(kept, 'utf8'), 'video-1')
    assert.equal(await exists(second), true, 'the refused file stays where it was')
    const mkv = await write(path.join(f.dir, 'Downloads', 'Three.mkv'))
    assert.equal(await f.index.attachMusicVideo(entry.id, mkv), false)
    assert.equal(await f.index.attachMusicVideo('nope', second), false)
  } finally {
    await f.cleanup()
  }
})

test('the video travels with its song: move, rename, copy, delete and remove', async () => {
  const f = await setup()
  try {
    const entry = (await f.index.addFile(await write(path.join(f.music, 'Song.mp3')), {space: 'music', managed: true}))!
    await f.index.settled()
    await f.index.attachMusicVideo(entry.id, await write(path.join(f.dir, 'Downloads', 'Clip.mp4'), 'v'))
    const renamed = await renameItem(f.index, entry.id, 'Better')
    await f.index.settled()
    assert.equal(await exists(path.join(f.music, 'Better.video.mp4')), true)
    assert.equal(await exists(path.join(f.music, 'Song.video.mp4')), false)
    const moved = await moveItems(f.index, {ids: [renamed.id], space: 'downloads'}, f.deps)
    assert.equal(moved.failed.length, 0)
    await f.index.settled()
    const downloads = f.settings.spaces.find((space) => space.id === 'downloads')!.folder
    assert.equal(await exists(path.join(downloads, 'Better.video.mp4')), true, 'moved with the song')
    assert.equal(await exists(path.join(f.music, 'Better.video.mp4')), false)
    const now = f.index.snapshot().items[0]
    assert.equal(now.musicVideo, true)
    // Remove music video: only the video goes
    await removeMusicVideo(f.index, now.id, f.deps)
    assert.deepEqual(f.trashed, [path.join(downloads, 'Better.video.mp4')])
    assert.equal(await exists(path.join(downloads, 'Better.mp3')), true)
    assert.equal(f.index.snapshot().items[0].musicVideo, undefined)
    await assert.rejects(removeMusicVideo(f.index, now.id, f.deps), /no music video/)
    // delete takes the video with the song
    await f.index.attachMusicVideo(now.id, await write(path.join(f.dir, 'Downloads2', 'Clip.mp4'), 'v'))
    f.trashed.length = 0
    await deleteItems(f.index, [now.id], f.deps)
    assert.deepEqual(f.trashed.map((file) => path.basename(file)).sort(), ['Better.mp3', 'Better.video.mp4'])
  } finally {
    await f.cleanup()
  }
})

test('a move or rename never puts the song\'s video over another file', async () => {
  const f = await setup()
  try {
    const entry = (await f.index.addFile(await write(path.join(f.music, 'Song.mp3'), 'song'), {space: 'music', managed: true}))!
    await f.index.settled()
    await f.index.attachMusicVideo(entry.id, await write(path.join(f.dir, 'Downloads', 'Clip.mp4'), 'ours'))
    const downloads = f.settings.spaces.find((space) => space.id === 'downloads')!.folder
    // someone else's video already has the name the song's video would take
    const stranger = await write(path.join(downloads, 'Song.video.mp4'), 'theirs')
    const moved = await moveItems(f.index, {ids: [entry.id], space: 'downloads'}, f.deps)
    assert.equal(moved.failed.length, 0)
    assert.equal(await fs.readFile(stranger, 'utf8'), 'theirs')
    assert.equal(await fs.readFile(path.join(downloads, 'Song (2).video.mp4'), 'utf8'), 'ours')
    assert.equal(await exists(path.join(downloads, 'Song (2).mp3')), true)
    await write(path.join(downloads, 'Taken.video.mp4'), 'other')
    await assert.rejects(renameItem(f.index, entry.id, 'Taken'), /already exists/)
    assert.equal(await exists(path.join(downloads, 'Song (2).mp3')), true, 'nothing was renamed')
  } finally {
    await f.cleanup()
  }
})

test('a video listed on its own becomes the song\'s once the song is beside it, and a removed one is noticed', async () => {
  const f = await setup()
  try {
    const video = await write(path.join(f.music, 'Late.video.mp4'), 'v')
    assert.ok(await f.index.addFile(video, {space: 'music', managed: true}), 'alone, it is an ordinary file')
    const song = (await f.index.addFile(await write(path.join(f.music, 'Late.mp3')), {space: 'music', managed: true}))!
    await f.index.settled()
    assert.deepEqual(f.index.snapshot().items.map((item) => item.id), [song.id], 'one item: the song')
    assert.equal(f.index.snapshot().items[0].musicVideo, true)
    assert.equal(await exists(video), true, 'the file stays')
    // a video that appears beside a song already in the library
    const other = (await f.index.addFile(await write(path.join(f.music, 'Other.mp3')), {space: 'music', managed: true}))!
    await f.index.settled()
    assert.equal(await f.index.addFile(await write(path.join(f.music, 'Other.video.webm'), 'w'), {space: 'music', managed: true}), undefined)

    await f.index.settled()
    assert.equal(f.index.snapshot().items.find((item) => item.id === other.id)?.musicVideo, true)
  } finally {
    await f.cleanup()
  }
})

test('the music video is served by the song\'s id with Range support, and by nothing else', async () => {
  assert.deepEqual(parseMediaUrl('keepfin-media://video/abc123'), {kind: 'video', id: 'abc123'})
  assert.equal(parseMediaUrl('keepfin-media://video/a/b'), undefined)
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-mv-protocol-'))
  try {
    const video = await write(path.join(dir, 'Song.video.mp4'), '0123456789')
    const resolver = {item: () => undefined, art: () => undefined, sub: () => undefined, job: () => undefined, prepared: () => undefined, video: (id: string) => (id === 'ok' ? video : undefined)}
    const get = (url: string, range?: string) => respondToMedia({url, method: 'GET', headers: {get: (name) => (name.toLowerCase() === 'range' ? (range ?? null) : null)}}, resolver)
    const part = await get('keepfin-media://video/ok', 'bytes=2-4')
    assert.equal(part.status, 206)
    assert.equal(part.headers.get('content-range'), 'bytes 2-4/10')
    assert.equal(part.headers.get('content-type'), 'video/mp4')
    assert.equal(await part.text(), '234')
    assert.equal((await get('keepfin-media://video/unknown')).status, 404)
  } finally {
    await fs.rm(dir, {recursive: true, force: true})
  }
})

test('a copy carries the music video only when it holds everything', () => {
  const rnd = prng(5)
  const actor = uuid(rnd)
  const main = uuid(rnd)
  let seq = 0
  const op = (kind: string, body: Record<string, SyncJson>) => makeOp(actor, ++seq, `${String(1_700_000_000_000 + seq).padStart(13, '0')}.000000.${actor}`, kind, body)
  const db = new SqlState(':memory:')
  const item = uuid(rnd)
  db.admit([
    op('item.add', {item, fields: {media: 'audio', space: 'music', title: 'Song'}}),
    op('item.place', {item, place: main, path: 'Music/Song.mp3'}),
    op('item.revise', {item, content: 'sha256:' + 'a'.repeat(64), size: 10, parents: []}),
    op('item.set', {item, field: 'companion:.lrc', value: {content: 'sha256:' + 'b'.repeat(64), size: 5}}),
    op('item.set', {item, field: 'companion:.video.mp4', value: {content: 'sha256:' + 'c'.repeat(64), size: 99}}),
  ])
  const paths = (holds: Holds) => [...desiredEntries(db.db, main, holds, true)].map((e) => e.path).sort()
  const everything: Holds = {all: true, spaces: [], playlists: [], favourites: false, smart: []}
  const part: Holds = {all: false, spaces: ['music'], playlists: [], favourites: false, smart: []}
  assert.deepEqual(paths(everything), ['Music/Song.lrc', 'Music/Song.mp3', 'Music/Song.video.mp4'])
  assert.deepEqual(paths(part), ['Music/Song.lrc', 'Music/Song.mp3'], 'a part copy leaves music videos out')
  db.close()
  void delay
})

test('finding a music video sends the core\'s videos search and says none for an empty answer', async () => {
  const sent: {url: string; body: string}[] = []
  const fetcher = (async (url: string, init: {body: string}) => {
    sent.push({url, body: init.body})
    return new Response('{}', {status: 200})
  }) as unknown as typeof fetch
  assert.equal(await findMusicVideo(['Daft Punk'], 'Around the World', fetcher), undefined)
  assert.equal(sent.length, 1)
  assert.match(sent[0].url, /music\.youtube\.com/)
  assert.match(sent[0].body, /Daft Punk/)
  const failing = (async () => new Response('', {status: 500})) as unknown as typeof fetch
  await assert.rejects(findMusicVideo(['A'], 'B', failing), /failed/)
})
test('musicVideo survives a restart', async () => {
  const f = await setup()
  try {
    const entry = (await f.index.addFile(await write(path.join(f.music, 'Song.mp3')), {space: 'music', managed: true}))!
    await f.index.settled()
    await f.index.attachMusicVideo(entry.id, await write(path.join(f.dir, 'Downloads', 'Clip.mp4'), 'v'))
    await f.index.close()
    const again = new LibraryIndex({file: path.join(f.dir, 'data', 'library.json'), artDir: path.join(f.dir, 'art'), ffmpeg: () => undefined, saveDelay: 10, pushDelay: 5, watchDelay: 30, watch: false, analyse: false})
    await again.init()
    again.configure(f.settings)
    assert.equal(again.snapshot().items.find((item) => item.id === entry.id)?.musicVideo, true)
    assert.equal(again.videoFile(entry.id), path.join(f.music, 'Song.video.mp4'))
    await again.close()
  } finally {
    await fs.rm(f.dir, {recursive: true, force: true})
  }
})
test('Get the music video queues an mp4 job that keeps the song, skips the existing check, and survives a restart of the queue', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-mv-manager-'))
  const engine = new FakeEngine()
  const entry = {id: 'song1', kind: 'audio', space: 'music', title: 'Song'}
  const manager = new DownloadManager({
    store: path.join(dir, 'queue.json'),
    defaultOutDir: path.join(dir, 'downloads'),
    engine,
    snapshotDelay: 5,
    retryDelays: [10],
    library: {lookup: (id: string) => (id === 'song1' ? entry : undefined), adoptJob: async () => {}, storeSpaces: () => undefined, configure() {}, forgetPaths() {}, hooks: {}} as never,
  })
  try {
    await manager.init()
    await assert.rejects(manager.getMusicVideo('nope', 'abcdefghijk'), /Choose a song/)
    await assert.rejects(manager.getMusicVideo('song1', 'not an id'), /not a video/)
    await manager.getMusicVideo('song1', 'abcdefghijk')
    assert.deepEqual(engine.inspectCalls, ['https://music.youtube.com/watch?v=abcdefghijk'])
    const job = manager.snapshot().jobs.at(-1)!
    assert.equal(job.url, 'https://music.youtube.com/watch?v=abcdefghijk')
    assert.deepEqual(job.attachVideo, {itemId: 'song1'})
    assert.equal(job.force, true)
    assert.deepEqual(job.format, {type: 'video', maxHeight: 1080, codec: 'h264', container: 'mp4'})
    assert.equal(job.options.subtitles, undefined)
    assert.deepEqual(migrateJob(JSON.parse(JSON.stringify(job)))?.attachVideo, {itemId: 'song1'})
    assert.equal(migrateJob({...job, attachVideo: {itemId: 5}})?.attachVideo, undefined)
    if (job.status !== 'completed' && job.status !== 'failed') await assert.rejects(manager.getMusicVideo('song1', 'abcdefghijk'), /already downloading/)
  } finally {
    await manager.close()
    await fs.rm(dir, {recursive: true, force: true})
  }
})
test('the Music videos option: stored only once chosen, by the holds until then, in the options key, and in the desired set', () => {
  const base = {mode: 'backup', holds: {all: true}}
  const unset = placeConfigFrom('p', base)!
  assert.equal(unset.options.musicVideos, undefined, 'not stored until the person chooses')
  assert.equal(placeMusicVideos(unset), true, 'a copy of everything carries the videos')
  assert.equal(placeMusicVideos(placeConfigFrom('p', {...base, holds: {spaces: ['music']}})!), false, 'a part copy leaves them out')
  const off = placeConfigFrom('p', {...base, options: {musicVideos: false}})!
  const on = placeConfigFrom('p', {mode: 'backup', holds: {spaces: ['music']}, options: {musicVideos: true}})!
  assert.equal(placeMusicVideos(off), false)
  assert.equal(placeMusicVideos(on), true)
  // a copy that already left out the files beside each song gets no videos until its person turns them on
  assert.equal(placeMusicVideos(placeConfigFrom('p', {...base, options: {companions: false}})!), false)
  assert.equal(placeMusicVideos(placeConfigFrom('p', {...base, options: {companions: false, musicVideos: true}})!), true)
  assert.equal(optionsKey(unset.options), JSON.stringify([false, false, false, 90, 10, true]), 'a copy that never chose keeps its key')
  assert.notEqual(optionsKey(off.options), optionsKey(unset.options), 'a change replans')
  assert.notEqual(optionsKey(on.options), optionsKey(off.options))

  const rnd = prng(6)
  const actor = uuid(rnd)
  const main = uuid(rnd)
  let seq = 0
  const op = (kind: string, body: Record<string, SyncJson>) => makeOp(actor, ++seq, `${String(1_700_000_000_000 + seq).padStart(13, '0')}.000000.${actor}`, kind, body)
  const db = new SqlState(':memory:')
  const item = uuid(rnd)
  db.admit([
    op('item.add', {item, fields: {media: 'audio', space: 'music', title: 'Song'}}),
    op('item.place', {item, place: main, path: 'Music/Song.mp3'}),
    op('item.revise', {item, content: 'sha256:' + 'a'.repeat(64), size: 10, parents: []}),
    op('item.set', {item, field: 'companion:.lrc', value: {content: 'sha256:' + 'b'.repeat(64), size: 5}}),
    op('item.set', {item, field: 'companion:.video.mp4', value: {content: 'sha256:' + 'c'.repeat(64), size: 99}}),
  ])
  const part: Holds = {all: false, spaces: ['music'], playlists: [], favourites: false, smart: []}
  const everything: Holds = {all: true, spaces: [], playlists: [], favourites: false, smart: []}
  const paths = (holds: Holds, companions: boolean, videos?: boolean) => [...desiredEntries(db.db, main, holds, companions, videos)].map((e) => e.path).sort()
  assert.deepEqual(paths(part, true), ['Music/Song.lrc', 'Music/Song.mp3'], 'by the holds, as before')
  assert.deepEqual(paths(part, true, true), ['Music/Song.lrc', 'Music/Song.mp3', 'Music/Song.video.mp4'], 'a part copy that turned them on')
  assert.deepEqual(paths(everything, true, false), ['Music/Song.lrc', 'Music/Song.mp3'], 'a copy of everything that turned them off')
  assert.deepEqual(paths(part, false, true), ['Music/Song.mp3', 'Music/Song.video.mp4'], 'videos apart from lyrics and info files')
  assert.deepEqual(paths(everything, true, true), ['Music/Song.lrc', 'Music/Song.mp3', 'Music/Song.video.mp4'])
  assert.deepEqual(paths(everything, false, false), ['Music/Song.mp3'])
  db.close()
})

// ---- Capture: the song and its video are two downloads, linked by the song's job ----

async function linked(script: (gates: {song: Promise<void>; video: Promise<void>}) => DownloadScript, findMusicVideo?: (artist: string, title: string) => Promise<MusicVideoFound>) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-mv-linked-'))
  const engine = new FakeEngine()
  const settings = defaultLibrarySettings({downloads: path.join(dir, 'Downloads'), music: path.join(dir, 'Music'), videos: path.join(dir, 'Videos')})
  const music = settings.spaces.find((space) => space.id === 'music')!.folder
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
  manager = new DownloadManager({store: path.join(dir, 'queue.json'), defaultOutDir: path.join(dir, 'downloads'), libraryFolders: {music, videos: path.join(dir, 'Videos')}, engine, library, findMusicVideo, snapshotDelay: 5, progressInterval: 20})
  await manager.init()
  const gate = () => {
    let open!: () => void
    const promise = new Promise<void>((resolve) => (open = resolve))
    return {promise, open}
  }
  const song = gate()
  const video = gate()
  engine.script = script({song: song.promise, video: video.promise})
  const songUrl = 'https://music.youtube.com/watch?v=songSONG123'
  engine.inspectResult = (url) => ({type: 'media', media: url === songUrl ? fakeMedia(url, {title: 'Song', music: {artist: 'Artist', track: 'Song'}, duration: 200}) : fakeMedia(url, {title: 'Song (Official Video)', duration: 210})})
  await manager.inspect(songUrl)
  const m = manager
  return {dir, music, engine, manager: m, library, songUrl, openSong: song.open, openVideo: video.open, cleanup: async () => {
    song.open()
    video.open()
    await m.close()
    await library.close()
    await fs.rm(dir, {recursive: true, force: true})
  }}
}
const audioFormat: FormatRequest = {type: 'audio', target: 'mp3', quality: 'best'}
const writeFor = async (task: {folder: string; stem: string; format: FormatRequest}) => {
  const file = path.join(task.folder, `${task.stem}.${task.format.type === 'audio' ? 'mp3' : 'mp4'}`)
  await fs.mkdir(task.folder, {recursive: true})
  await fs.writeFile(file, task.format.type === 'audio' ? 'song' : 'video')
  return {file, files: []}
}
const until = async (check: () => boolean | Promise<boolean>, label: string) => {
  for (let i = 0; i < 400; i++) {
    if (await check()) return
    await delay(10)
  }
  throw new Error(`Timed out waiting for ${label}`)
}
const jobsOf = (manager: DownloadManager) => {
  const jobs = manager.snapshot().jobs
  return {song: jobs.find((job) => job.kind === 'audio')!, video: jobs.find((job) => job.kind === 'video')!}
}

test('Capture, the song finishes first: the video is kept beside it as Song.video.mp4', async () => {
  const f = await linked(({video}) => async (task) => {
    if (task.format.type === 'video') await video
    return writeFor(task)
  })
  try {
    await f.manager.enqueue(f.songUrl, audioFormat, undefined, 'music', 'abcdefghijk')
    const { song, video } = jobsOf(f.manager)
    assert.deepEqual(video.attachVideo, { jobId: song.id }, 'the video is linked to the song\'s job')
    assert.equal(video.space, 'music')
    assert.equal(video.url, 'https://music.youtube.com/watch?v=abcdefghijk')
    await until(() => jobsOf(f.manager).song.status === 'completed', 'the song')
    await until(() => f.library.snapshot().items.length === 1, 'the song in the library')
    f.openVideo()
    await until(() => f.library.snapshot().items[0]?.musicVideo === true, 'the video attached')
    const songFile = jobsOf(f.manager).song.file!
    assert.equal(await fs.readFile(songFile.replace(/\.mp3$/, '.video.mp4'), 'utf8'), 'video')
    assert.equal(f.library.snapshot().items.length, 1, 'one item: the video is not a second one')
  } finally {
    await f.cleanup()
  }
})

test('Capture, the video finishes first: it waits where it is, and the song\'s adoption attaches it', async () => {
  const f = await linked(({song}) => async (task) => {
    if (task.format.type === 'audio') await song
    return writeFor(task)
  })
  try {
    await f.manager.enqueue(f.songUrl, audioFormat, undefined, 'music', 'abcdefghijk')
    await until(() => jobsOf(f.manager).video.status === 'completed', 'the video')
    await f.library.settled()
    const videoFile = jobsOf(f.manager).video.file!
    assert.equal(f.library.snapshot().items.length, 0, 'not an item of its own while its song is on the way')
    assert.equal(await exists(videoFile), true, 'kept where it finished')
    f.openSong()
    await until(() => f.library.snapshot().items[0]?.musicVideo === true, 'the video attached')
    assert.equal(await exists(videoFile), false, 'moved beside the song')
    assert.equal(f.library.snapshot().items.length, 1)
    // the catch-up after a restart finds nothing left to do
    await f.library.syncJobs()
    assert.equal(f.library.snapshot().items.length, 1)
  } finally {
    await f.cleanup()
  }
})

test('Capture, the song was already in the library: the video that finished first goes beside that one', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-mv-existing-'))
  const settings = defaultLibrarySettings({downloads: path.join(dir, 'Downloads'), music: path.join(dir, 'Music'), videos: path.join(dir, 'Videos')})
  const music = settings.spaces.find((space) => space.id === 'music')!.folder
  let jobs: Job[] = []
  const index = new LibraryIndex({file: path.join(dir, 'data', 'library.json'), artDir: path.join(dir, 'art'), ffmpeg: () => undefined, jobs: () => jobs, songPending: () => false, saveDelay: 10, pushDelay: 5, watchDelay: 30, watch: false, analyse: false})
  try {
    await index.init()
    index.configure(settings)
    const there = (await index.addFile(await write(path.join(music, 'Song.mp3')), {space: 'music', managed: true}))!
    await index.settled()
    const base = {url: 'https://music.youtube.com/watch?v=x', title: 'Song', label: '', options: {}, percent: 100, createdAt: 1} as unknown as Job
    const song = {...base, id: 'song-job', kind: 'audio', format: {type: 'audio', target: 'mp3', quality: 'best'}, status: 'completed', existing: {itemId: there.id, path: there.path, space: 'music', by: 'source'}} as Job
    const video = {...base, id: 'video-job', kind: 'video', format: {type: 'video'}, status: 'completed', file: await write(path.join(dir, 'Downloads', 'Clip.mp4'), 'v'), attachVideo: {jobId: 'song-job'}} as Job
    jobs = [song, video]
    await index.adoptJob(song)
    await index.settled()
    assert.equal(await fs.readFile(path.join(music, 'Song.video.mp4'), 'utf8'), 'v')
    assert.deepEqual(index.snapshot().items.map((item) => [item.id, item.musicVideo]), [[there.id, true]])
  } finally {
    await index.close()
    await fs.rm(dir, {recursive: true, force: true})
  }
})

test('Capture, the song was deleted before its video finished: the video is kept as an ordinary download, not left waiting', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-mv-gone-'))
  const settings = defaultLibrarySettings({downloads: path.join(dir, 'Downloads'), music: path.join(dir, 'Music'), videos: path.join(dir, 'Videos')})
  const music = settings.spaces.find((space) => space.id === 'music')!.folder
  let jobs: Job[] = []
  const index = new LibraryIndex({file: path.join(dir, 'data', 'library.json'), artDir: path.join(dir, 'art'), ffmpeg: () => undefined, jobs: () => jobs, songPending: () => false, saveDelay: 10, pushDelay: 5, watchDelay: 30, watch: false, analyse: false})
  try {
    await index.init()
    index.configure(settings)
    const base = {url: 'https://music.youtube.com/watch?v=x', title: 'Song', label: '', options: {}, percent: 100, createdAt: 1} as unknown as Job
    // the song finished, was adopted and then deleted: its file is gone and no item has it
    const song = {...base, id: 'song-job', kind: 'audio', format: {type: 'audio', target: 'mp3', quality: 'best'}, status: 'completed', file: path.join(music, 'Gone.mp3')} as Job
    const video = {...base, id: 'video-job', kind: 'video', format: {type: 'video'}, status: 'completed', space: 'music', file: await write(path.join(music, 'Gone (Official Video).mp4'), 'v'), attachVideo: {jobId: 'song-job'}} as Job
    jobs = [song, video]
    await index.adoptJob(video)
    await index.settled()
    assert.deepEqual(index.snapshot().items.map((item) => path.basename(item.path)), ['Gone (Official Video).mp4'])
  } finally {
    await index.close()
    await fs.rm(dir, {recursive: true, force: true})
  }
})

test('Capture, a restart between the two: the catch-up attaches the waiting video', async () => {
  const f = await linked(() => async (task) => writeFor(task))
  try {
    await f.manager.enqueue(f.songUrl, audioFormat, undefined, 'music', 'abcdefghijk')
    await until(() => f.manager.snapshot().jobs.every((job) => job.status === 'completed'), 'both downloads')
    await f.library.settled()
    // the library forgets what it adopted (as if the app closed before it did), then starts up again
    f.library.drop(f.library.snapshot().items.map((item) => item.id))
    const { song, video } = jobsOf(f.manager)
    assert.equal(f.library.snapshot().items.length, 0)
    void song
    void video
    await f.library.syncJobs()
    await f.library.settled()
    assert.equal(f.library.snapshot().items.length, 1)
    assert.equal(f.library.snapshot().items[0].musicVideo, true)
  } finally {
    await f.cleanup()
  }
})

test('Capture, the song is cancelled or fails: the finished video is kept as an ordinary download, and a failed video never fails the song', async () => {
  const f = await linked(({song}) => async (task) => {
    if (task.format.type === 'audio') {
      await song
      throw new Error('This video is private.')
    }
    return writeFor(task)
  })
  try {
    await f.manager.enqueue(f.songUrl, audioFormat, undefined, 'music', 'abcdefghijk')
    await until(() => jobsOf(f.manager).video.status === 'completed', 'the video')
    const videoFile = jobsOf(f.manager).video.file!
    f.openSong()
    await until(() => jobsOf(f.manager).song.status === 'failed', 'the song failing')
    await until(() => f.library.snapshot().items.length === 1, 'the video kept')
    assert.equal(f.library.snapshot().items[0].path, videoFile, 'where it finished, as an ordinary item')
    assert.equal(await exists(videoFile), true)
  } finally {
    await f.cleanup()
  }
  const g = await linked(({video}) => async (task) => {
    if (task.format.type === 'video') {
      await video
      throw new Error('This video is private.')
    }
    return writeFor(task)
  })
  try {
    await g.manager.enqueue(g.songUrl, audioFormat, undefined, 'music', 'abcdefghijk')
    await until(() => jobsOf(g.manager).song.status === 'completed', 'the song')
    g.openVideo()
    await until(() => jobsOf(g.manager).video.status === 'failed', 'the video failing')
    await g.library.settled()
    assert.equal(jobsOf(g.manager).song.status, 'completed', 'the song succeeded')
    assert.equal(g.library.snapshot().items.length, 1)
    assert.equal(g.library.snapshot().items[0].musicVideo, undefined)
    // Retry touches only the video
    assert.deepEqual(g.manager.snapshot().jobs.map((job) => job.status).sort(), ['completed', 'failed'])
  } finally {
    await g.cleanup()
  }
})

test('Capture: a music video goes only with a song downloaded as audio, and an unreadable video leaves the song queued', async () => {
  const f = await linked(() => async (task) => writeFor(task))
  try {
    await assert.rejects(f.manager.enqueue(f.songUrl, {type: 'video', codec: 'auto', container: 'mp4'}, undefined, 'music', 'abcdefghijk'), /song downloaded as audio/)
    await assert.rejects(f.manager.enqueue(f.songUrl, audioFormat, undefined, 'music', 'not an id'), /not a video/)
    assert.equal(f.manager.snapshot().jobs.length, 0, 'nothing is queued by a refused request')
    f.engine.inspectResult = (url) => (url.endsWith('abcdefghijk') ? {type: 'error', error: 'This video is private.'} as never : { type: 'media', media: fakeMedia(url, { title: 'Song', music: { artist: 'Artist', track: 'Song' } }) })
    await f.manager.enqueue(f.songUrl, audioFormat, undefined, 'music', 'abcdefghijk')
    assert.deepEqual(f.manager.snapshot().jobs.map((job) => job.kind), ['audio'], 'the song is queued without its video')
  } finally {
    await f.cleanup()
  }
})

// ---- Playlists and albums: each song job carries findVideo; when it finishes, one look, one linked video ----

const PLAYLIST = 'https://music.youtube.com/playlist?list=PLmusic'
/** Songs by one artist, found by the playlist's discovery. */
async function playlistOf(f: Awaited<ReturnType<typeof linked>>, count = 3) {
  f.engine.discover = async (_url, _page, _ctx, onEntry, onMeta) => {
    onMeta({title: 'Mix', uploader: 'Someone'})
    for (let n = 1; n <= count; n++) onEntry({url: `https://music.youtube.com/watch?v=song${n}XXXXXXX`, title: `Song ${n}`, artist: 'Artist', position: n})
    return {truncated: false}
  }
  f.manager.startDiscovery(PLAYLIST, 'd1')
  await until(() => f.manager.discoveryState('d1').state === 'completed', 'discovery')
  return f.manager.discoveryState('d1').entries.map((entry) => entry.url)
}
const songs = (manager: DownloadManager) => manager.snapshot().jobs.filter((job) => job.kind === 'audio')
const videos = (manager: DownloadManager) => manager.snapshot().jobs.filter((job) => job.kind === 'video')

test('findVideo: marked on the batch, persisted in the queue file, and safely migrated when missing', async () => {
  const f = await linked(() => async (task) => writeFor(task))
  try {
    const urls = await playlistOf(f, 2)
    await f.manager.enqueueDiscovered('d1', urls.slice(0, 1), 'audio-mp3', undefined, true, 'music', undefined, true)
    await f.manager.enqueueDiscovered('d1', urls.slice(1), 'audio-mp3', undefined, true, 'music')
    const [marked, plain] = songs(f.manager)
    assert.equal(marked.findVideo, true)
    assert.equal(plain.findVideo, undefined, 'only the marked batch looks')
    await until(() => songs(f.manager).every((job) => job.status === 'completed'), 'the songs')
    const stored = JSON.parse(await fs.readFile(path.join(f.dir, 'queue.json'), 'utf8')).jobs.find((job: Job) => job.id === marked.id)
    assert.equal(stored.findVideo, true, 'persisted')
    assert.equal(migrateJob(stored)?.findVideo, true)
    const {findVideo: _gone, ...old} = stored
    void _gone
    assert.equal(migrateJob(old)?.findVideo, undefined, 'a job saved before the field existed still loads')
    assert.equal(migrateJob({...stored, findVideo: 'yes'})?.findVideo, undefined, 'only true counts')
  } finally {
    await f.cleanup()
  }
})

test('findVideo: it is refused for video and for a link that is not a music collection', async () => {
  const f = await linked(() => async (task) => writeFor(task))
  try {
    const urls = await playlistOf(f, 1)
    await assert.rejects(f.manager.enqueueDiscovered('d1', urls, '720', undefined, true, 'music', undefined, true), /downloaded as audio/)
    assert.equal(f.manager.snapshot().jobs.length, 0, 'nothing queued by a refused request')
    f.engine.discover = async (_url, _page, _ctx, onEntry) => {
      onEntry({url: 'https://www.youtube.com/watch?v=plainVID123', title: 'Plain', position: 1})
      return {truncated: false}
    }
    f.manager.startDiscovery('https://www.youtube.com/playlist?list=PL123', 'd2')
    await until(() => f.manager.discoveryState('d2').state === 'completed', 'discovery')
    await assert.rejects(f.manager.enqueueDiscovered('d2', f.manager.discoveryState('d2').entries.map((entry) => entry.url), 'audio-mp3', undefined, true, 'music', undefined, true), /music collection/)
  } finally {
    await f.cleanup()
  }
})

test('findVideo: a finished song looks once and queues one video linked to its job; a repeat changes nothing', async () => {
  const looked: string[] = []
  const f = await linked(() => async (task) => writeFor(task), async (artist, title) => {
    looked.push(`${artist} - ${title}`)
    return {videoId: 'abcdefghijk', title: 'x', channel: artist}
  })
  try {
    const urls = await playlistOf(f, 1)
    await f.manager.enqueueDiscovered('d1', urls, 'audio-mp3', undefined, true, 'music', undefined, true)
    await until(() => videos(f.manager).length === 1 && f.manager.snapshot().jobs.every((job) => job.status === 'completed'), 'the song and its video')
    const song = songs(f.manager)[0]
    const video = videos(f.manager)[0]
    assert.deepEqual(looked, ['Artist - Song 1'], 'one look, by the real artist and title')
    assert.deepEqual(video.attachVideo, {jobId: song.id})
    assert.equal(video.url, 'https://music.youtube.com/watch?v=abcdefghijk')
    assert.equal(video.space, 'music')
    await until(async () => (await f.library.settled(), f.library.snapshot().items[0]?.musicVideo === true), 'the video beside the song')
    // a restart or a Retry of the same song job: a video job for it exists, and the song has its video, so there is no second look
    const inner = f.manager as unknown as {lookForVideo(job: unknown): void; jobs: Job[]; videoLooks: Promise<void>}
    inner.lookForVideo(inner.jobs.find((job) => job.id === song.id))
    await inner.videoLooks
    assert.equal(looked.length, 1, 'a linked video job already exists')
    inner.jobs = inner.jobs.filter((job) => job.id !== video.id)
    inner.lookForVideo(inner.jobs.find((job) => job.id === song.id))
    await inner.videoLooks
    assert.equal(looked.length, 1, 'the song already has its music video')
    assert.equal(videos(f.manager).length, 0)
  } finally {
    await f.cleanup()
  }
})

test('findVideo: a finished look ends the mark, a restart resumes only looks that never ran, and Get the music video does not add a second one', async () => {
  const looked: string[] = []
  // the videos stay downloading until the end, so they are still "on their way"
  const f = await linked(({video}) => async (task) => {
    if (task.format.type === 'video') await video
    return writeFor(task)
  }, async (artist, title) => {
    looked.push(title)
    return {videoId: 'abcdefghijk', title: 'x', channel: artist}
  })
  try {
    const urls = await playlistOf(f, 2)
    await f.manager.enqueueDiscovered('d1', urls.slice(0, 1), 'audio-mp3', undefined, true, 'music', undefined, true)
    await f.manager.enqueueDiscovered('d1', urls.slice(1), 'audio-mp3', undefined, true, 'music')
    await until(() => videos(f.manager).length === 1 && songs(f.manager).every((job) => job.status === 'completed'), 'the songs and one video')
    const [marked, plain] = songs(f.manager)
    await until(() => songs(f.manager)[0].findVideo === undefined, 'the mark ended')
    const stored = async () => JSON.parse(await fs.readFile(path.join(f.dir, 'queue.json'), 'utf8')).jobs.find((job: Job) => job.id === marked.id)
    // saved: the next start does not look again
    await until(async () => (await stored()).findVideo === undefined, 'the ended mark saved')
    // the playlist's video is on its way: Get the music video on the same song is refused
    await until(async () => (await f.library.settled(), f.library.snapshot().items.length === 2), 'the songs in the library')
    const markedItem = f.library.snapshot().items.find((item) => item.path === marked.file)!
    await assert.rejects(f.manager.getMusicVideo(markedItem.id, 'zyxwvutsrqp'), /already downloading/)
    // a song still marked when Keepfin closed (its look never ran): the next start looks once
    const inner = f.manager as unknown as {jobs: Job[]; videoLooks: Promise<void>}
    inner.jobs.find((job) => job.id === plain.id)!.findVideo = true
    f.manager.resumeVideoLooks()
    await inner.videoLooks
    assert.deepEqual(looked, ['Song 1', 'Song 2'])
    assert.deepEqual(videos(f.manager).map((job) => job.attachVideo?.jobId).sort(), [marked.id, plain.id].sort())
  } finally {
    await f.cleanup()
  }
})

test('findVideo: none found, or a look that fails, leaves the finished song alone; an unmarked song never looks', async () => {
  const finders: ((artist: string, title: string) => Promise<MusicVideoFound>)[] = [async () => ({none: true}), async () => { throw new Error('offline') }]
  for (const finder of finders) {
    let looks = 0
    const f = await linked(() => async (task) => writeFor(task), async (artist, title) => {
      looks++
      return finder(artist, title)
    })
    const warn = console.warn
    console.warn = () => undefined
    try {
      const urls = await playlistOf(f, 2)
      await f.manager.enqueueDiscovered('d1', urls.slice(0, 1), 'audio-mp3', undefined, true, 'music', undefined, true)
      await f.manager.enqueueDiscovered('d1', urls.slice(1), 'audio-mp3', undefined, true, 'music')
      await until(() => songs(f.manager).every((job) => job.status === 'completed'), 'the songs')
      await (f.manager as unknown as {videoLooks: Promise<void>}).videoLooks
      assert.equal(looks, 1, 'only the marked song looked')
      assert.equal(videos(f.manager).length, 0)
      assert.deepEqual(songs(f.manager).map((job) => job.status), ['completed', 'completed'])
      await f.library.settled()
      assert.equal(f.library.snapshot().items.length, 2, 'both songs are in the library, untouched')
      assert.equal(f.library.snapshot().items.some((item) => item.musicVideo), false)
    } finally {
      console.warn = warn
      await f.cleanup()
    }
  }
})

test('findVideo: looks run one at a time, and the videos go through the normal queue', async () => {
  let active = 0
  let most = 0
  let looks = 0
  const f = await linked(() => async (task) => writeFor(task), async () => {
    looks++
    active++
    most = Math.max(most, active)
    await delay(25)
    active--
    return {videoId: `video${looks}XXXXXX`.slice(0, 11), title: 'x', channel: 'Artist'}
  })
  try {
    const urls = await playlistOf(f, 4)
    await f.manager.enqueueDiscovered('d1', urls, 'audio-mp3', undefined, true, 'music', undefined, true)
    await until(() => videos(f.manager).length === 4 && f.manager.snapshot().jobs.every((job) => job.status === 'completed'), 'four songs and four videos')
    assert.equal(looks, 4)
    assert.equal(most, 1, 'never two looks at once')
    assert.equal(new Set(videos(f.manager).map((job) => job.attachVideo?.jobId)).size, 4, 'each video is linked to its own song')
  } finally {
    await f.cleanup()
  }
})
