package app.keepfin.copies

import app.keepfin.core.copies.CopyMode
import app.keepfin.core.copies.DesiredEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The executor over the fake folder with 20,000 files. The spec's budgets (dev-docs/plans/KEEPFIN-COPIES-SPEC.md section 11) are for 200,000
 * files: inventory of an unchanged copy under 10 s and planning under 3 s with the two indexes under 60 MB. At a tenth of the size the
 * unchanged rerun (inventory + plan + manifest) must stay under 3 s, and the heap it holds under 60 MB.
 */
internal class CopiesScaleTest {
    private fun heapMb(): Long {
        repeat(3) { System.gc() }
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
    }

    @Test fun twentyThousandFilesPlanBackUpAndRerunInBudget() {
        val rig = CopiesRig(CopyMode.Backup)
        val n = 20_000
        for (i in 0 until n) {
            val path = "Music/d${i % 200}/song-$i.mp3"
            val bytes = "content-$i".toByteArray()
            rig.main.files[path] = bytes
            rig.desired[path] = DesiredEntry(path, "item-$i", "op-$i", rig.sha(bytes), bytes.size.toLong())
        }
        for (d in 0 until 200) rig.main.mkdirs("Music/d$d")
        val before = heapMb()

        var t = System.nanoTime()
        val plan = rig.run(dryRun = true)
        val planMs = (System.nanoTime() - t) / 1_000_000
        assertEquals("preview", plan.status)
        assertEquals(n, plan.summary!!.counts.values.sum())

        t = System.nanoTime()
        val first = rig.run()
        val firstMs = (System.nanoTime() - t) / 1_000_000
        assertEquals("done", first.status)
        assertEquals(n, rig.copy.files.keys.count { it.startsWith("Music/") })
        val heapAfter = heapMb()

        t = System.nanoTime()
        val again = rig.run()
        val rerunMs = (System.nanoTime() - t) / 1_000_000
        assertEquals("done", again.status)
        assertEquals(0, again.exec?.committed ?: 0)
        println("KF-SCALE files=$n plan=${planMs}ms firstBackup=${firstMs}ms rerun=${rerunMs}ms heapBefore=${before}MB heapAfter=${heapAfter}MB")
        assertTrue("rerun $rerunMs ms", rerunMs <= 3_000)
        assertTrue("plan $planMs ms", planMs <= 6_000)
        assertTrue("heap grew ${heapAfter - before} MB", heapAfter - before <= 60 + n / 100)
    }
}
