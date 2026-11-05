package app.keepfin.folder.sql

import app.keepfin.PlaybackItems
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.core.library.LibraryMapping
import app.keepfin.core.sqlstate.SqlStateDb
import app.keepfin.core.sqlstate.SqlViews
import app.keepfin.core.sqlstate.Touched
import app.keepfin.core.dedupe.Recordings as CoreRecordings
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/**
 * The device-side index of the library (decision D-A4): one row per item with everything a list shows and every key a list sorts,
 * filters or searches by, kept next to the merge state and updated in the same breath (on the store thread, after each admitted
 * batch). Screens read pages of it through read-only connections (keyset pagination, FTS5 search); nothing holds the item list in
 * memory. The index is a cache of the merge state: it is rebuilt from `item_view` whenever it does not match it.
 *
 * Values are folded the way the old in-memory views folded them (lower case, no diacritics), so the order and the search of a
 * 200,000-item library match what a small library showed.
 */
class LibIndex(private val db: SqlStateDb, private val replicaId: String) {
    companion object {
        const val VERSION = "9"
        private val marks = Regex("\\p{Mn}+")
        private const val SEP = "\n"

        /** Lower-case, diacritic-free text ("Goeransson"). The order of every text column. */
        fun fold(text: String?): String {
            if (text.isNullOrEmpty()) return ""
            // plain ASCII has nothing to decompose: lower case is all the folding there is
            var ascii = true
            for (c in text) if (c.code >= 128) { ascii = false; break }
            return if (ascii) text.lowercase(Locale.ROOT) else marks.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
        }

        val DDL: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS lib_index (" +
                "n INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE, space TEXT NOT NULL, media TEXT NOT NULL, " +
                "title TEXT NOT NULL, tkey TEXT NOT NULL, artist TEXT, akey TEXT NOT NULL, rkey TEXT NOT NULL, album TEXT, bkey TEXT NOT NULL, albumArtist TEXT, albumKey TEXT, " +
                "track INTEGER, disc INTEGER, dsort INTEGER NOT NULL, tsort INTEGER NOT NULL, year INTEGER, genre TEXT, duration REAL, durs REAL NOT NULL, width INTEGER, height INTEGER, gain REAL, " +
                "added INTEGER NOT NULL, played INTEGER NOT NULL, plays INTEGER NOT NULL, position REAL, watched INTEGER NOT NULL, fav INTEGER NOT NULL, " +
                "hasLyrics INTEGER NOT NULL, srcUrl TEXT, srcId TEXT, tags TEXT NOT NULL, utags TEXT NOT NULL, place TEXT, dir TEXT, size INTEGER NOT NULL, content TEXT, prov TEXT, musicVideo INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS lib_sources (sid TEXT NOT NULL, id TEXT NOT NULL, PRIMARY KEY (sid, id)) WITHOUT ROWID",
            "CREATE TABLE IF NOT EXISTS lib_tags (tag TEXT NOT NULL, id TEXT NOT NULL, own INTEGER NOT NULL, PRIMARY KEY (tag, id)) WITHOUT ROWID",
            "CREATE INDEX IF NOT EXISTS lib_tags_by_id ON lib_tags (id)",
            "CREATE INDEX IF NOT EXISTS lib_title ON lib_index (tkey, id)",
            "CREATE INDEX IF NOT EXISTS lib_added ON lib_index (added DESC, id DESC)",
            "CREATE INDEX IF NOT EXISTS lib_size ON lib_index (size DESC, id DESC)",
            "CREATE INDEX IF NOT EXISTS lib_dur ON lib_index (durs DESC, id DESC)",
            "CREATE INDEX IF NOT EXISTS lib_space_title ON lib_index (space, tkey, id)",
            "CREATE INDEX IF NOT EXISTS lib_space_added ON lib_index (space, added DESC, id DESC)",
            "CREATE INDEX IF NOT EXISTS lib_media_title ON lib_index (media, tkey, id)",
            "CREATE INDEX IF NOT EXISTS lib_media_added ON lib_index (media, added DESC, id DESC)",
            "CREATE INDEX IF NOT EXISTS lib_artist ON lib_index (akey, bkey, dsort, tsort, tkey, id)",
            "CREATE INDEX IF NOT EXISTS lib_space_artist ON lib_index (space, akey, bkey, dsort, tsort, tkey, id)",
            "CREATE INDEX IF NOT EXISTS lib_album ON lib_index (space, albumKey, dsort, tsort, tkey, id) WHERE albumKey IS NOT NULL",
            "CREATE INDEX IF NOT EXISTS lib_albums ON lib_index (media, bkey, space, albumKey) WHERE albumKey IS NOT NULL",
            "CREATE INDEX IF NOT EXISTS lib_dir ON lib_index (space, dir, tkey, id)",
            "CREATE INDEX IF NOT EXISTS lib_played ON lib_index (played DESC, id DESC) WHERE played > 0",
            "CREATE INDEX IF NOT EXISTS lib_plays ON lib_index (plays DESC, played DESC, id DESC) WHERE plays > 0",
            "CREATE INDEX IF NOT EXISTS lib_fav ON lib_index (added DESC, id DESC) WHERE fav = 1",
            "CREATE INDEX IF NOT EXISTS lib_prov ON lib_index (prov) WHERE prov IS NOT NULL",
            "CREATE INDEX IF NOT EXISTS lib_unplaced ON lib_index (id) WHERE place IS NULL",
            "CREATE INDEX IF NOT EXISTS lib_linked ON lib_index (id) WHERE substr(place, 1, 7) = 'linked:'",
            "CREATE INDEX IF NOT EXISTS lib_content ON lib_index (content) WHERE content IS NOT NULL",
            "CREATE INDEX IF NOT EXISTS lib_never ON lib_index (added DESC, id DESC) WHERE plays = 0",
            "CREATE INDEX IF NOT EXISTS lib_resume ON lib_index (played DESC, id DESC) WHERE position > 5 AND watched = 0",
            "CREATE VIRTUAL TABLE IF NOT EXISTS lib_fts USING fts5(title, artist, album, extra, tokenize = 'unicode61 remove_diacritics 2', prefix = '2 3')",
        )

        private const val UPSERT =
            "INSERT INTO lib_index (id, space, media, title, tkey, artist, akey, rkey, album, bkey, albumArtist, albumKey, track, disc, dsort, tsort, year, genre, duration, durs, width, height, gain, " +
                "added, played, plays, position, watched, fav, hasLyrics, srcUrl, srcId, tags, utags, place, dir, size, content, prov, musicVideo) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (id) DO UPDATE SET space = excluded.space, media = excluded.media, title = excluded.title, tkey = excluded.tkey, artist = excluded.artist, " +
                "akey = excluded.akey, rkey = excluded.rkey, album = excluded.album, bkey = excluded.bkey, albumArtist = excluded.albumArtist, albumKey = excluded.albumKey, track = excluded.track, " +
                "disc = excluded.disc, dsort = excluded.dsort, tsort = excluded.tsort, year = excluded.year, genre = excluded.genre, duration = excluded.duration, durs = excluded.durs, width = excluded.width, height = excluded.height, " +
                "gain = excluded.gain, added = excluded.added, played = excluded.played, plays = excluded.plays, position = excluded.position, watched = excluded.watched, " +
                "fav = excluded.fav, hasLyrics = excluded.hasLyrics, srcUrl = excluded.srcUrl, srcId = excluded.srcId, tags = excluded.tags, utags = excluded.utags, " +
                "place = excluded.place, dir = excluded.dir, size = excluded.size, content = excluded.content, prov = excluded.prov, musicVideo = excluded.musicVideo"

        private val kinds = setOf("audio", "video", "image", "other")
        private val TITLE_SPLIT = Regex("\\s+[-\u2013\u2014]\\s+")

        /** The stable source ids an item answers to ("youtube:<id>"...), from its link and its source id. */
        fun sourcesOf(srcUrl: Any?, srcId: Any?): Set<String> = CoreRecordings.sourceIds(srcUrl as? String) + listOfNotNull(srcId as? String)
    }

