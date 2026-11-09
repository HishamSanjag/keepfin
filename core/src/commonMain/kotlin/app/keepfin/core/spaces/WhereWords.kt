package app.keepfin.core.spaces

import app.keepfin.core.copies.copyProblemShort
import app.keepfin.core.groupDigits
import app.keepfin.core.music.isJsWhitespace

/** One place's state, as the screens know it: [state] is upToDate, tidying, copying, waiting, offline, problem or needsDecision. */
class PlaceStatus(
    val state: String,
    val at: Long? = null,
    val done: Int = 0,
    val total: Int = 0,
    val reason: String? = null,
    val unplugged: Boolean = false,
    val count: Int = 0,
)

/**
 * The words of "Where your files are": what a place holds, its state, the warnings about files only one place has, and the line of one
 * file. Both apps show exactly these words; the wording is Windows'. The name of the device itself ("this PC", "this phone") is a
 * parameter, and so is the time.
 */
object WhereWords {
    private fun plural(n: Long, one: String, many: String = one + "s"): String = groupDigits(n) + " " + (if (n == 1L) one else many)

    /** "just now", "2 min ago", "3 h ago", "yesterday", "3 days ago", "2 weeks ago", "4 months ago": rounded, never a date. */
    fun ago(at: Long, now: Long): String {
        val seconds = ((now - at).coerceAtLeast(0L) + 500L) / 1000L
        if (seconds < 45) return "just now"
        val minutes = (seconds + 30L) / 60L
        if (minutes < 60) return "$minutes min ago"
        val hours = (minutes + 30L) / 60L
        if (hours < 24) return "$hours h ago"
        val days = (hours + 12L) / 24L
        if (days == 1L) return "yesterday"
        if (days < 14) return "$days days ago"
        if (days < 60) return "${(2 * days + 7L) / 14L} weeks ago"
        return "${(days + 15L) / 30L} months ago"
    }

    /**
     * "Everything", "Music, Favourites", "Road trip and 2 more". A place that keeps whatever its device has is told by [held] of [of]
     * files; with nothing chosen and nothing held it reads "Nothing yet". [spaces] and [playlists] are names.
     */
    fun holds(all: Boolean, spaces: List<String>, favourites: Boolean, playlists: List<String>, held: Long?, of: Long?): String {
        if (all) return "Everything"
        val parts = spaces + (if (favourites) listOf("Favourites") else emptyList()) + playlists
        if (parts.isEmpty()) {
            if (held == null || of == null || held <= 0) return "Nothing yet"
            if (held >= of) return "Everything"
            return groupDigits(held) + " of " + plural(of, "file")
        }
        return if (parts.size <= 2) parts.joinToString(", ") else parts[0] + " and " + (parts.size - 1) + " more"
    }

    private fun trimEndDots(text: String): String {
        var end = text.length
        while (end > 0 && (text[end - 1] == '.' || isJsWhitespace(text[end - 1]))) end--
        return text.substring(0, end)
    }

    /** A waiting reason as a state, whole: "Waiting for Wi-Fi." reads "waiting for Wi-Fi". The phone shows its full sentences this way. */
    fun reason(text: String): String = trimEndDots(text).replaceFirstChar { it.lowercase() }

    /**
     * The state in a few lowercase words: "up to date 2 min ago", "copying · 40 of 210", "waiting for Wi-Fi",
     * "last copied 3 days ago · not plugged in", "offline · last seen 5 min ago". [toCopy] is a copy (a drive, a cloud account, a device
     * that keeps one), not a device.
     */
    fun state(status: PlaceStatus, toCopy: Boolean, now: Long): String = when (status.state) {
        "upToDate" -> status.at?.let { "up to date " + ago(it, now) } ?: "up to date"
        // every file is there and only Keepfin's own old records are being removed from the account
        "tidying" -> "up to date · tidying"
        "copying" -> if (status.total > 0) "copying · " + groupDigits(status.done.toLong()) + " of " + groupDigits(status.total.toLong()) else "copying"
        "waiting" -> status.reason?.let(::reason)?.takeIf { it.isNotEmpty() && it.length <= 40 } ?: "waiting"
        "offline" -> {
            val at = status.at
            when {
                status.unplugged -> if (at != null) "last copied " + ago(at, now) + " · not plugged in" else "not plugged in"
                toCopy -> if (at != null) "last copied " + ago(at, now) + " · offline" else "offline"
                else -> if (at != null) "offline · last seen " + ago(at, now) else "offline"
            }
        }
        "problem" -> copyProblemShort(status.count)
        else -> "needs a decision"
    }

    /** "12 videos are only on Desk PC", "1 song is only on this PC". [kind] is songs, videos or other; [place] is the name to show. */
    fun single(kind: String, count: Long, place: String): String {
        val noun = when (kind) {
            "songs" -> plural(count, "song")
            "videos" -> plural(count, "video")
            else -> plural(count, "other file", "other files")
        }
        return noun + " " + (if (count == 1L) "is" else "are") + " only on " + place
    }

    /** What the page says when this device is the only place: "Everything is on this PC only. Keep a copy on another device, a drive or the cloud." */
    fun onePlace(thisName: String): String = "Everything is on $thisName only. Keep a copy on another device, a drive or the cloud."

    /** The per-file line: "On: this PC, Pixel 9 (offline), Google Drive", "Only on this PC", "Not on any device yet". [names] are shown names. */
    fun itemLine(names: List<String>, offline: List<Boolean>): String {
        if (names.isEmpty()) return "Not on any device yet"
        val labels = names.mapIndexed { i, name -> if (offline.getOrNull(i) == true) "$name (offline)" else name }
        return if (labels.size == 1) "Only on " + labels[0] else "On: " + labels.joinToString(", ")
    }
}
