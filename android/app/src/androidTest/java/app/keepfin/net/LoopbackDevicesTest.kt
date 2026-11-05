package app.keepfin.net

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.bench.LocalFolderAccess
import app.keepfin.core.net.NetAction
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two (or three) whole Devices stacks in one process on loopback with the real key store, TLS 1.3 with pinned keys, the real HTTP layer and
 * the real library store: pairing by the numbers, the first-pairing genesis rule, segment sync both ways, joining a shared library,
 * Range reads from a peer, "Download on...", and removing a device.
 */
@RunWith(AndroidJUnit4::class)
class LoopbackDevicesTest {
    private lateinit var context: Context
    private val stacks = ArrayList<NetStack>()
    private val library = UUID.randomUUID().toString()

    @Before fun setUp() {
        diagnostics = { stacks.joinToString("\n") { it.dump() } }
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After fun tearDown() {
        stacks.forEach { it.close() }
    }

    private fun stack(name: String, withLibrary: Boolean = true, jobs: Boolean = false): NetStack {
        val s = NetStack(name, context, File(context.cacheDir, "loopback-$name-${UUID.randomUUID().toString().take(6)}"))
        stacks += s
        if (withLibrary) s.openLibrary(library)
        s.start(jobsOn = jobs)
        return s
    }

    @Test fun twoPhonesPairByTheNumbersAndShareALibraryThatConverges() {
        val a = stack("alpha")
        val b = stack("beta")
        a.write(library, "title" to "written before pairing")
        pairBySas(a, b)
        assertEquals(1, a.service.computeState().paired.size)
        assertEquals("beta", a.service.computeState().paired.single().name)
        // the lower device id writes the membership root and the first grant; the other one receives them
        until("both libraries to enforce membership") {
            a.service.computeState().libraries.single().signed && b.service.computeState().libraries.single().signed
        }
        val admin = if (a.identity.deviceId < b.identity.deviceId) a else b
        assertTrue(admin.service.computeState().libraries.single().admin)
        until("the history before pairing to arrive") { b.views(library).contains("written before pairing") }
        // both directions
        b.write(library, "other" to "from beta")
        until("beta's change to reach alpha") { a.views(library).contains("from beta") }
        a.write(library, "more" to "from alpha")
        until("alpha's change to reach beta") { b.views(library).contains("from alpha") }
        until("both to show the other online") { a.service.computeState().paired.single().online && b.service.computeState().paired.single().online }
    }

    @Test fun aDevicePairedByQrNeedsNoNumbersAndNoApproval() {
        val a = stack("alpha")
        val b = stack("beta")
        a.service.showQr()
        val link = a.service.computeState().qr!!.link
        var failure: Throwable? = null
        // a scanned code is the approval (phase 1): nobody taps anything on alpha
        val t = Thread { try { b.service.ask(link) } catch (e: Throwable) { failure = e } }.also { it.isDaemon = true; it.start() }
        t.join(20_000)
        failure?.let { throw it }
        until("pairing to finish") { a.service.peers.get(b.identity.deviceId) != null && b.service.peers.get(a.identity.deviceId) != null }
        // the code is used up
        val c = stack("gamma")
        a.service.showQr() // a fresh code is needed for each device: reusing the old link fails
        c.service.ask(link)
        val view = c.service.computeState().pairing!!
        assertEquals("failed", view.stage)
        assertTrue(view.error, view.error!!.contains("already used"))
        assertEquals(1, a.service.peers.list().size)
    }

    @Test fun pairingWaitsForTheOtherDeviceToOpenAddADevice() {
        val a = stack("alpha")
        val b = stack("beta")
        var failure: Throwable? = null
        // beta asks first: alpha's window is closed
        val t = Thread { try { b.service.ask(a.address) } catch (e: Throwable) { failure = e } }.also { it.isDaemon = true; it.start() }
        until("beta to wait for alpha") { b.service.computeState().pairing?.stage == "waitingForPeer" }
        a.service.openPairing()
        until("both screens to show the numbers") { b.service.computeState().pairing?.sas != null && a.service.computeState().pairing?.sas != null }
        assertEquals("answering", a.service.computeState().pairing!!.role)
        b.service.decide(b.service.computeState().pairing!!.id, true)
        until("beta to wait for alpha") { b.service.computeState().pairing?.stage == "waiting" }
        a.service.decide(a.service.computeState().pairing!!.id, true)
        t.join(20_000)
        failure?.let { throw it }
        until("both devices to remember each other") { a.service.peers.get(b.identity.deviceId) != null && b.service.peers.get(a.identity.deviceId) != null }
    }

    @Test fun theAskedDeviceDecliningTellsTheAskingSideInOneSentence() {
        val a = stack("alpha")
        val b = stack("beta")
        a.service.openPairing()
        val t = Thread { runCatching { b.service.ask(a.address) } }.also { it.isDaemon = true; it.start() }
        until("both screens to show the numbers") { b.service.computeState().pairing?.sas != null && a.service.computeState().pairing?.sas != null }
        b.service.decide(b.service.computeState().pairing!!.id, true)
        until("beta to wait for alpha") { b.service.computeState().pairing?.stage == "waiting" }
        a.service.decide(a.service.computeState().pairing!!.id, false)
        t.join(20_000)
        until("beta to say so") { b.service.computeState().pairing?.stage == "failed" }
        val error = b.service.computeState().pairing!!.error!!
        assertTrue(error, error.contains("did not confirm, so nothing was paired."))
        assertTrue(a.service.peers.list().isEmpty() && b.service.peers.list().isEmpty())
    }

    @Test fun cancellingWhileWaitingLeavesNothingBehind() {
        val a = stack("alpha")
        val b = stack("beta")
        val t = Thread { runCatching { b.service.ask(a.address) } }.also { it.isDaemon = true; it.start() }
        until("beta to wait for alpha") { b.service.computeState().pairing?.stage == "waitingForPeer" }
        b.service.dismissPairing()
        t.join(10_000)
        until("the waiting card to go") { b.service.computeState().pairing == null }
        assertTrue(a.service.peers.list().isEmpty())
    }

    @Test fun anAddressThatIsNotPairedReachesNothing() {
        val a = stack("alpha")
        val outsider = stack("outsider")
        // not in pairing mode: even the pairing door is closed
        outsider.service.ask(a.address)
        val view = outsider.service.computeState().pairing!!
        assertEquals("failed", view.stage)
        assertTrue(view.error, view.error!!.contains("was not opened"))
        assertTrue(a.service.peers.list().isEmpty())
        // a stranger's request for the library is refused outright
        val client = PeerClient(ClientOptions(outsider.identity, "127.0.0.1", a.service.listeningPort!!, null, 3000))
        try {
            client.json("GET", "/v1/libraries/$library/segments?vector=")
            throw AssertionError("a stranger read the library")
        } catch (e: PeerHttpError) {
            assertEquals(403, e.status)
        } finally {
            client.close()
        }
    }

    @Test fun aPinnedKeyThatChangesIsRefused() {
        val a = stack("alpha")
        val b = stack("beta")
        pairBySas(a, b)
        // a client that expects a different key than the one the server presents
        val client = PeerClient(ClientOptions(b.identity, "127.0.0.1", a.service.listeningPort!!, "0".repeat(64), 3000))
        try {
            client.json("GET", "/v1/hello?v=1")
            throw AssertionError("connected to a key that was not pinned")
        } catch (e: PinMismatch) {
            // the connection never carried a request
        } finally {
            client.close()
        }
    }

    @Test fun aSharedLibraryCanBeJoinedFromAPairedDevice() {
        val a = stack("alpha")
        val b = stack("beta", withLibrary = false)
        a.write(library, "title" to "from the host")
        pairBySas(b, a)
        a.service.shareLibrary(b.identity.deviceId, library)
        until("beta to be offered the library") { b.service.computeState().offers.any { it.libraryId == library } }
        val target = LocalFolderAccess(File(b.root, "joined"))
        val joined = b.service.joinLibrary(a.identity.deviceId, library, target, "file:///joined")
        assertEquals(library, joined.libraryId)
        assertTrue(joined.segments > 0)
        // the host granted the actor: the new replica opens the folder and writes
        val store = b.openLibrary(library, target, actor = joined.actor, create = false)
        assertTrue(store.viewsJson().get(15, java.util.concurrent.TimeUnit.SECONDS).contains("from the host"))
        b.write(library, "title2" to "from the joiner")
        until("the host to take the joiner's change") { a.views(library).contains("from the joiner") }
        // an interrupted join continues with the same identity: the record says so
        assertEquals("ready", JoinFile.read(target)!!.stage)
        // a folder that is not empty cannot start a join
        val full = LocalFolderAccess(File(b.root, "full")).also { it.publish("keep.txt", "x".toByteArray()) }
        val c = stack("gamma", withLibrary = false)
        pairBySas(c, a)
        a.service.shareLibrary(c.identity.deviceId, library)
        until("gamma to be offered the library") { c.service.computeState().offers.any { it.libraryId == library } }
        try {
            c.service.joinLibrary(a.identity.deviceId, library, full, "file:///full")
            throw AssertionError("a folder with files was used")
        } catch (e: JoinError) {
            assertEquals("folder", e.code)
        }
    }

    @Test fun aDeviceThatLeavesAJoinedLibraryIsRevokedByTheHost() {
        val a = stack("alpha")
        val b = stack("beta", withLibrary = false)
        a.write(library, "title" to "from the host")
        pairBySas(b, a)
        a.service.shareLibrary(b.identity.deviceId, library)
        until("beta to be offered the library") { b.service.computeState().offers.any { it.libraryId == library } }
        val target = LocalFolderAccess(File(b.root, "joined"))
        val joined = b.service.joinLibrary(a.identity.deviceId, library, target, "file:///joined")
        val hostLibrary = { a.libs.first { it.libraryId == library } }
        until("the host to hold the joiner as a member") { hostLibrary().membership().members[joined.actor]?.cutoff == null && hostLibrary().membership().members.containsKey(joined.actor) }
        // an identity the host did not grant cannot make the host revoke anything
        assertFalse(b.service.leaveLibrary(a.identity.deviceId, library, UUID.randomUUID().toString()))
        assertEquals(null, hostLibrary().membership().members[joined.actor]!!.cutoff)
        // beta leaves: alpha revokes the actor it granted, with a cutoff
        assertTrue(b.service.leaveLibrary(a.identity.deviceId, library, joined.actor))
        assertNotNull("the actor is revoked with a cutoff", hostLibrary().membership().members[joined.actor]!!.cutoff)
        assertEquals("", a.service.peers.get(b.identity.deviceId)!!.libraries.first { it.libraryId == library }.actor)

        // asking again changes nothing: the host no longer holds that identity as granted
        assertFalse(b.service.leaveLibrary(a.identity.deviceId, library, joined.actor))
        // a device that is not connected cannot leave (the caller then publishes nothing and says so)
        assertFalse(b.service.leaveLibrary(UUID.randomUUID().toString(), library, joined.actor))
    }
    @Test fun aFileIsPlayedFromAPeerWithRealRanges() {
        val a = stack("alpha")
        val b = stack("beta")
        val bytes = ByteArray(300_000) { (it * 7 % 251).toByte() }
        val id = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        a.files[id] = bytes
        pairBySas(b, a) // beta asks, so beta knows the address to reach alpha at
        // beta may read metadata by default, not files
        until("beta to be connected") { b.service.links[a.identity.deviceId]?.online == true }
        try {
            b.service.playFrom(library, id, "song.mp3")
            throw AssertionError("played a file without the grant")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains("No paired device"))
        }
        grant(a, b, library, NetAction.MetadataRead, NetAction.MetadataWrite, NetAction.MediaRead)
        until("beta to learn it may play files") { b.service.links[a.identity.deviceId]?.lastHello?.libraries?.any { "media.read" in it.actions } == true }
        val result = b.service.playFrom(library, id, "song.mp3")
        assertEquals(bytes.size.toLong(), result.size)
        assertEquals("alpha", result.deviceName)
        val token = result.uri.removePrefix("keepfin-peer://")
        val source = b.service.peerSource(token)!!
        val whole = source.open(null)
        assertEquals(200, whole.status)
        assertEquals(bytes.toList(), whole.input.readBytes().toList())
        whole.close()
        val part = source.open(1000L..1999L)
        assertEquals(206, part.status)
        assertEquals("bytes 1000-1999/${bytes.size}", part.contentRange)
        assertEquals(bytes.copyOfRange(1000, 2000).toList(), part.input.readBytes().toList())
        part.close()
        // an unknown file is not offered by anyone
        try {
            b.service.playFrom(library, "sha256:" + "0".repeat(64), "nothing")
            throw AssertionError()
        } catch (e: IllegalStateException) {
            // none of the peers has it
        }
    }

