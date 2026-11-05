package app.keepfin.core.choice

import app.keepfin.core.formats.Size
import kotlin.math.floor

/**
 * The size of a download before it runs, one rule for every format and quality (fixtures/engine/choice/sizes.json). A stream
 * that is kept as it is (Original; Opus from an Opus source, M4A from an AAC source, both at Best) is the size of that stream.
 * A conversion is its bitrate times the duration. Every figure that is not one kept stream's own size is approximate.
 */
object ChoiceSize {
    private val LOSSY = setOf("mp3", "m4a", "opus")

    /** The bitrate (kbit/s) a conversion gets at "best", and WAV's fixed one. */
    private val bestKbps = mapOf("mp3" to 245, "m4a" to 256, "opus" to 160, "wav" to 1411)

    private val unknown = Size(null, false)

    /** JavaScript Math.round. */
    private fun round(x: Double): Long = floor(x + 0.5).toLong()

    /** The source stream an audio download starts from: the picked one, else the first (best). */
    fun source(audio: List<ChoiceAudio>, formatId: String?): ChoiceAudio? = audio.firstOrNull { it.formatId == formatId } ?: audio.firstOrNull()

    /** An audio download of [target] at [quality] from [source]; [duration] in seconds. */
    fun audio(target: String, quality: String, source: ChoiceAudio?, duration: Double?): Size {
        if (target == "original") return Size(source?.size, source?.size != null && source.sizeApprox)
        if (target == "flac") return unknown
        val lossy = target in LOSSY
        val kept = lossy && quality == "best" && ((target == "opus" && source?.codec == "opus") || (target == "m4a" && source?.codec == "aac"))
        if (kept) return Size(source?.size, source?.size != null)
        val kbps = if (lossy && quality != "best") quality.toIntOrNull() else bestKbps[target]
        if (kbps != null && duration != null && duration > 0) return Size(round(kbps * 1000.0 / 8 * duration), true)
        return if (lossy && source?.size != null) Size(source.size, true) else unknown
    }

    /** A video quality; [track] is another audio stream picked to go with it, [best] the stream its size already counts. */
    fun video(video: ChoiceVideo?, track: ChoiceAudio?, best: ChoiceAudio?): Size {
        val size = video?.size ?: return unknown
        if (size <= 0) return unknown
        if (track != null && !video.hasAudio && best?.size != null && track.size != null) return Size(size - best.size + track.size, true)
        return Size(size, video.sizeApprox)
    }
}
