// Real engine smoke test: actual yt-dlp + ffmpeg against a generated local video.
//   tsx scripts/test/smoke-engine.ts          local fixtures only (no network except engine download)
//   tsx scripts/test/smoke-engine.ts --live   also YouTube single, YouTube Music page, Spotify resolve
import {createServer} from 'node:http'
import {spawnSync} from 'node:child_process'
import fs from 'node:fs/promises'
import path from 'node:path'
import os from 'node:os'
import assert from 'node:assert/strict'
import ffmpeg from 'ffmpeg-static'
import {StudioEngine, type DownloadTask} from '../../gui/desktop/engine/index.js'
import {ToolManager} from '../../gui/desktop/engine/tools.js'
import {InfoCache} from '../../gui/desktop/engine/ytdlp.js'
import {findMusicVideo, resolveMusic} from '../../gui/desktop/engine/music.js'
import {fetchCover, tagFile} from '../../gui/desktop/engine/tagging.js'
import {defaultSettings} from '../../gui/desktop/settings.js'
import type {
  FormatRequest,
  MediaInfo,
  PlaylistEntry,
} from '../../gui/shared/contracts.js'

const live = process.argv.includes('--live')
const root = path.resolve(import.meta.dirname, '..', '..')
const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-engine-smoke-'))
const results: {name: string; ok: boolean; detail: string}[] = []

async function step(name: string, work: () => Promise<string | void>) {
  const started = Date.now()
  try {
    const detail = (await work()) ?? ''
    results.push({
      name,
      ok: true,
      detail:
        `${detail} (${((Date.now() - started) / 1000).toFixed(1)}s)`.trim(),
    })
  } catch (error) {
    results.push({
      name,
      ok: false,
      detail: error instanceof Error ? error.message : String(error),
    })
  }
  const last = results.at(-1)!
  console.log(`${last.ok ? 'PASS' : 'FAIL'}  ${name}  ${last.detail}`)
}

// ---------------------------------------------------------------- fixture media
const fixture = path.join(dir, 'fixture.mp4')
const made = spawnSync(
  ffmpeg!,
  [
    '-y',
    '-f',
    'lavfi',
    '-i',
    'testsrc2=size=1280x720:rate=30',
    '-f',
    'lavfi',
    '-i',
    'sine=frequency=440:sample_rate=44100',
    '-t',
    '12',
    '-c:v',
    'libx264',
    '-b:v',
    '6M',
    '-minrate',
    '6M',
    '-maxrate',
    '6M',
    '-bufsize',
    '12M',
    '-x264-params',
    'nal-hrd=cbr',
    '-pix_fmt',
    'yuv420p',
    '-c:a',
    'aac',
    '-movflags',
    '+faststart',
    fixture,
  ],
  {encoding: 'utf8'},
)
if (made.status !== 0) throw new Error(made.stderr)
const body = await fs.readFile(fixture)
const server = createServer((req, res) => {
  const range = req.headers.range?.match(/^bytes=(\d+)-(\d*)$/)
  const headers = {'Content-Type': 'video/mp4', 'Accept-Ranges': 'bytes'}
  if (range) {
    const start = Number(range[1])
    const end = range[2]
      ? Math.min(Number(range[2]), body.length - 1)
      : body.length - 1
    res.writeHead(206, {
      ...headers,
      'Content-Length': end - start + 1,
      'Content-Range': `bytes ${start}-${end}/${body.length}`,
    })
    res.end(req.method === 'HEAD' ? undefined : body.subarray(start, end + 1))
  } else {
    res.writeHead(200, {...headers, 'Content-Length': body.length})
    res.end(req.method === 'HEAD' ? undefined : body)
  }
})
await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
const localUrl = `http://127.0.0.1:${(server.address() as {port: number}).port}/fixture.mp4`

