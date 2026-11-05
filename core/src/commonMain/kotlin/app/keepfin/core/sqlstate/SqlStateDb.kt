package app.keepfin.core.sqlstate

// The device-local merge state over a [SqlDriver] (decision D-T3, shared by desktop and Android through D-A3):
// the admission control flow of the desktop spec section 4 over the `held`/`variants`/`equiv`/`vv`/`pending`
// tables, the touched-key tracker, and the item projection refresh. The rules (judging an op, the statements that
// join it, the projection) live in [judgeLine], [SqlState] and [SqlRows]; this class only sequences them.
//
// The op bytes are NOT kept here: the segments hold them. The database keeps a hash per held op, so "the same id
// with the same bytes" is an equal hash.

import app.keepfin.core.sync.Membership
import app.keepfin.core.sync.OpId
import app.keepfin.core.sync.OpDecode
import app.keepfin.core.sync.decodeOp
import app.keepfin.core.sync.SignedOp

private const val HELD_INSERT = "INSERT INTO held (actor, seq, hash, kind, size) VALUES (?, ?, ?, ?, ?)"

/** Indexes the host adds to the core's schema (the core's own list stays the shared one). */
private val EXTRA_DDL = listOf("CREATE INDEX IF NOT EXISTS entries_by_item ON entries (item)")

private const val META_BIT = 1 shl 16

/** The 17 shards of a checkpoint (sync spec 10.4): the first hex digit of an id, else `meta`. */
internal fun shardBit(id: String): Int {
    val c = if (id.isEmpty()) -1 else id[0].code
    if (c in 48..57) return 1 shl (c - 48)
    if (c in 97..102) return 1 shl (10 + c - 97)
    return META_BIT
}

const val ALL_SHARDS: Int = (1 shl 17) - 1

/** The keys changed since the last [SqlStateDb.takeTouched]; [all] after a rebuild, a bulk load or a fresh open. */
class Touched(
    val all: Boolean,
    val items: List<String>,
    val playlists: List<String>,
    val spaces: List<String>,
    val sets: List<String>,
    val places: List<String>,
    val profile: Boolean,
    val findings: Boolean,
)

private class Tracker(var all: Boolean) {
    val items = LinkedHashSet<String>()
    val playlists = LinkedHashSet<String>()
    val spaces = LinkedHashSet<String>()
    val sets = LinkedHashSet<String>()
    val places = LinkedHashSet<String>()
    var profile = false
    var findings = false
}

class SqlStateOptions(
    /** `FULL` waits for the disk on every commit (a crash loses nothing); `NORMAL` may lose the last commits in WAL mode. */
    val synchronous: String = "NORMAL",
    /** For tests of the tests: replaces statements of the catalog by id (a deliberately wrong join must be caught). */
    val catalogOverride: Map<Int, String> = emptyMap(),
    /** Page cache size in MB. */
    val cacheMb: Int? = null,
    /** How much of the database file may be memory-mapped for reads, in MB (0 where the platform does not allow it). */
    val mmapMb: Int = 1024,
    /** Page size of a database built by the bulk loader (bytes; the SQLite default is 4096). */
    val bulkPageSize: Int = 16384,
)

/** The file-level operations the merge state needs from its host (the connection itself is a [SqlDriver]). */
interface SqlHost {
    /** Opens (creating it if needed) the database file at [path]; `:memory:` gives an in-memory database. */
    fun open(path: String): SqlDriver

    /** Deletes a file; nothing happens when it does not exist. */
    fun remove(path: String)

    fun rename(from: String, to: String)

    /** The resident memory of the process in MB, for the bulk loader's report (0 where it is not known). */
    fun residentMb(): Double = 0.0
}

class BulkLoadResult(
    val ok: Boolean,
    val rows: Long,
    val ms: Double,
    /** The largest resident set seen while loading (sampled after every shard). */
    val peakRssMb: Double,
    /** Where the time went: decoding the parts, and writing rows into SQLite. */
    val coreMs: Double,
    val insertMs: Double,
)

