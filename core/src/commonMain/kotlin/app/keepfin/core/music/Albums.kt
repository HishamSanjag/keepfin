package app.keepfin.core.music

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.json.jsonQuote
import app.keepfin.core.json.parseJson
import app.keepfin.core.music.MusicMatching.known
import app.keepfin.core.music.MusicMatching.overlap
import app.keepfin.core.music.MusicMatching.tokens

/** An album found by an albums search on YouTube Music. [kind] is "Album", "EP" or "Single" when the page says. */
data class AlbumCandidate(
    val browseId: String,
    val title: String,
    val artists: List<String>,
    val year: Int? = null,
    val kind: String? = null,
)

/** One track of an album page. [position] is the 1-based place on the album. */
data class AlbumTrack(
    val videoId: String,
    val title: String,
    val artists: List<String>,
    val durationSec: Double? = null,
    val position: Int,
)

/** An album page. [trackCount] counts every row, including tracks without a playable video. */
data class AlbumPage(
    val title: String,
    val artists: List<String>,
    val year: Int? = null,
    val trackCount: Int,
    val tracks: List<AlbumTrack>,
)

/** The track found on an album. [confidence] is the track's, [albumConfidence] the album's; both are rounded to 0.01. */
data class AlbumMatch(
    val browseId: String,
    val albumTitle: String,
    val albumConfidence: Double,
    val position: Int,
    val candidate: Candidate,
    val confidence: Double,
)

/**
 * What to do next when matching through the album: fetch the album page [Fetch.browseId] (then ask again with the page
 * added), take [Matched], or give up with [None] and use the songs search.
 */
sealed interface AlbumStep {
    data class Fetch(val browseId: String) : AlbumStep
    data class Matched(val match: AlbumMatch) : AlbumStep
    data object None : AlbumStep
}

/**
 * Matching a track through its album on YouTube Music: find the same album (not a reissue, deluxe or anniversary edition
 * unless the source is one), then take the track at the same place. The platforms perform the two requests; every
 * decision is made here. Falling back to the songs search when no album fits is the caller's job.
 */
object AlbumMatching {
    /** An album must score at least this on the search results to be fetched. */
    const val SEARCH_MIN = 0.6

    /** An album must score at least this once its page is read (track count and year are known then). */
    const val ALBUM_MIN = 0.75

    /** The titles, without edition words, must be at least this alike (Dice coefficient of their words). */
    const val BASE_MIN = 0.75

    /** The source's artists must overlap the album's artists at least this much, or the album is not a candidate. */
    const val ARTIST_MIN = 0.5

    /** A track found on an album must score at least this. */
    const val TRACK_MIN = 0.7
    const val TRACK_MIN_TITLE_OVERLAP = 0.8
    const val TRACK_MAX_DELTA_SEC = 7.0

    /** Album pages fetched for one track, at most. */
    const val MAX_FETCHES = 2

    const val browseUrl: String = "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"

    private const val ALBUMS_FILTER = "EgWKAQIYAWoKEAkQBRAKEAMQBA=="
    private const val MAX_ITEMS = 20
    private const val MAX_DEPTH = 40
    private const val CLIENT =
        "\"context\":{\"client\":{\"clientName\":\"WEB_REMIX\",\"clientVersion\":\"1.20250101.01.00\",\"hl\":\"en\",\"gl\":\"US\"}}"

    /**
     * The text of the albums search: the first artist, then the album title. Only the first artist, so that every track
     * of an album (with its own featured artists) asks the same question and the answer is shared.
     */
    fun query(track: Track): String = jsTrim("${track.artists.firstOrNull() ?: ""} ${track.album ?: ""}")

    /** Whether this track can be matched through its album at all. */
    fun applies(track: Track): Boolean = !track.album.isNullOrBlank() && jsTrim(track.album).isNotEmpty()

    /** The JSON body of the albums search for [query] (same address and headers as the songs search). */
    fun searchBody(query: String): String = "{$CLIENT,\"query\":${jsonQuote(query.take(300))},\"params\":${jsonQuote(ALBUMS_FILTER)}}"

    /** The JSON body of the album page request. Null when [browseId] is not an album id (MPREb_ and then letters, digits, - or _). */
    fun browseBody(browseId: String): String? =
        if (isAlbumId(browseId)) "{$CLIENT,\"browseId\":${jsonQuote(browseId)}}" else null

