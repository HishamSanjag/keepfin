// yt-dlp info JSON → MediaInfo. The decisions (options, sizes, codecs, subtitles, music metadata, thumbnail, clean
// title) live in the shared core (core/, via ./core/formats.ts); this file keeps the desktop-only parts: the engine
// field, the normalised source tags, and the labels the renderer shows.
import {
  formatBytes,
  type AudioCodec,
  type AudioOption,
  type MediaInfo,
  type VideoCodec,
  type VideoOption,
} from './contracts.js'
import {collectSourceTags} from './library.js'
import {mediaFromInfo} from './core/formats.js'

export {audioCodec, bestThumbnail, estimateSize, heightLabel, subtitleTracks, videoCodec} from './core/formats.js'
// Quality presets for the Downloads screen (Best / High / Balanced / Small), computed from the real options.

/** The subset of a yt-dlp format dict this module reads. */
export type RawFormat = {
  format_id?: unknown
  format_note?: unknown
  ext?: unknown
  protocol?: unknown
  vcodec?: unknown
  acodec?: unknown
  width?: unknown
  height?: unknown
  fps?: unknown
  dynamic_range?: unknown
  tbr?: unknown
  abr?: unknown
  vbr?: unknown
  filesize?: unknown
  filesize_approx?: unknown
  language?: unknown
  language_preference?: unknown
  url?: unknown
  fragments?: unknown
  http_headers?: unknown
}

export type RawInfo = {
  _type?: unknown
  id?: unknown
  title?: unknown
  uploader?: unknown
  channel?: unknown
  creator?: unknown
  duration?: unknown
  thumbnail?: unknown
  thumbnails?: unknown
  webpage_url?: unknown
  extractor_key?: unknown
  is_live?: unknown
  live_status?: unknown
  artist?: unknown
  artists?: unknown
  album?: unknown
  track?: unknown
  release_year?: unknown
  genre?: unknown
  genres?: unknown
  tags?: unknown
  categories?: unknown
  playlist_title?: unknown
  formats?: unknown
  subtitles?: unknown
  automatic_captions?: unknown
  chapters?: unknown
}

export const codecLabel: Record<VideoCodec | AudioCodec, string> = {
  h264: 'H.264',
  hevc: 'HEVC',
  vp9: 'VP9',
  av1: 'AV1',
  aac: 'AAC',
  opus: 'Opus',
  mp3: 'MP3',
  vorbis: 'Vorbis',
  flac: 'FLAC',
  other: '',
}

/** Genre (list or single), tags and categories, normalised; undefined when the source has none. */
export function sourceTags(info: RawInfo): string[] | undefined {
  const tags = collectSourceTags(info)
  return tags.length ? tags : undefined
}

/** Converts a single-item yt-dlp info dict into the renderer-facing MediaInfo. */
export function mediaInfoFromYtDlp(info: RawInfo, url: string): MediaInfo {
  const media = mediaFromInfo(info, url)
  return {
    url: media.url,
    webpageUrl: media.webpageUrl,
    title: media.title,
    uploader: media.uploader,
    duration: media.duration,
    thumbnail: media.thumbnail,
    extractor: media.extractor,
    provider: media.provider,
    engine: 'yt-dlp',
    isLive: media.isLive,
    music: media.music,
    tags: sourceTags(info),
    video: media.video,
    audio: media.audio,
    subtitles: media.subtitles,
    chapters: media.chapters,
    playlist: media.playlistUrl ? {url: media.playlistUrl, title: media.playlistTitle} : undefined,
  }
}

const sizeLabel = (size: number | undefined, approx: boolean) =>
  size ? `${approx ? '~' : ''}${formatBytes(size)}` : ''

/** "Opus 133 kbps · English · original · ~5 MB" */
export function audioOptionLabel(o: AudioOption): string {
  return [
    codecLabel[o.codec] || o.ext.toUpperCase(),
    o.abr ? `${o.abr} kbps` : '',
    o.language ?? '',
    o.note ?? '',
    sizeLabel(o.size, o.sizeApprox),
  ]
    .filter(Boolean)
    .join(' · ')
}
