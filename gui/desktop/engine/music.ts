// Music resolver. Spotify / Apple Music / Deezer audio is DRM-protected and is never
// downloaded from those services: their public metadata is read, each track is
// matched to a YouTube Music recording, and the downloaded audio is tagged with the
// source metadata.
import {
  albumPageRequest,
  albumSearchRequest,
  bestMusicVideo,
  musicVideoRequest,
  parseMusicVideos,
  type VideoHit,
  parseAlbumPageText,
  parseAlbumSearchText,
  parseYtMusicSearchText,
  songsApiRequest,
  type AlbumHit,
  type AlbumPage,
  type AlbumRequest,
  type MatchTrack,
} from '../../shared/core/music.js'
import {validateUrl} from '../../shared/contracts.js'
import {pickChannel} from '../../shared/core/home.js'
import type {MusicVideoFound} from '../../shared/library.js'
import {isShortMusicLink, isYouTubeMusic, musicLink, musicLinkUrl, youTubeVideoId, type MusicLink, type MusicService} from '../../shared/providers.js'

export type SourceTrack = {
  title: string
  artists: string[]
  durationMs?: number
  /** Canonical service URL of the track. */
  url: string
  album?: string
  cover?: string
  trackNumber?: number
  releaseYear?: number
  /** Tracks on the source album (album collections). */
  trackCount?: number
}

export type SourceCollection = {
  service: MusicService
  type: 'album' | 'playlist'
  title: string
  owner?: string
  cover?: string
  tracks: SourceTrack[]
  /** Total tracks when the service reports more than it exposes publicly. */
  total?: number
}

export type MusicSource = {kind: 'track'; track: SourceTrack} | {kind: 'collection'; collection: SourceCollection}

export const serviceNames: Record<MusicService, string> = {
  spotify: 'Spotify',
  'apple-music': 'Apple Music',
  deezer: 'Deezer',
}

const browserHeaders = {
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36',
  'Accept-Language': 'en-US,en;q=0.9',
}

const str = (value: unknown, max = 500) => (typeof value === 'string' && value.trim() ? value.trim().slice(0, max) : undefined)
const positive = (value: unknown) => (typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : undefined)
const httpsUrl = (value: unknown) => {
  const text = str(value, 4096)
  return text && /^https:\/\//.test(text) ? text : undefined
}
const year = (value: unknown) => {
  const match = typeof value === 'string' ? value.match(/^(\d{4})/) : undefined
  return match ? Number(match[1]) : undefined
}

// ---------------------------------------------------------------------------
// Fetching
// ---------------------------------------------------------------------------

async function getText(fetcher: typeof fetch, url: string, signal?: AbortSignal, maxBytes = 8_000_000) {
  const response = await fetcher(url, {
    headers: browserHeaders,
    signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(20_000)]) : AbortSignal.timeout(20_000),
  })
  if (response.status === 404) throw new Error('This music link was not found. It may be private or removed.')
  if (!response.ok) throw new Error(`The music service returned HTTP ${response.status}.`)
  const text = await response.text()
  if (text.length > maxBytes) throw new Error('The music page was unexpectedly large.')
  return {text, url: response.url}
}

