package app.keepfin.core.spaces

import app.keepfin.core.groupDigits

/**
 * The words around a downloaded collection ("Playlist · 31 songs · following, checked 12 min ago", "Following · every 6 hours").
 * Both apps show exactly these words; the wording is the phone's. Times are parameters and so is the date written for something
 * older than a week: the core reads no clock and knows no locale.
 */
object CollectionWords {
    private const val MINUTE_MS = 60_000L

    /** "Playlist", "Album", "Channel" or "Yours". */
    fun kind(kind: String): String = when (kind) {
        "playlist" -> "Playlist"
        "album" -> "Album"
        "channel" -> "Channel"
        "own" -> "Yours"
        else -> kind
    }

    /** "hour", "day", "week" or "6 hours": what follows "every". */
    fun every(hours: Int): String = when (hours) { 1 -> "hour"; 168 -> "week"; 24 -> "day"; else -> "$hours hours" }

    /** "every hour", "every 6 hours". */
    fun everyWords(hours: Int): String = "every " + every(hours)

    /** The choice of an interval: "1 hour", "6 hours", "24 hours", "7 days". */
    fun interval(hours: Int): String = when (hours) { 1 -> "1 hour"; 24 -> "24 hours"; 168 -> "7 days"; else -> "$hours hours" }

    /**
     * "Following · every 6 hours", "Checks when you tap Check now" or "Not following". Null for a collection that cannot be followed.
     * [mode] is auto, manual or off, null when it has no follow settings; [press] is the word for the pointer ("tap", "click").
     */
    fun followLine(kind: String, sourceUrl: String?, mode: String?, intervalHours: Int, press: String = "tap"): String? {
        if (!Collections.isFollowable(kind, sourceUrl)) return null
        return when (mode) {
            "auto" -> "Following · " + everyWords(intervalHours)
            "manual" -> "Checks when you $press Check now"
            else -> "Not following"
        }
    }

    /** "just now", "12 min ago", "3 h ago", "yesterday", "4 days ago", or [date] of the time from a week on. */
    fun ago(timestamp: Long, now: Long, date: (Long) -> String): String {
        val minutes = (now - timestamp).coerceAtLeast(0L) / MINUTE_MS
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 24 * 60 -> "${minutes / 60} h ago"
            minutes < 48 * 60 -> "yesterday"
            minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)} days ago"
            else -> date(timestamp)
        }
    }

    /** "3 songs", "1 item": [noun] in the singular. */
    fun count(present: Int, noun: String): String = "${groupDigits(present.toLong())} ${if (present == 1) noun else noun + "s"}"

    /** The second line of the follow card: "Checked 12 min ago · 3 new songs added", the last error, or "Not checked yet". */
    fun checkedLine(lastCheckedAt: Long?, lastError: String?, newCount: Int, noun: String, now: Long, date: (Long) -> String): String {
        if (lastError != null) return lastError
        val at = lastCheckedAt ?: return "Not checked yet"
        return "Checked " + ago(at, now, date) + if (newCount > 0) " · $newCount new ${if (newCount == 1) noun else noun + "s"} added" else ""
    }

    /** "following, checked 12 min ago", "manual", "not following"; null when there is nothing to say (not followable, no settings, an album that is not followed). */
    fun status(kind: String, sourceUrl: String?, mode: String?, lastCheckedAt: Long?, now: Long, date: (Long) -> String): String? {
        if (!Collections.isFollowable(kind, sourceUrl)) return null
        return when (mode) {
            "auto" -> "following" + (lastCheckedAt?.let { ", checked " + ago(it, now, date) } ?: "")
            "manual" -> "manual"
            "off" -> if (kind == "album") null else "not following"
            else -> null
        }
    }

    /** "Playlist · 31 songs · following, checked 12 min ago": the kind, how many, the follow status, then any [extras] the screen adds. */
    fun rowSubtitle(
        kind: String, sourceUrl: String?, noun: String, present: Int, mode: String?, lastCheckedAt: Long?, now: Long, date: (Long) -> String,
        extras: List<String> = emptyList(),
    ): String = (listOfNotNull(kind(kind), count(present, noun), status(kind, sourceUrl, mode, lastCheckedAt, now, date)) + extras).joinToString(" · ")
}
