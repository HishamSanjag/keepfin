package app.keepfin.merge

import android.app.Application
import android.net.Uri
import app.keepfin.KeepfinApplication
import app.keepfin.MediaEngine
import app.keepfin.copies.CopiesRuntime
import app.keepfin.core.net.DeviceSigner
import app.keepfin.core.sqlstate.SqlHost
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.Profile
import app.keepfin.folder.ProfileStore
import app.keepfin.folder.SafFolderAccess
import app.keepfin.folder.sql.AndroidSqlHost
import app.keepfin.net.NetRuntime
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** [MergeEnv] on the phone: the engine's open library, the Devices service, the profile store and the places outside the library. */
class AndroidMergeEnv(private val application: Application) : MergeEnv {
    override val filesDir: File get() = application.filesDir
    override val profiles = ProfileStore(File(application.filesDir, "profiles.json"))
    override val sqlHost: SqlHost = AndroidSqlHost()
    override val signer: DeviceSigner? get() = if (NetRuntime.available) NetRuntime.signer(application) else null
    override val deviceName: String get() = android.os.Build.MODEL ?: "Android"
    override val appVersion: String get() = try { application.packageManager.getPackageInfo(application.packageName, 0).versionName.orEmpty() } catch (e: Exception) { "" }

    private val engine: MediaEngine? get() = (application as? KeepfinApplication)?.takeIf { it.engineCreated }?.engineApi as? MediaEngine

    override fun folder(treeUri: String): FolderAccess = SafFolderAccess.open(application.contentResolver, Uri.parse(treeUri))

    override fun currentProfile(): Profile? = profiles.current()

    override fun offer(deviceId: String): Pair<String, String>? =
        NetRuntime.peek()?.computeState()?.offers?.firstOrNull { it.deviceId == deviceId }?.let { it.libraryId to it.name }

    override fun connectedName(deviceId: String): String? =
        NetRuntime.peek()?.state?.value?.paired?.firstOrNull { it.deviceId == deviceId && it.online }?.name

    override fun offerGap(deviceId: String): String? = NetRuntime.peek()?.offerGap(deviceId)

    override fun openLibrary(): OpenLibrary {
        val repo = engine?.folderLibrary ?: throw MergeFailure("The library is not open.")
        val b = repo.mergeBackend()
        val profile = profiles.current()
        return OpenLibrary(b.store, b.state, b.replicaId, b.store.libraryId, profile?.name.orEmpty(), b.layout, repo.deviceDirectory)
    }

    override fun join(journal: MergeJournal, progress: (String, Int) -> Unit, cancelled: () -> Boolean): JoinOutcome {
        val net = NetRuntime.peek() ?: throw MergeFailure("Devices is not running.")
        val joined = net.joinInto(
            journal.peerDeviceId, journal.tLibraryId, journal.tName, folder(journal.treeUri), app.keepfin.folder.FolderLayout.forLibrary(journal.tLibraryId), cancelled, progress,
        )
        return JoinOutcome(joined.replicaId, joined.actor, joined.name, joined.hostActor)
    }

    override fun peer(journal: MergeJournal): PeerPort? = NetRuntime.peek()?.mergePeer(journal.peerDeviceId)

    override fun reopen() { engine?.reopenFolderLibrary() }

    override val engineReady: Boolean get() = engine != null

    override fun remapOutside(maps: IdMaps) {
        val e = engine
        if (e != null) e.remapLibraryIds(maps) else remapQueueFile(maps)
        val prefs = application.getSharedPreferences("playback-state", android.content.Context.MODE_PRIVATE)
        try {
            val saved = JSONObject(prefs.getString("queue", null) ?: return)
            val old = saved.getJSONArray("ids")
            val ids = JSONArray()
            for (i in 0 until old.length()) maps.item(old.getString(i))?.let { ids.put(it) }
            prefs.edit().putString("queue", saved.put("ids", ids).put("index", 0).put("position", 0).toString()).apply()
        } catch (e: Exception) {
            // no saved queue
        }
    }

    /** The engine is not built yet (recovery before the library opens): the download queue is rewritten on disk, and the engine reads it afterwards. */
    private fun remapQueueFile(maps: IdMaps) {
        val store = app.keepfin.JobStore(File(application.filesDir, "queue.json"))
        val records = store.load()
        if (records.isEmpty()) return
        store.save(records.map { r ->
            val j = r.job
            val ex = j.existing?.let { e -> e.copy(itemId = e.itemId?.let { maps.item(it) }, space = maps.space(e.space) ?: e.space) }
            r.copy(job = j.copy(existing = ex, space = j.space?.let { maps.space(it) ?: it }, collectionId = j.collectionId?.let { maps.collection(it) ?: it }))
        })
    }

    override fun copyRunning(): Boolean = (application as? KeepfinApplication)?.let { CopiesRuntime.of(it)?.isRunning() } == true

    override fun refusal(): String? {
        if (copyRunning()) return "A copy is running. Wait for it to finish, then combine."
        val copies = (application as? KeepfinApplication)?.let { CopiesRuntime.of(it) }
        if (copies != null && copies.state.value.review.isNotEmpty()) return "Some files need a decision first. Open Copies and resolve them, then combine."
        return null
    }

    override fun replayPlays(libraryId: String, plays: List<MergeFence.Play>, maps: IdMaps?) {
        val api = engine?.folderLibrary ?: return
        for (p in plays) {
            val id = maps?.item?.invoke(p.itemId) ?: p.itemId
            runCatching { api.recordPlayback(id, p.position, p.finished) }
        }
    }
}
