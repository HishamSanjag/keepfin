package app.keepfin

import android.os.SystemClock
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Socket timeouts are per request; this owner also bounds the entire extractor process. */
internal class MetadataRequests {
    private data class Call(val session: String, val process: String, val cancelled: AtomicBoolean = AtomicBoolean(), val timedOut: AtomicBoolean = AtomicBoolean())
    private val calls = ConcurrentHashMap<String, Call>()
    private val cancelledSessions = ConcurrentHashMap<String, Long>()
    private val watchdog = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "keepfin-metadata-deadlines").apply { isDaemon = true } }
    fun cancel(session: String) {
        cancelledSessions[session] = SystemClock.elapsedRealtime()
        calls.values.filter { it.session == session }.forEach {
            it.cancelled.set(true)
            YoutubeDL.destroyProcessById(it.process)
        }
    }
    fun cancelled(session: String) = cancelledSessions.containsKey(session)
    fun execute(request: YoutubeDLRequest, session: String, timeoutMs: Long, init: () -> Unit, onLine: ((String) -> Unit)? = null): String {
        require(timeoutMs in 100..120_000) { "Invalid metadata deadline." }
        val now = SystemClock.elapsedRealtime()
        cancelledSessions.entries.removeIf { now - it.value > 600_000 }
        val call = Call(session, "metadata-${UUID.randomUUID()}")
        val callbackFailure = java.util.concurrent.atomic.AtomicReference<Exception>()
        calls[call.process] = call
        // A worker can start between the UI cancellation and process registration.
        if (cancelledSessions.containsKey(session)) call.cancelled.set(true)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        val timer = watchdog.scheduleAtFixedRate({
            if (SystemClock.elapsedRealtime() >= deadline) call.timedOut.set(true)
            // Repeat until execute unwinds: cancellation can race process registration.
            if (call.cancelled.get() || call.timedOut.get() || callbackFailure.get() != null) YoutubeDL.destroyProcessById(call.process)
        }, 0, 100, TimeUnit.MILLISECONDS)
        fun check() {
            callbackFailure.get()?.let { throw it }
            check(!call.timedOut.get()) { "Inspection timed out. Check your connection, retry this link, or use an individual video." }
            check(!call.cancelled.get()) { "Inspection cancelled. Ready items are kept." }
        }
        try {
            init(); check()
            request.addOption("--socket-timeout", "10")
            request.addOption("--extractor-retries", "1")
            request.addOption("--retries", "1")
            var characters = 0
            val callback: ((Float, Long, String) -> Unit)? = onLine?.let { consumer ->
                { _, _, line ->
                    if (!call.cancelled.get() && !call.timedOut.get() && callbackFailure.get() == null) {
                        try {
                            characters += line.length
                            require(line.length <= 1_000_000 && characters <= 8_000_000) { "Playlist response exceeded the safe size limit." }
                            consumer(line)
                        } catch (error: Exception) { callbackFailure.compareAndSet(null, error) }
                    }
                }
            }
            val response = ToolsUpdater.reading { YoutubeDL.execute(request, call.process, callback).out }
            check()
            return response
        } catch (error: Exception) {
            check()
            throw error
        } finally {
            timer.cancel(false)
            calls.remove(call.process)
        }
    }
}
