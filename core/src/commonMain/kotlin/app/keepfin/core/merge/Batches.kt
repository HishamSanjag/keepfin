package app.keepfin.core.merge

import app.keepfin.core.library.OpDraft
import app.keepfin.core.sync.*

/** The receipt hashes a canonical JSON array of {kind,body}, UTF-8, with no trailing newline. */
fun draftsJson(drafts: List<OpDraft>): String = encodeCanonical(JArr(drafts.map {
    jObj("kind" to JStr(it.kind), "body" to JObj(it.body))
}))

data class Batch(val index: Int, val drafts: List<OpDraft>, val draftsHash: String) {
    init {
        require(index >= 0 && drafts.isNotEmpty() && drafts.size <= 500)
        require(draftsHash == sha256Hex(draftsJson(drafts))) { "batch receipt does not match drafts" }
    }
    companion object {
        fun of(index: Int, drafts: List<OpDraft>): Batch {
            val copy = drafts.toList()
            return Batch(index, copy, sha256Hex(draftsJson(copy)))
        }
    }
}

/** m is the contiguous actor-op count after actorSeqBefore, never a count of successful effects. */
fun remainingDrafts(batch: Batch, m: Int): List<OpDraft> {
    require(m in 0..batch.drafts.size)
    require(batch.draftsHash == sha256Hex(draftsJson(batch.drafts))) { "batch changed after its receipt" }
    return batch.drafts.drop(m)
}

internal class BatchWriter(private val sink: MergeSink) {
    private var index = 0
    private val pending = ArrayList<OpDraft>(500)
    fun add(draft: OpDraft) {
        pending.add(draft)
        if (pending.size == 500) flush()
    }
    fun flush() {
        if (pending.isEmpty()) return
        sink.batch(Batch.of(index++, pending))
        pending.clear()
    }
}
