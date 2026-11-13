package app.keepfin.folder.sql

import app.keepfin.AlbumGroup
import app.keepfin.ArtistGroup
import app.keepfin.Counts
import app.keepfin.Facet
import app.keepfin.SmartField
import app.keepfin.FolderGroup
import app.keepfin.ItemOrder
import app.keepfin.ItemQuery
import app.keepfin.LibraryBrowse
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.PageDirection
import app.keepfin.PageKey
import app.keepfin.PageResult
import app.keepfin.ScopedTag
import app.keepfin.SmartContext
import app.keepfin.SmartLogic
import app.keepfin.core.sqlstate.SqlDriver
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the browse needs to know about the app around the index: spaces, addresses of files, and which job saved an item. */
interface BrowseEnv {
    /** The app's id of the space the index calls [wire]. */
    fun modelSpace(wire: String): String

    /** The folder of a space inside the Keepfin folder (the placement prefix), "" for none. */
    fun spaceFolder(modelId: String): String

    /** The address the player opens for a placement of the Keepfin folder. */
    fun uriFor(place: String): String

    /** Where a linked file lives on this phone, or null when it has not been found (then the item is not shown). */
    fun linked(id: String, place: String): LinkedView?

    fun jobIdFor(id: String): String?

    /** The item a finished download became. */
    fun itemForJob(jobId: String): String?

    /** The app's ids of the music spaces: a video kept in one of them is a song. */
    fun musicSpaces(): List<String> = emptyList()
}

class LinkedView(val uri: String, val folder: String, val size: Long, val modifiedAt: Long)

/** Read-only connections to `state.db`; reopened when the file was replaced. */
class IndexReader(private val path: String, private val epoch: () -> Long, private val open: (String) -> SqlDriver) {
    private val idle = ConcurrentLinkedQueue<SqlDriver>()
    @Volatile private var seen = epoch()

    private fun take(): SqlDriver {
        val now = epoch()
        if (now != seen) {
            synchronized(this) {
                if (now != seen) {
                    seen = now
                    while (true) (idle.poll() ?: break).let { runCatching { it.close() } }
                }
            }
        }
        return idle.poll() ?: open(path)
    }

    fun <T> read(block: (SqlDriver) -> T): T {
        val c = take()
        var ok = false
        try {
            val out = block(c)
            ok = true
            return out
        } finally {
            if (ok && epoch() == seen && idle.size < 4) idle.add(c) else runCatching { c.close() }
        }
    }

    fun rows(sql: String, params: List<Any?> = emptyList()): List<List<Any?>> = read { c ->
        val s = c.prepare(sql)
        try { s.rows(params) } finally { s.close() }
    }

    fun close() {
        while (true) (idle.poll() ?: break).let { runCatching { it.close() } }
    }
}

private const val COLS =
    "i.id, i.space, i.media, i.title, i.tkey, i.artist, i.akey, i.rkey, i.album, i.bkey, i.albumArtist, i.albumKey, i.track, i.disc, i.dsort, i.tsort, i.year, i.genre, " +
        "i.duration, i.durs, i.width, i.height, i.gain, i.added, i.played, i.plays, i.position, i.watched, i.fav, i.hasLyrics, i.srcUrl, i.srcId, i.tags, i.utags, i.place, " +
        "i.dir, i.size, i.content, i.musicVideo"

private const val C_ID = 0; private const val C_SPACE = 1; private const val C_MEDIA = 2; private const val C_TITLE = 3; private const val C_TKEY = 4
private const val C_ARTIST = 5; private const val C_AKEY = 6; private const val C_ALBUM = 8; private const val C_BKEY = 9; private const val C_ALBUMARTIST = 10
private const val C_TRACK = 12; private const val C_DISC = 13; private const val C_DSORT = 14; private const val C_TSORT = 15; private const val C_YEAR = 16
private const val C_GENRE = 17; private const val C_DURATION = 18; private const val C_DURS = 19; private const val C_WIDTH = 20; private const val C_HEIGHT = 21
private const val C_GAIN = 22; private const val C_ADDED = 23; private const val C_PLAYED = 24; private const val C_PLAYS = 25; private const val C_POSITION = 26
private const val C_WATCHED = 27; private const val C_FAV = 28; private const val C_LYRICS = 29; private const val C_SRCURL = 30; private const val C_SRCID = 31
private const val C_TAGS = 32; private const val C_UTAGS = 33; private const val C_PLACE = 34; private const val C_DIR = 35; private const val C_SIZE = 36; private const val C_CONTENT = 37; private const val C_MUSICVIDEO = 38

