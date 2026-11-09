import {test} from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import {setTimeout as delay} from 'node:timers/promises'
import {defaultLibrarySettings, type LibrarySnapshot} from '../shared/library.js'
import {LibraryIndex} from './library/index.js'
import {deleteItems, importFiles, moveItems, renameItem} from './library/files.js'
import {parseMediaUrl, parseRange, respondToMedia, type MediaResolver} from './library/protocol.js'
import {planPlayback} from './library/prepare.js'
import {parseProbe} from './library/probe.js'
import {parseIntegratedLoudness, gainForLoudness} from './library/loudness.js'
import {assToVtt, srtToVtt} from './library/subtitles.js'

async function setup(options: {watch?: boolean} = {}) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-library-'))
  const settings = defaultLibrarySettings({downloads: path.join(dir, 'Downloads'), music: path.join(dir, 'Music'), videos: path.join(dir, 'Videos')})
  for (const space of settings.spaces.filter((item) => item.type === 'folder')) await fs.mkdir(space.folder, {recursive: true})
  const file = path.join(dir, 'data', 'library.json')
  const make = () =>
    new LibraryIndex({file, artDir: path.join(dir, 'art'), ffmpeg: () => undefined, saveDelay: 10, pushDelay: 5, watchDelay: 30, watch: options.watch ?? false, analyse: false})
  const index = make()
  await index.init()
  index.configure(settings)
  const trashed: string[] = []
  const deps = {
    trash: async (target: string) => {
      trashed.push(target)
      await fs.rm(target, {force: true})
    },
  }
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
async function until(check: () => boolean, label: string) {
  for (let i = 0; i < 300; i++) {
    if (check()) return
    await delay(10)
  }
  throw new Error(`Timed out waiting for ${label}`)
}

test('index lists managed files only, persists and reloads', async () => {
  const f = await setup()
  try {
    const managed = await write(path.join(f.settings.spaces[1].folder, 'Album', 'a.mp3'), 'audio-bytes')
    await write(path.join(f.settings.spaces[1].folder, 'stranger.mp3'))
    const entry = await f.index.addFile(managed, {space: 'music', managed: true})
    assert.ok(entry)
    await f.index.settled()
    const snapshot = f.index.snapshot()
    assert.equal(snapshot.items.length, 1)
    assert.equal(snapshot.items[0].folder, 'Album')
    assert.equal(snapshot.items[0].kind, 'audio')
    assert.equal(snapshot.items[0].title, 'a')
    assert.ok(!('managed' in snapshot.items[0]) && !('sig' in snapshot.items[0]))
    f.index.setFavorite([entry.id], true)
    f.index.saveCollection({name: 'Mix', itemIds: [entry.id, 'unknown']})
    await f.index.flush()
    const again = f.make()
    await again.init()
    again.configure(f.settings)
    const reloaded = again.snapshot()
    assert.equal(reloaded.items.length, 1)
    assert.equal(reloaded.items[0].favorite, true)
    assert.deepEqual(reloaded.collections[0].itemIds, [entry.id])
    assert.equal(reloaded.collections[0].kind, 'own')
    await again.close()
  } finally {
    await f.cleanup()
  }
})

test('lyrics recorded at an old place are found again beside the song (a moved Keepfin folder keeps each file\'s size and time)', async () => {
  const f = await setup()
  try {
    const song = await write(path.join(f.settings.spaces[1].folder, 'rr', 'Numb.mp3'), 'audio-bytes')
    const lrc = await write(path.join(f.settings.spaces[1].folder, 'rr', 'Numb.lrc'), '[00:01.00]la')
    const entry = await f.index.addFile(song, {space: 'music', managed: true})
    await f.index.settled()
    assert.equal(f.index.lookup(entry!.id)?.lrc, lrc)
    // what Change location left behind: the same song, its lyrics recorded under the old folder
    f.index.lookup(entry!.id)!.lrc = path.join(f.dir, 'old place', 'Music', 'rr', 'Numb.lrc')
    await f.index.scan()
    await f.index.settled()
    assert.equal(f.index.lookup(entry!.id)?.lrc, lrc)
  } finally {
    await f.cleanup()
  }
})