/** spotify.link / spoti.fi / link.deezer.com → canonical service URL. */
export async function expandShortLink(url: string, fetcher: typeof fetch, signal?: AbortSignal): Promise<string> {
  if (!isShortMusicLink(url)) return url
  const {text, url: final} = await getText(fetcher, url, signal, 2_000_000)
  if (musicLink(final)) return final
  // Branch.io style pages embed the destination instead of redirecting.
  for (const match of text.matchAll(/https:\/\/(?:open\.spotify\.com|www\.deezer\.com|music\.apple\.com)\/[^\s"'<>\\]+/g)) {
    const candidate = match[0].replace(/&amp;/g, '&')
    if (musicLink(candidate)) return validateUrl(candidate)
  }
  throw new Error('Could not expand this short music link.')
}

export async function resolveMusic(url: string, fetcher: typeof fetch = fetch, signal?: AbortSignal): Promise<MusicSource> {
  const source = await resolveMusicPage(url, fetcher, signal)
  // Every track of an album knows how many tracks the album has, which tells editions apart.
  if (source.kind === 'collection' && source.collection.type === 'album') {
    const count = source.collection.total ?? source.collection.tracks.length
    for (const track of source.collection.tracks) track.trackCount ??= count
  }
  return source
}

async function resolveMusicPage(url: string, fetcher: typeof fetch, signal?: AbortSignal): Promise<MusicSource> {
  const expanded = await expandShortLink(url, fetcher, signal)
  const link = musicLink(expanded)
  if (!link) throw new Error('This music link type is not supported. Use a track, album or playlist link.')
  switch (link.service) {
    case 'spotify': {
      const {text} = await getText(fetcher, `https://open.spotify.com/embed/${link.type}/${link.id}`, signal)
      return parseSpotifyEmbed(text, link)
    }
    case 'deezer':
      return resolveDeezer(link, fetcher, signal)
    case 'apple-music': {
      const {text} = await getText(fetcher, musicLinkUrl(link), signal)
      return parseAppleMusic(text, link)
    }
  }
}

// ---------------------------------------------------------------------------
// Spotify (embed page __NEXT_DATA__)
// ---------------------------------------------------------------------------

type SpotifyImage = {url?: unknown; maxHeight?: unknown; height?: unknown}

function largestImage(images: unknown): string | undefined {
  if (!Array.isArray(images)) return
  const sorted = [...(images as SpotifyImage[])].sort(
    (a, b) => (positive(b.maxHeight) ?? positive(b.height) ?? 0) - (positive(a.maxHeight) ?? positive(a.height) ?? 0),
  )
  return sorted.map((image) => httpsUrl(image?.url)).find(Boolean)
}

function spotifyTrackUrl(uri: unknown): string | undefined {
  const id = typeof uri === 'string' ? uri.match(/^spotify:track:([A-Za-z0-9]{8,40})$/)?.[1] : undefined
  return id ? `https://open.spotify.com/track/${id}` : undefined
}

/** Spotify joins several artists in a track subtitle with ", ". */
const splitArtists = (value?: string) =>
  value
    ? value
        .split(/\s*,\s*/)
        .map((artist) => artist.trim())
        .filter(Boolean)
    : []

export function parseSpotifyEmbed(html: string, link: MusicLink): MusicSource {
  const json = html.match(/<script id="__NEXT_DATA__" type="application\/json">([\s\S]*?)<\/script>/)?.[1]
  if (!json) throw new Error('Spotify did not return track data. The link may be private or region-locked.')
  let entity: Record<string, unknown>
  try {
    entity = JSON.parse(json)?.props?.pageProps?.state?.data?.entity
  } catch {
    throw new Error('Spotify returned unreadable track data.')
  }
  if (!entity || typeof entity !== 'object') throw new Error('Spotify did not return track data.')
  const visual = (entity.visualIdentity as {image?: unknown})?.image
  const cover = largestImage(visual) ?? largestImage((entity.coverArt as {sources?: unknown})?.sources)
  if (link.type === 'track' || entity.type === 'track') {
    const artists = Array.isArray(entity.artists)
      ? (entity.artists as {name?: unknown}[]).map((a) => str(a?.name, 200)).filter((a): a is string => !!a)
      : splitArtists(str(entity.subtitle))
    const title = str(entity.title) ?? str(entity.name)
    if (!title) throw new Error('Spotify did not return a track title.')
    return {
      kind: 'track',
      track: {
        title,
        artists,
        durationMs: positive(entity.duration),
        url: `https://open.spotify.com/track/${link.id}`,
        cover,
        releaseYear: year((entity.releaseDate as {isoString?: unknown})?.isoString),
      },
    }
  }
  const title = str(entity.title) ?? str(entity.name) ?? 'Spotify collection'
  const owner = str(entity.subtitle, 200)
  const list = Array.isArray(entity.trackList) ? (entity.trackList as Record<string, unknown>[]) : []
  const tracks: SourceTrack[] = []
  list.forEach((item, index) => {
    const url = spotifyTrackUrl(item.uri)
    const name = str(item.title)
    if (!url || !name) return
    tracks.push({
      title: name,
      artists: splitArtists(str(item.subtitle)),
      durationMs: positive(item.duration),
      url,
      album: link.type === 'album' ? title : undefined,
      cover,
      trackNumber: link.type === 'album' ? index + 1 : undefined,
    })
  })
  if (!tracks.length) throw new Error('This Spotify collection has no public tracks.')
  return {
    kind: 'collection',
    collection: {
      service: 'spotify',
      type: link.type === 'album' ? 'album' : 'playlist',
      title,
      owner,
      cover,
      tracks,
      // The public embed lists at most 100 tracks.
      total: tracks.length >= 100 ? undefined : tracks.length,
    },
  }
}

// ---------------------------------------------------------------------------
// Deezer (public API)
// ---------------------------------------------------------------------------

type DeezerTrack = {
  id?: unknown
  title?: unknown
  duration?: unknown
  link?: unknown
  track_position?: unknown
  release_date?: unknown
  artist?: {name?: unknown}
  contributors?: {name?: unknown}[]
  album?: {title?: unknown; cover_xl?: unknown; cover_big?: unknown; release_date?: unknown}
}

export function deezerTrack(item: DeezerTrack, album?: {title?: string; cover?: string; year?: number}, position?: number): SourceTrack | undefined {
  const id = typeof item.id === 'number' || (typeof item.id === 'string' && /^\d+$/.test(item.id)) ? String(item.id) : undefined
  const title = str(item.title)
  if (!id || !title) return
  const contributors = Array.isArray(item.contributors)
    ? item.contributors.map((c) => str(c?.name, 200)).filter((c): c is string => !!c)
    : []
  const artist = str(item.artist?.name, 200)
  const artists = contributors.length ? contributors : artist ? [artist] : []
  const seconds = positive(item.duration)
  return {
    title,
    artists,
    durationMs: seconds ? seconds * 1000 : undefined,
    url: `https://www.deezer.com/track/${id}`,
    album: album?.title ?? str(item.album?.title),
    cover: album?.cover ?? httpsUrl(item.album?.cover_xl) ?? httpsUrl(item.album?.cover_big),
    trackNumber: positive(item.track_position) ?? position,
    releaseYear: album?.year ?? year(item.release_date) ?? year(item.album?.release_date),
  }
}

export function parseDeezer(data: Record<string, unknown>, link: MusicLink): MusicSource {
  const error = (data.error as {message?: unknown})?.message
  if (error) throw new Error(`Deezer: ${String(error).slice(0, 200)}`)
  if (link.type === 'track') {
    const track = deezerTrack(data as DeezerTrack)
    if (!track) throw new Error('Deezer did not return this track.')
    return {kind: 'track', track}
  }
  const title = str(data.title) ?? 'Deezer collection'
  const cover = httpsUrl(data.cover_xl) ?? httpsUrl(data.picture_xl) ?? httpsUrl(data.cover_big)
  const items = ((data.tracks as {data?: unknown})?.data ?? []) as DeezerTrack[]
  const albumInfo = link.type === 'album' ? {title, cover, year: year(data.release_date)} : undefined
  const tracks = (Array.isArray(items) ? items : [])
    .map((item, index) => deezerTrack(item, albumInfo, link.type === 'album' ? index + 1 : undefined))
    .filter((t): t is SourceTrack => !!t)
  if (!tracks.length) throw new Error('This Deezer collection has no public tracks.')
  return {
    kind: 'collection',
    collection: {
      service: 'deezer',
      type: link.type === 'album' ? 'album' : 'playlist',
      title,
      owner: str((data.artist as {name?: unknown})?.name, 200) ?? str((data.creator as {name?: unknown})?.name, 200),
      cover,
      tracks,
      total: positive(data.nb_tracks),
    },
  }
}

async function resolveDeezer(link: MusicLink, fetcher: typeof fetch, signal?: AbortSignal): Promise<MusicSource> {
  const get = async (path: string) => JSON.parse((await getText(fetcher, `https://api.deezer.com/${path}`, signal)).text) as Record<string, unknown>
  const data = await get(`${link.type}/${link.id}`)
  const source = parseDeezer(data, link)
  // Large playlists/albums are paged by the API (25–400 per response).
  if (source.kind === 'collection' && source.collection.total && source.collection.tracks.length < source.collection.total) {
    const limit = Math.min(source.collection.total, 1000)
    while (source.collection.tracks.length < limit) {
      const page = await get(`${link.type}/${link.id}/tracks?index=${source.collection.tracks.length}&limit=100`)
      const items = Array.isArray(page.data) ? (page.data as DeezerTrack[]) : []
      const albumInfo = link.type === 'album' ? {title: source.collection.title, cover: source.collection.cover} : undefined
      const more = items
        .map((item, i) => deezerTrack(item, albumInfo, link.type === 'album' ? source.collection.tracks.length + i + 1 : undefined))
        .filter((t): t is SourceTrack => !!t)
      if (!more.length) break
      source.collection.tracks.push(...more)
    }
  }
  return source
}

// ---------------------------------------------------------------------------
// Apple Music (serialized server data, JSON-LD fallback)
// ---------------------------------------------------------------------------

function isoDuration(value: unknown): number | undefined {
  const match = typeof value === 'string' ? value.match(/^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?$/) : undefined
  if (!match) return
  const ms = ((Number(match[1] ?? 0) * 60 + Number(match[2] ?? 0)) * 60 + Number(match[3] ?? 0)) * 1000
  return ms > 0 ? ms : undefined
}

function appleArtwork(template: unknown, size = 1000): string | undefined {
  const url = httpsUrl(template)
  return url?.replace('{w}', String(size)).replace('{h}', String(size)).replace('{f}', 'jpg')
}

const appleArtistSplit = (value?: string) =>
  value
    ? value
        .split(/\s*,\s*|\s+&\s+/)
        .map((a) => a.trim())
        .filter(Boolean)
    : []

export function parseAppleMusic(html: string, link: MusicLink): MusicSource {
  const ldText = html.match(/<script[^>]*type="application\/ld\+json"[^>]*>([\s\S]*?)<\/script>/)?.[1]
  let ld: Record<string, unknown> | undefined
  try {
    ld = ldText ? JSON.parse(ldText) : undefined
  } catch {}
  if (link.type === 'track') {
    const audio = (ld?.audio ?? ld) as Record<string, unknown> | undefined
    const title = str(audio?.name) ?? str(ld?.name)
    if (!title) throw new Error('Apple Music did not return this song. It may be unavailable in this region.')
    const byArtist = Array.isArray(audio?.byArtist) ? (audio!.byArtist as {name?: unknown}[]) : []
    const album = audio?.inAlbum as {name?: unknown; image?: unknown} | undefined
    return {
      kind: 'track',
      track: {
        title,
        artists: byArtist.map((a) => str(a?.name, 200)).filter((a): a is string => !!a),
        durationMs: isoDuration(audio?.duration) ?? isoDuration(ld?.timeRequired),
        url: str(audio?.url, 2048) && /^https:\/\/music\.apple\.com\//.test(String(audio?.url)) ? String(audio!.url) : musicLinkUrl(link),
        album: str(album?.name),
        cover: httpsUrl(ld?.image) ?? httpsUrl(album?.image),
        releaseYear: year(ld?.datePublished),
      },
    }
  }
  const serialized = html.match(/<script type="application\/json" id="serialized-server-data">([\s\S]*?)<\/script>/)?.[1]
  type Lockup = Record<string, unknown> & {title?: unknown}
  let sections: {id?: unknown; itemKind?: unknown; items?: Lockup[]}[] = []
  try {
    const data = serialized ? JSON.parse(serialized) : undefined
    sections = data?.data?.[0]?.data?.sections ?? []
  } catch {}
  const header = sections.find((s) => typeof s.itemKind === 'string' && /HeaderLockup/.test(s.itemKind))?.items?.[0]
  const lockups = sections.filter((s) => s.itemKind === 'trackLockup').flatMap((s) => s.items ?? [])
  const title = str(header?.title) ?? str(ld?.name) ?? 'Apple Music collection'
  const owner =
    str((header?.subtitleLinks as {title?: unknown}[] | undefined)?.[0]?.title, 200) ??
    str((ld?.byArtist as {name?: unknown}[] | undefined)?.[0]?.name, 200)
  const cover =
    appleArtwork((header?.artwork as {dictionary?: {url?: unknown}})?.dictionary?.url) ?? httpsUrl(ld?.image)
  let tracks: SourceTrack[] = lockups
    .map((item, index): SourceTrack | undefined => {
      const name = str(item.title)
      const url = str((item.contentDescriptor as {url?: unknown})?.url, 2048)
      if (!name || !url || !/^https:\/\/music\.apple\.com\//.test(url)) return
      return {
        title: name,
        artists: appleArtistSplit(str(item.artistName, 300)),
        durationMs: positive(item.duration),
        url,
        album: link.type === 'album' ? title : str((item.tertiaryLinks as {title?: unknown}[] | undefined)?.[0]?.title),
        cover: appleArtwork((item.artwork as {dictionary?: {url?: unknown}})?.dictionary?.url) ?? cover,
        trackNumber: link.type === 'album' ? positive(item.trackNumber) ?? index + 1 : undefined,
      }
    })
    .filter((t): t is SourceTrack => !!t)
  if (!tracks.length && ld) {
    const list = (ld.tracks ?? ld.track) as Record<string, unknown>[] | undefined
    const artists = ((ld.byArtist as {name?: unknown}[] | undefined) ?? []).map((a) => str(a?.name, 200)).filter((a): a is string => !!a)
    tracks = (Array.isArray(list) ? list : [])
      .map((item, index): SourceTrack | undefined => {
        const name = str(item.name)
        const url = str(item.url, 2048)
        if (!name || !url || !/^https:\/\/music\.apple\.com\//.test(url)) return
        return {
          title: name,
          artists,
          durationMs: isoDuration(item.duration),
          url,
          album: link.type === 'album' ? title : undefined,
          cover,
          trackNumber: link.type === 'album' ? index + 1 : undefined,
        }
      })
      .filter((t): t is SourceTrack => !!t)
  }
  if (!tracks.length) throw new Error('Could not read this Apple Music collection. It may be unavailable in this region.')
  return {
    kind: 'collection',
    collection: {
      service: 'apple-music',
      type: link.type === 'album' ? 'album' : 'playlist',
      title,
      owner,
      cover,
      tracks,
      total: positive(ld?.numTracks),
    },
  }
}

// ---------------------------------------------------------------------------
// YouTube Music search + matching
// ---------------------------------------------------------------------------

export type Candidate = {
  url: string
  title: string
  artists: string[]
  album?: string
  durationSec?: number
  /** Where the candidate came from; YouTube Music "songs" results are official audio. */
  origin: 'ytm-song' | 'ytm-flat' | 'youtube'
  channel?: string
}

/** Searches YouTube Music songs; the request and the reading of the answer come from the shared core. */
export async function searchSongs(query: string, fetcher: typeof fetch = fetch, signal?: AbortSignal): Promise<Candidate[]> {
  const request = songsApiRequest(query)
  const response = await fetcher(request.url, {
    method: 'POST',
    headers: request.headers,
    body: request.body,
    signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(15_000)]) : AbortSignal.timeout(15_000),
  })
  if (!response.ok) throw new Error(`YouTube Music search failed (HTTP ${response.status}).`)
  return parseYtMusicSearchText(await response.text())
}

const ALBUM_RESPONSE_MAX_BYTES = 6_000_000

/** POSTs a YouTube Music request and returns the response text: no redirects, a deadline, and a size cap. */
async function postMusic(request: AlbumRequest, fetcher: typeof fetch, signal: AbortSignal | undefined, what: string): Promise<string> {
  const timeout = AbortSignal.timeout(15_000)
  const response = await fetcher(request.url, {
    method: 'POST',
    headers: request.headers,
    body: request.body,
    redirect: 'error',
    signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
  })
  if (!response.ok) throw new Error(`YouTube Music ${what} failed (HTTP ${response.status}).`)
  const text = await response.text()
  if (text.length > ALBUM_RESPONSE_MAX_BYTES) throw new Error(`YouTube Music ${what} was unexpectedly large.`)
  return text
}

/** Albums on YouTube Music for the track's album name (the request and the reading come from the shared core). */
export async function searchAlbums(track: MatchTrack, fetcher: typeof fetch = fetch, signal?: AbortSignal): Promise<{query: string; albums: AlbumHit[]}> {
  const {query, request} = albumSearchRequest(track)
  return {query, albums: parseAlbumSearchText(await postMusic(request, fetcher, signal, 'album search'))}
}

/** One album page; undefined when the id is not an album id or the page holds no album. */
export async function fetchAlbumPage(browseId: string, fetcher: typeof fetch = fetch, signal?: AbortSignal): Promise<AlbumPage | undefined> {
  const request = albumPageRequest(browseId)
  if (!request) return
  return parseAlbumPageText(await postMusic(request, fetcher, signal, 'album page'))
}

export function serviceOf(url: string): MusicService | undefined {
  return musicLink(url)?.service ?? (isShortMusicLink(url) ? (/spoti/.test(url) ? 'spotify' : 'deezer') : undefined)
}

// ---------------------------------------------------------------------------
// Regionally restricted YouTube songs (auto-generated "Topic" art tracks)
// ---------------------------------------------------------------------------

export type WatchProbe = {
  status?: string
  reason?: string
  video?: {id: string; title: string; author?: string; lengthSeconds?: number}
}

/** Reads playabilityStatus and videoDetails from an anonymous watch page. Pure. */
export function parseWatchPage(html: string): WatchProbe {
  const status = html.match(/"playabilityStatus":\{"status":"([A-Z_]+)"/)?.[1]
  const reason = html.match(/"playabilityStatus":\{"status":"[A-Z_]+","reason":"((?:[^"\\]|\\.)*)"/)?.[1]
  const details = html.match(/"videoDetails":\{"videoId":"([\w-]{11})","title":"((?:[^"\\]|\\.)*)","lengthSeconds":"(\d+)"/)
  const unescape = (value: string) => {
    try {
      return JSON.parse(`"${value}"`) as string
    } catch {
      return value
    }
  }
  const tail = details ? html.slice(details.index! + details[0].length, details.index! + details[0].length + 6000) : ''
  const author = tail.match(/"author":"((?:[^"\\]|\\.)*)"/)?.[1]
  return {
    status,
    reason: reason ? unescape(reason) : undefined,
    video: details
      ? {id: details[1], title: unescape(details[2]), author: author ? unescape(author) : undefined, lengthSeconds: Number(details[3]) || undefined}
      : undefined,
  }
}