    private fun exec(sql: String) {
        db.prep(sql).run(emptyList())
    }

    private fun begin() = exec("BEGIN IMMEDIATE")
    private fun commit() = exec("COMMIT")
    private fun rollback() = exec("ROLLBACK")

    private inline fun <T> tx(block: () -> T): T {
        begin()
        val out = try {
            block()
        } catch (e: Throwable) {
            try { rollback() } catch (_: Throwable) { }
            throw e
        }
        commit()
        return out
    }

    private fun rows(sql: String, params: List<Any?> = emptyList()) = db.prep(sql).rows(params)

    fun createSchema() {
        for (ddl in DDL) exec(ddl)
    }

    /** A signature of the merge state's version vector: the index is current when it was written under the same one. */
    fun signature(): String {
        val r = rows("SELECT COALESCE(SUM(top), 0), COUNT(*) FROM vv")[0]
        return "${r[0]}:${r[1]}:${db.getMeta("maxHlc") ?: ""}"
    }

    /** Whether the index on disk describes the state on disk. */
    fun isCurrent(): Boolean =
        db.getMeta("libIndex") == "$VERSION.$replicaId" && db.getMeta("libIndexSig") == signature() &&
            rows("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'lib_index'").isNotEmpty()

    private fun mark() {
        db.setMeta("libIndex", "$VERSION.$replicaId")
        db.setMeta("libIndexSig", signature())
    }

