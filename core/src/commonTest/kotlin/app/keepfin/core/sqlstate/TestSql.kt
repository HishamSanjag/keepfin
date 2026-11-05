package app.keepfin.core.sqlstate

/**
 * A real SQLite for the tests, per target: sqlite-jdbc on the JVM, node:sqlite on JS. Both are the "driver" of the
 * shared merge state; the tests below run the same checks on each.
 */
internal expect class TestSql() {
    val host: SqlHost

    /** A path for a database file inside this instance's temporary directory. */
    fun path(name: String): String

    /** Deletes the temporary directory. */
    fun cleanup()
}
