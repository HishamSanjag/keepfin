package app.keepfin

import org.junit.Assert.*
import org.junit.Test

class JobStoreTest {
    private val v1 = """
        [
         {"id":"a1","url":"https://www.youtube.com/watch?v=aqz-KE-bpKQ","title":"Big Buck Bunny","thumbnail":"https://i.ytimg.com/x.jpg","uploader":"Blender","duration":596.5,
          "choice":{"kind":"video","label":"720p · mp4","args":["-f","bv*[height=720]+ba/b[height=720]","-S","res,vcodec:h264,acodec:aac","--merge-output-format","mp4"]},
          "label":"720p · mp4","extras":{"metadata":true,"subtitles":true,"subtitleLanguages":"en.*,ar.*"},"status":"completed","percent":100,"createdAt":1700000000000,"attempt":2,
          "file":"/data/app/Keepfin/YouTube/Big Buck Bunny.mp4","outputTemplate":"/data/app/Keepfin/YouTube/Big Buck Bunny.%(ext)s","publicUri":"content://media/external/video/media/42",
          "publicPath":"Movies/Keepfin/Big Buck Bunny.mp4","publicFiles":["content://media/external/video/media/42"]},
         {"id":"a2","url":"https://soundcloud.com/a/b","title":"Track","choice":{"kind":"audio","label":"audio only · mp3","args":["-f","ba/b","-x","--audio-format","mp3"]},
          "label":"audio only · mp3","extras":{},"status":"downloading","percent":42,"createdAt":1700000001000,"attempt":1},
         {"id":"a3","url":"https://vimeo.com/1","title":"Failed","choice":{"kind":"video","label":"best available · mp4","args":["-f","bv*+ba/b"]},"label":"best available · mp4",
          "extras":{},"status":"failed","error":"HTTP Error 403","percent":10,"createdAt":1700000002000,"attempt":1,"saveError":"Automatic save failed: x"},
         {"id":"a4","title":"no url"},
         {"id":"a5","url":"https://x.com/a/status/1","title":"Queued","choice":{"kind":"video","label":"best","args":[]},"extras":{},"status":"queued","percent":0,"createdAt":1700000003000,"attempt":1}
        ]
    """.trimIndent()

    @Test fun v1HistoryIsKept() {
        val records = JobCodec.decodeFile(v1)
        assertEquals(listOf("a1", "a2", "a3", "a5"), records.map { it.id })
        val done = records[0]
        assertEquals(JobStatus.COMPLETED, done.status)
        assertEquals(100f, done.job.percent, 0f)
        assertEquals("Big Buck Bunny", done.job.title)
        assertEquals("/data/app/Keepfin/YouTube/Big Buck Bunny.mp4", done.job.file)
        assertEquals("content://media/external/video/media/42", done.job.publicUri)
        assertEquals("Movies/Keepfin/Big Buck Bunny.mp4", done.job.publicPath)
        assertEquals(listOf("content://media/external/video/media/42"), done.publicFiles)
        assertEquals(2, done.job.attempt)
        assertEquals(1700000000000L, done.job.createdAt)
        assertEquals("YouTube", done.job.provider)
        assertEquals(MediaKind.VIDEO, done.job.kind)
        assertEquals(FormatRequest.Video(maxHeight = 720), done.format)
        assertTrue(done.options.subtitles.enabled)
        assertEquals("en.*,ar.*", done.options.subtitles.languages)
        assertTrue(done.options.embedMetadata)
        assertEquals("720p · mp4", done.job.label)
        assertNotNull(done.job.completedAt)
    }

    @Test fun v1InterruptedJobsBecomeRetryableFailures() {
        val records = JobCodec.decodeFile(v1)
        val interrupted = records[1]
        assertEquals(JobStatus.FAILED, interrupted.status)
        assertEquals(MediaKind.AUDIO, interrupted.job.kind)
        assertEquals(FormatRequest.Audio("mp3", "best"), interrupted.format)
        assertTrue(interrupted.job.error!!.contains("retry"))
        assertEquals(JobStatus.FAILED, records[3].status)
        assertEquals("HTTP Error 403", records[2].job.error)
        assertEquals("Automatic save failed: x", records[2].job.saveError)
    }

