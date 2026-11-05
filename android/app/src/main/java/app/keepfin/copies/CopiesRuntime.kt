package app.keepfin.copies

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.keepfin.KeepfinApplication
import app.keepfin.MainActivity
import app.keepfin.MediaEngine
import app.keepfin.R
import app.keepfin.cloud.CloudAddress
import app.keepfin.cloud.CloudRuntime
import app.keepfin.folder.ProfileStore
import app.keepfin.net.NetRuntime
import app.keepfin.folder.SafFolderAccess
import java.io.File
import java.util.concurrent.TimeUnit

/** The one [CopiesManager] of the app, made when the Keepfin folder library is open. Null without a Keepfin folder. */
internal object CopiesRuntime {
    @Volatile private var manager: CopiesManager? = null
    @Volatile private var owner: Any? = null

    /** The cloud accounts of the same profile as [manager]; made together with it. */
    @Volatile var cloud: CloudRuntime? = null
        private set

    fun of(app: KeepfinApplication): CopiesManager? {
        val engine = app.engineApi as? MediaEngine ?: return null
        val repo = engine.folderLibrary ?: return null
        manager?.takeIf { owner === repo }?.let { return it }
        synchronized(this) {
            manager?.takeIf { owner === repo }?.let { return it }
            manager?.close()
            val profiles = ProfileStore(File(app.filesDir, "profiles.json"))
            val profile = profiles.current() ?: return null
            val deviceDir = profiles.deviceDir(app.filesDir, profile.id)
            val tools = engine.mediaTools
            val lib = RepoCopiesLibrary(repo)
            var holder: CopiesManager? = null
            val cloudRt = CloudRuntime(
                app, deviceDir,
                library = { runCatching { lib.backend().let { it.libraryId to it.replicaId } }.getOrNull() },
                knownPlaces = { holder?.knownReplicas().orEmpty() },
                addresses = { holder?.cloudAddresses().orEmpty() },
            )
            cloud = cloudRt
            // paired devices exist only where Devices does; the service is looked up when needed, because it may start later
            val peerRt = if (NetRuntime.available) PeerRuntime({ NetRuntime.peek() }, { android.os.Build.MODEL ?: "Android" }, { runCatching { lib.backend().libraryId }.getOrNull() }) else null
            val m = CopiesManager(
                app, lib, deviceDir, profile.folderTreeUri, android.os.Build.MODEL ?: "Android",
                openAccess = { if (CloudAddress.isCloud(it)) cloudRt.open(it) else SafFolderAccess.open(app.contentResolver, Uri.parse(it)) },
                cloud = cloudRt,
                peers = peerRt,
                converter = Mp3Converter { src, out, abort -> convertToMp3(tools, src, out, abort) },
                onActive = { active -> if (active) CopiesService.start(app) },
                onReport = { report -> CopiesService.result(app, report.title, report.detail) },
                candidateTrees = { persistedTrees(app) },
                power = { powerOf(app) },
            )
            watchVolumes(app)
            holder = m
            manager = m
            owner = repo
            return m
        }
    }

