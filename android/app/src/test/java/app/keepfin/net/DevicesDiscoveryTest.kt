package app.keepfin.net

import app.keepfin.core.net.NetAction
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Discovery that works (dev-docs/plans/DEVICES-DISCOVERY.md D2 and D3), Android side: announce, the scan, addresses, reconnect order, Tailscale. */
class DevicesDiscoveryTest {
    private val dirs = ArrayList<File>()
    private val closers = ArrayList<() -> Unit>()

    @After fun tearDown() {
        closers.forEach { runCatching { it() } }
        dirs.forEach { it.deleteRecursively() }
    }

    // ---- /v1/announce on the real server ------------------------------------------------------------------------------------------

    private class Rig(val host: SoftIdentity, val server: NetServer, val acceptor: PlainAcceptor, val peers: PeerStore, val pairing: PairingHost)

    private fun rig(limits: Limits = Limits(), addresses: List<String> = listOf("192.168.1.5", "100.64.0.7")): Rig {
        val host = SoftIdentity("Kitchen phone")
        val dir = tempDir().also { dirs += it }
        val peers = PeerStore.open(dir)
        val lib = FakeNetLibrary()
        val pairing = PairingHost(PairingHostOptions({ host }, limits, { System.currentTimeMillis() }, { listOf(PairLibraryRef(LIB, ACTOR_A)) }, { _, _ -> }, {}))
        val acceptor = PlainAcceptor()
        val executor = PeerExecutorImpl(lib.folder)
        val server = NetServer(
            NetServerDeps(identity = { host }, peers = peers, libraries = { listOf(lib) }, executor = { executor }, pairing = pairing, cacheDir = tempDir().also { dirs += it }, limits = limits, addresses = { addresses }),
        )
        server.start(acceptor)
        closers += { server.close(); pairing.shutdown() }
        return Rig(host, server, acceptor, peers, pairing)
    }

    @Test fun announceAnswersAStrangerWithExactlyTheDnsSdFacts() {
        val r = rig()
        val stranger = SoftIdentity("Stranger")
        val reply = rawRequest(r.acceptor, stranger.spki, get("/v1/announce"))
        assertEquals(200, reply.status)
        val body = JSONObject(reply.text)
        assertEquals(setOf("v", "id", "n", "p"), body.keys().asSequence().toSet())
        assertEquals(1, body.getInt("v"))
        assertEquals(r.host.deviceId.take(16), body.getString("id"))
        assertTrue(Regex("^[0-9a-f]{16}$").matches(body.getString("id")))
        assertEquals("Kitchen phone", body.getString("n"))
        assertEquals("android", body.getString("p"))
        // and nothing else is open to the same stranger
        assertEquals(403, rawRequest(r.acceptor, stranger.spki, get("/v1/hello?v=1")).status)
    }

    @Test fun announceHasItsOwnBudgetPerAddressAndTheStrangerBudgetIsNotUsedUp() {
        val r = rig(Limits(announcePerMinute = 3, strangerRequestsPerMinute = 2))
        val stranger = SoftIdentity("Stranger")
        val statuses = (1..5).map { rawRequest(r.acceptor, stranger.spki, get("/v1/announce")).status }
        assertEquals(listOf(200, 200, 200, 429, 429), statuses)
        // announce does not spend the stranger budget of the other endpoints (two allowed, then refused as before)
        val others = (1..3).map { rawRequest(r.acceptor, stranger.spki, get("/v1/hello?v=1")).status }
        assertEquals(listOf(403, 403, 429), others)
    }

    @Test fun theDefaultAnnounceBudgetIsOneHundredTwentyAMinute() {
        assertEquals(120, Limits().announcePerMinute)
    }

