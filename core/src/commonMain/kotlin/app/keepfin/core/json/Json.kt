package app.keepfin.core.json

// A small, strict JSON reader and writer, so the core needs no serialization library (which would add
// hundreds of KB to the desktop bundle). It behaves like JavaScript's JSON.parse and JSON.stringify
// where the matching code depends on it: objects keep their insertion order and the last duplicate key
// wins, numbers are doubles, \u escapes may form lone surrogates, and output escapes lone surrogates.

internal sealed class JsonValue

internal class JsonObject(val members: Map<String, JsonValue>) : JsonValue()

internal class JsonArray(val items: List<JsonValue>) : JsonValue()

internal class JsonString(val value: String) : JsonValue()

internal class JsonNumber(val value: Double) : JsonValue()

internal class JsonBool(val value: Boolean) : JsonValue()

internal object JsonNull : JsonValue()

internal class JsonException(message: String) : IllegalArgumentException(message)

private const val MAX_DEPTH = 512

/** Parses [text] as one JSON value. Throws [JsonException] on invalid JSON, trailing text or nesting deeper than 512. */
internal fun parseJson(text: String): JsonValue = JsonReader(text).readDocument()

private class JsonReader(private val text: String) {
    private var pos = 0

    fun readDocument(): JsonValue {
        skipSpace()
        val value = readValue(0)
        skipSpace()
        if (pos != text.length) fail("unexpected text after the value")
        return value
    }

    private fun fail(message: String): Nothing = throw JsonException("Invalid JSON at $pos: $message")

    private fun skipSpace() {
        while (pos < text.length) {
            val c = text[pos]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++ else break
        }
    }

    private fun readValue(depth: Int): JsonValue {
        if (depth > MAX_DEPTH) fail("nested too deeply")
        if (pos >= text.length) fail("unexpected end")
        return when (val c = text[pos]) {
            '{' -> readObject(depth)
            '[' -> readArray(depth)
            '"' -> JsonString(readString())
            't' -> literal("true", JsonBool(true))
            'f' -> literal("false", JsonBool(false))
            'n' -> literal("null", JsonNull)
            else -> if (c == '-' || c in '0'..'9') readNumber() else fail("unexpected character")
        }
    }

    private fun literal(word: String, value: JsonValue): JsonValue {
        if (!text.startsWith(word, pos)) fail("unexpected character")
        pos += word.length
        return value
    }

    private fun readObject(depth: Int): JsonValue {
        pos++
        val members = LinkedHashMap<String, JsonValue>()
        skipSpace()
        if (pos < text.length && text[pos] == '}') {
            pos++
            return JsonObject(members)
        }
        while (true) {
            skipSpace()
            if (pos >= text.length || text[pos] != '"') fail("expected a property name")
            val key = readString()
            skipSpace()
            if (pos >= text.length || text[pos] != ':') fail("expected ':'")
            pos++
            skipSpace()
            members[key] = readValue(depth + 1)
            skipSpace()
            if (pos >= text.length) fail("unexpected end")
            when (text[pos++]) {
                ',' -> continue
                '}' -> return JsonObject(members)
                else -> fail("expected ',' or '}'")
            }
        }
    }

    private fun readArray(depth: Int): JsonValue {
        pos++
        val items = ArrayList<JsonValue>()
        skipSpace()
        if (pos < text.length && text[pos] == ']') {
            pos++
            return JsonArray(items)
        }
        while (true) {
            skipSpace()
            items.add(readValue(depth + 1))
            skipSpace()
            if (pos >= text.length) fail("unexpected end")
            when (text[pos++]) {
                ',' -> continue
                ']' -> return JsonArray(items)
                else -> fail("expected ',' or ']'")
            }
        }
    }

    private fun readString(): String {
        pos++ // opening quote
        val out = StringBuilder()
        while (true) {
            if (pos >= text.length) fail("unterminated string")
            val c = text[pos++]
            when {
                c == '"' -> return out.toString()
                c < ' ' -> fail("control character in string")
                c != '\\' -> out.append(c)
                else -> {
                    if (pos >= text.length) fail("unterminated string")
                    when (val e = text[pos++]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\u0008')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> out.append(readHex4())
                        else -> fail("bad escape \\$e")
                    }
                }
            }
        }
    }

    private fun readHex4(): Char {
        if (pos + 4 > text.length) fail("bad \\u escape")
        var code = 0
        for (i in 0 until 4) {
            val c = text[pos + i]
            val digit = when (c) {
                in '0'..'9' -> c - '0'
                in 'a'..'f' -> c - 'a' + 10
                in 'A'..'F' -> c - 'A' + 10
                else -> fail("bad \\u escape")
            }
            code = code * 16 + digit
        }
        pos += 4
        return code.toChar()
    }

    private fun readNumber(): JsonValue {
        val start = pos
        if (text[pos] == '-') pos++
        if (pos >= text.length) fail("bad number")
        if (text[pos] == '0') pos++
        else if (text[pos] in '1'..'9') digits()
        else fail("bad number")
        if (pos < text.length && text[pos] == '.') {
            pos++
            if (!digits()) fail("bad number")
        }
        if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
            pos++
            if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
            if (!digits()) fail("bad number")
        }
        return JsonNumber(text.substring(start, pos).toDouble())
    }

    private fun digits(): Boolean {
        val start = pos
        while (pos < text.length && text[pos] in '0'..'9') pos++
        return pos > start
    }
}

private const val HEX = "0123456789abcdef"

/** A JSON string literal for [value], escaped like JavaScript's JSON.stringify (lone surrogates become \udXXX). */
internal fun jsonQuote(value: String): String {
    val out = StringBuilder(value.length + 2)
    out.append('"')
    var i = 0
    while (i < value.length) {
        val c = value[i]
        when {
            c == '"' -> backslash(out, '"')
            c == '\\' -> backslash(out, '\\')
            c == '\u0008' -> backslash(out, 'b')
            c == '\u000C' -> backslash(out, 'f')
            c == '\n' -> backslash(out, 'n')
            c == '\r' -> backslash(out, 'r')
            c == '\t' -> backslash(out, 't')
            c < ' ' -> escapeUnit(out, c)
            c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate() -> {
                out.append(c).append(value[i + 1])
                i++
            }
            c.isHighSurrogate() || c.isLowSurrogate() -> escapeUnit(out, c)
            else -> out.append(c)
        }
        i++
    }
    out.append('"')
    return out.toString()
}

private fun backslash(out: StringBuilder, escaped: Char) {
    out.append('\\').append(escaped)
}

private fun escapeUnit(out: StringBuilder, c: Char) {
    val code = c.code
    out.append('\\').append('u')
    for (shift in intArrayOf(12, 8, 4, 0)) out.append(HEX[(code shr shift) and 15])
}
