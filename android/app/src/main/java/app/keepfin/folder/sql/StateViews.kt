package app.keepfin.folder.sql

import app.keepfin.core.sqlstate.SqlState
import app.keepfin.core.sqlstate.SqlViews
import app.keepfin.core.sync.CanonicalResult
import app.keepfin.core.sync.JArr
import app.keepfin.core.sync.JBool
import app.keepfin.core.sync.JNum
import app.keepfin.core.sync.JObj
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.Jv
import app.keepfin.core.sync.decodeCanonical
import app.keepfin.core.sync.encodeCanonical

/** Runs a query on some connection (the writer's, or a read-only one) and returns its rows. */
typealias QueryFn = (sql: String, params: List<Any?>) -> List<List<Any?>>

/**
 * Reads the small views of the merge state (spaces, playlists with their songs, sets, profile, one item) from the tables, as
 * the same trees the views JSON of the core has (and `LibraryMapping.project` reads). The items are never read whole here:
 * a library of 200,000 items is read in pages (see [LibIndex]).
 */
class StateViews(private val query: QueryFn) {
    private fun parse(text: String): Jv = (decodeCanonical(text) as? CanonicalResult.Ok)?.value ?: throw IllegalStateException("A stored value is not canonical JSON")

    /** Playlists or spaces that exist in the state and have at least one add, with presence and fields, ordered by id. */
    fun containers(scope: String, ids: Collection<String>? = null): List<Triple<String, Boolean, Map<String, Jv>>> {
        val out = ArrayList<Triple<String, Boolean, Map<String, Jv>>>()
        fun fieldsOf(id: String): Map<String, Jv> {
            val fields = LinkedHashMap<String, Jv>()
            for (r in query(SqlViews.CONTAINER_FIELDS, listOf(scope, id))) fields[r[0] as String] = parse(r[1] as String)
            return fields
        }
        if (ids != null) {
            for (id in ids.sorted()) {
                val r = query(SqlViews.CONTAINERS + "AND p.k1 = ?2", listOf(scope, id)).firstOrNull() ?: continue
                out.add(Triple(r[0] as String, r[1] == 1L, fieldsOf(id)))
            }
            return out
        }
        for (r in query(SqlViews.CONTAINERS + "ORDER BY p.k1", listOf(scope))) out.add(Triple(r[0] as String, r[1] == 1L, fieldsOf(r[0] as String)))
        return out
    }

    /** A present playlist's songs ordered by (key, entry): the entry id, the item id and the key. */
    fun songs(playlist: String): List<Triple<String, String, String>> {
        val present = query(SqlState.queries.getValue("playlistPresent"), listOf(playlist)).firstOrNull()?.get(0) == 1L
        if (!present) return emptyList()
        return query(SqlState.queries.getValue("songs"), listOf(playlist)).map { Triple(it[0] as String, it[1] as String, it[2] as String) }
    }

    fun playlistJv(id: String, present: Boolean, fields: Map<String, Jv>): Jv = JObj(
        mapOf(
            "id" to JStr(id), "present" to JBool(present), "fields" to JObj(fields),
            "songs" to JArr(if (present) songs(id).map { JObj(mapOf("entry" to JStr(it.first), "item" to JStr(it.second), "key" to JStr(it.third))) } else emptyList()),
        ),
    )

    fun spaceJv(id: String, present: Boolean, fields: Map<String, Jv>): Jv = JObj(mapOf("id" to JStr(id), "present" to JBool(present), "fields" to JObj(fields)))