test('scanFolder lists unmanaged files, .nomedia and ignore are respected', async () => {
  const f = await setup()
  try {
    const music = f.settings.spaces[1]
    await write(path.join(music.folder, 'one.mp3'))
    await write(path.join(music.folder, 'skip', 'two.mp3'))
    await write(path.join(music.folder, 'hidden', 'three.mp3'))
    await write(path.join(music.folder, 'hidden', '.nomedia'), '')
    await write(path.join(music.folder, 'one.mp3.part'))
    await f.index.scan()
    assert.equal(f.index.snapshot().items.length, 0)
    music.scanFolder = true
    music.ignore = ['skip']
    f.index.configure(f.settings)
    await f.index.scan()
    assert.deepEqual(f.index.snapshot().items.map((item) => item.title), ['one'])
  } finally {
    await f.cleanup()
  }
})

test('a renamed managed file is followed and a deleted one is marked missing', async () => {
  const f = await setup({watch: true})
  try {
    const video = f.settings.spaces[2].folder
    const original = await write(path.join(video, 'clip.mp4'), 'video-bytes')
    const entry = (await f.index.addFile(original, {space: 'videos', managed: true}))!
    await f.index.settled()
    const oldId = entry.id
    f.index.setFavorite([entry.id], true)
    const renamed = path.join(video, 'renamed.mp4')
    await fs.rename(original, renamed)
    await until(() => f.index.snapshot().items[0]?.path === renamed, 'rename to be followed')
    const item = f.index.snapshot().items[0]
    assert.equal(item.favorite, true)
    assert.notEqual(item.id, oldId)
    await fs.rm(renamed)
    await until(() => f.index.snapshot().items[0]?.missing === true, 'deletion to be noticed')
  } finally {
    await f.cleanup()
  }
})

test('playback state counts plays once and marks videos watched', async () => {
  const f = await setup()
  try {
    const video = (await f.index.addFile(await write(path.join(f.settings.spaces[2].folder, 'v.mp4')), {space: 'videos', managed: true}))!
    video.duration = 100
    f.index.recordPlayback(video.id, 10, false)
    assert.equal(video.plays, undefined)
    f.index.recordPlayback(video.id, 35, false)
    f.index.recordPlayback(video.id, 40, false)
    assert.equal(video.plays, 1)
    assert.equal(video.position, 40)
    f.index.recordPlayback(video.id, 93, false)
    assert.equal(video.watched, true)
    f.index.recordPlayback(video.id, 100, true)
    assert.equal(video.position, undefined)
    assert.equal(video.plays, 1)
    f.index.recordPlayback(video.id, 1, false)
    f.index.recordPlayback(video.id, 31, false)
    assert.equal(video.plays, 2)
  } finally {
    await f.cleanup()
  }
})

test('snapshots push only when something changed', async () => {
  const f = await setup()
  try {
    const seen: LibrarySnapshot[] = []
    f.index.subscribe((snapshot) => seen.push(snapshot))
    await delay(40)
    const before = seen.length
    await delay(60)
    assert.equal(seen.length, before)
    await f.index.addFile(await write(path.join(f.settings.spaces[0].folder, 'x.mp4')), {space: 'downloads', managed: true})
    await until(() => seen.length > before, 'a push')
  } finally {
    await f.cleanup()
  }
})

test('removed items are pruned from playlists', async () => {
  const f = await setup()
  try {
    const a = (await f.index.addFile(await write(path.join(f.settings.spaces[0].folder, 'a.mp4')), {space: 'downloads', managed: true}))!
    const b = (await f.index.addFile(await write(path.join(f.settings.spaces[0].folder, 'b.mp4')), {space: 'downloads', managed: true}))!
    const playlist = f.index.saveCollection({name: 'P', itemIds: [a.id, b.id]})
    await deleteItems(f.index, [a.id], f.deps)
    assert.deepEqual(f.index.collection(playlist.id).itemIds, [b.id])
    assert.equal(f.trashed.length, 1)
    assert.throws(() => f.index.item(a.id))
  } finally {
    await f.cleanup()
  }
})

