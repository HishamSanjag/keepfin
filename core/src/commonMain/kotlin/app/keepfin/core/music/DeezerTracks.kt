package app.keepfin.core.music

import app.keepfin.core.dedupe.Recordings
import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.json.parseJson

/**
 * What Deezer's public API says about a recording's code. A track response, a playlist response and a `/tracks` page carry an
 * `isrc` on each track; an album response lists its tracks without one (its `/tracks` pages have it). Pure: no requests.
 */
object DeezerTracks {
    /** Track id to its clean ISRC (see [Recordings.cleanIsrc]) for every track [json] holds. Unreadable text or a track without a usable code gives nothing for it. */
    fun isrcs(json: String): Map<String, String> {
        val root = try { parseJson(json) } catch (e: Exception) { return emptyMap() }
        val out = LinkedHashMap<String, String>()
        fun add(item: JsonValue?) {
            val id = idOf(item.prop("id")) ?: return
            val isrc = Recordings.cleanIsrc((item.prop("isrc") as? JsonString)?.value) ?: return
            out[id] = isrc
        }
        add(root)
        for (list in listOf(root.prop("data"), root.prop("tracks").prop("data"))) {
            if (list is JsonArray) for (item in list.items) add(item)
        }
        return out
    }

    private fun idOf(value: JsonValue?): String? = when (value) {
        is JsonNumber -> if (value.value > 0 && value.value == kotlin.math.floor(value.value) && value.value < 1e15) value.value.toLong().toString() else null
        is JsonString -> if (value.value.isNotEmpty() && value.value.length <= 20 && value.value.all { it in '0'..'9' }) value.value else null
        else -> null
    }
}
