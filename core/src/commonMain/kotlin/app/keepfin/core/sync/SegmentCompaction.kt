package app.keepfin.core.sync

// Data compaction (dev-docs/plans/KEEPFIN-DATA-COMPACTION.md): which of a device's own small segment files to rewrite as
// fewer, larger ones, and which files a larger file of the same actor already covers. Pure: it decides from file facts and
// performs no I/O. The store (desktop store.ts, Android KeepfinStore) reads the facts, writes and verifies the merged file
// and only then removes the inputs.
//
// Nothing is ever dropped: a merged file holds exactly the ops of its inputs, signed by the same key.

/** What the planner knows about one segment file. [signer] is the device id in its `KFSIG` trailer (null: an unsigned v1 file). */
class SegmentInfo(
    val actor: String,
    val first: Long,
    val last: Long,
    val bytes: Long,
    val signer: String? = null,
    /** False when the file could not be read or decoded (the actor is then left alone). */
    val ok: Boolean = true,
    /** Named by membership (a genesis `lastSegmentHash` or a cutoff `segmentHash`): never merged or removed. */
    val named: Boolean = false,
) {
    val ops: Long get() = last - first + 1
}

class CompactionPolicy(
    /** A segment with fewer ops than this is "small" (tier 0). */
    val smallOps: Long = 256,
    /** At least this many consecutive small segments before they are merged. */
    val minSmall: Int = 8,
    val tier1Ops: Long = 2_000,
    val tier1Bytes: Long = 2L * 1024 * 1024,
    val tier2Ops: Long = 5_000,
    val tier2Bytes: Long = 4_000_000,
    /** Tier-1 files are merged into one when this many are contiguous (fewer when a tier-1 file is being written anyway). */
    val minTier1: Int = 4,
) {
    internal val capOps2: Long get() = minOf(tier2Ops, SEGMENT_MAX_OPS.toLong())
    internal val capBytes2: Long get() = minOf(tier2Bytes, SEGMENT_MAX_BYTES.toLong())
}

/** Replace the files at [inputs] (indexes into the planner's input list, in seq order) by one file [first]..[last] of [actor]. */
class Merge(val actor: String, val first: Long, val last: Long, val inputs: List<Int>)

/** The file at [index] holds nothing that the file at [by] (a larger file of the same actor, itself kept) does not. */
class Subsumed(val index: Int, val by: Int)

object SegmentCompaction {
    private class Piece(val first: Long, val last: Long, val bytes: Long, val inputs: List<Int>, val merged: Boolean) {
        val ops: Long get() = last - first + 1
    }

    private fun join(units: List<Piece>) = Piece(units.first().first, units.last().last, units.sumOf { it.bytes }, units.flatMap { it.inputs }, true)

    /**
     * The merges to make. Only segments signed by [currentSigner] (the device's current key; null for a device without a
     * key, which merges unsigned files) are merged, never across a gap or a file that is not eligible, and an actor that has
     * an unreadable file, an overlap or is in [blocked] (equivocations, problems) is skipped whole.
     */
    fun plan(segments: List<SegmentInfo>, currentSigner: String?, blocked: Set<String> = emptySet(), policy: CompactionPolicy = CompactionPolicy()): List<Merge> {
        val byActor = LinkedHashMap<String, MutableList<Int>>()
        for (i in segments.indices) byActor.getOrPut(segments[i].actor) { ArrayList() }.add(i)
        val out = ArrayList<Merge>()
        for ((actor, list) in byActor) {
            if (actor in blocked) continue
            val sorted = list.sortedWith(compareBy({ segments[it].first }, { segments[it].last }))
            if (sorted.any { !segments[it].ok }) continue
            if (sorted.zipWithNext().any { (a, b) -> segments[a].last >= segments[b].first }) continue
            var run = ArrayList<Piece>()
            fun flush() {
                if (run.isNotEmpty()) out.addAll(planRun(actor, run, policy))
                run = ArrayList()
            }
            for (i in sorted) {
                val s = segments[i]
                val eligible = s.signer == currentSigner && !s.named
                val contiguous = run.isEmpty() || run.last().last + 1 == s.first
                if (!eligible) {
                    flush()
                    continue
                }
                if (!contiguous) flush()
                run.add(Piece(s.first, s.last, s.bytes, listOf(i), false))
            }
            flush()
        }
        return out
    }

