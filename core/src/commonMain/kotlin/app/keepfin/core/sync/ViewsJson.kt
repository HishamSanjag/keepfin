package app.keepfin.core.sync

// The JSON the desktop reads (see Facade.viewsJson). [viewsJv] is the reference construction as a tree;
// [viewsJsonText] writes exactly the same canonical text in one pass, with each item's heads worked out once.
// A test compares the two on many states.

private fun strings(items: Collection<String>) = JArr(items.map { JStr(it) })

internal fun State.viewsJv(findings: Set<String>): Jv {
    val st = this
    val items = ArrayList<Jv>()
    for (id in st.items.keys.sorted()) {
        val status = st.itemStatus(id)
        if (status == ItemStatus.Unknown) continue
        val item = st.items.getValue(id)
        val heads = st.itemHeads(id).map { head ->
            val e = item.revisions.getValue(head)
            jObj("op" to JStr(head.toString()), "content" to JStr(e.content), "size" to JNum(e.size))
        }
        items.add(
            jObj(
                "id" to JStr(id),
                "status" to JStr(
                    when (status) {
                        ItemStatus.Present -> "present"
                        ItemStatus.Deleted -> "deleted"
                        else -> "deleteConflict"
                    },
                ),
                "fields" to JObj(item.fields.mapValues { it.value.value }),
                "heads" to JArr(heads),
                "contentConflict" to JBool(heads.size > 1),
                "deleteConflictHeads" to strings(st.deleteConflictHeads(id).map { it.toString() }),
                "plays" to JNum(st.plays(id)),
            ),
        )
    }
    val playlists = ArrayList<Jv>()
    for ((id, p) in st.playlists.entries.sortedBy { it.key }) {
        if (p.exists.adds.isEmpty()) continue
        playlists.add(
            jObj(
                "id" to JStr(id),
                "present" to JBool(p.exists.present),
                "fields" to JObj(p.fields.mapValues { it.value.value }),
                "songs" to JArr(st.playlistSongs(id).map { jObj("entry" to JStr(it.entry), "item" to JStr(it.item), "key" to JStr(it.key)) }),
            ),
        )
    }
    val spaces = ArrayList<Jv>()
    for ((id, s) in st.spaces.entries.sortedBy { it.key }) {
        if (s.exists.adds.isEmpty()) continue
        spaces.add(jObj("id" to JStr(id), "present" to JBool(s.exists.present), "fields" to JObj(s.fields.mapValues { it.value.value })))
    }
    val sets = LinkedHashMap<String, Jv>()
    for (name in st.sets.keys) {
        val members = st.setMembers(name)
        if (members.isNotEmpty()) sets[name] = strings(members)
    }
    return jObj(
        "items" to JArr(items),
        "playlists" to JArr(playlists),
        "spaces" to JArr(spaces),
        "sets" to JObj(sets),
        "placements" to JObj(st.placements.mapValues { (_, byPlace) -> JObj(byPlace.mapValues { it.value.value }) }),
        "places" to JObj(st.places.mapValues { (_, fields) -> JObj(fields.mapValues { it.value.value }) }),
        "profile" to JObj(st.profile.mapValues { it.value.value }),
        "findings" to strings(findings.sorted()),
    )
}

private fun StringBuilder.q(s: String) = appendQuoted(this, s)

private fun StringBuilder.fieldMap(m: Map<String, Register>) {
    append('{')
    var first = true
    for (e in sortedEntries(m)) {
        if (!first) append(',')
        first = false
        q(e.key)
        append(':')
        writeCanonical(this, e.value.value)
    }
    append('}')
}

private fun StringBuilder.idList(ids: Collection<OpId>) {
    append('[')
    var first = true
    for (id in ids) {
        if (!first) append(',')
        first = false
        q(id.toString())
    }
    append(']')
}

private fun StringBuilder.stringList(items: Collection<String>) {
    append('[')
    var first = true
    for (x in items) {
        if (!first) append(',')
        first = false
        q(x)
    }
    append(']')
}

