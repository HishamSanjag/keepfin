package app.keepfin.core.sqlstate

// The SqlDriver for node:sqlite (`DatabaseSync`, synchronous). The host hands in the database object; this file only
// converts values at the boundary (see the contract in SqlDriver.kt):
//  - Long -> JS number, refused above 2^53 (never rounded); Int and Double pass as they are; null stays null;
//    ByteArray -> Uint8Array.
//  - rows come back as arrays (setReturnArrays): string -> String, integral number -> Long, other number -> Double,
//    null -> null, Uint8Array -> ByteArray. A bigint (a value above 2^53 that node would only return with readBigInts)
//    cannot appear because node refuses to read such an integer without it; it is refused here as well.

private const val MAX_SAFE = 9007199254740991.0

@Suppress("UNUSED_PARAMETER")
private fun jsApply(f: dynamic, self: dynamic, args: dynamic): dynamic = js("f.apply(self, args)")

@Suppress("UNUSED_PARAMETER")
private fun jsBytes(bytes: ByteArray): dynamic = js("new Uint8Array(bytes.buffer, bytes.byteOffset, bytes.length)")

@Suppress("UNUSED_PARAMETER")
private fun jsIsInteger(x: dynamic): Boolean = js("Number.isInteger(x)")

@Suppress("UNUSED_PARAMETER")
private fun jsTypeOf(x: dynamic): String = js("typeof x")

@Suppress("UNUSED_PARAMETER")
private fun jsIsBytes(x: dynamic): Boolean = js("x instanceof Uint8Array")

@Suppress("UNUSED_PARAMETER")
private fun jsToBytes(x: dynamic): ByteArray = js("new Int8Array(x.buffer.slice(x.byteOffset, x.byteOffset + x.byteLength))")

private fun toJs(params: List<Any?>): dynamic {
    val out: dynamic = js("[]")
    for (p in params) {
        when (p) {
            null -> out.push(null)
            is String -> out.push(p)
            is Long -> {
                val d = p.toDouble()
                if (d > MAX_SAFE || d < -MAX_SAFE) throw IllegalArgumentException("An integer above 2^53 cannot cross to JavaScript exactly")
                out.push(d)
            }
            is Int -> out.push(p)
            is Double -> out.push(p)
            is ByteArray -> out.push(jsBytes(p))
            else -> throw IllegalArgumentException("unsupported SQL parameter")
        }
    }
    return out
}

private fun cell(x: dynamic): Any? = when (jsTypeOf(x)) {
    "string" -> x as String
    "number" -> if (jsIsInteger(x)) (x as Double).toLong() else (x as Double)
    "object" -> if (x == null) null else if (jsIsBytes(x)) jsToBytes(x) else throw IllegalStateException("unsupported SQL value")
    else -> throw IllegalStateException("unsupported SQL value")
}

private fun row(r: dynamic): List<Any?> {
    val n = (r.length as Int)
    val out = ArrayList<Any?>(n)
    for (i in 0 until n) out.add(cell(r[i]))
    return out
}

private class NodeStmt(private val s: dynamic) : SqlStmt {
    init {
        s.setReturnArrays(true)
    }

    override fun run(params: List<Any?>): Long {
        val r = jsApply(s.run, s, toJs(params))
        return (r.changes as Number).toLong()
    }

    override fun rows(params: List<Any?>): List<List<Any?>> {
        val all = jsApply(s.all, s, toJs(params))
        val n = (all.length as Int)
        val out = ArrayList<List<Any?>>(n)
        for (i in 0 until n) out.add(row(all[i]))
        return out
    }

    override fun first(params: List<Any?>): List<Any?>? {
        val r = jsApply(s.get, s, toJs(params))
        return if (r == undefined || r == null) null else row(r)
    }

    override fun close() {
        // node:sqlite finalizes a statement when it is collected or when the database closes
    }
}

/** [database] is a `DatabaseSync` from node:sqlite. */
class NodeSqlDriver(private val database: dynamic) : SqlDriver {
    override fun exec(sql: String) {
        database.exec(sql)
    }

    override fun prepare(sql: String): SqlStmt = NodeStmt(database.prepare(sql))

    override fun close() {
        database.close()
    }
}
