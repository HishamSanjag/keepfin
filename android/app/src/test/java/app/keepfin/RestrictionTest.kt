package app.keepfin

import org.junit.Assert.*
import org.junit.Test

class RestrictionTest {
    private val unplayable = """<html>...{"responseContext":{}},"playabilityStatus":{"status":"UNPLAYABLE","reason":"Video unavailable","errorScreen":{}},"videoDetails":{"videoId":"jdOSfoV1njc","title":"First Flame","lengthSeconds":"290","keywords":["a"],"channelId":"UCx","author":"Glum Aleks - Topic","viewCount":"1"}..."""
    private val ok = """"playabilityStatus":{"status":"OK","playableInEmbed":true},"videoDetails":{"videoId":"j3SiMcrObkU","title":"First Flame","lengthSeconds":"289","author":"Glum Aleks"}"""

    @Test fun parsesAnUnplayableWatchPage() {
        val probe = Restriction.parse(unplayable)!!
        assertEquals("UNPLAYABLE", probe.status)
        assertEquals("Video unavailable", probe.reason)
        assertEquals("jdOSfoV1njc", probe.videoId)
        assertEquals("First Flame", probe.title)
        assertEquals("Glum Aleks - Topic", probe.author)
        assertEquals(290.0, probe.lengthSeconds!!, 0.0)
        assertEquals(Restriction.Verdict.RESTRICTED, Restriction.verdict(probe))
    }

    @Test fun anOkPageIsPlayableAndAnUnreadablePageIsUnknown() {
        assertEquals(Restriction.Verdict.PLAYABLE, Restriction.verdict(Restriction.parse(ok)))
        assertEquals(Restriction.Verdict.UNKNOWN, Restriction.verdict(Restriction.parse("<html>consent</html>")))
        assertEquals(Restriction.Verdict.UNKNOWN, Restriction.verdict(null))
        // Unplayable without video details (for example a removed video) is not proven to be a regional restriction.
        assertEquals(Restriction.Verdict.UNKNOWN, Restriction.verdict(Restriction.parse(""""playabilityStatus":{"status":"UNPLAYABLE","reason":"Video unavailable"}""")))
        assertEquals(Restriction.Verdict.UNKNOWN, Restriction.verdict(Restriction.parse(""""playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"Sign in"}""")))
    }

    @Test fun unescapesTitlesAndAuthors() {
        val page = """"playabilityStatus":{"status":"UNPLAYABLE","reason":"Video unavailable"},"videoDetails":{"videoId":"abcdefghijk","title":"Café \"Noir\" & more","lengthSeconds":"61","author":"Sigur Rós - Topic""""
        val probe = Restriction.parse(page)!!
        assertEquals("Café \"Noir\" & more", probe.title)
        assertEquals("Sigur Rós - Topic", probe.author)
        assertEquals("Sigur Rós", Restriction.artistOf(probe.author))
    }

    @Test fun detectsTheUnavailableFailureButNotOtherMessages() {
        assertTrue(Restriction.isUnavailable("ERROR: [youtube] jdOSfoV1njc: Video unavailable"))
        assertTrue(Restriction.isUnavailable("ERROR: [youtube] x: This video is not available"))
        assertFalse(Restriction.isUnavailable("ERROR: [youtube] x: Video unavailable. This video has been removed by the uploader"))
        assertFalse(Restriction.isUnavailable("ERROR: [youtube] x: Video unavailable. The uploader has not made this video available in your country"))
        assertFalse(Restriction.isUnavailable("ERROR: [youtube] x: Video unavailable. This content isn't available, try again later."))
        assertFalse(Restriction.isUnavailable("ERROR: [youtube] x: Private video. Sign in if you've been granted access"))
        assertFalse(Restriction.isUnavailable("HTTP Error 403: Forbidden"))
        assertFalse(Restriction.isUnavailable(null))
    }

    @Test fun readsVideoIdsFromYouTubeLinks() {
        assertEquals("jdOSfoV1njc", Restriction.videoId("https://music.youtube.com/watch?v=jdOSfoV1njc&si=x"))
        assertEquals("jdOSfoV1njc", Restriction.videoId("https://youtu.be/jdOSfoV1njc?t=3"))
        assertEquals("jdOSfoV1njc", Restriction.videoId("https://www.youtube.com/shorts/jdOSfoV1njc"))
        assertNull(Restriction.videoId("https://vimeo.com/123456789"))
        assertNull(Restriction.videoId("https://www.youtube.com/playlist?list=PL123"))
    }

