package app.keepfin.core.formats

import app.keepfin.core.music.asciiLower
import app.keepfin.core.music.jsTrim
import kotlin.math.floor
import kotlin.math.min

/**
 * Smaller sizes made by converting. Some sites (Instagram) serve only a couple of sizes, so Keepfin offers a few smaller ones
 * it makes itself after downloading the nearest bigger served size. The rule is honest: only sizes strictly below the best
 * one served, never upscaling. Pure: the apps run the converter (`args`) and read its progress (`percent`).
 *
 * A size is the option's `height`, which Keepfin already defines as the short side (a 720x1280 reel is 720).
 */
object Conversion {
    /** The sizes that can be offered, biggest first. */
    val LADDER = listOf(1080, 720, 480, 360)

    /** At most this many converted sizes are offered. */
    const val MAX_OPTIONS = 3

    /** A site that serves more distinct sizes than this already has a ladder of its own. */
    const val MAX_SERVED_SIZES = 4

    /** A ladder size is skipped when a served size is within this share of it. */
    private const val NEAR = 0.10

    const val AUDIO_KBPS = 128

    /** JavaScript Math.round. */
    private fun round(x: Double): Long = floor(x + 0.5).toLong()

    /** The video bitrate (kbit/s) a converted size is made at. */
    fun videoKbps(height: Int): Int = when {
        height >= 1080 -> 4500
        height >= 720 -> 2500
        height >= 480 -> 1200
        else -> 700
    }

    /**
     * The video bitrate to convert at: [videoKbps], but never above 80% of what the downloaded file already carries (its bytes over
     * [duration]), so a small source does not come out bigger. Never below 100 kbit/s.
     */
    fun kbpsFor(height: Int, sourceBytes: Long?, duration: Double?): Int {
        val target = videoKbps(height)
        if (sourceBytes == null || sourceBytes <= 0 || duration == null || !(duration > 0)) return target
        val carried = sourceBytes * 8.0 / duration / 1000 - AUDIO_KBPS
        return min(target, maxOf(100, floor(carried * 0.8).toInt()))
    }

    private fun even(x: Double): Int = maxOf(2, (round(x / 2.0) * 2).toInt())

    /** The converted sizes to add to [served] (best first), best first; empty when the rule offers none. */
    fun options(served: List<VideoOption>, duration: Double?): List<VideoOption> {
        if (served.isEmpty() || served.any { it.hdr || it.convertedFrom != null }) return emptyList()
        if (served.map { it.height }.toSet().size > MAX_SERVED_SIZES) return emptyList()
        val best = served.maxOf { it.height }
        val result = ArrayList<VideoOption>()
        for (target in LADDER) {
            if (result.size >= MAX_OPTIONS) break
            if (target >= best) continue
            if (served.any { kotlin.math.abs(it.height - target) <= target * NEAR }) continue
            // The nearest served size above the target is the one downloaded (the smallest download that never upscales).
            val sourceHeight = served.filter { it.height > target }.minOf { it.height }
            val source = served.first { it.height == sourceHeight }
            val kbps = videoKbps(target) + AUDIO_KBPS
            result.add(
                VideoOption(
                    id = "${target}p-converted",
                    height = target,
                    width = source.width?.let { even(it.toDouble() * target / source.height) },
                    fps = source.fps,
                    hdr = false,
                    codec = "h264",
                    ext = "mp4",
                    formatId = source.formatId,
                    hasAudio = source.hasAudio,
                    size = estimate(target, source, duration),
                    sizeApprox = true,
                    tbr = kbps.toDouble(),
                    convertedFrom = source.height,
                ),
            )
        }
        return result
    }

    /** [served] plus its converted sizes, best first (a converted size follows a served one of the same height). */
    fun withConverted(served: List<VideoOption>, duration: Double?): List<VideoOption> {
        val extra = options(served, duration)
        if (extra.isEmpty()) return served
        return (served + extra).sortedWith(compareByDescending<VideoOption> { it.height }.thenByDescending { it.fps ?: 0 }.thenByDescending { it.hdr })
    }

