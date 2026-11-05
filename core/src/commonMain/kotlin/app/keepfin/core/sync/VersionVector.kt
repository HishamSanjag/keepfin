package app.keepfin.core.sync

/** Helpers over a version vector: actor to the highest contiguous seq durably stored. */
object VersionVectors {
    /** For each actor, the seq range that [have] lacks and [seen] holds: `(have[actor], seen[actor]]`. */
    fun missing(have: Map<String, Long>, seen: Map<String, Long>): Map<String, LongRange> {
        val out = LinkedHashMap<String, LongRange>()
        for ((actor, top) in seen) {
            val low = have[actor] ?: 0L
            if (top > low) out[actor] = (low + 1)..top
        }
        return out
    }

    /** The pointwise maximum. */
    fun merge(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> {
        val out = LinkedHashMap(a)
        for ((k, v) in b) out[k] = maxOf(out[k] ?: 0L, v)
        return out
    }
}
