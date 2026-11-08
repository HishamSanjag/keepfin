@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.sync

// The JavaScript surface of the segment compaction planner (see gui/shared/core/sync.ts). JSON text in and out:
// the files as `[{actor, first, last, bytes, signer, ok, named}]`, the answer as indexes into that list.

private fun infos(text: String): List<SegmentInfo> {
    val arr = (parseLoose(text) as? JArr)?.items ?: throw IllegalArgumentException("segments is not a JSON array")
    return arr.map { v ->
        val m = (v as? JObj)?.members ?: throw IllegalArgumentException("a segment is not an object")
        SegmentInfo(
            actor = (m["actor"] as? JStr)?.value ?: throw IllegalArgumentException("a segment has no actor"),
            first = (m["first"] as? JNum)?.value ?: throw IllegalArgumentException("a segment has no first"),
            last = (m["last"] as? JNum)?.value ?: throw IllegalArgumentException("a segment has no last"),
            bytes = (m["bytes"] as? JNum)?.value ?: 0L,
            signer = (m["signer"] as? JStr)?.value,
            ok = (m["ok"] as? JBool)?.value != false,
            named = (m["named"] as? JBool)?.value == true,
        )
    }
}

private fun actors(text: String): Set<String> =
    ((parseLoose(text) as? JArr)?.items ?: emptyList()).mapNotNull { (it as? JStr)?.value }.toSet()

/** `[{actor, first, last, inputs: [index, ...]}]`: see [SegmentCompaction.plan]. */
@JsExport
fun syncPlanCompaction(segmentsJson: String, currentSigner: String?, blockedJson: String): String {
    val merges = SegmentCompaction.plan(infos(segmentsJson), currentSigner, actors(blockedJson))
    return encodeCanonical(JArr(merges.map { m -> jObj("actor" to JStr(m.actor), "first" to JNum(m.first), "last" to JNum(m.last), "inputs" to JArr(m.inputs.map { JNum(it.toLong()) })) }))
}

/** `[{index, by}]`: see [SegmentCompaction.subsumed]. */
@JsExport
fun syncSubsumedSegments(segmentsJson: String, blockedJson: String): String {
    val found = SegmentCompaction.subsumed(infos(segmentsJson), actors(blockedJson))
    return encodeCanonical(JArr(found.map { jObj("index" to JNum(it.index.toLong()), "by" to JNum(it.by.toLong())) }))
}