/** Appends one item's view object (from the shared projection); false (nothing appended) when the item is unknown. */
private fun StringBuilder.item(id: String, item: ItemState): Boolean {
    val p = projectItem(item)
    val status = p.status ?: return false
    append("{\"contentConflict\":").append(if (p.contentConflict) "true" else "false")
    append(",\"deleteConflictHeads\":").append(p.deleteConflictHeads)
    append(",\"fields\":").append(p.fields)
    append(",\"heads\":").append(p.heads)
    append(",\"id\":")
    q(id)
    append(",\"plays\":").append(p.plays.toString())
    append(",\"status\":\"").append(status).append("\"}")
    return true
}

/** A present playlist's songs from the ids of its entries: live entries whose item is present or in a delete conflict. */
internal fun State.songsOf(playlist: String, entryIds: Collection<String>): List<PlaylistSong> {
    val out = ArrayList<PlaylistSong>()
    for (entry in entryIds) {
        if (entry in removedEntries) continue
        val facts = entries[entry] ?: continue
        if (facts.playlist != playlist) continue
        val status = itemStatus(facts.item)
        if (status != ItemStatus.Present && status != ItemStatus.DeleteConflict) continue
        out.add(PlaylistSong(entry, facts.item, (entryKey[entry]?.value as? JStr)?.value ?: ""))
    }
    if (out.size > 1) {
        out.sortWith { a, b ->
            val c = a.key.compareTo(b.key)
            if (c != 0) c else a.entry.compareTo(b.entry)
        }
    }
    return out
}

private fun State.entriesByPlaylist(): Map<String, Collection<String>> {
    val out = HashMap<String, ArrayList<String>>()
    for ((e, f) in entries) out.getOrPut(f.playlist) { ArrayList() }.add(e)
    return out
}

private fun StringBuilder.playlist(st: State, id: String, p: EntityState, entryIds: Collection<String>?): Boolean {
    if (p.exists.adds.isEmpty()) return false
    append("{\"fields\":")
    fieldMap(p.fields)
    append(",\"id\":")
    q(id)
    append(",\"present\":").append(if (p.exists.present) "true" else "false")
    append(",\"songs\":[")
    if (p.exists.present && entryIds != null) {
        var firstSong = true
        for (song in st.songsOf(id, entryIds)) {
            if (!firstSong) append(',')
            firstSong = false
            append("{\"entry\":")
            q(song.entry)
            append(",\"item\":")
            q(song.item)
            append(",\"key\":")
            q(song.key)
            append('}')
        }
    }
    append("]}")
    return true
}

private fun StringBuilder.space(id: String, s: EntityState): Boolean {
    if (s.exists.adds.isEmpty()) return false
    append("{\"fields\":")
    fieldMap(s.fields)
    append(",\"id\":")
    q(id)
    append(",\"present\":").append(if (s.exists.present) "true" else "false").append('}')
    return true
}

/** The canonical text of `viewsJv(findings)`. */
internal fun State.viewsJsonText(findings: Set<String>): String {
    val st = this
    val byPlaylist = st.entriesByPlaylist()
    val sb = StringBuilder(256 + st.items.size * 240)
    sb.append("{\"findings\":")
    sb.stringList(if (findings.size > 1) findings.sorted() else findings)
    sb.append(",\"items\":[")
    var first = true
    for (id in st.items.keys.sorted()) {
        val mark = sb.length
        if (!first) sb.append(',')
        if (sb.item(id, st.items.getValue(id))) first = false else sb.setLength(mark)
    }
    sb.append("],\"placements\":{")
    first = true
    for (e in sortedEntries(st.placements)) {
        if (!first) sb.append(',')
        first = false
        sb.q(e.key)
        sb.append(':')
        sb.fieldMap(e.value)
    }
    sb.append("},\"places\":{")
    first = true
    for (e in sortedEntries(st.places)) {
        if (!first) sb.append(',')
        first = false
        sb.q(e.key)
        sb.append(':')
        sb.fieldMap(e.value)
    }
    sb.append("},\"playlists\":[")
    first = true
    for (e in sortedEntries(st.playlists)) {
        val mark = sb.length
        if (!first) sb.append(',')
        if (sb.playlist(st, e.key, e.value, byPlaylist[e.key] ?: emptyList())) first = false else sb.setLength(mark)
    }
    sb.append("],\"profile\":")
    sb.fieldMap(st.profile)
    sb.append(",\"sets\":{")
    first = true
    for (e in sortedEntries(st.sets)) {
        val members = st.setMembers(e.key)
        if (members.isEmpty()) continue
        if (!first) sb.append(',')
        first = false
        sb.q(e.key)
        sb.append(':')
        sb.stringList(members)
    }
    sb.append("},\"spaces\":[")
    first = true
    for (e in sortedEntries(st.spaces)) {
        val mark = sb.length
        if (!first) sb.append(',')
        if (sb.space(e.key, e.value)) first = false else sb.setLength(mark)
    }
    sb.append("]}")
    return sb.toString()
}

