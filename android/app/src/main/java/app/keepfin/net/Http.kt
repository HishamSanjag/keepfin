package app.keepfin.net

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import org.json.JSONObject

// A small, strict HTTP/1.1 layer for the network server (spec 9.1 #7: "the HTTP layer is minimal and fuzzed"). It is the
// parsing half of what Node's own parser does on the desktop. Rules, all enforced before any handler runs:
//   - a request line `METHOD SP target SP HTTP/1.1`, CRLF line ends only, methods GET HEAD POST PUT only;
//   - header names are tokens, values hold no control characters, no obsolete line folding, no repeated header names;
//   - the whole head fits in [Limits.maxHeaderBytes] and [Limits.maxHeaders] lines, and arrives within [Limits.headersMs];
//   - a body has a Content-Length (digits only); Transfer-Encoding is refused (411), so a body is never ambiguous;
//   - a target is origin-form (`/path?query`), percent-decoding is strict UTF-8.
// Anything else is an [HttpError] with a status, and the connection is closed after the answer.

class HttpRequest(
    val method: String,
    val target: String,
    val path: String,
    val query: Map<String, String>,
    /** Lower-case names. */
    val headers: Map<String, String>,
    /** The declared body length, or 0. */
    val contentLength: Long,
) {
    val segments: List<String> get() = path.split('/').filter { it.isNotEmpty() }
    fun header(name: String): String? = headers[name.lowercase()]
}

private val ALLOWED_METHODS = setOf("GET", "HEAD", "POST", "PUT")

private fun isTokenChar(c: Int): Boolean =
    c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || "!#$%&'*+-.^_`|~".indexOf(c.toChar()) >= 0

class HttpParser(private val limits: Limits) {
    /**
     * Reads one request head. Null when the peer closed the connection cleanly before a new request. [socket] gets the timeouts: the
     * idle timeout for the first byte, the headers deadline for the rest (and the elapsed time is checked per byte, so a trickle
     * of bytes cannot keep a connection open past [Limits.headersMs]).
     */
    fun readHead(input: InputStream, socket: Socket?): HttpRequest? {
        socket?.soTimeout = limits.idleMs
        val first = try {
            input.read()
        } catch (e: SocketTimeoutException) {
            return null
        }
        if (first < 0) return null
        socket?.soTimeout = limits.headersMs
        val started = System.nanoTime()
        val deadline = started + limits.headersMs * 1_000_000L
        var total = 1
        fun next(): Int {
            if (System.nanoTime() > deadline) throw HttpError(408, "slow", "The request head is arriving too slowly.")
            val c = input.read()
            if (c < 0) throw IOException("closed inside the request head")
            if (++total > limits.maxHeaderBytes) throw HttpError(431, "header-size", "The request head is too large.")
            return c
        }

        /** One line up to CRLF, as Latin-1 text (bytes above 0x7e are refused later by the field rules). */
        fun line(firstByte: Int): String {
            val sb = StringBuilder(80)
            var c = firstByte
            while (true) {
                if (c == '\r'.code) {
                    if (next() != '\n'.code) throw HttpError(400, "line-end", "A line ends without a line feed.")
                    return sb.toString()
                }
                if (c == '\n'.code) throw HttpError(400, "line-end", "A line ends without a carriage return.")
                sb.append(c.toChar())
                c = next()
            }
        }

        val requestLine = line(first)
        val parts = requestLine.split(' ')
        if (parts.size != 3) throw HttpError(400, "request-line", "The request line is not valid.")
        val method = parts[0]
        val target = parts[1]
        if (method !in ALLOWED_METHODS) throw HttpError(405, "method", "That method is not supported.")
        if (parts[2] != "HTTP/1.1") throw HttpError(505, "version", "Only HTTP/1.1 is supported.")
        if (!target.startsWith("/") || target.length > limits.maxTargetChars) throw HttpError(400, "target", "The request target is not valid.")
        for (ch in target) if (ch.code <= 0x20 || ch.code >= 0x7f || ch == '#') throw HttpError(400, "target", "The request target is not valid.")

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val h = line(next())
            if (h.isEmpty()) break
            if (headers.size >= limits.maxHeaders) throw HttpError(431, "headers", "Too many headers.")
            if (h[0] == ' ' || h[0] == '\t') throw HttpError(400, "fold", "Folded header lines are not accepted.")
            val colon = h.indexOf(':')
            if (colon <= 0) throw HttpError(400, "header", "A header line is not valid.")
            val name = h.substring(0, colon)
            for (ch in name) if (!isTokenChar(ch.code)) throw HttpError(400, "header-name", "A header name is not valid.")
            val value = h.substring(colon + 1).trim(' ', '\t')
            for (ch in value) if ((ch.code < 0x20 && ch != '\t') || ch.code == 0x7f || ch.code > 0xff) throw HttpError(400, "header-value", "A header value is not valid.")
            val key = name.lowercase()
            if (headers.put(key, value) != null) throw HttpError(400, "duplicate-header", "A header was sent twice.")
        }
        if (headers.containsKey("transfer-encoding")) throw HttpError(411, "length", "A body needs a Content-Length.")
        var contentLength = 0L
        headers["content-length"]?.let {
            if (it.isEmpty() || it.length > 18 || !it.all { c -> c in '0'..'9' }) throw HttpError(400, "content-length", "The Content-Length is not valid.")
            contentLength = it.toLong()
        }
        if (contentLength > 0 && (method == "GET" || method == "HEAD")) throw HttpError(400, "body", "This request cannot have a body.")

