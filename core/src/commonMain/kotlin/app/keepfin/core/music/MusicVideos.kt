package app.keepfin.core.music

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.json.jsonQuote
import app.keepfin.core.json.parseJson

/** One row of a YouTube Music videos search: [type] is OMV (an official video), UGC (anyone's upload), ATV... or "" when unknown. */
data class VideoCandidate(
    val videoId: String,
    val type: String,
    val title: String,
    val artists: List<VideoArtist>,
    val views: Long?,
    val durationSec: Double?,
)

/** An artist link of a row: the name shown and its channel (browse id). */
data class VideoArtist(val name: String, val browseId: String)

/**
 * Finding a song's official music video on YouTube Music (dev-docs/plans/MUSIC-VIDEOS.md, "Finding the video"), shared by both apps.
 * Strict on purpose: a wrong video is worse than none, so anything doubtful gives no match. A match is a suggestion the person
 * confirms; the apps only run the search this names.
 */
object MusicVideos {
    /** Larger counts are refused: JavaScript (the desktop app) holds integers exactly only up to here. */
    private const val MAX_VIEWS = 9_007_199_254_740_991L

    /** Only the first rows are looked at: what the search ranks lower is rarely the official video. */
    const val ROWS = 6

    private const val VIDEOS_FILTER = "EgWKAQIQAWoKEAkQBRAKEAMQBA=="
    private const val ITEM_KEY = "musicResponsiveListItemRenderer"
    private const val MAX_ITEMS = 20
    private const val MAX_DEPTH = 40

    /** The JSON body of the videos search for [query] (sent to [MusicMatching.songsApiUrl] with [MusicMatching.songsApiHeaders]). */
    fun searchBody(query: String): String =
        "{\"context\":{\"client\":{\"clientName\":\"WEB_REMIX\",\"clientVersion\":\"1.20250101.01.00\",\"hl\":\"en\",\"gl\":\"US\"}}," +
            "\"query\":${jsonQuote(query.take(300))},\"params\":${jsonQuote(VIDEOS_FILTER)}}"

    /** The search text: the song's first artist, then its title. */
    fun query(artists: List<String>, title: String): String = jsTrim("${artists.firstOrNull() ?: ""} ${jsTrim(title)}")

    /** The rows of a videos search answer. Throws when [json] is not valid JSON. */
    fun parse(json: String): List<VideoCandidate> = videosFrom(parseJson(json))

    // -- reading ------------------------------------------------------------------------------------------------

    private fun videosFrom(data: JsonValue): List<VideoCandidate> {
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
        val out = ArrayList<VideoCandidate>()
        for (item in items) {
            val columns = (item.prop("flexColumns") as? JsonArray)?.items ?: emptyList()
            fun runs(index: Int): List<JsonValue> =
                (columns.getOrNull(index).prop("musicResponsiveListItemFlexColumnRenderer").prop("text").prop("runs") as? JsonArray)?.items ?: emptyList()
            val title = str(JsonString(runs(0).joinToString("") { stringOf(it) ?: "" })) ?: continue
            val watch = item.prop("overlay").prop("musicItemThumbnailOverlayRenderer").prop("content").prop("musicPlayButtonRenderer")
                .prop("playNavigationEndpoint").prop("watchEndpoint")
            val videoId = str(item.prop("playlistItemData").prop("videoId"), 20) ?: str(watch.prop("videoId"), 20) ?: continue
            if (!isVideoId(videoId)) continue
            val type = (watch.prop("watchEndpointMusicSupportedConfigs").prop("watchEndpointMusicConfig").prop("musicVideoType") as? JsonString)?.value
                ?.removePrefix("MUSIC_VIDEO_TYPE_") ?: ""
            val artists = ArrayList<VideoArtist>()
            var views: Long? = null
            var durationSec: Double? = null
            for (run in runs(1)) {
                val text = jsTrim(stringOf(run) ?: "")
                val browseId = str(run.prop("navigationEndpoint").prop("browseEndpoint").prop("browseId"), 100)
                if (browseId != null) artists.add(VideoArtist(text, browseId))
                else {
                    viewCount(text)?.let { views = it }
                    clockSeconds(text)?.let { durationSec = it }
                }
            }
            out.add(VideoCandidate(videoId, type, title, artists, views, durationSec))
        }
        return out
    }

    /** "3.2B views" is 3 200 000 000, "882M views", "18K views", "56 views", "1 view". Exact: no floating point. Null for anything else. */
    fun viewCount(text: String): Long? {
        val words = jsTrim(text).split(' ').filter { it.isNotEmpty() }
        if (words.size != 2 || (words[1] != "views" && words[1] != "view")) return null
        var number = words[0]
        val scale = when (number.lastOrNull()) {
            'K' -> 3
            'M' -> 6
            'B' -> 9
            else -> 0
        }
        if (scale > 0) number = number.dropLast(1)
        val dot = number.indexOf('.')
        val whole = if (dot < 0) number else number.substring(0, dot)
        val fraction = if (dot < 0) "" else number.substring(dot + 1)
        val digits = { s: String -> s.all { it in '0'..'9' } }
        if (whole.isEmpty() || !digits(whole) || !digits(fraction) || fraction.length > scale || (dot >= 0 && fraction.isEmpty())) return null
        var value = 0L
        for (c in whole + fraction) {
            value = value * 10 + (c - '0')
            if (value > MAX_VIEWS) return null
        }
        repeat(scale - fraction.length) {
            value *= 10
            if (value > MAX_VIEWS) return null
        }
        return value
    }