test('duplicate detection: source id, metadata and content', async () => {
  const f = await setup()
  try {
    const music = f.settings.spaces[1].folder
    const song = (await f.index.addFile(await write(path.join(music, 'song.mp3'), 'same-bytes'), {space: 'music', managed: true, sourceUrl: 'https://music.youtube.com/watch?v=abc123DEF45'}))!
    song.title = 'Song'
    song.artist = 'Artist'
    song.duration = 200
    song.isrc = 'GBDUW0000053'
    assert.equal(f.index.findExisting('music', {isrc: 'gb-duw-00-00053'})?.by, 'isrc')
    assert.equal(f.index.findExisting('music', {recording: {artist: 'Someone Else', title: 'Another Title', isrc: 'GBDUW0000053'}})?.by, 'isrc')
    assert.equal(f.index.findExisting('music', {isrc: 'GBDUW0000099'}), undefined)
    assert.equal(f.index.findExisting('music', {sourceId: 'youtube:abc123DEF45'})?.by, 'source')
    assert.equal(f.index.findExisting('music', {recording: {artist: 'Artist - Topic', title: 'Song (Official Audio)', duration: 201}})?.by, 'metadata')
    assert.equal(f.index.findExisting('music', {recording: {artist: 'Artist', title: 'Song (Live)', duration: 200}}), undefined)
    assert.equal(f.index.findExisting('videos', {sourceId: 'youtube:abc123DEF45'}), undefined)
    const same = await write(path.join(f.dir, 'elsewhere', 'copy.mp3'), 'same-bytes')
    assert.equal((await f.index.findByContent('music', same, 10))?.by, 'content')
    const other = await write(path.join(f.dir, 'elsewhere', 'other.mp3'), 'diff-bytes')
    assert.equal(await f.index.findByContent('music', other, 10), undefined)
  } finally {
    await f.cleanup()
  }
})

test('import copies, skips content duplicates and keeps names unique; move relocates', async () => {
  const f = await setup()
  try {
    const source = path.join(f.dir, 'incoming')
    const a = await write(path.join(source, 'a.mp3'), 'AAAA')
    await write(path.join(source, 'sub', 'b.mp3'), 'BBBB')
    await write(path.join(source, 'notes.txt'), 'no')
    const first = await importFiles(f.index, {paths: [a, path.join(source, 'sub')], space: 'music', mode: 'copy'}, f.deps)
    assert.equal(first.added.length, 2)
    assert.equal(first.failed.length, 0)
    assert.ok(await fs.stat(a), 'copy leaves the original')
    const again = await importFiles(f.index, {paths: [a], space: 'music', mode: 'copy'}, f.deps)
    assert.equal(again.added.length, 0)
    assert.equal(again.duplicates.length, 1)
    const clash = await write(path.join(source, 'x', 'a.mp3'), 'DIFFERENT')
    const named = await importFiles(f.index, {paths: [clash], space: 'music', mode: 'move'}, f.deps)
    assert.equal(named.added.length, 1)
    assert.ok(f.index.item(named.added[0]).path.endsWith('a (2).mp3'))
    await assert.rejects(importFiles(f.index, {paths: ['relative.mp3'], space: 'music', mode: 'copy'}, f.deps).then((r) => { if (r.failed.length) throw new Error('rejected') }))
    await assert.rejects(importFiles(f.index, {paths: [a], space: 'nope', mode: 'copy'}, f.deps), /space/)
    const result = await moveItems(f.index, {ids: first.added, space: 'videos'}, f.deps)
    assert.equal(result.moved.length, 2)
    for (const id of result.moved) {
      const item = f.index.item(id)
      assert.equal(item.space, 'videos')
      assert.ok(item.path.startsWith(f.settings.spaces[2].folder))
      assert.ok(await fs.stat(item.path))
    }
    assert.equal(f.index.itemsOf('music').length, 1)
  } finally {
    await f.cleanup()
  }
})

