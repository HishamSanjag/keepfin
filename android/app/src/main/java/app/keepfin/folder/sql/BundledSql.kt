package app.keepfin.folder.sql

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import app.keepfin.core.sqlstate.SqlDriver
import app.keepfin.core.sqlstate.SqlHost
import app.keepfin.core.sqlstate.SqlStmt
import java.io.File

// The Android host of the shared merge state (decision D-A3): the common SqlStateDb runs over androidx.sqlite's bundled
// SQLite (D-A2, one known SQLite version on every device), through the synchronous SqlDriver contract of the core.
// Parameters are String, Long, Int, Double, ByteArray or null; integers are 64-bit.

private const val INTEGER = 1
private const val FLOAT = 2
private const val TEXT = 3
private const val BLOB = 4

private class BundledStmt(private val st: SQLiteStatement, private val changes: SQLiteStatement, private val owner: MutableSet<BundledStmt>, private val counts: Boolean) : SqlStmt {
    private var open = true

    private fun bind(params: List<Any?>) {
        for ((i, p) in params.withIndex()) {
            val at = i + 1
            when (p) {
                null -> st.bindNull(at)
                is String -> st.bindText(at, p)
                is Long -> st.bindLong(at, p)
                is Int -> st.bindLong(at, p.toLong())
                is Double -> st.bindDouble(at, p)
                is ByteArray -> st.bindBlob(at, p)
                else -> throw IllegalArgumentException("unsupported SQL parameter")
            }
        }
    }

    override fun run(params: List<Any?>): Long {
        try {
            bind(params)
            while (st.step()) {
                // a statement that returns rows (PRAGMA) is run to its end
            }
        } finally {
            st.reset()
        }
        // a plain insert changes one row (or fails); only statements that can change none, or many, ask SQLite how many
        if (!counts) return 1L
        return try {
            if (changes.step()) changes.getLong(0) else 0L
        } finally {
            changes.reset()
        }
    }

    override fun rows(params: List<Any?>): List<List<Any?>> {
        val out = ArrayList<List<Any?>>()
        try {
            bind(params)
            val n = st.getColumnCount()
            while (st.step()) {
                val row = ArrayList<Any?>(n)
                for (c in 0 until n) {
                    row.add(
                        when (st.getColumnType(c)) {
                            INTEGER -> st.getLong(c)
                            FLOAT -> st.getDouble(c)
                            TEXT -> st.getText(c)
                            BLOB -> st.getBlob(c)
                            else -> null
                        },
                    )
                }
                out.add(row)
            }
        } finally {
            st.reset()
        }
        return out
    }

    override fun close() {
        if (!open) return
        open = false
        owner.remove(this)
        st.close()
    }
}

private fun needsCount(sql: String): Boolean {
    val s = sql.trimStart()
    if (s.startsWith("INSERT INTO") && !s.contains("ON CONFLICT") && !s.contains("SELECT")) {
        // a multi-row or plain insert: the caller gets the number of rows it wrote (rows in the VALUES lists)
        return false
    }
    return true
}

/** One connection. The statements are not thread safe: a connection belongs to one thread at a time. */
class BundledSqlDriver(private val conn: SQLiteConnection) : SqlDriver {
    private val changes: SQLiteStatement = conn.prepare("SELECT changes()")

    // every statement prepared here: closing the connection finalizes the ones still open (SQLite will not release the file while any lives)
    private val live: MutableSet<BundledStmt> = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())

    override fun exec(sql: String) {
        val st = conn.prepare(sql)
        try {
            while (st.step()) {
                // PRAGMA statements answer with a row
            }
        } finally {
            st.close()
        }
    }

    override fun prepare(sql: String): SqlStmt = BundledStmt(conn.prepare(sql), changes, live, needsCount(sql)).also { live.add(it) }

    override fun close() {
        for (s in live.toList()) runCatching { s.close() }
        try {
            changes.close()
        } finally {
            conn.close()
        }
    }

    /** Runs a query for its rows (a convenience for the readers; not part of the shared contract). */
    fun query(sql: String, params: List<Any?> = emptyList()): List<List<Any?>> {
        val s = prepare(sql)
        try {
            return s.rows(params)
        } finally {
            s.close()
        }
    }
}

object BundledSql {
    private val driver = BundledSQLiteDriver()

    fun openWriter(path: String): BundledSqlDriver =
        BundledSqlDriver(driver.open(path, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE))

    /** A read-only connection (WAL: it reads while the writer commits). */
    fun openReader(path: String): BundledSqlDriver =
        BundledSqlDriver(driver.open(path, SQLITE_OPEN_READONLY)).also {
            it.exec("PRAGMA query_only = 1")
            it.exec("PRAGMA cache_size = -8192")
            it.exec("PRAGMA mmap_size = 268435456")
        }

    /** The SQLite version and the compile options that matter, for the log (spec probe 1). */
    fun probe(path: String): String {
        val c = openWriter(path)
        try {
            val version = c.query("SELECT sqlite_version()")[0][0] as String
            val fts5 = try {
                c.exec("CREATE VIRTUAL TABLE IF NOT EXISTS temp.kf_fts_probe USING fts5(x)")
                c.exec("DROP TABLE temp.kf_fts_probe")
                true
            } catch (e: Throwable) {
                false
            }
            return "sqlite $version fts5=$fts5"
        } finally {
            c.close()
        }
    }
}

/** The Android [SqlHost]: opens writer connections through the bundled driver. */
class AndroidSqlHost : SqlHost {
    override fun open(path: String): SqlDriver = BundledSql.openWriter(path)

    override fun remove(path: String) {
        File(path).delete()
    }

    override fun rename(from: String, to: String) {
        val target = File(to)
        if (target.exists()) target.delete()
        if (!File(from).renameTo(target)) throw java.io.IOException("Could not rename $from")
    }

    override fun residentMb(): Double = try {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }?.let { l -> l.filter { it.isDigit() }.toLong() / 1024.0 }
        } ?: 0.0
    } catch (e: Throwable) {
        0.0
    }
}
