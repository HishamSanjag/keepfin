package app.keepfin.core.copies

import app.keepfin.core.sync.scaleEnv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Planner at scale (spec section 11): 20,000 paths by default, KEEPFIN_SCALE=200000 for the full size. The inputs are
 * generated streams (nothing is held in lists), so what the heap shows is the planner's own indexes plus its plan.
 * Mix: mostly unchanged files, some edits on main, some new on main, some edits and deletions on the copy, some renames.
 */
class ScaleTest {
    private fun name(i: Int) = "Music/d" + (i / 500).toString().padStart(4, '0') + "/t" + i.toString().padStart(6, '0') + ".mp3"

    private fun content(i: Int, gen: Int) = "sha256:" + (i * 10 + gen).toString().padStart(64, '0')

    @Test
    fun planning200kPathsStaysWithinItsBudget() {
        val n = scaleEnv()?.toIntOrNull() ?: 20_000
        val full = n >= 200_000
        // kind of change per index
        fun kind(i: Int) = when (i % 100) {
            in 0..1 -> 1 // edited on main
            2 -> 2 // new on main (not on copy, not in baseline)
            3 -> 3 // removed from the copy by hand
            4 -> 4 // item deleted in the library, still on the copy
            else -> 0
        }
        val deleted = HashSet<String>()
        for (i in 0 until n) if (kind(i) == 4) deleted.add("i$i")
        val desired: Source<DesiredEntry> = {
            (0 until n).asSequence().filter { kind(it) != 4 }.map { DesiredEntry(name(it), "i$it", "op", content(it, if (kind(it) == 1) 1 else 0), 5_000_000) }.iterator()
        }
        val main: Source<InvLine> = { (0 until n).asSequence().filter { kind(it) != 4 }.map { InvLine(name(it), 5_000_000, 1_000_000L + it) }.iterator() }
        val copy: Source<InvLine> = { (0 until n).asSequence().filter { kind(it) != 2 && kind(it) != 3 }.map { InvLine(name(it), 5_000_000, 1_000_000L + it) }.iterator() }
        val baseline: Source<BaselineEntry> = {
            (0 until n).asSequence().filter { kind(it) != 2 }.map {
                BaselineEntry(name(it), "i$it", "op", content(it, 0), Stat(5_000_000, 1_000_000L + it), Stat(5_000_000, 1_000_000L + it), 900_000_000L, 1)
            }.iterator()
        }
        val input = PlannerInput(
            CopyMode.TwoWay, PlaceOptions(deleteGuardPercent = 100), NameRules.caseInsensitiveAscii, desired, main, copy, baseline,
            SimpleLibraryFacts(deleted), timestampGranularityMs = 1,
        )
        val before = usedHeapBytes()
        val t = TimeSource.Monotonic.markNow()
        val outcome = Planner.plan(input, Evidence { _, line -> content(line.path.substringAfterLast("/t").substringBefore(".").toInt(), 0) })
        val ms = t.elapsedNow().inWholeMilliseconds
        val after = usedHeapBytes()
        val plan = (outcome as? PlanOutcome.Ready)?.plan ?: error("unexpected hash requests: ${(outcome as PlanOutcome.NeedsHashes).requests.size}")
        val counts = plan.summary.counts
        // 2% edited on main are copied, 1% new copied, 1% removed by hand are re-copied (and proposed), 1% deletions are removed
        val perHundred = n / 100
        assertEquals(2 * perHundred, counts[ActionKind.CopyTo]!! - perHundred - perHundred, "edits copied")
        assertEquals(perHundred, counts[ActionKind.RemoveFromCopy] ?: 0, "item deletions reach the copy")
        println("COPIES SCALE n=$n: plan $ms ms; ${plan.actions.size} actions; heap delta ${(after - before) / 1_000_000} MB (plan with actions included)")
        if (full) {
            assertTrue(ms <= 3000, "planning took $ms ms (budget 3 s)")
            assertTrue(after - before <= 60_000_000, "the planner holds ${(after - before) / 1_000_000} MB (budget 60 MB)")
        }
    }
}