    @Test fun musicLikeFromHostTopicChannelOrCollection() {
        assertTrue(Restriction.isMusicLike("https://music.youtube.com/watch?v=jdOSfoV1njc", null))
        assertTrue(Restriction.isMusicLike("https://www.youtube.com/watch?v=jdOSfoV1njc", "Glum Aleks - Topic"))
        assertTrue(Restriction.isMusicLike("https://www.youtube.com/watch?v=jdOSfoV1njc", "Some Artist", "https://music.youtube.com/playlist?list=PL1"))
        assertFalse(Restriction.isMusicLike("https://www.youtube.com/watch?v=jdOSfoV1njc", "Some Vlogger", "https://www.youtube.com/playlist?list=PL1"))
        assertFalse(Restriction.isMusicLike("https://www.youtube.com/watch?v=jdOSfoV1njc", null))
    }

    @Test fun sourceTrackPrefersTheJobsOwnMetadata() {
        val probe = Restriction.parse(unplayable)!!
        val fromPage = Restriction.sourceTrack(probe, "https://music.youtube.com/watch?v=jdOSfoV1njc", null, null)
        assertEquals("First Flame", fromPage.title)
        assertEquals(listOf("Glum Aleks"), fromPage.artists)
        assertEquals(290.0, fromPage.duration!!, 0.0)
        assertEquals("https://i.ytimg.com/vi/jdOSfoV1njc/hqdefault.jpg", fromPage.cover)
        val own = Restriction.sourceTrack(probe, "u", SourceTags("First Flame (Remastered)", "Glum", "Album", 3, 2020), null)
        assertEquals("First Flame (Remastered)", own.title)
        assertEquals(listOf("Glum"), own.artists)
        assertEquals("Album", own.album)
    }

    @Test fun restrictedFailuresArePermanentWithClearMessages() {
        val music = Restriction.failure(true, "First Flame", listOf("First Flame (https://x, 91%): unavailable too"))
        val other = Restriction.failure(false, "A vlog", emptyList())
        assertEquals(RetryPolicy.Kind.PERMANENT, ErrorMessages.classify(music))
        assertEquals(RetryPolicy.Kind.PERMANENT, ErrorMessages.classify(other))
        assertNull(RetryPolicy.next(0, music))
        assertNull(RetryPolicy.next(0, other))
        assertNull(RetryPolicy.next(0, music, crashed = true))
        assertEquals("YouTube doesn't play this song in your region without an account, and Keepfin couldn't find another copy. Sign in to YouTube in Settings, then retry.", ErrorMessages.friendly(music))
        assertEquals("YouTube doesn't make this video available in your region.", ErrorMessages.friendly(other))
        assertTrue(ErrorMessages.detail(music).contains("unavailable too"))
        assertFalse(ErrorMessages.friendly(music).contains("yt-dlp", ignoreCase = true))
    }

    @Test fun plainUnavailableStaysRetryable() {
        val raw = "ERROR: [youtube] jdOSfoV1njc: Video unavailable"
        assertEquals(RetryPolicy.Kind.RETRYABLE, ErrorMessages.classify(raw))
        assertNotNull(RetryPolicy.next(0, raw))
    }

    @Test fun captionsForTheMatchSource() {
        assertEquals("Saved from another upload of this song", Restriction.matchCaption(MusicMatch("youtube", "u", "m", "t", 0.9)))
        assertEquals("Matched from YouTube Music · 92%", Restriction.matchCaption(MusicMatch("spotify", "u", "m", "t", 0.92)))
    }

    @Test fun downloadUrlFollowsAnAlternateUpload() {
        val format = FormatRequest.Audio("mp3", "320")
        fun record(match: MusicMatch?): JobRecord {
            val job = Job(id = "1", url = "https://music.youtube.com/watch?v=jdOSfoV1njc", title = "t", thumbnail = null, uploader = null, duration = null, provider = null,
                label = "", kind = MediaKind.AUDIO, status = JobStatus.QUEUED, percent = 0f, speed = null, eta = null, downloadedBytes = null, totalBytes = null, expectedSize = null,
                fileSize = null, phase = null, error = null, file = null, publicUri = null, publicPath = null, saveError = null, createdAt = 0, completedAt = null, attempt = 1, match = match)
            return JobRecord(job, format, DownloadOptions())
        }
        assertEquals("https://music.youtube.com/watch?v=jdOSfoV1njc", record(null).downloadUrl)
        assertEquals("https://music.youtube.com/watch?v=jdOSfoV1njc", record(MusicMatch("spotify", "s", "https://x", "t", 1.0)).downloadUrl)
        assertEquals("https://www.youtube.com/watch?v=j3SiMcrObkU", record(MusicMatch("youtube", "s", "https://www.youtube.com/watch?v=j3SiMcrObkU", "t", 1.0)).downloadUrl)
    }
}
