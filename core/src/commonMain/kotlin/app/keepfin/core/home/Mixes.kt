package app.keepfin.core.home

import app.keepfin.core.spaces.artistNames

/** What a mix needs to know about one song. [artist] is the credit line (artist, else album artist). */
class MixSong(
    val id: String,
    val artist: String?,
    val plays: Int,
    val lastPlayedAt: Long?,
    val addedAt: Long,
    val favorite: Boolean,
)

/** One tile of "Made from your listening": [ids] are the songs, in play order. [id] is stable (a key for lists and tests). */
class Mix(val id: String, val title: String, val blurb: String, val ids: List<String>)

/**
 * "Made from your listening" (Home on both apps): mixes built only from the person's own plays and library. No network, no guessing:
 * a mix with fewer than [MIN_ROW] songs is not returned. Time is a parameter; [utcOffsetMinutes] is the local clock's offset
 * (the late-night mix reads the hour of the last play on the person's own clock).
 *
 * The plays are each song's lifetime count and last play, so "On repeat" is the most played among the songs played in the last 30 days.
 */
object Mixes {
    const val MIN_ROW = 5
    const val DAY = 86_400_000L
    private const val MINUTE = 60_000L

    const val ON_REPEAT = "on-repeat"
    const val FORGOTTEN = "forgotten"
    const val RECENT = "recent"
    const val NEVER = "never"
    const val BECAUSE = "because"
    const val LATE_NIGHT = "late-night"

    private fun byPlays(a: MixSong, b: MixSong): Int =
        if (a.plays != b.plays) b.plays - a.plays else compareValues(b.lastPlayedAt ?: 0L, a.lastPlayedAt ?: 0L)

    /** The mixes that have enough songs, in the order Home shows them, each with at most [limit] songs. */
    fun build(songs: List<MixSong>, now: Long, utcOffsetMinutes: Int = 0, limit: Int = 30): List<Mix> {
        val month = now - 30 * DAY
        val stale = now - 60 * DAY
        val out = ArrayList<Mix>()
        fun add(id: String, title: String, blurb: String, list: List<MixSong>) {
            if (list.size >= MIN_ROW) out.add(Mix(id, title, blurb, list.take(limit).map { it.id }))
        }
        add(ON_REPEAT, "On repeat", "Most played in the last 30 days",
            songs.filter { it.plays > 0 && (it.lastPlayedAt ?: 0L) >= month }.sortedWith(::byPlays))
        // A favourite you never played is not "forgotten": it belongs to Never played.
        add(FORGOTTEN, "Forgotten favourites", "Loved, not played for 60 days",
            songs.filter { (it.favorite || it.plays >= 5) && it.lastPlayedAt != null && it.lastPlayedAt < stale }
                .sortedWith(compareBy<MixSong> { it.lastPlayedAt ?: 0L }.thenByDescending { it.plays }.thenBy { it.id }))
        add(RECENT, "Recently added music", "Newest songs you kept",
            songs.sortedWith(compareByDescending<MixSong> { it.addedAt }.thenBy { it.id }))
        add(NEVER, "Never played", "Kept but still unheard",
            songs.filter { it.plays == 0 && it.lastPlayedAt == null }.sortedWith(compareByDescending<MixSong> { it.addedAt }.thenBy { it.id }))
        because(songs, month)?.let { (name, key) ->
            add(BECAUSE, "Because you play $name", "More from them, in your library",
                songs.filter { key in artistNames(it.artist) }
                    .sortedWith(compareBy<MixSong> { it.lastPlayedAt ?: 0L }.thenByDescending { it.plays }.thenBy { it.id }))
        }
        add(LATE_NIGHT, "Late-night mix", "Songs you play after 10 pm",
            songs.filter { it.plays > 0 && it.lastPlayedAt != null && isLateNight(it.lastPlayedAt, utcOffsetMinutes) }.sortedWith(::byPlays))
        return out
    }

    /** From 10 pm until 4 am on the person's clock. */
    fun isLateNight(at: Long, utcOffsetMinutes: Int): Boolean {
        val minutes = floorDiv(at, MINUTE) + utcOffsetMinutes
        val hour = floorMod(floorDiv(minutes, 60L), 24L).toInt()
        return hour >= 22 || hour < 4
    }

    private fun floorDiv(a: Long, b: Long): Long = if (a >= 0) a / b else -((-a + b - 1) / b)
    private fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b

    /** The artist played most (by plays) among the songs played since [since]: the name to show and the folded key. */
    private fun because(songs: List<MixSong>, since: Long): Pair<String, String>? {
        val totals = HashMap<String, Int>()
        val shown = HashMap<String, Pair<Int, String>>()
        for (s in songs) {
            if (s.plays <= 0 || (s.lastPlayedAt ?: 0L) < since) continue
            val names = artistNames(s.artist)
            val key = names.firstOrNull() ?: continue
            totals[key] = (totals[key] ?: 0) + s.plays
            // the name as the person's own tags write it: from the most played song that credits only this artist
            val raw = s.artist
            if (names.size == 1 && raw != null) {
                val best = shown[key]
                if (best == null || s.plays > best.first) shown[key] = s.plays to cleanName(raw)
            }
        }
        val top = totals.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).firstOrNull() ?: return null
        return (shown[top.key]?.second ?: top.key) to top.key
    }

    private fun cleanName(raw: String): String {
        var name = raw.trim()
        if (name.lowercase().endsWith(" - topic")) name = name.dropLast(8).trim()
        return name
    }
}