/** The columns an order sorts by, ending with the id; every column is NOT NULL. */
private fun orderColumns(order: ItemOrder): List<String> = when (order) {
    ItemOrder.ADDED -> listOf("added", "id")
    ItemOrder.TITLE -> listOf("tkey", "id")
    ItemOrder.ARTIST -> listOf("akey", "bkey", "dsort", "tsort", "tkey", "id")
    ItemOrder.DURATION -> listOf("durs", "id")
    ItemOrder.SIZE -> listOf("size", "id")
    ItemOrder.MOST_PLAYED -> listOf("plays", "played", "id")
    ItemOrder.RECENT, ItemOrder.STALE -> listOf("played", "id")
    ItemOrder.TRACK -> listOf("dsort", "tsort", "tkey", "id")
}

private val columnIndex = mapOf(
    "id" to C_ID, "tkey" to C_TKEY, "akey" to C_AKEY, "bkey" to C_BKEY, "dsort" to C_DSORT, "tsort" to C_TSORT, "added" to C_ADDED,
    "durs" to C_DURS, "size" to C_SIZE, "plays" to C_PLAYS, "played" to C_PLAYED,
)

private fun Any?.long(): Long = (this as? Number)?.toLong() ?: 0L
private fun Any?.int(): Int? = (this as? Number)?.toInt()
private fun Any?.dbl(): Double? = (this as? Number)?.toDouble()
private fun Any?.str(): String? = this as? String
private fun splitTags(text: Any?): List<String> = (text as? String)?.takeIf { it.isNotEmpty() }?.split('\n') ?: emptyList()

/** [LibraryBrowse] over the SQLite index: every read is a keyset query on an index, never the whole list. */
class IndexBrowse(private val reader: IndexReader, private val env: BrowseEnv, private val wire: (String) -> String) : LibraryBrowse {
    private val flow = MutableStateFlow(0L)
    override val changes: StateFlow<Long> get() = flow

    /** Called by the repository whenever the library changed. */
    fun bump() {
        flow.value = flow.value + 1
    }

    // ------------------------------------------------------------------------------------------------ rows to items

    private fun toItem(r: List<Any?>): LibraryItem? {
        val place = r[C_PLACE].str()
        val id = r[C_ID] as String
        val space = env.modelSpace(r[C_SPACE] as String)
        val uri: String
        val folder: String
        var size = r[C_SIZE].long()
        var modifiedAt = 0L
        if (place == null) {
            // not on this phone: a paired device that holds the content can play it (net PeerDataSource)
            uri = ""; folder = ""
        } else if (place.startsWith("device:")) {
            // music already on the phone: the address is the content uri, the folder is the one MediaStore reports
            uri = env.uriFor(place); folder = r[C_DIR].str().orEmpty()
        } else if (place.startsWith("linked:")) {
            val v = env.linked(id, place) ?: return null
            uri = v.uri; folder = v.folder; size = v.size; modifiedAt = v.modifiedAt
        } else {
            uri = env.uriFor(place)
            val root = env.spaceFolder(space)
            val rest = if (root.isEmpty()) place else if (place.startsWith("$root/")) place.substring(root.length + 1) else null
            folder = rest?.substringBeforeLast('/', "") ?: ""
        }
        val played = r[C_PLAYED].long()
        return LibraryItem(
            id = id, space = space, uri = uri, folder = folder,
            kind = LibraryItemKind.entries.firstOrNull { it.wire == r[C_MEDIA] } ?: LibraryItemKind.OTHER,
            title = r[C_TITLE] as String, artist = r[C_ARTIST].str(), album = r[C_ALBUM].str(), albumArtist = r[C_ALBUMARTIST].str(),
            track = r[C_TRACK].int(), year = r[C_YEAR].int(), duration = r[C_DURATION].dbl(), size = size, modifiedAt = modifiedAt,
            addedAt = r[C_ADDED].long(), sourceUrl = r[C_SRCURL].str(), sourceId = r[C_SRCID].str(), jobId = env.jobIdFor(id),
            width = r[C_WIDTH].int(), height = r[C_HEIGHT].int(), position = r[C_POSITION].dbl(), plays = r[C_PLAYS].long().toInt(),
            lastPlayedAt = played.takeIf { it > 0 }, favorite = r[C_FAV].long() == 1L, watched = r[C_WATCHED].long() == 1L,
            hasLyrics = r[C_LYRICS].long() == 1L, gain = r[C_GAIN].dbl(), disc = r[C_DISC].int(), genre = r[C_GENRE].str(),
            tags = splitTags(r[C_TAGS]), userTags = splitTags(r[C_UTAGS]),
            remote = place == null, contentId = r[C_CONTENT].str()?.takeIf { it.isNotEmpty() }, musicVideo = r[C_MUSICVIDEO].long() == 1L,
        )
    }

    private fun keyOf(r: List<Any?>, order: ItemOrder): PageKey = orderColumns(order).map { r[columnIndex.getValue(it)] ?: "" }

    // ------------------------------------------------------------------------------------------------ filters

    /** [exact] is false when [sql] only narrows (a smart rule that SQL cannot decide): the rows are then checked against the rules. */
    private class Filter(val sql: String, val params: List<Any?>, val exact: Boolean = true)

