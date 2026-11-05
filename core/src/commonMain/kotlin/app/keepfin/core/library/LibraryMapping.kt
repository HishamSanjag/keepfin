package app.keepfin.core.library

import app.keepfin.core.sync.Fractional
import app.keepfin.core.sync.JArr
import app.keepfin.core.sync.JBool
import app.keepfin.core.sync.JNum
import app.keepfin.core.sync.JObj
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.Jv
import app.keepfin.core.sync.decodeCanonical
import app.keepfin.core.sync.CanonicalResult
import app.keepfin.core.sync.isUuid
import app.keepfin.core.sync.sha256Hex
import kotlin.math.abs
import kotlin.math.floor

// The mapping between the library record and sync operations: a port of gui/desktop/store/mapping.ts, the reference for
// the wire conventions (dev-docs/plans/KEEPFIN-FOLDER-DESKTOP-SPEC.md section 3):
//
//   diff(before, after, ctx)   the operations that turn `before` into `after` (minimal; nothing for unchanged data)
//   project(views, replicaId)  the model a views document describes
//
//   * Sync numbers are integers, so fractional values are scaled: duration, position and chapter starts are milliseconds,
//     gain is milli-decibels. Other numbers are rounded (like JavaScript's Math.round: floor(x + 0.5)).
//   * `media` carries the item kind; `space` carries the space's wire id. The built-in spaces have fixed wire ids; any other
//     id that is not a UUID gets a name-derived UUID and the space stores the original in `legacyId`.
//   * The user's own tags are the set `tags:<item>`, favourites the set `favourites`.
//
// The order of operations and of fields is part of the behaviour (the fixtures compare lists), so maps keep insertion order.
// Items and collections whose id is not a UUID are skipped: the app gives them a UUID first.

object LibraryMapping {
    const val PLAYS_PREFIX = "plays@"
    const val FAVOURITES = "favourites"

    val BUILTIN_SPACE_IDS: Map<String, String> = linkedMapOf(
        "downloads" to "6b8e1c10-2d3a-4f55-8a01-000000000001",
        "music" to "6b8e1c10-2d3a-4f55-8a01-000000000002",
        "videos" to "6b8e1c10-2d3a-4f55-8a01-000000000003",
    )
    private val builtinByWire: Map<String, String> = BUILTIN_SPACE_IDS.entries.associate { it.value to it.key }
    private val KINDS = setOf("video", "audio", "image", "other")
    private const val MAX_SAFE = 9007199254740991.0
    private const val MAX_PLACEMENT = 1024

    private fun uuidFromName(name: String): String {
        val hex = sha256Hex("keepfin-space:$name")
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-5${hex.substring(13, 16)}-8${hex.substring(17, 20)}-${hex.substring(20, 32)}"
    }

    /** The id a space has on the wire. */
    fun spaceWireId(id: String): String {
        if (isUuid(id)) return id
        return BUILTIN_SPACE_IDS[id] ?: uuidFromName(id)
    }

    private fun needsLegacy(id: String) = !isUuid(id) && id !in BUILTIN_SPACE_IDS

    /** Whether text is a legal placement (sync spec 4.1): relative, '/'-separated, no empty, `.` or `..` segment. */
    fun validPlacement(value: String): Boolean {
        if (value.isEmpty() || value.length > MAX_PLACEMENT || value.startsWith("/") || value.contains('\\')) return false
        return value.split('/').all { it != "" && it != "." && it != ".." }
    }

    // ------------------------------------------------------------------------------------------------ field codecs

    private fun finite(v: Double?): Boolean = v != null && v.isFinite()

    private fun safeInt(v: Double): Long? {
        val r = floor(v + 0.5)
        if (!(abs(r) <= MAX_SAFE)) return null
        return if (r == 0.0) 0L else r.toLong()
    }

