package app.keepfin.merge

import app.keepfin.core.library.LibCollection
import app.keepfin.core.library.LibItem
import app.keepfin.core.library.LibRoutes
import app.keepfin.core.library.LibSpace
import app.keepfin.core.library.LibraryMapping
import app.keepfin.core.merge.THashIndex
import app.keepfin.core.merge.TItem
import app.keepfin.core.sync.CanonicalResult
import app.keepfin.core.sync.Hlc
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.Jv
import app.keepfin.core.sync.decodeCanonical
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.sql.SqlMergeState
import java.util.concurrent.TimeUnit
import org.json.JSONArray

/**
 * Read access to one library's SQLite state (the open one for S, the staged one for T), always on the store's own thread so a read never
 * races a write ([KeepfinStore.withState]). Everything is paged: ids go in as JSON arrays, rows come back by key, and nothing here holds
 * library-sized data.
 */
class LibraryReader(private val store: KeepfinStore, private val state: SqlMergeState, val replicaId: String) {
    private val actorField get() = LibraryMapping.PLAYS_PREFIX + store.actorId

    private fun <T> on(work: () -> T): T = store.withState { work() }.get(120, TimeUnit.SECONDS)

    private fun idsJson(ids: Collection<String>): String = JSONArray(ids.toList()).toString()


    /** The present items after [afterId] (BINARY order of the id), each with the wall time of its oldest register: the stand-in for item.add's clock. */
    fun orderPage(afterId: String, limit: Int): List<Pair<Long, String>> = on {
        state.query(
            "SELECT r.k1, MIN(r.wall) FROM regs r JOIN item_view v ON v.id = r.k1 WHERE r.scope = 'item' AND v.status IN ('present', 'deleteConflict') " +
                "AND r.k1 > ?1 GROUP BY r.k1 ORDER BY r.k1 LIMIT ?2",
            listOf(afterId, limit),
        ).map { (it[1] as Long) to (it[0] as String) }
    }

    /** The items as the shared mapping projects them for this replica (unknown and deleted ones are absent). */
    fun items(ids: Collection<String>): List<LibItem> = if (ids.isEmpty()) emptyList() else on { state.views.libItems(ids, replicaId, state.views.baseViews(), actorField).first }

    /** The revision hash and size of the head of each item (`lib_index.content` and `size`), as far as it has one. */
    fun contents(ids: Collection<String>): Map<String, Pair<String?, Long>> = if (ids.isEmpty()) emptyMap() else on {
        state.query("SELECT id, content, size FROM lib_index WHERE id IN (SELECT value FROM json_each(?1))", listOf(idsJson(ids)))
            .associate { (it[0] as String) to ((it[1] as String?)?.takeIf { c -> c.isNotEmpty() } to ((it[2] as? Long) ?: 0L)) }
    }

    /** The clock of every register of each item, by field. */
    fun clocks(ids: Collection<String>): Map<String, Map<String, Hlc>> = if (ids.isEmpty()) emptyMap() else on {
        val out = HashMap<String, MutableMap<String, Hlc>>()
        for (r in state.query("SELECT k1, k2, wall, counter, hactor FROM regs WHERE scope = 'item' AND k1 IN (SELECT value FROM json_each(?1))", listOf(idsJson(ids)))) {
            out.getOrPut(r[0] as String) { HashMap() }[r[1] as String] = Hlc(r[2] as Long, (r[3] as Long).toInt(), r[4] as String)
        }
        out
    }

    /** Where each replica keeps each item. */
    fun placements(ids: Collection<String>): Map<String, Map<String, String>> = if (ids.isEmpty()) emptyMap() else on {
        val out = HashMap<String, MutableMap<String, String>>()
        for (r in state.query("SELECT k1, k2, value FROM regs WHERE scope = 'placement' AND k1 IN (SELECT value FROM json_each(?1))", listOf(idsJson(ids)))) {
            val v = (decodeCanonical(r[2] as String) as? CanonicalResult.Ok)?.value as? JStr ?: continue
            if (v.value.isNotEmpty()) out.getOrPut(r[0] as String) { HashMap() }[r[1] as String] = v.value
        }
        out
    }

    /** The oldest register wall time of each item. */
    fun addWalls(ids: Collection<String>): Map<String, Long> = if (ids.isEmpty()) emptyMap() else on {
        state.query("SELECT k1, MIN(wall) FROM regs WHERE scope = 'item' AND k1 IN (SELECT value FROM json_each(?1)) GROUP BY k1", listOf(idsJson(ids)))
            .associate { (it[0] as String) to (it[1] as Long) }
    }

