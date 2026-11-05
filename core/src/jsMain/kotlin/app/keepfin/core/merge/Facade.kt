@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.merge

import app.keepfin.core.library.*
import app.keepfin.core.sync.*

private fun rows(text: String): List<Jv> = (MergeWire.parse(text) as JArr).items

private fun sink(emit: (String, String) -> Unit): MergeSink = object : MergeSink {
    override fun batch(batch: Batch) = emit("batch", MergeWire.batch(batch))
    override fun map(line: MapLine) = emit("map", line.json())
    override fun review(entry: ReviewEntry) = emit("review", MergeWire.review(entry))
    override fun art(copy: ArtCopy) = emit("art", MergeWire.art(copy))
}

private fun index(
    nextMatch: (String, String?) -> String?,
    targetJson: (String) -> String,
    nextName: (String, String, String?) -> String?,
): THashIndex = object : THashIndex {
    override fun lookup(hash: String): Sequence<String> = sequence {
        var after: String? = null
        while (true) {
            val id = nextMatch(hash, after) ?: break
            require(id != after) { "index cursor did not advance" }
            yield(id)
            after = id
        }
    }
    override fun item(id: String): TItem = MergeWire.target(MergeWire.parse(targetJson(id)))
    override fun sameName(kind: String, name: String): Sequence<String> = sequence {
        var after: String? = null
        while (true) {
            val id = nextName(kind, name, after) ?: break
            require(id != after) { "name cursor did not advance" }
            yield(id)
            after = id
        }
    }
}

/**
 * All callbacks are synchronous. Index cursors return the first/next id (null at end); match order is (add HLC,id).
 * Scratch callbacks belong to this planning pass, and emit receives canonical batch/map/review/art JSON.
 * See merge/README.md for the JSON input shape. No entire-library array crosses this facade.
 */
@JsExport
class NetMergePlanner(
    configJson: String,
    nextMatch: (String, String?) -> String?,
    itemJson: (String) -> String,
    nextName: (String, String, String?) -> String?,
    readFirstSource: (String) -> String?,
    writeFirstSource: (String, String) -> Unit,
    readMapJson: (String) -> String?,
    writeMapJson: (String) -> Unit,
    emit: (String, String) -> Unit,
) {
    private val planner = MergePlanner(MergeWire.config(MergeWire.parse(configJson)), index(nextMatch, itemJson, nextName),
        object : MergeLedger {
            override fun firstSource(hash: String): String? = readFirstSource(hash)
            override fun rememberContent(hash: String, source: String) = writeFirstSource(hash, source)
            override fun mapped(source: String): MapLine? = readMapJson(source)?.let { MergeWire.map(MergeWire.parse(it)) }
            override fun record(line: MapLine) = writeMapJson(line.json())
        }, sink(emit))

    fun itemsJson(pageJson: String): String = MergeWire.page(planner.items(rows(pageJson).map { MergeWire.source(it) }))

    fun reverseItemsJson(pageJson: String, bActorInT: String, activationSeq: Double): String {
        require(activationSeq.isFinite() && activationSeq == kotlin.math.floor(activationSeq) &&
            activationSeq >= 0 && activationSeq <= MAX_SAFE_INTEGER.toDouble())
        return MergeWire.page(planner.reverseItems(rows(pageJson).map { MergeWire.source(it) }, bActorInT, activationSeq.toLong()))
    }

    fun spacesJson(viewsJson: String) = planner.spaces(MergeWire.metadata(MergeWire.parse(viewsJson)).spaces)

    fun playlistsJson(viewsJson: String) {
        val views = MergeWire.parse(viewsJson)
        val rules = ((MergeWire.obj(views)["playlists"] as? JArr)?.items ?: emptyList()).associate {
            val row = MergeWire.obj(it)
            MergeWire.str(row, "id")!! to (MergeWire.obj(row.getValue("fields"))["rules"])
        }
        planner.playlists(MergeWire.metadata(views).collections) { rules[it] }
    }

    fun artworkJson(pageJson: String) = planner.artwork(rows(pageJson).map {
        val m = MergeWire.obj(it)
        ArtFile(MergeWire.str(m, "ownerKind")!!, MergeWire.str(m, "ownerId")!!,
            MergeWire.str(m, "source")!!, MergeWire.str(m, "name")!!)
    })

    fun finish() = planner.finish()
}

/** Separate step 2 only. observedJson returns {tags:[opId],heads:[opId]}. Missing choices default to Keep. */
@JsExport
class NetMergeSeparatePlanner(
    replicaId: String,
    itemJson: (String) -> String,
    observedJson: (String) -> String,
    emit: (String, String) -> Unit,
) {
    private val planner = SeparatePlanner(replicaId, index({ _, _ -> null }, itemJson, { _, _, _ -> null }),
        object : ObservedLookup {
            override fun item(id: String): ObservedTags {
                val m = MergeWire.obj(MergeWire.parse(observedJson(id)))
                fun strings(key: String) = ((m[key] as? JArr)?.items ?: emptyList()).map { (it as JStr).value }
                return ObservedTags(strings("tags"), strings("heads"))
            }
            override fun playlist(id: String): List<String> = emptyList()
            override fun space(id: String): List<String> = emptyList()
            override fun member(set: String, element: String): List<String> = emptyList()
        }, sink(emit))

    fun itemsJson(mapPageJson: String, choicesJson: String) {
        val choices = MergeWire.obj(MergeWire.parse(choicesJson))
        require(choices.values.all { it == JStr("Keep") || it == JStr("Remove") })
        planner.items(rows(mapPageJson).map { MergeWire.map(it) }) {
            if (choices[it] == JStr("Remove")) KeepChoice.Remove else KeepChoice.Keep
        }
    }
    fun finish() = planner.finish()
}

@JsExport
fun netMergeRemainingDrafts(batchJson: String, m: Int): String =
    draftsJson(remainingDrafts(MergeWire.readBatch(MergeWire.parse(batchJson)), m))