    private fun encInt(v: Double?): Jv? = if (v != null && v.isFinite()) safeInt(v)?.let { JNum(it) } else null
    private fun encScaled(v: Double?): Jv? = if (v != null && v.isFinite()) safeInt(v * 1000)?.let { JNum(it) } else null
    private fun encStr(v: String?): Jv? = v?.let { JStr(it) }
    private fun encBool(v: Boolean?): Jv? = v?.let { JBool(it) }
    private fun encStrings(v: List<String>?): Jv? = if (v != null && v.isNotEmpty()) JArr(v.map { JStr(it) }) else null
    private fun encChapters(v: List<LibChapter>?): Jv? {
        if (v == null || v.isEmpty()) return null
        val out = ArrayList<Jv>()
        for (c in v) {
            if (!c.start.isFinite()) return null
            val start = safeInt(c.start * 1000) ?: return null
            out.add(JObj(linkedMapOf("start" to JNum(start), "title" to JStr(c.title))))
        }
        return JArr(out)
    }

    private fun decInt(v: Jv?): Double? = (v as? JNum)?.value?.toDouble()
    private fun decScaled(v: Jv?): Double? = (v as? JNum)?.let { it.value.toDouble() / 1000 }
    private fun decStr(v: Jv?): String? = (v as? JStr)?.value
    private fun decBool(v: Jv?): Boolean? = (v as? JBool)?.value
    private fun decStrings(v: Jv?): List<String>? {
        val items = (v as? JArr)?.items ?: return null
        if (items.isEmpty() || !items.all { it is JStr }) return null
        return items.map { (it as JStr).value }
    }

    private fun decChapters(v: Jv?): List<LibChapter>? {
        val items = (v as? JArr)?.items ?: return null
        if (items.isEmpty()) return null
        val out = ArrayList<LibChapter>()
        for (c in items) {
            val m = (c as? JObj)?.members ?: return null
            val start = m["start"] as? JNum ?: return null
            val title = m["title"] as? JStr ?: return null
            out.add(LibChapter(start.value.toDouble() / 1000, title.value))
        }
        return out
    }

    private fun sortedUnique(values: Collection<String>): List<String> = LinkedHashSet(values).filter { it.isNotEmpty() }.sorted()

    // ------------------------------------------------------------------------------------------------ snapshots

    private class ItemSnap(val id: String, val fields: LinkedHashMap<String, Jv>, val favorite: Boolean, val userTags: List<String>, val plays: Long, val place: String?) {
        fun sameAs(o: ItemSnap) = fields == o.fields && favorite == o.favorite && userTags == o.userTags && plays == o.plays && place == o.place
    }

    private class CollectionSnap(val id: String, val fields: LinkedHashMap<String, Jv>, val itemIds: List<String>, val seen: List<String>, val news: List<String>, val removed: List<String>) {
        fun sameMetaAs(o: CollectionSnap) = fields == o.fields && seen == o.seen && news == o.news && removed == o.removed
    }

    private class SpaceSnap(val id: String, val wire: String, val fields: LinkedHashMap<String, Jv>)

    private class Snap(
        val items: LinkedHashMap<String, ItemSnap>,
        val collections: LinkedHashMap<String, CollectionSnap>,
        val spaces: LinkedHashMap<String, SpaceSnap>,
        val routes: Jv?,
    )

    private fun LinkedHashMap<String, Jv>.put(name: String, value: Jv?) {
        if (value != null) this[name] = value
    }

    private fun itemSnap(item: LibItem): ItemSnap {
        val fields = LinkedHashMap<String, Jv>()
        fields["media"] = JStr(if (item.kind in KINDS) item.kind else "other")
        fields["space"] = JStr(spaceWireId(item.space))
        fields.put("title", encStr(item.title))
        fields.put("artist", encStr(item.artist))
        fields.put("album", encStr(item.album))
        fields.put("albumArtist", encStr(item.albumArtist))
        fields.put("track", encInt(item.track))
        fields.put("disc", encInt(item.disc))
        fields.put("year", encInt(item.year))
        fields.put("genre", encStr(item.genre))
        fields.put("duration", encScaled(item.duration))
        fields.put("width", encInt(item.width))
        fields.put("height", encInt(item.height))
        fields.put("chapters", encChapters(item.chapters))
        fields.put("gain", encScaled(item.gain))
        fields.put("subtitles", encStrings(item.subtitles))
        fields.put("hasLyrics", encBool(item.hasLyrics))
        fields.put("sourceUrl", encStr(item.sourceUrl))
        fields.put("sourceId", encStr(item.sourceId))
        fields.put("addedAt", encInt(item.addedAt))
        fields.put("tags", encStrings(item.tags))
        fields.put("lastPlayedAt", encInt(item.lastPlayedAt))
        fields.put("position", encScaled(item.position))
        fields.put("watched", encBool(item.watched))
        val plays = item.plays
        val count = if (plays != null && plays.isFinite() && plays > 0) safeInt(plays) ?: 0L else 0L
        val place = item.place?.takeIf { validPlacement(it) }
        return ItemSnap(item.id, fields, item.favorite, sortedUnique(item.userTags), count, place)
    }