test('rename keeps the extension, rejects clashes and bad names', async () => {
  const f = await setup()
  try {
    const dir = f.settings.spaces[0].folder
    const a = (await f.index.addFile(await write(path.join(dir, 'a.mp4')), {space: 'downloads', managed: true}))!
    await write(path.join(dir, 'taken.mp4'))
    await assert.rejects(renameItem(f.index, a.id, 'taken'), /already exists/)
    await assert.rejects(renameItem(f.index, a.id, 'bad/name'), /cannot contain/)
    await assert.rejects(renameItem(f.index, a.id, '  '), /file name/)
    const renamed = await renameItem(f.index, a.id, 'Better')
    assert.ok(renamed.path.endsWith('Better.mp4'))
    assert.equal(renamed.title, 'Better')
    assert.ok(await fs.stat(path.join(dir, 'Better.mp4')))
  } finally {
    await f.cleanup()
  }
})

test('media URLs parse strictly', () => {
  assert.deepEqual(parseMediaUrl('keepfin-media://item/abc123'), {kind: 'item', id: 'abc123'})
  assert.deepEqual(parseMediaUrl('keepfin-media://sub/abc/2'), {kind: 'sub', id: 'abc', index: 2})
  for (const bad of ['keepfin-media://item/a/b', 'keepfin-media://file/abc', 'keepfin-media://item/C%3A%5Cx', 'https://item/abc', 'keepfin-media://sub/abc', 'keepfin-media://item/']) assert.equal(parseMediaUrl(bad), undefined, bad)
})

test('range parsing', () => {
  assert.deepEqual(parseRange('bytes=0-9', 100), {start: 0, end: 9})
  assert.deepEqual(parseRange('bytes=90-', 100), {start: 90, end: 99})
  assert.deepEqual(parseRange('bytes=-10', 100), {start: 90, end: 99})
  assert.deepEqual(parseRange('bytes=50-500', 100), {start: 50, end: 99})
  assert.equal(parseRange('bytes=100-', 100), 'unsatisfiable')
  assert.equal(parseRange('bytes=-0', 100), 'unsatisfiable')
  assert.equal(parseRange('bytes=0-1,5-6', 100), undefined)
  assert.equal(parseRange('bytes=9-2', 100), undefined)
  assert.equal(parseRange(null, 100), undefined)
})

test('protocol responses: 200, 206, 416, confinement', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-protocol-'))
  try {
    const media = await write(path.join(dir, 'a.mp3'), '0123456789')
    const secret = await write(path.join(dir, 'secret.txt'), 'nope')
    const sub = await write(path.join(dir, 'a.srt'), '1\n00:00:01,500 --> 00:00:02,000\nHi\n')
    const resolver: MediaResolver = {
      item: (id) => (id === 'ok' ? media : id === 'leak' ? secret : undefined),
      art: () => undefined,
      sub: (id) => (id === 'ok' ? sub : undefined),
      job: () => undefined,
      prepared: () => undefined,
    }
    const get = (url: string, range?: string, method = 'GET') =>
      respondToMedia({url, method, headers: {get: (name) => (name.toLowerCase() === 'range' ? (range ?? null) : null)}}, resolver)
    const full = await get('keepfin-media://item/ok')
    assert.equal(full.status, 200)
    assert.equal(full.headers.get('accept-ranges'), 'bytes')
    assert.equal(full.headers.get('content-type'), 'audio/mpeg')
    assert.equal(await full.text(), '0123456789')
    const part = await get('keepfin-media://item/ok', 'bytes=2-4')
    assert.equal(part.status, 206)
    assert.equal(part.headers.get('content-range'), 'bytes 2-4/10')
    assert.equal(part.headers.get('content-length'), '3')
    assert.equal(await part.text(), '234')
    assert.equal(await (await get('keepfin-media://item/ok', 'bytes=-3')).text(), '789')
    const bad = await get('keepfin-media://item/ok', 'bytes=20-30')
    assert.equal(bad.status, 416)
    assert.equal(bad.headers.get('content-range'), 'bytes */10')
    assert.equal((await get('keepfin-media://item/ok', undefined, 'HEAD')).headers.get('content-length'), '10')
    assert.equal((await get('keepfin-media://item/missing')).status, 404)
    assert.equal((await get('keepfin-media://item/leak')).status, 404, 'non-media files are never served')
    assert.equal((await get('keepfin-media://item/..%2F..%2Fx')).status, 404)
    assert.equal((await get('keepfin-media://item/ok', undefined, 'POST')).status, 405)
    const vtt = await (await get('keepfin-media://sub/ok/0')).text()
    assert.match(vtt, /^WEBVTT/)
    assert.match(vtt, /00:00:01\.500 --> 00:00:02\.000/)
  } finally {
    await fs.rm(dir, {recursive: true, force: true})
  }
})

