package app.keepfin.library.repo

import app.keepfin.core.library.LibChapter
import app.keepfin.core.library.LibItem
import org.json.JSONArray
import org.json.JSONObject

/** The collection places a delete took an item out of, kept for restore: the collection and (a window of) the order its items had. */
internal class TrashPlace(val collection: String, val before: List<String>)

/**
 * What `item.json` in a trash folder (`.keepfin/trash/<item id>/`) says about the deleted item, written when the delete runs so Restore
 * works after a restart. Every field is optional on read (Windows writes only id, title, path and deletedAt, and an older or damaged file
 * must not break the page); [item] is the full library record (tags, favourite, plays...) as it was.
 */
internal class TrashInfo(
    val id: String?,
    val title: String?,
    /** Where the file stood, relative to the Keepfin folder. */
    val place: String?,
    val space: String?,
    val size: Long?,
    val kind: String?,
    val deletedAt: Long?,
    val content: String?,
    val sidecars: List<String>,
    val item: LibItem?,
    val places: List<TrashPlace>,
) {
    fun toBytes(): ByteArray {
        val o = JSONObject()
        o.put("version", 1)
        id?.let { o.put("id", it) }
        title?.let { o.put("title", it) }
        place?.let { o.put("place", it) }
        space?.let { o.put("space", it) }
        size?.let { o.put("size", it) }
        kind?.let { o.put("kind", it) }
        deletedAt?.let { o.put("deletedAt", it) }
        content?.let { o.put("content", it) }
        o.put("sidecars", JSONArray(sidecars))
        item?.let { o.put("item", itemJson(it)) }
        o.put("collections", JSONArray().also { a -> places.forEach { p -> a.put(JSONObject().put("id", p.collection).put("before", JSONArray(p.before))) } })
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    companion object {
        /** The order kept per collection is a window around the item: a playlist of 100,000 songs must not be written 100,000 times over. */
        const val WINDOW = 200

        /** A window of [order] around [id]: all of it when short, else [WINDOW] places either side of the item. */
        fun window(order: List<String>, id: String): List<String> {
            if (order.size <= 2 * WINDOW + 1) return order
            val at = order.indexOf(id).takeIf { it >= 0 } ?: return order.take(2 * WINDOW + 1)
            return order.subList(maxOf(0, at - WINDOW), minOf(order.size, at + WINDOW + 1))
        }

        /** Reads a file; null when it is not JSON. Unknown or missing fields are fine. */
        fun parse(bytes: ByteArray): TrashInfo? {
            val o = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (e: Exception) { return null }
            fun str(k: String) = if (o.has(k) && !o.isNull(k)) (o.opt(k) as? String)?.takeIf { it.isNotEmpty() } else null
            fun long(k: String) = if (o.has(k) && !o.isNull(k)) (o.opt(k) as? Number)?.toLong() else null
            val sidecars = o.optJSONArray("sidecars")?.let { a -> (0 until a.length()).mapNotNull { (a.opt(it) as? String)?.takeIf { s -> s.isNotEmpty() } } }.orEmpty()
            val places = o.optJSONArray("collections")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val c = a.optJSONObject(i) ?: return@mapNotNull null
                    val id = (c.opt("id") as? String)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    TrashPlace(id, strings(c.optJSONArray("before")))
                }
            }.orEmpty()
            return TrashInfo(
                id = str("id"), title = str("title"), place = str("place"), space = str("space"), size = long("size"), kind = str("kind"),
                deletedAt = long("deletedAt"), content = str("content"), sidecars = sidecars, item = o.optJSONObject("item")?.let { itemFrom(it) }, places = places,
            )
        }

        private fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { (a.opt(it) as? String) }

        private fun itemJson(i: LibItem): JSONObject {
            val o = JSONObject()
            o.put("id", i.id).put("space", i.space).put("kind", i.kind).put("title", i.title)
            i.artist?.let { o.put("artist", it) }
            i.album?.let { o.put("album", it) }
            i.albumArtist?.let { o.put("albumArtist", it) }
            i.track?.let { o.put("track", it) }
            i.disc?.let { o.put("disc", it) }
            i.year?.let { o.put("year", it) }
            i.genre?.let { o.put("genre", it) }
            i.duration?.let { o.put("duration", it) }
            i.width?.let { o.put("width", it) }
            i.height?.let { o.put("height", it) }
            i.chapters?.let { c -> o.put("chapters", JSONArray().also { a -> c.forEach { a.put(JSONObject().put("start", it.start).put("title", it.title)) } }) }
            i.gain?.let { o.put("gain", it) }
            i.subtitles?.let { o.put("subtitles", JSONArray(it)) }
            i.hasLyrics?.let { o.put("hasLyrics", it) }
            i.sourceUrl?.let { o.put("sourceUrl", it) }
            i.sourceId?.let { o.put("sourceId", it) }
            i.addedAt?.let { o.put("addedAt", it) }
            i.tags?.let { o.put("tags", JSONArray(it)) }
            i.lastPlayedAt?.let { o.put("lastPlayedAt", it) }
            i.position?.let { o.put("position", it) }
            i.watched?.let { o.put("watched", it) }
            o.put("favorite", i.favorite)
            o.put("userTags", JSONArray(i.userTags))
            i.plays?.let { o.put("plays", it) }
            i.place?.let { o.put("place", it) }
            return o
        }

        private fun itemFrom(o: JSONObject): LibItem? {
            fun str(k: String) = if (o.has(k) && !o.isNull(k)) o.opt(k) as? String else null
            fun num(k: String) = if (o.has(k) && !o.isNull(k)) (o.opt(k) as? Number)?.toDouble() else null
            fun flag(k: String) = if (o.has(k) && !o.isNull(k)) o.opt(k) as? Boolean else null
            val id = str("id") ?: return null
            val title = str("title") ?: return null
            val chapters = o.optJSONArray("chapters")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { c -> LibChapter(c.optDouble("start", 0.0), c.optString("title", "")) } }
            }
            return LibItem(
                id = id, space = str("space") ?: "downloads", kind = str("kind") ?: "other", title = title,
                artist = str("artist"), album = str("album"), albumArtist = str("albumArtist"), track = num("track"), disc = num("disc"), year = num("year"),
                genre = str("genre"), duration = num("duration"), width = num("width"), height = num("height"), chapters = chapters, gain = num("gain"),
                subtitles = o.optJSONArray("subtitles")?.let { strings(it) }, hasLyrics = flag("hasLyrics"), sourceUrl = str("sourceUrl"), sourceId = str("sourceId"),
                addedAt = num("addedAt"), tags = o.optJSONArray("tags")?.let { strings(it) }, lastPlayedAt = num("lastPlayedAt"), position = num("position"),
                watched = flag("watched"), favorite = flag("favorite") ?: false, userTags = strings(o.optJSONArray("userTags")), plays = num("plays"), place = str("place"),
            )
        }
    }
}