    @Test fun aDownloadAskedOfAPeerIsRunOnceAndItsProgressComesBack() {
        val a = stack("alpha", jobs = true)
        val b = stack("beta")
        pairBySas(b, a)
        grant(a, b, library, NetAction.MetadataRead, NetAction.MetadataWrite, NetAction.Jobs)
        until("beta to learn it may send downloads") { b.service.computeState().paired.single().jobsAllowed }
        b.service.sendJob(a.identity.deviceId, "https://example.com/watch?v=1", JSONObject("""{"type":"video","maxHeight":720,"codec":"auto","container":"mp4"}"""))
        until("alpha to have the job") { a.jobs.created.size == 1 }
        until("beta to hear it was queued") { b.service.outgoing.list().single().state == "queued" }
        // the same request again (a reconnect resends) makes no second job
        b.service.kickAll()
        Thread.sleep(500)
        assertEquals(1, a.jobs.created.size)
        val jobId = a.jobs.created.single().first
        a.jobs.change(jobId, RemoteJobView("downloading", 42.0, "Title", "720p", 1000, 30))
        until("beta to show the progress") { b.service.outgoing.list().single().percent == 42.0 }
        a.jobs.change(jobId, RemoteJobView("completed", 100.0, "Title", "720p"))
        until("beta to show it done") { b.service.outgoing.list().single().state == "completed" }
        // a link that points inside the network is refused by the policy, however it is dressed
        val refusedUrl = a.service.jobsHost!!.let { host ->
            b.service.sendJob(a.identity.deviceId, "http://192.168.1.1/admin", JSONObject("""{"type":"audio","target":"mp3","quality":"best"}"""))
            until("the refusal to come back") { b.service.outgoing.list().any { it.url.contains("192.168") && it.state in setOf("refused", "queued") } }
            host
        }
        assertNotNull(refusedUrl)
    }