test('playback planning remuxes only what Chromium can decode', () => {
  const probe = (video?: string, audio?: string) => ({containers: ['matroska'], video: video ? {codec: video} : undefined, audio: audio ? [{codec: audio}] : [], chapters: []})
  assert.equal(planPlayback('mp4', undefined).kind, 'direct')
  assert.deepEqual(planPlayback('mkv', probe('h264', 'aac')), {kind: 'remux', target: 'mp4', video: 'h264', audio: 'aac'})
  assert.equal((planPlayback('mkv', probe('vp9', 'opus')) as {target: string}).target, 'webm')
  assert.equal(planPlayback('mkv', probe('mpeg4', 'aac')).kind, 'unsupported')
  assert.equal(planPlayback('avi', probe('h264', 'ac3')).kind, 'unsupported')
  assert.equal(planPlayback('mkv', undefined).kind, 'unsupported')
})

test('ffmpeg output parsers', () => {
  const banner = `Input #0, matroska,webm, from 'a.mkv':
  Duration: 00:01:02.50, start: 0.000000, bitrate: 500 kb/s
  Chapters:
    Chapter #0:0: start 0.000000, end 30.000000
      Metadata:
        title           : Intro
    Chapter #0:1: start 30.000000, end 62.500000
  Stream #0:0: Video: h264 (High), yuv420p(progressive), 640x360 [SAR 1:1 DAR 16:9], 25 fps
  Stream #0:1(eng): Audio: aac (LC), 44100 Hz, stereo, fltp`
  const probe = parseProbe(banner)
  assert.deepEqual(probe.containers, ['matroska', 'webm'])
  assert.equal(probe.duration, 62.5)
  assert.deepEqual(probe.video, {codec: 'h264', width: 640, height: 360})
  assert.deepEqual(probe.audio, [{codec: 'aac'}])
  assert.deepEqual(probe.chapters, [{start: 0, title: 'Intro'}, {start: 30, title: 'Chapter 2'}])
  const summary = '[Parsed_ebur128_0 @ x] Summary:\n\n  Integrated loudness:\n    I:         -20.3 LUFS\n    Threshold: -30.4 LUFS\n'
  assert.equal(parseIntegratedLoudness(summary), -20.3)
  assert.equal(gainForLoudness(-20.3), 0)
  assert.equal(gainForLoudness(-9.2), -4.8)
  assert.equal(parseIntegratedLoudness('I: -70.0 LUFS'), undefined)
})

test('subtitle conversion', () => {
  assert.match(srtToVtt('1\r\n00:00:01,5 --> 00:00:02,000\r\nHi <font color="red">x</font>\r\n'), /00:00:01\.5 --> 00:00:02\.000|00:00:01\.500 --> 00:00:02\.000/)
  const ass = '[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.50,0:00:03.00,Default,,0,0,0,,{\\i1}Hello, world\\Nline two\n'
  const vtt = assToVtt(ass)
  assert.match(vtt, /00:00:01\.500 --> 00:00:03\.000\nHello, world\nline two/)
})