// ---------------------------------------------------------------- engine
const infoCache = new InfoCache(path.join(dir, 'info'))
const tools = new ToolManager({
  dir: path.join(dir, 'tools'),
  // Reuse the build-time bundle (npm run tools:fetch) when present; otherwise download.
  resources: path.join(root, 'build', 'tools'),
  execPath: process.execPath,
  electron: false,
  nodeVersion: process.versions.node,
  ffmpegStatic: ffmpeg,
})
// Counts the YouTube Music requests the music matching makes (the albums search and the album pages are shared per album).
const musicRequests = {albums: 0, pages: 0, songs: 0}
const countingFetch = (async (input: string | URL | Request, init?: RequestInit) => {
  if (String(input).startsWith('https://music.youtube.com/youtubei/v1/') && typeof init?.body === 'string') {
    const body = JSON.parse(init.body) as {browseId?: string; params?: string}
    if (body.browseId) musicRequests.pages++
    else if (body.params === 'EgWKAQIYAWoKEAkQBRAKEAMQBA==') musicRequests.albums++
    else musicRequests.songs++
  }
  return fetch(input, init)
}) as typeof fetch
const engine = new StudioEngine(tools, infoCache, {
  ytDlpCacheDir: path.join(dir, 'ytdlp-cache'),
  fetch: countingFetch,
})
await tools.init()
await tools.ytDlp()
const settings = defaultSettings(path.join(dir, 'out'))
const ctx = () => ({settings})

async function run(
  name: string,
  url: string,
  media: MediaInfo,
  format: FormatRequest,
  turbo: boolean,
  options: DownloadTask['options'] = {embedMetadata: true},
) {
  const stem = name.replace(/\W+/g, '-')
  const task: DownloadTask = {
    url,
    title: media.title,
    format,
    options,
    folder: path.join(dir, 'out', name),
    stem,
  }
  const stages: string[] = []
  const percents: number[] = []
  const outcome = await engine.download(
    task,
    {settings: {...settings, turbo: {...settings.turbo, enabled: turbo}}},
    {
      onStage: (stage, phase) => stages.push(phase ?? stage),
      onProgress: (p) => percents.push(p.percent),
    },
  )
  const size = (await fs.stat(outcome.file)).size
  assert.ok(size > 1000, 'output file is empty')
  const probe = spawnSync(ffmpeg!, ['-hide_banner', '-i', outcome.file], {
    encoding: 'utf8',
  }).stderr
  return {outcome, size, probe, stages, percents}
}

let mp3File: string | undefined
/** Width x height of the first picture stream of a file, via the bundled ffmpeg. */
function coverDims(file: string): [number, number] {
  const info = spawnSync(ffmpeg!, ['-hide_banner', '-i', file], {encoding: 'utf8'}).stderr
  const match = /Video: \w+[^\n]*?, (\d{2,5})x(\d{2,5})/.exec(info)
  assert.ok(match, `no embedded picture in ${file}`)
  return [Number(match[1]), Number(match[2])]
}
function tagLine(file: string, key: string) {
  const info = spawnSync(ffmpeg!, ['-hide_banner', '-i', file], {encoding: 'utf8'}).stderr
  return new RegExp(`^\\s+${key}\\s*: (.+)$`, 'mi').exec(info)?.[1]?.trim()
}

