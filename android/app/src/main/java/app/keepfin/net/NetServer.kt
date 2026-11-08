package app.keepfin.net

import app.keepfin.core.net.AuthzContext
import app.keepfin.core.net.NetAction
import app.keepfin.core.net.authorize
import app.keepfin.core.net.deviceIdOf
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

// The network server (spec 4 and 9.1): HTTP/1.1 over TLS 1.3 with mutual authentication. A peer is whoever holds a pinned key; the
// certificate's name and chain are never looked at. Order of events for every request: the TLS handshake proved the key, the key is
// looked up in the pins, the core's authorization matrix decides (deny by default), then the budgets of 9.1 #7 apply. A port of
// gui/desktop/net/server.ts; the endpoints and their answers are the same:
//   GET  /v1/announce                                      strangers allowed (own budget): {v:1, id, n, p}, the DNS-SD facts for the scan
//   GET  /v1/hello?v=1                                     versions, name, the libraries this peer may see, capabilities, jobSpaces
//   POST /v1/pair/start|nonce|confirm                      pairing (PairingHost)
//   GET  /v1/libraries/<id>/segments?vector=<a:n,...>      framed KFSEG files the caller lacks (chunked)
//   POST /v1/libraries/<id>/segments                       framed KFSEG files pushed by the caller
//   POST /v1/libraries/<id>/join                           a device asks to join (body {actor})
//   GET  /v1/libraries/<id>/inventory                      the folder inventory as JSON lines (sorted), header x-keepfin-complete
//   GET|HEAD /v1/revisions/<sha256 hex>?library=<id>       file bytes with Range
//   GET  /v1/libraries/<id>/copy/state|file, POST .../copy/lock|heartbeat|unlock|trash|move|generation, PUT .../copy/file
//   POST /v1/jobs                                          Download on... (phase 6)
//   GET  /v1/events (WebSocket)                            change notices, filtered by what the peer may read
// Threads: one per open connection (at most Limits.maxConnections), each owned by this server and closed with it.

class Caller(val deviceId: String, val spki: ByteArray, val address: String, val peer: Peer?)

class Answer(val status: Int, val body: JSONObject)

/** One accepted TCP connection. [identify] runs the TLS handshake and returns the peer's SPKI (it throws when the handshake fails). */
class Accepted(val socket: Socket, val address: String, val identify: () -> ByteArray)

interface Acceptor {
    val port: Int

    /** Blocks for the next connection; throws IOException when closed. */
    fun accept(): Accepted
    fun close()
}

class NetServerDeps(
    val identity: () -> DeviceIdentity,
    val peers: PeerStore,
    val libraries: () -> List<NetLibrary>,
    val executor: (String) -> PeerExecutorPort?,
    val pairing: PairingHost,
    val cacheDir: File,
    val now: () -> Long = { System.currentTimeMillis() },
    val limits: Limits = Limits(),
    val log: NetLog? = null,
    /** A paired peer made a request: for "last seen". */
    val seen: ((Peer, String) -> Unit)? = null,
    /** A job request (phase 6). Absent: 501. */
    val jobs: ((Caller, JSONObject) -> Answer)? = null,
    /** The spaces a remote job may save into (id and name). */
    val jobSpaces: (() -> List<Pair<String, String>>)? = null,
    /** A device asks to join a library it does not hold yet (it names the actor it will write as). Absent: 501. */
    val join: ((Caller, String, JSONObject) -> Answer)? = null,
    val platform: String = "android",
    /** This device's own eligible IPv4 addresses (Tailscale's included): `hello` tells paired callers so they can reconnect later. */
    val addresses: () -> List<String> = { emptyList() },
)

private class Conn(val deviceId: String, val spki: ByteArray, val address: String, val paired: Boolean)

