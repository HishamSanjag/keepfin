package app.keepfin.core.merge

import app.keepfin.core.library.LibItem
import app.keepfin.core.sync.Hlc
import kotlin.test.*
import kotlin.time.TimeSource

/** Full 200k x 200k in-memory index/ledger, streaming pages and outputs, on both JVM and Node. */
class ScaleTest {
    private val actor = "10000000-0000-4000-8000-000000000001"
    private val replica = "10000000-0000-4000-8000-000000000002"
    private fun id(n: Int) = "20000000-0000-4000-8000-" + n.toString(16).padStart(12, '0')
    private fun hash(n: Int) = "sha256:" + n.toString(16).padStart(64, '0')

    @Test fun planning200000Against200000Within60Seconds() {
        val n = 200_000
        // Index construction is reported separately; planning includes source creation, reviews and batch hashing.
        val buildStart = TimeSource.Monotonic.markNow()
        val targets = (0 until n).map {
            TItem(LibItem(id(it), "music", "audio", "Song"), Hlc(it.toLong(), 0, actor), hash(it), 100)
        }
        val index = InMemoryTHashIndex(targets)
        val ledger = InMemoryMergeLedger()
        val buildMs = buildStart.elapsedNow().inWholeMilliseconds
        var drafts = 0
        var maps = 0
        var reviews = 0
        var added = 0
        val sink = object : MergeSink {
            override fun batch(batch: Batch) {
                drafts += batch.drafts.size
                assertTrue(batch.drafts.size <= 500)
            }
            override fun map(line: MapLine) {
                maps++
                if (line.how == MapHow.Added) added++
            }
            override fun review(entry: ReviewEntry) { reviews++ }
            override fun art(copy: ArtCopy) { error("no artwork") }
        }
        val config = MergeConfig("scale", actor, replica)
        val planner = MergePlanner(config, index, ledger, sink)
        val start = TimeSource.Monotonic.markNow()
        for (from in 0 until n step 200) {
            val page = (from until minOf(from + 200, n)).map {
                // Half linked, half new: exercise both the hash index and deterministic id generation.
                SItem(LibItem(id(n + it), "music", "audio", "Song"), Hlc(it.toLong(), 0, actor),
                    hash(if (it % 2 == 0) it else n + it), 100, 10, "Music/" + it + ".mp3",
                    FileEvidence(true, 100, 10))
            }
            assertTrue(planner.items(page).accepted)
        }
        planner.finish()
        val ms = start.elapsedNow().inWholeMilliseconds
        println("MERGE SCALE S=" + n + " T=" + n + ": index " + buildMs + " ms; plan " + ms +
            " ms; drafts " + drafts + "; map " + maps + "; review " + reviews + "; page=200 batch<=500")
        assertEquals(n, maps)
        assertEquals(n / 2, added)
        assertEquals(n / 2 + n / 2 * 3, drafts)
        assertTrue(ms <= 60_000, "planning exceeded the PC 60 s budget: " + ms + " ms")
    }
}
