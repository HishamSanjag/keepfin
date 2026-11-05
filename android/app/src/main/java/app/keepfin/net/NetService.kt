package app.keepfin.net

import app.keepfin.core.net.NetAction
import app.keepfin.core.net.PairCode
import app.keepfin.core.net.defaultGrants
import app.keepfin.core.net.parsePairCode
import app.keepfin.core.net.parsePairQr
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.MemberGrant
import app.keepfin.folder.writeLocalAtomic
import java.io.File
import java.io.IOException
import java.net.BindException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

// Devices on the network, as one service (dev-docs/plans/KEEPFIN-NETWORK-SPEC.md): this device's identity, the server, discovery, pairing,
// the paired devices and a link to each, and the operations Settings -> Devices asks for. Port of gui/desktop/net/service.ts. Free of
// Android imports; the app passes the platform pieces in ([NetworkProbe], [NetDiscovery], the library list). Nothing starts listening
// until the person turns devices on (or devices were on before).

/** What the service needs to know about the phone's connection. */
interface NetworkProbe {
    /** Any network at all (a Wi-Fi, mobile data, a VPN). */
    fun isUp(): Boolean

    /** The default network is metered (mobile data, or a hotspot): files wait for Wi-Fi unless the peer allows mobile files. */
    fun isMetered(): Boolean

    /** Called when the default network, or its addresses, changed (Wi-Fi switched, a VPN or Tailscale came up). Returns the way to stop. */
    fun onChange(listener: () -> Unit): () -> Unit

    /** A Wi-Fi or Ethernet connection is up (a phone on mobile data alone has none: nothing is scanned then). */
    fun hasLan(): Boolean = isUp()

    /** The names of the Wi-Fi/Ethernet interfaces, so a scan never walks the mobile network; null when unknown (tests: every private interface). */
    fun lanInterfaces(): Set<String>? = null
}

object AlwaysOnNetwork : NetworkProbe {
    override fun isUp() = true
    override fun isMetered() = false
    override fun onChange(listener: () -> Unit): () -> Unit = {}
}

class NetServiceDeps(
    val deviceDir: File,
    val identity: DeviceIdentity,
    val libraries: () -> List<NetLibrary>,
    val now: () -> Long = { System.currentTimeMillis() },
    val limits: Limits = Limits(),
    /** 47215 by default. A conflict is reported, never worked around (spec 9.1 #10). Tests pass 0. */
    val port: Int = DEFAULT_PORT,
    val network: NetworkProbe = AlwaysOnNetwork,
    /** Creates discovery once the port is known; null: no multicast at all (tests). */
    val discovery: ((port: Int, changed: () -> Unit, isPaired: (String) -> Boolean) -> NetDiscovery)? = null,
    val log: NetLog? = null,
    /** The host a QR code names (tests: loopback). By default the first eligible network address. */
    val advertiseHost: () -> String? = { eligibleAddresses().firstOrNull() },
    /** The phone's interfaces (tests pass a fixed list): Tailscale is detected from them, and `hello` names their addresses. */
    val nics: () -> List<Nic> = { systemNics() },
    val backoffMinMs: Long = 2000,
    val backoffMaxMs: Long = 30 * 60_000L,
    val leaseMs: Long = PeerExecutorImpl.LEASE_MS,
    /** Downloads other devices ask for ("Download on..."): the queue behind it. Absent: this phone takes none. */
    val jobs: JobPort? = null,
    val incomingRows: (originOf: (String) -> String?) -> List<IncomingJobRow> = { emptyList() },
    /** Replaces the link check of incoming jobs (tests). */
    val checkUrl: ((String) -> String)? = null,
    /** A library was taken in from a paired device: make it a library profile of this phone. */
    val adoptJoined: ((Joined, treeUri: String) -> Unit)? = null,
    val minFreeBytes: Long = 512L * 1024 * 1024,
    /** Start or stop the "Keep this phone reachable" foreground service. */
    val keepReachableChanged: ((Boolean) -> Unit)? = null,
    val connectTimeoutMs: Long = 6000,
    val notifyDelayMs: Long = 2500,
    /** The asking side retries a start the other device answered with "not pairing" every this long, for [pairWaitMs] (tests shorten both). */
    val pairRetryMs: Long = 2500,
    val pairWaitMs: Long = PAIRING_WINDOW_MS,
    /** A typed code with no device in sight yet looks for devices this long (tests shorten it). */
    val codeSearchMs: Long = 4_000,
    /** A paired device that answers at none of its addresses makes the phone scan the network, at most once in this long (as Windows). */
    val lookupEveryMs: Long = 60_000,
    /** The exchange itself (tests replace it; the app runs [Initiator.askToPair]). */
    val askToPair: (AskOptions) -> PairPeerInfo = { Initiator.askToPair(it) },
    /** Called whenever this device is the one being asked to pair (null: nothing is). The app shows it over every screen. */
    val incomingPairing: ((PairingUi?) -> Unit)? = null,
)

private class Asking(val view: PairingState, var decision: ((Boolean) -> Unit)? = null) {
    @Volatile var abort: (() -> Unit)? = null
    val cancelLatch = java.util.concurrent.CountDownLatch(1)
    @Volatile var cancelled = false
    fun cancel() { cancelled = true; cancelLatch.countDown() }
}

private class PairingState(
    val id: String,
    val method: String,
    var stage: String,
    var peerName: String,
    var peerFingerprint: String = "",
    var sas: String? = null,
    var error: String? = null,
    var expiresAt: Long,
)

private class JoinState(val deviceId: String, val deviceName: String, val libraryId: String, val name: String, @Volatile var stage: String = "folder", @Volatile var segments: Int = 0, @Volatile var error: String? = null, @Volatile var resumable: Boolean = false, val cancel: AtomicBoolean = AtomicBoolean(false))

class NetService(private val deps: NetServiceDeps) {
    val peers: PeerStore = PeerStore.open(File(deps.deviceDir, "net"))
    val identity: DeviceIdentity get() = deps.identity
    val outgoing: OutgoingJobs = OutgoingJobs.open(File(File(deps.deviceDir, "net"), "outgoing-jobs.json"), deps.now)
    val jobsHost: JobsHost? = deps.jobs?.let { port ->
        JobsHost.open(
            JobsHostOptions(
                file = File(File(deps.deviceDir, "net"), "jobs.json"), port = port, now = deps.now, checkUrl = deps.checkUrl ?: { UrlGuard.guard(it) },
                notify = { peerId, message -> server?.sendTo(peerId, message) }, minFreeBytes = deps.minFreeBytes,
            ),
        )
    }
    val pairing: PairingHost = PairingHost(
        PairingHostOptions(
            identity = { deps.identity }, limits = deps.limits, now = deps.now, libraries = { deps.libraries().filter { it.isOpen }.map { PairLibraryRef(it.libraryId, it.actor()) } },
            onPaired = { peer, method -> onPaired(peer, method, peer.address, null) }, changed = { changed() },
        ),
    )

