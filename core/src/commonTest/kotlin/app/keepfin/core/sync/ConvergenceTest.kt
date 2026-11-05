package app.keepfin.core.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

const val TEST_LIB = "00000000-0000-0000-0000-0000000000aa"

class ConvergenceTest {
    @Test
    fun semilatticeLawsHoldForRandomStates() {
        for (batch in 0 until 40) {
            val seed = 1000 + batch
            val pool = OpGen(seed, 4).generate(120)
            val rnd = Random(seed * 31)
            fun random(): State = stateOf(pool.filter { rnd.nextInt(100) < rnd.nextInt(30) })
            repeat(50) { n ->
                val a = random()
                val b = random()
                val c = random()
                val tag = "seed=$seed trial=$n"
                assertEquals(a.join(b), b.join(a), "commutative $tag")
                assertEquals(a.join(b.join(c)), a.join(b).join(c), "associative $tag")
                assertEquals(a, a.join(a), "idempotent $tag")
                assertEquals(a, a.join(State.EMPTY), "identity $tag")
            }
        }
    }

    @Test
    fun replicasConvergeUnderAnyOrderDuplicationAndPartition() {
        for (seed in 1..40) {
            val rnd = Random(seed)
            val ops = OpGen(seed, 3 + seed % 3).generate(150)
            val expected = stateOf(ops)
            val expectedViews = expected.views()
            val reference = logOf(ops)
            val referenceBytes = encodeCheckpoint(reference.checkpoint(TEST_LIB))
            assertEquals(expected, reference.state, "reference seed=$seed")
            for (replica in 0 until 3) {
                val tag = "seed=$seed replica=$replica"
                val delivery = ArrayList<Op>(ops.shuffled(rnd))
                repeat(ops.size / 4) { delivery.add(rnd.nextInt(delivery.size + 1), ops[rnd.nextInt(ops.size)]) }
                // partitions: fold random chunks separately, then join the partial states in random order
                val partials = ArrayList<State>()
                var i = 0
                while (i < delivery.size) {
                    val end = minOf(delivery.size, i + 1 + rnd.nextInt(40))
                    partials.add(stateOf(delivery.subList(i, end)))
                    i = end
                }
                val merged = joinAll(partials.shuffled(rnd))
                assertEquals(expected, merged, "partitioned join $tag")
                assertEquals(expectedViews, merged.views(), "views $tag")
                // the log gets the same stream: full state, version vector, findings and checkpoint bytes agree
                val log = logOf(delivery)
                assertEquals(expected, log.state, "log state $tag")
                assertEquals(reference.versionVector, log.versionVector, "version vector $tag")
                assertEquals(reference.findings, log.findings, "findings $tag")
                assertTrue(log.pending.isEmpty(), "no pending left $tag")
                assertContentEquals(referenceBytes, encodeCheckpoint(log.checkpoint(TEST_LIB)), "checkpoint bytes $tag")
            }
        }
    }

    @Test
    fun concurrentPlayCountsAddUp() {
        val a = "a0000000-0000-0000-0000-000000000001"
        val b = "b0000000-0000-0000-0000-000000000002"
        val item = "11111111-1111-1111-1111-111111111111"
        fun plays(actor: String, seq: Long, wall: Long, n: Long) = Op(
            OpId(actor, seq), Hlc(wall, 0, actor), "item.set",
            mapOf("item" to JStr(item), "field" to JStr(PLAYS_PREFIX + actor), "value" to JNum(n)),
        )
        val aOps = listOf(plays(a, 1, 100, 1), plays(a, 2, 101, 2), plays(a, 3, 102, 3))
        val bOps = listOf(plays(b, 1, 100, 1), plays(b, 2, 105, 2))
        val onA = stateOf(aOps)
        val onB = stateOf(bOps)
        assertEquals(3L, onA.plays(item))
        assertEquals(2L, onB.plays(item))
        assertEquals(5L, onA.join(onB).plays(item))
        assertEquals(onA.join(onB), onB.join(onA))
        assertEquals(6L, onA.join(onB).join(stateOf(listOf(plays(b, 3, 200, 3)))).plays(item))
    }

