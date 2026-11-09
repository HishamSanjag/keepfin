package app.keepfin.move

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

/** A long piece of work the foreground service runs (the folder move, or bringing spaces in) so it goes on with the screen off. */
internal interface ForegroundJob {
    val title: String

    /** Blocking: returns when the work is over, stopped or failed. */
    fun runBlocking()

    /** Ends the work between two files, keeping what is done. */
    fun requestStop()

    /** The text and percent (null: unknown) of the ongoing notification. */
    fun notice(): Pair<String, Int?>

    /** The title and text of the notification left when the work ends. */
    fun finishedNotice(): Pair<String, String>
}

/**
 * Runs a [ForegroundJob] in the foreground (type dataSync), like [app.keepfin.setup.MigrationService]. Android 15 limits dataSync services; on its
 * timeout the work stops between two files (everything done is in the journal) and a notification says how to go on.
 */
class FolderMoveService : Service() {
    companion object {
        private const val CHANNEL = "keepfin-folder-move"
        private const val ONGOING = 43
        private const val RESULT = 44
        private const val KIND = "kind"

        internal fun start(context: Context, kind: String) {
            ContextCompat.startForegroundService(context, Intent(context, FolderMoveService::class.java).putExtra(KIND, kind))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var worker: Thread? = null
    @Volatile private var finished = false
    @Volatile private var job: ForegroundJob? = null
    private var lastText = ""

    private fun manager() = getSystemService(NotificationManager::class.java)

    private fun channel() {
        if (Build.VERSION.SDK_INT >= 26) {
            manager().createNotificationChannel(NotificationChannel(CHANNEL, "Moving your folder", NotificationManager.IMPORTANCE_LOW).apply { description = "Progress while Keepfin moves its folder or brings spaces into it." })
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
            val j = job
            if (finished || j == null) return
            val (text, percent) = j.notice()
            if (text != lastText) {
                lastText = text
                manager().notify(ONGOING, build(j.title, text, percent, true))
            }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as KeepfinApplication
        val chosen: ForegroundJob = if (intent?.getStringExtra(KIND) == BringInController.KIND) app.bringIn else app.folderMove
        channel()
        try {
            ServiceCompat.startForeground(this, ONGOING, build(chosen.title, "Getting ready", null, true), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (e: RuntimeException) {
            // Android refused a start (the time limit passed in the background): the screen starts it again when it is in front
            stopSelf()
            return START_NOT_STICKY
        }
        job = chosen
        finished = false
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        if (worker?.isAlive != true) {
            worker = Thread({
                try {
                    chosen.runBlocking()
                } finally {
                    handler.post { done() }
                }
            }, "keepfin-folder-move").also { it.start() }
        }
        return START_NOT_STICKY
    }

    private fun done() {
        if (finished) return
        finished = true
        handler.removeCallbacks(ticker)
        val note = job?.finishedNotice() ?: ("Keepfin" to "")
        stopForeground(STOP_FOREGROUND_REMOVE)
        manager().notify(RESULT, build(note.first, note.second, null, false))
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        job?.requestStop()
        if (finished) return
        finished = true
        handler.removeCallbacks(ticker)
        stopForeground(STOP_FOREGROUND_REMOVE)
        manager().notify(RESULT, build("Keepfin paused", "Open Keepfin to carry on where it stopped.", null, false))
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