/**
 * The views of only the [touched] keys, as JSON (`SyncPartialViews`): the items now unknown are listed in `goneItems`,
 * a playlist carries its full song list, a set its full membership (an empty list when it is now empty), the
 * placements of the listed items, and `profile` and `findings` only when they changed. For `touched.all` it is the
 * full views plus `"goneItems":[]`. Cost: the touched keys and their songs, never the library.
 *
 * [entriesOf] gives a playlist's entry ids; [findings] is read only when the findings changed.
 */
internal fun State.partialViewsText(touched: Touched, entriesOf: (String) -> Collection<String>?, findings: () -> Set<String>): String {
    val st = this
    if (touched.all) {
        val full = viewsJsonText(findings())
        return full.substring(0, full.length - 1) + ",\"goneItems\":[]}"
    }
    val sb = StringBuilder(1024)
    sb.append('{')
    var sep = false
    if (touched.findings) {
        sb.append("\"findings\":")
        val f = findings()
        sb.stringList(if (f.size > 1) f.sorted() else f)
        sep = true
    }
    if (sep) sb.append(',')
    sb.append("\"goneItems\":[")
    val ids = touched.items.sorted()
    var firstGone = true
    for (id in ids) {
        val item = st.items[id]
        if (item == null || item.exists.adds.isEmpty()) {
            if (!firstGone) sb.append(',')
            firstGone = false
            sb.q(id)
        }
    }
    sb.append("],\"items\":[")
    var first = true
    for (id in ids) {
        val item = st.items[id] ?: continue
        val mark = sb.length
        if (!first) sb.append(',')
        if (sb.item(id, item)) first = false else sb.setLength(mark)
    }
    sb.append("],\"placements\":{")
    first = true
    for (id in ids) {
        val m = st.placements[id] ?: continue
        if (!first) sb.append(',')
        first = false
        sb.q(id)
        sb.append(':')
        sb.fieldMap(m)
    }
    sb.append("},\"places\":{")
    first = true
    for (id in touched.places.sorted()) {
        val m = st.places[id] ?: continue
        if (!first) sb.append(',')
        first = false
        sb.q(id)
        sb.append(':')
        sb.fieldMap(m)
    }
    sb.append("},\"playlists\":[")
    first = true
    for (id in touched.playlists.sorted()) {
        val p = st.playlists[id] ?: continue
        val mark = sb.length
        if (!first) sb.append(',')
        if (sb.playlist(st, id, p, entriesOf(id))) first = false else sb.setLength(mark)
    }
    sb.append(']')
    if (touched.profile) {
        sb.append(",\"profile\":")
        sb.fieldMap(st.profile)
    }
    sb.append(",\"sets\":{")
    first = true
    for (name in touched.sets.sorted()) {
        if (!first) sb.append(',')
        first = false
        sb.q(name)
        sb.append(':')
        sb.stringList(st.setMembers(name))
    }
    sb.append("},\"spaces\":[")
    first = true
    for (id in touched.spaces.sorted()) {
        val sp = st.spaces[id] ?: continue
        val mark = sb.length
        if (!first) sb.append(',')
        if (sb.space(id, sp)) first = false else sb.setLength(mark)
    }
    sb.append("]}")
    return sb.toString()
}