    @Test fun helloNamesThisDevicesAddressesForPairedCallersOnly() {
        val r = rig(addresses = listOf("192.168.1.5", "100.64.0.7", "192.168.1.5"))
        val friend = SoftIdentity("Friend")
        r.peers.put(Peer(friend.deviceId, Base64.getEncoder().encodeToString(friend.spki), "Friend", "windows", 1, 1, listOf(PeerLibrary(LIB, ACTOR_B, setOf(NetAction.MetadataRead)))))
        val hello = JSONObject(rawRequest(r.acceptor, friend.spki, get("/v1/hello?v=1")).text)
        val list = hello.getJSONArray("addresses")
        assertEquals(listOf("192.168.1.5", "100.64.0.7"), (0 until list.length()).map { list.getString(it) })
        val parsed = HelloAnswer.parse(hello)
        assertEquals(listOf("192.168.1.5", "100.64.0.7"), parsed.addresses)
        // an older peer's hello has none
        assertEquals(emptyList<String>(), HelloAnswer.parse(JSONObject().put("name", "Old")).addresses)
        assertEquals(403, rawRequest(r.acceptor, SoftIdentity("S").spki, get("/v1/hello?v=1")).status)
    }

    // ---- the scan ------------------------------------------------------------------------------------------------------------------

    /** A connector that opens the ports and announces what the test says; records every call. */
    private class FakeConnector(val open: Map<String, Announce> = emptyMap(), val delayMs: Long = 0) : ScanConnector {
        val probed = ConcurrentLinkedQueue<String>()
        val connectTimeouts = ConcurrentLinkedQueue<Int>()
        val announceTimeouts = ConcurrentLinkedQueue<Int>()
        private val running = AtomicInteger()
        @Volatile var maxRunning = 0
        val passes = AtomicInteger()

        override fun portOpen(host: String, port: Int, timeoutMs: Int): Boolean {
            val now = running.incrementAndGet()
            synchronized(this) { if (now > maxRunning) maxRunning = now }
            try {
                probed += host
                connectTimeouts += timeoutMs
                if (delayMs > 0) Thread.sleep(delayMs)
                return host in open
            } finally {
                running.decrementAndGet()
            }
        }

        override fun announce(host: String, port: Int, timeoutMs: Int): Announce? {
            announceTimeouts += timeoutMs
            return open[host]
        }
    }

    private fun hint(n: Int) = "%016x".format(n)

    @Test fun theScanWalksTheLocalSlash24SkippingSelfTailscaleAndTheEnds() {
        val c = FakeConnector()
        val scanner = NetScanner({ listOf("192.168.1.20") }, c, { hint(0) }, ownAddresses = { setOf("192.168.1.20", "100.101.102.103") })
        runBlocking { scanner.scanOnce() }
        val hosts = c.probed.toList()
        assertEquals(253, hosts.size)
        assertFalse("192.168.1.20" in hosts)
        assertFalse("192.168.1.0" in hosts)
        assertFalse("192.168.1.255" in hosts)
        assertTrue(hosts.all { it.startsWith("192.168.1.") })
        // a Tailscale address is never the base of a scan, and 100.64/10 hosts are never probed
        assertTrue(scanTargets(listOf("100.101.102.103")).isEmpty())
        assertTrue(scanTargets(listOf("192.168.1.20", "100.101.102.103")).none { it.startsWith("100.") })
        assertEquals(emptyList<String>(), scanTargets(emptyList()))
        // two addresses in the same /24 scan it once; two networks scan both
        assertEquals(252, scanTargets(listOf("10.0.0.5", "10.0.0.6")).size)
        assertEquals(253 + 254, scanTargets(listOf("10.0.0.5", "10.0.1.5"), setOf("10.0.0.5")).size)
    }

