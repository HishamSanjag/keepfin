package app.keepfin.core.sqlstate

import app.keepfin.core.sync.EntityState
import app.keepfin.core.sync.EntryFacts
import app.keepfin.core.sync.Hlc
import app.keepfin.core.sync.ItemState
import app.keepfin.core.sync.JNum
import app.keepfin.core.sync.JObj
import app.keepfin.core.sync.JArr
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.Jv
import app.keepfin.core.sync.OpId
import app.keepfin.core.sync.OrSet
import app.keepfin.core.sync.Register
import app.keepfin.core.sync.RevisionEvent
import app.keepfin.core.sync.State
import app.keepfin.core.sync.encodeCanonical
import app.keepfin.core.sync.parseLoose

// A device-local merge state held in SQL (decision D-T3 of the desktop spec). This package writes no I/O: it gives
// the schema, one fixed catalog of parameterized statements, and for each delta State (the output of Op.toDelta)
// the list of statements that join it into the tables EXACTLY as State.join joins it in memory. The host (node:sqlite
// on the desktop, Android's SQLite later) runs the statements; nothing here knows which database it is.
//
// The join, table by table:
//  - register (hlc, op): the larger (wall, counter, hlc actor, op actor, op seq) wins, the same total order as
//    Hlc.compareTo then OpId.compareTo, as separate columns (never wire strings, so seq 9 < 10). On an exact tie the
//    larger canonical value text wins, like Register.join.
//  - OR-set adds, removes and deleted heads, removed entries, findings: insert-ignore.
//  - revision events: keyed by (item, op); a repeat keeps the smaller (content, size) and the parents are unioned.
//  - entry facts: the smaller op wins, and a different playlist or item adds the finding entry-conflict:<id>
//    (decided against the row that is there before the new one is written).
//  - presence rows record that a key exists in the state map even when everything under it is empty, so the state
//    reads back identical (an item.delete with no tags still creates the item).

/** One statement of the fixed catalog, with its parameters (String, Long or null). */
class SqlStatement(val id: Int, val params: List<Any?>)

object SqlState {
    /** The schema. Every table is WITHOUT ROWID: the primary key is the clustered key, so no separate index is kept. */
    val ddl: List<String> = listOf(
        "CREATE TABLE IF NOT EXISTS present (scope TEXT NOT NULL, k1 TEXT NOT NULL, k2 TEXT NOT NULL, PRIMARY KEY (scope, k1, k2)) WITHOUT ROWID",
        "CREATE TABLE IF NOT EXISTS tags (scope TEXT NOT NULL, k1 TEXT NOT NULL, k2 TEXT NOT NULL, kind TEXT NOT NULL, actor TEXT NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY (scope, k1, k2, kind, actor, seq)) WITHOUT ROWID",
        "CREATE TABLE IF NOT EXISTS regs (scope TEXT NOT NULL, k1 TEXT NOT NULL, k2 TEXT NOT NULL, wall INTEGER NOT NULL, counter INTEGER NOT NULL, hactor TEXT NOT NULL, oactor TEXT NOT NULL, oseq INTEGER NOT NULL, value TEXT NOT NULL, PRIMARY KEY (scope, k1, k2)) WITHOUT ROWID",
        "CREATE TABLE IF NOT EXISTS revisions (item TEXT NOT NULL, actor TEXT NOT NULL, seq INTEGER NOT NULL, content TEXT NOT NULL, size INTEGER NOT NULL, PRIMARY KEY (item, actor, seq)) WITHOUT ROWID",
        "CREATE TABLE IF NOT EXISTS rev_parents (item TEXT NOT NULL, actor TEXT NOT NULL, seq INTEGER NOT NULL, pactor TEXT NOT NULL, pseq INTEGER NOT NULL, PRIMARY KEY (item, actor, seq, pactor, pseq)) WITHOUT ROWID",
        "CREATE TABLE IF NOT EXISTS entries (entry TEXT NOT NULL PRIMARY KEY, playlist TEXT NOT NULL, item TEXT NOT NULL, actor TEXT NOT NULL, seq INTEGER NOT NULL) WITHOUT ROWID",
        "CREATE INDEX IF NOT EXISTS entries_by_playlist ON entries (playlist)",
        "CREATE TABLE IF NOT EXISTS removed_entries (entry TEXT NOT NULL PRIMARY KEY) WITHOUT ROWID",
        "CREATE TABLE IF NOT EXISTS findings (text TEXT NOT NULL PRIMARY KEY) WITHOUT ROWID",
        // admission (the op bytes live only in segments; the database keeps what it needs to judge a repeat)
        "CREATE TABLE IF NOT EXISTS held (actor TEXT NOT NULL, seq INTEGER NOT NULL, hash TEXT NOT NULL, kind INTEGER NOT NULL, size INTEGER NOT NULL, PRIMARY KEY (actor, seq)) WITHOUT ROWID",
        "CREATE TABLE IF NOT EXISTS variants (actor TEXT NOT NULL, seq INTEGER NOT NULL, hash TEXT NOT NULL, PRIMARY KEY (actor, seq, hash)) WITHOUT ROWID",
        "CREATE TABLE IF NOT EXISTS equiv (actor TEXT NOT NULL PRIMARY KEY, from_seq INTEGER NOT NULL) WITHOUT ROWID",
        "CREATE TABLE IF NOT EXISTS base (actor TEXT NOT NULL PRIMARY KEY, seq INTEGER NOT NULL) WITHOUT ROWID",
        "CREATE TABLE IF NOT EXISTS vv (actor TEXT NOT NULL PRIMARY KEY, top INTEGER NOT NULL) WITHOUT ROWID",
        "CREATE TABLE IF NOT EXISTS pending (actor TEXT NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY (actor, seq)) WITHOUT ROWID",
        "CREATE TABLE IF NOT EXISTS meta (k TEXT NOT NULL PRIMARY KEY, v TEXT NOT NULL) WITHOUT ROWID",
        // member ops (few): the text of each with the device id that signed its segment, so membership survives a restart and a rebuild
        "CREATE TABLE IF NOT EXISTS members (actor TEXT NOT NULL, seq INTEGER NOT NULL, signer TEXT, text TEXT NOT NULL, PRIMARY KEY (actor, seq)) WITHOUT ROWID",
        "CREATE TABLE IF NOT EXISTS cycles (item TEXT NOT NULL PRIMARY KEY) WITHOUT ROWID",
        // the item projection: one flattened row per known item (the host maintains it in the same transaction)
        "CREATE TABLE IF NOT EXISTS item_view (id TEXT NOT NULL PRIMARY KEY, status TEXT NOT NULL, fields TEXT NOT NULL, heads TEXT NOT NULL, plays INTEGER NOT NULL, conflict INTEGER NOT NULL, dch TEXT NOT NULL) WITHOUT ROWID",
    )

