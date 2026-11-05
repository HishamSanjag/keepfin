package app.keepfin

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Environment
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DownloadHistoryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun v1QueueIsMigratedWithoutLosingHistory() = withIsolatedContext { isolated ->
        val queue = File(isolated.filesDir, "queue.json")
        val v1 = JSONArray()
            .put(JSONObject().put("id", "v1-a").put("url", "https://www.youtube.com/watch?v=aqz-KE-bpKQ").put("title", "Old completed").put("status", "completed")
                .put("label", "720p · mp4").put("createdAt", 1700000000000L).put("attempt", 1)
                .put("choice", JSONObject().put("kind", "video").put("label", "720p · mp4").put("args", JSONArray(listOf("-f", "bv*[height=720]+ba/b[height=720]"))))
                .put("publicUri", "content://media/external/video/media/1").put("publicPath", "Movies/Keepfin/Old completed.mp4"))
            .put(JSONObject().put("id", "v1-b").put("url", "https://soundcloud.com/a/b").put("title", "Old running").put("status", "downloading").put("percent", 40)
                .put("choice", JSONObject().put("kind", "audio").put("label", "audio only · mp3").put("args", JSONArray(listOf("-x")))))
        queue.writeText(v1.toString())
        val engine = MediaEngine(isolated)
        try {
            val jobs = engine.jobs.value
            assertEquals(setOf("v1-a", "v1-b"), jobs.map { it.id }.toSet())
            assertEquals("Old completed", jobs.first { it.id == "v1-a" }.title)
            assertEquals(JobStatus.COMPLETED, jobs.first { it.id == "v1-a" }.status)
            assertEquals("Movies/Keepfin/Old completed.mp4", jobs.first { it.id == "v1-a" }.publicPath)
            assertEquals("Interrupted work becomes a retryable failure", JobStatus.FAILED, jobs.first { it.id == "v1-b" }.status)
            assertEquals(MediaKind.AUDIO, jobs.first { it.id == "v1-b" }.kind)
            assertTrue("The v1 file is preserved byte for byte", File(isolated.filesDir, "queue.v1.json").readText() == v1.toString())
            assertTrue("queue.json is rewritten as v2", JSONObject(queue.readText()).getInt("version") == JobCodec.VERSION)
        } finally { engine.shutdown() }
        // A second start reads the v2 file with the same jobs.
        val again = MediaEngine(isolated)
        try { assertEquals(setOf("v1-a", "v1-b"), again.jobs.value.map { it.id }.toSet()) } finally { again.shutdown() }
    }

    @Test fun removeOneTerminalEntryPersistsAndKeepsItsDownloadedFiles() = withIsolatedContext { isolated ->
        val id = "history-test-${UUID.randomUUID()}"
        val otherId = "history-test-${UUID.randomUUID()}"
        val privateFile = File(isolated.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Keepfin/keep.mp4").apply {
            parentFile!!.mkdirs()
            writeBytes(instrumentation.context.assets.open("fixture.mp4").use { it.readBytes() })
        }
        val publisher = PublicDownloads(context)
        val saved = publisher.publish(listOf(privateFile), "history-keep-${UUID.randomUUID()}").getValue(privateFile)
        val queue = File(isolated.filesDir, "queue.json")
        val base = testJob(JobStatus.COMPLETED, id).copy(title = "Retained media", file = privateFile.absolutePath, publicUri = saved.uri.toString(), publicPath = saved.path)
        val other = testJob(JobStatus.FAILED, otherId).copy(title = "Other history")
        queue.writeText(JobCodec.encodeFile(listOf(
            JobRecord(base, FormatRequest.Video(), DownloadOptions()), JobRecord(other, FormatRequest.Video(), DownloadOptions()),
        )))
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val thumbnails = ThumbnailStore(isolated)
        thumbnails.saveJob(id, bitmap); thumbnails.saveJob(otherId, bitmap); bitmap.recycle()
        val engine = MediaEngine(isolated)
        try {
            engine.remove(id)
            val remaining = engine.jobs.value
            assertEquals(1, remaining.size)
            assertEquals(otherId, remaining[0].id)
            assertFalse("Removal must be persisted", queue.readText().contains(id))
            assertTrue("Removing history must retain the downloaded original", privateFile.isFile)
            assertArrayEquals(privateFile.readBytes(), context.contentResolver.openInputStream(saved.uri)!!.use { it.readBytes() })
            // the preview is held while the removal can be undone, and goes when that time is up
            thumbnails.loadJob(id).also { assertNotNull("Undo can still bring the preview back", it); it!!.recycle() }
            engine.purgeRemoved(System.currentTimeMillis() + RemovedHistory.KEEP_MS + 1)
            assertNull("Only the removed entry's owned preview is deleted", thumbnails.loadJob(id))
            val lateImage = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            try { engine.rememberThumbnail(id, lateImage) } finally { lateImage.recycle() }
            assertNull("A late thumbnail callback must not recreate a removed history preview", thumbnails.loadJob(id))
            thumbnails.loadJob(otherId).also { assertNotNull(it); it!!.recycle() }
            engine.clearHistory()
            assertTrue(engine.jobs.value.isEmpty())
            assertTrue("clearHistory keeps media", privateFile.isFile)
        } finally { engine.shutdown(); publisher.discard(listOf(saved)) }
    }

    @Test fun deleteFileRemovesBothCopiesAndTheEntry() = withIsolatedContext { isolated ->
        val id = "history-test-${UUID.randomUUID()}"
        val privateFile = File(isolated.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Keepfin/gone.mp4").apply {
            parentFile!!.mkdirs()
            writeBytes(instrumentation.context.assets.open("fixture.mp4").use { it.readBytes() })
        }
        val publisher = PublicDownloads(context)
        val saved = publisher.publish(listOf(privateFile), "history-gone-${UUID.randomUUID()}").getValue(privateFile)
        val job = testJob(JobStatus.COMPLETED, id).copy(file = privateFile.absolutePath, publicUri = saved.uri.toString(), publicPath = saved.path)
        File(isolated.filesDir, "queue.json").writeText(JobCodec.encodeFile(listOf(JobRecord(job, FormatRequest.Video(), DownloadOptions(), publicFiles = listOf(saved.uri.toString())))))
        val engine = MediaEngine(isolated)
        try {
            engine.deleteFile(id)
            assertFalse(privateFile.exists())
            assertTrue(engine.jobs.value.isEmpty())
            try { context.contentResolver.openInputStream(saved.uri)!!.close(); fail("Public copy must be gone") } catch (_: java.io.FileNotFoundException) { }
        } finally { engine.shutdown() }
    }

    @Test fun anActiveDownloadCannotBeRemovedFromHistory() = withIsolatedContext { isolated ->
        FixtureServer().use { server ->
            val engine = MediaEngine(isolated)
            var id: String? = null
            try {
                val media = engine.mediaOf(server.url("history-active.mp4"))
                server.hold = true
                engine.enqueue(media, FormatRequest.Video(), DownloadOptions())
                id = engine.jobs.value.first().id
                try { engine.remove(id); fail("Active download must be protected") }
                catch (error: IllegalArgumentException) { assertTrue(error.message!!.contains("Finish or cancel")) }
                assertEquals(id, engine.jobs.value.first().id)
            } finally {
                server.hold = false
                id?.let { runCatching { engine.cancel(it) } }
                engine.shutdown()
            }
        }
    }

    private fun withIsolatedContext(action: (Context) -> Unit) {
        val root = File(context.filesDir, "test-history-${UUID.randomUUID()}").apply { mkdirs() }
        check(root.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir() = root
            override fun getExternalFilesDir(type: String?) = File(root, "external/${type ?: "root"}").apply { mkdirs() }
        }
        try { action(isolated) } finally { root.deleteRecursively() }
    }
}
