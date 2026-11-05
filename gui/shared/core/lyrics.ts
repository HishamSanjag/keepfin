// Adapter between the shared core (core/, Kotlin Multiplatform; built to build/core-js by `npm run core:build`)
// and the lyrics lookup of the desktop engine. This is the only file that imports the core's lyrics package. The core decides
// (the two LRCLIB requests, which record fits the song, what to do with an answer that did not work); the engine does the requests.
import {
  lyricsCleanTitle,
  lyricsExactText,
  lyricsIsTimed,
  lyricsPreferTimed,
  lyricsUntimedWords,
  lyricsFindWords,
  lyricsGetUrl,
  lyricsIsBusy,
  lyricsOutcome as lyricsOutcome_,
  lyricsOutcomeWords,
  lyricsNormalizeKey,
  lyricsRetryAfterMs as coreRetryAfterMs,
  lyricsSearchText,
  lyricsSearchUrl,
} from '../../../build/core-js/keepfin-core.mjs'

export type LyricsQuery = {title: string; artist: string; album?: string; duration?: number}

/** Lower case, no "(feat. …)", "[Official Video]" or " - Topic", single spaces. */
export const normalizeLyricsKey = (value: string): string => lyricsNormalizeKey(value)

/** A clean title for the lookup: drops "[Official Video]"-style tags and a leading "Artist - " the artist already covers. */
export const cleanSongTitle = (title: string, artist?: string): string => lyricsCleanTitle(title, artist ?? null)

/** The exact-match request, or undefined when the song's length is unknown (LRCLIB requires it). */
export const lyricsExactUrl = (query: LyricsQuery): string | undefined =>
  lyricsGetUrl(query.title, query.artist, query.album ?? null, query.duration ?? null) ?? undefined

export const lyricsFindUrl = (query: LyricsQuery): string => lyricsSearchUrl(query.title, query.artist)

/** The lyrics in the answer to the exact-match request, or undefined. Throws when the answer is not JSON. */
/** Whether lyrics carry line times, so they can follow the song. */
export const lyricsAreTimed = (text: string | undefined): boolean => lyricsIsTimed(text ?? null)
/** Of the exact match and the search's best, the one that can follow the song. */
export const preferTimedLyrics = (exact: string | undefined, found: string | undefined): string | undefined => lyricsPreferTimed(exact ?? null, found ?? null) ?? undefined
/** Said above lyrics that have no line times. */
export const untimedLyricsWords = (): string => lyricsUntimedWords()
export const lyricsInExactAnswer = (body: string): string | undefined => lyricsExactText(body) ?? undefined

/** The lyrics of the best record in the answer to the search request, or undefined. Throws when the answer is not JSON. */
export const lyricsInSearchAnswer = (query: LyricsQuery, body: string): string | undefined =>
  lyricsSearchText(query.title, query.artist, query.duration ?? null, body) ?? undefined

/** How long to wait before trying a request again (`attempt` = tries made so far, from 1), or undefined to stop: a real "not found" is final. */
export const lyricsRetryAfterMs = (status: number | undefined, attempt: number): number | undefined => coreRetryAfterMs(status ?? null, attempt) ?? undefined

/** True when a status (undefined: no answer) means the service could not help just now, as opposed to a real answer or refusal. */
export const lyricsServiceBusy = (status: number | undefined): boolean => lyricsIsBusy(status ?? null)

/** What a "Find lyrics" search came to: lyrics, a service that never answered, or a real "not found". */
export type LyricsOutcome = 'found' | 'busy' | 'none'
export const lyricsOutcome = (found: boolean, gaveUpOnBusy: boolean): LyricsOutcome => lyricsOutcome_(found, gaveUpOnBusy) as LyricsOutcome

/** The plain line for a search that ended ("No lyrics found", "Could not reach the lyrics service. Try again later.", "Lyrics added."). */
export const lyricsOutcomeText = (outcome: LyricsOutcome): string => lyricsOutcomeWords(outcome)

/** The action's name: "Find lyrics". */
export const findLyricsWords: string = lyricsFindWords()
