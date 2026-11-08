package app.keepfin.folder

import app.keepfin.core.sync.Op
import app.keepfin.core.sync.SEGMENT_MAX_BYTES
import app.keepfin.core.sync.SegmentCompaction
import app.keepfin.core.sync.SegmentInfo
import app.keepfin.core.sync.SegmentResult
import app.keepfin.core.sync.decodeSegment
import app.keepfin.core.sync.encodeSegment
import app.keepfin.core.sync.segmentFileName
import org.json.JSONObject

/** The device id found in a segment's trailer (null: an unsigned v1 file). */
internal class Peek(val signer: String?)

internal data class SegmentFile(val ops: String, val actor: String, val first: Long, val last: Long, val name: String, val size: Long = 0) {
    val path get() = "$ops/$actor/$name"
    val key get() = "$actor/$name"
}

/** What the compaction reads from the [KeepfinStore] that owns it (everything runs on the store thread). */
internal interface CompactionHost {
    val o: StoreOptions
    val closed: Boolean
    val actor: String
    val deviceId: String?
    val state: MergeState
    val equivocations: Map<String, Long>
    val problems: Map<String, String>
    val retries: Map<String, Pair<Int, Long>>
    val processed: MutableSet<String>
    fun listSegments(): List<SegmentFile>
    fun flushNow()
    fun scheduleCompact(delayMs: Long)
    /** A segment file was written or removed: the network layer lists the folder again. */
    fun segmentsChanged()
}

/**
 * The compaction rounds of a store (dev-docs/plans/KEEPFIN-DATA-COMPACTION.md): the merge proof, the removal gate and the transfer
 * leases. The store owns the timer and the state; this class reads them through [CompactionHost].
 */
internal class StoreCompaction(private val host: CompactionHost) {
    private val o get() = host.o
    private val closed get() = host.closed
    private val actor get() = host.actor
    private val deviceId get() = host.deviceId
    private val state get() = host.state
    private val equivocations get() = host.equivocations
    private val problems get() = host.problems
    private val retries get() = host.retries
    private val processed get() = host.processed

    var lastCompactAt = 0L
    /** Own segment files written since the last compaction round (decides whether a flush asks for one). */
    var filesSinceCompact = 0
    /** Segment transfers in flight (token to expiry): compaction removes no file while one is open. */
    private val leases = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val removalGate = Any()

    private fun listSegments() = host.listSegments()
    private fun scheduleCompact(delayMs: Long) = host.scheduleCompact(delayMs)

    /**
     * A segment transfer starts (a peer is being sent files): compaction removes no file until the result is closed. Waits for a
     * removal in progress, so a transfer never lists files that are about to go.
     */
    fun hold(): AutoCloseable {
        val token = o.newId()
        synchronized(removalGate) { leases[token] = System.currentTimeMillis() + LEASE_MS }
        return AutoCloseable { leases.remove(token) }
    }

    private fun leasesOpen(): Boolean {
        val now = System.currentTimeMillis()
        leases.entries.removeIf { it.value < now } // a transfer that never ends cannot block compaction for ever
        return leases.isNotEmpty()
    }

    private fun waitForTransfers(): Boolean {
        val until = System.currentTimeMillis() + o.leaseWaitMs
        while (leasesOpen()) {
            if (System.currentTimeMillis() >= until) return false
            Thread.sleep(25)
        }
        return true
    }

    /** The actors whose files are left alone: equivocations, unreadable or still-arriving files. */
    private fun actorsWithProblems(): Set<String> {
        val bad = HashSet<String>(equivocations.keys)
        for (key in problems.keys + retries.keys) bad += key.substringBefore('/')
        return bad
    }

    /** The files membership names (the last segment an actor had when membership started): never merged or removed. */
    private fun namedFiles(segments: List<SegmentFile>): Set<String> {
        val frozen = try {
            val view = state.membership()
            if (view.active) view.frozen else emptyMap()
        } catch (e: Exception) {
            return segments.map { it.key }.toSet() // cannot tell: touch nothing
        }
        return segments.filter { frozen[it.actor] == it.last }.map { it.key }.toSet()
    }

    /** The device id in a segment's `KFSIG` trailer without decoding the file: Pair(null) for a v1 file, null when it cannot be read. */
    private fun peekSigner(file: SegmentFile): Peek? {
        return try {
            o.folder.openRead(file.path).use { input ->
                val head = ByteArray(8)
                var n = 0
                while (n < 8) { val r = input.read(head, n, 8 - n); if (r < 0) break; n += r }
                val magic = String(head, 0, n, Charsets.ISO_8859_1)
                if (magic == "KFSEG 1\n") return Peek(null)
                if (magic != "KFSEG 2\n") return null
                val size = if (file.size > 0) file.size else return null
                var skip = maxOf(0L, size - 2048 - 8)
                while (skip > 0) { val s = input.skip(skip); if (s <= 0) break; skip -= s }
                val tail = input.readBytes().toString(Charsets.UTF_8)
                val at = tail.lastIndexOf("\nKFSIG ")
                if (at < 0 || !tail.endsWith("\n")) return null
                Peek(JSONObject(tail.substring(at + 7, tail.length - 1)).getString("device"))
            }
        } catch (e: Exception) {
            null
        }
    }

