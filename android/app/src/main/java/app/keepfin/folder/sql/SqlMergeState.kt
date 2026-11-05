package app.keepfin.folder.sql

import app.keepfin.core.sqlstate.PartSource
import app.keepfin.core.sqlstate.SqlHost
import app.keepfin.core.sqlstate.SqlStateDb
import app.keepfin.core.sqlstate.SqlStateOptions
import app.keepfin.core.sqlstate.Touched
import app.keepfin.core.sync.FORMAT
import app.keepfin.core.sync.Hlc
import app.keepfin.core.sync.Observed
import app.keepfin.core.sync.ObservedItem
import app.keepfin.core.sync.OpId
import app.keepfin.core.sync.SEMANTICS
import app.keepfin.folder.AdmitKind
import app.keepfin.folder.CHECKPOINT_SHARDS
import app.keepfin.folder.MergeState
import java.io.File
import java.security.MessageDigest

/** What changed in the state since [SqlMergeState.takeChanges]: the keys the repository re-reads. Items are the index's business. */
class StateChanges(
    val all: Boolean,
    val playlists: Set<String>,
    val spaces: Set<String>,
    val sets: Set<String>,
    val places: Set<String>,
    val profile: Boolean,
) {
    val isEmpty: Boolean get() = !all && playlists.isEmpty() && spaces.isEmpty() && sets.isEmpty() && places.isEmpty() && !profile
}

private const val STATE_FORMAT = "1"

private fun sha256Hex(text: String): String {
    val d = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(64)
    for (b in d) {
        val v = b.toInt() and 0xff
        sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 15])
    }
    return sb.toString()
}

/**
 * The merge state of the Keepfin folder in SQLite (D-A3, D-A4): the shared admission of the core (`SqlStateDb`) over the bundled
 * SQLite, plus the [LibIndex] the screens page through. `state.db` is a cache of the segments: a file of another layout,
 * semantics or library, or one that cannot be opened, is deleted and started again.
 *
 * Every call comes from the store thread, which owns the writer connection. Readers use their own read-only connections
 * ([IndexReader]) and watch [generation]: it grows whenever a batch changed the index or the file was replaced ([epoch]).
 */
