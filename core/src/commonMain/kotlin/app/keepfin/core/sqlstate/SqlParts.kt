package app.keepfin.core.sqlstate

import app.keepfin.core.sync.FORMAT
import app.keepfin.core.sync.SEMANTICS
import app.keepfin.core.sync.appendQuoted
import app.keepfin.core.sync.sha256Hex

// Checkpoint parts (sync spec 10.4) written straight from the SQL tables.
//
// The bytes are the ones the core writes for the same state (`partBytes` of an in-memory log; the views tests compare
// them byte for byte), but the state is never built: the rows of one shard are read in key order and written as the
// canonical JSON line of the part body, one item at a time.
//
// Canonical form (spec section 8): no spaces, object keys in UTF-16 code unit order, integers only. The register values
// are kept in the tables as their canonical text, so they are copied as they are.
//
// The job runs in steps ([PartJob.step]) so a single-threaded host (the desktop's main thread, or Android's store
// thread between messages) can hand the thread back now and then; no write may happen in the database between steps.

private typealias Row = List<Any?>

/** The rows whose first cell is a key, in key order, taken group by group. */
private class Rows(private val all: List<Row>) {
    private var at = 0

    /** The rows whose first cell is [key] (skipping any that sort before it). */
    fun group(key: String): List<Row> {
        while (at < all.size && (all[at][0] as String) < key) at++
        val from = at
        while (at < all.size && all[at][0] == key) at++
        return all.subList(from, at)
    }

    fun peekKey(): String? = if (at < all.size) all[at][0] as String else null
}

private fun q(text: String): String {
    val sb = StringBuilder(text.length + 2)
    appendQuoted(sb, text)
    return sb.toString()
}

// The queries hand the database\'s own text for every number (a register\'s clock, an op id as `actor:seq`): the part is
// mostly digits, and formatting 64-bit integers in the host language costs more than the whole read on JavaScript.
private const val REG_COLS = "printf('%013d.%06d.%s', wall, counter, hactor), oactor || ':' || oseq, value"
private const val OP = "actor || ':' || seq"

private fun byKey(a: Pair<String, String>, b: Pair<String, String>) = a.first.compareTo(b.first)

/** `{"hlc":...,"op":...,"value":...}` for a register row (clock, op id and value as text from column [at], see [REG_COLS]). */
private fun reg(row: Row, at: Int): String = "{\"hlc\":\"${row[at]}\",\"op\":\"${row[at + 1]}\",\"value\":${row[at + 2]}}"

/** `{"name":reg,...}` for the register rows of one key, names in UTF-16 order. */
private fun regMap(rows: List<Row>, nameAt: Int, valueAt: Int): String {
    val parts = rows.map { (it[nameAt] as String) to reg(it, valueAt) }.toMutableList()
    if (parts.size > 1) parts.sortWith { a, b -> byKey(a, b) }
    return parts.joinToString(",", "{", "}") { "${q(it.first)}:${it.second}" }
}

/** `[ids]` of the tag rows of one kind, in the order of the rows (actor, then seq as a number). */
private fun ids(rows: List<Row>, kind: String, at: Int): String {
    val out = ArrayList<String>()
    for (r in rows) if (r[at] == kind) out.add("\"${r[at + 1]}\"")
    return out.joinToString(",", "[", "]")
}

private fun orSet(rows: List<Row>, at: Int) = "{\"adds\":${ids(rows, "a", at)},\"removes\":${ids(rows, "r", at)}}"

/** The ranges of the first key of a shard: one hex digit, or everything else (`meta`). */
internal fun shardRange(shard: String, col: String): String {
    if (shard == "meta") return "($col < '0' OR ($col >= ':' AND $col < 'a') OR $col >= 'g')"
    val upper = when (shard) {
        "9" -> ":"
        "f" -> "g"
        else -> (shard[0] + 1).toString()
    }
    return "$col >= '$shard' AND $col < '$upper'"
}

