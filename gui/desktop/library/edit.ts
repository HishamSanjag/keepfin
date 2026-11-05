// Edit info: rewrites title, artist, album, track and cover into the file's tags with the bundled engine.
// The original is replaced atomically and only after the rewrite succeeded.
import fs from 'node:fs/promises'
import path from 'node:path'
import {extensionOf, taggable} from '../../shared/library.js'
import {run} from '../engine/process.js'
import {fetchCover} from '../engine/tagging.js'
import type {LibraryIndex} from './index.js'

export type EditInfo = {
  title?: string
  artist?: string
  album?: string
  albumArtist?: string
  track?: number
  disc?: number
  year?: number
  genre?: string
  /** Local image path or https URL. */
  cover?: string
}

const coverContainers = new Set(['mp3', 'm4a', 'flac'])
const imageExtensions = new Set(['jpg', 'jpeg', 'png', 'webp'])
const MAX_COVER = 8_000_000

const text = (value: unknown, name: string): string | undefined => {
  if (value === undefined) return
  if (typeof value !== 'string' || value.length > 500) throw new Error(`The ${name} is not valid.`)
  return value.replace(/[\r\n]+/g, ' ').trim()
}
const count = (value: unknown, name: string, max: number): number | undefined => {
  if (value === undefined) return
  if (typeof value !== 'number' || !Number.isInteger(value) || value < 0 || value > max) throw new Error(`The ${name} is not valid.`)
  return value
}

/** Validates renderer-supplied tag values. `undefined` leaves a tag alone; an empty string or 0 clears it. */
export function validateInfo(input: unknown): EditInfo {
  if (!input || typeof input !== 'object' || Array.isArray(input)) throw new Error('Nothing to change.')
  const raw = input as Record<string, unknown>
  const info: EditInfo = {
    title: text(raw.title, 'title'),
    artist: text(raw.artist, 'artist'),
    album: text(raw.album, 'album'),
    albumArtist: text(raw.albumArtist, 'album artist'),
    track: count(raw.track, 'track number', 9999),
    disc: count(raw.disc, 'disc number', 99),
    year: count(raw.year, 'year', 3000),
    genre: text(raw.genre, 'genre'),
    cover: text(raw.cover, 'cover'),
  }
  if (info.title === '') throw new Error('A title cannot be empty.')
  for (const key of Object.keys(info) as (keyof EditInfo)[]) if (info[key] === undefined) delete info[key]
  if (!Object.keys(info).length) throw new Error('Nothing to change.')
  return info
}

async function loadCover(source: string, signal?: AbortSignal): Promise<Buffer> {
  if (/^https:\/\//i.test(source)) {
    const bytes = await fetchCover(source, undefined, signal)
    if (!bytes) throw new Error('That cover could not be downloaded.')
    return bytes
  }
  if (!path.isAbsolute(source) || !imageExtensions.has(extensionOf(source))) throw new Error('Choose a JPEG, PNG or WebP image for the cover.')
  const stat = await fs.stat(source).catch(() => undefined)
  if (!stat?.isFile()) throw new Error('That cover image was not found.')
  if (stat.size > MAX_COVER) throw new Error('That cover image is too large.')
  return fs.readFile(source)
}

export async function editInfo(
  index: LibraryIndex,
  id: unknown,
  input: unknown,
  ffmpeg: string | undefined,
): Promise<void> {
  const entry = index.item(id)
  const info = validateInfo(input)
  if (!ffmpeg) throw new Error('The media processor is not available, so tags cannot be edited.')
  const file = entry.path
  const ext = extensionOf(file)
  if (!taggable.has(ext)) throw new Error('Tags cannot be edited for this file type.')
  if (info.cover && !coverContainers.has(ext)) throw new Error('Cover art can only be embedded in MP3, M4A and FLAC files.')
  if (entry.missing || !(await fs.stat(file).catch(() => undefined))?.isFile()) throw new Error('The file is no longer at its saved location.')
  const operation = index.beginOperation('edit', 1)
  operation.step(path.basename(file))
  const base = file.slice(0, -(ext.length + 1))
  const output = `${base}.kfedit.${ext}`
  const rawCover = `${base}.cover.raw`
  const jpegCover = `${base}.cover.jpg`
  try {
    let cover: Buffer | undefined
    if (info.cover) {
      const bytes = await loadCover(info.cover, operation.signal)
      await fs.writeFile(rawCover, bytes)
      const converted = await run(
        ffmpeg,
        ['-hide_banner', '-nostdin', '-loglevel', 'error', '-y', '-i', rawCover, '-vf', 'scale=w=min(1200\\,iw):h=-2:flags=lanczos', '-frames:v', '1', '-q:v', '2', jpegCover],
        {signal: operation.signal, timeoutMs: 30_000},
      )
      if (converted.code !== 0) throw new Error('That image could not be used as a cover.')
      cover = await fs.readFile(jpegCover)
    }
    const tags: [string, string | number | undefined][] = [
      ['title', info.title],
      ['artist', info.artist],
      ['album', info.album],
      ['album_artist', info.albumArtist],
      ['track', info.track === undefined ? undefined : info.track || ''],
      ['disc', info.disc === undefined ? undefined : info.disc || ''],
      ['date', info.year === undefined ? undefined : info.year || ''],
      ['genre', info.genre],
    ]
    const args = ['-hide_banner', '-nostdin', '-loglevel', 'error', '-y', '-i', file]
    if (cover) args.push('-i', jpegCover, '-map', '0:a', '-map', '1')
    else args.push('-map', '0')
    args.push('-c', 'copy', '-map_metadata', '0')
    for (const [key, value] of tags) if (value !== undefined) args.push('-metadata', `${key}=${value}`)
    if (cover) args.push('-metadata:s:v', 'title=Album cover', '-metadata:s:v', 'comment=Cover (front)', '-disposition:v', 'attached_pic')
    if (ext === 'mp3') args.push('-id3v2_version', '3')
    args.push(output)
    const result = await run(ffmpeg, args, {signal: operation.signal, timeoutMs: 5 * 60_000})
    if (result.code !== 0) throw new Error('The tags could not be written. The file was not changed.')
    await fs.rename(output, file)
    operation.advance()
    // Cleared tags must not be refilled from what the download knew.
    if (info.title !== undefined) entry.title = info.title
    if (info.artist !== undefined) entry.artist = info.artist || undefined
    if (info.album !== undefined) entry.album = info.album || undefined
    if (info.albumArtist !== undefined) entry.albumArtist = info.albumArtist || undefined
    if (info.track !== undefined) entry.track = info.track || undefined
    if (info.disc !== undefined) entry.disc = info.disc || undefined
    if (info.year !== undefined) entry.year = info.year || undefined
    if (info.genre !== undefined) entry.genre = info.genre || undefined
    if (cover) await index.setArt(entry.id, cover, 'image/jpeg')
    await index.refresh(entry.id)
  } finally {
    await Promise.all([output, rawCover, jpegCover].map((name) => fs.rm(name, {force: true}).catch(() => {})))
    operation.end()
  }
}
