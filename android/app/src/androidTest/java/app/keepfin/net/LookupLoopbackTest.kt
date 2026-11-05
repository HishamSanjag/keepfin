package app.keepfin.net

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A paired device that comes back at another address while nobody has the Devices page open (a PC whose router handed it a new one): the
 * phone tries every address it knows, then scans the network once and finds it there, as the Windows app does.
 */
@RunWith(AndroidJUnit4::class)
class LookupLoopbackTest {
    private lateinit var context: Context
    private val stacks = ArrayList<NetStack>()
    private val library = UUID.randomUUID().toString()

    @Before fun setUp() {
        diagnostics = { stacks.joinToString("\n") { it.dump() } }
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After fun tearDown() = stacks.forEach { it.close() }

    /** Sees nothing while nobody looks; a lookup answers with where [where] says the device is now. */
    private class LookupOnly(val where: () -> List<Discovered>) : NetDiscovery {
        val lookups = AtomicInteger()
        override fun start() {}
        override fun stop() {}
        override fun browse() {}
        override fun stopBrowsing() {}
        override fun list(): List<Discovered> = emptyList()
        override fun primaryAddress(): String? = null
        override fun lookOnce(): List<Discovered> { lookups.incrementAndGet(); return where() }
    }

    @Test fun aPairedDeviceAtANewAddressIsFoundByOneScan() {
        val b = NetStack("beta", context, File(context.cacheDir, "lookup-b-${UUID.randomUUID().toString().take(6)}")).also { stacks += it }
        b.openLibrary(library)
        b.start()
        val a = NetStack("alpha", context, File(context.cacheDir, "lookup-a-${UUID.randomUUID().toString().take(6)}")).also { stacks += it }
        a.openLibrary(library)
        var discovery: LookupOnly? = null
        a.start(discovery = { _, _, _ -> LookupOnly { listOf(Discovered(b.identity.deviceId.take(16), "beta", "127.0.0.1", b.service.listeningPort!!, true)) }.also { discovery = it } }, lookupEveryMs = 200)
        pairBySas(a, b)
        until("alpha to reach beta") { a.service.links[b.identity.deviceId]?.online == true }
        val oldPort = b.service.listeningPort!!

        // beta goes away and comes back on another port; alpha's last address answers no more
        b.service.close()
        b.start()
        val newPort = b.service.listeningPort!!
        assertTrue("a new address ($oldPort, $newPort)", newPort != oldPort)
        a.service.kickAll()
        until("alpha to reach beta at its new address", 30_000) { a.service.links[b.identity.deviceId]?.let { it.online && it.lastAddress?.port == newPort } == true }
        assertTrue("found by a lookup", discovery!!.lookups.get() >= 1)
        assertEquals(newPort, a.service.links.getValue(b.identity.deviceId).lastAddress?.port)
    }
}