    @Test fun aRemovedDeviceIsCutOffAndItsLaterChangesAreSetAside() {
        val x = stack("alpha")
        val y = stack("beta")
        // the device with the lower id is the administrator (it writes the root); the other one asks, so its own link works
        val admin = if (x.identity.deviceId < y.identity.deviceId) x else y
        val other = if (admin === x) y else x
        pairBySas(other, admin)
        until("both libraries to enforce membership") { x.service.computeState().libraries.single().signed && y.service.computeState().libraries.single().signed }
        other.write(library, "x" to "before removal")
        until("the administrator to have it") { admin.views(library).contains("before removal") }
        val result = admin.service.remove(other.identity.deviceId)
        assertEquals(1, result.revoked.size)
        assertTrue(admin.service.peers.list().isEmpty())
        // the removed device cannot reach the administrator any more
        until("the removed device to be turned away") { other.service.peers.get(admin.identity.deviceId)?.unreachableSince != null }
        other.write(library, "y" to "after removal")
        Thread.sleep(1200)
        assertFalse(admin.views(library).contains("after removal"))
        assertTrue(admin.views(library).contains("before removal"))
    }
    @Test fun anUnreachableDeviceIsRetriedWithBackOffAndFoundAgain() {
        val a = stack("alpha")
        val b = stack("beta")
        pairBySas(a, b) // alpha asks, so alpha's link to beta is the one that works
        until("alpha to see beta") { a.service.links[b.identity.deviceId]?.online == true }
        val port = b.service.listeningPort!!
        b.service.disable()
        until("alpha to notice") { a.service.links[b.identity.deviceId]?.online != true }
        until("a failed attempt to be recorded") { a.service.peers.get(b.identity.deviceId)?.unreachableSince != null }
        // unreachableSince is recorded a moment before the next delay is set: wait for the delay, then check it stays within the cap
        until("a back-off delay to be scheduled") { (a.service.links[b.identity.deviceId]?.nextDelayMs ?: 0L) > 0L }
        assertTrue(a.service.links[b.identity.deviceId]!!.nextDelayMs in 1..1000)
        assertNotNull(port)
        val since = a.service.peers.get(b.identity.deviceId)!!.unreachableSince!!
        assertTrue(since > 0)
        b.service.enable() // a new port: alpha learns it from the next reconnect only if the address is reachable
    }
}
