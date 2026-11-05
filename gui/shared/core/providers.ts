// Adapter between the shared core (core/, Kotlin Multiplatform; built to build/core-js by `npm run core:build`)
// and the provider, routing and naming functions the desktop code already uses. This is the only file that imports
// the core's providers package: gui/shared/providers.ts, output.ts, contracts.ts and library.ts re-export from here,
// so call sites keep their imports.
//
// The core decides; it makes no requests and reads no clock. URLs are parsed by the core's own WHATWG-compatible
// parser, so desktop and Android read every link the same way.
import {
  JsPathJob,
  namingChannelArtist,
  namingIsoDate,
  namingStem,
  namingSubfolders,
  providersFolder,
  providersIsCollection,
  providersIsGallery,
  providersIsRadioMix,
  providersIsShortMusicLink,
  providersIsYouTube,
  providersIsYouTubeMusic,
  providersMusicLink,
  providersMusicLinkUrl,
  providersMusicService,
  providersName,
  providersPlaylistUrl,
  providersPrefersAudio,
  providersStartsAsVideo,
  providersSafeName,
  providersValidateUrl,
  providersVideoId,
  urlParse,
} from '../../../build/core-js/keepfin-core.mjs'

export type MusicService = 'spotify' | 'apple-music' | 'deezer'
export type MusicLinkType = 'track' | 'album' | 'playlist'

export type MusicLink = {
  service: MusicService
  type: MusicLinkType
  id: string
  /** Apple Music storefront (e.g. "us"); needed to rebuild page URLs. */
  storefront?: string
}

/** Human provider name; falls back to the download engine's extractor key, then the host. */
export const providerName = (url: string, extractor?: string): string => providersName(url, extractor ?? null)

/** Output sub-folder used when organization is 'platform'. Always safe as a folder name. */
export const platformFolder = (url: string): string => providersFolder(url)

/** Hosts whose posts are mostly images: route to the gallery engine first. */
export const isGalleryHost = (url: string): boolean => providersIsGallery(url)

/** Short links that must be expanded (by following redirects) before parsing. */
export const isShortMusicLink = (url: string): boolean => providersIsShortMusicLink(url)

/** Parses Spotify, Apple Music and Deezer links. Returns undefined for anything else. */
export function musicLink(url: string): MusicLink | undefined {
  const link = providersMusicLink(url)
  if (!link) return undefined
  const result: MusicLink = {service: link.service as MusicService, type: link.type as MusicLinkType, id: link.id}
  if (link.storefront != null) result.storefront = link.storefront
  return result
}

/** `spotify`, `apple-music` or `deezer` for links the music resolver handles (including their short links); never YouTube Music. */
export const musicService = (url: string): MusicService | undefined => (providersMusicService(url) as MusicService | null) ?? undefined

/** Canonical public URL for a parsed music link. */
export const musicLinkUrl = (link: MusicLink): string => providersMusicLinkUrl(link.service, link.type, link.id, link.storefront ?? null)

/**
 * Heuristic for links that are certainly collections, so inspection can use a cheap flat probe instead of a full
 * extraction. Single items inside a playlist (watch?v=…&list=…) are NOT collections; they inspect as media with
 * playlist context.
 */
export const isCollectionUrl = (url: string): boolean => providersIsCollection(url)

/** A radio mix list (RD + seed, RDMM, RDAMVM...), not a curated RDCLAK/RDAMPL YouTube Music playlist. */
export const isRadioMix = (list: string): boolean => providersIsRadioMix(list)

/** Playlist context for single items opened from inside a playlist. */
export function playlistContext(url: string): {url: string} | undefined {
  const playlist = providersPlaylistUrl(url)
  return playlist == null ? undefined : {url: playlist}
}

/** True for any YouTube or YouTube Music link (watch, short, embed, music). */
export const isYouTube = (url: string | undefined): boolean => providersIsYouTube(url ?? null)

/** True for music.youtube.com links of any shape (watch, playlist, browse, radio mixes). */
export const isYouTubeMusic = (url: string | undefined): boolean => providersIsYouTubeMusic(url ?? null)

/**
 * Music links default to the Audio tab and audio presets. Spotify/Apple Music/Deezer always do (they are matched,
 * never downloaded as video); YouTube Music follows the user's setting.
 */
export const startsAsVideo = (url: string | undefined, youtubeMusic: 'audio' | 'video' = 'audio'): boolean =>
  providersStartsAsVideo(url ?? null, youtubeMusic === 'audio')
export const prefersAudio = (url: string | undefined, youtubeMusic: 'audio' | 'video' = 'audio'): boolean =>
  providersPrefersAudio(url ?? null, youtubeMusic === 'audio')

/** The 11-character video id of a YouTube / YouTube Music link (watch, youtu.be, shorts, embed, live). */
export const youTubeVideoId = (url: string): string | undefined => providersVideoId(url) ?? undefined

/** A name safe as a file or folder name (at most 80 characters unless `max` says otherwise). */
export const safeName = (value: string, max?: number): string => providersSafeName(value, max)

/** A checked link: `href` is the normalised address; `problem` says why it was refused. */
export type UrlCheck = {href: string; problem?: undefined} | {href?: undefined; problem: 'tooLong' | 'notALink' | 'unsupported'}

/** An absolute http(s) link without credentials, at most 4096 characters. */
export function checkUrl(value: string): UrlCheck {
  const check = providersValidateUrl(value)
  return check.href != null ? {href: check.href} : {problem: check.problem as 'tooLong' | 'notALink' | 'unsupported'}
}

/** The parts of a link as the core parses it (WHATWG rules); null when it is not an absolute URL. For tests and diagnostics. */
export function parseUrl(value: string) {
  const url = urlParse(value)
  if (!url) return null
  return {
    scheme: url.scheme, username: url.username, password: url.password, host: url.host ?? null, port: url.port ?? null,
    path: url.path, query: url.query ?? null, fragment: url.fragment ?? null, href: url.href,
  }
}

// -- naming -------------------------------------------------------------------------------------------------------

/** "Daft Punk - Topic" (YouTube's auto-generated artist channels) → "Daft Punk". */
export const channelArtist = (uploader: string | undefined): string | undefined => namingChannelArtist(uploader ?? null) ?? undefined

export type PathJob = {
  url: string
  title: string
  uploader?: string
  /** Milliseconds since the epoch; the date in `date-title` names is its UTC day. */
  createdAt: number
  group?: {title: string}
  music?: {artist?: string; album?: string; track?: string}
}

const toPathJob = (job: PathJob) =>
  new JsPathJob(
    job.url, job.title, job.uploader ?? null, job.createdAt, job.group?.title ?? null,
    job.music?.artist ?? null, job.music?.album ?? null, job.music?.track ?? null,
  )

/** Subfolder segments inside a space for a layout. Pure; the caller joins them to the space folder. */
export const outputSubfolders = (layout: string, job: PathJob): string[] => [...namingSubfolders(layout, toPathJob(job))]

/** File name stem (no extension) for a job in a space with this naming style. */
export const outputStem = (naming: string, job: PathJob): string => namingStem(naming, toPathJob(job))

/** `YYYY-MM-DD` (UTC) of a time in milliseconds. */
export const isoDate = (millis: number): string => namingIsoDate(millis)
