package app.keepfin.core.lyrics

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.parseJson
import app.keepfin.core.music.NOT_EOL
import app.keepfin.core.music.WS
import app.keepfin.core.music.ci
import app.keepfin.core.music.jsTrim
import app.keepfin.core.music.matchStartAtEnd
import app.keepfin.core.spaces.Collections
import kotlin.math.abs
import kotlin.math.floor

// Lyrics for downloaded songs from LRCLIB (https://lrclib.net), a free, open lyrics library: request building, result choosing and
// what to do with an answer. The behaviour is the desktop app's (gui/shared/lyrics.ts, formerly). Each app does its own requests.

/** The song a lookup is for; [duration] is in seconds. */
data class LyricsQuery(val title: String, val artist: String, val album: String? = null, val duration: Double? = null)

/** One LRCLIB record, as the service answers it. */
data class LrclibRecord(
    val id: Long? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

object Lyrics {
    const val LRCLIB = "https://lrclib.net"

    /** A result's length must be this close to the song's (seconds). */
    const val DURATION_TOLERANCE = 2.0

    /** The wait before the second and the third try of one request. */
    private val retryDelaysMs = longArrayOf(2_000, 6_000)

    private val noise = Regex(
        WS + "*[(\\[](?:" + ci("official") + "(?: " + ci("music") + "| " + ci("lyric") + "| " + ci("audio") + ")? " + ci("video") + "|" + ci("official audio") + "|" +
            ci("official") + "|" + ci("lyric video") + "|" + ci("lyrics") + "?|" + ci("audio") + "|" + ci("visualizer") + "|" + ci("music video") + "|" + ci("video") + "|" +
            ci("hd") + "|" + ci("hq") + "|" + ci("4k") + "|" + ci("remastered") + "(?: [0-9]{4})?|" + ci("explicit") + ")[)\\]]",
    )
    private val featBracket = Regex(WS + "*[(\\[](?:feat\\.?|ft\\.?|featuring|with)" + WS + "[^)\\]]*[)\\]]")
    private val featTail = Regex(WS + "(?:feat\\.?|ft\\.?|featuring)" + WS + NOT_EOL + "*")
    private val topic = Regex(WS + "*-" + WS + "*topic")
    private val apostrophes = Regex("[’']")
    private val symbols = Regex("[^\\p{L}\\p{N}' ]+")
    private val spaces = Regex("$WS+")
    private val leadingArtist = Regex("(" + NOT_EOL + "+?)" + WS + "+[-–—]" + WS + "+(" + NOT_EOL + "+)")

    /** [text] without its first match that runs to the end, followed by a space (JavaScript's `replace(/...$/, ' ')`). */
    private fun replaceAtEnd(text: String, regex: Regex): String {
        val at = matchStartAtEnd(regex, text)
        return if (at < 0) text else text.substring(0, at) + " "
    }

    /** Lower case, no "(feat. ...)", "[Official Video]" or " - Topic", single spaces. */
    fun normalizeKey(value: String): String {
        var key = value.lowercase().replace(noise, " ").replace(featBracket, " ")
        key = replaceAtEnd(key, featTail)
        key = replaceAtEnd(key, topic)
        return jsTrim(key.replace(apostrophes, "'").replace(symbols, " ").replace(spaces, " "))
    }

    /** A clean title for the lookup: drops "[Official Video]"-style tags and a leading "Artist - " the artist already covers. */
    fun cleanTitle(title: String, artist: String? = null): String {
        var clean = jsTrim(title.replace(noise, ""))
        if (!artist.isNullOrEmpty()) {
            val dash = leadingArtist.matchEntire(clean)
            if (dash != null && normalizeKey(dash.groupValues[1]) == normalizeKey(artist)) clean = jsTrim(dash.groupValues[2])
        }
        return clean
    }

    private fun queryString(pairs: List<Pair<String, String>>) =
        pairs.joinToString("&") { (key, value) -> Collections.formEncode(key) + "=" + Collections.formEncode(value) }

    /** The exact-match request, or null when the song's length is unknown (LRCLIB requires it). */
    fun getUrl(query: LyricsQuery): String? {
        val duration = query.duration?.takeIf { it > 0 } ?: return null
        val pairs = buildList {
            add("track_name" to query.title)
            add("artist_name" to query.artist)
            if (!query.album.isNullOrEmpty()) add("album_name" to query.album)
            add("duration" to floor(duration + 0.5).toLong().toString())
        }
        return "$LRCLIB/api/get?" + queryString(pairs)
    }

    fun searchUrl(query: LyricsQuery): String =
        "$LRCLIB/api/search?" + queryString(listOf("track_name" to query.title, "artist_name" to query.artist))

    /** Synced (LRC) text when there is any, otherwise plain text; null for instrumentals or empty records. */
    fun text(record: LrclibRecord?): String? {
        if (record == null || record.instrumental) return null
        record.syncedLyrics?.let(::jsTrim)?.takeIf { it.isNotEmpty() }?.let { return it }
        return record.plainLyrics?.let(::jsTrim)?.takeIf { it.isNotEmpty() }
    }

    private fun similar(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        if (a.contains(b) || b.contains(a)) return 0.8
        val words = a.split(' ').toSet()
        val other = b.split(' ')
        return other.count { it in words }.toDouble() / maxOf(words.size, other.size)
    }

    /**
     * The best search result for the song: its length within [DURATION_TOLERANCE] (or, when the song's length is unknown, an exact title
     * and artist match), the closest title and artist, and synced lyrics preferred among equals. Null when nothing fits.
     */
    fun choose(query: LyricsQuery, records: List<LrclibRecord>): LrclibRecord? {
        val title = normalizeKey(query.title)
        val artist = normalizeKey(query.artist)
        val duration = query.duration?.takeIf { it != 0.0 && !it.isNaN() }
        var best: LrclibRecord? = null
        var bestScore = 0.0
        for (record in records) {
            if (text(record) == null) continue
            val t = similar(title, normalizeKey(record.trackName.orEmpty()))
            val a = similar(artist, normalizeKey(record.artistName.orEmpty()))
            if (duration != null) {
                val length = record.duration
                if (length == null || abs(length - duration) > DURATION_TOLERANCE) continue
                if (t < 0.5 || a < 0.5) continue
            } else if (t < 1 || a < 1) continue
            val score = t * 2 + a + if (!record.syncedLyrics?.let(::jsTrim).isNullOrEmpty()) 0.25 else 0.0
            if (best == null || score > bestScore) {
                best = record
                bestScore = score
            }
        }
        return best
    }

    private fun JsonObject.string(key: String): String? = (members[key] as? JsonString)?.value

    private fun recordOf(json: JsonObject) = LrclibRecord(
        id = (json.members["id"] as? JsonNumber)?.value?.toLong(),
        trackName = json.string("trackName"),
        artistName = json.string("artistName"),
        albumName = json.string("albumName"),
        duration = (json.members["duration"] as? JsonNumber)?.value,
        instrumental = (json.members["instrumental"] as? JsonBool)?.value == true,
        plainLyrics = json.string("plainLyrics"),
        syncedLyrics = json.string("syncedLyrics"),
    )

    /** The lyrics in the answer to the exact-match request ([getUrl]); null when it holds none. Throws when [body] is not JSON. */
    fun exactText(body: String): String? = (parseJson(body) as? JsonObject)?.let { text(recordOf(it)) }

    /** The lyrics of the best record in the answer to the search request ([searchUrl]); null when none fits. Throws when [body] is not JSON. */
    fun searchText(query: LyricsQuery, body: String): String? {
        val records = (parseJson(body) as? JsonArray)?.items?.mapNotNull { (it as? JsonObject)?.let(::recordOf) } ?: return null
        return text(choose(query, records))
    }

    /**
     * What to do with an answer to one request. [status] is the HTTP status, or null when there was none (no network, a timeout);
     * [attempt] counts the tries made so far, from 1. Returns how long to wait before the next try, or null to stop: an answer, a real
     * "not found" or any other refusal (4xx except 429) is final; a busy service (429, 5xx) or a failed transfer is tried again,
     * three tries in all, after 2 s and then 6 s.
     */
    fun retryAfterMs(status: Int?, attempt: Int): Long? {
        if (!isBusy(status)) return null
        return retryDelaysMs.getOrNull(attempt - 1)
    }

    /** True when [status] (null: no answer at all) says the service could not help just now, as opposed to a real answer or refusal. */
    fun isBusy(status: Int?): Boolean = status == null || status == 429 || status >= 500

    /** "Find lyrics" on a song that has none. */
    const val FIND = "Find lyrics"

    /** Said above lyrics that have no line times. */
    const val UNTIMED = "These lyrics have no timing, so they can't follow the song."

    private val timedLine = Regex("(^|\n)[ \t]*\\[[0-9]{1,3}:[0-9]{1,2}")

    /** Whether lyrics carry line times, so they can follow the song. */
    fun isTimed(text: String?): Boolean = text != null && timedLine.containsMatchIn(text)

    /** Of the exact match and the search's best, the one that can follow the song; plain words only when nothing is timed. */
    fun preferTimed(exact: String?, found: String?): String? = when {
        isTimed(exact) -> exact
        isTimed(found) -> found
        else -> exact ?: found
    }

    /** The result of a search that ended: "found" with lyrics, "busy" when no lyrics came and a request was left unanswered, else "none". */
    fun outcome(found: Boolean, gaveUpOnBusy: Boolean): String = if (found) "found" else if (gaveUpOnBusy) "busy" else "none"

    /** The plain line for an [outcome]. Busy and not found read differently; for "found" the lyrics themselves appear, this line is for a menu that shows nothing else. */
    fun outcomeWords(outcome: String): String = when (outcome) {
        "found" -> "Lyrics added."
        "busy" -> "Could not reach the lyrics service. Try again later."
        else -> "No lyrics found"
    }
}