    private fun filter(q: ItemQuery): Filter {
        val where = ArrayList<String>()
        val params = ArrayList<Any?>()
        // items whose file is not on this phone are listed too (placement null): a paired device can play them
        where += "1 = 1"
        if (q.spaces.isNotEmpty()) {
            where += "i.space IN (${q.spaces.joinToString(",") { "?" }})"
            for (s in q.spaces) params += wire(s)
        }
        if (q.kinds.isNotEmpty()) {
            where += "i.media IN (${q.kinds.joinToString(",") { "?" }})"
            for (k in q.kinds) params += k.wire
        }
        if (q.songs) {
            // Songs.isSong: audio anywhere, or a video in a music space
            val music = env.musicSpaces()
            where += if (music.isEmpty()) "i.media = 'audio'" else "(i.media = 'audio' OR (i.media = 'video' AND i.space IN (${music.joinToString(",") { "?" }})))"
            for (s in music) params += wire(s)
        }
        if (q.excludeSpaces.isNotEmpty()) {
            where += "i.space NOT IN (${q.excludeSpaces.joinToString(",") { "?" }})"
            for (s in q.excludeSpaces) params += wire(s)
        }
        if (q.tagsAny.isNotEmpty()) {
            where += "i.id IN (SELECT t.id FROM lib_tags t WHERE t.tag IN (SELECT value FROM json_each(?)))"
            params += jsonArray(q.tagsAny)
        }
        if (q.tagsAll.isNotEmpty()) {
            where += "i.id IN (SELECT t.id FROM lib_tags t WHERE t.tag IN (SELECT value FROM json_each(?1)) GROUP BY t.id HAVING COUNT(*) = ?2)"
            params += jsonArray(q.tagsAll.distinct())
            params += q.tagsAll.distinct().size.toLong()
        }
        if (q.favorite) where += "i.fav = 1"
        if (q.musicVideos) where += "i.musicVideo = 1"
        if (q.unplayed) where += "i.plays = 0"
        if (q.played) where += "i.plays > 0"
        if (q.minPlays > 0) { where += "i.plays >= ?"; params += q.minPlays.toLong() }
        if (q.playedSince != null) { where += "i.played >= ?"; params += q.playedSince }
        if (q.playedBefore != null) { where += "i.played > 0 AND i.played < ?"; params += q.playedBefore }
        if (q.everPlayed) where += "i.played > 0"
        if (q.resumable) where += "i.position > 5 AND i.watched = 0 AND i.media IN ('audio', 'video')"
        if (q.folder != null) {
            val space = q.spaces.singleOrNull() ?: throw IllegalArgumentException("A folder needs one space")
            val root = env.spaceFolder(space)
            where += "i.space = ? AND i.dir = ?"
            params += wire(space)
            params += if (root.isEmpty()) q.folder else if (q.folder.isEmpty()) root else "$root/${q.folder}"
        }
        if (q.subtree != null) {
            val space = q.spaces.singleOrNull() ?: throw IllegalArgumentException("A folder needs one space")
            val root = env.spaceFolder(space)
            val base = if (root.isEmpty()) q.subtree else if (q.subtree.isEmpty()) root else "$root/${q.subtree}"
            if (base.isNotEmpty()) {
                where += "(i.dir = ? OR i.dir LIKE ? ESCAPE '\\')"
                params += base
                params += base.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "/%"
            }
        }
        if (q.artist != null) {
            where += "i.akey = ?"
            params += q.artist
        }
        if (q.album != null) {
            where += "i.albumKey = ?"
            params += q.album
        }
        val match = matchOf(q.text, q.textColumns)
        if (match != null) {
            where += "i.n IN (SELECT rowid FROM lib_fts WHERE lib_fts MATCH ?)"
            params += match
        }
        if (q.ids != null) {
            where += "i.id IN (SELECT value FROM json_each(?))"
            params += jsonArray(q.ids)
        }
        var exact = true
        val smart = q.smart
        if (smart != null) {
            val compiled = SmartSql.compile(smart, wire)
            where += compiled.sql
            params.addAll(compiled.params)
            exact = compiled.exact
        }
        return Filter(where.joinToString(" AND "), params, exact)
    }

