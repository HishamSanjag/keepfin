package app.keepfin.core.sqlstate

import app.keepfin.core.sync.Admission
import app.keepfin.core.sync.ByteSink
import app.keepfin.core.sync.Hlc
import app.keepfin.core.sync.JNum
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.Op
import app.keepfin.core.sync.OpGen
import app.keepfin.core.sync.OpLog
import app.keepfin.core.sync.PartResult
import app.keepfin.core.sync.SHARD_IDS
import app.keepfin.core.sync.StateWriter
import app.keepfin.core.sync.decodePart
import app.keepfin.core.sync.sha256Hex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SQL-backed admission against the in-memory OpLog (sync spec section 4), on every target: the same admission codes,
 * version vector, pending seqs, set-aside ranges, findings, clock and state bytes for random histories with duplicates,
 * equivocations, invalid, unknown and malformed ops, before and after a bulk load from checkpoint parts. The OpLog is the
 * independent oracle; the SQL side is the shared SqlStateDb over the target's driver.
 */
class SqlStateDbTest {
    private val library = "00000000-0000-0000-0000-0000000000aa"

    private fun code(a: Admission): String = when (a) {
        Admission.Admitted -> "admitted"
        Admission.Duplicate -> "duplicate"
        Admission.Kept -> "kept"
        is Admission.Invalid -> "invalid:${a.reason}"
        Admission.Equivocation -> "equivocation"
        Admission.SetAside -> "setAside"
        is Admission.SetAsideBy -> "setAside:${a.reason}"
        Admission.Covered -> "covered"
        is Admission.Malformed -> "malformed:${a.problem.name}"
    }

    /** The same op id with other bytes (a later clock): an equivocation. */
    private fun variantOf(op: Op): String = Op(op.id, Hlc(op.hlc.wall, op.hlc.counter + 7, op.hlc.actor), op.kind, op.body).encode()

    /** Lines the log must judge invalid, kept or malformed. */
    private fun badLines(actor: String, other: String, base: Int, sample: String): List<String> {
        fun hlc(a: String) = "1700000000000.000000.$a"
        return listOf(
            "{\"body\":{\"item\":5},\"hlc\":\"${hlc(actor)}\",\"id\":\"$actor:$base\",\"kind\":\"item.set\"}",
            "{\"body\":{\"field\":\"n\",\"value\":1},\"hlc\":\"${hlc(other)}\",\"id\":\"$actor:${base + 1}\",\"kind\":\"profile.set\"}",
            "{\"body\":{},\"hlc\":\"${hlc(actor)}\",\"id\":\"$actor:${base + 2}\",\"kind\":\"future.thing\"}",
            "not json at all",
            sample.replace("{\"body\":", "{ \"body\":"),
            "{\"body\":{}}",
        )
    }

    private fun <T> shuffled(list: List<T>, rnd: Random): List<T> = list.shuffled(rnd)

    private fun <T> batches(list: List<T>, rnd: Random): List<List<T>> {
        val out = ArrayList<List<T>>()
        var i = 0
        while (i < list.size) {
            val n = 1 + rnd.nextInt(25)
            out.add(list.subList(i, minOf(list.size, i + n)))
            i += n
        }
        return out
    }

    private fun stateBytes(write: (ByteSink) -> Unit): String {
        val sink = ByteSink()
        write(sink)
        return sink.buf.copyOf(sink.size).decodeToString()
    }

    private fun assertSame(log: OpLog, db: SqlStateDb, all: List<String>, label: String) {
        if (db.rebuildRequired) db.rebuild(all.iterator())
        assertEquals(log.versionVector, db.versionVector(), "$label: version vector")
        assertEquals(log.pending, db.pending().mapValues { it.value.toSet() }, "$label: pending")
        assertEquals(log.setAside, db.setAside(), "$label: set-aside")
        assertEquals(log.allFindings().sorted(), db.findings(), "$label: findings")
        assertEquals(log.maxHlc?.wire(), db.maxHlc(), "$label: max clock")
        val sql = stateBytes { StateWriter.write(it, SqlState.stateFromRows(db.readRowsJson())) }
        val mem = stateBytes { StateWriter.write(it, log.state) }
        assertTrue(sql == mem, "$label: state bytes differ")
    }

    private class Delivery(val ops: List<String>, val all: List<String>)

    private fun delivery(seed: Int, rnd: Random, count: Int): Delivery {
        val gen = OpGen(seed, 3)
        val ops = gen.generate(count)
        val texts = ops.map { it.encode() }
        val actors = ops.map { it.id.actor }.distinct()
        val variants = ops.filter { rnd.nextInt(10) == 0 }.map { variantOf(it) }
        val bad = badLines(actors[0], actors.getOrElse(1) { actors[0] }, 5000, texts[0])
        val all = texts + variants + bad
        val order = shuffled(texts + texts.filter { rnd.nextInt(5) == 0 } + variants + bad, rnd)
        return Delivery(order, all)
    }