class SqlMergeState(
    private val path: String,
    private val libraryId: String,
    private val replicaId: String,
    /** The device id that signs this device's segments (the membership's verdicts); null before membership exists. */
    private val signer: String? = null,
    private val host: SqlHost = AndroidSqlHost(),
    private val log: (String) -> Unit = {},
) : MergeState {
    private var db: SqlStateDb
    lateinit var index: LibIndex
        private set

    /** Grows with every change of the index; readers re-query when it moves. */
    @Volatile var generation: Long = 0L
        private set

    /** Grows when the database file is replaced (a bulk load, a rebuild): read connections reopen. */
    @Volatile var epoch: Long = 0L
        private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val pendingLock = Any()
    private var pAll = false
    private val pPlaylists = LinkedHashSet<String>()
    private val pSpaces = LinkedHashSet<String>()
    private val pSets = LinkedHashSet<String>()
    private val pPlaces = LinkedHashSet<String>()
    private var pProfile = false

    init {
        File(path).parentFile?.mkdirs()
        db = openChecked()
        attachIndex(initial = true)
    }

    // a phone is not a laptop: a 32 MB page cache on the writer (readers take 8 MB each), mapped reads for the rest
    private fun options() = SqlStateOptions(synchronous = "NORMAL", cacheMb = 64, mmapMb = 1024)

    private fun fresh(): SqlStateDb {
        for (suffix in listOf("", "-wal", "-shm", "-journal")) host.remove(path + suffix)
        return SqlStateDb(path, host, options(), ::sha256Hex)
    }

    private fun openChecked(): SqlStateDb {
        var d: SqlStateDb
        try {
            d = SqlStateDb(path, host, options(), ::sha256Hex)
            val format = d.getMeta("format")
            if (format != null && (format != STATE_FORMAT || d.getMeta("library") != libraryId || d.getMeta("semantics") != "$SEMANTICS.$FORMAT")) {
                d.close()
                d = fresh()
            }
        } catch (e: Throwable) {
            log("The library state could not be opened; it is started again: ${e.message}")
            d = fresh()
        }
        return d
    }

    private fun markReady() {
        db.setMeta("format", STATE_FORMAT)
        db.setMeta("library", libraryId)
        db.setMeta("semantics", "$SEMANTICS.$FORMAT")
    }

    /** Diagnostics of the last cold build: the bulk load of the checkpoint parts, and what the index took. */
    @Volatile var lastBulkLoad: app.keepfin.core.sqlstate.BulkLoadResult? = null
        private set
    @Volatile var lastIndexMs: Long = 0L
        private set
    val indexTimings: Map<String, Long> get() = index.timings

    private fun attachIndex(initial: Boolean) {
        val started = System.nanoTime()
        try { attachIndexNow(initial) } finally { lastIndexMs = (System.nanoTime() - started) / 1_000_000 }
        // a rebuilt index is a big commit: fold it out of the write-ahead log now
        if (!initial) runCatching { db.checkpoint(false) }
    }

    private fun attachIndexNow(initial: Boolean) {
        if (!initial) synchronized(pendingLock) { pAll = true }
        index = LibIndex(db, replicaId)
        index.createSchema()
        if (initial && db.getMeta("format") != null && index.isCurrent()) {
            db.takeTouched() // a fresh handle starts with "everything changed"; the index on disk is already current
        } else if (db.getMeta("format") != null) {
            val t = db.takeTouched()
            index.apply(Touched(true, t.items, t.playlists, t.spaces, t.sets, t.places, t.profile, t.findings))
        } else {
            index.rebuild()
            db.takeTouched()
        }
    }

    /** Refills the browse index from the state (benchmarks and repair; the store thread must be idle). */
    fun rebuildIndex() {
        val started = System.nanoTime()
        index.rebuild()
        lastIndexMs = (System.nanoTime() - started) / 1_000_000
    }

    fun onChange(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private fun changed() {
        generation++
        for (l in listeners) try { l() } catch (_: Throwable) { }
    }

    private fun collect(t: Touched) {
        synchronized(pendingLock) {
            if (t.all) pAll = true
            pPlaylists += t.playlists
            pSpaces += t.spaces
            pSets += t.sets
            pPlaces += t.places
            if (t.profile) pProfile = true
        }
    }

    /** What changed (other than items) since the last call; thread safe. */
    fun takeChanges(): StateChanges = synchronized(pendingLock) {
        val out = StateChanges(pAll, LinkedHashSet(pPlaylists), LinkedHashSet(pSpaces), LinkedHashSet(pSets), LinkedHashSet(pPlaces), pProfile)
        pAll = false
        pPlaylists.clear(); pSpaces.clear(); pSets.clear(); pPlaces.clear()
        pProfile = false
        out
    }

    /** Runs a query on the writer connection (store thread only). */
    fun query(sql: String, params: List<Any?> = emptyList()): List<List<Any?>> = db.prep(sql).rows(params)

    val views: StateViews get() = StateViews(::query)

    val statePath: String get() = path

    // ------------------------------------------------------------------------------------------------ MergeState

    override fun admit(lines: List<String>): List<AdmitKind> = admit(lines, signer)

    override fun admit(lines: List<String>, signer: String?): List<AdmitKind> {
        if (lines.isEmpty()) return emptyList()
        val codes = db.admit(lines, signer)
        afterAdmit()
        return codes.map(::kindOf)
    }

    private fun afterAdmit() {
        val t = db.takeTouched()
        collect(t)
        if (db.rebuildRequired) {
            // the rebuild (from the segments) re-reads everything: the store calls it before the next read
            return
        }
        index.apply(t)
        changed()
    }

    private fun kindOf(code: String): AdmitKind = when {
        code == "admitted" -> AdmitKind.Admitted
        code == "duplicate" -> AdmitKind.Duplicate
        code == "kept" -> AdmitKind.Kept
        code == "equivocation" -> AdmitKind.Equivocation
        code == "covered" -> AdmitKind.Covered
        code.startsWith("setAside") -> AdmitKind.SetAside
        code.startsWith("invalid") -> AdmitKind.Invalid
        else -> AdmitKind.Malformed
    }

    override fun versionVector(): Map<String, Long> = db.versionVector()

    override fun maxHlc(): Hlc? = db.maxHlc()?.let { Hlc.parse(it) }

    override fun loadParts(parts: List<PartSource>, libraryId: String, equivocations: Map<String, Long>): Boolean {
        // a usable database from the last run is kept: only the segments above its version vector are admitted
        if (db.getMeta("format") != null) return true
        if (parts.isEmpty()) {
            markReady()
            return false
        }
        val loaded = try {
            db.bulkLoadSources(parts, libraryId, equivocations).also { lastBulkLoad = it }
        } catch (e: Throwable) {
            log("The checkpoint parts could not be loaded; the library is replayed from its segments: ${e.message}")
            markReady()
            return false
        }
        markReady()
        epoch++
        attachIndex(initial = false)
        changed()
        return loaded.ok
    }

    override fun needsRebuild(): Boolean = db.rebuildRequired

    override fun membership(): app.keepfin.folder.MembershipView = app.keepfin.folder.MembershipView.of(db.membership)

    /**
     * The first import of a library: every op line in one pass into the empty database (null, and nothing done, when the database already
     * holds ops). Faster than [admit] for a big first import; the index is built from the result.
     */
    fun importFresh(lines: Iterator<String>, signers: Iterator<String?>? = null): List<AdmitKind>? {
        val codes = db.importFresh(lines, signers) ?: return null
        val t = db.takeTouched()
        collect(t)
        index.apply(Touched(true, t.items, t.playlists, t.spaces, t.sets, t.places, t.profile, t.findings))
        changed()
        return codes.map(::kindOf)
    }

    override fun rebuild(ops: Iterator<String>, signers: Iterator<String?>?) {
        db.rebuild(ops, signers)
        epoch++
        attachIndex(initial = false)
        changed()
    }

    override fun dirtyShards(): List<String> {
        val mask = db.dirtyShards
        return CHECKPOINT_SHARDS.filterIndexed { i, _ -> (mask shr i) and 1 == 1 }
    }

    override fun partBytes(libraryId: String, shard: String): ByteArray? {
        val coverage = db.checkpointCoverage() ?: return null
        val job = db.partJob(libraryId, shard, coverage)
        while (!job.step()) {
            // the writer yields to nobody: this runs on the store thread
        }
        val bytes = job.bytes()
        db.markShardsClean(1 shl CHECKPOINT_SHARDS.indexOf(shard))
        return bytes
    }

    override fun findings(): Set<String> = db.findings().toSet()

    override fun viewsJson(): String = StateViews(::query).fullViewsJson(db.findings())

    override fun observed(items: List<String>, playlists: List<String>, spaces: List<String>, setElements: List<Pair<String, String>>): Observed {
        val o = db.observed(items, playlists, spaces, setElements)
        fun ids(l: List<String>) = l.mapNotNull { OpId.parse(it) }
        return Observed(
            o.items.mapValues { (_, v) -> ObservedItem(ids(v.tags), ids(v.heads)) },
            o.playlists.mapValues { ids(it.value) },
            o.spaces.mapValues { ids(it.value) },
            o.setElements.mapValues { (_, byElement) -> byElement.mapValues { ids(it.value) } },
        )
    }

    /** Folds the write-ahead log into the file (when idle). */
    override fun checkpoint() {
        try { db.checkpoint(false) } catch (_: Throwable) { }
    }

    private val closedOnce = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Closes once: a second call (a store closed by its owner and again by a caller that abandons it) must never touch the closed connection. */
    override fun close() {
        if (!closedOnce.compareAndSet(false, true)) return
        try { db.checkpoint(true) } catch (_: Throwable) { }
        db.close()
    }

    /** The SQLite version and FTS5 availability (spec probe 1). */
    fun probe(): String = BundledSql.probe(path)
}