    @Test fun theScanUsesTheDocumentedTimeoutsAndOnlyAsksOpenHostsToAnnounce() {
        val c = FakeConnector(open = mapOf("192.168.1.31" to Announce(hint(31), "Desk PC", "windows")))
        val found = runBlocking { NetScanner({ listOf("192.168.1.20") }, c, { hint(0) }).scanOnce() }
        assertEquals(setOf(400), c.connectTimeouts.toSet())
        assertEquals(listOf(1500), c.announceTimeouts.toList())
        assertEquals(1, found.size)
        assertEquals("192.168.1.31", found.single().host)
        assertEquals(DEFAULT_PORT, found.single().port)
        assertEquals("Desk PC", found.single().name)
        assertEquals("windows", found.single().platform)
    }

    @Test fun theScanNeverMoreThanFortyEightAtATime() {
        val c = FakeConnector(delayMs = 4)
        runBlocking { NetScanner({ listOf("192.168.1.20", "10.1.2.3") }, c, { hint(0) }).scanOnce() }
        assertTrue("probed in parallel (${c.maxRunning})", c.maxRunning > 8)
        assertTrue("at most 48 at once (${c.maxRunning})", c.maxRunning <= 48)
        assertEquals(253 + 253, c.probed.size)
    }

    @Test fun anAnswerFromThisPhoneItselfAndFailuresAreLeftOut() {
        val throwing = object : ScanConnector {
            override fun portOpen(host: String, port: Int, timeoutMs: Int): Boolean = when (host) {
                "192.168.1.5" -> true // announces itself
                "192.168.1.6" -> true // not a Keepfin device: the announce fails
                "192.168.1.7" -> throw java.io.IOException("reset")
                else -> false
            }

            override fun announce(host: String, port: Int, timeoutMs: Int): Announce? = when (host) {
                "192.168.1.5" -> Announce(hint(99), "Me", "android")
                else -> throw java.net.SocketTimeoutException("slow")
            }
        }
        val found = runBlocking { NetScanner({ listOf("192.168.1.20") }, throwing, { hint(99) }).scanOnce() }
        assertTrue(found.isEmpty())
    }

    @Test fun anAnnounceAnswerMustBeExactlyTheProtocol() {
        assertNotNull(parseAnnounce(JSONObject("""{"v":1,"id":"0123456789abcdef","n":"Phone","p":"android"}""")))
        assertNull(parseAnnounce(JSONObject("""{"v":2,"id":"0123456789abcdef","n":"Phone","p":"android"}""")))
        assertNull(parseAnnounce(JSONObject("""{"v":1,"id":"xyz","n":"Phone","p":"android"}""")))
        assertNull(parseAnnounce(JSONObject("""{"v":1,"n":"Phone","p":"android"}""")))
        assertEquals(40, parseAnnounce(JSONObject().put("v", 1).put("id", "0123456789ABCDEF").put("n", "x".repeat(90)).put("p", "p"))!!.name.length)
    }

    /** A DNS-SD stand-in with fixed results. */
    private class FakeNsd(var results: List<Discovered> = emptyList()) : NetDiscovery {
        var browsing = false
        override fun start() {}
        override fun stop() {}
        override fun browse() { browsing = true }
        override fun stopBrowsing() { browsing = false }
        override fun list(): List<Discovered> = results
        override fun primaryAddress(): String? = null
    }