    @Test fun v2RoundTripKeepsEverything() {
        val original = JobCodec.decodeFile(v1).map { it.copy(source = SourceTags("T", "A", "B", 3, 2001), outputTemplate = it.outputTemplate ?: "/x/%(ext)s") }
        val text = JobCodec.encodeFile(original)
        val back = JobCodec.decodeFile(text)
        assertEquals(original.size, back.size)
        back.zip(original).forEach { (b, o) ->
            assertEquals(o.job.id, b.job.id)
            assertEquals(o.job.title, b.job.title)
            assertEquals(o.job.file, b.job.file)
            assertEquals(o.job.publicUri, b.job.publicUri)
            assertEquals(o.job.status, b.job.status)
            assertEquals(o.format, b.format)
            assertEquals(o.options, b.options)
            assertEquals(o.source, b.source)
            assertEquals(o.outputTemplate, b.outputTemplate)
            assertEquals(o.publicFiles, b.publicFiles)
        }
    }

    @Test fun aMusicVideoJobRemembersWhichSongItBelongsToAcrossARestart() {
        val plain = JobCodec.decodeFile(v1).first()
        assertNull(plain.attachTo)
        val video = plain.copy(attachTo = "00000000-0000-4000-8000-000000000001")
        val back = JobCodec.decodeFile(JobCodec.encodeFile(listOf(video, plain)))
        assertEquals("00000000-0000-4000-8000-000000000001", back[0].attachTo)
        assertNull(back[1].attachTo)
    }

    @Test fun aMusicVideoQueuedWithItsSongRemembersTheSongsJobAcrossARestart() {
        val plain = JobCodec.decodeFile(v1).first()
        assertNull(plain.attachToJob)
        val video = plain.copy(attachToJob = "song-job-1", job = plain.job.copy(status = JobStatus.COMPLETED, phase = MusicVideoLink.WAITING, publicUri = null, publicPath = null, saveError = null))
        val back = JobCodec.decodeFile(JobCodec.encodeFile(listOf(video, plain)))
        assertEquals("song-job-1", back[0].attachToJob)
        assertEquals(MusicVideoLink.WAITING, back[0].job.phase)
        assertTrue(MusicVideoLink.waiting(back[0]))
        assertNull(back[1].attachToJob)
    }

    @Test fun theChosenPlaylistSurvivesARestart() {
        val plain = JobCodec.decodeFile(v1).first()
        assertNull(plain.joinPlaylist)
        val back = JobCodec.decodeFile(JobCodec.encodeFile(listOf(plain.copy(joinPlaylist = "playlist-7"), plain)))
        assertEquals("playlist-7", back[0].joinPlaylist)
        assertNull(back[1].joinPlaylist)
    }

    @Test fun v2ActiveJobsResumeAsPaused() {
        val active = JobCodec.decodeFile(v1)[3].copy(job = JobCodec.decodeFile(v1)[3].job.copy(status = JobStatus.DOWNLOADING))
        val back = JobCodec.decodeFile(JobCodec.encodeFile(listOf(active))).single()
        assertEquals(JobStatus.PAUSED, back.status)
        assertEquals("Interrupted · tap resume", back.job.phase)
        assertNull(back.job.speed)
    }

    @Test fun musicMatchSurvivesRoundTrip() {
        val base = JobCodec.decodeFile(v1)[0]
        val match = MusicMatch("spotify", "https://open.spotify.com/track/x", "https://music.youtube.com/watch?v=y", "Title", 0.93)
        val record = base.copy(job = base.job.copy(match = match), format = FormatRequest.Audio("m4a", "best", "140"),
            options = DownloadOptions(sponsorBlock = "mark", sectionStart = 5.0, sectionEnd = 9.5, embedThumbnail = false))
        val back = JobCodec.decodeFile(JobCodec.encodeFile(listOf(record))).single()
        assertEquals(match, back.job.match)
        assertEquals(record.format, back.format)
        assertEquals(record.options, back.options)
    }