    private fun followWire(f: LibFollow): Jv {
        val out = LinkedHashMap<String, Jv>()
        out["mode"] = JStr(f.mode)
        out.put("intervalHours", encInt(f.intervalHours))
        out["wifiOnly"] = JBool(f.wifiOnly)
        out["notify"] = JBool(f.notify)
        out.put("confirmAbove", encInt(f.confirmAbove))
        val count = f.pendingCount
        val at = f.pendingAt
        if (count != null && at != null && count.isFinite() && at.isFinite()) {
            val pending = LinkedHashMap<String, Jv>()
            pending.put("count", encInt(count))
            pending.put("at", encInt(at))
            out["pending"] = JObj(pending)
        }
        return JObj(out)
    }

    private fun collectionSnap(c: LibCollection, known: Map<String, *>): CollectionSnap {
        val own = c.kind == "own"
        val fields = LinkedHashMap<String, Jv>()
        fields["kind"] = JStr(if (own) "playlist" else "collection")
        if (!own) fields["collectionKind"] = JStr(c.kind)
        fields["name"] = JStr(c.name)
        fields["space"] = JStr(spaceWireId(c.space))
        val source = c.source
        if (source != null) {
            val s = LinkedHashMap<String, Jv>()
            s["url"] = JStr(source.url)
            s["provider"] = JStr(source.provider)
            if (source.id != null) s["id"] = JStr(source.id)
            fields["source"] = JObj(s)
        }
        if (c.follow != null) fields["follow"] = followWire(c.follow)
        fields.put("coverItemId", encStr(c.coverItemId))
        fields.put("createdAt", encInt(c.createdAt))
        fields.put("updatedAt", encInt(c.updatedAt))
        fun knownOnly(ids: List<String>) = LinkedHashSet(ids).filter { it in known }.sorted()
        val seen = sortedUnique(c.seen)
        val news = knownOnly(c.newItemIds)
        val removed = knownOnly(c.removedItemIds)
        val members = LinkedHashSet(c.itemIds).filter { it in known }
        return CollectionSnap(c.id, fields, members, seen, news, removed)
    }

    private fun spaceSnap(space: LibSpace, order: Int): SpaceSnap {
        val smart = space.type == "smart"
        val fields = LinkedHashMap<String, Jv>()
        fields["name"] = JStr(space.name)
        fields["kind"] = JStr(space.kind)
        fields["folder"] = JStr(if (smart) "" else space.folder)
        fields["layout"] = JStr(space.layout)
        fields["naming"] = JStr(space.naming)
        fields["skipExisting"] = JBool(space.skipExisting)
        if (!space.ignore.isNullOrEmpty()) fields["ignore"] = JArr(space.ignore.map { JStr(it) })
        fields.put("scanFolder", encBool(space.scanFolder))
        fields["type"] = JStr(space.type)
        fields.put("color", encStr(space.color))
        fields.put("icon", encStr(space.icon))
        fields.put("pinned", encBool(space.pinned))
        if (space.rules != null) fields["rules"] = space.rules
        if (!smart && space.linked) fields["linked"] = JBool(true)
        fields["order"] = JNum(order.toLong())
        if (needsLegacy(space.id)) fields["legacyId"] = JStr(space.id)
        return SpaceSnap(space.id, spaceWireId(space.id), fields)
    }

