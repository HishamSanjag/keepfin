package app.keepfin.cloud

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The account a request runs for. Tokens are the caller's business; the executor only asks for the header. */
internal interface CloudAccount {
    val id: String
    val provider: String

    /** The Authorization header value, or null when there is none. Throws [CloudError] (NeedsSignIn) when the account cannot sign requests. */
    fun authorization(): String?

    /** Gets a new access token after a 401. True when a new token is available. Calls are serialised by the account. */
    fun refresh(): Boolean
}

/** Spaces requests at least `intervalMs` apart; a 429 doubles the spacing (up to a cap), successes ease it back. */
internal class RateLimiter(private val baseIntervalMs: Long = 0, private val maxIntervalMs: Long = 5000, private val now: () -> Long = System::currentTimeMillis, private val sleep: (Long) -> Unit = { Thread.sleep(it) }) {
    private var nextFree = 0L
    @Volatile var currentIntervalMs: Long = baseIntervalMs
        private set

    fun acquire() {
        val wait = synchronized(this) {
            val t = now()
            val start = maxOf(t, nextFree)
            nextFree = start + currentIntervalMs
            start - t
        }
        if (wait > 0) sleep(wait)
    }

    @Synchronized fun penalize() {
        currentIntervalMs = minOf(maxIntervalMs, maxOf(50, currentIntervalMs * 2))
    }

    @Synchronized fun relax() {
        if (currentIntervalMs > baseIntervalMs) currentIntervalMs = maxOf(baseIntervalMs, (currentIntervalMs * 0.9).toLong())
    }
}

/** What the platform adds to one send: where the bytes of a `bodyRef` come from, a stop request, and the call's context. */
internal class SendIo(
    /** The slice a request's `bodyRef` names. */
    val body: ((offset: Long, length: Long) -> RequestBody)? = null,
    val abort: () -> Boolean = { false },
    /** "upload" makes a 404 mean "the session is gone". */
    val context: String = "",
)

internal class HttpOptions(
    val client: OkHttpClient? = null,
    /** Maps a real provider URL to the URL to call (tests send everything to local fakes). */
    val rewrite: ((String) -> String)? = null,
    val sleep: (Long) -> Unit = { Thread.sleep(it) },
    val random: () -> Double = Math::random,
    val now: () -> Long = System::currentTimeMillis,
    val maxAttempts: Int = 6,
    val baseDelayMs: Long = 400,
    val maxDelayMs: Long = 30_000,
    val limiter: RateLimiter? = null,
    val transfers: Semaphore? = null,
    /** The network rule (Wi-Fi only unless the account allows mobile data): a reason when requests must wait, null when they may go. */
    val gate: () -> String? = { null },
)

internal object Http {
    /** One shared client (connection pool, no redirects: [CloudHttp] follows them itself so a credential never travels to another host). */
    val shared: OkHttpClient by lazy {
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(true)
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).callTimeout(0, TimeUnit.SECONDS).build()
    }
}

/** A slice of a file as a request body (WebDAV sends a whole file in one PUT; the file is a staging copy that is read again on a retry). */
internal class FileSliceBody(private val file: File, private val offset: Long, private val length: Long, private val abort: () -> Boolean = { false }) : RequestBody() {
    override fun contentType() = null
    override fun contentLength() = length
    override fun isOneShot() = false
    override fun writeTo(sink: BufferedSink) {
        RandomAccessFile(file, "r").use { f ->
            f.seek(offset)
            val buf = ByteArray(64 * 1024)
            var left = length
            while (left > 0) {
                if (abort()) throw CloudError(Kind.TRANSIENT, code = "paused")
                val n = f.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw IOException("The staged file ended early.")
                sink.write(buf, 0, n)
                left -= n
            }
        }
    }
}