        val q = target.indexOf('?')
        val rawPath = if (q < 0) target else target.substring(0, q)
        val rawQuery = if (q < 0) "" else target.substring(q + 1)
        val path = percentDecode(rawPath, plus = false)
        if (path.contains('\u0000')) throw HttpError(400, "target", "The request target is not valid.")
        val query = LinkedHashMap<String, String>()
        if (rawQuery.isNotEmpty()) {
            val pieces = rawQuery.split('&')
            if (pieces.size > 32) throw HttpError(400, "query", "The query has too many parts.")
            for (piece in pieces) {
                if (piece.isEmpty()) continue
                val eq = piece.indexOf('=')
                val key = percentDecode(if (eq < 0) piece else piece.substring(0, eq), plus = true)
                val value = if (eq < 0) "" else percentDecode(piece.substring(eq + 1), plus = true)
                if (!query.containsKey(key)) query[key] = value
            }
        }
        return HttpRequest(method, target, path, query, headers, contentLength)
    }
}

/** Strict percent-decoding to UTF-8: a lone `%`, bad hex or invalid UTF-8 is a 400. */
fun percentDecode(text: String, plus: Boolean): String {
    if (text.indexOf('%') < 0 && (!plus || text.indexOf('+') < 0)) return text
    val out = ByteArrayOutputStream(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '%' -> {
                if (i + 2 >= text.length) throw HttpError(400, "percent", "The request target is not valid.")
                val hi = Character.digit(text[i + 1], 16)
                val lo = Character.digit(text[i + 2], 16)
                if (hi < 0 || lo < 0) throw HttpError(400, "percent", "The request target is not valid.")
                out.write(hi * 16 + lo)
                i += 3
            }
            plus && c == '+' -> {
                out.write(' '.code)
                i++
            }
            c.code < 0x80 -> {
                out.write(c.code)
                i++
            }
            else -> throw HttpError(400, "target", "The request target is not valid.")
        }
    }
    val bytes = out.toByteArray()
    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    return try {
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) {
        throw HttpError(400, "utf8", "The request target is not valid.")
    }
}

/** A request body of a declared length: reads stop at the end, and what the handler left unread can be drained for the next request. */
class LimitedBody(private val input: InputStream, length: Long) : InputStream() {
    var remaining: Long = length
        private set