    private fun jsonArray(ids: Collection<String>): String {
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

    private fun matchOf(text: String, columns: String? = null): String? {
        val terms = app.keepfin.LibraryViewsKeys.fold(text.trim()).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return null
        val body = terms.joinToString(" ") { "\"" + it.replace("\"", "\"\"") + "\"*" }
        return if (columns == null) body else "{$columns} : ($body)"
    }

    // ------------------------------------------------------------------------------------------------ pages

    private fun rawPage(q: ItemQuery, direction: PageDirection, key: PageKey?, limit: Int): Triple<List<List<Any?>>, Boolean, Boolean> {
        val f = filter(q)
        val cols = orderColumns(q.order)
        val desc = q.order.descending
        val back = direction == PageDirection.BEFORE
        val rowValue = cols.joinToString(",", "(", ")") { "i.$it" }
        val sb = StringBuilder("SELECT $COLS FROM lib_index i WHERE ${f.sql}")
        val params = ArrayList<Any?>(f.params)
        if (key != null && direction != PageDirection.FIRST) {
            val op = when (direction) {
                PageDirection.AFTER -> if (desc) "<" else ">"
                PageDirection.BEFORE -> if (desc) ">" else "<"
                else -> if (desc) "<=" else ">="
            }
            sb.append(" AND $rowValue $op ${cols.joinToString(",", "(", ")") { "?" }}")
            params.addAll(key.take(cols.size))
        }
        val dir = if (desc != back) "DESC" else "ASC"
        sb.append(" ORDER BY ").append(cols.joinToString(", ") { "i.$it $dir" }).append(" LIMIT ?")
        params += limit + 1
        var rows = reader.rows(sb.toString(), params)
        val more = rows.size > limit
        if (more) rows = rows.subList(0, limit)
        if (back) rows = rows.reversed()
        return Triple(rows, more, back)
    }

    override fun page(query: ItemQuery, direction: PageDirection, key: PageKey?, limit: Int): PageResult {
        val smart = query.smart
        if (smart != null && !SmartSql.compile(smart, wire).exact) return smartPage(query, smart, direction, key, limit)
        val (rows, more, back) = rawPage(query, direction, key, limit)
        val items = rows.mapNotNull(::toItem)
        val first = rows.firstOrNull()?.let { keyOf(it, query.order) }
        val last = rows.lastOrNull()?.let { keyOf(it, query.order) }
        val prev = when (direction) {
            PageDirection.FIRST -> null
            PageDirection.BEFORE -> if (more) first else null
            else -> first
        }
        val next = if (back) last else if (more) last else null
        return PageResult(items, prev, next)
    }

    /** The collections (playlists) that hold each of [ids], for the smart rules that name a collection. */
    private fun collectionsOf(ids: List<String>): Map<String, Set<String>> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableSet<String>>()
        for (r in reader.rows("SELECT e.item, e.playlist FROM entries e WHERE e.item IN (SELECT value FROM json_each(?)) AND e.entry NOT IN (SELECT entry FROM removed_entries)", listOf(jsonArray(ids)))) {
            out.getOrPut(r[0] as String) { HashSet() }.add(r[1] as String)
        }
        return out
    }

    /**
     * A smart space with a rule that SQL cannot decide exactly (an artist, an album, a source): the rows that pass what SQL can decide
     * are read in batches in the order of the list and checked against the rules, until the page is full.
     */
    private fun smartPage(q: ItemQuery, smart: app.keepfin.SmartRules, direction: PageDirection, key: PageKey?, limit: Int): PageResult {
        val back = direction == PageDirection.BEFORE
        val now = (q.smartContext ?: SmartContext()).now
        val matched = ArrayList<Pair<PageKey, LibraryItem>>() // in scan order: away from the key
        var cursor = key
        var dir = direction
        var more = false
        scan@ while (true) {
            val (rows, rowsMore, _) = rawPage(q, dir, cursor, 300)
            if (rows.isEmpty()) break
            val membership = collectionsOf(rows.map { it[C_ID] as String })
            for (r in (if (back) rows.asReversed() else rows)) {
                val item = toItem(r) ?: continue
                if (!SmartLogic.matches(item, smart, SmartContext(now, membership))) continue
                if (matched.size == limit) { more = true; break@scan }
                matched += keyOf(r, q.order) to item
            }
            if (!rowsMore) break
            cursor = keyOf(if (back) rows.first() else rows.last(), q.order)
            dir = if (back) PageDirection.BEFORE else PageDirection.AFTER
        }
        if (back) matched.reverse()
        val first = matched.firstOrNull()?.first
        val last = matched.lastOrNull()?.first
        val prev = when (direction) { PageDirection.FIRST -> null; PageDirection.BEFORE -> if (more) first else null; else -> first }
        val next = if (back) last else if (more) last else null
        return PageResult(matched.map { it.second }, prev, next)
    }
    override fun keyOf(item: LibraryItem, order: ItemOrder): PageKey = when (order) {
        ItemOrder.ADDED -> listOf(item.addedAt, item.id)
        ItemOrder.TITLE -> listOf(app.keepfin.LibraryViewsKeys.fold(item.title), item.id)
        ItemOrder.ARTIST -> listOf(
            app.keepfin.LibraryViewsKeys.fold(item.artist ?: item.albumArtist), app.keepfin.LibraryViewsKeys.fold(item.album),
            (item.disc ?: 1).toLong(), (item.track ?: Int.MAX_VALUE).toLong(), app.keepfin.LibraryViewsKeys.fold(item.title), item.id,
        )
        ItemOrder.DURATION -> listOf(item.duration ?: 0.0, item.id)
        ItemOrder.SIZE -> listOf(item.size, item.id)
        ItemOrder.MOST_PLAYED -> listOf(item.plays.toLong(), item.lastPlayedAt ?: 0L, item.id)
        ItemOrder.RECENT, ItemOrder.STALE -> listOf(item.lastPlayedAt ?: 0L, item.id)
        ItemOrder.TRACK -> listOf((item.disc ?: 1).toLong(), (item.track ?: Int.MAX_VALUE).toLong(), app.keepfin.LibraryViewsKeys.fold(item.title), item.id)
    }

