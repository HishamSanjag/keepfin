package app.keepfin.net

import app.keepfin.core.sync.SegmentResult
import app.keepfin.core.sync.decodeSegment
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import app.keepfin.folder.FolderLayout
import java.io.ByteArrayOutputStream
import java.io.InputStream

// Segment files on the wire (spec 4): the receiver stores a peer's `KFSEG` files in the folder like any replica's, and the library
// store admits them (with the signer the file proves). Nothing here decides what is trusted: a segment is decoded and checksummed,
// then written under the name its own header gives; membership is admission's job. Mirrors gui/desktop/net/segments.ts.

const val SEGMENT_MAX_BYTES = 16 * 1024 * 1024
private val UUID = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
private val SEG_NAME = Regex("^(\\d{16})-(\\d{16})\\.seg$")
private fun pad(n: Long) = n.toString().padStart(16, '0')

fun isActorId(value: String): Boolean = UUID.matches(value)

class SegInfo(val actor: String, val first: Long, val last: Long, val name: String, val size: Long, val ops: String = FolderLayout.DEFAULT.ops) {
    val path: String get() = "$ops/$actor/$name"
}

sealed class Ingest {
    class Stored(val actor: String, val first: Long, val last: Long) : Ingest()
    class Have(val actor: String, val first: Long, val last: Long) : Ingest()
    class Rejected(val reason: String) : Ingest()
}

object SegmentFiles {
    /** Every segment file in the folder, by actor then first seq. */
    fun list(folder: FolderAccess, layout: FolderLayout = FolderLayout.DEFAULT): List<SegInfo> {
        val out = ArrayList<SegInfo>()
        val actors = try {
            folder.list(layout.ops).entries.filter { it.isDirectory && isActorId(it.name) }
        } catch (e: FolderError.NotFound) {
            return out
        }
        for (dir in actors) {
            val listing = try {
                folder.list("${layout.ops}/${dir.name}")
            } catch (e: FolderError.NotFound) {
                continue
            }
            for (f in listing.entries) {
                if (f.isDirectory) continue
                val m = SEG_NAME.matchEntire(f.name) ?: continue
                out += SegInfo(dir.name, m.groupValues[1].toLong(), m.groupValues[2].toLong(), f.name, f.size, layout.ops)
            }
        }
        return out.sortedWith(compareBy<SegInfo>({ it.actor }, { it.first }, { it.last }))
    }

    fun vectorOf(list: List<SegInfo>): Map<String, Long> {
        val out = HashMap<String, Long>()
        for (s in list) out[s.actor] = maxOf(out[s.actor] ?: 0L, s.last)
        return out
    }

    fun formatVector(vector: Map<String, Long>): String =
        vector.entries.filter { it.value > 0 }.sortedBy { it.key }.joinToString(",") { "${it.key}:${it.value}" }

    /** Reads `actor:seq,actor:seq`. Null for anything else (a bad vector is a bad request, never a guess). */
    fun parseVector(text: String, maxEntries: Int = 512): Map<String, Long>? {
        val out = LinkedHashMap<String, Long>()
        if (text.isEmpty()) return out
        val parts = text.split(',')
        if (parts.size > maxEntries) return null
        for (part in parts) {
            val at = part.lastIndexOf(':')
            if (at < 0) return null
            val actor = part.substring(0, at)
            val seqText = part.substring(at + 1)
            if (!isActorId(actor) || seqText.isEmpty() || seqText.length > 15 || !seqText.all { it in '0'..'9' }) return null
            if (out.containsKey(actor)) return null
            out[actor] = seqText.toLong()
        }
        return out
    }

    /** The segments a peer holding [vector] lacks, in the order they should arrive. */
    fun missingFor(list: List<SegInfo>, vector: Map<String, Long>): List<SegInfo> = list.filter { it.last > (vector[it.actor] ?: 0L) }

    /** Checks a received file and stores it under `ops/<actor>/<first>-<last>.seg` (never overwriting). */
    fun ingest(folder: FolderAccess, libraryId: String, bytes: ByteArray, layout: FolderLayout = FolderLayout.DEFAULT): Ingest {
        if (bytes.isEmpty() || bytes.size > SEGMENT_MAX_BYTES) return Ingest.Rejected("size")
        val decoded = decodeSegment(bytes, libraryId)
        if (decoded !is SegmentResult.Ok) return Ingest.Rejected("invalid")
        if (!isActorId(decoded.actor)) return Ingest.Rejected("actor")
        val dir = "${layout.ops}/${decoded.actor}"
        val path = "$dir/${pad(decoded.first)}-${pad(decoded.last)}.seg"
        if (folder.stat(path) != null) return Ingest.Have(decoded.actor, decoded.first, decoded.last)
        folder.mkdirs(dir)
        try {
            folder.publish(path, bytes)
        } catch (e: FolderError.NameConflict) {
            return Ingest.Have(decoded.actor, decoded.first, decoded.last)
        }
        return Ingest.Stored(decoded.actor, decoded.first, decoded.last)
    }

    /** `u32be length` then the file's bytes. */
    fun frame(bytes: ByteArray): ByteArray {
        val out = ByteArray(4 + bytes.size)
        out[0] = (bytes.size ushr 24).toByte()
        out[1] = (bytes.size ushr 16).toByte()
        out[2] = (bytes.size ushr 8).toByte()
        out[3] = bytes.size.toByte()
        bytes.copyInto(out, 4)
        return out
    }
}

class FrameError(message: String) : Exception(message)

/**
 * Splits a byte stream into frames. A frame over [maxFrame] bytes, a count over [maxFrames], an empty frame or a stream that ends
 * inside a frame ends it with a [FrameError]; [onFrame] runs for each complete frame in order.
 */
class FrameReader(private val maxFrame: Int, private val maxFrames: Int, private val onFrame: (ByteArray) -> Unit) {
    private var count = 0

    fun readAll(input: InputStream) {
        val head = ByteArray(4)
        while (true) {
            val n = readUpTo(input, head, 4)
            if (n == 0) return
            if (n < 4) throw FrameError("the stream ended inside a frame")
            val size = ((head[0].toInt() and 0xff) shl 24) or ((head[1].toInt() and 0xff) shl 16) or ((head[2].toInt() and 0xff) shl 8) or (head[3].toInt() and 0xff)
            if (size <= 0 || size > maxFrame) throw FrameError("a frame is too large")
            if (++count > maxFrames) throw FrameError("too many frames")
            val body = ByteArray(size)
            if (readUpTo(input, body, size) < size) throw FrameError("the stream ended inside a frame")
            onFrame(body)
        }
    }

    private fun readUpTo(input: InputStream, buf: ByteArray, len: Int): Int {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) break
            off += n
        }
        return off
    }
}

internal fun collectBytes(input: InputStream, max: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        if (out.size() + n > max) throw FrameError("the answer is too large")
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
