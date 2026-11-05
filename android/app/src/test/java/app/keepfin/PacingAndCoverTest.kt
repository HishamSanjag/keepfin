package app.keepfin

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PacingAndCoverTest {
    private fun yt(id: String, group: String? = "g") = Pacing.Item(id, true, group)
    private fun other(id: String, group: String? = null) = Pacing.Item(id, false, group)

    @Test fun youtubeIsDetectedFromHostOrMusicMatch() {
        assertTrue(Pacing.isYouTube("https://music.youtube.com/watch?v=x", false))
        assertTrue(Pacing.isYouTube("https://youtu.be/x", false))
        assertTrue(Pacing.isYouTube("https://open.spotify.com/track/abc", true))
        assertFalse(Pacing.isYouTube("https://vimeo.com/1", false))
        assertFalse(Pacing.isYouTube("https://example.com/youtube.com", false))
    }

    @Test fun youtubeJobsUseTheFullLimitUntilARateLimitIsSeen() {
        val running = listOf(yt("1"), yt("2"), yt("3"))
        assertTrue("no cap yet: a third and fourth YouTube job may start", Pacing.canStart(yt("4"), running, limit = 4, holds = emptyMap(), now = 0))
        assertFalse("the overall limit still applies", Pacing.canStart(yt("4"), running + yt("5"), limit = 4, holds = emptyMap(), now = 0))
    }

    @Test fun afterARateLimitYouTubeIsCappedAtTwoForThirtyMinutesThenReturnsToFull() {
        val seen = 1_000L
        val until = Pacing.capEnd(seen)
        assertEquals(seen + 30 * 60_000L, until)
        val running = listOf(yt("1"), yt("2"))
        assertFalse(Pacing.canStart(yt("3"), running, limit = 4, holds = emptyMap(), now = seen + 1, cappedUntil = until))
        assertFalse("still capped just before the end", Pacing.canStart(yt("3"), running, limit = 4, holds = emptyMap(), now = until - 1, cappedUntil = until))
        assertTrue("another site still uses a free slot", Pacing.canStart(other("4"), running, limit = 4, holds = emptyMap(), now = seen + 1, cappedUntil = until))
        assertTrue("one YouTube job plus another site is under the cap", Pacing.canStart(yt("3"), listOf(yt("1"), other("5")), limit = 4, holds = emptyMap(), now = seen + 1, cappedUntil = until))
        assertTrue("back to full after 30 minutes", Pacing.canStart(yt("3"), running, limit = 4, holds = emptyMap(), now = until, cappedUntil = until))
        assertFalse("the overall limit still applies", Pacing.canStart(other("4"), running, limit = 2, holds = emptyMap(), now = 0))
    }

    @Test fun aNewRateLimitExtendsTheCapAndNeverShortensIt() {
        val first = Pacing.capEnd(0)
        assertEquals(first + 60_000L, Pacing.capEnd(60_000L, first))
        assertEquals("an older signal cannot shorten a cap in force", first + 60_000L, Pacing.capEnd(10_000L, first + 60_000L))
    }

    @Test fun theOldConcurrencyDefaultMigratesOnceAndLaterChoicesStick() {
        assertEquals("old default without the marker becomes 3", 3, Pacing.migratedConcurrency(2, markerPresent = false))
        assertEquals("a stored 2 with the marker is the user's choice", 2, Pacing.migratedConcurrency(2, markerPresent = true))
        assertEquals(1, Pacing.migratedConcurrency(1, markerPresent = false))
        assertEquals(4, Pacing.migratedConcurrency(4, markerPresent = false))
        assertEquals(3, EngineSettings().concurrency)
    }

    @Test fun aThrottledJobNeverHoldsBackOtherStarts() {
        val holds = mapOf("g" to 10_000L)
        assertTrue("a retrying job never holds back its collection", Pacing.canStart(yt("3", "g"), emptyList(), 4, holds, now = 9_999))
        assertTrue("other collections are unaffected", Pacing.canStart(yt("3", "h"), emptyList(), 4, holds, now = 9_999))
        assertTrue("ungrouped jobs are unaffected", Pacing.canStart(yt("3", null), emptyList(), 4, holds, now = 9_999))
        assertTrue("still free later", Pacing.canStart(yt("3", "g"), emptyList(), 4, holds, now = 10_000))
    }

    @Test fun requestSpacingOnlyForYouTube() {
        assertEquals(listOf(Arg("--sleep-requests", "0.75")), Pacing.requestArgs(true))
        assertTrue(Pacing.requestArgs(false).isEmpty())
    }

    @Test fun squareCoverCropAppliesToAnyAudioWhenRequested() {
        val crop = { audio: Boolean, square: Boolean ->
            ArgsPolicy.download(if (audio) FormatRequest.Audio("mp3", "320") else FormatRequest.Video(), DownloadOptions(embedThumbnail = true), square)
                .any { it.name == "--ppa" && it.value!!.contains("crop=") }
        }
        assertTrue(crop(true, true))
        assertFalse(crop(true, false))
        assertFalse("video covers stay 16:9", crop(false, true))
    }

    @Test fun coverUrlsMustBeHttpsAndNotVideoFrames() {
        assertTrue(CoverArt.allowedUrl("https://i.scdn.co/image/ab67616d0000b273"))
        assertFalse(CoverArt.allowedUrl("http://i.scdn.co/image/x"))
        assertFalse(CoverArt.allowedUrl("file:///sdcard/x.jpg"))
        assertFalse(CoverArt.allowedUrl("content://media/x"))
        assertFalse(CoverArt.allowedUrl("https://user:pw@host.example/x.jpg"))
        assertFalse(CoverArt.allowedUrl(null))
        assertTrue(CoverArt.isSourceArtwork("https://e-cdns-images.dzcdn.net/images/cover/x/1000x1000.jpg"))
        assertFalse(CoverArt.isSourceArtwork("https://i.ytimg.com/vi/abc/maxresdefault.jpg"))
    }

    @Test fun coverBytesAreSniffedNotTrusted() {
        assertEquals("jpeg", CoverArt.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())))
        assertEquals("png", CoverArt.sniff(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)))
        assertEquals("webp", CoverArt.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertNull(CoverArt.sniff("<html>nope</html>".toByteArray()))
        assertNull(CoverArt.sniff(ByteArray(0)))
    }

    @Test fun patchedInfoPrefersTheSourceCoverAndKeepsFallbacks() {
        val original = """{"id":"x","thumbnails":[{"url":"https://i.ytimg.com/a.jpg","id":"0"}],"thumbnail":"https://i.ytimg.com/a.jpg"}"""
        val patched = JSONObject(CoverArt.patchedInfo(original, "file:///cache/c.img"))
        val thumbnails = patched.getJSONArray("thumbnails")
        assertEquals(2, thumbnails.length())
        assertEquals("https://i.ytimg.com/a.jpg", thumbnails.getJSONObject(0).getString("url"))
        assertEquals("file:///cache/c.img", thumbnails.getJSONObject(1).getString("url"))
        assertEquals(1000, thumbnails.getJSONObject(1).getInt("preference"))
        assertEquals("file:///cache/c.img", patched.getString("thumbnail"))
        assertNotNull(JSONObject(CoverArt.patchedInfo("""{"id":"x"}""", "file:///c.img")).optJSONArray("thumbnails"))
    }
}