    // ------------------------------------------------------------------------------------------------ counts and single reads

    override fun count(query: ItemQuery): Int {
        if (query.smart != null && !SmartSql.compile(query.smart!!, wire).exact) {
            var n = 0
            var key: PageKey? = null
            var dir = PageDirection.FIRST
            while (true) {
                val p = smartPage(query, query.smart!!, dir, key, 500)
                n += p.items.size
                key = p.next ?: break
                dir = PageDirection.AFTER
            }
            return n
        }
        val f = filter(query)
        return reader.rows("SELECT COUNT(*) FROM lib_index i WHERE ${f.sql}", f.params)[0][0].long().toInt()
    }

    override fun counts(query: ItemQuery): Counts {
        if (query.smart != null && !SmartSql.compile(query.smart!!, wire).exact) {
            val all = ArrayList<LibraryItem>()
            var key: PageKey? = null
            var dir = PageDirection.FIRST
            while (true) {
                val p = smartPage(query, query.smart!!, dir, key, 500)
                all += p.items
                key = p.next ?: break
                dir = PageDirection.AFTER
            }
            return Counts(all.size, all.count { it.kind == LibraryItemKind.AUDIO }, all.count { it.kind == LibraryItemKind.VIDEO }, all.count { it.kind == LibraryItemKind.IMAGE }, all.count { it.kind == LibraryItemKind.OTHER }, all.sumOf { it.size })
        }
        val f = filter(query.copy(order = ItemOrder.ADDED))
        var total = 0; var audio = 0; var video = 0; var image = 0; var other = 0; var bytes = 0L
        for (r in reader.rows("SELECT i.media, COUNT(*), COALESCE(SUM(i.size), 0) FROM lib_index i WHERE ${f.sql} GROUP BY i.media", f.params)) {
            val n = r[1].long().toInt()
            total += n
            bytes += r[2].long()
            when (r[0]) { "audio" -> audio += n; "video" -> video += n; "image" -> image += n; else -> other += n }
        }
        return Counts(total, audio, video, image, other, bytes)
    }

    override fun facets(field: SmartField, limit: Int): List<Facet> {
        val rows = when (field) {
            SmartField.TAG -> reader.rows("SELECT tag, COUNT(*) FROM lib_tags GROUP BY tag ORDER BY COUNT(*) DESC, tag LIMIT ?", listOf(limit.toLong()))
            SmartField.SOURCE -> reader.rows("SELECT prov, COUNT(*) FROM lib_index WHERE prov IS NOT NULL AND place IS NOT NULL GROUP BY prov ORDER BY COUNT(*) DESC, prov LIMIT ?", listOf(limit.toLong()))
            SmartField.ALBUM -> reader.rows("SELECT album, COUNT(*) FROM lib_index WHERE album IS NOT NULL AND album <> '' AND place IS NOT NULL GROUP BY album ORDER BY COUNT(*) DESC, album LIMIT ?", listOf(limit.toLong()))
            SmartField.ARTIST -> reader.rows(
                "SELECT name, COUNT(*) FROM (SELECT artist AS name FROM lib_index WHERE artist <> '' AND place IS NOT NULL UNION ALL SELECT albumArtist FROM lib_index WHERE albumArtist <> '' AND albumArtist IS NOT artist AND place IS NOT NULL) " +
                    "WHERE name IS NOT NULL GROUP BY name ORDER BY COUNT(*) DESC, name LIMIT ?", listOf(limit.toLong()),
            )
            else -> emptyList()
        }
        return rows.map { Facet(it[0] as String, it[1].long().toInt()) }
    }

