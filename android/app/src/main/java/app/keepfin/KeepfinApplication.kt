package app.keepfin
import android.app.Application
class KeepfinApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        Thread { ExitReports.record(this) }.also { it.isDaemon = true }.start()
        LyricsLookup.versionName = BuildConfig.VERSION_NAME
        registerActivityLifecycleCallbacks(foregroundTracker)
        recordAppUpdate()
        // Make the launcher icon match the stored choice (an update or restore can reset the component states).
        AppIcons.reconcile(this)
        // Hourly check of followed playlists (WorkManager keeps one schedule across restarts).
        FollowScheduler.schedule(this)
    }

    internal val notifications by lazy { DownloadNotifications(this) }
    /** Typed Studio 2 engine used by the Compose UI, service and notifications. */
    val engineApi: EngineApi by lazy { MediaEngine(this).also { engineCreated = true } }

    /** False while the engine is being built (the library is opened inside its constructor): merge recovery then works on files only. */
    @Volatile var engineCreated: Boolean = false
        private set
    val updates by lazy { AppUpdates(this) }

    /** First start and setup of an earlier library (the Keepfin folder). */
    internal val storageSetup: app.keepfin.setup.StorageSetup by lazy { app.keepfin.setup.StorageSetup(this, engineApi as app.keepfin.setup.SetupEngine) }

    /** The Activity list, shared by the engine, the follow check and the app updater (dev-docs/architecture/ACTIVITY.md). */
    val activity: ActivityStore by lazy { ActivityStore(java.io.File(filesDir, "activity.json")) }

    /** Tells [AppForeground] whether any screen is in front, so Activity can tell "while you were away" from "while you watched". */
    private val foregroundTracker = object : ActivityLifecycleCallbacks {
        private var started = 0
        override fun onActivityStarted(activity: android.app.Activity) { started++; AppForeground.visible = true; devicesForeground(true) }
        override fun onActivityStopped(activity: android.app.Activity) { started = (started - 1).coerceAtLeast(0); AppForeground.visible = started > 0; if (started == 0) { devicesForeground(false); publishLibrary() } }
        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
        override fun onActivityResumed(activity: android.app.Activity) {}
        override fun onActivityPaused(activity: android.app.Activity) {}
        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
        override fun onActivityDestroyed(activity: android.app.Activity) {}
    }

    /** Devices on the network (Keepfin folder build): starts the service when it was on, and lets it rest while no screen is open. */
    private fun devicesForeground(visible: Boolean) {
        if (!app.keepfin.net.NetRuntime.available) return
        if (visible) Thread { app.keepfin.net.NetRuntime.startIfNeeded(this, engineApi); app.keepfin.net.NetRuntime.foreground(true) }.also { it.isDaemon = true }.start()
        else app.keepfin.net.NetRuntime.foreground(false)
    }

    /**
     * No screen is in front: whatever the library has not published yet is written to the Keepfin folder now (flushes wait up to 30 s while the
     * app is used, and the process may be stopped soon after). Does nothing before the engine exists.
     */
    private fun publishLibrary() {
        if (!engineCreated) return
        Thread { runCatching { (engineApi as? MediaEngine)?.folderLibrary?.onBackground() } }.also { it.isDaemon = true }.start()
    }

    /** The first start of a new version leaves one "Keepfin updated to X" entry; a fresh install records nothing. */
    private fun recordAppUpdate() {
        try {
            val prefs = getSharedPreferences("activity", MODE_PRIVATE)
            val current = BuildConfig.VERSION_NAME
            // Before this list existed the UI remembered the last version it saw; that answers the very first comparison.
            val previous = prefs.getString("lastVersion", null) ?: getSharedPreferences("studio-ui", MODE_PRIVATE).getString("lastSeenVersion", null)
            if (previous == current) return
            prefs.edit().putString("lastVersion", current).apply()
            if (previous != null && runCatching { UpdateRelease.newer(current, previous) }.getOrDefault(false)) {
                val note = runCatching { app.keepfin.ui.updates.updateHighlights(app.keepfin.ui.settings.ReleaseNotes.bundledNotes(this), 1).firstOrNull() }.getOrNull()
                activity.recordAppUpdated(current, note)
            }
        } catch (error: Exception) { android.util.Log.w("KeepfinActivity", "app-updated not recorded", error) }
    }
}
