package app.keepfin.core.sqlstate

import app.keepfin.core.sync.Admission
import app.keepfin.core.sync.ByteSink
import app.keepfin.core.sync.MemberWorld
import app.keepfin.core.sync.OpLog
import app.keepfin.core.sync.SHARD_IDS
import app.keepfin.core.sync.StateWriter
import app.keepfin.core.sync.membershipJson
import app.keepfin.core.sync.sha256Hex
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Membership in the SQL admission against the in-memory OpLog (network spec 9.1 #1 and #2), on every target: the same
 * admission codes (including `setAside:<reason>`), the same membership, the same state after the host's rebuild, for random
 * histories with genesis, grants, rotations, revocations, unsigned and mis-signed ops, in generated and shuffled orders.
 */
class MemberSqlTest {
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

    private fun stateBytes(write: (ByteSink) -> Unit): String {
        val sink = ByteSink()
        write(sink)
        return sink.buf.copyOf(sink.size).decodeToString()
    }

    private fun open(t: TestSql, name: String) = SqlStateDb(t.path(name), t.host, SqlStateOptions(mmapMb = 0)) { sha256Hex(it) }

    private fun assertSame(log: OpLog, db: SqlStateDb, label: String) {
        assertEquals(log.versionVector, db.versionVector(), "$label: version vector")
        assertEquals(log.pending, db.pending().mapValues { it.value.toSet() }, "$label: pending")
        assertEquals(log.allFindings().sorted(), db.findings(), "$label: findings")
        assertEquals(membershipJson(log.membership), membershipJson(db.membership), "$label: membership")
        val sql = stateBytes { StateWriter.write(it, SqlState.stateFromRows(db.readRowsJson())) }
        val mem = stateBytes { StateWriter.write(it, log.state) }
        assertTrue(sql == mem, "$label: state bytes differ")
    }

    @Test
    fun admissionAndStateEqualTheOpLog() {
        val t = TestSql()
        try {
            val seen = HashSet<String>()
            var rebuilds = 0
            for (seed in 1..25) {
                val deliveries = MemberWorld(seed).generate(70)
                for (variant in 0..1) {
                    val order = if (variant == 0) deliveries else deliveries.shuffled(Random(seed))
                    val log = OpLog()
                    val db = open(t, "m$seed-$variant.db")
                    for ((i, d) in order.withIndex()) {
                        val text = d.op.encode()
                        val want = code(log.admit(text, d.signer))
                        assertEquals(want, db.admit(listOf(text), d.signer)[0], "seed $seed/$variant op $i")
                        seen.add(want)
                        // the host rebuilds before the next read, so a rebuild that is needed but not asked for shows up now
                        if (db.rebuildRequired) {
                            rebuilds++
                            db.rebuild(order.subList(0, i + 1).map { it.op.encode() }.iterator(), order.subList(0, i + 1).map { it.signer }.iterator())
                            if (i % 7 == 0) assertSame(log, db, "seed $seed/$variant after op $i")
                        }
                    }
                    assertSame(log, db, "seed $seed/$variant")
                    db.close()
                }
            }
            for (c in listOf("admitted", "setAside:unsigned", "setAside:unauthorized", "setAside:revoked")) assertTrue(c in seen, "no $c in the histories: $seen")
            assertTrue(rebuilds > 5, "only $rebuilds rebuilds were exercised")
        } finally {
            t.cleanup()
        }
    }

    @Test
    fun theMembershipSurvivesAReopenAndABulkLoad() {
        val t = TestSql()
        try {
            val deliveries = MemberWorld(77).generate(80)
            val log = OpLog()
            var db = open(t, "k.db")
            for (d in deliveries) {
                log.admit(d.op, d.signer)
                db.admit(listOf(d.op.encode()), d.signer)
            }
            if (db.rebuildRequired) db.rebuild(deliveries.map { it.op.encode() }.iterator(), deliveries.map { it.signer }.iterator())
            val want = membershipJson(log.membership)
            assertTrue(log.membership.active)
            assertEquals(want, membershipJson(db.membership))
            db.close()
            db = open(t, "k.db") // reopened: the members table is read back
            assertEquals(want, membershipJson(db.membership), "after a reopen")
            val files = SHARD_IDS.map { assertNotNull(log.partBytes(library, it), "part $it") }
            assertTrue(db.bulkLoad(files, library).ok)
            assertEquals(want, membershipJson(db.membership), "after a bulk load")
            db.close()
        } finally {
            t.cleanup()
        }
    }
}
