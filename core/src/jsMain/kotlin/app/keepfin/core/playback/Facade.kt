@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.playback

// The JavaScript surface of the playback core (see gui/shared/core/playback.ts): numbers and string arrays in and out.

@JsExport
fun playbackSpeeds(): Array<Double> = Playback.SPEEDS.toTypedArray()

@JsExport
fun playbackSkipWindowMs(): Double = Playback.SKIP_WINDOW_MS

/** [total, at] of the run after one more skip of `delta` seconds; `prevTotal`/`prevAt` null when there is no run. */
@JsExport
fun playbackAccumulateSkip(prevTotal: Double?, prevAt: Double?, delta: Double, now: Double): Array<Double> {
    val prev = if (prevTotal != null && prevAt != null) SkipRun(prevTotal, prevAt) else null
    val run = Playback.accumulateSkip(prev, delta, now)
    return arrayOf(run.total, run.at)
}

@JsExport
fun playbackSkipLabel(total: Double): String = Playback.skipLabel(total)

@JsExport
fun playbackSkipAnnouncement(total: Double): String = Playback.skipAnnouncement(total)

@JsExport
fun playbackResumeFrom(position: Double?, duration: Double?, watched: Boolean): Double? = Playback.resumeFrom(position, duration, watched)

@JsExport
fun playbackChapterAt(starts: Array<Double>, time: Double): Int = Playback.chapterAt(starts.toList(), time)

@JsExport
fun playbackChapterTarget(starts: Array<Double>, time: Double, dir: Int): Double? = Playback.chapterTarget(starts.toList(), time, dir)

@JsExport
fun playbackCrossedChapter(starts: Array<Double>, from: Double, to: Double): Boolean = Playback.crossedChapter(starts.toList(), from, to)

@JsExport
fun playbackList(context: Array<String>, folder: Array<String>?): Array<String> = Playback.playbackList(context.toList(), folder?.toList()).toTypedArray()

@JsExport
fun playbackNeighbour(list: Array<String>, id: String, dir: Int): String? = Playback.neighbour(list.toList(), id, dir)

@JsExport
fun playbackFolderOrder(ids: Array<String>, titles: Array<String>): Array<String> = Playback.folderOrder(ids.toList(), titles.toList()).toTypedArray()

@JsExport
fun playbackCompareTitles(a: String, b: String): Int = Playback.compareTitles(a, b)

@JsExport
fun playbackClock(seconds: Double): String = Playback.clock(seconds)

@JsExport
fun playbackTimeLeft(seconds: Double): String = Playback.timeLeft(seconds)