    fun compactRound(): CompactStats {
        var merged = 0
        var removed = 0
        var dropped = 0
        fun done(skipped: String? = null) = CompactStats(merged, removed, dropped, skipped).also { o.onCompact?.invoke(it) }
        if (closed || o.readOnly) return done("closed")
        host.flushNow()
        if (!waitForTransfers()) {
            scheduleCompact(120_000)
            return done("busy")
        }
        lastCompactAt = System.currentTimeMillis()
        filesSinceCompact = 0
        var segments = listSegments()
        dropped = dropSubsumed(segments)
        if (dropped > 0) segments = listSegments()
        if (actor in actorsWithProblems()) return done("blocked")
        val own = segments.filter { it.actor == actor }
        if (own.size < 2) return done()
        val named = namedFiles(own)
        val infos = own.map { s ->
            // a file over 2,000 ops is never merged, so who signed it does not matter and it is not opened
            val big = s.last - s.first + 1 > 2000
            val peek = if (big) Peek(deviceId) else peekSigner(s)
            SegmentInfo(s.actor, s.first, s.last, s.size, peek?.signer, ok = peek != null, named = s.key in named)
        }
        for (merge in SegmentCompaction.plan(infos, deviceId)) {
            if (closed) break
            when (applyMerge(merge.first, merge.last, merge.inputs.map { own[it] })) {
                "busy" -> {
                    scheduleCompact(120_000)
                    return done("busy")
                }
                "failed" -> return done()
                else -> {
                    merged++
                    removed += merge.inputs.size
                }
            }
        }
        return done()
    }

    /** Writes the one file that holds [inputs], proves it, then removes the inputs. Returns "merged", "busy" or "failed". */
    private fun applyMerge(first: Long, last: Long, inputs: List<SegmentFile>): String {
        val lines = ArrayList<String>()
        val ops = ArrayList<Op>()
        for (input in inputs) {
            val value = try {
                decodeSegment(o.folder.read(input.path), o.libraryId)
            } catch (e: FolderError) {
                return "failed"
            }
            if (value !is SegmentResult.Ok || value.actor != actor || value.first != input.first || value.last != input.last || value.signer != deviceId) return "failed"
            for (op in value.ops) {
                lines += op.encode()
                ops += op
            }
        }
        if (lines.size.toLong() != last - first + 1) return "failed"
        val path = "${o.layout.ops}/$actor/${segmentFileName(first, last)}"
        fun holdsAll(bytes: ByteArray): Boolean {
            val v = decodeSegment(bytes, o.libraryId) as? SegmentResult.Ok ?: return false
            return v.actor == actor && v.first == first && v.last == last && v.signer == deviceId && v.ops.size == lines.size && v.ops.indices.all { v.ops[it].encode() == lines[it] }
        }
        var wrote = false
        val existing = o.folder.stat(path)
        if (existing != null) {
            // left by an interrupted round: usable only if it is exactly the merge
            if (!holdsAll(o.folder.read(path))) return "failed"
        } else {
            val bytes = encodeSegment(o.libraryId, actor, ops, o.signer)
            if (bytes.size > SEGMENT_MAX_BYTES) return "failed"
            o.folder.publish(path, bytes)
            wrote = true
            host.segmentsChanged()
        }
        val key = "$actor/${segmentFileName(first, last)}"
        processed += key // its ops are held already: a rescan need not read it again
        o.onCompactStep?.invoke("published", first, last, 0)
        // read back what is on disk, not what was written
        val proven = try { holdsAll(o.folder.read(path)) } catch (e: FolderError) { false }
        if (!proven) {
            processed -= key
            if (wrote) runCatching { o.folder.remove(path) }
            return "failed"
        }
        o.onCompactStep?.invoke("verified", first, last, 0)
        return if (removeSegments(inputs.filter { it.path != path }) == null) "busy" else "merged"
    }

    /** Removes covered or merged segment files, unless a transfer is open (then none: the next round does it). Null when busy. */
    private fun removeSegments(files: List<SegmentFile>): Int? {
        if (files.isEmpty()) return 0
        synchronized(removalGate) {
            if (leasesOpen()) return null
            var count = 0
            for (f in files) {
                o.onCompactStep?.invoke("removing", f.first, f.last, count)
                o.folder.remove(f.path)
                processed -= f.key
                count++
            }
            host.segmentsChanged() // the network layer lists the folder again
            return count
        }
    }

    /**
     * Removes the files that a larger file of the same actor in this folder already holds, after checking op for op that it does
     * (R5). Never across actors, never for an actor with a problem, never a file that membership names. Returns how many.
     */
    fun dropSubsumed(segments: List<SegmentFile>): Int {
        if (segments.size < 2) return 0
        val bad = actorsWithProblems()
        val named = namedFiles(segments)
        val infos = segments.map { SegmentInfo(it.actor, it.first, it.last, it.size, null, ok = it.key !in problems && it.key !in retries, named = it.key in named) }
        val found = SegmentCompaction.subsumed(infos, bad)
        if (found.isEmpty()) return 0
        val covers = HashMap<String, SegmentResult?>()
        fun cover(s: SegmentFile) = covers.getOrPut(s.key) { try { decodeSegment(o.folder.read(s.path), o.libraryId) } catch (e: FolderError) { null } }
        val verified = ArrayList<SegmentFile>()
        for (hit in found) {
            val small = segments[hit.index]
            val large = cover(segments[hit.by]) as? SegmentResult.Ok ?: continue
            val inner = (try { decodeSegment(o.folder.read(small.path), o.libraryId) } catch (e: FolderError) { null }) as? SegmentResult.Ok ?: continue
            if (large.actor != inner.actor || inner.first < large.first || inner.last > large.last) continue
            var same = true
            var seq = inner.first
            while (seq <= inner.last && same) {
                same = inner.ops[(seq - inner.first).toInt()].encode() == large.ops[(seq - large.first).toInt()].encode()
                seq++
            }
            if (same) verified += small
        }
        return removeSegments(verified) ?: 0
    }

    private companion object {
        const val LEASE_MS = 600_000L
    }
}
