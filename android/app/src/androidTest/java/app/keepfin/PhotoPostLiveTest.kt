package app.keepfin

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * LIVE: a public Pinterest photo post is read by the real gallery engine, downloaded, and found as a photo under Pictures/Keepfin.
 * Needs network and the packaged gallery engine (`npm run gallery:fetch`). Run on the emulator only:
 * gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.keepfin.PhotoPostLiveTest
 */
@RunWith(AndroidJUnit4::class)
class PhotoPostLiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine get() = (context.applicationContext as KeepfinApplication).engineApi as MediaEngine
    private val url = "https://www.pinterest.com/pin/99360735500167749/"
    private var library: LibrarySettings? = null

    // Without a Keepfin folder the photos are published to MediaStore, which is what this test looks at.
    @Before fun libraryOff() {
        library = engine.settings.value.library
        engine.updateSettings { it.copy(library = it.library.copy(enabled = false)) }
    }

    @After fun restore() { library?.let { saved -> engine.updateSettings { it.copy(library = saved) } } }

    @Test fun aPublicPinIsReadDownloadedAndSavedUnderPicturesKeepfin() {
        val media = engine.mediaOf(url)
        val gallery = media.gallery ?: error("a photo post, got $media")
        assertTrue("at least one photo: $gallery", gallery.count >= 1 && gallery.items.first().photo)
        assertTrue(media.video.isEmpty() && media.audio.isEmpty())

        val before = engine.jobs.value.map { it.id }.toSet()
        engine.enqueue(media, FormatRequest.Gallery(gallery.count), DownloadOptions())
        val job = engine.await(engine.newestJobId(before), timeoutMs = 180_000)

        assertEquals("job: $job", JobStatus.COMPLETED, job.status)
        assertEquals(MediaKind.PHOTOS, job.kind)
        assertNull("saved without a problem: ${job.saveError}", job.saveError)
        val path = job.publicPath ?: error("a public path: $job")
        assertTrue("saved under Pictures/Keepfin: $path", path.startsWith("Pictures/Keepfin/"))

        val uri = Uri.parse(job.publicUri)
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE), null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals("Pictures/Keepfin/", it.getString(0))
            assertTrue("an image: ${it.getString(1)}", it.getString(1).startsWith("image/"))
            assertTrue("not empty", it.getLong(2) > 0)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        assertTrue("decodes as a picture: ${bounds.outWidth}x${bounds.outHeight}", bounds.outWidth > 0 && bounds.outHeight > 0)

        // clean up what the test saved
        engine.deleteFile(job.id)
    }
}
