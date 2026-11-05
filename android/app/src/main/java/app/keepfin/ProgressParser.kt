package app.keepfin

import app.keepfin.core.progress.PhaseKey
import app.keepfin.core.progress.ProgressChange
import app.keepfin.core.progress.ProgressPlatform
import kotlin.math.roundToLong
import app.keepfin.core.progress.ProgressParser as CoreParser

/**
 * Engine / aria2c output lines → aggregated transfer progress and human processing phases.
 *
 * The parsing and the arithmetic are the shared core's (app.keepfin.core.progress, the same code the desktop app runs);
 * this class keeps Android's types and the wording of each phase on screen.
 */
class ProgressParser(expectedSize: Long? = null) {
    data class Snapshot(
        val percent: Float,
        val speed: Long?,
        val eta: Long?,
        val downloadedBytes: Long?,
        val totalBytes: Long?,
        val phase: String?,
        val processing: Boolean,
    )

    private val core = CoreParser(ProgressPlatform.ANDROID, expectedSize = expectedSize?.toDouble())

    val sawTransfer: Boolean get() = core.sawTransfer
    val usedAria2c: Boolean get() = core.usedAria2c

    val snapshot: Snapshot
        get() {
            val s = core.snapshot
            return Snapshot(
                // The job reaches 100% only when it is saved: the screens never show more than 99% before that.
                percent = minOf(s.percent, 99.0).toFloat(),
                speed = s.speed?.roundToLong(),
                eta = s.eta?.roundToLong(),
                downloadedBytes = s.downloadedBytes?.toLong(),
                totalBytes = s.totalBytes?.toLong(),
                phase = s.phase?.let { phaseText(it, s.phaseDetail) },
                processing = s.processing,
            )
        }

    /** Returns true when the line changed the visible state. */
    fun accept(raw: String): Boolean = core.accept(raw) != ProgressChange.NONE

    private fun phaseText(key: String, detail: String?): String = when (key) {
        PhaseKey.MERGE -> "Merging"
        PhaseKey.EXTRACT_AUDIO -> if (detail != null) "Converting to ${detail.uppercase().take(5)}" else "Preparing audio"
        PhaseKey.EMBED_COVER -> "Embedding cover art"
        PhaseKey.CONVERT_COVER -> "Preparing cover art"
        PhaseKey.METADATA -> "Adding metadata"
        PhaseKey.EMBED_SUBS -> "Embedding subtitles"
        PhaseKey.CONVERT_SUBS -> "Converting subtitles"
        PhaseKey.SPONSORBLOCK -> "Checking SponsorBlock"
        PhaseKey.MODIFY_CHAPTERS -> "Removing sponsor segments"
        PhaseKey.SPLIT_CHAPTERS -> "Splitting chapters"
        PhaseKey.FIXUP -> "Fixing container"
        PhaseKey.REMUX -> "Remuxing"
        PhaseKey.CONVERT_VIDEO -> "Converting video"
        else -> key
    }
}
