package app.keepfin

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the process alive while any job is active and owns the single progress notification
 * (aggregate percent, speed and count). Terminal notifications belong to the engine.
 */
class DownloadService : Service() {
    companion object {
        const val ACTION_CANCEL = "app.keepfin.CANCEL_DOWNLOAD"
        const val ACTION_RETRY = "app.keepfin.RETRY_DOWNLOAD"
        const val ACTION_PAUSE = "app.keepfin.PAUSE_DOWNLOAD"
        const val ACTION_RESUME = "app.keepfin.RESUME_DOWNLOAD"
        private const val REFRESH_MS = 1000L
        private const val IDLE_GRACE_MS = 1500L
        /** Safety net only: the locks are released as soon as the queue is idle. */
        private const val LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private val startingWork = AtomicInteger()
        /** Reserve the service before committing queue work; a denied start leaves the queue untouched. */
        fun beginWork(context: Context): Closeable {
            startingWork.incrementAndGet()
            try { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
            catch (error: Exception) { startingWork.decrementAndGet(); throw error }
            return object : Closeable {
                private val closed = java.util.concurrent.atomic.AtomicBoolean()
                override fun close() { if (closed.compareAndSet(false, true)) startingWork.decrementAndGet() }
            }
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private val commands = Executors.newSingleThreadExecutor()
    private val app get() = application as KeepfinApplication
    private val engine get() = app.engineApi
    private val pendingCommands = AtomicInteger()
    @Volatile private var destroyed = false
    private var lastNotice: List<Any?>? = null
    private var idleSince = 0L

    /** QUEUED covers jobs waiting for an automatic retry, so the service (and its locks) stay up during backoff. */
    private fun active() = engine.jobs.value.filter { it.status.active }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Keeps the CPU and Wi-Fi radio at full speed while jobs run, even with the screen off or the app in the background. */
    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "keepfin:downloads")?.apply { setReferenceCounted(false) }
            if (wakeLock?.isHeld == false) wakeLock?.acquire(LOCK_TIMEOUT_MS)
            if (wifiLock == null) wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "keepfin:downloads")?.apply { setReferenceCounted(false) }
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
        } catch (_: Exception) { /* Locks are an optimisation; downloads continue without them. */ }
    }

    private fun releaseLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (_: Exception) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (_: Exception) {}
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (destroyed) return
            val jobs = active()
            val idle = jobs.isEmpty() && pendingCommands.get() == 0 && startingWork.get() == 0
            // A queue that is about to be filled (enqueue races the service start) must not stop it: require a short, steady idle.
            if (!idle) idleSince = 0L else if (idleSince == 0L) idleSince = System.currentTimeMillis()
            if (idle && System.currentTimeMillis() - idleSince >= IDLE_GRACE_MS) { releaseLocks(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
            if (jobs.isNotEmpty()) acquireLocks()
            val all = engine.jobs.value
            // Only re-post when something visible changed (group members count because the title shows "7 of 12").
            val key = jobs.map { listOf(it.id, it.status, it.percent.toInt(), it.speed?.div(50_000), it.phase, it.retryAt?.div(1000)) } +
                all.filter { it.group != null }.map { listOf(it.id, it.status) }
            if (key != lastNotice) {
                lastNotice = key
                getSystemService(NotificationManager::class.java).notify(1, app.notifications.progress(jobs, all))
            }
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate() { super.onCreate(); app.notifications.channels() }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(this, 1, app.notifications.progress(active(), engine.jobs.value),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (error: RuntimeException) {
            android.widget.Toast.makeText(this, "Android could not start background downloads. Reopen Keepfin and retry.", android.widget.Toast.LENGTH_LONG).show()
            commands.execute {
                active().forEach { job ->
                    try { (engine as? MediaEngine)?.cancel(job.id, "Android blocked the background download service. Reopen the app and retry.") ?: engine.cancel(job.id) }
                    catch (_: Exception) {}
                }
            }
            stopSelf(); return START_NOT_STICKY
        }
        lastNotice = null
        acquireLocks()
        val id = intent?.getStringExtra("jobId")
        if (id != null && intent.action in setOf(ACTION_CANCEL, ACTION_RETRY, ACTION_PAUSE, ACTION_RESUME)) {
            pendingCommands.incrementAndGet()
            commands.execute {
                val ids = if (intent.getBooleanExtra("group", false)) engine.jobs.value.filter { it.group?.id == id }.map { it.id } else listOf(id)
                for (target in ids) try {
                    val status = engine.jobs.value.firstOrNull { it.id == target }?.status
                    when (intent.action) {
                        ACTION_CANCEL -> engine.cancel(target)
                        ACTION_PAUSE -> engine.pause(target)
                        ACTION_RESUME -> engine.resume(target)
                        else -> if (status == JobStatus.FAILED || status == JobStatus.CANCELLED || ids.size == 1) engine.retry(target)
                    }
                } catch (_: Exception) { /* Already finished/retried actions are harmless. */ }
                finally { pendingCommands.decrementAndGet(); if (!destroyed) handler.post { if (!destroyed) { handler.removeCallbacks(ticker); handler.post(ticker) } } }
            }
        }
        handler.removeCallbacks(ticker); handler.postDelayed(ticker, 100)
        return START_NOT_STICKY
    }

    override fun onDestroy() { destroyed = true; handler.removeCallbacks(ticker); releaseLocks(); commands.shutdown(); super.onDestroy() }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15 limits dataSync services; pause (not cancel) so partial files resume later.
        destroyed = true
        releaseLocks()
        val jobs = active()
        Thread { jobs.forEach { try { engine.pause(it.id) } catch (_: Exception) { try { engine.cancel(it.id) } catch (_: Exception) {} } } }.start()
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