    private fun snapshot(model: LibModel): Snap {
        val items = LinkedHashMap<String, ItemSnap>()
        for (item in model.items) if (isUuid(item.id)) items[item.id] = itemSnap(item)
        val collections = LinkedHashMap<String, CollectionSnap>()
        for (c in model.collections) if (isUuid(c.id)) collections[c.id] = collectionSnap(c, items)
        val spaces = LinkedHashMap<String, SpaceSnap>()
        model.spaces.forEachIndexed { order, space -> spaces[space.id] = spaceSnap(space, order) }
        val routes = JObj(linkedMapOf("music" to JStr(spaceWireId(model.routes.music)), "other" to JStr(spaceWireId(model.routes.other))))
        return Snap(items, collections, spaces, routes)
    }

    // ------------------------------------------------------------------------------------------------ diff

    private fun draft(kind: String, vararg body: Pair<String, Jv>) = OpDraft(kind, linkedMapOf(*body))

    private fun strs(values: List<String>): Jv = JArr(values.map { JStr(it) })

    /** Field-level set operations between two field maps; a removed field is written as null. */
    private fun fieldOps(kind: String, id: String, before: Map<String, Jv>, after: Map<String, Jv>, out: MutableList<OpDraft>) {
        for ((name, value) in after) if (before[name] != value) out.add(draft("$kind.set", kind to JStr(id), "field" to JStr(name), "value" to value))
        for (name in before.keys) if (name !in after) out.add(draft("$kind.set", kind to JStr(id), "field" to JStr(name), "value" to app.keepfin.core.sync.JNull))
    }

    private fun setOps(set: String, before: List<String>, after: List<String>, ctx: DiffContext, out: MutableList<OpDraft>) {
        val had = before.toHashSet()
        val has = after.toHashSet()
        for (element in after) if (element !in had) out.add(draft("set.add", "set" to JStr(set), "element" to JStr(element)))
        for (element in before) {
            if (element in has) continue
            val observedTags = ctx.observed.member(set, element)
            if (observedTags.isNotEmpty()) out.add(draft("set.remove", "set" to JStr(set), "element" to JStr(element), "observedTags" to strs(observedTags)))
        }
    }

