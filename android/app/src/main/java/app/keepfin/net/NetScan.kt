package app.keepfin.net

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject

// Discovery, second half (dev-docs/plans/DEVICES-DISCOVERY.md D2): many routers and phones drop multicast, so DNS-SD alone finds nothing. The
// scan walks every host of the local /24 networks (never Tailscale's 100.64/10, never this phone), tries a plain TCP connect to the Keepfin
// port (400 ms, 48 at a time) and asks only the hosts with the port open for `GET /v1/announce` (1.5 s) over TLS. What it learns is a
// hint, exactly like a DNS-SD answer: the pin decides who a device is.

/** What `/v1/announce` says: the same facts as the DNS-SD TXT record. */
class Announce(val id: String, val name: String, val platform: String, /** A typed pairing code is showing on that device (it does not say which). */ val code: Boolean = false)

/** A device the scan found. */
class ScanFound(val hint: String, val name: String, val host: String, val port: Int, val platform: String)

/** The two network steps of a scan, replaced by a fake in tests. Both block. */
interface ScanConnector {
    /** Whether a TCP connection to [host]:[port] opens within [timeoutMs]. */
    fun portOpen(host: String, port: Int, timeoutMs: Int): Boolean

    /** `GET /v1/announce` over TLS with this device's identity; null when the host is not a Keepfin device (or does not answer in time). */
    fun announce(host: String, port: Int, timeoutMs: Int): Announce?
}

const val SCAN_CONNECT_MS = 400
const val SCAN_ANNOUNCE_MS = 1500
const val SCAN_CONCURRENCY = 48
const val SCAN_EVERY_MS = 20_000L

/** Reads an announce answer; null for anything that is not exactly what the protocol says (version 1, a 16-hex id, a name, a platform). */
fun parseAnnounce(o: JSONObject): Announce? {
    if (o.optInt("v") != 1) return null
    val id = o.optString("id").lowercase()
    if (!Regex("^[0-9a-f]{16}$").matches(id)) return null
    val name = o.optString("n").trim().take(40)
    val platform = o.optString("p").trim().take(20)
    return Announce(id, name, platform, o.optInt("c") == 1)
}

/** The hosts of the /24 around each address in [local], without this phone's own addresses, .0, .255 and anything in 100.64/10. */
fun scanTargets(local: List<String>, own: Set<String> = local.toSet()): List<String> {
    val out = LinkedHashSet<String>()
    for (address in local) {
        val n = parseV4(address) ?: continue
        if (isTailscaleV4(address)) continue
        for (last in 1..254) {
            val host = "${n[0]}.${n[1]}.${n[2]}.$last"
            if (host in own || isTailscaleV4(host)) continue
            out += host
        }
    }
    return out.toList()
}

