package app.keepfin.net

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.WebSocket
import org.json.JSONObject

// One paired peer, kept in step (spec 4, 7): connect with the pinned key, say hello, listen to its change notices, and sync the
// libraries both share. An unreachable peer is retried with exponential back-off (at most every 30 minutes, jittered); a change of
// network, a change in our own library or a notice from the peer makes the next pass happen at once. Port of gui/desktop/net/link.ts;
// the work runs on one thread owned by the link.

class HelloLibrary(val libraryId: String, val name: String, val actor: String, val actions: List<String>)

class HelloAnswer(
    val version: Int,
    val name: String,
    val deviceId: String,
    val platform: String?,
    val libraries: List<HelloLibrary>,
    val jobsCapable: Boolean,
    val joinCapable: Boolean,
    /** Where this peer lets us save a job ("Download on..."); empty when it does not take jobs from us. */
    val jobSpaces: List<Pair<String, String>>,
    /** The addresses this peer says it can be reached at (LAN, Tailscale); empty from an older build. */
    val addresses: List<String> = emptyList(),
    /** How many libraries the peer holds that it has not shared with this device; null from an older build. */
    val unshared: Int? = null,
) {
    /** Why this peer offers nothing: "none" (no library), "unshared" (one not shared with us yet; also what an older build that does not count gets), "held" or "denied". */
    fun gap(held: Set<String>): String = when {
        libraries.isEmpty() -> if (unshared == 0) "none" else "unshared"
        libraries.all { it.libraryId in held } -> "held"
        else -> "denied"
    }

    companion object {
        fun parse(o: JSONObject): HelloAnswer {
            val libs = ArrayList<HelloLibrary>()
            o.optJSONArray("libraries")?.let { a ->
                for (i in 0 until a.length()) {
                    val l = a.optJSONObject(i) ?: continue
                    val actions = ArrayList<String>()
                    l.optJSONArray("actions")?.let { x -> for (j in 0 until x.length()) actions += x.optString(j) }
                    libs += HelloLibrary(l.optString("libraryId"), l.optString("name"), l.optString("actor"), actions)
                }
            }
            val spaces = ArrayList<Pair<String, String>>()
            o.optJSONArray("jobSpaces")?.let { a ->
                for (i in 0 until a.length()) a.optJSONObject(i)?.let { spaces += it.optString("id") to it.optString("name") }
            }
            val caps = o.optJSONObject("capabilities")
            val addresses = ArrayList<String>()
            o.optJSONArray("addresses")?.let { a -> for (i in 0 until minOf(a.length(), 16)) a.optString(i).takeIf { it.isNotEmpty() }?.let { addresses += it } }
            return HelloAnswer(o.optInt("version", 1), o.optString("name"), o.optString("deviceId"), o.optString("platform").ifEmpty { null }, libs, caps?.optBoolean("jobs") == true, caps?.optBoolean("join") == true, spaces, addresses,
                if (o.has("unshared")) o.optInt("unshared", 0).coerceAtLeast(0) else null)
        }
    }
}

data class Address(val host: String, val port: Int)

/** Exponential back-off (doubling from [minMs]) with a jitter of plus or minus a quarter, and never longer than [maxMs]: 30 minutes by default (spec 7). */
fun backoffDelay(failures: Int, minMs: Long = 2000, maxMs: Long = 30 * 60_000L, random: () -> Double = Math::random): Long {
    val doublings = Math.pow(2.0, minOf(maxOf(failures, 0), 20).toDouble())
    return minOf(maxMs.toDouble(), minMs * doublings * (0.75 + random() * 0.5)).toLong()
}

class LinkDeps(
    val identity: () -> DeviceIdentity,
    val peers: PeerStore,
    val peerId: String,
    val libraries: () -> List<NetLibrary>,
    /** Where this peer might be: its last address and what discovery saw. */
    val hints: () -> List<Address>,
    /** Asked once when every address failed: scans the network and answers where the peer was seen (throttled by the service), as on Windows. */
    val lookup: (() -> List<Address>)? = null,
    val now: () -> Long,
    val log: NetLog? = null,
    /** The connection state or a sync result changed (the page shows "last seen"). */
    val changed: () -> Unit,
    val synced: ((SyncReport) -> Unit)? = null,
    val backoffMinMs: Long = 2000,
    val backoffMaxMs: Long = 30 * 60_000L,
    /** A connection is up and the peer said hello (jobs are delivered, offers are read). */
    val connected: ((PeerClient, HelloAnswer) -> Unit)? = null,
    /** A notice other than the library vectors arrived (the progress of a job). */
    val event: ((JSONObject) -> Unit)? = null,
    /** The peer could not be reached (the number of failed attempts in a row), and when it could again. */
    val unreachable: ((Int) -> Unit)? = null,
    val reachable: (() -> Unit)? = null,
    /** False while the phone has no network at all: nothing is tried, the next network wakes the link. */
    val networkUp: () -> Boolean = { true },
    /** May this pass pull and push segments (metadata)? Always true on mobile data (spec 7); a hook for tests. */
    val metadataAllowed: () -> Boolean = { true },
    val connectTimeoutMs: Long = 6000,
)

