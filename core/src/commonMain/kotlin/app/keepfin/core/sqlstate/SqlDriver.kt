package app.keepfin.core.sqlstate

// The synchronous SQL surface the shared merge state runs on (decision D-A3). The core performs no I/O: the host
// (node:sqlite on the desktop, a bundled SQLite driver on Android, JDBC in the JVM tests) implements these two
// interfaces and the common code never learns which one it has.
//
// The contract, identical on every host:
//  - A statement is prepared once and reused; [SqlStmt.run] and [SqlStmt.rows] bind ALL parameters in one call and
//    reset the statement afterwards (bind, step to the end, reset: a host that steps natively does it in its loop).
//    A host that has a cheaper way to run a whole parameter list (node:sqlite does) is free to use it; that is why the
//    interface is coarse instead of bind/step per value.
//  - Parameters are positional (`?` and `?N` alike, in order) and are `String`, `Long`, `Int`, `Double`, `ByteArray` or null.
//  - Integers are 64-bit. A host that cannot carry a number above 2^53 exactly (JavaScript) must refuse it with an
//    exception instead of rounding: HLC walls, sequence numbers and counts are far below it, and any value that might
//    be larger travels as TEXT. Rows give integers back as `Long`.
//  - NULL is `null` in both directions. REAL is `Double`. BLOB is `ByteArray` (not used by the merge state's tables).
//  - TEXT is compared by SQLite's default BINARY collation (UTF-8 bytes) on every host; the schema declares no other.
//  - A statement that fails throws; the transaction helper then rolls back. There are no nested transactions: the
//    merge state opens exactly one at a time ([SqlDriver.begin] / [SqlDriver.commit] / [SqlDriver.rollback]).
//  - [SqlStmt.close] finalizes the statement; closing the driver finalizes the rest. Statements are never used after.

interface SqlStmt {
    /** Runs the statement for its effect and returns the number of rows it changed. */
    fun run(params: List<Any?>): Long

    /** Runs a query and returns all its rows, each row a list of cells in column order (see the contract above). */
    fun rows(params: List<Any?>): List<List<Any?>>

    /** The first row of a query, or null when it has none. */
    fun first(params: List<Any?>): List<Any?>? = rows(params).firstOrNull()

    fun close()
}

interface SqlDriver {
    /** Runs one or several statements that take no parameters (DDL, PRAGMA, BEGIN, COMMIT). */
    fun exec(sql: String)

    fun prepare(sql: String): SqlStmt

    fun begin() = exec("BEGIN IMMEDIATE")

    fun commit() = exec("COMMIT")

    fun rollback() = exec("ROLLBACK")

    fun close()
}

/** Runs [block] in one transaction: commit when it returns, roll back and rethrow when it throws. */
inline fun <T> SqlDriver.transaction(block: () -> T): T {
    begin()
    val out = try {
        block()
    } catch (e: Throwable) {
        try {
            rollback()
        } catch (_: Throwable) {
            // the original failure matters more
        }
        throw e
    }
    commit()
    return out
}
