@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.lyrics

// The JavaScript surface of the lyrics core (see gui/shared/core/lyrics.ts): plain values in, nullable values out.

@JsExport
fun lyricsNormalizeKey(value: String): String = Lyrics.normalizeKey(value)

@JsExport
fun lyricsCleanTitle(title: String, artist: String?): String = Lyrics.cleanTitle(title, artist)

@JsExport
fun lyricsGetUrl(title: String, artist: String, album: String?, duration: Double?): String? = Lyrics.getUrl(LyricsQuery(title, artist, album, duration))

@JsExport
fun lyricsSearchUrl(title: String, artist: String): String = Lyrics.searchUrl(LyricsQuery(title, artist))

/** The lyrics in an exact-match answer, or null. Throws when [body] is not JSON. */
@JsExport
fun lyricsExactText(body: String): String? = Lyrics.exactText(body)

@JsExport
fun lyricsIsTimed(text: String?): Boolean = Lyrics.isTimed(text)

@JsExport
fun lyricsPreferTimed(exact: String?, found: String?): String? = Lyrics.preferTimed(exact, found)

@JsExport
fun lyricsUntimedWords(): String = Lyrics.UNTIMED

/** The lyrics of the best record in a search answer, or null. Throws when [body] is not JSON. */
@JsExport
fun lyricsSearchText(title: String, artist: String, duration: Double?, body: String): String? =
    Lyrics.searchText(LyricsQuery(title, artist, null, duration), body)

/** Milliseconds to wait before the next try, or null to stop. */
@JsExport
fun lyricsRetryAfterMs(status: Int?, attempt: Int): Double? = Lyrics.retryAfterMs(status, attempt)?.toDouble()

/** True when the status (null: no answer) means the service could not help just now. */
@JsExport
fun lyricsIsBusy(status: Int?): Boolean = Lyrics.isBusy(status)

/** "found", "busy" or "none". */
@JsExport
fun lyricsOutcome(found: Boolean, gaveUpOnBusy: Boolean): String = Lyrics.outcome(found, gaveUpOnBusy)

/** The line for a search that ended. */
@JsExport
fun lyricsOutcomeWords(outcome: String): String = Lyrics.outcomeWords(outcome)

@JsExport
fun lyricsFindWords(): String = Lyrics.FIND
