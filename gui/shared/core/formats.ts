// Adapter between the shared core (core/, Kotlin Multiplatform; built to build/core-js by `npm run core:build`)
// and the format, size and title functions the desktop code already uses. This is the only file that imports the
// core's formats package: gui/shared/formats.ts and title.ts re-export from here, so call sites keep their imports.
//
// The core reads the info JSON (passed as text, parsed once in common code) and decides the options, sizes, codec
// names, subtitle tracks, music metadata, thumbnail and clean title. It makes no requests.
import {
  formatsAudioCodec,
  formatsHeightLabel,
  formatsMediaInfo,
  formatsSize,
  formatsSubtitles,
  formatsThumbnail,
  formatsUnavailable,
  formatsUnavailableLabel,
  formatsVideoCodec,
  titleClean,
} from '../../../build/core-js/keepfin-core.mjs'
import type {AudioOption, MusicMetadata, SubtitleTrack, VideoCodec, VideoOption, AudioCodec} from '../contracts.js'

export type TitleKind = 'video' | 'audio' | 'post'

export const TITLE_MAX = 100

/** Short display title for a media item or collection entry (see the rule in fixtures/engine/formats/SCHEMA.md). */
export const cleanTitle = (raw: string | undefined, uploader: string | undefined, provider: string, kind: TitleKind = 'video'): string =>
  titleClean(raw ?? null, uploader ?? null, provider, kind)

export const videoCodec = (vcodec: unknown): VideoCodec =>
  formatsVideoCodec(typeof vcodec === 'string' ? vcodec : null) as VideoCodec

export const audioCodec = (acodec: unknown): AudioCodec =>
  formatsAudioCodec(typeof acodec === 'string' ? acodec : null) as AudioCodec

/** Size estimate: filesize → filesize_approx → tbr × duration / 8 (kbit/s). */
export function estimateSize(format: object, duration?: number): {size?: number; approx: boolean} {
  const sized = formatsSize(JSON.stringify(format), duration ?? null)
  return sized.size == null ? {approx: sized.approx} : {size: sized.size, approx: sized.approx}
}

export function subtitleTracks(info: object): SubtitleTrack[] {
  return Array.from(formatsSubtitles(JSON.stringify(info)), (t) => ({lang: t.lang, name: t.name ?? undefined, auto: t.auto}))
}

export function bestThumbnail(info: object): string | undefined {
  return formatsThumbnail(JSON.stringify(info)) ?? undefined
}

/** Everything the core decides about one inspected item (the desktop-only parts, engine and tags, are added by the caller). */
export type CoreMedia = {
  url: string
  webpageUrl?: string
  title: string
  uploader?: string
  duration?: number
  thumbnail?: string
  extractor?: string
  provider: string
  isLive?: boolean
  music?: MusicMetadata
  video: VideoOption[]
  audio: AudioOption[]
  subtitles: SubtitleTrack[]
  chapters: number
  playlistUrl?: string
  playlistTitle?: string
}

const opt = <T>(value: T | null | undefined): T | undefined => value ?? undefined

/** Converts a single-item yt-dlp info dict into the options and facts the renderer shows. Throws when `info` is not an object. */
export function mediaFromInfo(info: object, url: string): CoreMedia {
  if (!info || typeof info !== 'object') throw new Error('The engine returned no media information.')
  const m = formatsMediaInfo(url, JSON.stringify(info), 'auto', false)
  const music = m.music
  return {
    url: m.url,
    webpageUrl: opt(m.webpageUrl),
    title: m.title,
    uploader: opt(m.uploader),
    duration: opt(m.duration),
    thumbnail: opt(m.thumbnail),
    extractor: opt(m.extractor),
    provider: m.provider,
    isLive: m.isLive || undefined,
    music: music
      ? {artist: opt(music.artist), album: opt(music.album), track: opt(music.track), releaseYear: opt(music.releaseYear)}
      : undefined,
    video: Array.from(m.video, (v) => ({
      id: v.id,
      height: v.height,
      width: opt(v.width),
      fps: opt(v.fps),
      hdr: v.hdr,
      codec: v.codec as VideoCodec,
      ext: v.ext,
      formatId: v.formatId,
      hasAudio: v.hasAudio,
      size: opt(v.size),
      sizeApprox: v.sizeApprox,
      tbr: opt(v.tbr),
    })),
    audio: Array.from(m.audio, (a) => ({
      id: a.id,
      formatId: a.formatId,
      codec: a.codec as AudioCodec,
      ext: a.ext,
      abr: opt(a.abr),
      size: opt(a.size),
      sizeApprox: a.sizeApprox,
      language: opt(a.language),
      note: opt(a.note),
    })),
    subtitles: Array.from(m.subtitles, (t) => ({lang: t.lang, name: opt(t.name), auto: t.auto})),
    chapters: m.chapters,
    playlistUrl: opt(m.playlistUrl),
    playlistTitle: opt(m.playlistTitle),
  }
}

/** Why a playlist entry cannot be downloaded (private, deleted, or gone from YouTube), or undefined. Decided by the core. */
export const unavailableReason = (title: string | undefined, uploader: string | undefined, duration: number | undefined, youtube: boolean): string | undefined =>
  formatsUnavailable(title ?? null, uploader ?? null, duration ?? null, youtube) ?? undefined

/** The label shown in place of an unavailable entry's title. */
export const unavailableLabel: string = formatsUnavailableLabel()

/** The name of a height: 720p, or Original for a direct file with none to read. */
export const heightLabel = (height: number): string => formatsHeightLabel(height)
