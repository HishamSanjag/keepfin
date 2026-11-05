package app.keepfin.folder.sql

import app.keepfin.core.copies.COMPANION_FIELD
import app.keepfin.core.copies.DesiredEntry
import app.keepfin.core.copies.Holds
import app.keepfin.core.copies.Names
import app.keepfin.core.copies.companionCandidate
import app.keepfin.core.copies.companionKey
import app.keepfin.core.copies.keptCompanionIndices
import app.keepfin.core.sqlstate.SqlState
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** A present item whose revisions are in a content conflict (more than one head). */
class ConflictRow(val id: String, val title: String, val heads: List<HeadRow>)

class HeadRow(val op: String, val content: String, val size: Long)

/** What one replica holds of the library: items and bytes, and the actors that wrote its placements. */
class WhereHolding(val replica: String, val actors: Set<String>, val items: Long, val bytes: Long)

/** Items held by exactly one replica, of one media kind and space. */
class WhereSingleRow(val replica: String, val media: String, val space: String?, val count: Long)

/**
 * The reads copies need from the merge state's tables (dev-docs/plans/KEEPFIN-COPIES-SPEC.md sections 2 and 5.8): the desired set D of a place and the
 * facts around it. Plain SELECTs over the same tables and views the desktop reads (`gui/desktop/sqlstate/desired.ts`), through any read-only
 * connection. Items are read in keyset pages, so a library of 200,000 items is never held whole.
 */
