package app.keepfin.net

import java.io.IOException
import java.io.InputStream
import org.json.JSONObject

// Exchanging segments with one peer (spec 4): pull what we lack by version vector, push what the peer lacks. Both directions stream
// framed KFSEG files; the receiver stores them as from any replica and the library store admits them, judging membership itself.
// A transfer that breaks halfway keeps what already arrived; the next pass starts from the new vector, so it resumes.
// Port of gui/desktop/net/sync.ts.

class SyncReport(val pulled: Int, val pushed: Int, val rejected: Int, val denied: String? = null)

class PullResult(val stored: Int, val rejected: Int, val remote: Map<String, Long>?, val denied: Boolean)

object Sync {
    /** Pulls the segments the peer has and we lack. */
    fun pull(client: PeerClient, lib: NetLibrary, cancelled: () -> Boolean = { false }, onStored: ((Int) -> Unit)? = null): PullResult {
        lib.flush()
        val vector = SegmentFiles.vectorOf(lib.segments())
        client.request("GET", "/v1/libraries/${lib.libraryId}/segments?vector=${java.net.URLEncoder.encode(SegmentFiles.formatVector(vector), "UTF-8")}", timeoutMs = 120_000).use { response ->
            if (response.code == 403) return PullResult(0, 0, null, true)
            if (response.code != 200) throw PeerHttpError(response.code, "segments", "The device answered ${response.code}.")
            var stored = 0
            var rejected = 0
            val remote = response.header("x-keepfin-vector")?.let { SegmentFiles.parseVector(it) }
            val reader = FrameReader(SEGMENT_MAX_BYTES, 1_000_000) { bytes ->
                if (cancelled()) throw IOException("The transfer was stopped.")
                when (SegmentFiles.ingest(lib.folder, lib.libraryId, bytes, lib.layout)) {
                    is Ingest.Stored -> { stored++; onStored?.invoke(stored) }
                    is Ingest.Rejected -> rejected++
                    is Ingest.Have -> {}
                }
            }
            try {
                reader.readAll(response.body!!.byteStream())
            } finally {
                if (stored > 0) {
                    lib.invalidate()
                    lib.rescan() // whatever arrived is admitted, even when the stream broke
                }
            }
            return PullResult(stored, rejected, remote, false)
        }
    }

    /** Pushes the segments [remote] (the peer's vector) lacks. */
    fun push(client: PeerClient, lib: NetLibrary, remote: Map<String, Long>): Pair<Int, Boolean> {
        lib.flush()
        val missing = SegmentFiles.missingFor(lib.segments(), remote).filter { it.size in 1..SEGMENT_MAX_BYTES }
        if (missing.isEmpty()) return 0 to false
        val total = missing.sumOf { 4L + it.size }
        val body = FramedSegments(lib, missing)
        client.request("POST", "/v1/libraries/${lib.libraryId}/segments", body = body, contentLength = total, timeoutMs = 120_000).use { response ->
            val bytes = PeerClient.readLimited(response.body!!.byteStream(), 1024 * 1024)
            if (response.code == 403) return 0 to true
            if (response.code != 200) throw PeerHttpError(response.code, "push", "The device answered ${response.code}.")
            return (try { JSONObject(String(bytes, Charsets.UTF_8)).optInt("stored", 0) } catch (e: Exception) { 0 }) to false
        }
    }

    /** One full pass for one library: pull, then push what the peer lacks. */
    fun library(client: PeerClient, lib: NetLibrary, cancelled: () -> Boolean = { false }): SyncReport {
        val pulled = pull(client, lib, cancelled)
        var pushed = 0
        var denied: String? = if (pulled.denied) "read" else null
        if (pulled.remote != null) {
            // our vector may have grown by what we just took in; the peer's vector (before it answered) is what it lacks of ours
            val (n, wasDenied) = push(client, lib, pulled.remote)
            pushed = n
            if (wasDenied && denied == null) denied = "write"
        }
        return SyncReport(pulled.stored, pushed, pulled.rejected, denied)
    }
}

/** The framed bytes of segment files, read one file at a time as the connection takes them (the announced length is exact: files are immutable). */
private class FramedSegments(private val lib: NetLibrary, private val list: List<SegInfo>) : InputStream() {
    private var index = 0
    private var current: ByteArray? = null
    private var at = 0

    private fun fill(): Boolean {
        while (current == null || at >= current!!.size) {
            if (index >= list.size) return false
            val seg = list[index++]
            val bytes = lib.folder.read(seg.path)
            if (bytes.size.toLong() != seg.size) throw IOException("A segment changed while it was sent.")
            current = SegmentFiles.frame(bytes)
            at = 0
        }
        return true
    }

    override fun read(): Int {
        if (!fill()) return -1
        return current!![at++].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, current!!.size - at)
        System.arraycopy(current!!, at, b, off, n)
        at += n
        return n
    }
}
