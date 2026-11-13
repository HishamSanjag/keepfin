package app.keepfin.devicemusic

import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.PlaybackItems
import app.keepfin.core.sqlstate.SqlDriver
import app.keepfin.core.sqlstate.SqlStmt
import app.keepfin.folder.sql.LibIndex
import org.json.JSONArray

/** What the store knows of a file from the last scan. */
internal class KnownFile(val modified: Long, val size: Long, val hidden: Boolean)

/** One device song's play record. */
internal class PlayRow(val plays: Long, val played: Long, val position: Double?, val watched: Boolean, val duration: Double?)

/**
 * The device-only table of the music already on this phone (dev-docs/plans/DEVICE-MUSIC.md, M4/M12): its own SQLite file, never the
 * Keepfin folder's state and never synced. `lib_index` has the same shape as the library's index, so the library's paged reads run on it
 * unchanged; `dm_files` holds what a scan needs (the diff keys, the hidden flag); `dm_playlists` is the local playlist overlay (M9).
 * One writer: every method is synchronized.
 */
internal class DeviceStore(private val db: SqlDriver) {
    init {
        try { db.exec("PRAGMA journal_mode = WAL") } catch (_: Throwable) { /* readers then wait for a write instead of reading beside it */ }
        for (ddl in LibIndex.DDL) db.exec(ddl)
        db.exec("CREATE TABLE IF NOT EXISTS dm_files (id TEXT PRIMARY KEY, rel TEXT NOT NULL, name TEXT NOT NULL, size INTEGER NOT NULL, modified INTEGER NOT NULL, hidden INTEGER NOT NULL DEFAULT 0, gen INTEGER NOT NULL) WITHOUT ROWID")
        db.exec("CREATE INDEX IF NOT EXISTS dm_files_where ON dm_files (rel, name, size)")
        db.exec("CREATE TABLE IF NOT EXISTS dm_folders (path TEXT PRIMARY KEY, gen INTEGER NOT NULL) WITHOUT ROWID")
        db.exec("CREATE TABLE IF NOT EXISTS dm_playlists (collection TEXT NOT NULL, id TEXT NOT NULL, pos INTEGER NOT NULL, PRIMARY KEY (collection, id)) WITHOUT ROWID")
        db.exec("CREATE TABLE IF NOT EXISTS dm_meta (k TEXT PRIMARY KEY, v TEXT NOT NULL) WITHOUT ROWID")
    }

    // a statement is prepared once and reused (the driver contract allows it): a scan runs a handful of them for every file
    private val statements = HashMap<String, SqlStmt>()

    private fun statement(sql: String): SqlStmt = statements.getOrPut(sql) { db.prepare(sql) }

    @Synchronized private fun rows(sql: String, params: List<Any?> = emptyList()): List<List<Any?>> = statement(sql).rows(params)

    @Synchronized private fun run(sql: String, params: List<Any?> = emptyList()): Long = statement(sql).run(params)

    @Synchronized fun <T> transaction(block: () -> T): T {
        db.begin()
        val out = try { block() } catch (e: Throwable) { try { db.rollback() } catch (_: Throwable) { }; throw e }
        db.commit()
        return out
    }

    @Synchronized fun meta(key: String): String? = rows("SELECT v FROM dm_meta WHERE k = ?", listOf(key)).firstOrNull()?.get(0) as String?

    @Synchronized fun setMeta(key: String, value: String) { run("INSERT OR REPLACE INTO dm_meta (k, v) VALUES (?, ?)", listOf(key, value)) }

    @Synchronized fun count(): Int = (rows("SELECT COUNT(*) FROM lib_index")[0][0] as Long).toInt()

    @Synchronized fun hiddenCount(): Int = (rows("SELECT COUNT(*) FROM dm_files WHERE hidden = 1")[0][0] as Long).toInt()

    @Synchronized fun nextGeneration(): Long {
        val next = (meta("gen")?.toLongOrNull() ?: 0L) + 1
        setMeta("gen", next.toString())
        return next
    }

    // ------------------------------------------------------------------------------------------------ scanning