    /** The members of the named sets, sorted (an unknown or empty set is an empty list). */
    fun setsNamed(names: Collection<String>): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        for (name in names.sorted()) {
            val sql = SqlViews.members("p.k1 = ?1")
            out[name] = query(sql, listOf(name)).map { it[1] as String }.sorted()
        }
        return out
    }

    /** Every set whose name starts with [prefix] (a set with no members is absent). */
    fun setsByPrefix(prefix: String): Map<String, List<String>> {
        val upper = prefix.dropLast(1) + (prefix.last() + 1)
        val out = LinkedHashMap<String, MutableList<String>>()
        for (r in query(SqlViews.members("p.k1 >= ?1 AND p.k1 < ?2"), listOf(prefix, upper))) out.getOrPut(r[0] as String) { ArrayList() }.add(r[1] as String)
        for (l in out.values) l.sort()
        return out
    }

    fun allSets(): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (r in query(SqlViews.members("1 = 1"), emptyList())) out.getOrPut(r[0] as String) { ArrayList() }.add(r[1] as String)
        for (l in out.values) l.sort()
        return out
    }

    fun profile(): Map<String, Jv> {
        val out = LinkedHashMap<String, Jv>()
        for (r in query(SqlViews.PROFILE, emptyList())) out[r[0] as String] = parse(r[1] as String)
        return out
    }

    fun registers(scope: String): Map<String, Map<String, Jv>> {
        val out = LinkedHashMap<String, LinkedHashMap<String, Jv>>()
        for (r in query(SqlViews.REGISTERS_ALL, listOf(scope))) out.getOrPut(r[0] as String) { LinkedHashMap() }[r[1] as String] = parse(r[2] as String)
        return out
    }

    /** The placement of [item] under [replica], or null. */
    fun placement(item: String, replica: String): String? {
        val r = query("SELECT value FROM regs WHERE scope = 'placement' AND k1 = ?1 AND k2 = ?2", listOf(item, replica)).firstOrNull() ?: return null
        return (parse(r[0] as String) as? JStr)?.value
    }

    /** One item's view row as the views JSON has it, or null when the item is unknown. */
    fun itemJv(id: String): Jv? {
        val r = query(SqlViews.ITEM_VIEW_ONE, listOf(id)).firstOrNull() ?: return null
        return itemJv(r)
    }

    fun itemJv(r: List<Any?>): Jv = JObj(
        mapOf(
            "id" to JStr(r[0] as String), "status" to JStr(r[1] as String), "fields" to parse(r[2] as String), "heads" to parse(r[3] as String),
            "plays" to JNum(r[4] as Long), "contentConflict" to JBool(r[5] == 1L), "deleteConflictHeads" to parse(r[6] as String),
        ),
    )

    /** The views document without items: spaces, playlists, sets, placements of nothing, profile. [LibraryMapping.project] reads it. */
    fun smallViews(): Jv {
        val playlists = containers("playlist").map { playlistJv(it.first, it.second, it.third) }
        val spaces = containers("space").map { spaceJv(it.first, it.second, it.third) }
        return JObj(
            mapOf(
                "items" to JArr(emptyList()), "playlists" to JArr(playlists), "spaces" to JArr(spaces),
                "sets" to JObj(allSets().mapValues { (_, v) -> JArr(v.map { JStr(it) }) }), "placements" to JObj(emptyMap()),
                "places" to JObj(registers("place").mapValues { (_, v) -> JObj(v) }), "profile" to JObj(profile()), "findings" to JArr(emptyList()),
            ),
        )
    }

    /** The whole state as the canonical views JSON of the core (tests and diagnostics; reads every item). */
    fun fullViewsJson(findings: List<String>): String {
        val sb = StringBuilder()
        sb.append("{\"findings\":").append(encodeCanonical(JArr(findings.sorted().map { JStr(it) })))
        sb.append(",\"items\":[")
        var first = true
        var after = ""
        while (true) {
            val page = query(SqlViews.ITEM_VIEWS.replace("FROM item_view", "FROM item_view WHERE id > ?1 ORDER BY id LIMIT 2000"), listOf(after))
            if (page.isEmpty()) break
            for (r in page) {
                if (!first) sb.append(',')
                first = false
                sb.append(encodeCanonical(itemJv(r)))
            }
            after = page.last()[0] as String
        }
        sb.append("],\"placements\":").append(encodeCanonical(JObj(registers("placement").mapValues { (_, v) -> JObj(v) })))
        sb.append(",\"places\":").append(encodeCanonical(JObj(registers("place").mapValues { (_, v) -> JObj(v) })))
        sb.append(",\"playlists\":").append(encodeCanonical(JArr(containers("playlist").map { playlistJv(it.first, it.second, it.third) })))
        sb.append(",\"profile\":").append(encodeCanonical(JObj(profile())))
        sb.append(",\"sets\":").append(encodeCanonical(JObj(allSets().mapValues { (_, v) -> JArr(v.map { JStr(it) }) })))
        sb.append(",\"spaces\":").append(encodeCanonical(JArr(containers("space").map { spaceJv(it.first, it.second, it.third) })))
        sb.append('}')
        return sb.toString()
    }

    private fun idsJson(ids: Collection<String>): String {
        val sb = StringBuilder("[")
        var first = true
        for (id in ids) {
            if (!first) sb.append(',')
            first = false
            sb.append('"')
            for (c in id) when (c) { '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); else -> sb.append(c) }
            sb.append('"')
        }
        return sb.append(']').toString()
    }

    /**
     * The library records of [ids] (unknown and deleted items are absent) as the shared mapping projects them for the replica
     * [replicaId], and the play counter of [actorField] of each. [base] is the spaces-and-profile part of the views (see [smallViews]).
     */
    fun libItems(ids: Collection<String>, replicaId: String, base: Map<String, Jv>, actorField: String): Pair<List<app.keepfin.core.library.LibItem>, Map<String, Long>> {
        val items = ArrayList<app.keepfin.core.library.LibItem>()
        val plays = HashMap<String, Long>()
        for (chunk in ids.distinct().chunked(500)) {
            val json = idsJson(chunk)
            val rows = query("SELECT id, status, fields, heads, plays, conflict, dch FROM item_view WHERE id IN (SELECT value FROM json_each(?1))", listOf(json))
            if (rows.isEmpty()) continue
            val itemRows = ArrayList<Jv>(rows.size)
            for (r in rows) {
                val jv = itemJv(r)
                itemRows += jv
                ((jv as JObj).members["fields"] as? JObj)?.members?.get(actorField)?.let { if (it is JNum) plays[r[0] as String] = it.value }
            }
            val placements = LinkedHashMap<String, Jv>()
            for (r in query("SELECT k1, value FROM regs WHERE scope = 'placement' AND k2 = ?2 AND k1 IN (SELECT value FROM json_each(?1))", listOf(json, replicaId))) {
                placements[r[0] as String] = JObj(mapOf(replicaId to parse(r[1] as String)))
            }
            val favourites = query(SqlViews.members("p.k1 = 'favourites' AND p.k2 IN (SELECT value FROM json_each(?1))"), listOf(json)).map { JStr(it[1] as String) }
            val sets = LinkedHashMap<String, Jv>()
            if (favourites.isNotEmpty()) sets["favourites"] = JArr(favourites)
            val tags = LinkedHashMap<String, MutableList<Jv>>()
            for (r in query(SqlViews.members("p.k1 IN (SELECT 'tags:' || value FROM json_each(?1))"), listOf(json))) tags.getOrPut(r[0] as String) { ArrayList() }.add(JStr(r[1] as String))
            for ((k, v) in tags) sets[k] = JArr(v)
            val doc = JObj(base + mapOf("items" to JArr(itemRows), "sets" to JObj(sets), "placements" to JObj(placements)))
            items += app.keepfin.core.library.LibraryMapping.project(doc, replicaId).items
        }
        return items to plays
    }

    /** The part of the views the projection of items and collections needs besides them: spaces and the profile. */
    fun baseViews(): Map<String, Jv> = mapOf(
        "spaces" to JArr(containers("space").map { spaceJv(it.first, it.second, it.third) }),
        "profile" to JObj(profile()),
    )

    /**
     * The collections ([ids], or every one) as the mapping projects them: the songs of a playlist are its entries whose item is
     * present, and the new and removed lists keep present items only. Items are not read, only checked for presence.
     */
    fun collections(ids: Collection<String>?, replicaId: String, base: Map<String, Jv>): List<app.keepfin.core.library.LibCollection> {
        if (ids == null) {
            // All of them at once would hold a JSON document for every song of every playlist (200,000 entries: over 100 MB while it is built):
            // many playlists are read a hundred at a time, in the same order, and only the finished collections are kept.
            val all = containers("playlist").map { it.first }
            if (all.size > 200) return all.chunked(100).flatMap { collections(it, replicaId, base) }
        }
        val lists = containers("playlist", ids)
        if (lists.isEmpty()) return emptyList()
        val playlists = lists.map { playlistJv(it.first, it.second, it.third) }
        val sets = LinkedHashMap<String, Jv>()
        val wanted = LinkedHashSet<String>()
        if (ids == null) {
            for (prefix in listOf("seen:", "new:", "removed:")) for ((k, v) in setsByPrefix(prefix)) { sets[k] = JArr(v.map { JStr(it) }); if (prefix != "seen:") wanted += v }
        } else {
            val names = lists.flatMap { listOf("seen:${it.first}", "new:${it.first}", "removed:${it.first}") }
            for ((k, v) in setsNamed(names)) { sets[k] = JArr(v.map { JStr(it) }); if (!k.startsWith("seen:")) wanted += v }
        }
        for (p in lists) for (s in songs(p.first)) wanted += s.second
        val present = ArrayList<Jv>()
        for (chunk in wanted.chunked(500)) {
            for (r in query("SELECT id FROM item_view WHERE status IN ('present', 'deleteConflict') AND id IN (SELECT value FROM json_each(?1))", listOf(idsJson(chunk)))) {
                present += JObj(mapOf("id" to JStr(r[0] as String), "status" to JStr("present"), "fields" to JObj(emptyMap())))
            }
        }
        val doc = JObj(base + mapOf("items" to JArr(present), "playlists" to JArr(playlists), "sets" to JObj(sets), "placements" to JObj(emptyMap())))
        return app.keepfin.core.library.LibraryMapping.project(doc, replicaId).collections
    }

    /** The spaces and routes of the profile, as the mapping projects them. */
    fun spacesAndRoutes(replicaId: String, base: Map<String, Jv>): Pair<List<app.keepfin.core.library.LibSpace>, app.keepfin.core.library.LibRoutes> {
        val doc = JObj(base + mapOf("items" to JArr(emptyList()), "playlists" to JArr(emptyList()), "sets" to JObj(emptyMap()), "placements" to JObj(emptyMap())))
        val model = app.keepfin.core.library.LibraryMapping.project(doc, replicaId)
        return model.spaces to model.routes
    }
}