    // -- matching -----------------------------------------------------------------------------------------------

    /** Brackets that only decorate a video's title: removed before titles are compared. Compared normalised. */
    private val DECORATIONS = setOf(
        "official video", "official music video", "official hd video", "official 4k video", "official mv", "music video", "mv", "m v",
        "4k", "hd", "hq", "video", "video oficial", "videoclip", "videoclip oficial", "clip officiel", "official video 4k",
    )

    /** Words that make another recording: both titles must have the same ones. */
    private val QUALIFIERS = setOf("live", "remix", "acoustic", "unplugged", "demo", "edit", "version", "remaster", "remastered", "clean", "explicit")

    /** Never the music video: a still picture, lyrics, someone's edit or cover. Unless the song's own title has the word. */
    private val REJECTED = listOf(
        "official audio", "audio only", "lyric", "lyrics", "visualizer", "visualiser", "slowed", "sped up", "reverb", "8d", "cover", "karaoke", "instrumental",
    )

    private fun isFeaturing(inner: String): Boolean {
        val first = MusicMatching.normalize(inner).split(' ').firstOrNull() ?: return false
        return first == "feat" || first == "ft" || first == "with" || first == "featuring"
    }

    /** [title] without decorating brackets and featuring credits, and without a leading "<one of [artists]> - ". */
    internal fun cleanTitle(title: String, artists: List<String>): String {
        val kept = StringBuilder()
        var i = 0
        while (i < title.length) {
            val open = title[i]
            val close = if (open == '(') ')' else if (open == '[') ']' else null
            val end = if (close != null) title.indexOf(close, i + 1) else -1
            if (close != null && end > i) {
                val inner = title.substring(i + 1, end)
                // a credit that names another recording ("feat. X - Live") stays: the titles then differ
                if (MusicMatching.normalize(inner) in DECORATIONS || (isFeaturing(inner) && qualifiers(MusicMatching.normalize(inner)).isEmpty())) {
                    i = end + 1
                    continue
                }
            }
            kept.append(title[i])
            i++
        }
        var text = jsTrim(kept.toString())
        // "Adele - Hello": the artist before the first dash; the rest is the title, dashes and all
        val dash = DASH.find(text)
        if (dash != null && artists.any { MusicMatching.normalize(it) == MusicMatching.normalize(text.substring(0, dash.range.first)) }) text = jsTrim(text.substring(dash.range.last + 1))
        return text
    }

    private val DASH = Regex("$WS+[-–—]$WS+")

    private fun qualifiers(normalized: String): Set<String> = normalized.split(' ').filter { it in QUALIFIERS }.toSet()

    /** The song's artists and each of its credited artists ("Bob Marley & The Wailers" is also "Bob Marley" and "The Wailers"). */
    private fun artistNames(artists: List<String>): Set<String> =
        artists.flatMap { listOf(it) + artistSplit(it) }.map { MusicMatching.normalize(it) }.filter { it.isNotEmpty() }.toSet()

    /**
     * The official music video of the song by [artists] titled [title] among [candidates] (a videos search, best first), or null.
     * A row counts when it is an official video (OMV) by one of the song's artists, not a still picture, lyrics, a cover or an edit,
     * and its title, once decorations and featuring credits are removed, is the song's title with the same qualifiers (live, remix...).
     * [artistIds]: the song's artist channels (browse ids) when known; a row must then come from one of them.
     * Several rows that count (all have the song's title by then): the most watched, since the artist's channel often carries two
     * uploads of one video.
     */
    fun best(artists: List<String>, title: String, candidates: List<VideoCandidate>, artistIds: List<String> = emptyList()): VideoCandidate? {
        val names = artistNames(artists)
        if (names.isEmpty()) return null
        val want = MusicMatching.normalize(cleanTitle(title, artists))
        if (want.isEmpty()) return null
        val songWords = MusicMatching.normalize(title)
        var best: VideoCandidate? = null
        for (c in candidates.take(ROWS)) {
            if (c.type != "OMV") continue
            val raw = MusicMatching.normalize(c.title)
            if (REJECTED.any { containsJsWord(raw, it) && !containsJsWord(songWords, it) }) continue
            if (c.artists.none { MusicMatching.normalize(it.name) in names }) continue
            // when the song's artist channels are known, a namesake's channel does not count
            if (artistIds.isNotEmpty() && c.artists.none { it.browseId in artistIds }) continue
            val got = MusicMatching.normalize(cleanTitle(c.title, artists))
            if (got != want || qualifiers(got) != qualifiers(want)) continue
            if (best == null || (c.views ?: -1L) > (best.views ?: -1L)) best = c
        }
        return best
    }
}