    internal fun isAlbumId(value: String): Boolean =
        value.startsWith("MPREb_") && value.length in 7..60 &&
            value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }

    // -- reading responses -----------------------------------------------------------------------------------------------

    private fun rows(data: JsonValue, limit: Int): List<JsonValue> {
        val found = ArrayList<JsonValue>()
        fun walk(node: JsonValue?, depth: Int) {
            if (depth > MAX_DEPTH || found.size >= limit) return
            when (node) {
                is JsonObject -> {
                    val renderer = node.members["musicResponsiveListItemRenderer"]
                    if (truthy(renderer)) {
                        found.add(renderer!!)
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

    private fun runsOf(text: JsonValue?): List<JsonValue> = (text.prop("runs") as? JsonArray)?.items ?: emptyList()

    private fun columnRuns(item: JsonValue, index: Int): List<JsonValue> {
        val columns = (item.prop("flexColumns") as? JsonArray)?.items ?: emptyList()
        return runsOf(columns.getOrNull(index).prop("musicResponsiveListItemFlexColumnRenderer").prop("text"))
    }

    private fun pageType(run: JsonValue): String? =
        (run.prop("navigationEndpoint").prop("browseEndpoint").prop("browseEndpointContextSupportedConfigs")
            .prop("browseEndpointContextMusicConfig").prop("pageType") as? JsonString)?.value

    private fun artistRuns(runs: List<JsonValue>): List<String> =
        runs.filter { pageType(it).let { type -> type == "MUSIC_PAGE_TYPE_ARTIST" || type == "MUSIC_PAGE_TYPE_USER_CHANNEL" } }
            .map { jsTrim(stringOf(it) ?: "") }.filter { it.isNotEmpty() }

    private fun yearOf(text: String): Int? {
        val t = jsTrim(text)
        if (t.length != 4 || t.any { it !in '0'..'9' }) return null
        val year = t.toInt()
        return if (year in 1000..2999) year else null
    }

    /** Albums from an innertube WEB_REMIX search response (albums filter). Throws when [json] is not valid JSON. */
    fun parseSearch(json: String): List<AlbumCandidate> {
        val albums = ArrayList<AlbumCandidate>()
        for (item in rows(parseJson(json), MAX_ITEMS)) {
            val browseId = str(item.prop("navigationEndpoint").prop("browseEndpoint").prop("browseId"), 80)
            val title = str(JsonString(columnRuns(item, 0).joinToString("") { stringOf(it) ?: "" }))
            if (browseId == null || title == null || !isAlbumId(browseId)) continue
            val details = columnRuns(item, 1)
            var artists = artistRuns(details)
            var year: Int? = null
            var kind: String? = null
            details.forEachIndexed { index, run ->
                val text = jsTrim(stringOf(run) ?: "")
                if (index == 0 && pageType(run) == null && text.isNotEmpty() && text != "•") kind = text
                yearOf(text)?.let { year = it }
            }
            if (artists.isEmpty()) {
                val parts = details.joinToString("") { textOf(it) }.split(" • ")
                if (parts.size >= 2) artists = artistSplit(parts[1])
            }
            albums.add(AlbumCandidate(browseId, title, artists, year, kind))
        }
        return albums
    }

    private fun findKey(node: JsonValue?, key: String, depth: Int = 0): JsonValue? {
        if (depth > MAX_DEPTH) return null
        when (node) {
            is JsonObject -> {
                val own = node.members[key]
                if (truthy(own)) return own
                for (value in jsValues(node)) findKey(value, key, depth + 1)?.let { return it }
            }
            is JsonArray -> for (value in node.items) findKey(value, key, depth + 1)?.let { return it }
            else -> {}
        }
        return null
    }

    /** An album page from an innertube WEB_REMIX browse response. Null when it has no title or no tracks. Throws when [json] is not valid JSON. */
    fun parsePage(json: String): AlbumPage? {
        val data = parseJson(json)
        val header = findKey(data, "musicResponsiveHeaderRenderer") ?: findKey(data, "musicDetailHeaderRenderer")
        val title = str(JsonString(runsOf(header.prop("title")).joinToString("") { stringOf(it) ?: "" }))
        val subtitle = runsOf(header.prop("subtitle"))
        val strapline = runsOf(header.prop("straplineTextOne"))
        var artists = artistRuns(strapline).ifEmpty { artistRuns(subtitle) }
        if (artists.isEmpty()) artists = strapline.map { jsTrim(stringOf(it) ?: "") }.filter { it.isNotEmpty() && it != "•" }
        val year = subtitle.firstNotNullOfOrNull { yearOf(stringOf(it) ?: "") }
        val shelf = findKey(data, "musicShelfRenderer")
        val items = (shelf.prop("contents") as? JsonArray)?.items?.mapNotNull { it.prop("musicResponsiveListItemRenderer") } ?: emptyList()
        if (title == null || items.isEmpty()) return null
        val tracks = ArrayList<AlbumTrack>()
        items.forEachIndexed { index, item ->
            val titleRuns = columnRuns(item, 0)
            val name = str(JsonString(titleRuns.joinToString("") { stringOf(it) ?: "" }))
            val videoId = str(item.prop("playlistItemData").prop("videoId"), 20)
                ?: str(
                    titleRuns.firstOrNull { truthy(it.prop("navigationEndpoint").prop("watchEndpoint")) }
                        .prop("navigationEndpoint").prop("watchEndpoint").prop("videoId"),
                    20,
                )
            if (name == null || videoId == null || !isVideoId(videoId)) return@forEachIndexed
            val position = jsTrim(textOf(runsOf(item.prop("index")).firstOrNull())).let { text ->
                if (text.isNotEmpty() && text.length <= 4 && text.all { it in '0'..'9' } && text.toInt() > 0) text.toInt() else index + 1
            }
            val fixed = (item.prop("fixedColumns") as? JsonArray)?.items?.firstOrNull()
                .prop("musicResponsiveListItemFixedColumnRenderer").prop("text")
            val duration = runsOf(fixed).firstNotNullOfOrNull { clockSeconds(jsTrim(stringOf(it) ?: "")) }
            tracks.add(AlbumTrack(videoId, name, artistRuns(columnRuns(item, 1)).ifEmpty { artists }, duration, position))
        }
        return AlbumPage(title, artists, year, items.size, tracks)
    }

    // -- choosing the album -----------------------------------------------------------------------------------------------

    // Words that make a title a particular edition of an album. Titles are compared without them, and two titles
    // that carry different ones are different editions ("1989" is not "1989 (Taylor's Version)", whatever else matches).
    private val EDITION_WORDS = setOf(
        "deluxe", "anniversary", "remaster", "remastered", "remasters", "expanded", "reissue", "bonus", "version", "special",
        "collector", "collectors", "drumless", "instrumental", "acoustic", "live", "tour", "platinum", "complete", "super",
        "extended", "mono", "stereo", "demos", "demo", "anthology", "redux", "revisited", "legacy", "limited",
    )

    private fun isOrdinal(token: String): Boolean {
        if (token.length < 3) return false
        val suffix = token.substring(token.length - 2)
        if (suffix != "st" && suffix != "nd" && suffix != "rd" && suffix != "th") return false
        return token.substring(0, token.length - 2).all { it in '0'..'9' }
    }

    private fun isEditionToken(token: String) = token in EDITION_WORDS || isOrdinal(token)

    private fun editionOf(title: String): Set<String> = tokens(title).filter { isEditionToken(it) }.toSet()

    private fun baseTokens(title: String): Set<String> {
        val all = tokens(title)
        return all.filterNot { isEditionToken(it) }.toSet().ifEmpty { all }
    }

    private fun dice(a: Set<String>, b: Set<String>): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else 2.0 * a.count { it in b } / (a.size + b.size)

    /**
     * 0 to 1, not rounded: how likely an album with these details is the source track's album. Title (without edition
     * words) dominates, then artist, year and track count; unknown year or count counts as neutral. A different edition
     * (deluxe, anniversary, drumless, "Taylor's Version") loses 0.4 in either direction.
     */
    internal fun albumScore(track: Track, title: String, artists: List<String>, year: Int?, trackCount: Int?): Double {
        val source = track.album ?: return 0.0
        val base = dice(baseTokens(source), baseTokens(title))
        // Other titles are other albums ("Piano Covers", "Medley"), however well the artist and year fit.
        if (base < BASE_MIN) return 0.0
        val primary = tokens(track.artists.firstOrNull() ?: "")
        val candidateArtists = tokens(artists.joinToString(" "))
        val artist = if (track.artists.isEmpty()) 0.5
        else maxOf(overlap(primary, candidateArtists), 0.8 * overlap(tokens(track.artists.joinToString(" ")), candidateArtists))
        // The same title by someone else is a different album, whatever else fits.
        if (track.artists.isNotEmpty() && artist < ARTIST_MIN) return 0.0
        // A release from another year is another release (a reissue, a re-recording), whatever else fits.
        if (track.year != null && year != null && kotlin.math.abs(track.year - year) > 1) return 0.0
        val yearScore = if (track.year == null || year == null) 0.5
        else if (track.year == year) 1.0 else 0.7
        val countScore = if (track.trackCount == null || trackCount == null) 0.5
        else when (kotlin.math.abs(track.trackCount - trackCount)) { 0 -> 1.0; 1 -> 0.4; else -> 0.0 }
        var score = 0.5 * base + 0.2 * artist + 0.15 * yearScore + 0.15 * countScore
        if (editionOf(source) != editionOf(title)) score -= 0.4
        return maxOf(0.0, minOf(1.0, score))
    }

    /**
     * The album name to look for when the source does not give one (a Spotify track link carries none): the album of
     * the best songs-search result. The year, when the source has one, then decides which edition of that album it is.
     */
    fun hint(track: Track, candidates: List<Candidate>): String? =
        if (applies(track)) null else MusicMatching.best(track, candidates)?.candidate?.album?.let { jsTrim(it) }?.takeIf { it.isNotEmpty() }

    /** The albums worth fetching, best first (ties keep their order): search-result score at least [SEARCH_MIN], once per album. */
    fun rank(track: Track, albums: List<AlbumCandidate>): List<AlbumCandidate> {
        val seen = HashSet<String>()
        return albums.filter { seen.add(it.browseId) }
            .map { it to round2(albumScore(track, it.title, it.artists, it.year, null)) }
            .filter { it.second >= SEARCH_MIN }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    // -- choosing the track -----------------------------------------------------------------------------------------------

    private fun trackCandidate(track: Track, page: AlbumPage, entry: AlbumTrack) = Candidate(
        videoId = entry.videoId,
        url = "https://music.youtube.com/watch?v=${entry.videoId}",
        title = entry.title,
        artists = entry.artists,
        album = page.title,
        durationSec = entry.durationSec,
        origin = Origin.YTM_SONG,
    )

    private fun acceptable(track: Track, entry: AlbumTrack, confidence: Double): Boolean {
        if (confidence < TRACK_MIN) return false
        if (overlap(tokens(MusicMatching.coreTitle(track.title)), tokens(entry.title)) < TRACK_MIN_TITLE_OVERLAP) return false
        if (known(track.durationSec) && known(entry.durationSec) &&
            kotlin.math.abs(track.durationSec!! - entry.durationSec!!) > TRACK_MAX_DELTA_SEC
        ) return false
        return true
    }

    /**
     * The track on [page], or null when this is not the right album (its score, with the page's own title, year and
     * track count, is below [ALBUM_MIN]) or no track fits. The track at the source's position is tried first; when its
     * title or length do not fit, the best fitting track anywhere on the album is taken.
     */
    fun matchPage(track: Track, browseId: String, page: AlbumPage): AlbumMatch? {
        val albumConfidence = round2(albumScore(track, page.title, page.artists, page.year, page.trackCount))
        if (albumConfidence < ALBUM_MIN) return null
        fun matchOf(entry: AlbumTrack): AlbumMatch? {
            val candidate = trackCandidate(track, page, entry)
            val confidence = MusicMatching.confidence(track, candidate)
            return if (acceptable(track, entry, confidence)) AlbumMatch(browseId, page.title, albumConfidence, entry.position, candidate, confidence) else null
        }
        val number = track.trackNumber
        if (number != null) page.tracks.firstOrNull { it.position == number }?.let { entry -> matchOf(entry)?.let { return it } }
        var best: AlbumMatch? = null
        for (entry in page.tracks) {
            val match = matchOf(entry) ?: continue
            if (best == null || match.confidence > best.confidence) best = match
        }
        return best
    }

    /**
     * The next step. [fetched] holds the pages read so far by album id; a null page is an album whose page could not
     * be read. Albums are tried in [rank] order, at most [MAX_FETCHES] of them.
     */
    fun step(track: Track, albums: List<AlbumCandidate>, fetched: Map<String, AlbumPage?>): AlbumStep {
        if (!applies(track)) return AlbumStep.None
        for (album in rank(track, albums).take(MAX_FETCHES)) {
            if (!fetched.containsKey(album.browseId)) return AlbumStep.Fetch(album.browseId)
            val page = fetched[album.browseId] ?: continue
            matchPage(track, album.browseId, page)?.let { return AlbumStep.Matched(it) }
        }
        return AlbumStep.None
    }
}
