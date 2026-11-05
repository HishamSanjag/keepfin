package app.keepfin.net

import java.io.IOException
import java.io.InputStream
import okhttp3.Response
import org.json.JSONObject

/** Why a request to the other device did not do its work (mirrors `PeerTransportError` of gui/desktop/net/peerTransport.ts). */
internal class PeerCopyError(val kind: Kind, val status: Int, message: String) : Exception(message) {
    enum class Kind { NOT_FOUND, EXISTS, CHANGED, LOCKED, DENIED, SPACE, HASH, UNREACHABLE, OTHER }
}

internal class PeerStat(val size: Long, val mtimeMs: Long)

internal class PeerScan(val count: Int, val complete: Boolean, val unreadable: List<String>)

/**
 * A paired device as one more transport for copies (network spec 5, copies spec 9; a port of gui/desktop/net/peerTransport.ts). The peer's
 * Keepfin folder is the place: its file list is read as a stream, its files come down by name with Range, and every change is a request that
 * the peer's own executor runs under its own place lock ([PeerExecutorImpl]). This phone never writes into the other device's folder.
 *
 * Honest capabilities: the SHA-256 content id is checked on every upload by the peer; replace, trash and move are conditional on the
 * content id the caller saw (HTTP If-Match, hashed by the peer); create never replaces; an upload starts again from the beginning.
 */