/** The sub-ranges of a shard: its ids by the next hex digit (null = the whole shard, for the meta shard). */
private fun subRanges(shard: String): List<Char?> = if (shard == "meta") listOf(null) else "0123456789abcdef".toList()

/** The ids of [shard] whose next hex digit is [digit]. */
private fun subRange(shard: String, digit: Char, col: String): String {
    val upper = when (digit) {
        '9' -> ":"
        'f' -> "g"
        else -> (digit + 1).toString()
    }
    return "$col >= '$shard$digit' AND $col < '$shard$upper'"
}

private fun groupBy(rows: List<Row>): Map<String, List<Row>> {
    val out = LinkedHashMap<String, MutableList<Row>>()
    for (r in rows) out.getOrPut(r[0] as String) { ArrayList() }.add(r)
    return out
}

private class Ticker {
    private var since = 0

    /** Counts [weight] units of work and hands the thread back every so often. */
    fun add(weight: Int): Boolean {
        since += weight
        if (since < 20_000) return false
        since = 0
        return true
    }
}

private suspend fun SequenceScope<Unit>.tick(ticker: Ticker, weight: Int) {
    if (ticker.add(weight)) yield(Unit)
}

private const val SPILL_CHARS = 256 * 1024

/** One part file being written. Call [step] until it returns true, then take [bytes]. */
class PartJob(
    private val db: SqlDriver,
    private val library: String,
    private val shard: String,
    private val coverage: Map<String, Long>,
    private val maxHlc: String?,
) {
    private var result: ByteArray? = null
    private val steps: Iterator<Unit> = sequence<Unit> { build() }.iterator()

    /** Runs until the next point where the thread may be handed back. True when the part is finished. */
    fun step(): Boolean {
        if (result != null) return true
        if (!steps.hasNext()) return result != null
        steps.next()
        return false
    }

    fun bytes(): ByteArray = result ?: throw IllegalStateException("The part is not finished")

    private fun rows(sql: String): List<Row> {
        val s = db.prepare(sql)
        try {
            return s.rows(emptyList())
        } finally {
            s.close()
        }
    }

    private suspend fun SequenceScope<Unit>.build() {
        val ticker = Ticker()
        val meta = shard == "meta"
        val inShard = shardRange(shard, "k1")
        val chunks = StringBuilder()
        // the text goes to UTF-8 in pieces as it is written, so a large part is never held as text, a String copy and bytes at once (200k items on a phone)
        val spilled = ArrayList<ByteArray>()
        fun spill(all: Boolean = false) {
            if (chunks.isEmpty() || (!all && chunks.length < SPILL_CHARS)) return
            spilled += utf8Bytes(chunks.toString())
            chunks.clear()
        }

        // entries: {"<entry>":{"item":..,"op":..,"playlist":..}}
        chunks.append("{\"entries\":{")
        if (true) {
            var first = true
            for (r in rows("SELECT entry, playlist, item, $OP FROM entries WHERE ${shardRange(shard, "entry")} ORDER BY entry")) {
                chunks.append(if (first) "" else ",").append(q(r[0] as String)).append(":{\"item\":").append(q(r[2] as String)).append(",\"op\":\"").append(r[3]).append("\",\"playlist\":").append(q(r[1] as String)).append('}')
                first = false
                tick(ticker, 3)
                spill()
            }
        }
        // entryKey: registers keyed by entry
        chunks.append("},\"entryKey\":{")
        if (true) {
            var first = true
            for (r in rows("SELECT k1, $REG_COLS FROM regs WHERE scope = 'entryKey' AND $inShard ORDER BY k1")) {
                chunks.append(if (first) "" else ",").append(q(r[0] as String)).append(':').append(reg(r, 1))
                first = false
                tick(ticker, 3)
                spill()
            }
        }
        // findings
        if (true) {
            val texts = rows(if (meta) "SELECT text FROM findings WHERE text NOT GLOB 'entry-conflict:[0-9a-f]*'" else "SELECT text FROM findings WHERE text GLOB 'entry-conflict:$shard*'")
                .map { it[0] as String }.sorted()
            chunks.append("},\"findings\":[").append(texts.joinToString(",") { q(it) }).append("],\"items\":{")
        }
        // items, one at a time: the cursors run in step over the sorted ids
        if (true) {
            var first = true
            // one sub-range of the shard at a time (the next hex digit of the id): the rows held are a sixteenth of the shard's, so a part
            // of 12,500 items does not hold all its register rows at once. The order is the same: ids sort by their text.
            for (sub in subRanges(shard)) {
            val inSub = if (sub == null) inShard else subRange(shard, sub, "k1")
            val inSubItem = if (sub == null) shardRange(shard, "item") else subRange(shard, sub, "item")
            val present = rows("SELECT k1 FROM present WHERE scope = 'item' AND $inSub ORDER BY k1")
            val tags = Rows(rows("SELECT k1, kind, $OP FROM tags WHERE scope = 'item' AND $inSub AND k2 = '' ORDER BY k1, kind, actor, seq"))
            val regs = Rows(rows("SELECT k1, k2, $REG_COLS FROM regs WHERE scope = 'item' AND $inSub ORDER BY k1, k2"))
            val revisions = Rows(rows("SELECT item, $OP, content, CAST(size AS TEXT) FROM revisions WHERE $inSubItem ORDER BY item, actor, seq"))
            val parents = Rows(rows("SELECT item, $OP, pactor || ':' || pseq FROM rev_parents WHERE $inSubItem ORDER BY item, actor, seq, pactor, pseq"))
            for (p in present) {
                val id = p[0] as String
                val tagRows = tags.group(id)
                val regRows = regs.group(id)
                val revRows = revisions.group(id)
                val parentRows = parents.group(id)
                var revisionsText = ""
                if (revRows.isNotEmpty()) {
                    val byParent = HashMap<String, MutableList<String>>()
                    for (r in parentRows) byParent.getOrPut(r[1] as String) { ArrayList() }.add("\"${r[2]}\"")
                    val events = revRows.map { r ->
                        val key = r[1] as String
                        key to "{\"content\":${q(r[2] as String)},\"parents\":[${(byParent[key] ?: emptyList<String>()).joinToString(",")}],\"size\":${r[3]}}"
                    }.toMutableList()
                    if (events.size > 1) events.sortWith { a, b -> byKey(a, b) } // the keys are op ids as text, in text order
                    revisionsText = events.joinToString(",") { "${q(it.first)}:${it.second}" }
                }
                chunks.append(if (first) "" else ",").append(q(id)).append(":{\"deletedHeads\":").append(ids(tagRows, "h", 1)).append(",\"exists\":").append(orSet(tagRows, 1))
                    .append(",\"fields\":").append(regMap(regRows, 1, 2)).append(",\"revisions\":{").append(revisionsText).append("}}")
                first = false
                tick(ticker, 2 + tagRows.size + regRows.size * 2 + revRows.size + parentRows.size)
                spill()
            }
            }
        }
        // placements: {"<item>":{"<place>":reg}}
        chunks.append("},\"placements\":{")
        if (true) {
            val rows = Rows(rows("SELECT k1, k2, $REG_COLS FROM regs WHERE scope = 'placement' AND $inShard ORDER BY k1, k2"))
            var first = true
            while (true) {
                val id = rows.peekKey() ?: break
                chunks.append(if (first) "" else ",").append(q(id)).append(':').append(regMap(rows.group(id), 1, 2))
                first = false
                tick(ticker, 3)
                spill()
            }
        }
        // the keys of the other scopes live in the meta shard only
        if (meta) {
            chunks.append("},\"places\":{")
            chunks.append(groupedRegs("place"))
            chunks.append("},\"playlists\":{")
            chunks.append(entities("playlist"))
            chunks.append("},\"profile\":")
            chunks.append(regMap(rows("SELECT k1, $REG_COLS FROM regs WHERE scope = 'profile'"), 0, 1))
        } else {
            chunks.append("},\"places\":{},\"playlists\":{},\"profile\":{}")
        }
        // removed entries
        chunks.append(",\"removedEntries\":[").append(rows("SELECT entry FROM removed_entries WHERE ${shardRange(shard, "entry")} ORDER BY entry").joinToString(",") { q(it[0] as String) }).append(']')
        // sets and spaces (meta shard only)
        chunks.append(",\"sets\":{")
        if (meta) chunks.append(sets())
        chunks.append("},\"spaces\":{")
        if (meta) chunks.append(entities("space"))
        chunks.append("}}\n")

        spill(all = true)
        val body = ByteArray(spilled.sumOf { it.size })
        var at = 0
        for (piece in spilled) { piece.copyInto(body, at); at += piece.size }
        spilled.clear()
        val actors = coverage.keys.sorted()
        val header = "{\"coverage\":{${actors.joinToString(",") { "${q(it)}:${coverage.getValue(it)}" }}},\"library\":${q(library)},\"maxHlc\":${if (maxHlc == null) "null" else q(maxHlc)}," +
            "\"part\":${q(shard)},\"semantics\":$SEMANTICS,\"sha256\":\"${sha256Hex(body)}\"}"
        val head = utf8Bytes("KFCKP $FORMAT\n$header\n")
        val out = ByteArray(head.size + body.size)
        head.copyInto(out)
        body.copyInto(out, head.size)
        result = out
    }

    /** `{"<k1>":{"<k2>":reg}}` for one register scope. */
    private fun groupedRegs(scope: String): String {
        val byKey = groupBy(rows("SELECT k1, k2, $REG_COLS FROM regs WHERE scope = '$scope'"))
        return byKey.keys.sorted().joinToString(",") { key -> "${q(key)}:${regMap(byKey.getValue(key), 1, 2)}" }
    }

    /** `{"<id>":{"exists":{...},"fields":{...}}}` for playlists or spaces. */
    private fun entities(scope: String): String {
        val tagRows = groupBy(rows("SELECT k1, kind, $OP FROM tags WHERE scope = '$scope' ORDER BY k1, kind, actor, seq"))
        val regRows = groupBy(rows("SELECT k1, k2, $REG_COLS FROM regs WHERE scope = '$scope'"))
        val idList = rows("SELECT k1 FROM present WHERE scope = '$scope'").map { it[0] as String }.sorted()
        return idList.joinToString(",") { id -> "${q(id)}:{\"exists\":${orSet(tagRows[id] ?: emptyList(), 1)},\"fields\":${regMap(regRows[id] ?: emptyList(), 1, 2)}}" }
    }

    /** `{"<set>":{"<element>":{"adds":..,"removes":..}}}`. */
    private fun sets(): String {
        val tagRows = HashMap<String, MutableList<Row>>()
        for (r in rows("SELECT k1, k2, kind, $OP FROM tags WHERE scope = 'set' ORDER BY k1, k2, kind, actor, seq")) tagRows.getOrPut("${r[0]}\u0000${r[1]}") { ArrayList() }.add(r)
        val bySet = LinkedHashMap<String, MutableList<String>>()
        for (r in rows("SELECT k1, k2 FROM present WHERE scope = 'set'")) bySet.getOrPut(r[0] as String) { ArrayList() }.add(r[1] as String)
        return bySet.keys.sorted().joinToString(",") { name ->
            val elements = bySet.getValue(name).sorted()
            "${q(name)}:{${elements.joinToString(",") { el -> "${q(el)}:${orSet(tagRows["$name\u0000$el"] ?: emptyList(), 2)}" }}}"
        }
    }
}
