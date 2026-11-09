package app.keepfin.where

import app.keepfin.KeepfinApplication
import app.keepfin.MediaEngine
import app.keepfin.copies.CopiesManager
import app.keepfin.copies.CopiesRuntime
import app.keepfin.copies.CopiesUi
import app.keepfin.copies.Manifest
import app.keepfin.library.repo.CopiesBackend
import app.keepfin.library.repo.LibraryRepository
import app.keepfin.folder.sql.CopiesSql
import app.keepfin.folder.sql.WhereHolding
import app.keepfin.folder.sql.WhereSingleRow
import app.keepfin.net.DevicesState
import app.keepfin.net.NetRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/** The reads behind the counts, over any [CopiesSql] (so a test can run them over a built state). */
internal object WhereSql {
    private const val PAGE = 2000

    /**
     * Items held by one replica only, with the ones a copy also keeps taken out: the replica is [self] (a copy keeps files of this phone's
     * placements). One query for the counts; only when a copy keeps something are the items that are on this phone alone read in pages.
     */
    fun singlesAfterCopies(q: CopiesSql, self: String, copyHashes: List<LongArray>): List<WhereSingleRow> {
        val rows = q.singles()
        val kept = copyHashes.filter { it.isNotEmpty() }
        if (kept.isEmpty() || rows.none { it.replica == self }) return rows
        val minus = HashMap<Pair<String, String?>, Long>()
        var after = ""
        while (true) {
            val page = q.soleItems(self, after, PAGE)
            if (page.isEmpty()) break
            for ((id, media, space) in page) {
                val h = WhereIds.hash(id)
                if (kept.any { java.util.Arrays.binarySearch(it, h) >= 0 }) minus.merge(media to space, 1L, Long::plus)
            }
            after = page.last().first
            if (page.size < PAGE) break
        }
        return rows.map { r -> if (r.replica == self) WhereSingleRow(r.replica, r.media, r.space, (r.count - (minus[r.media to r.space] ?: 0L)).coerceAtLeast(0)) else r }
    }
}

/**
 * Keeps [state] current: the placements are read once per change of the library (off the main thread, never per render), and the words are
 * made again when a copy or a device changes. The reads run in a single coroutine, so nothing piles up while an import is running.
 */
@OptIn(FlowPreview::class)
internal class WhereFilesEngine(
    scope: CoroutineScope,
    private val backend: () -> CopiesBackend,
    libraryChanges: Flow<Any?>,
    private val copies: CopiesManager?,
    devices: Flow<DevicesState?>,
    private val now: () -> Long = System::currentTimeMillis,
) : WhereFilesSource {
    private class Read(val total: Long, val holdings: List<WhereHolding>, val singles: List<WhereSingleRow>, val replica: String, val libraryId: String, val videoBytes: Long)

    private val flow = MutableStateFlow(WhereFilesUi())
    override val state: StateFlow<WhereFilesUi> = flow.asStateFlow()

    @Volatile private var read: Read? = null
    @Volatile private var devicesNow: DevicesState? = null
    @Volatile private var dirty = true
    @Volatile private var heldSig = 0L

    init {
        val copiesFlow: Flow<CopiesUi?> = copies?.state ?: emptyFlow()
        scope.launch(Dispatchers.IO) {
            runCatching { copies?.start() }
            merge(
                libraryChanges.map { dirty = true; 1 },
                copiesFlow.map { 2 },
                devices.map { devicesNow = it; 3 },
            ).debounce(300).collect { runCatching { refresh() } }
        }
        scope.launch(Dispatchers.IO) {
            // the first answer should not wait for a change
            delay(50)
            runCatching { refresh() }
        }
    }

    private fun signature(): Long = copies?.heldByCopy()?.values?.sumOf { it.items * 31 + it.bytes } ?: 0L

    @Synchronized private fun refresh() {
        val held = copies?.heldByCopy().orEmpty()
        val sig = signature()
        if (sig != heldSig) { heldSig = sig; dirty = true }
        if (dirty || read == null) {
            dirty = false
            val b = backend()
            val hashes = held.values.map { it.itemHashes }
            read = b.read { q ->
                val (items, _) = q.presentTotals()
                Read(items, q.holdings(), WhereSql.singlesAfterCopies(q, b.replicaId, hashes), b.replicaId, b.libraryId, q.musicVideoBytes())
            }
        }
        val r = read ?: return
        val ui = copies?.state?.value
        flow.value = WhereCompose.build(
            WhereInputs(
                r.replica, r.libraryId, r.total, r.holdings, r.singles, ui?.places.orEmpty(),
                held.mapValues { it.value.items to it.value.bytes }, devicesNow?.paired.orEmpty(), ui?.spaces.orEmpty(), ui?.playlists.orEmpty(), now(), r.videoBytes,
            ),
        )
    }

    override fun placesOf(itemId: String): List<ItemPlace> {
        val b = backend()
        val devs = devicesNow?.paired.orEmpty()
        val ui = copies?.state?.value
        val held = copies?.heldByCopy().orEmpty()
        val out = ArrayList<ItemPlace>()
        var content: String? = null
        var path: String? = null
        val placed = b.read { q ->
            content = q.firstContent(itemId)
            path = q.placement(itemId, b.replicaId)
            q.placementsOf(itemId)
        }
        if (placed.any { it.first == b.replicaId }) out.add(ItemPlace("this phone", false, true))
        val seen = HashSet<String>()
        for ((replica, actor) in placed) {
            if (replica == b.replicaId) continue
            val d = devs.firstOrNull { d -> d.libraries.any { it.actor.isNotEmpty() && it.actor == actor } }
            if (d == null) { if (seen.add("?")) out.add(ItemPlace("another device", true)); continue }
            if (seen.add(d.deviceId)) out.add(ItemPlace(d.name, !d.online, deviceId = d.deviceId))
        }
        val c = content
        val p = path
        if (c != null && p != null) for (card in ui?.places.orEmpty()) {
            val keys = held[card.id]?.keys ?: continue
            if (java.util.Arrays.binarySearch(keys, Manifest.keyHash(itemId, c, p)) >= 0) out.add(ItemPlace(card.name, !card.connected))
        }
        return out
    }
}

/** The one engine of the app, made when the Keepfin folder library is open. */
internal object WhereFilesRuntime {
    @Volatile private var engine: WhereFilesEngine? = null
    @Volatile private var owner: Any? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun of(app: KeepfinApplication): WhereFilesEngine? {
        val repo = (app.engineApi as? MediaEngine)?.folderLibrary ?: return null
        engine?.takeIf { owner === repo }?.let { return it }
        synchronized(this) {
            engine?.takeIf { owner === repo }?.let { return it }
            val copies = runCatching { CopiesRuntime.of(app) }.getOrNull()
            val devices: Flow<DevicesState?> = if (NetRuntime.available) flow {
                while (true) { emit(NetRuntime.peek()); delay(3_000) }
            }.distinctUntilChanged().flatMapLatest { s -> (s?.state as Flow<DevicesState?>?) ?: kotlinx.coroutines.flow.flowOf<DevicesState?>(null) } else emptyFlow()
            val e = WhereFilesEngine(scope, { repo.copiesBackend() }, repo.library, copies, devices)
            engine = e
            owner = repo
            return e
        }
    }
}
