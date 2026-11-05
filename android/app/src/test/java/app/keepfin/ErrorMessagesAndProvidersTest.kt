package app.keepfin

import org.junit.Assert.*
import org.junit.Test

class ErrorMessagesTest {
    @Test fun neverLeaksToolNames() {
        val raw = "WARNING: x\nERROR: [youtube] abc: yt-dlp crashed in python3 while running oops via qqq and zzz"
        val shown = ErrorMessages.friendly(raw)
        assertFalse(shown, Regex("yt-?dlp|youtube-?dl|ffmpeg|aria2|python|ERROR:|youtube]", RegexOption.IGNORE_CASE).containsMatchIn(shown))
        assertFalse(Regex("yt-?dlp|ffmpeg|python", RegexOption.IGNORE_CASE).containsMatchIn(ErrorMessages.scrub("yt-dlp 2026 / Python 3.11 / ffmpeg 7")))
    }

    @Test fun mapsKnownFailures() {
        fun friendly(raw: String) = ErrorMessages.friendly(raw)
        assertTrue(friendly("ERROR: [youtube] abc: Private video. Sign in if you've been granted access").startsWith("Sign in required"))
        assertTrue(friendly("ERROR: [youtube] abc: Sign in to confirm you're not a bot. Use --cookies-from-browser or --cookies for the authentication").startsWith("YouTube asked to confirm"))
        assertTrue(friendly("ERROR: This video is not available in your country").contains("region"))
        assertTrue(friendly("ERROR: unable to download video data: HTTP Error 403: Forbidden").contains("HTTP 403"))
        assertTrue(friendly("ERROR: HTTP Error 429: Too Many Requests").startsWith("YouTube is limiting downloads"))
        assertTrue(friendly("ERROR: Unsupported URL: https://example.com/").contains("isn't supported"))
        assertTrue(friendly("OSError: [Errno 28] No space left on device").contains("storage"))
        assertTrue(friendly("ERROR: Requested format is not available. Use --list-formats").contains("quality"))
        assertTrue(friendly("ERROR: Video unavailable. This video has been removed").contains("removed"))
        assertTrue(friendly("WARNING: [youtube] [SSL] record layer failure\nERROR: [youtube] abc: The page needs to be reloaded.").contains("connection"))
        assertTrue(friendly("ERROR: [youtube] abc: Sign in to confirm your age. This video may be inappropriate for some users").contains("Age-restricted"))
        assertTrue(friendly("Read timed out").startsWith("Network problem"))
        assertTrue(friendly("ERROR: Postprocessing: ffmpeg exited with code 1").contains("Processing"))
    }

    @Test fun unknownErrorsKeepTheUsefulLine() {
        assertEquals("Something odd happened", ErrorMessages.friendly("WARNING: noise\nERROR: [x] Something odd happened"))
        assertTrue(ErrorMessages.friendly("").startsWith("Download failed"))
        assertTrue(ErrorMessages.friendly(null).startsWith("Download failed"))
        assertTrue(ErrorMessages.friendly("x".repeat(2000)).length <= 300)
    }

    @Test fun transientFailuresAreDetected() {
        assertTrue(ErrorMessages.transient("ERROR: [youtube] lYBUbBu4W08: The page needs to be reloaded."))
        assertTrue(ErrorMessages.transient("HTTP Error 503: Service Unavailable"))
        assertFalse(ErrorMessages.transient("ERROR: Private video"))
        assertFalse(ErrorMessages.transient("HTTP Error 403: Forbidden"))
    }

    @Test fun retryableExcludesPermanentFailures() {
        assertFalse(ErrorMessages.retryable("Private video"))
        assertFalse(ErrorMessages.retryable("ERROR: Unsupported URL"))
        assertFalse(ErrorMessages.retryable("No space left on device"))
        assertTrue(ErrorMessages.retryable("aria2c exited with code 1"))
        assertTrue(ErrorMessages.retryable("HTTP Error 403"))
    }
}

class ProvidersTest {
    @Test fun providerNames() {
        assertEquals("YouTube", Providers.name("https://www.youtube.com/watch?v=aqz-KE-bpKQ"))
        assertEquals("YouTube", Providers.name("https://youtu.be/aqz-KE-bpKQ"))
        assertEquals("YouTube Music", Providers.name("https://music.youtube.com/watch?v=x"))
        assertEquals("X", Providers.name("https://x.com/a/status/1"))
        assertEquals("TikTok", Providers.name("https://www.tiktok.com/@a/video/1"))
        assertEquals("Spotify", Providers.name("https://open.spotify.com/track/abc"))
        assertEquals("Vimeo", Providers.name("https://player.vimeo.com/video/1"))
        assertEquals("Example", "Example".also { assertEquals("example.org", Providers.name("https://example.org/a.mp4")) })
    }

