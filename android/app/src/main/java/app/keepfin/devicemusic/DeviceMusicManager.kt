package app.keepfin.devicemusic

import app.keepfin.DeviceMusicState
import app.keepfin.LibraryBrowse
import app.keepfin.LibraryItemKind
import app.keepfin.PlaybackRules
import app.keepfin.core.sqlstate.SqlDriver
import app.keepfin.folder.sql.BundledSql
import app.keepfin.folder.sql.IndexBrowse
import app.keepfin.folder.sql.IndexReader
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/** The files Keepfin lists by other means, as the manager can tell them: (content uri, relative path + name) of each linked file. */
internal fun interface KnownFiles { fun all(): Collection<Pair<String, String>> }

/**
 * Music on this phone (dev-docs/plans/DEVICE-MUSIC.md): the switch, the folders left out, the scan, the watch for changes and the
 * play records of device songs, all kept in the device-only [DeviceStore]. Nothing here touches the Keepfin folder's state.
 */
internal class DeviceMusicManager(
    private val platform: DeviceMusicPlatform,
    file: File,
    private val knownFiles: KnownFiles = KnownFiles { emptyList() },
    private val openWriter: (String) -> SqlDriver = { BundledSql.openWriter(it) },
    private val openReader: (String) -> SqlDriver = { BundledSql.openReader(it) },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
    private val onChanged: () -> Unit = {},
    private val debounceMs: Long = 2_000L,
    private val minGapMs: Long = 10_000L,
) {
    private val store = DeviceStore(openWriter(file.path))
    private val reader = IndexReader(file.path, { 0L }, openReader)
    private val browseImpl = IndexBrowse(reader, DeviceBrowseEnv, { it })
    private val flow = MutableStateFlow<DeviceMusicState>(DeviceMusicState.Off)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "keepfin-device-music").also { it.isDaemon = true } }
    private val counted = HashSet<String>()
    private var stopWatching: (() -> Unit)? = null
    private var pending: ScheduledFuture<*>? = null
    @Volatile private var lastScan = 0L
    @Volatile private var scanning = false

    val state: StateFlow<DeviceMusicState> get() = flow

    /** The paged reads of the device songs, or null while the feature is off or has no permission (their rows stay stored, only hidden). */
    val browse: LibraryBrowse? get() = if (flow.value is DeviceMusicState.Off || flow.value is DeviceMusicState.NeedsPermission) null else browseImpl

    fun start() {
        if (store.meta(ON) == "1") enable() else flow.value = DeviceMusicState.Off
    }

    val isOn: Boolean get() = store.meta(ON) == "1"

    fun missingPermissions(): List<String> = platform.missingPermissions()

    /** Turns the feature on or off. On without the permission leaves [DeviceMusicState.NeedsPermission]; call again once it is granted. */
    fun setOn(on: Boolean) {
        store.setMeta(ON, if (on) "1" else "0")
        if (on) enable() else disable()
    }

    private fun enable() {
        if (platform.missingPermissions().isNotEmpty()) {
            stopWatch()
            flow.value = DeviceMusicState.NeedsPermission
            onChanged()
            return
        }
        if (flow.value !is DeviceMusicState.Scanning) flow.value = DeviceMusicState.Ready(store.count())
        startWatch()
        onChanged()
        refreshSoon(0L)
    }

    private fun disable() {
        stopWatch()
        flow.value = DeviceMusicState.Off
        onChanged()
    }

    @Synchronized private fun startWatch() {
        if (stopWatching == null) stopWatching = platform.watch { refreshSoon(debounceMs) }
    }

    @Synchronized private fun stopWatch() {
        stopWatching?.invoke()
        stopWatching = null
        pending?.cancel(false)
    }

    /** Looks again soon: the watch calls it with the debounce, the app calls it when it opens ([refreshIfStale]). */
    @Synchronized fun refreshSoon(delayMs: Long) {
        if (!isOn) return
        pending?.cancel(false)
        pending = scheduler.schedule({ refreshNow() }, delayMs, TimeUnit.MILLISECONDS)
    }

    /** The app opened or came back: a refresh when the last one is older than the minimum gap. */
    fun refreshIfStale() {
        if (isOn && now() - lastScan >= minGapMs) refreshSoon(0L)
    }

    /** Reads the phone's music now and updates the store. Blocking; runs on the manager's own thread, or directly in tests. */
    fun refreshNow(): ScanResult? {
        if (!isOn || scanning) return null
        if (platform.missingPermissions().isNotEmpty()) {
            flow.value = DeviceMusicState.NeedsPermission
            onChanged()
            return null
        }
        scanning = true
        try {
            flow.value = DeviceMusicState.Scanning(0)
            val tails = knownFiles.all().mapNotNullTo(HashSet()) { (uri, _) -> uri.substringAfterLast('/').toLongOrNull() }
            val keys = knownFiles.all().mapTo(HashSet()) { (_, path) -> (path).lowercase(java.util.Locale.ROOT) }
            val roots = platform.ownRoots.map { (it).lowercase(java.util.Locale.ROOT) }
            val known = DeviceScanner.Known { f ->
                f.mediaId in tails || (f.relativePath + f.displayName).lowercase(java.util.Locale.ROOT) in keys || roots.any { (f.relativePath).lowercase(java.util.Locale.ROOT).startsWith(it) }
            }
            val result = DeviceScanner.scan(platform.source, store, leftOut(), known) { found -> flow.value = DeviceMusicState.Scanning(found); onChanged() }
            lastScan = now()
            flow.value = DeviceMusicState.Ready(store.count())
            onChanged()
            return result
        } catch (t: Throwable) {
            log("Music on this phone could not be read.", t)
            flow.value = if (platform.missingPermissions().isNotEmpty()) DeviceMusicState.NeedsPermission else DeviceMusicState.Ready(store.count())
            onChanged()
            return null
        } finally {
            scanning = false
        }
    }

    // ------------------------------------------------------------------------------------------------ the person's choices

    fun leftOut(): List<String> = store.meta(LEFT_OUT)?.let { text -> JSONArray(text).let { a -> List(a.length()) { a.getString(it) } } }.orEmpty()

    fun setLeftOut(folders: List<String>) {
        store.setMeta(LEFT_OUT, JSONArray(folders.map { it.trim() }.filter { it.isNotEmpty() }.distinct()).toString())
        refreshSoon(0L)
    }

    /** The folders the phone's music is in (and the ones already left out), for the "Folders left out" list. */
    fun folders(): List<String> = (store.folders() + leftOut()).distinctBy { (it).lowercase(java.util.Locale.ROOT) }.sortedBy { (it).lowercase(java.util.Locale.ROOT) }

    fun hide(ids: List<String>) { store.hide(ids); changed() }

    fun unhideAll() { store.unhideAll(); changed(); refreshSoon(0L) }

    val hiddenCount: Int get() = store.hiddenCount()

    fun forget(ids: List<String>) { store.forget(ids); changed() }

    fun uriOf(id: String): String? = store.uriOf(id)

    // ------------------------------------------------------------------------------------------------ local favourites, plays, playlists (M9)

    fun setFavorite(id: String, favorite: Boolean) { store.setFavorite(id, favorite); changed() }

    /** Same counting as the library's: a play after the first 30 s or at the end, a resume point past 1 s. */
    fun recordPlayback(id: String, position: Double?, finished: Boolean) {
        val row = store.playRow(id) ?: return
        var plays = row.plays; var last = row.played; var resume = row.position; var watched = row.watched
        if (finished) {
            if (!counted.remove(id)) plays++
            last = now(); resume = null
        } else if (position == null) {
            resume = null
        } else {
            if (position < 5.0) counted.remove(id)
            if (position >= PlaybackRules.COUNT_AFTER_SECONDS && counted.add(id)) plays++
            if (position > 0.0) last = now()
            if (PlaybackRules.watched(position, row.duration)) { watched = true; resume = null }
            else resume = if (PlaybackRules.resumes(LibraryItemKind.AUDIO, row.duration) && position >= 1.0) position else null
        }
        store.setPlay(id, plays, last, resume, watched)
        changed()
    }

    fun overlay(): Map<String, List<String>> = store.overlay()
    fun overlayAdd(collection: String, ids: List<String>) { store.overlayAdd(collection, ids); changed() }
    fun overlayRemove(collection: String, ids: List<String>) { store.overlayRemove(collection, ids); changed() }
    fun overlayDelete(collection: String) { store.overlayDelete(collection); changed() }

    fun shutdown() {
        stopWatch()
        scheduler.shutdown()
        reader.close()
        store.close()
    }

    private fun changed() = onChanged()

    private companion object {
        const val ON = "on"
        const val LEFT_OUT = "leftOut"
    }
}

/** The device index has no folder of its own and no other spaces: an address is the content uri after "device:". */
private object DeviceBrowseEnv : app.keepfin.folder.sql.BrowseEnv {
    override fun modelSpace(wire: String): String = wire
    override fun spaceFolder(modelId: String): String = ""
    override fun uriFor(place: String): String = place.removePrefix(DeviceStore.PLACE)
    override fun linked(id: String, place: String) = null
    override fun jobIdFor(id: String): String? = null
    override fun itemForJob(jobId: String): String? = null
}
