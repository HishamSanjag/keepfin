package app.keepfin

import org.junit.Assert.*
import org.junit.Test

class MusicTest {
    @Test fun spotifyTrack() {
        val c = MusicParsers.spotifyEmbed(Fixtures.text("music/spotify-track-embed.html"))
        assertEquals("track", c.kind)
        val t = c.tracks.single()
        assertEquals("Never Gonna Give You Up", t.title)
        assertEquals(listOf("Rick Astley"), t.artists)
        assertEquals(213.573, t.duration!!, 0.01)
        assertEquals("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT", t.url)
        assertEquals(1987, t.year)
        assertTrue(t.cover!!.contains("b273")) // largest cover image
    }

    @Test fun spotifyPlaylistAndAlbum() {
        val playlist = MusicParsers.spotifyEmbed(Fixtures.text("music/spotify-playlist-embed.html"))
        assertEquals("playlist", playlist.kind)
        assertEquals(4, playlist.tracks.size)
        assertEquals("Patient Zero", playlist.tracks[0].title)
        assertEquals(listOf("Taylor Swift"), playlist.tracks[0].artists)
        assertTrue(playlist.tracks.all { it.url.startsWith("https://open.spotify.com/track/") })
        val album = MusicParsers.spotifyEmbed(Fixtures.text("music/spotify-album-embed.html"))
        assertEquals("album", album.kind)
        assertEquals("Global Warming", album.title)
        assertEquals("Global Warming", album.tracks.first().album)
        assertEquals(listOf("Pitbull", "Sensato"), album.tracks.first().artists) // non-breaking space separated
        assertEquals(1, album.tracks.first().position)
    }

    @Test fun spotifyRefsAndErrors() {
        assertEquals("track" to "4cOdK2wGLETKBW3PvgPWqT", MusicParsers.spotifyRef("https://open.spotify.com/intl-de/track/4cOdK2wGLETKBW3PvgPWqT?si=x"))
        assertEquals("playlist" to "37i9dQZF1DXcBWIGoYBM5M", MusicParsers.spotifyRef("https://open.spotify.com/embed/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertNull(MusicParsers.spotifyRef("https://open.spotify.com/artist/0gxyHStUsqpMadRV0Di1Qt"))
        assertNull(MusicParsers.spotifyRef("https://evil.example/track/4cOdK2wGLETKBW3PvgPWqT"))
        assertThrows(IllegalStateException::class.java) { MusicParsers.spotifyEmbed("<html>blocked</html>") }
    }

    @Test fun deezer() {
        assertEquals("track" to "3135556", MusicParsers.deezerRef("https://www.deezer.com/en/track/3135556"))
        assertNull(MusicParsers.deezerRef("https://example.com/track/1"))
        val track = MusicParsers.deezer(Fixtures.text("music/deezer-track.json"), "track")
        assertEquals("Harder, Better, Faster, Stronger", track.tracks.single().title)
        assertEquals(226.0, track.tracks.single().duration!!, 0.1)
        val album = MusicParsers.deezer(Fixtures.text("music/deezer-album.json"), "album")
        assertEquals("Discovery", album.title)
        assertEquals(14, album.total)
        assertEquals("Discovery", album.tracks.first().album)
        assertTrue(album.tracks.isNotEmpty())
        val playlist = MusicParsers.deezer(Fixtures.text("music/deezer-playlist.json"), "playlist")
        assertEquals("En mode 60", playlist.title)
        assertTrue(playlist.tracks.isNotEmpty())
        assertThrows(IllegalStateException::class.java) { MusicParsers.deezer("""{"error":{"message":"no data"}}""", "track") }
    }

    @Test fun deezerCarriesIsrcWhenTheAnswerHasOne() {
        val withCode = MusicParsers.deezer("""{"id":3135556,"title":"Harder","isrc":"GBDUW0000059","duration":226,"artist":{"name":"Daft Punk"}}""", "track")
        assertEquals("GBDUW0000059", withCode.tracks.single().isrc)
        val page = MusicParsers.deezer("""{"data":[{"id":1,"title":"a","isrc":"GBDUW0000053"},{"id":2,"title":"b"}],"total":2}""", "album")
        assertEquals(listOf("GBDUW0000053", null), page.tracks.map { it.isrc })
    }

    @Test fun apple() {
        val song = MusicParsers.apple(Fixtures.text("music/apple-song.html"), "https://music.apple.com/us/song/one-more-time/697195462")
        assertEquals("track", song.kind)
        assertEquals("One More Time", song.tracks.single().title)
        assertEquals(320.0, song.tracks.single().duration!!, 0.1)
        assertEquals(2000, song.tracks.single().year)
        val album = MusicParsers.apple(Fixtures.text("music/apple-album.html"), "https://music.apple.com/us/album/discovery/697194953")
        assertEquals("album", album.kind)
        assertEquals("Discovery", album.title)
        assertEquals(3, album.tracks.size)
        assertEquals("Discovery", album.tracks[0].album)
        assertEquals(listOf("Daft Punk"), album.tracks[0].artists)
        assertThrows(IllegalStateException::class.java) { MusicParsers.apple("<html></html>", "https://music.apple.com/x") }
    }

    @Test fun isoDurations() {
        assertEquals(320.0, MusicParsers.isoDuration("PT5M20S")!!, 0.0)
        assertEquals(3725.0, MusicParsers.isoDuration("PT1H2M5S")!!, 0.0)
        assertNull(MusicParsers.isoDuration("garbage"))
    }

    private val track = SourceTrack("One More Time", listOf("Daft Punk"), "Discovery", 320.0, "u", null, 1)

    @Test fun sourceTrackTags() {
        assertEquals(SourceTags("One More Time", "Daft Punk", "Discovery", 1, null), track.tags())
    }
}