    /** Per-connection helper table: the items whose projection is being refreshed. */
    val sessionDdl: List<String> = listOf("CREATE TEMP TABLE IF NOT EXISTS touched (id TEXT NOT NULL PRIMARY KEY) WITHOUT ROWID")

    /**
     * Named statements the host runs (admission bookkeeping, the rows the projection is computed from, and the songs
     * query, whose ORDER BY is the song order). Parameters are positional.
     */
    val queries: Map<String, String> = linkedMapOf(
        "heldGet" to "SELECT hash, kind FROM held WHERE actor = ? AND seq = ?",
        "heldPut" to "INSERT INTO held (actor, seq, hash, kind, size) VALUES (?, ?, ?, ?, ?)",
        "variantPut" to "INSERT OR IGNORE INTO variants (actor, seq, hash) VALUES (?, ?, ?)",
        "equivGet" to "SELECT from_seq FROM equiv WHERE actor = ?",
        "equivPut" to "INSERT OR REPLACE INTO equiv (actor, from_seq) VALUES (?, ?)",
        "baseGet" to "SELECT seq FROM base WHERE actor = ?",
        "vvGet" to "SELECT top FROM vv WHERE actor = ?",
        "vvPut" to "INSERT OR REPLACE INTO vv (actor, top) VALUES (?, ?)",
        "pendingDrop" to "DELETE FROM pending WHERE actor = ? AND seq = ?",
        "pendingPut" to "INSERT OR IGNORE INTO pending (actor, seq) VALUES (?, ?)",
        "metaGet" to "SELECT v FROM meta WHERE k = ?",
        "metaPut" to "INSERT OR REPLACE INTO meta (k, v) VALUES (?, ?)",
        "memberPut" to "INSERT OR REPLACE INTO members (actor, seq, signer, text) VALUES (?, ?, ?, ?)",
        "cyclePut" to "INSERT OR IGNORE INTO cycles (item) VALUES (?)",
        "cycleDrop" to "DELETE FROM cycles WHERE item = ?",
        "touchedPut" to "INSERT OR IGNORE INTO touched (id) VALUES (?)",
        "touchedClear" to "DELETE FROM touched",
        "projTags" to "SELECT t.k1 AS id, t.kind AS kind, t.actor AS actor, t.seq AS seq FROM touched d CROSS JOIN tags t ON t.scope = 'item' AND t.k1 = d.id AND t.k2 = ''",
        "projFields" to "SELECT r.k1 AS id, r.k2 AS name, r.value AS value FROM touched d CROSS JOIN regs r ON r.scope = 'item' AND r.k1 = d.id",
        "projRevisions" to "SELECT v.item AS item, v.actor AS actor, v.seq AS seq, v.content AS content, v.size AS size FROM touched d CROSS JOIN revisions v ON v.item = d.id",
        "projParents" to "SELECT p.item AS item, p.actor AS actor, p.seq AS seq, p.pactor AS pactor, p.pseq AS pseq FROM touched d CROSS JOIN rev_parents p ON p.item = d.id",
        "viewPut" to "INSERT OR REPLACE INTO item_view (id, status, fields, heads, plays, conflict, dch) VALUES (?, ?, ?, ?, ?, ?, ?)",
        "viewDrop" to "DELETE FROM item_view WHERE id = ?",
        "playlistPresent" to "SELECT EXISTS (SELECT 1 FROM tags a WHERE a.scope = 'playlist' AND a.k1 = ? AND a.k2 = '' AND a.kind = 'a' " +
            "AND NOT EXISTS (SELECT 1 FROM tags r WHERE r.scope = 'playlist' AND r.k1 = a.k1 AND r.k2 = '' AND r.kind = 'r' AND r.actor = a.actor AND r.seq = a.seq)) AS present",
        // a present playlist's songs: live entries whose item is present or in a delete conflict, ordered by (key, entry)
        "songs" to "SELECT e.entry AS entry, e.item AS item, " +
            "COALESCE(CASE WHEN json_type(k.value) = 'text' THEN json_extract(k.value, '$') END, '') AS key " +
            "FROM entries e JOIN item_view v ON v.id = e.item AND v.status IN ('present', 'deleteConflict') " +
            "LEFT JOIN regs k ON k.scope = 'entryKey' AND k.k1 = e.entry AND k.k2 = '' " +
            "WHERE e.playlist = ? AND NOT EXISTS (SELECT 1 FROM removed_entries r WHERE r.entry = e.entry) ORDER BY key, e.entry",
    )

