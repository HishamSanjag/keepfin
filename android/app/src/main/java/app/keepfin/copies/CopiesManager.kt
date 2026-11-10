package app.keepfin.copies

import android.content.Context
import android.net.Uri
import android.util.Base64
import app.keepfin.core.copies.ActionKind
import app.keepfin.core.copies.CopyMode
import app.keepfin.core.copies.Holds
import app.keepfin.core.copies.auditDue
import app.keepfin.core.copies.onlineOnlyWords
import app.keepfin.core.copies.PlaceConfig
import app.keepfin.core.copies.PlaceOptions
import app.keepfin.core.copies.ReviewKind
import app.keepfin.core.copies.copyProblemGroups
import app.keepfin.core.copies.copyProblemShort
import app.keepfin.core.copies.placeConfigFrom
import app.keepfin.core.copies.syncReport
import app.keepfin.core.sync.JArr
import app.keepfin.core.sync.JBool
import app.keepfin.core.sync.JNull
import app.keepfin.core.sync.JNum
import app.keepfin.core.sync.JObj
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.Jv
import app.keepfin.core.sync.CanonicalResult
import app.keepfin.core.sync.decodeCanonical
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import app.keepfin.folder.KeepfinFolders
import app.keepfin.folder.OpDraft
import app.keepfin.folder.OpenFolderResult
import app.keepfin.library.repo.CopiesBackend
import app.keepfin.library.repo.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** What the manager needs of the library (the repository on a phone; a small fake in tests). */
internal interface CopiesLibrary {
    fun backend(): CopiesBackend

    /** The ids of the items the smart spaces [wireIds] gather: what a copy that holds a smart space holds. */
    fun smartItems(wireIds: Set<String>): Set<String> = emptySet()
    fun apply(drafts: List<OpDraft>)
    fun observed(itemId: String): Pair<List<String>, List<String>>
    fun versionSum(): Long

    /** Writes whatever the library has not published yet to the Keepfin folder, so a copy never lags it. */
    fun publish() {}
}

internal class RepoCopiesLibrary(private val repo: LibraryRepository) : CopiesLibrary {
    override fun backend() = repo.copiesBackend()
    override fun smartItems(wireIds: Set<String>): Set<String> = repo.smartItemIds(wireIds)
    override fun apply(drafts: List<OpDraft>) = repo.applyCopiesOps(drafts)
    override fun observed(itemId: String): Pair<List<String>, List<String>> =
        repo.observedItem(itemId)?.let { o -> o.tags.map { it.toString() } to o.heads.map { it.toString() } } ?: (emptyList<String>() to emptyList())
    override fun versionSum() = repo.versionSum()
    override fun publish() = repo.publishPending()
}

/** Run results that ended without a verdict on the library: the copy could not be reached, was held by another run, or crashed. */
private val UNFINISHED = setOf("disconnected", "busy", "crashed")

private class Local(var treeUri: String, var lastRunAt: Long = 0, var seenSum: Long = -1)

private class Pending(val draft: CopyDraft, val placeId: String, val replica: String, val created: Boolean)

private class Rt {
    var connected = false
    var running = false
    var abort = AtomicBoolean(false)
    var progress: CopyProgressUi? = null
    var last: JSONObject? = null
    var kept = 0
    var waiting = 0
    var freeBytes: Long? = null
    var previewPending = false
    var error: String? = null
    var paused = false
    var scannedAt = 0L

    /** A run that did not finish: how many times in a row, and when the next automatic try is due (back-off 2, 4, 8, 16, 30 minutes). */
    var retryAttempts = 0
    var retryAt = 0L

    /** Stop keeping this copy: what it is doing, or why it stopped. */
    var removal: CopyRemovalUi? = null

    /** Why a copy that is not connected cannot be reached right now (a cloud copy: no Wi-Fi, no network). */
    var away: String? = null

    /** When a cloud copy was last looked at over the network. */
    var checkedAt = 0L

    /** What the copy keeps, read from its manifest in the main folder (so also while it is away): bytes, hashes of item ids (sorted), manifest keys (sorted). */
    var heldBytes = 0L
    var heldItems: LongArray = LongArray(0)
    var heldKeys: LongArray = LongArray(0)
    var heldRead = false
}

/** What a copy keeps, for the Where your files are counts. [itemHashes] are sorted hashes of item ids ([app.keepfin.where.WhereIds]). */
internal class CopyHeld(val bytes: Long, val itemHashes: LongArray, val keys: LongArray) {
    val items: Long get() = itemHashes.size.toLong()
}

/**
 * The copies of this phone (dev-docs/plans/KEEPFIN-COPIES-SPEC.md sections 2, 7, 8, 10 and 5.8, as built on the desktop in `copiesHost.ts`).
 * The places are `place.set` registers in the library, written through the store, so every device sees them. Where a copy is on this phone
 * (its folder address), when it last ran and what the person decided are device-local (`copies.json`, and `copies/<placeId>/` for the baseline, journal
 * and last run). A sync runs on a background thread over a read-only connection to the library's state; its library-side actions come back
 * through [MainHost] and become ordinary library operations. Nothing here runs on the main thread.
 */