    override fun spaceCounts(query: ItemQuery): Map<String, Counts> {
        val by = HashMap<String, IntArray>() // total, audio, video, image, other
        val bytes = HashMap<String, Long>()
        val smart = query.smart
        if (smart != null && !SmartSql.compile(smart, wire).exact) {
            val all = ArrayList<LibraryItem>()
            var key: PageKey? = null
            var dir = PageDirection.FIRST
            while (true) {
                val p = smartPage(query, smart, dir, key, 500)
                all += p.items
                key = p.next ?: break
                dir = PageDirection.AFTER
            }
            return all.groupBy { it.space }.mapValues { (_, l) ->
                Counts(l.size, l.count { it.kind == LibraryItemKind.AUDIO }, l.count { it.kind == LibraryItemKind.VIDEO }, l.count { it.kind == LibraryItemKind.IMAGE }, l.count { it.kind == LibraryItemKind.OTHER }, l.sumOf { it.size })
            }
        }
        val f = filter(query.copy(order = ItemOrder.ADDED))
        for (r in reader.rows("SELECT i.space, i.media, COUNT(*), COALESCE(SUM(i.size), 0) FROM lib_index i WHERE ${f.sql} GROUP BY i.space, i.media", f.params)) {
            val space = env.modelSpace(r[0] as String)
            val a = by.getOrPut(space) { IntArray(5) }
            val n = r[2].long().toInt()
            a[0] += n
            when (r[1]) { "audio" -> a[1] += n; "video" -> a[2] += n; "image" -> a[3] += n; else -> a[4] += n }
            bytes[space] = (bytes[space] ?: 0L) + r[3].long()
        }
        return by.mapValues { (k, a) -> Counts(a[0], a[1], a[2], a[3], a[4], bytes[k] ?: 0L) }
    }

    override fun outsideCounts(space: String): Map<String, Int> {
        val out = HashMap<String, Int>()
        for (r in reader.rows(
            "SELECT e.playlist, COUNT(*) FROM entries e JOIN lib_index i ON i.id = e.item WHERE i.space <> ? AND i.place IS NOT NULL " +
                "AND e.entry NOT IN (SELECT entry FROM removed_entries) GROUP BY e.playlist",
            listOf(wire(space)),
        )) out[r[0] as String] = r[1].long().toInt()
        return out
    }

    override fun spacesOf(ids: Collection<String>): Set<String> {
        val out = HashSet<String>()
        for (chunk in ids.distinct().chunked(20_000)) {
            for (r in reader.rows("SELECT DISTINCT space FROM lib_index WHERE id IN (SELECT value FROM json_each(?))", listOf(jsonArray(chunk)))) out += env.modelSpace(r[0] as String)
        }
        return out
    }

    override fun byIds(ids: List<String>): List<LibraryItem> {
        if (ids.isEmpty()) return emptyList()
        val found = HashMap<String, LibraryItem>()
        for (chunk in ids.distinct().chunked(900)) {
            for (r in reader.rows("SELECT $COLS FROM lib_index i WHERE i.id IN (SELECT value FROM json_each(?))", listOf(jsonArray(chunk)))) {
                toItem(r)?.let { found[it.id] = it }
            }
        }
        return ids.mapNotNull { found[it] }
    }

    override fun duplicates(query: ItemQuery, ids: Collection<String>?, limit: Int): List<List<LibraryItem>> {
        if (ids != null && ids.isEmpty()) return emptyList()
        // the scope: a collection's items, or what the list's query selects
        val (scope, params) = if (ids != null) "i.id IN (SELECT value FROM json_each(?))" to listOf<Any?>(jsonArray(ids.distinct()))
            else filter(query).let { it.sql to it.params }
        // one grouped pass over the content index finds the fingerprints held more than once
        val contents = reader.rows(
            "SELECT i.content FROM lib_index i WHERE i.content IS NOT NULL AND ($scope) GROUP BY i.content HAVING COUNT(*) > 1 LIMIT ?",
            params + limit.toLong(),
        ).map { it[0] as String }
        if (contents.isEmpty()) return emptyList()
        val rows = reader.rows(
            "SELECT i.id, i.content FROM lib_index i WHERE i.content IN (SELECT value FROM json_each(?)) AND ($scope) ORDER BY i.added ASC, i.id ASC",
            listOf<Any?>(jsonArray(contents)) + params,
        )
        val groups = LinkedHashMap<String, MutableList<String>>()
        for (r in rows) groups.getOrPut(r[1] as String) { ArrayList() } += r[0] as String
        val items = byIds(rows.map { it[0] as String }).associateBy { it.id }
        return groups.values.mapNotNull { group -> group.mapNotNull(items::get).takeIf { it.size > 1 } }
    }

    override fun byJob(jobId: String): LibraryItem? = env.itemForJob(jobId)?.let { item(it) }

    override fun item(id: String): LibraryItem? =
        reader.rows("SELECT $COLS FROM lib_index i WHERE i.id = ?", listOf(id)).firstOrNull()?.let(::toItem)

    // ------------------------------------------------------------------------------------------------ groups

    private fun covers(ids: List<String?>): Map<String, LibraryItem> = byIds(ids.filterNotNull().distinct()).associateBy { it.id }

