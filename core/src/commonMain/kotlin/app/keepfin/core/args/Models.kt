package app.keepfin.core.args

/** What the user asked to download: the structured intent both apps send, never a raw argument. */
sealed class DownloadFormat {
    /** [maxHeight] is an upper bound (null = best); [codec] is auto|h264|hevc|vp9|av1; [container] is mp4|mkv|webm. */
    data class Video(
        val maxHeight: Int? = null,
        val formatId: String? = null,
        val audioFormatId: String? = null,
        val fps: Int? = null,
        val codec: String = "auto",
        val container: String = "mp4",
        /** Set when the size is made by converting the downloaded [formatId] (formats.Conversion): the short side to convert to, below [maxHeight]. */
        val scaleHeight: Int? = null,
    ) : DownloadFormat()

    /** [target] is mp3|m4a|opus|flac|wav|original; [quality] is best|320|256|192|128. */
    data class Audio(val target: String, val quality: String = "best", val formatId: String? = null) : DownloadFormat()
}

data class SubtitleSettings(val enabled: Boolean, val languages: String, val auto: Boolean, val embed: Boolean)

/**
 * The optional extras of a download. A null flag is "not set" (the apps treat it as off); validation returns exactly the
 * values that were given, so the desktop keeps what it saved.
 */
data class PlanOptions(
    val subtitles: SubtitleSettings? = null,
    val embedThumbnail: Boolean? = null,
    val embedMetadata: Boolean? = null,
    val embedChapters: Boolean? = null,
    val writeThumbnail: Boolean? = null,
    val writeDescription: Boolean? = null,
    val writeInfoJson: Boolean? = null,
    val splitChapters: Boolean? = null,
    /** off | mark | remove */
    val sponsorBlock: String? = null,
    /** Seconds. */
    val sectionStart: Double? = null,
    val sectionEnd: Double? = null,
)

/**
 * The documented differences between the apps, as data.
 * [coverArtForOpusFlac]: embedding cover art into opus, flac and original-audio files needs an optional Python module. The
 * desktop always has it; Android has it only when its engine reports it (ARGS-9 in fixtures/engine/DECISIONS.md).
 * Tool and runtime paths, network, cookies, multi-connection transfers and the progress/output flags are not part of the
 * plan: each app adds its own around [DownloadPlan.args].
 */
data class PlanPlatform(val coverArtForOpusFlac: Boolean = true)

data class AudioPlan(
    /** The target format, or "original" to keep the source stream. */
    val format: String,
    /** The conversion quality argument for mp3|m4a|opus ("0" is the best VBR); null when the format ignores it. */
    val quality: String?,
)

data class SubtitlePlan(val langs: String, val auto: Boolean, val embed: Boolean)

/** A time range in seconds; [end] is null for "to the end". */
data class SectionPlan(val start: Double, val end: Double?)

/**
 * The semantic download plan: every decision, none of the spelling. [DownloadPlan.args] spells it as the download engine's
 * arguments. The cover-art flags are already gated by the container and the platform.
 */
data class Plan(
    val selector: String,
    val sort: String?,
    val merge: String?,
    val remux: String?,
    val audio: AudioPlan?,
    val subtitles: SubtitlePlan?,
    val embedThumbnail: Boolean,
    val squareCover: Boolean,
    val writeThumbnail: Boolean,
    val embedMetadata: Boolean,
    val embedChapters: Boolean,
    /** mark | remove | null */
    val sponsorBlock: String?,
    val section: SectionPlan?,
    val splitChapters: Boolean,
    /** The output template for the per-chapter files when [splitChapters] is on. */
    val chapterTemplate: String?,
    val writeDescription: Boolean,
    val writeInfoJson: Boolean,
)

/** Source metadata written into resolver downloads (Spotify, Apple Music or Deezer to a YouTube Music match). */
data class SourceTags(
    val title: String,
    val artist: String?,
    val album: String?,
    val track: Int? = null,
    val year: Int? = null,
    /** The recording's ISRC when the source service gives one. */
    val isrc: String? = null,
)

/** The size facts the label and the estimate need about one video quality. */
data class LabelVideo(val formatId: String, val height: Int, val fps: Int?, val hdr: Boolean, val size: Long?, val sizeApprox: Boolean = false, val hasAudio: Boolean = false, val convertedFrom: Int? = null)

data class LabelAudio(val formatId: String, val size: Long?, val sizeApprox: Boolean = false, val codec: String = "other")

/**
 * The label and the expected size of a request. [approx] is true whenever [expectedSize] is an estimate rather than the
 * size of the one stream that will be downloaded (a conversion from a bitrate, or the best audio stream of the item).
 */
data class Quote(val label: String, val expectedSize: Long?, val approx: Boolean)
