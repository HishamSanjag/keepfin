package app.keepfin.ui.model

import app.keepfin.ActivityAction
import app.keepfin.ActivityEntry
import app.keepfin.EngineApi
import app.keepfin.JobStatus
import app.keepfin.LibraryApi
import app.keepfin.UpdateSnapshot
import app.keepfin.ui.UiPreferences
import app.keepfin.ui.Destination
import app.keepfin.ui.SubPage
import app.keepfin.ui.updates.UpdateSource
import app.keepfin.ui.updates.hasUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** What [UpdatesFlow] uses of the ViewModel: the update state and the Compose state it sets, and the other flows' actions the Activity buttons run. */
internal interface UpdatesFlowHost : Notices {
    val scope: CoroutineScope
    val engine: EngineApi
    val lib: LibraryApi
    val prefs: UiPreferences
    val updates: UpdateSource
    val updateState: UpdateSnapshot
    var updateSheet: Boolean
    var whatsNew: String?
    var laterVersion: String?
    fun navigate(target: Destination)
    fun open(page: SubPage)
    fun openFromNotification(page: String, job: String?)
    fun showCollection(id: String)
    fun reviewCollection(id: String, count: Int)
    fun retry(id: String)
    fun retryGroupFailed(id: String)
}

/** The app update prompts (dialog, banner, sheet, channel, checking) and the Activity list's reading, removing and buttons. */
internal class UpdatesFlow(private val host: UpdatesFlowHost) {
    val updateAvailable: Boolean get() = host.updateState.hasUpdate(host.updates.currentVersion)

    /** The release to announce in the one-time dialog, or null. Waits for What's new to close. */
    val updateDialogVersion: String? get() {
        val state = host.updateState
        if (state.phase != "available" || !updateAvailable || state.version.isEmpty()) return null
        // A skip made on another channel never hides what this channel offers.
        if ((state.version == host.prefs.skippedUpdateVersion && host.prefs.skippedUpdateChannel == host.prefs.appChannel.wire) || state.version == host.laterVersion) return null
        if (host.whatsNew != null || host.updateSheet) return null
        return state.version
    }
    /** The Downloads banner shows while a newer release exists and the user has not hidden it for that version. */
    val updateBannerVersion: String? get() {
        val version = host.updateState.version
        return version.takeIf { updateAvailable && it.isNotEmpty() && !(it == host.prefs.hiddenBannerVersion && host.prefs.hiddenBannerChannel == host.prefs.appChannel.wire) }
    }
    fun updateLater() { host.laterVersion = host.updateState.version }
    fun skipUpdate() { host.prefs.skipUpdateVersion(host.updateState.version) }
    fun hideUpdateBanner() { updateBannerVersion?.let(host.prefs::hideUpdateBanner) }
    /** Update now: opens the update sheet and starts downloading, the same flow as the sheet's Download button. */
    fun updateNow() {
        host.updateSheet = true
        host.scope.launch(Dispatchers.IO) { runCatching { host.updates.download() } }
    }

    fun showUpdateSheet() { host.updateSheet = true }
    fun hideUpdateSheet() { host.updateSheet = false }
    fun showWhatsNew() { host.whatsNew = host.updates.currentVersion }
    fun dismissWhatsNew() { host.whatsNew = null }

    /** Switches between Stable and Beta app releases and checks again. */
    fun chooseAppChannel(channel: app.keepfin.UpdateChannel) {
        if (channel == host.prefs.appChannel) return
        host.prefs.chooseAppChannel(channel)
        host.laterVersion = null
        host.updates.switchChannel(channel)
    }
    fun checkForUpdates() { host.scope.launch(Dispatchers.IO) { runCatching { host.updates.check() } } }
    /** Settings' button: an update already downloading or downloaded reopens its sheet, since a check skips it. */
    fun checkOrShowUpdate() { if (host.updateState.phase in setOf("downloading", "ready")) showUpdateSheet() else checkForUpdates() }

    /** Marks entries read; the page calls this for everything it shows. */
    fun activityRead(ids: Collection<String>) { host.scope.launch(Dispatchers.IO) { runCatching { host.engine.activity.markRead(ids) } } }
    fun activityReadAll() { host.scope.launch(Dispatchers.IO) { runCatching { host.engine.activity.markAllRead() } } }
    fun activityRemove(id: String) { host.scope.launch(Dispatchers.IO) { runCatching { host.engine.activity.remove(id) } } }

    /** Runs an entry's button with the existing navigation. A target that no longer exists says so instead of failing silently. */
    fun activityAction(entry: ActivityEntry, action: ActivityAction) {
        val t = entry.target
        val jobs = host.engine.jobs.value
        val collection = t.collectionId?.let { id -> host.lib.library.value.collections.firstOrNull { it.id == id } }
        val groupJobs = t.groupId?.let { id -> jobs.filter { it.group?.id == id } }.orEmpty()
        val targetJobs = t.jobIds.mapNotNull { id -> jobs.firstOrNull { it.id == id } }
        when (action) {
            ActivityAction.OPEN, ActivityAction.DETAILS -> when {
                collection != null && action == ActivityAction.OPEN -> host.showCollection(collection.id)
                groupJobs.isNotEmpty() -> host.openFromNotification("Downloads", "group:${t.groupId}")
                targetJobs.isNotEmpty() -> host.openFromNotification("Downloads", targetJobs.first().id)
                else -> host.say(if (t.collectionId != null && action == ActivityAction.OPEN) "That collection is gone" else "Those downloads are gone")
            }
            ActivityAction.REVIEW -> {
                val pending = collection?.follow?.pending
                when {
                    collection == null -> host.say("That collection is gone")
                    pending != null -> host.reviewCollection(collection.id, pending.count)
                    else -> host.showCollection(collection.id)
                }
            }
            ActivityAction.RETRY -> {
                val failed = (groupJobs.ifEmpty { targetJobs }).filter { it.status == JobStatus.FAILED || (it.status == JobStatus.COMPLETED && it.saveError != null) }
                when {
                    groupJobs.isEmpty() && targetJobs.isEmpty() -> host.say("Those downloads are gone")
                    failed.isEmpty() -> host.say("Nothing left to retry")
                    groupJobs.isNotEmpty() -> host.retryGroupFailed(t.groupId.orEmpty())
                    else -> failed.forEach { host.retry(it.id) }
                }
            }
            ActivityAction.WHATS_NEW -> showWhatsNew()
            ActivityAction.INSTALL -> if (host.updateState.phase in setOf("ready", "available", "downloading")) showUpdateSheet() else host.say("That update is no longer available")
            ActivityAction.SIGN_IN -> { host.navigate(Destination.SETTINGS); host.open(SubPage.SIGN_IN) }
        }
    }
}