    private const val REG_TIE = "(excluded.wall, excluded.counter, excluded.hactor, excluded.oactor, excluded.oseq)"
    private const val REG_OLD = "(regs.wall, regs.counter, regs.hactor, regs.oactor, regs.oseq)"

    /** The statement catalog; a [SqlStatement.id] is an index into it. Parameter order is the order of the `?`s. */
    val catalog: List<String> = listOf(
        /* 0 */ "INSERT OR IGNORE INTO present (scope, k1, k2) VALUES (?, ?, ?)",
        /* 1 */ "INSERT OR IGNORE INTO tags (scope, k1, k2, kind, actor, seq) VALUES (?, ?, ?, ?, ?, ?)",
        /* 2 */ "INSERT INTO regs (scope, k1, k2, wall, counter, hactor, oactor, oseq, value) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
            "ON CONFLICT (scope, k1, k2) DO UPDATE SET wall = excluded.wall, counter = excluded.counter, hactor = excluded.hactor, " +
            "oactor = excluded.oactor, oseq = excluded.oseq, value = excluded.value " +
            "WHERE $REG_TIE > $REG_OLD OR ($REG_TIE = $REG_OLD AND excluded.value > regs.value)",
        /* 3 */ "INSERT INTO revisions (item, actor, seq, content, size) VALUES (?, ?, ?, ?, ?) " +
            "ON CONFLICT (item, actor, seq) DO UPDATE SET content = excluded.content, size = excluded.size " +
            "WHERE excluded.content < revisions.content OR (excluded.content = revisions.content AND excluded.size < revisions.size)",
        /* 4 */ "INSERT OR IGNORE INTO rev_parents (item, actor, seq, pactor, pseq) VALUES (?, ?, ?, ?, ?)",
        // params: finding text, entry, playlist, item. Runs BEFORE statement 6 so it sees the row already there.
        /* 5 */ "INSERT OR IGNORE INTO findings (text) SELECT ?1 FROM entries WHERE entry = ?2 AND (playlist <> ?3 OR item <> ?4)",
        /* 6 */ "INSERT INTO entries (entry, playlist, item, actor, seq) VALUES (?, ?, ?, ?, ?) " +
            "ON CONFLICT (entry) DO UPDATE SET playlist = excluded.playlist, item = excluded.item, actor = excluded.actor, seq = excluded.seq " +
            "WHERE (excluded.actor, excluded.seq) < (entries.actor, entries.seq)",
        /* 7 */ "INSERT OR IGNORE INTO removed_entries (entry) VALUES (?)",
        /* 8 */ "INSERT OR IGNORE INTO findings (text) VALUES (?)",
    )