internal class PeerCopyTransport(
    /** The live connection to the device, or null when it is not connected now. */
    private val clientOf: () -> PeerClient?,
    val libraryId: String,
    /** This phone's name, shown to the other device in its lock. */
    private val device: String,
    /** Why file bytes must wait right now (mobile data), or null. Listing and bookkeeping are never held. */
    private val gate: () -> String? = { null },
) {
    @Volatile private var token: String? = null

    private val base get() = "/v1/libraries/$libraryId"

    private fun client(): PeerClient = clientOf() ?: throw PeerCopyError(PeerCopyError.Kind.UNREACHABLE, 0, "That device is not connected right now.")

    private fun <T> call(block: (PeerClient) -> T): T = try {
        block(client())
    } catch (e: PeerCopyError) {
        throw e
    } catch (e: PeerHttpError) {
        throw fromHttp(e.status, e.code, e.message ?: "The device refused.")
    } catch (e: PinMismatch) {
        throw PeerCopyError(PeerCopyError.Kind.DENIED, 0, "This is not the device that was paired (its key is different), so nothing was sent.")
    } catch (e: IOException) {
        throw PeerCopyError(PeerCopyError.Kind.UNREACHABLE, 0, e.message ?: "That device could not be reached.")
    }

    private fun fromHttp(status: Int, code: String, message: String): PeerCopyError {
        val kind = when {
            status == 404 -> PeerCopyError.Kind.NOT_FOUND
            status == 409 && code == "exists" -> PeerCopyError.Kind.EXISTS
            status == 409 -> PeerCopyError.Kind.LOCKED
            status == 412 -> PeerCopyError.Kind.CHANGED
            status == 403 -> PeerCopyError.Kind.DENIED
            status == 507 -> PeerCopyError.Kind.SPACE
            status == 422 -> PeerCopyError.Kind.HASH
            else -> PeerCopyError.Kind.OTHER
        }
        return PeerCopyError(kind, status, message)
    }

    private fun failure(r: Response): PeerCopyError {
        val text = try { r.body?.string().orEmpty() } catch (e: IOException) { "" }
        val o = try { JSONObject(text) } catch (e: Exception) { JSONObject() }
        return fromHttp(r.code, o.optString("error", "error"), o.optString("message", "The device refused."))
    }

    private fun waitForWifi() {
        gate()?.let { throw PeerCopyError(PeerCopyError.Kind.UNREACHABLE, 0, it) }
    }

    // ------------------------------------------------------------------------------------------------------------- reading

    /** Streams the other device's file list ([onLine]: path, size, mtime). A listing that was cut short is not complete. */
    fun scan(onLine: (path: String, size: Long, mtimeMs: Long) -> Unit): PeerScan = try {
        call { c ->
            c.request("GET", "$base/inventory", timeoutMs = 10 * 60_000).use { r ->
                if (r.code == 403) throw failure(r)
                if (r.code != 200) return@call PeerScan(0, false, listOf("The device answered ${r.code}."))
                var count = 0
                try {
                    r.body!!.byteStream().bufferedReader(Charsets.UTF_8).use { reader ->
                        while (true) {
                            val text = reader.readLine() ?: break
                            if (text.isEmpty()) continue
                            val row = JSONObject(text)
                            onLine(row.getString("path"), row.getLong("size"), row.optDouble("mtimeMs", 0.0).toLong())
                            count++
                        }
                    }
                } catch (e: IOException) {
                    return@call PeerScan(count, false, listOf(e.message ?: "The list of files was cut short."))
                }
                PeerScan(count, r.header("x-keepfin-complete") == "1", emptyList())
            }
        }
    } catch (e: PeerCopyError) {
        if (e.kind == PeerCopyError.Kind.DENIED || e.kind == PeerCopyError.Kind.UNREACHABLE) throw e
        PeerScan(0, false, listOf(e.message ?: "The list of files could not be read."))
    }

    /** One file as the other device shows it now (size and modification time are also its revision), or null when it is not there. */
    fun stat(path: String): PeerStat? = call { c ->
        val target = "$base/copy/file?path=${java.net.URLEncoder.encode(path, "UTF-8")}"
        for (range in listOf("bytes=0-0", null)) {
            c.request("GET", target, headers = if (range != null) mapOf("Range" to range) else emptyMap(), timeoutMs = 30_000).use { r ->
                if (r.code == 416 && range != null) return@use // an empty file has no byte to ask for: ask again for the headers alone
                if (r.code == 200 || r.code == 206) {
                    val m = Regex("^\"(\\d+)-(\\d+)\"$").matchEntire(r.header("etag").orEmpty()) ?: throw PeerCopyError(PeerCopyError.Kind.OTHER, r.code, "The device did not say how big the file is.")
                    return@call PeerStat(m.groupValues[1].toLong(), m.groupValues[2].toLong())
                }
                if (r.code == 404) return@call null
                throw failure(r)
            }
        }
        null
    }

    /** The bytes of a file by name. The caller closes the stream; the bytes are hashed where they are used. */
    fun openRead(path: String): InputStream {
        waitForWifi()
        return call { c ->
            val r = c.request("GET", "$base/copy/file?path=${java.net.URLEncoder.encode(path, "UTF-8")}", timeoutMs = 120_000)
            if (r.code != 200) {
                val e = failure(r)
                r.close()
                throw e
            }
            val body = r.body!!.byteStream()
            object : InputStream() {
                override fun read(): Int = guarded { body.read() }
                override fun read(b: ByteArray, off: Int, len: Int): Int = guarded { body.read(b, off, len) }
                override fun close() {
                    r.close()
                }

                private inline fun <T> guarded(block: () -> T): T = try {
                    block()
                } catch (e: IOException) {
                    throw PeerCopyError(PeerCopyError.Kind.UNREACHABLE, 0, e.message ?: "The transfer was interrupted.")
                }
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------------- the lock

    /** Asks the other device's executor for its place lock. Returns the holder's name when it is busy, null when this phone holds it now. */
    fun lock(run: String): String? {
        val answer = call { it.json("POST", "$base/copy/lock", JSONObject().put("device", device).put("run", run)) }
        if (!answer.optBoolean("ok") || answer.optString("token").isEmpty()) return answer.optString("holder", "another device").ifEmpty { "another device" }
        token = answer.getString("token")
        return null
    }

    fun heartbeat() {
        val t = token ?: return
        call { it.json("POST", "$base/copy/heartbeat", JSONObject().put("token", t)) }
    }

    fun unlock() {
        val t = token ?: return
        token = null
        runCatching { call { it.json("POST", "$base/copy/unlock", JSONObject().put("token", t)) } }
    }

    val holdsLock: Boolean get() = token != null

    private fun requireToken(): String = token ?: throw PeerCopyError(PeerCopyError.Kind.LOCKED, 409, "The copy must be locked before it is changed.")

    fun readGeneration(): Long = call { it.json("GET", "$base/copy/state") }.optLong("generation", 0L)

    fun appendGeneration(generation: Long, replica: String): Boolean =
        call { it.json("POST", "$base/copy/generation", JSONObject().put("token", requireToken()).put("generation", generation).put("replica", replica)) }.optBoolean("ok")

    // -------------------------------------------------------------------------------------------------------------- changes

    /**
     * Sends a file. [ifMatch] (a content id) makes it a replacement of exactly that content; null creates a file that is not there yet. The
     * other device checks the bytes against [contentId] and keeps what it displaces in its own trash.
     */
    fun put(path: String, source: InputStream, size: Long, contentId: String, ifMatch: String?, onBytes: ((Long) -> Unit)? = null) {
        waitForWifi()
        val t = requireToken()
        val query = "token=${enc(t)}&path=${enc(path)}&content=${enc(contentId)}&mode=${if (ifMatch != null) "replace" else "new"}"
        call { c ->
            val counted = if (onBytes == null) source else object : java.io.FilterInputStream(source) {
                private var total = 0L
                override fun read(): Int = super.read().also { if (it >= 0) { total += 1; onBytes(total) } }
                override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) { total += it; onBytes(total) } }
            }
            c.request("PUT", "$base/copy/file?$query", body = counted, contentLength = size, headers = if (ifMatch != null) mapOf("If-Match" to "\"$ifMatch\"") else emptyMap(), timeoutMs = 10 * 60_000).use { r ->
                if (r.code >= 400) throw failure(r)
            }
        }
    }

    /** Moves a file into the other device's trash; it keeps the bytes. Only while the file still has the content [ifMatch]. */
    fun trash(path: String, ifMatch: String) {
        call { it.json("POST", "$base/copy/trash", JSONObject().put("token", requireToken()).put("path", path).put("revision", JSONObject.NULL).put("ifMatch", ifMatch)) }
    }

    /** Renames or moves a file inside the other device's folder, only while it still has the content [ifMatch]; never replaces. */
    fun move(from: String, to: String, ifMatch: String) {
        call { it.json("POST", "$base/copy/move", JSONObject().put("token", requireToken()).put("from", from).put("to", to).put("revision", JSONObject.NULL).put("ifMatch", ifMatch)) }
    }

    /** Whether the device answers (its copy state), without moving any file. */
    fun reachable(): Boolean = try {
        call { it.json("GET", "$base/copy/state", timeoutMs = 8_000) }
        true
    } catch (e: PeerCopyError) {
        false
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