let media: MediaInfo | undefined
await step('inspect local direct URL', async () => {
  const result = await engine.inspect(localUrl, ctx())
  assert.equal(result.type, 'media')
  if (result.type !== 'media') return
  media = result.media
  assert.ok(media.title)
  return `title="${media.title}" video=${media.video.length} audio=${media.audio.length}`
})
if (media) {
  const m = media
  await step('download video mp4 (plain yt-dlp)', async () => {
    const r = await run(
      'video-plain',
      localUrl,
      m,
      {type: 'video', codec: 'auto', container: 'mp4'},
      false,
    )
    assert.match(r.probe, /Video: h264/)
    assert.match(r.probe, /Audio: aac/)
    assert.equal(r.outcome.turbo, false)
    return `${r.size} bytes, stages=${[...new Set(r.stages)].join('>')}`
  })
  await step('a title with Arabic letters and emoji keeps its full file name (no repair needed)', async () => {
    const title = 'كرت 5090 يحتاج هوية! 😳 _ أخبارَ الباشا 📰'
    const folder = path.join(dir, 'out', 'unicode')
    const warnings: string[] = []
    const warn = console.warn
    console.warn = (...args: unknown[]) => void warnings.push(args.join(' '))
    let outcome
    try {
      outcome = await engine.download(
        {url: localUrl, title, format: {type: 'video', codec: 'auto', container: 'mp4'}, options: {embedMetadata: true}, folder, stem: title},
        ctx(),
        {onStage: () => {}, onProgress: () => {}},
      )
    } finally {
      console.warn = warn
    }
    const expected = path.join(folder, `${title}.mp4`)
    assert.equal(outcome.file, expected, 'the reported path is the full name')
    assert.ok((await fs.stat(outcome.file)).size > 1000, 'the finished job\'s file exists')
    assert.deepEqual(await fs.readdir(folder), [`${title}.mp4`])
    assert.deepEqual(warnings.filter((line) => line.includes('damaged')), [], 'the engine reported the name correctly, the safety net had nothing to fix')
    return path.basename(outcome.file)
  })
  await step('download audio mp3', async () => {
    const r = await run(
      'audio-mp3',
      localUrl,
      m,
      {type: 'audio', target: 'mp3', quality: '192'},
      false,
    )
    assert.ok(r.outcome.file.endsWith('.mp3'))
    assert.match(r.probe, /Audio: mp3/)
    assert.doesNotMatch(r.probe, /Video: (?!mjpeg|png)/)
    mp3File = r.outcome.file
    return `${r.size} bytes`
  })
  await step('source cover replaces the embedded picture (stubbed https server, square JPEG)', async () => {
    assert.ok(mp3File, 'needs the mp3 from the previous step')
    const art = path.join(dir, 'source-art.png')
    const made = spawnSync(ffmpeg!, ['-y', '-f', 'lavfi', '-i', 'color=c=red:s=900x900', '-frames:v', '1', art])
    assert.equal(made.status, 0)
    const artBytes = await fs.readFile(art)
    const artServer = createServer((_req, res) => res.writeHead(200, {'content-type': 'image/png'}).end(artBytes))
    await new Promise<void>((resolve) => artServer.listen(0, '127.0.0.1', resolve))
    const port = (artServer.address() as {port: number}).port
    try {
      const fetcher = ((url: string, init?: RequestInit) =>
        fetch(url.replace('https://covers.test', `http://127.0.0.1:${port}`), init)) as typeof fetch
      const track = {title: 'Cover Test', artists: ['Smoke'], album: 'Album', url: 'https://example.com/t', cover: 'https://covers.test/art.png'}
      assert.equal(await tagFile(ffmpeg!, mp3File!, track, {fetch: fetcher}), true)
      const dims = coverDims(mp3File!)
      assert.deepEqual(dims, [900, 900])
      return `embedded cover ${dims.join('x')}`
    } finally {
      artServer.close()
    }
  })
  await step(
    'download with Turbo (info JSON reports size; local range server)',
    async () => {
      // The generic extractor reports no size for bare URLs, which keeps Turbo off. Seed the
      // inspection cache with the same shape real extractors return (direct https-style stream).
      const sizedUrl = `${localUrl}?sized`
      const info = {
        id: 'fixture',
        title: 'fixture-sized',
        ext: 'mp4',
        extractor: 'generic',
        extractor_key: 'Generic',
        webpage_url: sizedUrl,
        original_url: sizedUrl,
        duration: 12,
        url: sizedUrl,
        protocol: 'http',
        filesize: body.length,
        format_id: 'direct',
        vcodec: 'h264',
        acodec: 'aac',
        width: 1280,
        height: 720,
        formats: [
          {
            format_id: 'direct',
            url: sizedUrl,
            ext: 'mp4',
            protocol: 'http',
            filesize: body.length,
            vcodec: 'h264',
            acodec: 'aac',
            width: 1280,
            height: 720,
            http_headers: {},
          },
        ],
      }
      await infoCache.put(sizedUrl, JSON.stringify(info))
      const r = await run(
        'video-turbo',
        sizedUrl,
        m,
        {type: 'video', codec: 'auto', container: 'mp4'},
        true,
      )
      assert.match(r.probe, /Video: h264/)
      assert.equal(
        r.outcome.turbo,
        true,
        'Turbo should have transferred the stream',
      )
      return `${r.size} bytes, turbo=true, stages=${[...new Set(r.stages)].join('>')}`
    },
  )
}