    @Test fun validation() {
        assertEquals("https://example.com/a", Providers.validateUrl(" https://example.com/a "))
        listOf("file:///etc/passwd", "javascript:alert(1)", "ftp://x/y", "https://user:pw@example.com/", "not a url", "").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { Providers.validateUrl(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { Providers.validateUrl("https://example.com/" + "a".repeat(5000)) }
    }

    @Test fun foldersAndNames() {
        // YouTube Music shares the "YouTube" platform folder, like desktop.
        assertEquals("YouTube", Providers.folder("https://music.youtube.com/watch?v=x"))
        assertEquals("example.org", Providers.folder("https://example.org/a"))
        assertEquals("a_b_c", Providers.safeName("a/b\\c"))
        assertEquals("_CON", Providers.safeName("CON"))
        assertEquals("Untitled", Providers.safeName("..."))
        assertTrue(Providers.safeName("x".repeat(300)).length <= 80)
        assertFalse(Providers.safeName("100% real").contains("%"))
    }

    @Test fun musicSources() {
        assertEquals("spotify", Providers.musicSource("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"))
        assertEquals("spotify", Providers.musicSource("https://spotify.link/abc"))
        assertEquals("apple-music", Providers.musicSource("https://music.apple.com/us/album/x/1"))
        assertEquals("deezer", Providers.musicSource("https://www.deezer.com/track/1"))
        assertNull(Providers.musicSource("https://music.youtube.com/watch?v=x"))
    }

    @Test fun collectionDetection() {
        assertTrue(Providers.isCollectionUrl("https://music.youtube.com/playlist?list=RDCLAK5uy_kmPRjHDECIcuVwnKsx2Ng7fyNgFKWNJFs"))
        assertTrue(Providers.isCollectionUrl("https://www.youtube.com/@channel"))
        assertTrue(Providers.isCollectionUrl("https://music.youtube.com/browse/MPREb_abc"))
        assertFalse(Providers.isCollectionUrl("https://www.youtube.com/watch?v=aqz-KE-bpKQ&list=PL123"))
        assertTrue(Providers.isCollectionUrl("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M"))
        assertFalse(Providers.isCollectionUrl("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"))
        assertTrue(Providers.isCollectionUrl("https://music.apple.com/us/album/discovery/697194953"))
        assertFalse(Providers.isCollectionUrl("https://music.apple.com/us/album/discovery/697194953?i=697195462"))
    }

    @Test fun playlistHints() {
        assertEquals("https://www.youtube.com/playlist?list=PLabc", Providers.playlistUrlOf("https://www.youtube.com/watch?v=aqz-KE-bpKQ&list=PLabc"))
        assertEquals("https://www.youtube.com/watch?v=aqz-KE-bpKQ&list=RDaqz-KE-bpKQ", Providers.playlistUrlOf("https://www.youtube.com/watch?v=aqz-KE-bpKQ&list=RDaqz-KE-bpKQ"))
        assertNull(Providers.playlistUrlOf("https://www.youtube.com/watch?v=aqz-KE-bpKQ"))
        assertNull(Providers.playlistUrlOf("https://www.youtube.com/playlist?list=PLabc"))
        assertNull(Providers.playlistUrlOf("https://vimeo.com/1?list=PLabc"))
    }

    @Test fun youTubeMusicDefaultsToAudioOtherYouTubeToVideo() {
        listOf(
            "https://music.youtube.com/watch?v=aqz-KE-bpKQ",
            "https://music.youtube.com/playlist?list=OLAK5uy_x",
            "https://music.youtube.com/browse/MPREb_abc",
            "https://music.youtube.com/watch?v=aqz-KE-bpKQ&list=RDAMVMaqz-KE-bpKQ",
            "https://music.youtube.com/channel/UCabc",
        ).forEach {
            assertTrue(it, Providers.isYouTubeMusic(it))
            assertEquals(it, "audio", Providers.defaultKind(it))
            assertEquals(it, "video", Providers.defaultKind(it, youTubeMusicAudio = false))
        }
        listOf("https://www.youtube.com/watch?v=aqz-KE-bpKQ", "https://youtu.be/aqz-KE-bpKQ", "https://m.youtube.com/watch?v=x", "https://youtube.com/playlist?list=PL1").forEach {
            assertFalse(it, Providers.isYouTubeMusic(it))
            assertEquals(it, "video", Providers.defaultKind(it))
        }
        assertEquals("audio", Providers.defaultKind("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT", youTubeMusicAudio = false))
        assertEquals("audio", Providers.defaultKind("https://music.apple.com/us/album/x/1"))
    }
}
