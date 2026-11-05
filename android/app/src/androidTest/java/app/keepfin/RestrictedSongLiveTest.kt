package app.keepfin

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * LIVE: a YouTube Music "Topic" song that YouTube marks unplayable for anonymous requests in some regions
 * (jdOSfoV1njc, "First Flame" by Glum Aleks). Only meaningful from an affected region; elsewhere the original simply downloads
 * and the assertions about the alternate are skipped. Run with
 * gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.keepfin.RestrictedSongLiveTest
 */
@RunWith(AndroidJUnit4::class)
class RestrictedSongLiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine get() = (context.applicationContext as KeepfinApplication).engineApi as MediaEngine
    private val url = "https://music.youtube.com/watch?v=jdOSfoV1njc"
    private fun log(message: String) { Log.i("KeepfinLive", message) }

    private fun awaitFinished(before: Set<String>): Job {
        val id = engine.jobs.value.first { it.id !in before }.id
        val deadline = System.currentTimeMillis() + 240_000
        var job = engine.jobs.value.first { it.id == id }
        while (System.currentTimeMillis() < deadline) {
            job = engine.jobs.value.first { it.id == id }
            if (job.status == JobStatus.COMPLETED || job.status == JobStatus.FAILED) break
            Thread.sleep(500)
        }
        log("restricted-song status=${job.status} url=${job.url} match=${job.match} error=${job.error} detail=${job.errorDetail?.take(400)}")
        return job
    }

    private fun assertSavedFromAlternate(job: Job) {
        assertEquals(job.error, JobStatus.COMPLETED, job.status)
        assertEquals("the job keeps the song the user asked for", url, job.url)
        val match = job.match!!
        assertEquals(Restriction.SOURCE, match.source)
        assertEquals(url, match.sourceUrl)
        assertNotNull(match.matchedUrl)
        assertNotEquals(url, match.matchedUrl)
        assertTrue((match.confidence ?: 0.0) >= Restriction.MIN_CONFIDENCE)
        assertEquals("First Flame", job.title)
    }

    @Test fun inspectingARestrictedSongFindsAnotherUploadAndDownloadsAudio() {
        engine.updateSettings { it.copy(turbo = false, wifiOnly = false) }
        val media = (engine.inspect(url, UUID.randomUUID().toString()) as InspectResult.Media).media
        log("inspect restricted: title=${media.title} uploader=${media.uploader} match=${media.match}")
        if (media.match == null) return // not an affected region: the original is playable
        assertEquals(Restriction.SOURCE, media.match!!.source)
        assertEquals("First Flame", media.title)
        assertEquals(url, media.url)
        val before = engine.jobs.value.map { it.id }.toSet()
        engine.enqueue(media, FormatRequest.Audio("mp3", "320"), DownloadOptions())
        assertSavedFromAlternate(awaitFinished(before))
    }

    @Test fun aQueuedRestrictedSongRecoversAtDownloadTime() {
        engine.updateSettings { it.copy(turbo = false, wifiOnly = false) }
        // Any playable video supplies a valid MediaInfo; the job itself points at the restricted song without a recorded match.
        val template = (engine.inspect("https://www.youtube.com/watch?v=aqz-KE-bpKQ", UUID.randomUUID().toString()) as InspectResult.Media).media
        val media = template.copy(url = url, webpageUrl = url, title = "First Flame", uploader = "Glum Aleks - Topic", provider = "YouTube Music",
            thumbnail = "https://i.ytimg.com/vi/jdOSfoV1njc/hqdefault.jpg", match = null, music = null, playlistUrl = null)
        val before = engine.jobs.value.map { it.id }.toSet()
        engine.enqueue(media, FormatRequest.Audio("mp3", "320"), DownloadOptions())
        val job = awaitFinished(before)
        if (job.match == null && job.status == JobStatus.COMPLETED) return // not an affected region
        assertSavedFromAlternate(job)
    }
}
