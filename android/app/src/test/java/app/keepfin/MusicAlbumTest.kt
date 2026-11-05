package app.keepfin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The album route of the resolver on top of the shared core: one albums search and one album page for a whole album (also with
 * parallel workers), the lazily found fallbacks, the remembered choice, and persistence of the richer match. Real YouTube Music
 * captures from fixtures/engine/music/samples stand in for the network.
 */
class MusicAlbumTest {
    @get:Rule val temp = TemporaryFolder()

    private fun sample(name: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val file = File(dir, "fixtures/engine/music/samples/$name")
            if (file.isFile) return file.readText(Charsets.UTF_8)
            dir = dir.parentFile
        }
        error("fixtures/engine was not found; run the unit tests from the repository checkout.")
    }

    // Spotify's Random Access Memories as the embed lists it: title, artists, seconds. Album of 13.
    private val ram = listOf(
        Triple("Give Life Back to Music", listOf("Daft Punk"), 275.386),
        Triple("The Game of Love", listOf("Daft Punk"), 322.146),
        Triple("Giorgio by Moroder", listOf("Daft Punk"), 544.626),
        Triple("Within", listOf("Daft Punk"), 228.506),
        Triple("Instant Crush (feat. Julian Casablancas)", listOf("Daft Punk", "Julian Casablancas"), 337.56),
        Triple("Lose Yourself to Dance (feat. Pharrell Williams)", listOf("Daft Punk", "Pharrell Williams"), 353.893),
        Triple("Touch (feat. Paul Williams)", listOf("Daft Punk", "Paul Williams"), 498.96),
        Triple("Get Lucky (feat. Pharrell Williams and Nile Rodgers)", listOf("Daft Punk", "Pharrell Williams", "Nile Rodgers"), 369.626),
        Triple("Beyond", listOf("Daft Punk"), 290.24),
        Triple("Motherboard", listOf("Daft Punk"), 341.653),
        Triple("Fragments of Time (feat. Todd Edwards)", listOf("Daft Punk", "Todd Edwards"), 279.773),
        Triple("Doin' it Right (feat. Panda Bear)", listOf("Daft Punk", "Panda Bear"), 251.293),
        Triple("Contact", listOf("Daft Punk"), 383.626),
    )

    private fun albumTracks() = ram.mapIndexed { index, (title, artists, seconds) ->
        SourceTrack(title, artists, "Random Access Memories", seconds, "https://open.spotify.com/track/ram$index", null, index + 1, null, 13)
    }

    private class Network(private val sample: (String) -> String) {
        val albums = AtomicInteger()
        val pages = AtomicInteger()
        val songs = AtomicInteger()
        fun post(url: String, headers: List<Pair<String, String>>, body: String): String {
            check(url.startsWith("https://music.youtube.com/youtubei/v1/") && headers.isNotEmpty()) { "unexpected request $url" }
            val json = JSONObject(body)
            return when {
                json.has("browseId") -> {
                    pages.incrementAndGet()
                    sample(when (json.getString("browseId")) {
                        "MPREb_K8qWMWVqXGi" -> "ytmusic-album-ram.json"
                        "MPREb_OgeEnoHTsCm" -> "ytmusic-album-ram-drumless.json"
                        else -> error("unexpected album")
                    })
                }
                json.optString("params") == "EgWKAQIYAWoKEAkQBRAKEAMQBA==" -> { albums.incrementAndGet(); sample("ytmusic-search-albums-ram.json") }
                else -> { songs.incrementAndGet(); sample("ytmusic-search-songs.json") }
            }
        }
    }

    private fun resolver(network: Network, chosen: (String) -> MusicChoiceStore.Choice? = { null }) =
        MusicResolver({ _, _ -> """{"entries":[]}""" }, network::post, chosen)

    @Test fun aWholeAlbumIsMatchedThroughOneAlbumsSearchAndOneAlbumPage() {
        val network = Network(::sample)
        val resolver = resolver(network)
        val picks = albumTracks().map { resolver.picks(it, 1).first() }
        assertEquals(List(13) { "album" }, picks.map { it.method })
        assertEquals((1..13).toList(), picks.map { it.position })
        assertEquals("ajGKWk0auOc", picks[1].match.candidate.videoId)
        assertTrue(picks.all { it.album == "Random Access Memories" })
        assertEquals(1, network.albums.get()); assertEquals(1, network.pages.get()); assertEquals(0, network.songs.get())
    }

    @Test fun parallelWorkersShareTheSameLookups() {
        val network = Network(::sample)
        val resolver = resolver(network)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val picks = pool.invokeAll(albumTracks().map { track -> Callable { resolver.picks(track, 1).first() } }).map { it.get() }
            assertEquals(List(13) { "album" }, picks.map { it.method })
        } finally { pool.shutdown() }
        assertEquals(1, network.albums.get()); assertEquals(1, network.pages.get())
    }

    @Test fun aSpotifyTrackWithoutAnAlbumTakesTheAlbumOfTheBestSongResultAndTheYearPicksTheEdition() {
        val network = Network(::sample)
        val track = SourceTrack("Get Lucky", listOf("Daft Punk"), null, 369.626, "https://open.spotify.com/track/69kOkLUCkxIZYexIgSG8rq", null, null, 2013)
        val pick = resolver(network).picks(track, 3).first()
        assertEquals("album", pick.method)
        assertEquals("Random Access Memories", pick.album)
        assertEquals(8, pick.position)
        assertEquals(1, network.songs.get()); assertEquals(1, network.albums.get()); assertEquals(1, network.pages.get())
    }

    @Test fun anAlbumThatCannotBeReadHandsOverToTheSongsSearchAndIsNotCached() {
        var fail = true
        val network = Network(::sample)
        val resolver = MusicResolver({ _, _ -> """{"entries":[]}""" }, { url, headers, body ->
            if (fail && JSONObject(body).has("browseId")) error("offline") else network.post(url, headers, body)
        })
        val track = albumTracks()[7]
        val first = resolver.picks(track, 1).first()
        assertEquals("search", first.method)
        fail = false
        assertEquals("album", resolver.picks(track, 1).first().method)
    }

    @Test fun aRememberedChoiceComesFirstAndNeedsNoRequest() {
        val network = Network(::sample)
        val choice = MusicChoiceStore.Choice("AAAAAAAAAAA", "Picked", 200.0)
        val picks = resolver(network) { choice }.picks(albumTracks()[1], 1)
        val first = picks.first()
        assertEquals("chosen", first.method)
        assertEquals("https://music.youtube.com/watch?v=AAAAAAAAAAA", first.match.candidate.url)
        assertEquals(0, network.albums.get() + network.pages.get() + network.songs.get())
        // The next recordings are only looked for when asked for.
        assertEquals("album", picks.drop(1).first().method)
    }

    @Test fun theChoiceStoreValidatesAndSurvivesRestart() {
        val file = File(temp.root, "music-choices.json")
        val store = MusicChoiceStore(file)
        store.set("https://open.spotify.com/track/3ctALmweZBapfBdFiIVpji?si=abc", "ajGKWk0auOc", "The Game of Love", 323.0)
        assertEquals("ajGKWk0auOc", MusicChoiceStore(file).get("https://open.spotify.com/track/3ctALmweZBapfBdFiIVpji")?.videoId)
        assertTrue(runCatching { store.set("https://example.com/x", "ajGKWk0auOc") }.isFailure)
        assertTrue(runCatching { store.set("https://open.spotify.com/track/3ctALmweZBapfBdFiIVpji", "short") }.isFailure)
        file.writeText("""{"choices":{"https://open.spotify.com/track/0dEIca2nhcxDUV8C5QkPYb":{"videoId":"BBBBBBBBBBB"},"https://open.spotify.com/track/2cGxRwrMyEAp8dEbuZaVv6":{"videoId":"bad"},"http://insecure.example/x":{"videoId":"CCCCCCCCCCC"}}}""")
        val loaded = MusicChoiceStore(file)
        assertEquals("BBBBBBBBBBB", loaded.get("https://open.spotify.com/track/0dEIca2nhcxDUV8C5QkPYb")?.videoId)
        assertNull(loaded.get("https://open.spotify.com/track/2cGxRwrMyEAp8dEbuZaVv6"))
        file.writeText("{not json")
        assertNull(MusicChoiceStore(file).get("https://open.spotify.com/track/0dEIca2nhcxDUV8C5QkPYb"))
    }
}
