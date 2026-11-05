package app.keepfin.merge

import android.app.Application
import android.content.Context
import app.keepfin.KeepfinApplication

/** The one [MergeService] of the app, and the two recovery hooks (before the library opens, after it did). */
object MergeRuntime {
    @Volatile private var value: MergeService? = null

    @Synchronized fun service(app: Application): MergeService = value ?: MergeService(AndroidMergeEnv(app)).also { value = it }

    /** Tests stand a service in. */
    internal fun installForTests(service: MergeService?) { value = service }

    /** Called by the library selector before it reads the profile: unfinished merges fence their libraries and finish the switch. */
    fun recoverBeforeOpen(context: Context) {
        val app = context.applicationContext as? Application ?: return
        if (!app.getFileStreamPath("merges").isDirectory) return
        try { service(app).recoverBeforeOpen() } catch (e: Throwable) { android.util.Log.w("KeepfinMerge", "recovery before open stopped", e) }
    }

    /** Called when the engine is built: resumes the stages that need the library, and cleans up combines past their 30 days. */
    fun afterOpen(context: Context) {
        val app = context.applicationContext as? KeepfinApplication ?: return
        if (!app.getFileStreamPath("merges").isDirectory) return
        Thread {
            var waited = 0
            while (!app.engineCreated && waited++ < 600) Thread.sleep(50)
            try { service(app).also { it.recoverAfterOpen(); it.cleanupDue() } } catch (e: Throwable) { android.util.Log.w("KeepfinMerge", "recovery after open stopped", e) }
        }.also { it.isDaemon = true }.start()
    }
}
