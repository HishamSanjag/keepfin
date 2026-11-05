package app.keepfin.net

import app.keepfin.core.net.NetAction
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The server end to end over a plain loopback socket: the handshake is replaced by the test naming the key (the real acceptor proves it
 * with TLS 1.3), everything after that is the production code: pins, the core authorization matrix, budgets and the endpoints.
 */
class ServerAuthzTest {
    private lateinit var host: SoftIdentity
    private lateinit var friend: SoftIdentity
    private lateinit var stranger: SoftIdentity
    private lateinit var peers: PeerStore
    private lateinit var lib: FakeNetLibrary
    private lateinit var acceptor: PlainAcceptor
    private lateinit var server: NetServer
    private lateinit var pairing: PairingHost
    private lateinit var executor: PeerExecutorImpl
    private val dirs = ArrayList<java.io.File>()
    private val libB = "22222222-2222-4222-8222-222222222222"

    private fun start(limits: Limits = Limits(), jobs: Boolean = false) {
        host = SoftIdentity("Host")
        friend = SoftIdentity("Friend")
        stranger = SoftIdentity("Stranger")
        val dir = tempDir().also { dirs += it }
        peers = PeerStore.open(dir)
        lib = FakeNetLibrary()
        pairing = PairingHost(PairingHostOptions({ host }, limits, { System.currentTimeMillis() }, { listOf(PairLibraryRef(LIB, ACTOR_A)) }, { _, _ -> }, {}))
        acceptor = PlainAcceptor()
        executor = PeerExecutorImpl(lib.folder)
        server = NetServer(
            NetServerDeps(
                identity = { host }, peers = peers, libraries = { listOf(lib) }, executor = { executor }, pairing = pairing,
                cacheDir = tempDir().also { dirs += it }, limits = limits,
                jobs = if (jobs) { _, body -> Answer(200, JSONObject().put("ok", true).put("got", body.optString("requestId"))) } else null,
            ),
        )
        server.start(acceptor)
    }

    private fun pairFriend(vararg actions: NetAction, libraryId: String = LIB) {
        peers.put(Peer(friend.deviceId, Base64.getEncoder().encodeToString(friend.spki), "Friend", "windows", 1, 1, listOf(PeerLibrary(libraryId, ACTOR_B, actions.toSet()))))
    }

    private fun grant(vararg actions: NetAction) { peers.update(friend.deviceId) { p -> p.libraries.first().actions = actions.toSet() } }

    @Before fun setUp() = start()

    @After fun tearDown() {
        server.close()
        pairing.shutdown()
        dirs.forEach { it.deleteRecursively() }
    }

    private fun send(who: SoftIdentity, request: ByteArray, body: ByteArray = ByteArray(0)) = rawRequest(acceptor, who.spki, request, body)
    private fun error(r: RawReply) = JSONObject(r.text).optString("error")

    @Test fun aStrangerGetsNothingButTheClosedDoorOfPairing() {
        val hello = send(stranger, get("/v1/hello?v=1"))
        assertEquals(403, hello.status)
        assertEquals("unknown-peer", error(hello))
        assertEquals(403, send(stranger, get("/v1/libraries/$LIB/segments?vector=")).status)
        assertEquals(403, send(stranger, get("/v1/revisions/${"a".repeat(64)}?library=$LIB")).status)
        val pair = send(stranger, post("/v1/pair/start", """{"versions":[1],"name":"S","platform":"windows"}"""))
        assertEquals(403, pair.status)
        assertEquals("not-pairing", error(pair))
    }

    @Test fun pairingAnswersAStrangerOnlyInsideTheWindow() {
        pairing.open()
        val started = send(stranger, post("/v1/pair/start", """{"versions":[1],"name":"S","platform":"windows"}"""))
        assertEquals(200, started.status)
        val body = JSONObject(started.text)
        assertTrue(body.getString("commitment").isNotEmpty())
        assertEquals("Host", body.getString("name"))
        // the same key still reaches nothing else
        assertEquals(403, send(stranger, get("/v1/hello?v=1")).status)
    }

    @Test fun anIncompatibleVersionIsToldToUpdate() {
        pairing.open()
        val r = send(stranger, post("/v1/pair/start", """{"versions":[99],"name":"S"}"""))
        assertEquals(426, r.status)
        assertEquals("version", error(r))
    }

    @Test fun aPairedDeviceReadsAndWritesMetadataAndNothingMore() {
        pairFriend(NetAction.MetadataRead, NetAction.MetadataWrite)
        val hello = send(friend, get("/v1/hello?v=1"))
        assertEquals(200, hello.status)
        val libs = JSONObject(hello.text).getJSONArray("libraries")
        assertEquals(1, libs.length())
        assertEquals(LIB, libs.getJSONObject(0).getString("libraryId"))
        assertFalse(JSONObject(hello.text).getJSONObject("capabilities").getBoolean("jobs"))
        assertEquals(200, send(friend, get("/v1/libraries/$LIB/segments?vector=")).status)
        assertEquals(200, send(friend, post("/v1/libraries/$LIB/segments", "")).status)
        for (path in listOf("/v1/libraries/$LIB/inventory", "/v1/revisions/${"a".repeat(64)}?library=$LIB", "/v1/libraries/$LIB/copy/state", "/v1/libraries/$LIB/copy/file?path=a.mp3")) {
            val r = send(friend, get(path))
            assertEquals("for $path", 403, r.status)
            assertEquals("action", error(r))
        }
        assertEquals(403, send(friend, post("/v1/libraries/$LIB/copy/lock", """{"device":"d","run":"r"}""")).status)
        assertEquals(403, send(friend, post("/v1/jobs", """{"requestId":"x"}""")).status)
    }

