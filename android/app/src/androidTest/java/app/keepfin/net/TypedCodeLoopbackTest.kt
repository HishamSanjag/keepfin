package app.keepfin.net

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.core.net.pairCodeOf
import app.keepfin.core.net.pairPinOf
import app.keepfin.core.net.parsePairCode
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Devices phase 2, the asking side over real TLS on loopback: a typed code finds the device that shows it by its key and pairs like a scanned code. */
@RunWith(AndroidJUnit4::class)
class TypedCodeLoopbackTest {
    private lateinit var context: Context
    private val stacks = ArrayList<NetStack>()
    private val library = UUID.randomUUID().toString()

    @Before fun setUp() {
        diagnostics = { stacks.joinToString("\n") { it.dump() } }
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After fun tearDown() = stacks.forEach { it.close() }

    private class FixedDiscovery(val results: List<Discovered>) : NetDiscovery {
        override fun start() {}
        override fun stop() {}
        override fun browse() {}
        override fun stopBrowsing() {}
        override fun list(): List<Discovered> = results
        override fun primaryAddress(): String? = null
    }

    private fun stack(name: String, seen: List<NetStack> = emptyList()): NetStack {
        val s = NetStack(name, context, File(context.cacheDir, "typed-$name-${UUID.randomUUID().toString().take(6)}"))
        stacks += s
        s.openLibrary(library)
        s.start(
            discovery = { _, _, _ -> FixedDiscovery(seen.map { Discovered(it.identity.deviceId.take(16), it.name, "127.0.0.1", it.service.listeningPort!!, false) }) },
            codeSearchMs = 300,
        )
        return s
    }

    private fun codeOf(s: NetStack): String {
        s.service.showQr()
        return s.service.computeState().qr!!.code!!
    }

    @Test fun aTypedCodeFindsTheDeviceShowingItByItsKeySkipsTheOtherAndPairsLikeAScannedCode() {
        val b = stack("beta")
        val c = stack("gamma")
        val a = stack("alpha", seen = listOf(c, b)) // the decoy is tried first
        val codeB = codeOf(b)
        codeOf(c)
        // typed the way a person might: lower case, a space for the dash
        a.service.ask(codeB.lowercase().replace('-', ' '))
        val view = a.service.computeState().pairing!!
        assertEquals(view.error, "done", view.stage)
        until("beta to remember alpha") { b.service.peers.get(a.identity.deviceId) != null && a.service.peers.get(b.identity.deviceId) != null }
        assertTrue("beta's side was approved by the code", b.service.computeState().pairing?.autoApproved == true)
        assertNull("the device with another key got no start", c.service.computeState().pairing)
        assertNull(c.service.peers.get(a.identity.deviceId))
        assertNull("the used code is gone", b.service.computeState().qr)
    }

    @Test fun aCodeNoDeviceIsShowingOrWhoseKeyNobodyHasSaysSoAndSendsNothing() {
        val b = stack("beta")
        val a = stack("alpha", seen = listOf(b))
        val none = "No device showing this code was found. Check the code, and that both devices are on the same network or Tailscale."
        // beta's key is right but it shows no code
        a.service.ask(pairCodeOf(77, pairPinOf(b.identity.spki)))
        assertEquals(none, a.service.computeState().pairing!!.error)
        a.service.dismissPairing()
        // beta shows one, but the typed code carries another key
        val shown = parsePairCode(codeOf(b))!!
        a.service.ask(pairCodeOf(shown.token, pairPinOf(a.identity.spki)))
        assertEquals(none, a.service.computeState().pairing!!.error)
        assertNull(b.service.computeState().pairing)
    }

    @Test fun aWrongCodeIsOneTryOnThatDeviceThenNotRightAndTheRightCodeStillWorks() {
        val b = stack("beta")
        val a = stack("alpha", seen = listOf(b))
        val shown = codeOf(b)
        a.service.ask(pairCodeOf((parsePairCode(shown)!!.token + 1) and 0xfffff, pairPinOf(b.identity.spki)))
        val failed = a.service.computeState().pairing!!
        assertEquals("failed", failed.stage)
        assertEquals("That code is not right or has expired.", failed.error)
        assertNull(a.service.peers.get(b.identity.deviceId))
        a.service.dismissPairing()
        a.service.ask(shown)
        assertEquals(a.service.computeState().pairing!!.error, "done", a.service.computeState().pairing!!.stage)
        assertNotNull(a.service.peers.get(b.identity.deviceId))
        assertFalse(b.service.computeState().qr != null)
    }
}