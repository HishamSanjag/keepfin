// Tiny real media for the UI fixtures, generated at test start with the bundled ffmpeg and served over http by
// the Vite server (see harness.mjs). Nothing here is committed: the files are git-ignored in ./media.
// Codecs are VP9/Opus/MP3 because the Chromium that Playwright ships has no proprietary H.264/AAC decoder.
import {spawnSync} from 'node:child_process'
import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {createRequire} from 'node:module'
import zlib from 'node:zlib'

const here = path.dirname(fileURLToPath(import.meta.url))
export const mediaDir = path.join(here, 'media')
const require = createRequire(import.meta.url)

function ffmpeg(args) {
  const bin = require('ffmpeg-static')
  const run = spawnSync(bin, ['-hide_banner', '-loglevel', 'error', '-y', ...args], {encoding: 'utf8'})
  if (run.status !== 0) throw new Error(`ffmpeg failed: ${run.stderr || run.error}`)
}

// WHATS_NEW_SHOTS: calm generated landscapes instead of the test-pattern fixtures, so pictures of the app look like a real library.
const scenesOn = !!process.env.WHATS_NEW_SHOTS
const SCENES = 6
const hsl = (h, s, l) => {
  s /= 100
  l /= 100
  const k = (n) => (n + h / 30) % 12
  const a = s * Math.min(l, 1 - l)
  return [0, 8, 4].map((n) => Math.round(255 * (l - a * Math.max(-1, Math.min(k(n) - 3, Math.min(9 - k(n), 1))))))
}
function scenePng(width, height, seed) {
  const hue = (seed * 53 + 200) % 360
  const sky = [hsl(hue, 45, 30), hsl((hue + 40) % 360, 60, 68)]
  const sun = hsl((hue + 30) % 360, 85, 88)
  const sunX = width * (0.2 + 0.13 * (seed % 6))
  const sunY = height * 0.34
  const sunR = Math.min(width, height) * 0.11
  const layers = [0.55, 0.68, 0.8].map((base, i) => ({base: base * height, amp: height * (0.1 - i * 0.02), phase: seed * 1.7 + i * 2.3, color: hsl((hue + 15 * i) % 360, 38 - i * 4, 34 - i * 9)}))
  const rows = []
  for (let y = 0; y < height; y++) {
    const row = Buffer.alloc(1 + width * 3)
    for (let x = 0; x < width; x++) {
      const t = y / height
      let color = sky[0].map((c, i) => Math.round(c + (sky[1][i] - c) * t))
      const d = Math.hypot(x - sunX, y - sunY)
      if (d < sunR) color = sun
      else if (d < sunR * 2.2) color = color.map((c, i) => Math.round(c + (sun[i] - c) * 0.35 * (1 - (d - sunR) / (sunR * 1.2))))
      for (const layer of layers) {
        const u = x / width
        const ridge = layer.base - layer.amp * (0.55 + 0.3 * Math.sin(u * 5 + layer.phase) + 0.15 * Math.sin(u * 13 + layer.phase * 2))
        if (y > ridge) color = layer.color
      }
      row.set(color, 1 + x * 3)
    }
    rows.push(row)
  }
  const chunk = (type, data) => {
    const body = Buffer.concat([Buffer.from(type), data])
    const out = Buffer.alloc(body.length + 8)
    out.writeUInt32BE(data.length, 0)
    body.copy(out, 4)
    out.writeUInt32BE(zlib.crc32(body) >>> 0, body.length + 4)
    return out
  }
  const head = Buffer.alloc(13)
  head.writeUInt32BE(width, 0)
  head.writeUInt32BE(height, 4)
  head.set([8, 2, 0, 0, 0], 8)
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', head), chunk('IDAT', zlib.deflateSync(Buffer.concat(rows))), chunk('IEND', Buffer.alloc(0))])
}
function ensureScenes(out) {
  for (let n = 0; n < SCENES; n++) {
    if (!fs.existsSync(out(`scene-${n}.png`))) fs.writeFileSync(out(`scene-${n}.png`), scenePng(640, 360, n))
    if (!fs.existsSync(out(`scene-cover-${n}.png`))) fs.writeFileSync(out(`scene-cover-${n}.png`), scenePng(300, 300, n + 3))
    if (!fs.existsSync(out(`scene-${n}.mp4`)))
      ffmpeg(['-loop', '1', '-framerate', '5', '-i', out(`scene-${n}.png`), '-t', '6', '-c:v', 'libvpx-vp9', '-b:v', '200k', '-deadline', 'realtime', '-cpu-used', '8', '-pix_fmt', 'yuv420p', out(`scene-${n}.mp4`)])
  }
  if (!fs.existsSync(out('scene-tall.png'))) fs.writeFileSync(out('scene-tall.png'), scenePng(360, 640, 1))
}
const sceneOf = (id) => [...id].reduce((sum, ch) => (sum * 31 + ch.charCodeAt(0)) % 9973, 7) % SCENES
export const lrc = ['[00:00.40]First line of the fixture lyrics', '[00:01.40]Second line arrives', '[00:02.40]Third line keeps the beat', '[00:03.20]Last line fades out'].join('\n')