internal class CopiesManager(
    private val context: Context?,
    private val library: CopiesLibrary,
    private val deviceDir: File,
    private val mainTreeUri: String,
    private val device: String,
    private val openAccess: (String) -> FolderAccess,
    private val converter: Mp3Converter? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** Tell the foreground service whether a run is active (null in tests). */
    private val onActive: ((Boolean) -> Unit)? = null,
    private val runOptions: (RunConfig) -> RunConfig = { it },
    /** Folder addresses the app may use (persisted permissions); a copy whose address changed is looked for among them by replica id. */
    private val candidateTrees: () -> List<String> = { emptyList() },
    /** (charging, unmetered) right now; read when a background run is large. */
    private val power: () -> Pair<Boolean, Boolean> = { true to true },
    /** What counts as a large background run (see [Scheduling]); tests lower it. */
    private val largeBytes: Long = Scheduling.LARGE_BYTES,
    private val largeFiles: Int = Scheduling.LARGE_FILES,
    /** Cloud accounts (null: copies go to folders only). A cloud copy's address opens through [openAccess] as well. */
    private val cloud: CloudHooks? = null,
    /** Paired devices (null: copies go to folders and cloud accounts only). A copy on a device runs straight over the network. */
    private val peers: PeerHooks? = null,
    /** Shows what a sync the person started did, once it has finished (a notification; null in tests). */
    private val onReport: ((SyncReportUi) -> Unit)? = null,
) {
    /** The last sync the person started, once it finished. */
    @Volatile private var report: SyncReportUi? = null
    private val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "keepfin-copies").also { it.isDaemon = true } }
    private val flow = MutableStateFlow(CopiesUi())
    val state: StateFlow<CopiesUi> = flow.asStateFlow()

    private val local = LinkedHashMap<String, Local>()
    private val acks = HashSet<String>()
    private val rt = HashMap<String, Rt>()
    private val pending = HashMap<String, Pending>()
    private var places: List<PlaceConfig> = emptyList()
    private var raw: Map<String, Map<String, Jv>> = emptyMap()
    private var started = false

    private fun runtime(id: String) = rt.getOrPut(id) { Rt() }
    private fun stateDir(placeId: String) = File(File(deviceDir, "copies"), placeId)
    private val localFile get() = File(deviceDir, "copies.json")

    // ------------------------------------------------------------------ lifecycle

    @Synchronized
    fun start() {
        if (started) return
        started = true
        loadLocal()
        refresh()
    }

    private fun loadLocal() {
        try {
            val o = JSONObject(localFile.readText())
            val p = o.optJSONObject("places") ?: JSONObject()
            for (k in p.keys()) p.getJSONObject(k).let { local[k] = Local(it.getString("treeUri"), it.optLong("lastRunAt"), it.optLong("seenSum", -1)) }
            val a = o.optJSONArray("acks") ?: JSONArray()
            for (i in 0 until a.length()) acks.add(a.getString(i))
        } catch (e: Exception) {
            // none yet
        }
    }

    @Synchronized
    private fun saveLocal() {
        val p = JSONObject()
        for ((k, v) in local) p.put(k, JSONObject().put("treeUri", v.treeUri).put("lastRunAt", v.lastRunAt).put("seenSum", v.seenSum))
        writeFileAtomic(localFile, JSONObject().put("version", 1).put("places", p).put("acks", JSONArray(acks.toList())).toString().toByteArray(Charsets.UTF_8))
    }

    fun close() {
        for (r in rt.values) r.abort.set(true)
        io.shutdown()
    }

    // ------------------------------------------------------------------ reading the library

    private fun jv(text: String): Jv = (decodeCanonical(text) as? CanonicalResult.Ok)?.value ?: JNull

    /** Reads the places, then the device-side facts about each. */
    @Synchronized
    fun refresh() {
        val b = library.backend()
        val regs = b.read { it.placeRegisters() }
        raw = regs.mapValues { (_, f) -> f.mapValues { jv(it.value) } }
        places = raw.mapNotNull { (id, f) -> if ((f["removed"] as? JBool)?.value == true) null else placeConfigFrom(id, f) }.filter { local.containsKey(it.id) || true }
        val (spaces, playlists) = b.read { s -> s.containers("space").map { it.first to it.second } to s.containers("playlist").map { it.first to it.second } }
        for (p in places) {
            val r = runtime(p.id)
            if (r.last == null) r.last = readLast(p.id)
            r.previewPending = !Relationship.read(stateDir(p.id)).previewConfirmed
            checkConnected(p)
            if (r.connected && !r.running) coverage(p, b, r)
            else if (!r.heldRead && !r.running) coverage(p, b, r, card = false)
        }
        publish(spaces, playlists)
    }

    /** The copy's folder: a paired device's files over the network with the bookkeeping on this phone, or the address opened as any folder. */
    private fun accessFor(placeId: String, tree: String): FolderAccess =
        if (peers?.isPeer(tree) == true) peers.open(tree, stateDir(placeId), library.backend().libraryId) else openAccess(tree)

    private fun isRemote(tree: String?) = cloud?.isCloud(tree) == true || peers?.isPeer(tree) == true

    private fun checkConnected(p: PlaceConfig) {
        val l = local[p.id]
        val r = runtime(p.id)
        if (l != null && peers?.isPeer(l.treeUri) == true) {
            // a device is looked at through its live link (no request): reachable, allowed to keep a copy, and not waiting for Wi-Fi
            val card = peers.card(l.treeUri)
            r.connected = false
            r.away = card?.waiting
            if (card != null && card.waiting == null) {
                val metaAccess = peers.meta(stateDir(p.id))
                val opened = runCatching { KeepfinFolders.open(metaAccess) }.getOrNull()
                r.connected = opened is OpenFolderResult.Ok && opened.folder.replicaId == p.replica && opened.folder.libraryId == library.backend().libraryId
            }
            return
        }
        if (l != null && cloud?.isCloud(l.treeUri) == true) {
            // a cloud copy is looked at over the network: not on every tick (every two minutes while connected, every 15 seconds while waiting)
            val gap = if (r.connected) 120_000L else 15_000L
            if (r.checkedAt != 0L && now() - r.checkedAt < gap && cloud.card(l.treeUri)?.needsSignIn != true) return
            r.checkedAt = now()
        }
        r.connected = false
        if (l == null) return
        try {
            val access = openAccess(l.treeUri)
            val opened = KeepfinFolders.open(access)
            r.connected = opened is OpenFolderResult.Ok && opened.folder.replicaId == p.replica && opened.folder.libraryId == library.backend().libraryId
            if (r.connected) r.freeBytes = runCatching { access.freeBytes() }.getOrNull()
            r.away = null
        } catch (e: FolderError) {
            r.connected = false
            r.away = if (cloud?.isCloud(l.treeUri) == true) e.message else null
        } catch (e: Exception) {
            r.connected = false
        }
        if (!r.connected && !isRemote(l.treeUri)) recognise(p, l, r)
    }

    /** A card or drive that came back under another address: found by the replica id in its folder.json, at most every 20 seconds per copy. */
    private fun recognise(p: PlaceConfig, l: Local, r: Rt) {
        val t = now()
        if (r.scannedAt != 0L && t - r.scannedAt < 20_000) return
        r.scannedAt = t
        val taken = local.filterKeys { it != p.id }.values.map { it.treeUri }.toSet() + mainTreeUri
        val found = Recognition.find(p.replica ?: return, library.backend().libraryId, candidateTrees().filter { it != l.treeUri }, taken, openAccess) ?: return
        l.treeUri = found
        saveLocal()
        r.freeBytes = runCatching { openAccess(found).freeBytes() }.getOrNull()
        r.connected = true
    }

    private fun coverage(p: PlaceConfig, b: CopiesBackend, r: Rt, card: Boolean = true) {
        try {
            val manifest = Manifest.latest(b.folder, p.id)
            val keys = if (manifest != null) Manifest.keptKeys(b.folder, manifest.second) else LongArray(0)
            var total = 0
            var kept = 0
            var bytes = 0L
            val hashes = ArrayList<Long>()
            val smart = heldSmart(p.holds)
            for (e in b.read { it.desiredEntries(b.replicaId, p.holds, p.options.companions, p.options.musicVideos, smart).toList().asSequence() }) {
                total++
                if (java.util.Arrays.binarySearch(keys, Manifest.keyHash(e.itemId, e.contentId, e.path)) >= 0) {
                    kept++
                    bytes += e.bytes
                    hashes.add(app.keepfin.where.WhereIds.hash(e.itemId))
                }
            }
            r.heldBytes = bytes
            r.heldItems = hashes.toLongArray().also { it.sort() }.let { a -> if (a.isEmpty()) a else a.distinct().toLongArray() }
            r.heldKeys = keys
            r.heldRead = true
            if (!card) return
            r.kept = kept
            r.waiting = total - kept
        } catch (e: Exception) {
            // coverage is a convenience; a failed read shows nothing rather than a wrong number
        }
    }

    private fun readLast(placeId: String): JSONObject? = try {
        JSONObject(File(stateDir(placeId), "last-run.json").readText())
    } catch (e: Exception) {
        null
    }

    /** What the smart spaces a place holds gather (empty when it holds none): read once per run, not per file. */
    private fun heldSmart(h: Holds): Set<String> = if (h.smart.isEmpty()) emptySet() else library.smartItems(h.smart)

    private fun holdsUi(h: Holds) = CopyHoldsUi(h.all, h.spaces, h.playlists, h.favourites, h.smart)

    private fun publish(spaces: List<Pair<String, String>>? = null, playlists: List<Pair<String, String>>? = null) {
        val cards = places.map { card(it) }
        val review = ArrayList<CopyReviewUi>()
        for (p in places) review.addAll(reviewOf(p))
        val old = flow.value
        flow.value = CopiesUi(true, cards, review, rt.values.any { it.running }, spaces ?: old.spaces, playlists ?: old.playlists, peers?.let { runCatching { it.devices() }.getOrDefault(emptyList()) }.orEmpty(), report)
    }

    private fun card(p: PlaceConfig): CopyCardUi {
        val r = runtime(p.id)
        val l = local[p.id]
        val last = r.last
        val verified = (raw[p.id]?.get("verified") as? JObj)?.members?.get("at")?.let { (it as? JNum)?.value }
        val reviewCount = reviewOf(p).size
        val problem = problemOf(last)
        var text: String? = null
        val status = when {
            r.running -> CopyStatus.SYNCING
            l == null || !r.connected -> {
                val cc = l?.let { cloud?.card(it.treeUri) }
                val pc = l?.let { peers?.card(it.treeUri) }
                text = when {
                    l == null -> "This copy is on another device. Choose its folder here to use it on this phone."
                    pc != null -> pc.waiting ?: r.away ?: "${pc.deviceName} is not connected right now. The copy continues when it is."
                    cc != null && cc.needsSignIn -> "Sign in again to keep this copy up to date. Nothing is missing."
                    cc != null -> cc.waiting ?: r.away ?: "The account could not be reached. Keepfin will try again. Nothing is missing."
                    else -> "The folder or card is not connected. Nothing is missing."
                }
                CopyStatus.DISCONNECTED
            }
            last?.optString("status") == "refused" -> { text = last.optString("reason"); CopyStatus.REFUSED }
            r.previewPending -> { text = "Check the first sync, then confirm it."; CopyStatus.PREVIEW }
            last?.optString("status") == "held" -> { text = last.optString("holdReason").ifEmpty { "A large change is waiting for you." }; CopyStatus.HELD }
            last?.optString("status") == "diskFull" -> { text = "The copy is full."; CopyStatus.REVIEW }
            r.paused || last?.optString("status") == "paused" -> CopyStatus.PAUSED
            r.error != null -> { text = r.error; CopyStatus.REVIEW }
            reviewCount > 0 -> CopyStatus.REVIEW
            problem != null -> CopyStatus.PROBLEM
            verified == null && last == null -> CopyStatus.NEW
            r.retryAt > now() -> {
                val at = java.time.LocalTime.ofInstant(Instant.ofEpochMilli(r.retryAt), java.time.ZoneId.systemDefault())
                text = "The last try did not finish. Keepfin will try again at %02d:%02d.".format(at.hour, at.minute)
                CopyStatus.WAITING
            }
            r.waiting > 0 -> CopyStatus.WAITING
            else -> CopyStatus.UP_TO_DATE
        }
        val every = (raw[p.id]?.get("schedule") as? JObj)?.members?.get("auto")
        return CopyCardUi(
            p.id, p.name.ifEmpty { "Copy" }, p.mode, holdsUi(p.holds), folderLabel(l?.treeUri), r.connected, status, text, verified?.toLong(), r.kept, r.waiting, reviewCount, r.freeBytes, r.progress,
            p.everyHours, (every as? JBool)?.value != false, p.options.playlistFiles, p.options.flat, p.options.mp3, l?.let { cloud?.card(it.treeUri) }, l?.let { peers?.card(it.treeUri) },
            companions = p.options.companions, musicVideos = p.options.musicVideos, problem = problem, removal = r.removal,
        )
    }

    /** The files that could not be copied at the last sync: not decisions but a problem with a plain reason (words from the shared core). */
    private fun problemOf(last: JSONObject?): CopyProblemUi? {
        val failed = last?.optJSONArray("failed") ?: return null
        val count = maxOf(last.optInt("failedCount", 0), failed.length())
        if (count == 0) return null
        val reasons = (0 until failed.length()).map { failed.getJSONObject(it).optString("reason") }
        val files = (0 until failed.length()).map { failed.getJSONObject(it).getString("path") }
        return CopyProblemUi(count, copyProblemGroups(reasons), files, count - failed.length(), copyProblemShort(count))
    }

    private fun folderLabel(tree: String?): String {
        if (tree == null) return ""
        if (cloud?.isCloud(tree) == true) return cloud.label(tree)
        if (peers?.isPeer(tree) == true) return peers.label(tree)
        val id = runCatching { android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(tree)) }.getOrNull() ?: return tree
        val vol = id.substringBefore(':')
        val rest = id.substringAfter(':', "")
        return (if (vol == "primary") "Phone storage" else "Card $vol") + if (rest.isEmpty()) "" else " / ${rest.replace("/", " / ")}"
    }

    // ------------------------------------------------------------------ review

    private fun ackKey(placeId: String, kind: String, path: String?) = "$placeId|$kind|${path ?: ""}"
    private fun reviewId(key: String) = Base64.encodeToString(key.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun keyOf(id: String) = String(Base64.decode(id, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)

    private fun nameOf(path: String) = path.substringAfterLast('/')

    private fun reviewOf(p: PlaceConfig): List<CopyReviewUi> {
        val last = runtime(p.id).last ?: return emptyList()
        val out = ArrayList<CopyReviewUi>()
        fun make(kind: String, path: String?, title: String, detail: String, actions: List<ReviewAction>) {
            val key = ackKey(p.id, kind, path)
            if (key in acks) return
            out.add(CopyReviewUi(reviewId(key), p.id, p.name.ifEmpty { "Copy" }, kind, title, detail, actions))
        }
        if (last.optString("generation") == "Lower") {
            make("restoredDrive", null, "This drive looks restored from an older backup", "Keepfin will not remove or replace anything on it. If it is the same drive restored from its own backup, say so. If it is a clone, treat it as a separate copy.", listOf(ReviewAction.SAME_DRIVE, ReviewAction.SEPARATE_COPY))
        }
        if (last.optString("status") == "held") make("held", null, "A large change is waiting for you", "${last.optString("holdReason").ifEmpty { "Many files would be removed or replaced." }} Nothing has been changed yet.", listOf(ReviewAction.CONFIRM_HELD))
        val rv = last.optJSONArray("review") ?: JSONArray()
        for (i in 0 until rv.length()) {
            val r = rv.getJSONObject(i)
            val path = r.getString("path")
            val name = nameOf(path)
            val detail = r.optString("detail")
            when (r.getString("kind")) {
                ReviewKind.CopyDeletedProposal.name -> make("copyDeleted", path, "$name was removed from the copy", "Keepfin copied it back. If you meant to delete it everywhere, confirm: it goes to the Keepfin trash and leaves your library.", listOf(ReviewAction.CONFIRM_DELETE, ReviewAction.RESTORE))
                ReviewKind.MissingSource.name -> make("missingSource", path, "$name is missing", "Keepfin cannot find this file in your library or on the copy. Nothing was removed.", listOf(ReviewAction.DISMISS))
                ReviewKind.MissingEverywhere.name -> make("missingEverywhere", path, "$name is missing everywhere", "The file is gone from your library and from the copy. If another copy has it, restore it from there.", listOf(ReviewAction.DISMISS))
                ReviewKind.NameCollision.name -> make("nameCollision", path, "Two files share the name $name", "The copy has two entries that count as one name. Keepfin left both alone.", listOf(ReviewAction.DISMISS))
                ReviewKind.UnknownFile.name -> make("unknownFile", path, "$name is not Keepfin's", "This file is on the copy but Keepfin did not put it there. It is left alone.", listOf(ReviewAction.DISMISS))
                ReviewKind.KeptOnBackup.name -> make("keptOnBackup", path, "$name is kept on the backup", "It was deleted or is no longer held, so the backup keeps it until you clean up.", listOf(ReviewAction.DISMISS))
                ReviewKind.NotHeld.name -> make("notHeld", path, "$name is no longer held", "This copy no longer holds this file. It is left where it is.", listOf(ReviewAction.DISMISS))
                ReviewKind.Difference.name -> make("difference", path, "$name differs", "${detail.ifEmpty { "The two versions differ." }} Confirming keeps both versions: nothing is lost.", listOf(ReviewAction.KEEP_BOTH))
                ReviewKind.OnlineOnly.name -> make("onlineOnly", path, "$name is only in the cloud", detail.ifEmpty { onlineOnlyWords(name) }, listOf(ReviewAction.DISMISS))
                ReviewKind.TooLarge.name -> make("tooLarge", path, "$name is too large for this drive", detail, listOf(ReviewAction.DISMISS))
                ReviewKind.Suspended.name -> make("suspended", path, "Removals are paused", detail.ifEmpty { "Keepfin could not be sure what is on the copy, so it only added files." }, listOf(ReviewAction.DISMISS))
            }
        }
        // files that could not be copied are not decisions: they show as a problem on the copy (problemOf) and are tried again at every sync
        return out
    }

    @Synchronized
    fun resolve(reviewId: String, action: ReviewAction) {
        val key = keyOf(reviewId)
        val parts = key.split('|', limit = 3)
        val placeId = parts[0]
        val target = parts.getOrElse(2) { "" }
        when (action) {
            ReviewAction.CONFIRM_DELETE -> {
                val f = File(stateDir(placeId), "confirmed-deletes.json")
                val list = (runCatching { JSONArray(f.readText()) }.getOrNull() ?: JSONArray()).let { a -> (0 until a.length()).map { a.getString(it) }.toMutableList() }
                if (target !in list) list.add(target)
                writeFileAtomic(f, JSONArray(list).toString().toByteArray())
                acks.add(key)
                saveLocal()
                sync(placeId)
            }
            ReviewAction.SAME_DRIVE -> {
                val l = local[placeId] ?: return
                val gen = Generations.read(accessFor(placeId, l.treeUri))
                Relationship.write(stateDir(placeId), Relationship.read(stateDir(placeId)).let { Relationship(gen, it.previewConfirmed, it.rules, it.auditedAt) })
                File(stateDir(placeId), "last-run.json").delete()
                runtime(placeId).last = null
                sync(placeId)
            }
            ReviewAction.SEPARATE_COPY -> forkCopy(placeId)
            ReviewAction.CONFIRM_HELD -> sync(placeId, confirmHeld = true)
            ReviewAction.TAKE_MAIN, ReviewAction.TAKE_COPY, ReviewAction.KEEP_BOTH, ReviewAction.RESTORE, ReviewAction.DISMISS -> {
                acks.add(key)
                saveLocal()
            }
        }
        publish()
    }

    private fun forkCopy(placeId: String) {
        val l = local[placeId] ?: return
        val access = if (peers?.isPeer(l.treeUri) == true) peers.meta(stateDir(placeId)) else openAccess(l.treeUri)
        val opened = KeepfinFolders.open(access) as? OpenFolderResult.Ok ?: return
        val replica = newId()
        KeepfinFolders.reassignReplica(access, opened.folder, replica, Instant.ofEpochMilli(now()).toString())
        Generations.readFolderJson(access)?.let { }
        setRegisters(placeId, mapOf("replica" to JStr(replica)))
        // a copy on a device keeps its own bookkeeping inside its state folder: only the relationship is forgotten
        if (peers?.isPeer(l.treeUri) == true) stateDir(placeId).listFiles().orEmpty().filter { it.name != "peer" }.forEach { it.deleteRecursively() }
        else stateDir(placeId).deleteRecursively()
        runtime(placeId).last = null
        refresh()
    }

    // ------------------------------------------------------------------ places

    private fun setRegisters(placeId: String, fields: Map<String, Jv>) {
        library.apply(fields.map { (field, value) -> OpDraft("place.set", mapOf("place" to JStr(placeId), "field" to JStr(field), "value" to value)) })
    }

    private fun holdsJv(h: CopyHoldsUi): Jv = JObj(
        mapOf("all" to JBool(h.all), "spaces" to JArr(h.spaces.sorted().map { JStr(it) }), "playlists" to JArr(h.playlists.sorted().map { JStr(it) }), "favourites" to JBool(h.favourites)) +
            // written only when a smart space is held, so the register of a copy that holds none stays as it was
            (if (h.smart.isEmpty()) emptyMap() else mapOf("smart" to JArr(h.smart.sorted().map { JStr(it) }))),
    )
    private fun scheduleJv(every: Int?, auto: Boolean): Jv = JObj(mapOf("everyHours" to (every?.let { JNum(it.toLong()) } ?: JNull), "auto" to JBool(auto)))
    /** [musicVideos] is written only once the person chose it; until then the place carries none and reads as "everything is held" (the core's rule). */
    private fun optionsJv(o: PlaceOptions, musicVideos: Boolean? = null): Jv = JObj(
        mapOf("playlistFiles" to JBool(o.playlistFiles), "flat" to JBool(o.flat), "mp3" to JBool(o.mp3), "companions" to JBool(o.companions), "versionsKeepDays" to JNum(o.versionsKeepDays.toLong()), "deleteGuardPercent" to JNum(o.deleteGuardPercent.toLong()))
            + (musicVideos?.let { mapOf("musicVideos" to JBool(it)) } ?: emptyMap()),
    )

    /** What the person chose for [placeId]'s music videos, or null while they have not. */
    private fun chosenMusicVideos(placeId: String): Boolean? = ((raw[placeId]?.get("options") as? JObj)?.members?.get("musicVideos") as? JBool)?.value

    private fun refusalFor(treeUri: String): String? {
        if (sameOrNested(treeUri, mainTreeUri)) return "A copy cannot be inside your Keepfin folder, or contain it."
        if (local.values.any { it.treeUri == treeUri }) return if (peers?.isPeer(treeUri) == true) "There is already a copy on this device." else "This folder is already one of your copies."
        return null
    }

    /** A paired device as the place of a copy: it must be paired, reachable, and allow this phone to keep a copy on it. */
    private fun checkDevice(address: String): CopyFolderCheck {
        val hooks = peers ?: return CopyFolderCheck(false, "Devices are not available in this version of Keepfin.")
        val id = PeerAddress.deviceOf(address) ?: return CopyFolderCheck(false, "That device is not known.")
        val choice = hooks.devices().firstOrNull { it.deviceId == id } ?: return CopyFolderCheck(false, "That device is not paired with this one.")
        if (!choice.allowed) return CopyFolderCheck(false, choice.why ?: "That device does not allow a copy on it.")
        refusalFor(address)?.let { return CopyFolderCheck(false, it) }
        return CopyFolderCheck(true, null, choice.name, false, 0, null)
    }

    private fun docId(tree: String): String = runCatching { android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(tree)) }.getOrDefault(tree)

    private fun sameOrNested(a: String, b: String): Boolean {
        val x = docId(a).trimEnd('/')
        val y = docId(b).trimEnd('/')
        return x == y || x.startsWith("$y/") || y.startsWith("$x/") || (x.endsWith(":") && x.substringBefore(':') == y.substringBefore(':')) || (y.endsWith(":") && x.substringBefore(':') == y.substringBefore(':'))
    }

    /** Looks at a picked folder before the wizard goes on. */
    fun checkFolder(treeUri: String): CopyFolderCheck {
        if (peers?.isPeer(treeUri) == true) return checkDevice(treeUri)
        refusalFor(treeUri)?.let { return CopyFolderCheck(false, it) }
        return try {
            val access = openAccess(treeUri)
            val label = folderLabel(treeUri).substringAfterLast(" / ")
            val backend = library.backend()
            var isCopy = false
            when (val opened = KeepfinFolders.open(access)) {
                is OpenFolderResult.Ok -> {
                    if (opened.folder.libraryId != backend.libraryId) return CopyFolderCheck(false, "This folder belongs to a different Keepfin library.")
                    if (opened.folder.replicaId == backend.replicaId) return CopyFolderCheck(false, "This is your Keepfin folder itself.")
                    if (places.any { it.replica == opened.folder.replicaId }) return CopyFolderCheck(false, "This is already one of your copies.")
                    isCopy = true
                }
                OpenFolderResult.Corrupt -> return CopyFolderCheck(false, "This Keepfin folder is damaged.")
                else -> {}
            }
            val existing = runCatching { access.list("").entries.count { it.name != ".keepfin" } }.getOrDefault(0)
            CopyFolderCheck(true, null, label.ifEmpty { "Copy" }, isCopy, existing, runCatching { access.freeBytes() }.getOrNull())
        } catch (e: FolderError.NeedsAccess) {
            CopyFolderCheck(false, "Keepfin does not have access to this folder. Choose it again.")
        } catch (e: FolderError.UnsupportedProvider) {
            CopyFolderCheck(false, e.message)
        } catch (e: FolderError) {
            CopyFolderCheck(false, e.message ?: "This folder cannot be used.")
        }
    }

    /** Makes a folder a Keepfin folder of this library (its own replica id, generation 0) unless it already is one. */
    private fun prepareCopyFolder(access: FolderAccess, libraryId: String): Pair<String, Boolean> {
        val opened = KeepfinFolders.open(access)
        if (opened is OpenFolderResult.Ok) {
            if (opened.folder.libraryId != libraryId) throw IllegalStateException("This folder belongs to a different Keepfin library.")
            return opened.folder.replicaId to false
        }
        if (opened !is OpenFolderResult.NotAKeepfinFolder && opened !is OpenFolderResult.Missing) throw IllegalStateException("This folder cannot be used as a copy.")
        for (sub in listOf("ops", "checkpoints", "art", "versions", "trash", "places")) access.mkdirs(".keepfin/$sub")
        val replica = newId()
        val json = JSONObject().put("format", 1).put("libraryId", libraryId).put("replicaId", replica).put("createdAt", Instant.ofEpochMilli(now()).toString()).put("generation", 0)
        access.publish(Generations.FOLDER_JSON, json.toString(2).toByteArray(Charsets.UTF_8))
        access.publish(".keepfin/README.txt", "A copy of a Keepfin library's files. Do not edit; deleting it loses earlier versions.".toByteArray())
        access.publish(".keepfin/.nomedia", ByteArray(0))
        return replica to true
    }

    private fun removeFreshCopyFolder(access: FolderAccess) {
        val allowed = setOf("ops", "checkpoints", "art", "versions", "trash", "places", "folder.json", "README.txt", ".nomedia", "lock")
        try {
            val names = access.list(".keepfin").entries
            if (names.any { it.name !in allowed }) return
            for (sub in listOf("ops", "checkpoints", "art", "versions", "trash", "places")) if (access.list(".keepfin/$sub").entries.isNotEmpty()) return
            access.remove(".keepfin")
        } catch (e: Exception) {
            // leave it
        }
    }

    private fun placeFrom(id: String, d: CopyDraft, replica: String) = PlaceConfig(id, "folder", d.name, replica, d.mode, Holds(d.holds.all, d.holds.spaces, d.holds.playlists, d.holds.favourites, d.holds.smart), PlaceOptions(companions = d.companions, musicVideos = d.musicVideos ?: d.holds.all), d.everyHours)

    /** The real plan of the first sync, additive only (nothing is executed, no generation bumped, no baseline written). */
    fun preview(draft: CopyDraft, previousId: String? = null): CopyPreview {
        checkFolder(draft.treeUri).let { if (!it.ok) return CopyPreview(previousId ?: "", "refused", it.problem) }
        if (peers?.isPeer(draft.treeUri) == true) return previewPeer(draft, previousId)
        var entry = previousId?.let { pending[it] }
        if (entry != null && entry.draft.treeUri != draft.treeUri) {
            cancelPreview(entry.placeId)
            entry = null
        }
        val access = openAccess(draft.treeUri)
        if (entry == null) {
            val (replica, created) = try {
                prepareCopyFolder(access, library.backend().libraryId)
            } catch (e: FolderError) {
                return CopyPreview(previousId ?: "", "refused", e.message)
            } catch (e: IllegalStateException) {
                return CopyPreview(previousId ?: "", "refused", e.message)
            }
            entry = Pending(draft, newId(), replica, created)
            pending[entry.placeId] = entry
        } else pending[entry.placeId] = Pending(draft, entry.placeId, entry.replica, entry.created)
        val place = placeFrom(entry.placeId, draft, entry.replica)
        val dir = stateDir(entry.placeId).also { it.mkdirs() }
        val report = executeRun(place, access, dir, dryRun = true, flags = Flags(), tree = draft.treeUri)
        return previewFrom(entry.placeId, report)
    }

    /** The real plan of the first sync of a copy on a paired device, additive only: it reads the device and writes nothing to it. */
    private fun previewPeer(draft: CopyDraft, previousId: String?): CopyPreview {
        val hooks = peers ?: return CopyPreview(previousId ?: "", "refused", "Devices are not available in this version of Keepfin.")
        val libraryId = library.backend().libraryId
        var entry = previousId?.let { pending[it] }
        if (entry != null && entry.draft.treeUri != draft.treeUri) {
            cancelPreview(entry.placeId)
            entry = null
        }
        if (entry == null) {
            val placeId = newId()
            val dir = stateDir(placeId).also { it.mkdirs() }
            val (replica, _) = try {
                prepareCopyFolder(hooks.meta(dir), libraryId)
            } catch (e: FolderError) {
                stateDir(placeId).deleteRecursively()
                return CopyPreview(previousId ?: "", "refused", e.message)
            }
            entry = Pending(draft, placeId, replica, true)
        } else entry = Pending(draft, entry.placeId, entry.replica, entry.created)
        pending[entry.placeId] = entry
        val dir = stateDir(entry.placeId).also { it.mkdirs() }
        val place = placeFrom(entry.placeId, draft, entry.replica).copy(kind = "device")
        val report = executeRun(place, hooks.open(draft.treeUri, dir, libraryId), dir, dryRun = true, flags = Flags(), tree = draft.treeUri)
        return previewFrom(entry.placeId, report)
    }

    fun cancelPreview(placeId: String) {
        val entry = pending.remove(placeId) ?: return
        rt.remove(placeId)
        peers?.forget(stateDir(placeId))
        stateDir(placeId).deleteRecursively()
        if (entry.created && peers?.isPeer(entry.draft.treeUri) != true) runCatching { removeFreshCopyFolder(openAccess(entry.draft.treeUri)) }
    }

    @Synchronized
    fun add(placeId: String) {
        val entry = pending.remove(placeId) ?: throw IllegalStateException("This preview has expired. Start again.")
        val d = entry.draft
        val dir = stateDir(placeId).also { it.mkdirs() }
        Relationship.write(dir, Relationship(null, true))
        local[placeId] = Local(d.treeUri)
        saveLocal()
        setRegisters(
            placeId,
            linkedMapOf(
                "kind" to JStr(if (peers?.isPeer(d.treeUri) == true) "device" else "folder"), "name" to JStr(d.name), "replica" to JStr(entry.replica), "mode" to JStr(modeWire(d.mode)),
                "holds" to holdsJv(d.holds), "options" to optionsJv(PlaceOptions(companions = d.companions), d.musicVideos), "schedule" to scheduleJv(d.everyHours, d.auto),
            ),
        )
        refresh()
        sync(placeId, previewConfirmed = true)
    }

    private fun modeWire(m: CopyMode) = when (m) { CopyMode.TwoWay -> "twoWay"; CopyMode.Backup -> "backup"; CopyMode.Mirror -> "mirror" }

    /** Uses a copy this library already knows (made on another phone, or on a card that came back under another address) from [treeUri] here. */
    @Synchronized
    fun reconnect(placeId: String, treeUri: String): String? {
        val place = places.firstOrNull { it.id == placeId } ?: return "That copy is gone."
        if (peers?.isPeer(treeUri) == true) return "A copy on another device is set up on the phone that made it."
        refusalFor(treeUri)?.takeIf { local[placeId]?.treeUri != treeUri }?.let { return it }
        val access = try { openAccess(treeUri) } catch (e: FolderError) { return e.message }
        val opened = KeepfinFolders.open(access) as? OpenFolderResult.Ok ?: return "This is not a Keepfin copy."
        if (opened.folder.replicaId != place.replica) return "This is a different copy than ${place.name.ifEmpty { "the one" }} you chose."
        val dir = stateDir(placeId).also { it.mkdirs() }
        if (local[placeId] == null) Relationship.write(dir, Relationship(null, true))
        local[placeId] = Local(treeUri, local[placeId]?.lastRunAt ?: 0)
        saveLocal()
        refresh()
        sync(placeId)
        return null
    }

    @Synchronized
    fun edit(placeId: String, name: String? = null, holds: CopyHoldsUi? = null, everyHours: Int? = null, auto: Boolean? = null, exports: Triple<Boolean, Boolean, Boolean>? = null) {
        val p = places.firstOrNull { it.id == placeId } ?: throw IllegalStateException("That copy is gone.")
        val fields = LinkedHashMap<String, Jv>()
        if (name != null && name.isNotBlank() && name.trim() != p.name) fields["name"] = JStr(name.trim().take(80))
        var holdsChanged = false
        if (holds != null && holds != holdsUi(p.holds)) { fields["holds"] = holdsJv(holds); holdsChanged = true }
        if (everyHours != null || auto != null) {
            val cur = (raw[placeId]?.get("schedule") as? JObj)?.members?.get("auto")
            fields["schedule"] = scheduleJv(if (everyHours == 0) null else everyHours ?: p.everyHours, auto ?: ((cur as? JBool)?.value != false))
        }
        if (exports != null) {
            if (peers?.isPeer(local[placeId]?.treeUri) == true) throw IllegalStateException("Playlist files and other exports are for copies on a folder or drive.")
            val o = p.options.copy(playlistFiles = exports.first, flat = exports.second, mp3 = exports.third)
            if (o != p.options) { fields["options"] = optionsJv(o, chosenMusicVideos(placeId)); holdsChanged = true }
        }
        if (fields.isNotEmpty()) {
            setRegisters(placeId, fields)
            refresh()
            if (holdsChanged) sync(placeId)
        }
    }

    /** Lyrics, subtitles, info files and covers go with their items, or not; turned off, the ones already on the copy stay. */
    @Synchronized
    fun setCompanions(placeId: String, on: Boolean) {
        val p = places.firstOrNull { it.id == placeId } ?: throw IllegalStateException("That copy is gone.")
        if (p.options.companions == on) return
        setRegisters(placeId, mapOf("options" to optionsJv(p.options.copy(companions = on), chosenMusicVideos(placeId))))
        refresh()
        sync(placeId)
    }

    /** Songs' music videos go with them, or not; turned off, the ones already on the copy stay. This is the person's choice: from here it no longer follows what the copy holds. */
    @Synchronized
    fun setMusicVideos(placeId: String, on: Boolean) {
        val p = places.firstOrNull { it.id == placeId } ?: throw IllegalStateException("That copy is gone.")
        if (p.options.musicVideos == on && chosenMusicVideos(placeId) == on) return
        setRegisters(placeId, mapOf("options" to optionsJv(p.options.copy(musicVideos = on), on)))
        refresh()
        sync(placeId)
    }

    @Synchronized
    fun remove(placeId: String) {
        val place = places.firstOrNull { it.id == placeId } ?: return
        val r = runtime(placeId)
        // a second tap, or the screen opened again: the removal already running is the only one
        if (r.removal?.failed == false) return
        val wasPaused = r.paused
        fun step(text: String) { r.removal = CopyRemovalUi(false, text); publish() }
        step("Stopping the copy…")
        try {
            pause(placeId)
            step("Forgetting the copy…")
            setRegisters(placeId, mapOf("removed" to JBool(true)))
            local.remove(placeId)
            acks.removeAll { it.startsWith("$placeId|") }
            saveLocal()
            peers?.forget(stateDir(placeId))
            stateDir(placeId).deleteRecursively()
        } catch (e: Exception) {
            r.paused = wasPaused
            r.removal = CopyRemovalUi(true, e.message ?: "Keepfin could not stop keeping this copy.")
            publish()
            throw e
        }
        rt.remove(placeId)
        report = SyncReportUi("${place.name.ifEmpty { "The copy" }} was removed", "Its files stay where they are.", false, System.currentTimeMillis())
        refresh()
    }

    // ------------------------------------------------------------------ running

    class Flags(val previewConfirmed: Boolean = false, val confirmHeld: Boolean = false, val audit: Boolean = false)

    /** Starts a sync of one place on a background thread (one run per place). Returns false when it is already running or cannot start. */
    @Synchronized
    fun sync(placeId: String, previewConfirmed: Boolean = false, confirmHeld: Boolean = false, audit: Boolean = false): Boolean = start(placeId, Flags(previewConfirmed, confirmHeld, audit), null)

    /**
     * Sync now and Sync all: the copies the person asked for, then one report of what they did (the page and a notification), even when
     * there was nothing to do. A copy that is already syncing reports through its own run.
     */
    @Synchronized
    fun syncByPerson(placeIds: List<String>) {
        val results = java.util.Collections.synchronizedMap(HashMap<String, RunReport?>())
        val started = placeIds.filter { id ->
            runtime(id).paused = false
            !runtime(id).running && local[id] != null
        }
        if (started.isEmpty()) return
        report = null
        val left = java.util.concurrent.atomic.AtomicInteger(started.size)
        val finished = { id: String, run: RunReport? ->
            results[id] = run
            if (left.decrementAndGet() == 0) reportSync(started.associateWith { results[it] })
        }
        for (id in started) if (!start(id, Flags(), finished)) finished(id, null)
    }

    private fun reportSync(done: Map<String, RunReport?>) {
        val nameOf = { id: String -> places.firstOrNull { it.id == id }?.name ?: "A copy" }
        var synced = 0
        var changed = 0
        var problems = 0
        val notReached = ArrayList<String>()
        val needOk = ArrayList<String>()
        var onCloud = false
        for ((id, run) in done) when (run?.status) {
            "paused" -> Unit
            "done" -> { synced++; changed += run.exec?.committed ?: 0; problems += run.exec?.failedCount ?: 0 }
            "held", "refused", "preview" -> needOk.add(nameOf(id))
            else -> {
                notReached.add(nameOf(id))
                onCloud = cloud?.isCloud(local[id]?.treeUri) == true
            }
        }
        if (synced == 0 && notReached.isEmpty() && needOk.isEmpty()) return
        val words = syncReport(synced, changed, problems, notReached, needOk, onCloud)
        val made = SyncReportUi(words.title, words.detail, words.warn, now())
        synchronized(this) {
            report = made
            publish()
        }
        onReport?.invoke(made)
    }

    @Synchronized
    private fun start(placeId: String, flags: Flags, then: ((String, RunReport?) -> Unit)?): Boolean {
        val r = runtime(placeId)
        if (r.running || local[placeId] == null) return false
        if (app.keepfin.merge.MergeFence.copiesWait) return false
        r.running = true
        r.paused = false
        r.error = null
        r.abort.set(false)
        onActive?.invoke(true)
        publish()
        io.execute {
            var run: RunReport? = null
            try {
                run = runNow(placeId, flags)
            } catch (t: Throwable) {
                r.error = t.message ?: t.javaClass.simpleName
            } finally {
                synchronized(this) {
                    r.running = false
                    r.progress = null
                    if (rt.values.none { it.running }) onActive?.invoke(false)
                    publish()
                }
                then?.invoke(placeId, run)
            }
        }
        return true
    }

    fun syncAll() = syncByPerson(places.map { it.id })

    fun pause(placeId: String? = null) {
        for ((id, r) in rt) if (placeId == null || id == placeId) {
            r.paused = true
            r.abort.set(true)
        }
        publish()
    }

    fun isRunning(): Boolean = rt.values.any { it.running }

    /** The copies this library lists, by the replica each one holds: replica id -> place id. */
    /** What each copy keeps of the library (for Where your files are): from its last coverage; empty arrays until it was read once. */
    @Synchronized fun heldByCopy(): Map<String, CopyHeld> = places.associate { p -> runtime(p.id).let { r -> p.id to CopyHeld(r.heldBytes, r.heldItems, r.heldKeys) } }

    @Synchronized fun knownReplicas(): Map<String, String> = places.mapNotNull { p -> p.replica?.let { it to p.id } }.toMap()

    /** The addresses of the cloud copies on this phone. */
    @Synchronized fun cloudAddresses(): List<String> = local.values.map { it.treeUri }.filter { cloud?.isCloud(it) == true }

    /** Null when the copy is gone or could not be reached. */
    private fun runNow(placeId: String, flags: Flags): RunReport? {
        val place = places.firstOrNull { it.id == placeId } ?: return null
        val l = local[placeId] ?: return null
        val r = runtime(placeId)
        val access = try { accessFor(placeId, l.treeUri) } catch (e: FolderError) {
            r.connected = false
            r.error = e.message
            return null
        }
        val dir = stateDir(placeId).also { it.mkdirs() }
        val confirmedFile = File(dir, "confirmed-deletes.json")
        val confirmed = runCatching { JSONArray(confirmedFile.readText()).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
        val before = library.versionSum()
        val report = executeRun(place, access, dir, false, flags, l.treeUri, confirmed.toSet())
        if (confirmed.isNotEmpty() && report.status == "done") confirmedFile.delete()
        val summary = summarise(report)
        r.last = summary
        writeFileAtomic(File(dir, "last-run.json"), summary.toString().toByteArray(Charsets.UTF_8))
        l.lastRunAt = now()
        // Only a run that finished marks the changes as seen (what it wrote itself is not "the library changed"); one that could not
        // reach the copy, found it held or crashed keeps them unseen and tries again with back-off.
        if (report.status in UNFINISHED) {
            r.retryAttempts++
            r.retryAt = now() + minOf(30L, 1L shl r.retryAttempts.coerceAtMost(5)) * 60_000L
        } else {
            l.seenSum = library.versionSum().also { if (it < before) Unit }
            r.retryAttempts = 0
            r.retryAt = 0L
        }
        saveLocal()
        r.paused = report.status == "paused"
        r.connected = report.status != "disconnected"
        val m = report.manifest
        if (report.status == "done" && m != null) {
            runCatching { setRegisters(placeId, mapOf("verified" to JObj(mapOf("at" to JNum(now()), "manifest" to JNum(m.n.toLong()), "sha256" to JStr(m.sha256), "generation" to JNum(report.generation.committedAt ?: 0))))) }
        }
        refresh()
        return report
    }

    private fun executeRun(place: PlaceConfig, copy: FolderAccess, dir: File, dryRun: Boolean, flags: Flags, tree: String, confirmed: Set<String> = emptySet()): RunReport {
        // publish before copying: operations wait in the outbox for a while (batched flushes) and the copy must not lag them
        // (dev-docs/plans/KEEPFIN-DATA-COMPACTION.md R7). A failed flush changes nothing: the ops are safe in the outbox.
        runCatching { library.publish() }
        val b = library.backend()
        val r = runtime(place.id)
        val host = LibraryHost(b, library, newId, now)
        val deleted = b.read { it.deletedItems() }
        val onCloud = cloud?.isCloud(tree) == true
        val onPeer = peers?.isPeer(tree) == true
        val cfg = RunConfig(
            // a cloud lock is advisory and slow to refresh (every 30 s, stale after 5 minutes); two phones of one model must not mistake each other for one device.
            // A device's lock is its own lease (60 s): it is kept alive every 15 s
            lockHeartbeatMs = if (onCloud) 30_000L else if (onPeer) 15_000L else CopyLock.HEARTBEAT_MS, lockStaleMs = if (onCloud) 300_000L else CopyLock.STALE_MS,
            relistDelayMs = if (onCloud) 1_500L else 300L,
            main = b.folder, copy = copy, stateDir = dir, placeId = place.id, mode = place.mode, device = if (onCloud) "$device (${b.replicaId.take(8)})" else device,
            desired = heldSmart(place.holds).let { smart -> b.read { it.desiredEntries(b.replicaId, place.holds, place.options.companions, place.options.musicVideos, smart).toList().asSequence() } },
            volumeFormat = if (onCloud || onPeer) null else VolumeFormat.of(tree),
            deleted = deleted, confirmedDeletes = confirmed,
            holdsKey = holdsKey(place.holds), optionsKey = optionsKey(place.options), deleteGuardPercent = place.options.deleteGuardPercent, companions = place.options.companions, musicVideos = place.options.musicVideos,
            previewConfirmed = flags.previewConfirmed, confirmHeld = flags.confirmHeld, dryRun = dryRun, audit = flags.audit,
            host = host, parallelism = 2, now = now, abort = { r.abort.get() },
            onProgress = { p ->
                r.progress = CopyProgressUi(p.doneActions, p.totalActions, p.doneBytes, p.totalBytes, p.current)
                publish()
            },
            refusal = refusalFor(tree)?.takeIf { dryRun.not() && local[place.id]?.treeUri != tree },
            mainReplica = b.replicaId, copyReplica = place.replica,
        )
        val report = CopyRunner.run(runOptions(cfg))
        // a device keeps what it displaces in its own trash and holds its own library data: nothing but the media goes there
        if (!dryRun && report.status == "done" && !onPeer) {
            val access = copy
            runCatching {
                if (place.mode == CopyMode.Backup) Versions.pruneVersions(access, now(), place.options.versionsKeepDays) else Versions.purgeTrash(access, now())
            }
            runCatching { CatalogueCompaction.compactAll(access) }
            runCatching { copyMetadata(b.folder, access, b.libraryId) }
            if (place.options.playlistFiles || place.options.flat || place.options.mp3 || access.stat(Exports.RECORD) != null) {
                runCatching {
                    val names = NameMap.load(access)
                    val smart = heldSmart(place.holds)
                    val entries = b.read { it.desiredEntries(b.replicaId, place.holds, false, false, smart).toList() }
                    val metas = HashMap<String, ExportItem>()
                    val playlists = b.read { s -> s.containers("playlist").map { ExportPlaylist(it.first, it.second, it.third) } }
                    val out = Exports.sync(
                        Exports.Job(access, names, place.holds, place.options, entries, { id -> metas.getOrPut(id) { b.read { s -> s.itemMeta(id) }.let { ExportItem(it.first, it.second.ifEmpty { id }) } } }, { playlists }, converter, File(dir, "exports-tmp"), r.abort::get),
                    )
                    exportsNote[place.id] = out
                }
            }
        }
        return report
    }

    val exportsNote = HashMap<String, ExportsReport>()

    /**
     * Copies the library's data folders (`ops`, `checkpoints`, `art`) to the copy, files it lacks or holds shorter. A segment file that a
     * larger file of the main folder covers is not copied. Nothing is deleted from the copy except segment files that a larger file on the
     * copy covers (op for op) and that the main folder no longer lists (dev-docs/plans/KEEPFIN-DATA-COMPACTION.md R5, R6).
     */
    internal fun copyMetadata(main: FolderAccess, copy: FolderAccess, libraryId: String): Int {
        var copied = 0
        val mainOps = app.keepfin.net.SegmentFiles.list(main)
        val covered = SegmentPrune.coveredByName(mainOps).map { it.first.path }.toSet()
        for (dir in listOf("ops", "checkpoints", "art")) {
            val stack = ArrayList<String>().apply { add(".keepfin/$dir") }
            while (stack.isNotEmpty()) {
                val d = stack.removeAt(stack.size - 1)
                val listing = try { main.list(d) } catch (e: FolderError.NotFound) { continue }
                for (e in listing.entries) {
                    val p = "$d/${e.name}"
                    if (e.isDirectory) { stack.add(p); continue }
                    if (e.name.endsWith(".tmp") || e.name.contains(".kftmp")) continue
                    if (p in covered) continue
                    val have = copy.stat(p)
                    if (have != null && !have.isDirectory && have.size >= e.size) continue
                    copy.mkdirs(parentPath(p))
                    if (have != null) copy.remove(p)
                    main.openRead(p).use { copy.publishFrom(p, it) }
                    copied++
                }
            }
        }
        // the copy's own pile: files a merged file on the copy covers, once the main folder has dropped them too
        val listed = mainOps.map { "${it.actor}/${it.name}" }.toSet()
        runCatching { SegmentPrune.pruneCovered(copy, libraryId) { "${it.actor}/${it.name}" in listed } }
        return copied
    }

    private fun holdsKey(h: Holds) = if (h.all) "all" else JSONArray(listOf(h.spaces.sorted(), h.playlists.sorted(), h.favourites, h.smart.sorted()).map { if (it is Collection<*>) JSONArray(it) else it }).toString()
    private fun optionsKey(o: PlaceOptions) = JSONArray(listOf(o.playlistFiles, o.flat, o.mp3, o.versionsKeepDays, o.deleteGuardPercent, o.companions, o.musicVideos)).toString()

    // ------------------------------------------------------------------ summaries

    private fun summarise(report: RunReport): JSONObject {
        val o = JSONObject().put("status", report.status).put("reason", report.reason ?: "").put("held", report.held).put("holdReason", report.holdReason ?: "")
            .put("generation", report.generation.state.name).put("finishedAt", now()).put("additiveOnly", report.additiveOnly)
        val rv = JSONArray()
        for (x in report.review.take(200)) rv.put(JSONObject().put("kind", x.kind.name).put("path", x.path).put("detail", x.detail))
        o.put("review", rv).put("reviewCount", report.reviewCount)
        val failed = JSONArray()
        for (f in report.exec?.failed.orEmpty().take(50)) failed.put(JSONObject().put("path", f.path).put("reason", f.reason))
        o.put("failed", failed).put("failedCount", report.exec?.failedCount ?: 0).put("committed", report.exec?.committed ?: 0).put("bytes", report.exec?.bytes ?: 0)
        return o
    }

    private val lineLabels = listOf(
        ActionKind.CopyTo to "Put on the copy", ActionKind.MoveOnCopy to "Rename on the copy", ActionKind.RemoveFromCopy to "Remove from the copy",
        ActionKind.ImportNew to "Add to your library from the copy", ActionKind.ReviseFromCopy to "Update your library from the copy",
        ActionKind.RestoreFromCopy to "Bring back from the copy", ActionKind.RestoreEditedDeleted to "Restore an edited file for review", ActionKind.DeleteItem to "Delete from your library (you confirmed)",
    )

    private fun previewFrom(placeId: String, r: RunReport): CopyPreview {
        if (r.status == "refused") return CopyPreview(placeId, "refused", r.reason)
        if (r.status == "disconnected") return CopyPreview(placeId, "disconnected", r.reason ?: "The folder is not connected.")
        if (r.status == "busy") return CopyPreview(placeId, "busy", r.reason)
        val s = r.summary
        val lines = lineLabels.mapNotNull { (k, label) -> (s?.counts?.get(k) ?: 0).takeIf { it > 0 }?.let { CopyPlanLine(label, it, s?.bytes?.get(k) ?: 0) } }
        return CopyPreview(
            placeId, if (r.held) "held" else "ready", r.holdReason, r.additiveOnly, lines, s?.counts?.get(ActionKind.CopyTo) ?: 0, s?.bytes?.get(ActionKind.CopyTo) ?: 0, s?.destructive ?: 0,
            r.review.take(20).map { it.path to it.detail.ifEmpty { it.kind.name } }, maxOf(0, r.reviewCount - 20), s?.copySpace?.free, s?.copySpace?.enough,
            (s?.counts?.get(ActionKind.ImportNew) ?: 0) + (s?.counts?.get(ActionKind.RestoreEditedDeleted) ?: 0),
        )
    }

    // ------------------------------------------------------------------ backups: Restore and Clean up

    private val peerVersionsNote = "Earlier versions of files on another device are kept by that device, in its own trash."

    fun versions(placeId: String): List<CopyVersionUi> {
        if (peers?.isPeer(local[placeId]?.treeUri) == true) return emptyList()
        val access = openAccess(local[placeId]?.treeUri ?: throw IllegalStateException("Connect the copy first."))
        return Versions.liveEntries(access, Store.VERSIONS).map { CopyVersionUi(it.id, nameOf(it.path), it.path, it.archivedAt, it.why, it.why == "deleted" || it.why == "removed") }.sortedByDescending { it.archivedAt }.take(500)
    }

    /** What Free up space… would delete: all kept entries (counted like Windows) and those kept for files deleted since. */
    fun versionStats(placeId: String): CopyVersionStats {
        if (peers?.isPeer(local[placeId]?.treeUri) == true) return CopyVersionStats(0, 0)
        val access = openAccess(local[placeId]?.treeUri ?: throw IllegalStateException("Connect the copy first."))
        val all = Versions.liveEntries(access, Store.VERSIONS)
        return CopyVersionStats(all.size, all.count { it.why == "deleted" || it.why == "removed" })
    }

    /** Brings an earlier version back into the library as a new file under "Restored from backup"; the version stays on the backup. */
    fun restore(placeId: String, entryId: String) {
        if (peers?.isPeer(local[placeId]?.treeUri) == true) throw IllegalStateException(peerVersionsNote)
        val access = openAccess(local[placeId]?.treeUri ?: throw IllegalStateException("Connect the copy first."))
        val found = Versions.liveEntries(access, Store.VERSIONS).firstOrNull { it.id == entryId } ?: throw IllegalStateException("That version is no longer on the backup.")
        val b = library.backend()
        val source = ".keepfin/${found.file}"
        val hashed = hashPath(access, source)
        if (hashed.contentId != found.contentId) throw IllegalStateException("This version on the backup does not match what Keepfin recorded. It was not restored.")
        val stem = nameOf(found.path)
        var rel = "Restored from backup/$stem"
        var n = 2
        while (b.folder.stat(rel) != null) {
            val (s, e) = splitExt(stem)
            rel = "Restored from backup/$s ($n)$e"
            n++
        }
        b.folder.mkdirs(parentPath(rel))
        val result = access.openRead(source).use { b.folder.publishFrom(rel, it) }
        if (CONTENT_PREFIX + result.sha256 != found.contentId) {
            b.folder.remove(rel)
            throw IllegalStateException("The restored file did not read back the same. It was not added.")
        }
        LibraryHost(b, library, newId, now).addItem(rel, found.contentId, result.size, null)
        refresh()
    }

    fun cleanup(placeId: String) {
        val p = places.firstOrNull { it.id == placeId } ?: throw IllegalStateException("That copy is gone.")
        if (peers?.isPeer(local[placeId]?.treeUri) == true) throw IllegalStateException(peerVersionsNote)
        val access = openAccess(local[placeId]?.treeUri ?: throw IllegalStateException("Connect the copy first."))
        Versions.cleanUpDeleted(access)
        Versions.pruneVersions(access, now(), p.options.versionsKeepDays)
        refresh()
    }

    // ------------------------------------------------------------------ schedule (spec 8)

    /** Called every few seconds in the app and by the background worker: reconnects, then starts the syncs that are due. */
    fun tick(background: Boolean = false, debounceMs: Long = 30_000) {
        if (app.keepfin.merge.MergeFence.copiesWait) return
        start()
        val sum = runCatching { library.versionSum() }.getOrNull()
        for (p in places) {
            val l = local[p.id] ?: continue
            val r = runtime(p.id)
            val was = r.connected
            checkConnected(p)
            if (r.running || r.paused || r.previewPending || !r.connected) continue
            val lastStatus = r.last?.optString("status")
            if (lastStatus == "held" || lastStatus == "refused") continue
            val auto = (raw[p.id]?.get("schedule") as? JObj)?.members?.get("auto").let { (it as? JBool)?.value != false }
            if (!auto && p.everyHours == null) continue
            if (r.retryAt > now()) continue
            val appeared = r.connected && !was
            // a full audit (every file hashed) by itself every 30 days, while the copy is here and nothing else runs; a failed one is not retried for hours
            if (auditIsDue(p.id, now())) {
                if (!(background && holdsBackground(p, l))) sync(p.id, audit = true)
                continue
            }
            val due = p.everyHours?.let { now() - l.lastRunAt >= it * 3_600_000L } ?: false
            val changed = auto && sum != null && l.seenSum >= 0 && sum != l.seenSum && now() - l.lastRunAt >= debounceMs
            val firstSeen = auto && l.seenSum < 0 && sum != null && also(l, sum)
            if ((auto && appeared) || due || changed) {
                // large runs wait for power or an unmetered network when the background worker starts them (see [Scheduling])
                if (background && holdsBackground(p, l)) continue
                sync(p.id)
            }
        }
        publish()
    }

    private fun also(l: Local, sum: Long): Boolean { l.seenSum = sum; return false }

    /** When each copy's last background audit was asked for in this session. */
    private val auditAsked = HashMap<String, Long>()

    private fun auditIsDue(placeId: String, at: Long): Boolean {
        if (at - (auditAsked[placeId] ?: 0L) < 6 * 3_600_000L) return false
        if (!auditDue(Relationship.read(stateDir(placeId)).auditedAt, at)) return false
        auditAsked[placeId] = at
        return true
    }

    /** True when this background run is large (a dry plan decides) and the phone is neither charging nor on an unmetered network. */
    private fun holdsBackground(p: PlaceConfig, l: Local): Boolean {
        val (charging, unmetered) = power()
        if (charging || unmetered) return false
        val access = try { accessFor(p.id, l.treeUri) } catch (e: FolderError) { return true }
        val report = runCatching { executeRun(p, access, stateDir(p.id).also { it.mkdirs() }, dryRun = true, flags = Flags(), tree = l.treeUri) }.getOrNull() ?: return true
        val s = report.summary ?: return false
        return Scheduling.hold(s.counts[ActionKind.CopyTo] ?: 0, s.bytes[ActionKind.CopyTo] ?: 0, charging, unmetered, largeBytes, largeFiles)
    }
}
