package app.keepfin.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.keepfin.ActivityAction
import app.keepfin.PlaybackItems
import app.keepfin.ActivityEntry
import app.keepfin.ActivityFeed
import app.keepfin.DownloadOptions
import app.keepfin.DownloadPreset
import app.keepfin.EngineApi
import app.keepfin.EngineSettings
import app.keepfin.FollowUpdate
import app.keepfin.InspectResult
import app.keepfin.CollectionLogic
import app.keepfin.FormatRequest
import app.keepfin.JobStatus
import app.keepfin.PhotoPosts
import app.keepfin.SignInNeeded
import app.keepfin.Providers
import app.keepfin.MediaInfo
import app.keepfin.PlaylistEntry
import app.keepfin.UpdateSnapshot
import app.keepfin.ExistingMatch
import app.keepfin.LibraryApi
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.Space
import app.keepfin.TagEdit
import app.keepfin.ui.library.ExternalOpen
import app.keepfin.ui.library.HomeSection
import app.keepfin.ui.library.ImageRequest
import app.keepfin.ui.library.LibraryRoute
import app.keepfin.ui.library.MusicTab
import app.keepfin.ui.library.PendingAccess
import app.keepfin.ui.library.VideoRequest
import app.keepfin.ui.model.CollectionFlow
import app.keepfin.ui.model.CollectionFlowHost
import app.keepfin.ui.model.JobActions
import app.keepfin.ui.model.JobActionsHost
import app.keepfin.ui.model.LibraryActions
import app.keepfin.ui.model.LibraryActionsHost
import app.keepfin.ui.model.SongVideoFlow
import app.keepfin.ui.model.SongVideoFlowHost
import app.keepfin.ui.model.SpaceActions
import app.keepfin.ui.model.SpaceActionsHost
import app.keepfin.ui.model.UpdatesFlow
import app.keepfin.ui.model.UpdatesFlowHost
import app.keepfin.ui.player.ControllerPlayerPort
import app.keepfin.ui.player.PlayerPort
import app.keepfin.ui.player.SimulatedPlayerPort
import app.keepfin.ui.settings.SettingsAnchor
import app.keepfin.ui.settings.SettingsCategory
import app.keepfin.ui.settings.SettingsGroups
import app.keepfin.ui.settings.SettingsRedirect
import app.keepfin.ui.settings.SettingsTarget
import app.keepfin.ui.updates.UpdateSource
import app.keepfin.ui.updates.isNewer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * The top-level pages. Full mode shows Home, Search, Library and Downloads in the bar; Settings opens from the top bar.
 * Downloader-only mode shows Downloads and Settings; Home and Search cannot be opened ([needsLibrary]). LIBRARY with the library off is
 * the plain download history (reached from Downloads, never from the bar).
 */
enum class Destination(val label: String, val needsLibrary: Boolean = false) {
    HOME("Home", true), SEARCH("Search", true), LIBRARY("Library"), DOWNLOADS("Downloads"), SETTINGS("Settings");
}
enum class SubPage { SIGN_IN, SUPPORT, GUIDE, ACTIVITY }

/** Where Home's box sent what was typed or pasted. */
enum class BoxRoute { NOTHING, CAPTURE, SEARCH }

/** Filters of the Downloads job list. Finished is the old History view. */
enum class DownloadsFilter(val label: String) { ALL("All"), ACTIVE("Active"), FINISHED("Finished"), FAILED("Failed") }

/** What the capture area is showing. Immutable snapshots; a new link always produces a new value. */
sealed interface Capture {
    data object Empty : Capture
    data class Inspecting(val urls: List<String>, val done: Int = 0) : Capture
    data class Single(val media: MediaInfo) : Capture
    data class Batch(val items: List<BatchItem>, val running: Boolean) : Capture
    /** [signInSite]: the site (for example "Instagram") that shows this link only to signed-in people; the preview then offers to sign in. */
    data class Failed(val url: String, val message: String, val signInSite: String? = null, val signInKey: String? = null) : Capture
}

data class BatchItem(val url: String, val media: MediaInfo? = null, val error: String? = null)

data class UiMessage(
    val text: String,
    val actionLabel: String? = null,
    val action: (() -> Unit)? = null,
    /** A second button, for "Saved to Music" with Play and Show in Library. */
    val secondLabel: String? = null,
    val secondAction: (() -> Unit)? = null,
    /** Runs once when the notice goes without its button being pressed (its time ran out, it was closed or a newer one replaced it). */
    val onGone: (() -> Unit)? = null,
)

/** A collection under review. Entries stream in; selection is keyed by entry URL and survives recreation. */
@Stable
class CollectionReview(val url: String, val session: String, initialTitle: String?) {
    var title by mutableStateOf(initialTitle)
    val entries = mutableStateListOf<PlaylistEntry>()
    val selected = mutableStateMapOf<String, Boolean>()
    var discovering by mutableStateOf(false)
    var truncated by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var nextStart by mutableIntStateOf(1)
    var preset by mutableStateOf(DownloadPreset.BEST)
    var adding by mutableStateOf(false)
    /** Music source: audio placeholders and an audio default quality. */
    var music by mutableStateOf(false)
    /** "Also keep music videos where there is one": starts as the "Keep music videos automatically" setting; only used while the collection offers it. */
    var findVideo by mutableStateOf(false)
    /** "Keep together" (true) or "Separate items" (false); remembered through [UiPreferences]. */
    var keepTogether by mutableStateOf(true)
    /** Space the user picked with Save to; null = the smart default for this link. */
    var space by mutableStateOf<String?>(null)
    /** "Add to playlist" for this review: a playlist's id; null = none. Belongs to the review, not a setting. */
    var playlist by mutableStateOf<String?>(null)
    /** "Keep in sync": check this playlist for new songs and download them. Off until the user turns it on; options show only while on. */
    var sync by mutableStateOf(false)
    var syncHours by mutableIntStateOf(6)
    var syncWifiOnly by mutableStateOf(true)
    /** The extras for this collection's downloads: they start as the person's defaults. */
    var options by mutableStateOf(DownloadOptions())
    /** Entry URLs whose recording is already in the target space: deselected by default and badged. */
    val existing = androidx.compose.runtime.mutableStateSetOf<String>()
    /**
     * Albums and playlists start with every entry selected, including entries that arrive later, until the user changes
     * the selection (owner feedback 2026-10-12). Channels and endless radio mixes stay a pick-what-you-want list.
     */
    internal var autoSelect = collectionKind(url).let { it != "channel" && it != "mix" }
    internal var worker: Job? = null
    val selectedCount: Int get() = entries.count { selected[it.url] == true }
}

/** The "Get the music video" sheet: [lookup] is null while Keepfin is looking. */
data class MusicVideoSheet(val songId: String, val title: String, val lookup: app.keepfin.MusicVideoLookup?)

/** The Capture offer of a song's music video: [lookup] null while Keepfin looks; [keep] is the check box. */
data class CaptureVideo(val lookup: app.keepfin.MusicVideoLookup?, val keep: Boolean)

