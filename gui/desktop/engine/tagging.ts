// Writes the source service's metadata and album art over a matched recording.
import fs from 'node:fs/promises'
import path from 'node:path'
import type {SourceTrack} from './music.js'
import {renameWithRetry} from '../fsutil.js'
import {readEmbeddedLyrics} from '../library/tags.js'
import {run} from './process.js'

const imageTypes = new Set(['image/jpeg', 'image/png', 'image/webp'])
export const MAX_COVER_BYTES = 8_000_000
export const COVER_TIMEOUT_MS = 10_000
/** Containers where an attached picture is reliable with stream copy. */
const coverContainers = new Set(['.mp3', '.m4a', '.flac'])

/** Downloads album art: https only, bounded in time and size, JPEG/PNG/WebP only. */
export async function fetchCover(url: string | undefined, fetcher: typeof fetch = fetch, signal?: AbortSignal) {
  if (!url || url.length > 4096) return
  const source = new URL(url)
  if (source.protocol !== 'https:' || source.username || source.password) return
  const timeout = AbortSignal.timeout(COVER_TIMEOUT_MS)
  const response = await fetcher(url, {
    signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
    headers: {Accept: 'image/jpeg,image/png,image/webp'},
  })
  const mime = response.headers.get('content-type')?.split(';')[0].trim().toLowerCase() || ''
  if (!response.ok || !imageTypes.has(mime) || Number(response.headers.get('content-length')) > MAX_COVER_BYTES) {
    await response.body?.cancel()
    return
  }
  const reader = response.body?.getReader()
  if (!reader) return
  const chunks: Buffer[] = []
  let size = 0
  try {
    while (true) {
      const result = await reader.read()
      if (result.done) break
      size += result.value.byteLength
      if (size > MAX_COVER_BYTES) {
        await reader.cancel()
        return
      }
      chunks.push(Buffer.from(result.value))
    }
  } finally {
    reader.releaseLock()
  }
  return size ? Buffer.concat(chunks) : undefined
}

/** Centre-crops any image to a square JPEG of at most 1200 px. Returns false on failure. */
export async function squareJpeg(ffmpeg: string, input: string, output: string, signal?: AbortSignal) {
  const result = await run(
    ffmpeg,
    [
      '-hide_banner', '-nostdin', '-loglevel', 'error', '-y', '-i', input,
      '-vf', 'crop=min(iw\\,ih):min(iw\\,ih),scale=w=min(1200\\,iw):h=-2:flags=lanczos',
      '-frames:v', '1', '-q:v', '2', output,
    ],
    {signal, timeoutMs: 30_000},
  )
  return result.code === 0
}

export type TagOptions = {fetch?: typeof fetch; signal?: AbortSignal}

/**
 * Applies title/artist/album/date and, where the container supports it, the source's
 * square cover. Any failure leaves the original file untouched.
 */
export async function tagFile(ffmpeg: string, file: string, track: SourceTrack, options: TagOptions = {}) {
  const ext = path.extname(file).toLowerCase()
  const base = file.slice(0, -ext.length)
  const temp = base + '.tagging' + ext
  const coverTemp = base + '.cover.jpg'
  const rawTemp = base + '.cover.raw'
  let cover: string | undefined
  if (coverContainers.has(ext)) {
    try {
      const bytes = await fetchCover(track.cover, options.fetch, options.signal)
      if (bytes) {
        await fs.writeFile(rawTemp, bytes)
        if (await squareJpeg(ffmpeg, rawTemp, coverTemp, options.signal)) cover = coverTemp
      }
    } catch {
      cover = undefined
    }
  }
  try {
    const metadata: [string, string | undefined][] = [
      ['title', track.title],
      ['artist', track.artists.join(', ')],
      ['album_artist', track.artists[0]],
      ['album', track.album],
      ['track', track.trackNumber ? String(track.trackNumber) : undefined],
      ['date', track.releaseYear ? String(track.releaseYear) : undefined],
      ['isrc', track.isrc],
    ]
    const args = ['-hide_banner', '-nostdin', '-loglevel', 'error', '-y', '-i', file]
    if (cover) args.push('-i', cover, '-map', '0:a', '-map', '1')
    else args.push('-map', '0')
    args.push('-c', 'copy', '-map_metadata', '0')
    for (const [key, value] of metadata)
      if (value) args.push('-metadata', `${key}=${value.replace(/[\r\n]+/g, ' ').slice(0, 500)}`)
    if (cover) {
      args.push('-metadata:s:v', 'title=Album cover', '-metadata:s:v', 'comment=Cover (front)', '-disposition:v', 'attached_pic')
    }
    if (ext === '.mp3') args.push('-id3v2_version', '3')
    args.push(temp)
    let result = await run(ffmpeg, args, {signal: options.signal, timeoutMs: 120_000})
    if (result.code !== 0 && cover) {
      // The picture is optional: retry with metadata only.
      await fs.rm(temp, {force: true})
      return await tagFile(ffmpeg, file, {...track, cover: undefined}, options)
    }
    if (result.code !== 0) {
      await fs.rm(temp, {force: true})
      return false
    }
    await fs.rename(temp, file)
    return !!cover
  } finally {
    await Promise.all([rawTemp, coverTemp, temp].map((name) => fs.rm(name, {force: true}).catch(() => {})))
  }
}

/**
 * Containers whose lyrics tag survives a stream copy and is read back by the library (MP4 ©lyr, Vorbis LYRICS). MP3 is not
 * here: the bundled encoder writes a custom TXXX frame instead of USLT, which players ignore, so MP3 gets a .lrc file.
 */
const lyricsContainers = new Set(['.m4a', '.opus', '.ogg', '.flac'])

/** Whether the rewritten song kept its length (at least 90% of the original's bytes) and the library reads its lyrics back. */
export async function lyricsKept(original: string, written: string) {
  const [before, after] = await Promise.all([fs.stat(original), fs.stat(written)])
  return after.size >= before.size * 0.9 && !!(await readEmbeddedLyrics(written))
}

/**
 * Saves lyrics (LRC text keeps its timestamps) inside the song file. Formats that can't hold them, or a rewrite that did not check out
 * (the length or the tag did not survive), get a `.lrc` file with the same name instead (an earlier one is replaced), which the library
 * also reads. Returns where they went, or undefined.
 */
export async function writeLyrics(ffmpeg: string | undefined, file: string, text: string, signal?: AbortSignal): Promise<'embedded' | 'sidecar' | undefined> {
  const ext = path.extname(file).toLowerCase()
  const base = file.slice(0, -ext.length)
  if (ffmpeg && lyricsContainers.has(ext)) {
    const temp = base + '.tagging' + ext
    try {
      const args = ['-hide_banner', '-nostdin', '-loglevel', 'error', '-y', '-i', file, '-map', '0', '-c', 'copy', '-map_metadata', '0', '-metadata', `lyrics=${text}`]
      args.push(temp)
      const result = await run(ffmpeg, args, {signal, timeoutMs: 120_000})
      if (result.code === 0 && (await lyricsKept(file, temp))) {
        await renameWithRetry(temp, file)
        return 'embedded'
      }
    } catch {
      // fall through to the sidecar
    } finally {
      await fs.rm(temp, {force: true}).catch(() => {})
    }
  }
  if (signal?.aborted) return undefined
  try {
    await fs.writeFile(base + '.lrc', text.endsWith('\n') ? text : text + '\n', {flag: 'w'})
    return 'sidecar'
  } catch {
    return undefined
  }
}
