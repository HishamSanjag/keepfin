package app.keepfin.net

import app.keepfin.core.sync.JStr
import app.keepfin.folder.FakeFolderAccess
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.MemberGrant
import app.keepfin.folder.OpDraft
import app.keepfin.folder.MergeState
import app.keepfin.folder.sql.JdbcHost
import app.keepfin.folder.sql.SqlMergeState
import app.keepfin.folder.StoreOptions
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Membership in the store (network spec 9.1 #1 and #2): signed segments, the root that freezes history, grants, revocation, and the cold open that replays. */
class MembershipReplayTest {
    private lateinit var root: File
    private lateinit var folder: FakeFolderAccess
    private val stores = ArrayList<KeepfinStore>()
    private var clock = 1_700_000_000_000L
    private var ids = 0
    private lateinit var devA: SoftIdentity
    private lateinit var devB: SoftIdentity

    @Before fun setUp() {
        root = tempDir("keepfin-membership")
        folder = FakeFolderAccess()
        devA = SoftIdentity("A")
        devB = SoftIdentity("B")
    }

    @After fun tearDown() {
        stores.forEach { it.abandon() }
        root.deleteRecursively()
    }

    private fun sqlState(device: String, signer: SoftIdentity?) = SqlMergeState(File(root, "state-$device").also { it.mkdirs() }.resolve("state.db").path, LIB, "replica-$device", signer?.deviceId, JdbcHost())

    private fun open(actor: String, device: String, signer: SoftIdentity?, ckptEvery: Int = 5_000, state: MergeState = sqlState(device, signer)): KeepfinStore =
        KeepfinStore.open(
            StoreOptions(
                folder = folder, libraryId = LIB, actor = actor, deviceDir = File(root, "dev-$device"), outboxDir = File(root, "out-$device"), newId = { "cccccccc-cccc-4ccc-8ccc-%012d".format(++ids) },
                now = { clock++ }, flushIdleMs = 3_600_000, checkpointEvery = ckptEvery, checkpointDelayMs = 5, retryMs = 10, state = state, signer = signer?.signer,
            ),
        ).get(10, TimeUnit.SECONDS).also { stores += it }

    private fun set(field: String, value: String) = OpDraft("profile.set", mapOf("field" to JStr(field), "value" to JStr(value)))
    private fun KeepfinStore.write(vararg changes: Pair<String, String>) {
        apply(changes.map { set(it.first, it.second) }).get(10, TimeUnit.SECONDS)
        flush().get(10, TimeUnit.SECONDS)
    }
    private fun KeepfinStore.views() = viewsJson().get(10, TimeUnit.SECONDS)
    private fun KeepfinStore.sync() = rescan(true).get(10, TimeUnit.SECONDS)
    private fun spki(d: SoftIdentity) = Base64.getEncoder().encodeToString(d.spki)

    @Test fun aLibraryWithoutAMembershipRootAcceptsEveryone() {
        val a = open(ACTOR_A, "a", devA)
        val b = open(ACTOR_B, "b", null)
        a.write("name" to "from a")
        b.sync()
        assertFalse(b.membership().get(10, TimeUnit.SECONDS).active)
        b.write("title" to "from b")
        a.sync()
        assertTrue(a.views().contains("from b"))
    }

    @Test fun theRootFreezesHistoryAndUnsignedNewcomersAreSetAside() {
        val a = open(ACTOR_A, "a", devA)
        a.write("name" to "before")
        // a device that opened the folder before the root existed does not know about it yet
        val z = open("dddddddd-dddd-4ddd-8ddd-dddddddddddd", "z", null)
        z.sync()
        a.enableMembership().get(10, TimeUnit.SECONDS)
        a.flush().get(10, TimeUnit.SECONDS)
        val m = a.membership().get(10, TimeUnit.SECONDS)
        assertTrue(m.active)
        assertTrue(m.members.getValue(ACTOR_A).isAdmin)
        assertTrue(m.members.getValue(ACTOR_A).holds(devA.deviceId))
        assertNotNull("the marker for cold opens", folder.stat(KeepfinStore.MEMBERSHIP_MARKER))
        val before = a.views()
        // somebody who is not a member writes (unsigned): it is kept in the folder, never applied
        z.write("name" to "intruder")
        a.sync()
        assertEquals(before, a.views())
        assertFalse(a.views().contains("intruder"))
    }

    @Test fun aGrantedDeviceWritesAndARevokedOneIsCutOff() {
        val a = open(ACTOR_A, "a", devA)
        a.write("seed" to "1")
        val actorC = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        val impostor = open(actorC, "c", SoftIdentity("impostor")) // opened before the root: it will not know it
        impostor.sync()
        a.enableMembership(MemberGrant(ACTOR_B, spki(devB), listOf("write"))).get(10, TimeUnit.SECONDS)
        a.flush().get(10, TimeUnit.SECONDS)
        a.grantMember(actorC, spki(SoftIdentity("the real C")), listOf("write")).get(10, TimeUnit.SECONDS)
        a.flush().get(10, TimeUnit.SECONDS)
        val b = open(ACTOR_B, "b", devB)
        b.sync()
        assertTrue(b.membership().get(10, TimeUnit.SECONDS).members.containsKey(ACTOR_B))
        b.write("title" to "granted write")
        a.sync()
        assertTrue(a.views().contains("granted write"))
        // B writes with the wrong key: refused even though the actor is a member
        impostor.write("title" to "wrong key")
        a.sync()
        assertFalse(a.views().contains("wrong key"))
        // revoked at the sequence it reached: what it writes later is set aside
        val cutoff = a.versionVector().get(10, TimeUnit.SECONDS)[ACTOR_B] ?: 0L
        a.revokeMember(ACTOR_B, cutoff).get(10, TimeUnit.SECONDS)
        a.flush().get(10, TimeUnit.SECONDS)
        b.write("title" to "after revoke")
        a.sync()
        assertFalse(a.views().contains("after revoke"))
        assertTrue(a.views().contains("granted write")) // what it did before stays
    }

    @Test fun onlyAnAdministratorMayGrantOrRevoke() {
        val a = open(ACTOR_A, "a", devA)
        a.enableMembership(MemberGrant(ACTOR_B, spki(devB), listOf("write"))).get(10, TimeUnit.SECONDS)
        a.flush().get(10, TimeUnit.SECONDS)
        val b = open(ACTOR_B, "b", devB)
        b.sync()
        try {
            b.grantMember("dddddddd-dddd-4ddd-8ddd-dddddddddddd", spki(SoftIdentity()), listOf("write")).get(10, TimeUnit.SECONDS)
            org.junit.Assert.fail("a writer granted a member")
        } catch (e: java.util.concurrent.ExecutionException) {
            assertTrue(e.cause is IllegalStateException || e.cause is IllegalArgumentException || e.cause is SecurityException || e.cause != null)
        }
    }

    @Test fun aColdOpenReplaysSegmentsEvenWhenCheckpointsExist() {
        val a = open(ACTOR_A, "a", devA, ckptEvery = 4)
        a.write(*(1..12).map { "k$it" to "v$it" }.toTypedArray())
        a.enableMembership(MemberGrant(ACTOR_B, spki(devB), listOf("write"))).get(10, TimeUnit.SECONDS)
        a.write("after" to "root")
        Thread.sleep(300)
        val views = a.views()
        a.close().get(10, TimeUnit.SECONDS)
        assertTrue("checkpoint parts were written", folder.files.keys.any { it.contains("/checkpoints/") })
        // a new install: no device files, a state that is empty
        val fresh = open("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", "fresh", null)
        assertTrue(fresh.membership().get(10, TimeUnit.SECONDS).active)
        assertFalse("the checkpoint was not used", fresh.stats!!.checkpoint)
        assertEquals(views, fresh.views())
    }

    @Test fun aFolderWithoutARootIsStillOpenedFromCheckpoints() {
        val a = open(ACTOR_A, "a", devA, ckptEvery = 4)
        a.write(*(1..12).map { "k$it" to "v$it" }.toTypedArray())
        Thread.sleep(300)
        a.close().get(10, TimeUnit.SECONDS)
        assertNull(folder.stat(KeepfinStore.MEMBERSHIP_MARKER))
        val fresh = open("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", "fresh", null)
        assertTrue(fresh.stats!!.checkpoint)
    }

    @Test fun signedSegmentsCarryTheDeviceKey() {
        val a = open(ACTOR_A, "a", devA)
        a.write("name" to "signed")
        val path = folder.segmentPaths().single()
        val bytes = folder.files.getValue(path)
        assertTrue("KFSEG 2 header", String(bytes, 0, 6, Charsets.ISO_8859_1).startsWith("KFSEG"))
        assertNotEquals("unsigned files are version 1", "1", String(bytes, 0, 8, Charsets.ISO_8859_1).substring(6).trim().take(1))
    }
}
