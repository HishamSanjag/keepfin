package app.keepfin.core.sync

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonException
import app.keepfin.core.json.JsonNull
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.json.jsonQuote
import app.keepfin.core.json.parseJson
import kotlin.math.abs
import kotlin.math.floor

/** A JSON value in the sync format. Numbers are integers only, within +-(2^53-1). */
sealed class Jv

object JNull : Jv() {
    override fun toString() = "null"
}

data class JBool(val value: Boolean) : Jv()

data class JNum(val value: Long) : Jv() {
    init {
        require(value >= -MAX_SAFE_INTEGER && value <= MAX_SAFE_INTEGER) { "number out of range: $value" }
    }
}

data class JStr(val value: String) : Jv()

data class JArr(val items: List<Jv>) : Jv()

data class JObj(val members: Map<String, Jv>) : Jv()

fun jObj(vararg pairs: Pair<String, Jv>): JObj = JObj(mapOf(*pairs))

/** Canonical JSON text: keys sorted by UTF-16 code unit, no whitespace, integers only. */
fun encodeCanonical(value: Jv): String {
    val sb = StringBuilder()
    writeCanonical(sb, value)
    return sb.toString()
}

/**
 * Appends `jsonQuote(s)`. A string with nothing to escape (no control character, quote, backslash or surrogate) is
 * appended whole; anything else goes through [jsonQuote], so the text is always identical.
 */
internal fun appendQuoted(sb: StringBuilder, s: String) {
    for (i in 0 until s.length) {
        val c = s[i].code
        if (c < 0x20 || c == 34 || c == 92 || (c >= 0xD800 && c <= 0xDFFF)) {
            sb.append(jsonQuote(s))
            return
        }
    }
    sb.append('"').append(s).append('"')
}

/** The entries of [m] ordered by key (UTF-16 code unit order), the order canonical JSON writes them in. */
internal fun <V> sortedEntries(m: Map<String, V>): List<Map.Entry<String, V>> {
    val list = ArrayList<Map.Entry<String, V>>(m.size)
    list.addAll(m.entries)
    val n = list.size
    if (n > 16) {
        list.sortWith { a, b -> a.key.compareTo(b.key) }
    } else {
        // insertion sort: bodies and field maps are tiny, and this allocates nothing
        for (i in 1 until n) {
            val x = list[i]
            var j = i - 1
            while (j >= 0 && list[j].key > x.key) {
                list[j + 1] = list[j]
                j--
            }
            list[j + 1] = x
        }
    }
    return list
}

internal fun writeCanonical(sb: StringBuilder, v: Jv) {
    when (v) {
        is JNull -> sb.append("null")
        is JBool -> sb.append(if (v.value) "true" else "false")
        is JNum -> sb.append(v.value.toString())
        is JStr -> appendQuoted(sb, v.value)
        is JArr -> {
            sb.append('[')
            v.items.forEachIndexed { i, item ->
                if (i > 0) sb.append(',')
                writeCanonical(sb, item)
            }
            sb.append(']')
        }
        is JObj -> {
            sb.append('{')
            var first = true
            for (e in sortedEntries(v.members)) {
                if (!first) sb.append(',')
                first = false
                appendQuoted(sb, e.key)
                sb.append(':')
                writeCanonical(sb, e.value)
            }
            sb.append('}')
        }
    }
}

enum class CanonicalProblem { MalformedUtf8, Bom, Syntax, BadNumber, NotCanonical, DuplicateKey }

sealed class CanonicalResult {
    class Ok(val value: Jv) : CanonicalResult()
    class Bad(val problem: CanonicalProblem) : CanonicalResult()
}

/** Parse without requiring canonical form; null on bad syntax or a number that is not an in-range integer. */
internal fun parseLoose(text: String): Jv? {
    val raw = try {
        parseJson(text)
    } catch (e: JsonException) {
        return null
    }
    return convert(raw)
}