/** Creates the fixture files once per run (idempotent). */
export function ensureMedia() {
  fs.mkdirSync(mediaDir, {recursive: true})
  fs.writeFileSync(path.join(mediaDir, '.gitignore'), '*\n!.gitignore\n')
  const out = (name) => path.join(mediaDir, name)
  if (!fs.existsSync(out('tiny.mp4')))
    ffmpeg([
      '-f', 'lavfi', '-i', 'testsrc=size=320x180:rate=25:duration=6',
      '-f', 'lavfi', '-i', 'sine=frequency=440:duration=6',
      '-c:v', 'libvpx-vp9', '-b:v', '120k', '-deadline', 'realtime', '-cpu-used', '8', '-pix_fmt', 'yuv420p',
      '-c:a', 'libopus', '-strict', '-2', '-shortest', '-movflags', '+faststart', out('tiny.mp4'),
    ])
  if (!fs.existsSync(out('cover.jpg'))) ffmpeg(['-f', 'lavfi', '-i', 'testsrc2=size=300x300:rate=1', '-frames:v', '1', out('cover.jpg')])
  if (!fs.existsSync(out('tiny.mp3')))
    ffmpeg([
      '-f', 'lavfi', '-i', 'sine=frequency=330:duration=4', '-i', out('cover.jpg'),
      '-map', '0:a', '-map', '1:v', '-c:a', 'libmp3lame', '-b:a', '64k', '-c:v', 'mjpeg', '-id3v2_version', '3',
      '-metadata', 'title=Fixture tone', '-metadata:s:v', 'title=Album cover', out('tiny.mp3'),
    ])
  if (scenesOn) ensureScenes(out)
  fs.writeFileSync(out('tiny.lrc'), lrc)
  fs.writeFileSync(out('tiny.vtt'), 'WEBVTT\n\n00:00.500 --> 00:02.500\nHello from the fixture subtitles\n\n00:03.000 --> 00:05.000\nA second subtitle cue\n')
  if (!fs.existsSync(out('tiny.jpg'))) ffmpeg(['-f', 'lavfi', '-i', 'testsrc=size=640x360:rate=1', '-frames:v', '1', out('tiny.jpg')])
  if (!fs.existsSync(out('tall.jpg'))) ffmpeg(['-f', 'lavfi', '-i', 'testsrc=size=1080x1920:rate=1', '-frames:v', '1', out('tall.jpg')])
}

const types = {'.mp4': 'video/mp4', '.mp3': 'audio/mpeg', '.jpg': 'image/jpeg', '.png': 'image/png', '.vtt': 'text/vtt', '.lrc': 'text/plain'}

/** Vite plugin: /__media/{item|art|sub|job|video}/<id> → a fixture file, with HTTP Range support for seeking. */
export function mediaPlugin() {
  const handler = (request, response, next) => {
    const match = /^\/__media\/(item|art|sub|job|video)\/([^/?]+)(?:\/(\d+))?/.exec(request.url || '')
    if (!match) return next()
    const [, kind, rawId] = match
    const id = decodeURIComponent(rawId)
    // Fixture ids: a-* audio, v-* video, i-* image; the market photos are tall portraits.
    let file = 'tiny.jpg'
    if (kind === 'item' || kind === 'job') file = id.startsWith('a-') ? 'tiny.mp3' : id.startsWith('v-') ? 'tiny.mp4' : id.startsWith('i-market-') ? 'tall.jpg' : 'tiny.jpg'
    else if (kind === 'art') file = id.startsWith('v-') ? 'tiny.jpg' : 'cover.jpg'
    else if (kind === 'sub') file = 'tiny.vtt'
    else if (kind === 'video') file = 'tiny.mp4'
    if (scenesOn) {
      if (file === 'tiny.jpg') file = `scene-${sceneOf(id)}.png`
      else if (file === 'cover.jpg') file = `scene-cover-${sceneOf(id)}.png`
      else if (file === 'tiny.mp4') file = `scene-${sceneOf(id)}.mp4`
      else if (file === 'tall.jpg') file = 'scene-tall.png'
    }
    const full = path.join(mediaDir, file)
    if (!fs.existsSync(full)) {
      response.statusCode = 404
      return response.end('missing fixture')
    }
    const size = fs.statSync(full).size
    const type = types[path.extname(file)] || 'application/octet-stream'
    response.setHeader('Content-Type', type)
    response.setHeader('Accept-Ranges', 'bytes')
    response.setHeader('Access-Control-Allow-Origin', '*')
    const range = /bytes=(\d*)-(\d*)/.exec(request.headers.range || '')
    if (range) {
      const start = range[1] ? Number(range[1]) : 0
      const end = range[2] ? Math.min(Number(range[2]), size - 1) : size - 1
      if (start >= size) {
        response.statusCode = 416
        response.setHeader('Content-Range', `bytes */${size}`)
        return response.end()
      }
      response.statusCode = 206
      response.setHeader('Content-Range', `bytes ${start}-${end}/${size}`)
      response.setHeader('Content-Length', end - start + 1)
      return fs.createReadStream(full, {start, end}).pipe(response)
    }
    response.setHeader('Content-Length', size)
    fs.createReadStream(full).pipe(response)
  }
  return {
    name: 'keepfin-fixture-media',
    configureServer: (server) => void server.middlewares.use(handler),
    configurePreviewServer: (server) => void server.middlewares.use(handler),
  }
}