    override fun read(): Int {
        if (remaining <= 0) return -1
        val c = input.read()
        if (c < 0) throw IOException("the body ended early")
        remaining--
        return c
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (remaining <= 0) return -1
        val n = input.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n < 0) throw IOException("the body ended early")
        remaining -= n
        return n
    }

    override fun available(): Int = minOf(input.available().toLong(), remaining).toInt()

    /** True when the whole body was consumed (or was drained within [maxBytes]). */
    fun drain(maxBytes: Long): Boolean {
        if (remaining > maxBytes) return false
        val buf = ByteArray(8192)
        while (remaining > 0) if (read(buf, 0, buf.size) < 0) break
        return remaining == 0L
    }

    /** Reads the whole body into memory, at most [max] bytes and not slower than [minBytesPerSecond] after a grace period. */
    fun readAll(max: Int, minBytesPerSecond: Long, now: () -> Long): ByteArray {
        if (remaining > max) throw HttpError(413, "too-large", "The request is too large.")
        val out = ByteArrayOutputStream(remaining.toInt().coerceAtLeast(16))
        val buf = ByteArray(8192)
        val started = now()
        var total = 0L
        while (true) {
            val n = read(buf, 0, buf.size)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
            val elapsed = now() - started
            if (elapsed > 5000 && total / (elapsed / 1000.0) < minBytesPerSecond) throw HttpError(408, "slow", "The request is arriving too slowly.")
        }
        return out.toByteArray()
    }
}

private val STATUS_TEXT = mapOf(
    101 to "Switching Protocols", 200 to "OK", 201 to "Created", 204 to "No Content", 206 to "Partial Content",
    400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed", 408 to "Request Timeout",
    409 to "Conflict", 410 to "Gone", 411 to "Length Required", 412 to "Precondition Failed", 413 to "Content Too Large", 414 to "URI Too Long",
    416 to "Range Not Satisfiable", 422 to "Unprocessable Content", 426 to "Upgrade Required", 429 to "Too Many Requests",
    431 to "Request Header Fields Too Large", 500 to "Internal Server Error", 501 to "Not Implemented", 505 to "HTTP Version Not Supported",
    507 to "Insufficient Storage",
)

class HttpResponder(private val out: OutputStream, private val headOnly: Boolean) {
    var headersSent = false
        private set
    var bytesSent = 0L
        private set

    /** Writes the status line and headers. [length] is the body length; HEAD responses declare it and send no body. */
    fun head(status: Int, headers: Map<String, String>, length: Long?) {
        check(!headersSent)
        val sb = StringBuilder(256)
        sb.append("HTTP/1.1 ").append(status).append(' ').append(STATUS_TEXT[status] ?: "Status").append("\r\n")
        for ((k, v) in headers) {
            if (v.any { it == '\r' || it == '\n' }) continue
            sb.append(k).append(": ").append(v).append("\r\n")
        }
        if (length != null) sb.append("content-length: ").append(length).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        headersSent = true
    }

    fun write(bytes: ByteArray, off: Int = 0, len: Int = bytes.size) {
        if (headOnly) return
        out.write(bytes, off, len)
        bytesSent += len
    }

    fun flush() = out.flush()

    /** A streamed body of unknown length: `Transfer-Encoding: chunked`. */
    fun beginChunked(status: Int, headers: Map<String, String>) {
        head(status, headers + ("transfer-encoding" to "chunked"), null)
    }

    fun chunk(bytes: ByteArray, off: Int = 0, len: Int = bytes.size) {
        if (headOnly || len == 0) return
        out.write((Integer.toHexString(len) + "\r\n").toByteArray(Charsets.ISO_8859_1))
        out.write(bytes, off, len)
        out.write(byteArrayOf(13, 10))
        bytesSent += len
    }

    fun endChunked() {
        if (!headOnly) out.write("0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    /** A complete answer with a fixed body. */
    fun send(status: Int, headers: Map<String, String>, body: ByteArray) {
        head(status, headers, body.size.toLong())
        write(body)
        flush()
    }

    fun json(status: Int, value: JSONObject, close: Boolean = false) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8)
        val h = linkedMapOf("content-type" to "application/json", "cache-control" to "no-store")
        if (close) h["connection"] = "close"
        send(status, h, bytes)
    }
}
