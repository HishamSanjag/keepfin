package app.keepfin

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import app.keepfin.net.NetRuntime
import app.keepfin.ui.Destination
import app.keepfin.ui.Links
import app.keepfin.ui.StudioApp
import app.keepfin.ui.StudioEngine
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.SubPage
import app.keepfin.ui.devices.ServiceDevicesController
import app.keepfin.ui.settings.SettingsCategory
import app.keepfin.ui.settings.SettingsGroups
import app.keepfin.ui.settings.SettingsTarget
import app.keepfin.ui.theme.ThemeMode

/**
 * Hosts the Compose Studio UI. Single task: shared links and notification taps arrive through
 * [onNewIntent] and update the existing screen. A shared link is inspected automatically; downloading
 * always needs an explicit tap.
 */
class MainActivity : ComponentActivity() {
    private val vm: StudioViewModel by viewModels { StudioViewModel.factory(application) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG && intent.getBooleanExtra(EXTRA_FIXTURES, false)) {
            StudioEngine.installDebugFixtures(this, intent.getStringExtra(EXTRA_SCENE))
            intent.getStringExtra(EXTRA_UPDATE)?.let { scene ->
                StudioEngine.updatesOverride = Class.forName("app.keepfin.ui.preview.FakeUpdates")
                    .getMethod("scene", String::class.java).invoke(null, scene) as app.keepfin.ui.updates.UpdateSource
            }
        }
        // A recreated activity keeps its draft and results in the ViewModel; only fresh intents are handled.
        // Recents (and the launcher, after the process ended) recreate the activity with the share that first opened it:
        // that share was already handled, so it must not be inspected again by itself.
        if (savedInstanceState == null) handle(intent, fresh = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0)
        setContent { StudioApp(vm) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent, fresh = true)
    }

    private fun handle(intent: Intent, fresh: Boolean) {
        if (intent.action == Intent.ACTION_SEND && fresh) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            intent.removeExtra(Intent.EXTRA_TEXT)
            when {
                Links.extract(text).isEmpty() -> if (text.isNotBlank()) vm.say("No link found in the shared text.")
                // Keepfin stopped last time while this link was open: it waits in the link box for a tap instead of stopping again.
                CrashLog.stoppedOn(this, text) -> vm.holdLink(text)
                else -> { CrashLog.noteShared(this, text); vm.loadLink(text, fromShare = true) }
            }
        }
        if (fresh) handlePairLink(intent)
        intent.getStringExtra(EXTRA_NOTIFICATION_PAGE)?.let { page ->
            vm.openFromNotification(page, intent.getStringExtra(EXTRA_NOTIFICATION_JOB))
            intent.removeExtra(EXTRA_NOTIFICATION_PAGE)
            intent.removeExtra(EXTRA_NOTIFICATION_JOB)
        }
        if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) {
            intent.removeExtra(EXTRA_OPEN_PLAYER)
            vm.openCurrentPlayer()
        }
        if (BuildConfig.DEBUG) debugExtras(intent)
    }

    /**
     * The phone's camera app opened a pairing code (`keepfin://pair?...`): show Settings > Devices and start the same pairing Keepfin's
     * own scanner starts. Devices are never turned on here; when they are off the person is told so and can turn them on.
     */
    private fun handlePairLink(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        val data = intent.data ?: return
        if (data.scheme != "keepfin" || data.host != "pair") return
        intent.data = null // handled once, not again when the activity is recreated
        vm.openSettings(SettingsTarget(SettingsCategory.DEVICES, SettingsGroups.DEVICES))
        if (!NetRuntime.available) return
        val link = data.toString()
        Thread {
            val message = ServiceDevicesController(application, vm.engine).beginAsk(link)
            if (message != null) runOnUiThread { vm.say(message) }
        }.also { it.isDaemon = true }.start()
    }

    /** Debug-only launch extras for reproducible screenshots (theme, destination, sub-page, link). */
    private fun debugExtras(intent: Intent) {
        intent.getStringExtra(EXTRA_THEME)?.let { vm.prefs.chooseTheme(ThemeMode.of(it)) }
        intent.getStringExtra(EXTRA_PAGE)?.let { page ->
            Destination.entries.firstOrNull { it.name.equals(page, true) }?.let(vm::navigate)
            when (page.lowercase()) {
                "about" -> vm.openSettings(SettingsTarget(SettingsCategory.ABOUT))
                "support" -> { vm.navigate(Destination.SETTINGS); vm.open(SubPage.SUPPORT) }
                "signin" -> { vm.navigate(Destination.SETTINGS); vm.open(SubPage.SIGN_IN) }
            }
        }
        intent.getStringExtra(EXTRA_SHEET)?.let { sheet ->
            when (sheet) {
                "update" -> vm.updatesFlow.showUpdateSheet()
                "whatsnew" -> vm.updatesFlow.showWhatsNew()
            }
        }
        intent.getStringExtra(EXTRA_LINK)?.let { vm.loadLink(it) }
        intent.getStringExtra(EXTRA_PLAYLIST)?.let { vm.collections.openPlaylist(it) }
    }

    companion object {
        /** Extras set by DownloadNotifications' content intents. */
        const val EXTRA_NOTIFICATION_PAGE = "notificationPage"
        const val EXTRA_NOTIFICATION_JOB = "notificationJob"
        /** Set by the media session activity: show the player of whatever is playing (full music player, or the video player). */
        const val EXTRA_OPEN_PLAYER = "openPlayer"
        private const val EXTRA_FIXTURES = "keepfin.fixtures"
        private const val EXTRA_SCENE = "keepfin.scene"
        private const val EXTRA_UPDATE = "keepfin.update"
        private const val EXTRA_SHEET = "keepfin.sheet"
        private const val EXTRA_THEME = "keepfin.theme"
        private const val EXTRA_PAGE = "keepfin.page"
        private const val EXTRA_LINK = "keepfin.link"
        private const val EXTRA_PLAYLIST = "keepfin.playlist"
    }
}
