// Looks a song's lyrics up on LRCLIB: the exact match first, then a search checked by length, title and artist.
// Best effort: every failure (network, timeout, unexpected reply) is "no lyrics". A busy service is asked again a little later
// (the shared core decides when and how often); a real "not found" is final. A search says which it was (LyricsOutcome), so
// "Find lyrics" can tell a song without lyrics from a service that could not be reached.
import {
  lyricsExactUrl,
  lyricsFindUrl,
  lyricsInExactAnswer,
  lyricsAreTimed, lyricsInSearchAnswer, preferTimedLyrics,
  lyricsOutcome,
  lyricsRetryAfterMs,
  lyricsServiceBusy,
  type LyricsOutcome,
  type LyricsQuery,
} from '../../shared/core/lyrics.js'

export const LYRICS_TIMEOUT_MS = 8_000

export type LyricsLookupOptions = {
  fetch?: typeof fetch
  signal?: AbortSignal
  userAgent?: string
  /** Waits between tries; ends at once when the signal aborts. Tests pass an instant one. */
  wait?: (ms: number, signal?: AbortSignal) => Promise<void>
}

function pause(ms: number, signal?: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    if (signal?.aborted) return resolve()
    const done = () => {
      clearTimeout(timer)
      signal?.removeEventListener('abort', done)
      resolve()
    }
    const timer = setTimeout(done, ms)
    signal?.addEventListener('abort', done, {once: true})
  })
}

/** The text of an answer; `busy` when the service kept failing (no real answer), neither when it said no. */
async function getText(url: string, options: LyricsLookupOptions): Promise<{text?: string; busy?: boolean}> {
  for (let attempt = 1; ; attempt++) {
    let status: number | undefined
    try {
      const timeout = AbortSignal.timeout(LYRICS_TIMEOUT_MS)
      const response = await (options.fetch ?? fetch)(url, {
        signal: options.signal ? AbortSignal.any([options.signal, timeout]) : timeout,
        headers: {Accept: 'application/json', ...(options.userAgent ? {'User-Agent': options.userAgent} : {})},
      })
      status = response.status
      if (response.ok) return {text: await response.text()}
      await response.body?.cancel()
    } catch (error) {
      if (options.signal?.aborted) throw error
    }
    const wait = lyricsRetryAfterMs(status, attempt)
    if (wait === undefined) return {busy: lyricsServiceBusy(status)}
    await (options.wait ?? pause)(wait, options.signal)
    if (options.signal?.aborted) return {}
  }
}

/** Synced (LRC) or plain lyrics for the song with what the search came to; never throws. */
export async function findLyricsText(query: LyricsQuery, options: LyricsLookupOptions = {}): Promise<{text?: string; outcome: LyricsOutcome}> {
  if (!query.title.trim() || !query.artist.trim()) return {outcome: 'none'}
  try {
    let busy = false
    let matched: string | undefined
    const exact = lyricsExactUrl(query)
    if (exact) {
      const answer = await getText(exact, options)
      busy ||= !!answer.busy
      matched = answer.text === undefined ? undefined : lyricsInExactAnswer(answer.text)
      if (lyricsAreTimed(matched)) return {text: matched, outcome: 'found'}
    }
    if (options.signal?.aborted) return {text: matched, outcome: lyricsOutcome(!!matched, false)}
    // plain words from the exact match are kept only when the search has nothing timed either
    const answer = await getText(lyricsFindUrl(query), options)
    busy ||= !!answer.busy
    const text = preferTimedLyrics(matched, answer.text === undefined ? undefined : lyricsInSearchAnswer(query, answer.text))
    return {text, outcome: lyricsOutcome(!!text, busy)}
  } catch {
    return {outcome: 'none'}
  }
}

/** Synced (LRC) or plain lyrics for the song, or undefined. Never throws. */
export async function lookupLyrics(query: LyricsQuery, options: LyricsLookupOptions = {}): Promise<string | undefined> {
  return (await findLyricsText(query, options)).text
}
