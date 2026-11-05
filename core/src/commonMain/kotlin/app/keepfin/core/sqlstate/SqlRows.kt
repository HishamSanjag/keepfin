package app.keepfin.core.sqlstate

import app.keepfin.core.sync.CheckpointPart
import app.keepfin.core.sync.EntityState
import app.keepfin.core.sync.Hlc
import app.keepfin.core.sync.ItemState
import app.keepfin.core.sync.JArr
import app.keepfin.core.sync.JObj
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.OpId
import app.keepfin.core.sync.OrSet
import app.keepfin.core.sync.PartResult
import app.keepfin.core.sync.Register
import app.keepfin.core.sync.RevisionEvent
import app.keepfin.core.sync.SEMANTICS
import app.keepfin.core.sync.SHARD_IDS
import app.keepfin.core.sync.State
import app.keepfin.core.sync.StateAccumulator
import app.keepfin.core.sync.appendQuoted
import app.keepfin.core.sync.decodeCanonical
import app.keepfin.core.sync.CanonicalResult
import app.keepfin.core.sync.decodePart
import app.keepfin.core.sync.encodeCanonical
import app.keepfin.core.sync.sortedEntries
import app.keepfin.core.sync.isUuid
import app.keepfin.core.sync.parseLoose
import app.keepfin.core.sync.projectItem
import app.keepfin.core.sync.shardIndexOfName

// Rows for the bulk loader and the shared item projection (no I/O here: JSON text in, JSON text out).

private fun cmpOp(a: OpId, b: OpId): Int = a.compareTo(b)

/** Writes JSON rows of strings, integers and nulls without building trees (a library has millions of them). */
private class RowsOut {
    val sb = StringBuilder()
    private var first = true

    fun start(): RowsOut {
        sb.append('[')
        first = true
        return this
    }

    fun end(): String {
        sb.append(']')
        return sb.toString()
    }

    /** Starts the next row: returns the buffer to append the row's JSON to. */
    fun next(): StringBuilder {
        if (!first) sb.append(',')
        first = false
        return sb
    }

    fun row(vararg cells: Any?) {
        if (!first) sb.append(',')
        first = false
        sb.append('[')
        for (i in cells.indices) {
            if (i > 0) sb.append(',')
            when (val c = cells[i]) {
                null -> sb.append("null")
                is String -> appendQuoted(sb, c)
                is Long -> sb.append(c.toString())
                is Int -> sb.append(c.toString())
                else -> throw IllegalArgumentException("unsupported cell")
            }
        }
        sb.append(']')
    }
}

/** Quotes of repeated strings (an actor id appears in nearly every row), so each is escaped once. */
private class QuoteCache {
    private var a: String? = null
    private var qa = ""
    private var b: String? = null
    private var qb = ""

    fun of(s: String): String {
        if (s === a || s == a) return qa
        if (s === b || s == b) {
            val t = qb
            b = a
            qb = qa
            a = s
            qa = t
            return t
        }
        val sbq = StringBuilder(s.length + 2)
        appendQuoted(sbq, s)
        b = a
        qb = qa
        a = s
        qa = sbq.toString()
        return qa
    }
}

private fun sortedIds(set: Set<OpId>): Collection<OpId> = if (set.size <= 1) set else set.sorted()

