package app.keepfin.core.gallery

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonNull
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.json.parseJson
import app.keepfin.core.music.asciiLower
import app.keepfin.core.music.jsTrim
import app.keepfin.core.providers.Providers
import app.keepfin.core.providers.Url
import kotlin.math.max

/**
 * One file of a post: [position] is 1-based in the engine's order (what its range selects), [photo] when it is an image. A video has no image
 * link of its own ([url] is a page the engine hands to the download engine), so [cover] is the post's picture of it, when the site gives one.
 */
data class GalleryItem(val position: Int, val url: String, val photo: Boolean, val cover: String? = null)

/**
 * A finished photo job an app remembers: the post link, the photos it asked for ([items] null = all [count]) and whether every one of them
 * reached storage and is still there ([complete]: no save error, all files present).
 */
data class EarlierSave(val url: String, val count: Int, val items: List<Int>?, val complete: Boolean)

/** What the gallery engine's `-j` output holds. */
sealed interface GalleryRead {
    data class Post(
        val title: String,
        val uploader: String?,
        val category: String,
        val provider: String,
        val previews: List<String>,
        /** Every file in the engine's output order (capped at the read limit). */
        val items: List<GalleryItem>,
        val count: Int,
        val truncated: Boolean,
    ) : GalleryRead

    /** The engine reported a failure while exiting 0 (for example a login redirect). */
    data class Failed(val message: String) : GalleryRead

    /** Unusable output, or no images and no failure. */
    data object Empty : GalleryRead
}

/** Reads the gallery engine's `-j` output (message tuples: 2 = directory, 3 = file URL, -1 = failure). The behaviour is the desktop app's. */
object Gallery {
    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")

    private class File(val url: String, val meta: JsonValue?)

    private fun JsonValue?.get(key: String): JsonValue? = (this as? JsonObject)?.members?.get(key)

    /** A non-blank string, trimmed and cut to [max]; anything else is null. */
    private fun str(value: JsonValue?, max: Int = 500): String? {
        val s = (value as? JsonString)?.value ?: return null
        val t = jsTrim(s)
        return if (t.isEmpty()) null else t.take(max)
    }

    private fun isObject(value: JsonValue?) = value is JsonObject || value is JsonArray

    /** JavaScript `a ?? b`: JSON null counts as missing. */
    private fun JsonValue?.orElse(other: JsonValue?): JsonValue? = if (this == null || this === JsonNull) other else this

    fun read(json: String, url: String, limit: Int = 200): GalleryRead {
        val data = try {
            parseJson(json)
        } catch (_: IllegalArgumentException) {
            return GalleryRead.Empty
        } as? JsonArray ?: return GalleryRead.Empty

        var directory: JsonValue? = null
        val files = ArrayList<File>()
        var declared = 0.0
        var failure = ""
        for (message in data.items) {
            val parts = (message as? JsonArray)?.items ?: continue
            val kind = (parts.getOrNull(0) as? JsonNumber)?.value
            val second = parts.getOrNull(1)
            val third = parts.getOrNull(2)
            if (kind == 2.0 && isObject(second) && directory == null) directory = second
            if (kind == 3.0 && second is JsonString) {
                val meta = if (isObject(third)) third else null
                files.add(File(second.value, meta))
                val count = (meta.get("count") as? JsonNumber)?.value
                if (count != null && count > declared) declared = count
            }
            if (kind == -1.0 && isObject(second) && failure.isEmpty()) failure = str(second.get("message")) ?: ""
        }
        if (files.isEmpty()) return if (failure.isNotEmpty()) GalleryRead.Failed(failure) else GalleryRead.Empty

        val first = files[0].meta
        val category = str(first.get("category"), 60) ?: str(directory.get("category"), 60) ?: "gallery"
        val album = directory.get("album").orElse(first.get("album"))
        val title = str(album.get("title"))
            ?: str(directory.get("title"))
            ?: str(first.get("title"))
            ?: str(first.get("description"), 120)
            ?: str(first.get("content"), 120)
            ?: "${Providers.name(url)} post"
        val uploader = str(first.get("author").get("name"), 200)
            ?: str(first.get("user").get("name"), 200)
            ?: str(first.get("username"), 200)
            ?: str(first.get("uploader"), 200)
            ?: str(first.get("owner"), 200)
        val previews = files
            .filter { it.url.startsWith("https://") && isImage(it) }
            .take(12)
            .map { it.url }
        val items = files.take(limit).mapIndexed { i, f -> GalleryItem(i + 1, f.url, isImage(f), if (isImage(f)) null else cover(f)) }
        val count = max(files.size.toDouble(), declared).toInt()
        return GalleryRead.Post(title, uploader, category, Providers.name(url, category), previews, items, count, count > limit || files.size >= limit)
    }