    @Test
    fun entryFactFindingsAreEqualInEveryMergeGrouping() {
        val e = "e1000000-0000-0000-0000-000000000001"
        val a = "a0000000-0000-0000-0000-00000000000a"
        val b = "b0000000-0000-0000-0000-00000000000b"
        val c = "c0000000-0000-0000-0000-00000000000c"
        val pl1 = "33333333-3333-3333-3333-333333333333"
        val pl2 = "44444444-4444-4444-4444-444444444444"
        val item = "11111111-1111-1111-1111-111111111111"
        fun insert(actor: String, playlist: String) = Op(
            OpId(actor, 1), Hlc(1, 0, actor), "entry.insert",
            mapOf("playlist" to JStr(playlist), "entry" to JStr(e), "item" to JStr(item), "key" to JStr("a0")),
        ).toDelta()
        val d = listOf(insert(a, pl1), insert(b, pl2), insert(c, pl1))
        val results = HashSet<State>()
        val perms = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        for (p in perms) {
            val x = d[p[0]]
            val y = d[p[1]]
            val z = d[p[2]]
            results.add(x.join(y).join(z))
            results.add(x.join(y.join(z)))
        }
        assertEquals(1, results.size)
        val only = results.first()
        assertEquals(setOf("entry-conflict:$e"), only.findings)
        assertEquals(OpId(a, 1), only.entries[e]!!.op)
        // agreeing copies (same facts from two ops) are not a conflict
        assertEquals(emptySet(), insert(a, pl1).join(insert(c, pl1)).findings)
    }

    @Test
    fun hlcRules() {
        val t1 = Hlc.tick(null, 100, "a")
        assertEquals(Hlc(100, 0, "a"), t1)
        assertEquals(Hlc(100, 1, "a"), Hlc.tick(t1, 100, "a"))
        assertEquals(Hlc(100, 2, "a"), Hlc.tick(Hlc(100, 1, "a"), 50, "a"))
        assertEquals(Hlc(200, 0, "a"), Hlc.tick(t1, 200, "a"))
        assertEquals(Hlc(300, 0, "a"), Hlc.receive(t1, Hlc(299, 7, "b"), 300, "a"))
        assertEquals(Hlc(300, 5, "a"), Hlc.receive(Hlc(300, 4, "a"), Hlc(300, 2, "b"), 100, "a"))
        assertEquals(Hlc(300, 3, "a"), Hlc.receive(Hlc(100, 9, "a"), Hlc(300, 2, "b"), 100, "a"))
        assertEquals(Hlc(300, 0, "a"), Hlc.receive(null, Hlc(299, 7, "b"), 300, "a"))
        // a receive is strictly after both inputs
        val local = Hlc(500, 3, "a")
        val remote = Hlc(500, 8, "b")
        val after = Hlc.receive(local, remote, 400, "a")
        assertTrue(after > local && after > remote)
        val wire = Hlc(1700000000000, 12, "act").wire()
        assertEquals("1700000000000.000012.act", wire)
        assertEquals(Hlc(1700000000000, 12, "act"), Hlc.parse(wire))
        assertEquals(null, Hlc.parse("170000000000.000012.act"))
        assertEquals(null, Hlc.parse("1700000000000.12.act"))
        assertTrue(Hlc(5, 0, "a") < Hlc(5, 1, "a"))
        assertTrue(Hlc(5, 1, "a") < Hlc(5, 1, "b"))
    }

    @Test
    fun hlcCounterOverflowAdvancesTheWallAndWallOverflowIsATypedError() {
        val full = Hlc(1000, HLC_MAX_COUNTER, "a")
        assertEquals(Hlc(1001, 0, "a"), Hlc.tick(full, 1000, "a"))
        assertEquals(Hlc(1001, 0, "a"), Hlc.receive(full, Hlc(900, 5, "b"), 500, "a"))
        assertEquals(Hlc(HLC_MAX_WALL, 0, "a"), Hlc.tick(null, HLC_MAX_WALL, "a"))
        assertFailsWith<HlcExhausted> { Hlc.tick(Hlc(HLC_MAX_WALL, HLC_MAX_COUNTER, "a"), 5, "a") }
        assertFailsWith<HlcExhausted> { Hlc.tick(null, HLC_MAX_WALL + 1, "a") }
        assertFailsWith<HlcExhausted> { Hlc.receive(null, Hlc(HLC_MAX_WALL, HLC_MAX_COUNTER, "b"), 5, "a") }
        assertEquals("9999999999999.999999.a", Hlc(HLC_MAX_WALL, HLC_MAX_COUNTER, "a").wire())
    }
}