/**
 * Executes the core's request descriptions over OkHttp (dev-docs/plans/KEEPFIN-CLOUD-SPEC.md section 4): retries with back-off and jitter
 * that honour Retry-After, one token refresh on an expired access token, a per-account rate limiter that slows down on 429, and at
 * most 4 parallel transfers per account. The core decides what a failure means; this class only waits and repeats.
 */
internal class CloudHttp(val account: CloudAccount, private val options: HttpOptions = HttpOptions()) {
    private val client = options.client ?: Http.shared
    val limiter: RateLimiter = options.limiter ?: RateLimiter(0, 5000, options.now, options.sleep)
    private val transfers = options.transfers ?: Semaphore(4)
    private val serial = Semaphore(1)
    private val refreshLock = Any()

    /** Requests actually sent, retries included (tests read it). */
    val sent = AtomicInteger()

    /** Back-off for attempt n (0-based): full jitter over an exponential window. */
    fun backoffMs(attempt: Int): Long {
        val cap = minOf(options.maxDelayMs.toDouble(), options.baseDelayMs * Math.pow(2.0, attempt.toDouble()))
        return (options.random() * cap).toLong()
    }

    /** Runs [job] as one of at most 4 parallel transfers of this account. */
    fun <T> transfer(job: () -> T): T {
        transfers.acquireUninterruptibly()
        try {
            return job()
        } finally {
            transfers.release()
        }
    }

    private fun refreshOnce(): Boolean = synchronized(refreshLock) { account.refresh() }

    private fun execute(req: CloudRequest, io: SendIo): Response {
        options.gate()?.let { throw CloudError(Kind.TRANSIENT, code = "waiting-for-network", info = it) }
        if (io.abort()) throw CloudError(Kind.TRANSIENT, code = "paused")
        val headers = LinkedHashMap<String, String>()
        for ((k, v) in req.headers) if (!k.equals("content-length", true)) headers[k] = v
        if (req.auth) account.authorization()?.let { headers["Authorization"] = it }
        var url = options.rewrite?.invoke(req.url) ?: req.url
        var method = req.method
        var body: RequestBody? = when {
            req.bodyOffset != null -> (io.body ?: throw IllegalStateException("the request names a file slice but no reader was given"))(req.bodyOffset, req.bodyLength ?: 0L)
            req.body != null -> req.body.toRequestBody(null)
            else -> null
        }
        if (body == null && (method == "POST" || method == "PUT" || method == "PATCH")) body = ByteArray(0).toRequestBody(null)
        for (hop in 0..5) {
            val b = Request.Builder().url(url)
            for ((k, v) in headers) b.header(k, v)
            val withBody = if (method == "GET" || method == "HEAD") null else body
            b.method(method, withBody)
            sent.incrementAndGet()
            val resp = client.newCall(b.build()).execute()
            val loc = resp.header("Location")
            if (req.manualRedirect || loc == null || resp.code !in intArrayOf(301, 302, 303, 307, 308)) return resp
            resp.close()
            val next = resp.request.url.resolve(loc)?.toString() ?: return resp
            if (resp.request.url.host != next.toHttpHost()) headers.remove("Authorization")
            url = next
            if (resp.code == 301 || resp.code == 302 || resp.code == 303) {
                method = if (method == "HEAD") "HEAD" else "GET"
                body = null
            }
        }
        throw CloudError(Kind.FATAL, code = "too-many-redirects")
    }

    private fun String.toHttpHost(): String = runCatching { java.net.URI(this).host ?: "" }.getOrDefault("")

