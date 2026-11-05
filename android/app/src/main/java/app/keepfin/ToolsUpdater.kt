package app.keepfin

import android.content.Context
import android.util.Log
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * Owns the bundled engine binaries: one-time initialisation off the main thread, version reporting and
 * yt-dlp self-updates (stale yt-dlp is the main cause of throttled or failing YouTube downloads).
 */
internal class ToolsUpdater(private val context: Context, private val settings: () -> EngineSettings) {
    private val prefs = context.getSharedPreferences("engine-tools", Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "keepfin-tools").apply { isDaemon = true } }
    private val updating = AtomicBoolean()
    private val initLock = Any()
    @Volatile private var initialized = false
    @Volatile var aria2cAvailable = false
        private set
    /** mutagen lets yt-dlp embed covers into Opus/FLAC. */
    @Volatile var mutagen = false
        private set

    private val state = MutableStateFlow(listOf(
        ToolStatus("yt-dlp", "updating", prefs.getString("yt-dlp", null), prefs.getLong("checkedAt", 0).takeIf { it > 0 }, "Starting engine…"),
        ToolStatus("ffmpeg", "ready", prefs.getString("ffmpeg", null), null, null),
        ToolStatus("python", "ready", prefs.getString("python", null), null, null),
    ))
    val tools: StateFlow<List<ToolStatus>> = state

    /** Absolute path of the CA bundle shipped with the Python package (aria2c has no system store). */
    val caCertificate: String? get() = File(context.noBackupFilesDir, "youtubedl-android/packages/python/usr/etc/tls/cert.pem").takeIf { it.isFile }?.absolutePath

    fun ensureInit() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
            aria2cAvailable = try { Aria2c.getInstance().init(context); true } catch (error: Throwable) { Log.w(TAG, "aria2c unavailable", error); false }
            initialized = true
        }
    }

    /** Background start: initialise, report versions, then update yt-dlp if the last check is older than 24 h. */
    fun start() = worker.execute {
        try {
            ensureInit()
            probe(null)
            val settings = settings()
            val last = prefs.getLong("checkedAt", 0)
            if (settings.autoUpdate && System.currentTimeMillis() - last > DAY_MS) runUpdate(false)
        } catch (error: Throwable) {
            Log.e(TAG, "engine start failed", error)
            set("yt-dlp") { it.copy(state = "error", message = "The download engine could not start: ${ErrorMessages.scrub(error.message).ifEmpty { "unexpected error" }}") }
        }
    }

    fun update(force: Boolean) = worker.execute { runUpdate(force) }

    /** True while an update is running. */
    fun busy() = updating.get()

    /** Epoch millis of the last completed update check, or 0. */
    fun lastChecked(): Long = prefs.getLong("checkedAt", 0)

    /**
     * Starts a background update unless one ran within [maxAgeMs]. Never takes the exclusive lock blocking: a busy engine
     * postpones the update, so other jobs keep running. Returns whether an update was started.
     */
    fun updateIfStale(maxAgeMs: Long): Boolean {
        if (updating.get() || System.currentTimeMillis() - lastChecked() < maxAgeMs) return false
        worker.execute { runUpdate(false) }
        return true
    }

    /** Blocking update used by [update] and tests. Returns true when a new yt-dlp was installed. */
    fun runUpdate(force: Boolean): Boolean {
        if (!updating.compareAndSet(false, true)) return false
        try {
            ensureInit()
            val last = prefs.getLong("checkedAt", 0)
            if (!force && System.currentTimeMillis() - last < 10 * 60_000L) return false
            val previous = state.value.first { it.name == "yt-dlp" }.version
            // Never replace yt-dlp under a running process: wait briefly when forced, else postpone.
            val locked = if (force) executionLock.writeLock().tryLock(30, TimeUnit.SECONDS) else executionLock.writeLock().tryLock()
            if (!locked) {
                if (force) set("yt-dlp") { it.copy(state = "error", message = "The engine is busy. Update again when downloads finish.") }
                else worker.schedule({ runUpdate(false) }, 10, TimeUnit.MINUTES)
                return false
            }
            try {
            set("yt-dlp") { it.copy(state = "updating", message = "Checking for engine updates…") }
            val channel = if (settings().channel == "nightly") YoutubeDL.UpdateChannel._NIGHTLY else YoutubeDL.UpdateChannel._STABLE
            val status = YoutubeDL.getInstance().updateYoutubeDL(context, channel)
            val now = System.currentTimeMillis()
            prefs.edit().putLong("checkedAt", now).apply()
            probe(if (status == YoutubeDL.UpdateStatus.DONE) "Updated" else "Up to date")
            val current = state.value.first { it.name == "yt-dlp" }.version
            Log.i(TAG, "yt-dlp update status=$status $previous -> $current")
            return status == YoutubeDL.UpdateStatus.DONE
            } finally { executionLock.writeLock().unlock() }
        } catch (error: Throwable) {
            Log.w(TAG, "yt-dlp update failed", error)
            set("yt-dlp") { it.copy(state = "error", checkedAt = System.currentTimeMillis(), message = "Engine update failed: ${ErrorMessages.friendly(error.message)}") }
            return false
        } finally { updating.set(false) }
    }

    /** Reads versions from yt-dlp's verbose header (yt-dlp, Python, ffmpeg and optional modules). */
    private fun probe(message: String?) {
        val header = try {
            val request = YoutubeDLRequest(emptyList<String>())
            request.addOption("--verbose"); request.addOption("--ignore-config"); request.addOption("--list-impersonate-targets")
            val response = YoutubeDL.getInstance().execute(request, "tools-probe-${System.nanoTime()}", null)
            response.out + "\n" + response.err
        } catch (error: Exception) { error.message.orEmpty() }
        val ytdlp = Regex("yt-dlp version (\\S+@)?(\\S+)").find(header)?.groupValues?.get(2)
            ?: header.lineSequence().map { it.trim() }.firstOrNull { it.matches(Regex("\\d{4}\\.\\d{2}\\.\\d{2}(\\.\\d+)?")) }
            ?: YoutubeDL.getInstance().versionName(context) ?: YoutubeDL.getInstance().version(context)
        val python = Regex("\\[debug] Python (\\S+)").find(header)?.groupValues?.get(1)
        val ffmpeg = Regex("exe versions: ffmpeg (\\S+)").find(header)?.groupValues?.get(1)
        mutagen = header.contains("mutagen")
        val now = System.currentTimeMillis()
        prefs.edit().putString("yt-dlp", ytdlp).putString("python", python).putString("ffmpeg", ffmpeg).apply()
        val checked = prefs.getLong("checkedAt", 0).takeIf { it > 0 }
        state.value = listOf(
            ToolStatus("yt-dlp", if (ytdlp != null) "ready" else "error", ytdlp, checked, message ?: if (ytdlp == null) "Engine version unavailable." else null),
            ToolStatus("ffmpeg", "ready", ffmpeg, now, null),
            ToolStatus("python", "ready", python, now, null),
        )
    }

    private fun set(name: String, transform: (ToolStatus) -> ToolStatus) {
        state.value = state.value.map { if (it.name == name) transform(it) else it }
    }

    companion object {
        /** Shared by every yt-dlp execution (read) and self-updates (write). */
        private val executionLock = ReentrantReadWriteLock()
        fun <T> reading(block: () -> T): T {
            executionLock.readLock().lock()
            try { return block() } finally { executionLock.readLock().unlock() }
        }
        private const val TAG = "KeepfinTools"
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