    // ------------------------------------------------------------------------------------------------ writing

    /** Updates the index for what changed. [touched] came from the state; [all] rebuilds everything. */
    fun apply(touched: Touched) {
        if (touched.all) {
            rebuild()
            return
        }
        val items = LinkedHashSet<String>(touched.items)
        var favourites = false
        for (set in touched.sets) {
            if (set == "favourites") favourites = true
            else if (set.startsWith("tags:")) items.add(set.substring(5))
        }
        if (items.isEmpty() && !favourites) {
            tx { mark() }
            return
        }
        tx {
            if (items.isNotEmpty()) refresh(items.toList())
            if (favourites) syncFavourites()
            mark()
        }
    }

    /** Where the last rebuild spent its time (ms): reading item_view, building rows, writing, search, tags, indexes. */
    val timings = LinkedHashMap<String, Long>()

    /** Drops and refills the whole index (the first open of a state, after a bulk load or a rebuild). */
    fun rebuild() {
        timings.clear()
        var tRead = 0L; var tRow = 0L; var tUpsert = 0L; var tFts = 0L; var tSide = 0L
        exec("DROP TABLE IF EXISTS lib_fts")
        exec("DROP TABLE IF EXISTS lib_sources")
        exec("DROP TABLE IF EXISTS lib_tags")
        exec("DROP TABLE IF EXISTS lib_index")
        createSchema()
        // the bulk path: the secondary indexes are built after the load
        val indexes = DDL.filter { it.startsWith("CREATE INDEX") }
        for (ddl in indexes) exec(ddl.replace("CREATE INDEX IF NOT EXISTS ", "DROP INDEX IF EXISTS ").substringBefore(" ON "))
        val favourites = favouriteSet()
        val userTags = userTagMap()
        val memo = Memo()
        var after = ""
        tx {
            val upsert = db.prep(UPSERT)
            val fts = db.prep("INSERT INTO lib_fts (rowid, title, artist, album, extra) VALUES (?, ?, ?, ?, ?)")
            val rowid = db.prep("SELECT n FROM lib_index WHERE id = ?")
            val sourcePut = db.prep("INSERT OR IGNORE INTO lib_sources (sid, id) VALUES (?, ?)")
            val tagPut = db.prep("INSERT OR IGNORE INTO lib_tags (tag, id, own) VALUES (?, ?, ?)")
            while (true) {
                var t = System.nanoTime()
                val page = rows(FIELDS_SQL, listOf(replicaId, after))
                tRead += System.nanoTime() - t
                if (page.isEmpty()) break
                for (r in page) {
                    val id = r[0] as String
                    t = System.nanoTime()
                    val values = rowValuesSql(r, id in favourites, userTags[id].orEmpty(), memo)
                    tRow += System.nanoTime() - t
                    t = System.nanoTime()
                    upsert.run(values)
                    tUpsert += System.nanoTime() - t
                    t = System.nanoTime()
                    for (sid in sourcesOf(values[30], values[31])) sourcePut.run(listOf(sid, id))
                    tagsOf(values).forEach { (tag, own) -> tagPut.run(listOf(tag, id, own)) }
                    tSide += System.nanoTime() - t
                    t = System.nanoTime()
                    val n = rowid.first(listOf(id))!![0] as Long
                    fts.run(ftsRow(n, values))
                    tFts += System.nanoTime() - t
                }
                after = page.last()[0] as String
            }
            mark()
        }
        val tIdx = System.nanoTime()
        for (ddl in indexes) exec(ddl)
        timings["read item_view"] = tRead / 1_000_000; timings["build row values"] = tRow / 1_000_000; timings["insert lib_index"] = tUpsert / 1_000_000
        timings["tags and sources"] = tSide / 1_000_000; timings["search rows"] = tFts / 1_000_000; timings["secondary indexes"] = (System.nanoTime() - tIdx) / 1_000_000
    }

