package app.keepfin.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

// Discovery (spec 2 and 9.1 #10): DNS-SD service `_keepfin._tcp` through the platform's NsdManager. The TXT record carries only `v`
// (protocol), `id` (the first 8 bytes of the device id, hex) and `n` (the name); the port comes from the SRV record. Everything learned
// here is a hint: the pin decides who a device is. The multicast lock is held only while the Add a device page is browsing.

class Discovered(val hint: String, val name: String, val host: String, val port: Int, var paired: Boolean)

interface NetDiscovery {
    /** Publishes this device. */
    fun start()
    fun stop()

    /** Starts listening for other devices (while Add a device is open or a sync is due). */
    fun browse()
    fun stopBrowsing()
    fun list(): List<Discovered>

    /** The address people on the same network would use to reach this device. */
    fun primaryAddress(): String?

    /** A scan of the local network is running right now (the page says it is still looking). */
    val scanning: Boolean get() = false

    /** One scan of the local network now, even with nobody looking (a paired device answered at none of its addresses). Blocks. */
    fun lookOnce(): List<Discovered> = emptyList()
}

/** One network interface as discovery sees it: IPv4 addresses only, link-local and loopback addresses already left out. */
class Nic(val name: String, val up: Boolean, val loopback: Boolean, val addresses: List<String>)

/** The phone's interfaces, read from the system. */
fun systemNics(): List<Nic> {
    val out = ArrayList<Nic>()
    try {
        for (nic in NetworkInterface.getNetworkInterfaces().toList()) {
            if (nic.isVirtual) continue
            val v4 = nic.inetAddresses.toList().filter { it is Inet4Address && !it.isLinkLocalAddress && !it.isLoopbackAddress }.mapNotNull { it.hostAddress }
            out += Nic(nic.name, nic.isUp, nic.isLoopback, v4)
        }
    } catch (e: Exception) {
        // no interfaces to list
    }
    return out
}

/** "a.b.c.d" as four numbers, or null when it is not a dotted IPv4 address. */
fun parseV4(host: String): IntArray? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val n = IntArray(4)
    for (i in 0 until 4) n[i] = parts[i].takeIf { it.isNotEmpty() && it.length <= 3 && it.all { c -> c in '0'..'9' } }?.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
    return n
}

private fun isPrivateV4(n: IntArray): Boolean = n[0] == 10 || (n[0] == 172 && n[1] in 16..31) || (n[0] == 192 && n[1] == 168) || (n[0] == 100 && n[1] in 64..127)

/** 100.64.0.0/10: the range Tailscale hands out (and carriers' shared addresses, which are never scanned). */
fun isTailscaleV4(host: String): Boolean = parseV4(host)?.let { it[0] == 100 && it[1] in 64..127 } == true

/** How an address is reached: "tailscale" for a 100.64/10 address or a name (MagicDNS), "lan" otherwise. */
fun routeOf(host: String): String = when {
    parseV4(host) != null -> if (isTailscaleV4(host)) "tailscale" else "lan"
    host.contains(':') -> "lan"
    else -> "tailscale"
}

/** Real, up, private-IPv4 addresses on this phone, best first (a 192.168/10./172.16 address beats a Tailscale 100.64 one). */
fun eligibleAddresses(nics: List<Nic> = systemNics()): List<String> {
    val out = ArrayList<String>()
    for (nic in nics) {
        if (!nic.up || nic.loopback) continue
        for (a in nic.addresses) if (parseV4(a)?.let(::isPrivateV4) == true) out += a
    }
    return out.distinct().sortedBy { if (isTailscaleV4(it)) 1 else 0 }
}

/** Addresses of the local network only (what the scan walks): eligible and not Tailscale. */
fun lanAddresses(nics: List<Nic>): List<String> = eligibleAddresses(nics).filter { !isTailscaleV4(it) }

/** Tailscale is on when an up interface holds a 100.64.0.0/10 address (the Tailscale VPN); returns that address. */
fun detectTailscale(nics: List<Nic>): String? {
    for (nic in nics) {
        if (!nic.up || nic.loopback) continue
        nic.addresses.firstOrNull { isTailscaleV4(it) }?.let { return it }
    }
    return null
}
class NsdDiscovery(
    private val context: Context,
    private val deviceId: () -> String,
    private val name: () -> String,
    private val port: () -> Int,
    private val isPaired: (hint: String) -> Boolean,
    private val changed: () -> Unit,
    private val log: NetLog? = null,
) : NetDiscovery {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val found = ConcurrentHashMap<String, Discovered>()
    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var lock: WifiManager.MulticastLock? = null
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    override fun primaryAddress(): String? = eligibleAddresses().firstOrNull()

    @Synchronized override fun start() {
        if (registration != null) return
        val info = NsdServiceInfo().apply {
            serviceName = "Keepfin-${deviceId().take(8)}"
            serviceType = SERVICE_TYPE
            port = this@NsdDiscovery.port()
            setAttribute("v", "1")
            setAttribute("id", deviceId().take(16))
            setAttribute("n", name().take(40))
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { log?.log("discovery could not publish ($errorCode)") }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
            registration = listener
        } catch (e: Exception) {
            log?.log("discovery could not start: ${e.message}")
        }
    }

    @Synchronized override fun stop() {
        stopBrowsing()
        registration?.let { runCatching { nsd.unregisterService(it) } }
        registration = null
    }

    @Synchronized override fun browse() {
        if (discovery != null) return
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            lock = wifi?.createMulticastLock("keepfin-discovery")?.apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) {
            // without the lock some phones miss answers; by-address still works
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { log?.log("discovery could not browse ($errorCode)") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) { enqueue(serviceInfo) }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                if (found.remove(serviceInfo.serviceName) != null) changed()
            }
        }
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            discovery = listener
        } catch (e: Exception) {
            log?.log("discovery could not browse: ${e.message}")
            releaseLock()
        }
    }

    @Synchronized override fun stopBrowsing() {
        discovery?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discovery = null
        synchronized(queue) { queue.clear() }
        found.clear()
        releaseLock()
    }

    private fun releaseLock() {
        runCatching { lock?.takeIf { it.isHeld }?.release() }
        lock = null
    }

    private fun enqueue(info: NsdServiceInfo) {
        synchronized(queue) { queue.add(info) }
        next()
    }

    @Suppress("DEPRECATION")
    private fun next() {
        val info = synchronized(queue) {
            if (resolving) return
            val n = queue.poll() ?: return
            resolving = true
            n
        }
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { done() }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    try { accept(serviceInfo) } finally { done() }
                }
            })
        } catch (e: Exception) {
            done()
        }
    }

    private fun done() {
        synchronized(queue) { resolving = false }
        next()
    }

    @Suppress("DEPRECATION")
    private fun accept(info: NsdServiceInfo) {
        fun txt(key: String): String? = info.attributes[key]?.toString(Charsets.UTF_8)
        val hint = txt("id")?.lowercase().orEmpty()
        if (!Regex("^[0-9a-f]{16}$").matches(hint) || hint == deviceId().take(16)) return
        if (txt("v") != "1") return
        val host = info.host?.hostAddress?.substringBefore('%') ?: return
        if (info.port !in 1..65535) return
        val name = txt("n")?.trim()?.take(40)?.ifEmpty { null } ?: info.serviceName
        found[info.serviceName] = Discovered(hint, name, host, info.port, isPaired(hint))
        changed()
    }

    override fun list(): List<Discovered> = found.values.map { Discovered(it.hint, it.name, it.host, it.port, isPaired(it.hint)) }
}