    private fun lower(r: Response): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (name in r.headers.names()) out[name.lowercase()] = r.headers.values(name).joinToString(", ")
        return out
    }

    /** Parses a failed response with the core. */
    private fun failureOf(response: CloudResponse, context: String): CloudError {
        val parsed = coreCall(account.provider, "parseDone", JSONObject().put("response", response.json()).put("context", context))
        return if (!parsed.optBoolean("ok", true) && parsed.has("error")) CloudError.of(parsed.getJSONObject("error")) else CloudError(Kind.FATAL, response.status)
    }

    /**
     * Sends [req] and returns the final response (any status below 400 is success to the caller; so are the statuses in [accept]).
     * Retries rate limits, transient failures and network errors. The failure is returned, not thrown, once attempts run out or it is
     * not retryable, so the parsers can read it.
     */
    fun send(req: CloudRequest, io: SendIo = SendIo(), accept: List<Int> = emptyList()): CloudResponse {
        if (!req.serial) return sendNow(req, io, accept)
        serial.acquireUninterruptibly()
        try {
            return sendNow(req, io, accept)
        } finally {
            serial.release()
        }
    }

    private fun sendNow(req: CloudRequest, io: SendIo, accept: List<Int>): CloudResponse {
        val max = options.maxAttempts
        var refreshed = false
        var attempt = 0
        while (true) {
            limiter.acquire()
            val response: CloudResponse = try {
                execute(req, io).use { res ->
                    val text = if (req.manualRedirect || res.code < 200) "" else (res.body?.string() ?: "")
                    CloudResponse(res.code, lower(res), text)
                }
            } catch (e: CloudError) {
                throw e
            } catch (e: IOException) {
                if (io.abort()) throw CloudError(Kind.TRANSIENT, code = "paused")
                if (attempt + 1 >= max) throw CloudError(Kind.TRANSIENT, code = "network", info = e.message)
                options.sleep(backoffMs(attempt))
                attempt++
                continue
            }
            if (response.status < 400 || response.status in accept) {
                limiter.relax()
                return response
            }
            val info = failureOf(response, io.context)
            if (info.kind == Kind.NEEDS_SIGN_IN && info.refresh && !refreshed) {
                refreshed = true
                if (refreshOnce()) continue
            }
            val retryable = info.kind == Kind.RATE_LIMITED || info.kind == Kind.TRANSIENT
            if (info.kind == Kind.RATE_LIMITED) limiter.penalize()
            if (!retryable || attempt + 1 >= max) return response
            options.sleep(maxOf(info.retryAfterMs ?: 0L, backoffMs(attempt)))
            attempt++
        }
    }

    /** Like [send], but the body of a successful answer is handed back unread (downloads). The caller must close it. A 416 is handed back too. */
    fun stream(req: CloudRequest, io: SendIo = SendIo()): Response {
        val max = options.maxAttempts
        var refreshed = false
        var attempt = 0
        while (true) {
            limiter.acquire()
            val res: Response = try {
                execute(req, io)
            } catch (e: CloudError) {
                throw e
            } catch (e: IOException) {
                if (io.abort()) throw CloudError(Kind.TRANSIENT, code = "paused")
                if (attempt + 1 >= max) throw CloudError(Kind.TRANSIENT, code = "network", info = e.message)
                options.sleep(backoffMs(attempt))
                attempt++
                continue
            }
            if (res.code < 400 || res.code == 416) {
                limiter.relax()
                return res
            }
            val text = res.use { it.body?.string() ?: "" }
            val response = CloudResponse(res.code, lower(res), text)
            val info = failureOf(response, io.context.ifEmpty { "download" })
            if (info.kind == Kind.NEEDS_SIGN_IN && info.refresh && !refreshed) {
                refreshed = true
                if (refreshOnce()) continue
            }
            if (info.kind == Kind.RATE_LIMITED) limiter.penalize()
            val retryable = info.kind == Kind.RATE_LIMITED || info.kind == Kind.TRANSIENT
            if (!retryable || attempt + 1 >= max) throw CloudError(info.kind, info.status, info.code, info.info, info.retryAfterMs, info.refresh, info.correctOffset, "download")
            options.sleep(maxOf(info.retryAfterMs ?: 0L, backoffMs(attempt)))
            attempt++
        }
    }
}

internal val OCTET = "application/octet-stream".toMediaTypeOrNull()