    private fun open(t: TestSql, name: String) = SqlStateDb(t.path(name), t.host, SqlStateOptions(mmapMb = 0)) { sha256Hex(it) }

    @Test
    fun admissionEqualsTheOpLogEquivocationsIncluded() {
        val t = TestSql()
        try {
            var equivocations = 0
            val seen = HashSet<String>()
            for (seed in 1..20) {
                val rnd = Random(seed * 977)
                val d = delivery(seed, rnd, 70 + (seed % 5) * 30)
                val log = OpLog()
                val db = open(t, "a$seed.db")
                val expected = d.ops.map { code(log.admit(it)) }
                val got = ArrayList<String>()
                for (batch in batches(d.ops, rnd)) got.addAll(db.admit(batch))
                assertEquals(expected, got, "seed $seed: admission codes")
                for (c in expected) seen.add(c.substringBefore(':'))
                equivocations += expected.count { it == "equivocation" }
                assertSame(log, db, d.all, "seed $seed")
                db.close()
            }
            assertTrue(equivocations > 10, "only $equivocations equivocations were exercised")
            for (c in listOf("admitted", "duplicate", "kept", "invalid", "equivocation", "setAside", "malformed")) assertTrue(c in seen, "no $c in the histories")
        } finally {
            t.cleanup()
        }
    }

    @Test
    fun admissionAfterABulkLoadFromCheckpointParts() {
        val t = TestSql()
        try {
            var usable = 0
            var discarded = 0
            for (seed in 1..12) {
                val rnd = Random(seed * 1511)
                val ops = OpGen(seed + 500, 3).generate(120 + (seed % 4) * 30)
                val texts = ops.map { it.encode() }
                val cut = 20 + rnd.nextInt(texts.size - 40)
                val early = OpLog()
                for (x in texts.subList(0, cut)) early.admit(x)
                val files = SHARD_IDS.map { assertNotNull(early.partBytes(library, it), "part $it") }
                val parts = files.map { (decodePart(it, library) as PartResult.Ok).part }
                val actors = ops.map { it.id.actor }.distinct()
                val coverage = early.versionVector
                // known equivocations: none, one above every coverage (usable), one at the coverage (the parts are unusable)
                val above = mapOf(actors[0] to (coverage[actors[0]] ?: 0L) + 1 + rnd.nextInt(3))
                val below = mapOf(actors[0] to maxOf(1L, coverage[actors[0]] ?: 1L))
                for ((n, known) in listOf(emptyMap(), above, below).withIndex()) {
                    val log = OpLog.fromParts(parts, library, known)
                    val db = open(t, "b$seed-$n.db")
                    val loaded = db.bulkLoad(files, library, known)
                    assertEquals(log != null, loaded.ok, "seed $seed: the loader and the log disagree on whether the parts are usable")
                    if (log == null) {
                        discarded++
                        db.close()
                        continue
                    }
                    usable++
                    val variants = ops.filter { rnd.nextInt(10) == 0 }.map { variantOf(it) }
                    val order = shuffled(texts + texts.filter { rnd.nextInt(5) == 0 } + variants, rnd)
                    // a variant below the coverage would make a real checkpoint unusable, so segments never hold one
                    fun covered(v: String): Boolean {
                        val id = v.substringAfter("\"id\":\"").substringBefore('"')
                        return id.substringAfter(':').toLong() <= (coverage[id.substringBefore(':')] ?: 0L)
                    }
                    val all = texts + variants.filter { !covered(it) }
                    val expected = order.map { code(log.admit(it)) }
                    val got = ArrayList<String>()
                    for (batch in batches(order, rnd)) got.addAll(db.admit(batch))
                    assertEquals(expected, got, "seed $seed (known $known): admission codes after the load")
                    assertTrue("covered" in expected, "ops below the coverage must be covered")
                    assertSame(log, db, all, "seed $seed after a load, known $known")
                    db.close()
                }
            }
            assertTrue(usable > 12 && discarded > 2, "usable $usable, discarded $discarded")
        } finally {
            t.cleanup()
        }
    }