    @Test fun theRicherMatchSurvivesTheQueueFileAndOldFilesStillLoad() {
        val base = JobCodec.decodeFile(v1)[0]
        val match = MusicMatch(
            "spotify", "https://open.spotify.com/track/x", "https://music.youtube.com/watch?v=y", "Title", 0.93,
            method = "album", album = "Random Access Memories", position = 2, durationSec = 323.0,
            alternates = listOf(MusicAlternate("https://music.youtube.com/watch?v=z", "Other", 322.0, 0.81), MusicAlternate("https://music.youtube.com/watch?v=w", "Third", null, 0.7)),
        )
        val source = SourceTags("T", "A", "Alb", 2, 2013, 13)
        val back = JobCodec.decodeFile(JobCodec.encodeFile(listOf(base.copy(job = base.job.copy(match = match), source = source)))).single()
        assertEquals(match, back.job.match)
        assertEquals(source, back.source)
        // A match saved before these fields existed has none of them.
        val old = JobCodec.decodeFile(JobCodec.encodeFile(listOf(base.copy(job = base.job.copy(match = MusicMatch("spotify", "u", "m", "t", 0.9)))))).single().job.match!!
        assertEquals(null, old.method)
        assertEquals(null, old.album)
        assertEquals(emptyList<MusicAlternate>(), old.alternates)
    }

    @Test fun corruptRecordsAreSkippedNotFatal() {
        val text = """{"version":2,"jobs":[{"id":"ok","url":"https://a.b/c","status":"completed"},{"status":"completed"},"junk"]}"""
        assertEquals(listOf("ok"), JobCodec.decodeFile(text).map { it.id })
        assertTrue(JobCodec.decodeFile("").isEmpty())
    }

    @Test fun groupAndRetryStateSurviveRoundTrip() {
        val base = JobCodec.decodeFile(v1)[0]
        val group = JobGroup("g-1", "Blender Open Movies", "https://www.youtube.com/playlist?list=PLx", "Blender Studio", "https://i.ytimg.com/vi/a/hq.jpg", "album")
        val record = base.copy(job = base.job.copy(group = group, retryCount = 2, retryAt = 1234L, status = JobStatus.FAILED))
        val back = JobCodec.decodeFile(JobCodec.encodeFile(listOf(record))).single()
        assertEquals(group, back.job.group)
        assertEquals(2, back.job.retryCount)
        assertNull("A countdown never survives a restart", back.job.retryAt)
    }

    @Test fun waitingRetryComesBackPausedWithoutCountdown() {
        val base = JobCodec.decodeFile(v1)[3]
        val waiting = base.copy(job = base.job.copy(status = JobStatus.QUEUED, retryCount = 1, retryAt = System.currentTimeMillis() + 5000))
        val back = JobCodec.decodeFile(JobCodec.encodeFile(listOf(waiting))).single()
        assertEquals(JobStatus.PAUSED, back.status)
        assertNull(back.job.retryAt)
    }

    @Test fun recordsWithoutGroupFieldsStillLoad() {
        val legacy = """{"version":2,"jobs":[{"id":"old","url":"https://a.b/c","status":"completed","title":"Old","attempt":1}]}"""
        val job = JobCodec.decodeFile(legacy).single().job
        assertNull(job.group)
        assertEquals(0, job.retryCount)
        assertNull(job.retryAt)
        // A malformed group object is ignored instead of dropping the job.
        val broken = """{"version":2,"jobs":[{"id":"x","url":"https://a.b/c","status":"completed","group":{"title":"no id"}}]}"""
        assertNull(JobCodec.decodeFile(broken).single().job.group)
    }

    @Test fun v1RecordsHaveNoGroup() {
        assertTrue(JobCodec.decodeFile(v1).all { it.job.group == null })
    }
}