    @Test fun aLibraryThatIsNotSharedIsNotThere() {
        pairFriend(NetAction.MetadataRead, NetAction.MetadataWrite, libraryId = libB)
        val r = send(friend, get("/v1/libraries/$LIB/segments?vector="))
        assertEquals(403, r.status)
        assertEquals("library", error(r))
        val hello = JSONObject(send(friend, get("/v1/hello?v=1")).text)
        assertEquals(0, hello.getJSONArray("libraries").length()) // a library not shared is not even named
    }

    @Test fun aRemovedDeviceIsAStrangerAgain() {
        pairFriend(NetAction.MetadataRead)
        assertEquals(200, send(friend, get("/v1/hello?v=1")).status)
        peers.remove(friend.deviceId)
        assertEquals(403, send(friend, get("/v1/hello?v=1")).status)
    }

    @Test fun mediaIsServedByContentWithRealRanges() {
        pairFriend(NetAction.MediaRead)
        val bytes = ByteArray(1000) { (it % 251).toByte() }
        val id = "sha256:" + MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        lib.content[id] = bytes
        val hex = id.removePrefix("sha256:")
        val whole = send(friend, get("/v1/revisions/$hex?library=$LIB"))
        assertEquals(200, whole.status)
        assertEquals("bytes", whole.headers["accept-ranges"])
        assertEquals(1000, whole.body.size)
        val part = send(friend, get("/v1/revisions/$hex?library=$LIB", "range" to "bytes=100-199"))
        assertEquals(206, part.status)
        assertEquals("bytes 100-199/1000", part.headers["content-range"])
        assertEquals(bytes.copyOfRange(100, 200).toList(), part.body.toList())
        val tail = send(friend, get("/v1/revisions/$hex?library=$LIB", "range" to "bytes=-10"))
        assertEquals(206, tail.status)
        assertEquals(bytes.copyOfRange(990, 1000).toList(), tail.body.toList())
        val open = send(friend, get("/v1/revisions/$hex?library=$LIB", "range" to "bytes=990-"))
        assertEquals(10, open.body.size)
        for (bad in listOf("bytes=5-2", "bytes=2000-3000", "bytes=a-b", "bytes=0-1,5-6", "items=0-1", "bytes=")) {
            assertEquals("for $bad", 416, send(friend, get("/v1/revisions/$hex?library=$LIB", "range" to bad)).status)
        }
        assertEquals(404, send(friend, get("/v1/revisions/${"b".repeat(64)}?library=$LIB")).status)
        assertEquals(400, send(friend, get("/v1/revisions/nothex?library=$LIB")).status)
    }

    @Test fun copyMutationsNeedTheirOwnGrantAndTheLockIsPerDevice() {
        pairFriend(NetAction.CopyMutate, NetAction.InventoryRead)
        lib.folder.mkdirs(".keepfin")
        lib.folder.publish(".keepfin/folder.json", """{"format":1,"libraryId":"$LIB","replicaId":"r","createdAt":"x","generation":3}""".toByteArray())
        val state = JSONObject(send(friend, get("/v1/libraries/$LIB/copy/state")).text)
        assertEquals(3, state.getInt("generation"))
        val locked = JSONObject(send(friend, post("/v1/libraries/$LIB/copy/lock", """{"device":"laptop","run":"r1"}""")).text)
        assertTrue(locked.getBoolean("ok"))
        val token = locked.getString("token")
        // a path outside the folder cannot be named
        val escape = send(friend, post("/v1/libraries/$LIB/copy/trash", """{"token":"$token","path":"../x.mp3"}"""))
        assertEquals(escape.text, 400, escape.status)
        val hidden = send(friend, post("/v1/libraries/$LIB/copy/trash", """{"token":"$token","path":".keepfin/folder.json"}"""))
        assertEquals(400, hidden.status)
        // without the token nothing changes
        assertEquals(409, send(friend, post("/v1/libraries/$LIB/copy/trash", """{"token":"nope","path":"a.mp3"}""")).status)
        assertEquals(200, send(friend, post("/v1/libraries/$LIB/copy/unlock", """{"token":"$token"}""")).status)
    }

    @Test fun downloadRequestsReachTheHandlerOnlyWithTheJobsGrant() {
        tearDown(); start(jobs = true)
        pairFriend(NetAction.MetadataRead)
        assertEquals(403, send(friend, post("/v1/jobs", """{"requestId":"abc"}""")).status)
        grant(NetAction.MetadataRead, NetAction.Jobs)
        val r = send(friend, post("/v1/jobs", """{"requestId":"abc"}"""))
        assertEquals(200, r.status)
        assertEquals("abc", JSONObject(r.text).getString("got"))
        assertTrue(JSONObject(send(friend, get("/v1/hello?v=1")).text).getJSONObject("capabilities").getBoolean("jobs"))
    }

