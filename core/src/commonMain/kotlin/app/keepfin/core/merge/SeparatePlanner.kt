package app.keepfin.core.merge

import app.keepfin.core.library.*
import app.keepfin.core.sync.*

enum class KeepChoice { Keep, Remove }

/**
 * Section 7.2 only. Executors must already be fenced. Linked items predate the merge and are never deleted.
 * A null choice means Keep. Deletes observe the current T tags/heads, not a frozen merge-time snapshot.
 * Pass map lines in their original order, once, with one line per target; reuse the streaming sink for receipts.
 */
class SeparatePlanner(
    private val replicaId: String,
    private val target: THashIndex,
    private val observed: ObservedLookup,
    sink: MergeSink,
) {
    private val writer = BatchWriter(sink)
    private var finished = false

    fun items(page: List<MapLine>, choice: (String) -> KeepChoice = { KeepChoice.Keep }) {
        check(!finished)
        for (line in page) {
            val item = target.item(line.t)
            val heldElsewhere = item.placements.any { (replica, path) -> replica != replicaId && path.isNotEmpty() }
            if (line.how != MapHow.Linked && (!heldElsewhere || choice(line.t) == KeepChoice.Remove)) {
                val seen = observed.item(line.t)
                require(seen.tags.isNotEmpty()) { "delete needs the current observed item.add tags" }
                writer.add(OpDraft("item.delete", mapOf(
                    "item" to JStr(line.t), "observedTags" to JArr(seen.tags.sorted().map { JStr(it) }),
                    "observedHeads" to JArr(seen.heads.sorted().map { JStr(it) }),
                )))
            } else if (replicaId in item.placements) {
                writer.add(LibraryMapping.placeDraft(line.t, replicaId, null))
            }
        }
    }

    fun finish() { check(!finished); writer.flush(); finished = true }
}
