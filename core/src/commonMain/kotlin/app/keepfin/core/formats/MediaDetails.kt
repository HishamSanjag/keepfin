package app.keepfin.core.formats

import app.keepfin.core.music.asciiLower
import app.keepfin.core.music.jsTrim
import kotlin.math.floor

/**
 * The words of a file's technical details (resolution, codecs, bitrate), so Windows and the phone say the same thing about the same
 * file. The apps read the file (Windows from the converter's report, the phone from Android's media extractor) and hand the raw
 * facts here: a codec as either app names it (`h264`, `video/avc`, `mp4a.40.2`), a bitrate in kbit/s, the width and the height.
 */
object MediaDetails {
    /** JavaScript Math.round. */
    private fun round(x: Double): Long = floor(x + 0.5).toLong()

    private val names = mapOf(
        "h264" to "H.264", "avc" to "H.264", "avc1" to "H.264",
        "hevc" to "H.265 (HEVC)", "h265" to "H.265 (HEVC)", "hev1" to "H.265 (HEVC)", "hvc1" to "H.265 (HEVC)",
        "vp9" to "VP9", "x-vnd.on2.vp9" to "VP9", "vp09" to "VP9", "vp8" to "VP8", "x-vnd.on2.vp8" to "VP8",
        "av1" to "AV1", "av01" to "AV1",
        "mpeg4" to "MPEG-4", "mp4v" to "MPEG-4", "mp4v-es" to "MPEG-4", "mpeg2video" to "MPEG-2", "h263" to "H.263", "mjpeg" to "Motion JPEG",
        "aac" to "AAC", "mp4a" to "AAC", "mp4a-latm" to "AAC", "opus" to "Opus", "mp3" to "MP3", "mpeg" to "MP3",
        "vorbis" to "Vorbis", "flac" to "FLAC", "alac" to "ALAC", "ac3" to "AC-3", "eac3" to "E-AC-3", "dts" to "DTS", "wmav2" to "WMA",
        "raw" to "PCM", "amr_nb" to "AMR", "3gpp" to "AMR", "amr-wb" to "AMR-WB", "amr_wb" to "AMR-WB",
    )

    /**
     * A codec as people write it: "H.264", "AAC", "Opus". [raw] is the name from the converter's report (`h264`, `aac`, `pcm_s16le`)
     * or an Android media type (`video/avc`, `audio/mp4a-latm`); anything else is shown in capitals without its prefix.
     */
    fun codecName(raw: String?): String? {
        val text = asciiLower(jsTrim(raw ?: ""))
        if (text.isEmpty()) return null
        // "video/avc" -> "avc"; "mp4a.40.2" -> "mp4a"; "aac (lc)" -> "aac"; "pcm_s16le" -> "pcm";
        // Android's vendor names keep the codec last: "video/x-vnd.on2.vp9" -> "vp9"
        val type = text.substringAfter('/').substringBefore(' ')
        val bare = if (type.startsWith("x-vnd.")) type.substringAfterLast('.') else type.substringBefore('.')
        if (bare.startsWith("pcm")) return "PCM"
        return names[bare] ?: bare.uppercase()
    }

    /** "1080x1920" (width by height), or null when either is not known. */
    fun resolution(width: Int?, height: Int?): String? =
        if (width != null && height != null && width > 0 && height > 0) "${width}x$height" else null

    /** A bitrate in kbit/s as words: "320 kbit/s" below 1000, "2.4 Mbit/s" above. */
    fun bitrate(kbps: Double): String {
        if (!(kbps > 0)) return ""
        if (kbps < 1000) return "${round(kbps)} kbit/s"
        val tenths = round(kbps / 100)
        val whole = tenths / 10
        val fraction = tenths % 10
        return if (fraction == 0L) "$whole Mbit/s" else "$whole.$fraction Mbit/s"
    }
}
