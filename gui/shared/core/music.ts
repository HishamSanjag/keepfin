// Adapter between the shared core (core/, Kotlin Multiplatform; built to build/core-js by `npm run core:build`)
// and the music types the desktop code already uses. This is the only file that imports the core's music
// package: everything else imports these functions, so the core's generated API stays behind one boundary.
//
// The core makes the matching decisions (queries, searches, scoring, ranking). It performs no requests.
import {
  JsAlbumCandidate,
  JsAlbumFetched,
  JsAlbumPage,
  JsCandidate,
  JsTrack,
  musicAlbumApplies,
  musicAlbumHint,
  musicAlbumBrowseBody,
  musicAlbumBrowseUrl,
  musicAlbumQuery,
  musicAlbumRank,
  musicAlbumSearchBody,
  musicAlbumStep,
  musicParseAlbumPage,
  musicParseAlbumSearch,
  musicAlternates,
  musicBest,
  musicCoreTitle,
  musicFlatCandidates,
  musicIsConfidentScore,
  musicNormalize,
  musicParseSongsApi,
  musicRanked,
  musicScore,
  musicSearches,
  musicSongsApiBody,
  musicSongsApiHeaders,
  musicSongsApiUrl,
  musicQuery,
  musicConstants,
  musicDeezerIsrcs,
  musicVideosBest,
  musicVideosParse,
  musicVideosQuery,
  musicVideosSearchBody,
  JsVideoCandidate,
  JsVideoArtist,
  JsMatch,
} from '../../../build/core-js/keepfin-core.mjs'

import {youTubeVideoId} from './providers.js'

export type CandidateOrigin = 'ytm-song' | 'ytm-flat' | 'youtube'

/** The part of a source recording that matching uses (structurally the desktop `SourceTrack`). */
export type MatchTrack = {
  title: string
  artists: string[]
  /** Milliseconds. Zero or absent means unknown. */
  durationMs?: number
  album?: string
  /** Position on the album (1-based), when known. */
  trackNumber?: number
  /** Tracks on the source album, when known. */
  trackCount?: number
  /** Release year, when known. */
  year?: number
}

/** A search result (structurally the desktop `Candidate`). */
export type MatchCandidate = {
  url: string
  title: string
  artists: string[]
  album?: string
  /** Seconds. Zero or absent means unknown. */
  durationSec?: number
  /** Where the candidate came from; YouTube Music "songs" results are official audio. */
  origin: CandidateOrigin
  channel?: string
}

export type ScoredCandidate<C extends MatchCandidate = MatchCandidate> = {candidate: C; confidence: number}

/** A search to run, in order. */
export type SearchStep =
  | {kind: 'songsApi'; query: string}
  | {kind: 'flat'; target: string; origin: CandidateOrigin; playlistEnd: 8}

// The thresholds live in core/src/commonMain (MusicMatching); these are read from there, never repeated.
const constants = musicConstants()
export const MIN_CONFIDENCE: number = constants.minConfidence
export const CONFIDENT: number = constants.confident
export const ALTERNATE_MIN_CONFIDENCE: number = constants.alternateMinConfidence
export const ALTERNATE_MAX_DELTA_SEC: number = constants.alternateMaxDeltaSec
export const MAX_ALTERNATES: number = constants.maxAlternates

// -- YouTube video ids -------------------------------------------------------------------------------------------

/** The video id of a YouTube / YouTube Music link (watch, youtu.be, shorts, embed, live); the providers core reads it. */
export const videoIdOf = youTubeVideoId

// -- mapping ------------------------------------------------------------------------------------------------------

const toTrack = (track: MatchTrack) =>
  new JsTrack(
    track.title,
    track.artists,
    track.album ?? null,
    track.durationMs ? track.durationMs / 1000 : null,
    track.trackNumber ?? null,
    track.trackCount ?? null,
    track.year ?? null,
  )

function toCandidate(candidate: MatchCandidate): JsCandidate {
  const id = videoIdOf(candidate.url)
  if (!id) throw new Error(`Not a YouTube link: ${candidate.url}`)
  return new JsCandidate(id, candidate.url, candidate.title, candidate.artists, candidate.channel ?? null, candidate.album ?? null, candidate.durationSec ?? null, candidate.origin)
}

