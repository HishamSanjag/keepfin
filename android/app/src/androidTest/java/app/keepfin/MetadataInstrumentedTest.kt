package app.keepfin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MetadataInstrumentedTest {
    @Test fun realMetadataCacheCancellationAndDeadline() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val engine = (context.applicationContext as KeepfinApplication).engineApi as MediaEngine
        val body = instrumentation.context.assets.open("fixture.mp4").use { it.readBytes() }
        val server = ServerSocket(0)
        val stopped = AtomicBoolean()
        val requests = AtomicInteger()
        val accepted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val clients = Executors.newCachedThreadPool()
        val operations = Executors.newSingleThreadExecutor()
        val accept = Thread {
            while (!stopped.get()) try {
                val socket = server.accept()
                clients.execute {
                    socket.use {
                        try {
                            val reader = it.getInputStream().bufferedReader()
                            val line = reader.readLine().orEmpty()
                            while (!reader.readLine().isNullOrEmpty()) {}
                            requests.incrementAndGet()
                            if (line.contains("/blocked")) {accepted.countDown(); release.await(20, TimeUnit.SECONDS)}
                            val output = it.getOutputStream()
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp4\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            if (!line.startsWith("HEAD")) output.write(body)
                            output.flush()
                        } catch (_: Exception) {}
                    }
                }
            } catch (error: Exception) {if (!stopped.get()) throw error}
        }
        accept.start()
        val base = "http://127.0.0.1:${server.localPort}"
        try {
            val firstStarted = android.os.SystemClock.elapsedRealtime()
            val initial = engine.inspect("$base/fixture.mp4", "metadata-first")
            val firstMs = android.os.SystemClock.elapsedRealtime() - firstStarted
            assertTrue((initial as InspectResult.Media).media.video.isNotEmpty())
            val requestCount = requests.get()
            val cacheStarted = android.os.SystemClock.elapsedRealtime()
            assertEquals(initial, engine.inspect("$base/fixture.mp4", "metadata-cached"))
            val cacheMs = android.os.SystemClock.elapsedRealtime() - cacheStarted
            assertEquals("Cached inspect must not call the provider again", requestCount, requests.get())
            val blocked = operations.submit<InspectResult> { engine.inspect("$base/blocked-cancel.mp4", "cancel-fixture") }
            assertTrue("Actual yt-dlp process must reach the server", accepted.await(15, TimeUnit.SECONDS))
            val cancelStarted = android.os.SystemClock.elapsedRealtime()
            engine.cancelInspection("cancel-fixture")
            try {blocked.get(5, TimeUnit.SECONDS); fail("Cancellation must stop the metadata process")}
            catch (error: java.util.concurrent.ExecutionException) {assertTrue(error.cause?.message.orEmpty().contains("cancelled"))}
            val cancelMs = android.os.SystemClock.elapsedRealtime() - cancelStarted
            engine.cancelInspection("cancel-before-start")
            try {engine.inspect("$base/never-started.mp4", "cancel-before-start"); fail("Cancelled sessions must not start late workers")}
            catch (error: IllegalStateException) {assertTrue(error.message.orEmpty().contains("cancelled"))}
            val timeoutStarted = android.os.SystemClock.elapsedRealtime()
            try {engine.inspect("$base/blocked-deadline.mp4", "deadline-fixture", 1500); fail("Metadata must stop at its total deadline")}
            catch (error: IllegalStateException) {assertTrue(error.message.orEmpty().contains("timed out"))}
            val timeoutMs = android.os.SystemClock.elapsedRealtime() - timeoutStarted
            assertTrue("The process deadline must not wait for the socket's 10-second timeout", timeoutMs < 6000)
            assertEquals(initial, engine.inspect("$base/fixture.mp4", "metadata-cached"))
            android.util.Log.i("KeepfinMetadata", "first=${firstMs}ms cache=${cacheMs}ms cancel=${cancelMs}ms deadline=${timeoutMs}ms")
        } finally {
            engine.cancelInspection("cancel-fixture"); engine.cancelInspection("deadline-fixture"); engine.cancelInspection("cancel-before-start")
            stopped.set(true); release.countDown(); server.close(); accept.join(2000)
            clients.shutdownNow(); operations.shutdownNow()
        }
    }
}