    @Test fun joinNeedsWriteAccessAndAHandler() {
        pairFriend(NetAction.MetadataRead)
        assertEquals(403, send(friend, post("/v1/libraries/$LIB/join", """{"actor":"$ACTOR_B"}""")).status)
        grant(NetAction.MetadataRead, NetAction.MetadataWrite)
        assertEquals(501, send(friend, post("/v1/libraries/$LIB/join", """{"actor":"$ACTOR_B"}""")).status) // this build has no join handler wired in the test server
    }

    @Test fun malformedRequestsAreAnsweredAndTheConnectionEnds() {
        pairFriend(NetAction.MetadataRead)
        assertEquals(400, send(friend, "GET /v1/hello HTTP/1.1\nhost: x\n\n".toByteArray()).status)
        assertEquals(405, send(friend, "DELETE /v1/hello HTTP/1.1\r\n\r\n".toByteArray()).status)
        assertEquals(431, send(friend, ("GET /v1/hello HTTP/1.1\r\nA: " + "x".repeat(20_000) + "\r\n\r\n").toByteArray()).status)
        assertEquals(404, send(friend, get("/v1/nothing")).status)
        assertEquals(404, send(friend, get("/other")).status)
    }

    @Test fun strangersAreRateLimitedByAddress() {
        tearDown(); start(Limits(strangerRequestsPerMinute = 3))
        val statuses = (1..6).map { send(stranger, get("/v1/hello?v=1")).status }
        assertEquals(listOf(403, 403, 403), statuses.take(3))
        assertTrue("later tries are slowed down: $statuses", statuses.drop(3).all { it == 429 })
    }

    @Test fun aKeyThatCannotHandshakeIsJustAClosedSocket() {
        val s = Socket(java.net.InetAddress.getLoopbackAddress(), acceptor.port)
        s.soTimeout = 5000
        s.getOutputStream().write(get("/v1/hello"))
        s.getOutputStream().flush()
        assertEquals(-1, s.getInputStream().read()) // closed with no answer at all
        s.close()
    }

    // ---- events -----------------------------------------------------------------------------------------------------------

    private fun readFrame(input: java.io.InputStream): Pair<Int, String>? {
        val b0 = input.read()
        if (b0 < 0) return null
        val len = input.read()
        require(len < 126)
        val payload = ByteArray(len)
        var off = 0
        while (off < len) off += input.read(payload, off, len - off)
        return (b0 and 0x0f) to String(payload, Charsets.UTF_8)
    }

    @Test fun eventsStartWithOurVectorAndEndWhenTheDeviceIsRemoved() {
        pairFriend(NetAction.MetadataRead)
        acceptor.next(friend.spki)
        val s = Socket(java.net.InetAddress.getLoopbackAddress(), acceptor.port)
        s.soTimeout = 10_000
        val key = Base64.getEncoder().encodeToString(ByteArray(16) { it.toByte() })
        s.getOutputStream().write(get("/v1/events", "upgrade" to "websocket", "connection" to "Upgrade", "sec-websocket-version" to "13", "sec-websocket-key" to key))
        s.getOutputStream().flush()
        val reply = readReply(s.getInputStream().let { PushbackHead(it) })
        assertEquals(101, reply.status)
        assertEquals(WebSocketHandshake.acceptKey(key), reply.headers["sec-websocket-accept"])
        val (opcode, text) = readFrame(s.getInputStream())!!
        assertEquals(1, opcode)
        val msg = JSONObject(text)
        assertEquals("segments", msg.getString("t"))
        assertEquals(LIB, msg.getString("library"))
        // removing the device closes its stream at once
        server.dropPeer(friend.deviceId)
        assertEquals(-1, runCatching { s.getInputStream().read() }.getOrDefault(-1))
        s.close()
    }

    @Test fun aStreamForADeviceWithoutReadAccessGetsNoNotices() {
        pairFriend(NetAction.MediaRead) // hello is allowed, metadata is not
        acceptor.next(friend.spki)
        val s = Socket(java.net.InetAddress.getLoopbackAddress(), acceptor.port)
        s.soTimeout = 1500
        val key = Base64.getEncoder().encodeToString(ByteArray(16) { 9 })
        s.getOutputStream().write(get("/v1/events", "upgrade" to "websocket", "connection" to "Upgrade", "sec-websocket-version" to "13", "sec-websocket-key" to key))
        s.getOutputStream().flush()
        assertEquals(101, readReply(PushbackHead(s.getInputStream())).status)
        val frame = try { readFrame(s.getInputStream()) } catch (e: java.net.SocketTimeoutException) { null }
        assertEquals(null, frame)
        s.close()
    }
}

/** Reads the head of a 101 answer, which has no body, one byte at a time so the frames after it stay unread. */
private class PushbackHead(private val inner: java.io.InputStream) : java.io.InputStream() {
    override fun read(): Int = inner.read()
}