    private fun favouriteSet(): HashSet<String> {
        val out = HashSet<String>()
        for (r in rows(SqlViews.members("p.k1 = ?1"), listOf("favourites"))) out.add(r[1] as String)
        return out
    }

    private fun userTagMap(): HashMap<String, List<String>> {
        val out = HashMap<String, MutableList<String>>()
        for (r in rows(SqlViews.members("p.k1 >= ?1 AND p.k1 < ?2"), listOf("tags:", "tags;"))) {
            out.getOrPut((r[0] as String).substring(5)) { ArrayList() }.add(r[1] as String)
        }
        for (l in out.values) l.sort()
        return HashMap<String, List<String>>(out)
    }

    /** The fields of live items taken out by SQLite itself (no JSON parsing in Kotlin); the order of the columns is [rowValuesSql]'s. */
    private val FIELDS_SQL =
        "SELECT v.id, json_extract(v.fields, '$.media'), json_extract(v.fields, '$.space'), json_extract(v.fields, '$.title'), json_extract(v.fields, '$.artist'), " +
            "json_extract(v.fields, '$.album'), json_extract(v.fields, '$.albumArtist'), json_extract(v.fields, '$.track'), json_extract(v.fields, '$.disc'), " +
            "json_extract(v.fields, '$.year'), json_extract(v.fields, '$.genre'), json_extract(v.fields, '$.duration'), json_extract(v.fields, '$.width'), " +
            "json_extract(v.fields, '$.height'), json_extract(v.fields, '$.gain'), json_extract(v.fields, '$.addedAt'), json_extract(v.fields, '$.lastPlayedAt'), v.plays, " +
            "json_extract(v.fields, '$.position'), json_extract(v.fields, '$.watched'), json_extract(v.fields, '$.hasLyrics'), json_extract(v.fields, '$.sourceUrl'), " +
            "json_extract(v.fields, '$.sourceId'), (SELECT group_concat(j.value, char(10)) FROM json_each(json_extract(v.fields, '$.tags')) j), " +
            "(SELECT json_extract(r.value, '$') FROM regs r WHERE r.scope = 'placement' AND r.k1 = v.id AND r.k2 = ?1), json_extract(v.heads, '$[0].size'), json_extract(v.heads, '$[0].content'), " +
            "CASE WHEN instr(v.fields, '\"companion:.video.') > 0 THEN (SELECT COUNT(*) FROM json_each(v.fields) j WHERE substr(j.key, 1, 17) = 'companion:.video.' AND j.type = 'object') > 0 ELSE 0 END " +
            "FROM item_view v WHERE v.status <> 'deleted' AND v.id > ?2 ORDER BY v.id LIMIT 2000"

