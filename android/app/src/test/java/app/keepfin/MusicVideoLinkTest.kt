package app.keepfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a music video queued with its song goes, whichever finishes first and whatever becomes of the song (dev-docs/plans/MUSIC-VIDEOS.md). */
class MusicVideoLinkTest {
    private fun record(id: String, status: JobStatus, attachToJob: String? = null, attachTo: String? = null, phase: String? = null): JobRecord {
        val job = Job(
            id = id, url = "https://music.youtube.com/watch?v=aaaaaaaaaaa", title = id, thumbnail = null, uploader = null, duration = null, provider = null, label = "", kind = MediaKind.VIDEO,
            status = status, percent = 0f, speed = null, eta = null, downloadedBytes = null, totalBytes = null, expectedSize = null, fileSize = null, phase = phase, error = null,
            file = null, publicUri = null, publicPath = null, saveError = null, createdAt = 1L, completedAt = null, attempt = 1,
        )
        return JobRecord(job, FormatRequest.Video(maxHeight = 1080, container = "mp4"), DownloadOptions(), attachTo = attachTo, attachToJob = attachToJob)
    }

    private val items = mapOf("song" to "item-1")
    private fun fate(video: JobRecord, song: JobRecord?) = MusicVideoLink.fate(video, song) { items[it] }

    @Test fun theVideoThatFinishesFirstWaitsForASongThatIsStillComing() {
        val video = record("video", JobStatus.PROCESSING, attachToJob = "song")
        for (status in listOf(JobStatus.QUEUED, JobStatus.PREPARING, JobStatus.DOWNLOADING, JobStatus.PROCESSING, JobStatus.PAUSED)) {
            assertEquals("song $status", MusicVideoLink.Fate.Wait, fate(video, record("song", status)))
        }
    }

    @Test fun theSongThatFinishesFirstGivesItsItemToTheVideo() {
        val video = record("video", JobStatus.PROCESSING, attachToJob = "song")
        assertEquals(MusicVideoLink.Fate.Attach("item-1"), fate(video, record("song", JobStatus.COMPLETED)))
        // the song's history entry may be gone by then: the item it became is remembered by its job
        assertEquals(MusicVideoLink.Fate.Attach("item-1"), fate(video, null))
    }

    @Test fun aSongThatFailedOrWasCancelledLeavesTheVideoAsAnOrdinaryDownload() {
        val video = record("video", JobStatus.PROCESSING, attachToJob = "song")
        assertEquals(MusicVideoLink.Fate.Ordinary, fate(video, record("song", JobStatus.FAILED)))
        assertEquals(MusicVideoLink.Fate.Ordinary, fate(video, record("song", JobStatus.CANCELLED)))
        // a song that never reached the library has no item, so the video is not lost
        assertEquals(MusicVideoLink.Fate.Ordinary, MusicVideoLink.fate(record("video", JobStatus.PROCESSING, attachToJob = "other"), null) { null })
    }

    @Test fun aVideoForASongOfTheLibraryNeverWaitsAndAnOrdinaryJobIsNotAVideo() {
        assertEquals(MusicVideoLink.Fate.Attach("item-9"), fate(record("video", JobStatus.PROCESSING, attachTo = "item-9"), null))
        assertEquals(MusicVideoLink.Fate.Ordinary, fate(record("plain", JobStatus.PROCESSING), record("song", JobStatus.DOWNLOADING)))
    }

    @Test fun onlyAFinishedUnsavedVideoMarkedWaitingIsWaiting() {
        assertTrue(MusicVideoLink.waiting(record("v", JobStatus.COMPLETED, attachToJob = "song", phase = MusicVideoLink.WAITING)))
        assertFalse(MusicVideoLink.waiting(record("v", JobStatus.PROCESSING, attachToJob = "song", phase = MusicVideoLink.WAITING)))
        assertFalse(MusicVideoLink.waiting(record("v", JobStatus.COMPLETED, attachToJob = "song")))
        assertFalse(MusicVideoLink.waiting(record("v", JobStatus.COMPLETED, phase = MusicVideoLink.WAITING)))
    }
}