class SqlStateDb(
    private val path: String,
    private val host: SqlHost,
    private val options: SqlStateOptions = SqlStateOptions(),
    /** SHA-256 of a UTF-8 text as lower-case hex (the host's native routine where it has one). */
    private val hash: (String) -> String,
) {
    private val catalog: List<String> = SqlState.catalog.mapIndexed { id, sql -> options.catalogOverride[id] ?: sql }
    private var prepared = arrayOfNulls<SqlStmt>(catalog.size)
    private var named = HashMap<String, SqlStmt>()
    private var multi = HashMap<String, SqlStmt>()
    private lateinit var db: SqlDriver

    /** Set when an equivocation moved a set-aside range: the host rebuilds the state from the segments before the next read. */
    var rebuildRequired = false
        private set

    // Small bookkeeping read on every op is kept in memory and written once per batch (reloaded after a rollback).
    private var equiv = HashMap<String, Long>()
    private var base = HashMap<String, Long>()
    private var vv = HashMap<String, Long>()
    private val dirtyVv = LinkedHashSet<String>()
    private var pendingTotal = 0L
    private var maxHlcSeen: String? = null
    private var maxHlcDirty = false

    /** Who may write (member ops, kept in the `members` table); the verdicts below are asked of it for every op. */
    private var members = Membership()

    /** The library's membership as the member ops admitted so far define it. */
    val membership: Membership get() = members

    /** Diagnostics of the bulk loader (one line per shard and per index); null in production. */
    var trace: ((String) -> Unit)? = null

    private var tracker = Tracker(true)
    var dirtyShards = ALL_SHARDS
        private set
    private val presentInBatch = HashSet<String>()
    private val buffers = HashMap<String, MutableList<List<Any?>>>()

    init {
        try {
            open(path, false)
        } catch (e: Throwable) {
            // a file that is not a database: do not leave it locked (the host deletes it and starts again)
            try {
                db.close()
            } catch (_: Throwable) {
                // not open
            }
            throw e
        }
    }

    private fun open(at: String, bulk: Boolean) {
        db = host.open(at)
        if (bulk) {
            db.exec("PRAGMA journal_mode = OFF")
            db.exec("PRAGMA synchronous = OFF")
            db.exec("PRAGMA locking_mode = EXCLUSIVE")
            db.exec("PRAGMA page_size = ${options.bulkPageSize}")
            db.exec("PRAGMA temp_store = MEMORY")
        } else {
            db.exec("PRAGMA journal_mode = WAL")
            db.exec("PRAGMA synchronous = ${options.synchronous}")
            // a commit must never pay for a checkpoint: the WAL is folded into the file by [checkpoint], which the host calls when idle
            db.exec("PRAGMA wal_autocheckpoint = 0")
            // reads map the file instead of copying pages: the first touches after an open are much cheaper
            if (options.mmapMb > 0) db.exec("PRAGMA mmap_size = ${options.mmapMb.toLong() * 1048576}")
        }
        db.exec("PRAGMA cache_size = ${-(options.cacheMb ?: (if (bulk) 96 else 64)) * 1024}")
        for (ddl in SqlState.ddl) if (!(bulk && ddl.startsWith("CREATE INDEX"))) db.exec(ddl)
        if (!bulk) for (ddl in EXTRA_DDL) db.exec(ddl)
        for (ddl in SqlState.sessionDdl) db.exec(ddl)
        prepared = arrayOfNulls(catalog.size)
        named = HashMap()
        multi = HashMap()
        buffers.clear()
        loadCaches()
    }
    /** A prepared statement for any SQL text, cached for the life of the connection. */
    fun prep(sql: String): SqlStmt = named.getOrPut(sql) { db.prepare(sql) }

    private fun statement(id: Int): SqlStmt = prepared[id] ?: db.prepare(catalog[id]).also { prepared[id] = it }

    private fun q(name: String): SqlStmt = prep(SqlState.queries.getValue(name))

    fun getMeta(key: String): String? = q("metaGet").first(listOf(key))?.get(0) as String?

    fun setMeta(key: String, value: String) {
        q("metaPut").run(listOf(key, value))
    }

    /** The keys changed since the last call; `all` after a rebuild, a bulk load or a fresh open (everything may have changed). */
    fun takeTouched(): Touched {
        val t = tracker
        tracker = Tracker(false)
        if (t.all) return Touched(true, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), true, true)
        return Touched(false, t.items.toList(), t.playlists.toList(), t.spaces.toList(), t.sets.toList(), t.places.toList(), t.profile, t.findings)
    }

    /** Marks everything as possibly changed (the state was replaced). */
    fun touchAll() {
        tracker = Tracker(true)
        dirtyShards = ALL_SHARDS
    }

    fun markShardsClean(mask: Int) {
        dirtyShards = dirtyShards and mask.inv()
    }

    private fun loadCaches() {
        fun pairs(sql: String): HashMap<String, Long> {
            val out = HashMap<String, Long>()
            val s = db.prepare(sql)
            try {
                for (r in s.rows(emptyList())) out[r[0] as String] = r[1] as Long
            } finally {
                s.close()
            }
            return out
        }
        equiv = pairs("SELECT actor, from_seq FROM equiv")
        base = pairs("SELECT actor, seq FROM base")
        vv = pairs("SELECT actor, top FROM vv")
        dirtyVv.clear()
        pendingTotal = scalarLong("SELECT COUNT(*) FROM pending")
        maxHlcSeen = scalarString("SELECT v FROM meta WHERE k = 'maxHlc'")
        maxHlcDirty = false
        members = Membership()
        val s = db.prepare("SELECT signer, text FROM members ORDER BY actor, seq")
        try {
            for (r in s.rows(emptyList())) {
                val op = (decodeOp(r[1] as String) as? OpDecode.Ok)?.op ?: continue
                members.add(op, r[0] as String?)
            }
        } finally {
            s.close()
        }
    }

    private fun hasOpsOf(actor: String): Boolean =
        vv.containsKey(actor) || prep("SELECT 1 FROM pending WHERE actor = ? LIMIT 1").first(listOf(actor)) != null

    private fun scalarLong(sql: String): Long {
        val s = db.prepare(sql)
        try {
            return s.first(emptyList())!![0] as Long
        } finally {
            s.close()
        }
    }

    private fun scalarString(sql: String): String? {
        val s = db.prepare(sql)
        try {
            return s.first(emptyList())?.get(0) as String?
        } finally {
            s.close()
        }
    }

    /** Writes the bookkeeping the batch changed. */
    private fun flushCaches() {
        for (actor in dirtyVv) q("vvPut").run(listOf(actor, vv.getValue(actor)))
        dirtyVv.clear()
        val hlc = maxHlcSeen
        if (maxHlcDirty && hlc != null) q("metaPut").run(listOf("maxHlc", hlc))
        maxHlcDirty = false
    }

    fun close() {
        db.close()
    }

    /**
     * Folds the write-ahead log into the database file. Commits never do this themselves (it would show up as a latency
     * spike on a save), so the host calls it when idle. Returns the number of pages the log held.
     */
    fun checkpoint(truncate: Boolean = false): Long {
        val s = db.prepare("PRAGMA wal_checkpoint(${if (truncate) "TRUNCATE" else "PASSIVE"})")
        try {
            // the pragma answers (busy, log, checkpointed)
            return (s.first(emptyList())!![1] as Long)
        } finally {
            s.close()
        }
    }

    // ---- tracking which keys a statement list changes ----------------------------------------------------------------

    private class After(val entries: MutableList<String> = ArrayList(), val moved: MutableList<String> = ArrayList())

    private fun scan(statements: List<SqlStatement>, after: After) {
        val t = tracker
        val track = !t.all
        for (call in statements) {
            val p = call.params
            when (call.id) {
                0, 1, 2 -> {
                    val scope = p[0] as String
                    val k1 = p[1] as String
                    when (scope) {
                        "item" -> {
                            dirtyShards = dirtyShards or shardBit(k1)
                            if (track) t.items.add(k1)
                            if (call.id == 1) after.moved.add(k1)
                        }
                        "placement" -> {
                            dirtyShards = dirtyShards or shardBit(k1)
                            if (track) t.items.add(k1)
                        }
                        "entryKey" -> {
                            dirtyShards = dirtyShards or shardBit(k1)
                            after.entries.add(k1)
                        }
                        "set" -> {
                            dirtyShards = dirtyShards or META_BIT
                            if (track) t.sets.add(k1)
                        }
                        "playlist" -> {
                            dirtyShards = dirtyShards or META_BIT
                            if (track) t.playlists.add(k1)
                        }
                        "space" -> {
                            dirtyShards = dirtyShards or META_BIT
                            if (track) t.spaces.add(k1)
                        }
                        "place" -> {
                            dirtyShards = dirtyShards or META_BIT
                            if (track) t.places.add(k1)
                        }
                        "profile" -> {
                            dirtyShards = dirtyShards or META_BIT
                            t.profile = true
                        }
                    }
                }
                3, 4 -> {
                    val item = p[0] as String
                    dirtyShards = dirtyShards or shardBit(item)
                    if (track) t.items.add(item)
                    after.moved.add(item)
                }
                5 -> {
                    val entry = p[1] as String
                    t.findings = true
                    dirtyShards = dirtyShards or shardBit(entry)
                }
                6 -> {
                    val entry = p[0] as String
                    dirtyShards = dirtyShards or shardBit(entry)
                    if (track) {
                        t.playlists.add(p[1] as String)
                        // the playlist the entry leaves, when another one wins it
                        val old = prep("SELECT playlist FROM entries WHERE entry = ?").first(listOf(entry))
                        if (old != null) t.playlists.add(old[0] as String)
                    }
                    after.entries.add(entry)
                }
                7 -> {
                    val entry = p[0] as String
                    dirtyShards = dirtyShards or shardBit(entry)
                    after.entries.add(entry)
                }
                8 -> {
                    t.findings = true
                    val text = p[0] as String
                    dirtyShards = dirtyShards or (if (text.startsWith("entry-conflict:")) shardBit(text.substring(15)) else META_BIT)
                }
            }
        }
    }

    /** After the statements ran: the playlists of entries whose key or removal changed, and of items whose presence or revisions moved. */
    private fun scanAfter(after: After) {
        val t = tracker
        if (t.all) return
        for (entry in after.entries) {
            val row = prep("SELECT playlist FROM entries WHERE entry = ?").first(listOf(entry))
            if (row != null) t.playlists.add(row[0] as String)
        }
        for (item in after.moved) {
            for (row in prep("SELECT DISTINCT playlist FROM entries WHERE item = ?").rows(listOf(item))) t.playlists.add(row[0] as String)
        }
    }

    // ---- admission (spec section 4) -----------------------------------------------------------------------------------

    /**
     * Admits a batch of canonical op lines in one transaction and answers with one admission code per op, the codes of
     * `OpLog.admit`: admitted, duplicate, kept, invalid:<reason>, equivocation, setAside, covered, malformed:<problem>.
     * The item projection of every touched item is refreshed in the same transaction.
     *
     * An equivocation that moves a set-aside range sets [rebuildRequired]: the join cannot undo the effects of the
     * actor's ops from that seq on, so the state tables are rebuilt from the segments.
     *
     * [signer] is the device id of the verified signature of the segment the ops came in (null: an unsigned segment).
     * Once the library has a genesis, ops the membership refuses answer `setAside:<unsigned|unauthorized|revoked>`; a member
     * op that changes earlier verdicts sets [rebuildRequired] as well.
     */
    fun admit(ops: List<String>, signer: String? = null): List<String> {
        db.begin()
        buffers.clear()
        try {
            val touched = LinkedHashSet<String>()
            val codes = admitBatch(ops, touched, signer)
            flushBuffers()
            flushCaches()
            refreshItems(touched.toList())
            db.commit()
            return codes
        } catch (e: Throwable) {
            try {
                db.rollback()
            } catch (_: Throwable) {
                // the original failure matters more
            }
            buffers.clear()
            loadCaches()
            throw e
        }
    }

    /** The admission control of one batch: a code per op, the ops' statements run (or staged, see [importFresh]). Writes nothing else. */
    private fun admitBatch(ops: List<String>, touched: MutableSet<String>, signer: String?, signers: List<String?>? = null): List<String> {
        presentInBatch.clear()
        val judged = ArrayList<Pair<String, Judged>>(ops.size)
        for (text in ops) judged.add(text to judgeLine(text))
        // Which of the ops are held already: one query per few hundred ops instead of one per op.
        val keys = ArrayList<Any?>()
        for ((_, j) in judged) if (j is Judged.Op && j.seq > (base[j.actor] ?: 0L)) {
            keys.add(j.actor)
            keys.add(j.seq)
        }
        val known = heldAmong(keys)
        val batch = HashMap<String, String>()
        val codes = ArrayList<String>(ops.size)
        for ((i, p) in judged.withIndex()) codes.add(admitOne(p.first, p.second, touched, known, batch, if (signers != null) signers[i] else signer))
        return codes
    }

    /** Joins a batch of ops and answers how many were admitted (the code `admitted`). */
    fun applyOps(ops: List<String>): Int = admit(ops).count { it == "admitted" }

    private fun buffer(table: String, params: List<Any?>) {
        val rows = buffers[table]
        if (rows == null) {
            buffers[table] = arrayListOf(params)
        } else {
            rows.add(params)
            if (rows.size >= 20 * ROWS) flushBuffer(table)
        }
    }

    private fun multiStatement(table: String, sql: String): SqlStmt = multi.getOrPut(table) {
        val at = sql.indexOf("VALUES (")
        val end = if (at < 0) -1 else sql.indexOf(')', at)
        if (end < 0) throw IllegalStateException("Not an insert of one row: $sql")
        val group = sql.substring(at + 7, end + 1)
        db.prepare(sql.substring(0, at) + "VALUES " + List(ROWS) { group }.joinToString(", ") + sql.substring(end + 1))
    }

    /** The insert of a buffered table: `held`, a catalog statement by id, or a staging table `s<id>` (see [importFresh]). */
    private fun insertSql(table: String): String = when {
        table == "held" -> HELD_INSERT
        table.startsWith("s") -> "INSERT INTO st${table.substring(1)} VALUES (${List(STAGING_ARITY.getValue(table.substring(1).toInt())) { "?" }.joinToString(", ")})"
        else -> catalog[table.toInt()]
    }

    private fun flushBuffer(table: String) {
        val rows = buffers.remove(table)
        if (rows == null || rows.isEmpty()) return
        val sql = insertSql(table)
        val single = prep(sql)
        val many = multiStatement(table, sql)
        var at = 0
        while (at + ROWS <= rows.size) {
            val flat = ArrayList<Any?>(ROWS * rows[at].size)
            for (i in at until at + ROWS) flat.addAll(rows[i])
            many.run(flat)
            at += ROWS
        }
        while (at < rows.size) {
            single.run(rows[at])
            at++
        }
    }

    /** Writes every collected row (before anything reads the tables). */
    private fun flushBuffers() {
        for (table in buffers.keys.toList()) flushBuffer(table)
    }

    /** The "actor:seq" keys among the flat [keys] (actor, seq, actor, seq, ...) that the database holds. */
    private fun heldAmong(keys: List<Any?>): HashSet<String> {
        val out = HashSet<String>()
        val size = 300
        val pairs = keys.size / 2
        var at = 0
        var full: SqlStmt? = null
        while (at < pairs) {
            val n = minOf(size, pairs - at)
            val part = keys.subList(at * 2, (at + n) * 2)
            val statement: SqlStmt
            val own: Boolean
            if (n == size) {
                statement = full ?: prep(heldSql(size)).also { full = it }
                own = false
            } else {
                statement = db.prepare(heldSql(n))
                own = true
            }
            try {
                for (row in statement.rows(part)) out.add("${row[0]}:${row[1]}")
            } finally {
                if (own) statement.close()
            }
            at += n
        }
        return out
    }

    private fun heldSql(n: Int) = "SELECT actor, seq FROM held WHERE (actor, seq) IN (VALUES ${List(n) { "(?, ?)" }.joinToString(", ")})"

    private fun admitOne(text: String, j: Judged, touched: MutableSet<String>, known: Set<String>, batch: MutableMap<String, String>, signer: String?): String {
        if (j is Judged.Malformed) return "malformed:${j.problem}"
        j as Judged.Op
        val actor = j.actor
        val seq = j.seq
        val h = j.hash ?: hash(text)
        // Held rows exist only above the base (the state a bulk load started from), so a covered op is never a repeat.
        if (seq <= (base[actor] ?: 0L)) return "covered"
        val key = "$actor:$seq"
        val seen = batch[key]
        if (seen == h) return "duplicate"
        if (seen != null || known.contains(key)) {
            // A repeat, or a second version of an op: rare, so it is handled one by one over the rows written so far.
            flushBuffers()
            val row = q("heldGet").first(listOf(actor, seq))
            val heldHash = if (row != null) row[0] as String else (seen ?: "")
            if (heldHash == h) return "duplicate"
            q("variantPut").run(listOf(actor, seq, heldHash))
            if (q("variantPut").run(listOf(actor, seq, h)) == 0L) return "duplicate"
            tracker.findings = true
            val from = equiv[actor]
            if (from == null || seq < from) {
                q("equivPut").run(listOf(actor, seq))
                equiv[actor] = seq
                rebuildRequired = true
            }
            return "equivocation"
        }
        batch[key] = h
        buffer("held", listOf(actor, seq, h, j.kind, j.size))
        val seenHlc = maxHlcSeen
        if (seenHlc == null || j.hlc > seenHlc) {
            maxHlcSeen = j.hlc
            maxHlcDirty = true
        }
        advance(actor, seq)
        val from = equiv[actor]
        val equivocated = from != null && seq >= from
        val memberOp = j.memberOp
        if (j.kind == 0 && memberOp != null) {
            q("memberPut").run(listOf(actor, seq, signer, text))
            val change = members.add(memberOp, signer)
            if (change.all || change.actors.any { hasOpsOf(it) }) rebuildRequired = true
        }
        val memberWhy = if (j.kind != 0 || equivocated) null
        else if (memberOp != null) members.memberOpVerdict(OpId(actor, seq), signer)
        else members.verdict(actor, seq, signer)
        val aside = equivocated || memberWhy != null
        if (j.kind == 0 && !aside && staging) {
            for (call in j.statements) stage(call)
            touched.addAll(j.items)
        } else if (j.kind == 0 && !aside) {
            val after = After()
            scan(j.statements, after)
            // statements that read other tables (the entries and the findings they raise) wait for the rows collected so far
            if (j.statements.any { !BUFFERED.contains(it.id) || (it.id == 2 && it.params[0] == "entryKey") }) flushBuffers()
            for (call in j.statements) {
                // a key's presence row is written once per batch
                if (call.id == 0) {
                    val presence = "${call.params[0]}\u0000${call.params[1]}\u0000${call.params[2]}"
                    if (!presentInBatch.add(presence)) continue
                }
                if (BUFFERED.contains(call.id) && !(call.id == 2 && call.params[0] == "entryKey")) buffer(call.id.toString(), call.params)
                else statement(call.id).run(call.params)
            }
            scanAfter(after)
            touched.addAll(j.items)
        }
        if (j.kind == 0) return if (equivocated) "setAside" else if (memberWhy != null) "setAside:$memberWhy" else "admitted"
        if (j.kind == 1) return "kept"
        tracker.findings = true
        return "invalid:${j.reason}"
    }

    /** The version vector moves over the contiguous run of seqs; a seq above a gap waits in `pending`. */
    private fun advance(actor: String, seq: Long) {
        val top = vv[actor] ?: 0L
        if (seq == top + 1) {
            var next = seq
            if (pendingTotal > 0) {
                while (q("pendingDrop").run(listOf(actor, next + 1)) > 0) {
                    next++
                    pendingTotal--
                }
            }
            vv[actor] = next
            dirtyVv.add(actor)
        } else if (q("pendingPut").run(listOf(actor, seq)) > 0) {
            pendingTotal++
        }
    }

    // ---- the projection, from the shared core routine -----------------------------------------------------------------

    /** Recomputes the projection rows of [ids] (the core projects; this stores). One set of queries for the whole batch. */
    fun refreshItems(ids: List<String>) {
        var at = 0
        while (at < ids.size) {
            refreshChunk(ids.subList(at, minOf(ids.size, at + 5000)))
            at += 5000
        }
    }

    private fun refreshChunk(ids: List<String>) {
        if (ids.isEmpty()) return
        q("touchedClear").run(emptyList())
        for (id in ids) q("touchedPut").run(listOf(id))
        val none = emptyList<Any?>()
        val projected = SqlRows.projectRows(ids, q("projTags").rows(none), q("projFields").rows(none), q("projRevisions").rows(none), q("projParents").rows(none))
        for (r in projected) {
            val id = r[0] as String
            if (r[1] == null) q("viewDrop").run(listOf(id)) else q("viewPut").run(listOf(id, r[1], r[2], r[3], r[4], r[5], r[6]))
            val changed = if (r[7] == 1) q("cyclePut").run(listOf(id)) else q("cycleDrop").run(listOf(id))
            if (changed > 0) tracker.findings = true
        }
    }

    // ---- the first import: staged, merged in key order ----------------------------------------------------------------------

    private var staging = false

    /** Whether nothing was ever admitted: no held op, no loaded base, no state rows. */
    private fun isFresh(): Boolean {
        val s = db.prepare(
            "SELECT NOT EXISTS (SELECT 1 FROM held) AND NOT EXISTS (SELECT 1 FROM base) AND NOT EXISTS (SELECT 1 FROM vv) AND NOT EXISTS (SELECT 1 FROM pending) " +
                "AND NOT EXISTS (SELECT 1 FROM equiv) AND NOT EXISTS (SELECT 1 FROM present) AND NOT EXISTS (SELECT 1 FROM entries) AND NOT EXISTS (SELECT 1 FROM regs)",
        )
        try {
            return s.first(emptyList())!![0] == 1L
        } finally {
            s.close()
        }
    }

    private fun stage(call: SqlStatement) {
        // statement 5 only decides a finding against the entry row that is there; the merge decides it over all deliveries
        if (call.id != 5) buffer("s${call.id}", call.params)
    }

    /**
     * The first import of a library: admits every op of [ops] into an empty database (null, and nothing done, when the
     * database is not empty). The answer is one code per op, exactly what [admit] gives for the same ops in the same order.
     *
     * One op at a time would insert rows into the big tables in the order of the ops, which is the order of nothing: every
     * insert lands on another page. Instead the admission control runs as usual (held ops, the version vector, equivocations,
     * pending seqs), but the statements of the ops go to unindexed staging tables, and at the end each table is filled once, in
     * key order, from an aggregate of its staged rows: a register is the maximum of its deliveries, a revision the minimum,
     * an entry the smallest op, the rest the distinct rows, which is what the joins of [SqlState.catalog] compute one row at
     * a time. The projection of every touched item is then refreshed. All in one transaction.
     */
    fun importFresh(ops: Iterator<String>, signers: Iterator<String?>? = null): List<String>? {
        if (!isFresh()) return null
        db.begin()
        buffers.clear()
        staging = true
        try {
            for (ddl in STAGING_DDL) db.exec(ddl)
            touchAll()
            val touched = LinkedHashSet<String>()
            val codes = ArrayList<String>()
            val chunk = ArrayList<String>(IMPORT_CHUNK)
            val chunkSigners = ArrayList<String?>(if (signers != null) IMPORT_CHUNK else 0)
            while (ops.hasNext()) {
                chunk.add(ops.next())
                if (signers != null) chunkSigners.add(if (signers.hasNext()) signers.next() else null)
                if (chunk.size == IMPORT_CHUNK) {
                    codes.addAll(admitBatch(chunk, touched, null, if (signers != null) chunkSigners else null))
                    flushBuffers() // the held rows of this chunk, for the next chunk's lookup of repeats
                    chunk.clear()
                    chunkSigners.clear()
                }
            }
            if (chunk.isNotEmpty()) codes.addAll(admitBatch(chunk, touched, null, if (signers != null) chunkSigners else null))
            flushBuffers()
            flushCaches()
            staging = false
            mergeStaged()
            refreshItems(touched.toList())
            for (n in STAGING_ARITY.keys) db.exec("DROP TABLE temp.st$n")
            db.commit()
            return codes
        } catch (e: Throwable) {
            staging = false
            try {
                db.rollback()
            } catch (_: Throwable) {
                // the original failure matters more
            }
            buffers.clear()
            loadCaches()
            throw e
        }
    }

    private fun mergeStaged() {
        // the two secondary indexes of `entries` are built after the load, from the sorted rows
        db.exec("DROP INDEX IF EXISTS entries_by_playlist")
        db.exec("DROP INDEX IF EXISTS entries_by_item")
        for (sql in MERGE) db.exec(sql)
        for (ddl in SqlState.ddl + EXTRA_DDL) if (ddl.startsWith("CREATE INDEX")) db.exec(ddl)
    }
    // ---- reading the bookkeeping and the checkpoint inputs ----------------------------------------------------------------

    private fun query(sql: String, params: List<Any?> = emptyList()): List<List<Any?>> = prep(sql).rows(params)

    fun versionVector(): Map<String, Long> = query("SELECT actor, top FROM vv").associate { it[0] as String to it[1] as Long }

    fun pending(): Map<String, List<Long>> {
        val out = LinkedHashMap<String, MutableList<Long>>()
        for (r in query("SELECT actor, seq FROM pending ORDER BY actor, seq")) out.getOrPut(r[0] as String) { ArrayList() }.add(r[1] as Long)
        return out
    }

    fun setAside(): Map<String, Long> = query("SELECT actor, from_seq FROM equiv").associate { it[0] as String to it[1] as Long }

    fun maxHlc(): String? = maxHlcSeen

    /** Every state table as JSON rows `{"present": [[...]], ...}` (the input of [SqlState.stateFromRows]), for tests and tools. */
    fun readRowsJson(): String {
        val tables = LinkedHashMap<String, app.keepfin.core.sync.Jv>()
        for ((name, sql) in SqlViews.STATE_ROWS) {
            tables[name] = app.keepfin.core.sync.JArr(
                query(sql).map { row ->
                    app.keepfin.core.sync.JArr(
                        row.map { c ->
                            when (c) {
                                null -> app.keepfin.core.sync.JNull
                                is String -> app.keepfin.core.sync.JStr(c)
                                is Long -> app.keepfin.core.sync.JNum(c)
                                else -> throw IllegalStateException("unexpected cell")
                            }
                        },
                    )
                },
            )
        }
        return app.keepfin.core.sync.encodeCanonical(app.keepfin.core.sync.JObj(tables))
    }

    /** The state's findings, `invalid:<op>` for each invalid op, `equivocation:<op>` for each equivocated id and `revision-cycle:<item>`. */
    fun findings(): List<String> {
        fun all(sql: String) = query(sql).map { it[0] as String }
        return (
            all("SELECT text AS t FROM findings") +
                all("SELECT 'invalid:' || actor || ':' || seq AS t FROM held WHERE kind = 2") +
                all("SELECT DISTINCT 'equivocation:' || actor || ':' || seq AS t FROM variants") +
                all("SELECT 'revision-cycle:' || item AS t FROM cycles")
            ).sorted()
    }

    /**
     * The library's coverage for a checkpoint: the contiguous version vector, with an equivocating actor cut before its
     * set-aside range. Null when ops are held above a gap (the tables then hold ops a checkpoint must not): the host writes
     * nothing and tries again later.
     */
    fun checkpointCoverage(): Map<String, Long>? {
        if (pendingTotal > 0) return null
        val cap = LinkedHashMap<String, Long>()
        for ((actor, top) in vv) {
            val from = equiv[actor]
            var limit = if (from == null) top else minOf(top, from - 1)
            val cutoff = members.cutoff(actor)
            if (cutoff != null) limit = minOf(limit, cutoff)
            if (limit > 0) cap[actor] = limit
        }
        return cap
    }

    /**
     * The part file of one shard, written from the tables (SqlParts.kt). [coverage] is [checkpointCoverage] (the caller
     * guarantees it is not null). No write may happen in the database until the job is finished.
     */
    fun partJob(library: String, shard: String, coverage: Map<String, Long>): PartJob {
        if (shard != "meta" && "0123456789abcdef".indexOf(shard) < 0) throw IllegalArgumentException("Not a checkpoint shard: $shard")
        return PartJob(db, library, shard, coverage, maxHlcSeen)
    }

    /** What a remove or delete must observe (see `SyncObserved`): live add-tags and revision heads. */
    private fun liveTags(scope: String, k1: String, k2: String): List<String> {
        val rows = prep(SqlViews.LIVE_TAGS).rows(listOf(scope, k1, k2)).map { (it[0] as String) to (it[1] as Long) }.toMutableList()
        if (rows.size > 1) rows.sortWith { x, y -> if (x.first != y.first) x.first.compareTo(y.first) else x.second.compareTo(y.second) }
        return rows.map { "${it.first}:${it.second}" }
    }

    /** The revision events of an item that no other event names as a parent (all of them when that leaves none), sorted. */
    private fun headsOf(item: String): List<String> {
        val events = prep(SqlViews.REVISION_EVENTS).rows(listOf(item)).map { (it[0] as String) to (it[1] as Long) }.toMutableList()
        if (events.isEmpty()) return emptyList()
        val parents = prep(SqlViews.REVISION_PARENTS).rows(listOf(item)).map { "${it[0]}:${it[1]}" }.toHashSet()
        events.sortWith { x, y -> if (x.first != y.first) x.first.compareTo(y.first) else x.second.compareTo(y.second) }
        val all = events.map { "${it.first}:${it.second}" }
        val heads = all.filter { it !in parents }
        return heads.ifEmpty { all }
    }

    class ObservedItem(val tags: List<String>, val heads: List<String>)

    class Observed(
        val items: Map<String, ObservedItem>,
        val playlists: Map<String, List<String>>,
        val spaces: Map<String, List<String>>,
        val setElements: Map<String, Map<String, List<String>>>,
    )

    fun observed(items: List<String>, playlists: List<String>, spaces: List<String>, setElements: List<Pair<String, String>>): Observed {
        val setOut = LinkedHashMap<String, LinkedHashMap<String, List<String>>>()
        for ((set, element) in setElements) setOut.getOrPut(set) { LinkedHashMap() }[element] = liveTags("set", set, element)
        return Observed(
            items.associateWith { ObservedItem(liveTags("item", it, ""), headsOf(it)) },
            playlists.associateWith { liveTags("playlist", it, "") },
            spaces.associateWith { liveTags("space", it, "") },
            setOut,
        )
    }

    // ---- the cold path: the sorted bulk loader ----------------------------------------------------------------------------

    private val loadPlan: List<Pair<String, Int>> = listOf(
        "present" to 3, "tags" to 6, "regs" to 9, "revisions" to 5, "rev_parents" to 5, "entries" to 5,
        "removed_entries" to 1, "findings" to 1, "item_view" to 7, "cycles" to 1,
    )

    /** Inserts the rows of a state into the tables of the file being built (one reused parameter list per table). */
    private inner class Inserter : RowSink {
        var rows = 0L
        private val stmts = loadPlan.associate { (table, columns) -> table to db.prepare("INSERT INTO $table VALUES (${List(columns) { "?" }.joinToString(", ")})") }
        private val cells = loadPlan.associate { (table, columns) -> table to ArrayList<Any?>(columns) }

        private fun put(table: String, vararg values: Any?) {
            val c = cells.getValue(table)
            c.clear()
            for (v in values) c.add(v)
            stmts.getValue(table).run(c)
            rows++
        }

        override fun present(scope: String, k1: String, k2: String) = put("present", scope, k1, k2)
        override fun tag(scope: String, k1: String, k2: String, kind: String, actor: String, seq: Long) = put("tags", scope, k1, k2, kind, actor, seq)
        override fun reg(scope: String, k1: String, k2: String, wall: Long, counter: Long, hactor: String, oactor: String, oseq: Long, value: String) =
            put("regs", scope, k1, k2, wall, counter, hactor, oactor, oseq, value)
        override fun revision(item: String, actor: String, seq: Long, content: String, size: Long) = put("revisions", item, actor, seq, content, size)
        override fun revParent(item: String, actor: String, seq: Long, pactor: String, pseq: Long) = put("rev_parents", item, actor, seq, pactor, pseq)
        override fun entry(entry: String, playlist: String, item: String, actor: String, seq: Long) = put("entries", entry, playlist, item, actor, seq)
        override fun removedEntry(entry: String) = put("removed_entries", entry)
        override fun finding(text: String) = put("findings", text)
        override fun itemView(id: String, status: String, fields: String, heads: String, plays: Long, conflict: Long, dch: String) = put("item_view", id, status, fields, heads, plays, conflict, dch)
        override fun cycle(item: String) = put("cycles", item)

        fun close() {
            for (s in stmts.values) s.close()
        }
    }

    /**
     * Replaces this database by one built from checkpoint parts (sync spec 10.4), without ever holding more than one
     * shard of the state: parts are read shard by shard, their rows written in primary-key order inside one transaction
     * per shard into a new file with no journal, the index is built after the load, and the finished file is switched
     * in. The ops above the coverage are then admitted normally by the caller.
     *
     * `equivocations` (actor to first seq at which two variants are known) are recorded as set-aside ranges. Returns
     * `ok = false`, changing nothing, when an equivocation lies at or below a coverage some part claims (the parts are
     * then unusable, as for `OpLog.fromCheckpoint`).
     */
    fun bulkLoad(parts: List<ByteArray>, library: String, equivocations: Map<String, Long> = emptyMap()): BulkLoadResult =
        bulkLoadSources(parts.map { BytesPart(it) }, library, equivocations)

    /** The same for parts read on demand (see [PartSource]): the memory it needs is one shard, not the checkpoint. */
    fun bulkLoadSources(parts: List<PartSource>, library: String, equivocations: Map<String, Long> = emptyMap()): BulkLoadResult {
        try {
            return bulkLoadInto(parts, library, equivocations)
        } catch (e: Throwable) {
            // A part that cannot be read half-way: the half-built file is dropped and the database as it was is opened again.
            try {
                db.close()
            } catch (_: Throwable) {
                // already closed
            }
            for (suffix in listOf("", "-wal", "-shm", "-journal")) host.remove("$path.build$suffix")
            open(path, false)
            throw e
        }
    }

    private fun bulkLoadInto(parts: List<PartSource>, library: String, equivocations: Map<String, Long>): BulkLoadResult {
        if (path == ":memory:") throw IllegalStateException("bulkLoad needs a file database")
        val started = kotlin.time.TimeSource.Monotonic.markNow()
        fun ms() = started.elapsedNow().inWholeMicroseconds / 1000.0
        var peak = host.residentMb()
        trace?.invoke("bulk start: rss ${peak.toInt()} MB")
        val loader = app.keepfin.core.sqlstate.PartsLoader(library)
        for (part in parts) loader.add(part)
        val plan = loader.plan()
        for ((actor, seq) in equivocations) {
            if (seq <= (plan.highest[actor] ?: 0L)) return BulkLoadResult(false, 0, ms(), peak, 0.0, 0.0)
        }
        val target = "$path.build"
        val memberRows = db.prepare("SELECT actor, seq, signer, text FROM members").rows(emptyList()) // membership is not in checkpoints: carried over
        db.close()
        for (suffix in listOf("", "-wal", "-shm", "-journal")) host.remove(target + suffix)
        open(target, true)
        var rows = 0L
        var insertMs = 0.0
        var coreMs = 0.0
        for (shard in plan.shards) {
            val decoded = ms()
            val state = loader.shardState(shard) ?: throw IllegalStateException("The part of shard $shard cannot be read")
            coreMs += ms() - decoded
            val insertStarted = ms()
            db.exec("BEGIN")
            val inserter = Inserter()
            emitRows(state, inserter)
            inserter.close()
            rows += inserter.rows
            db.exec("COMMIT")
            insertMs += ms() - insertStarted
            peak = maxOf(peak, host.residentMb())
            trace?.invoke("bulk shard $shard: ${inserter.rows} rows, rss ${host.residentMb().toInt()} MB, ${ms().toInt()} ms")
        }
        for (ddl in SqlState.ddl + EXTRA_DDL) if (ddl.startsWith("CREATE INDEX")) {
            db.exec(ddl)
            peak = maxOf(peak, host.residentMb())
            trace?.invoke("bulk index ${ddl.substringAfter("EXISTS ").substringBefore(" ON")}: rss ${host.residentMb().toInt()} MB, ${ms().toInt()} ms")
        }
        db.exec("BEGIN")
        for ((actor, seq) in plan.coverage) {
            db.prepare("INSERT INTO base (actor, seq) VALUES (?, ?)").run(listOf(actor, seq))
            db.prepare("INSERT INTO vv (actor, top) VALUES (?, ?)").run(listOf(actor, seq))
        }
        plan.maxHlc?.let { db.prepare("INSERT INTO meta (k, v) VALUES (?, ?)").run(listOf("maxHlc", it)) }
        for ((actor, seq) in equivocations) db.prepare("INSERT INTO equiv (actor, from_seq) VALUES (?, ?)").run(listOf(actor, seq))
        for (row in memberRows) db.prepare("INSERT INTO members (actor, seq, signer, text) VALUES (?, ?, ?, ?)").run(row)
        db.exec("COMMIT")
        peak = maxOf(peak, host.residentMb())
        db.close()
        for (suffix in listOf("", "-wal", "-shm")) host.remove(path + suffix)
        host.rename(target, path)
        open(path, false)
        rebuildRequired = false
        touchAll()
        return BulkLoadResult(true, rows, ms(), peak, coreMs, insertMs)
    }

    // ---- equivocation: rebuild the state tables without the set-aside range -----------------------------------------------

    /**
     * After an equivocation the state must not contain the actor's ops from the first equivocated seq on, and a join
     * cannot take effects back. So the state tables are rebuilt: a new database gets the admission bookkeeping copied
     * over, then the state is replayed from [ops], which must give every op line of the segments (including the ones
     * below the checkpoint coverage; variants and set-aside ops are skipped), and the finished file is switched in.
     * Rare, so it favours being obviously right over being fast.
     */
    fun rebuild(ops: Iterator<String>, signers: Iterator<String?>? = null) {
        if (path == ":memory:") throw IllegalStateException("rebuild needs a file database")
        val old = db
        val target = "$path.rebuild"
        for (suffix in listOf("", "-wal", "-shm", "-journal")) host.remove(target + suffix)
        val fresh = host.open(target)
        // the statements of the old connection stay valid until it closes; the new one gets its own
        val savedPrepared = prepared
        val savedNamed = named
        val savedMulti = multi
        try {
            fresh.exec("PRAGMA journal_mode = OFF")
            fresh.exec("PRAGMA synchronous = OFF")
            for (ddl in SqlState.ddl + EXTRA_DDL) fresh.exec(ddl)
            for (ddl in SqlState.sessionDdl) fresh.exec(ddl)
            db = fresh
            prepared = arrayOfNulls(catalog.size)
            named = HashMap()
            multi = HashMap()
            // the admission bookkeeping is unchanged by a rebuild
            fresh.exec("BEGIN")
            for (table in listOf("held", "variants", "equiv", "base", "vv", "pending", "meta", "members")) {
                val rows = old.prepare("SELECT * FROM $table").rows(emptyList())
                if (rows.isEmpty()) continue
                val put = fresh.prepare("INSERT INTO $table VALUES (${List(rows[0].size) { "?" }.joinToString(", ")})")
                for (row in rows) put.run(row)
                put.close()
            }
            val touched = LinkedHashSet<String>()
            for (text in ops) {
                val signer = if (signers != null && signers.hasNext()) signers.next() else null
                val j = judgeLine(text)
                if (j !is Judged.Op || j.kind != 0) continue
                val held = q("heldGet").first(listOf(j.actor, j.seq))
                if (held != null && (held[0] as String) != (j.hash ?: hash(text))) continue // a variant, not the op that was held
                val from = q("equivGet").first(listOf(j.actor))?.get(0) as Long?
                if (from != null && j.seq >= from) continue
                val refused = if (j.memberOp != null) members.memberOpVerdict(OpId(j.actor, j.seq), signer) else members.verdict(j.actor, j.seq, signer)
                if (refused != null) continue
                for (call in j.statements) statement(call.id).run(call.params)
                touched.addAll(j.items)
                if (touched.size >= 5000) {
                    refreshItems(touched.toList())
                    touched.clear()
                }
            }
            refreshItems(touched.toList())
            fresh.exec("COMMIT")
            fresh.close()
        } catch (e: Throwable) {
            try {
                fresh.close()
            } catch (_: Throwable) {
                // closed already
            }
            for (suffix in listOf("", "-wal", "-shm", "-journal")) host.remove(target + suffix)
            db = old
            prepared = savedPrepared
            named = savedNamed
            multi = savedMulti
            throw e
        }
        old.close()
        for (suffix in listOf("", "-wal", "-shm")) host.remove(path + suffix)
        host.rename(target, path)
        open(path, false)
        rebuildRequired = false
        touchAll()
    }
    private companion object {
        val BUFFERED = setOf(0, 1, 2, 3, 4)
        const val ROWS = 100
        const val IMPORT_CHUNK = 5000

        /** Parameters of the statements that are staged, by statement id (see [importFresh]). */
        val STAGING_ARITY = mapOf(0 to 3, 1 to 6, 2 to 9, 3 to 5, 4 to 5, 6 to 5, 7 to 1, 8 to 1)

        val STAGING_DDL = listOf(
            "CREATE TEMP TABLE st0 (scope TEXT, k1 TEXT, k2 TEXT)",
            "CREATE TEMP TABLE st1 (scope TEXT, k1 TEXT, k2 TEXT, kind TEXT, actor TEXT, seq INTEGER)",
            "CREATE TEMP TABLE st2 (scope TEXT, k1 TEXT, k2 TEXT, wall INTEGER, counter INTEGER, hactor TEXT, oactor TEXT, oseq INTEGER, value TEXT)",
            "CREATE TEMP TABLE st3 (item TEXT, actor TEXT, seq INTEGER, content TEXT, size INTEGER)",
            "CREATE TEMP TABLE st4 (item TEXT, actor TEXT, seq INTEGER, pactor TEXT, pseq INTEGER)",
            "CREATE TEMP TABLE st6 (entry TEXT, playlist TEXT, item TEXT, actor TEXT, seq INTEGER)",
            "CREATE TEMP TABLE st7 (entry TEXT)",
            "CREATE TEMP TABLE st8 (text TEXT)",
        )

        /** Fills every state table once, in key order (see [importFresh]). */
        val MERGE = listOf(
            "INSERT OR IGNORE INTO present SELECT DISTINCT scope, k1, k2 FROM st0 ORDER BY scope, k1, k2",
            "INSERT OR IGNORE INTO tags SELECT DISTINCT scope, k1, k2, kind, actor, seq FROM st1 ORDER BY scope, k1, k2, kind, actor, seq",
            "INSERT INTO regs SELECT scope, k1, k2, wall, counter, hactor, oactor, oseq, value FROM (" +
                "SELECT *, ROW_NUMBER() OVER (PARTITION BY scope, k1, k2 ORDER BY wall DESC, counter DESC, hactor DESC, oactor DESC, oseq DESC, value DESC) AS rn FROM st2) " +
                "WHERE rn = 1 ORDER BY scope, k1, k2",
            "INSERT INTO revisions SELECT item, actor, seq, content, size FROM (" +
                "SELECT *, ROW_NUMBER() OVER (PARTITION BY item, actor, seq ORDER BY content, size) AS rn FROM st3) WHERE rn = 1 ORDER BY item, actor, seq",
            "INSERT OR IGNORE INTO rev_parents SELECT DISTINCT item, actor, seq, pactor, pseq FROM st4 ORDER BY item, actor, seq, pactor, pseq",
            "INSERT OR IGNORE INTO findings SELECT 'entry-conflict:' || entry FROM st6 GROUP BY entry HAVING COUNT(DISTINCT playlist) > 1 OR COUNT(DISTINCT item) > 1",
            "INSERT INTO entries SELECT entry, playlist, item, actor, seq FROM (" +
                "SELECT *, ROW_NUMBER() OVER (PARTITION BY entry ORDER BY actor, seq) AS rn FROM st6) WHERE rn = 1 ORDER BY entry",
            "INSERT OR IGNORE INTO removed_entries SELECT DISTINCT entry FROM st7 ORDER BY entry",
            "INSERT OR IGNORE INTO findings SELECT DISTINCT text FROM st8",
        )
    }
}