object SqlRows {
    /**
     * Every row of the tables for [s] (typically one shard of a checkpoint), as
     * `{present, tags, regs, revisions, revParents, entries, removedEntries, findings, itemView, cycles}` in the
     * column order of the schema, each table in primary-key order within its scope (so the host inserts sequentially).
     */
    fun shardRowsJson(s: State): String {
        val present = RowsOut().start()
        val tags = RowsOut().start()
        val regs = RowsOut().start()
        val revisions = RowsOut().start()
        val revParents = RowsOut().start()
        val entries = RowsOut().start()
        val removed = RowsOut().start()
        val findings = RowsOut().start()
        val view = RowsOut().start()
        val cycles = RowsOut().start()
        val quotes = QuoteCache()

        // one row of tags: ["scope", k1, k2, "kind", actor, seq]
        fun tagRow(scope: String, qk1: String, qk2: String, kind: String, id: OpId) {
            val sb = tags.next()
            sb.append("[\"").append(scope).append("\",").append(qk1).append(',').append(qk2).append(",\"").append(kind).append("\",").append(quotes.of(id.actor)).append(',').append(id.seq.toString()).append(']')
        }

        fun orTags(scope: String, qk1: String, qk2: String, o: OrSet, heads: Set<OpId> = emptySet()) {
            for (x in sortedIds(o.adds)) tagRow(scope, qk1, qk2, "a", x)
            for (x in sortedIds(heads)) tagRow(scope, qk1, qk2, "h", x)
            for (x in sortedIds(o.removes)) tagRow(scope, qk1, qk2, "r", x)
        }

        fun regRow(scope: String, qk1: String, qk2: String, r: Register) {
            val sb = regs.next()
            sb.append("[\"").append(scope).append("\",").append(qk1).append(',').append(qk2).append(',')
            sb.append(r.hlc.wall.toString()).append(',').append(r.hlc.counter.toString()).append(',').append(quotes.of(r.hlc.actor)).append(',')
            sb.append(quotes.of(r.op.actor)).append(',').append(r.op.seq.toString()).append(',')
            appendQuoted(sb, encodeCanonical(r.value))
            sb.append(']')
        }

        fun q(x: String): String {
            val b = StringBuilder(x.length + 2)
            appendQuoted(b, x)
            return b.toString()
        }

        val empty = "\"\""
        // entry keys (scope entryKey comes first in key order)
        for (e in s.entryKey.keys.sorted()) regRow("entryKey", q(e), empty, s.entryKey.getValue(e))

        // items: one pass over the sorted ids feeds present, tags, regs, revisions, revParents, item_view and cycles
        for (id in s.items.keys.sorted()) {
            val item = s.items.getValue(id)
            val qid = q(id)
            present.next().append("[\"item\",").append(qid).append(",\"\"]")
            orTags("item", qid, empty, item.exists, item.deletedHeads)
            for (e in sortedEntries(item.fields)) regRow("item", qid, q(e.key), e.value)
            for (op in sortedIds(item.revisions.keys)) {
                val e = item.revisions.getValue(op)
                revisions.next().append('[').append(qid).append(',').append(quotes.of(op.actor)).append(',').append(op.seq.toString()).append(',').also { appendQuoted(it, e.content) }.append(',').append(e.size.toString()).append(']')
                for (p in sortedIds(e.parents)) {
                    revParents.next().append('[').append(qid).append(',').append(quotes.of(op.actor)).append(',').append(op.seq.toString()).append(',').append(quotes.of(p.actor)).append(',').append(p.seq.toString()).append(']')
                }
            }
            val p = projectItem(item)
            val status = p.status
            if (status != null) {
                val sb = view.next()
                sb.append('[').append(qid).append(",\"").append(status).append("\",")
                appendQuoted(sb, p.fields)
                sb.append(',')
                appendQuoted(sb, p.heads)
                sb.append(',').append(p.plays.toString()).append(',').append(if (p.contentConflict) "1" else "0").append(',')
                appendQuoted(sb, p.deleteConflictHeads)
                sb.append(']')
            }
            if (p.cycle) cycles.next().append('[').append(qid).append(']')
        }

        // the remaining registers and the other scopes (key order: place, placement, playlist, profile, space; then sets)
        for (place in s.places.keys.sorted()) for (e in sortedEntries(s.places.getValue(place))) regRow("place", q(place), q(e.key), e.value)
        for (item in s.placements.keys.sorted()) for (e in sortedEntries(s.placements.getValue(item))) regRow("placement", q(item), q(e.key), e.value)
        for (id in s.playlists.keys.sorted()) {
            val qid = q(id)
            val pl = s.playlists.getValue(id)
            present.next().append("[\"playlist\",").append(qid).append(",\"\"]")
            orTags("playlist", qid, empty, pl.exists)
            for (e in sortedEntries(pl.fields)) regRow("playlist", qid, q(e.key), e.value)
        }
        for (e in sortedEntries(s.profile)) regRow("profile", q(e.key), empty, e.value)
        for (name in s.sets.keys.sorted()) {
            val qn = q(name)
            val els = s.sets.getValue(name)
            for (el in els.keys.sorted()) {
                val qe = q(el)
                present.next().append("[\"set\",").append(qn).append(',').append(qe).append(']')
                orTags("set", qn, qe, els.getValue(el))
            }
        }
        for (id in s.spaces.keys.sorted()) {
            val qid = q(id)
            val sp = s.spaces.getValue(id)
            present.next().append("[\"space\",").append(qid).append(",\"\"]")
            orTags("space", qid, empty, sp.exists)
            for (e in sortedEntries(sp.fields)) regRow("space", qid, q(e.key), e.value)
        }

        for (e in s.entries.keys.sorted()) {
            val f = s.entries.getValue(e)
            entries.next().append('[').append(q(e)).append(',').append(q(f.playlist)).append(',').append(q(f.item)).append(',').append(quotes.of(f.op.actor)).append(',').append(f.op.seq.toString()).append(']')
        }
        for (e in s.removedEntries.sorted()) removed.next().append('[').append(q(e)).append(']')
        for (f in s.findings.sorted()) findings.next().append('[').append(q(f)).append(']')

        return "{\"present\":" + present.end() + ",\"tags\":" + tags.end() + ",\"regs\":" + regs.end() +
            ",\"revisions\":" + revisions.end() + ",\"revParents\":" + revParents.end() + ",\"entries\":" + entries.end() +
            ",\"removedEntries\":" + removed.end() + ",\"findings\":" + findings.end() + ",\"itemView\":" + view.end() +
            ",\"cycles\":" + cycles.end() + "}"
    }