class PeerLink(private val deps: LinkDeps) {
    @Volatile var online = false
        private set

    /** When the last sync pass with the peer finished, and how many library changes it took in and sent (Devices shows it). */
    @Volatile var lastSync: LastSyncUi? = null
        private set

    /** The peer needs an update (or we do) before they can talk. */
    @Volatile var versionNote: String? = null
        private set

    @Volatile var lastAddress: Address? = null
        private set

    @Volatile var lastHello: HelloAnswer? = null
        private set

    /** The delay before the next attempt, as scheduled (tests read the back-off from it). */
    @Volatile var nextDelayMs = 0L
        private set

    @Volatile private var stopped = false
    @Volatile private var client: PeerClient? = null
    @Volatile private var socket: WebSocket? = null
    private val lock = Object()
    private var wake = false
    private val dirty = LinkedHashSet<String>()
    private var failures = 0
    private var syncFailed = false
    private var thread: Thread? = null
    private val firstPass = CountDownLatch(1)

    fun start() {
        val t = Thread({ loop() }, "keepfin-link-${deps.peerId.take(6)}")
        t.isDaemon = true
        thread = t
        t.start()
    }

    fun stop() {
        stopped = true
        socket?.cancel()
        client?.close()
        synchronized(lock) { wake = true; lock.notifyAll() }
        if (online) {
            online = false
            deps.changed()
        }
    }

