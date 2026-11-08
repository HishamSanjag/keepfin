package app.keepfin.net

import app.keepfin.FormatRequest
import app.keepfin.InspectResult
import app.keepfin.PhotoPosts
import app.keepfin.core.gallery.Gallery
import app.keepfin.core.gallery.GalleryRead
import app.keepfin.ui.devices.formatJson
import app.keepfin.ui.preview.FakeEngine
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** "Download on..." with a photo post (issue #14): the receiving phone reads the post itself and downloads the photos chosen on the other device. */
class EngineJobPortPhotosTest {
    private val engine = FakeEngine()
    private val port = EngineJobPort(engine, metered = { false }, free = { null }, guard = { it })

    @After fun close() = port.close()

    private val link = "https://www.instagram.com/p/AAA/"

    private fun listing(count: Int) = "[[2,{\"category\":\"instagram\",\"title\":\"Trip\"}]," +
        (1..count).joinToString(",") { "[3,\"https://cdn.example/$it.jpg\",{\"category\":\"instagram\",\"extension\":\"jpg\",\"count\":$count}]" } + "]"

    private fun readsAs(count: Int) {
        engine.inspectResults[link] = InspectResult.Media(PhotoPosts.media(Gallery.read(listing(count), link) as GalleryRead.Post, link))
    }

    private fun waitFor(jobId: String, state: String): RemoteJobView {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) {
            port.view(jobId)?.takeIf { it.state == state }?.let { return it }
            Thread.sleep(20)
        }
        fail("job $jobId never reached $state, it is ${port.view(jobId)?.state}: ${port.view(jobId)?.error}")
        throw IllegalStateException()
    }

    @Test fun theChosenPhotosTravelAndTheOtherPhoneDownloadsThem() {
        readsAs(6)
        // what the sending phone puts on the wire for a choice of 3 photos
        val wire = formatJson(PhotoPosts.request(6, listOf(2, 4, 5)))
        assertEquals("""{"type":"gallery","items":[2,4,5]}""", wire.toString())
        port.create("job1", link, JSONObject(wire.toString()), null, null)
        val view = waitFor("job1", "queued")
        assertEquals("3 photos", view.label)
        assertEquals(1, engine.enqueued.size)
        assertEquals(FormatRequest.Gallery(6, listOf(2, 4, 5)), engine.enqueued[0].second)
    }

    @Test fun allPhotosTravelAsNoList() {
        readsAs(4)
        assertEquals("""{"type":"gallery"}""", formatJson(PhotoPosts.request(4, listOf(1, 2, 3, 4))).toString())
        port.create("job2", link, JSONObject("""{"type":"gallery"}"""), null, null)
        assertEquals("4 photos", waitFor("job2", "queued").label)
        assertEquals(FormatRequest.Gallery(4, null), engine.enqueued[0].second)
    }

    @Test fun aVideoRequestForAPhotoPostTakesEveryPhoto() {
        readsAs(2)
        port.create("job3", link, JSONObject("""{"type":"video","codec":"auto","container":"mp4"}"""), null, null)
        waitFor("job3", "queued")
        assertEquals(FormatRequest.Gallery(2, null), engine.enqueued[0].second)
    }

    @Test fun photosOutsideThePostAreRefusedLikeALocalRequest() {
        readsAs(3)
        port.create("job4", link, JSONObject("""{"type":"gallery","items":[2,9]}"""), null, null)
        val failed = waitFor("job4", "failed")
        assertEquals("The photos chosen on the other device are not in this post.", failed.error)
        assertTrue(engine.enqueued.isEmpty())
    }

    @Test fun aMalformedChoiceIsRefusedBeforeAnythingIsRead() {
        for (bad in listOf("""{"type":"gallery","items":[]}""", """{"type":"gallery","items":["a"]}""", """{"type":"gallery","items":"1"}""", """{"type":"gallery","items":[1.5]}""")) {
            try { port.create("bad-${bad.hashCode()}", link, JSONObject(bad), null, null); fail("accepted $bad") } catch (e: IllegalArgumentException) { assertNotNull(e.message) }
        }
        assertTrue(engine.inspectCalls.isEmpty())
    }

    @Test fun aPhotoRequestForALinkWithoutPhotosFailsHonestly() {
        port.create("job5", "https://example.com/clip", JSONObject("""{"type":"gallery"}"""), null, null)
        assertEquals("This link has no photos to download.", waitFor("job5", "failed").error)
    }
}
