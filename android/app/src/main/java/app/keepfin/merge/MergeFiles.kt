package app.keepfin.merge

import app.keepfin.core.merge.ArtCopy
import app.keepfin.core.merge.Batch
import app.keepfin.core.merge.MapHow
import app.keepfin.core.merge.MapLine
import app.keepfin.core.merge.MergeLedger
import app.keepfin.core.merge.MergeSink
import app.keepfin.core.merge.MergeWire
import app.keepfin.core.merge.ReviewEntry
import app.keepfin.core.sqlstate.SqlDriver
import app.keepfin.core.sqlstate.SqlHost
import app.keepfin.core.sqlstate.SqlStmt
import java.io.BufferedWriter
import java.io.File

// The files and scratch tables of one planning pass. Nothing here is library-sized in memory: batches are small files, the map and review are
// append-only line files, and the lookups the planner needs (content already seen in S, the S-to-T map, the order of S) are SQLite tables in a
// scratch database that is thrown away and rebuilt for every pass ("start with fresh scratch data", M1 README).

private fun name(index: Int) = index.toString().padStart(6, '0') + ".json"

/** Writes what the planner emits to a work directory: `batches/<k>.json`, `map.jsonl`, `review.jsonl`, `art.jsonl`. */
class PlanWriter(private val dir: File) : MergeSink {
    private val batchDir = File(dir, "batches").also { it.mkdirs() }
    private val mapOut: BufferedWriter = File(dir, "map.jsonl").bufferedWriter(Charsets.UTF_8)
    private val reviewOut: BufferedWriter = File(dir, "review.jsonl").bufferedWriter(Charsets.UTF_8)
    private val artOut: BufferedWriter = File(dir, "art.jsonl").bufferedWriter(Charsets.UTF_8)

    var batches = 0
        private set
    var mapLines = 0L
        private set
    var reviews = 0L
        private set
    var arts = 0
        private set

    override fun batch(batch: Batch) {
        File(batchDir, name(batch.index)).writeText(MergeWire.batch(batch), Charsets.UTF_8)
        batches = maxOf(batches, batch.index + 1)
    }

    override fun map(line: MapLine) { mapOut.write(line.json()); mapOut.write("\n"); mapLines++ }
    override fun review(entry: ReviewEntry) { reviewOut.write(MergeWire.review(entry)); reviewOut.write("\n"); reviews++ }
    override fun art(copy: ArtCopy) { artOut.write(MergeWire.art(copy)); artOut.write("\n"); arts++ }

    fun close() {
        mapOut.close(); reviewOut.close(); artOut.close()
    }
}

/** Reads a work directory back. */
object PlanFiles {
    fun batch(dir: File, index: Int): Batch =
        MergeWire.readBatch(MergeWire.parse(File(File(dir, "batches"), name(index)).readText(Charsets.UTF_8)))

    /** The S-to-T map in the order it was planned. */
    fun mapLines(file: File): Sequence<MapLine> = sequence {
        if (!file.isFile) return@sequence
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) if (line.isNotEmpty()) yield(MergeWire.map(MergeWire.parse(line)))
        }
    }

    fun lines(file: File): Sequence<String> = sequence {
        if (!file.isFile) return@sequence
        file.bufferedReader(Charsets.UTF_8).useLines { lines -> for (line in lines) if (line.isNotEmpty()) yield(line) }
    }

    fun clear(dir: File) { dir.deleteRecursively(); dir.mkdirs() }
}

/**
 * Hash results kept across the dry run and the real run, so a file that did not change is read once. A result is used only when the file's
 * size and modification time are the ones it was read at; anything else is read again. Survives a restart (a file in the merge's work directory).
 */
class HashCache(host: SqlHost, path: String) : AutoCloseable {
    private val db: SqlDriver
    private val getStmt: SqlStmt
    private val putStmt: SqlStmt

    init {
        File(path).parentFile?.mkdirs()
        db = host.open(path)
        db.exec("PRAGMA journal_mode = OFF")
        db.exec("PRAGMA synchronous = OFF")
        db.exec("CREATE TABLE IF NOT EXISTS hashes (path TEXT NOT NULL PRIMARY KEY, size INTEGER NOT NULL, mtime INTEGER NOT NULL, hash TEXT NOT NULL) WITHOUT ROWID")
        getStmt = db.prepare("SELECT hash FROM hashes WHERE path = ?1 AND size = ?2 AND mtime = ?3")
        putStmt = db.prepare("INSERT OR REPLACE INTO hashes (path, size, mtime, hash) VALUES (?1, ?2, ?3, ?4)")
    }

    fun get(path: String, size: Long, mtime: Long): String? = getStmt.first(listOf(path, size, mtime))?.get(0) as String?
    fun put(path: String, size: Long, mtime: Long, hash: String) { putStmt.run(listOf(path, size, mtime, hash)) }

