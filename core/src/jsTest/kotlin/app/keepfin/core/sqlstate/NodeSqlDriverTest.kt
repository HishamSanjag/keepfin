package app.keepfin.core.sqlstate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The value contract of SqlDriver (see SqlDriver.kt) as node:sqlite keeps it: integers exact to 2^53 and refused beyond, NULL, REAL, BLOB, TEXT. */
class NodeSqlDriverTest {
    @Test
    fun valuesCrossTheBoundaryAsTheContractSays() {
        val t = TestSql()
        try {
            val db = t.host.open(":memory:")
            db.exec("CREATE TABLE v (i INTEGER, r REAL, t TEXT, b BLOB, n TEXT)")
            val put = db.prepare("INSERT INTO v VALUES (?, ?, ?, ?, ?)")
            val safe = 9007199254740991L
            assertEquals(1L, put.run(listOf(safe, 1.5, "naïve 😀", byteArrayOf(1, 2, -3), null)))
            assertEquals(1L, put.run(listOf(-safe, 0.0, "", byteArrayOf(), null)))
            val rows = db.prepare("SELECT i, r, t, b, n FROM v ORDER BY i DESC").rows(emptyList())
            assertEquals(safe, rows[0][0])
            assertEquals(1.5, rows[0][1])
            assertEquals("naïve 😀", rows[0][2])
            assertTrue((rows[0][3] as ByteArray).contentEquals(byteArrayOf(1, 2, -3)))
            assertNull(rows[0][4])
            assertEquals(-safe, rows[1][0])
            // beyond 2^53 an integer is refused on the way in, never rounded
            assertFailsWith<IllegalArgumentException> { put.run(listOf(safe + 1, 0.0, "", byteArrayOf(), null)) }
            // and an integer SQLite holds beyond 2^53 is refused on the way out (node:sqlite throws instead of rounding)
            db.exec("INSERT INTO v (i) VALUES (9223372036854775807)")
            assertFailsWith<Throwable> { db.prepare("SELECT i FROM v WHERE i > 9007199254740992").rows(emptyList()) }
            db.close()
        } finally {
            t.cleanup()
        }
    }
}