/** Candidates the core reports back, as plain objects without absent keys. */
function fromCandidate(candidate: JsCandidate): MatchCandidate {
  const result: MatchCandidate = {url: candidate.url, title: candidate.title, artists: [...candidate.artists], origin: candidate.origin as CandidateOrigin}
  if (candidate.album != null) result.album = candidate.album
  if (candidate.durationSec != null) result.durationSec = candidate.durationSec
  if (candidate.channel != null) result.channel = candidate.channel
  return result
}

/** The caller's own candidate objects for the core's matches (the first one with that video id). */
function toScored<C extends MatchCandidate>(matches: JsMatch[], candidates: C[]): ScoredCandidate<C>[] {
  const byId = new Map<string, C>()
  for (const candidate of candidates) {
    const id = videoIdOf(candidate.url)
    if (id && !byId.has(id)) byId.set(id, candidate)
  }
  return matches.map((match) => ({candidate: byId.get(match.candidate.videoId)!, confidence: match.confidence}))
}

// -- text ---------------------------------------------------------------------------------------------------------

export const normalize = (value: string): string => musicNormalize(value)

/** Title without featuring credits and edition suffixes, which services format differently. */
export const coreTitle = (title: string): string => musicCoreTitle(title)

// -- searching ----------------------------------------------------------------------------------------------------

export const searchQuery = (track: MatchTrack): string => musicQuery(toTrack(track))

/** The searches to run for a recording, in order: YouTube Music songs, YouTube Music flat, YouTube flat. */
export function searchPlan(track: MatchTrack): SearchStep[] {
  return musicSearches(toTrack(track)).map((search): SearchStep =>
    search.kind === 'songsApi'
      ? {kind: 'songsApi', query: search.query!}
      : {kind: 'flat', target: search.target!, origin: search.origin as CandidateOrigin, playlistEnd: 8},
  )
}

/** The YouTube Music songs search request for a query: address, headers and JSON body. */
export function songsApiRequest(query: string): {url: string; headers: Record<string, string>; body: string} {
  return {
    url: musicSongsApiUrl(),
    headers: Object.fromEntries(musicSongsApiHeaders().map(([name, value]) => [name, value])),
    body: musicSongsApiBody(query),
  }
}

/** Parses the text of an innertube WEB_REMIX search response (songs filter). */
export const parseYtMusicSearchText = (json: string): MatchCandidate[] => musicParseSongsApi(json).map(fromCandidate)

/** Parses an innertube WEB_REMIX search response (songs filter) that is already parsed JSON. */
export const parseYtMusicSearch = (data: unknown): MatchCandidate[] => parseYtMusicSearchText(JSON.stringify(data))

/** Engine flat search output (the text of `-J`) to candidates. */
export const parseFlatSearchText = (json: string, origin: CandidateOrigin): MatchCandidate[] => musicFlatCandidates(json, origin).map(fromCandidate)

/** Engine flat search output that is already parsed JSON. */
export const parseFlatSearch = (data: unknown, origin: CandidateOrigin): MatchCandidate[] => parseFlatSearchText(JSON.stringify(data), origin)

// -- scoring ------------------------------------------------------------------------------------------------------

/** 0 to 1, not rounded. */
export const scoreCandidate = (track: MatchTrack, candidate: MatchCandidate): number => musicScore(toTrack(track), toCandidate(candidate))

/** Every candidate at or above MIN_CONFIDENCE, once per video, best first. */
export function rankedMatches<C extends MatchCandidate>(track: MatchTrack, candidates: C[]): ScoredCandidate<C>[] {
  return toScored(musicRanked(toTrack(track), candidates.map(toCandidate)), candidates)
}

/** The best candidate, or undefined when none reaches MIN_CONFIDENCE. */
export function bestMatch<C extends MatchCandidate>(track: MatchTrack, candidates: C[]): ScoredCandidate<C> | undefined {
  const match = musicBest(toTrack(track), candidates.map(toCandidate))
  return match ? toScored([match], candidates)[0] : undefined
}

/** True when the match is good enough to stop searching. */
export function isConfident(match: {confidence: number} | undefined): boolean {
  return match !== undefined && musicIsConfidentScore(match.confidence)
}

/** Other uploads of the same recording worth trying, best first. Excludes the original and videos already tried. */
export function rankAlternates<C extends MatchCandidate>(
  track: MatchTrack,
  candidates: C[],
  options: {exclude?: Iterable<string>; limit?: number} = {},
): ScoredCandidate<C>[] {
  const matches = musicAlternates(toTrack(track), candidates.map(toCandidate), [...(options.exclude ?? [])], options.limit ?? MAX_ALTERNATES)
  return toScored(matches, candidates)
}

