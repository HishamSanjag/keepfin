import {test} from 'node:test'
import assert from 'node:assert/strict'
import {createServer, type Server} from 'node:http'
import {spawnSync} from 'node:child_process'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import ffmpegPath from 'ffmpeg-static'
import {fetchCover, lyricsKept, tagFile, writeLyrics} from './tagging.js'
import {readEmbeddedLyrics, readTags} from '../library/tags.js'

const ffmpeg = ffmpegPath as string

function ff(args: string[]) {
  return spawnSync(ffmpeg, ['-hide_banner', '-nostdin', ...args], {encoding: 'buffer'})
}
/** Picture stream size + average colour of the attached cover. */
function coverOf(file: string) {
  const info = ff(['-i', file]).stderr.toString()
  const match = /Video: (\w+)[^\n]*?, (\d{2,5})x(\d{2,5})/.exec(info)
  const raw = ff(['-v', 'error', '-i', file, '-map', '0:v:0', '-vf', 'scale=1:1', '-frames:v', '1', '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'])
  return match && {codec: match[1], width: Number(match[2]), height: Number(match[3]), rgb: [...raw.stdout.subarray(0, 3)]}
}

async function fixture() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-tagging-'))
  const png = (name: string, size: string, color: string) => {
    const file = path.join(dir, name)
    const made = ff(['-y', '-f', 'lavfi', '-i', `color=c=${color}:s=${size}`, '-frames:v', '1', file])
    assert.equal(made.status, 0, made.stderr.toString())
    return file
  }
  const youtubeCover = png('youtube.jpg', '1280x720', 'blue')
  const sourceCover = png('source.png', '900x900', 'red')
  const wideCover = png('wide.jpg', '1600x900', 'lime')
  const mp3 = path.join(dir, 'song.mp3')
  const made = ff(['-y', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=2', '-i', youtubeCover, '-map', '0:a', '-map', '1', '-c:a', 'libmp3lame', '-c:v', 'copy', '-id3v2_version', '3', '-disposition:v', 'attached_pic', mp3])
  assert.equal(made.status, 0, made.stderr.toString())
  return {dir, mp3, sourceCover, wideCover}
}

async function serve(routes: Record<string, {type: string; body: Buffer | string; status?: number}>) {
  const server: Server = createServer((req, res) => {
    const route = routes[req.url ?? '']
    if (!route) return void res.writeHead(404).end()
    res.writeHead(route.status ?? 200, {'content-type': route.type}).end(route.body)
  })
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve))
  const port = (server.address() as {port: number}).port
  // The cover URL must be https; tests route it to the local plain-HTTP server.
  const fetcher = ((url: string, init?: RequestInit) =>
    fetch(String(url).replace('https://covers.test', `http://127.0.0.1:${port}`), init)) as typeof fetch
  return {fetcher, close: () => new Promise((resolve) => server.close(resolve))}
}

const track = {title: 'Real Title', artists: ['Real Artist', 'Guest'], album: 'Real Album', url: 'https://open.spotify.com/track/x', trackNumber: 3, releaseYear: 2020}