    private const val ENTRY_CONFLICT = "entry-conflict:"

    private fun MutableList<SqlStatement>.presence(scope: String, k1: String, k2: String) {
        add(SqlStatement(0, listOf(scope, k1, k2)))
    }

    private fun MutableList<SqlStatement>.orSet(scope: String, k1: String, k2: String, s: OrSet) {
        for (a in s.adds) add(SqlStatement(1, listOf(scope, k1, k2, "a", a.actor, a.seq)))
        for (r in s.removes) add(SqlStatement(1, listOf(scope, k1, k2, "r", r.actor, r.seq)))
    }

    private fun MutableList<SqlStatement>.reg(scope: String, k1: String, k2: String, r: Register) {
        add(SqlStatement(2, listOf(scope, k1, k2, r.hlc.wall, r.hlc.counter.toLong(), r.hlc.actor, r.op.actor, r.op.seq, encodeCanonical(r.value))))
    }

    /** The statements that join [d] into the tables. Applying one delta twice, or deltas in any order, gives the same tables. */
    fun deltaToSql(d: State): List<SqlStatement> {
        val out = ArrayList<SqlStatement>()
        for ((id, i) in d.items) {
            out.presence("item", id, "")
            out.orSet("item", id, "", i.exists)
            for (h in i.deletedHeads) out.add(SqlStatement(1, listOf("item", id, "", "h", h.actor, h.seq)))
            for ((f, r) in i.fields) out.reg("item", id, f, r)
            for ((op, e) in i.revisions) {
                out.add(SqlStatement(3, listOf(id, op.actor, op.seq, e.content, e.size)))
                for (p in e.parents) out.add(SqlStatement(4, listOf(id, op.actor, op.seq, p.actor, p.seq)))
            }
        }
        for ((name, els) in d.sets) {
            for ((el, o) in els) {
                out.presence("set", name, el)
                out.orSet("set", name, el, o)
            }
        }
        for ((id, p) in d.playlists) {
            out.presence("playlist", id, "")
            out.orSet("playlist", id, "", p.exists)
            for ((f, r) in p.fields) out.reg("playlist", id, f, r)
        }
        for ((id, s) in d.spaces) {
            out.presence("space", id, "")
            out.orSet("space", id, "", s.exists)
            for ((f, r) in s.fields) out.reg("space", id, f, r)
        }
        for ((e, f) in d.entries) {
            out.add(SqlStatement(5, listOf(ENTRY_CONFLICT + e, e, f.playlist, f.item)))
            out.add(SqlStatement(6, listOf(e, f.playlist, f.item, f.op.actor, f.op.seq)))
        }
        for ((e, r) in d.entryKey) out.reg("entryKey", e, "", r)
        for (e in d.removedEntries) out.add(SqlStatement(7, listOf(e)))
        for ((item, byPlace) in d.placements) for ((place, r) in byPlace) out.reg("placement", item, place, r)
        for ((place, byField) in d.places) for ((f, r) in byField) out.reg("place", place, f, r)
        for ((f, r) in d.profile) out.reg("profile", f, "", r)
        for (f in d.findings) out.add(SqlStatement(8, listOf(f)))
        return out
    }

    // ---- reading the tables back as a State ----

    private fun arr(v: Jv?): List<List<Jv>> = ((v as? JArr)?.items ?: emptyList()).map { (it as JArr).items }

    private fun s(v: Jv): String = (v as JStr).value

    private fun n(v: Jv): Long = (v as JNum).value

    private class OrBuild {
        val adds = LinkedHashSet<OpId>()
        val removes = LinkedHashSet<OpId>()
        val heads = LinkedHashSet<OpId>()
    }

