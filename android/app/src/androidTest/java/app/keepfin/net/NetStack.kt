package app.keepfin.net

import android.content.Context
import app.keepfin.bench.LocalFolderAccess
import app.keepfin.core.net.NetAction
import app.keepfin.core.sync.JStr
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.KeepfinFolders
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.OpDraft
import app.keepfin.folder.StoreOptions
import app.keepfin.folder.sql.AndroidSqlHost
import app.keepfin.folder.sql.SqlMergeState
import java.io.File
import java.security.KeyStore
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/** Waits for [check], polling; throws with [label] when it does not come true. */
var diagnostics: () -> String = { "" }

fun until(label: String, ms: Long = 15_000, check: () -> Boolean) {
    val end = System.currentTimeMillis() + ms
    while (System.currentTimeMillis() < end) {
        if (try { check() } catch (e: Exception) { false }) return
        Thread.sleep(25)
    }
    throw AssertionError("Timed out waiting for $label\n" + diagnostics())
}

/** A recording download queue standing in for the engine: what other devices ask for lands here. */
class RecordingJobPort : JobPort {
    val created = CopyOnWriteArrayList<Triple<String, String, JSONObject>>()
    val views = java.util.concurrent.ConcurrentHashMap<String, RemoteJobView>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    override fun spaces() = listOf("music" to "Music")
    override fun create(jobId: String, url: String, format: JSONObject, options: JSONObject?, space: String?, musicVideo: Boolean) {
        created += Triple(jobId, url, format)
        views[jobId] = RemoteJobView("queued", 0.0, "Title", "720p")
    }
    override fun view(jobId: String) = views[jobId]
    override fun subscribe(listener: () -> Unit): () -> Unit { listeners += listener; return { listeners -= listener } }
    override fun freeBytes(): Long? = 50L * 1024 * 1024 * 1024
    fun change(jobId: String, view: RemoteJobView) { views[jobId] = view; listeners.forEach { it() } }
}

/** A library that serves given files by content id (the content index of the real library is a different test). */
class FileServingLibrary(private val inner: NetLibrary, private val files: Map<String, ByteArray>) : NetLibrary by inner {
    /** The offsets the files were opened at: a seek that reads only the part after it shows up as a late offset. */
    val opened = CopyOnWriteArrayList<Long>()

    override fun findContent(contentId: String): ContentFile? {
        val bytes = files[contentId] ?: return null
        return ContentFile(bytes.size.toLong()) { offset -> opened += offset; bytes.inputStream().also { it.skip(offset) } }
    }
}

/**
 * One whole app instance of Devices inside this process: its own key in AndroidKeyStore, its own folder, store, server and service, on
 * loopback with an ephemeral port and no multicast.
 */
class NetStack(val name: String, val context: Context, val root: File, private val keyAlias: String = "kftest_${name}_${UUID.randomUUID().toString().take(8)}") {
    val identity: KeystoreIdentity = KeystoreIdentity.load(context, keyAlias).also { it.rename(name) }
    val libs = CopyOnWriteArrayList<NetLibrary>()
    val stores = ArrayList<KeepfinStore>()
    val jobs = RecordingJobPort()
    val files = HashMap<String, ByteArray>()
    val deviceDir = File(root, "device").also { it.mkdirs() }
    private var service0: NetService? = null
    val service: NetService get() = service0!!
    var storeOf = HashMap<String, KeepfinStore>()

    /** The library that serves [files] (the last one opened). */
    var served: FileServingLibrary? = null

    fun openLibrary(libraryId: String, folder: FolderAccess = LocalFolderAccess(File(root, "folder-$libraryId")), actor: String = UUID.randomUUID().toString(), create: Boolean = true): KeepfinStore {
        val replica = UUID.randomUUID().toString()
        if (create) KeepfinFolders.create(folder, libraryId, replica, java.time.Instant.now().toString())
        val dir = File(root, "lib-$libraryId").also { it.mkdirs() }
        val state = SqlMergeState(File(dir, "state/state.db").also { it.parentFile!!.mkdirs() }.path, libraryId, replica, identity.deviceId, AndroidSqlHost())
        val store = KeepfinStore.open(
            StoreOptions(
                folder = folder, libraryId = libraryId, actor = actor, deviceDir = dir, outboxDir = File(dir, "outbox"), newId = { UUID.randomUUID().toString() }, state = state,
                flushIdleMs = 150, signer = identity.signer, retryMs = 50,
            ),
        ).get(30, TimeUnit.SECONDS)
        stores += store
        storeOf[libraryId] = store
        val lib = FileServingLibrary(StoreNetLibrary(libraryId, "Library of $name", folder, store, state, replica), files)
        served = lib
        libs += lib
        service0?.libraryOpened(lib)
        return store
    }

