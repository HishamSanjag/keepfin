package app.keepfin.core.sqlstate

import app.keepfin.core.sync.ItemStatus
import app.keepfin.core.sync.OpGen
import app.keepfin.core.sync.hasRevisionCycle
import app.keepfin.core.sync.itemHeads
import app.keepfin.core.sync.itemStatus
import app.keepfin.core.sync.logOf
import app.keepfin.core.sync.plays
import app.keepfin.core.sync.projectItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The one item projection (used by the views and by the SQL projection) agrees with the State's own reading rules. */
class ProjectionTest {
    @Test
    fun theProjectionFollowsTheStateRules() {
        var checked = 0
        var cycles = 0
        for (seed in 1..40) {
            val state = logOf(OpGen(seed, 4).generate(120 + seed * 3)).state
            for ((id, item) in state.items) {
                val p = projectItem(item)
                val status = state.itemStatus(id)
                val expected = when (status) {
                    ItemStatus.Unknown -> null
                    ItemStatus.Present -> "present"
                    ItemStatus.Deleted -> "deleted"
                    ItemStatus.DeleteConflict -> "deleteConflict"
                }
                assertEquals(expected, p.status, "status of $id seed=$seed")
                assertEquals(state.hasRevisionCycle(id), p.cycle, "cycle of $id seed=$seed")
                if (status != ItemStatus.Unknown) {
                    assertEquals(state.plays(id), p.plays, "plays of $id")
                    assertEquals(state.itemHeads(id).size > 1, p.contentConflict, "content conflict of $id")
                    // the heads text lists exactly the heads, in op order
                    assertEquals(state.itemHeads(id).size, Regex("\"op\":").findAll(p.heads).count())
                    checked++
                }
                if (p.cycle) cycles++
            }
        }
        assertTrue(checked > 100, "checked $checked items")
    }

    @Test
    fun theRowsFedBackThroughTheProjectionGiveTheSameAnswer() {
        // the SQL path: rows of the touched items go in as JSON and the same routine answers
        val tag = "[\"i1\",\"a\",\"a0000000-0000-0000-0000-00000000000a\",1]"
        val input = "{\"ids\":[\"i1\",\"i2\"],\"tags\":[$tag],\"fields\":[[\"i1\",\"title\",\"\\\"x\\\"\"],[\"i1\",\"plays@a\",\"3\"],[\"i1\",\"plays@b\",\"4\"]],\"revisions\":[],\"parents\":[]}"
        val out = SqlRows.projectItemsJson(input)
        assertTrue(out.startsWith("[[\"i1\",\"present\","), out)
        assertTrue(out.contains(",7,0,"), "plays 7, no conflict: $out")
        assertTrue(out.contains("[\"i2\",null,"), "an item with no add is unknown: $out")
    }
}