private fun convert(v: JsonValue): Jv? = when (v) {
    is JsonNull -> JNull
    is JsonBool -> JBool(v.value)
    is JsonString -> JStr(v.value)
    is JsonNumber -> {
        val d = v.value
        if (d == floor(d) && abs(d) <= 9007199254740991.0) JNum(d.toLong()) else null
    }
    is JsonArray -> {
        val items = ArrayList<Jv>(v.items.size)
        var ok = true
        for (item in v.items) {
            val c = convert(item)
            if (c == null) { ok = false; break }
            items.add(c)
        }
        if (ok) JArr(items) else null
    }
    is JsonObject -> {
        val members = LinkedHashMap<String, Jv>()
        var ok = true
        for ((k, item) in v.members) {
            val c = convert(item)
            if (c == null) { ok = false; break }
            members[k] = c
        }
        if (ok) JObj(members) else null
    }
}

/** The canonical bytes: UTF-8 of [encodeCanonical], no BOM. */
fun encodeCanonicalBytes(value: Jv): ByteArray = encodeCanonical(value).encodeToByteArray()

/** Strict UTF-8 decode (malformed sequences are an error, never replaced). Null when the bytes are malformed. */
internal fun strictUtf8(bytes: ByteArray): String? = try {
    bytes.decodeToString(throwOnInvalidSequence = true)
} catch (e: Exception) {
    null
}

internal fun hasBomBytes(bytes: ByteArray): Boolean =
    bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()

/**
 * Strict decode from bytes: valid UTF-8, no BOM, parses, and re-encoding gives exactly the original bytes.
 * That also rejects duplicate keys, non-integer numbers, other number spellings and other escapes.
 */
fun decodeCanonicalBytes(bytes: ByteArray): CanonicalResult {
    if (hasBomBytes(bytes)) return CanonicalResult.Bad(CanonicalProblem.Bom)
    val text = strictUtf8(bytes) ?: return CanonicalResult.Bad(CanonicalProblem.MalformedUtf8)
    val raw = try {
        parseJson(text)
    } catch (e: JsonException) {
        return CanonicalResult.Bad(CanonicalProblem.Syntax)
    }
    val value = convert(raw) ?: return CanonicalResult.Bad(CanonicalProblem.BadNumber)
    if (encodeCanonicalBytes(value).contentEquals(bytes)) return CanonicalResult.Ok(value)
    return CanonicalResult.Bad(if (hasDuplicateKeys(text)) CanonicalProblem.DuplicateKey else CanonicalProblem.NotCanonical)
}

/** Strict decode of text (UTF-8 validity is then not in question). */
fun decodeCanonical(text: String): CanonicalResult = decodeCanonicalBytes(text.encodeToByteArray())

/** How deep [v] nests (a scalar is 0, an object or array adds 1). */
internal fun jsonDepth(v: Jv): Int {
    var deepest = 0
    when (v) {
        is JArr -> for (x in v.items) { val d = jsonDepth(x); if (d > deepest) deepest = d }
        is JObj -> for (x in v.members.values) { val d = jsonDepth(x); if (d > deepest) deepest = d }
        else -> return 0
    }
    return 1 + deepest
}

/** True when [text] (already valid JSON) has an object with the same key twice. */
internal fun hasDuplicateKeys(text: String): Boolean {
    val stack = ArrayList<HashSet<String>?>()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when (c) {
            '{' -> { stack.add(HashSet()); i++ }
            '[' -> { stack.add(null); i++ }
            '}', ']' -> { if (stack.isNotEmpty()) stack.removeAt(stack.size - 1); i++ }
            '"' -> {
                var j = i + 1
                while (j < text.length && text[j] != '"') j += if (text[j] == '\\') 2 else 1
                var k = j + 1
                while (k < text.length && (text[k] == ' ' || text[k] == '\t' || text[k] == '\n' || text[k] == '\r')) k++
                val top = if (stack.isEmpty()) null else stack[stack.size - 1]
                if (top != null && k < text.length && text[k] == ':') {
                    val key = (parseJson(text.substring(i, j + 1)) as JsonString).value
                    if (!top.add(key)) return true
                }
                i = j + 1
            }
            else -> i++
        }
    }
    return false
}
