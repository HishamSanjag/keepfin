// Embedded tags (music-metadata), sidecar discovery and lyrics for library items.
import fs from 'node:fs/promises'
import path from 'node:path'
import {parseFile, type IAudioMetadata} from 'music-metadata'
import {isVideoCompanion, videoCompanionStem} from '../../shared/core/copies.js'
import {cleanIsrc, extensionOf, mediaKindOf, parseLrc, subtitleExtensions, type Lyrics} from '../../shared/library.js'

export type Tags = {
  title?: string
  artist?: string
  album?: string
  albumArtist?: string
  track?: number
  disc?: number
  year?: number
  genre?: string
  /** The recording's ISRC when the file carries one (ID3 TSRC, MP4 ISRC atom, Vorbis ISRC). */
  isrc?: string
  duration?: number
  picture?: {data: Uint8Array; format: string}
  hasLyrics: boolean
  /** Gain in dB toward the -14 LUFS target, attenuation only, from ReplayGain tags. */
  gain?: number
}

const text = (value: string | undefined) => value?.replace(/\0/g, '').replace(/\s+/g, ' ').trim() || undefined
const whole = (value: number | null | undefined) =>
  typeof value === 'number' && Number.isFinite(value) && value > 0 ? Math.floor(value) : undefined

/** ReplayGain aims at -18 LUFS; the library target is -14, so +4 dB, and only ever quieter. */
export function gainFromReplayGain(db: number | undefined): number | undefined {
  if (db === undefined || !Number.isFinite(db)) return
  return Math.min(0, Math.round((db + 4) * 10) / 10)
}

export async function readTags(file: string, wantPicture: boolean): Promise<Tags> {
  let meta: IAudioMetadata
  try {
    meta = await parseFile(file, {duration: true, skipCovers: !wantPicture})
  } catch {
    return {hasLyrics: false}
  }
  const {common, format} = meta
  const picture = common.picture?.find((item) => item.data.length > 0 && /^image\/(jpe?g|png|webp)$/i.test(item.format))
  return {
    title: text(common.title),
    artist: text(common.artist) ?? text(common.artists?.join(', ')),
    album: text(common.album),
    albumArtist: text(common.albumartist),
    track: whole(common.track?.no),
    disc: whole(common.disk?.no),
    year: whole(common.year),
    genre: text(common.genre?.[0]),
    isrc: cleanIsrc(common.isrc?.[0]),
    duration: format.duration && format.duration > 0 ? Math.round(format.duration * 100) / 100 : undefined,
    picture: wantPicture && picture ? {data: picture.data, format: picture.format.toLowerCase()} : undefined,
    hasLyrics: !!common.lyrics?.some((entry) => entry.text?.trim() || entry.syncText?.length),
    gain: gainFromReplayGain(common.replaygain_track_gain?.dB),
  }
}

/** Embedded lyrics (synchronised when the tag carries timestamps). */
export async function readEmbeddedLyrics(file: string): Promise<Lyrics | undefined> {
  let meta: IAudioMetadata
  try {
    meta = await parseFile(file, {skipCovers: true})
  } catch {
    return
  }
  const tags = meta.common.lyrics ?? []
  const synced = tags.find((entry) => entry.syncText?.length)
  if (synced)
    return {
      synced: true,
      lines: synced.syncText
        .map((line) => ({time: Math.max(0, (line.timestamp ?? 0) / 1000), text: line.text.trim()}))
        .sort((a, b) => a.time - b.time),
    }
  const plain = tags.find((entry) => entry.text?.trim())
  if (!plain) return
  const parsed = parseLrc(plain.text!)
  if (parsed.synced) return parsed
  return {synced: false, lines: plain.text!.split(/\r?\n/).map((line) => ({text: line.trim()}))}
}

export type Sidecars = {
  /** Subtitle files, labelled by language or extension, in a stable order. */
  subtitles: {path: string; label: string}[]
  lrc?: string
  /** yt-dlp metadata written next to the media. */
  info?: string
  /** A song's music video: `<stem>.video.<ext>`. */
  video?: string
}

const subtitleSet = new Set<string>(subtitleExtensions)

/** Files that belong to `file`: same stem, optionally with a language tag (`Title.en.vtt`). */
export function sidecarsFrom(file: string, names: string[]): Sidecars {
  const dir = path.dirname(file)
  const stem = path.basename(file, path.extname(file))
  const lower = stem.toLowerCase()
  const result: Sidecars = {subtitles: []}
  for (const name of [...names].sort()) {
    const ext = extensionOf(name)
    const base = name.slice(0, name.length - ext.length - 1)
    if (name === path.basename(file)) continue
    if (isVideoCompanion('.video.' + ext)) {
      if (base.toLowerCase() === lower + '.video') result.video = path.join(dir, name)
      continue
    }
    const matches = base.toLowerCase() === lower || base.toLowerCase().startsWith(lower + '.')
    if (!matches) continue
    const language = base.length > stem.length ? base.slice(stem.length + 1) : ''
    if (subtitleSet.has(ext)) {
      const label = language || ext.toUpperCase()
      result.subtitles.push({path: path.join(dir, name), label: result.subtitles.some((item) => item.label === label) ? `${label} (${ext})` : label})
    } else if (ext === 'lrc' && !language) result.lrc = path.join(dir, name)
    else if (name.toLowerCase() === `${lower}.info.json`) result.info = path.join(dir, name)
  }
  return result
}

/**
 * The names (lowercase) in [names], one folder's listing, that are a song's music video: `Song.video.mp4` with an audio file
 * of the stem `Song` beside it. They are not library items of their own; the song carries them.
 */
export function musicVideoNames(names: readonly string[]): Set<string> {
  const stems = new Set<string>()
  const candidates: {name: string; stem: string}[] = []
  for (const name of names) {
    const stem = videoCompanionStem(name)
    if (stem !== null) candidates.push({name, stem: stem.toLowerCase()})
    else if (mediaKindOf(name) === 'audio') stems.add(path.parse(name).name.toLowerCase())
  }
  return new Set(candidates.filter((c) => stems.has(c.stem)).map((c) => c.name.toLowerCase()))
}

/** True when [file] is a song's music video (see [musicVideoNames]); reads the folder only for a name shaped like one. */
export async function isMusicVideoFile(file: string): Promise<boolean> {
  const name = path.basename(file)
  if (videoCompanionStem(name) === null) return false
  try {
    return musicVideoNames(await fs.readdir(path.dirname(file))).has(name.toLowerCase())
  } catch {
    return false
  }
}

export async function findSidecars(file: string): Promise<Sidecars> {
  try {
    return sidecarsFrom(file, await fs.readdir(path.dirname(file)))
  } catch {
    return {subtitles: []}
  }
}

/** Chapters from a yt-dlp info.json sidecar. */
export async function readInfoChapters(info: string): Promise<{start: number; title: string}[] | undefined> {
  try {
    if ((await fs.stat(info)).size > 20_000_000) return
    const data = JSON.parse(await fs.readFile(info, 'utf8')) as {chapters?: {start_time?: unknown; title?: unknown}[]}
    const chapters = (data.chapters ?? [])
      .filter((chapter) => typeof chapter.start_time === 'number' && Number.isFinite(chapter.start_time))
      .map((chapter, index) => ({
        start: chapter.start_time as number,
        title: typeof chapter.title === 'string' && chapter.title.trim() ? chapter.title.trim().slice(0, 200) : `Chapter ${index + 1}`,
      }))
    return chapters.length ? chapters : undefined
  } catch {
    return
  }
}
