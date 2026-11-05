package app.keepfin.core.music

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.json.parseJson

private val ARTIST_SPLIT = Regex("$WS*,$WS*|$WS+&$WS+")
private val TOPIC_SUFFIX = Regex("$WS+-$WS+${ci("topic")}")

private const val ITEM_KEY = "musicResponsiveListItemRenderer"
private const val MAX_ITEMS = 20
private const val MAX_DEPTH = 40

/** " - Topic" channel suffix removed (ASCII case-insensitive, at the end only). */
internal fun withoutTopic(name: String): String = removeFirstAtEnd(TOPIC_SUFFIX, name)

internal fun artistSplit(value: String): List<String> =
    if (value.isEmpty()) emptyList() else splitByRegex(value, ARTIST_SPLIT).map(::jsTrim).filter { it.isNotEmpty() }

/** A YouTube video id: exactly 11 of A-Z a-z 0-9 _ - */
internal fun isVideoId(value: String): Boolean =
    value.length == 11 && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }

/** The duration text of a song row: `m:ss`, `mm:ss` or `h:mm:ss`. Null when [text] is something else. */
internal fun clockSeconds(text: String): Double? {
    val parts = text.split(':')
    if (parts.size !in 2..3) return null
    val digits = { part: String -> part.isNotEmpty() && part.all { it in '0'..'9' } }
    if (!digits(parts[0]) || parts[0].length > 2) return null
    if (parts.drop(1).any { !digits(it) || it.length != 2 }) return null
    return parts.fold(0.0) { total, part -> total * 60 + part.toInt() }
}

private fun watchUrl(host: String, id: String) = "https://$host/watch?v=$id"

/** Candidates from a parsed innertube WEB_REMIX search response (songs filter). */
internal fun songsFrom(data: JsonValue): List<Candidate> {
    val items = ArrayList<JsonValue>()
    fun walk(node: JsonValue?, depth: Int) {
        if (depth > MAX_DEPTH || items.size >= MAX_ITEMS) return
        when (node) {
            is JsonObject -> {
                val renderer = node.members[ITEM_KEY]
                if (truthy(renderer)) {
                    items.add(renderer!!)
                    return
                }
                for (value in jsValues(node)) walk(value, depth + 1)
            }
            is JsonArray -> for (value in node.items) walk(value, depth + 1)
            else -> {}
        }
    }
    walk(data, 0)

    val candidates = ArrayList<Candidate>()
    for (item in items) {
        val columns = (item.prop("flexColumns") as? JsonArray)?.items ?: emptyList()
        fun runs(index: Int): List<JsonValue> =
            columns.getOrNull(index).prop("musicResponsiveListItemFlexColumnRenderer").prop("text").prop("runs").let {
                (it as? JsonArray)?.items ?: emptyList()
            }

        val titleRuns = runs(0)
        val title = str(JsonString(titleRuns.joinToString("") { stringOf(it) ?: "" }))
        val videoId =
            str(item.prop("playlistItemData").prop("videoId"), 20)
                ?: str(
                    titleRuns.firstOrNull { truthy(it.prop("navigationEndpoint").prop("watchEndpoint")) }
                        .prop("navigationEndpoint").prop("watchEndpoint").prop("videoId"),
                    20,
                )
        if (title == null || videoId == null || !isVideoId(videoId)) continue

        val details = runs(1)
        val artists = ArrayList<String>()
        var album: String? = null
        var durationSec: Double? = null
        for (run in details) {
            val text = jsTrim(stringOf(run) ?: "")
            val pageType = run.prop("navigationEndpoint").prop("browseEndpoint")
                .prop("browseEndpointContextSupportedConfigs").prop("browseEndpointContextMusicConfig").prop("pageType")
                .let { (it as? JsonString)?.value }
            if (pageType == "MUSIC_PAGE_TYPE_ARTIST" || pageType == "MUSIC_PAGE_TYPE_USER_CHANNEL") artists.add(text)
            else if (pageType == "MUSIC_PAGE_TYPE_ALBUM") album = text
            else clockSeconds(text)?.let { durationSec = it }
        }
        if (artists.isEmpty()) {
            // Artists without links: the first " • " group.
            val first = details.joinToString("") { textOf(it) }.split(" • ")[0]
            artists.addAll(artistSplit(first))
        }
        candidates.add(
            Candidate(
                videoId = videoId,
                url = watchUrl("music.youtube.com", videoId),
                title = title,
                artists = artists,
                album = album,
                durationSec = durationSec,
                origin = Origin.YTM_SONG,
            ),
        )
    }
    return candidates
}

/** Candidates from parsed engine flat-search output (`{"entries": [...]}`). */
internal fun flatFrom(data: JsonValue, origin: Origin): List<Candidate> {
    val entries = (data.prop("entries") as? JsonArray)?.items ?: return emptyList()
    val host = if (origin == Origin.YTM_FLAT) "music.youtube.com" else "www.youtube.com"
    return entries.mapNotNull { entry ->
        val id = str(entry.prop("id"), 20)
        val title = str(entry.prop("title"))
        if (id == null || !isVideoId(id) || title == null) return@mapNotNull null
        val channel = str(entry.prop("channel"), 200) ?: str(entry.prop("uploader"), 200)
        Candidate(
            videoId = id,
            url = watchUrl(host, id),
            title = title,
            artists = if (channel != null) listOf(withoutTopic(channel)) else emptyList(),
            channel = channel,
            durationSec = positive(entry.prop("duration")),
            origin = origin,
        )
    }
}
