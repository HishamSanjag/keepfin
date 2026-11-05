package app.keepfin.core.sqlstate

// The SQL the readers run over the merge state's tables (the reads of the desktop's views, the checkpoint shard rows and
// the test dumps). One text for desktop and Android, as the admission statements are. The texts that need a parameter
// use positional `?` (or `?1`/`?2` where noted); a host binds them in order.

object SqlViews {
    /** SQL for "this OR-set key has an add that no remove names": the key's `present`. */
    fun live(scope: String, k1: String, k2: String): String =
        "EXISTS (SELECT 1 FROM tags a WHERE a.scope = $scope AND a.k1 = $k1 AND a.k2 = $k2 AND a.kind = 'a' " +
            "AND NOT EXISTS (SELECT 1 FROM tags r WHERE r.scope = a.scope AND r.k1 = a.k1 AND r.k2 = a.k2 AND r.kind = 'r' AND r.actor = a.actor AND r.seq = a.seq))"

    /** Every item projection row: id, status, fields, heads, plays, conflict, dch. */
    const val ITEM_VIEWS = "SELECT id, status, fields, heads, plays, conflict, dch FROM item_view"

    /** One item projection row (param: the id). */
    const val ITEM_VIEW_ONE = "SELECT id, status, fields, heads, plays, conflict, dch FROM item_view WHERE id = ?"

    /** Every projection row with the item's placements as a JSON object (the last column, null when it has none). */
    const val ITEM_VIEWS_WITH_PLACEMENTS =
        "SELECT v.id AS id, v.status AS status, v.fields AS fields, v.heads AS heads, v.plays AS plays, v.conflict AS conflict, v.dch AS dch, " +
            "(SELECT json_group_object(p.k2, json(p.value)) FROM regs p WHERE p.scope = 'placement' AND p.k1 = v.id) AS pl FROM item_view v"

    /** Playlists or spaces that exist and have at least one add: id, present. Param ?1 is the scope. Append `ORDER BY p.k1` or `AND p.k1 = ?2`. */
    val CONTAINERS: String =
        "SELECT p.k1 AS id, ${live("p.scope", "p.k1", "''")} AS present FROM present p WHERE p.scope = ?1 " +
            "AND EXISTS (SELECT 1 FROM tags a WHERE a.scope = p.scope AND a.k1 = p.k1 AND a.k2 = '' AND a.kind = 'a') "

    /** The fields of a container (params: scope, id): name, value. */
    const val CONTAINER_FIELDS = "SELECT k2 AS name, value FROM regs WHERE scope = ? AND k1 = ?"

    /** The live members of sets: name, element. [where] filters `p` (the present row of the set element). */
    fun members(where: String): String =
        "SELECT p.k1 AS name, p.k2 AS el FROM present p WHERE p.scope = 'set' AND $where AND ${live("p.scope", "p.k1", "p.k2")} ORDER BY p.k1"

    /** The registers of a scope, in order (param: scope): k1, k2, value. */
    const val REGISTERS_ALL = "SELECT k1, k2, value FROM regs WHERE scope = ? ORDER BY k1, k2"

    /** The registers of one key of a scope (params: scope, k1): k1, k2, value. */
    const val REGISTERS_OF = "SELECT k1, k2, value FROM regs WHERE scope = ? AND k1 = ?"

    const val PROFILE = "SELECT k1, value FROM regs WHERE scope = 'profile'"

    /** The live add-tags of an OR-set key (params: scope, k1, k2): actor, seq. */
    const val LIVE_TAGS =
        "SELECT a.actor AS actor, a.seq AS seq FROM tags a WHERE a.scope = ? AND a.k1 = ? AND a.k2 = ? AND a.kind = 'a' " +
            "AND NOT EXISTS (SELECT 1 FROM tags r WHERE r.scope = a.scope AND r.k1 = a.k1 AND r.k2 = a.k2 AND r.kind = 'r' AND r.actor = a.actor AND r.seq = a.seq)"

    const val REVISION_EVENTS = "SELECT actor, seq FROM revisions WHERE item = ?"
    const val REVISION_PARENTS = "SELECT pactor, pseq FROM rev_parents WHERE item = ?"

    /** The tables as rows (every table in the order of its schema), for rebuilding the State in tests and tools. */
    val STATE_ROWS: Map<String, String> = linkedMapOf(
        "present" to "SELECT scope, k1, k2 FROM present",
        "tags" to "SELECT scope, k1, k2, kind, actor, seq FROM tags",
        "regs" to "SELECT scope, k1, k2, wall, counter, hactor, oactor, oseq, value FROM regs",
        "revisions" to "SELECT item, actor, seq, content, size FROM revisions",
        "revParents" to "SELECT item, actor, seq, pactor, pseq FROM rev_parents",
        "entries" to "SELECT entry, playlist, item, actor, seq FROM entries",
        "removedEntries" to "SELECT entry FROM removed_entries",
        "findings" to "SELECT text FROM findings",
    )

    /** The rows of the tables that belong to one shard (every row of the shard's keys), in the column order of the schema. */
    fun shardRows(shard: String): Map<String, String> {
        val hex = if (shard.length == 1) "0123456789abcdef".indexOf(shard) else -1
        if (shard != "meta" && hex < 0) throw IllegalArgumentException("Not a checkpoint shard: $shard")
        val meta = shard == "meta"
        fun sharded(scopes: String) = "scope IN ($scopes) AND ${shardRange(shard, "k1")}"
        val metaScopes = "'set', 'playlist', 'space'"
        fun where(itemScopes: String, metaOnly: String) = if (meta) "(${sharded(itemScopes)} OR scope IN ($metaOnly))" else sharded(itemScopes)
        return linkedMapOf(
            "present" to "SELECT scope, k1, k2 FROM present WHERE ${where("'item'", metaScopes)}",
            "tags" to "SELECT scope, k1, k2, kind, actor, seq FROM tags WHERE ${where("'item'", metaScopes)}",
            "regs" to "SELECT scope, k1, k2, wall, counter, hactor, oactor, oseq, value FROM regs WHERE ${where("'item', 'placement', 'entryKey'", "'set', 'playlist', 'space', 'place', 'profile'")}",
            "revisions" to "SELECT item, actor, seq, content, size FROM revisions WHERE ${shardRange(shard, "item")}",
            "revParents" to "SELECT item, actor, seq, pactor, pseq FROM rev_parents WHERE ${shardRange(shard, "item")}",
            "entries" to "SELECT entry, playlist, item, actor, seq FROM entries WHERE ${shardRange(shard, "entry")}",
            "removedEntries" to "SELECT entry FROM removed_entries WHERE ${shardRange(shard, "entry")}",
            "findings" to if (meta) "SELECT text FROM findings WHERE text NOT GLOB 'entry-conflict:[0-9a-f]*'" else "SELECT text FROM findings WHERE text GLOB 'entry-conflict:$shard*'",
        )
    }
}
