package app.keepfin

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FormatsTest {
    private val info = Fixtures.json("ytdlp/youtube-video.json")
    private val media = Formats.mediaInfo("https://www.youtube.com/watch?v=aqz-KE-bpKQ", info)
    private val m4a get() = media.audio.first { it.ext == "m4a" && !it.note.orEmpty().contains("DRC") }

    private val reel = JSONObject("""{"id":"R1","title":"Reel","extractor":"instagram","extractor_key":"Instagram","duration":20,"webpage_url":"https://www.instagram.com/reel/R1/",
        "formats":[{"format_id":"dash-720","url":"https://x/a.mp4","ext":"mp4","vcodec":"avc1.64001f","acodec":"mp4a.40.2","height":720,"width":406,"tbr":900,"filesize":2250000,"protocol":"https"},
                   {"format_id":"dash-540","url":"https://x/b.mp4","ext":"mp4","vcodec":"avc1.64001f","acodec":"mp4a.40.2","height":540,"width":304,"tbr":600,"filesize":1500000,"protocol":"https"}]}""")

    @Test fun aSingleLinkOffersConvertedSizesBelowTheServedOnesButACollectionEntryDoesNot() {
        val single = Formats.mediaInfo("https://www.instagram.com/reel/R1/", reel, single = true)
        val converted = single.video.filter { it.convertedFrom != null }
        assertTrue(converted.isNotEmpty())
        assertTrue(converted.all { it.sizeApprox && it.height < 540 && it.formatId in setOf("dash-720", "dash-540") })
        assertTrue(Formats.mediaInfo("https://www.instagram.com/reel/R1/", reel).video.none { it.convertedFrom != null })
    }

    @Test fun dedupesPerHeightFpsHdr() {
        val ids = media.video.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertTrue(ids.toString(), ids.contains("1080p60"))
        assertTrue("storyboards are never offered", media.video.none { it.height in 27..90 })
        assertEquals(media.video.sortedByDescending { it.height }.map { it.height }, media.video.map { it.height })
        // One option per height+fps+hdr group.
        assertEquals(media.video.size, media.video.map { Triple(it.height, it.fps, it.hdr) }.distinct().size)
    }

    @Test fun sizesIncludeAudio() {
        val option = media.video.first { it.height == 720 }
        assertEquals("h264 over HLS/VP9/AV1, direct https", "298", option.formatId)
        assertEquals(150_524_867L + m4a.size!!, option.size)
        assertFalse(option.hasAudio)
        assertEquals("h264", option.codec)
        assertTrue(option.size!! > 150_000_000L)
    }

    @Test fun prefersDirectStreamsOverHls() {
        val option = media.video.first { it.height == 1080 }
        assertEquals("299", option.formatId)
        assertEquals(257_619_653L + m4a.size!!, option.size)
    }

    @Test fun audioOptionsListedWithCodecs() {
        assertTrue(media.audio.any { it.codec == "opus" })
        assertTrue(media.audio.any { it.codec == "aac" })
        assertTrue(media.audio.all { it.size != null })
        assertFalse("DRC variants must not lead", media.audio.first().note.orEmpty().contains("DRC"))
    }

    @Test fun sizeFallbackOrder() {
        assertEquals(Formats.Size(1000, false), Formats.size(JSONObject("""{"filesize":1000,"filesize_approx":2000,"tbr":800}"""), 10.0))
        assertEquals(Formats.Size(2000, true), Formats.size(JSONObject("""{"filesize_approx":2000,"tbr":800}"""), 10.0))
        assertEquals(Formats.Size(1_000_000, true), Formats.size(JSONObject("""{"tbr":800}"""), 10.0))
        assertNull(Formats.size(JSONObject("""{"ext":"mp4"}"""), 10.0).bytes)
    }

    @Test fun metadata() {
        assertEquals("YouTube", media.provider)
        assertNotNull(media.duration)
        assertFalse(media.isLive)
        assertNull(media.playlistUrl)
        assertEquals(info.optJSONArray("chapters")?.length() ?: 0, media.chapters)
    }

    @Test fun codecMapping() {
        assertEquals("h264", Formats.videoCodec("avc1.64002A"))
        assertEquals("vp9", Formats.videoCodec("vp09.00.40.08"))
        assertEquals("vp9", Formats.videoCodec("vp9"))
        assertEquals("av1", Formats.videoCodec("av01.0.08M.08"))
        assertEquals("hevc", Formats.videoCodec("hev1.1.6.L120"))
        assertEquals("aac", Formats.audioCodec("mp4a.40.2"))
        assertEquals("opus", Formats.audioCodec("opus"))
    }

    @Test fun musicTrackFixture() {
        val music = Formats.mediaInfo("https://music.youtube.com/watch?v=dQw4w9WgXcQ", Fixtures.json("ytdlp/youtube-music-track.json"))
        assertEquals("YouTube Music", music.provider)
        assertEquals(MusicMetadata("Daft Punk", "Discovery", "One More Time", 2001), music.music)
        assertTrue(music.audio.isNotEmpty())
    }

    @Test fun playlistUrlForWatchWithList() {
        assertEquals("https://www.youtube.com/playlist?list=PL1234567890",
            Formats.mediaInfo("https://www.youtube.com/watch?v=aqz-KE-bpKQ&list=PL1234567890", info).playlistUrl)
        assertEquals("https://www.youtube.com/watch?v=aqz-KE-bpKQ&list=RDaqz-KE-bpKQ",
            Formats.mediaInfo("https://www.youtube.com/watch?v=aqz-KE-bpKQ&list=RDaqz-KE-bpKQ", info).playlistUrl)
    }

    @Test fun collectionDetection() {
        assertTrue(Formats.isCollection(JSONObject("""{"_type":"playlist","entries":[]}""")))
        assertFalse(Formats.isCollection(info))
    }

    @Test fun directFileFallsBackToOriginalOption() {
        val direct = JSONObject("""{"title":"clip","ext":"mp4","url":"http://x/clip.mp4","protocol":"http","filesize":5000}""")
        val m = Formats.mediaInfo("http://x/clip.mp4", direct)
        assertEquals("original", m.video.single().id)
        assertEquals(5000L, m.video.single().size)
    }

    @Test fun labels() {
        // The wording is the desktop's (ARGS-13); the core's own cases are in fixtures/engine/args/labels.json.
        assertEquals("Best quality · MP4", Labels.label(FormatRequest.Video(), null))
        assertEquals("1080p · MP4", Labels.label(FormatRequest.Video(maxHeight = 1080), null))
        assertEquals("MP3 · 320 kbps", Labels.label(FormatRequest.Audio("mp3", "320"), null))
        assertEquals("FLAC", Labels.label(FormatRequest.Audio("flac"), null))
        val option = media.video.first { it.height == 720 }
        assertTrue(Labels.label(FormatRequest.Video(formatId = option.formatId), media).startsWith("720p60"))
        assertEquals(option.size, Labels.expectedSize(FormatRequest.Video(formatId = option.formatId), media))
    }

    @Test fun youtubeMusicTracksWithoutArtworkGetTheVideoThumbnail() {
        val flat = JSONObject().put("ie_key", "Youtube").put("id", "nUsrYVxrDwI").put("url", "https://music.youtube.com/watch?v=nUsrYVxrDwI")
        assertEquals("https://i.ytimg.com/vi/nUsrYVxrDwI/mqdefault.jpg", Formats.thumbnail(flat))
        assertEquals("https://i.ytimg.com/vi/nUsrYVxrDwI/mqdefault.jpg", Formats.thumbnail(JSONObject().put("url", "https://youtu.be/nUsrYVxrDwI")))
        val provided = JSONObject(flat.toString()).put("thumbnails", org.json.JSONArray().put(JSONObject().put("url", "https://i.ytimg.com/vi/nUsrYVxrDwI/large.jpg")))
        assertEquals("https://i.ytimg.com/vi/nUsrYVxrDwI/large.jpg", Formats.thumbnail(provided))
        assertNull(Formats.thumbnail(JSONObject().put("id", "abcdefghijk").put("url", "https://soundcloud.com/a/b")))
    }
}