    override fun albums(query: ItemQuery, after: String?, limit: Int, byArtist: Boolean): List<AlbumGroup> {
        val f = filter(query.copy(kinds = emptySet(), songs = true, order = ItemOrder.TITLE))
        val params = ArrayList<Any?>(f.params)
        var cond = "i.albumKey IS NOT NULL AND ${f.sql}"
        // by title: (title, space, key); by artist: (space, key), the key starting with the album artist
        val cols = if (byArtist) "i.space, i.albumKey" else "i.bkey, i.space, i.albumKey"
        if (after != null) {
            val parts = after.split('\u0000')
            if (parts.size == (if (byArtist) 2 else 3)) {
                cond += " AND ($cols) > (${parts.joinToString(", ") { "?" }})"
                params.addAll(parts)
            }
        }
        params += limit
        val rows = reader.rows(
            "SELECT i.space, i.albumKey, MIN(i.album), MIN(i.bkey), MIN(CASE WHEN i.albumArtist <> '' THEN i.albumArtist END), MIN(CASE WHEN i.artist <> '' THEN i.artist END), " +
                "COUNT(DISTINCT CASE WHEN i.rkey <> '' THEN i.rkey END), MIN(i.year), COUNT(*), COALESCE(SUM(i.duration), 0), COUNT(DISTINCT i.dsort), MIN(i.id) " +
                "FROM lib_index i WHERE $cond GROUP BY $cols ORDER BY $cols LIMIT ?",
            params,
        )
        val cover = covers(rows.map { it[11] as String })
        return rows.map { r -> albumOf(r, cover, byArtist) }
    }

    private val various = setOf("various artists", "various", "va", "compilation")

    private fun albumOf(r: List<Any?>, cover: Map<String, LibraryItem>, byArtist: Boolean = false): AlbumGroup {
        val space = env.modelSpace(r[0] as String)
        val routeKey = r[1] as String
        val albumArtist = r[4].str()
        val artists = r[6].long().toInt()
        val compilation = albumArtist?.let { app.keepfin.LibraryViewsKeys.fold(it) in various } == true || (albumArtist != null && artists > 1)
        return AlbumGroup(
            key = "$space/$routeKey", space = space, title = r[2].str().orEmpty(),
            artist = albumArtist ?: if (artists > 1) "Various Artists" else r[5].str().orEmpty().ifBlank { "Unknown artist" },
            year = r[7].int(), tracks = r[8].long().toInt(), duration = r[9].dbl() ?: 0.0, discs = r[10].long().toInt(), compilation = compilation,
            routeKey = routeKey, cover = cover[r[11] as String], cursor = if (byArtist) "${r[0]}\u0000$routeKey" else "${r[3]}\u0000${r[0]}\u0000$routeKey",
        )
    }

    override fun albumCount(query: ItemQuery): Int {
        val f = filter(query.copy(kinds = emptySet(), songs = true, order = ItemOrder.TITLE))
        return reader.rows("SELECT COUNT(*) FROM (SELECT 1 FROM lib_index i WHERE i.albumKey IS NOT NULL AND ${f.sql} GROUP BY i.space, i.albumKey)", f.params)[0][0].long().toInt()
    }

    override fun artistCount(query: ItemQuery): Int {
        val f = filter(query.copy(kinds = emptySet(), songs = true, order = ItemOrder.TITLE))
        return reader.rows("SELECT COUNT(*) FROM (SELECT 1 FROM lib_index i WHERE ${f.sql} GROUP BY i.akey)", f.params)[0][0].long().toInt()
    }

    override fun album(space: String, routeKey: String): AlbumGroup? {
        val rows = reader.rows(
            "SELECT i.space, i.albumKey, MIN(i.album), MIN(i.bkey), MIN(CASE WHEN i.albumArtist <> '' THEN i.albumArtist END), MIN(CASE WHEN i.artist <> '' THEN i.artist END), " +
                "COUNT(DISTINCT CASE WHEN i.rkey <> '' THEN i.rkey END), MIN(i.year), COUNT(*), COALESCE(SUM(i.duration), 0), COUNT(DISTINCT i.dsort), MIN(i.id) " +
                "FROM lib_index i WHERE i.space = ? AND i.albumKey = ? AND i.place IS NOT NULL GROUP BY i.space, i.albumKey",
            listOf(wire(space), routeKey),
        )
        val r = rows.firstOrNull() ?: return null
        return albumOf(r, covers(listOf(r[11] as String)))
    }

    override fun artists(query: ItemQuery, after: String?, limit: Int): List<ArtistGroup> {
        val f = filter(query.copy(kinds = emptySet(), songs = true, order = ItemOrder.TITLE))
        val params = ArrayList<Any?>(f.params)
        var cond = f.sql
        if (after != null) {
            cond += " AND i.akey > ?"
            params += after
        }
        params += limit
        val rows = reader.rows(
            "SELECT i.akey, MIN(COALESCE(NULLIF(i.artist, ''), i.albumArtist)), COUNT(*), COUNT(DISTINCT i.albumKey), MIN(i.id) FROM lib_index i WHERE $cond " +
                "GROUP BY i.akey ORDER BY i.akey LIMIT ?",
            params,
        )
        val cover = covers(rows.map { it[4] as String })
        return rows.map { r ->
            ArtistGroup(r[1].str()?.takeIf { it.isNotBlank() } ?: "Unknown artist", r[0] as String, r[2].long().toInt(), r[3].long().toInt(), cover[r[4] as String], r[0] as String)
        }
    }

