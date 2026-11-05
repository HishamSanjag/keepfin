package app.keepfin

import app.keepfin.core.music.AlbumCandidate
import app.keepfin.core.music.AlbumMatch
import app.keepfin.core.music.AlbumMatching
import app.keepfin.core.music.AlbumPage
import app.keepfin.core.music.AlbumStep
import app.keepfin.core.music.Candidate
import app.keepfin.core.music.Match
import app.keepfin.core.music.MusicMatching
import app.keepfin.core.music.Origin
import app.keepfin.core.music.Search
import app.keepfin.core.music.Track
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/** One track as described by the source music service (never downloaded from it: its audio is DRM protected). */
data class SourceTrack(
    val title: String,
    val artists: List<String>,
    val album: String?,
    val duration: Double?,
    val url: String,
    val cover: String?,
    val position: Int?,
    val year: Int? = null,
    /** Tracks on the source album (album collections), used to tell editions apart. */
    val trackCount: Int? = null,
) {
    val artist get() = artists.joinToString(", ").ifBlank { null }
    fun tags() = SourceTags(title, artist, album, position, year, trackCount)
}

data class SourceCollection(
    /** spotify | apple-music | deezer */
    val source: String,
    /** track | album | playlist */
    val kind: String,
    val title: String?,
    val owner: String?,
    val cover: String?,
    val tracks: List<SourceTrack>,
    val total: Int? = null,
)

/** Pure parsers for public Spotify embed pages, the Deezer public API and Apple Music JSON-LD. */
object MusicParsers {
    private val spotifyPath = Regex("^/(?:intl-[a-z-]+/)?(?:embed/)?(track|album|playlist)/([A-Za-z0-9]{10,40})")
    private val deezerPath = Regex("^/(?:[a-z]{2}(?:-[a-z]{2})?/)?(track|album|playlist)/(\\d{1,20})")

    fun spotifyRef(url: String): Pair<String, String>? = try {
        val uri = URI(url)
        if (uri.host?.lowercase() != "open.spotify.com") null else spotifyPath.find(uri.path.orEmpty())?.let { it.groupValues[1] to it.groupValues[2] }
    } catch (_: Exception) { null }

    fun deezerRef(url: String): Pair<String, String>? = try {
        val uri = URI(url)
        if (!uri.host.orEmpty().lowercase().endsWith("deezer.com")) null else deezerPath.find(uri.path.orEmpty())?.let { it.groupValues[1] to it.groupValues[2] }
    } catch (_: Exception) { null }

    private fun JSONObject.text(key: String) = optString(key, "").takeIf { has(key) && !isNull(key) && it.isNotBlank() }

    private fun cleanArtists(value: String?): List<String> = value.orEmpty().replace(' ', ' ').split(",").map { it.trim() }.filter { it.isNotEmpty() }

    private fun bestImage(sources: JSONArray?): String? {
        if (sources == null) return null
        return (0 until sources.length()).mapNotNull { sources.optJSONObject(it) }
            .maxByOrNull { maxOf(it.optInt("maxWidth", 0), it.optInt("width", 0)) }?.text("url")
    }