    /** Items whose `item.add` was written by [actor] with a sequence number above [seq], after [afterId], as (oldest register wall, id). */
    fun addedAfterPage(actor: String, seq: Long, afterId: String, limit: Int): List<Pair<Long, String>> {
        val ids = on {
            state.query(
                "SELECT t.k1 FROM tags t JOIN item_view v ON v.id = t.k1 WHERE t.scope = 'item' AND t.k2 = '' AND t.kind = 'a' AND t.actor = ?1 AND t.seq > ?2 " +
                    "AND t.k1 > ?3 AND v.status IN ('present', 'deleteConflict') ORDER BY t.k1 LIMIT ?4",
                listOf(actor, seq, afterId, limit),
            ).map { it[0] as String }
        }
        val walls = addWalls(ids)
        return ids.map { (walls[it] ?: 0L) to it }
    }

    /** The sequence number of each item's `item.add` by [actor]. */
    fun addSeqs(ids: Collection<String>, actor: String): Map<String, Long> = if (ids.isEmpty()) emptyMap() else on {
        state.query(
            "SELECT k1, MAX(seq) FROM tags WHERE scope = 'item' AND k2 = '' AND kind = 'a' AND actor = ?2 AND k1 IN (SELECT value FROM json_each(?1)) GROUP BY k1",
            listOf(idsJson(ids), actor),
        ).associate { (it[0] as String) to (it[1] as Long) }
    }

    fun idsWithContent(hash: String): List<String> = on { state.query("SELECT id FROM lib_index WHERE content = ?1", listOf(hash)).map { it[0] as String } }

    fun spacesAndRoutes(): Pair<List<LibSpace>, LibRoutes> = on { state.views.spacesAndRoutes(replicaId, state.views.baseViews()) }

    fun collections(): List<LibCollection> = on { state.views.collections(null, replicaId, state.views.baseViews()) }

    fun presentCount(): Long = on {
        (state.query("SELECT COUNT(*) FROM item_view WHERE status IN ('present', 'deleteConflict')").firstOrNull()?.get(0) as? Long) ?: 0L
    }

    /** The count of this actor's own operations (admitted, outbox included): where the receipts of a batch start. */
    fun actorSeq(actor: String): Long = on { state.versionVector()[actor] ?: 0L }
}

/** The stand-in for an item.add clock: the wall time of the oldest register, no counter, a fixed actor. Equal walls order by id. */
fun addClockOf(wall: Long): Hlc = Hlc(wall, 0, "00000000-0000-4000-8000-000000000000")

/**
 * T's content index for the planner, over the staged T's own SQLite state (`lib_index.content`, indexed by `lib_content`). Candidates for a
 * hash come back oldest first by (add clock, id).
 */
class TargetIndex(private val reader: LibraryReader) : THashIndex {
    private val names: Map<Pair<String, String>, List<String>> by lazy {
        val out = HashMap<Pair<String, String>, MutableList<String>>()
        for (c in reader.collections()) out.getOrPut("playlist" to c.name) { ArrayList() }.add(c.id)
        for (s in reader.spacesAndRoutes().first) out.getOrPut("space" to s.name) { ArrayList() }.add(s.id)
        out
    }

    override fun lookup(hash: String): Sequence<String> {
        val ids = reader.idsWithContent(hash)
        if (ids.size <= 1) return ids.asSequence()
        val walls = reader.addWalls(ids)
        return ids.sortedWith(compareBy<String>({ walls[it] ?: Long.MAX_VALUE }, { it })).asSequence()
    }

    override fun item(id: String): TItem {
        val item = reader.items(listOf(id)).firstOrNull() ?: throw IllegalStateException("The library item $id is no longer there.")
        val content = reader.contents(listOf(id))[id]
        val wall = reader.addWalls(listOf(id))[id] ?: 0L
        return TItem(
            fields = item, added = addClockOf(wall), revisionHash = content?.first, size = content?.second,
            placements = reader.placements(listOf(id))[id].orEmpty(), fieldClocks = reader.clocks(listOf(id))[id].orEmpty(),
        )
    }

    override fun sameName(kind: String, name: String): Sequence<String> = (names[kind to name] ?: emptyList()).sorted().asSequence()
}
