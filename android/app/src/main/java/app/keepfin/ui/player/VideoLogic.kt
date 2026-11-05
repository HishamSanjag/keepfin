package app.keepfin.ui.player

import app.keepfin.core.playback.Playback

/** A run of skips in one direction: the shared core's run (seconds so far, negative = back; the time of the last one in ms). */
internal typealias SkipRun = app.keepfin.core.playback.SkipRun

internal val SkipRun.forward: Boolean get() = total > 0

/**
 * The phone video player's side of the shared playback core (`core/`, app.keepfin.core.playback; the cases in
 * fixtures/engine/playback): the decisions both apps make the same way come from there; the phone's milliseconds are
 * converted at this boundary. Only the phone's own wording (the speed pill, the sleep row) and constants live here.
 */
internal object VideoLogic {
    const val SKIP_STEP_MS = 10_000L
    /** Skips the same way within this long add up; taps inside it keep skipping instead of toggling the controls. */
    val SKIP_WINDOW_MS: Long = Playback.SKIP_WINDOW_MS.toLong()
    const val COUNTDOWN_SECONDS = 8

    /** The speeds a player offers (the music player keeps its own list). */
    val SPEEDS: List<Float> = Playback.SPEEDS.map { it.toFloat() }

    /** Adds one 10 s skip that way to [prev] (adding up inside the window). */
    fun accumulate(prev: SkipRun?, forward: Boolean, now: Long): SkipRun =
        Playback.accumulateSkip(prev, (if (forward) SKIP_STEP_MS else -SKIP_STEP_MS) / 1000.0, now.toDouble())

    /** Whether a tap at [now] continues [run] (same side, still inside the window). */
    fun continues(run: SkipRun?, forward: Boolean, now: Long): Boolean = Playback.continues(run, forward, now.toDouble())

    fun skipLabel(run: SkipRun): String = Playback.skipLabel(run.total)

    fun skipAnnouncement(run: SkipRun): String = Playback.skipAnnouncement(run.total)

    /** Where playback starts, in milliseconds (0 = from the beginning); the rule is the core's. Seconds in. */
    fun resumeStartMs(positionSec: Double?, durationSec: Double?, watched: Boolean): Long =
        Playback.resumeFrom(positionSec, durationSec, watched)?.let { (it * 1000).toLong() } ?: 0L

    /** The chapter playing at [ms] (index into [startsMs], sorted), or -1. */
    fun chapterIndexAt(startsMs: List<Long>, ms: Long): Int = Playback.chapterAt(startsMs.map { it / 1000.0 }, ms / 1000.0)

    /** Whether going from [fromMs] to [toMs] crosses into another chapter (the scrub tick). */
    fun crossedChapter(startsMs: List<Long>, fromMs: Long, toMs: Long): Boolean =
        Playback.crossedChapter(startsMs.map { it / 1000.0 }, fromMs / 1000.0, toMs / 1000.0)

    /** "Speed 1.5×" for the pill. */
    fun speedPill(speed: Float): String = "Speed " + speedLabel(speed)

    fun sleepSummary(active: Boolean, endOfTrack: Boolean, remainingMs: Long): String =
        if (!active) "Off" else if (endOfTrack) "End of video" else sleepClock(remainingMs).let { "$it left" }

    /** "2:05", "1:02:03": the time label every Keepfin player uses. */
    fun timeLabel(ms: Long): String = Playback.clock(ms / 1000.0)
}