    @Test
    fun aRebuildAfterAnEquivocationRemovesTheSetAsideOpsAndKeepsTheRest() {
        val t = TestSql()
        try {
            val actor = "a1000000-0000-4000-8000-000000000001"
            val item = "b1000000-0000-4000-8000-000000000001"
            fun mk(seq: Long, kind: String, body: Map<String, app.keepfin.core.sync.Jv>, counter: Int = seq.toInt()) =
                Op(app.keepfin.core.sync.OpId(actor, seq), Hlc(1700000000000L, counter, actor), kind, body).encode()
            val ops = listOf(
                mk(1, "item.add", mapOf("item" to JStr(item))),
                mk(2, "item.set", mapOf("item" to JStr(item), "field" to JStr("title"), "value" to JStr("one"))),
                mk(3, "item.set", mapOf("item" to JStr(item), "field" to JStr("title"), "value" to JStr("two"))),
                mk(4, "item.set", mapOf("item" to JStr(item), "field" to JStr("artist"), "value" to JStr("a"))),
            )
            val variant = mk(3, "item.set", mapOf("item" to JStr(item), "field" to JStr("title"), "value" to JStr("other")), 99)
            val log = OpLog()
            val db = open(t, "r.db")
            for (op in ops + variant) assertEquals(code(log.admit(op)), db.admit(listOf(op))[0])
            assertTrue(db.rebuildRequired)
            // before the rebuild the SQL state still holds ops 3 and 4; after it, as the log does, neither
            db.rebuild((ops + variant).iterator())
            assertEquals(mapOf(actor to 3L), db.setAside())
            assertEquals(stateBytes { StateWriter.write(it, log.state) }, stateBytes { StateWriter.write(it, SqlState.stateFromRows(db.readRowsJson())) })
            // ops from the set-aside range keep being set aside
            assertEquals("setAside", db.admit(listOf(mk(5, "item.set", mapOf("item" to JStr(item), "field" to JStr("artist"), "value" to JStr("late")))))[0])
            db.close()
        } finally {
            t.cleanup()
        }
    }

    @Test
    fun theFirstImportGivesTheCodesAndTheStateOfAdmittingOpByOp() {
        val t = TestSql()
        try {
            var equivocations = 0
            for (seed in 1..20) {
                val rnd = Random(seed * 3571)
                val d = delivery(seed, rnd, 70 + (seed % 5) * 30)
                val log = OpLog()
                val db = open(t, "i$seed.db")
                val expected = d.ops.map { code(log.admit(it)) }
                val got = assertNotNull(db.importFresh(d.ops.iterator()), "seed $seed: the database is empty")
                assertEquals(expected, got, "seed $seed: import codes")
                equivocations += expected.count { it == "equivocation" }
                assertSame(log, db, d.all, "seed $seed after the import")
                // it only ever starts on an empty database
                assertNull(db.importFresh(d.ops.iterator()), "seed $seed: a second import must refuse")
                // and ordinary admission carries on from the imported state
                val more = OpGen(seed + 900, 3).generate(10).map { it.encode() }
                assertEquals(more.map { code(log.admit(it)) }, db.admit(more), "seed $seed: admission after the import")
                assertSame(log, db, d.all + more, "seed $seed after more ops")
                db.close()
            }
            assertTrue(equivocations > 10, "only $equivocations equivocations were exercised")
        } finally {
            t.cleanup()
        }
    }


    @Test
    fun aBatchIsOneTransactionAndAFailingBatchLeavesNothingBehind() {
        val t = TestSql()
        try {
            val ops = OpGen(7, 3).generate(40).map { it.encode() }
            val empty = open(t, "x.db")
            val emptyRows = empty.readRowsJson()
            empty.close()
            // a statement that cannot run (a catalog entry replaced by one on a missing table) fails the whole batch
            val broken = SqlStateDb(t.path("y.db"), t.host, SqlStateOptions(mmapMb = 0, catalogOverride = mapOf(1 to "INSERT INTO no_such_table VALUES (?, ?, ?, ?, ?, ?)"))) { sha256Hex(it) }
            val failed = try {
                broken.admit(ops)
                false
            } catch (e: Throwable) {
                true
            }
            assertTrue(failed, "a broken statement must fail the batch")
            assertEquals(emptyRows, broken.readRowsJson(), "a failed batch leaves nothing behind")
            assertNull(broken.getMeta("maxHlc"), "a failed batch leaves no bookkeeping")
            assertEquals(0, broken.versionVector().size)
            broken.close()
        } finally {
            t.cleanup()
        }
    }

    @Test
    fun hlcAndSequenceValuesStayExactAcrossTheDriver() {
        val t = TestSql()
        try {
            val big = 9007199254740991L - 5
            val db = open(t, "z.db")
            val actor = "a1000000-0000-4000-8000-000000000001"
            val item = "b1000000-0000-4000-8000-000000000001"
            val op = Op(app.keepfin.core.sync.OpId(actor, 1), Hlc(1700000000000L, 5, actor), "item.add", mapOf("item" to JStr(item), "fields" to app.keepfin.core.sync.JObj(mapOf("n" to JNum(big))))).encode()
            assertEquals("admitted", db.admit(listOf(op))[0])
            assertTrue(db.readRowsJson().contains("$big"), "a value near 2^53 must come back exactly")
            assertEquals("1700000000000.000005.$actor", db.maxHlc())
            db.close()
        } finally {
            t.cleanup()
        }
    }
}
