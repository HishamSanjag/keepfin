@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.sqlstate

import app.keepfin.core.sync.Interpretation
import app.keepfin.core.sync.JArr
import app.keepfin.core.sync.JNull
import app.keepfin.core.sync.JNum
import app.keepfin.core.sync.JStr
import app.keepfin.core.sync.Jv
import app.keepfin.core.sync.ByteSink
import app.keepfin.core.sync.Op
import app.keepfin.core.sync.installNativeSha
import app.keepfin.core.sync.OpDecode
import app.keepfin.core.sync.sha256Hex
import app.keepfin.core.sync.JBool
import app.keepfin.core.sync.OpTextReader
import app.keepfin.core.sync.StateWriter
import app.keepfin.core.sync.decodeOp
import app.keepfin.core.sync.encodeCanonical
import app.keepfin.core.sync.jObj
import app.keepfin.core.sync.judgeOp
import app.keepfin.core.sync.utf8LengthOrMinusOne

// The JavaScript surface of the SQL merge state (see gui/shared/core/sync.ts and gui/desktop/sqlstate).

/** The schema: a JSON array of CREATE statements. */
@JsExport
fun sqlStateSchema(): String = encodeCanonical(JArr(SqlState.ddl.map { JStr(it) }))

/** The statement catalog: a JSON array of SQL texts; a statement id is an index into it. */
@JsExport
fun sqlStateCatalog(): String = encodeCanonical(JArr(SqlState.catalog.map { JStr(it) }))

private fun param(p: Any?): Jv = when (p) {
    null -> JNull
    is String -> JStr(p)
    is Long -> JNum(p)
    is Int -> JNum(p.toLong())
    else -> throw IllegalArgumentException("unsupported SQL parameter")
}

/**
 * The statements joining one op line into the tables, as `{"i":[item ids the op touches],"s":[[statement id, params...]]}`,
 * or an empty string when the op does not change the state (malformed, unknown kind, or invalid body).
 */
@JsExport
fun sqlStateOp(opText: String): String {
    val size = utf8LengthOrMinusOne(opText)
    val op = (if (size >= 0) OpTextReader(opText).read() else null)
        ?: (decodeOp(opText) as? OpDecode.Ok)?.op
        ?: return ""
    val judged = judgeOp(op, utf8LengthOrMinusOne(opText).let { if (it < 0) opText.length else it })
    if (judged !is Interpretation.Applied) return ""
    val delta = judged.delta
    val statements = SqlState.deltaToSql(delta).map { st -> JArr(listOf<Jv>(JNum(st.id.toLong())) + st.params.map { param(it) }) }
    return encodeCanonical(jObj("i" to JArr(delta.items.keys.map { JStr(it) }), "s" to JArr(statements)))
}

/** The canonical bytes of the state a set of rows describes (see [SqlState.stateFromRows]). */
@JsExport
fun sqlStateBodyFromRows(rowsJson: String): ByteArray {
    val sink = ByteSink()
    StateWriter.write(sink, SqlState.stateFromRows(rowsJson))
    return sink.buf.copyOf(sink.size)
}

/**
 * The part file (sync spec 10.4) of [shard] for the rows of the tables that belong to that shard (see [SqlState.stateFromRows]),
 * with the library's [coverageJson] (actor to highest contiguous seq) and the largest clock [maxHlc]. Null for a name that is not a
 * shard. The host selects the rows by shard; the bytes equal what `partBytes` of an in-memory log with the same state gives.
 */
@JsExport
fun sqlStatePartFromRows(rowsJson: String, library: String, shard: String, coverageJson: String, maxHlc: String?): ByteArray? {
    val index = app.keepfin.core.sync.shardIndexOfName(shard)
    if (index < 0) return null
    installNativeSha()
    val coverage = LinkedHashMap<String, Long>()
    for ((actor, n) in (app.keepfin.core.sync.parseLoose(coverageJson) as app.keepfin.core.sync.JObj).members) coverage[actor] = (n as JNum).value
    val clock = maxHlc?.let { app.keepfin.core.sync.Hlc.parse(it) }
    return app.keepfin.core.sync.encodeFile(library, coverage, clock, app.keepfin.core.sync.SEMANTICS, SqlState.stateFromRows(rowsJson), shard, index, true)
}

/** The per-connection helper tables (JSON array of CREATE statements). */
@JsExport
fun sqlStateSessionSchema(): String = encodeCanonical(JArr(SqlState.sessionDdl.map { JStr(it) }))

/** The named statements the host runs: a JSON object from name to SQL (admission bookkeeping, projection inputs, songs). */
@JsExport
fun sqlStateQueries(): String = encodeCanonical(app.keepfin.core.sync.JObj(SqlState.queries.mapValues { JStr(it.value) }))

/**
 * Judges one op line for admission, with no state: `{"m":"<problem>"}` when it is malformed, else
 * `{a: actor, q: seq, k: 0 applied | 1 kept | 2 invalid, r: reason (invalid only), z: canonical size in bytes,
 * w: clock wire, h: sha-256 hex (only when the text is not plain UTF-8, so the host must not hash it itself),
 * i: item ids touched, s: statements (applied only)}`.
 */
@JsExport
fun sqlStateAdmit(opText: String): String {
    var size = utf8LengthOrMinusOne(opText)
    var op: Op? = if (size >= 0) OpTextReader(opText).read() else null
    var hash: String? = null
    if (op == null) {
        val bytes = opText.encodeToByteArray()
        when (val d = decodeOp(bytes)) {
            is OpDecode.Bad -> return encodeCanonical(jObj("m" to JStr(d.problem.name)))
            is OpDecode.Ok -> op = d.op
        }
        size = bytes.size
        hash = sha256Hex(bytes)
    }
    val judged = judgeOp(op, size)
    val members = LinkedHashMap<String, Jv>()
    members["a"] = JStr(op.id.actor)
    members["q"] = JNum(op.id.seq)
    members["z"] = JNum(size.toLong())
    members["w"] = JStr(op.hlc.wire())
    if (hash != null) members["h"] = JStr(hash)
    when (judged) {
        is Interpretation.Applied -> {
            members["k"] = JNum(0)
            members["i"] = JArr(judged.delta.items.keys.map { JStr(it) })
            members["s"] = JArr(SqlState.deltaToSql(judged.delta).map { st -> JArr(listOf<Jv>(JNum(st.id.toLong())) + st.params.map { param(it) }) })
        }
        is Interpretation.Unknown -> members["k"] = JNum(1)
        is Interpretation.Invalid -> {
            members["k"] = JNum(2)
            members["r"] = JStr(judged.reason)
        }
    }
    return encodeCanonical(app.keepfin.core.sync.JObj(members))
}

/** The shared item projection for the touched items (see [SqlRows.projectItemsJson]). */
@JsExport
fun sqlStateProject(inputJson: String): String = SqlRows.projectItemsJson(inputJson)

/** Streams checkpoint parts into rows, one shard at a time (see [PartsLoader]). */
@JsExport
class JsSqlLoader internal constructor(library: String) {
    private val loader = PartsLoader(library)

    init {
        installNativeSha() // part checksums are over megabytes
    }

    /** Keeps a part (reading only its header); returns its shard name, or null when it is not a part of this library. */
    fun add(bytes: ByteArray): String? = loader.add(bytes)

    fun planJson(): String = loader.planJson()

    /** The rows of one shard as JSON (see [SqlRows.shardRowsJson]), or null when a part of it cannot be read. */
    fun shardRows(shard: String): String? = loader.shardRows(shard)
}

@JsExport
fun sqlStateLoader(library: String): JsSqlLoader = JsSqlLoader(library)