// -- running searches ---------------------------------------------------------------------------------------------

/**
 * The app's stop rule: run the searches in order, collect every candidate, and stop as soon as the best so far is
 * confident. A search that throws is skipped (`onError` can rethrow, e.g. for an abort). Returns the best match
 * over everything found and how many searches were attempted.
 */
export async function pickBest<C extends MatchCandidate>(
  track: MatchTrack,
  searches: (() => Promise<C[]>)[],
  onError?: (error: unknown) => void,
): Promise<{best: ScoredCandidate<C> | undefined; searchesRun: number}> {
  const found: C[] = []
  let searchesRun = 0
  for (const search of searches) {
    searchesRun++
    try {
      found.push(...(await search()))
    } catch (error) {
      onError?.(error)
      continue
    }
    if (isConfident(bestMatch(track, found))) break
  }
  return {best: bestMatch(track, found), searchesRun}
}

/** Other uploads of the same recording: run the searches in order, rank after each, stop at MAX_ALTERNATES. */
export async function collectAlternates<C extends MatchCandidate>(
  track: MatchTrack,
  searches: (() => Promise<C[]>)[],
  exclude: Iterable<string>,
  onError?: (error: unknown) => void,
): Promise<ScoredCandidate<C>[]> {
  const found: C[] = []
  const skip = [...exclude]
  let ranked: ScoredCandidate<C>[] = []
  for (const search of searches) {
    try {
      found.push(...(await search()))
    } catch (error) {
      onError?.(error)
    }
    ranked = rankAlternates(track, found, {exclude: skip})
    if (ranked.length >= MAX_ALTERNATES) break
  }
  return ranked
}

// -- matching through the album -----------------------------------------------------------------------------------

/** An album found by an albums search on YouTube Music. */
export type AlbumHit = {browseId: string; title: string; artists: string[]; year?: number; kind?: string}

/** An album page as the core read it. Opaque: keep it and hand it back to `albumStep` / `matchViaAlbum`. */
export type AlbumPage = JsAlbumPage

/** The track the album route found. */
export type AlbumTrackMatch = {
  browseId: string
  albumTitle: string
  albumConfidence: number
  position: number
  candidate: MatchCandidate
  confidence: number
}

/** A POST to YouTube Music (the albums search and the album page go to the same family of addresses as the songs search). */
export type AlbumRequest = {url: string; headers: Record<string, string>; body: string}

export type AlbumStep = {kind: 'fetch'; browseId: string} | {kind: 'matched'; match: AlbumTrackMatch} | {kind: 'none'}

/** Whether the track can be matched through its album at all (it has an album name). */
export const albumApplies = (track: MatchTrack): boolean => musicAlbumApplies(toTrack(track))

const requestHeaders = () => Object.fromEntries(musicSongsApiHeaders().map(([name, value]) => [name, value]))

/** The albums search request for a track, with its search text (a cache key). */
export function albumSearchRequest(track: MatchTrack): {query: string; request: AlbumRequest} {
  const query = musicAlbumQuery(toTrack(track))
  return {query, request: {url: musicSongsApiUrl(), headers: requestHeaders(), body: musicAlbumSearchBody(query)}}
}

/** The album page request, or undefined when the id is not a YouTube Music album id. */
export function albumPageRequest(browseId: string): AlbumRequest | undefined {
  const body = musicAlbumBrowseBody(browseId)
  return body == null ? undefined : {url: musicAlbumBrowseUrl(), headers: requestHeaders(), body}
}

function fromAlbum(album: JsAlbumCandidate): AlbumHit {
  const hit: AlbumHit = {browseId: album.browseId, title: album.title, artists: [...album.artists]}
  if (album.year != null) hit.year = album.year
  if (album.kind != null) hit.kind = album.kind
  return hit
}

const toAlbum = (hit: AlbumHit) => new JsAlbumCandidate(hit.browseId, hit.title, hit.artists, hit.year ?? null, hit.kind ?? null)

/** Albums from the text of an albums search response. Throws when it is not JSON. */
export const parseAlbumSearchText = (json: string): AlbumHit[] => musicParseAlbumSearch(json).map(fromAlbum)

/** The album page in the text of a browse response; undefined when it holds no album. Throws when it is not JSON. */
export const parseAlbumPageText = (json: string): AlbumPage | undefined => musicParseAlbumPage(json) ?? undefined

