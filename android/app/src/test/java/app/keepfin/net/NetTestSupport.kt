package app.keepfin.net

import app.keepfin.core.net.DeviceSigner
import app.keepfin.core.net.deviceIdOf
import app.keepfin.folder.FakeFolderAccess
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.MemberGrant
import app.keepfin.folder.MembershipView
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.ConcurrentLinkedQueue
import javax.net.ssl.X509KeyManager

/** A device identity held in software (a P-256 key made in the test), for the tests that need no TLS. */
class SoftIdentity(override var name: String = "Test phone") : DeviceIdentity {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    override val spki: ByteArray = pair.public.encoded
    override val deviceId: String = deviceIdOf(spki)
    override val signer: DeviceSigner = object : DeviceSigner {
        override val spki: ByteArray get() = this@SoftIdentity.spki
        override fun sign(message: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(message)
            sign()
        }
    }

    override fun keyManager(): X509KeyManager = throw UnsupportedOperationException("a software identity has no certificate")
}

fun tempDir(prefix: String = "keepfin-net"): File = Files.createTempDirectory(prefix).toFile()

const val LIB = "11111111-1111-4111-8111-111111111111"
const val ACTOR_A = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
const val ACTOR_B = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

/** A library in memory for the server tests: segments are given, content is looked up by id. */
class FakeNetLibrary(
    override val libraryId: String = LIB,
    override val name: String = "Test library",
    override val folder: FolderAccess = FakeFolderAccess(),
) : NetLibrary {
    @Volatile override var isOpen: Boolean = true
    var actorId = ACTOR_A
    val content = HashMap<String, ByteArray>()
    var flushes = 0
    private val listeners = ArrayList<() -> Unit>()

    override fun actor(): String = actorId
    override fun flush() { flushes++ }
    override fun rescan() {}
    override fun onChange(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }
    fun changed() = listeners.toList().forEach { it() }
    override fun membership(): MembershipView = MembershipView.INACTIVE
    override fun ensureMembership(grant: MemberGrant?) {}
    override fun grantMember(actor: String, spkiBase64: String, perms: List<String>) {}
    override fun revokeMember(actor: String, cutoffSeq: Long) {}
    override fun segments(): List<SegInfo> = SegmentFiles.list(folder)
    override fun findContent(contentId: String): ContentFile? {
        val bytes = content[contentId] ?: return null
        return ContentFile(bytes.size.toLong()) { offset -> bytes.inputStream().also { it.skip(offset) } }
    }
}

/**
 * A plain-socket acceptor: the "handshake" is decided by the test, which says which identity the next connection has (the real
 * acceptor proves the key with TLS 1.3; everything after that point is the same code).
 */
class PlainAcceptor : Acceptor {
    private val server = ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
    private val identities = ConcurrentLinkedQueue<ByteArray>()
    override val port: Int get() = server.localPort

    /** The next connection is [spki]. */
    fun next(spki: ByteArray) { identities.add(spki) }

    override fun accept(): Accepted {
        val socket = server.accept()
        val spki = identities.poll() ?: ByteArray(0)
        return Accepted(socket, addressOf(socket)) { if (spki.isEmpty()) throw IOException("no identity") else spki }
    }

    override fun close() { runCatching { server.close() } }
}

/** One raw HTTP exchange against a loopback server. */
class RawReply(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    val text: String get() = String(body, Charsets.UTF_8)
}

fun rawRequest(acceptor: PlainAcceptor, spki: ByteArray, request: ByteArray, bodyBytes: ByteArray = ByteArray(0)): RawReply {
    acceptor.next(spki)
    Socket(java.net.InetAddress.getLoopbackAddress(), acceptor.port).use { s ->
        s.soTimeout = 10_000
        s.getOutputStream().write(request)
        if (bodyBytes.isNotEmpty()) s.getOutputStream().write(bodyBytes)
        s.getOutputStream().flush()
        return readReply(s.getInputStream())
    }
}

fun readReply(input: java.io.InputStream): RawReply {
    fun line(): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }
    val status = line()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: return RawReply(0, emptyMap(), ByteArray(0))
    val headers = LinkedHashMap<String, String>()
    while (true) {
        val l = line() ?: break
        if (l.isEmpty()) break
        headers[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim()
    }
    val length = headers["content-length"]?.toIntOrNull()
    val out = java.io.ByteArrayOutputStream()
    if (length != null) {
        val buf = ByteArray(length)
        var off = 0
        while (off < length) { val n = input.read(buf, off, length - off); if (n < 0) break; off += n }
        out.write(buf, 0, off)
    } else if (headers["transfer-encoding"]?.contains("chunked") == true) {
        while (true) {
            val size = (line() ?: break).trim().toIntOrNull(16) ?: break
            if (size == 0) { line(); break }
            val buf = ByteArray(size)
            var off = 0
            while (off < size) { val n = input.read(buf, off, size - off); if (n < 0) break; off += n }
            out.write(buf, 0, off)
            line()
        }
    }
    return RawReply(status, headers, out.toByteArray())
}

fun get(path: String, vararg headers: Pair<String, String>): ByteArray =
    ("GET $path HTTP/1.1\r\nhost: x\r\n" + headers.joinToString("") { "${it.first}: ${it.second}\r\n" } + "\r\n").toByteArray()

fun post(path: String, body: String): ByteArray =
    ("POST $path HTTP/1.1\r\nhost: x\r\ncontent-type: application/json\r\ncontent-length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray()