    private val PAGE_SQL =
        "SELECT v.id, v.status, v.fields, v.heads, v.plays, " +
            "(SELECT value FROM regs WHERE scope = 'placement' AND k1 = v.id AND k2 = ?1) FROM item_view v WHERE v.id > ?2 ORDER BY v.id LIMIT 2000"

    private fun refresh(ids: List<String>) {
        val upsert = db.prep(UPSERT)
        val fts = db.prep("INSERT INTO lib_fts (rowid, title, artist, album, extra) VALUES (?, ?, ?, ?, ?)")
        val ftsDrop = db.prep("DELETE FROM lib_fts WHERE rowid = ?")
        val rowid = db.prep("SELECT n, title, artist, albumArtist, album, genre, srcUrl, srcId, tags, utags, dir FROM lib_index WHERE id = ?")
        val one = db.prep(
            "SELECT v.id, v.status, v.fields, v.heads, v.plays, " +
                "(SELECT value FROM regs WHERE scope = 'placement' AND k1 = v.id AND k2 = ?1) FROM item_view v WHERE v.id = ?2",
        )
        val favourite = db.prep("SELECT 1 FROM present p WHERE p.scope = 'set' AND p.k1 = 'favourites' AND p.k2 = ?1 AND ${SqlViews.live("p.scope", "p.k1", "p.k2")}")
        val tagsOf = db.prep(SqlViews.members("p.k1 = ?1"))
        val drop = db.prep("DELETE FROM lib_index WHERE id = ?")
        val sourceDrop = db.prep("DELETE FROM lib_sources WHERE id = ?")
        val tagDrop = db.prep("DELETE FROM lib_tags WHERE id = ?")
        val tagPut = db.prep("INSERT OR IGNORE INTO lib_tags (tag, id, own) VALUES (?, ?, ?)")
        val sourcePut = db.prep("INSERT OR IGNORE INTO lib_sources (sid, id) VALUES (?, ?)")
        for (id in ids) {
            val oldRow = rowid.first(listOf(id))
            val old = oldRow?.get(0) as Long?
            val row = one.first(listOf(replicaId, id))
            if (row == null || row[1] == "deleted") {
                if (old != null) {
                    ftsDrop.run(listOf(old))
                    drop.run(listOf(id))
                    sourceDrop.run(listOf(id))
                    tagDrop.run(listOf(id))
                }
                continue
            }
            val fav = favourite.first(listOf(id)) != null
            val utags = tagsOf.rows(listOf("tags:$id")).map { it[1] as String }.sorted()
            val values = rowValues(row, fav, utags)
            upsert.run(values)
            // the search row, the tags and the source ids only change when the text they come from changed
            val same = oldRow != null && oldRow[1] == values[3] && oldRow[2] == values[5] && oldRow[3] == values[10] && oldRow[4] == values[8] && oldRow[5] == values[17] &&
                oldRow[6] == values[30] && oldRow[7] == values[31] && oldRow[8] == values[32] && oldRow[9] == values[33] && oldRow[10] == values[35]
            if (!same) {
                sourceDrop.run(listOf(id))
                for (sid in sourcesOf(values[30], values[31])) sourcePut.run(listOf(sid, id))
                tagDrop.run(listOf(id))
                tagsOf(values).forEach { (tag, own) -> tagPut.run(listOf(tag, id, own)) }
                val n = old ?: (db.prep("SELECT n FROM lib_index WHERE id = ?").first(listOf(id))!![0] as Long)
                if (old != null) ftsDrop.run(listOf(old))
                fts.run(ftsRow(n, values))
            }
        }
    }

