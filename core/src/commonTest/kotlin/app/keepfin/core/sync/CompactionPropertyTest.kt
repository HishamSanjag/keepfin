package app.keepfin.core.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Random segment layouts against the compaction planner: it never opens a gap, never merges across signers, never loses coverage. */
class CompactionPropertyTest {
    private val signers = listOf("k", "old", null)

    private fun layout(random: Random, allowOverlap: Boolean): List<SegmentInfo> {
        val out = ArrayList<SegmentInfo>()
        for (actor in listOf("a", "b", "c").take(1 + random.nextInt(3))) {
            var next = 1L
            val files = random.nextInt(1, 120)
            var signer = signers[random.nextInt(signers.size)]
            repeat(files) {
                // mostly tiny files, now and then a big one, a gap, a signer change, a bad file, a named file
                val ops = when (random.nextInt(10)) {
                    0 -> random.nextLong(200, 3500)
                    1, 2 -> random.nextLong(30, 400)
                    else -> random.nextLong(1, 6)
                }
                if (random.nextInt(25) == 0) next += random.nextLong(1, 5)
                if (random.nextInt(12) == 0) signer = signers[random.nextInt(signers.size)]
                val first = if (allowOverlap && out.isNotEmpty() && random.nextInt(15) == 0) maxOf(1L, next - random.nextLong(1, 20)) else next
                val bytes = ops * random.nextLong(60, 400)
                // a folder never holds two files of one name
                if (out.none { it.actor == actor && it.first == first && it.last == first + ops - 1 }) {
                    out.add(SegmentInfo(actor, first, first + ops - 1, bytes, signer, random.nextInt(60) != 0, random.nextInt(40) == 0))
                }
                next = maxOf(next, first + ops)
            }
        }
        return out
    }

    private fun coverage(files: List<SegmentInfo>): Map<String, Set<Long>> {
        val out = HashMap<String, MutableSet<Long>>()
        for (f in files) {
            val set = out.getOrPut(f.actor) { HashSet() }
            var s = f.first
            while (s <= f.last) set.add(s++)
        }
        return out
    }

    private fun cleanActors(files: List<SegmentInfo>): Set<String> {
        val bad = HashSet<String>()
        for ((actor, list) in files.groupBy { it.actor }) {
            val sorted = list.sortedBy { it.first }
            if (sorted.any { !it.ok } || sorted.zipWithNext().any { (x, y) -> x.last >= y.first }) bad.add(actor)
        }
        return files.map { it.actor }.toSet() - bad
    }

    @Test
    fun plansNeverOpenAGapOrCrossASigner() {
        for (seed in 1..400) {
            val random = Random(seed)
            val files = layout(random, allowOverlap = false)
            val current = signers[random.nextInt(signers.size)]
            val blocked = if (random.nextInt(5) == 0) setOf("b") else emptySet()
            val merges = SegmentCompaction.plan(files, current, blocked)
            val used = HashSet<Int>()
            val clean = cleanActors(files)
            for (m in merges) {
                assertTrue(m.actor in clean && m.actor !in blocked, "seed $seed: merged an actor with a problem")
                assertTrue(m.inputs.size >= 2, "seed $seed: a merge of one file")
                var ops = 0L
                var bytes = 0L
                for ((n, i) in m.inputs.withIndex()) {
                    assertTrue(used.add(i), "seed $seed: file $i is in two merges")
                    val f = files[i]
                    assertEquals(m.actor, f.actor)
                    assertEquals(current, f.signer, "seed $seed: merged a file of another signer")
                    assertTrue(!f.named, "seed $seed: merged a named file")
                    if (n > 0) assertEquals(files[m.inputs[n - 1]].last + 1, f.first, "seed $seed: inputs are not contiguous")
                    ops += f.ops
                    bytes += f.bytes
                }
                assertEquals(files[m.inputs.first()].first, m.first)
                assertEquals(files[m.inputs.last()].last, m.last)
                assertTrue(ops <= SEGMENT_MAX_OPS && bytes <= SEGMENT_MAX_BYTES, "seed $seed: a merge over the format limits")
            }
        }
    }

    @Test
    fun applyingPlansKeepsCoverageAndConverges() {
        for (seed in 1..400) {
            val random = Random(1000 + seed)
            var files = layout(random, allowOverlap = false)
            val before = coverage(files)
            val current = signers[random.nextInt(signers.size)]
            var rounds = 0
            while (true) {
                val merges = SegmentCompaction.plan(files, current)
                if (merges.isEmpty()) break
                assertTrue(++rounds <= 6, "seed $seed: still merging after $rounds rounds")
                val gone = merges.flatMap { it.inputs }.toSet()
                files = files.filterIndexed { i, _ -> i !in gone } +
                    merges.map { m -> SegmentInfo(m.actor, m.first, m.last, m.inputs.sumOf { files[it].bytes }, current) }
            }
            assertEquals(before, coverage(files), "seed $seed: coverage changed")
        }
    }

    @Test
    fun subsumptionKeepsCoverageAndOnlyDropsContainedFiles() {
        for (seed in 1..400) {
            val random = Random(5000 + seed)
            val files = layout(random, allowOverlap = true)
            val found = SegmentCompaction.subsumed(files)
            val dropped = found.map { it.index }.toSet()
            assertEquals(dropped.size, found.size, "seed $seed: a file listed twice")
            for (s in found) {
                val file = files[s.index]
                val by = files[s.by]
                assertEquals(file.actor, by.actor)
                assertTrue(by.first <= file.first && file.last <= by.last && (by.first != file.first || by.last != file.last), "seed $seed: not contained")
                assertTrue(s.by !in dropped, "seed $seed: covered by a file that is dropped itself")
                assertTrue(!file.named && file.ok)
            }
            assertEquals(coverage(files), coverage(files.filterIndexed { i, _ -> i !in dropped }), "seed $seed: coverage changed")
        }
    }

    @Test
    fun aLibraryOfTwoHundredThousandOpsPlansToFewFiles() {
        // 100,000 two-op flushes by one device with one key
        var files = List(100_000) { SegmentInfo("a", it * 2L + 1, it * 2L + 2, 700, "k") }
        var rounds = 0
        while (true) {
            val merges = SegmentCompaction.plan(files, "k")
            if (merges.isEmpty()) break
            assertTrue(++rounds <= 4)
            val gone = merges.flatMap { it.inputs }.toSet()
            val old = files
            files = old.filterIndexed { i, _ -> i !in gone } + merges.map { m -> SegmentInfo(m.actor, m.first, m.last, m.inputs.sumOf { old[it].bytes }, "k") }
            files = files.sortedBy { it.first }
        }
        assertTrue(files.size < 60, "${files.size} files")
        assertEquals(200_000L, files.sumOf { it.ops })
    }
}
