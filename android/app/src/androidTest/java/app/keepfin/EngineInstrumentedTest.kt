package app.keepfin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EngineInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine get() = (context.applicationContext as KeepfinApplication).engineApi as MediaEngine

    @Test fun launcherUsesKeepfinAdaptiveIcon() {
        val icon = context.packageManager.getApplicationIcon(context.packageName)
        assertTrue("Launcher must use a custom adaptive icon", icon is android.graphics.drawable.AdaptiveIconDrawable)
        val adaptive = icon as android.graphics.drawable.AdaptiveIconDrawable
        assertEquals(android.graphics.Color.rgb(8, 9, 11), (adaptive.background as android.graphics.drawable.ColorDrawable).color)
        if (android.os.Build.VERSION.SDK_INT >= 33) assertNotNull("Themed launcher needs a monochrome layer", adaptive.monochrome)
        val bitmap = android.graphics.Bitmap.createBitmap(108, 108, android.graphics.Bitmap.Config.ARGB_8888)
        adaptive.setBounds(0, 0, 108, 108)
        adaptive.draw(android.graphics.Canvas(bitmap))
        val pixels = IntArray(108 * 108)
        bitmap.getPixels(pixels, 0, 108, 0, 0, 108, 108)
        // The Ink Tide shark is painted art: its cobalt waves and ivory shark show as many shades, not one exact colour.
        fun cobalt(c: Int) = android.graphics.Color.blue(c) > 120 && android.graphics.Color.blue(c) > android.graphics.Color.red(c) * 1.8
        fun ivory(c: Int) = minOf(android.graphics.Color.red(c), android.graphics.Color.green(c), android.graphics.Color.blue(c)) > 190
        assertTrue("The cobalt waves must be rendered", pixels.any(::cobalt))
        assertTrue("The ivory shark must be rendered", pixels.any(::ivory))
        java.io.File(context.cacheDir, "launcher-icon.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun onDeviceVideoAndMp3() {
        FixtureServer().use { server ->
            DownloadNotificationTestState(context).use { notices ->
                engine.updateSettings { it.copy(organization = "platform", wifiOnly = false, concurrency = 2) }
                val url = server.url("fixture.mp4")
                for (kind in listOf("video", "audio")) {
                    val session = UUID.randomUUID().toString()
                    val entries = mutableListOf<PlaylistEntry>()
                    val page = engine.discover(url, session, 1, 100) { entries.add(it) }
                    assertEquals(page.error, "completed", page.state)
                    assertEquals("Native wrapper must deliver parsed output lines", 1, entries.size)
                    assertEquals(url, entries.single().url)
                    try { engine.enqueueEntries(session, listOf(entries.single().copy(url = "$url?unknown")), DownloadPreset.BEST, DownloadOptions()); fail("Unknown entries must be rejected") }
                    catch (_: IllegalStateException) { }
                    val before = engine.jobs.value.map { it.id }.toSet()
                    val count = engine.enqueueEntries(session, entries, if (kind == "audio") DownloadPreset.MP3 else DownloadPreset.BEST, DownloadOptions(embedThumbnail = false))
                    assertEquals(1, count)
                    val id = notices.own(engine.newestJobId(before))
                    try { engine.enqueueEntries(session, entries, DownloadPreset.BEST, DownloadOptions()); fail("Already queued entries must be rejected") }
                    catch (_: IllegalStateException) { }
                    val result = engine.await(id, 120_000)
                    assertEquals(result.error, JobStatus.COMPLETED, result.status)
                    val notice = notices.await(id)
                    assertEquals("Real engine completion must post a notification", "Download complete",
                        notice.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
                    assertNotNull("Notification tap opens the saved job in the app", notice.contentIntent)
                    assertTrue("Completed media should have an Open action", notice.actions.any { it.title.toString() == "Open" })
                    val file = engine.file(id)
                    assertTrue(file.length() > 0)
                    assertEquals(file.length(), result.fileSize)
                    assertEquals("127.0.0.1", file.parentFile!!.name)
                    assertTrue(file.name.startsWith("fixture"))
                    assertEquals(if (kind == "audio") "mp3" else "mp4", file.extension)
                    assertNull(result.saveError)
                    assertTrue(result.publicPath!!, result.publicPath!!.startsWith(if (kind == "audio") "Music/Keepfin/" else "Movies/Keepfin/"))
                    val publicUri = android.net.Uri.parse(result.publicUri)
                    if (kind == "video") {
                        assertArrayEquals("Public media must match the downloaded output", file.readBytes(),
                            context.contentResolver.openInputStream(publicUri)!!.use { it.readBytes() })
                        context.contentResolver.query(
                            android.provider.MediaStore.Video.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),
                            arrayOf(android.provider.MediaStore.Video.Media.BUCKET_DISPLAY_NAME),
                            "${android.provider.MediaStore.MediaColumns._ID} = ?", arrayOf(publicUri.lastPathSegment), null
                        )!!.use {
                            assertTrue("Public video must be indexed in the phone's video collection", it.moveToFirst())
                            assertEquals("Keepfin", it.getString(0))
                        }
                    } else context.contentResolver.query(publicUri, arrayOf(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                        android.provider.MediaStore.MediaColumns.MIME_TYPE), null, null, null)!!.use {
                        assertTrue(it.moveToFirst())
                        assertEquals("Music/Keepfin/", it.getString(0))
                        assertEquals("audio/mpeg", it.getString(1))
                    }
                    engine.retryPublicSave(id)
                    assertEquals("Saving again must not duplicate the public file", result.publicUri, engine.job(id).publicUri)
                    engine.remove(id)
                    assertTrue("Removing history keeps the downloaded media", file.isFile)
                    context.contentResolver.openInputStream(publicUri)!!.close()
                    assertFalse(engine.jobs.value.any { it.id == id })
                    file.delete()
                }
            }
        }
    }

    @Test fun deleteFileRemovesPublicAndPrivateCopies() {
        FixtureServer().use { server ->
            engine.updateSettings { it.copy(organization = "flat", wifiOnly = false) }
            val before = engine.jobs.value.map { it.id }.toSet()
            engine.enqueue(engine.mediaOf(server.url("delete.mp4")).copy(title = "Delete ${UUID.randomUUID()}"), FormatRequest.Video(), DownloadOptions(embedThumbnail = false))
            val id = engine.newestJobId(before)
            val done = engine.await(id, 120_000)
            assertEquals(done.error, JobStatus.COMPLETED, done.status)
            val file = engine.file(id)
            val uri = android.net.Uri.parse(done.publicUri)
            engine.deleteFile(id)
            assertFalse(file.exists())
            assertFalse(engine.jobs.value.any { it.id == id })
            try { context.contentResolver.openInputStream(uri)!!.close(); fail("The public copy must be deleted") } catch (_: java.io.FileNotFoundException) { }
        }
    }

    @Test fun concurrentJobsRespectLimitPauseAndResume() {
        FixtureServer().use { server ->
            engine.updateSettings { it.copy(organization = "flat", wifiOnly = false, concurrency = 2) }
            val media = engine.mediaOf(server.url("hold.mp4"))
            server.hold = true
            val before = engine.jobs.value.map { it.id }.toSet()
            repeat(3) { engine.enqueue(media.copy(title = "Concurrent ${it + 1} ${UUID.randomUUID()}"), FormatRequest.Video(), DownloadOptions(embedThumbnail = false)) }
            val ids = engine.jobs.value.filter { it.id !in before }.map { it.id }
            assertEquals(3, ids.size)
            try {
                val deadline = System.currentTimeMillis() + 60_000
                var maxRunning = 0
                while (System.currentTimeMillis() < deadline) {
                    val running = ids.count { engine.job(it).status in setOf(JobStatus.PREPARING, JobStatus.DOWNLOADING, JobStatus.PROCESSING) }
                    maxRunning = maxOf(maxRunning, running)
                    assertTrue("Never more than the concurrency setting", running <= 2)
                    if (running == 2) break
                    Thread.sleep(50)
                }
                assertEquals("Two jobs run in parallel", 2, maxRunning)
                assertEquals("The third waits", 1, ids.count { engine.job(it).status == JobStatus.QUEUED })
                // Pausing a running job hands its slot to the queued one.
                val queued = ids.first { engine.job(it).status == JobStatus.QUEUED }
                val running = ids.first { engine.job(it).status != JobStatus.QUEUED }
                engine.pause(running)
                assertEquals(JobStatus.PAUSED, engine.job(running).status)
                engine.await(queued, 30_000) { it.status != JobStatus.QUEUED }
                server.hold = false
                engine.resume(running)
                ids.forEach { id ->
                    val done = engine.await(id, 120_000)
                    assertEquals(done.error, JobStatus.COMPLETED, done.status)
                }
                assertTrue(ids.all { engine.job(it).fileSize!! > 0 })
            } finally {
                server.hold = false
                ids.forEach { runCatching { engine.cancel(it) } }
                engine.awaitIdle(30_000)
            }
        }
    }

    @Test fun cancelStopsTransferThenRetryCompletes() {
        FixtureServer().use { server ->
            engine.updateSettings { it.copy(organization = "flat", wifiOnly = false, concurrency = 2) }
            val media = engine.mediaOf(server.url("cancel.mp4"))
            server.hold = true
            val before = engine.jobs.value.map { it.id }.toSet()
            engine.enqueue(media.copy(title = "Cancel me ${UUID.randomUUID()}"), FormatRequest.Video(), DownloadOptions())
            val id = engine.newestJobId(before)
            try {
                engine.await(id, 30_000) { it.status != JobStatus.QUEUED }
                engine.cancel(id)
                assertEquals(JobStatus.CANCELLED, engine.job(id).status)
                assertTrue("The worker slot is freed", engine.awaitIdle(20_000))
                server.hold = false
                engine.retry(id)
                val done = engine.await(id, 120_000)
                assertEquals(done.error, JobStatus.COMPLETED, done.status)
                assertEquals(2, done.attempt)
            } finally { server.hold = false; runCatching { engine.cancel(id) }; engine.awaitIdle(30_000) }
        }
    }

    @Test fun wifiOnlyKeepsJobsQueuedWithAReason() {
        FixtureServer().use { server ->
            val media = engine.mediaOf(server.url("wifi.mp4"))
            val granted = context.checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val metered = granted && context.getSystemService(android.net.ConnectivityManager::class.java).isActiveNetworkMetered
            engine.updateSettings { it.copy(wifiOnly = true) }
            val before = engine.jobs.value.map { it.id }.toSet()
            try {
                engine.enqueue(media.copy(title = "Wifi ${UUID.randomUUID()}"), FormatRequest.Video(), DownloadOptions())
                val id = engine.newestJobId(before)
                if (!granted || metered) {
                    // Without network-status access the engine cannot prove an unmetered link, so it must not download.
                    Thread.sleep(1000)
                    assertEquals(JobStatus.QUEUED, engine.job(id).status)
                    val reason = engine.job(id).phase.orEmpty()
                    assertTrue(reason, reason == "Waiting for Wi-Fi" || reason.contains("network-status access"))
                    engine.updateSettings { it.copy(wifiOnly = false) }
                }
                val done = engine.await(id, 120_000)
                assertEquals(done.error, JobStatus.COMPLETED, done.status)
            } finally { engine.updateSettings { it.copy(wifiOnly = false) } }
        }
    }

    @Test fun progressStateFlowDoesNotEmitPerLine() {
        FixtureServer().use { server ->
            engine.updateSettings { it.copy(organization = "flat", wifiOnly = false) }
            val media = engine.mediaOf(server.url("flow.mp4"))
            val emissions = java.util.concurrent.atomic.AtomicInteger()
            val running = java.util.concurrent.atomic.AtomicBoolean(true)
            val before = engine.jobs.value.map { it.id }.toSet()
            val collector = Thread {
                var last: List<Job>? = null
                while (running.get()) {
                    val now = engine.jobs.value
                    if (now !== last) { emissions.incrementAndGet(); last = now }
                    Thread.sleep(2)
                }
            }.apply { start() }
            engine.enqueue(media.copy(title = "Flow ${UUID.randomUUID()}"), FormatRequest.Video(), DownloadOptions())
            val id = engine.newestJobId(before)
            val done = engine.await(id, 60_000)
            running.set(false); collector.join(5_000)
            assertEquals(done.error, JobStatus.COMPLETED, done.status)
            // Queue + prepare + download + processing + completion + publishing are structural; never one emission per percent.
            assertTrue("emissions=${emissions.get()}", emissions.get() < 40)
        }
    }
}
