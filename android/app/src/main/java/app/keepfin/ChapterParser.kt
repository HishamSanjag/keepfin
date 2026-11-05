package app.keepfin

import app.keepfin.core.library.LibChapter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Chapters of a video from the download engine's info JSON (`chapters: [{start_time, end_time, title}]`). Pure: the same cleaning the
 * Windows app applies (finite, not negative, in order, one per start, none at or after the end, a trimmed title or "Chapter N").
 */
object ChapterParser {
    /** The chapters of an info JSON text, or null when it has none (or is not JSON). [durationSeconds] drops starts at or after the end. */
    fun parse(infoJson: String, durationSeconds: Double? = null): List<LibChapter>? {
        val root = try { JSONObject(infoJson) } catch (_: Exception) { return null }
        val array = root.optJSONArray("chapters") ?: return null
        return clean(array, durationSeconds ?: root.optDouble("duration", Double.NaN).takeIf { it.isFinite() })
    }

    fun clean(array: JSONArray, durationSeconds: Double?): List<LibChapter>? {
        val raw = ArrayList<Pair<Double, String>>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val start = (o.opt("start_time") as? Number)?.toDouble() ?: continue
            if (!start.isFinite() || start < 0.0) continue
            if (durationSeconds != null && durationSeconds > 0.0 && start >= durationSeconds) continue
            raw += start to (o.opt("title") as? String).orEmpty().trim()
        }
        val sorted = raw.sortedBy { it.first }
        val out = ArrayList<LibChapter>()
        for ((start, title) in sorted) {
            if (out.isNotEmpty() && out.last().start == start) continue
            out += LibChapter(start, title.ifEmpty { "Chapter ${out.size + 1}" })
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /** The chapters in an info JSON file, or null when it is missing, unreadable (or over 20 MB) or has none. */
    fun fromFile(file: File?, durationSeconds: Double? = null): List<LibChapter>? {
        if (file == null || !file.isFile || file.length() > 20_000_000L) return null
        return try { parse(file.readText(Charsets.UTF_8), durationSeconds) } catch (_: Exception) { null }
    }
}