class NetServer(private val deps: NetServerDeps) {
    val limits: Limits = deps.limits
    private val requestRate = AttemptLimiter(limits.requestsPerMinute, 60_000, deps.now)
    private val strangerRate = AttemptLimiter(limits.strangerRequestsPerMinute, 60_000, deps.now)
    private val announceRate = AttemptLimiter(limits.announcePerMinute, 60_000, deps.now)
    private val verifyRate = AttemptLimiter(limits.verifiesPerMinute, 60_000, deps.now)
    private val conns = ConcurrentHashMap<Socket, Conn>()
    private val transfers = ConcurrentHashMap<String, Int>()
    private val eventSockets = ConcurrentHashMap<String, MutableSet<WsSession>>()
    private val changeListeners = ConcurrentHashMap<String, () -> Unit>()
    private val inventoryRuns = ConcurrentHashMap<String, InventoryRun>()
    private val timer = ScheduledThreadPoolExecutor(1) { r -> Thread(r, "keepfin-net-notify").also { it.isDaemon = true } }.apply { removeOnCancelPolicy = true }
    private var notifyTimer: java.util.concurrent.ScheduledFuture<*>? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "keepfin-net-conn").also { it.isDaemon = true } }
    private val closed = AtomicBoolean(false)
    private var acceptor: Acceptor? = null

    private class InventoryRun(val file: File, val count: Long, val complete: Boolean, val at: Long)

    val listeningPort: Int get() = acceptor?.port ?: 0

    // ---- lifecycle -------------------------------------------------------------------------------------------------------

    fun start(acceptor: Acceptor) {
        this.acceptor = acceptor
        val t = Thread({ acceptLoop(acceptor) }, "keepfin-net-accept")
        t.isDaemon = true
        t.start()
        attachLibraries()
    }

    private fun acceptLoop(acceptor: Acceptor) {
        while (!closed.get()) {
            val accepted = try {
                acceptor.accept()
            } catch (e: IOException) {
                if (closed.get()) return
                try { Thread.sleep(50) } catch (_: InterruptedException) { return }
                continue
            }
            if (conns.size >= limits.maxConnections + 16) {
                runCatching { accepted.socket.close() }
                continue
            }
            try {
                pool.execute { serve(accepted) }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                runCatching { accepted.socket.close() }
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        for (off in changeListeners.values) runCatching { off() }
        changeListeners.clear()
        // snapshots by toArray: Kotlin's toList() reads the size first, and a connection that closes meanwhile made it throw
        for (set in eventSockets.values) for (ws in ArrayList(set)) ws.terminate()
        eventSockets.clear()
        runCatching { acceptor?.close() }
        for (socket in ArrayList(conns.keys)) runCatching { socket.close() }
        timer.shutdownNow()
        pool.shutdownNow()
    }

    /** Closes everything open for one peer (Remove device: its streams end now). */
    fun dropPeer(deviceId: String) {
        eventSockets.remove(deviceId)?.forEach { it.terminate() }
        for ((socket, conn) in conns) if (conn.deviceId == deviceId) runCatching { socket.close() }
    }

    fun openConnections(deviceId: String? = null): Int = conns.values.count { deviceId == null || it.deviceId == deviceId }

    fun isOnline(deviceId: String): Boolean = (eventSockets[deviceId]?.count { it.isOpen } ?: 0) > 0 || openConnections(deviceId) > 0

    // ---- one connection ----------------------------------------------------------------------------------------------------

    private fun serve(accepted: Accepted) {
        val socket = accepted.socket
        try {
            socket.soTimeout = limits.handshakeMs
            val spki = try {
                accepted.identify()
            } catch (e: Exception) {
                deps.log?.log("handshake from ${accepted.address} failed: ${e.javaClass.simpleName} ${e.message?.take(80)}")
                return // a failed handshake is a closed socket, nothing more
            }
            val deviceId = deviceIdOf(spki)
            val paired = deps.peers.get(deviceId) != null
            var same = 0
            for (c in conns.values) if (if (paired) c.deviceId == deviceId else !c.paired && c.address == accepted.address) same++
            if (same >= (if (paired) limits.perPeerConnections else limits.perStrangerAddress)) {
                deps.log?.log("too many connections from ${if (paired) "a paired device" else accepted.address}")
                return
            }
            val conn = Conn(deviceId, spki, accepted.address, paired)
            conns[socket] = conn
            runCatching { socket.tcpNoDelay = true }
            requestLoop(socket, conn)
        } catch (e: IOException) {
            // the peer went away
        } catch (e: Exception) {
            deps.log?.log("connection failed: ${e.javaClass.simpleName}")
        } finally {
            conns.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun requestLoop(socket: Socket, conn: Conn) {
        val input = BufferedInputStream(socket.getInputStream(), 8192)
        val out = BufferedOutputStream(socket.getOutputStream(), 16 * 1024)
        val parser = HttpParser(limits)
        while (!closed.get()) {
            var responder = HttpResponder(out, false)
            val req = try {
                parser.readHead(input, socket)
            } catch (e: HttpError) {
                fail(responder, e)
                return
            } ?: return
            responder = HttpResponder(out, req.method == "HEAD")
            val body = LimitedBody(input, req.contentLength)
            var keep = true
            try {
                socket.soTimeout = 30_000
                val caller = Caller(conn.deviceId, conn.spki, conn.address, deps.peers.get(conn.deviceId))
                if (isAnnounce(req)) announceBudget(caller) else budget(caller)
                keep = route(caller, req, body, responder, socket, input, out)
            } catch (e: Exception) {
                if (e is IOException && !responder.headersSent) {
                    deps.log?.log("request ${req.method} ${req.path} ended without an answer: ${e.javaClass.simpleName} ${e.message?.take(80)}")
                    throw e
                }
                if (e is IOException) return
                fail(responder, e)
                // a 404 is answered without "Connection: close" (a copy asks for names that are not there all the time): the connection stays
                keep = e is HttpError && e.status == 404
            }
            if (!keep || closed.get()) return
            if (!body.drain(1024 * 1024)) return
            out.flush()
        }
    }

    private fun fail(res: HttpResponder, error: Throwable) {
        var status = 500
        var code = "error"
        var message = "Something went wrong."
        when (error) {
            is HttpError -> { status = error.status; code = error.code; message = error.message ?: message }
            is PairError -> { status = error.status; code = error.code; message = error.message ?: message }
            is FrameError -> { status = 413; code = "frame"; message = error.message ?: message }
            else -> deps.log?.log("request failed: ${error.javaClass.simpleName}")
        }
        if (res.headersSent) throw IOException("failed after the head was sent")
        val body = JSONObject().put("error", code).put("message", message)
        try {
            res.json(status, body, close = status >= 400 && status != 404)
        } catch (e: IOException) {
            // the peer is gone
        }
    }

    // ---- checks -----------------------------------------------------------------------------------------------------------

    private fun check(caller: Caller, action: NetAction, library: String?) {
        val decision = authorize(AuthzContext(deps.pairing.active || action == NetAction.Pair, deps.peers.grants(caller.deviceId)), action, library)
        if (!decision.allow) throw HttpError(403, decision.reason, "Not allowed (${decision.reason}).")
    }

    private fun budget(caller: Caller) {
        val limiter = if (caller.peer != null) requestRate else strangerRate
        if (!limiter.take(if (caller.peer != null) caller.deviceId else caller.address)) throw HttpError(429, "rate", "Too many requests.")
        caller.peer?.let { deps.seen?.invoke(it, caller.address) }
    }

    private fun isAnnounce(req: HttpRequest): Boolean = req.method == "GET" && req.segments.size == 2 && req.segments[0] == "v1" && req.segments[1] == "announce"

    /** Announce has its own small budget per source address, for strangers and paired devices alike; a paired caller is still seen. */
    private fun announceBudget(caller: Caller) {
        if (!announceRate.take(caller.address)) throw HttpError(429, "rate", "Too many requests.")
        caller.peer?.let { deps.seen?.invoke(it, caller.address) }
    }

    private fun transfer(caller: Caller): AutoCloseable {
        val have = transfers.merge(caller.deviceId, 1) { a, b -> a + b } ?: 1
        if (have > limits.transfersPerPeer) {
            transfers.merge(caller.deviceId, -1) { a, b -> a + b }
            throw HttpError(429, "transfers", "Too many transfers at once.")
        }
        val released = AtomicBoolean(false)
        return AutoCloseable {
            if (released.compareAndSet(false, true)) transfers.compute(caller.deviceId) { _, n -> if (n == null || n <= 1) null else n - 1 }
        }
    }

    private fun library(id: String): NetLibrary = deps.libraries().firstOrNull { it.libraryId == id && it.isOpen } ?: throw HttpError(404, "library", "No such library.")

    private fun jsonBody(body: LimitedBody): JSONObject {
        val bytes = body.readAll(limits.maxJsonBytes, limits.minBodyBytesPerSecond, deps.now)
        if (bytes.isEmpty()) return JSONObject()
        return try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (e: org.json.JSONException) {
            throw HttpError(400, "json", "The request is not valid.")
        }
    }

    /** A body read below the minimum rate (after a grace period) is cut off. */
    private fun slowGuarded(input: InputStream): InputStream = object : InputStream() {
        private val started = deps.now()
        private var total = 0L

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = input.read(b, off, len)
            if (n > 0) {
                total += n
                val elapsed = deps.now() - started
                if (elapsed > 5000 && total / (elapsed / 1000.0) < limits.minBodyBytesPerSecond) throw HttpError(408, "slow", "The request is arriving too slowly.")
            }
            return n
        }
    }

    // ---- routing ------------------------------------------------------------------------------------------------------------

    /** Returns false when the connection must close after the answer. */
    private fun route(caller: Caller, req: HttpRequest, body: LimitedBody, res: HttpResponder, socket: Socket, input: InputStream, out: java.io.OutputStream): Boolean {
        val parts = req.segments
        if (parts.isEmpty() || parts[0] != "v1") throw HttpError(404, "not-found", "Not found.")
        val a = parts.getOrNull(1)
        val b = parts.getOrNull(2)
        val c = parts.getOrNull(3)
        val d = parts.getOrNull(4)
        val method = req.method

        if (a == "announce" && method == "GET" && parts.size == 2) {
            // open to strangers: the same facts as the DNS-SD record and nothing more (no libraries, peers or addresses)
            val identity = deps.identity()
            // c: 1 appears only while a typed pairing code is showing, and never says what the code is
            val announced = JSONObject().put("v", 1).put("id", identity.deviceId.take(16)).put("n", identity.name.take(40)).put("p", deps.platform)
            if (deps.pairing.codeShowing) announced.put("c", 1)
            res.json(200, announced)
            return true
        }
        if (a == "hello" && method == "GET" && parts.size == 2) {
            check(caller, NetAction.Hello, null)
            hello(caller, req, res)
            return true
        }
        if (a == "events" && method == "GET" && parts.size == 2) {
            check(caller, NetAction.Hello, null)
            events(caller, req, res, socket, input, out)
            return false
        }
        if (a == "pair" && method == "POST" && parts.size == 3) {
            check(caller, NetAction.Pair, null)
            pair(caller, b!!, body, res)
            return true
        }
        if (a == "jobs" && method == "POST" && parts.size == 2) {
            // the jobs action is granted per library; a job names no library, so any grant of it counts
            val grants = deps.peers.grants(caller.deviceId)
            val library = grants?.libraries?.entries?.firstOrNull { NetAction.Jobs in it.value }?.key
            check(caller, NetAction.Jobs, library)
            val handler = deps.jobs ?: throw HttpError(501, "not-available", "Download on another device is not available.")
            val answer = handler(caller, jsonBody(body))
            res.json(answer.status, answer.body)
            return true
        }
        if (a == "revisions" && (method == "GET" || method == "HEAD") && parts.size == 3) {
            val library = req.query["library"]
            check(caller, NetAction.MediaRead, library)
            revision(caller, library(library!!), b!!, req, res)
            return true
        }
        if (a == "libraries" && b != null && c != null) {
            if (c == "segments" && method == "GET" && d == null) {
                check(caller, NetAction.MetadataRead, b)
                segmentsOut(caller, library(b), req, res)
                return true
            }
            if (c == "segments" && method == "POST" && d == null) {
                check(caller, NetAction.MetadataWrite, b)
                segmentsIn(caller, library(b), req, body, res)
                return true
            }
            if (c == "join" && method == "POST" && d == null) {
                // the library must be shared with this peer already (the person here chose it); the grant of its actor is the host's decision
                check(caller, NetAction.MetadataWrite, b)
                val handler = deps.join ?: throw HttpError(501, "not-available", "Joining a library is not available.")
                val answer = handler(caller, b, jsonBody(body))
                res.json(answer.status, answer.body)
                return true
            }
            if (c == "inventory" && method == "GET" && d == null) {
                check(caller, NetAction.InventoryRead, b)
                inventory(caller, library(b), res)
                return true
            }
            if (c == "copy") {
                val executor = deps.executor(b) ?: throw HttpError(404, "library", "No such library.")
                val read = method == "GET" && d == "state"
                // reading a file of the copy by name is reading media; everything else that changes the folder is a copy mutation
                check(caller, if (read) NetAction.InventoryRead else if (method == "GET" && d == "file") NetAction.MediaRead else NetAction.CopyMutate, b)
                try {
                    copy(caller, executor, d, method, req, body, res)
                } finally {
                    // a listing made before a change would show the folder as it was: the next one is made again (a copy trusts the listing)
                    if (method != "GET") inventoryRuns.remove(b)?.file?.delete()
                }
                return true
            }
        }
        throw HttpError(404, "not-found", "Not found.")
    }

    // ---- hello and pairing -------------------------------------------------------------------------------------------------

    private fun hello(caller: Caller, req: HttpRequest, res: HttpResponder) {
        val asked = (req.query["v"] ?: "1").split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it > 0 }
        val common = PROTOCOL_VERSIONS.filter { it in asked }
        val identity = deps.identity()
        val grants = deps.peers.grants(caller.deviceId)
        val libs = deps.libraries().filter { it.isOpen && grants?.libraries?.get(it.libraryId)?.isNotEmpty() == true }
        if (common.isEmpty()) {
            // an older peer is told to update; both keep working alone
            val update = if ((asked.maxOrNull() ?: 0) < PROTOCOL_MIN) "peer" else "here"
            res.json(
                426,
                JSONObject().put("error", "version").put("update", update).put("versions", JSONArray(PROTOCOL_VERSIONS))
                    .put("message", if (update == "peer") "The other device needs to update Keepfin." else "This device needs to update Keepfin."),
            )
            return
        }
        val libraries = JSONArray()
        for (l in libs) {
            libraries.put(
                JSONObject().put("libraryId", l.libraryId).put("name", l.name).put("actor", l.actor())
                    .put("actions", JSONArray(grants!!.libraries.getValue(l.libraryId).map { it.wire })),
            )
        }
        val spaces = JSONArray()
        if (deps.jobs != null && grants?.libraries?.values?.any { NetAction.Jobs in it } == true) {
            for ((id, name) in deps.jobSpaces?.invoke().orEmpty()) spaces.put(JSONObject().put("id", id).put("name", name))
        }
        res.json(
            200,
            JSONObject().put("app", "keepfin").put("versions", JSONArray(PROTOCOL_VERSIONS)).put("version", common.max()).put("name", identity.name)
                .put("platform", deps.platform).put("deviceId", identity.deviceId).put("libraries", libraries)
                // libraries held here that are not shared with the asker (a count only): it tells "no Keepfin folder" from "not shared yet"
                .put("unshared", deps.libraries().count { it.isOpen } - libs.size)
                .put(
                    "capabilities",
                    JSONObject().put("segments", true).put("inventory", libs.any { true }).put("revisions", true).put("copies", true)
                        .put("join", deps.join != null).put("jobs", deps.jobs != null),
                )
                .put("jobSpaces", spaces)
                .put("addresses", JSONArray(deps.addresses().distinct().take(8))),
        )
    }

    private fun pair(caller: Caller, step: String, body: LimitedBody, res: HttpResponder) {
        val json = jsonBody(body)
        val pairing = deps.pairing
        when (step) {
            "start" -> {
                val versions = json.optJSONArray("versions")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: listOf(1)
                if (PROTOCOL_VERSIONS.none { it in versions }) {
                    res.json(426, JSONObject().put("error", "version").put("update", if ((versions.maxOrNull() ?: 0) < PROTOCOL_MIN) "peer" else "here").put("versions", JSONArray(PROTOCOL_VERSIONS)))
                    return
                }
                val token = if (json.has("token")) PairingHost.decode64(json.opt("token"), 64) else null
                if (token != null && token.size != 16) throw PairError("token", "This code is not valid.")
                // the typed code: the 20-bit number. A request carries a token or a code, never both
                val code: Int? = if (json.has("code")) {
                    val raw = json.opt("code")
                    val n: Long? = when (raw) {
                        is Int -> raw.toLong()
                        is Long -> raw
                        is Double -> raw.takeIf { !it.isNaN() && !it.isInfinite() && it == Math.floor(it) }?.toLong()
                        else -> null
                    }
                    if (token != null || n == null || n < 0 || n >= (1 shl 20)) throw PairError("token", "This code is not valid.")
                    n.toInt()
                } else null
                val started = pairing.start(PairingHost.peerInfo(caller.spki, json), caller.address, token, code)
                val libs = JSONArray()
                for (l in started.libraries) libs.put(JSONObject().put("libraryId", l.libraryId).put("actor", l.actor))
                res.json(
                    200,
                    JSONObject().put("id", started.id).put("commitment", b64(started.commitment)).put("expiresAt", started.expiresAt).put("name", started.name)
                        .put("platform", started.platform).put("libraries", libs).put("versions", JSONArray(PROTOCOL_VERSIONS)),
                )
            }
            "nonce" -> {
                val nb = pairing.nonce(caller.deviceId, json.optString("id"), PairingHost.decode64(json.opt("na"), 64))
                res.json(200, JSONObject().put("nb", b64(nb)))
            }
            "confirm" -> {
                if (json.optBoolean("decline", false)) {
                    pairing.cancel(caller.deviceId, json.optString("id"))
                    res.json(200, JSONObject().put("ok", true))
                    return
                }
                val sig = pairing.confirm(caller.deviceId, json.optString("id"), PairingHost.decode64(json.opt("sig"), 160))
                res.json(200, JSONObject().put("sig", b64(sig)))
            }
            else -> throw HttpError(404, "not-found", "Not found.")
        }
    }

    private fun b64(bytes: ByteArray) = java.util.Base64.getEncoder().encodeToString(bytes)

    // ---- segments -----------------------------------------------------------------------------------------------------------

    private fun segmentsOut(caller: Caller, lib: NetLibrary, req: HttpRequest, res: HttpResponder) {
        val text = req.query["vector"] ?: ""
        if (text.length > limits.maxVectorChars) throw HttpError(414, "vector", "The version vector is too long.")
        val vector = SegmentFiles.parseVector(text) ?: throw HttpError(400, "vector", "The version vector is not valid.")
        transfer(caller).use {
            lib.flush()
            // while files are listed and sent, compaction removes none of them
            val held = lib.hold()
            try {
            val list = lib.segments()
            val missing = SegmentFiles.missingFor(list, vector)
            res.beginChunked(
                200,
                linkedMapOf(
                    "content-type" to "application/x-keepfin-segments",
                    "cache-control" to "no-store",
                    "x-keepfin-vector" to SegmentFiles.formatVector(SegmentFiles.vectorOf(list)).take(8192),
                ),
            )
            for (segment in missing) {
                val bytes = try {
                    lib.folder.read(segment.path)
                } catch (e: app.keepfin.folder.FolderError) {
                    continue
                }
                if (bytes.size > limits.maxSegmentBytes) continue
                res.chunk(SegmentFiles.frame(bytes))
            }
            res.endChunked()
            } finally {
                held.close()
            }
        }
    }

    private fun segmentsIn(caller: Caller, lib: NetLibrary, req: HttpRequest, body: LimitedBody, res: HttpResponder) {
        transfer(caller).use {
            if (req.contentLength > limits.maxPushBytes) throw HttpError(413, "too-large", "The push is too large.")
            var stored = 0
            var have = 0
            val rejected = ArrayList<String>()
            val reader = FrameReader(limits.maxSegmentBytes, limits.maxPushSegments) { bytes ->
                // signature checks cost CPU: they run only for an authenticated peer and inside a budget
                if (!verifyRate.take(caller.deviceId)) throw HttpError(429, "verify-budget", "Too many segments to check right now.")
                when (val outcome = SegmentFiles.ingest(lib.folder, lib.libraryId, bytes, lib.layout)) {
                    is Ingest.Stored -> stored++
                    is Ingest.Have -> have++
                    is Ingest.Rejected -> rejected += outcome.reason
                }
            }
            try {
                reader.readAll(slowGuarded(body))
            } finally {
                if (stored > 0) (lib as? StoreNetLibrary)?.invalidate()
            }
            if (stored > 0) lib.rescan()
            res.json(200, JSONObject().put("stored", stored).put("have", have).put("rejected", JSONArray(rejected)))
        }
    }

    // ---- inventory and media ------------------------------------------------------------------------------------------------

    private fun inventory(caller: Caller, lib: NetLibrary, res: HttpResponder) {
        transfer(caller).use {
            // one scan at a time per library; a scan made in the last 10 seconds is reused
            val run = synchronized(inventoryRuns) {
                val known = inventoryRuns[lib.libraryId]
                if (known != null && deps.now() - known.at < 10_000 && known.file.isFile) known
                else {
                    known?.file?.delete()
                    deps.cacheDir.mkdirs()
                    val file = File(deps.cacheDir, "inventory-${lib.libraryId}.jsonl")
                    val result = file.outputStream().buffered().use { lib.inventory(it) } ?: run {
                        file.delete()
                        throw HttpError(501, "not-available", "This device does not offer its file list.")
                    }
                    InventoryRun(file, result.count, result.complete, deps.now()).also { inventoryRuns[lib.libraryId] = it }
                }
            }
            res.head(
                200,
                linkedMapOf("content-type" to "application/x-ndjson", "cache-control" to "no-store", "x-keepfin-complete" to if (run.complete) "1" else "0", "x-keepfin-count" to run.count.toString()),
                run.file.length(),
            )
            run.file.inputStream().use { copyTo(it, res) }
            res.flush()
        }
    }

    private fun copyTo(input: InputStream, res: HttpResponder) {
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            res.write(buf, 0, n)
        }
    }

    private fun revision(caller: Caller, lib: NetLibrary, hex: String, req: HttpRequest, res: HttpResponder) {
        if (!Regex("^[0-9a-f]{64}$").matches(hex)) throw HttpError(400, "content-id", "The content id is not valid.")
        val file = lib.findContent("sha256:$hex") ?: throw HttpError(404, "content", "This file is not in the library.")
        sendFile(caller, file.size, "\"sha256:$hex\"", file.open, req, res, file.name)
    }

    private fun sendFile(caller: Caller, size: Long, etag: String, open: (Long) -> InputStream, req: HttpRequest, res: HttpResponder, name: String = "") {
        transfer(caller).use {
            var start = 0L
            var end = size - 1
            var status = 200
            val range = req.header("range")
            if (range != null) {
                val m = Regex("^bytes=(\\d*)-(\\d*)$").matchEntire(range)
                if (m == null || (m.groupValues[1].isEmpty() && m.groupValues[2].isEmpty())) throw HttpError(416, "range", "The range is not valid.")
                val a = m.groupValues[1]
                val z = m.groupValues[2]
                if (a.length > 15 || z.length > 15) throw HttpError(416, "range", "The range is not valid.")
                if (a.isEmpty()) {
                    start = maxOf(0L, size - z.toLong())
                } else {
                    start = a.toLong()
                    if (z.isNotEmpty()) end = minOf(end, z.toLong())
                }
                if (start > end || start >= size || end - start + 1 > limits.maxRangeBytes) {
                    res.head(416, mapOf("content-range" to "bytes */$size"), 0)
                    res.flush()
                    return
                }
                status = 206
            }
            val headers = linkedMapOf("content-type" to "application/octet-stream", "accept-ranges" to "bytes", "etag" to etag, "cache-control" to "no-store")
            if (status == 206) headers["content-range"] = "bytes $start-$end/$size"
            // as the desktop sends it: the asker needs the extension when the name it knows has none (an item not on that device)
            if (name.isNotEmpty()) headers["x-keepfin-name"] = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
            val length = if (size == 0L) 0L else end - start + 1
            res.head(status, headers, length)
            if (req.method == "HEAD" || size == 0L) {
                res.flush()
                return
            }
            open(start).use { input ->
                val buf = ByteArray(64 * 1024)
                var left = length
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("the file ended early")
                    res.write(buf, 0, n)
                    left -= n
                }
            }
            res.flush()
        }
    }

    // ---- copy mutations (peer executor) -------------------------------------------------------------------------------------

    private fun copy(caller: Caller, ex: PeerExecutorPort, what: String?, method: String, req: HttpRequest, body: LimitedBody, res: HttpResponder) {
        val owner = caller.deviceId
        fun str(o: JSONObject, key: String) = if (o.isNull(key)) "" else o.optString(key)
        fun optStr(o: JSONObject, key: String): String? = if (o.isNull(key) || !o.has(key)) null else o.optString(key)
        when {
            what == "state" && method == "GET" -> res.json(200, ex.state())
            what == "lock" && method == "POST" -> {
                val j = jsonBody(body)
                res.json(200, ex.lock(owner, str(j, "device"), str(j, "run"), deps.now()))
            }
            what == "heartbeat" && method == "POST" -> res.json(200, ex.heartbeat(owner, str(jsonBody(body), "token")))
            what == "unlock" && method == "POST" -> res.json(200, ex.unlock(owner, str(jsonBody(body), "token")))
            what == "trash" && method == "POST" -> {
                val j = jsonBody(body)
                res.json(200, ex.trash(owner, str(j, "token"), str(j, "path"), optStr(j, "revision"), optStr(j, "ifMatch")))
            }
            what == "move" && method == "POST" -> {
                val j = jsonBody(body)
                res.json(200, ex.move(owner, str(j, "token"), str(j, "from"), str(j, "to"), optStr(j, "revision"), optStr(j, "ifMatch")))
            }
            what == "generation" && method == "POST" -> {
                val j = jsonBody(body)
                res.json(200, ex.appendGeneration(owner, str(j, "token"), j.optLong("generation", -1), str(j, "replica")))
            }
            what == "file" && method == "GET" -> {
                val opened = ex.open(req.query["path"] ?: "")
                sendFile(caller, opened.size, "\"${opened.size}-${opened.mtimeMs}\"", opened.open, req, res)
            }
            what == "file" && method == "PUT" -> transfer(caller).use {
                if (!req.headers.containsKey("content-length")) throw HttpError(411, "length", "The size of the file is needed.")
                val q = req.query
                val ifMatch = req.header("if-match")?.trim('"')
                val params = PutParams(q["token"] ?: "", q["path"] ?: "", q["content"] ?: "", if (q["mode"] == "replace") "replace" else "new", q["revision"], ifMatch, req.contentLength)
                res.json(200, ex.put(owner, params, slowGuarded(body)))
            }
            else -> throw HttpError(404, "not-found", "Not found.")
        }
    }

    // ---- events (WebSocket) --------------------------------------------------------------------------------------------------

    private fun events(caller: Caller, req: HttpRequest, res: HttpResponder, socket: Socket, input: InputStream, out: java.io.OutputStream) {
        val key = WebSocketHandshake.check(req)
        val open = eventSockets.computeIfAbsent(caller.deviceId) { java.util.Collections.newSetFromMap(ConcurrentHashMap()) }
        open.removeAll { !it.isOpen }
        if (open.size >= limits.eventSocketsPerPeer) throw HttpError(429, "event-sockets", "Too many event streams.")
        out.write(WebSocketHandshake.response(key))
        out.flush()
        val ws = WsSession(input, out, socket, limits.eventBackpressureMessages)
        open.add(ws)
        timer.execute { sendVectors(caller.deviceId, ws) }
        try {
            ws.run()
        } finally {
            open.remove(ws)
        }
    }

    private fun allowedFor(deviceId: String, libraryId: String): Boolean =
        authorize(AuthzContext(false, deps.peers.grants(deviceId)), NetAction.MetadataRead, libraryId).allow

    /** On connect, and after every change: the libraries this peer may read, with our version vector. */
    private fun sendVectors(deviceId: String, only: WsSession? = null) {
        for (lib in deps.libraries()) {
            if (!lib.isOpen || !allowedFor(deviceId, lib.libraryId)) continue
            val targets = if (only != null) listOf(only) else eventSockets[deviceId]?.let { ArrayList(it) }.orEmpty()
            if (targets.isEmpty()) continue
            val vector = try {
                lib.flush()
                SegmentFiles.formatVector(SegmentFiles.vectorOf(lib.segments()))
            } catch (e: Exception) {
                continue
            }
            for (ws in targets) ws.send(JSONObject().put("t", "segments").put("library", lib.libraryId).put("vector", vector).toString())
        }
    }

    private fun attachLibraries() {
        for (lib in deps.libraries()) attach(lib)
    }

    /** Starts watching a library that was opened after the server started. */
    fun attach(lib: NetLibrary) {
        if (closed.get() || changeListeners.containsKey(lib.libraryId)) return
        changeListeners[lib.libraryId] = lib.onChange { scheduleNotify() }
    }

    /** A library was closed: its listener goes. */
    fun detach(libraryId: String) {
        changeListeners.remove(libraryId)?.invoke()
    }

    private fun scheduleNotify() {
        if (closed.get()) return
        synchronized(this) {
            notifyTimer?.cancel(false)
            // the store writes a segment about two seconds after the last change; the notice follows it
            notifyTimer = try {
                timer.schedule({ for (id in ArrayList(eventSockets.keys)) sendVectors(id) }, notifyDelayMs, TimeUnit.MILLISECONDS)
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                null
            }
        }
    }

    /** How long after a change the peers are told (tests shorten it). */
    @Volatile var notifyDelayMs: Long = 2500

    /** A notice for one peer only (the progress of a job it asked for). Dropped when the peer has no event stream open: it asks again on reconnect. */
    fun sendTo(deviceId: String, value: JSONObject) {
        val text = value.toString()
        for (ws in eventSockets[deviceId]?.let { ArrayList(it) }.orEmpty()) ws.send(text)
    }

    /** Tells every connected peer that may read a library that something changed. */
    fun announce() {
        // a server that is closing has stopped its timer: nothing is announced then
        try { timer.execute { for (id in ArrayList(eventSockets.keys)) sendVectors(id) } } catch (_: java.util.concurrent.RejectedExecutionException) { }
    }
}
