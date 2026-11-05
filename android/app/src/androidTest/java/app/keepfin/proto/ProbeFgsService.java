package app.keepfin.proto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

/** Probe 5: a dataSync foreground service that records when onTimeout(startId, fgsType) arrives. Host-driven (no test class). Java so the test APK needs no Kotlin runtime. */
public class ProbeFgsService extends Service {
    private static final String TAG = "KeepfinProbe5";
    private long started = 0L;
    private String mode = "obey";

    @Override public IBinder onBind(Intent intent) { return null; }

    private Notification note(String title) {
        return new Notification.Builder(this, "probe5").setSmallIcon(android.R.drawable.stat_sys_download).setContentTitle(title).build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        mode = intent != null && intent.getStringExtra("mode") != null ? intent.getStringExtra("mode") : "obey";
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("probe5", "probe5", NotificationManager.IMPORTANCE_LOW));
        try {
            startForeground(5, note("probe5"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            started = SystemClock.elapsedRealtime();
            Log.i(TAG, "started foreground dataSync, mode=" + mode + ", startId=" + startId);
        } catch (Exception e) {
            Log.i(TAG, "startForeground FAILED " + e.getClass().getName() + ": " + e.getMessage());
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override public void onTimeout(int startId, int fgsType) {
        long after = SystemClock.elapsedRealtime() - started;
        Log.i(TAG, "onTimeout(startId=" + startId + ", fgsType=" + fgsType + ") delivered " + after + " ms after start; mode=" + mode);
        if (mode.equals("obey")) { stopSelf(); Log.i(TAG, "called stopSelf()"); }
        else if (mode.equals("restart")) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            try {
                startForeground(5, note("probe5b"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
                Log.i(TAG, "re-startForeground after onTimeout SUCCEEDED");
            } catch (Exception e) { Log.i(TAG, "re-startForeground after onTimeout FAILED " + e.getClass().getName() + ": " + e.getMessage()); }
        } else Log.i(TAG, "ignoring the timeout on purpose");
    }

    @Override public void onDestroy() { Log.i(TAG, "onDestroy after " + (SystemClock.elapsedRealtime() - started) + " ms"); super.onDestroy(); }
}
