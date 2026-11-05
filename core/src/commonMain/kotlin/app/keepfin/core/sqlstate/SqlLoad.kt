package app.keepfin.core.sqlstate

import app.keepfin.core.sync.OpId
import app.keepfin.core.sync.OrSet
import app.keepfin.core.sync.Register
import app.keepfin.core.sync.State
import app.keepfin.core.sync.encodeCanonical
import app.keepfin.core.sync.projectItem
import app.keepfin.core.sync.sortedEntries

/** Receives the rows of a state in the column order of the schema (see [emitRows]). */
interface RowSink {
    fun present(scope: String, k1: String, k2: String)
    fun tag(scope: String, k1: String, k2: String, kind: String, actor: String, seq: Long)
    fun reg(scope: String, k1: String, k2: String, wall: Long, counter: Long, hactor: String, oactor: String, oseq: Long, value: String)
    fun revision(item: String, actor: String, seq: Long, content: String, size: Long)
    fun revParent(item: String, actor: String, seq: Long, pactor: String, pseq: Long)
    fun entry(entry: String, playlist: String, item: String, actor: String, seq: Long)
    fun removedEntry(entry: String)
    fun finding(text: String)
    fun itemView(id: String, status: String, fields: String, heads: String, plays: Long, conflict: Long, dch: String)
    fun cycle(item: String)
}

private fun sorted(set: Set<OpId>): Collection<OpId> = if (set.size <= 1) set else set.sorted()

/**
 * Every row of the tables for [s] (typically one shard of a checkpoint), in the order of [SqlRows.shardRowsJson]: each
 * table in primary-key order, so the host inserts sequentially. The rows go straight to [sink]; nothing is quoted or
 * parsed on the way, which is what makes the first load fast.
 */
fun emitRows(s: State, sink: RowSink) {
    fun orTags(scope: String, k1: String, k2: String, o: OrSet, heads: Set<OpId> = emptySet()) {
        for (x in sorted(o.adds)) sink.tag(scope, k1, k2, "a", x.actor, x.seq)
        for (x in sorted(heads)) sink.tag(scope, k1, k2, "h", x.actor, x.seq)
        for (x in sorted(o.removes)) sink.tag(scope, k1, k2, "r", x.actor, x.seq)
    }

    fun regRow(scope: String, k1: String, k2: String, r: Register) {
        sink.reg(scope, k1, k2, r.hlc.wall, r.hlc.counter.toLong(), r.hlc.actor, r.op.actor, r.op.seq, encodeCanonical(r.value))
    }

    for (e in s.entryKey.keys.sorted()) regRow("entryKey", e, "", s.entryKey.getValue(e))

    for (id in s.items.keys.sorted()) {
        val item = s.items.getValue(id)
        sink.present("item", id, "")
        orTags("item", id, "", item.exists, item.deletedHeads)
        for (e in sortedEntries(item.fields)) regRow("item", id, e.key, e.value)
        for (op in sorted(item.revisions.keys)) {
            val e = item.revisions.getValue(op)
            sink.revision(id, op.actor, op.seq, e.content, e.size)
            for (p in sorted(e.parents)) sink.revParent(id, op.actor, op.seq, p.actor, p.seq)
        }
        val p = projectItem(item)
        val status = p.status
        if (status != null) sink.itemView(id, status, p.fields, p.heads, p.plays, if (p.contentConflict) 1 else 0, p.deleteConflictHeads)
        if (p.cycle) sink.cycle(id)
    }

    for (place in s.places.keys.sorted()) for (e in sortedEntries(s.places.getValue(place))) regRow("place", place, e.key, e.value)
    for (item in s.placements.keys.sorted()) for (e in sortedEntries(s.placements.getValue(item))) regRow("placement", item, e.key, e.value)
    for (id in s.playlists.keys.sorted()) {
        val pl = s.playlists.getValue(id)
        sink.present("playlist", id, "")
        orTags("playlist", id, "", pl.exists)
        for (e in sortedEntries(pl.fields)) regRow("playlist", id, e.key, e.value)
    }
    for (e in sortedEntries(s.profile)) regRow("profile", e.key, "", e.value)
    for (name in s.sets.keys.sorted()) {
        val els = s.sets.getValue(name)
        for (el in els.keys.sorted()) {
            sink.present("set", name, el)
            orTags("set", name, el, els.getValue(el))
        }
    }
    for (id in s.spaces.keys.sorted()) {
        val sp = s.spaces.getValue(id)
        sink.present("space", id, "")
        orTags("space", id, "", sp.exists)
        for (e in sortedEntries(sp.fields)) regRow("space", id, e.key, e.value)
    }

    for (e in s.entries.keys.sorted()) {
        val f = s.entries.getValue(e)
        sink.entry(e, f.playlist, f.item, f.op.actor, f.op.seq)
    }
    for (e in s.removedEntries.sorted()) sink.removedEntry(e)
    for (f in s.findings.sorted()) sink.finding(f)
}