    override fun close() {
        runCatching { getStmt.close(); putStmt.close() }
        runCatching { db.close() }
    }
}

/** A scratch SQLite file: the planner's ledger, the order of the items of S, and (later) the lookups of a finished map. */
class Scratch(private val host: SqlHost, private val path: String) : MergeLedger, AutoCloseable {
    private val db: SqlDriver
    private val firstSourceStmt: SqlStmt
    private val rememberStmt: SqlStmt
    private val mappedStmt: SqlStmt
    private val recordStmt: SqlStmt

    init {
        File(path).parentFile?.mkdirs()
        host.remove(path)
        db = host.open(path)
        db.exec("PRAGMA journal_mode = OFF")
        db.exec("PRAGMA synchronous = OFF")
        db.exec("CREATE TABLE content (hash TEXT NOT NULL PRIMARY KEY, src TEXT NOT NULL) WITHOUT ROWID")
        db.exec("CREATE TABLE lines (s TEXT NOT NULL PRIMARY KEY, t TEXT NOT NULL, how TEXT NOT NULL) WITHOUT ROWID")
        db.exec("CREATE TABLE src_order (w INTEGER NOT NULL, id TEXT NOT NULL, PRIMARY KEY (w, id)) WITHOUT ROWID")
        firstSourceStmt = db.prepare("SELECT src FROM content WHERE hash = ?1")
        rememberStmt = db.prepare("INSERT OR IGNORE INTO content (hash, src) VALUES (?1, ?2)")
        mappedStmt = db.prepare("SELECT s, t, how FROM lines WHERE s = ?1")
        recordStmt = db.prepare("INSERT INTO lines (s, t, how) VALUES (?1, ?2, ?3)")
    }

    override fun firstSource(hash: String): String? = firstSourceStmt.first(listOf(hash))?.get(0) as String?
    override fun rememberContent(hash: String, source: String) { rememberStmt.run(listOf(hash, source)) }

    override fun mapped(source: String): MapLine? = mappedStmt.first(listOf(source))?.let { row ->
        MapLine(row[0] as String, row[1] as String, MapHow.entries.first { it.wire == row[2] })
    }

    override fun record(line: MapLine) {
        require(mapped(line.s) == null) { "source item already planned" }
        recordStmt.run(listOf(line.s, line.t, line.how.wire))
    }

    // ---- the order of S -------------------------------------------------------------------------------------------

    fun addOrder(rows: List<Pair<Long, String>>) {
        if (rows.isEmpty()) return
        db.begin()
        try {
            val st = db.prepare("INSERT OR IGNORE INTO src_order (w, id) VALUES (?1, ?2)")
            for ((w, id) in rows) st.run(listOf(w, id))
            st.close()
            db.commit()
        } catch (e: Throwable) {
            runCatching { db.rollback() }
            throw e
        }
    }

    private val orderFirst: SqlStmt by lazy { db.prepare("SELECT w, id FROM src_order ORDER BY w, id LIMIT ?1") }
    private val orderAfter: SqlStmt by lazy { db.prepare("SELECT w, id FROM src_order WHERE (w, id) > (?1, ?2) ORDER BY w, id LIMIT ?3") }
    private val sOfStmt: SqlStmt by lazy { db.prepare("SELECT s FROM lines WHERE t = ?1 ORDER BY s LIMIT 1") }

    fun orderCount(): Long = (db.prepare("SELECT COUNT(*) FROM src_order").first(emptyList())?.get(0) as? Long) ?: 0L

    /** The next [limit] items after the key (w, id), in (w, id) order; null starts at the beginning. */
    fun orderPage(after: Pair<Long, String>?, limit: Int): List<Pair<Long, String>> {
        val rows = if (after == null) orderFirst.rows(listOf(limit)) else orderAfter.rows(listOf(after.first, after.second, limit))
        return rows.map { (it[0] as Long) to (it[1] as String) }
    }

    // ---- a finished map ---------------------------------------------------------------------------------------------

    /** Loads the lines of a map file into the `lines` table (used by remapping, which needs lookups by either side). */
    fun loadMap(lines: Sequence<MapLine>) {
        db.exec("CREATE INDEX IF NOT EXISTS lines_by_t ON lines (t)")
        db.begin()
        try {
            val st = db.prepare("INSERT OR IGNORE INTO lines (s, t, how) VALUES (?1, ?2, ?3)")
            for (l in lines) st.run(listOf(l.s, l.t, l.how.wire))
            st.close()
            db.commit()
        } catch (e: Throwable) {
            runCatching { db.rollback() }
            throw e
        }
    }

    fun tOf(s: String): String? = mappedStmt.first(listOf(s))?.get(1) as String?
    fun sOf(t: String): String? = sOfStmt.first(listOf(t))?.get(0) as String?

    override fun close() {
        runCatching { firstSourceStmt.close(); rememberStmt.close(); mappedStmt.close(); recordStmt.close() }
        runCatching { db.close() }
    }
}
