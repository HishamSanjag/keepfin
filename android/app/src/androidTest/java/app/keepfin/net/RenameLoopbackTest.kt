package app.keepfin.net

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A device renamed after pairing: the paired device shows the new name at once while connected, and keeps it after a restart. */
@RunWith(AndroidJUnit4::class)
class RenameLoopbackTest {
    private lateinit var context: Context
    private val stacks = ArrayList<NetStack>()
    private val library = UUID.randomUUID().toString()

    @Before fun setUp() {
        diagnostics = { stacks.joinToString("\n") { it.dump() } }
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After fun tearDown() = stacks.forEach { it.close() }

    @Test fun aRenameReachesThePairedDevice() {
        val dirA = File(context.cacheDir, "rename-a-${UUID.randomUUID().toString().take(6)}")
        val b = NetStack("beta", context, File(context.cacheDir, "rename-b-${UUID.randomUUID().toString().take(6)}")).also { stacks += it }
        b.openLibrary(library)
        b.start()
        val a = NetStack("alpha", context, dirA).also { stacks += it }
        a.openLibrary(library)
        a.start()
        pairBySas(a, b)
        until("alpha to reach beta") { a.service.links[b.identity.deviceId]?.online == true }
        assertEquals("beta", a.service.peers.get(b.identity.deviceId)!!.name)

        b.service.rename("Living room PC")
        until("alpha to show beta's new name") { a.service.peers.get(b.identity.deviceId)?.name == "Living room PC" }
        assertEquals("Living room PC", PeerStore.open(File(a.deviceDir, "net")).get(b.identity.deviceId)?.name)
    }
}