class NetScanner(
    /** This phone's local-network addresses (not Tailscale's); empty when there is no Wi-Fi or Ethernet. */
    private val addresses: () -> List<String>,
    private val connector: ScanConnector,
    /** The first 16 hex of this device's id: an answer with it is this phone itself. */
    private val selfHint: () -> String,
    /** Every address of this phone (to skip), Tailscale's included. */
    private val ownAddresses: () -> Set<String> = { addresses().toSet() },
    private val port: Int = DEFAULT_PORT,
    private val concurrency: Int = SCAN_CONCURRENCY,
    private val connectMs: Int = SCAN_CONNECT_MS,
    private val announceMs: Int = SCAN_ANNOUNCE_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** One full pass. Cancelling the caller stops it; hosts already being probed finish their short timeouts. */
    suspend fun scanOnce(): List<ScanFound> = withContext(dispatcher) {
        val hosts = scanTargets(addresses(), ownAddresses())
        if (hosts.isEmpty()) return@withContext emptyList()
        val gate = Semaphore(concurrency)
        val self = selfHint()
        coroutineScope {
            hosts.map { host ->
                async {
                    gate.withPermit {
                        if (!isActive) return@withPermit null
                        val open = try { connector.portOpen(host, port, connectMs) } catch (e: Exception) { false }
                        if (!open) return@withPermit null
                        val a = try { connector.announce(host, port, announceMs) } catch (e: Exception) { null } ?: return@withPermit null
                        if (a.id == self) null else ScanFound(a.id, a.name.ifEmpty { "Keepfin device" }, host, port, a.platform)
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }
}

/** The real connector: a socket with a short timeout, then the pinned-free TLS client with this device's key. */
class SocketScanConnector(private val identity: () -> DeviceIdentity) : ScanConnector {
    override fun portOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (e: Exception) {
        false
    }

    override fun announce(host: String, port: Int, timeoutMs: Int): Announce? {
        val client = PeerClient(ClientOptions(identity(), host, port, null, timeoutMs.toLong()))
        return try {
            parseAnnounce(client.json("GET", "/v1/announce", timeoutMs = timeoutMs.toLong()))
        } catch (e: Exception) {
            null
        } finally {
            client.close()
        }
    }
}

/**
 * DNS-SD and the scan as one [NetDiscovery]: results merge by id hint and the DNS-SD address wins when both saw the device. The scan runs
 * only while [browse] is on (the Devices page or Add a device is open): at once, then every [everyMs].
 */
class CombinedDiscovery(
    private val nsd: NetDiscovery,
    private val scanner: NetScanner,
    private val isPaired: (hint: String) -> Boolean,
    private val changed: () -> Unit,
    /** False without Wi-Fi or Ethernet (mobile data only): no scan then. */
    private val allowed: () -> Boolean = { true },
    private val everyMs: Long = SCAN_EVERY_MS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val log: NetLog? = null,
) : NetDiscovery {
    private val scanned = ConcurrentHashMap<String, ScanFound>()
    @Volatile private var job: Job? = null
    @Volatile private var scope: CoroutineScope? = null
    @Volatile override var scanning: Boolean = false
        private set

    override fun start() = nsd.start()

    override fun stop() {
        stopBrowsing()
        nsd.stop()
    }

    override fun primaryAddress(): String? = nsd.primaryAddress()

    @Synchronized override fun browse() {
        nsd.browse()
        if (job?.isActive == true) return
        val s = CoroutineScope(SupervisorJob() + dispatcher)
        scope = s
        job = s.launch {
            while (isActive) {
                if (allowed()) {
                    scanning = true
                    changed()
                    try {
                        val found = scanner.scanOnce()
                        val before = scanned.keys.toSet()
                        scanned.clear()
                        for (f in found) scanned[f.hint] = f
                        if (before != scanned.keys) changed()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log?.log("scan failed: ${e.javaClass.simpleName}")
                    } finally {
                        scanning = false
                    }
                    changed()
                } else if (scanned.isNotEmpty()) {
                    scanned.clear()
                    changed()
                }
                delay(everyMs)
            }
        }
    }

    @Synchronized override fun stopBrowsing() {
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        scanned.clear()
        scanning = false
        nsd.stopBrowsing()
    }

    /** One pass of the scan (not on mobile data only). While the page looks too, what it finds joins the list. */
    override fun lookOnce(): List<Discovered> {
        if (!allowed()) return emptyList()
        val found = try {
            kotlinx.coroutines.runBlocking { scanner.scanOnce() }
        } catch (e: Exception) {
            log?.log("lookup scan failed: ${e.javaClass.simpleName}")
            return emptyList()
        }
        if (job?.isActive == true && found.any { !scanned.containsKey(it.hint) }) { for (f in found) scanned[f.hint] = f; changed() }
        return found.map { Discovered(it.hint, it.name, it.host, it.port, isPaired(it.hint)) }
    }

    override fun list(): List<Discovered> {
        val byHint = LinkedHashMap<String, Discovered>()
        for (d in nsd.list()) byHint[d.hint] = d
        for (f in scanned.values) if (f.hint !in byHint) byHint[f.hint] = Discovered(f.hint, f.name, f.host, f.port, isPaired(f.hint))
        return byHint.values.toList()
    }
}