    /**
     * What a converted size is expected to weigh: the bitrate it is made at ([kbpsFor], so the same 80% cap as the conversion)
     * plus 128 kbit/s audio, times the duration, never more than the source it is made from. The duration is the item's, else the
     * source's own size over its bitrate; null when neither says.
     */
    fun estimate(height: Int, source: VideoOption, duration: Double?): Long? {
        val seconds = duration?.takeIf { it > 0 }
            ?: if (source.size != null && source.tbr != null && source.tbr > 0) source.size * 8.0 / (source.tbr * 1000) else null
        if (seconds == null) return null
        val bytes = round((kbpsFor(height, source.size, seconds) + AUDIO_KBPS) * 1000.0 * seconds / 8)
        return if (source.size != null) min(source.size, bytes) else bytes
    }

    /**
     * The converter's arguments: the short side becomes [height] (the other side follows, even), H.264 at [kbps], AAC at 128 kbit/s,
     * progress on standard output. [input] and [output] are paths; the output is an MP4 (fast start) when its name says so.
     */
    fun args(input: String, output: String, height: Int, kbps: Int, copyAudio: Boolean = false): List<String> {
        val h = height.toString()
        // Landscape fixes the height, portrait (and square) the width; -2 keeps the aspect with an even size.
        val scale = "scale=w='if(gt(iw,ih),-2,$h)':h='if(gt(iw,ih),$h,-2)'"
        val result = arrayListOf(
            "-hide_banner", "-nostdin", "-i", input,
            "-map", "0:v:0", "-map", "0:a:0?", "-map_metadata", "0",
            "-vf", scale,
            "-c:v", "libx264", "-preset", "veryfast", "-b:v", "${kbps}k", "-maxrate", "${kbps * 3 / 2}k", "-bufsize", "${kbps * 2}k",
            "-pix_fmt", "yuv420p",
            // Audio that is already light AAC is kept as it is (re-encoding it at 128 kbit/s could make the file bigger).
            *(if (copyAudio) arrayOf("-c:a", "copy") else arrayOf("-c:a", "aac", "-b:a", "${AUDIO_KBPS}k")),
        )
        val name = asciiLower(output)
        if (name.endsWith(".mp4") || name.endsWith(".m4v") || name.endsWith(".mov")) result.addAll(listOf("-movflags", "+faststart"))
        result.addAll(listOf("-progress", "pipe:1", "-nostats", "-y", output))
        return result
    }

    /** What the converter's own report of a file (`-i file`, no output) says that the conversion needs. */
    data class Probe(val duration: Double?, val copyAudio: Boolean)

    /** Reads [report] (the converter's output for `-i file`): the length, and whether the audio is AAC at 160 kbit/s or less and can be copied as it is. */
    fun probe(report: String): Probe {
        var duration: Double? = null
        var copyAudio = false
        for (raw in report.split('\n')) {
            val line = jsTrim(raw)
            if (duration == null && line.startsWith("Duration: ")) duration = clock(line.substring("Duration: ".length))
            val at = line.indexOf("Audio: ")
            if (at >= 0 && line.startsWith("Stream #") && line.substring(at + "Audio: ".length).startsWith("aac")) {
                val rate = line.indexOf(" kb/s")
                var start = rate
                while (start > 0 && line[start - 1] in '0'..'9') start--
                val kbps = if (rate > start) line.substring(start, rate).toIntOrNull() else null
                copyAudio = kbps != null && kbps <= 160
            }
        }
        return Probe(duration, copyAudio)
    }

    /** "00:00:07.55" (and what follows it) as seconds, or null. */
    private fun clock(text: String): Double? {
        val parts = text.substring(0, minOf(text.length, 11)).split(':')
        if (parts.size != 3) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val s = parts[2].toDoubleOrNull() ?: return null
        return h * 3600.0 + m * 60 + s
    }

    /** The converter's `-progress` line to a percent (0 to 99) of [duration] seconds, or null for any other line. */
    fun percent(line: String, duration: Double): Double? {
        val prefix = "out_time_us="
        if (!line.startsWith(prefix) || !(duration > 0)) return null
        val micros = jsTrim(line.substring(prefix.length)).toLongOrNull() ?: return null
        if (micros < 0) return 0.0
        return min(99.0, floor(micros / 1_000_000.0 / duration * 100))
    }
}