class StudioViewModel(
    application: Application,
    val engine: EngineApi,
    private val saved: SavedStateHandle,
    val prefs: UiPreferences = UiPreferences(application),
    val updates: UpdateSource = StudioEngine.updates(application),
    val player: PlayerPort = defaultPlayer(application, engine),
) : AndroidViewModel(application) {

    val lib: LibraryApi get() = engine.libraryApi

    /** What the flows in ui/model may use of this ViewModel: its own members, forwarded. Each flow takes only its own interface of this. */
    private interface FlowHosts : LibraryActionsHost, CollectionFlowHost, SpaceActionsHost, JobActionsHost, UpdatesFlowHost, SongVideoFlowHost
    private val host: FlowHosts = object : FlowHosts {
        override val scope: CoroutineScope get() = this@StudioViewModel.viewModelScope
        override val engine: EngineApi get() = this@StudioViewModel.engine
        override val lib: LibraryApi get() = this@StudioViewModel.lib
        override val prefs: UiPreferences get() = this@StudioViewModel.prefs
        override val updates: UpdateSource get() = this@StudioViewModel.updates
        override val _messages: MutableSharedFlow<UiMessage> get() = this@StudioViewModel._messages

        override val libraryEnabled: Boolean get() = this@StudioViewModel.libraryEnabled
        override val libraryRoute: LibraryRoute get() = this@StudioViewModel.libraryRoute
        override val capture: Capture get() = this@StudioViewModel.capture
        override var collection: CollectionReview?
            get() = this@StudioViewModel.collection
            set(value) { this@StudioViewModel.collection = value }
        override var collectionOpen: Boolean
            get() = this@StudioViewModel.collectionOpen
            set(value) { this@StudioViewModel.collectionOpen = value }
        override var linkSpace: String?
            get() = this@StudioViewModel.linkSpace
            set(value) { this@StudioViewModel.linkSpace = value }
        override var access: PendingAccess?
            get() = this@StudioViewModel.access
            set(value) { this@StudioViewModel.access = value }
        override var deleting: Boolean
            get() = this@StudioViewModel.deleting
            set(value) { this@StudioViewModel.deleting = value }
        override var trashEntries: List<app.keepfin.TrashEntry>?
            get() = this@StudioViewModel.trashEntries
            set(value) { this@StudioViewModel.trashEntries = value }
        override var trashBusy: String?
            get() = this@StudioViewModel.trashBusy
            set(value) { this@StudioViewModel.trashBusy = value }
        override var removingDuplicates: Boolean
            get() = this@StudioViewModel.removingDuplicates
            set(value) { this@StudioViewModel.removingDuplicates = value }
        override var duplicatesProgress: Pair<Int, Int>?
            get() = this@StudioViewModel.duplicatesProgress
            set(value) { this@StudioViewModel.duplicatesProgress = value }
        override var libraryAdding: String?
            get() = this@StudioViewModel.libraryAdding
            set(value) { this@StudioViewModel.libraryAdding = value }
        override val updateState: UpdateSnapshot get() = this@StudioViewModel.updateState
        override var updateSheet: Boolean
            get() = this@StudioViewModel.updateSheet
            set(value) { this@StudioViewModel.updateSheet = value }
        override var whatsNew: String?
            get() = this@StudioViewModel.whatsNew
            set(value) { this@StudioViewModel.whatsNew = value }
        override var laterVersion: String?
            get() = this@StudioViewModel.laterVersion
            set(value) { this@StudioViewModel.laterVersion = value }
        override var announceJob: Job?
            get() = this@StudioViewModel.announceJob
            set(value) { this@StudioViewModel.announceJob = value }
        override val finishedNow: ArrayList<app.keepfin.Job> get() = this@StudioViewModel.finishedNow

        override fun <T : Application> getApplication(): T = this@StudioViewModel.getApplication()
        override fun say(text: String, actionLabel: String?, action: (() -> Unit)?) = this@StudioViewModel.say(text, actionLabel, action)
        override fun sayWithTwo(text: String, actionLabel: String?, action: (() -> Unit)?, secondLabel: String, secondAction: () -> Unit) = this@StudioViewModel.sayWithTwo(text, actionLabel, action, secondLabel, secondAction)
        override fun spaceName(id: String?): String? = this@StudioViewModel.spaceName(id)
        override fun updateSettings(transform: (EngineSettings) -> EngineSettings) = this@StudioViewModel.updateSettings(transform)
        override fun play(items: List<LibraryItem>, index: Int) = this@StudioViewModel.play(items, index)

        override fun navigate(target: Destination) = this@StudioViewModel.navigate(target)
        override fun open(page: SubPage) = this@StudioViewModel.open(page)
        override fun closeSubPage() = this@StudioViewModel.closeSubPage()
        override fun back(): Boolean = this@StudioViewModel.back()
        override fun openFromNotification(page: String, job: String?) = this@StudioViewModel.openFromNotification(page, job)
        override fun openLibraryRoot(route: LibraryRoute) = this@StudioViewModel.openLibraryRoot(route)
        override fun openLibraryDetail(route: LibraryRoute) = this@StudioViewModel.openLibraryDetail(route)
        override fun showInLibrary(space: String?) = this@StudioViewModel.showInLibrary(space)
        override fun invalidate() = this@StudioViewModel.invalidate()

        override fun undoCall(work: LibraryApi.() -> Unit) = libraryActions.undoCall(work)
        override fun showCollection(id: String) = libraryActions.showCollection(id)
        override fun reviewCollection(id: String, count: Int) = libraryActions.reviewCollection(id, count)
        override fun groupJobs(groupId: String): List<app.keepfin.Job> = collections.groupJobs(groupId)
        override fun retryGroupFailed(id: String) = collections.retryGroupFailed(id)
        override fun retry(id: String) = jobs.retry(id)
        override fun saidRemovedFromHistory(ids: List<String>) = jobs.saidRemovedFromHistory(ids)
    }
    internal val libraryActions = LibraryActions(host)
    /** Followed artists: Home's Following and New releases, the Follow buttons. */
    val following by lazy { app.keepfin.ui.home.FollowingController(this) }
    internal val collections = CollectionFlow(host)
    internal val spaces = SpaceActions(host)
    internal val jobs = JobActions(host)
    internal val updatesFlow = UpdatesFlow(host)

    /** First start and setup of an earlier library; null while a fake engine is installed (tests, previews). */
    internal val setup: app.keepfin.setup.StorageSetup? = if (StudioEngine.isFake) null else (application as app.keepfin.KeepfinApplication).storageSetup
    val libraryEnabled: Boolean get() = engine.settings.value.library.enabled

    // Navigation -----------------------------------------------------------------------------
    var destination by mutableStateOf(Destination.entries.firstOrNull { it.name == saved.get<String>("destination") }
        ?: Destination.entries.firstOrNull { it.name.equals(prefs.startPage.resolve(prefs.lastDestination, engine.settings.value.library.enabled), true) } ?: Destination.DOWNLOADS)
        private set
    var subPage by mutableStateOf(SubPage.entries.firstOrNull { it.name == saved.get<String>("subPage") })
        private set
    /**
     * One-shot "show this item" event from a notification tap. The list scrolls and highlights once, then calls
     * [consumeReveal]; a repeat tap creates a new event (new [RevealEvent.seq]). [RevealEvent.target] is a job id or "group:<id>".
     */
    var reveal by mutableStateOf<RevealEvent?>(null)
        private set
    private var revealSeq = 0
    fun consumeReveal(event: RevealEvent) { if (reveal?.seq == event.seq) reveal = null }

    /** Sets the mode: Keepfin Complete or Just downloads. The start pages choose it on a fresh install; Settings › Library & playback changes it later. */
    fun chooseMode(full: Boolean) {
        updateSettings { it.copy(library = it.library.copy(enabled = full)) }
        if (!full && destination.needsLibrary) { destination = Destination.DOWNLOADS; saved["destination"] = destination.name }
    }
    /** Text typed into Home's box (or the Search tab's own field) and the filter chip of the Search tab. */
    var searchQuery by mutableStateOf(saved.get<String>("searchQuery") ?: "")
        private set
    fun typeSearch(text: String) { searchQuery = text; saved["searchQuery"] = text }
    /** Opens the Search tab with [text] already typed. */
    fun openSearch(text: String) { typeSearch(text); navigate(Destination.SEARCH) }

    /** Raised by the floating "+ Download" button: the capture field takes focus once per tap. */
    var captureFocusSeq by mutableIntStateOf(0)
        private set
    /** "+ Download": the capture and paste flow is the top of Downloads. */
    fun startDownload() { navigate(Destination.DOWNLOADS); captureFocusSeq++ }

    /** What a box that takes links and words does with [text]: a link starts capture (the quality picker), words open Search. */
    fun routeBox(text: String): BoxRoute {
        val value = text.trim()
        if (value.isEmpty()) return BoxRoute.NOTHING
        if (Links.extract(value).isNotEmpty()) { loadLink(value); navigate(Destination.DOWNLOADS); return BoxRoute.CAPTURE }
        openSearch(value)
        return BoxRoute.SEARCH
    }

    /**
     * "Download the copied link?" on resume: shown once per distinct clipboard item. [key] identifies the clip without reading its text
     * (the clip's timestamp on Android 12+, the text on older versions); only its hash is stored. [read] runs only if the user accepts.
     */
    fun offerCopiedLink(key: String, read: () -> String?): Boolean {
        val hash = key.hashCode().toString() + ":" + key.length
        if (prefs.lastOfferedClip == hash) return false
        prefs.lastOfferedClip = hash
        say("Download the copied link?", "Download", { read()?.takeIf { Links.extract(it).isNotEmpty() }?.let { routeBox(it) } })
        return true
    }

    /** Where Back goes once a Library page opened from Home or Search is closed (null: stay in the Library). */
    private var libraryReturn: Destination? = null

    /**
     * Opens a Library page (an album, an artist, a collection, a space) from anywhere. From Home or Search, Back returns there
     * instead of landing on the Library's own start page.
     */
    fun openLibraryDeep(route: LibraryRoute) {
        if (!libraryEnabled) return
        if (destination == Destination.LIBRARY) { openLibraryDetail(route); return }
        val from = destination
        navigate(Destination.LIBRARY)
        libraryReturn = from.takeIf { it == Destination.HOME || it == Destination.SEARCH || it == Destination.SETTINGS }
        libraryStack = listOf(LibraryRoute.Home, route)
    }
    fun openAlbumOf(item: LibraryItem) { PlaybackItems.albumKey(item)?.let { openLibraryDeep(LibraryRoute.Album(item.space, it)) } }
    fun openArtistOf(item: LibraryItem) { (item.artist ?: item.albumArtist)?.takeIf { it.isNotBlank() }?.let { openLibraryDeep(LibraryRoute.Artist(item.space, it)) } }
    /** Go to song: its album, else its artist, else its space. */
    fun goToSong(item: LibraryItem) { if (PlaybackItems.albumKey(item) != null) openAlbumOf(item) else if (!(item.artist ?: item.albumArtist).isNullOrBlank()) openArtistOf(item) else showInLibrary(item.space) }
    /** A song's music video from its row: alone on the video screen from its start (no queue, no resume, no play counted). */
    fun playMusicVideo(id: String) { video = VideoRequest(id, 0L, false, musicVideo = true); player.playMusicVideo(id) }

    fun navigate(target: Destination) {
        libraryReturn = null
        val to = if (target.needsLibrary && !libraryEnabled) Destination.DOWNLOADS else target
        // Tapping the tab you are on goes back to its start page: Library Home, the Settings overview.
        if (to == destination && subPage == null) {
            if (to == Destination.LIBRARY) libraryStack = listOf(LibraryRoute.Home)
            if (to == Destination.SETTINGS) chooseSettingsPage(null)
        }
        destination = to; saved["destination"] = to.name; prefs.lastDestination = to.name.lowercase()
        closeSubPage()
    }

    // Settings pages --------------------------------------------------------------------------
    /** The Settings category that is open, or null for the overview. Survives recreation. */
    var settingsPage by mutableStateOf(SettingsRedirect.resolve(saved.get<String>("settingsPage"))?.category)
        private set
    fun chooseSettingsPage(page: SettingsCategory?) { settingsPage = page; saved["settingsPage"] = page?.name; settingsSearching = false; if (page == null) settingsAnchor = null }

    /** The group or row to scroll to and highlight on the open Settings page, until the page reports it was shown. */
    var settingsAnchor by mutableStateOf<SettingsAnchor?>(null)
        private set
    fun clearSettingsAnchor() { settingsAnchor = null }

    /** True while the Settings search screen is open. */
    var settingsSearching by mutableStateOf(false)
        private set
    fun searchSettings(on: Boolean) { settingsSearching = on }

    /** Opens Settings on a category, scrolling to (and briefly highlighting) a group or row in it. */
    fun openSettings(target: SettingsTarget) {
        navigate(Destination.SETTINGS)
        closeSubPage()
        // Support Keepfin is one screen: open it straight away, from the Settings list or a link
        if (target.category == app.keepfin.ui.settings.SettingsCategory.SUPPORT) { open(SubPage.SUPPORT); settingsAnchor = null; return }
        chooseSettingsPage(target.category)
        settingsAnchor = if (target.group != null || target.tag != null) SettingsAnchor(target, System.nanoTime()) else null
    }

    // Where your files are ----------------------------------------------------------------------
    /** The component's source: null in a preview or without a Keepfin folder. Tests may set their own. */
    var whereSource: app.keepfin.where.WhereFilesSource? = null
        get() = field ?: if (StudioEngine.isFake) null else (getApplication<Application>() as? app.keepfin.KeepfinApplication)?.let { app.keepfin.where.WhereFilesRuntime.of(it) }

    /** A tap that crosses between Devices and Storage, until the other page has used it. */
    var whereIntent by mutableStateOf<app.keepfin.where.WhereIntent?>(null)
        private set
    fun whereConsumed() { whereIntent = null }
    fun whereOpenDevice(deviceId: String) { whereIntent = app.keepfin.where.WhereIntent.Device(deviceId); openSettings(SettingsTarget(SettingsCategory.DEVICES, SettingsGroups.DEVICES)) }
    fun whereOpenCopy(placeId: String) { whereIntent = app.keepfin.where.WhereIntent.Copy(placeId); openSettings(SettingsTarget(SettingsCategory.STORAGE, SettingsGroups.STORAGE)) }
    fun whereKeepCopy(spaceIds: Set<String>) { whereIntent = app.keepfin.where.WhereIntent.Keep(spaceIds); openSettings(SettingsTarget(SettingsCategory.STORAGE, SettingsGroups.STORAGE)) }

    /** Opens Settings from an id the older Settings used (General, Performance, Network, Accounts, Updates, Support...): see [SettingsRedirect]. */
    fun openSettings(oldId: String): Boolean {
        val target = SettingsRedirect.resolve(oldId) ?: return false
        openSettings(target)
        return true
    }

    // Downloads filters ----------------------------------------------------------------------
    var downloadsFilter by mutableStateOf(DownloadsFilter.entries.firstOrNull { it.name == saved.get<String>("downloadsFilter") } ?: DownloadsFilter.ALL)
        private set
    fun chooseDownloadsFilter(filter: DownloadsFilter) { downloadsFilter = filter; saved["downloadsFilter"] = filter.name }

    // Library navigation ---------------------------------------------------------------------
    /** True while the Library has items selected: the bottom navigation gives way to the selection actions. */
    var librarySelecting by mutableStateOf(false)
    private var libraryStack by mutableStateOf<List<LibraryRoute>>(listOf(LibraryRoute.Home))
    val libraryRoute: LibraryRoute get() = libraryStack.last()
    private val spaceTabs = androidx.compose.runtime.mutableStateMapOf<String, MusicTab>()
    fun musicTab(space: String): MusicTab = spaceTabs[space] ?: MusicTab.SONGS
    fun chooseMusicTab(space: String, tab: MusicTab) { spaceTabs[space] = tab }

    /** A top-level library page straight from Home (a space, the playlists list); Back returns to Home. */
    fun openLibraryRoot(route: LibraryRoute) { libraryStack = if (route == LibraryRoute.Home) listOf(LibraryRoute.Home) else listOf(LibraryRoute.Home, route) }
    /** A detail page on top of the current one. */
    fun openLibraryDetail(route: LibraryRoute) { if (route != libraryRoute) libraryStack = libraryStack + route }
    /** Jumps to a space from anywhere (toasts, Save to). */
    fun showInLibrary(space: String?) {
        navigate(Destination.LIBRARY)
        openLibraryRoot(if (space == null) LibraryRoute.Home else LibraryRoute.Space(space))
    }
    private fun libraryBack(): Boolean {
        if (libraryStack.size <= 1) return false
        libraryStack = libraryStack.dropLast(1)
        // A page that Home or Search opened hands Back to where it came from.
        if (libraryStack.size == 1) libraryReturn?.let { target -> navigate(target) }
        return true
    }
    /** Opens Settings › Library. */
    fun openLibrarySettings() { openSettings(SettingsTarget(SettingsCategory.LIBRARY)) }

    // Player surfaces -------------------------------------------------------------------------
    var fullPlayer by mutableStateOf(false)
    var video by mutableStateOf<VideoRequest?>(null)
        private set
    /** The video kept as a song whose picture shows in the full player's cover place. Only what is shown changes; the playback is still the song's. */
    var songPicture by mutableStateOf<String?>(null)
        private set
    var photos by mutableStateOf<ImageRequest?>(null)
        private set
    /**
     * Closing the video ends it, as closing the player does (the video replaced the queue when it started). A song's music video is only
     * the other side of the song: closing it goes back to the song, where it was, paused (the queue stays).
     */
    fun closeVideo() {
        if (video?.fromPlayer == true) { leaveVideoToPicture(); return }
        if (video?.musicVideo == true) { chooseSide(false, keepPlaying = false); return }
        video = null; player.clearQueue()
    }

    /** The full-screen button on the picture in the full player: the same playback, same place in time, on the video screen. */
    fun openPictureFullScreen() {
        val cur = player.ui.value.current ?: return
        video = VideoRequest(cur.id, 0L, false, musicVideo = cur.musicVideo, fromPlayer = true)
    }

    /** Back from that full screen: the full player with the picture in place, still playing. */
    private fun leaveVideoToPicture() {
        sideSwitching = true
        viewModelScope.launch { delay(1_000); sideSwitching = false }
        video = null; fullPlayer = true
    }

    // Music videos (dev-docs/plans/MUSIC-VIDEOS.md) -------------------------------------------------

    /** Songs and their videos: Capture's line and the link kept as a video, the switch's start, Find lyrics (see [SongVideoFlow]). */
    internal val songVideo = SongVideoFlow(host)

    /** True for a moment while a side switch closes or opens the video screen: the screen does not pause what the switch is about to continue. */
    var sideSwitching by mutableStateOf(false)
        private set

    init {
        player.setMusicVideo(prefs.musicVideo)
        // the video screen follows the queue: a song that starts as its video opens it, the next song without one closes it
        viewModelScope.launch {
            player.ui.map { it.current }.distinctUntilChanged { a, b -> a?.id == b?.id && a?.musicVideo == b?.musicVideo && a?.resolved == b?.resolved }.collect { cur ->
                if (songPicture != null && songPicture != cur?.id) songPicture = null
                // the full screen opened from the picture of a video kept as a song follows the queue: the next song brings the full player back
                val shown = video
                if (shown?.fromPlayer == true && cur != null && cur.resolved && !cur.musicVideo && cur.id != shown.id) { video = null; fullPlayer = true; return@collect }
                when (val step = app.keepfin.ui.player.MusicVideoLogic.follow(cur, video, fullPlayer)) {
                    is app.keepfin.ui.player.MusicVideoLogic.Follow.Open -> video = VideoRequest(step.id, 0L, false, musicVideo = true, fromPlayer = video?.fromPlayer == true)
                    app.keepfin.ui.player.MusicVideoLogic.Follow.Close -> { video = null; fullPlayer = true }
                    app.keepfin.ui.player.MusicVideoLogic.Follow.Nothing -> Unit
                }
            }
        }
    }

    /**
     * The Song | Video switch: the song that plays continues on the other side from the same second, stopped at the end of the shorter one (the core's
     * rule), the queue stays, and the choice is remembered for the next songs. Nothing is counted as a play and no sound stays under the picture: the
     * queue is made again with the other side, so only that side plays.
     */
    fun chooseSide(toVideo: Boolean, keepPlaying: Boolean = true) {
        val cur = player.ui.value.current ?: return
        if (cur.musicVideo == toVideo) return
        val at = player.position.value.positionMs
        prefs.chooseMusicVideo(toVideo)
        sideSwitching = true
        viewModelScope.launch {
            val start = try { withContext(Dispatchers.IO) { songVideo.startOnOtherSide(cur.id, toVideo, at) } } catch (e: CancellationException) { throw e } catch (_: Exception) { at }
            launch { delay(1_000); sideSwitching = false }
            player.switchSide(toVideo, start)
            // Video shows the picture in the cover's place in the full player (no request); Song brings the cover back
            if (!toVideo) { video = null; fullPlayer = true }
            if (!keepPlaying) player.pause()
        }
    }

    /** The video side was asked for but the file is not on this phone: the song plays, the video screen closes, and it says so. */
    fun musicVideoMissing() {
        if (video?.musicVideo != true && !fullPlayer) return
        video = null; fullPlayer = true
        prefs.chooseMusicVideo(false); player.setMusicVideo(false)
        say("The music video isn't on this phone.")
    }

    /** Watch music video: the song's video from its start. A song that is playing just switches side. */
    fun watchMusicVideo(item: LibraryItem) {
        val playing = player.ui.value.current
        video = null; fullPlayer = true
        if (playing?.id == item.id) {
            if (!playing.musicVideo) chooseSide(true)
            return
        }
        prefs.chooseMusicVideo(true); player.setMusicVideo(true)
        player.playNow(listOf(item.id), 0, 0L)
    }

    /** The "Get the music video" sheet: the song it is for and how the look is going. Null while it is closed. */
    var musicVideoSheet by mutableStateOf<MusicVideoSheet?>(null)
        private set
    private var musicVideoLook: Job? = null

    /** Looks for [item]'s official music video and shows what came of it. Nothing is downloaded until the person says Download. */
    fun getMusicVideo(item: LibraryItem) {
        musicVideoLook?.cancel()
        musicVideoSheet = MusicVideoSheet(item.id, item.title, null)
        val artists = listOfNotNull((item.artist ?: item.albumArtist)?.takeIf { it.isNotBlank() })
        musicVideoLook = viewModelScope.launch {
            val found = try { withContext(Dispatchers.IO) { engine.findMusicVideo(artists, item.title) } } catch (e: CancellationException) { throw e } catch (e: Exception) {
                app.keepfin.MusicVideoLookup.Failed("Couldn't look for the music video. Check your connection and try again.")
            }
            musicVideoSheet = musicVideoSheet?.takeIf { it.songId == item.id }?.copy(lookup = found) ?: return@launch
        }
    }

    fun retryMusicVideo() {
        val sheet = musicVideoSheet ?: return
        val item = try { lib.item(sheet.songId) } catch (e: Exception) { null }
        if (item == null) { musicVideoSheet = null; say("This song is no longer in your library."); return }
        getMusicVideo(item)
    }

    fun closeMusicVideoSheet() { musicVideoLook?.cancel(); musicVideoSheet = null }

    /** Download on the sheet: queues the video to be kept beside the song. */
    fun downloadMusicVideo() {
        val sheet = musicVideoSheet ?: return
        val found = (sheet.lookup as? app.keepfin.MusicVideoLookup.Found)?.video ?: return
        musicVideoSheet = null
        val space = try { lib.item(sheet.songId)?.space } catch (e: Exception) { null }
        try {
            engine.enqueueMusicVideo(found, sheet.songId, space)
            say("Downloading the music video")
        } catch (e: Exception) { say(friendly(e, "The music video couldn't be queued.")) }
    }

    /** Deletes the music video file beside [item]; the song stays. The caller has asked first. */
    fun removeMusicVideo(item: LibraryItem) {
        if (player.ui.value.current?.let { it.id == item.id && it.musicVideo } == true) chooseSide(false, keepPlaying = false)
        libraryActions.libraryCall("Couldn't delete the music video.") { removeMusicVideo(item.id) }
        say("Music video deleted")
    }
    fun closePhotos() { photos = null }

    /** Plays [items] from [index]: audio in the queue, video on its own screen, photos in the viewer, or another app when chosen. */
    fun play(items: List<LibraryItem>, index: Int) {
        val item = items.getOrNull(index) ?: return
        // Lists on screen can be a moment behind a delete: play only what the library still has (a read of the index, off the main thread).
        val browse = lib.browse
        if (browse.cheap) { playChecked(items, item, browse.byIds(items.map { it.id }).mapTo(HashSet()) { it.id }) }
        else viewModelScope.launch {
            val alive = withContext(Dispatchers.IO) { browse.byIds(items.map { it.id }).mapTo(HashSet()) { it.id } }
            playChecked(items, item, alive)
        }
    }

    private fun playChecked(items: List<LibraryItem>, item: LibraryItem, alive: Set<String>) {
        if (item.id !in alive) { say("This file is no longer in your library."); return }
        // not on this phone: it plays from a paired device that has it, or says plainly why it cannot
        if (item.remote) {
            // the holder is read from the library placements (a database read, off the main thread)
            viewModelScope.launch {
                val blocked = withContext(Dispatchers.IO) { app.keepfin.net.PeerPlayback.blockedMessage(whereSource, item.id) }
                if (blocked != null) say(blocked) else playAllowed(items, item, alive)
            }
            return
        }
        playAllowed(items, item, alive)
    }

    private fun playAllowed(items: List<LibraryItem>, item: LibraryItem, alive: Set<String>) {
        if (!engine.settings.value.library.builtInPlayer && item.kind != LibraryItemKind.IMAGE && !item.remote) {
            ExternalOpen.open(getApplication(), item)?.let(::say)
            return
        }
        when (item.kind) {
            LibraryItemKind.AUDIO -> playSongs(items, item, alive)
            // a video kept in a music space is a song: it plays in the music player with the songs around it
            LibraryItemKind.VIDEO -> if (isVideoSong(item)) playSongs(items, item, alive) else playVideo(item, context = app.keepfin.ui.library.LibraryViews.windowAround(items.filter { it.kind == LibraryItemKind.VIDEO && it.id in alive }.map { it.id }, item.id))
            LibraryItemKind.IMAGE -> {
                val pics = items.filter { it.kind == LibraryItemKind.IMAGE && it.id in alive }
                photos = ImageRequest(pics.map { it.id }, pics.indexOfFirst { it.id == item.id }.coerceAtLeast(0))
            }
            LibraryItemKind.OTHER -> ExternalOpen.open(getApplication(), item)?.let(::say)
        }
    }

    private fun playSongs(items: List<LibraryItem>, item: LibraryItem, alive: Set<String>) {
        val songs = items.filter { (it.kind == LibraryItemKind.AUDIO || isVideoSong(it)) && it.id in alive }
        player.playNow(songs.map { it.id }, songs.indexOfFirst { it.id == item.id }.coerceAtLeast(0))
        // Video is on and this song has one: it starts as its picture, shown in place when the full player is open
    }

    /** The music spaces' ids: a video in one of them is a song, listed with the songs and not with the videos. */
    fun musicSpaceIds(): List<String> = engine.settings.value.library.spaces.filter { it.kind == app.keepfin.SpaceKind.MUSIC }.map { it.id }

    /** A video kept in a music space is a song ([app.keepfin.core.spaces.Songs.isVideoSong]): it plays in the music player and offers Watch video. */
    fun isVideoSong(item: LibraryItem): Boolean =
        app.keepfin.core.spaces.Songs.isVideoSong(item.kind.wire, engine.settings.value.library.spaces.firstOrNull { it.id == item.space }?.kind?.wire)

    /** Plays [item] with the rows of [query] around it as the queue (read in the background): the neighbourhood, not the whole list. */
    fun playWindow(query: app.keepfin.ItemQuery, item: LibraryItem) {
        val browse = lib.browse
        val key = browse.keyOf(item, query.order)
        fun window(): Pair<List<LibraryItem>, Int> {
            val q = if (item.kind == LibraryItemKind.IMAGE) query.copy(kinds = setOf(LibraryItemKind.IMAGE)) else query.copy(kinds = emptySet(), songs = true)
            val before = browse.page(q, app.keepfin.PageDirection.BEFORE, key, QUEUE_BEFORE).items
            val from = browse.page(q, app.keepfin.PageDirection.AT, key, QUEUE_AFTER).items
            return (before + from) to before.size
        }
        if (browse.cheap) { val (list, at) = window(); play(list, at) }
        else viewModelScope.launch { val (list, at) = withContext(Dispatchers.IO) { window() }; play(list, at) }
    }

    /** Plays (or shuffles) the start of [query]. */
    fun playQuery(query: app.keepfin.ItemQuery, shuffle: Boolean) {
        val browse = lib.browse
        fun load(): List<LibraryItem> = browse.top(query.copy(kinds = emptySet(), songs = true), QUEUE_BEFORE + QUEUE_AFTER)
        fun run(list: List<LibraryItem>) { if (shuffle) shuffle(list) else { player.setShuffle(false); play(list, 0) } }
        if (browse.cheap) run(load()) else viewModelScope.launch { run(withContext(Dispatchers.IO) { load() }) }
    }

    /** [context] is the list this video was opened from (previous/next/autoplay follow it); empty means "its folder". */
    fun playVideo(item: LibraryItem, startOver: Boolean = false, context: List<String> = emptyList()) {
        val start = if (startOver) 0L else app.keepfin.ui.player.VideoLogic.resumeStartMs(item.position, item.duration, item.watched)
        video = VideoRequest(item.id, start, resumed = start > 0, context = context)
        player.playNow(listOf(item.id), 0, start, watch = true)
    }

    /** Watch video on a video kept as a song: the same file in the video player, from where the music player is when it is playing it. */
    fun watchVideoSong(item: LibraryItem) {
        video = null; fullPlayer = true
        showVideoSongPicture(item)
    }

    /** Video on a video kept as a song, in the full player: the picture takes the cover's place; the queue, place and play/pause state stay. */
    fun showVideoSongPicture(item: LibraryItem) {
        if (player.ui.value.current?.id != item.id) player.playNow(listOf(item.id), 0, 0L)
        songPicture = item.id
    }

    /** Song on a video kept as a song: the cover comes back. A file the player holds as a video is made again as the song, from the same place. */
    fun listenToVideoSong(item: LibraryItem) {
        songPicture = null
        video = null
        fullPlayer = true
        val cur = player.ui.value.current
        if (cur?.id != item.id) player.playNow(listOf(item.id), 0, 0L)
        else if (cur.video) player.playNow(listOf(item.id), 0, player.position.value.positionMs, play = player.ui.value.playing)
    }

    /** Songs and videos: the music player plays a video's sound. */
    private fun sounds(items: List<LibraryItem>) = items.filter { it.kind == LibraryItemKind.AUDIO || it.kind == LibraryItemKind.VIDEO }

    /** Plays the sound of [items] in the music player from [index]; a video opens no video screen. */
    fun playAudio(items: List<LibraryItem>, index: Int = 0) {
        val list = sounds(items)
        player.playNow(list.map { it.id }, list.indexOfFirst { it.id == items.getOrNull(index)?.id }.coerceAtLeast(0))
    }

    fun playNext(items: List<LibraryItem>) { player.playNext(sounds(items).map { it.id }); say("Playing next") }
    fun addToQueue(items: List<LibraryItem>) { player.addToQueue(sounds(items).map { it.id }); say("Added to the queue") }

    /** Takes [item] off Continue watching by forgetting where it stopped (it starts from the beginning next time); Undo puts it back. */
    fun removeFromContinue(item: LibraryItem) {
        val was = item.position
        viewModelScope.launch(Dispatchers.IO) { lib.recordPlayback(item.id, null, false) }
        say("Removed “${item.title}” from Continue watching", "Undo") { viewModelScope.launch(Dispatchers.IO) { lib.recordPlayback(item.id, was, false) } }
    }
    /**
     * Plays a finished download in the built-in player. False (nothing happened) when the library is off, the file is not in the library
     * or the built-in player is off, so the caller can open it in another app instead.
     */
    fun playJob(job: app.keepfin.Job): Boolean {
        if (!libraryEnabled || !engine.settings.value.library.builtInPlayer) return false
        // two indexed lookups from a tap handler (not a frame)
        val item = lib.browse.byJob(job.id) ?: job.publicUri?.takeIf { it.isNotBlank() }?.let { u -> lib.browse.byUri(u) } ?: return false
        if (item.kind == LibraryItemKind.OTHER) return false
        play(listOf(item), 0)
        return true
    }

    fun openExternally(item: LibraryItem) { ExternalOpen.open(getApplication(), item)?.let(::say) }

    fun shuffle(items: List<LibraryItem>) {
        val audio = sounds(items).shuffled()
        if (audio.isEmpty()) return
        player.setShuffle(false)
        player.playNow(audio.map { it.id }, 0)
    }

    /** "Try it" in the guide: the destinations the guide's `try` ids name. Library ones explain themselves when the library is off. */
    fun guideTry(id: String) {
        when (id) {
            "downloads" -> navigate(Destination.DOWNLOADS)
            "settings" -> { navigate(Destination.SETTINGS); chooseSettingsPage(null) }
            "library", "library-edit" -> {
                if (!libraryEnabled) { openLibrarySettings(); say("Turn on the Library to try this."); return }
                navigate(Destination.LIBRARY)
                openLibraryRoot(if (id == "library-edit") LibraryRoute.Manage else LibraryRoute.Home)
            }
        }
    }

    fun open(page: SubPage) { subPage = page; saved["subPage"] = page.name }
    fun closeSubPage() { subPage = null; saved["subPage"] = null; signInRequest = null }

    /** System Back: sub-page → its parent; secondary destination → Downloads; otherwise the system. */
    fun back(): Boolean {
        if (photos != null) { photos = null; return true }
        if (video != null) { closeVideo(); return true }
        if (fullPlayer) { fullPlayer = false; return true }
        if (subPage != null) { closeSubPage(); return true }
        if (destination == Destination.LIBRARY && libraryEnabled && libraryBack()) return true
        if (destination == Destination.SETTINGS && settingsSearching) { settingsSearching = false; return true }
        if (destination == Destination.SETTINGS && settingsPage != null) { chooseSettingsPage(null); return true }
        if (destination != Destination.DOWNLOADS) { navigate(Destination.DOWNLOADS); return true }
        return false
    }

    /**
     * The system media card was tapped: show the player that matches the session. A song opens the full music player,
     * a video its video player (Library when the file is gone). The player connection can be a moment behind a cold start,
     * so an empty queue waits a few seconds before giving up instead of leaving a hidden "open" request behind.
     */
    fun openCurrentPlayer() {
        if (!libraryEnabled) return
        viewModelScope.launch {
            val current = player.ui.value.current ?: withTimeoutOrNull(4_000) { player.ui.first { it.current != null } }?.current ?: return@launch
            closeSubPage(); photos = null
            // a song's picture shows in place in the full player (dev-docs/plans/FULL-PLAYER.md)
            if (current.musicVideo) { video = null; fullPlayer = true }
            else if (current.video) {
                val item = withContext(Dispatchers.IO) { lib.item(current.id) }
                if (item != null && isVideoSong(item)) { video = null; fullPlayer = true }
                else if (item != null) playVideo(item) else { video = null; navigate(Destination.LIBRARY) }
            } else { video = null; fullPlayer = true }
        }
    }

    fun openFromNotification(page: String, job: String?) {
        when (page) {
            "Downloads" -> navigate(Destination.DOWNLOADS)
            "History" -> if (libraryEnabled) { navigate(Destination.DOWNLOADS); chooseDownloadsFilter(DownloadsFilter.FINISHED) } else navigate(Destination.LIBRARY)
            "Settings" -> navigate(Destination.SETTINGS)
            "Library" -> { navigate(Destination.LIBRARY); closeSubPage(); return }
            else -> return
        }
        closeSubPage()
        reveal = job?.takeIf { it.isNotBlank() }?.let { RevealEvent(++revealSeq, it) }
    }

    // App updates & what's new ---------------------------------------------------------------
    /** Snapshot of the app updater; AppUpdates has no stream, so it is sampled and only changes publish. */
    var updateState by mutableStateOf(updates.snapshot())
        private set
    var updateSheet by mutableStateOf(false)
        private set
    /** Version whose notes are shown after an update; null when nothing is pending. */
    var whatsNew by mutableStateOf<String?>(null)
        private set

    /** Versions the user answered "Later" for in this launch; "Skip this version" is remembered in [UiPreferences]. */
    private var laterVersion by mutableStateOf<String?>(null)




    private val announced = java.util.Collections.synchronizedSet(HashSet<String>())
    private var announceJob: Job? = null
    private val finishedNow = ArrayList<app.keepfin.Job>()

    init {
        updates.applyChannel(prefs.appChannel)
        val current = updates.currentVersion
        val seen = prefs.lastSeenVersion
        // A stored older version means the app was updated since the last launch; a fresh install has none.
        if (seen != null && seen != current && isNewer(current, seen)) whatsNew = current
        if (seen != current) prefs.lastSeenVersion = current
        if (System.currentTimeMillis() - prefs.lastUpdateCheck > UPDATE_CHECK_INTERVAL_MS) {
            prefs.lastUpdateCheck = System.currentTimeMillis()
            updatesFlow.checkForUpdates()
        }
        if (!StudioEngine.isFake && engine.settings.value.library.enabled) viewModelScope.launch { player.connectIfSaved() }
        viewModelScope.launch {
            // Turning the library off closes every player surface and stops playback: no player without a library.
            engine.settings.map { it.library.enabled }.distinctUntilChanged().collect { enabled ->
                if (!enabled) { fullPlayer = false; video = null; photos = null; player.clearQueue() }
            }
        }
        viewModelScope.launch {
            engine.jobs.collect { list ->
                for (job in list) {
                    if (job.status.active) announced.add(job.id)
                    else if (job.status == JobStatus.COMPLETED && announced.remove(job.id) && job.saveError == null && job.existing == null && job.space != null && libraryEnabled) spaces.announceSaved(job)
                    else if (job.status != JobStatus.PAUSED) announced.remove(job.id)
                }
                // One notice when the queue is done, never one per finished item: a 68-song playlist says it once.
                if (list.any { it.status.active }) announceJob?.cancel()
                else if (announceJob?.isActive != true && synchronized(finishedNow) { finishedNow.isNotEmpty() }) spaces.flushSaved()
            }
        }
        viewModelScope.launch { lib.followReports.collect(libraryActions::announceCheck) }
        viewModelScope.launch {
            while (true) {
                val next = updates.snapshot()
                if (next != updateState) updateState = next
                delay(if (next.phase == "downloading" || next.phase == "checking") 300 else 1000)
            }
        }
    }

    // Activity -------------------------------------------------------------------------------------
    /** The Activity list the bell and the Activity page read. Recording happens in the engine, never here. */
    val activityFeed: ActivityFeed get() = engine.activity
    fun openActivity() = open(SubPage.ACTIVITY)


    // Messages --------------------------------------------------------------------------------
    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 16)
    val messages: SharedFlow<UiMessage> = _messages
    /** A notice, with one button when [actionLabel] is given: `say(text, "Undo") { … }` hands the block to that button. */
    fun say(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        _messages.tryEmit(UiMessage(text, actionLabel, action))
    }

    /** A notice with two buttons (Play and Show in Library). Its own name, so a trailing block after [say] never lands on a second button nobody sees. */
    fun sayWithTwo(text: String, actionLabel: String?, action: (() -> Unit)?, secondLabel: String, secondAction: () -> Unit) {
        _messages.tryEmit(UiMessage(text, actionLabel, action, secondLabel, secondAction))
    }

    /** A swipe past either end of the play queue says why nothing changed. */
    fun sayQueueEdge(next: Boolean) = say(if (next) "That's the last song in the queue" else "That's the first song in the queue")

    init {
        // "N things happened while you were away": once per app start, through the ordinary notice, after the screen is listening.
        viewModelScope.launch {
            val away = try { withContext(Dispatchers.IO) { engine.activity.takeAwayCount() } } catch (e: CancellationException) { throw e } catch (_: Exception) { 0 }
            if (away <= 0) return@launch
            _messages.subscriptionCount.first { it > 0 }
            say(if (away == 1) "1 thing happened while you were away" else "$away things happened while you were away", "Show", { openActivity() })
        }
    }

    // Capture & inspection --------------------------------------------------------------------
    var draft by mutableStateOf(saved.get<String>("draft") ?: "")
        private set
    var capture by mutableStateOf<Capture>(Capture.Empty)
        private set
    var choice by mutableStateOf(FormatChoice())
    var options by mutableStateOf(engine.settings.value.options)
    /**
     * Whether the single-item review sheet is showing. Closing it (swipe down, Cancel, back) keeps the review, the choices and the link:
     * the Downloads page shows a small "Review" row and the sheet reopens with the same choices. Only Clear discards it.
     */
    var reviewOpen by mutableStateOf(true)
        private set
    fun closeReview() { reviewOpen = false }
    fun openReview() { reviewOpen = true }
    var batchPreset by mutableStateOf(engine.settings.value.defaultPreset)
    var adding by mutableStateOf(false)
        private set
    var collection by mutableStateOf<CollectionReview?>(null)
        private set
    /** Whether the collection's review sheet is showing. Closing it keeps the review (a summary row reopens it); only its X discards it. */
    var collectionOpen by mutableStateOf(false)

    /** Generation token: results from an older link can never replace a newer one. */
    private var generation = 0
    private var inspectedLinks: List<String> = emptyList()
    private var inspectionWork: Job? = null
    private var debounce: Job? = null
    private val sessions = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    fun onDraftChange(text: String) {
        draft = text; saved["draft"] = text
        val links = Links.extract(text)
        if (links == inspectedLinks) return
        invalidate()
        debounce?.cancel()
        if (links.isEmpty()) return
        debounce = viewModelScope.launch {
            delay(AUTO_INSPECT_DELAY_MS)
            inspect()
        }
    }

    /** Paste and share load a fresh draft and inspect immediately. */
    fun loadLink(text: String, fromShare: Boolean = false) {
        val links = Links.extract(text)
        val value = if (fromShare && links.isNotEmpty()) links.joinToString("\n") else text.trim()
        draft = value; saved["draft"] = value
        // a shared link shows its download: nothing stays on top of it (music keeps playing, a video ends)
        if (fromShare) { closeSubPage(); photos = null; fullPlayer = false; if (video != null) closeVideo(); navigate(Destination.DOWNLOADS) }
        debounce?.cancel()
        inspectedLinks = emptyList()
        inspect()
    }

    /** A shared link that waits in the link box without being inspected (after Keepfin stopped while it was open). */
    fun holdLink(text: String) {
        val value = Links.extract(text).joinToString("\n")
        debounce?.cancel()
        invalidate()
        draft = value; saved["draft"] = value
        inspectedLinks = Links.extract(value)
        reviewOpen = true
        capture = Capture.Failed(value, "Keepfin closed while it was opening this link last time. Tap Try again to open it.")
        closeSubPage(); navigate(Destination.DOWNLOADS)
    }

    fun clearDraft() {
        debounce?.cancel()
        invalidate()
        draft = ""; saved["draft"] = ""
    }

    // Save to ------------------------------------------------------------------------------------
    /** The space the user picked for the current capture; null = the smart default. Changing it never changes the defaults. */
    var saveTo by mutableStateOf<String?>(null)
        private set
    /** "Add to playlist" for the one link or the links under review; cleared with the review. */
    var playlistTo by mutableStateOf<String?>(null)
    /** "Already in <space>" for a single inspected link, with Play and Download anyway. */
    var captureExisting by mutableStateOf<ExistingMatch?>(null)
        private set

    /**
     * "Also keep the music video" in the review of one song (dev-docs/plans/MUSIC-VIDEOS.md): the look for its official video, and whether the box is checked.
     * Null until the review asks for a look; a [lookup] of null is the look in progress.
     */
    var captureVideo by mutableStateOf<CaptureVideo?>(null)
        private set
    private var captureVideoLook: Job? = null

    /** Whether Capture can offer the music video of [media]: a song with its artist, kept as audio, that is not already in the library. */
    fun offersMusicVideo(media: MediaInfo, request: app.keepfin.FormatRequest): Boolean =
        // a song already in the library gets its video too: it goes beside the one there
        libraryEnabled && request is app.keepfin.FormatRequest.Audio && !media.music?.artist.isNullOrBlank()

    /** Looks for the official video of the inspected song in the background (once per link). Nothing is queued by this. */
    fun lookForCaptureVideo(media: MediaInfo) {
        if (captureVideo != null) return
        val artist = media.music?.artist?.takeIf { it.isNotBlank() } ?: return
        val title = (media.music?.track ?: media.title).ifBlank { return }
        captureVideo = CaptureVideo(null, engine.settings.value.keepMusicVideos)
        val token = generation
        captureVideoLook = viewModelScope.launch {
            val found = try { withContext(Dispatchers.IO) { engine.findMusicVideo(listOf(artist), title) } } catch (e: CancellationException) { throw e } catch (e: Exception) {
                app.keepfin.MusicVideoLookup.Failed("Couldn't look for the music video.")
            }
            if (token == generation) captureVideo = captureVideo?.copy(lookup = found)
        }
    }

    fun keepCaptureVideo(on: Boolean) { captureVideo = captureVideo?.let { if (it.lookup is app.keepfin.MusicVideoLookup.Found) it.copy(keep = on) else it } }

    /** The space a link will be saved to, or null while the library is off. */
    fun targetSpace(url: String, uploader: String?): String? =
        if (!libraryEnabled) null else (saveTo ?: lib.defaultSpaceFor(url, uploader))?.takeIf { id -> engine.settings.value.library.folderSpaces.any { it.id == id } }

    /** The Save to chip for one link; null while the library is off. */
    fun saveToFor(url: String, uploader: String?): app.keepfin.ui.downloads.SaveToUi? {
        if (!libraryEnabled) return null
        val id = targetSpace(url, uploader) ?: return null
        return app.keepfin.ui.downloads.SaveToUi(spaceName(id) ?: id, id, engine.settings.value.library.folderSpaces, false, ::chooseSaveTo, null, ::openLibrarySettings)
    }

    /**
     * The space a video is about to be saved to when that makes it a song with its picture (a music space, the core's rule), for the line
     * under the Video choice; null otherwise.
     */
    fun keptAsSongIn(media: MediaInfo): String? {
        if (!libraryEnabled || choice.audioMode || media.video.isEmpty()) return null
        val id = targetSpace(media.webpageUrl ?: media.url, media.uploader) ?: return null
        val kind = engine.settings.value.library.spaces.firstOrNull { it.id == id }?.kind?.wire
        return if (app.keepfin.core.spaces.Songs.isVideoSong("video", kind)) spaceName(id) ?: id else null
    }

    /** The Save to chip for a batch of links: one destination when they agree, else "By link type". */
    fun saveToForBatch(links: List<MediaInfo>): app.keepfin.ui.downloads.SaveToUi? {
        if (!libraryEnabled || links.isEmpty()) return null
        val spaces = engine.settings.value.library.folderSpaces
        val picked = saveTo
        val ids = links.mapNotNull { lib.defaultSpaceFor(it.webpageUrl ?: it.url, it.uploader) }.distinct()
        return when {
            picked != null -> app.keepfin.ui.downloads.SaveToUi(spaceName(picked) ?: picked, picked, spaces, false, ::chooseSaveTo, { saveTo = null }, ::openLibrarySettings)
            ids.size == 1 -> app.keepfin.ui.downloads.SaveToUi(spaceName(ids.first()) ?: ids.first(), ids.first(), spaces, false, ::chooseSaveTo, null, ::openLibrarySettings)
            else -> app.keepfin.ui.downloads.SaveToUi("By link type", null, spaces, true, ::chooseSaveTo, { saveTo = null }, ::openLibrarySettings)
        }
    }

    fun saveToForCollection(review: CollectionReview): app.keepfin.ui.downloads.SaveToUi? {
        if (!libraryEnabled) return null
        val id = collections.collectionSpace(review) ?: return null
        return app.keepfin.ui.downloads.SaveToUi(spaceName(id) ?: id, id, engine.settings.value.library.folderSpaces, false, { collections.chooseCollectionSpace(review, it) }, null, ::openLibrarySettings)
    }

    fun spaceName(id: String?): String? = engine.settings.value.library.spaces.firstOrNull { it.id == id }?.name

    fun chooseSaveTo(space: String) {
        saveTo = space
        (capture as? Capture.Single)?.let { refreshExisting(it.media) }
    }

    private fun refreshExisting(media: MediaInfo) {
        captureExisting = null; songVideo.lookForSong(media)
        if (media.gallery != null) return
        val target = targetSpace(media.webpageUrl ?: media.url, media.uploader) ?: return
        if (engine.settings.value.library.spaces.firstOrNull { it.id == target }?.skipExisting != true) return
        val token = generation
        viewModelScope.launch {
            val match = try { withContext(Dispatchers.IO) { lib.existingFor(target, media) } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (token == generation && (capture as? Capture.Single)?.media?.url == media.url) captureExisting = match
        }
    }

    /** Plays the recording a capture matched. */
    fun playExisting(match: ExistingMatch) {
        val item = match.itemId?.let { lib.item(it) } ?: lib.browse.byUri(match.uri)
        if (item == null) say("This file is no longer in your library.") else play(listOf(item), 0)
    }

    /** Stops in-flight work and forgets results so a late callback cannot resurrect them. */
    private fun invalidate() {
        generation++
        saveTo = null; playlistTo = null; captureExisting = null; songVideo.reset()
        captureVideoLook?.cancel(); captureVideo = null
        inspectedLinks = emptyList()
        inspectionWork?.cancel(); inspectionWork = null
        cancelSessions()
        if (capture !is Capture.Empty) capture = Capture.Empty
    }

    private fun cancelSessions() {
        val active = synchronized(sessions) { sessions.toList().also { sessions.clear() } }
        if (active.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) { active.forEach { runCatching { engine.cancelInspection(it) } } }
    }

    fun cancelInspection() {
        invalidate()
        say("Inspection stopped")
    }

    fun inspect() {
        reviewOpen = true
        val links = Links.extract(draft)
        if (links.isEmpty()) {
            capture = Capture.Failed(draft.trim(), "Paste a full link that starts with https://")
            return
        }
        invalidate()
        val token = generation
        inspectedLinks = links
        capture = Capture.Inspecting(links)
        inspectionWork = viewModelScope.launch {
            if (links.size == 1) inspectSingle(links.first(), token) else inspectBatch(links, token)
        }
    }

    private suspend fun inspectOne(url: String): InspectResult {
        val session = UUID.randomUUID().toString()
        sessions.add(session)
        try {
            return withContext(Dispatchers.IO) { engine.inspect(url, session) }
        } finally { sessions.remove(session) }
    }

    private suspend fun inspectSingle(url: String, token: Int) {
        val result = try { inspectOne(url) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (token == generation) capture = failedCapture(url, e)
            return
        }
        if (token != generation) return
        when (result) {
            is InspectResult.Media -> showMedia(result.media)
            is InspectResult.Collection -> {
                capture = Capture.Empty
                collections.startCollection(result.url, result.title)
            }
        }
    }

    /** A link that could not be read; one the site shows only to signed-in people offers "Sign in to [site]" (any site Keepfin has a sign-in page for). */
    private fun failedCapture(url: String, error: Throwable): Capture.Failed {
        val wall = PhotoPosts.signInWall(url, error)
        if (error is SignInNeeded) return Capture.Failed(url, error.message.orEmpty(), error.site, wall?.key)
        if (wall != null) return Capture.Failed(url, PhotoPosts.signInMessage(wall.name), wall.name, wall.key)
        return Capture.Failed(url, friendly(error))
    }

    /** The site (a core key) whose sign-in page opens first, and the link that is read again once the sign-in is done. */
    var signInRequest by mutableStateOf<String?>(null)
        private set

    /** "Sign in to [site]" in a preview: opens the in-app sign-in at that site's login page; finishing it reads the link again. */
    fun signInTo(key: String?) {
        navigate(Destination.SETTINGS)
        open(SubPage.SIGN_IN)
        signInRequest = key
    }

    /** The sign-in for a link was saved: leave the sign-in page and read the same link again. */
    fun signedInForLink() {
        closeSubPage()
        navigate(Destination.DOWNLOADS)
        inspect()
    }

    private fun showMedia(media: MediaInfo) {
        val settings = engine.settings.value
        // YouTube Music defaults to audio like Spotify/Apple/Deezer; the user can still switch to Video.
        val link = media.webpageUrl ?: media.url
        choice = FormatChoice.initial(media, settings.defaultPreset, link, media.match != null, settings.ytMusicAudio)
        options = settings.options
        reviewOpen = true
        captureVideoLook?.cancel(); captureVideo = null
        capture = Capture.Single(media)
        refreshExisting(media)
    }

    private suspend fun inspectBatch(links: List<String>, token: Int) {
        val items = links.map { BatchItem(it) }.toMutableList()
        batchPreset = engine.settings.value.defaultPreset
        options = engine.settings.value.options
        capture = Capture.Batch(items.toList(), running = true)
        val gate = Semaphore(METADATA_WORKERS)
        val workers = links.mapIndexed { index, url ->
            viewModelScope.launch {
                gate.withPermit {
                    if (token != generation) return@withPermit
                    val outcome = try {
                        when (val result = inspectOne(url)) {
                            is InspectResult.Media -> BatchItem(url, media = result.media)
                            is InspectResult.Collection -> BatchItem(url, error = "This is a collection. Open it on its own to choose items.")
                        }
                    } catch (e: CancellationException) { throw e } catch (e: Exception) { BatchItem(url, error = friendly(e)) }
                    if (token != generation) return@withPermit
                    items[index] = outcome
                    capture = Capture.Batch(items.toList(), running = true)
                }
            }
        }
        workers.forEach { it.join() }
        if (token == generation) capture = Capture.Batch(items.toList(), running = false)
    }

    /** Retries only the links that failed; successful results are kept. */
    fun retryFailedBatch() {
        val current = capture as? Capture.Batch ?: return
        val failed = current.items.filter { it.media == null }.map { it.url }
        if (failed.isEmpty()) return
        val ready = current.items.filter { it.media != null }
        val token = generation
        val items = (ready + failed.map { BatchItem(it) }).toMutableList()
        capture = Capture.Batch(items.toList(), running = true)
        inspectionWork = viewModelScope.launch {
            val gate = Semaphore(METADATA_WORKERS)
            failed.map { url ->
                launch {
                    gate.withPermit {
                        val outcome = try {
                            when (val result = inspectOne(url)) {
                                is InspectResult.Media -> BatchItem(url, media = result.media)
                                is InspectResult.Collection -> BatchItem(url, error = "This is a collection. Open it on its own to choose items.")
                            }
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { BatchItem(url, error = friendly(e)) }
                        if (token != generation) return@withPermit
                        val index = items.indexOfFirst { it.url == url }
                        if (index >= 0) items[index] = outcome
                        capture = Capture.Batch(items.toList(), running = true)
                    }
                }
            }.forEach { it.join() }
            if (token == generation) capture = Capture.Batch(items.toList(), running = false)
        }
    }

    /**
     * Download anyway: the engine skips a recording that is already in the space when the job starts, so the job is queued
     * normally and, if it completes as "Already in <space>", asked to download again.
     */
    private fun forceWhenExisting(url: String) {
        viewModelScope.launch {
            val job = withTimeoutOrNull(60_000) {
                engine.jobs.map { list -> list.firstOrNull { it.url == url && it.status == JobStatus.COMPLETED && it.existing != null } }.first { it != null }
            } ?: return@launch
            try { withContext(Dispatchers.IO) { engine.downloadAnyway(job!!.id) } } catch (e: CancellationException) { throw e } catch (e: Exception) { say(friendly(e, "Could not download this again.")) }
        }
    }

    fun download(anyway: Boolean = false) {
        val media = (capture as? Capture.Single)?.media ?: return
        val request = choice.request(media)
        val chosenOptions = options
        val space = saveTo
        val playlist = playlistTo.takeIf { libraryEnabled }
        val token = generation
        // the video goes only when the box is shown and checked (a song kept as audio, with the video found)
        val video = (captureVideo?.takeIf { it.keep }?.lookup as? app.keepfin.MusicVideoLookup.Found)?.video?.takeIf { offersMusicVideo(media, request) } ?: songVideo.linkVideo(media, request)
        adding = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    StudioEngine.withServiceWork(getApplication()) { engine.enqueue(media, request, chosenOptions, space, video, playlist) }
                }
                if (token == generation) clearDraft()
                say("Added to queue · ${media.title}")
                if (anyway) forceWhenExisting(media.url)
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                say(friendly(e, "Could not add this download."))
            } finally { adding = false }
        }
    }

    fun downloadBatch() {
        val batch = capture as? Capture.Batch ?: return
        val ready = batch.items.mapNotNull { it.media }
        if (ready.isEmpty()) return
        val failed = batch.items.filter { it.media == null }.map { it.url }
        val preset = batchPreset
        val chosenOptions = options
        val space = saveTo
        val playlist = playlistTo.takeIf { libraryEnabled }
        val token = generation
        adding = true
        viewModelScope.launch {
            var added = 0
            try {
                withContext(Dispatchers.IO) {
                    StudioEngine.withServiceWork(getApplication()) {
                        for (media in ready) { engine.enqueue(media, media.gallery?.let { FormatRequest.Gallery(it.count) } ?: FormatChoice.forPreset(preset), chosenOptions, space, null, playlist); added++ }
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                say(friendly(e, "Could not add every download."))
            } finally {
                adding = false
                if (added > 0) say("Added $added ${if (added == 1) "download" else "downloads"} to the queue")
                if (token == generation && added == ready.size) {
                    // Keep links that failed inspection in the draft so they can be retried.
                    if (failed.isEmpty()) clearDraft() else { invalidate(); draft = failed.joinToString("\n"); saved["draft"] = draft }
                }
            }
        }
    }

    // Collections ------------------------------------------------------------------------------
    /** Space chosen by a space's "Add from a link"; the next playlist review starts with Save to on it. */
    private var linkSpace: String? = null

    /**
     * A space's "Add › From a link": the normal Downloads flow with Save to preset to [space] (a single link and a playlist review both show it in the Save to chip).
     */
    fun addLink(url: String, space: String) {
        closeSubPage(); navigate(Destination.DOWNLOADS)
        clearDraft()
        linkSpace = space
        loadLink(url)
        saveTo = space
    }



    // Library operations --------------------------------------------------------------------------
    /** A system consent waiting to be shown (files from before a reinstall). The root composable launches it. */
    var access by mutableStateOf<PendingAccess?>(null)
        private set


    /** True while a delete runs (or waits for the system's consent): the selection bar shows progress and disables itself. */
    var deleting by mutableStateOf(false)
        private set


    // ---- the Trash page ---------------------------------------------------------------------------------------

    /** What the Keepfin trash holds, newest deletion first; null until the first read, empty when the library keeps no trash. */
    var trashEntries by mutableStateOf<List<app.keepfin.TrashEntry>?>(null)
        private set


    /** What a running Delete for good / Empty trash is doing ("Emptying the trash…"); the confirm dialog shows it until it ends. */
    var trashBusy by mutableStateOf<String?>(null)
        private set

    /**
     * Removes the extra copies of duplicates ([removedToKept]: each removed item to the copy that stays). Collections that list a
     * removed copy get the kept one in its place first, so no playlist loses a song; then the extra files are deleted (with Android's
     * consent where it asks).
     */
    /** True while extra copies are being removed (the collections first, then the files): the Duplicates page shows it. */
    var removingDuplicates by mutableStateOf(false)
        private set

    /** How far the removal is: files removed so far and how many in all (null when none runs). */
    var duplicatesProgress by mutableStateOf<Pair<Int, Int>?>(null)
        private set

    // Spaces v2: collections, follow, tags, add from library, smart rules -------------------------

    /** The running or last "Add to a space" operation; the picker shows it, a snackbar reports the result. */
    var libraryAdding by mutableStateOf<String?>(null)
        private set


    /** Opens the smart rules editor on top of the Library. [draft] is the space being created or edited. */
    fun openRules(draft: Space, creating: Boolean) {
        if (destination != Destination.LIBRARY) navigate(Destination.LIBRARY)
        openLibraryDetail(LibraryRoute.Rules(draft, creating))
    }

    /** Replaces the page on top (the rules editor) with another, so Back skips it. */
    fun replaceLibraryRoute(route: LibraryRoute) {
        libraryStack = libraryStack.dropLast(1).let { if (it.isEmpty()) listOf(LibraryRoute.Home) else it } + route
    }



    // "Saved to Music" announcements ---------------------------------------------------------------


    // Jobs --------------------------------------------------------------------------------------

    // Settings & auth --------------------------------------------------------------------------
    fun updateSettings(transform: (EngineSettings) -> EngineSettings) {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { engine.updateSettings(transform) } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { say(friendly(e, "Could not save this setting.")) }
        }
    }

    fun updateDefaultOptions(transform: (DownloadOptions) -> DownloadOptions) = updateSettings { it.copy(options = transform(it.options)) }

    fun updateTools() {
        viewModelScope.launch(Dispatchers.IO) {
            try { engine.updateTools(force = true) } catch (e: Exception) { say(friendly(e, "Could not update the engine.")) }
        }
    }

    fun importCookies(uri: Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val text = resolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readNBytesCompat(COOKIE_LIMIT + 1)
                        require(bytes.size <= COOKIE_LIMIT) { "This cookie file is too large." }
                        String(bytes, Charsets.UTF_8)
                    } ?: error("Cannot read this cookie file.")
                    engine.importCookies(text)
                }
                say("Cookies imported")
            } catch (e: CancellationException) { throw e } catch (e: Exception) { say(friendly(e, "Could not import cookies.")) }
        }
    }

    var signingIn by mutableStateOf(false)
        private set

    fun finishSignIn(onDone: (Int) -> Unit) {
        signingIn = true
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    android.webkit.CookieManager.getInstance().flush()
                    engine.importSignInSession()
                }
                if (count > 0) say("Signed in · $count cookies saved") else say("No sign-in cookies found. Sign in on the site first.")
                onDone(count)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { say(friendly(e, "Could not save the sign-in.")) }
            finally { signingIn = false }
        }
    }

    fun clearSignIn() {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { engine.clearCookies() }
                android.webkit.CookieManager.getInstance().removeAllCookies(null)
                say("Signed out. Saved cookies removed.")
            } catch (e: CancellationException) { throw e } catch (e: Exception) { say(friendly(e, "Could not clear sign-in.")) }
        }
    }

    override fun onCleared() {
        player.release()
        cancelSessions()
        collection?.let { collections.stopCollection(it) }
        super.onCleared()
    }

    companion object {
        const val QUEUE_BEFORE = 100
        const val QUEUE_AFTER = 400
        const val AUTO_INSPECT_DELAY_MS = 650L
        const val METADATA_WORKERS = 2
        const val COLLECTION_PAGE = 200
        const val UPDATE_CHECK_INTERVAL_MS = 6 * 3600_000L
        private const val COOKIE_LIMIT = 2_000_000

        /** Real playback in production; simulated queue state while a fake engine (fixtures, UI tests) is installed. */
        fun defaultPlayer(application: Application, engine: EngineApi): PlayerPort =
            if (StudioEngine.isFake) SimulatedPlayerPort { engine.libraryApi.item(it) } else ControllerPlayerPort(application)

        fun factory(application: Application) = viewModelFactory {
            initializer { StudioViewModel(application, StudioEngine.get(application), createSavedStateHandle()) }
        }

        /** Engine messages are already human; strip exception class prefixes that leak from Java. */
        fun friendly(error: Throwable, fallback: String = "Something went wrong. Try again."): String {
            val message = error.message?.trim().orEmpty()
            if (message.isEmpty()) return fallback
            // Raw engine failures (inspection, discovery) become the same actionable sentences downloads show.
            if (message.contains("ERROR:")) return app.keepfin.ErrorMessages.friendly(message)
            return app.keepfin.ErrorMessages.scrub(message.removePrefix("java.lang.IllegalStateException: ").removePrefix("java.lang.IllegalArgumentException: ")
                .lineSequence().first().take(240)).ifEmpty { fallback }
        }
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}

/** A one-shot request to scroll to and briefly highlight an item or collection ("group:<id>"). */
data class RevealEvent(val seq: Int, val target: String)

/** playlist | album | channel | mix from the link alone; used for the grouped card's label. */
fun collectionKind(url: String): String = app.keepfin.CollectionLogic.collectionKind(url)
