package app.keepfin.net

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** The address text of a peer: IPv4-mapped IPv6 addresses are shown as IPv4, a scope id is dropped. */
fun addressOf(socket: Socket): String {
    val raw = socket.inetAddress?.hostAddress ?: return ""
    val noScope = raw.substringBefore('%')
    return if (noScope.startsWith("::ffff:") && noScope.count { it == '.' } == 3) noScope.removePrefix("::ffff:") else noScope
}

/**
 * The server socket (spec 4: "the Android server runs as SSLServerSocket with client auth plus a small HTTP layer"). TLS 1.3 only,
 * a client certificate is required, and the pin is decided by the server after the handshake from the key the client proved. The
 * port is fixed (47215): a conflict is reported, never worked around with an ephemeral port (spec 9.1 #10), so away-from-home
 * addresses stay valid.
 */
class TlsAcceptor private constructor(private val server: ServerSocket) : Acceptor {
    override val port: Int get() = server.localPort

    override fun accept(): Accepted {
        val socket = server.accept() as SSLSocket
        return Accepted(socket, addressOf(socket)) {
            socket.startHandshake()
            val cert = socket.session.peerCertificates.firstOrNull() as? X509Certificate ?: throw IOException("no client certificate")
            cert.publicKey.encoded
        }
    }

    override fun close() {
        runCatching { server.close() }
    }

    companion object {
        /** Binds [port] on all addresses. Throws IOException when another program holds the port. */
        fun bind(context: SSLContext, port: Int = DEFAULT_PORT): TlsAcceptor {
            val server = context.serverSocketFactory.createServerSocket() as SSLServerSocket
            try {
                server.reuseAddress = true
                server.needClientAuth = true
                server.enabledProtocols = arrayOf("TLSv1.3")
                server.bind(InetSocketAddress(port), 64)
            } catch (e: IOException) {
                runCatching { server.close() }
                throw e
            }
            return TlsAcceptor(server)
        }
    }
}
