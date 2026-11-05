package app.keepfin

import app.keepfin.core.lyrics.Lyrics
import app.keepfin.core.lyrics.LyricsQuery
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lyrics from LRCLIB (https://lrclib.net), a free lyrics library. The rules (the requests, which record fits, when to ask again) are the
 * shared core's (app.keepfin.core.lyrics.Lyrics); [get] and the waiting are the only parts that touch the outside world.
 */
object LyricsLookup {
    const val TIMEOUT_MS = 8_000
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val SLICE_MS = 100L

    /** Sleeps [ms] in short slices and ends early once [cancelled]. */
    private fun sleepUnless(ms: Long, cancelled: () -> Boolean) {
        var left = ms
        while (left > 0 && !cancelled()) { Thread.sleep(minOf(SLICE_MS, left)); left -= SLICE_MS }
    }

    /** One request, asked again while the core says so (a busy service, no answer); the last answer otherwise. */
    private fun askAgain(url: String, cancelled: () -> Boolean, pause: (Long) -> Unit, http: (String) -> Pair<Int, String>?): Pair<Int, String>? {
        var attempt = 1
        while (!cancelled()) {
            val answer = http(url)
            val wait = Lyrics.retryAfterMs(answer?.first, attempt++) ?: return answer
            pause(wait)
        }
        return null
    }

    /** What a search came to: the lyrics, or [busy] when none came and the service could not answer (a refusal to try again later, not "no lyrics"). */
    class Answer(val text: String?, val busy: Boolean)

    /**
     * Resolves lyrics text for a song: the exact lookup first (needs the length), then the search fallback on a miss.
     * [http] returns (status, body) or null on failure; [pause] waits between tries; both are injectable for tests. Stops waiting once
     * [cancelled]. Never throws.
     */
    fun search(
        query: LyricsQuery,
        cancelled: () -> Boolean = { false },
        pause: (Long) -> Unit = { sleepUnless(it, cancelled) },
        http: (String) -> Pair<Int, String>? = ::get,
    ): Answer = try {
        val clean = query.copy(title = Lyrics.cleanTitle(query.title, query.artist))
        if (clean.title.isBlank() || clean.artist.isBlank()) Answer(null, false) else {
            var busy = false
            fun ask(url: String) = askAgain(url, cancelled, pause, http).also { busy = busy || (!cancelled() && Lyrics.isBusy(it?.first)) }
            val exact = Lyrics.getUrl(clean)?.let(::ask)
            val direct = if (exact != null && exact.first == 200) Lyrics.exactText(exact.second) else null
            if (Lyrics.isTimed(direct)) Answer(direct, false) else {
                // plain words from the exact match are kept only when the search has nothing timed either
                val found = ask(Lyrics.searchUrl(clean))
                Answer(Lyrics.preferTimed(direct, if (found != null && found.first == 200) Lyrics.searchText(clean, found.second) else null), busy)
            }
        }
    } catch (_: Exception) { Answer(null, false) }

    /** The lyrics text of [search], or null. */
    fun find(
        query: LyricsQuery,
        cancelled: () -> Boolean = { false },
        pause: (Long) -> Unit = { sleepUnless(it, cancelled) },
        http: (String) -> Pair<Int, String>? = ::get,
    ): String? = search(query, cancelled, pause, http).text

    /** The saved "lyrics" setting; settings saved before it existed get true (on by default). */
    fun enabledIn(json: JSONObject?): Boolean = json?.optBoolean("lyrics", true) ?: true

    /** Set from the app at start-up; the User-Agent LRCLIB asks clients to send. */
    @Volatile var versionName: String = "dev"

    fun userAgent() = "Keepfin/$versionName (https://github.com/HishamSanjag/keepfin)"

    private fun get(url: String): Pair<Int, String>? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS; instanceFollowRedirects = true
                setRequestProperty("User-Agent", userAgent()); setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            if (code != 200) return code to ""
            val bytes = connection.inputStream.use { it.readBytes() }
            if (bytes.size > MAX_BYTES) null else code to String(bytes, Charsets.UTF_8)
        } catch (_: Exception) { null } finally { connection?.disconnect() }
    }
}
