package app.keepfin

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Background scheduling of the follow check (dev-docs/architecture/SPACES-V2.md):
 *  - [PERIODIC]: WorkManager periodic work, 1 hour, network required. [FollowScanWorker] only looks at what is due; it does no download work.
 *  - [FollowRunWorker]: expedited one-time work that does the checks and keeps itself a foreground (dataSync) service while the queued downloads run.
 *    Android lets expedited work start a foreground service from the background; a plain app process cannot start [DownloadService] there.
 *  - "Check now" is the same run worker for one collection, so it behaves identically with the app open or closed.
 */
object FollowScheduler {
    const val PERIODIC = "keepfin-follow-periodic"
    const val RUN = "keepfin-follow-run"
    const val KEY_ID = "id"
    const val KEY_REVIEW = "review"
    private const val PREFS = "follow"
    private const val ACTIVE = "active"
    private const val ARTISTS = "artists"

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Registers the hourly scan (kept if it already exists). Cheap: safe to call on every start. */
    fun schedule(context: Context) {
        try {
            val request = PeriodicWorkRequestBuilder<FollowScanWorker>(1, TimeUnit.HOURS).setConstraints(network)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        } catch (error: Exception) { Log.w(TAG, "follow scan not scheduled", error) }
    }

    /** "Check now" / "Review" for [id], or the due collections when null. One-time, expedited, and a second tap while one runs is ignored. */
    fun checkNow(context: Context, id: String?, review: Boolean) {
        try {
            val request = OneTimeWorkRequestBuilder<FollowRunWorker>().setConstraints(network).setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(Data.Builder().apply { if (id != null) putString(KEY_ID, id); putBoolean(KEY_REVIEW, review) }.build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork(if (id != null) "$RUN-$id" else RUN, ExistingWorkPolicy.KEEP, request)
        } catch (error: Exception) { Log.w(TAG, "follow check not queued", error) }
    }

    /** Whether any collection follows automatically: the scan skips starting the engine when nothing does. Written by the library on every save. */
    fun remember(context: Context, active: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ACTIVE, active).apply()
    }

    /** "Check now" for one followed artist, or all of them when null. */
    fun checkArtists(context: Context, id: String?, manual: Boolean = id != null) {
        try {
            val request = OneTimeWorkRequestBuilder<FollowArtistsWorker>().setConstraints(network)
                .setInputData(Data.Builder().apply { if (id != null) putString(KEY_ID, id); putBoolean("manual", manual) }.build()).build()
            WorkManager.getInstance(context).enqueueUniqueWork(if (id != null) "keepfin-artists-$id" else if (manual) "keepfin-artists-all" else "keepfin-artists", ExistingWorkPolicy.KEEP, request)
        } catch (error: Exception) { Log.w(TAG, "artist check not queued", error) }
    }

    /** Whether any artist is followed: the hourly scan then also looks at the artists. */
    fun rememberArtists(context: Context, any: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ARTISTS, any).apply()
    }

    internal fun anyArtists(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ARTISTS, false)

    internal fun anyActive(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ACTIVE, false)

    private const val TAG = "KeepfinFollow"
}

private fun Context.engine(): MediaEngine? = (applicationContext as? KeepfinApplication)?.engineApi as? MediaEngine

/** Hourly: starts a run only when a collection is due. */
class FollowScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val artists = FollowScheduler.anyArtists(applicationContext)
        if (!FollowScheduler.anyActive(applicationContext) && !artists) return Result.success()
        val engine = applicationContext.engine() ?: return Result.success()
        if (artists && withContext(Dispatchers.IO) { engine.artistFollowing.due().isNotEmpty() }) FollowScheduler.checkArtists(applicationContext, null)
        if (!FollowScheduler.anyActive(applicationContext)) return Result.success()
        val due = withContext(Dispatchers.IO) { CollectionLogic.due(engine.collectionsNow(), System.currentTimeMillis(), engine.follow.metered()) }
        if (due.isNotEmpty()) FollowScheduler.checkNow(applicationContext, null, false)
        return Result.success()
    }
}

/** Does the checks and holds a foreground service until the downloads it queued are done (or [MAX_WAIT_MS] passes). */
class FollowRunWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun getForegroundInfo(): ForegroundInfo = info("Checking your followed playlists")

    private fun info(text: String): ForegroundInfo {
        val notification = (applicationContext.applicationContext as KeepfinApplication).notifications.followProgress(text)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(NOTIFICATION_ID, notification)
    }

    override suspend fun doWork(): Result {
        val engine = applicationContext.engine() ?: return Result.success()
        val id = inputData.getString(FollowScheduler.KEY_ID)
        val review = inputData.getBoolean(FollowScheduler.KEY_REVIEW, false)
        // A denied foreground start (quota used up, restricted device) only costs the background guarantee: the check still runs.
        val foreground = try { setForeground(info("Checking your followed playlists")); true } catch (error: Exception) { Log.w(TAG, "foreground not allowed: ${error.message}"); false }
        val outcome = withContext(Dispatchers.IO) { if (id != null) engine.follow.check(id, review, manual = true) else engine.follow.checkDue() }
        Log.i(TAG, "follow run: checked=${outcome.checked} queued=${outcome.queued.size} held=${outcome.held} errors=${outcome.errors} foreground=$foreground")
        if (outcome.queued.isEmpty()) return Result.success()
        val queued = outcome.queued.toSet()
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        while (coroutineContext.isActive && System.currentTimeMillis() < deadline) {
            val mine = engine.jobs.value.filter { it.id in queued }
            val running = mine.filter { it.status.active }
            if (running.isEmpty()) break
            if (foreground) try {
                val done = mine.count { it.status == JobStatus.COMPLETED }
                setForeground(info("Downloading new items · $done of ${mine.size}"))
            } catch (_: Exception) { }
            delay(POLL_MS)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "KeepfinFollow"
        private const val NOTIFICATION_ID = 7
        private const val POLL_MS = 3_000L
        /** Longer than any single run should take; Android 15 caps dataSync services at about six hours a day anyway. */
        private const val MAX_WAIT_MS = 2 * 60 * 60 * 1000L
    }
}

/** Checks followed artists for new releases: one artist (Check now), or the ones that are due. Short, so it needs no foreground service. */
class FollowArtistsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val engine = applicationContext.engine() ?: return Result.success()
        val id = inputData.getString(FollowScheduler.KEY_ID)
        val found = withContext(Dispatchers.IO) { engine.artistFollowing.check(id, manual = inputData.getBoolean("manual", id != null)) }
        Log.i("KeepfinFollow", "artist run: new releases=$found")
        return Result.success()
    }
}
