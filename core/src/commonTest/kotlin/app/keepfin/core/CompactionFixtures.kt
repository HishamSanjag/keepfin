package app.keepfin.core

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.sync.SegmentCompaction
import app.keepfin.core.sync.SegmentInfo

// fixtures/engine/compaction: see SCHEMA.md there.

private fun str(o: JsonObject, key: String): String? = (o.members[key] as? JsonString)?.value

private fun num(o: JsonObject, key: String): Long? = (o.members[key] as? JsonNumber)?.value?.toLong()

private fun flag(o: JsonObject, key: String): Boolean? = (o.members[key] as? JsonBool)?.value

/** `segments` are explicit; `runs` lay down `count` contiguous files of `ops` ops each for an actor, after its last file. */
private fun segments(input: JsonObject): List<SegmentInfo> {
    val out = ArrayList<SegmentInfo>()
    val next = HashMap<String, Long>()
    val entries = (input.members["segments"] as? JsonArray)?.items ?: (input.members["runs"] as JsonArray).items
    for (e in entries) {
        e as JsonObject
        val actor = str(e, "actor") ?: "a"
        val signer = str(e, "signer")
        val named = flag(e, "named") ?: false
        val ok = flag(e, "ok") ?: true
        val count = num(e, "count")
        if (count != null) {
            val ops = num(e, "ops") ?: 1
            val bytes = num(e, "bytes") ?: (ops * 200)
            repeat(count.toInt()) {
                val first = next[actor] ?: 1L
                out.add(SegmentInfo(actor, first, first + ops - 1, bytes, signer, ok, named && it == 0))
                next[actor] = first + ops
            }
            // `skip` leaves a gap after the run
            next[actor] = (next[actor] ?: 1L) + (num(e, "skip") ?: 0)
        } else {
            val first = num(e, "first")!!
            val last = num(e, "last")!!
            out.add(SegmentInfo(actor, first, last, num(e, "bytes") ?: ((last - first + 1) * 200), signer, ok, named))
        }
    }
    return out
}

internal fun compactionHandler(input: JsonObject): JsonValue {
    val list = segments(input)
    val blocked = ((input.members["blocked"] as? JsonArray)?.items ?: emptyList()).map { (it as JsonString).value }.toSet()
    if (str(input, "op") == "subsumed") {
        val found = SegmentCompaction.subsumed(list, blocked)
        return obj("subsumed" to arr(found.map { arr(listOf(jnum(it.index), jnum(it.by))) }))
    }
    val merges = SegmentCompaction.plan(list, str(input, "currentSigner"), blocked)
    return obj("merges" to arr(merges.map { jstr("${it.actor}:${it.first}-${it.last}:${it.inputs.size}") }))
}
