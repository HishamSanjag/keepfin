package app.keepfin.core.args

import app.keepfin.core.choice.ChoiceAudio
import app.keepfin.core.choice.ChoiceSize
import app.keepfin.core.choice.ChoiceVideo
import kotlin.math.floor

/**
 * The label and the expected size of a queued download (ARGS-13 in fixtures/engine/DECISIONS.md). Wording is the desktop's
 * ("1080p60 · MP4 · H.264", "MP3 · 320 kbps"); the estimate is Android's richer one, which also covers audio conversions.
 */
object Labels {
    private val codecNames = mapOf("h264" to "H.264", "vp9" to "VP9", "av1" to "AV1", "hevc" to "HEVC")

    /** JavaScript Math.round. */
    private fun round(x: Double): Long = floor(x + 0.5).toLong()

    /**
     * 1000-based unit steps divided by 1024, like the desktop's `formatBytes`: whole numbers from 100 (or in bytes), else one
     * decimal rounded exactly like JavaScript's toFixed(1). Empty for nothing usable.
     */
    fun formatBytes(bytes: Double): String {
        if (!(bytes > 0) || !bytes.isFinite()) return ""
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var value = bytes
        var unit = 0
        while (value >= 1000 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        val text = if (value >= 100 || unit == 0) round(value).toString() else toFixedOne(value)
        return "$text ${units[unit]}"
    }

    /** `toFixed(1)` for 0 < x < 128: the exact binary value decides, a tie goes to the larger number. */
    private fun toFixedOne(x: Double): String {
        val bits = x.toBits()
        val exponent = ((bits ushr 52) and 0x7ff).toInt()
        val mantissa = (bits and 0xfffffffffffffL) or (1L shl 52)
        val shift = 1075 - exponent
        val scaled = mantissa * 10
        val tenths = if (shift <= 0) scaled shl -shift else {
            val quotient = scaled shr shift
            val remainder = scaled and ((1L shl shift) - 1)
            if (remainder >= (1L shl (shift - 1))) quotient + 1 else quotient
        }
        return "${tenths / 10}.${tenths % 10}"
    }

    /**
     * The quality of a request for a queued job, e.g. "1080p · MP4 · H.264" or "MP3 · 320 kbps". [detail] is the quality that
     * will actually be fetched when known (its height, frame rate and HDR win over the request's cap); [withSize] adds its
     * size as "~12.3 MB".
     */
    fun label(format: DownloadFormat, detail: LabelVideo? = null, withSize: Boolean = true): String {
        if (format is DownloadFormat.Audio) {
            val target = if (format.target == "original") "Original audio" else format.target.uppercase()
            val quality = when {
                format.target == "flac" || format.target == "wav" || format.target == "original" -> ""
                format.quality == "best" -> "best"
                else -> "${format.quality} kbps"
            }
            return listOf(target, quality).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        val video = format as DownloadFormat.Video
        val height = detail?.height ?: video.maxHeight
        val fps = detail?.fps ?: video.fps ?: 0
        val quality = if (height != null && height != 0) "${height}p${if (fps > 32) fps.toString() else ""}${if (detail?.hdr == true) " HDR" else ""}" else "Best quality"
        val size = if (withSize && detail?.size != null && detail.size > 0) "~" + formatBytes(detail.size.toDouble()) else ""
        return listOf(quality, video.container.uppercase(), if (video.codec == "auto") "" else codecNames.getValue(video.codec), size)
            .filter { it.isNotEmpty() }.joinToString(" · ")
    }

    /** The video quality a request will fetch: its exact format id, else the first (best) one within the height cap. */
    fun pickVideo(format: DownloadFormat.Video, video: List<LabelVideo>): LabelVideo? =
        if (format.formatId != null) video.firstOrNull { it.formatId == format.formatId }
        else video.firstOrNull { format.maxHeight == null || it.height <= format.maxHeight }

    /**
     * Estimated bytes at enqueue time; null when the inspection gave nothing usable. It is the same figure the preview shows
     * (choice.ChoiceSize), so the Downloads row and the preview agree.
     */
    fun expectedSize(format: DownloadFormat, video: List<LabelVideo>, audio: List<LabelAudio>, duration: Double?): Pair<Long?, Boolean> {
        val streams = audio.map { ChoiceAudio(it.formatId, it.codec, it.size, it.sizeApprox) }
        val size = when (format) {
            is DownloadFormat.Video -> {
                val option = pickVideo(format, video)?.let { ChoiceVideo(it.formatId, it.height, it.fps, it.hdr, "other", it.hasAudio, it.formatId, it.size, it.sizeApprox) }
                ChoiceSize.video(option, streams.firstOrNull { it.formatId == format.audioFormatId }, streams.firstOrNull())
            }
            is DownloadFormat.Audio -> ChoiceSize.audio(format.target, format.quality, ChoiceSize.source(streams, format.formatId), duration)
        }
        return Pair(size.bytes, size.approx)
    }

    /**
     * Label and expected size together. [withSize] puts the video size in the label (the desktop does; the Android job row
     * shows the size on its own).
     */
    fun quote(format: DownloadFormat, video: List<LabelVideo>, audio: List<LabelAudio>, duration: Double?, withSize: Boolean = true): Quote {
        val detail = (format as? DownloadFormat.Video)?.let { pickVideo(it, video) }
        val estimate = expectedSize(format, video, audio, duration)
        return Quote(label(format, detail, withSize), estimate.first, estimate.second)
    }
}
