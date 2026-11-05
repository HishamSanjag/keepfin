package app.keepfin.net

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.keepfin.MainActivity
import app.keepfin.R

/**
 * "{device} wants to pair with this phone": a high-priority notification while another device is asking and Keepfin is not on screen
 * (it is open in the background, or "Keep this phone reachable" runs it). Tapping opens Keepfin, where the compare dialog shows. It is
 * cancelled as soon as the pairing ends.
 */
object PairingNotifier {
    private const val CHANNEL = "keepfin-devices"
    private const val ID = 62

    /** The pairing is waiting for this person (numbers to compare, or a scanned code to allow). */
    fun needsPerson(p: PairingUi?): Boolean = p != null && p.role == "answering" && !p.autoApproved && (p.stage == "comparing" || p.stage == "approving")

    /** Shown whenever a person is needed and Keepfin is in the background, including for an unrequested request; never for a scanned code (auto-approved). */
    fun update(context: Context, pairing: PairingUi?, foreground: Boolean, now: Long = System.currentTimeMillis()) {
        if (needsPerson(pairing) && !foreground) show(context, pairing!!, now) else cancel(context)
    }

    private fun show(context: Context, pairing: PairingUi, now: Long) {
        val peerName = pairing.peerName
        try {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Devices", NotificationManager.IMPORTANCE_HIGH).apply { description = "Asks you to compare numbers when another device wants to pair with this phone." })
            }
            val open = PendingIntent.getActivity(
                context, ID, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notice = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification).setColor(0xff5279ee.toInt())
                .setContentTitle(if (pairing.unrequested) "$peerName wants to connect" else "$peerName wants to pair with this phone")
                .setContentText(if (pairing.unrequested) "Tap to compare the codes" else "Tap to compare the numbers")
                .setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_STATUS)
                // it goes when the session does: 60 seconds for an unrequested request, two minutes otherwise
                .setTimeoutAfter((pairing.expiresAt - now).coerceIn(1_000L, PAIR_SESSION_MS)).build()
            manager.notify(ID, notice)
        } catch (e: RuntimeException) {
            // no notification is better than a failed pairing
        }
    }

    fun cancel(context: Context) {
        try {
            context.getSystemService(NotificationManager::class.java)?.cancel(ID)
        } catch (e: RuntimeException) {
            // nothing to cancel
        }
    }
}
