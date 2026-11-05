package app.keepfin.ui.player

import java.util.Locale

/** A timed caption. */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * Parses subtitle files that sit next to a video (.srt, .vtt, .ass/.ssa) so Keepfin can show them with its own delay and size
 * controls. Styling is simplified to plain text. Pure and unit-tested.
 */
object SubtitleCues {
    private val time = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})""")
    private val arrow = Regex("""\s*-->\s*""")
    private val tags = Regex("""<[^>]*>|\{[^}]*\}""")

    fun parse(text: String, mimeOrName: String): List<SubtitleCue> {
        val body = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n')
        val lower = mimeOrName.lowercase(Locale.ROOT)
        val cues = when {
            lower.contains("ass") || lower.contains("ssa") || body.contains("[Script Info]") -> ass(body)
            else -> cueBlocks(body)
        }
        return cues.filter { it.text.isNotBlank() && it.endMs > it.startMs }.sortedBy { it.startMs }
    }

    private fun millis(match: MatchResult): Long {
        val (h, m, s, f) = match.destructured
        val fraction = (f + "00").take(3).toLong()
        return (h.ifEmpty { "0" }.toLong() * 3600 + m.toLong() * 60 + s.toLong()) * 1000 + fraction
    }

    private fun clean(raw: String): String = tags.replace(raw, "").replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").trim()

    private fun cueBlocks(body: String): List<SubtitleCue> {
        val out = ArrayList<SubtitleCue>()
        for (block in body.split(Regex("\n{2,}"))) {
            val lines = block.lines()
            val at = lines.indexOfFirst { arrow.containsMatchIn(it) }
            if (at < 0) continue
            val parts = lines[at].split(arrow)
            val start = time.find(parts[0]) ?: continue
            val end = time.find(parts.getOrNull(1) ?: continue) ?: continue
            out.add(SubtitleCue(millis(start), millis(end), clean(lines.drop(at + 1).joinToString("\n"))))
        }
        return out
    }

    private fun ass(body: String): List<SubtitleCue> {
        val out = ArrayList<SubtitleCue>()
        val assTime = Regex("""(\d+):(\d{2}):(\d{2})[.](\d{1,2})""")
        fun ms(value: String): Long? = assTime.find(value.trim())?.let { m ->
            val (h, mm, s, cs) = m.destructured
            (h.toLong() * 3600 + mm.toLong() * 60 + s.toLong()) * 1000 + (cs + "0").take(2).toLong() * 10
        }
        for (line in body.lines()) {
            if (!line.startsWith("Dialogue:")) continue
            val fields = line.removePrefix("Dialogue:").split(",", limit = 10)
            if (fields.size < 10) continue
            val start = ms(fields[1]) ?: continue
            val end = ms(fields[2]) ?: continue
            out.add(SubtitleCue(start, end, clean(fields[9].replace("\\N", "\n").replace("\\n", "\n"))))
        }
        return out
    }

    /** The cues showing at [positionMs] once shifted by [delayMs] (positive = later). */
    fun at(cues: List<SubtitleCue>, positionMs: Long, delayMs: Long): String? {
        if (cues.isEmpty()) return null
        val t = positionMs - delayMs
        // Cues are sorted by start; a short backwards scan from the insertion point finds overlapping ones.
        var low = 0
        var high = cues.size
        while (low < high) { val mid = (low + high) ushr 1; if (cues[mid].startMs <= t) low = mid + 1 else high = mid }
        val shown = ArrayList<String>(2)
        var i = low - 1
        while (i >= 0 && low - i <= 6) { val c = cues[i]; if (t < c.endMs) shown.add(0, c.text); i-- }
        return shown.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}