    @Synchronized fun known(ids: List<String>): Map<String, KnownFile> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<String, KnownFile>()
        for (r in rows("SELECT id, modified, size, hidden FROM dm_files WHERE id IN (SELECT value FROM json_each(?))", listOf(json(ids)))) {
            out[r[0] as String] = KnownFile(r[1] as Long, r[2] as Long, r[3] as Long == 1L)
        }
        return out
    }

    @Synchronized fun touch(ids: List<String>, gen: Long) {
        if (ids.isNotEmpty()) run("UPDATE dm_files SET gen = ? WHERE id IN (SELECT value FROM json_each(?))", listOf(gen, json(ids)))
    }

    @Synchronized fun noteFolders(paths: Collection<String>, gen: Long) {
        for (p in paths) run("INSERT INTO dm_folders (path, gen) VALUES (?, ?) ON CONFLICT (path) DO UPDATE SET gen = excluded.gen", listOf(p, gen))
    }

    /** A file the earlier scan listed under another id (MediaStore rebuilt its ids): takes its record over, favourites and plays included. */
    @Synchronized fun adopt(newId: String, file: DeviceFile, gen: Long): Boolean {
        val old = rows("SELECT id FROM dm_files WHERE rel = ? AND name = ? AND size = ? AND gen <> ? LIMIT 1", listOf(file.relativePath, file.displayName, file.size, gen)).firstOrNull()?.get(0) as String? ?: return false
        if (rows("SELECT 1 FROM dm_files WHERE id = ?", listOf(newId)).isNotEmpty()) return false
        run("UPDATE lib_index SET id = ? WHERE id = ?", listOf(newId, old))
        run("UPDATE dm_files SET id = ? WHERE id = ?", listOf(newId, old))
        run("UPDATE dm_playlists SET id = ? WHERE id = ?", listOf(newId, old))
        return true
    }

    /** Writes a file's record. A new one starts without plays; a known one keeps its favourite, plays and resume point. */
    @Synchronized fun upsert(file: DeviceFile, gen: Long) {
        val hidden = rows("SELECT hidden FROM dm_files WHERE id = ?", listOf(file.id)).firstOrNull()?.get(0) as Long? ?: 0L
        run(
            "INSERT INTO dm_files (id, rel, name, size, modified, hidden, gen) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO UPDATE SET rel = excluded.rel, name = excluded.name, size = excluded.size, modified = excluded.modified, gen = excluded.gen",
            listOf(file.id, file.relativePath, file.displayName, file.size, file.dateModified, hidden, gen),
        )
        if (hidden == 1L) return
        val v = indexValues(file)
        val old = rows("SELECT n FROM lib_index WHERE id = ?", listOf(file.id)).firstOrNull()?.get(0) as Long?
        if (old != null) run("DELETE FROM lib_fts WHERE rowid = ?", listOf(old))
        run(UPSERT, v)
        val n = rows("SELECT n FROM lib_index WHERE id = ?", listOf(file.id))[0][0] as Long
        run("INSERT INTO lib_fts (rowid, title, artist, album, extra) VALUES (?, ?, ?, ?, ?)", listOf(n, v[3], listOfNotNull(v[5], v[10]).joinToString(" "), v[8], v[35]))
    }

    /** Drops everything the scan with [gen] did not see: files that are gone, and folders that no longer hold music. */
    @Synchronized fun sweep(gen: Long): Int {
        val gone = rows("SELECT COUNT(*) FROM dm_files WHERE gen <> ?", listOf(gen))[0][0] as Long
        if (gone > 0) {
            run("DELETE FROM lib_fts WHERE rowid IN (SELECT n FROM lib_index WHERE id IN (SELECT id FROM dm_files WHERE gen <> ?))", listOf(gen))
            run("DELETE FROM lib_index WHERE id IN (SELECT id FROM dm_files WHERE gen <> ?)", listOf(gen))
            run("DELETE FROM dm_files WHERE gen <> ?", listOf(gen))
        }
        run("DELETE FROM dm_folders WHERE gen <> ?", listOf(gen))
        return gone.toInt()
    }

    @Synchronized fun folders(): List<String> = rows("SELECT path FROM dm_folders ORDER BY path").map { it[0] as String }

    // ------------------------------------------------------------------------------------------------ hiding

    /** Removes songs from Keepfin without touching the files; a scan leaves them out until [unhideAll]. */
    @Synchronized fun hide(ids: List<String>) {
        if (ids.isEmpty()) return
        transaction {
            run("DELETE FROM lib_fts WHERE rowid IN (SELECT n FROM lib_index WHERE id IN (SELECT value FROM json_each(?)))", listOf(json(ids)))
            run("DELETE FROM lib_index WHERE id IN (SELECT value FROM json_each(?))", listOf(json(ids)))
            run("UPDATE dm_files SET hidden = 1 WHERE id IN (SELECT value FROM json_each(?))", listOf(json(ids)))
        }
    }

    /** Forgets the hidden marks: the next scan lists those songs again as new. */
    @Synchronized fun unhideAll(): Int = run("DELETE FROM dm_files WHERE hidden = 1").toInt()

    /** Deletes records of files that are gone (after a delete the person confirmed). */
    @Synchronized fun forget(ids: List<String>) {
        if (ids.isEmpty()) return
        transaction {
            run("DELETE FROM lib_fts WHERE rowid IN (SELECT n FROM lib_index WHERE id IN (SELECT value FROM json_each(?)))", listOf(json(ids)))
            run("DELETE FROM lib_index WHERE id IN (SELECT value FROM json_each(?))", listOf(json(ids)))
            run("DELETE FROM dm_files WHERE id IN (SELECT value FROM json_each(?))", listOf(json(ids)))
        }
    }

    @Synchronized fun uriOf(id: String): String? = (rows("SELECT place FROM lib_index WHERE id = ?", listOf(id)).firstOrNull()?.get(0) as String?)?.removePrefix(PLACE)

    // ------------------------------------------------------------------------------------------------ favourites, plays, playlists (M9)

    @Synchronized fun setFavorite(id: String, favorite: Boolean) { run("UPDATE lib_index SET fav = ? WHERE id = ?", listOf(if (favorite) 1L else 0L, id)) }

    @Synchronized fun playRow(id: String): PlayRow? = rows("SELECT plays, played, position, watched, duration FROM lib_index WHERE id = ?", listOf(id)).firstOrNull()?.let {
        PlayRow(it[0] as Long, it[1] as Long, (it[2] as? Number)?.toDouble(), it[3] as Long == 1L, (it[4] as? Number)?.toDouble())
    }

    @Synchronized fun setPlay(id: String, plays: Long, played: Long, position: Double?, watched: Boolean) {
        run("UPDATE lib_index SET plays = ?, played = ?, position = ?, watched = ? WHERE id = ?", listOf(plays, played, position, if (watched) 1L else 0L, id))
    }

    /** The device songs of each playlist, in the order they were added. */
    @Synchronized fun overlay(): Map<String, List<String>> {
        val out = LinkedHashMap<String, MutableList<String>>()
        for (r in rows("SELECT collection, id FROM dm_playlists ORDER BY collection, pos")) out.getOrPut(r[0] as String) { ArrayList() } += r[1] as String
        return out
    }

    @Synchronized fun overlayAdd(collection: String, ids: List<String>) {
        var next = (rows("SELECT COALESCE(MAX(pos), 0) FROM dm_playlists WHERE collection = ?", listOf(collection))[0][0] as Long) + 1
        for (id in ids) if (run("INSERT OR IGNORE INTO dm_playlists (collection, id, pos) VALUES (?, ?, ?)", listOf(collection, id, next)) > 0) next++
    }

    @Synchronized fun overlayRemove(collection: String, ids: List<String>) {
        for (id in ids) run("DELETE FROM dm_playlists WHERE collection = ? AND id = ?", listOf(collection, id))
    }

    @Synchronized fun overlayDelete(collection: String) { run("DELETE FROM dm_playlists WHERE collection = ?", listOf(collection)) }

    // ------------------------------------------------------------------------------------------------ rows

    private fun indexValues(f: DeviceFile): List<Any?> {
        val title = f.title ?: f.displayName.substringBeforeLast('.', f.displayName)
        // some phones keep the disc number in the thousands of the track number
        val disc = f.track?.takeIf { it >= 1000 }?.let { it / 1000 }
        val track = f.track?.let { if (it >= 1000) it % 1000 else it }?.takeIf { it > 0 }
        val dir = f.relativePath.trimEnd('/')
        val album = f.album
        val albumKey = if (album.isNullOrBlank()) null else PlaybackItems.albumKey(
            LibraryItem(id = "", space = "", uri = "", folder = "", kind = LibraryItemKind.AUDIO, title = title, artist = f.artist, album = album, albumArtist = f.albumArtist, size = 0, modifiedAt = 0, addedAt = 0),
        )
        val seconds = f.durationMs / 1000.0
        return listOf(
            f.id, SPACE, "audio", title, LibIndex.fold(title), f.artist, LibIndex.fold(f.artist ?: f.albumArtist), LibIndex.fold(f.artist), album, LibIndex.fold(album), f.albumArtist, albumKey,
            track?.toLong(), disc?.toLong(), (disc ?: 1).toLong(), (track ?: Int.MAX_VALUE).toLong(), f.year?.toLong(), null, seconds, seconds, null, null, null,
            (if (f.dateAdded > 0) f.dateAdded else f.dateModified) * 1000, 0L, 0L, null, 0L, 0L, 0L, null, null, "", "", PLACE + f.uri, dir, f.size, null, null, 0L,
        )
    }

    private fun json(ids: Collection<String>): String = JSONArray(ids.toList()).toString()

    @Synchronized fun close() {
        statements.values.forEach { runCatching { it.close() } }
        statements.clear()
        db.close()
    }

    companion object {
        const val SPACE = "device"
        const val PLACE = "device:"

        // metadata is replaced on a rescan; the favourite, the plays, the resume point and the time first seen are kept
        private const val UPSERT =
            "INSERT INTO lib_index (id, space, media, title, tkey, artist, akey, rkey, album, bkey, albumArtist, albumKey, track, disc, dsort, tsort, year, genre, duration, durs, width, height, gain, " +
                "added, played, plays, position, watched, fav, hasLyrics, srcUrl, srcId, tags, utags, place, dir, size, content, prov, musicVideo) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT (id) DO UPDATE SET title = excluded.title, tkey = excluded.tkey, artist = excluded.artist, akey = excluded.akey, rkey = excluded.rkey, album = excluded.album, " +
                "bkey = excluded.bkey, albumArtist = excluded.albumArtist, albumKey = excluded.albumKey, track = excluded.track, disc = excluded.disc, dsort = excluded.dsort, tsort = excluded.tsort, " +
                "year = excluded.year, duration = excluded.duration, durs = excluded.durs, place = excluded.place, dir = excluded.dir, size = excluded.size"
    }
}
