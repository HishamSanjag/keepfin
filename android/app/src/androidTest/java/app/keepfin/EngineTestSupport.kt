package app.keepfin

import androidx.test.platform.app.InstrumentationRegistry
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Minimal local HTTP server serving the instrumentation fixture video; can hold or fail media responses. */
internal class FixtureServer : AutoCloseable {
    private val body = InstrumentationRegistry.getInstrumentation().context.assets.open("fixture.mp4").use { it.readBytes() }
    private val server = ServerSocket(0)
    private val stopped = AtomicBoolean()
    private val clients = java.util.concurrent.Executors.newCachedThreadPool()
    @Volatile var serveMedia = true
    /** Status sent while [serveMedia] is false (404 = permanent, 503 = transient). */
    @Volatile var failStatus = 404
    /** When true the response is 8 KB of junk: the transfer succeeds but audio conversion fails quickly (a retryable processing failure). */
    @Volatile var garbage = false
    /** While true, GET responses send headers and then wait before the body. */
    @Volatile var hold = false
    val requests = AtomicInteger()
    val paths = CopyOnWriteArrayList<String>()
    val port get() = server.localPort
    private val thread = Thread {
        while (!stopped.get()) try {
            val socket = server.accept()
            clients.execute {
                socket.use {
                    try {
                        val reader = it.getInputStream().bufferedReader()
                        val line = reader.readLine().orEmpty()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        requests.incrementAndGet(); paths.add(line)
                        val out = it.getOutputStream()
                        if (!serveMedia) { out.write("HTTP/1.1 $failStatus Test failure\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return@use }
                        val payload = if (garbage) ByteArray(8192) { 0x55 } else body
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: ${if (garbage) "audio/mpeg" else "video/mp4"}\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        out.flush()
                        if (!line.startsWith("HEAD")) {
                            while (hold && !stopped.get()) Thread.sleep(25)
                            out.write(payload)
                        }
                        out.flush()
                    } catch (_: Exception) { }
                }
            }
        } catch (error: Exception) { if (!stopped.get()) Thread.sleep(10) }
    }.apply { isDaemon = true; start() }
    fun url(name: String = "fixture-${UUID.randomUUID()}.mp4") = "http://127.0.0.1:$port/$name"
    override fun close() { stopped.set(true); hold = false; server.close(); thread.join(3000); clients.shutdownNow() }
}

internal fun MediaEngine.mediaOf(url: String, session: String = UUID.randomUUID().toString()): MediaInfo =
    (inspect(url, session) as InspectResult.Media).media

internal fun MediaEngine.newestJobId(before: Set<String>) = jobs.value.first { it.id !in before }.id

internal fun MediaEngine.job(id: String) = jobs.value.first { it.id == id }

internal fun MediaEngine.await(id: String, timeoutMs: Long = 120_000, until: (Job) -> Boolean = { !it.status.active }): Job {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        jobs.value.firstOrNull { it.id == id }?.let { if (until(it)) return it }
        Thread.sleep(50)
    }
    error("Timed out waiting for job $id: ${jobs.value.firstOrNull { it.id == id }}")
}

internal fun testJob(status: JobStatus, id: String = "notice-test-${UUID.randomUUID()}", attempt: Int = 1) = Job(
    id = id, url = "http://127.0.0.1/clip.mp4", title = "Notification test clip", thumbnail = null, uploader = null, duration = null, provider = "Web",
    label = "Best quality · MP4", kind = MediaKind.VIDEO, status = status, percent = 0f, speed = null, eta = null, downloadedBytes = null, totalBytes = null,
    expectedSize = null, fileSize = null, phase = null, error = if (status == JobStatus.FAILED) "Local test server returned 404" else null, file = null,
    publicUri = null, publicPath = if (status == JobStatus.COMPLETED) "Movies/Keepfin/test.mp4" else null, saveError = null,
    createdAt = System.currentTimeMillis(), completedAt = null, attempt = attempt,
)