    // ---- the shared item projection, fed the rows of the touched items ----

    private fun cells(v: app.keepfin.core.sync.Jv?): List<app.keepfin.core.sync.Jv> = (v as? JArr)?.items ?: emptyList()

    /**
     * [inputJson] is `{ids, tags, fields, revisions, parents}`: the rows of the listed items as the queries
     * `projTags`, `projFields`, `projRevisions` and `projParents` give them (arrays of cells, in column order).
     * The answer is a JSON array with one `[id, status or null, fields, heads, plays, conflict, deleteConflictHeads,
     * cycle]` row per id: status null means the item is unknown (the host removes its projection row).
     */
    fun projectItemsJson(inputJson: String): String {
        val m = (parseLoose(inputJson) as JObj).members
        fun rows(key: String): List<List<Any?>> = cells(m[key]).map { r ->
            (r as JArr).items.map { c ->
                when (c) {
                    is JStr -> c.value
                    is app.keepfin.core.sync.JNum -> c.value
                    else -> null
                }
            }
        }
        val ids = cells(m["ids"]).map { (it as JStr).value }
        val out = RowsOut().start()
        for (row in projectRows(ids, rows("tags"), rows("fields"), rows("revisions"), rows("parents"))) out.row(*row.toTypedArray())
        return out.end()
    }

