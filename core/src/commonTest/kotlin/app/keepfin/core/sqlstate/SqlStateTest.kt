package app.keepfin.core.sqlstate

import app.keepfin.core.sync.OpGen
import app.keepfin.core.sync.toDelta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The statements are data for a host that runs them; these checks keep the catalog and the parameters in step. */
class SqlStateTest {
    private fun placeholders(sql: String): Int {
        // numbered placeholders (?1, ?2) count once each; plain ones count every occurrence
        val numbered = HashSet<Char>()
        var plain = 0
        var i = 0
        while (i < sql.length) {
            if (sql[i] == '?') {
                if (i + 1 < sql.length && sql[i + 1] in '1'..'9') numbered.add(sql[i + 1]) else plain++
            }
            i++
        }
        return plain + numbered.size
    }

    @Test
    fun everyStatementGetsExactlyItsParameters() {
        var seen = HashSet<Int>()
        for (seed in 1..20) {
            for (op in OpGen(seed, 3).generate(80)) {
                for (st in SqlState.deltaToSql(op.toDelta())) {
                    assertEquals(placeholders(SqlState.catalog[st.id]), st.params.size, "statement ${st.id}")
                    seen.add(st.id)
                    for (p in st.params) assertTrue(p == null || p is String || p is Long, "parameter type ${p?.let { it::class }}")
                }
            }
        }
        // the generator reaches every statement of the catalog
        assertEquals(SqlState.catalog.indices.toSet() - setOf(8), seen) // 8 (a delta's own findings) never occurs for a single op
    }

    @Test
    fun theSchemaCreatesEveryTableTheCatalogWrites() {
        val schema = SqlState.ddl.joinToString("\n")
        for (table in listOf("present", "tags", "regs", "revisions", "rev_parents", "entries", "removed_entries", "findings", "item_view")) {
            assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS $table "), table)
        }
    }
}
