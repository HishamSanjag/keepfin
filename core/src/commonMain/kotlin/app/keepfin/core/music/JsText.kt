package app.keepfin.core.music

// Text helpers that behave like the JavaScript functions the first (desktop) implementation used.
// Every platform runs the same code, so none of them may depend on a platform's own regex dialect,
// Locale or String.trim().

/** JavaScript's WhiteSpace and LineTerminator set, for use inside a regex character class. */
internal const val JS_WS: String =
    "\t\n\u000B\u000C\r \u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF"

/** Regex for one JavaScript whitespace character. */
internal const val WS: String = "[$JS_WS]"

/** Regex for one character that is not a JavaScript line terminator. */
internal const val NOT_EOL: String = "[^\n\r\u2028\u2029]"

internal fun isJsWhitespace(c: Char): Boolean =
    c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r' || c == ' ' || c == '\u00A0' ||
        c == '\u1680' || c in '\u2000'..'\u200A' || c == '\u2028' || c == '\u2029' || c == '\u202F' ||
        c == '\u205F' || c == '\u3000' || c == '\uFEFF'

/** JavaScript String.trim(). Kotlin's trim() strips a different set of characters on the JVM. */
internal fun jsTrim(value: String): String {
    var start = 0
    var end = value.length
    while (start < end && isJsWhitespace(value[start])) start++
    while (end > start && isJsWhitespace(value[end - 1])) end--
    return value.substring(start, end)
}

/** Lower-cases ASCII letters only, like an ASCII-only case-insensitive match. */
internal fun asciiLower(value: String): String {
    val out = StringBuilder(value.length)
    for (c in value) out.append(if (c in 'A'..'Z') c + 32 else c)
    return out.toString()
}

/** A regex fragment that matches [letters] ignoring ASCII case, without the engine's ignore-case flag. */
internal fun ci(letters: String): String {
    val out = StringBuilder()
    for (c in letters) {
        if (c in 'a'..'z') out.append('[').append(c).append(c - 32).append(']') else out.append(c)
    }
    return out.toString()
}

/** JavaScript's word-boundary character class: ASCII letters, digits and underscore only. */
private fun isJsWordChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

/**
 * Same result as a regex word-boundary match of [word] in [text] in JavaScript, where [word] starts and
 * ends with an ASCII letter or digit.
 */
internal fun containsJsWord(text: String, word: String): Boolean {
    var from = 0
    while (true) {
        val at = text.indexOf(word, from)
        if (at < 0) return false
        val end = at + word.length
        val before = at == 0 || !isJsWordChar(text[at - 1])
        val after = end == text.length || !isJsWordChar(text[end])
        if (before && after) return true
        from = at + 1
    }
}

/**
 * Index of the first position where [regex] matches and the match runs to the end of [text], or -1.
 * Stands in for a trailing end-of-input anchor, which the JVM and JavaScript define differently.
 * Not `matchAt`: on Android 15 its region with transparent bounds makes the system's regex library read outside the text and
 * crash the app (native SIGSEGV in RegexMatcher::MatchChunkAt, seen on a real phone).
 */
internal fun matchStartAtEnd(regex: Regex, text: String): Int {
    for (i in 0..text.length) {
        if (regex.matchEntire(text.substring(i)) != null) return i
    }
    return -1
}

/** Removes the first match of [regex] that runs to the end of [text]. */
internal fun removeFirstAtEnd(regex: Regex, text: String): String {
    val at = matchStartAtEnd(regex, text)
    return if (at < 0) text else text.substring(0, at)
}

/** Splits [text] around every match of [regex] (the pieces keep empty strings, like JavaScript's split). */
internal fun splitByRegex(text: String, regex: Regex): List<String> {
    val pieces = ArrayList<String>()
    var last = 0
    for (m in regex.findAll(text)) {
        pieces.add(text.substring(last, m.range.first))
        last = m.range.last + 1
    }
    pieces.add(text.substring(last))
    return pieces
}

/** JavaScript Math.round for the values used here: floor(x + 0.5). Kotlin's round() rounds half to even. */
internal fun round2(value: Double): Double = kotlin.math.floor(value * 100 + 0.5) / 100

private const val HEX = "0123456789ABCDEF"

/** JavaScript encodeURIComponent: UTF-8, leaving A-Z a-z 0-9 - _ . ! ~ * ' ( ) as they are. */
fun encodeURIComponent(value: String): String {
    val out = StringBuilder()
    for (byte in value.encodeToByteArray()) {
        val b = byte.toInt() and 0xFF
        val c = b.toChar()
        val plain = b < 128 && (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "-_.!~*'()")
        if (plain) out.append(c) else out.append('%').append(HEX[b shr 4]).append(HEX[b and 15])
    }
    return out.toString()
}

/** Unicode compatibility decomposition (NFKD); the common standard library has no normaliser. */
internal expect fun nfkd(value: String): String