    /**
     * Rebuilds the State from the rows of the tables: [rowsJson] is `{present, tags, regs, revisions, revParents,
     * entries, removedEntries, findings}`, each a list of the rows in the column order of the schema. Meant for tests
     * and for checks; the app reads the projection, never this.
     */
    fun stateFromRows(rowsJson: String): State {
        val rows = (parseLoose(rowsJson) as JObj).members
        val orSets = HashMap<String, OrBuild>()
        fun key(scope: String, k1: String, k2: String) = scope + "\u0000" + k1 + "\u0000" + k2
        for (r in arr(rows["tags"])) {
            val b = orSets.getOrPut(key(s(r[0]), s(r[1]), s(r[2]))) { OrBuild() }
            val id = OpId(s(r[4]), n(r[5]))
            when (s(r[3])) {
                "a" -> b.adds.add(id)
                "r" -> b.removes.add(id)
                else -> b.heads.add(id)
            }
        }
        fun orSetOf(scope: String, k1: String, k2: String): OrSet {
            val b = orSets[key(scope, k1, k2)] ?: return OrSet()
            return OrSet(b.adds, b.removes)
        }
        // registers by scope, then by first key (one pass; a part has hundreds of thousands of rows)
        val regsByScope = HashMap<String, HashMap<String, LinkedHashMap<String, Register>>>()
        for (r in arr(rows["regs"])) {
            val reg = Register(Hlc(n(r[3]), n(r[4]).toInt(), s(r[5])), OpId(s(r[6]), n(r[7])), parseLoose(s(r[8]))!!)
            regsByScope.getOrPut(s(r[0])) { HashMap() }.getOrPut(s(r[1])) { LinkedHashMap() }[s(r[2])] = reg
        }
        fun fieldsOf(scope: String, k1: String): Map<String, Register> = regsByScope[scope]?.get(k1) ?: emptyMap()
        val revs = HashMap<String, LinkedHashMap<OpId, RevisionEvent>>()
        val parents = HashMap<String, LinkedHashSet<OpId>>()
        for (r in arr(rows["revParents"])) parents.getOrPut(s(r[0]) + "\u0000" + s(r[1]) + "\u0000" + n(r[2])) { LinkedHashSet() }.add(OpId(s(r[3]), n(r[4])))
        for (r in arr(rows["revisions"])) {
            val ps = parents[s(r[0]) + "\u0000" + s(r[1]) + "\u0000" + n(r[2])] ?: LinkedHashSet()
            revs.getOrPut(s(r[0])) { LinkedHashMap() }[OpId(s(r[1]), n(r[2]))] = RevisionEvent(s(r[3]), n(r[4]), ps)
        }
        val items = LinkedHashMap<String, ItemState>()
        val sets = LinkedHashMap<String, LinkedHashMap<String, OrSet>>()
        val playlists = LinkedHashMap<String, EntityState>()
        val spaces = LinkedHashMap<String, EntityState>()
        for (r in arr(rows["present"])) {
            val k1 = s(r[1])
            val k2 = s(r[2])
            when (s(r[0])) {
                "item" -> {
                    val b = orSets[key("item", k1, "")]
                    items[k1] = ItemState(
                        exists = orSetOf("item", k1, ""),
                        fields = fieldsOf("item", k1),
                        revisions = revs[k1] ?: emptyMap(),
                        deletedHeads = b?.heads ?: emptySet(),
                    )
                }
                "set" -> sets.getOrPut(k1) { LinkedHashMap() }[k2] = orSetOf("set", k1, k2)
                "playlist" -> playlists[k1] = EntityState(orSetOf("playlist", k1, ""), fieldsOf("playlist", k1))
                "space" -> spaces[k1] = EntityState(orSetOf("space", k1, ""), fieldsOf("space", k1))
            }
        }
        val entries = LinkedHashMap<String, EntryFacts>()
        for (r in arr(rows["entries"])) entries[s(r[0])] = EntryFacts(s(r[1]), s(r[2]), OpId(s(r[3]), n(r[4])))
        val entryKey = LinkedHashMap<String, Register>()
        for ((k1, byK2) in regsByScope["entryKey"] ?: emptyMap<String, LinkedHashMap<String, Register>>()) for ((_, v) in byK2) entryKey[k1] = v
        val placements = LinkedHashMap<String, LinkedHashMap<String, Register>>()
        for ((k1, byK2) in regsByScope["placement"] ?: emptyMap<String, LinkedHashMap<String, Register>>()) placements[k1] = byK2
        val places = LinkedHashMap<String, LinkedHashMap<String, Register>>()
        for ((k1, byK2) in regsByScope["place"] ?: emptyMap<String, LinkedHashMap<String, Register>>()) places[k1] = byK2
        val profile = LinkedHashMap<String, Register>()
        for ((k1, byK2) in regsByScope["profile"] ?: emptyMap<String, LinkedHashMap<String, Register>>()) for ((_, v) in byK2) profile[k1] = v
        val removed = LinkedHashSet<String>()
        for (r in arr(rows["removedEntries"])) removed.add(s(r[0]))
        val findings = LinkedHashSet<String>()
        for (r in arr(rows["findings"])) findings.add(s(r[0]))
        return State(
            items = items,
            sets = sets,
            playlists = playlists,
            spaces = spaces,
            entries = entries,
            entryKey = entryKey,
            removedEntries = removed,
            placements = placements,
            places = places,
            profile = profile,
            findings = findings,
        )
    }
}