/** UNPLAYABLE with details present is a deterministic restriction; OK is transient; the rest is unknown. */
export function classifyProbe(probe: WatchProbe): 'restricted' | 'transient' | 'unknown' {
  if (probe.status === 'UNPLAYABLE' && probe.video) return 'restricted'
  if (probe.status === 'OK') return 'transient'
  return 'unknown'
}

export {youTubeVideoId}

export async function probeWatchPage(videoId: string, fetcher: typeof fetch = fetch, signal?: AbortSignal): Promise<WatchProbe> {
  const response = await fetcher(`https://www.youtube.com/watch?v=${videoId}&hl=en`, {
    headers: browserHeaders,
    signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(15_000)]) : AbortSignal.timeout(15_000),
  })
  if (!response.ok) return {}
  return parseWatchPage(await response.text())
}

export const stripTopic = (name: string) => name.replace(/\s+-\s+Topic$/i, '').trim()

/** Music-like: a music.youtube.com link, a " - Topic" channel, or a collection from YouTube Music. */
export function isMusicLike(input: {url: string; author?: string; groupUrl?: string}): boolean {
  return isYouTubeMusic(input.url) || isYouTubeMusic(input.groupUrl) || /\s-\s+Topic$/i.test(input.author ?? '')
}

/** A song's official music video on YouTube Music (the request, the reading and the choice come from the shared core); undefined when there is none. */
export async function findMusicVideo(artists: string[], title: string, fetcher: typeof fetch = fetch, signal?: AbortSignal): Promise<VideoHit | undefined> {
  const request = musicVideoRequest(artists, title)
  const hits = parseMusicVideos(await postMusic(request, fetcher, signal, 'video search'))
  return bestMusicVideo(artists, title, hits)
}

/** [findMusicVideo] as the renderer is told: what was found, or none (Keepfin never guesses); a failed look is an error the person can retry. */
export async function lookupMusicVideo(artist: string, title: string): Promise<MusicVideoFound> {
  let hit
  try {
    hit = await findMusicVideo([artist], title)
  } catch {
    throw new Error('Keepfin could not look for the music video. Check your connection and try again.')
  }
  if (!hit) return {none: true}
  return {videoId: hit.videoId, title: hit.title, channel: hit.artists.map((a: {name: string}) => a.name).join(', '), views: hit.views, durationSec: hit.durationSec}
}

/** The YouTube Music channel (UC...) of an artist, found with the videos search (the same request the music video look uses); undefined when no result is surely that artist. */
export async function findArtistChannel(name: string, key: string, fetcher: typeof fetch = fetch, signal?: AbortSignal): Promise<string | undefined> {
  const request = musicVideoRequest([name], '')
  const hits = parseMusicVideos(await postMusic(request, fetcher, signal, 'artist search'))
  return pickChannel(hits.flatMap((hit) => hit.artists), key)
}