    /**
     * The same projection over rows already read from the database (cells are String, Long or null): one row
     * `[id, status or null, fields, heads, plays, conflict, deleteConflictHeads, cycle]` per id, in the order of [ids].
     */
    fun projectRows(ids: List<String>, tagRows: List<List<Any?>>, fieldRows: List<List<Any?>>, revisionRows: List<List<Any?>>, parentRows: List<List<Any?>>): List<List<Any?>> {
        val adds = HashMap<String, LinkedHashSet<OpId>>()
        val removes = HashMap<String, LinkedHashSet<OpId>>()
        val heads = HashMap<String, LinkedHashSet<OpId>>()
        for (c in tagRows) {
            val id = c[0] as String
            val op = OpId(c[2] as String, num(c[3]))
            val into = when (c[1] as String) { "a" -> adds; "r" -> removes; else -> heads }
            into.getOrPut(id) { LinkedHashSet() }.add(op)
        }
        val fields = HashMap<String, LinkedHashMap<String, Register>>()
        for (c in fieldRows) {
            val id = c[0] as String
            // only the value matters to the projection; the clock and op id are placeholders
            fields.getOrPut(id) { LinkedHashMap() }[c[1] as String] = Register(Hlc(0, 0, ""), OpId("", 0), parseLoose(c[2] as String)!!)
        }
        val parents = HashMap<String, LinkedHashSet<OpId>>()
        for (c in parentRows) {
            val key = (c[0] as String) + "\u0000" + (c[1] as String) + "\u0000" + num(c[2])
            parents.getOrPut(key) { LinkedHashSet() }.add(OpId(c[3] as String, num(c[4])))
        }
        val revs = HashMap<String, LinkedHashMap<OpId, RevisionEvent>>()
        for (c in revisionRows) {
            val item = c[0] as String
            val op = OpId(c[1] as String, num(c[2]))
            val ps = parents[item + "\u0000" + op.actor + "\u0000" + op.seq] ?: LinkedHashSet()
            revs.getOrPut(item) { LinkedHashMap() }[op] = RevisionEvent(c[3] as String, num(c[4]), ps)
        }
        val out = ArrayList<List<Any?>>(ids.size)
        for (id in ids) {
            val item = ItemState(
                exists = OrSet(adds[id] ?: emptySet(), removes[id] ?: emptySet()),
                fields = fields[id] ?: emptyMap(),
                revisions = revs[id] ?: emptyMap(),
                deletedHeads = heads[id] ?: emptySet(),
            )
            val p = projectItem(item)
            out.add(listOf(id, p.status, p.fields, p.heads, p.plays, if (p.contentConflict) 1 else 0, p.deleteConflictHeads, if (p.cycle) 1 else 0))
        }
        return out
    }

    private fun num(v: Any?): Long = when (v) {
        is Long -> v
        is Int -> v.toLong()
        else -> throw IllegalArgumentException("expected an integer cell")
    }
}
/**
 * Streams a checkpoint's parts into rows one shard at a time (sync spec 10.4): [add] reads each part's header only,
 * [plan] gives the merged coverage, and [shardRows] decodes and merges just one shard, so the whole state is never
 * held at once. Unreadable parts are ignored, as a reader must.
 */
class PartsLoader(private val library: String) {
    private class Entry(val shard: Int, val source: PartSource)

    private val entries = ArrayList<Entry>()
    private val coverages = Array(SHARD_IDS.size) { LinkedHashMap<String, Long>() }
    private val present = BooleanArray(SHARD_IDS.size)
    private val highest = LinkedHashMap<String, Long>()
    private var maxHlc: Hlc? = null

    /** Reads the header line of [bytes] and keeps the part when it is a part of this library and semantics. Returns its shard or null. */
    fun add(bytes: ByteArray): String? = add(BytesPart(bytes))