    fun start(jobsOn: Boolean = false, nics: (() -> List<Nic>)? = null, discovery: ((port: Int, changed: () -> Unit, isPaired: (String) -> Boolean) -> NetDiscovery)? = null, codeSearchMs: Long = 4_000, lookupEveryMs: Long = 60_000): NetService {
        val svc = NetService(
            NetServiceDeps(
                deviceDir = deviceDir, identity = identity, libraries = { libs.filter { it.isOpen } }, port = 0, discovery = discovery, codeSearchMs = codeSearchMs, lookupEveryMs = lookupEveryMs, advertiseHost = { "127.0.0.1" },
                backoffMinMs = 40, backoffMaxMs = 400, jobs = if (jobsOn) jobs else null, checkUrl = { it }, notifyDelayMs = 50, connectTimeoutMs = 3000, pairRetryMs = 50, pairWaitMs = 1500,
                adoptJoined = null, leaseMs = 5_000, nics = nics ?: { systemNics() }, log = NetLog { android.util.Log.i("KfNetTest", "$name: $it") },
            ),
        )
        service0 = svc
        svc.enable()
        return svc
    }

    val address: String get() = "127.0.0.1:${service.listeningPort}"

    fun write(libraryId: String, vararg fields: Pair<String, String>) {
        val store = storeOf.getValue(libraryId)
        store.apply(fields.map { OpDraft("profile.set", mapOf("field" to JStr(it.first), "value" to JStr(it.second))) }).get(15, TimeUnit.SECONDS)
        store.flush().get(15, TimeUnit.SECONDS)
    }

    fun views(libraryId: String): String = storeOf.getValue(libraryId).viewsJson().get(15, TimeUnit.SECONDS)

    /** What this stack sees, for a failure message. */
    fun dump(): String = try {
        val st = service.computeState()
        val lib = libs.firstOrNull()
        val m = lib?.membership()
        "[$name id=${identity.deviceId.take(6)} port=${service.listeningPort} libs=${st.libraries.map { "${it.signed}/${it.admin}" }} paired=${st.paired.map { "${it.name}:online=${it.online}" }} " +
            "membership=${m?.active} members=${m?.members?.keys?.map { it.take(4) }} actor=${lib?.actor()?.take(4)} vector=${lib?.let { SegmentFiles.formatVector(SegmentFiles.vectorOf(it.segments())).replace(Regex("[0-9a-f]{4}[0-9a-f-]{32}")) { r -> r.value.take(4) } }} " +
            "views=${storeOf.values.firstOrNull()?.viewsJson()?.get(5, TimeUnit.SECONDS)?.take(400)} " +
            "links=${service.links.map { "${it.key.take(6)} online=${it.value.online} next=${it.value.nextDelayMs}" }} notes=${st.notes}]"
    } catch (e: Exception) {
        "[$name dump failed: $e]"
    }

    fun close() {
        runCatching { service0?.close() }
        stores.forEach { runCatching { it.abandon() } }
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias) }
        runCatching { File(File(context.filesDir, "net"), "device-$keyAlias.json").delete() }
        runCatching { root.deleteRecursively() }
    }
}

/** Pairs [asker] with [answerer] by comparing the numbers; both people agree. Returns once both sides saved each other. */
fun pairBySas(asker: NetStack, answerer: NetStack) {
    answerer.service.openPairing()
    var failure: Throwable? = null
    val t = Thread { try { asker.service.ask(answerer.address) } catch (e: Throwable) { failure = e } }.also { it.isDaemon = true; it.start() }
    until("both screens to show the numbers") { asker.service.computeState().pairing?.sas != null && answerer.service.computeState().pairing?.sas != null }
    val a = asker.service.computeState().pairing!!
    val b = answerer.service.computeState().pairing!!
    if (a.sas != b.sas) throw AssertionError("The numbers differ: ${a.sas} and ${b.sas}")
    asker.service.decide(a.id, true)
    // the asking side now shows the code with "tap Codes match on the other device too" and waits
    until("the asking side to wait for the other person") { asker.service.computeState().pairing?.stage == "waiting" }
    answerer.service.decide(b.id, true)
    t.join(20_000)
    failure?.let { throw it }
    until("both devices to remember each other") { asker.service.peers.get(answerer.identity.deviceId) != null && answerer.service.peers.get(asker.identity.deviceId) != null }
}

fun grant(owner: NetStack, peer: NetStack, libraryId: String, vararg actions: NetAction) = owner.service.setAccess(peer.identity.deviceId, libraryId, actions.toList())