    /** The tags of an index row: imported ones, then the user's own (own wins when a tag is both). */
    private fun tagsOf(v: List<Any?>): List<Pair<String, Long>> {
        val own = (v[33] as String).takeIf { it.isNotEmpty() }?.split(SEP).orEmpty()
        val imported = (v[32] as String).takeIf { it.isNotEmpty() }?.split(SEP).orEmpty()
        return own.map { it to 1L } + imported.filter { it !in own }.map { it to 0L }
    }

    /** Makes the fav column equal to the favourites set (the set is small; only the differences are written). */
    private fun syncFavourites() {
        val want = favouriteSet()
        val have = HashSet<String>()
        for (r in rows("SELECT id FROM lib_index WHERE fav = 1")) have.add(r[0] as String)
        val set = db.prep("UPDATE lib_index SET fav = ?2 WHERE id = ?1")
        for (id in want) if (id !in have) set.run(listOf(id, 1L))
        for (id in have) if (id !in want) set.run(listOf(id, 0L))
    }

    private fun ftsRow(n: Long, v: List<Any?>): List<Any?> = listOf(n, v[3], listOfNotNull(v[5], v[10]).joinToString(" "), v[8], listOfNotNull(v[17], v[35], v[32], v[33]).joinToString(" "))

    /** The fields of an item as the index keeps them, however they were read (from the view's JSON, or extracted by SQLite). */
    private class Raw(
        val id: String, val media: String, val space: String, val title: String, val artist: String?, val album: String?, val albumArtist: String?,
        val track: Long?, val disc: Long?, val year: Long?, val genre: String?, val duration: Double?, val width: Long?, val height: Long?, val gain: Double?,
        val added: Long, val played: Long, val plays: Long, val position: Double?, val watched: Boolean, val hasLyrics: Boolean,
        val srcUrl: String?, val srcId: String?, val tags: String, val place: String?, val size: Long, val content: String?, val musicVideo: Boolean = false,
    )

    /** Results of the pure key functions, kept for a rebuild: a library has a few thousand artists and albums for hundreds of thousands of songs. */
    private class Memo {
        val albumKeys = HashMap<String, String?>()
        val folds = HashMap<String, String>()
        fun fold(text: String?): String = if (text == null) "" else folds.getOrPut(text) { LibIndex.fold(text) }
    }

    private fun albumKeyOf(raw: Raw, memo: Memo?): String? {
        if (raw.album.isNullOrBlank()) return null
        val key = (raw.albumArtist ?: "") + "\u0000" + (raw.artist ?: "") + "\u0000" + raw.album
        memo?.albumKeys?.let { m -> if (m.containsKey(key)) return m[key] }
        val kind = LibraryItemKind.entries.firstOrNull { it.wire == raw.media } ?: LibraryItemKind.OTHER
        val v = PlaybackItems.albumKey(
            LibraryItem(id = "", space = "", uri = "", folder = "", kind = kind, title = raw.title, artist = raw.artist, album = raw.album, albumArtist = raw.albumArtist, size = 0, modifiedAt = 0, addedAt = 0),
        )
        memo?.albumKeys?.put(key, v)
        return v
    }

    /** The values of an index row in the column order of [UPSERT]. */
    private fun values(raw: Raw, fav: Boolean, userTags: List<String>, memo: Memo? = null): List<Any?> {
        val place = raw.place?.takeIf { LibraryMapping.validPlacement(it) }
        fun f(text: String?) = memo?.fold(text) ?: fold(text)
        return listOf(
            raw.id, raw.space, raw.media, raw.title, f(raw.title), raw.artist, f(raw.artist ?: raw.albumArtist), f(raw.artist), raw.album, f(raw.album), raw.albumArtist, albumKeyOf(raw, memo),
            raw.track, raw.disc, raw.disc ?: 1L, raw.track ?: 2147483647L, raw.year, raw.genre, raw.duration, raw.duration ?: 0.0, raw.width, raw.height, raw.gain,
            raw.added, raw.played, raw.plays.coerceAtLeast(0L), raw.position, if (raw.watched) 1L else 0L,
            if (fav) 1L else 0L, if (raw.hasLyrics) 1L else 0L, raw.srcUrl, raw.srcId, raw.tags,
            userTags.joinToString(SEP), place, place?.substringBeforeLast('/', ""), raw.size, raw.content?.takeIf { it.isNotEmpty() },
            raw.srcUrl?.let { app.keepfin.Providers.name(it) }, if (raw.musicVideo && raw.media == "audio") 1L else 0L,
        )
    }

