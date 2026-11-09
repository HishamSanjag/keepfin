package app.keepfin.core.home

import app.keepfin.core.spaces.artistNames

/** A release found for a followed artist. [foundAt] is when Keepfin first saw it: the feed is dated by that. */
class FeedRelease(
    val id: String,
    val artistKey: String,
    val title: String,
    val foundAt: Long,
    val kept: Boolean,
)

class FeedGroup(val label: String, val ids: List<String>)

/**
 * The follow feed of Home ("New from artists you follow"), written once for both apps: who is followed, what counts as new, how the
 * feed is grouped and worded. A release is dated by when it was found (a follow check cannot always read a release date), and the first
 * check of a newly followed artist only records what already exists, so following never floods the feed with a back catalogue.
 */
object ReleaseFeed {
    const val DAY = 86_400_000L
    const val THIS_WEEK_DAYS = 7
    const val WINDOW_DAYS = 31
    const val THIS_WEEK = "This week"
    const val EARLIER = "Earlier this month"

    /** The key an artist is followed under: the first credited artist, folded ("Pale Cassette feat. X" and "pale  cassette" are one). */
    fun artistKey(name: String?): String? = artistNames(name).firstOrNull()?.takeIf { it.isNotEmpty() }

    /** The ids of the releases a check just read that the app has not seen before. The first check of an artist reports none. */
    fun fresh(known: Set<String>, found: List<String>, firstCheck: Boolean): List<String> =
        if (firstCheck) emptyList() else found.distinct().filter { it !in known }

    /** The feed: releases not yet kept, found within the last month, newest first, in "This week" and "Earlier this month". */
    fun group(releases: List<FeedRelease>, now: Long): List<FeedGroup> {
        val live = releases.filter { !it.kept && it.foundAt <= now && now - it.foundAt < WINDOW_DAYS * DAY }
            .sortedWith(compareByDescending<FeedRelease> { it.foundAt }.thenBy { it.id })
        val week = live.filter { now - it.foundAt < THIS_WEEK_DAYS * DAY }
        val earlier = live.filter { now - it.foundAt >= THIS_WEEK_DAYS * DAY }
        return listOfNotNull(
            week.takeIf { it.isNotEmpty() }?.let { FeedGroup(THIS_WEEK, it.map(FeedRelease::id)) },
            earlier.takeIf { it.isNotEmpty() }?.let { FeedGroup(EARLIER, it.map(FeedRelease::id)) },
        )
    }

    /** The artists (keys) with something in the feed: the dot beside their name in Following. */
    fun artistsWithNew(releases: List<FeedRelease>, now: Long): Set<String> {
        val shown = group(releases, now).flatMap { it.ids }.toSet()
        return releases.filter { it.id in shown }.map { it.artistKey }.toSet()
    }

    /** The channel id of the first search-result artist that is surely [key]: a folded-name match and a channel id (UC...). Null rather than a guess. */
    fun pickChannel(artists: List<Pair<String, String>>, key: String): String? =
        artists.firstOrNull { artistKey(it.first) == key && it.second.startsWith("UC") }?.second

    /** "Album - Salt Season" gives (Album, Salt Season); a title without a kind gives (null, title). */
    fun splitKind(title: String): Pair<String?, String> {
        for (kind in listOf("Album", "Single", "EP")) {
            for (dash in listOf(" - ", " – ")) if (title.startsWith(kind + dash)) return kind to title.removePrefix(kind + dash).trim()
        }
        return null to title
    }

    /** "today", "yesterday", "3 days ago", "1 week ago", "2 weeks ago", "1 month ago". */
    fun ageWords(foundAt: Long, now: Long): String {
        val days = ((now - foundAt).coerceAtLeast(0L) / DAY).toInt()
        return when {
            days < 1 -> "today"
            days < 2 -> "yesterday"
            days < 7 -> "$days days ago"
            days < 14 -> "1 week ago"
            days < 31 -> "${days / 7} weeks ago"
            else -> "${days / 30} month${if (days / 30 > 1) "s" else ""} ago"
        }
    }
}
