package app.keepfin.core.cloud

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.json.jsonQuote

// Phase 4 (dev-docs/plans/KEEPFIN-CLOUD-SPEC.md): the cloud providers' logic is pure. Everything crosses the core's door as JSON
// text (CloudApi.call), so the JavaScript facade, the fixtures and Android's OkHttp executor all use one entry point.

/** Read access to parsed JSON input; a missing key reads as an empty value. */
internal class J(val v: JsonValue?) {
    operator fun get(key: String): J = J((v as? JsonObject)?.members?.get(key))
    operator fun get(index: Int): J = J((v as? JsonArray)?.items?.getOrNull(index))
    val str: String? get() = (v as? JsonString)?.value
    val long: Long? get() = (v as? JsonNumber)?.value?.toLong()
    val bool: Boolean? get() = (v as? JsonBool)?.value
    val list: List<J> get() = (v as? JsonArray)?.items?.map { J(it) } ?: emptyList()
    val isObject: Boolean get() = v is JsonObject
    val isPresent: Boolean get() = v != null && v !is app.keepfin.core.json.JsonNull
    val keys: List<String> get() = (v as? JsonObject)?.members?.keys?.toList() ?: emptyList()
    fun has(key: String): Boolean = this[key].isPresent
    fun s(key: String): String? = this[key].str
    fun l(key: String): Long? = this[key].long

    /** The member as a number whether it is written as a JSON number or as a string (Google writes sizes as strings). */
    fun numberLike(key: String): Long? {
        val x = this[key]
        return x.long ?: x.str?.toLongOrNull()
    }

    fun need(key: String): String = s(key) ?: throw IllegalArgumentException("'$key' is missing")
    fun needLong(key: String): Long = l(key) ?: throw IllegalArgumentException("'$key' is missing")
}

/** Serialises maps, lists, strings, numbers, booleans and null in insertion order (the output of every cloud call). */
internal fun jsonOut(value: Any?): String {
    val sb = StringBuilder()
    writeOut(sb, value)
    return sb.toString()
}

private fun writeOut(sb: StringBuilder, value: Any?) {
    when (value) {
        null -> sb.append("null")
        is String -> sb.append(jsonQuote(value))
        is Boolean -> sb.append(if (value) "true" else "false")
        is Int -> sb.append(value)
        is Long -> sb.append(value)
        is Double -> if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 9.0e15) sb.append(value.toLong()) else sb.append(value)
        is Map<*, *> -> {
            sb.append('{')
            var first = true
            for ((k, v) in value) {
                if (!first) sb.append(',')
                first = false
                sb.append(jsonQuote(k as String)).append(':')
                writeOut(sb, v)
            }
            sb.append('}')
        }
        is List<*> -> {
            sb.append('[')
            var first = true
            for (v in value) {
                if (!first) sb.append(',')
                first = false
                writeOut(sb, v)
            }
            sb.append(']')
        }
        else -> throw IllegalArgumentException("cannot write ${value::class}")
    }
}

/** A map that skips null values (optional members stay absent from the output). */
internal fun mapOfNotNull(vararg pairs: Pair<String, Any?>): LinkedHashMap<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    for ((k, v) in pairs) if (v != null) out[k] = v
    return out
}

/**
 * A request description. The platform executes it. [auth] says the account's `Authorization` header goes on it (never put the
 * secret in the description: descriptions are journaled and logged). [bodyRef] is a slice of the pinned source file
 * (`offset`, `length`) that the platform reads and sends. [redirect] is "manual" when the platform must hand back a 3xx.
 * [serial] marks a request the platform sends one at a time per account (the account's other requests still run alongside).
 */
internal fun request(
    method: String,
    url: String,
    headers: Map<String, String> = emptyMap(),
    body: String? = null,
    bodyRef: Pair<Long, Long>? = null,
    auth: Boolean = true,
    redirect: String? = null,
    serial: Boolean = false,
): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>()
    out["method"] = method
    out["url"] = url
    out["headers"] = LinkedHashMap(headers)
    out["auth"] = auth
    if (body != null) out["body"] = body
    if (bodyRef != null) out["bodyRef"] = linkedMapOf<String, Any?>("offset" to bodyRef.first, "length" to bodyRef.second)
    if (redirect != null) out["redirect"] = redirect
    if (serial) out["serial"] = true
    return out
}

/** A response as the platform hands it to the core: header names lower-cased. */
internal class Resp(val status: Int, val headers: Map<String, String>, val body: String) {
    fun header(name: String): String? = headers[name.lowercase()]
    val ok: Boolean get() = status in 200..299
    fun json(): J = try {
        J(app.keepfin.core.json.parseJson(body))
    } catch (e: app.keepfin.core.json.JsonException) {
        J(null)
    }

    companion object {
        fun of(r: J): Resp {
            val h = LinkedHashMap<String, String>()
            for (k in r["headers"].keys) h[k.lowercase()] = r["headers"].s(k) ?: ""
            return Resp((r.l("status") ?: 0L).toInt(), h, r.s("body") ?: "")
        }
    }
}