    /**
     * The same for a part that is read on demand: only [PartSource.head] is read now, and the whole part when its shard is built, so
     * a library whose parts add up to hundreds of megabytes is never in memory at once.
     */
    fun add(source: PartSource): String? {
        val bytes = source.head()
        var n1 = -1
        var n2 = -1
        for (i in bytes.indices) {
            if (bytes[i] == 10.toByte()) {
                if (n1 < 0) n1 = i else { n2 = i; break }
            }
            if (i > 4096) break
        }
        if (n1 < 0 || n2 < 0 || bytes.decodeToString(0, n1) != "KFCKP 1") return null
        val r = decodeCanonical(bytes.decodeToString(n1 + 1, n2)) as? CanonicalResult.Ok ?: return null
        val h = (r.value as? JObj)?.members ?: return null
        if ((h["library"] as? JStr)?.value != library) return null
        if ((h["semantics"] as? app.keepfin.core.sync.JNum)?.value != SEMANTICS.toLong()) return null
        val shard = shardIndexOfName((h["part"] as? JStr)?.value ?: return null)
        if (shard < 0) return null
        val cov = (h["coverage"] as? JObj)?.members ?: return null
        for ((actor, n) in cov) {
            val v = (n as? app.keepfin.core.sync.JNum)?.value ?: return null
            if (!isUuid(actor) || v < 1) return null
            if (v > (coverages[shard][actor] ?: 0L)) coverages[shard][actor] = v
            if (v > (highest[actor] ?: 0L)) highest[actor] = v
        }
        (h["maxHlc"] as? JStr)?.let { Hlc.parse(it.value) }?.let { if (maxHlc == null || it > maxHlc!!) maxHlc = it }
        present[shard] = true
        entries.add(Entry(shard, source))
        return SHARD_IDS[shard]
    }

    /** The merged view of the parts added so far (see [plan]). */
    class Plan(val coverage: Map<String, Long>, val highest: Map<String, Long>, val maxHlc: String?, val shards: List<String>)

    /**
     * The library coverage (the minimum over the 17 shards, a missing shard counting as 0), the highest coverage any part
     * claims per actor, the largest clock, and the shards that have parts.
     */
    fun plan(): Plan {
        val coverage = LinkedHashMap<String, Long>()
        for (actor in highest.keys) {
            var low = Long.MAX_VALUE
            for (i in SHARD_IDS.indices) {
                val v = if (present[i]) (coverages[i][actor] ?: 0L) else 0L
                if (v < low) low = v
            }
            if (low > 0 && low != Long.MAX_VALUE) coverage[actor] = low
        }
        return Plan(coverage, highest, maxHlc?.wire(), SHARD_IDS.indices.filter { present[it] }.map { SHARD_IDS[it] })
    }

    /** `{coverage, highest, maxHlc, shards}` as JSON (see [plan]). */
    fun planJson(): String {
        val p = plan()
        return encodeCanonical(
            app.keepfin.core.sync.jObj(
                "coverage" to JObj(p.coverage.mapValues { app.keepfin.core.sync.JNum(it.value) }),
                "highest" to JObj(p.highest.mapValues { app.keepfin.core.sync.JNum(it.value) }),
                "maxHlc" to (p.maxHlc?.let { JStr(it) } ?: app.keepfin.core.sync.JNull),
                "shards" to JArr(p.shards.map { JStr(it) }),
            ),
        )
    }

    /** The decoded and merged state of [shard], or null when a part is unreadable or there is none. */
    fun shardState(shard: String): State? {
        val index = shardIndexOfName(shard)
        val states = ArrayList<State>()
        for (e in entries) {
            if (e.shard != index) continue
            val r = decodePart(e.source.bytes(), library)
            if (r !is PartResult.Ok) return null
            states.add(r.part.checkpoint.state)
        }
        if (states.isEmpty()) return null
        return if (states.size == 1) states[0] else StateAccumulator().also { acc -> for (st in states) acc.add(st) }.snapshot()
    }

    /** The rows of [shard] (see [SqlRows.shardRowsJson]) after decoding and merging its parts, or null when a part is unreadable. */
    fun shardRows(shard: String): String? = shardState(shard)?.let { SqlRows.shardRowsJson(it) }
}

/** A checkpoint part that can be read in two steps: its first bytes (the header lines), then the whole of it. */
interface PartSource {
    /** At least the first 4,200 bytes (or the whole part when it is shorter). */
    fun head(): ByteArray

    fun bytes(): ByteArray
}

/** A part already in memory. */
class BytesPart(private val bytes: ByteArray) : PartSource {
    override fun head(): ByteArray = bytes
    override fun bytes(): ByteArray = bytes
}