class CopiesSql(private val query: QueryFn) {
    companion object {
        private const val PAGE = 1000

        fun heads(text: String): List<HeadRow> = try {
            val a = JSONArray(text)
            (0 until a.length()).map { i -> a.getJSONObject(i).let { HeadRow(it.getString("op"), it.getString("content"), it.optLong("size")) } }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    /** The live members of one named set. */
    fun setMembers(set: String): List<String> =
        query(app.keepfin.core.sqlstate.SqlViews.members("p.k1 = ?1"), listOf(set)).map { it[1] as String }

    /** The items on a present playlist (none for a playlist that is not present). */
    fun playlistItems(playlist: String): List<String> {
        val present = query(SqlState.queries.getValue("playlistPresent"), listOf(playlist)).firstOrNull()?.get(0) == 1L
        if (!present) return emptyList()
        return query(SqlState.queries.getValue("songs"), listOf(playlist)).map { it[1] as String }
    }

    /**
     * D for [holds], unsorted: every present item that the holds select (a space, a favourite, an item of a held playlist), each head of
     * its content at the item's placement under [mainReplica]; heads after the first sit at conflict names. Items without a placement for
     * [mainReplica] cannot be copied yet and are skipped. Smart-space holds are not wired (spec 4.8 rule 12). With [companions] each item's
     * lyrics, subtitles and info file follow, after every media entry (the core decides which and where: `companionCandidate`,
     * `keptCompanionIndices`). A music video (`Song.video.mp4`) follows with [musicVideos], its own option (the place's effective value: what the person
     * chose, else a copy that holds everything has them and a part copy does not; dev-docs/plans/MUSIC-VIDEOS.md, decision 5).
     */
    fun desiredEntries(mainReplica: String, holds: Holds, companions: Boolean = true, musicVideos: Boolean = holds.all): Sequence<DesiredEntry> = sequence {
        val favourites = if (holds.favourites) setMembers("favourites").toHashSet() else emptySet()
        val inPlaylists = HashSet<String>()
        for (pl in holds.playlists) inPlaylists.addAll(playlistItems(pl))
        // the fields only of an item that records a companion (most have none)
        val withCompanions = companions || musicVideos
        val companionSql = if (withCompanions) "CASE WHEN instr(v.fields, '\"companion:') > 0 THEN v.fields END" else "NULL"
        val mediaKeys = HashSet<String>()
        val candidates = ArrayList<DesiredEntry>()
        var after = ""
        while (true) {
            val rows = query(
                "SELECT v.id, json_extract(v.fields, '\$.space'), v.heads, " +
                    "(SELECT CASE WHEN json_type(p.value) = 'text' THEN json_extract(p.value, '\$') END FROM regs p WHERE p.scope = 'placement' AND p.k1 = v.id AND p.k2 = ?1), " +
                    "$companionSql FROM item_view v WHERE v.status = 'present' AND v.id > ?2 ORDER BY v.id LIMIT $PAGE",
                listOf(mainReplica, after),
            )
            if (rows.isEmpty()) break
            for (r in rows) {
                val id = r[0] as String
                val space = r[1] as? String
                val path = r[3] as? String
                val selected = holds.all || (space != null && space in holds.spaces) || id in favourites || id in inPlaylists
                if (!selected || path == null) continue
                val heads = heads(r[2] as String)
                for ((i, h) in heads.withIndex()) {
                    val p = if (i == 0) path else Names.conflictPath(path, h.content)
                    if (withCompanions) mediaKeys.add(companionKey(p))
                    yield(DesiredEntry(p, id, h.op, h.content, h.size))
                }
                (r[4] as? String)?.let { candidates.addAll(companionsIn(id, path, heads.size, it, musicVideos, companions)) }
            }
            after = rows.last()[0] as String
            if (rows.size < PAGE) break
        }
        if (candidates.isNotEmpty()) {
            for (i in keptCompanionIndices(candidates.map { it.path }, candidates.map { it.itemId }) { it in mediaKeys }) yield(candidates[i])
        }
    }

    /** The companion entries an item's fields (JSON text) record, read by the core's rules. */
    private fun companionsIn(id: String, path: String, heads: Int, fields: String, videos: Boolean, others: Boolean): List<DesiredEntry> = try {
        val o = JSONObject(fields)
        o.keys().asSequence().filter { it.startsWith(COMPANION_FIELD) }.mapNotNull { name ->
            val v = o.optJSONObject(name)
            val size = v?.opt("size")
            companionCandidate(id, path, heads, name, v?.optString("content", "").orEmpty(), if (size is Int || size is Long) (size as Number).toLong() else -1L, videos, others)
        }.toList()
    } catch (e: JSONException) {
        emptyList()
    }

    /** Item ids an `item.delete` removed (status deleted): the planner's `deleted` set. */
    fun deletedItems(): Set<String> {
        val out = HashSet<String>()
        var after = ""
        while (true) {
            val rows = query("SELECT id FROM item_view WHERE status = 'deleted' AND id > ?1 ORDER BY id LIMIT $PAGE", listOf(after))
            if (rows.isEmpty()) break
            for (r in rows) out.add(r[0] as String)
            after = rows.last()[0] as String
            if (rows.size < PAGE) break
        }
        return out
    }

    /** Present items whose revisions are in a content conflict, with the heads in the desired set's order. */
    fun conflictItems(limit: Int = 200): List<ConflictRow> =
        query("SELECT id, json_extract(fields, '\$.title'), heads FROM item_view WHERE status = 'present' AND conflict = 1 ORDER BY id LIMIT ?1", listOf(limit.toLong()))
            .map { ConflictRow(it[0] as String, (it[1] as? String).orEmpty(), heads(it[2] as String)) }

    /**
     * The present song (id, path) whose audio file is named [stem] with a known audio extension in [dir], or null: the song a music video file
     * (`Song.video.mp4`) found in a copy belongs to. A look at the index of this device by exact path, only when such a file is imported.
     */
    fun songBeside(dir: String, stem: String): Pair<String, String>? {
        val paths = JSONArray(listOf("mp3", "m4a", "flac", "ogg", "opus", "wav", "aac").map { if (dir.isEmpty()) "$stem.$it" else "$dir/$stem.$it" })
        val r = query("SELECT id, place FROM lib_index WHERE place IN (SELECT value FROM json_each(?1)) LIMIT 1", listOf(paths.toString())).firstOrNull() ?: return null
        return (r[0] as String) to (r[1] as String)
    }

    /** The title and media kind of an item, or empty strings. */
    fun itemMeta(id: String): Pair<String, String> {
        val r = query("SELECT json_extract(fields, '\$.media'), json_extract(fields, '\$.title') FROM item_view WHERE id = ?1", listOf(id)).firstOrNull()
        return ((r?.get(0) as? String).orEmpty()) to ((r?.get(1) as? String).orEmpty())
    }

    /** The head revisions of one item (op, content, size), in the desired set's order. */
    fun headsOf(id: String): List<HeadRow> = query("SELECT heads FROM item_view WHERE id = ?1", listOf(id)).firstOrNull()?.let { heads(it[0] as String) } ?: emptyList()

    /** An item's placement under [replica], or null. */
    fun placement(id: String, replica: String): String? =
        query("SELECT CASE WHEN json_type(value) = 'text' THEN json_extract(value, '\$') END FROM regs WHERE scope = 'placement' AND k1 = ?1 AND k2 = ?2", listOf(id, replica)).firstOrNull()?.get(0) as? String

    // ---- where your files are (dev-docs/plans/WHERE-FILES.md): who holds what, read from the placements --------------------------------

    /** Present items and the bytes of their first head: the whole library, to say "Everything". */
    fun presentTotals(): Pair<Long, Long> {
        val r = query("SELECT COUNT(*), COALESCE(SUM(CAST(json_extract(heads, '\$[0].size') AS INTEGER)), 0) FROM item_view WHERE status = 'present'", emptyList()).firstOrNull()
        return ((r?.get(0) as? Long) ?: 0L) to ((r?.get(1) as? Long) ?: 0L)
    }

    /** The bytes of the music videos recorded beside present songs (`companion:.video.*`), for the Storage line. Only items that record one are opened. */
    fun musicVideoBytes(): Long {
        val r = query(
            "SELECT COALESCE(SUM(CAST(json_extract(j.value, '\$.size') AS INTEGER)), 0) FROM item_view v, json_each(v.fields) j " +
                "WHERE v.status = 'present' AND instr(v.fields, '\"companion:.video.') > 0 AND substr(j.key, 1, 17) = 'companion:.video.' AND j.type = 'object'",
            emptyList(),
        ).firstOrNull()
        return (r?.get(0) as? Number)?.toLong() ?: 0L
    }

    /**
     * What each replica holds: present items with a placement under it, and their bytes. One pass over the placements (the primary key of
     * `regs` is (scope, k1, k2), so the scope is a range) joined to `item_view` by its key. [WhereHolding.actors] are the actors that wrote the
     * placements, which is how a paired device (known by its actor in this library) is told from the others.
     */
    fun holdings(): List<WhereHolding> {
        val byReplica = LinkedHashMap<String, WhereHolding>()
        val rows = query(
            "SELECT p.k2, p.oactor, COUNT(*), COALESCE(SUM(CAST(json_extract(v.heads, '\$[0].size') AS INTEGER)), 0) " +
                "FROM regs p JOIN item_view v ON v.id = p.k1 WHERE p.scope = 'placement' AND json_type(p.value) = 'text' AND v.status = 'present' GROUP BY p.k2, p.oactor",
            emptyList(),
        )
        for (r in rows) {
            val replica = r[0] as String
            val old = byReplica[replica]
            byReplica[replica] = WhereHolding(replica, (old?.actors ?: emptySet()) + (r[1] as String), (old?.items ?: 0L) + (r[2] as Long), (old?.bytes ?: 0L) + (r[3] as Long))
        }
        return byReplica.values.toList()
    }

    /**
     * Present items held by exactly one replica, counted by that replica, media kind and space. One query: each placement is looked at once
     * and the number of placements of its item is the prefix of the same key.
     */
    fun singles(): List<WhereSingleRow> =
        query(
            "SELECT p.k2, json_extract(v.fields, '\$.media'), json_extract(v.fields, '\$.space'), COUNT(*) " +
                "FROM regs p JOIN item_view v ON v.id = p.k1 " +
                "WHERE p.scope = 'placement' AND json_type(p.value) = 'text' AND v.status = 'present' " +
                "AND (SELECT COUNT(*) FROM regs q WHERE q.scope = 'placement' AND q.k1 = p.k1 AND json_type(q.value) = 'text') = 1 " +
                "GROUP BY p.k2, 2, 3",
            emptyList(),
        ).map { WhereSingleRow(it[0] as String, (it[1] as? String).orEmpty(), it[2] as? String, it[3] as Long) }

    /** In keyset pages, the present items whose only placement is under [replica] (id, media, space): to take out the ones a copy also keeps. */
    fun soleItems(replica: String, after: String, limit: Int): List<Triple<String, String, String?>> =
        query(
            "SELECT p.k1, json_extract(v.fields, '\$.media'), json_extract(v.fields, '\$.space') FROM regs p JOIN item_view v ON v.id = p.k1 " +
                "WHERE p.scope = 'placement' AND p.k1 > ?2 AND p.k2 = ?1 AND json_type(p.value) = 'text' AND v.status = 'present' " +
                "AND (SELECT COUNT(*) FROM regs q WHERE q.scope = 'placement' AND q.k1 = p.k1 AND json_type(q.value) = 'text') = 1 ORDER BY p.k1 LIMIT ?3",
            listOf(replica, after, limit.toLong()),
        ).map { Triple(it[0] as String, (it[1] as? String).orEmpty(), it[2] as? String) }

    /** The replicas that hold one item, each with the actor that wrote its placement. */
    fun placementsOf(id: String): List<Pair<String, String>> =
        query("SELECT k2, oactor FROM regs WHERE scope = 'placement' AND k1 = ?1 AND json_type(value) = 'text' ORDER BY k2", listOf(id)).map { (it[0] as String) to (it[1] as String) }

    /** An item's first head content id, or null. */
    fun firstContent(id: String): String? = headsOf(id).firstOrNull()?.content

    /** Each space's folder under the Keepfin folder by wire id ('' for a linked or unknown one): where an imported file belongs. */
    fun spaceFolders(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (r in query("SELECT k1, value FROM regs WHERE scope = 'space' AND k2 = 'folder'", emptyList())) {
            val folder = try { JSONArray("[" + (r[1] as String) + "]").optString(0, "") } catch (e: JSONException) { "" }
            out[r[0] as String] = folder
        }
        return out
    }

    /** The place registers by place id: field to its JSON text. */
    fun placeRegisters(): Map<String, Map<String, String>> {
        val out = LinkedHashMap<String, LinkedHashMap<String, String>>()
        for (r in query("SELECT k1, k2, value FROM regs WHERE scope = 'place' ORDER BY k1, k2", emptyList())) out.getOrPut(r[0] as String) { LinkedHashMap() }[r[1] as String] = r[2] as String
        return out
    }

    /** Present playlists (id, name) and present folder spaces (id, name), for the holds pickers and playlist exports. */
    fun containers(scope: String): List<Triple<String, String, List<String>>> {
        val out = ArrayList<Triple<String, String, List<String>>>()
        for (r in query(app.keepfin.core.sqlstate.SqlViews.CONTAINERS + "AND 1 = 1 ORDER BY p.k1", listOf(scope))) {
            if (r[1] != 1L) continue
            val id = r[0] as String
            val name = query(app.keepfin.core.sqlstate.SqlViews.CONTAINER_FIELDS, listOf(scope, id)).firstOrNull { it[0] == "name" }?.let { runCatching { JSONArray("[" + (it[1] as String) + "]").getString(0) }.getOrNull() } ?: id
            out.add(Triple(id, name, if (scope == "playlist") playlistItems(id) else emptyList()))
        }
        return out
    }}
