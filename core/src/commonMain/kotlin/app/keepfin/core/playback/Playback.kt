package app.keepfin.core.playback

import app.keepfin.core.music.nfkd

/**
 * A run of skips in one direction: [total] seconds so far (negative = back) and [at], the time of the last one in
 * milliseconds. Repeated skips the same way inside [Playback.SKIP_WINDOW_MS] add up ("+10 s", "+20 s", "+30 s").
 */
data class SkipRun(val total: Double, val at: Double)

/**
 * The decisions every Keepfin player makes the same way on both apps: skips that add up, where playback resumes,
 * chapters, which item plays next, the speeds offered and the time label. No clock calls: time is a parameter.
 * Seconds are Doubles throughout (JavaScript numbers); the phone converts its milliseconds at the call.
 */
object Playback {
    /** Skips the same way within this long add up; on the phone, taps inside it keep skipping (YouTube). */
    const val SKIP_WINDOW_MS = 1000.0

    /** The speeds a player offers. */
    val SPEEDS: List<Double> = listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)

    // ---- skips

    /** Adds [delta] seconds to [prev] when it points the same way and came within the window; otherwise starts a new run. */
    fun accumulateSkip(prev: SkipRun?, delta: Double, now: Double): SkipRun =
        if (prev != null && continues(prev, delta > 0, now)) SkipRun(prev.total + delta, now) else SkipRun(delta, now)

    /** Whether a skip that way at [now] continues [run] (same direction, still inside the window). */
    fun continues(run: SkipRun?, forward: Boolean, now: Double): Boolean =
        run != null && (run.total > 0) == forward && now - run.at <= SKIP_WINDOW_MS

    /** "+10 s" / "−15 s" (a real minus sign). */
    fun skipLabel(total: Double): String = (if (total < 0) "−" else "+") + roundSeconds(total) + " s"

    /** What a screen reader says once the run settles. */
    fun skipAnnouncement(total: Double): String = "Skipped " + (if (total < 0) "back " else "forward ") + roundSeconds(total) + " seconds"

    private fun roundSeconds(total: Double): Long = kotlin.math.floor(kotlin.math.abs(total) + 0.5).toLong()

    // ---- resume

    /**
     * Where playback starts, in seconds: the saved [position] when it is over 10 s, before the last 8 % of the item
     * (any spot when the [duration] is unknown) and the item is not [watched]. Null starts from the beginning.
     */
    fun resumeFrom(position: Double?, duration: Double?, watched: Boolean): Double? {
        if (position == null || watched || !(position > 10.0)) return null
        if (duration != null && duration > 0.0 && position >= duration * 0.92) return null
        return position
    }

    // ---- chapters (starts in seconds, ascending)

    /** The chapter playing at [time]: its index, or -1 before the first one or without chapters. */
    fun chapterAt(starts: List<Double>, time: Double): Int = starts.indexOfLast { it <= time }

    /**
     * Where "previous chapter" (dir -1) and "next chapter" (dir 1) go. Next: the following chapter's start. Previous: the
     * start of the current chapter when more than 3 s into it, else the chapter before (0 from the first). Null: nowhere.
     */
    fun chapterTarget(starts: List<Double>, time: Double, dir: Int): Double? {
        if (starts.isEmpty()) return null
        val now = chapterAt(starts, time)
        if (dir > 0) return starts.getOrNull(now + 1)
        if (now >= 0 && time - starts[now] > 3.0) return starts[now]
        return if (now > 0) starts[now - 1] else 0.0
    }

    /** Whether moving from [from] to [to] lands in another chapter (the phone's scrub tick). */
    fun crossedChapter(starts: List<Double>, from: Double, to: Double): Boolean =
        starts.isNotEmpty() && chapterAt(starts, from) != chapterAt(starts, to)

    // ---- what plays next

    /** The list that decides next and previous: the one the item was opened from, or (opened alone) its folder. */
    fun playbackList(context: List<String>, folder: List<String>?): List<String> =
        if (context.size <= 1 && folder != null && folder.size > 1) folder else context

    /** The id after (dir 1) or before (dir -1) [id] in [list]; the caller passes only items that still exist. */
    fun neighbour(list: List<String>, id: String, dir: Int): String? {
        val at = list.indexOf(id)
        if (at < 0) return null
        return list.getOrNull(at + if (dir > 0) 1 else -1)
    }

    /** The ids of a folder's items in natural title order ("Episode 2" before "Episode 10"); ties keep id order. */
    fun folderOrder(ids: List<String>, titles: List<String>): List<String> {
        require(ids.size == titles.size) { "ids and titles differ in length" }
        val folded = titles.map(::fold)
        return ids.indices.sortedWith { a, b -> naturalCompare(folded[a], folded[b]).let { if (it != 0) it else ids[a].compareTo(ids[b]) } }.map { ids[it] }
    }

    /** Title order: case and accents ignored, runs of digits compared as numbers. */
    fun compareTitles(a: String, b: String): Int = naturalCompare(fold(a), fold(b))

    private fun fold(text: String): String {
        val out = StringBuilder()
        for (c in nfkd(text)) if (c.code !in 0x0300..0x036F) out.append(c) // drop combining accents
        return out.toString().lowercase()
    }

    private fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val na = a.substring(i, ei).trimStart('0')
                val nb = b.substring(j, ej).trimStart('0')
                if (na.length != nb.length) return if (na.length < nb.length) -1 else 1
                val c = na.compareTo(nb)
                if (c != 0) return if (c < 0) -1 else 1
                i = ei
                j = ej
            } else {
                if (a[i] != b[j]) return if (a[i] < b[j]) -1 else 1
                i++
                j++
            }
        }
        val left = (a.length - i) - (b.length - j)
        return if (left < 0) -1 else if (left > 0) 1 else 0
    }

    // ---- labels

    /** "2:05", "1:02:03"; negative or unknown time is "0:00". */
    fun clock(seconds: Double): String {
        val total = if (seconds.isNaN() || seconds < 0) 0L else kotlin.math.floor(seconds).toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        fun two(n: Long) = if (n < 10) "0$n" else "$n"
        return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
    }

    /** How much is left to watch: "0:11 left" under a minute, then "4 min left", "1 h 12 min left" and "1 h left" (whole minutes, rounded down). */
    fun timeLeft(seconds: Double): String {
        val total = if (seconds.isNaN() || seconds < 0) 0L else kotlin.math.floor(seconds).toLong()
        if (total < 60) return "${clock(seconds)} left"
        val minutes = total / 60
        if (minutes < 60) return "$minutes min left"
        return if (minutes % 60 == 0L) "${minutes / 60} h left" else "${minutes / 60} h ${minutes % 60} min left"
    }
}
