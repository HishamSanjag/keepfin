package app.keepfin.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.keepfin.MainActivity
import app.keepfin.R

/**
 * "Keep this phone reachable" (network spec 7): an opt-in foreground service of type connectedDevice, with a notification the person
 * can read and stop. While it runs the app keeps serving the devices that are paired with it, even when no screen is open. Without it
 * the server rests a minute after the app leaves the screen. It holds no wake lock beyond what the open connections need.
 */
class ReachableService : Service() {
    companion object {
        private const val CHANNEL = "keepfin-reachable"
        private const val ONGOING = 61
        const val ACTION_STOP = "app.keepfin.net.STOP_REACHABLE"

        /** Starts (on) or stops (off) the service. A start refused from the background is not an error: it starts the next time the app opens. */
        fun set(context: Context, on: Boolean) {
            try {
                if (on) ContextCompat.startForegroundService(context, Intent(context, ReachableService::class.java))
                else context.stopService(Intent(context, ReachableService::class.java))
            } catch (e: RuntimeException) {
                // Android refused a start from the background
            }
        }
    }

    private fun manager() = getSystemService(NotificationManager::class.java)

    private fun build(): Notification {
        if (Build.VERSION.SDK_INT >= 26 && manager().getNotificationChannel(CHANNEL) == null) {
            manager().createNotificationChannel(NotificationChannel(CHANNEL, "Reachable by your devices", NotificationManager.IMPORTANCE_LOW).apply { description = "Shown while this phone stays reachable for your other devices." })
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, ReachableService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification).setColor(0xff5279ee.toInt())
            .setContentTitle("Reachable by your devices").setContentText("Your other Keepfin devices can sync with this phone.")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", stop).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            NetRuntime.peek()?.setKeepReachable(false)
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(this, ONGOING, build(), if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        NetRuntime.peek()?.resume()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