    private const val MAX_CHOSEN = 500

    /**
     * The engine's range for the [chosen] positions (1..[count]): null when all are chosen, else sorted and compact ("1,3-5").
     * Empty, out-of-range, repeated or more than 500 values throw [IllegalArgumentException].
     */
    fun range(chosen: List<Int>, count: Int): String? {
        require(chosen.isNotEmpty() && chosen.size <= MAX_CHOSEN) { "Choose between 1 and $MAX_CHOSEN items." }
        require(chosen.all { it in 1..count }) { "An item is outside 1..$count." }
        val sorted = chosen.sorted()
        require(sorted.zipWithNext().all { (a, b) -> a != b }) { "An item is chosen twice." }
        if (sorted.size == count) return null
        val parts = ArrayList<String>()
        var start = sorted[0]
        var end = start
        for (n in sorted.drop(1) + 0) {
            if (n == end + 1) {
                end = n
                continue
            }
            parts.add(if (start == end) "$start" else "$start-$end")
            start = n
            end = n
        }
        return parts.joinToString(",")
    }

    /** The positions a request names: [items] sorted, or every position 1..[count] when it names none. */
    fun positions(items: List<Int>?, count: Int): List<Int> = items?.sorted() ?: (1..count).toList()

    private val trackingParams = setOf("igsh", "igshid", "si", "ref", "share", "feature")

    /** The part of a post link that names the post: host without `www.`, path without a trailing slash, the query without tracking parameters. */
    private fun postKey(link: String): String {
        val url = Url.parse(jsTrim(link)) ?: return asciiLower(jsTrim(link))
        val host = asciiLower(url.hostname).removePrefix("www.")
        val query = url.query.orEmpty().split('&').filter { it.isNotEmpty() && !isTracking(it.substringBefore('=')) }.joinToString("&")
        return host + url.path.trimEnd('/') + if (query.isEmpty()) "" else "?$query"
    }

    private fun isTracking(name: String) = asciiLower(name).let { it in trackingParams || it.startsWith("utm_") }

    /** True when both links name the same post. */
    fun samePost(a: String, b: String): Boolean = postKey(a) == postKey(b)

    /**
     * Which [earlier] save already holds the photos asked for ([items] among [count] of the post at [url]): the index of the first complete save
     * of the same post whose photos include every one asked for, else -1. A save with missing files or a save error never counts, and a post
     * that has grown since (more photos than the save knew) is not covered when its new photos are asked for.
     */
    fun alreadySaved(url: String, count: Int, items: List<Int>?, earlier: List<EarlierSave>): Int {
        val wanted = positions(items, count)
        return earlier.indexOfFirst { it.complete && samePost(it.url, url) && positions(it.items, it.count).containsAll(wanted) }
    }

    /** The label of a job asking for [photos] photos and [videos] videos: "6 photos", "1 video", or "2 photos, 8 videos" for a mixed post. */
    fun label(photos: Int, videos: Int): String = listOf(photos to "photo", videos to "video")
        .filter { it.first > 0 }
        .joinToString(", ") { "${it.first} ${it.second}${if (it.first == 1) "" else "s"}" }
        .ifEmpty { "0 photos" }

    /**
     * The label once [saved] of the [expected] files reached storage: "4 of 6 photos" while some are missing, the plain "6 photos" when all
     * are there. [label] is the job's current label, partial or not. A mixed label ("2 photos, 8 videos") reads "7 of 10 items" meanwhile.
     */
    fun savedLabel(saved: Int, expected: Int, label: String): String {
        val of = label.indexOf(" of ")
        val full = if (of > 0 && label.substring(0, of).all { it in '0'..'9' }) label.substring(of + 4) else label
        if (saved >= expected) return full
        return if (full.contains(", ")) "$saved of $expected items" else "$saved of $full"
    }

    /** The picture the site gives for a video of the post (`display_url`), when it is a secure link. */
    private fun cover(file: File): String? = str(file.meta.get("display_url"), 2000)?.takeIf { it.startsWith("https://") }

    /** The meta's extension (or ext), else the last dot-part of the link before any `?` or `#`. */
    private fun isImage(file: File): Boolean {
        val declared = file.meta.get("extension").orElse(file.meta.get("ext"))
        val ext = if (declared != null) (declared as? JsonString)?.value ?: "" else file.url.substringBefore('?').substringBefore('#').substringAfterLast('.')
        return asciiLower(ext) in imageExt
    }
}
