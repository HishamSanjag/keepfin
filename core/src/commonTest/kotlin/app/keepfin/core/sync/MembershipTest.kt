package app.keepfin.core.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class MembershipTest {
    private fun reason(a: Admission): String? = (a as? Admission.SetAsideBy)?.reason

    private fun keys(log: OpLog): Set<String> = log.state.profile.keys

    private fun stateText(log: OpLog): String {
        val sink = ByteSink()
        StateWriter.write(sink, log.liveState)
        return sink.buf.copyOf(sink.size).decodeToString()
    }

    @Test
    fun aLibraryWithoutGenesisBehavesAsToday() {
        val w = MemberWorld(1)
        val a = w.members[0]
        val b = w.members[1]
        val plain = OpLog()
        val withMemberOps = OpLog()
        val ops = listOf(w.profile(a, "x1"), w.profile(b, "x2"), w.profile(a, "x3"))
        for ((i, op) in ops.withIndex()) {
            val signer = if (i == 1) b.id else null
            assertTrue(plain.admit(op, signer) is Admission.Admitted)
            assertTrue(withMemberOps.admit(op, signer) is Admission.Admitted)
        }
        // member ops without a genesis are held and change nothing
        assertTrue(withMemberOps.admit(w.grant(b, a, listOf("admin")), b.id) is Admission.Admitted)
        assertTrue(withMemberOps.admit(w.revoke(b, a, 0), null) is Admission.Admitted)
        assertTrue(withMemberOps.admit(w.profile(a, "x4"), null) is Admission.Admitted)
        assertFalse(withMemberOps.membership.active)
        assertEquals(setOf("x1", "x2", "x3", "x4"), keys(withMemberOps))
        assertEquals(setOf("x1", "x2", "x3"), keys(plain))
    }

    private fun assertFalse(b: Boolean) = assertTrue(!b)

    @Test
    fun genesisFreezesTheUnsignedHistory() {
        val w = MemberWorld(2)
        val (a, b, c) = w.members
        val log = OpLog()
        assertTrue(log.admit(w.profile(a, "a1"), null) is Admission.Admitted)
        assertTrue(log.admit(w.profile(a, "a2"), null) is Admission.Admitted)
        assertTrue(log.admit(w.profile(b, "b0"), null) is Admission.Admitted) // history of B, not frozen
        assertTrue(log.admit(w.genesis(a, mapOf(a to 2L)), a.id) is Admission.Admitted)
        assertTrue(log.membership.active)
        // the history of A stays; B's unsigned history is not frozen, so it is set aside (kept)
        assertEquals(setOf("a1", "a2"), keys(log))
        assertEquals("unsigned", reason(log.admit(w.profile(a, "a3"), null)))
        assertTrue(log.admit(w.profile(a, "a4"), a.id) is Admission.Admitted)
        assertEquals("unauthorized", reason(log.admit(w.profile(b, "b1"), b.id)))
        assertEquals("unsigned", reason(log.admit(w.profile(b, "b2"), null)))
        assertEquals("unauthorized", reason(log.admit(w.profile(c, "c1"), c.id)))
        assertEquals(setOf("a1", "a2", "a4"), keys(log))
        // a grant by an admin lets B in: its signed op held earlier now counts, the unsigned ones never do
        assertTrue(log.admit(w.grant(a, b, listOf("write")), a.id) is Admission.Admitted)
        assertEquals(setOf("a1", "a2", "a4", "b1"), keys(log))
        assertEquals("unauthorized", reason(log.admit(w.profile(b, "b3"), c.id)), "a key the actor does not hold")
        // a grant by a member without admin is held and ignored
        val ignored = w.grant(b, c, listOf("write"))
        assertEquals("unauthorized", reason(log.admit(ignored, b.id)))
        assertEquals("unauthorized", reason(log.admit(w.profile(c, "c2"), c.id)))
        assertEquals(setOf(a.actor, b.actor), log.membership.members().keys)
        // nothing set aside is lost: every op is held and counted in the version vector
        assertEquals(mapOf(a.actor to 6L, b.actor to 5L, c.actor to 2L), log.versionVector)
    }

    @Test
    fun revocationGoesBySequence() {
        for (revokeFirst in listOf(false, true)) {
            val w = MemberWorld(3)
            val (a, b) = w.members
            val deliveries = ArrayList<Delivery>()
            deliveries.add(w.d(w.genesis(a, emptyMap()), a.id))
            deliveries.add(w.d(w.grant(a, b, listOf("write")), a.id))
            val writes = (1..5).map { w.d(w.profile(b, "b$it"), b.id) }
            val revoke = w.d(w.revoke(a, b, 3), a.id)
            val later = w.d(w.profile(b, "b6"), b.id)
            val log = OpLog()
            for (d in deliveries) log.admit(d.op, d.signer)
            if (revokeFirst) log.admit(revoke.op, revoke.signer)
            val codes = writes.map { log.admit(it.op, it.signer) }
            if (!revokeFirst) log.admit(revoke.op, revoke.signer)
            if (revokeFirst) {
                assertEquals(listOf(null, null, null, "revoked", "revoked"), codes.map { reason(it) })
            }
            assertEquals("revoked", reason(log.admit(later.op, later.signer)))
            assertEquals(setOf("b1", "b2", "b3"), keys(log), "revokeFirst=$revokeFirst")
            assertEquals(3L, log.membership.cutoff(b.actor))
            assertEquals(3L, log.checkpoint("00000000-0000-0000-0000-0000000000aa").coverage[b.actor], "a checkpoint stops at the cutoff")
            assertEquals(6L, log.versionVector[b.actor], "the revoked ops are kept and counted")
        }
    }

    @Test
    fun concurrentRevocationsUnionAndNonAdminsAreIgnored() {
        val w = MemberWorld(4, 5)
        val (a, b, c, d, e) = w.members
        val setup = listOf(
            w.d(w.genesis(a, emptyMap()), a.id), w.d(w.grant(a, b, listOf("admin")), a.id), w.d(w.grant(a, c, listOf("write")), a.id),
            w.d(w.grant(a, d, listOf("write")), a.id), w.d(w.grant(a, e, listOf("write")), a.id),
        )
        val ra = w.d(w.revoke(a, c, 5), a.id)
        val rb = w.d(w.revoke(b, c, 3), b.id)
        val byD = w.d(w.revoke(d, a, 0), d.id) // D has no admin: ignored
        for (order in listOf(listOf(ra, rb, byD), listOf(rb, ra, byD), listOf(byD, rb, ra))) {
            val log = OpLog()
            for (x in setup + order) log.admit(x.op, x.signer)
            assertEquals(3L, log.membership.cutoff(c.actor), "the lowest cutoff wins")
            assertNull(log.membership.cutoff(a.actor))
            assertTrue(log.admit(w.profile(a, "still-a"), a.id) is Admission.Admitted)
        }
    }

    @Test
    fun theFirstOfTwoMutualRevocationsWins() {
        val w = MemberWorld(5)
        val (a, b) = w.members
        val setup = listOf(w.d(w.genesis(a, emptyMap()), a.id), w.d(w.grant(a, b, listOf("admin")), a.id))
        val rb = w.d(w.revoke(b, a, 2), b.id) // earlier clock
        val ra = w.d(w.revoke(a, b, 0), a.id) // by A at seq 3, above the cutoff of 2 once B''s revocation stands
        for (order in listOf(listOf(rb, ra), listOf(ra, rb))) {
            val log = OpLog()
            for (x in setup + order) log.admit(x.op, x.signer)
            assertEquals(2L, log.membership.cutoff(a.actor))
            assertNull(log.membership.cutoff(b.actor))
        }
    }

    @Test
    fun aKeyRotationSealsTheOldKey() {
        val w = MemberWorld(6)
        val (a, b) = w.members
        val oldId = b.id
        val newKey = app.keepfin.core.net.SoftwareSigner.generate()
        val newId = app.keepfin.core.net.deviceIdOf(newKey.spki)
        val deliveries = listOf(
            w.d(w.genesis(a, emptyMap()), a.id), w.d(w.grant(a, b, listOf("write")), a.id),
            w.d(w.profile(b, "b1"), oldId), w.d(w.profile(b, "b2"), oldId),
            w.d(w.grant(b, b, listOf("write"), newKey), oldId), // seq 3: the rotation, signed by the old key
            w.d(w.profile(b, "b4"), newId), w.d(w.profile(b, "b5"), oldId),
        )
        val rnd = Random(9)
        repeat(12) {
            val log = OpLog()
            for (x in if (it == 0) deliveries else deliveries.shuffled(rnd)) log.admit(x.op, x.signer)
            assertEquals(setOf("b1", "b2", "b4"), keys(log), "run $it")
            assertEquals(2, log.membership.members().getValue(b.actor).keys.size)
        }
    }

    @Test
    fun anEarlierGenesisReplacesALaterOneAndAnUnsignedGenesisIsIgnored() {
        val w = MemberWorld(7)
        val (a, b) = w.members
        val g1 = w.d(w.genesis(a, emptyMap()), a.id)
        val g2 = w.d(w.genesis(b, emptyMap()), b.id)
        val unsigned = w.d(w.genesis(b, emptyMap()), null)
        val wrongKey = w.d(w.genesis(b, emptyMap()), a.id) // signed by a key the genesis does not carry
        for (order in listOf(listOf(g1, g2), listOf(g2, g1))) {
            val log = OpLog()
            for (x in listOf(unsigned, wrongKey) + order) log.admit(x.op, x.signer)
            assertEquals(setOf(a.actor), log.membership.members().keys, "the lowest clock wins whatever the order")
        }
    }

    @Test
    fun theMembershipDoesNotDependOnTheOrderOfDelivery() {
        for (seed in 1..40) {
            val w = MemberWorld(seed)
            val deliveries = w.generate(60)
            val reference = OpLog()
            for (d in deliveries) reference.admit(d.op, d.signer)
            val want = stateText(reference)
            val wantMembers = membershipJson(reference.membership)
            val rnd = Random(seed)
            repeat(6) { run ->
                val log = OpLog()
                for (d in deliveries.shuffled(rnd)) log.admit(d.op, d.signer)
                assertEquals(wantMembers, membershipJson(log.membership), "seed $seed run $run: membership")
                assertTrue(want == stateText(log), "seed $seed run $run: state")
                assertEquals(reference.versionVector, log.versionVector, "seed $seed run $run: vector")
            }
        }
    }

    @Test
    fun aLogRestartedFromACheckpointKnowsTheMembersItIsGiven() {
        val w = MemberWorld(8)
        val (a, b) = w.members
        val deliveries = listOf(
            w.d(w.genesis(a, emptyMap()), a.id), w.d(w.grant(a, b, listOf("write")), a.id),
            w.d(w.profile(b, "b1"), b.id), w.d(w.profile(b, "b2"), b.id), w.d(w.revoke(a, b, 1), a.id),
        )
        val full = OpLog()
        for (x in deliveries) full.admit(x.op, x.signer)
        val cp = full.checkpoint("00000000-0000-0000-0000-0000000000aa")
        val records = full.membership.records()
        val restarted = OpLog.fromCheckpoint(cp, emptyMap(), records) ?: fail("checkpoint refused")
        assertEquals(membershipJson(full.membership), membershipJson(restarted.membership))
        // later ops of B are refused after the restart
        assertEquals("revoked", reason(restarted.admit(w.profile(b, "b9").let { Op(OpId(b.actor, 6), it.hlc, it.kind, it.body) }, b.id)))
        assertNotNull(restarted.membership.cutoff(b.actor))
    }
}