    private fun until(label: String, ms: Long = 5000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (check()) return
            Thread.sleep(10)
        }
        throw AssertionError("Timed out waiting for $label")
    }

    @Test fun scanAndDnsSdResultsMergeByIdHintAndTheDnsSdAddressWins() {
        val nsd = FakeNsd(listOf(Discovered(hint(1), "Tablet (DNS-SD)", "192.168.1.31", 47215, false)))
        val c = FakeConnector(open = mapOf("192.168.1.99" to Announce(hint(1), "Tablet (scan)", "android"), "192.168.1.40" to Announce(hint(2), "Desk PC", "windows")))
        var changes = 0
        val d = CombinedDiscovery(nsd, NetScanner({ listOf("192.168.1.20") }, c, { hint(0) }), { false }, { changes++ }, everyMs = 60_000)
        closers += { d.stop() }
        d.browse()
        until("both devices") { d.list().size == 2 }
        val byHint = d.list().associateBy { it.hint }
        assertEquals("192.168.1.31", byHint.getValue(hint(1)).host)
        assertEquals("Tablet (DNS-SD)", byHint.getValue(hint(1)).name)
        assertEquals("192.168.1.40", byHint.getValue(hint(2)).host)
        assertTrue(nsd.browsing)
        assertTrue("the page was told", changes > 0)
        until("the scan to end") { !d.scanning }
    }

    @Test fun aLookupScansOnceWithNobodyLookingAndNeverOnMobileDataOnly() {
        // a paired PC moved to a new address while the Devices page is closed: one scan finds it there
        val c = FakeConnector(open = mapOf("192.168.1.77" to Announce(hint(7), "Desk PC", "windows")))
        var lan = true
        val nsd = FakeNsd()
        val d = CombinedDiscovery(nsd, NetScanner({ listOf("192.168.1.20") }, c, { hint(0) }), { it == hint(7) }, {}, allowed = { lan }, everyMs = 60_000)
        closers += { d.stop() }
        val found = d.lookOnce()
        assertEquals(listOf("192.168.1.77"), found.map { it.host })
        assertTrue("it is the paired one", found.single().paired)
        assertFalse("a lookup does not start looking", nsd.browsing)
        assertTrue("nothing stays listed after a lookup", d.list().isEmpty())
        lan = false
        c.probed.clear()
        assertTrue(d.lookOnce().isEmpty())
        assertTrue("no scan on mobile data", c.probed.isEmpty())
    }

    @Test fun theScanRepeatsWhileBrowsingStopsWithItAndNeverRunsWithoutALocalNetwork() {
        val c = FakeConnector(open = mapOf("192.168.1.40" to Announce(hint(2), "Desk PC", "windows")))
        var lan = true
        val nsd = FakeNsd()
        val d = CombinedDiscovery(nsd, NetScanner({ listOf("192.168.1.20") }, c, { hint(0) }), { false }, {}, allowed = { lan }, everyMs = 60)
        closers += { d.stop() }
        d.browse()
        until("two passes") { c.probed.count { it == "192.168.1.1" } >= 2 }
        d.stopBrowsing()
        assertFalse(nsd.browsing)
        assertTrue(d.list().isEmpty())
        assertFalse(d.scanning)
        Thread.sleep(150)
        val after = c.probed.size
        Thread.sleep(250)
        assertEquals("nothing is probed after the page closed", after, c.probed.size)
        // mobile data only: no Wi-Fi or Ethernet, so no scan
        lan = false
        val before = c.probed.size
        d.browse()
        Thread.sleep(300)
        assertEquals(before, c.probed.size)
        assertTrue(d.list().isEmpty())
    }

    // ---- one server found by another through /v1/announce ----------------------------------------------------------------------------------

    @Test fun aServerIsFoundByAnotherDeviceThroughAnnounce() {
        val server = rig()
        val asker = SoftIdentity("Asker")
        // the connector speaks the real wire to the real server (the TLS handshake is the only part a plain acceptor replaces)
        val connector = object : ScanConnector {
            override fun portOpen(host: String, port: Int, timeoutMs: Int): Boolean = host == "127.0.0.1"
            override fun announce(host: String, port: Int, timeoutMs: Int): Announce? {
                val reply = rawRequest(server.acceptor, asker.spki, get("/v1/announce"))
                return if (reply.status == 200) parseAnnounce(JSONObject(reply.text)) else null
            }
        }
        val found = runBlocking { NetScanner({ listOf("127.0.0.77") }, connector, { asker.deviceId.take(16) }, port = server.acceptor.port).scanOnce() }
        assertEquals(1, found.size)
        assertEquals(server.host.deviceId.take(16), found.single().hint)
        assertEquals("Kitchen phone", found.single().name)
        assertEquals("127.0.0.1", found.single().host)
        assertEquals(server.acceptor.port, found.single().port)
        // that hint is what a paired device's link looks for
        assertTrue(server.host.deviceId.startsWith(found.single().hint))
    }

    // ---- addresses of paired devices -----------------------------------------------------------------------------------------------------

    private fun peerFile(dir: File, addresses: List<String>?): File {
        val peer = JSONObject().put("deviceId", "d".repeat(64)).put("spki", "AAAA").put("name", "Desk PC").put("platform", "windows").put("pairedAt", 1).put("lastSeen", JSONObject.NULL).put("libraries", JSONArray())
        if (addresses != null) peer.put("addresses", JSONArray(addresses))
        return File(dir, "peers.json").also { it.writeText(JSONObject().put("v", 1).put("peers", JSONArray().put(peer)).toString()) }
    }

    @Test fun aPeersFileWithoutAddressesOpensWithNone() {
        val dir = tempDir().also { dirs += it }
        peerFile(dir, null)
        assertEquals(emptyList<String>(), PeerStore.open(dir).list().single().addresses)
    }

    @Test fun peersKeepAtMostSixAddressesNewestFirstAndSurviveARestart() {
        val dir = tempDir().also { dirs += it }
        peerFile(dir, null)
        val store = PeerStore.open(dir)
        val id = "d".repeat(64)
        store.learnAddresses(id, listOf("192.168.1.2", "192.168.1.3"))
        store.learnAddresses(id, listOf("10.0.0.9"))
        assertEquals(listOf("10.0.0.9", "192.168.1.2", "192.168.1.3"), store.get(id)!!.addresses)
        store.learnAddresses(id, (10..20).map { "192.168.5.$it" })
        val kept = store.get(id)!!.addresses
        assertEquals(6, kept.size)
        assertEquals((10..15).map { "192.168.5.$it" }, kept)
        assertEquals(kept, PeerStore.open(dir).get(id)!!.addresses)
        // a file written with too many is read back capped
        peerFile(dir, (1..9).map { "192.168.7.$it" })
        assertEquals(6, PeerStore.open(dir).list().single().addresses.size)
    }

    @Test fun aLongLanListStillKeepsTwoTailscaleAddressesAndGarbageIsDropped() {
        val merged = mergeAddresses(listOf("100.64.0.7", "my-pc.tailnet.ts.net") + (1..8).map { "192.168.1.$it" }, emptyList())
        assertEquals(6, merged.size)
        assertTrue("100.64.0.7" in merged)
        assertTrue("my-pc.tailnet.ts.net" in merged)
        assertEquals(emptyList<String>(), mergeAddresses(listOf("not an address", "http://x/y", "a:b", ""), emptyList()))
        assertEquals(listOf("192.168.1.2"), mergeAddresses(listOf("192.168.1.2", "192.168.1.2"), emptyList()))
    }

    @Test fun aNewNameFromHelloIsKeptAndABlankOneIsNot() {
        val dir = tempDir().also { dirs += it }
        peerFile(dir, null)
        val store = PeerStore.open(dir)
        val id = "d".repeat(64)
        store.learnName(id, "  Living room PC ")
        assertEquals("Living room PC", store.get(id)!!.name)
        store.learnName(id, "   ")
        assertEquals("Living room PC", store.get(id)!!.name)
        store.learnName(id, "x".repeat(200))
        assertEquals(MAX_NAME, store.get(id)!!.name.length)
        assertEquals(store.get(id)!!.name, PeerStore.open(dir).get(id)!!.name)
    }

    @Test fun learningTheSameAddressesAgainDoesNotRewriteTheFile() {
        val dir = tempDir().also { dirs += it }
        peerFile(dir, null)
        val store = PeerStore.open(dir)
        val id = "d".repeat(64)
        store.learnAddresses(id, listOf("192.168.1.2", "100.64.0.7"))
        val file = File(dir, "peers.json")
        val first = file.lastModified()
        file.setLastModified(first - 10_000)
        val stamp = file.lastModified()
        store.learnAddresses(id, listOf("192.168.1.2", "100.64.0.7"))
        assertEquals(stamp, file.lastModified())
    }

    @Test fun reconnectOrderIsDiscoveryThenLastHostThenLanThenTailscale() {
        val order = reconnectOrder(
            discovered = listOf(Route("192.168.1.50", 47215)), lastHost = "192.168.1.20", lastPort = 47300,
            stored = listOf("100.64.0.7", "192.168.1.21", "desk.tailnet.ts.net", "192.168.1.20", "10.0.0.4"),
        )
        assertEquals(
            listOf("192.168.1.50:47215", "192.168.1.20:47300", "192.168.1.21:47300", "10.0.0.4:47300", "100.64.0.7:47300", "desk.tailnet.ts.net:47300"),
            order.map { "${it.host}:${it.port}" },
        )
        assertEquals(listOf("192.168.1.20:47215"), reconnectOrder(emptyList(), "192.168.1.20", null, listOf("192.168.1.20")).map { "${it.host}:${it.port}" })
        assertTrue(reconnectOrder(emptyList(), null, null, emptyList()).isEmpty())
    }

    // ---- Tailscale and routes ------------------------------------------------------------------------------------------------------------

    @Test fun tailscaleIsAnUpInterfaceWithA100x64Address() {
        val wifi = Nic("wlan0", true, false, listOf("192.168.1.20"))
        val tun = Nic("tun0", true, false, listOf("100.101.102.103"))
        assertEquals("100.101.102.103", detectTailscale(listOf(wifi, tun)))
        assertNull(detectTailscale(listOf(wifi)))
        assertNull("an interface that is down", detectTailscale(listOf(wifi, Nic("tun0", false, false, listOf("100.101.102.103")))))
        assertNull("100.128 is outside 100.64/10", detectTailscale(listOf(Nic("tun0", true, false, listOf("100.128.0.1")))))
        assertNull(detectTailscale(listOf(Nic("lo", true, true, listOf("100.64.0.1")))))
        assertEquals(listOf("192.168.1.20", "100.101.102.103"), eligibleAddresses(listOf(tun, wifi)))
        assertEquals(listOf("192.168.1.20"), lanAddresses(listOf(tun, wifi)))
        assertEquals(listOf("10.0.0.2"), lanAddresses(listOf(Nic("rmnet0", true, false, listOf("100.70.1.1", "10.0.0.2")), Nic("dummy", false, false, listOf("192.168.9.9")))))
    }

    @Test fun routesAreLanForNumbersAndTailscaleForTailscaleNumbersAndNames() {
        assertEquals("lan", routeOf("192.168.1.20"))
        assertEquals("tailscale", routeOf("100.101.102.103"))
        assertEquals("lan", routeOf("100.63.255.255"))
        assertEquals("tailscale", routeOf("desk-pc.tailnet.ts.net"))
        assertEquals("lan", routeOf("fe80::1"))
        assertEquals("tailscale", DiscoveredUi("a", "n", "100.64.0.9", 47215).route)
        assertEquals("lan", DiscoveredUi("a", "n", "192.168.1.9", 47215).route)
    }

    @Test fun addByAddressAcceptsATailscaleNameOrNumber() {
        val name = parseAddress("desk-pc.tailnet.ts.net")!!
        assertEquals("desk-pc.tailnet.ts.net", name.host)
        assertEquals(DEFAULT_PORT, name.port)
        val number = parseAddress("100.101.102.103:47300")!!
        assertEquals("100.101.102.103", number.host)
        assertEquals(47300, number.port)
        assertNull(parseAddress("https://desk-pc.tailnet.ts.net"))
        assertNull(parseAddress("desk pc"))
    }
}
