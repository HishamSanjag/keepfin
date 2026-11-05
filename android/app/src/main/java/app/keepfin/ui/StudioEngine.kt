package app.keepfin.ui

import android.content.Context
import app.keepfin.BuildConfig
import app.keepfin.DownloadService
import app.keepfin.EngineApi
import app.keepfin.KeepfinApplication

/**
 * Resolves the [EngineApi] the UI talks to. Production uses the application's engine; tests and the
 * debug-only fixture launch path install a fake through [override]. Release builds never load fixtures.
 */
object StudioEngine {
    @Volatile var override: EngineApi? = null
    /** Tests and debug fixtures replace the app updater the same way. */
    @Volatile var updatesOverride: app.keepfin.ui.updates.UpdateSource? = null

    fun updates(context: Context): app.keepfin.ui.updates.UpdateSource =
        updatesOverride ?: app.keepfin.ui.updates.productionUpdates(context)

    /** True while a fake engine is installed: no foreground service or real files are involved. */
    val isFake: Boolean get() = override != null

    fun get(context: Context): EngineApi =
        override ?: (context.applicationContext as KeepfinApplication).engineApi

    /** Debug builds can launch with fixtures for screenshots: `--ez keepfin.fixtures true`. */
    fun installDebugFixtures(context: Context, scene: String?) {
        if (!BuildConfig.DEBUG) return
        val type = Class.forName("app.keepfin.ui.preview.FakeEngine")
        val existing = override
        if (existing != null && type.isInstance(existing)) {
            type.getMethod("applyScene", String::class.java).invoke(existing, scene ?: "default")
            return
        }
        val engine = type.getMethod("fixtures", Context::class.java, String::class.java)
            .invoke(null, context.applicationContext, scene ?: "default") as EngineApi
        override = engine
    }

    /**
     * Reserves the foreground download service before committing queue work, as the service owns
     * transfer progress notifications. A denied start leaves the queue untouched.
     */
    fun <T> withServiceWork(context: Context, work: () -> T): T {
        if (isFake) return work()
        return DownloadService.beginWork(context.applicationContext).use { work() }
    }
}