    fun spotifyEmbed(html: String): SourceCollection {
        val json = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)
            ?: error("Spotify did not return playlist data. Try again later.")
        val entity = JSONObject(json).optJSONObject("props")?.optJSONObject("pageProps")?.optJSONObject("state")
            ?.optJSONObject("data")?.optJSONObject("entity") ?: error("Spotify did not return playlist data. Try again later.")
        val type = entity.optString("type", "track")
        val cover = bestImage(entity.optJSONObject("visualIdentity")?.optJSONArray("image")) ?: bestImage(entity.optJSONObject("coverArt")?.optJSONArray("sources"))
        val year = entity.optJSONObject("releaseDate")?.optString("isoString")?.take(4)?.toIntOrNull()
        val name = entity.text("name") ?: entity.text("title")
        if (type == "track") {
            val artists = entity.optJSONArray("artists")?.let { array -> (0 until array.length()).mapNotNull { array.optJSONObject(it)?.text("name") } }
                ?: cleanArtists(entity.text("subtitle"))
            val id = entity.optString("id")
            val track = SourceTrack(name ?: "Untitled", artists, null, entity.optDouble("duration", 0.0).takeIf { it > 0 }?.div(1000.0),
                "https://open.spotify.com/track/$id", cover, null, year)
            return SourceCollection("spotify", "track", track.title, track.artist, cover, listOf(track), 1)
        }
        val list = entity.optJSONArray("trackList") ?: JSONArray()
        val album = if (type == "album") name else null
        val tracks = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapIndexedNotNull { index, item ->
            val uri = item.optString("uri")
            val id = uri.substringAfter("spotify:track:", "").takeIf { it.matches(Regex("[A-Za-z0-9]{10,40}")) } ?: return@mapIndexedNotNull null
            SourceTrack(item.text("title") ?: "Untitled", cleanArtists(item.text("subtitle")), album,
                item.optDouble("duration", 0.0).takeIf { it > 0 }?.div(1000.0), "https://open.spotify.com/track/$id",
                if (type == "album") cover else null,
                // A playlist position is not a track number, and a playlist has no release year: only albums tag them.
                if (type == "album") index + 1 else null, if (type == "album") year else null)
        }
        return SourceCollection("spotify", type, name, entity.text("subtitle") ?: entity.optJSONObject("authors")?.text("name"), cover, tracks)
    }

    private fun deezerTrack(item: JSONObject, album: String?, cover: String?, position: Int?): SourceTrack? {
        val id = item.optLong("id").takeIf { it > 0 } ?: return null
        val itemAlbum = item.optJSONObject("album")
        return SourceTrack(item.text("title") ?: "Untitled", listOfNotNull(item.optJSONObject("artist")?.text("name")),
            itemAlbum?.text("title") ?: album, item.optDouble("duration", 0.0).takeIf { it > 0 }, "https://www.deezer.com/track/$id",
            itemAlbum?.text("cover_xl") ?: cover, item.optInt("track_position").takeIf { it > 0 } ?: position,
            item.text("release_date")?.take(4)?.toIntOrNull())
    }

    fun deezer(json: String, type: String, offset: Int = 0): SourceCollection {
        val root = JSONObject(json)
        root.optJSONObject("error")?.let { error("Deezer: ${it.optString("message", "not found")}") }
        if (type == "track") {
            val track = deezerTrack(root, null, null, null) ?: error("Deezer did not return this track.")
            return SourceCollection("deezer", "track", track.title, track.artist, track.cover, listOf(track), 1)
        }
        val cover = root.text("cover_xl") ?: root.text("picture_xl")
        val album = if (type == "album") root.text("title") else null
        val data = root.optJSONObject("tracks")?.optJSONArray("data") ?: root.optJSONArray("data") ?: JSONArray()
        val tracks = (0 until data.length()).mapNotNull { index -> data.optJSONObject(index)?.let { deezerTrack(it, album, cover, if (type == "album") offset + index + 1 else null) } }
        val owner = root.optJSONObject("artist")?.text("name") ?: root.optJSONObject("creator")?.text("name")
        return SourceCollection("deezer", type, root.text("title"), owner, cover, tracks, root.optInt("nb_tracks").takeIf { it > 0 } ?: root.optInt("total").takeIf { it > 0 })
    }

    /** ISO-8601 duration such as PT5M20S. */
    fun isoDuration(value: String?): Double? {
        val match = Regex("PT(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?").find(value ?: return null) ?: return null
        val (h, m, s) = match.destructured
        val total = (h.toDoubleOrNull() ?: 0.0) * 3600 + (m.toDoubleOrNull() ?: 0.0) * 60 + (s.toDoubleOrNull() ?: 0.0)
        return total.takeIf { it > 0 }
    }

    private fun names(array: JSONArray?): List<String> = array?.let { (0 until it.length()).mapNotNull { i -> it.optJSONObject(i)?.text("name") } } ?: emptyList()

    fun apple(html: String, url: String): SourceCollection {
        val blocks = Regex("<script[^>]*type=\"application/ld\\+json\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).findAll(html)
            .mapNotNull { runCatching { JSONObject(it.groupValues[1]) }.getOrNull() }.toList()
        val songId = Regex("[?&]i=(\\d+)").find(url)?.groupValues?.get(1)
        blocks.firstOrNull { it.optString("@type") == "MusicAlbum" || it.optString("@type") == "MusicPlaylist" }?.let { album ->
            val playlist = album.optString("@type") == "MusicPlaylist"
            val artists = names(album.optJSONArray("byArtist"))
            val cover = album.text("image")
            val title = album.text("name")
            val year = album.text("datePublished")?.take(4)?.toIntOrNull()
            val list = album.optJSONArray("tracks") ?: album.optJSONArray("track") ?: JSONArray()
            val tracks = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapIndexed { index, item ->
                SourceTrack(item.text("name") ?: "Untitled", names(item.optJSONArray("byArtist")).ifEmpty { artists }, if (playlist) null else title,
                    isoDuration(item.text("duration")), item.text("url") ?: url, item.optJSONObject("audio")?.text("thumbnailUrl") ?: cover,
                    if (playlist) null else index + 1, year)
            }
            if (songId != null) tracks.firstOrNull { it.url.endsWith("/$songId") }?.let { return SourceCollection("apple-music", "track", it.title, it.artist, it.cover, listOf(it), 1) }
            return SourceCollection("apple-music", if (playlist) "playlist" else "album", title, artists.joinToString(", ").ifBlank { null }, cover, tracks)
        }
        blocks.firstOrNull { it.optString("@type") in setOf("MusicComposition", "MusicRecording") }?.let { song ->
            val recording = song.optJSONObject("audio")?.takeIf { it.optString("@type") == "MusicRecording" } ?: song
            val inAlbum = recording.optJSONObject("inAlbum")
            val track = SourceTrack(recording.text("name") ?: song.text("name") ?: "Untitled", names(recording.optJSONArray("byArtist")),
                inAlbum?.text("name"), isoDuration(recording.text("duration") ?: song.text("timeRequired")), song.text("url") ?: url,
                song.text("image") ?: recording.text("image"), null, (song.text("datePublished"))?.take(4)?.toIntOrNull())
            return SourceCollection("apple-music", "track", track.title, track.artist, track.cover, listOf(track), 1)
        }
        // Last resort: Open Graph "Title by Artist on Apple Music".
        val og = Regex("<meta property=\"og:title\" content=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
            ?.replace(" on Apple Music", "")?.replace("&amp;", "&") ?: error("Apple Music page could not be read. Paste a song or album link.")
        val title = og.substringBeforeLast(" by ").trim()
        val artist = og.substringAfterLast(" by ", "").trim()
        val track = SourceTrack(title, listOfNotNull(artist.ifBlank { null }), null, null, url, null, null)
        return SourceCollection("apple-music", "track", title, artist.ifBlank { null }, null, listOf(track), 1)
    }
}

/** A recording picked for a music-service song, and how: [method] is "chosen", "album" or "search". */
internal data class Pick(
    val match: Match,
    val method: String,
    val album: String? = null,
    val position: Int? = null,
    val alternates: List<Match> = emptyList(),
) {
    fun toMusicMatch(source: String, sourceUrl: String) = MusicMatch(
        source, sourceUrl, match.candidate.url, match.candidate.title, match.confidence, method, album, position, match.candidate.durationSec,
        alternates.take(MusicMatching.MAX_ALTERNATES).map { MusicAlternate(it.candidate.url, it.candidate.title, it.candidate.durationSec, it.confidence) },
    )
}

/**
 * Resolves Spotify / Apple Music / Deezer links to source metadata and matches each track on YouTube Music.
 * The matching decision (which searches, scoring, stop rule, alternates) is the shared core's (app.keepfin.core.music), the
 * same as the desktop app's. This class only performs the requests: [search] runs the engine's flat search of a target and
 * returns its raw JSON (reading at most `playlistEnd` entries); [songsPost] sends the YouTube Music songs request.
 */
internal class MusicResolver(
    private val search: (target: String, playlistEnd: Int) -> String,
    /** POSTs a YouTube Music request (songs search, albums search, album page) and returns the response text. */
    private val songsPost: (url: String, headers: List<Pair<String, String>>, body: String) -> String = { url, headers, body -> post(url, headers, body) },
    /** The recording the user picked for a source link, if any. */
    private val chosen: (sourceUrl: String) -> MusicChoiceStore.Choice? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        private const val ALBUM_CACHE_MS = 10 * 60_000L
        private const val ALBUM_CACHE_SIZE = 100

        private const val AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
        private const val MAX_BYTES = 4_000_000

        fun get(url: String, timeoutMs: Int = 15_000): Pair<String, String> {
            var current = URL(url)
            repeat(6) {
                val connection = (current.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false; connectTimeout = timeoutMs; readTimeout = timeoutMs
                    setRequestProperty("User-Agent", AGENT); setRequestProperty("Accept-Language", "en-US,en;q=0.8")
                }
                try {
                    val code = connection.responseCode
                    if (code in 300..399) {
                        val location = connection.getHeaderField("Location") ?: error("Music service redirect without a target.")
                        current = URL(current, location)
                        require(current.protocol == "https") { "Music service redirected to an insecure link." }
                        return@repeat
                    }
                    check(code in 200..299) { "Music service returned HTTP $code." }
                    val bytes = connection.inputStream.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            check(out.size() <= MAX_BYTES) { "Music service response is too large." }
                        }
                        out.toByteArray()
                    }
                    return current.toString() to String(bytes, Charsets.UTF_8)
                } finally { connection.disconnect() }
            }
            error("Too many redirects from the music service.")
        }

        /**
         * POST [body] (UTF-8 JSON) to an https [url] and return the response text. Redirects are not followed (a 3xx is an error),
         * timeouts are [timeoutMs], and the response is capped like [get]. Any non-2xx answer throws.
         */
        fun post(url: String, headers: List<Pair<String, String>>, body: String, timeoutMs: Int = 15_000): String {
            val target = URL(url)
            require(target.protocol == "https") { "Music service link must be https." }
            val connection = (target.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                instanceFollowRedirects = false; connectTimeout = timeoutMs; readTimeout = timeoutMs
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            try {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                check(code in 200..299) { "Music service returned HTTP $code." }
                val bytes = connection.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        check(out.size() <= MAX_BYTES) { "Music service response is too large." }
                    }
                    out.toByteArray()
                }
                return String(bytes, Charsets.UTF_8)
            } finally { connection.disconnect() }
        }

        /** Follows short links (spotify.link, spoti.fi, deezer.page.link, link.deezer.com) to the canonical page URL. */
        fun canonical(url: String): String {
            val host = Providers.host(url)
            if (host !in setOf("spotify.link", "spoti.fi", "deezer.page.link", "link.deezer.com")) return url
            val (final, body) = get(url)
            if (Providers.host(final) != host) return final
            // Some short-link services answer with an HTML/JS redirect.
            return Regex("https://(?:open\\.spotify\\.com|www\\.deezer\\.com)/[^\"'<>\\s]+").find(body)?.value?.replace("&amp;", "&") ?: final
        }
    }

    fun resolve(input: String, start: Int = 1, end: Int = 500): SourceCollection {
        val collection = resolvePage(input, start, end)
        // Every track of an album knows how many tracks the album has, which tells editions apart.
        if (collection.kind != "album") return collection
        val count = collection.total ?: collection.tracks.size
        return collection.copy(tracks = collection.tracks.map { it.copy(trackCount = it.trackCount ?: count) })
    }

    private fun resolvePage(input: String, start: Int, end: Int): SourceCollection {
        val url = canonical(input)
        return when (Providers.musicSource(url)) {
            "spotify" -> {
                val (type, id) = MusicParsers.spotifyRef(url) ?: error("Paste a Spotify track, album or playlist link. Artist pages aren't supported.")
                MusicParsers.spotifyEmbed(get("https://open.spotify.com/embed/$type/$id").second)
            }
            "deezer" -> {
                val (type, id) = MusicParsers.deezerRef(url) ?: error("Paste a Deezer track, album or playlist link.")
                if (type == "track") MusicParsers.deezer(get("https://api.deezer.com/track/$id").second, type)
                else {
                    val head = MusicParsers.deezer(get("https://api.deezer.com/$type/$id").second, type)
                    val limit = (end - start + 1).coerceIn(1, 500)
                    val page = MusicParsers.deezer(get("https://api.deezer.com/$type/$id/tracks?index=${start - 1}&limit=$limit").second, type, start - 1)
                    head.copy(tracks = page.tracks.map { it.copy(album = it.album ?: head.title.takeIf { type == "album" }, cover = it.cover ?: head.cover) },
                        total = page.total ?: head.total)
                }
            }
            "apple-music" -> MusicParsers.apple(get(url).second, url)
            else -> error("This isn't a Spotify, Apple Music or Deezer link.")
        }
    }

    private fun coreTrack(track: SourceTrack) = Track(track.title, track.artists, track.album, track.duration, track.position, track.trackCount, track.year)

    /** The candidates one planned search finds. Throws when the request or its answer fails; callers skip that search. */
    private fun candidatesOf(step: Search): List<Candidate> = when (step) {
        is Search.SongsApi ->
            MusicMatching.parseSongsApi(songsPost(MusicMatching.songsApiUrl, MusicMatching.songsApiHeaders, MusicMatching.songsApiBody(step.query)))
        is Search.Flat -> MusicMatching.flatCandidates(search(step.target, step.playlistEnd), step.origin)
    }

    /**
     * The song's official music video (the core's strict matcher, app.keepfin.core.music.MusicVideos) or null when there is none. The same request
     * path as the songs search. Throws when the request or its answer fails, which callers tell apart from "none".
     */
    fun findVideo(artists: List<String>, title: String): app.keepfin.core.music.VideoCandidate? {
        val query = app.keepfin.core.music.MusicVideos.query(artists, title)
        val answer = songsPost(MusicMatching.songsApiUrl, MusicMatching.songsApiHeaders, app.keepfin.core.music.MusicVideos.searchBody(query))
        return app.keepfin.core.music.MusicVideos.best(artists, title, app.keepfin.core.music.MusicVideos.parse(answer))
    }

    // -- matching through the album ------------------------------------------------------------------------------------

    private class Cached<T>(val value: T, val at: Long)

    private val cacheLock = Any()
    private val keyLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private val albumSearches = LinkedHashMap<String, Cached<List<AlbumCandidate>>>()
    private val albumPages = LinkedHashMap<String, Cached<AlbumPage>>()

    /**
     * Runs [load] once per [key] while the cached answer is fresh (10 minutes). Every track of an album, and parallel
     * workers, share one albums search and one album page. A failed load (or a null) is not kept.
     */
    private fun <T : Any> shared(cache: MutableMap<String, Cached<T>>, key: String, load: () -> T?): T? {
        if (keyLocks.size > 500) keyLocks.clear()
        val lock = keyLocks.getOrPut("${System.identityHashCode(cache)}:$key") { Any() }
        synchronized(lock) {
            synchronized(cacheLock) { cache[key]?.takeIf { clock() - it.at < ALBUM_CACHE_MS }?.let { return it.value } }
            val value = load() ?: return null
            synchronized(cacheLock) {
                if (cache.size >= ALBUM_CACHE_SIZE) cache.remove(cache.keys.first())
                cache[key] = Cached(value, clock())
            }
            return value
        }
    }

    private fun albumSearch(query: String): List<AlbumCandidate>? = shared(albumSearches, query) {
        AlbumMatching.parseSearch(songsPost(MusicMatching.songsApiUrl, MusicMatching.songsApiHeaders, AlbumMatching.searchBody(query)))
    }

    private fun albumPage(browseId: String): AlbumPage? = shared(albumPages, browseId) {
        AlbumMatching.browseBody(browseId)?.let { body -> AlbumMatching.parsePage(songsPost(AlbumMatching.browseUrl, MusicMatching.songsApiHeaders, body)) }
    }

    /**
     * The track found through its album: an albums search, then the album page the core asks for (at most
     * [AlbumMatching.MAX_FETCHES]). Null when no album fits or a request fails, and the songs search takes over.
     */
    private fun viaAlbum(source: Track): AlbumMatch? {
        if (!AlbumMatching.applies(source)) return null
        val albums = try { albumSearch(AlbumMatching.query(source)) } catch (_: Exception) { null } ?: return null
        val fetched = LinkedHashMap<String, AlbumPage?>()
        repeat(AlbumMatching.MAX_FETCHES + 4) {
            when (val step = AlbumMatching.step(source, albums, fetched)) {
                is AlbumStep.Fetch -> fetched[step.browseId] = try { albumPage(step.browseId) } catch (_: Exception) { null }
                is AlbumStep.Matched -> return step.match
                AlbumStep.None -> return null
            }
        }
        return null
    }

    /**
     * Recordings for [track], best first, found lazily (a later one is looked for only when the earlier ones are not usable):
     * the recording the user chose for this link; the same track on the same album (shared album lookups); then the songs
     * search and the rest of the search plan. A track without an album (a Spotify track link) takes the album of the best
     * songs-search result, and its release year then tells the editions apart. [limit] caps the search matches.
     */
    fun picks(track: SourceTrack, limit: Int = 3): Sequence<Pick> = sequence {
        val source = coreTrack(track)
        chosen(track.url)?.let { choice ->
            val candidate = Candidate(
                videoId = choice.videoId, url = "https://music.youtube.com/watch?v=${choice.videoId}", title = choice.title ?: track.title,
                artists = emptyList(), durationSec = choice.durationSec, origin = Origin.YTM_SONG,
            )
            yield(Pick(Match(candidate, 1.0), "chosen"))
        }
        val steps = MusicMatching.searches(source)
        val found = ArrayList<Candidate>()
        var hinted = source
        var firstDone = false
        if (!AlbumMatching.applies(source)) {
            runCatching { candidatesOf(steps[0]) }.getOrNull()?.let { found.addAll(it) }
            firstDone = true
            AlbumMatching.hint(source, found)?.let { hinted = source.copy(album = it) }
        }
        viaAlbum(hinted)?.let { yield(Pick(Match(it.candidate, it.confidence), "album", it.albumTitle, it.position)) }
        for ((index, step) in steps.withIndex()) {
            if (!(index == 0 && firstDone)) runCatching { candidatesOf(step) }.getOrNull()?.let { found.addAll(it) }
            if (MusicMatching.isConfident(MusicMatching.best(source, found))) break
        }
        for (match in MusicMatching.ranked(source, found).take(limit)) {
            yield(Pick(match, "search", alternates = MusicMatching.alternates(source, found, setOf(match.candidate.videoId))))
        }
    }

    fun match(track: SourceTrack): Match? = matches(track, 1).firstOrNull()

    /**
     * Best matches first. Runs the planned searches in order (YouTube Music songs, YouTube Music flat, YouTube flat), skips a
     * search that fails, accumulates the candidates and stops as soon as the best match so far is confident.
     */
    fun matches(track: SourceTrack, limit: Int = 3): List<Match> {
        val source = coreTrack(track)
        val found = ArrayList<Candidate>()
        for (step in MusicMatching.searches(source)) {
            runCatching { candidatesOf(step) }.getOrNull()?.let { found.addAll(it) }
            if (MusicMatching.isConfident(MusicMatching.best(source, found))) break
        }
        return MusicMatching.ranked(source, found).take(limit)
    }

    /**
     * Other uploads of the same recording (not [exclude]d, close in length, confident), best first, at most
     * [MusicMatching.MAX_ALTERNATES]. Ranks after every search and stops once there are enough.
     */
    fun alternates(track: SourceTrack, exclude: Set<String>): List<Match> {
        val source = coreTrack(track)
        val found = ArrayList<Candidate>()
        var ranked: List<Match> = emptyList()
        for (step in MusicMatching.searches(source)) {
            runCatching { candidatesOf(step) }.getOrNull()?.let { found.addAll(it) }
            ranked = MusicMatching.alternates(source, found, exclude)
            if (ranked.size >= MusicMatching.MAX_ALTERNATES) break
        }
        return ranked
    }
}

