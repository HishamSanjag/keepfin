@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.sqlstate

import app.keepfin.core.sync.JArr
import app.keepfin.core.sync.JBool
import app.keepfin.core.sync.JNull
import app.keepfin.core.sync.JNum
import app.keepfin.core.sync.JObj
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.Jv
import app.keepfin.core.sync.encodeCanonical
import app.keepfin.core.sync.installNativeSha
import app.keepfin.core.sync.parseLoose

// The JavaScript surface of the shared merge state (SqlStateDb) over node:sqlite (see gui/desktop/sqlstate/sqlstate.ts,
// the thin adapter that opens the database files and calls this). Results that are not plain strings or numbers cross
// as canonical JSON text; byte arrays as Int8Array.

private fun nodeSha256(): (String) -> String {
    val crypto: dynamic = js("process.getBuiltinModule('node:crypto')")
    return { text -> crypto.createHash("sha256").update(text, "utf8").digest("hex") as String }
}

/** The files of the merge state: the TypeScript adapter opens, deletes and renames them. */
private class JsHost(private val h: dynamic) : SqlHost {
    override fun open(path: String): SqlDriver = NodeSqlDriver(h.open(path))

    override fun remove(path: String) {
        h.remove(path)
    }

    override fun rename(from: String, to: String) {
        h.rename(from, to)
    }

    override fun residentMb(): Double = (h.residentMb() as Number).toDouble()
}

private fun jsonLongs(m: Map<String, Long>): String = encodeCanonical(JObj(m.mapValues { JNum(it.value) }))

private fun strings(list: Collection<String>) = JArr(list.map { JStr(it) })

private fun longsOf(text: String): Map<String, Long> {
    val out = LinkedHashMap<String, Long>()
    for ((k, v) in (parseLoose(text) as JObj).members) out[k] = (v as JNum).value
    return out
}

private fun stringList(v: Jv?): List<String> = ((v as? JArr)?.items ?: emptyList()).map { (it as JStr).value }

@JsExport
class JsPartJob internal constructor(private val job: PartJob) {
    /** Runs until the next point where the thread may be handed back; true when the part is finished. */
    fun step(): Boolean = job.step()

    fun bytes(): ByteArray = job.bytes()
}

@JsExport
class JsSqlStateDb internal constructor(private val inner: SqlStateDb) {
    /** [signer] is the device id of the verified signature of the segment the ops came in (null: unsigned). */
    fun admit(ops: Array<String>, signer: String? = null): Array<String> = inner.admit(ops.asList(), signer).toTypedArray()

    /** The membership the admitted member ops define. */
    fun membershipJson(): String = app.keepfin.core.sync.membershipJson(inner.membership)

    fun refreshItems(ids: Array<String>) = inner.refreshItems(ids.asList())

    fun getMeta(key: String): String? = inner.getMeta(key)

    fun setMeta(key: String, value: String) = inner.setMeta(key, value)

    fun checkpoint(truncate: Boolean): Double = inner.checkpoint(truncate).toDouble()

    fun rebuildRequired(): Boolean = inner.rebuildRequired

    fun dirtyShards(): Int = inner.dirtyShards

    fun markShardsClean(mask: Int) = inner.markShardsClean(mask)

    fun close() = inner.close()

    /** `{all, items, playlists, spaces, sets, places, profile, findings}` (see [SqlStateDb.takeTouched]). */
    fun takeTouched(): String {
        val t = inner.takeTouched()
        return encodeCanonical(
            JObj(
                mapOf(
                    "all" to JBool(t.all), "items" to strings(t.items), "playlists" to strings(t.playlists), "spaces" to strings(t.spaces),
                    "sets" to strings(t.sets), "places" to strings(t.places), "profile" to JBool(t.profile), "findings" to JBool(t.findings),
                ),
            ),
        )
    }

    fun versionVector(): String = jsonLongs(inner.versionVector())

    fun pending(): String = encodeCanonical(JObj(inner.pending().mapValues { (_, seqs) -> JArr(seqs.map { JNum(it) }) }))

    fun setAside(): String = jsonLongs(inner.setAside())

    fun maxHlc(): String? = inner.maxHlc()

    fun findings(): Array<String> = inner.findings().toTypedArray()

    fun checkpointCoverage(): String? = inner.checkpointCoverage()?.let { jsonLongs(it) }

    fun partJob(library: String, shard: String, coverageJson: String): JsPartJob = JsPartJob(inner.partJob(library, shard, longsOf(coverageJson)))

    /** [queryJson] is `{items?, playlists?, spaces?, setElements?: [[set, element]]}`; the answer is `SyncObserved` as JSON. */
    fun observed(queryJson: String): String {
        val m = (parseLoose(queryJson) as JObj).members
        val pairs = ((m["setElements"] as? JArr)?.items ?: emptyList()).map { val p = (it as JArr).items; (p[0] as JStr).value to (p[1] as JStr).value }
        val o = inner.observed(stringList(m["items"]), stringList(m["playlists"]), stringList(m["spaces"]), pairs)
        return encodeCanonical(
            JObj(
                mapOf(
                    "items" to JObj(o.items.mapValues { (_, v) -> JObj(mapOf("tags" to strings(v.tags), "heads" to strings(v.heads))) }),
                    "playlists" to JObj(o.playlists.mapValues { strings(it.value) }),
                    "spaces" to JObj(o.spaces.mapValues { strings(it.value) }),
                    "setElements" to JObj(o.setElements.mapValues { (_, els) -> JObj(els.mapValues { strings(it.value) }) }),
                ),
            ),
        )
    }

