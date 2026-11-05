package app.keepfin.core.merge

import app.keepfin.core.library.*
import app.keepfin.core.sync.*

/**
 * A single deterministic pass over S in (item.add HLC, id) order, across page boundaries.
 * Call items for all pages, then spaces/playlists/art, then finish. Rejected pages have no effects.
 * Never retains items, map lines, reviews or batches; the host owns their disk-backed adapters.
 * Sink/ledger failures abort the pass: replay from its beginning with fresh scratch data, never retry a half-emitted page.
 */
class MergePlanner(
    private val config: MergeConfig,
    private val target: THashIndex,
    private val ledger: MergeLedger,
    private val sink: MergeSink,
) {
    private val writer = BatchWriter(sink)
    private var previous: Pair<Hlc, String>? = null
    private var metadataStarted = false
    private var finished = false
    private var spaceOrder = config.firstSpaceOrder

    /** Names are domain-separated and length-delimited by canonical JSON, independent of paging. */
    fun entityId(kind: String, source: String): String {
        val hex = sha256Hex(encodeCanonical(JArr(listOf(JStr("keepfin-merge-v1"), JStr(config.mergeId), JStr(kind), JStr(source)))))
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-5" + hex.substring(13, 16) +
            "-8" + hex.substring(17, 20) + "-" + hex.substring(20, 32)
    }

    private fun spaceId(id: String) = entityId("space", LibraryMapping.spaceWireId(id))

    private fun context(entity: String): DiffContext {
        var entry = 0
        return DiffContext(config.actor, config.replicaId, { entityId("entry:" + entity, (entry++).toString()) },
            { 0L }, { emptyList() }, RecordingObserved())
    }

    private fun model(items: List<LibItem> = emptyList(), collections: List<LibCollection> = emptyList(),
        spaces: List<LibSpace> = emptyList()) =
        LibModel(items, collections, spaces, LibRoutes("music", "downloads"))

    private fun mappedDrafts(item: LibItem): List<OpDraft> =
        LibraryMapping.diff(null, model(items = listOf(item)), context(item.id)).filter { it.kind != "profile.set" }

    private fun fieldsOf(item: LibItem): Map<String, Jv> =
        (mappedDrafts(item).first { it.kind == "item.add" }.body.getValue("fields") as JObj).members

    private fun missing(item: SItem): Boolean = !item.file.readable || item.pathOnB == null

    private fun content(item: SItem): String? {
        if (missing(item)) return item.revisionHash
        if (item.file.hash != null) return item.file.hash
        return if (item.revisionHash != null && item.size != null && item.mtimeHint != null &&
            item.size == item.file.size && item.mtimeHint == item.file.mtime) item.revisionHash else null
    }

    /** Re-submit the same page with hash results (or readable=false) when needsHash is nonempty. */
    fun items(page: List<SItem>, metadata: (String) -> SMetadata? = { null }): PageResult {
        check(!finished && !metadataStarted) { "items must precede metadata and finish" }
        var prev = previous
        val needs = ArrayList<NeedsHash>()
        for (item in page) {
            require(item.id.isNotEmpty())
            val key = item.added to item.id
            require(prev == null || prev.first < key.first || (prev.first == key.first && prev.second < key.second)) {
                "S pages must be strictly ordered by item.add HLC, then id"
            }
            require(ledger.mapped(item.id) == null) { "source item already planned" }
            prev = key
            item.revisionHash?.let { require(isContentId(it)) }
            item.file.hash?.let { require(isContentId(it)) }
            require(item.size == null || item.size in 0..MAX_SAFE_INTEGER)
            require(item.file.size == null || item.file.size in 0..MAX_SAFE_INTEGER)
            if (!missing(item)) {
                require(LibraryMapping.validPlacement(item.pathOnB!!)) { "invalid source placement" }
                if (content(item) == null) needs.add(NeedsHash(item.id, item.pathOnB, item.file.size, item.file.mtime))
                // A fresh hash must describe a known size, not the stale record's size.
                if (item.file.hash != null) require(item.file.size != null) { "fresh hash needs observed size" }
            }
        }
        if (needs.isNotEmpty()) return PageResult(needs)
        for (item in page) describe(item, metadata(item.id))
        previous = prev
        return PageResult(emptyList())
    }

    private fun describe(source: SItem, meta: SMetadata?) {
        val hash = content(source)
        val absent = missing(source)
        val first = if (hash != null) ledger.firstSource(hash) else null
        val candidates = if (!absent && first == null && hash != null) target.lookup(hash).iterator() else emptyList<String>().iterator()
        val match = if (candidates.hasNext()) target.item(candidates.next()) else null
        if (match != null) require(match.revisionHash == hash) { "hash index returned different content" }
        val id = match?.id ?: entityId("item", source.id)
        val how = if (first != null) MapHow.KeptDuplicate else if (match != null) MapHow.Linked else MapHow.Added
        if (match != null) {
            while (candidates.hasNext()) sink.review(ReviewEntry(ReviewKind.ManyMatches, source.id, id, candidates.next()))
        }
        if (first != null) sink.review(ReviewEntry(ReviewKind.DuplicateInS, source.id, id, first))
        if (absent) sink.review(ReviewEntry(ReviewKind.MissingFile, source.id, id))

        var item = source.fields.copy(id = id, space = spaceId(source.fields.space),
            place = if (absent) null else source.pathOnB)
        if (meta != null) item = item.copy(favorite = item.favorite || meta.favourite,
            userTags = (item.userTags + meta.tags).distinct(), plays = meta.plays ?: item.plays,
            lastPlayedAt = meta.lastPlayedAt ?: item.lastPlayedAt,
            position = meta.position ?: item.position, watched = meta.watched ?: item.watched)
        val drafts = mappedDrafts(item)
        val incoming = (drafts.first { it.kind == "item.add" }.body.getValue("fields") as JObj).members
        require(incoming.keys.all { it in MergeFieldRules.item }) { "item field has no merge policy" }
        if (match == null) {
            writer.add(drafts.first { it.kind == "item.add" })
            if (hash != null) {
                val size = if (absent) source.size else source.file.size
                if (size != null) writer.add(LibraryMapping.reviseDraft(id, hash, size, emptyList()))
            }
            for (d in drafts) if (d.kind != "item.add") writer.add(d)
        } else {
            val existing = fieldsOf(match.fields)
            for ((name, value) in incoming) {
                if (MergeFieldRules.item.getValue(name) == FieldRule.Newer) {
                    if (newer(name, source, item, match) && existing[name] != value)
                        writer.add(OpDraft("item.set", mapOf("item" to JStr(id), "field" to JStr(name), "value" to value)))
                } else if (existing[name] != value) {
                    sink.review(ReviewEntry(ReviewKind.DifferingField, source.id, id, field = name,
                        sourceValue = value, targetValue = existing[name] ?: JNull))
                }
            }
            if (!absent) writer.add(LibraryMapping.placeDraft(id, config.replicaId, source.pathOnB))
            // Absolute per-actor counters (not increments) make replay idempotent, including plays.
            for (d in drafts) if (d.kind == "set.add" || d.kind == "item.set") writer.add(d)
        }
        val line = MapLine(source.id, id, how)
        ledger.record(line)
        sink.map(line)
        if (hash != null && first == null) ledger.rememberContent(hash, source.id)
    }

    private fun newer(field: String, source: SItem, incoming: LibItem, target: TItem): Boolean {
        val s = source.fieldClocks[field]
        val t = target.fieldClocks[field]
        if (s != null || t != null) return s != null && (t == null || s > t)
        // Without register clocks, the playback snapshot is timestamped by lastPlayedAt. T wins ties.
        return (incoming.lastPlayedAt ?: -1.0) > (target.fields.lastPlayedAt ?: -1.0)
    }

    private fun startMetadata() { check(!finished); metadataStarted = true }
    private fun itemId(id: String): String = ledger.mapped(id)?.t
        ?: error("metadata references an item not in the S map: " + id)

    /** Structured reference fields only; artist/tag/source strings which happen to equal ids are not rewritten. */
    fun remapRules(value: Jv): Jv = remap(value, null)

    private fun remap(value: Jv, domain: String?): Jv = when (value) {
        is JStr -> when (domain) {
            "item" -> JStr(itemId(value.value))
            "space" -> JStr(spaceId(value.value))
            "collection", "playlist" -> JStr(entityId("playlist", value.value))
            else -> value
        }
        is JArr -> JArr(value.items.map { remap(it, domain) })
        is JObj -> {
            val field = (value.members["field"] as? JStr)?.value
            JObj(value.members.mapValues { (key, v) ->
                val ref = when (key) {
                    "item", "itemId", "itemIds", "coverItemId" -> "item"
                    "space", "spaceId", "spaceIds", "excludeSpaces" -> "space"
                    "collection", "collectionId", "collectionIds", "playlist", "playlistId" -> "collection"
                    "values" -> field?.takeIf { it in setOf("item", "space", "collection", "playlist") }
                    else -> null
                }
                remap(v, ref)
            })
        }
        else -> value
    }

    fun spaces(page: List<LibSpace>) {
        startMetadata()
        for (source in page) {
            val id = spaceId(source.id)
            for (same in target.sameName("space", source.name))
                sink.review(ReviewEntry(ReviewKind.SameNameSpace, source.id, id, same))
            val space = source.copy(id = id, rules = source.rules?.let { remapRules(it) })
            for (d in LibraryMapping.diff(null, model(spaces = listOf(space)), context(id))) {
                if (d.kind != "space.add") continue
                val fields = (d.body.getValue("fields") as JObj).members
                require(fields.keys.all { it in MergeFieldRules.space }) { "space field has no merge policy" }
                val adjusted = fields.toMutableMap()
                adjusted["order"] = JNum(spaceOrder++)
                // The entity has a new id, so preserving legacyId would project it back onto S's identity.
                writer.add(d.copy(body = d.body + ("fields" to JObj(adjusted))))
            }
        }
    }

    /** All collection kinds, embedded follows, sets and entry order use LibraryMapping's codec. */
    fun playlists(page: List<LibCollection>, rules: (String) -> Jv? = { null }) {
        startMetadata()
        for (source in page) {
            val id = entityId("playlist", source.id)
            for (same in target.sameName("playlist", source.name))
                sink.review(ReviewEntry(ReviewKind.SameNamePlaylist, source.id, id, same))
            val c = source.copy(id = id, space = spaceId(source.space), itemIds = source.itemIds.map { itemId(it) },
                coverItemId = source.coverItemId?.let { itemId(it) }, newItemIds = source.newItemIds.map { itemId(it) },
                removedItemIds = source.removedItemIds.map { itemId(it) })
            // Mapping filters references against known items. Minimal stubs provide that knowledge; their drafts are discarded.
            val known = (c.itemIds + listOfNotNull(c.coverItemId) + c.newItemIds + c.removedItemIds).distinct()
                .map { LibItem(it, c.space, "other", "") }
            for (d in LibraryMapping.diff(null, model(items = known, collections = listOf(c)), context(id))) {
                if (d.kind == "playlist.add") {
                    val fields = (d.body.getValue("fields") as JObj).members
                    require(fields.keys.all { it in MergeFieldRules.playlist }) { "playlist field has no merge policy" }
                    val rule = rules(source.id)
                    writer.add(if (rule == null) d else d.copy(body = d.body +
                        ("fields" to JObj(fields + ("rules" to remapRules(rule))))))
                } else if (d.kind == "entry.insert" || (d.kind == "set.add" &&
                        (d.body["set"] as? JStr)?.value in listOf("seen:" + id, "new:" + id, "removed:" + id))) writer.add(d)
            }
        }
    }

    fun artwork(page: List<ArtFile>) {
        startMetadata()
        for (file in page) {
            require(file.name.isNotEmpty() && file.name != "." && file.name != ".." &&
                !file.name.contains('/') && !file.name.contains('\\')) { "art name must be one path segment" }
            val id = when (file.ownerKind) {
                "item" -> itemId(file.ownerId)
                "space" -> spaceId(file.ownerId)
                "playlist" -> entityId("playlist", file.ownerId)
                else -> error("unknown artwork owner")
            }
            sink.art(ArtCopy(file.ownerKind, id, file.source,
                "art/" + entityId("art:" + file.ownerKind, file.ownerId) + "/" + file.name))
        }
    }

    /** Selects only additions by B after activation; old items' post-combine edits stay in T. */
    fun reverseItems(page: List<SItem>, bActorInT: String, activationSeq: Long,
        metadata: (String) -> SMetadata? = { null }): PageResult {
        require(isUuid(bActorInT) && activationSeq in 0..MAX_SAFE_INTEGER)
        return items(page.filter { it.addActor == bActorInT && it.addSeq > activationSeq }, metadata)
    }

    fun finish() { check(!finished); writer.flush(); finished = true }
}