    @Volatile var server: NetServer? = null
        private set
    @Volatile var discovery: NetDiscovery? = null
        private set
    val links = ConcurrentHashMap<String, PeerLink>()
    private val executors = ConcurrentHashMap<String, PeerExecutorImpl>()
    private val stateFile = File(File(deps.deviceDir, "net"), "state.json")
    @Volatile private var enabled = false
    @Volatile private var keepReachable = false
    @Volatile private var port: Int? = null
    @Volatile private var portError: String? = null
    @Volatile private var asking: Asking? = null
    @Volatile private var qr: QrUi? = null
    @Volatile private var looking = false
    private val lookupLock = Any()
    private var lastLookup = Long.MIN_VALUE / 2
    @Volatile private var lookedUp: List<Discovered> = emptyList()

    /**
     * A sync found no address that answers (the device may have a new one): scan the network once, at most once a minute for all
     * devices together, and answer where this one was seen. Links that ask meanwhile get the last scan's answer.
     */
    private fun lookupFor(peer: Peer): List<Address> {
        val d = discovery ?: return emptyList()
        synchronized(lookupLock) {
            if (deps.now() - lastLookup >= deps.lookupEveryMs) {
                lastLookup = deps.now()
                lookedUp = d.lookOnce()
            }
        }
        return (d.list() + lookedUp).filter { peer.deviceId.startsWith(it.hint) }.map { Address(it.host, it.port) }.distinct()
    }
    private val notes = java.util.Collections.synchronizedMap(LinkedHashMap<String, String>())
    private val libraryInfo = ConcurrentHashMap<String, Pair<Boolean, Boolean>>()
    private val offers = ConcurrentHashMap<String, List<Pair<String, String>>>()
    /** Why a connected device offers nothing (from its last hello): "none" (no Keepfin folder), "held" (only this phone's libraries), "denied". */
    private val offerGaps = ConcurrentHashMap<String, String>()