// ---------------------------------------------------------------- live checks
if (live) {
  const ytUrl =
    process.env.KEEPFIN_LIVE_YT ?? 'https://www.youtube.com/watch?v=78wrful9cVU'
  await step('LIVE YouTube single: inspect + 360p download', async () => {
    const result = await engine.inspect(ytUrl, ctx())
    assert.equal(result.type, 'media')
    if (result.type !== 'media') return
    const heights = result.media.video.map((v) => v.height)
    const r = await run(
      'yt',
      ytUrl,
      result.media,
      {type: 'video', maxHeight: 360, codec: 'auto', container: 'mp4'},
      true,
    )
    assert.match(r.probe, /Video:/)
    return `"${result.media.title}" heights=[${heights.slice(0, 6).join(',')}] -> ${r.size} bytes turbo=${r.outcome.turbo}`
  })
  const ytmUrl =
    process.env.KEEPFIN_LIVE_YTM ??
    'https://music.youtube.com/playlist?list=PLMC9KNkIncKtPzgY-5rmhvj7fax8fdxoj'
  await step('LIVE YouTube Music playlist page', async () => {
    const result = await engine.inspect(ytmUrl, ctx())
    assert.equal(result.type, 'collection')
    const entries: PlaylistEntry[] = []
    let title: string | undefined
    const page = await engine.discover(
      ytmUrl,
      {start: 1, end: 200},
      ctx(),
      (e) => entries.push(e),
      (m) => (title = m.title),
    )
    assert.ok(entries.length > 0, 'no entries discovered')
    return `title="${title}" entries=${entries.length} truncated=${page.truncated} first="${entries[0].title}"`
  })
  const spotifyUrl =
    process.env.KEEPFIN_LIVE_SPOTIFY ??
    'https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M'
  await step('LIVE Spotify playlist resolve + first track match', async () => {
    const result = await engine.inspect(spotifyUrl, ctx())
    assert.equal(result.type, 'collection')
    const entries: PlaylistEntry[] = []
    const page = await engine.discover(
      spotifyUrl,
      {start: 1, end: 200},
      ctx(),
      (e) => entries.push(e),
      () => {},
    )
    assert.ok(entries.length > 0, 'no tracks resolved')
    const first = await engine.inspect(entries[0].url, ctx())
    assert.equal(first.type, 'media')
    if (first.type !== 'media') return
    assert.equal(first.media.engine, 'music')
    assert.ok(first.media.match?.matchedUrl, 'no YouTube Music match')
    return `tracks=${entries.length} truncated=${page.truncated} "${first.media.title}" -> ${first.media.match?.matchedTitle} confidence=${first.media.match?.confidence?.toFixed(2)}`
  })
  const albumUrl = 'https://open.spotify.com/album/4m2880jivSbbyEGAKfITCa'
  // Album-aware matching: every track of Random Access Memories (13 tracks) is taken from the original album on YouTube Music,
  // not from the Drumless Edition, with one albums search and one album page for the whole album.
  await step('LIVE Spotify album Random Access Memories: all tracks match method album on the original album', async () => {
    const source = await resolveMusic(albumUrl, countingFetch)
    assert.equal(source.kind, 'collection')
    if (source.kind !== 'collection') return
    const tracks = source.collection.tracks
    assert.equal(tracks.length, 13, 'the album should list 13 tracks')
    const before = {...musicRequests}
    const matcher = engine as unknown as {
      matchTrack(track: unknown, url: string, ctx: unknown, yt: unknown): Promise<{method: string; album?: string; position?: number; candidate: {url: string; title: string}} | undefined>
      ytdlp(ctx: unknown): Promise<unknown>
    }
    const yt = await matcher.ytdlp(ctx())
    const picks = await Promise.all(tracks.map((track) => matcher.matchTrack(track, track.url, ctx(), yt)))
    const lines = picks.map((pick, index) => `${index + 1}. ${pick?.method}/${pick?.album}/#${pick?.position} ${pick?.candidate.title} ${pick?.candidate.url.slice(-11)}`)
    picks.forEach((pick, index) => {
      assert.ok(pick, `track ${index + 1} found nothing`)
      assert.equal(pick.method, 'album', `track ${index + 1} was matched by ${pick.method}: ${lines[index]}`)
      assert.equal(pick.album, 'Random Access Memories', `track ${index + 1} came from album ${pick.album}`)
      assert.equal(pick.position, index + 1, `track ${index + 1} sits at position ${pick.position}`)
      assert.ok(!/drumless|anniversary/i.test(pick.candidate.title), `track ${index + 1}: ${pick.candidate.title}`)
    })
    const used = {albums: musicRequests.albums - before.albums, pages: musicRequests.pages - before.pages, songs: musicRequests.songs - before.songs}
    assert.equal(used.albums, 1, 'one albums search for the whole album')
    assert.equal(used.pages, 1, 'one album page for the whole album')
    assert.equal(used.songs, 0, 'no per-track songs search')
    return `13/13 album, requests: albums search ${used.albums}, album page ${used.pages}, songs search ${used.songs}; first three: ${lines.slice(0, 3).join(' | ')}`
  })
  await step('LIVE Spotify track The Game of Love (no album in the link): method album, original album, year decides the edition', async () => {
    const result = await engine.inspect('https://open.spotify.com/track/3ctALmweZBapfBdFiIVpji', ctx())
    assert.equal(result.type, 'media')
    if (result.type !== 'media') return
    const match = result.media.match
    assert.equal(match?.method, 'album', `matched by ${match?.method}`)
    assert.equal(match?.album, 'Random Access Memories')
    assert.equal(match?.position, 2)
    assert.ok(!/drumless|anniversary/i.test(match?.matchedTitle ?? ''), match?.matchedTitle)
    return `${match?.matchedTitle} -> ${match?.matchedUrl} album="${match?.album}" #${match?.position} confidence=${match?.confidence?.toFixed(2)} duration=${match?.durationSec}`
  })
  const cover = {embedThumbnail: true, embedMetadata: true}
  await step('LIVE YouTube Music track as MP3: embedded cover is square, tags present', async () => {
    const url = process.env.KEEPFIN_LIVE_YTM_TRACK ?? 'https://music.youtube.com/watch?v=fcXJMqDW8aI'
    const result = await engine.inspect(url, ctx())
    assert.equal(result.type, 'media')
    if (result.type !== 'media') return
    const r = await run('ytm-mp3', url, result.media, {type: 'audio', target: 'mp3', quality: '192'}, false, cover)
    const [w, h] = coverDims(r.outcome.file)
    assert.equal(w, h, `cover is ${w}x${h}, expected square`)
    const title = tagLine(r.outcome.file, 'title')
    assert.ok(title, 'title tag missing')
    return `cover=${w}x${h} title="${title}" artist="${tagLine(r.outcome.file, 'artist')}" ${r.size} bytes`
  })
  await step('LIVE music video: the find step offers the official video, downloaded as the app keeps it (mp4, up to 1080p)', async () => {
    const hit = await findMusicVideo(['Adele'], 'Hello', countingFetch)
    assert.equal(hit?.videoId, 'YQHsXMglC9A', 'the official video')
    const url = `https://music.youtube.com/watch?v=${hit!.videoId}`
    const result = await engine.inspect(url, ctx())
    assert.equal(result.type, 'media')
    if (result.type !== 'media') return
    // the format Get the music video and Capture queue (gui/desktop/manager.ts queueMusicVideo)
    const r = await run('music-video', url, result.media, {type: 'video', maxHeight: 1080, codec: 'h264', container: 'mp4'}, false, {})
    assert.ok(r.outcome.file.endsWith('.mp4'), r.outcome.file)
    assert.match(r.probe, /Video:/)
    assert.match(r.probe, /Audio:/)
    const height = Number(/, \d+x(\d+)/.exec(r.probe)?.[1])
    assert.ok(height > 0 && height <= 1080, `height ${height}`)
    return `"${result.media.title}" ${height}p -> ${r.size} bytes`
  })
  await step('LIVE Spotify track as MP3: embedded cover is the Spotify art', async () => {
    const entries: PlaylistEntry[] = []
    await engine.discover(spotifyUrl, {start: 1, end: 5}, ctx(), (e) => entries.push(e), () => {})
    const entry = entries.find((e) => e.thumbnail) ?? entries[0]
    assert.ok(entry?.thumbnail, 'Spotify entry has no cover URL')
    const art = await fetchCover(entry.thumbnail)
    assert.ok(art, 'could not fetch Spotify art')
    const artFile = path.join(dir, 'spotify-art.jpg')
    await fs.writeFile(artFile, art)
    const expected = coverDims(artFile)
    const result = await engine.inspect(entry.url, ctx())
    assert.equal(result.type, 'media')
    if (result.type !== 'media') return
    const r = await run('spotify-mp3', entry.url, result.media, {type: 'audio', target: 'mp3', quality: '192'}, false, cover)
    const actual = coverDims(r.outcome.file)
    assert.deepEqual(actual, expected)
    assert.equal(actual[0], actual[1], 'cover is not square')
    return `cover=${actual.join('x')} (Spotify art ${expected.join('x')}) title="${tagLine(r.outcome.file, 'title')}" artist="${tagLine(r.outcome.file, 'artist')}" album="${tagLine(r.outcome.file, 'album')}"`
  })
}

server.close()
await fs.rm(dir, {recursive: true, force: true}).catch(() => {})
const failed = results.filter((r) => !r.ok)
console.log(
  `\n${results.length - failed.length}/${results.length} passed${live ? ' (live)' : ''}`,
)
process.exit(failed.length ? 1 : 0)
