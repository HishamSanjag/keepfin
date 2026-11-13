package app.keepfin.library.repo

import app.keepfin.DeviceMusicState
import app.keepfin.LibraryAccess
import app.keepfin.LibraryBrowse
import app.keepfin.LibraryCollection
import app.keepfin.DeviceMusicApi
import app.keepfin.devicemusic.DeviceMusicManager
import app.keepfin.devicemusic.DeviceMusicPlatform
import app.keepfin.devicemusic.KnownFiles
import app.keepfin.isDeviceItem
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Music that is already on the phone (dev-docs/plans/DEVICE-MUSIC.md): its own device-only table, opened when the repository starts and never
 * touching the store. Deleting such a song asks the person through the system's own consent (the same flow as linked files), and nothing goes
 * to the Keepfin trash (M8); removing a song from Keepfin without deleting the file is [DeviceMusicManager.hide]. Playlist entries of device
 * songs stay on this device (M9).
 */
internal class RepoDevice(
    private val platform: DeviceMusicPlatform?,
    private val dir: File,
    private val log: (String, Throwable?) -> Unit,
    private val linkedMedia: LinkedMedia?,
    private val sdk: Int,
) : DeviceMusicApi {
    /** Set by the repository: the files Keepfin already knows (linked spaces), and what to do after any change here. */
    private var known: () -> List<Pair<String, String>> = { emptyList() }
    private var changed: () -> Unit = {}

    fun attach(known: () -> List<Pair<String, String>>, changed: () -> Unit) { this.known = known; this.changed = changed }

    @Volatile private var manager: DeviceMusicManager? = null
    private val flow = MutableStateFlow<DeviceMusicState>(DeviceMusicState.Off)
    private val linked: () -> LinkedMedia? = { linkedMedia }

    val browse: LibraryBrowse? get() = manager?.browse

    override val deviceMusic: StateFlow<DeviceMusicState> get() = flow
    override fun deviceMusicPermissions(): List<String> = manager?.missingPermissions().orEmpty()
    override fun setDeviceMusic(on: Boolean) { manager?.setOn(on) }
    override fun deviceLeftOut(): List<String> = manager?.leftOut().orEmpty()
    override fun setDeviceLeftOut(folders: List<String>) { manager?.setLeftOut(folders) }
    override fun deviceFolders(): List<String> = manager?.folders().orEmpty()
    override fun hideDeviceItems(ids: List<String>) { manager?.hide(ids.filter(::isDeviceItem)) }
    override fun unhideDevice() { manager?.unhideAll() }
    override fun hiddenDeviceCount(): Int = manager?.hiddenCount ?: 0

    fun start() {
        val p = platform ?: return
        try {
            dir.mkdirs()
            val m = DeviceMusicManager(
                p, File(dir, "device-music.db"), KnownFiles { known() }, log = log,
                onChanged = { flow.value = manager?.state?.value ?: DeviceMusicState.Off; changed() },
            )
            manager = m
            m.start()
            flow.value = m.state.value
        } catch (t: Throwable) {
            log("Music on this phone could not be opened.", t)
        }
    }

    fun shutdown() { manager?.shutdown() }
    fun refreshIfStale() { manager?.refreshIfStale() }

    /** Plays and favorites of a device song are kept in its own table; true when [id] was one. */
    fun recordPlayback(id: String, position: Double?, finished: Boolean): Boolean = isDeviceItem(id).also { if (it) manager?.recordPlayback(id, position, finished) }

    fun setFavorite(id: String, favorite: Boolean, other: () -> Unit) { if (isDeviceItem(id)) manager?.setFavorite(id, favorite) else other() }

    /** The ids that belong to the library itself (device songs are dropped). */
    fun mine(ids: List<String>): List<String> = ids.filter { !isDeviceItem(it) }

    /** Deletes [ids]: the library's own through [others], the device songs through the system's question. */
    fun deleteItems(ids: List<String>, others: (List<String>) -> LibraryAccess): LibraryAccess {
        val (device, mine) = ids.partition(::isDeviceItem)
        if (mine.isNotEmpty()) {
            val access = others(mine)
            if (access !is LibraryAccess.Done) return access
        }
        return if (device.isEmpty()) LibraryAccess.Done else delete(device)
    }

    fun save(id: String?, itemIds: List<String>, saveMine: (List<String>) -> String): String {
        val (theirs, mine) = itemIds.partition(::isDeviceItem)
        val saved = saveMine(mine)
        manager?.let { m ->
            if (id != null) m.overlayDelete(saved)
            if (theirs.isNotEmpty()) m.overlayAdd(saved, theirs)
            changed()
        }
        return saved
    }

    fun deleted(id: String) { manager?.let { it.overlayDelete(id); changed() } }

    fun added(id: String, itemIds: List<String>, addMine: (List<String>) -> Unit) {
        val (theirs, mine) = itemIds.partition(::isDeviceItem)
        if (mine.isNotEmpty() || theirs.isEmpty()) addMine(mine)
        manager?.takeIf { theirs.isNotEmpty() }?.let { it.overlayAdd(id, theirs); changed() }
    }

    fun removed(id: String, itemIds: List<String>, removeMine: (List<String>) -> Unit) {
        val (theirs, mine) = itemIds.partition(::isDeviceItem)
        if (mine.isNotEmpty() || theirs.isEmpty()) removeMine(mine)
        manager?.takeIf { theirs.isNotEmpty() }?.let { it.overlayRemove(id, theirs); changed() }
    }

    /** The playlists with their device songs appended; only while the phone's music is listed. */
    fun withSongs(list: List<LibraryCollection>): List<LibraryCollection> {
        val m = manager ?: return list
        if (m.browse == null) return list
        val extra = m.overlay()
        if (extra.isEmpty()) return list
        return list.map { c -> extra[c.id]?.let { c.copy(itemIds = c.itemIds + it.filter { id -> id !in c.itemIds }) } ?: c }
    }

    /** Deletes the device songs [ids]: the ones Android lets this app delete go at once, the rest come back as one system question. */
    fun delete(ids: List<String>): LibraryAccess {
        val device = manager ?: return LibraryAccess.Done
        val media = linked() ?: throw IllegalStateException("Android didn't allow changing this file.")
        val rows = ids.mapNotNull { id -> device.uriOf(id)?.let { id to it } }
        val gone = ArrayList<String>()
        val present = rows.filter { (id, uri) -> if (media.stat(uri) == null) { gone += id; false } else true }
        // Android 11 and newer ask once for all of them; on 10 each refusal carries its own question
        val needs = if (sdk < 30) emptyList() else media.withoutWriteAccess(present.map { it.second })
        for ((id, uri) in present.filter { it.second !in needs }) {
            try {
                if (media.delete(uri)) gone += id else throw IllegalStateException("Android didn't allow deleting this file.")
            } catch (e: SecurityException) {
                device.forget(gone)
                val sender = media.recoverable(e) ?: throw IllegalStateException("Android didn't allow deleting this file. Allow access to music, then try again.")
                return LibraryAccess.NeedsConfirmation(sender, listOf(id))
            }
        }
        device.forget(gone)
        if (needs.isEmpty()) return LibraryAccess.Done
        return LibraryAccess.NeedsConfirmation(media.consentRequest(needs, true), present.filter { it.second in needs }.map { it.first })
    }

    /** After the system answered a delete question: the songs whose file is gone leave the list. */
    fun confirmed(ids: List<String>, granted: Boolean) {
        val device = manager ?: return
        val media = linked()
        if (granted && media != null) device.forget(ids.filter { id -> device.uriOf(id)?.let { media.stat(it) == null } == true })
        device.refreshSoon(0L)
    }
}
