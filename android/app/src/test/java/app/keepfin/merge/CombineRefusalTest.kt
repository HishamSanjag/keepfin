package app.keepfin.merge

import app.keepfin.folder.Profile
import app.keepfin.folder.ProfileStore
import app.keepfin.folder.sql.JdbcHost
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A connected device that offers no library to combine: the review says why (no Keepfin folder, the same library, no permission). */
class CombineRefusalTest {
    private lateinit var root: File

    @Before fun setUp() { root = Files.createTempDirectory("keepfin-refusal").toFile() }
    @After fun tearDown() { root.deleteRecursively() }

    private fun envWith(gap: String?, online: Boolean = true) = object : MergeEnv {
        override val filesDir: File get() = root
        override val profiles = ProfileStore(File(root, "profiles.json"))
        override val sqlHost = JdbcHost()
        override val signer = null
        override fun folder(treeUri: String) = error("unused")
        override fun offer(deviceId: String) = null
        override fun connectedName(deviceId: String) = if (online) "Desk PC" else null
        override fun offerGap(deviceId: String) = gap
        override fun currentProfile() = Profile("p", "Me", "content://tree", "lib-mine", "actor", "2026-10-22T00:00:00Z")
        override fun openLibrary(): OpenLibrary = error("unused")
        override fun join(journal: MergeJournal, progress: (String, Int) -> Unit, cancelled: () -> Boolean): JoinOutcome = error("unused")
        override fun peer(journal: MergeJournal): PeerPort? = null
        override fun reopen() {}
        override fun remapOutside(maps: IdMaps) {}
        override fun refusal(): String? = null
        override fun copyRunning() = false
        override fun replayPlays(libraryId: String, plays: List<MergeFence.Play>, maps: IdMaps?) {}
    }

    private fun refusal(gap: String?, online: Boolean = true) = MergeService(envWith(gap, online)).combinePreview("desk").refusal.orEmpty()

    @Test fun aConnectedDeviceThatOffersNothingSaysWhy() {
        assertTrue(refusal("none").startsWith("Desk PC has no Keepfin folder yet"))
        assertTrue(refusal("held").startsWith("Desk PC already shares this library"))
        assertTrue(refusal("denied").startsWith("Desk PC does not let this phone read its library"))
        assertTrue(refusal("unshared").startsWith("Desk PC has not shared its library with this phone yet. On Desk PC, open Settings › Devices"))
        // a device with a Keepfin folder it has not shared is not "no Keepfin folder"; an older one that does not count is not either
        fun hello(json: String) = app.keepfin.net.HelloAnswer.parse(org.json.JSONObject(json))
        org.junit.Assert.assertEquals("unshared", hello("""{"libraries":[],"unshared":1}""").gap(emptySet()))
        org.junit.Assert.assertEquals("none", hello("""{"libraries":[],"unshared":0}""").gap(emptySet()))
        org.junit.Assert.assertEquals("unshared", hello("""{"libraries":[]}""").gap(emptySet()))
        // before its hello nothing is known, and an unconnected device gets the general message
        assertEquals("That device is not offering its library right now. Make sure it is turned on and connected.", refusal(null))
        assertEquals("That device is not offering its library right now. Make sure it is turned on and connected.", refusal("none", online = false))
    }
}