    /** Waits until the first sync pass with this peer ended (or [timeoutMs], or it could not connect). */
    fun settled(timeoutMs: Long) {
        firstPass.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    /** Something may have changed here or on the network: sync now (or connect now when waiting to retry). */
    fun kick() {
        synchronized(lock) {
            for (lib in deps.libraries()) dirty.add(lib.libraryId)
            if (!online) failures = 0
            wake = true
            lock.notifyAll()
        }
    }

    private fun markDirty(libraryId: String) {
        synchronized(lock) {
            dirty.add(libraryId)
            wake = true
            lock.notifyAll()
        }
    }

    private fun waitFor(ms: Long) {
        val end = System.nanoTime() + ms * 1_000_000L
        synchronized(lock) {
            while (!wake && !stopped) {
                val left = (end - System.nanoTime()) / 1_000_000L
                if (left <= 0) break
                lock.wait(left)
            }
            wake = false
        }
    }

    private val peer: Peer? get() = deps.peers.get(deps.peerId)

    private fun loop() {
        while (!stopped && peer != null) {
            if (!deps.networkUp()) {
                // no network at all: not a failure of the peer; the next network wakes the link
                waitFor(60_000)
                continue
            }
            try {
                syncFailed = false
                session()
                failures = if (syncFailed) failures + 1 else 0
            } catch (e: PinMismatch) {
                deps.log?.log("a device answered with a different key than the one paired")
                failures++
            } catch (e: Exception) {
                failures++
            }
            if (online) {
                online = false
                deps.changed()
            }
            if (!stopped && failures > 0) deps.unreachable?.invoke(failures)
            firstPass.countDown() // nobody should wait on a link that cannot connect
            if (stopped) break
            val delay = backoffDelay(failures, deps.backoffMinMs, deps.backoffMaxMs)
            nextDelayMs = delay
            waitFor(delay)
        }
        firstPass.countDown()
    }

    private fun session() {
        val p = peer ?: return
        var lastError: Exception? = null
        // what discovery just saw, the last address that worked, then the addresses the device named (LAN first, Tailscale last)
        var round = reconnectOrder(deps.hints().map { Route(it.host, it.port) }, p.lastHost, p.lastPort, p.addresses).map { Address(it.host, it.port) }
        val tried = HashSet<Address>()
        for (pass in 0..1) {
        for (hint in round) {
            if (stopped) return
            if (!tried.add(hint)) continue
            val c = PeerClient(ClientOptions(deps.identity(), hint.host, hint.port, p.deviceId, deps.connectTimeoutMs))
            try {
                val hello = HelloAnswer.parse(c.json("GET", "/v1/hello?v=${PROTOCOL_VERSIONS.joinToString(",")}", timeoutMs = 8000))
                client = c
                lastHello = hello
                versionNote = null
                lastAddress = hint
                deps.peers.touch(p.deviceId, deps.now(), hint.host, hint.port)
                deps.peers.learnAddresses(p.deviceId, hello.addresses)
                deps.peers.learnName(p.deviceId, hello.name)
                connected(c, hello)
                return
            } catch (e: Exception) {
                c.close()
                lastError = e
                if (e is PeerHttpError && e.status == 426) {
                    versionNote = e.message
                    deps.changed()
                }
                if (e is PeerHttpError && e.status == 403) throw e // not paired on their side any more
            }
        }
        // nothing answered (the device may have a new address): look for it on the network once, then try what that found
        val lookup = deps.lookup
        if (pass == 0 && lookup != null && !stopped) round = try { lookup() } catch (e: Exception) { emptyList() } else break
        }
        throw lastError ?: IllegalStateException("no address")
    }

    private fun connected(c: PeerClient, hello: HelloAnswer) {
        online = true
        deps.reachable?.invoke()
        deps.changed()
        deps.connected?.invoke(c, hello)
        val closed = CountDownLatch(1)
        synchronized(lock) { for (lib in deps.libraries()) dirty.add(lib.libraryId); wake = true }
        var firstNotice = true
        socket = c.events(
            { text ->
                // what this device may do could have changed between the hello and the moment the stream opened: read it again once
                if (firstNotice) { firstNotice = false; refreshHello() }
                val m = try { JSONObject(text) } catch (e: Exception) { return@events }
                val t = m.optString("t")
                if (t == "job") { deps.event?.invoke(m); return@events }
                // the peer changed what this device may do, or its own name
                if (t == "access" || t == "hello") { refreshHello(); return@events }
                if (t != "segments") return@events
                val library = m.optString("library")
                if (SegmentFiles.parseVector(m.optString("vector")) == null) return@events
                // a library we have not heard of: the peer started sharing something, so its hello is read again
                if (lastHello?.libraries?.none { it.libraryId == library } == true) refreshHello()
                markDirty(library)
            },
            { closed.countDown(); synchronized(lock) { wake = true; lock.notifyAll() } },
        )
        try {
            while (!stopped && closed.count > 0L) {
                syncDirty()
                firstPass.countDown()
                if (syncFailed) break
                waitFor(60_000)
            }
        } finally {
            socket?.cancel()
            socket = null
            c.close()
            if (client === c) client = null
        }
    }

    @Volatile private var refreshing = false

    /** Reads the peer's hello again (it shares something new, or changed what this device may do). */
    fun refreshHello() {
        val c = client ?: return
        if (!online || refreshing) return
        refreshing = true
        Thread({
            try {
                val hello = HelloAnswer.parse(c.json("GET", "/v1/hello?v=${PROTOCOL_VERSIONS.joinToString(",")}", timeoutMs = 8000))
                lastHello = hello
                deps.peers.learnAddresses(deps.peerId, hello.addresses)
                deps.peers.learnName(deps.peerId, hello.name)
                deps.connected?.invoke(c, hello)
                deps.changed()
            } catch (e: Exception) {
                // the connection is going away; the next session reads it anyway
            } finally {
                refreshing = false
            }
        }, "keepfin-link-hello").also { it.isDaemon = true }.start()
    }

    /** Runs [fn] on the live connection; null when the peer is not connected right now. */
    fun <T> withClient(fn: (PeerClient) -> T): T? {
        val c = client
        if (c == null || !online) return null
        return fn(c)
    }

    /** One pass per dirty library, one at a time. */
    private fun syncDirty() {
        val c = client ?: return
        val hello = lastHello ?: return
        if (!deps.metadataAllowed()) return
        while (!stopped) {
            val libraryId = synchronized(lock) { dirty.firstOrNull()?.also { dirty.remove(it) } } ?: break
            val lib = deps.libraries().firstOrNull { it.libraryId == libraryId && it.isOpen }
            val offered = hello.libraries.firstOrNull { it.libraryId == libraryId }
            if (lib == null || offered == null) continue
            try {
                val report = Sync.library(c, lib) { stopped }
                lastSync = LastSyncUi(deps.now(), report.pulled, report.pushed)
                deps.changed()
                if (report.pulled > 0 || report.pushed > 0 || report.rejected > 0) deps.synced?.invoke(report)
            } catch (e: Exception) {
                deps.log?.log("sync of a library failed: ${e.message}")
                markDirty(libraryId)
                syncFailed = true
                socket?.cancel() // reconnect with back-off
                return
            }
        }
    }
}