test('matched tracks get the source cover as a centred square JPEG plus source tags', async () => {
  const f = await fixture()
  const server = await serve({
    '/art.png': {type: 'image/png', body: await fs.readFile(f.sourceCover)},
    '/wide.jpg': {type: 'image/jpeg', body: await fs.readFile(f.wideCover)},
  })
  try {
    assert.deepEqual(coverOf(f.mp3)?.rgb && [coverOf(f.mp3)!.width, coverOf(f.mp3)!.height], [1280, 720])
    assert.equal(await tagFile(ffmpeg, f.mp3, {...track, cover: 'https://covers.test/art.png'}, {fetch: server.fetcher}), true)
    const cover = coverOf(f.mp3)!
    assert.deepEqual([cover.width, cover.height], [900, 900])
    assert.equal(cover.codec, 'mjpeg')
    assert.ok(cover.rgb[0] > 200 && cover.rgb[2] < 60, `expected the red source art, got ${cover.rgb}`)
    const tags = ff(['-i', f.mp3]).stderr.toString()
    assert.match(tags, /title\s*: Real Title/)
    assert.match(tags, /artist\s*: Real Artist, Guest/)
    assert.match(tags, /album\s*: Real Album/)
    assert.equal((tags.match(/Stream #0:\d: Video/g) ?? []).length, 1)

    // Wider art is cropped to a square and capped at 1200 px.
    await tagFile(ffmpeg, f.mp3, {...track, cover: 'https://covers.test/wide.jpg'}, {fetch: server.fetcher})
    const wide = coverOf(f.mp3)!
    assert.deepEqual([wide.width, wide.height], [900, 900])
    assert.ok(wide.rgb[1] > 200 && wide.rgb[0] < 100)
  } finally {
    await server.close()
    await fs.rm(f.dir, {recursive: true, force: true})
  }
})

test('cover failures keep the existing picture and still write tags', async () => {
  const f = await fixture()
  const server = await serve({
    '/missing.png': {type: 'image/png', body: 'nope', status: 404},
    '/html': {type: 'text/html', body: '<html></html>'},
    '/corrupt.png': {type: 'image/png', body: 'not really an image'},
  })
  try {
    for (const cover of ['https://covers.test/missing.png', 'https://covers.test/html', 'https://covers.test/corrupt.png', 'http://covers.test/art.png', undefined]) {
      await tagFile(ffmpeg, f.mp3, {...track, cover}, {fetch: server.fetcher})
      const kept = coverOf(f.mp3)!
      assert.deepEqual([kept.width, kept.height], [1280, 720], String(cover))
      assert.match(ff(['-i', f.mp3]).stderr.toString(), /title\s*: Real Title/)
    }
    assert.deepEqual((await fs.readdir(f.dir)).filter((name) => /tagging|cover\./.test(name)), [])
  } finally {
    await server.close()
    await fs.rm(f.dir, {recursive: true, force: true})
  }
})

test('cover downloads are https-only, image-only and size-bounded', async () => {
  const big = Buffer.alloc(9_000_000, 1)
  const server = await serve({
    '/ok.jpg': {type: 'image/jpeg', body: 'abc'},
    '/big.jpg': {type: 'image/jpeg', body: big},
    '/page': {type: 'text/html', body: 'hi'},
  })
  try {
    assert.equal((await fetchCover('https://covers.test/ok.jpg', server.fetcher))?.toString(), 'abc')
    assert.equal(await fetchCover('http://covers.test/ok.jpg', server.fetcher), undefined)
    assert.equal(await fetchCover('https://user:pw@covers.test/ok.jpg', server.fetcher), undefined)
    assert.equal(await fetchCover('https://covers.test/big.jpg', server.fetcher), undefined)
    assert.equal(await fetchCover('https://covers.test/page', server.fetcher), undefined)
    assert.equal(await fetchCover(undefined, server.fetcher), undefined)
  } finally {
    await server.close()
  }
})

test('lyrics are embedded in m4a and opus with their timestamps; mp3 and other formats get a .lrc file', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-lyrics-'))
  const lrc = '[00:00.50] First line\n[00:01.20] Second line'
  try {
    for (const [ext, codec] of [['.m4a', 'aac'], ['.opus', 'libopus']] as const) {
      const file = path.join(dir, 'song' + ext)
      const made = ff(['-y', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=2', '-c:a', codec, file])
      assert.equal(made.status, 0, made.stderr.toString())
      assert.equal(await writeLyrics(ffmpeg, file, lrc), 'embedded', ext)
      const lyrics = await readEmbeddedLyrics(file)
      assert.ok(lyrics, `${ext} keeps lyrics`)
      assert.equal(lyrics!.synced, true, `${ext} keeps timestamps`)
      assert.deepEqual(lyrics!.lines.map((line) => line.text), ['First line', 'Second line'], ext)
      assert.equal(ff(['-v', 'error', '-i', file, '-f', 'null', '-']).status, 0, `${ext} still plays`)
    }
    for (const [name, codec] of [['tune.mp3', 'libmp3lame'], ['tone.wav', 'pcm_s16le']] as const) {
      const file = path.join(dir, name)
      assert.equal(ff(['-y', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=1', '-c:a', codec, file]).status, 0)
      const before = await fs.readFile(file)
      assert.equal(await writeLyrics(ffmpeg, file, lrc), 'sidecar', name)
      assert.equal(await fs.readFile(file.slice(0, -path.extname(file).length) + '.lrc', 'utf8'), lrc + '\n')
      assert.deepEqual(await fs.readFile(file), before, `${name} itself is untouched`)
      const fresh = lrc.replace('First line', 'Other first line')
      assert.equal(await writeLyrics(ffmpeg, file, fresh), 'sidecar', 'an existing .lrc is replaced')
      assert.equal(await fs.readFile(file.slice(0, -path.extname(file).length) + '.lrc', 'utf8'), fresh + '\n')
    }
  } finally {
    await fs.rm(dir, {recursive: true, force: true})
  }
})

test('a rewritten song only replaces the original when it kept its length and its lyrics read back', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-lyrics-kept-'))
  try {
    const original = path.join(dir, 'song.m4a')
    assert.equal(ff(['-y', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=2', '-c:a', 'aac', original]).status, 0)
    const withLyrics = path.join(dir, 'with.m4a')
    assert.equal(ff(['-y', '-i', original, '-map', '0', '-c', 'copy', '-metadata', 'lyrics=[00:01.00] Hello', withLyrics]).status, 0)
    assert.equal(await lyricsKept(original, withLyrics), true)
    assert.equal(await lyricsKept(original, original), false, 'a copy without the lyrics tag is not kept')
    const cut = path.join(dir, 'cut.m4a')
    assert.equal(ff(['-y', '-i', withLyrics, '-t', '0.2', '-map', '0', '-c', 'copy', '-metadata', 'lyrics=[00:01.00] Hello', cut]).status, 0)
    assert.equal(await lyricsKept(original, cut), false, 'a much shorter copy is not kept')
  } finally {
    await fs.rm(dir, {recursive: true, force: true})
  }
})

// m4a is left out on purpose: the converter cannot write the iTunes ISRC atom, and rewriting MP4 boxes by hand risks the file
test('the recording code is written into mp3, opus and flac and read back by the library', async () => {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'keepfin-isrc-'))
  try {
    for (const [ext, codec] of [['.mp3', 'libmp3lame'], ['.opus', 'libopus'], ['.flac', 'flac']] as const) {
      const file = path.join(dir, 'song' + ext)
      assert.equal(ff(['-y', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=2', '-c:a', codec, file]).status, 0, ext)
      await tagFile(ffmpeg, file, {...track, isrc: 'GBDUW0000053'})
      assert.equal((await readTags(file, false)).isrc, 'GBDUW0000053', `${ext} keeps the ISRC`)
      assert.equal(ff(['-v', 'error', '-i', file, '-f', 'null', '-']).status, 0, `${ext} still plays`)
    }
    const plain = path.join(dir, 'plain.mp3')
    assert.equal(ff(['-y', '-f', 'lavfi', '-i', 'sine=frequency=440:duration=1', '-c:a', 'libmp3lame', plain]).status, 0)
    await tagFile(ffmpeg, plain, track)
    assert.equal((await readTags(plain, false)).isrc, undefined)
  } finally {
    await fs.rm(dir, {recursive: true, force: true})
  }
})