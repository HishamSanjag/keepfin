package app.keepfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The resolver's search loop on top of the shared core (app.keepfin.core.music): which searches run, in what order, and when it stops.
 * Fake functions stand in for the engine's flat search and the YouTube Music songs request, so nothing touches the network.
 */
class MusicResolverSearchTest {
    private val track = SourceTrack("Get Lucky", listOf("Daft Punk"), "Random Access Memories", 369.0, "https://example.test/track", null, null)

    /** A real captured songs-search response (fixtures/engine/music/samples): its first song is the official recording. */
    private val songsResponse: String by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val file = File(dir, "fixtures/engine/music/samples/ytmusic-search-songs.json")
            if (file.isFile) return@lazy file.readText(Charsets.UTF_8)
            dir = dir.parentFile
        }
        error("fixtures/engine was not found; run the unit tests from the repository checkout.")
    }

    private fun flat(vararg entries: Triple<String, String, Int>) = entries.joinToString(",", """{"entries":[""", "]}") { (id, title, seconds) ->
        """{"ie_key":"Youtube","id":"$id","url":"https://www.youtube.com/watch?v=$id","title":"$title","duration":$seconds,"channel":"Daft Punk - Topic"}"""
    }

    private class Fakes(val songs: () -> String, val flat: (String, Int) -> String) {
        var songsCalls = 0
        val flatTargets = ArrayList<String>()
        val resolver = MusicResolver(
            { target, playlistEnd -> flatTargets.add(target); flat(target, playlistEnd) },
            { url, headers, body ->
                songsCalls++
                check(url.startsWith("https://music.youtube.com/") && headers.isNotEmpty() && body.contains("Daft Punk Get Lucky")) { "unexpected songs request" }
                songs()
            },
        )
    }

    @Test fun aConfidentSongsResultStopsAfterTheFirstSearch() {
        val fakes = Fakes({ songsResponse }, { _, _ -> error("the flat search must not run") })
        val matches = fakes.resolver.matches(track)
        assertEquals(1, fakes.songsCalls)
        assertTrue(fakes.flatTargets.isEmpty())
        assertEquals("4D7u5KF7SP8", matches.first().candidate.videoId)
        assertEquals("4D7u5KF7SP8", fakes.resolver.match(track)?.candidate?.videoId)
    }

    @Test fun failingSearchesAreSkippedAndTheNextOneIsTried() {
        val fakes = Fakes({ error("songs request failed") }, { target, _ ->
            if (target.startsWith("https://music.youtube.com/search")) error("engine failed") else flat(Triple("aaaaaaaaaaa", "Daft Punk - Get Lucky", 369))
        })
        val matches = fakes.resolver.matches(track)
        assertEquals(1, fakes.songsCalls)
        assertEquals(2, fakes.flatTargets.size)
        assertTrue(fakes.flatTargets[0].startsWith("https://music.youtube.com/search?q=Daft%20Punk%20Get%20Lucky"))
        assertEquals("ytsearch5:Daft Punk Get Lucky", fakes.flatTargets[1])
        assertEquals(listOf("aaaaaaaaaaa"), matches.map { it.candidate.videoId })
    }

    @Test fun everySearchFailingGivesNoMatch() {
        val fakes = Fakes({ "not json" }, { _, _ -> error("engine failed") })
        assertTrue(fakes.resolver.matches(track).isEmpty())
        assertEquals(null, fakes.resolver.match(track))
        assertEquals(emptyList<String>(), fakes.resolver.alternates(track, emptySet()).map { it.candidate.videoId })
    }

    @Test fun alternatesStopOnceThereAreThree() {
        val three = flat(Triple("aaaaaaaaaaa", "Daft Punk - Get Lucky", 369), Triple("bbbbbbbbbbb", "Daft Punk - Get Lucky", 368),
            Triple("ccccccccccc", "Daft Punk - Get Lucky", 370), Triple("ddddddddddd", "Daft Punk - Get Lucky", 369))
        val fakes = Fakes({ error("songs request failed") }, { _, _ -> three })
        val alternates = fakes.resolver.alternates(track, setOf("ccccccccccc"))
        assertEquals(1, fakes.flatTargets.size)
        assertEquals(3, alternates.size)
        assertTrue(alternates.none { it.candidate.videoId == "ccccccccccc" })
    }

    @Test fun alternatesKeepSearchingWhileThereAreFewerThanThree() {
        val fakes = Fakes({ error("songs request failed") }, { _, _ -> flat(Triple("aaaaaaaaaaa", "Daft Punk - Get Lucky", 369)) })
        val alternates = fakes.resolver.alternates(track, emptySet())
        assertEquals(1, fakes.songsCalls)
        assertEquals(2, fakes.flatTargets.size)
        assertEquals(listOf("aaaaaaaaaaa"), alternates.map { it.candidate.videoId })
    }

    @Test fun theSongsRequestIsHttpsOnly() {
        val failure = runCatching { MusicResolver.post("http://music.youtube.com/youtubei/v1/search", emptyList(), "{}") }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
