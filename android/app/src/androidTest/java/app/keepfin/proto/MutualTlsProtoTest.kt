package app.keepfin.proto

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Phase 0 prototype A: mutual TLS 1.3 with pinned SPKI hashes, keys held by AndroidKeyStore. */
@RunWith(AndroidJUnit4::class)
class MutualTlsProtoTest {
    private val tag = "KeepfinProto"

    /** Accepts exactly one peer public key (SHA-256 of SubjectPublicKeyInfo), compared in constant time. */
    private class PinnedTrust(private val pin: ByteArray) : X509TrustManager {
        private fun check(chain: Array<X509Certificate>?) {
            Log.i("KeepfinProto", "A: trust check chain=${chain?.size}")
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            val h = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
            if (!MessageDigest.isEqual(h, pin)) throw CertificateException("key not pinned")
        }
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = check(chain)
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) = check(chain)
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun makeKey(alias: String) {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(alias)) ks.deleteEntry(alias)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_NONE)
            .setCertificateSubject(X500Principal("CN=keepfin-$alias"))
            .setCertificateNotBefore(java.util.Date(System.currentTimeMillis() - 86_400_000L))
            .setCertificateNotAfter(java.util.Date(System.currentTimeMillis() + 10L * 365 * 86_400_000L))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
    }

    private fun spki(alias: String): ByteArray {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cert = ks.getCertificate(alias) as X509Certificate
        return MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
    }

    private fun ctx(alias: String, pinnedPeer: ByteArray): SSLContext {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, null) }
        val base = kmf.keyManagers.filterIsInstance<X509KeyManager>().first()
        // Always present the one alias we want (the store holds several test keys).
        val only = object : X509ExtendedKeyManager() {
            override fun getClientAliases(k: String?, i: Array<out java.security.Principal>?) = arrayOf(alias)
            override fun chooseClientAlias(k: Array<out String>?, i: Array<out java.security.Principal>?, s: java.net.Socket?) = alias
            override fun getServerAliases(k: String?, i: Array<out java.security.Principal>?) = arrayOf(alias)
            override fun chooseServerAlias(k: String?, i: Array<out java.security.Principal>?, s: java.net.Socket?) = alias
            override fun getCertificateChain(a: String?) = base.getCertificateChain(a)
            override fun getPrivateKey(a: String?) = base.getPrivateKey(a).also { Log.i("KeepfinProto", "A: getPrivateKey $a -> ${it?.javaClass?.simpleName}") }
        }
        return SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(only), arrayOf(PinnedTrust(pinnedPeer)), SecureRandom()) }
    }

    private fun readLine(i: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0 || c == 10) break
            if (c != 13) sb.append(c.toChar())
        }
        return sb.toString()
    }

    /** Tiny HTTP/1.1 server: GET /hello returns "hi"; GET /big?n=bytes returns n zero bytes. */
    private inner class Server(val sock: SSLServerSocket) {
        val port get() = sock.localPort
        init {
            Thread {
                while (!sock.isClosed) {
                    try {
                        val s = sock.accept() as SSLSocket
                        Thread { serve(s) }.start()
                    } catch (_: Exception) { }
                }
            }.also { it.isDaemon = true }.start()
        }
        fun close() = sock.close()
    }

    private fun serve(s: SSLSocket) {
        try {
            s.startHandshake()
            val i = s.inputStream
            val o = s.outputStream
            val req = readLine(i)
            while (readLine(i).isNotEmpty()) { }
            if (req.startsWith("GET /big?n=")) {
                val n = req.substringAfter("n=").substringBefore(' ').toLong()
                o.write("HTTP/1.1 200 OK\r\nContent-Length: $n\r\nConnection: close\r\n\r\n".toByteArray())
                val buf = ByteArray(64 * 1024)
                var left = n
                while (left > 0) {
                    val k = minOf(left, buf.size.toLong()).toInt()
                    o.write(buf, 0, k); left -= k
                }
            } else {
                o.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nhi".toByteArray())
            }
            o.flush(); s.close()
        } catch (e: Exception) { Log.i(tag, "A: server side: ${e.javaClass.simpleName}: ${e.message?.take(160)}"); runCatching { s.close() } }
    }

    private fun startServer(c: SSLContext): Server {
        val ss = c.serverSocketFactory.createServerSocket() as SSLServerSocket
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
        ss.needClientAuth = true
        ss.enabledProtocols = arrayOf("TLSv1.3")
        return Server(ss)
    }

    private fun connect(c: SSLContext, port: Int, protocols: Array<String> = arrayOf("TLSv1.3")): SSLSocket {
        val s = c.socketFactory.createSocket() as SSLSocket
        s.enabledProtocols = protocols
        s.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 5000)
        s.soTimeout = 15000
        s.startHandshake()
        return s
    }

    private fun get(s: SSLSocket, path: String): String {
        s.outputStream.write("GET $path HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); s.outputStream.flush()
        val i = s.inputStream
        val status = readLine(i)
        var len = 0L
        while (true) {
            val h = readLine(i)
            if (h.isEmpty()) break
            if (h.startsWith("Content-Length:")) len = h.substringAfter(":").trim().toLong()
        }
        val body = ByteArray(len.toInt())
        var off = 0
        while (off < body.size) { val k = i.read(body, off, body.size - off); if (k < 0) break; off += k }
        return status + "|" + String(body)
    }

    /** True when the block throws (TLS 1.3 client cert failures surface on first read, so blocks do an exchange). */
    private fun rejected(what: String, block: () -> Unit): Boolean = try {
        block(); false
    } catch (e: Exception) {
        Log.i(tag, "A: $what rejected as expected: ${e.javaClass.simpleName}: ${e.message?.take(100)}"); true
    }

    private fun sas(hA: ByteArray, nA: ByteArray, hB: ByteArray, nB: ByteArray): String {
        val parts = listOf(hA + nA, hB + nB).sortedWith { a, b -> java.util.Arrays.compare(a, b) }
        val d = MessageDigest.getInstance("SHA-256").digest(parts[0] + parts[1])
        val m = java.math.BigInteger(1, d).mod(java.math.BigInteger.valueOf(1_000_000)).toInt()
        return "%06d".format(m)
    }

    @Test fun mutualTlsPinned() {
        Log.i(tag, "A: API ${Build.VERSION.SDK_INT} model=${Build.MODEL} fingerprint=${Build.FINGERPRINT}")
        makeKey("proto_server"); makeKey("proto_client"); makeKey("proto_other")
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val priv = ks.getKey("proto_server", null)
        val info = runCatching {
            val i = KeyFactory.getInstance(priv.algorithm, "AndroidKeyStore").getKeySpec(priv, KeyInfo::class.java)
            "insideSecureHardware=${i.isInsideSecureHardware} securityLevel=${if (Build.VERSION.SDK_INT >= 31) i.securityLevel else -1}"
        }.getOrElse { it.toString() }
        Log.i(tag, "A: AndroidKeyStore private key class=${priv.javaClass.name} exportable=${priv.encoded != null} $info")
        val hS = spki("proto_server"); val hC = spki("proto_client"); val hO = spki("proto_other")

        val server = startServer(ctx("proto_server", hC))
        try {
            val cli = ctx("proto_client", hS)
            val s = try { connect(cli, server.port) } catch (e: Exception) { Log.e(tag, "A: first connect failed", e); throw e }
            Log.i(tag, "A: negotiated ${s.session.protocol} ${s.session.cipherSuite}")
            assertEquals("TLSv1.3", s.session.protocol)
            assertEquals("HTTP/1.1 200 OK|hi", get(s, "/hello"))
            s.close()

            assertTrue("unpinned client must fail", rejected("client with other key") {
                val b = connect(ctx("proto_other", hS), server.port); get(b, "/hello"); b.close()
            })
            assertTrue("server with other key must be rejected", rejected("pin mismatch on server key") {
                val b = connect(ctx("proto_client", hO), server.port); b.close()
            })
            assertTrue("TLS1.2 must be refused", rejected("TLS 1.2") {
                val b = connect(cli, server.port, arrayOf("TLSv1.2")); get(b, "/hello"); b.close()
            })

            val hsOnly = ArrayList<Double>(); val full = ArrayList<Double>()
            repeat(3) { connect(cli, server.port).close() }
            repeat(20) {
                val t0 = System.nanoTime()
                val b = connect(cli, server.port)
                val t1 = System.nanoTime()
                get(b, "/hello")
                val t2 = System.nanoTime()
                b.close()
                hsOnly += (t1 - t0) / 1e6; full += (t2 - t0) / 1e6
            }
            hsOnly.sort(); full.sort()
            fun med(l: List<Double>) = "%.2f".format((l[9] + l[10]) / 2)
            Log.i(tag, "A: TCP connect+handshake (client side) median=${med(hsOnly)} ms min=${"%.2f".format(hsOnly[0])} max=${"%.2f".format(hsOnly[19])}; connect+handshake+tiny request median=${med(full)} ms (n=20)")

            val total = 50L * 1024 * 1024
            val speeds = ArrayList<Double>()
            repeat(3) {
                val b = connect(cli, server.port)
                val t0 = System.nanoTime()
                b.outputStream.write("GET /big?n=$total HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); b.outputStream.flush()
                val i = b.inputStream
                readLine(i); while (readLine(i).isNotEmpty()) { }
                val buf = ByteArray(64 * 1024)
                var got = 0L
                while (got < total) { val k = i.read(buf); if (k < 0) break; got += k }
                val sec = (System.nanoTime() - t0) / 1e9
                assertEquals(total, got)
                speeds += total / 1048576.0 / sec
                b.close()
            }
            Log.i(tag, "A: 50 MB loopback TLS1.3 throughput MB/s, 3 runs = ${speeds.joinToString { "%.1f".format(it) }}")
        } finally { server.close() }

        // A different server key (not the pinned one) is rejected by the pinned client.
        val otherServer = startServer(ctx("proto_other", hC))
        try {
            assertTrue("client must reject different server", rejected("server presenting other key") {
                val b = connect(ctx("proto_client", hS), otherServer.port); b.close()
            })
        } finally { otherServer.close() }

        val rnd = SecureRandom()
        val nA = ByteArray(16).also(rnd::nextBytes); val nB = ByteArray(16).also(rnd::nextBytes)
        val codePhone = sas(hS, nA, hC, nB); val codePc = sas(hC, nB, hS, nA)
        Log.i(tag, "A: SAS phone=$codePhone pc=$codePc")
        assertEquals(codePhone, codePc)
        assertEquals(6, codePhone.length)
        val tampered = sas(hS, nA, hO, nB)
        Log.i(tag, "A: SAS when peer key differs = $tampered (differs=${tampered != codePhone})")
        assertFalse(tampered == codePhone)
        Log.i(tag, "A: ALL ASSERTIONS PASSED")
    }
}
