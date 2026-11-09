package app.keepfin

import app.keepfin.core.formats.Formats as Core
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Sizes for formats the site reports without one (Instagram gives a bitrate but no length). The shared core picks the
 * formats (`sizeProbesOf`); here the server is asked for each file's length: HEAD first, then a one-byte ranged GET when HEAD
 * is refused. Plain requests, no cookies. A failure leaves the size unknown. The desktop does the same (gui/desktop/engine/sizes.ts).
 */
object SizeFiller {
    class Reply(val status: Int, val contentLength: Long?, val contentRange: String?)

    /** One request; null when it failed. */
    fun interface Fetcher { fun request(url: String, method: String, headers: Map<String, String>): Reply? }

    private const val EACH_MS = 3_000
    private const val TOTAL_MS = 4_000L
    private const val PARALLEL = 4

    val http = Fetcher { url, method, headers ->
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = EACH_MS; readTimeout = EACH_MS; instanceFollowRedirects = true; requestMethod = method
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            Reply(connection.responseCode, connection.contentLengthLong.takeIf { it > 0 }, connection.getHeaderField("Content-Range"))
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect() // only the headers are needed
        }
    }

    /** The length of one file, or null. */
    internal fun lengthOf(url: String, headers: Map<String, String>, fetcher: Fetcher): Long? {
        val head = fetcher.request(url, "HEAD", headers) ?: return null
        if (head.status in 200..299) return head.contentLength
        if (head.status != 403 && head.status != 405) return null
        val ranged = fetcher.request(url, "GET", headers + ("Range" to "bytes=0-0")) ?: return null
        if (ranged.status != 206) return null
        val range = ranged.contentRange?.trim().orEmpty()
        if (!range.startsWith("bytes 0-0/")) return null
        val total = range.substringAfter('/')
        return if (total.isNotEmpty() && total.all { it in '0'..'9' }) total.toLongOrNull()?.takeIf { it > 0 } else null
    }

    /** Sets `filesize` on the formats of [info] that have no size, from the server. Never throws; at most ~4 s in all. */
    fun fill(info: JSONObject, fetcher: Fetcher = http) {
        try {
            val formats = info.optJSONArray("formats") ?: return
            val probes = Core.sizeProbesOf(info.toString(), 16)
            if (probes.isEmpty()) return
            val pool = Executors.newFixedThreadPool(minOf(PARALLEL, probes.size))
            try {
                val tasks = probes.map { probe -> Callable { probe.formatId to lengthOf(probe.url, probe.headers, fetcher) } }
                val results = pool.invokeAll(tasks, TOTAL_MS, TimeUnit.MILLISECONDS)
                for (future in results) {
                    val (formatId, bytes) = runCatching { future.get() }.getOrNull() ?: continue
                    if (bytes == null) continue
                    for (i in 0 until formats.length()) {
                        val format = formats.optJSONObject(i) ?: continue
                        if (format.optString("format_id") == formatId) format.put("filesize", bytes)
                    }
                }
            } finally {
                pool.shutdownNow()
            }
        } catch (_: Exception) { /* sizes are a bonus */ }
    }
}