test('downloaded collections keep source order whatever order the files finish in, and survive a reload', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-collection-'))
  try {
    const settings = defaultLibrarySettings({downloads: path.join(dir, 'Downloads'), music: path.join(dir, 'Music'), videos: path.join(dir, 'Videos')})
    const music = settings.spaces[1].folder
    const file = path.join(dir, 'data', 'library.json')
    const open = (jobs: unknown[] = []) => new LibraryIndex({file, artDir: path.join(dir, 'art'), ffmpeg: () => undefined, jobs: () => jobs as never, saveDelay: 10, pushDelay: 5, watch: false, analyse: false})
    const index = open()
    await index.init()
    index.configure(settings)
    const collection = index.ensureCollection({url: 'https://music.youtube.com/playlist?list=PL1&si=abc', name: 'Night drive', kind: 'playlist', space: 'music'})
    assert.equal(index.ensureCollection({url: 'https://www.youtube.com/playlist?list=PL1', kind: 'playlist', space: 'music'}).id, collection.id, 'the same link reuses the collection')
    const job = (n: number, collectionIndex: number, tags?: string[]) => {
      const group = {id: 'grp-1', title: 'Night drive', url: 'https://music.youtube.com/playlist?list=PL1', kind: 'playlist' as const}
      return ({id: `job-${n}`, url: `https://music.youtube.com/watch?v=aaaaaaaaaa${n}`, title: `Song ${n}`, status: 'completed', file: path.join(music, 'Night drive', `Song ${n}.mp3`), createdAt: n, completedAt: n + 1, group, space: 'music', collectionId: collection.id, collectionIndex, tags, kind: 'audio', format: {type: 'audio'}, options: {}, label: 'MP3', percent: 100}) as never
    }
    // Source order is 3, 1, 2; they finish as 2, 3, 1.
    const finished = [job(2, 2), job(3, 0, ['Lo-Fi', 'lo-fi', 'Chill']), job(1, 1)]
    for (const done of finished) {
      await write((done as {file: string}).file)
      await index.adoptJob(done)
    }
    const names = (ids: string[]) => ids.map((id) => path.basename(index.item(id).path))
    assert.deepEqual(names(index.collection(collection.id).itemIds), ['Song 3.mp3', 'Song 1.mp3', 'Song 2.mp3'])
    const snapshot = index.snapshot()
    assert.ok(snapshot.items.every((item) => !('collection' in item) && !('colIndex' in item)))
    assert.deepEqual(snapshot.items.find((item) => item.title === 'Song 3')?.tags, ['lo-fi', 'chill'])
    await index.close()
    const again = open()
    await again.init()
    again.configure(settings)
    assert.deepEqual(names(again.collection(collection.id).itemIds).length, 3)
    assert.deepEqual(again.collection(collection.id).source, {url: 'https://www.youtube.com/playlist?list=PL1', provider: 'YouTube Music', id: 'PL1'})
    // A later download slots in by its place in the source, not by when it finished.
    const late = job(4, 1)
    await write((late as {file: string}).file)
    await again.adoptJob(late)
    assert.deepEqual(again.collection(collection.id).itemIds.map((id) => path.basename(again.item(id).path)), ['Song 3.mp3', 'Song 1.mp3', 'Song 4.mp3', 'Song 2.mp3'])
    await again.close()
  } finally {
    await fs.rm(dir, {recursive: true, force: true})
  }
})

test('deleting everything reports progress, drops playlists and queue references, and reloads cleanly', async () => {
  const f = await setup()
  try {
    const ids: string[] = []
    for (let n = 0; n < 60; n++) {
      const entry = await f.index.addFile(await write(path.join(f.settings.spaces[1].folder, `s${n}.mp3`)), {space: 'music', managed: true})
      ids.push(entry!.id)
    }
    f.index.saveCollection({name: 'All', itemIds: ids})
    const seen: number[] = []
    f.index.subscribe((snapshot) => {
      if (snapshot.operation?.kind === 'delete') seen.push(snapshot.operation.done)
    })
    const pending = deleteItems(f.index, ids, f.deps)
    await assert.rejects(deleteItems(f.index, ids, f.deps), /Wait for the current file operation/)
    const result = await pending
    await delay(30)
    assert.equal(result.deleted.length, 60)
    assert.equal(result.failed.length, 0)
    assert.ok(seen.length > 0 && seen.every((done) => done <= 60), 'progress was pushed while it ran')
    const snapshot = f.index.snapshot()
    assert.equal(snapshot.operation, undefined)
    assert.equal(snapshot.items.length, 0)
    assert.deepEqual(snapshot.collections[0].itemIds, [])
    // A second delete of the same ids (stale selection) is a no-op, not an error.
    assert.deepEqual(await deleteItems(f.index, ids, f.deps), {deleted: [], failed: []})
    await f.index.flush()
    const again = f.make()
    await again.init()
    again.configure(f.settings)
    assert.equal(again.snapshot().items.length, 0)
    assert.deepEqual(again.snapshot().collections[0].itemIds, [])
    await again.close()
  } finally {
    await f.cleanup()
  }
})