    /** (charging, unmetered) now. A large run started by the background worker waits unless one is true ([Scheduling]); Sync now is never held. */
    fun powerOf(context: Context): Pair<Boolean, Boolean> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val unmetered = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) } == true
        val charging = context.getSystemService(BatteryManager::class.java)?.isCharging == true
        return charging to unmetered
    }

    /** Folder addresses the person gave this app permission to write (an SD card or drive keeps its permission under its own address). */
    fun persistedTrees(context: Context): List<String> =
        context.contentResolver.persistedUriPermissions.filter { it.isReadPermission && it.isWritePermission }.map { it.uri.toString() }

    /** How many times the background worker has run in this process (tests read it). */
    val workerRuns = java.util.concurrent.atomic.AtomicInteger()

    @Volatile private var watching = false

    /** A card or drive being mounted triggers a pass at once, so a waiting copy starts without waiting for the next tick. */
    fun watchVolumes(app: KeepfinApplication) {
        if (watching) return
        watching = true
        val filter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_CHECKING)
            addDataScheme("file")
        }
        androidx.core.content.ContextCompat.registerReceiver(app, object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                Thread { runCatching { of(app)?.tick() } }.start()
            }
        }, filter, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
    }

    fun convertToMp3(tools: app.keepfin.MediaTools, src: File, out: File, abort: () -> Boolean) {
        if (abort()) throw SkipError("paused")
        val r = tools.run(listOf("-v", "error", "-y", "-i", src.absolutePath, "-map", "0:a:0", "-map", "0:v:0?", "-c:v", "mjpeg", "-disposition:v", "attached_pic", "-map_metadata", "0", "-c:a", "libmp3lame", "-q:a", "2", "-id3v2_version", "3", "-f", "mp3", out.absolutePath), 600)
            ?: throw IllegalStateException("No audio converter is available.")
        if (r.exit != 0) throw IllegalStateException(r.text.trim().takeLast(300).ifEmpty { "The conversion ended with code ${r.exit}." })
    }

    /** Schedules the periodic check once there is a copy (every 30 minutes at most; Android decides the exact time). */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<CopiesWorker>(30, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("keepfin-copies", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

/** Starts due syncs in the background (dev-docs/plans/KEEPFIN-COPIES-SPEC.md section 8). The work itself runs in [CopiesService]. */
internal class CopiesWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        CopiesRuntime.workerRuns.incrementAndGet()
        val app = applicationContext as? KeepfinApplication ?: return Result.success()
        val m = CopiesRuntime.of(app) ?: return Result.success()
        runCatching { m.tick(background = true) }
        return Result.success()
    }
}

/**
 * Keeps a long run alive in the foreground (type dataSync). On Android 15 and later the system calls [onTimeout]; the run stops within a
 * second (every read checks the stop request), everything done is in the journal, and the next sync carries on.
 */
class CopiesService : Service() {
    companion object {
        private const val CHANNEL = "keepfin-copies"
        private const val ONGOING = 51
        private const val RESULT = 52

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, CopiesService::class.java))
            } catch (e: RuntimeException) {
                // Android refused a start from the background: the run goes on while the process lives, and the next foreground visit starts the service
            }
        }

        /** What a sync the person started did (Sync all, Sync now), once every copy in it finished. Silent without the permission. */
        fun result(context: Context, title: String, text: String) {
            if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Copies", NotificationManager.IMPORTANCE_LOW).apply { description = "Progress while Keepfin keeps your copies up to date." })
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            manager.notify(
                RESULT,
                NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setColor(0xff5279ee.toInt()).setContentTitle(title).setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(open).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_STATUS).build(),
            )
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastText = ""
    private fun manager() = getSystemService(NotificationManager::class.java)
    private fun copies() = (application as KeepfinApplication).let { CopiesRuntime.of(it) }

    private fun open(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun build(title: String, text: String, percent: Int?, ongoing: Boolean): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification).setColor(0xff5279ee.toInt()).setContentTitle(title).setContentText(text).setContentIntent(open())
        .setOngoing(ongoing).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_PROGRESS).setPriority(NotificationCompat.PRIORITY_LOW)
        .also { if (ongoing) it.setProgress(100, percent ?: 0, percent == null) }.build()

    private val ticker = object : Runnable {
        override fun run() {
            val m = copies()
            if (m == null || !m.isRunning()) {
                finish()
                return
            }
            val running = m.state.value.places.filter { it.progress != null || it.status == CopyStatus.SYNCING }
            val p = running.firstOrNull()?.progress
            val percent = p?.let { if (it.total > 0) (it.done * 100L / it.total).toInt().coerceIn(0, 100) else null }
            val text = if (p == null) "Getting ready" else "${p.done} of ${p.total} files" + (running.firstOrNull()?.name?.let { " · $it" } ?: "")
            if (text != lastText) {
                lastText = text
                manager().notify(ONGOING, build("Keeping your copies up to date", text, percent, true))
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun finish() {
        handler.removeCallbacks(ticker)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 26) manager().createNotificationChannel(NotificationChannel(CHANNEL, "Copies", NotificationManager.IMPORTANCE_LOW).apply { description = "Progress while Keepfin keeps your copies up to date." })
        try {
            ServiceCompat.startForeground(this, ONGOING, build("Keeping your copies up to date", "Getting ready", null, true), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (e: RuntimeException) {
            stopSelf()
            return START_NOT_STICKY
        }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        copies()?.pause()
        manager().notify(RESULT, build("Copies paused", "Open Keepfin to carry on where it stopped.", null, false))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
