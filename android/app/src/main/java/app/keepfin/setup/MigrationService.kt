package app.keepfin.setup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.keepfin.KeepfinApplication
import app.keepfin.MainActivity
import app.keepfin.R

/**
 * Runs the copy of an earlier library in the foreground (type dataSync, dev-docs/plans/KEEPFIN-FOLDER-ANDROID-SPEC.md section 5). It is started
 * from the setup screen, which is the only way Android allows a start after the time limit. On Android 15 and later the system calls
 * [onTimeout]; the run ends within a second (every read checks the stop request), everything done is in the journal, and a
 * "Continue setup" notification brings the user back to the app, where continuing starts the service again.
 */
class MigrationService : Service() {
    companion object {
        private const val CHANNEL = "keepfin-setup"
        private const val ONGOING = 41
        private const val RESULT = 42

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, MigrationService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val setup get() = (application as KeepfinApplication).storageSetup
    @Volatile private var worker: Thread? = null
    @Volatile private var finished = false
    private var lastText = ""

    private fun manager() = getSystemService(NotificationManager::class.java)

    private fun channel() {
        if (Build.VERSION.SDK_INT >= 26) {
            manager().createNotificationChannel(NotificationChannel(CHANNEL, "Library setup", NotificationManager.IMPORTANCE_LOW).apply { description = "Progress while Keepfin brings your library into its folder." })
        }
    }

    private fun open(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun build(title: String, text: String, percent: Int?, ongoing: Boolean): Notification = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification).setColor(0xff5279ee.toInt()).setContentTitle(title).setContentText(text).setContentIntent(open())
        .setOngoing(ongoing).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_PROGRESS).setPriority(NotificationCompat.PRIORITY_LOW)
        .also { if (ongoing) it.setProgress(100, percent ?: 0, percent == null) }.build()

    private val ticker = object : Runnable {
        override fun run() {
            if (finished) return
            val p = setup.ui.value.progress
            val percent = p?.let { if (it.total > 0) (it.done * 100L / it.total).toInt().coerceIn(0, 100) else null }
            val text = if (p == null) "Getting ready" else "${p.done} of ${p.total} files"
            if (text != lastText) {
                lastText = text
                manager().notify(ONGOING, build("Bringing your library over", text, percent, true))
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channel()
        try {
            ServiceCompat.startForeground(this, ONGOING, build("Bringing your library over", "Getting ready", null, true), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (e: RuntimeException) {
            // Android refused a start (the time limit passed while Keepfin was in the background): the screen starts it again when it is in front.
            setup.serviceRefused()
            stopSelf()
            return START_NOT_STICKY
        }
        finished = false
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        if (worker?.isAlive != true) {
            worker = Thread({
                try {
                    setup.runMigration()
                } finally {
                    handler.post { done() }
                }
            }, "keepfin-migration").also { it.start() }
        }
        return START_NOT_STICKY
    }

    /** The run ended by itself: tell the user what is next, then go. */
    private fun done() {
        if (finished) return
        finished = true
        handler.removeCallbacks(ticker)
        val ui = setup.ui.value
        val note = when (ui.step) {
            SetupStep.REPORT -> "Your library is in the Keepfin folder." to "Open Keepfin to see the report."
            SetupStep.CONSENT -> "Keepfin needs your OK" to "Allow it to remove the old copies of ${ui.consent?.files ?: 0} files."
            SetupStep.STOPPED -> "Setup stopped" to (ui.stopped ?: "Open Keepfin to continue.")
            else -> "Continue setup" to "Open Keepfin to carry on where it stopped."
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        manager().notify(RESULT, build(note.first, note.second, null, false))
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // The system limit for dataSync: stop the run (it checks the request on every read), keep everything journaled and say how to go on.
        setup.requestStop()
        if (finished) return
        finished = true
        handler.removeCallbacks(ticker)
        stopForeground(STOP_FOREGROUND_REMOVE)
        manager().notify(RESULT, build("Continue setup", "Open Keepfin to carry on where it stopped.", null, false))
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