    /** One view row read as JSON ([PAGE_SQL]'s shape: id, status, fields, heads, plays, placement). */
    private fun rowValues(r: List<Any?>, fav: Boolean, userTags: List<String>): List<Any?> {
        val f = JSONObject(r[2] as String)
        fun str(name: String): String? = if (f.has(name) && !f.isNull(name)) (f.opt(name) as? String) else null
        fun int(name: String): Long? = if (f.has(name) && !f.isNull(name)) (f.opt(name) as? Number)?.toLong() else null
        fun scaled(name: String): Double? = int(name)?.let { it / 1000.0 }
        fun bool(name: String): Boolean? = if (f.has(name) && !f.isNull(name)) f.opt(name) as? Boolean else null
        fun strings(name: String): List<String> {
            val a = f.optJSONArray(name) ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.opt(it) as? String }
        }
        val place = (r[5] as String?)?.let { raw -> (JSONObject("{\"v\":$raw}").opt("v") as? String) }
        val heads = JSONArray(r[3] as String)
        val head = if (heads.length() > 0) heads.getJSONObject(0) else null
        val raw = Raw(
            id = r[0] as String, media = str("media")?.takeIf { it in kinds } ?: "other", space = str("space") ?: "", title = str("title") ?: "", artist = str("artist"),
            album = str("album"), albumArtist = str("albumArtist"), track = int("track"), disc = int("disc"), year = int("year"), genre = str("genre"), duration = scaled("duration"),
            width = int("width"), height = int("height"), gain = scaled("gain"), added = int("addedAt") ?: 0L, played = int("lastPlayedAt") ?: 0L, plays = r[4] as Long,
            position = scaled("position"), watched = bool("watched") == true, hasLyrics = bool("hasLyrics") == true, srcUrl = str("sourceUrl"), srcId = str("sourceId"),
            tags = strings("tags").joinToString(SEP), place = place, size = head?.optLong("size", 0L) ?: 0L, content = head?.optString("content", ""),
            musicVideo = f.keys().asSequence().any { it.startsWith("companion:.video.") && f.optJSONObject(it) != null },
        )
        return values(raw, fav, userTags)
    }

    /** One view row with SQLite's own JSON functions having taken the fields out ([FIELDS_SQL]'s shape). */
    private fun rowValuesSql(c: List<Any?>, fav: Boolean, userTags: List<String>, memo: Memo): List<Any?> {
        fun l(i: Int): Long? = (c[i] as? Number)?.toLong()
        fun d(i: Int): Double? = (c[i] as? Number)?.let { it.toLong() / 1000.0 }
        val raw = Raw(
            id = c[0] as String, media = (c[1] as String?)?.takeIf { it in kinds } ?: "other", space = (c[2] as String?) ?: "", title = (c[3] as String?) ?: "", artist = c[4] as String?,
            album = c[5] as String?, albumArtist = c[6] as String?, track = l(7), disc = l(8), year = l(9), genre = c[10] as String?, duration = d(11), width = l(12), height = l(13),
            gain = d(14), added = l(15) ?: 0L, played = l(16) ?: 0L, plays = c[17] as Long, position = d(18), watched = l(19) == 1L, hasLyrics = l(20) == 1L,
            srcUrl = c[21] as String?, srcId = c[22] as String?, tags = (c[23] as String?) ?: "", place = c[24] as String?, size = l(25) ?: 0L, content = c[26] as String?, musicVideo = l(27) == 1L,
        )
        return values(raw, fav, userTags, memo)
    }
}