    /** Replaces the database by one built from checkpoint parts; the answer is `{ok, rows, ms, peakRssMb, coreMs, insertMs}` as JSON. */
    fun bulkLoad(parts: Array<ByteArray>, library: String, equivocationsJson: String): String {
        installNativeSha()
        val r = inner.bulkLoad(parts.asList(), library, longsOf(equivocationsJson))
        return encodeCanonical(
            JObj(
                mapOf(
                    "ok" to JBool(r.ok), "rows" to JNum(r.rows), "ms" to JNum(r.ms.toLong()), "peakRssMb" to JNum((r.peakRssMb * 10).toLong()),
                    "coreMs" to JNum(r.coreMs.toLong()), "insertMs" to JNum(r.insertMs.toLong()),
                ),
            ),
        )
    }

    /** [ops] is any JavaScript iterable of op lines (it is read once, one line at a time). */
    fun rebuild(ops: dynamic, signers: dynamic = undefined) {
        inner.rebuild(lines(ops), optionalLines(signers))
    }

    /** The first import (see [SqlStateDb.importFresh]); null when the database is not empty. */
    fun importFresh(ops: dynamic, signers: dynamic = undefined): Array<String>? = inner.importFresh(lines(ops), optionalLines(signers))?.toTypedArray()

    /** An iterable of device ids (or null for an unsigned segment), parallel to the op lines; undefined when all are unsigned. */
    private fun optionalLines(signers: dynamic): Iterator<String?>? {
        if (signers == undefined || signers == null) return null
        val iterator: dynamic = signers[js("Symbol.iterator")]()
        return object : Iterator<String?> {
            private var step: dynamic = iterator.next()
            override fun hasNext(): Boolean = step.done != true
            override fun next(): String? {
                val v: dynamic = step.value
                step = iterator.next()
                return if (v == null || v == undefined) null else v as String
            }
        }
    }

    private fun lines(ops: dynamic): Iterator<String> {
        val iterator: dynamic = ops[js("Symbol.iterator")]()
        return object : Iterator<String> {
            private var step: dynamic = iterator.next()

            override fun hasNext(): Boolean = step.done != true

            override fun next(): String {
                val value = step.value as String
                step = iterator.next()
                return value
            }
        }
    }
}

/**
 * Opens the merge state at [path] through [host] (`open(path)` giving a node:sqlite `DatabaseSync`, `remove(path)`,
 * `rename(from, to)`, `residentMb()`). [catalogOverrideJson] is `{"<id>": "<sql>"}` or an empty string.
 */
@JsExport
fun sqlStateDbOpen(host: dynamic, path: String, synchronous: String, cacheMb: Int, mmapMb: Int, bulkPageSize: Int, catalogOverrideJson: String): JsSqlStateDb {
    installNativeSha()
    val override = if (catalogOverrideJson.isEmpty()) emptyMap() else (parseLoose(catalogOverrideJson) as JObj).members.entries.associate { it.key.toInt() to (it.value as JStr).value }
    val options = SqlStateOptions(synchronous = synchronous, catalogOverride = override, cacheMb = if (cacheMb > 0) cacheMb else null, mmapMb = mmapMb, bulkPageSize = bulkPageSize)
    return JsSqlStateDb(SqlStateDb(path, JsHost(host), options, nodeSha256()))
}

/** The SQL texts of the readers (see [SqlViews]) as a JSON object. */
@JsExport
fun sqlStateViewQueries(): String = encodeCanonical(
    JObj(
        mapOf(
            "itemViews" to JStr(SqlViews.ITEM_VIEWS),
            "itemViewOne" to JStr(SqlViews.ITEM_VIEW_ONE),
            "itemViewsWithPlacements" to JStr(SqlViews.ITEM_VIEWS_WITH_PLACEMENTS),
            "containers" to JStr(SqlViews.CONTAINERS),
            "containerFields" to JStr(SqlViews.CONTAINER_FIELDS),
            "membersAll" to JStr(SqlViews.members("1 = 1")),
            "membersNamed" to JStr(SqlViews.members("p.k1 = ?")),
            "membersRange" to JStr(SqlViews.members("p.k1 >= ? AND p.k1 < ?")),
            "registersAll" to JStr(SqlViews.REGISTERS_ALL),
            "registersOf" to JStr(SqlViews.REGISTERS_OF),
            "profile" to JStr(SqlViews.PROFILE),
            "stateRows" to JObj(SqlViews.STATE_ROWS.mapValues { JStr(it.value) }),
        ),
    ),
)

/** The SQL of the rows that belong to one checkpoint shard, by table (see [SqlViews.shardRows]), as a JSON object. */
@JsExport
fun sqlStateShardQueries(shard: String): String = encodeCanonical(JObj(SqlViews.shardRows(shard).mapValues { JStr(it.value) }))
