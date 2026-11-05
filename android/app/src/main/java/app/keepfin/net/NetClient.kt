package app.keepfin.net

import app.keepfin.core.net.deviceIdOf
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import okhttp3.ConnectionSpec
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.BufferedSink
import okio.source
import org.json.JSONObject

// The network client (spec 9.1 #2): one pinned TLS 1.3 connection pool to one peer. The pin is checked in the trust manager of every new
// socket before any byte is written; an unpinned client (pairing by address) accepts the first key it sees and then insists on that
// same key. Port of gui/desktop/net/client.ts over OkHttp (HTTP/1.1 only, no sessions, no redirects, no proxies).

class PeerHttpError(val status: Int, val code: String, message: String, val body: JSONObject? = null) : Exception(message)

class ClientOptions(
    val identity: DeviceIdentity,
    val host: String,
    val port: Int,
    /** The device id (SHA-256 of the SPKI) the peer must have, or null to accept the first key and keep it. */
    val pin: String?,
    val connectTimeoutMs: Long = 10_000,
    val keepAliveMs: Long = 15_000,
    /** The first 30 bits of the key's pin that a typed code carried: a key that does not start with them is refused in the handshake, before any request is written. */
    val pinBits: Int? = null,
)

class PeerClient(private val options: ClientOptions) {
    private val trust = PinTrust(options.pin, options.pinBits)
    private val ssl = Tls.clientContext(options.identity, trust)
    private val http: OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(ssl.socketFactory, trust)
        .hostnameVerifier { _, _ -> true } // the pin is the trust; the handshake still proves the peer holds the key
        .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.RESTRICTED_TLS).tlsVersions(TlsVersion.TLS_1_3).build()))
        .protocols(listOf(Protocol.HTTP_1_1))
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .proxy(java.net.Proxy.NO_PROXY)
        .connectTimeout(options.connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(0, TimeUnit.MILLISECONDS)
        .connectionPool(okhttp3.ConnectionPool(4, options.keepAliveMs, TimeUnit.MILLISECONDS))
        .build()

    val host: String get() = options.host
    val port: Int get() = options.port
    val peerSpki: ByteArray? get() = trust.seenSpki
    val peerDeviceId: String? get() = trust.seenSpki?.let { deviceIdOf(it) }

    private fun url(path: String): HttpUrl {
        val base = HttpUrl.Builder().scheme("https").host(options.host).port(options.port).build()
        return base.resolve(path) ?: throw IllegalArgumentException("Bad path.")
    }

    private fun <T> guard(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw translate(e)
    }

    private fun translate(e: IOException): Exception {
        if (trust.mismatch) return PinMismatch()
        if (e is SSLException && e.cause is java.security.cert.CertificateException && e.cause?.message == "key not pinned") return PinMismatch()
        if (e is SocketTimeoutException) return IOException("The device did not answer.", e)
        return e
    }

    /** Sends a request; the caller closes the response. [body] may be a stream with a known [contentLength]. */
    fun request(
        method: String,
        path: String,
        json: JSONObject? = null,
        body: InputStream? = null,
        contentLength: Long = -1,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = 30_000,
    ): Response {
        val payload: RequestBody? = when {
            json != null -> json.toString().toRequestBody("application/json".toMediaType())
            body != null -> object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = contentLength
                override fun writeTo(sink: BufferedSink) {
                    body.source().use { sink.writeAll(it) }
                }
            }
            method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody(null)
            else -> null
        }
        val builder = Request.Builder().url(url(path)).method(method, payload)
        for ((k, v) in headers) builder.header(k, v)
        val call = http.newBuilder().readTimeout(timeoutMs, TimeUnit.MILLISECONDS).build().newCall(builder.build())
        return guard { call.execute() }
    }

    /** A request whose answer is JSON; any status of 400 or more throws [PeerHttpError] with the peer's words. */
    fun json(method: String, path: String, json: JSONObject? = null, timeoutMs: Long = 30_000): JSONObject {
        request(method, path, json = json, timeoutMs = timeoutMs).use { response ->
            val bytes = guard { readLimited(response.body!!.byteStream(), 4 * 1024 * 1024) }
            val parsed = try {
                if (bytes.isEmpty()) JSONObject() else JSONObject(String(bytes, Charsets.UTF_8))
            } catch (e: Exception) {
                JSONObject()
            }
            if (response.code >= 400) {
                throw PeerHttpError(response.code, parsed.optString("error", "error"), parsed.optString("message", "The device answered ${response.code}."), parsed)
            }
            return parsed
        }
    }

    /** The WebSocket of change notices. */
    fun events(onMessage: (String) -> Unit, onClose: () -> Unit): WebSocket {
        val client = http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(30, TimeUnit.SECONDS).build()
        val request = Request.Builder().url(url("/v1/events")).build()
        return client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = onMessage(text)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onClose()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onClose()
        })
    }

    fun close() {
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    companion object {
        fun readLimited(input: InputStream, max: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > max) throw IOException("The answer is too large.")
            }
            return out.toByteArray()
        }

        /** Whether a failure is the network's (try again later) rather than the peer's answer. */
        fun isNetworkFailure(e: Throwable): Boolean = e is IOException
    }
}

