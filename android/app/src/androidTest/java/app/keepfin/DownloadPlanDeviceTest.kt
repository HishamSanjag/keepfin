package app.keepfin

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Real downloads through the shared core's download plan (phase 4): a section clip from the local fixture, and (live, needs
 * network) subtitles converted and embedded. Run explicitly:
 * gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.keepfin.DownloadPlanDeviceTest
 */
@RunWith(AndroidJUnit4::class)
class DownloadPlanDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine get() = (context.applicationContext as KeepfinApplication).engineApi as MediaEngine

    private fun durationMs(file: File): Long = MediaMetadataRetriever().run {
        try {
            setDataSource(file.absolutePath)
            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
        } finally {
            release()
        }
    }

    private fun download(media: MediaInfo, format: FormatRequest, options: DownloadOptions): Pair<Job, File> {
        val before = engine.jobs.value.map { it.id }.toSet()
        engine.enqueue(media, format, options)
        val id = engine.jobs.value.first { it.id !in before }.id
        val job = engine.await(id, 180_000)
        assertEquals(job.error, JobStatus.COMPLETED, job.status)
        return job to engine.file(id)
    }

    @Test fun sectionClipIsCut() {
        FixtureServer().use { server ->
            engine.updateSettings { it.copy(wifiOnly = false, concurrency = 2, turbo = false) }
            val url = server.url("fixture.mp4")
            val media = (engine.inspect(url, UUID.randomUUID().toString()) as InspectResult.Media).media
            val (_, whole) = download(media, FormatRequest.Video(), DownloadOptions(embedThumbnail = false))
            val full = durationMs(whole)
            // The fixture is one second long: a 0.2-0.6 s section must come out clearly shorter than the whole.
            assertTrue("fixture must be long enough to clip, was ${full}ms", full >= 900)
            val (job, clip) = download(media, FormatRequest.Video(), DownloadOptions(embedThumbnail = false, sectionStart = 0.2, sectionEnd = 0.6))
            val cut = durationMs(clip)
            assertTrue("a 0.2-0.6 s section must be well under the whole ${full}ms, was ${cut}ms", cut in 200..(full * 3 / 4))
            engine.remove(job.id)
        }
    }

    @Test fun liveSubtitlesAreEmbedded() {
        engine.updateSettings { it.copy(wifiOnly = false, concurrency = 2, turbo = false) }
        val url = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
        val media = (engine.inspect(url, UUID.randomUUID().toString()) as InspectResult.Media).media
        assertTrue("the test video must list subtitles: ${media.subtitles.take(5)}", media.subtitles.any { it.lang.startsWith("en") })
        val (job, file) = download(
            media,
            FormatRequest.Video(maxHeight = 240, container = "mp4"),
            DownloadOptions(subtitles = SubtitleOptions(enabled = true, languages = "en.*", auto = true, embed = true), embedThumbnail = false),
        )
        val extractor = MediaExtractor()
        val mimes = try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
        } finally {
            extractor.release()
        }
        assertTrue("the MP4 must carry a subtitle track, tracks: $mimes", mimes.any { it.startsWith("text/") || it.contains("tx3g") })
        engine.remove(job.id)
    }
}