    override fun artist(key: String, space: String?): ArtistGroup? =
        artists(ItemQuery(spaces = listOfNotNull(space), artist = key), null, 1).firstOrNull()

    override fun albumsOfArtist(artistKey: String, space: String?): List<AlbumGroup> {
        val f = filter(ItemQuery(spaces = listOfNotNull(space), artist = artistKey, songs = true))
        val rows = reader.rows(
            "SELECT DISTINCT i.space, i.albumKey FROM lib_index i WHERE i.albumKey IS NOT NULL AND ${f.sql}",
            f.params,
        )
        val out = ArrayList<AlbumGroup>()
        for (r in rows) album(env.modelSpace(r[0] as String), r[1] as String)?.let { out += it }
        return out.sortedBy { app.keepfin.LibraryViewsKeys.fold(it.title) }
    }

    override fun folderList(space: String, kinds: Set<LibraryItemKind>): List<FolderGroup> {
        val root = env.spaceFolder(space)
        val prefix = if (root.isEmpty()) "" else "$root/"
        val params = ArrayList<Any?>()
        params += wire(space)
        var cond = "i.space = ? AND i.place IS NOT NULL AND i.dir <> ?"
        params += root
        if (kinds.isNotEmpty()) { cond += " AND i.media IN (SELECT value FROM json_each(?))"; params += jsonArray(kinds.map { it.wire }) }
        val rows = reader.rows("SELECT i.dir, COUNT(*), MIN(i.id) FROM lib_index i WHERE $cond GROUP BY i.dir ORDER BY i.dir", params)
        val cover = covers(rows.map { it[2] as String })
        return rows.mapNotNull { r ->
            val dir = r[0] as String
            val rest = if (prefix.isEmpty()) dir else if (dir.startsWith(prefix)) dir.substring(prefix.length) else return@mapNotNull null
            if (rest.isEmpty()) null else FolderGroup(rest.substringAfterLast('/'), rest, r[1].long().toInt(), cover[r[2] as String])
        }.sortedBy { app.keepfin.LibraryViewsKeys.fold(it.path) }
    }

    override fun folders(space: String, folder: String): List<FolderGroup> {
        val root = env.spaceFolder(space)
        val base = if (root.isEmpty()) folder else if (folder.isEmpty()) root else "$root/$folder"
        val prefix = if (base.isEmpty()) "" else "$base/"
        val rows = reader.rows(
            "SELECT substr(i.dir, ?1) AS rest, COUNT(*), MIN(i.id) FROM lib_index i WHERE i.space = ?2 AND i.place IS NOT NULL AND " +
                (if (prefix.isEmpty()) "i.dir <> ''" else "i.dir LIKE ?3 ESCAPE '\\' ") + " GROUP BY substr(i.dir, ?1, CASE WHEN instr(substr(i.dir, ?1), '/') = 0 THEN 100000 ELSE instr(substr(i.dir, ?1), '/') - 1 END)",
            if (prefix.isEmpty()) listOf(1L, wire(space)) else listOf((prefix.length + 1).toLong(), wire(space), prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"),
        )
        val cover = covers(rows.map { it[2] as String })
        return rows.map { r ->
            val rest = r[0] as String
            val name = rest.substringBefore('/')
            FolderGroup(name, if (folder.isEmpty()) name else "$folder/$name", r[1].long().toInt(), cover[r[2] as String])
        }.sortedBy { app.keepfin.LibraryViewsKeys.fold(it.name) }
    }

    override fun tags(query: ItemQuery, minItems: Int): List<ScopedTag> {
        val f = filter(query.copy(order = ItemOrder.ADDED))
        val rows = reader.rows(
            "SELECT t.tag, COUNT(*), MAX(t.own) FROM lib_tags t JOIN lib_index i ON i.id = t.id WHERE ${f.sql} GROUP BY t.tag HAVING MAX(t.own) = 1 OR COUNT(*) >= ?",
            f.params + minItems.toLong(),
        )
        return rows.map { ScopedTag(it[0] as String, it[1].long().toInt(), it[2].long() == 1L) }
            .sortedWith(compareByDescending<ScopedTag> { it.count }.thenBy { it.tag })
    }

    override fun ids(query: ItemQuery, limit: Int): List<String> {
        if (query.smart != null && !SmartSql.compile(query.smart!!, wire).exact) return top(query, limit).map { it.id }
        val f = filter(query)
        val cols = orderColumns(query.order)
        val dir = if (query.order.descending) "DESC" else "ASC"
        return reader.rows("SELECT i.id FROM lib_index i WHERE ${f.sql} ORDER BY ${cols.joinToString(", ") { "i.$it $dir" }} LIMIT ?", f.params + limit.toLong()).map { it[0] as String }
    }
}