    private fun planRun(actor: String, units: List<Piece>, policy: CompactionPolicy): List<Merge> {
        // Tier 0 to 1: runs of small segments (at least minSmall in a row) packed into files of up to tier1Ops
        val a = ArrayList<Piece>()
        var at = 0
        while (at < units.size) {
            if (units[at].ops >= policy.smallOps) {
                a.add(units[at++])
                continue
            }
            var end = at
            while (end < units.size && units[end].ops < policy.smallOps) end++
            val seq = units.subList(at, end)
            if (seq.size < policy.minSmall) a.addAll(seq) else a.addAll(pack(seq, policy.tier1Ops, policy.tier1Bytes))
            at = end
        }
        // Tier 1 to 2: consecutive tier-1 files (and the ones just made) packed into files of up to tier2Ops
        val b = ArrayList<Piece>()
        at = 0
        fun tier1(u: Piece) = u.ops >= policy.smallOps && u.ops <= policy.tier1Ops
        while (at < a.size) {
            if (!tier1(a[at])) {
                b.add(a[at++])
                continue
            }
            var end = at
            while (end < a.size && tier1(a[end])) end++
            for (group in packGroups(a.subList(at, end), policy.capOps2, policy.capBytes2)) {
                val enough = group.size >= policy.minTier1 || (group.size >= 2 && group.any { it.merged })
                if (enough) b.add(join(group)) else b.addAll(group)
            }
            at = end
        }
        return b.filter { it.merged }.map { Merge(actor, it.first, it.last, it.inputs) }
    }

    /** Greedy packing: groups of consecutive units within the caps; a group of one unit stays as it is. */
    private fun packGroups(units: List<Piece>, maxOps: Long, maxBytes: Long): List<List<Piece>> {
        val groups = ArrayList<List<Piece>>()
        var cur = ArrayList<Piece>()
        var ops = 0L
        var bytes = 0L
        for (u in units) {
            if (cur.isNotEmpty() && (ops + u.ops > maxOps || bytes + u.bytes > maxBytes)) {
                groups.add(cur)
                cur = ArrayList()
                ops = 0
                bytes = 0
            }
            cur.add(u)
            ops += u.ops
            bytes += u.bytes
        }
        if (cur.isNotEmpty()) groups.add(cur)
        return groups
    }

    private fun pack(units: List<Piece>, maxOps: Long, maxBytes: Long): List<Piece> =
        packGroups(units, maxOps, maxBytes).flatMap { if (it.size >= 2) listOf(join(it)) else it }

    /**
     * Files whose ops another file of the same actor already holds (its range contains theirs). The caller must still check
     * op for op that the covering file holds the same ops before removing anything. An actor with an unreadable file is
     * skipped whole, files named by membership are never listed.
     */
    fun subsumed(segments: List<SegmentInfo>, blocked: Set<String> = emptySet()): List<Subsumed> {
        val byActor = LinkedHashMap<String, MutableList<Int>>()
        for (i in segments.indices) byActor.getOrPut(segments[i].actor) { ArrayList() }.add(i)
        val out = ArrayList<Subsumed>()
        for ((actor, list) in byActor) {
            if (actor in blocked || list.any { !segments[it].ok }) continue
            // first ascending, last descending: whoever came before with the highest last is a maximal file
            val sorted = list.sortedWith(compareBy<Int>({ segments[it].first }, { -segments[it].last }))
            var best = -1
            for (i in sorted) {
                val s = segments[i]
                if (best >= 0 && s.last <= segments[best].last) {
                    if (!s.named) out.add(Subsumed(i, best))
                } else {
                    best = i
                }
            }
        }
        return out
    }
}
