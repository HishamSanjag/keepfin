package app.keepfin.core.music

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.json.jsonQuote
import app.keepfin.core.json.parseJson

/** A release listed on an artist's YouTube Music page. [link] is what downloads it; [kind] is Album, Single, EP or Video. */
data class ArtistReleaseItem(val link: String, val title: String, val kind: String, val thumbnail: String?)

/**
 * The new-release check of a followed artist: the artist's YouTube Music page (one browse request by channel id), read for its albums and
 * singles, newest first as the page lists them. Most artist channels have no "releases" tab on YouTube (and "Topic" channels have no tabs
 * at all), so the page is the one source that answers for every artist. A channel that is not a music artist (a game studio, a cover
 * channel) has no albums there; its latest uploads (the page's first videos shelf) stand in, so a follow still has something to report.
 * The platforms perform the one request; every decision is made here.
 */
object ArtistReleases {
    const val browseUrl: String = AlbumMatching.browseUrl

    /** Albums and singles taken from the page, at most (the page shows ten of each; older ones are not "new"). */
    const val MAX_ITEMS = 20

    private const val MAX_DEPTH = 40
    private const val CLIENT =
        "\"context\":{\"client\":{\"clientName\":\"WEB_REMIX\",\"clientVersion\":\"1.20250101.01.00\",\"hl\":\"en\",\"gl\":\"US\"}}"

    /** The JSON body of the artist page request. Null when [channelId] is not a channel id (UC and then letters, digits, - or _). */
    fun browseBody(channelId: String): String? = if (isChannelId(channelId)) "{$CLIENT,\"browseId\":${jsonQuote(channelId)}}" else null

    /** The channel page address an artist is kept under (the check reads the id back with [channelId]). */
    fun channelUrl(channelId: String): String = "https://music.youtube.com/channel/$channelId"

    /** The channel id inside a stored artist address (the current page address or the older .../channel/UC.../releases one); null when there is none. */
    fun channelId(url: String): String? {
        val marker = "/channel/"
        val at = url.indexOf(marker)
        if (at < 0) return null
        val id = url.substring(at + marker.length).takeWhile { idChar(it) }
        return id.takeIf { isChannelId(it) }
    }

    /** Whether a stored address is from the first follow check, which read the channel's releases tab (most channels have none). */
    fun isLegacyAddress(url: String): Boolean = url.endsWith("/releases")

    private fun idChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-'

    private fun isChannelId(value: String) = value.startsWith("UC") && value.length in 8..60 && value.all(::idChar)

    private fun isReleaseId(value: String) =
        value.startsWith("MPREb_") && value.length in 7..60 && value.all(::idChar)

    private fun isVideoId(value: String) = value.length == 11 && value.all(::idChar)

    private fun carousels(data: JsonValue): List<List<JsonValue>> {
        val found = ArrayList<List<JsonValue>>()
        fun walk(node: JsonValue?, depth: Int) {
            if (depth > MAX_DEPTH) return
            when (node) {
                is JsonObject -> {
                    val shelf = node.members["musicCarouselShelfRenderer"]
                    if (shelf != null) {
                        found.add((shelf.prop("contents") as? JsonArray)?.items?.mapNotNull { it.prop("musicTwoRowItemRenderer") } ?: emptyList())
                        return
                    }
                    for (value in jsValues(node)) walk(value, depth + 1)
                }
                is JsonArray -> for (value in node.items) walk(value, depth + 1)
                else -> {}
            }
        }
        walk(data, 0)
        return found
    }

    private fun firstOlak(node: JsonValue?, depth: Int = 0): String? {
        if (depth > MAX_DEPTH) return null
        when (node) {
            is JsonObject -> {
                val id = (node.members["playlistId"] as? JsonString)?.value
                if (id != null && id.startsWith("OLAK5uy_") && id.all(::idChar)) return id
                for (value in jsValues(node)) firstOlak(value, depth + 1)?.let { return it }
            }
            is JsonArray -> for (value in node.items) firstOlak(value, depth + 1)?.let { return it }
            else -> {}
        }
        return null
    }

    private fun endpoint(item: JsonValue, name: String): JsonValue? = item.prop("navigationEndpoint").prop(name)

    private fun thumbnailOf(item: JsonValue): String? {
        val thumbs = item.prop("thumbnailRenderer").prop("musicThumbnailRenderer").prop("thumbnail").prop("thumbnails") as? JsonArray
        return str(thumbs?.items?.lastOrNull().prop("url"), 600)
    }

    private fun titleOf(item: JsonValue): String? =
        str(JsonString(((item.prop("title").prop("runs") as? JsonArray)?.items ?: emptyList()).joinToString("") { stringOf(it) ?: "" }))

    private fun isYear(text: String) = text.length == 4 && text.all { it in '0'..'9' }

    /** "Single • 2026" is a Single; "2026" and "Album • 2026" are Albums; "EP" stays EP. */
    private fun albumKind(item: JsonValue): String {
        val first = ((item.prop("subtitle").prop("runs") as? JsonArray)?.items ?: emptyList()).map { jsTrim(stringOf(it) ?: "") }
            .firstOrNull { it.isNotEmpty() && it != "•" } ?: return "Album"
        return if (isYear(first) || first.length > 20) "Album" else first
    }

    /**
     * The releases in an artist page response: its albums and singles (carousel items that open an album), or when the page has none, the
     * first shelf of videos. Empty when the page holds neither (nothing to report is not a failure). Throws when [json] is not valid JSON.
     */
    fun parse(json: String): List<ArtistReleaseItem> {
        val shelves = carousels(parseJson(json))
        val albums = ArrayList<ArtistReleaseItem>()
        for (shelf in shelves) for (item in shelf) {
            val id = str(endpoint(item, "browseEndpoint").prop("browseId"), 80) ?: continue
            val pageType = (endpoint(item, "browseEndpoint").prop("browseEndpointContextSupportedConfigs").prop("browseEndpointContextMusicConfig").prop("pageType") as? JsonString)?.value
            if (!isReleaseId(id) || pageType != "MUSIC_PAGE_TYPE_ALBUM") continue
            val title = titleOf(item) ?: continue
            val link = firstOlak(item)?.let { "https://music.youtube.com/playlist?list=$it" } ?: "https://music.youtube.com/browse/$id"
            albums.add(ArtistReleaseItem(link, title, albumKind(item), thumbnailOf(item)))
        }
        if (albums.isNotEmpty()) return albums.distinctBy { it.link }.take(MAX_ITEMS)
        val videos = ArrayList<ArtistReleaseItem>()
        val shelf = shelves.firstOrNull { items -> items.any { videoId(it) != null } } ?: return emptyList()
        for (item in shelf) {
            val id = videoId(item) ?: continue
            val title = titleOf(item) ?: continue
            videos.add(ArtistReleaseItem("https://www.youtube.com/watch?v=$id", title, "Video", thumbnailOf(item)))
        }
        return videos.distinctBy { it.link }.take(MAX_ITEMS)
    }

    private fun videoId(item: JsonValue): String? = str(endpoint(item, "watchEndpoint").prop("videoId"), 20)?.takeIf(::isVideoId)
}
