package app.keepfin.net

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** D2 and D3 over real TLS 1.3 on loopback: a stranger finds a device through /v1/announce, and a paired device learns its addresses from hello. */
@RunWith(AndroidJUnit4::class)
class AnnounceLoopbackTest {
    private lateinit var context: Context
    private val stacks = ArrayList<NetStack>()

    @Before fun setUp() {
        diagnostics = { stacks.joinToString("\n") { it.dump() } }
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After fun tearDown() {
        stacks.forEach { it.close() }
    }

    private fun stack(name: String, nics: (() -> List<Nic>)? = null): NetStack {
        val s = NetStack(name, context, File(context.cacheDir, "announce-$name-${UUID.randomUUID().toString().take(6)}"))
        stacks += s
        s.start(nics = nics)
        return s
    }

    /** The real connector (TLS 1.3 with the asker's key), but only 127.0.0.1 counts as open: every 127.x address reaches the same listener. */
    private fun loopbackOnly(asker: NetStack): ScanConnector {
        val real = SocketScanConnector { asker.identity }
        return object : ScanConnector {
            override fun portOpen(host: String, port: Int, timeoutMs: Int): Boolean = host == "127.0.0.1" && real.portOpen(host, port, timeoutMs)
            override fun announce(host: String, port: Int, timeoutMs: Int): Announce? = real.announce(host, port, timeoutMs)
        }
    }

    @Test fun anotherDeviceIsFoundByTheScanThroughAnnounceOverTls() {
        val server = stack("alpha")
        val asker = stack("beta")
        val found = runBlocking {
            NetScanner({ listOf("127.0.0.77") }, loopbackOnly(asker), { asker.identity.deviceId.take(16) }, port = server.service.listeningPort!!).scanOnce()
        }
        assertEquals(1, found.size)
        val f = found.single()
        assertEquals(server.identity.deviceId.take(16), f.hint)
        assertEquals("alpha", f.name)
        assertEquals("android", f.platform)
        assertEquals("127.0.0.1", f.host)
        assertEquals(server.service.listeningPort, f.port)
    }

    @Test fun theScanFindsADeviceThatIsNotPairedAndStillOffersNothingElse() {
        val server = stack("alpha")
        val asker = stack("beta")
        val client = PeerClient(ClientOptions(asker.identity, "127.0.0.1", server.service.listeningPort!!, null, 3000))
        try {
            val announce = client.json("GET", "/v1/announce")
            assertEquals(setOf("v", "id", "n", "p"), announce.keys().asSequence().toSet())
            val refused = runCatching { client.json("GET", "/v1/hello?v=1") }.exceptionOrNull()
            assertTrue("hello is for paired devices only", refused is PeerHttpError && refused.status == 403)
        } finally {
            client.close()
        }
    }

    @Test fun aPairedDeviceLearnsAddressesFromHelloAndReportsTheRoute() {
        val tailscaleNics = { listOf(Nic("wlan0", true, false, listOf("192.168.77.5")), Nic("tun0", true, false, listOf("100.64.7.7"))) }
        val a = stack("alpha", nics = tailscaleNics)
        val b = stack("beta")
        pairBySas(b, a)
        until("beta to read alpha's hello") { b.service.peers.get(a.identity.deviceId)?.addresses == listOf("192.168.77.5", "100.64.7.7") }
        val state = b.service.computeState()
        until("beta to show alpha online on the home network") { b.service.computeState().paired.singleOrNull()?.let { it.online && it.route == "lan" } == true }
        assertFalse(state.network.tailscale)
        assertEquals("100.64.7.7", a.service.computeState().network.tailscaleAddress)
        assertNotNull(a.service.computeState().network)
    }
}