    fun offerGap(deviceId: String): String? = offerGaps[deviceId]
    @Volatile private var join: JoinState? = null
    private val playTokens = ConcurrentHashMap<String, PlayToken>()
    private val unwatch = ArrayList<() -> Unit>()
    private var unwatchNetwork: (() -> Unit)? = null
    private val closed = AtomicBoolean(false)
    private val timer = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "keepfin-net-service").also { it.isDaemon = true } }.apply { removeOnCancelPolicy = true }
    private val work = Executors.newCachedThreadPool { r -> Thread(r, "keepfin-net-work").also { it.isDaemon = true } }
    // a state push is queued (changed() coalesces); no lock, so a call holding the service's lock never holds up the page
    private val pushQueued = AtomicBoolean(false)
    private val _state = MutableStateFlow(computeState())
    val state: StateFlow<DevicesState> get() = _state

    private class PlayToken(val deviceId: String, val libraryId: String, val hex: String, val size: Long, val name: String, val expires: Long, val allowMetered: Boolean = false)

    init {
        val saved = try {
            JSONObject(stateFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null // a first start, or a file that cannot be read: devices are off
        }
        keepReachable = saved?.optBoolean("keepReachable") == true
        if (saved?.optBoolean("enabled") == true) {
            try {
                enable()
            } catch (e: Exception) {
                // enable() left devices off (never half on and silent); the switch starts them again
                deps.log?.log("devices could not start: $e")
            }
        }
    }

    private fun saveState() {
        try {
            writeLocalAtomic(stateFile, JSONObject().put("enabled", enabled).put("keepReachable", keepReachable).toString().toByteArray(Charsets.UTF_8))
        } catch (e: Exception) {
            deps.log?.log("devices state could not be saved: ${e.message}")
        }
    }

    // ---- switching on and off ------------------------------------------------------------------------------------------------

    val isEnabled: Boolean get() = enabled
    val listeningPort: Int? get() = port
    val isKeepReachable: Boolean get() = keepReachable

    /** Starts the server, discovery and the links; makes each library's membership root when it is first paired. */
    @Synchronized fun enable() {
        // already on: the page may still show an older state, so it is pushed again
        if (enabled && server != null) return changed()
        enabled = true
        saveState()
        try {
            startRuntime()
        } catch (e: Exception) {
            // startRuntime stopped what it had started: devices show off now, and the switch (or the next start, as the choice is kept) tries again
            enabled = false
            changed()
            throw e
        }
        refreshLibraryInfoAsync()
        changed()
    }

    @Synchronized private fun startRuntime() {
        if (server != null) return
        val srv = NetServer(
            NetServerDeps(
                identity = { deps.identity }, peers = peers, libraries = { deps.libraries() }, executor = { id -> executorFor(id) }, pairing = pairing,
                cacheDir = File(deps.deviceDir, "net-cache"), now = deps.now, limits = deps.limits, log = deps.log,
                seen = { peer, address -> peers.touch(peer.deviceId, deps.now(), address, peer.lastPort) },
                jobs = jobsHost?.let { host -> { caller, body -> host.handle(caller.deviceId, caller.peer?.name ?: "A device", body).let { Answer(it.status, it.body) } } },
                jobSpaces = deps.jobs?.let { port -> { port.spaces() } },
                join = { caller, libraryId, body -> joinRequest(caller.deviceId, libraryId, body) },
                addresses = { eligibleAddresses(deps.nics()) },
            ),
        )
        srv.notifyDelayMs = deps.notifyDelayMs
        try {
            val acceptor = TlsAcceptor.bind(Tls.serverContext(deps.identity), deps.port)
            srv.start(acceptor)
            port = srv.listeningPort
            portError = null
            server = srv
        } catch (e: IOException) {
            portError = if (e is BindException || e.message?.contains("in use", true) == true) {
                "Another program is using port ${deps.port}. Close it, then turn devices off and on again. Keepfin keeps this port fixed so other devices can find you."
            } else "Devices could not start (${e.message})."
            srv.close()
            port = null
            return
        }
        try {
            val d = deps.discovery?.invoke(port!!, { changed() }) { hint -> peers.list().any { it.deviceId.startsWith(hint) } }
            discovery = d
            d?.start()
            if (looking) d?.browse()
            for (peer in peers.list()) startLink(peer)
            for (lib in deps.libraries()) watchLibrary(lib)
            unwatchNetwork = deps.network.onChange { kickAll(); changed() }
        } catch (e: Exception) {
            // never half on (the server up without discovery, links or library updates): all of it stops, and the next start begins clean
            runCatching { stopRuntime() }
            throw e
        }
    }

    private fun watchLibrary(lib: NetLibrary) {
        srvAttach(lib)
        synchronized(unwatch) { unwatch += lib.onChange { libraryChanged(); scheduleInfoRefresh() } }
    }

    private fun srvAttach(lib: NetLibrary) {
        server?.attach(lib)
    }

    /** A library was opened after the service started (or switched): it is served and synced from now on. */
    fun libraryOpened(lib: NetLibrary) {
        if (server == null) return
        watchLibrary(lib)
        for (link in links.values) link.kick()
        refreshLibraryInfoAsync()
    }

    @Synchronized fun disable() {
        if (!enabled) return
        enabled = false
        saveState()
        pairing.close()
        stopRuntime()
        changed()
    }

    /** The app went to the background without "Keep this phone reachable": the server and the links stop until it is opened again. */
    @Synchronized fun pause() {
        if (keepReachable) return
        pairing.close()
        stopRuntime()
        changed()
    }

    /** The app is open again (or the phone is kept reachable): everything that was on runs. */
    @Synchronized fun resume() {
        if (enabled && server == null && !closed.get()) {
            startRuntime()
            refreshLibraryInfoAsync()
            changed()
        }
    }

    @Synchronized private fun stopRuntime() {
        for (link in links.values) link.stop()
        links.clear()
        synchronized(unwatch) { for (off in unwatch) off(); unwatch.clear() }
        unwatchNetwork?.invoke()
        unwatchNetwork = null
        discovery?.stop()
        discovery = null
        server?.close()
        server = null
        port = null
    }

    @Synchronized fun setKeepReachable(value: Boolean) {
        keepReachable = value
        saveState()
        deps.keepReachableChanged?.invoke(value && enabled)
        changed()
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        jobsHost?.close()
        join?.cancel?.set(true)
        pairing.shutdown()
        stopRuntime()
        timer.shutdownNow()
        work.shutdownNow()
    }

    private fun executorFor(libraryId: String): PeerExecutorPort? {
        val lib = deps.libraries().firstOrNull { it.libraryId == libraryId && it.isOpen } ?: return null
        return executors.getOrPut(libraryId) { PeerExecutorImpl(lib.folder, deps.now, deps.identity.name, deps.leaseMs) }
    }

    // ---- links ---------------------------------------------------------------------------------------------------------------

    private fun startLink(peer: Peer) {
        if (links.containsKey(peer.deviceId) || server == null) return
        val link = PeerLink(
            LinkDeps(
                identity = { deps.identity }, peers = peers, peerId = peer.deviceId, libraries = { deps.libraries().filter { it.isOpen } },
                hints = { discovery?.list().orEmpty().filter { peer.deviceId.startsWith(it.hint) }.map { Address(it.host, it.port) } },
                lookup = { lookupFor(peer) },
                now = deps.now, log = deps.log, changed = { changed() }, synced = { refreshLibraryInfoAsync() },
                backoffMinMs = deps.backoffMinMs, backoffMaxMs = deps.backoffMaxMs,
                connected = { client, hello -> onConnected(peer.deviceId, client, hello) },
                event = { message -> onPeerEvent(peer.deviceId, message) },
                unreachable = { peers.setReachable(peer.deviceId, false, deps.now()) },
                reachable = { peers.setReachable(peer.deviceId, true, deps.now()) },
                networkUp = { deps.network.isUp() }, connectTimeoutMs = deps.connectTimeoutMs,
            ),
        )
        links[peer.deviceId] = link
        link.start()
    }

    /** Stops syncing with one device without forgetting it (a network cut, in tests). */
    fun pauseLink(deviceId: String) {
        links.remove(deviceId)?.stop()
        changed()
    }

    fun resumeLink(deviceId: String) {
        val peer = peers.get(deviceId)
        if (peer != null && enabled) startLink(peer)
    }

    private val infoRefreshQueued = AtomicBoolean(false)

    /** What the page says about a library (signed, administrator) follows what arrives, at most twice a second. */
    private fun scheduleInfoRefresh() {
        if (closed.get() || !infoRefreshQueued.compareAndSet(false, true)) return
        try {
            timer.schedule({ infoRefreshQueued.set(false); refreshLibraryInfoAsync() }, 500, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            infoRefreshQueued.set(false)
        }
    }

    private fun libraryChanged() {
        for (link in links.values) link.kick()
    }

    /** The network changed (a VPN came up, Wi-Fi switched): try everyone now. */
    fun kickAll() {
        for (link in links.values) link.kick()
    }

    // ---- pairing -------------------------------------------------------------------------------------------------------------

    /** "Add a device" is open: this phone answers pairing requests for 2 minutes and looks for others. */
    fun openPairing() {
        requireRunning()
        pairing.open()
        discovery?.browse()
        changed()
    }

    fun closePairing() {
        asking?.takeIf { it.view.stage == "waitingForPeer" }?.cancel()
        pairing.close()
        qr = null
        if (!looking) discovery?.stopBrowsing()
        changed()
    }

    /** The Devices page is open: DNS-SD and the scan look for other devices (at once, then every 20 seconds) until [stopLooking]. */
    fun startLooking() {
        looking = true
        discovery?.browse()
        changed()
    }

    fun stopLooking() {
        looking = false
        if (pairing.windowEnd == null) discovery?.stopBrowsing()
        changed()
    }

    /** A one-use pairing link and its expiry; the page draws it as a QR code. */
    fun showQr() {
        requireRunning()
        val host = deps.advertiseHost() ?: "localhost"
        val (link, expiresAt, code) = pairing.makeQr(host, port!!)
        qr = QrUi(link, expiresAt, code)
        discovery?.browse()
        changed()
    }

    class Target(val host: String, val port: Int, val pin: String?, val token: ByteArray?, val knownName: String? = null)

    private fun parseTarget(text: String): Target {
        guardAsk()
        if (text.trim().startsWith("keepfin://")) {
            val link = parsePairQr(text.trim()) ?: throw IllegalArgumentException("That pairing code is not valid.")
            return Target(link.host, link.port, link.pin.joinToString("") { "%02x".format(it) }, link.token, knownNameOf(link.host))
        }
        val parsed = parseAddress(text) ?: throw IllegalArgumentException("That is not an address. Use a name or number, like 192.168.1.20 or my-pc.tailnet.ts.net, with :port if it is not $DEFAULT_PORT.")
        return Target(parsed.host, parsed.port, null, null, knownNameOf(parsed.host))
    }

    /** The name this device was found under, when the address is one discovery saw. */
    private fun knownNameOf(host: String): String? = discovery?.list().orEmpty().firstOrNull { it.host == host }?.name?.takeIf { it.isNotBlank() }

    /** The person typed an address, scanned a code or pasted a link. Returns once the address is known to be usable; the pairing goes on and the page follows it. */
    fun beginAsk(text: String) {
        val code = typedCode(text)
        if (code != null) {
            guardAsk()
            work.execute { runCatching { runAskCode(code) } }
            return
        }
        val target = parseTarget(text)
        work.execute { runCatching { runAsk(target) } }
    }

    /** Like [beginAsk], but returns when the pairing ended (paired or not). */
    fun ask(text: String) {
        val code = typedCode(text)
        if (code != null) {
            guardAsk()
            runAskCode(code)
        } else runAsk(parseTarget(text))
    }

    /** What was typed, when it is a pairing code (ten symbols, any case, with or without the dash). A link or an address is not. */
    private fun typedCode(text: String): PairCode? {
        val t = text.trim()
        return if (t.startsWith("keepfin://")) null else parsePairCode(t)
    }

    private fun guardAsk() {
        requireRunning()
        val a = asking
        if (a != null && a.view.stage !in setOf("done", "failed")) throw IllegalStateException("Another pairing is already in progress.")
    }

    /**
     * A typed code: ask the devices seen on this network and Tailscale, one try each. A device whose key does not start with the code's
     * 30 pin bits is dropped in the handshake, before a single request is written; one that does not say a code is showing gets no
     * /start. The first device that accepts the code is paired like a scanned QR. Nothing is retried.
     */
    private fun runAskCode(code: PairCode) {
        val view = PairingState(UUID.randomUUID().toString(), "qr", "connecting", "", expiresAt = deps.now() + PAIRING_WINDOW_MS)
        val a = Asking(view)
        asking = a
        changed()
        try {
            fun candidates() = discovery?.list().orEmpty().distinctBy { "${it.host}:${it.port}" }
            var found = candidates()
            if (found.isEmpty()) {
                // nobody is looking yet (the Devices page is closed): look for a moment
                discovery?.browse()
                var waited = 0L
                while (found.isEmpty() && !a.cancelled && waited < deps.codeSearchMs) {
                    a.cancelLatch.await(200, TimeUnit.MILLISECONDS)
                    waited += 200
                    found = candidates()
                }
            }
            var paired: Triple<PairPeerInfo, String, Int>? = null
            for (device in found) {
                if (a.cancelled) break
                var started = false
                try {
                    val peer = deps.askToPair(
                        AskOptions(
                            identity = deps.identity, host = device.host, port = device.port, pin = null, token = null, code = code,
                            libraries = deps.libraries().filter { it.isOpen }.map { PairLibraryRef(it.libraryId, it.actor()) },
                            connected = { name, fingerprint -> started = true; view.peerName = name; view.peerFingerprint = fingerprint; changed() },
                            decide = { info ->
                                view.peerName = info.peerName
                                view.peerFingerprint = info.peerFingerprint
                                view.expiresAt = info.expiresAt
                                view.stage = "waiting"
                                changed()
                                CompletableFuture.completedFuture(true) // the code named the key and the other device accepted it
                            },
                            connectTimeoutMs = minOf(deps.connectTimeoutMs, 2_500L),
                        ),
                    )
                    paired = Triple(peer, device.host, device.port)
                    break
                } catch (e: Exception) {
                    // before the other device accepted, a device that is not the one (wrong key, no code showing, unreachable) is skipped silently
                    if (started) throw e
                    if (e is AskError && e.code == "token") throw AskError("That code is not right or has expired.", "token")
                    if (e is AskError && (e.code == "rate" || e.code == "version")) throw e
                }
            }
            val (peer, host, port) = paired ?: throw AskError("No device showing this code was found. Check the code, and that both devices are on the same network or Tailscale.", "nocode")
            view.stage = "done"
            onPaired(peer, "qr", host, port)
        } catch (e: Exception) {
            if (a.cancelled) {
                if (asking === a) asking = null
            } else {
                view.stage = "failed"
                view.error = e.message ?: "Pairing failed."
            }
        } finally {
            changed()
        }
    }

    private fun runAsk(t: Target) {
        val view = PairingState(UUID.randomUUID().toString(), if (t.token != null) "qr" else "sas", "connecting", t.knownName ?: t.host, expiresAt = deps.now() + PAIRING_WINDOW_MS)
        val a = Asking(view)
        asking = a
        changed()
        try {
            val waitName = t.knownName ?: PairWait.FALLBACK_NAME
            val wait = PairWait(deps.pairRetryMs, deps.pairWaitMs, deps.now) { ms -> a.cancelLatch.await(ms, TimeUnit.MILLISECONDS) }
            val peer = wait.run(waitName, waiting = { view.stage = "waitingForPeer"; view.peerName = waitName; changed() }, cancelled = { a.cancelled }) {
                deps.askToPair(
                AskOptions(
                    identity = deps.identity, host = t.host, port = t.port, pin = t.pin, token = t.token,
                    libraries = deps.libraries().filter { it.isOpen }.map { PairLibraryRef(it.libraryId, it.actor()) },
                    connected = { name, fingerprint -> view.peerName = name; view.peerFingerprint = fingerprint; changed() },
                    abortHook = { a.abort = it },
                    decide = { info ->
                        val future = CompletableFuture<Boolean>()
                        view.peerName = info.peerName
                        view.peerFingerprint = info.peerFingerprint
                        view.expiresAt = info.expiresAt
                        if (info.method == "qr") {
                            view.stage = "waiting"
                            future.complete(true) // the code in the QR already named the key
                        } else {
                            // both people confirm the numbers: this side signs only after its own person said they match
                            view.stage = "comparing"
                            view.sas = info.sas
                            var answered = false
                            a.decision = { match ->
                                if (!answered) {
                                    answered = true
                                    view.stage = if (match) "waiting" else "failed"
                                    future.complete(match)
                                } else if (!match) {
                                    // Cancel while the other device decides: drop the connection, nothing is paired
                                    a.cancel()
                                    a.abort?.invoke()
                                    if (asking === a) asking = null
                                }
                                changed()
                            }
                        }
                        changed()
                        future
                    },
                ),
                )
            }
            if (a.cancelled) return
            view.stage = "done"
            onPaired(peer, if (t.token != null) "qr" else "sas", t.host, t.port)
        } catch (e: Exception) {
            if (a.cancelled) {
                if (asking === a) asking = null // cancelled by the person: nothing to show
            } else {
                view.stage = "failed"
                val who = view.peerName.ifBlank { "The other device" }
                view.error = when ((e as? AskError)?.code) {
                    "declined", "expired" -> "$who did not confirm, so nothing was paired."
                    else -> e.message ?: "Pairing failed."
                }
            }
        } finally {
            changed()
        }
    }

    /** The person answered the numbers screen: they match (true) or not (false); or approved/declined a QR pairing. */
    fun decide(id: String, match: Boolean) {
        val a = asking
        if (a != null && a.view.id == id) {
            a.decision?.invoke(match)
            return
        }
        pairing.decide(id, match)
    }

    /** "<name> connected" was not meant: removes the device the scanned code just paired (the same removal as Remove device) and closes the notice. */
    fun undoPair(deviceId: String): RemoveResult {
        val result = remove(deviceId)
        pairing.dismiss()
        changed()
        return result
    }

    fun dismissPairing() {
        val a = asking
        if (a != null && a.view.stage in setOf("done", "failed")) asking = null
        else if (a != null && a.view.stage == "waitingForPeer") a.cancel()
        else if (a != null && a.view.stage == "waiting" && a.view.sas != null) a.decision?.invoke(false)
        // the other device asked: its finished result is closed here too (OK used to do nothing on the answering side)
        else if (a == null) pairing.dismiss()
        changed()
    }

    /** Both sides confirmed. Saves the pin and starts the link; the library side (membership) follows in the background. */
    private fun onPaired(info: PairPeerInfo, method: String, host: String?, port: Int?) {
        val shared = deps.libraries().filter { lib -> lib.isOpen && info.libraries.any { it.libraryId == lib.libraryId } }
        val peer = Peer(
            deviceId = info.deviceId, spki = Base64.getEncoder().encodeToString(info.spki), name = info.name, platform = info.platform, pairedAt = deps.now(), lastSeen = deps.now(),
            libraries = shared.map { lib -> PeerLibrary(lib.libraryId, info.libraries.first { it.libraryId == lib.libraryId }.actor, defaultGrants()) },
            lastHost = host, lastPort = if (host != null) (port ?: DEFAULT_PORT) else null,
        )
        peers.put(peer)
        if (enabled) startLink(peers.get(peer.deviceId)!!)
        qr = null
        work.execute { runCatching { completeMembership(peer.deviceId) }.onFailure { deps.log?.log("membership after pairing failed: ${it.message}") } }
        changed()
    }

    /**
     * The library side of a new pairing (spec 9.1 #1). Where nothing is enforced yet, the device with the lower device id writes the
     * genesis and the first grant in one segment, after taking in what the peer holds (so its history is frozen too); the other
     * device simply receives them. Where membership exists, an administrator device grants the new device's actor.
     */
    fun completeMembership(deviceId: String) {
        val link = links[deviceId]
        for (lib in deps.libraries()) {
            if (!lib.isOpen) continue
            val peer = peers.get(deviceId) ?: continue
            val entry = peer.libraries.firstOrNull { it.libraryId == lib.libraryId }
            if (entry == null || entry.actor.isEmpty()) continue // shared but not joined yet: the actor is granted when it joins
            try {
                var membership = lib.membership()
                val grant = MemberGrant(entry.actor, peer.spki, if (NetAction.MembershipAdmin in entry.actions) listOf("write", "admin") else listOf("write"))
                if (!membership.active) {
                    if (deps.identity.deviceId > peer.deviceId) continue // the other device writes the genesis; it reaches us by sync
                    link?.settled(15_000)
                    membership = lib.membership()
                    if (!membership.active) {
                        lib.ensureMembership(grant)
                        refreshLibraryInfo()
                        continue
                    }
                }
                if (membership.members[lib.actor()]?.isAdmin != true) {
                    note("admin:${lib.libraryId}", "${lib.name}: this phone is not an administrator, so ${peer.name} is not yet allowed to write to it. Pair it with the administrator device too.")
                    continue
                }
                val known = membership.members[entry.actor]
                val key = app.keepfin.core.net.deviceIdOf(Base64.getDecoder().decode(peer.spki))
                if (known != null && known.holds(key)) continue
                lib.grantMember(grant.actor, grant.spkiBase64, grant.perms)
            } catch (e: Exception) {
                note("grant:${lib.libraryId}", "${lib.name}: ${peer.name} could not be added to the library (${e.message}).")
            }
        }
        refreshLibraryInfo()
    }

    // ---- paired devices ------------------------------------------------------------------------------------------------------

    /** What this phone lets one paired device do with one library. */
    fun setAccess(deviceId: String, libraryId: String, actions: List<NetAction>) {
        val clean = actions.filter { it in GRANTABLE_ACTIONS }.toSet()
        val peer = peers.update(deviceId) { p -> p.libraries.firstOrNull { it.libraryId == libraryId }?.actions = clean } ?: throw IllegalStateException("That device is not paired.")
        if (deps.libraries().any { it.libraryId == libraryId } && NetAction.MembershipAdmin in clean) work.execute { runCatching { completeMembership(peer.deviceId) } }
        server?.sendTo(deviceId, JSONObject().put("t", "access"))
        changed()
    }

    fun setAllowMobileFiles(deviceId: String, allow: Boolean) {
        peers.update(deviceId) { it.allowMobileFiles = allow }
        changed()
    }

    /**
     * Forgets a device: its pin is deleted, its connections close, and in each shared library an administrator device revokes its actor
     * with a cutoff (its later operations are set aside, never applied). What it already copied stays with it.
     */
    fun remove(deviceId: String): RemoveResult {
        val peer = peers.get(deviceId) ?: throw IllegalStateException("That device is not paired.")
        server?.dropPeer(deviceId)
        links.remove(deviceId)?.stop()
        for (ex in executors.values) ex.release(deviceId)
        peers.remove(deviceId)
        offers.remove(deviceId)
        val revoked = ArrayList<String>()
        val notRevoked = ArrayList<String>()
        for (entry in peer.libraries) {
            val lib = deps.libraries().firstOrNull { it.libraryId == entry.libraryId && it.isOpen }
            if (lib == null || entry.actor.isEmpty()) continue
            try {
                val membership = lib.membership()
                if (!membership.active || membership.members[entry.actor] == null) continue
                lib.flush()
                val cutoff = SegmentFiles.vectorOf(lib.segments())[entry.actor] ?: 0L
                lib.revokeMember(entry.actor, cutoff)
                revoked += lib.name
            } catch (e: Exception) {
                notRevoked += lib.name
                note("revoke:${lib.libraryId}", "${lib.name}: ${peer.name} could not be revoked here (${e.message}). Remove it on the administrator device.")
            }
        }
        refreshLibraryInfoAsync()
        changed()
        return RemoveResult(revoked, notRevoked)
    }

    fun rename(name: String) {
        (deps.identity as? KeystoreIdentity)?.rename(name) ?: (deps.identity as? RenamableIdentity)?.rename(name)
        // connected devices read the hello again and show the new name now (the others at their next connection)
        for (p in peers.list()) server?.sendTo(p.deviceId, JSONObject().put("t", "hello"))
        changed()
    }

    // ---- what a connected peer tells us --------------------------------------------------------------------------------------

    /** A peer said hello: remember the libraries it offers that we do not hold, and deliver the downloads we asked it for. */
    private fun onConnected(deviceId: String, client: PeerClient, hello: HelloAnswer) {
        val held = deps.libraries().filter { it.isOpen }.map { it.libraryId }.toSet()
        offers[deviceId] = hello.libraries.filter { it.libraryId !in held && "metadata.read" in it.actions }.map { it.libraryId to it.name }
        offerGaps[deviceId] = hello.gap(held)
        jobsHost?.resend(deviceId)
        work.execute { runCatching { deliverJobs(deviceId, client) }.onFailure { deps.log?.log("jobs could not be delivered: ${it.message}") } }
        changed()
    }

    private fun onPeerEvent(deviceId: String, message: JSONObject) {
        if (message.optString("t") != "job") return
        val requestId = message.optString("requestId")
        val view = message.optJSONObject("view") ?: return
        val job = outgoing.get(requestId) ?: return
        if (job.deviceId != deviceId) return
        outgoing.applyView(requestId, RemoteJobView.of(view))
        changed()
    }

    // ---- joining a library (spec 9.4) ----------------------------------------------------------------------------------------

    /**
     * A paired device asks to join a library this phone holds and has shared with it. This phone must administer the library: it
     * grants the actor the joining device will write as (and writes the membership root first when the library was not signed yet).
     */
    private fun joinRequest(deviceId: String, libraryId: String, body: JSONObject): Answer {
        fun err(status: Int, code: String, message: String) = Answer(status, JSONObject().put("error", code).put("message", message))
        val actor = body.optString("actor").lowercase()
        if (!isActorId(actor)) return err(400, "actor", "The request needs an actor id.")
        val peer = peers.get(deviceId)
        val lib = deps.libraries().firstOrNull { it.libraryId == libraryId && it.isOpen }
        val entry = peer?.libraries?.firstOrNull { it.libraryId == libraryId }
        if (peer == null || lib == null || entry == null) return err(403, "library", "This library is not shared with you.")
        if (body.optBoolean("leave", false)) return leaveRequest(deviceId, lib, actor, entry.actor)
        if (entry.actor.isNotEmpty() && entry.actor != actor) return err(409, "actor", "This device already joined this library with another identity.")
        val perms = if (NetAction.MembershipAdmin in entry.actions) listOf("write", "admin") else listOf("write")
        try {
            val membership = lib.membership()
            if (!membership.active) lib.ensureMembership(MemberGrant(actor, peer.spki, perms))
            else {
                if (membership.members[lib.actor()]?.isAdmin != true) return err(409, "not-admin", "This device does not administer the library. Ask the device that does to share it.")
                val known = membership.members[actor]
                val key = app.keepfin.core.net.deviceIdOf(Base64.getDecoder().decode(peer.spki))
                if (!(known != null && known.holds(key))) lib.grantMember(actor, peer.spki, perms)
            }
        } catch (e: Exception) {
            return err(500, "grant", "The library could not add your device (${e.message}).")
        }
        peers.update(deviceId) { p -> p.libraries.firstOrNull { it.libraryId == libraryId }?.actor = actor }
        lib.flush()
        refreshLibraryInfoAsync()
        changed()
        return Answer(200, JSONObject().put("ok", true).put("libraryId", libraryId).put("name", lib.name).put("actor", lib.actor()))
    }

    /**
     * A device that combined its library into this one and then separated again leaves it (dev-docs/plans/LIBRARY-MERGE-CONTRACT.md section 7.6):
     * the actor it joined as is revoked here with a cutoff at what this phone holds of it, so the actor's later operations are never applied.
     * Needs this phone to administer the library. The same request and answer as the Windows app.
     */
    private fun leaveRequest(deviceId: String, lib: NetLibrary, actor: String, granted: String): Answer {
        fun err(status: Int, code: String, message: String) = Answer(status, JSONObject().put("error", code).put("message", message))
        if (granted.isEmpty() || granted != actor) return err(409, "actor", "That is not the identity this device granted.")
        try {
            val membership = lib.membership()
            if (!membership.active || membership.members[actor] == null) return Answer(200, JSONObject().put("ok", true).put("revoked", false))
            if (membership.members[lib.actor()]?.isAdmin != true) return err(409, "not-admin", "This device does not administer the library.")
            lib.flush()
            val cutoff = SegmentFiles.vectorOf(lib.segments())[actor] ?: 0L
            lib.revokeMember(actor, cutoff)
        } catch (e: Exception) {
            return err(500, "revoke", "The library could not revoke the device (${e.message}).")
        }
        peers.update(deviceId) { p -> p.libraries.firstOrNull { it.libraryId == lib.libraryId }?.actor = "" }
        refreshLibraryInfoAsync()
        changed()
        return Answer(200, JSONObject().put("ok", true).put("revoked", true))
    }

    /** Tells [deviceId], which granted [actor] in [libraryId], that this phone leaves the library (it revokes the actor). */
    fun leaveLibrary(deviceId: String, libraryId: String, actor: String): Boolean {
        val link = links[deviceId] ?: return false
        return try {
            link.withClient { client -> client.json("POST", "/v1/libraries/$libraryId/join", JSONObject().put("actor", actor).put("leave", true)).optBoolean("ok", false) } == true
        } catch (e: Exception) {
            false
        }
    }

    /** Shares a library of this phone with one paired device: it may then read and write its operations, and may join it. */
    fun shareLibrary(deviceId: String, libraryId: String) {
        deps.libraries().firstOrNull { it.libraryId == libraryId && it.isOpen } ?: throw IllegalStateException("That library is not open on this phone.")
        peers.update(deviceId) { p -> if (p.libraries.none { it.libraryId == libraryId }) p.libraries = p.libraries + PeerLibrary(libraryId, "", defaultGrants()) } ?: throw IllegalStateException("That device is not paired.")
        links[deviceId]?.kick()
        server?.sendTo(deviceId, JSONObject().put("t", "access"))
        server?.announce()
        changed()
    }

    /**
     * "Get this library": makes a Keepfin folder in [target] for a library a paired device shares, takes in its segments and (through
     * `adoptJoined`) makes it a library of this phone. An interrupted join continues when the same folder is chosen again.
     */
    fun joinLibrary(deviceId: String, libraryId: String, target: FolderAccess, treeUri: String): Joined {
        requireRunning()
        val current = join
        if (current != null && current.stage !in setOf("done", "failed")) throw IllegalStateException("Another library is being taken in. Wait for it to finish.")
        val peer = peers.get(deviceId) ?: throw IllegalStateException("That device is not paired.")
        val offer = offers[deviceId]?.firstOrNull { it.first == libraryId } ?: throw IllegalStateException("That device is not offering this library right now. Make sure it is turned on and connected.")
        if (deps.libraries().any { it.libraryId == libraryId }) throw IllegalStateException("This phone already holds that library.")
        val link = links[deviceId]
        val state = JoinState(deviceId, peer.name, libraryId, offer.second)
        join = state
        changed()
        try {
            val joined = link?.withClient { client ->
                JoinRunner.run(client, deviceId, libraryId, offer.second, target, now = deps.now, cancelled = { state.cancel.get() }) { p ->
                    state.stage = p.stage
                    state.segments = p.segments
                    changed()
                }
            } ?: throw JoinError("network", "That device is not connected right now.")
            // from now on this phone and the peer share the library: the pin carries the actors (this phone's own is the joined one)
            peers.update(deviceId) { p -> if (p.libraries.none { it.libraryId == libraryId }) p.libraries = p.libraries + PeerLibrary(libraryId, joined.hostActor, defaultGrants()) }
            deps.adoptJoined?.invoke(joined, treeUri)
            state.stage = "done"
            offers[deviceId] = offers[deviceId].orEmpty().filter { it.first != libraryId }
            changed()
            return joined
        } catch (e: Exception) {
            state.stage = "failed"
            state.error = e.message ?: "The library could not be taken in."
            state.resumable = !(e is JoinError && (e.code == "folder" || e.code == "not-admin"))
            changed()
            throw e
        }
    }

    /**
     * Combining libraries: joins [libraryId] of a paired device into the records root [layout] of the Keepfin folder [target] (contract section 2).
     * The folder keeps its own library; resumable with the same arguments. The caller owns the profile (this does not call `adoptJoined`).
     */
    fun joinInto(deviceId: String, libraryId: String, name: String, target: FolderAccess, layout: app.keepfin.folder.FolderLayout, cancelled: () -> Boolean, progress: (String, Int) -> Unit): Joined {
        requireRunning()
        val peer = peers.get(deviceId) ?: throw IllegalStateException("That device is not paired.")
        if (deps.libraries().any { it.libraryId == libraryId }) throw IllegalStateException("This device already has that library.")
        val link = links[deviceId] ?: throw JoinError("network", "That device is not connected right now.")
        val joined = link.withClient { client ->
            JoinRunner.run(client, deviceId, libraryId, name, target, now = deps.now, cancelled = cancelled, layout = layout) { p -> progress(p.stage, p.segments) }
        } ?: throw JoinError("network", "That device is not connected right now.")
        peers.update(deviceId) { p -> if (p.libraries.none { it.libraryId == libraryId }) p.libraries = p.libraries + PeerLibrary(libraryId, joined.hostActor, defaultGrants()) }
        changed()
        return joined
    }

    /** The paired device that holds [libraryId], as Separate again needs it (reachable, publish and wait for the durable acknowledgement, withdraw). */
    fun mergePeer(deviceId: String): app.keepfin.merge.PeerPort = object : app.keepfin.merge.PeerPort {
        override fun reachable(): Boolean = links[deviceId]?.online == true

        override fun publishAndWait(libraryId: String, actor: String, seq: Long): Boolean {
            val lib = deps.libraries().firstOrNull { it.libraryId == libraryId } ?: return false
            // the peer may be mid-sync: a few rounds, each ending with the peer's own vector as the durable acknowledgement
            for (round in 0 until 8) {
                try {
                    val link = links[deviceId] ?: return false
                    val acked = link.withClient { client ->
                        lib.flush()
                        val first = Sync.pull(client, lib)
                        val remote = first.remote
                        if (first.denied || remote == null) return@withClient false
                        val (_, denied) = Sync.push(client, lib, remote)
                        if (denied) return@withClient false
                        val again = Sync.pull(client, lib)
                        val have = again.remote?.get(actor) ?: 0L
                        if (have < seq) deps.log?.log("separate: the other device holds $have of $seq operations")
                        have >= seq
                    }
                    if (acked == true) return true
                } catch (e: Exception) {
                    deps.log?.log("separate: publishing failed: ${e.message}")
                }
                Thread.sleep(750)
            }
            return false
        }

        override fun withdraw(libraryId: String, actor: String, cutoffSeq: Long): Boolean {
            // B's T membership is revoked by an administrator of T; B itself is not one. The administrator it can reach is asked to revoke the actor
            // with a cutoff (the same request the Windows app sends); either way the pin forgets T so this phone stops syncing it.
            val left = leaveLibrary(deviceId, libraryId, actor)
            peers.update(deviceId) { p -> p.libraries = p.libraries.filter { it.libraryId != libraryId } }
            changed()
            return left
        }
    }

    fun cancelJoin() {        join?.cancel?.set(true)
    }

    fun dismissJoin() {
        val j = join
        if (j != null && j.stage in setOf("done", "failed")) join = null
        changed()
    }

    // ---- Download on... (phase 6): the sending side --------------------------------------------------------------------------

    /** Asks a paired device to download a link. The request is written to disk first and resent until the device answers. */
    fun sendJob(deviceId: String, url: String, format: JSONObject, options: JSONObject? = null, space: String? = null) {
        val peer = peers.get(deviceId) ?: throw IllegalStateException("That device is not paired.")
        if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url.trim())) throw IllegalArgumentException("Paste a complete https:// link.")
        val job = outgoing.create(deviceId, peer.name, url.trim(), format, options, space)
        changed()
        val link = links[deviceId]
        work.execute { runCatching { link?.withClient { client -> deliverOne(client, job.requestId) } } }
    }

    fun dismissJob(requestId: String) {
        outgoing.dismiss(requestId)
        changed()
    }

    private fun deliverJobs(deviceId: String, client: PeerClient) {
        for (job in outgoing.openFor(deviceId)) deliverOne(client, job.requestId)
    }

    /** One request (or the same one again: the answer is the current state of the job on the other device). */
    private fun deliverOne(client: PeerClient, requestId: String) {
        val job = outgoing.get(requestId) ?: return
        if (isTerminalJob(job.state)) return
        try {
            val body = JSONObject().put("requestId", requestId).put("url", job.url).put("format", job.format)
            job.options?.let { body.put("options", it) }
            job.space?.let { body.put("space", it) }
            val answer = client.json("POST", "/v1/jobs", body, timeoutMs = 60_000)
            val view = answer.optJSONObject("view")
            if (view != null) outgoing.applyView(requestId, RemoteJobView.of(view)) else outgoing.update(requestId) { it.state = "queued" }
        } catch (e: PeerHttpError) {
            when (e.status) {
                422, 409, 400 -> outgoing.update(requestId) { it.state = "refused"; it.error = e.message }
                403 -> outgoing.update(requestId) { it.state = "refused"; it.error = "That device does not allow downloads from this device." }
                501 -> outgoing.update(requestId) { it.state = "refused"; it.error = "That device does not take downloads from other devices." }
                507, 429 -> outgoing.update(requestId) { it.error = e.message } // stays: tried again when it connects
            }
        } catch (e: Exception) {
            // a lost connection: the request stays and is sent again when the device is back
        }
        changed()
    }

    // ---- Play from this device (phase 6) -------------------------------------------------------------------------------------

    /** May file bytes move to or from [deviceId] now? On mobile data they wait unless the person allowed it for that device (spec 7). */
    fun canMoveFiles(deviceId: String): Boolean = !deps.network.isMetered() || peers.get(deviceId)?.allowMobileFiles == true

    /**
     * Finds a paired device that holds the file with [contentId] and lets this phone play it: answers with a `keepfin-peer://` URI for
     * the player (Range requests are passed through, so seeking works). Nothing is saved on this phone.
     */
    /** The name of the device behind a `keepfin-peer://<token>` address. */
    fun peerNameOf(token: String): String? = playTokens[token]?.let { peers.get(it.deviceId)?.name }

    /** The paired devices that share [libraryId], with whether each is reachable and lets this phone play its files. */
    fun playCandidates(libraryId: String): List<PlayCandidate> = peers.list().mapNotNull { peer ->
        if (peer.libraries.none { it.libraryId == libraryId }) return@mapNotNull null
        val link = links[peer.deviceId]
        val offered = link?.lastHello?.libraries?.firstOrNull { it.libraryId == libraryId }
        val reachable = link?.online == true && offered != null
        PlayCandidate(peer.name, reachable, offersMedia = if (reachable) "media.read" in offered!!.actions else true, lastSeen = peer.lastSeen)
    }

    /** [allowMetered]: the person asked for this file now, so it may stream on mobile data (the player says where it streams from). */
    fun playFrom(libraryId: String, contentId: String, name: String, allowMetered: Boolean = false): PlayFromResult {
        val m = Regex("^sha256:([0-9a-f]{64})$").matchEntire(contentId) ?: throw IllegalArgumentException("This item has no content to look for on other devices.")
        var waitedForWifi = false
        for ((deviceId, link) in links) {
            val peer = peers.get(deviceId)
            val offered = link.lastHello?.libraries?.firstOrNull { it.libraryId == libraryId }
            if (peer == null || offered == null || "media.read" !in offered.actions) continue
            if (!allowMetered && !canMoveFiles(deviceId)) {
                waitedForWifi = true
                continue
            }
            val size = try {
                link.withClient { client ->
                    client.request("HEAD", "/v1/revisions/${m.groupValues[1]}?library=${java.net.URLEncoder.encode(libraryId, "UTF-8")}", timeoutMs = 8000).use { r ->
                        if (r.code == 200) (r.header("Content-Length")?.toLongOrNull() ?: 0L) else -1L
                    }
                }
            } catch (e: Exception) {
                null
            }
            if (size == null || size < 0) continue
            val token = UUID.randomUUID().toString().replace("-", "")
            val at = deps.now()
            playTokens.entries.removeAll { it.value.expires < at }
            playTokens[token] = PlayToken(deviceId, libraryId, m.groupValues[1], size, name, at + 6 * 60 * 60_000L, allowMetered)
            return PlayFromResult("keepfin-peer://$token", peer.name, size)
        }
        throw IllegalStateException(if (waitedForWifi) "The device that has this file is reachable only on mobile data. Connect to Wi-Fi, or allow mobile data for that device in Settings." else "No paired device that lets you play its files has this one right now.")
    }

    class PeerSource(val size: Long, val name: String, val open: (range: LongRange?) -> PeerStream)

    class PeerStream(val status: Int, val contentLength: Long, val contentRange: String?, val input: java.io.InputStream, val close: () -> Unit)

    /** The source behind a `keepfin-peer://<token>` URI (the player's data source reads it with Range). */
    fun peerSource(token: String): PeerSource? {
        val entry = playTokens[token] ?: return null
        if (entry.expires < deps.now()) return null
        val link = links[entry.deviceId] ?: return null
        return PeerSource(entry.size, entry.name) { range ->
            if (!entry.allowMetered && !canMoveFiles(entry.deviceId)) throw IOException("Waiting for Wi-Fi: this device is on mobile data.")
            val headers = if (range != null) mapOf("Range" to "bytes=${range.first}-${range.last}") else emptyMap()
            val response = link.withClient { client ->
                client.request("GET", "/v1/revisions/${entry.hex}?library=${java.net.URLEncoder.encode(entry.libraryId, "UTF-8")}", headers = headers, timeoutMs = 120_000)
            } ?: throw IOException("The device that has this file is not connected.")
            PeerStream(response.code, response.header("Content-Length")?.toLongOrNull() ?: -1L, response.header("Content-Range"), response.body!!.byteStream()) { response.close() }
        }
    }

    // ---- the page ------------------------------------------------------------------------------------------------------------

    private fun note(key: String, text: String) {
        notes[key] = text
        changed()
    }

    private fun requireRunning() {
        if (!enabled) throw IllegalStateException("Turn devices on first.")
        if (server == null) throw IllegalStateException(portError ?: "Devices are not running.")
    }

    private fun refreshLibraryInfoAsync() {
        if (!closed.get()) work.execute { runCatching { refreshLibraryInfo() } }
    }

    private fun refreshLibraryInfo() {
        for (lib in deps.libraries()) {
            if (!lib.isOpen) continue
            try {
                val m = lib.membership()
                libraryInfo[lib.libraryId] = m.active to (m.members[lib.actor()]?.isAdmin == true)
            } catch (e: Exception) {
                // the store may be closing
            }
        }
        changed()
    }

    fun changed() {
        if (closed.get() || !pushQueued.compareAndSet(false, true)) return
        try {
            timer.schedule({
                pushQueued.set(false)
                if (!closed.get()) {
                    try {
                        _state.value = computeState()
                    } catch (e: Throwable) {
                        // an exception here would be swallowed by the timer and leave the page on an old state (Devices "Off" while on): say so and try again
                        deps.log?.log("devices state could not be made: $e")
                        retryPush()
                    }
                }
            }, 120, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            pushQueued.set(false) // closing
        }
    }

    private fun retryPush() {
        try {
            timer.schedule({ changed() }, 2, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // closing
        }
    }

    fun computeState(): DevicesState {
        val libs = try { deps.libraries().filter { it.isOpen } } catch (e: Exception) { emptyList() }
        val paired = peers.list().map { peer ->
            val hello = links[peer.deviceId]?.lastHello
            val jobsOk = hello?.jobsCapable == true && hello.libraries.any { "jobs" in it.actions }
            PairedDeviceUi(
                deviceId = peer.deviceId, fingerprint = fingerprintOf(peer.deviceId), name = peer.name, platform = peer.platform, pairedAt = peer.pairedAt, lastSeen = peer.lastSeen,
                online = links[peer.deviceId]?.online == true || server?.isOnline(peer.deviceId) == true,
                libraries = peer.libraries.map { l -> PairedLibraryUi(l.libraryId, libs.firstOrNull { it.libraryId == l.libraryId }?.name ?: "Library", l.actions.map { it.wire }, l.actor) },
                unreachableSince = peer.unreachableSince, address = peer.lastHost?.let { formatAddress(it, peer.lastPort ?: DEFAULT_PORT) },
                route = routeOfPeer(peer),
                lastSync = links[peer.deviceId]?.lastSync,
                jobsAllowed = jobsOk, jobSpaces = if (jobsOk) hello!!.jobSpaces else emptyList(),
                shareable = libs.filter { lib -> peer.libraries.none { it.libraryId == lib.libraryId } }.map { ShareableUi(it.libraryId, it.name) },
                allowMobileFiles = peer.allowMobileFiles,
            )
        }
        val offerList = ArrayList<OfferUi>()
        for ((deviceId, list) in offers) {
            val peer = peers.get(deviceId) ?: continue
            for ((libraryId, name) in list) offerList += OfferUi(deviceId, peer.name, libraryId, name, links[deviceId]?.online == true)
        }
        val remote = outgoing.list().take(100).map { j -> RemoteJobRowUi(j.requestId, j.deviceId, j.deviceName, j.url, j.state, j.percent, j.title, j.label, j.totalBytes, j.error, j.createdAt) }
        val responder = pairing.view()
        val a = asking
        val responderUi = responder?.let { PairingUi(it.id, it.method, "answering", responderStage(it.stage), it.peerName, it.peerFingerprint, it.sas, it.error, it.expiresAt, it.unrequested, it.autoApproved, it.peerDeviceId) }
        val pairingUi = a?.view?.let { PairingUi(it.id, it.method, "asking", it.stage, it.peerName, it.peerFingerprint, it.sas, it.error, it.expiresAt) } ?: responderUi
        deps.incomingPairing?.invoke(responderUi)
        val notesList = synchronized(notes) { notes.values.toMutableList() }
        for (link in links.values) link.versionNote?.let { notesList += it }
        val j = join
        val q = qr
        return DevicesState(
            available = true, thisDevice = ThisDevice(deps.identity.name, fingerprintOf(deps.identity.deviceId), "android"), enabled = enabled, port = port, portError = portError,
            libraries = libs.map { LibraryInfoUi(it.libraryId, it.name, libraryInfo[it.libraryId]?.first ?: false, libraryInfo[it.libraryId]?.second ?: false) },
            paired = paired, discovered = discovery?.list().orEmpty().filter { !it.paired }.map { DiscoveredUi(it.hint, it.name, it.host, it.port) },
            pairingUntil = pairing.windowEnd, pairing = pairingUi, qr = if (q != null && q.expiresAt > deps.now()) q else null, notes = notesList, offers = offerList,
            join = j?.let { JoinUi(it.deviceId, it.deviceName, it.libraryId, it.name, it.stage, it.segments, it.error, it.resumable) }, remoteJobs = remote,
            incomingJobs = deps.incomingRows { id -> jobsHost?.originOf(id) }, keepReachable = keepReachable, metered = deps.network.isMetered(),
            network = networkUi(), scanning = discovery?.scanning == true,
        )
    }

    /** "lan", "tailscale" or "none": how a paired device is reached right now. */
    private fun routeOfPeer(peer: Peer): String {
        val online = links[peer.deviceId]?.online == true || server?.isOnline(peer.deviceId) == true
        if (!online) return "none"
        return routeOf(links[peer.deviceId]?.lastAddress?.host ?: peer.lastHost ?: return "lan")
    }

    private fun networkUi(): NetworkUi {
        val nics = try { deps.nics() } catch (e: Exception) { emptyList() }
        return NetworkUi(lan = deps.network.hasLan(), tailscaleAddress = detectTailscale(nics))
    }

    private fun responderStage(stage: PairStage): String = when (stage) {
        PairStage.Comparing -> "comparing"
        PairStage.Approving -> "approving"
        PairStage.Done -> "done"
        PairStage.Failed -> "failed"
    }

    /** The server's own pin material for a QR (tests read the link from state). */
    val pin: ByteArray get() = app.keepfin.core.net.pairPinOf(deps.identity.spki)
}

/** An identity whose name can change (tests). */
interface RenamableIdentity : DeviceIdentity {
    fun rename(newName: String)
}