/** The albums worth fetching for a track, best first. */
export const rankAlbums = (track: MatchTrack, albums: AlbumHit[]): AlbumHit[] =>
  musicAlbumRank(toTrack(track), albums.map(toAlbum)).map(fromAlbum)

/** The next step of the album route; `fetched` holds the pages read so far (null = could not be read). */
export function albumStep(track: MatchTrack, albums: AlbumHit[], fetched: Map<string, AlbumPage | null>): AlbumStep {
  const step = musicAlbumStep(
    toTrack(track),
    albums.map(toAlbum),
    [...fetched].map(([browseId, page]) => new JsAlbumFetched(browseId, page)),
  )
  if (step.kind === 'fetch') return {kind: 'fetch', browseId: step.browseId!}
  if (step.kind !== 'matched') return {kind: 'none'}
  const m = step.match!
  return {
    kind: 'matched',
    match: {browseId: m.browseId, albumTitle: m.albumTitle, albumConfidence: m.albumConfidence, position: m.position, candidate: fromCandidate(m.candidate), confidence: m.confidence},
  }
}

/**
 * The album route: search the albums, fetch the page the core asks for (at most a few), and return the track it finds,
 * or undefined when no album fits (the caller then uses the songs search). A failing search ends the route; a failing
 * page counts as unreadable. `onError` can rethrow (for an abort).
 */
export async function matchViaAlbum(
  track: MatchTrack,
  io: {search: () => Promise<AlbumHit[]>; page: (browseId: string) => Promise<AlbumPage | undefined>},
  onError?: (error: unknown) => void,
): Promise<AlbumTrackMatch | undefined> {
  if (!albumApplies(track)) return
  let albums: AlbumHit[]
  try {
    albums = await io.search()
  } catch (error) {
    onError?.(error)
    return
  }
  const fetched = new Map<string, AlbumPage | null>()
  for (let round = 0; round < 6; round++) {
    const step = albumStep(track, albums, fetched)
    if (step.kind === 'matched') return step.match
    if (step.kind === 'none') return
    try {
      fetched.set(step.browseId, (await io.page(step.browseId)) ?? null)
    } catch (error) {
      onError?.(error)
      fetched.set(step.browseId, null)
    }
  }
}

/** The album to look for when the track has none: the album of the best songs-search result. */
export const albumHint = (track: MatchTrack, candidates: MatchCandidate[]): string | undefined => musicAlbumHint(toTrack(track), candidates.map(toCandidate)) ?? undefined

// ---- a song's official music video (core MusicVideos) ----

/** A videos-search row (type is OMV for an official video, UGC, ATV, or empty). */
export type VideoHit = {videoId: string; type: string; title: string; artists: {name: string; browseId: string}[]; views?: number; durationSec?: number}

const fromVideo = (c: JsVideoCandidate): VideoHit => ({
  videoId: c.videoId,
  type: c.type,
  title: c.title,
  artists: Array.from(c.artists, (a) => ({name: a.name, browseId: a.browseId})),
  views: c.views ?? undefined,
  durationSec: c.durationSec ?? undefined,
})

/** The videos search request for a song (sent to the songs API, like the songs search). */
export function musicVideoRequest(artists: string[], title: string): {url: string; headers: Record<string, string>; body: string} {
  return {
    url: musicSongsApiUrl(),
    headers: Object.fromEntries(musicSongsApiHeaders().map(([name, value]) => [name, value])),
    body: musicVideosSearchBody(musicVideosQuery(artists, title)),
  }
}

export const parseMusicVideos = (json: string): VideoHit[] => Array.from(musicVideosParse(json), fromVideo)

/** The song's official music video among [hits], or undefined: Keepfin never guesses. [artistIds] are the artist's channel ids when known (a library song has none yet). */
export function bestMusicVideo(artists: string[], title: string, hits: VideoHit[], artistIds: string[] = []): VideoHit | undefined {
  const found = musicVideosBest(
    artists,
    title,
    hits.map((h) => new JsVideoCandidate(h.videoId, h.type, h.title, h.artists.map((a) => new JsVideoArtist(a.name, a.browseId)) as never, h.views, h.durationSec)),
    artistIds,
  )
  return found ? fromVideo(found) : undefined
}
/** Track id to ISRC for every track a Deezer API response holds (a track, a playlist, or a page of /tracks). */
export const deezerIsrcs = (json: string): Map<string, string> => new Map(Array.from(musicDeezerIsrcs(json), (pair) => [pair[0], pair[1]] as [string, string]))