    /** Indexes of a longest strictly increasing subsequence of [values]. */
    private fun longestIncreasing(values: List<Int>): List<Int> {
        val tails = ArrayList<Int>()
        val prev = IntArray(values.size) { -1 }
        for (i in values.indices) {
            var lo = 0
            var hi = tails.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (values[tails[mid]] < values[i]) lo = mid + 1 else hi = mid
            }
            if (lo > 0) prev[i] = tails[lo - 1]
            if (lo == tails.size) tails.add(i) else tails[lo] = i
        }
        val out = ArrayList<Int>()
        var at = if (tails.isNotEmpty()) tails[tails.size - 1] else -1
        while (at >= 0) {
            out.add(at)
            at = prev[at]
        }
        out.reverse()
        return out
    }

    /**
     * The entry operations that turn a playlist's old item order into the new one. Entries of kept items stay (their keys are
     * untouched) except the minimum set outside a longest increasing subsequence, which gets `entry.move`; new items get
     * `entry.insert`; removed items get `entry.remove`.
     */
    private fun entryOps(playlist: String, oldIds: List<String>, newIds: List<String>, songs: List<Song>, ctx: DiffContext, out: MutableList<OpDraft>) {
        val was = oldIds.toHashSet()
        val now = newIds.toHashSet()
        val byItem = HashMap<String, MutableList<Song>>()
        for (song in songs) byItem.getOrPut(song.item) { ArrayList() }.add(song)
        for (item in oldIds) if (item !in now) for (song in byItem[item] ?: emptyList<Song>()) out.add(draft("entry.remove", "entry" to JStr(song.entry)))

        val position = HashMap<String, Int>()
        newIds.forEachIndexed { at, item -> position[item] = at }
        val keptSongs = ArrayList<Song>()
        val seenItem = HashSet<String>()
        for (song in songs) {
            if (song.item !in was || song.item !in now || song.item in seenItem) continue
            seenItem.add(song.item)
            keptSongs.add(song)
        }
        val stable = HashSet<String>()
        var tied = false
        for (at in keptSongs.indices) if (at > 0 && keptSongs[at].key == keptSongs[at - 1].key) tied = true
        if (!tied) for (at in longestIncreasing(keptSongs.map { position.getValue(it.item) })) stable.add(keptSongs[at].item)
        val keyOf = HashMap<String, String>()
        val entryOf = HashMap<String, String>()
        for (song in keptSongs) {
            keyOf[song.item] = song.key
            entryOf[song.item] = song.entry
        }

        var left: String? = null
        var at = 0
        while (at < newIds.size) {
            val item = newIds[at]
            if (item in stable) {
                left = keyOf.getValue(item)
                at++
                continue
            }
            var end = at
            while (end < newIds.size && newIds[end] !in stable) end++
            val right: String? = if (end < newIds.size) keyOf.getValue(newIds[end]) else null
            var cursor: String? = left
            for (k in at until end) {
                val target = newIds[k]
                // a kept item with no entry any more (removed elsewhere) is not resurrected
                if (target in was && target !in entryOf) continue
                val key = Fractional.keyBetween(cursor, right)
                cursor = key
                val entry = entryOf[target]
                if (entry != null) out.add(draft("entry.move", "entry" to JStr(entry), "key" to JStr(key)))
                else out.add(draft("entry.insert", "playlist" to JStr(playlist), "entry" to JStr(ctx.newId()), "item" to JStr(target), "key" to JStr(key)))
            }
            left = cursor
            at = end
        }
    }

    private fun addItem(a: ItemSnap, ctx: DiffContext, out: MutableList<OpDraft>) {
        out.add(draft("item.add", "item" to JStr(a.id), "fields" to JObj(a.fields)))
        if (a.place != null) out.add(draft("item.place", "item" to JStr(a.id), "place" to JStr(ctx.replicaId), "path" to JStr(a.place)))
        if (a.plays > 0) out.add(draft("item.set", "item" to JStr(a.id), "field" to JStr(PLAYS_PREFIX + ctx.actor), "value" to JNum(a.plays)))
        if (a.favorite) out.add(draft("set.add", "set" to JStr(FAVOURITES), "element" to JStr(a.id)))
        for (tag in a.userTags) out.add(draft("set.add", "set" to JStr("tags:${a.id}"), "element" to JStr(tag)))
    }

    /** The operations that turn [before] into [after] (a null [before] is an empty library, which also writes the routes). */
    fun diff(before: LibModel?, after: LibModel, ctx: DiffContext): List<OpDraft> {
        val was = if (before == null) Snap(LinkedHashMap(), LinkedHashMap(), LinkedHashMap(), null) else snapshot(before)
        val now = snapshot(after)
        val out = ArrayList<OpDraft>()

        // spaces
        for ((id, a) in now.spaces) {
            val b = was.spaces[id]
            if (b == null) out.add(draft("space.add", "space" to JStr(a.wire), "fields" to JObj(a.fields)))
            else if (a.fields != b.fields) fieldOps("space", a.wire, b.fields, a.fields, out)
        }
        for ((id, b) in was.spaces) {
            if (id in now.spaces) continue
            out.add(draft("space.delete", "space" to JStr(b.wire), "observedTags" to strs(ctx.observed.space(b.wire))))
        }
        if (now.routes != null && now.routes != was.routes) out.add(draft("profile.set", "field" to JStr("routes"), "value" to now.routes))

        // items
        for ((id, a) in now.items) {
            val b = was.items[id]
            if (b == null) {
                addItem(a, ctx, out)
                continue
            }
            if (a.sameAs(b)) continue
            fieldOps("item", id, b.fields, a.fields, out)
            if (a.place != b.place) out.add(draft("item.place", "item" to JStr(id), "place" to JStr(ctx.replicaId), "path" to (a.place?.let { JStr(it) } ?: app.keepfin.core.sync.JNull)))
            if (a.plays > b.plays) {
                out.add(draft("item.set", "item" to JStr(id), "field" to JStr(PLAYS_PREFIX + ctx.actor), "value" to JNum(ctx.ownPlays(id) + (a.plays - b.plays))))
            }
            if (a.favorite != b.favorite) {
                if (a.favorite) out.add(draft("set.add", "set" to JStr(FAVOURITES), "element" to JStr(id)))
                else {
                    val observedTags = ctx.observed.member(FAVOURITES, id)
                    if (observedTags.isNotEmpty()) out.add(draft("set.remove", "set" to JStr(FAVOURITES), "element" to JStr(id), "observedTags" to strs(observedTags)))
                }
            }
            setOps("tags:$id", b.userTags, a.userTags, ctx, out)
        }
        for (id in was.items.keys) {
            if (id in now.items) continue
            val seen = ctx.observed.item(id)
            out.add(draft("item.delete", "item" to JStr(id), "observedTags" to strs(seen.tags), "observedHeads" to strs(seen.heads)))
        }

        // collections
        for ((id, a) in now.collections) {
            val b = was.collections[id]
            if (b == null) {
                out.add(draft("playlist.add", "playlist" to JStr(id), "fields" to JObj(a.fields)))
                entryOps(id, emptyList(), a.itemIds, emptyList(), ctx, out)
                setOps("seen:$id", emptyList(), a.seen, ctx, out)
                setOps("new:$id", emptyList(), a.news, ctx, out)
                setOps("removed:$id", emptyList(), a.removed, ctx, out)
                continue
            }
            if (!a.sameMetaAs(b)) {
                fieldOps("playlist", id, b.fields, a.fields, out)
                setOps("seen:$id", b.seen, a.seen, ctx, out)
                setOps("new:$id", b.news, a.news, ctx, out)
                setOps("removed:$id", b.removed, a.removed, ctx, out)
            }
            if (a.itemIds != b.itemIds) entryOps(id, b.itemIds, a.itemIds, ctx.songs(id), ctx, out)
        }
        for (id in was.collections.keys) {
            if (id !in now.collections) out.add(draft("playlist.delete", "playlist" to JStr(id), "observedTags" to strs(ctx.observed.playlist(id))))
        }
        return out
    }

    /**
     * A diff whose deletes and removes observe what the store holds. [context] builds the context for a lookup; it is called
     * twice when the first pass needed any observed tags (the first pass only records which keys), so its id source should
     * not be consumed by the first result.
     */
    fun diffObserving(before: LibModel?, after: LibModel, context: (ObservedLookup) -> DiffContext, query: (RecordingObserved) -> ObservedLookup): List<OpDraft> {
        val recording = RecordingObserved()
        val first = diff(before, after, context(recording))
        if (recording.isEmpty) return first
        return diff(before, after, context(query(recording)))
    }

    /** `item.revise` for new content: [contentId] is `sha256:` + hex, [parents] the item's current head op ids. */
    fun reviseDraft(item: String, contentId: String, size: Long, parents: List<String>): OpDraft =
        draft("item.revise", "item" to JStr(item), "content" to JStr(contentId), "size" to JNum(size), "parents" to strs(parents))

    /** `item.place` for this replica; a null [path] clears the placement. */
    fun placeDraft(item: String, replicaId: String, path: String?): OpDraft =
        draft("item.place", "item" to JStr(item), "place" to JStr(replicaId), "path" to (path?.let { JStr(it) } ?: app.keepfin.core.sync.JNull))

    // ------------------------------------------------------------------------------------------------ projection

    private fun asRec(v: Jv?): Map<String, Jv>? = (v as? JObj)?.members
    private fun asArr(v: Jv?): List<Jv> = (v as? JArr)?.items ?: emptyList()

    /** Reads canonical views text (the store's `viewsJson`) into a tree; null when it is not valid. */
    fun parseViews(text: String): Jv? = (decodeCanonical(text) as? CanonicalResult.Ok)?.value

    private fun setOf(sets: Map<String, Jv>?, name: String): List<String> = asArr(sets?.get(name)).mapNotNull { decStr(it) }

    private class Env(val spaces: List<LibSpace>, val routes: LibRoutes, val modelId: (String) -> String)

    private fun projectEnv(views: Map<String, Jv>): Env {
        class Row(val id: String, val fields: Map<String, Jv>, val order: Long)
        val rows = ArrayList<Row>()
        for (row in asArr(views["spaces"])) {
            val m = asRec(row) ?: continue
            if (decBool(m["present"]) != true) continue
            val fields = asRec(m["fields"]) ?: emptyMap()
            val order = (fields["order"] as? JNum)?.value ?: MAX_SAFE.toLong()
            rows.add(Row(decStr(m["id"]) ?: continue, fields, order))
        }
        val sorted = rows.sortedWith(Comparator { a, b -> if (a.order != b.order) a.order.compareTo(b.order) else a.id.compareTo(b.id) })
        val modelIds = HashMap<String, String>()
        for (r in sorted) modelIds[r.id] = decStr(r.fields["legacyId"]) ?: builtinByWire[r.id] ?: r.id
        val modelId = { wire: String -> modelIds[wire] ?: builtinByWire[wire] ?: wire }
        val spaces = sorted.map { r ->
            val f = r.fields
            val smart = f["type"].let { it is JStr && it.value == "smart" }
            val kindText = decStr(f["kind"])
            val layoutText = decStr(f["layout"])
            LibSpace(
                id = modelId(r.id),
                name = decStr(f["name"]) ?: modelId(r.id),
                kind = if (kindText == "music" || kindText == "video" || kindText == "mixed") kindText else "mixed",
                folder = if (smart) "" else (decStr(f["folder"]) ?: ""),
                linked = !smart && decBool(f["linked"]) == true,
                layout = if (layoutText == "flat" || layoutText == "collection" || layoutText == "platform" || layoutText == "artist-album") layoutText else "flat",
                naming = decStr(f["naming"]) ?: "title",
                skipExisting = decBool(f["skipExisting"]) == true,
                ignore = (f["ignore"] as? JArr)?.takeIf { a -> a.items.all { it is JStr } }?.items?.map { (it as JStr).value },
                scanFolder = decBool(f["scanFolder"]),
                type = if (smart) "smart" else "folder",
                color = decStr(f["color"]),
                icon = decStr(f["icon"]),
                pinned = decBool(f["pinned"]),
                rules = f["rules"]?.takeIf { it is JObj || it is JArr },
            )
        }
        val routesField = asRec(asRec(views["profile"])?.get("routes"))
        val routes = LibRoutes(
            modelId(decStr(routesField?.get("music")) ?: BUILTIN_SPACE_IDS.getValue("music")),
            modelId(decStr(routesField?.get("other")) ?: BUILTIN_SPACE_IDS.getValue("downloads")),
        )
        return Env(spaces, routes, modelId)
    }

    private fun projectItem(view: Map<String, Jv>, id: String, placement: String?, favorite: Boolean, userTags: List<String>, env: Env): LibItem {
        val f = asRec(view["fields"]) ?: emptyMap()
        val kindText = decStr(f["media"])
        val place = placement?.takeIf { validPlacement(it) }
        return LibItem(
            id = id,
            space = env.modelId(decStr(f["space"]) ?: ""),
            kind = if (kindText != null && kindText in KINDS) kindText else "other",
            title = decStr(f["title"]) ?: "",
            artist = decStr(f["artist"]),
            album = decStr(f["album"]),
            albumArtist = decStr(f["albumArtist"]),
            track = decInt(f["track"]),
            disc = decInt(f["disc"]),
            year = decInt(f["year"]),
            genre = decStr(f["genre"]),
            duration = decScaled(f["duration"]),
            width = decInt(f["width"]),
            height = decInt(f["height"]),
            chapters = decChapters(f["chapters"]),
            gain = decScaled(f["gain"]),
            subtitles = decStrings(f["subtitles"]),
            hasLyrics = decBool(f["hasLyrics"]),
            sourceUrl = decStr(f["sourceUrl"]),
            sourceId = decStr(f["sourceId"]),
            addedAt = decInt(f["addedAt"]) ?: 0.0,
            tags = decStrings(f["tags"]),
            lastPlayedAt = decInt(f["lastPlayedAt"]),
            position = decScaled(f["position"]),
            watched = decBool(f["watched"]),
            favorite = favorite,
            userTags = userTags.sorted(),
            plays = (view["plays"] as? JNum)?.value?.takeIf { it > 0 }?.toDouble(),
            place = place,
        )
    }

    private fun projectCollection(p: Map<String, Jv>, id: String, seen: List<String>, news: List<String>, removed: List<String>, present: Set<String>, env: Env): LibCollection? {
        if (decBool(p["present"]) != true) return null
        val f = asRec(p["fields"]) ?: emptyMap()
        val kindField = decStr(f["kind"])
        val own = kindField == "playlist"
        if (!own && kindField != "collection") return null
        val kind = if (own) "own" else (decStr(f["collectionKind"])?.takeIf { it == "playlist" || it == "album" || it == "channel" } ?: "playlist")
        val itemIds = ArrayList<String>()
        val seenItems = HashSet<String>()
        for (song in asArr(p["songs"])) {
            val item = decStr(asRec(song)?.get("item")) ?: continue
            if (item !in present || !seenItems.add(item)) continue
            itemIds.add(item)
        }
        var source: LibSource? = null
        val sourceField = asRec(f["source"])
        if (sourceField != null && sourceField["url"] is JStr) {
            source = LibSource(decStr(sourceField["url"])!!, decStr(sourceField["provider"]) ?: "", decStr(sourceField["id"]))
        }
        var follow: LibFollow? = null
        val followField = asRec(f["follow"])
        if (!own && followField != null) {
            val mode = decStr(followField["mode"])
            val interval = (followField["intervalHours"] as? JNum)?.value
            val pending = asRec(followField["pending"])
            val count = decInt(pending?.get("count"))
            val at = decInt(pending?.get("at"))
            follow = LibFollow(
                mode = if (mode == "auto" || mode == "manual" || mode == "off") mode else "off",
                intervalHours = if (interval == 1L || interval == 6L || interval == 12L || interval == 24L || interval == 168L) interval.toDouble() else 24.0,
                wifiOnly = decBool(followField["wifiOnly"]) == true,
                notify = decBool(followField["notify"]) == true,
                confirmAbove = decInt(followField["confirmAbove"]) ?: 50.0,
                pendingCount = if (count != null && at != null) count else null,
                pendingAt = if (count != null && at != null) at else null,
            )
        }
        return LibCollection(
            id = id,
            name = decStr(f["name"]) ?: "",
            kind = kind,
            space = env.modelId(decStr(f["space"]) ?: ""),
            itemIds = itemIds,
            seen = seen.sorted(),
            createdAt = decInt(f["createdAt"]) ?: 0.0,
            updatedAt = decInt(f["updatedAt"]) ?: 0.0,
            coverItemId = decStr(f["coverItemId"]),
            source = source,
            follow = follow,
            newItemIds = news.filter { it in present }.sorted(),
            removedItemIds = removed.filter { it in present }.sorted(),
        )
    }

    /**
     * The model that a views document describes. [replicaId] picks the placement of this device. Items without a placement here
     * have `place == null` (the app shows them as missing). [views] is the shape of the store's `viewsJson`.
     */
    fun project(views: Jv, replicaId: String): LibModel {
        val v = asRec(views) ?: emptyMap()
        val env = projectEnv(v)
        val sets = asRec(v["sets"])
        val favourites = setOf(sets, FAVOURITES).toHashSet()
        val placements = asRec(v["placements"])
        val items = ArrayList<LibItem>()
        val present = HashSet<String>()
        for (row in asArr(v["items"])) {
            val m = asRec(row) ?: continue
            if (decStr(m["status"]) == "deleted") continue
            val id = decStr(m["id"]) ?: continue
            present.add(id)
            val placement = decStr(asRec(placements?.get(id))?.get(replicaId))
            items.add(projectItem(m, id, placement, id in favourites, setOf(sets, "tags:$id"), env))
        }
        val collections = ArrayList<LibCollection>()
        for (row in asArr(v["playlists"])) {
            val m = asRec(row) ?: continue
            val id = decStr(m["id"]) ?: continue
            val c = projectCollection(m, id, setOf(sets, "seen:$id"), setOf(sets, "new:$id"), setOf(sets, "removed:$id"), present, env)
            if (c != null) collections.add(c)
        }
        return LibModel(items, collections, env.spaces, env.routes)
    }
}
