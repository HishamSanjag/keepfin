package app.keepfin.core.merge

import app.keepfin.core.library.*
import app.keepfin.core.sync.*

/** JSON seam shared by generated fixtures and the JS facade. Fields use the existing sync-view wire units. */
object MergeWire {
    fun obj(v: Jv): Map<String, Jv> = (v as? JObj)?.members ?: error("expected object")
    fun parse(text: String): Jv = parseLoose(text) ?: error("invalid JSON")
    fun str(m: Map<String, Jv>, key: String): String? = (m[key] as? JStr)?.value
    fun num(m: Map<String, Jv>, key: String): Long? = (m[key] as? JNum)?.value
    private fun strings(v: Jv?): List<String> = (v as? JArr)?.items?.map { (it as JStr).value } ?: emptyList()
    private fun clocks(m: Map<String, Jv>): Map<String, Hlc> =
        (m["fieldClocks"] as? JObj)?.members?.mapValues { Hlc.parse((it.value as JStr).value) ?: error("invalid clock") } ?: emptyMap()
    private fun placements(m: Map<String, Jv>): Map<String, String> =
        (m["placements"] as? JObj)?.members?.mapValues { (it.value as JStr).value } ?: emptyMap()

    private fun fields(m: Map<String, Jv>): LibItem {
        val id = str(m, "id") ?: error("missing id")
        val view = jObj("items" to JArr(listOf(jObj("id" to JStr(id), "fields" to (m["fields"] ?: JObj(emptyMap())),
            "plays" to (m["plays"] ?: JNum(0))))),
            "sets" to jObj("favourites" to JArr(if (m["favourite"] == JBool(true)) listOf(JStr(id)) else emptyList()),
                ("tags:" + id) to JArr(strings(m["userTags"]).map { JStr(it) })))
        return LibraryMapping.project(view, "").items.single()
    }

    fun source(v: Jv): SItem {
        val m = obj(v)
        val f = obj(m["file"] ?: error("missing file evidence"))
        val added = Hlc.parse(str(m, "added") ?: "") ?: error("invalid item.add HLC")
        return SItem(fields(m), added, str(m, "revisionHash"), num(m, "size"), num(m, "mtimeHint"), str(m, "pathOnB"),
            FileEvidence(f["readable"] == JBool(true), num(f, "size"), num(f, "mtime"), str(f, "hash")),
            placements(m), clocks(m), str(m, "addActor") ?: added.actor, num(m, "addSeq") ?: 0)
    }

    fun target(v: Jv): TItem {
        val m = obj(v)
        return TItem(fields(m), Hlc.parse(str(m, "added") ?: "") ?: error("invalid item.add HLC"),
            str(m, "revisionHash"), num(m, "size"), num(m, "mtimeHint"), placements(m), clocks(m))
    }

    fun config(v: Jv): MergeConfig {
        val m = obj(v)
        return MergeConfig(str(m, "mergeId") ?: error("missing mergeId"), str(m, "actor") ?: error("missing actor"),
            str(m, "replicaId") ?: error("missing replicaId"), num(m, "firstSpaceOrder") ?: 0)
    }

    fun map(v: Jv): MapLine {
        val m = obj(v)
        return MapLine(str(m, "s")!!, str(m, "t")!!, MapHow.entries.first { it.wire == str(m, "how") })
    }

    fun batch(batch: Batch): String = encodeCanonical(jObj("index" to JNum(batch.index.toLong()),
        "drafts" to parse(draftsJson(batch.drafts)), "draftsHash" to JStr(batch.draftsHash)))

    fun readBatch(v: Jv): Batch {
        val m = obj(v)
        val drafts = (m.getValue("drafts") as JArr).items.map {
            val d = obj(it)
            OpDraft(str(d, "kind")!!, obj(d.getValue("body")))
        }
        return Batch(num(m, "index")!!.toInt(), drafts, str(m, "draftsHash")!!)
    }

    fun review(entry: ReviewEntry): String = encodeCanonical(JObj(buildMap {
        put("kind", JStr(entry.kind.wire)); put("s", JStr(entry.s))
        entry.t?.let { put("t", JStr(it)) }; entry.related?.let { put("related", JStr(it)) }
        entry.field?.let { put("field", JStr(it)) }
        entry.sourceValue?.let { put("sourceValue", it) }; entry.targetValue?.let { put("targetValue", it) }
    }))

    fun art(copy: ArtCopy): String = encodeCanonical(jObj("ownerKind" to JStr(copy.ownerKind),
        "ownerId" to JStr(copy.ownerId), "source" to JStr(copy.source), "target" to JStr(copy.target)))

    fun page(result: PageResult): String = encodeCanonical(jObj("accepted" to JBool(result.accepted),
        "needsHash" to JArr(result.needsHash.map {
            jObj("s" to JStr(it.s), "path" to JStr(it.path), "size" to (it.size?.let { n -> JNum(n) } ?: JNull),
                "mtime" to (it.mtime?.let { n -> JNum(n) } ?: JNull))
        })))

    /**
     * Metadata uses existing views: {playlists,spaces,sets}. Reference-only item stubs prevent projection filtering entries.
     * No field codec is duplicated here. Playlist rules (an opaque extension) are supplied separately to playlists().
     */
    fun metadata(v: Jv): LibModel {
        val m = obj(v)
        val refs = LinkedHashSet<String>()
        val playlists = (m["playlists"] as? JArr)?.items ?: emptyList()
        for (p in playlists) {
            val row = obj(p)
            for (song in (row["songs"] as? JArr)?.items ?: emptyList()) str(obj(song), "item")?.let { refs.add(it) }
            str(obj(row["fields"] ?: JObj(emptyMap())), "coverItemId")?.let { refs.add(it) }
        }
        for ((name, values) in (m["sets"] as? JObj)?.members ?: emptyMap())
            if (name.startsWith("new:") || name.startsWith("removed:")) refs.addAll(strings(values))
        val items = refs.map { jObj("id" to JStr(it), "fields" to JObj(emptyMap())) }
        return LibraryMapping.project(JObj(m + ("items" to JArr(items))), "")
    }
}
