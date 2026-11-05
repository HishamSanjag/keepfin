package app.keepfin.core.cloud

// Small text codecs the providers need (no platform URL, Base64 or date classes: they disagree between targets).

private const val HEX_UP = "0123456789ABCDEF"
private const val HEX_LOW = "0123456789abcdef"
private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

private fun isUnreserved(c: Char): Boolean =
    c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~'

/** Percent-encodes the UTF-8 bytes of [s], keeping RFC 3986 unreserved characters and any character of [keep]. */
internal fun pctEncode(s: String, keep: String = ""): String {
    val sb = StringBuilder(s.length + 8)
    for (b in s.encodeToByteArray()) {
        val v = b.toInt() and 0xFF
        val c = v.toChar()
        if (v < 128 && (isUnreserved(c) || keep.indexOf(c) >= 0)) {
            sb.append(c)
        } else {
            sb.append('%').append(HEX_UP[v shr 4]).append(HEX_UP[v and 15])
        }
    }
    return sb.toString()
}

/** Encodes each segment of a `/`-separated path and keeps the separators. */
internal fun pctEncodePath(path: String): String = path.split('/').joinToString("/") { pctEncode(it) }

private fun hexVal(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

/** Decodes %XX sequences as UTF-8; a % that is not followed by two hex digits stays as it is. */
internal fun pctDecode(s: String): String {
    if (s.indexOf('%') < 0) return s
    val out = ArrayList<Byte>(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '%' && i + 2 < s.length &&hexVal(s[i + 1]) >= 0 && hexVal(s[i + 2]) >= 0) {
            out.add(((hexVal(s[i + 1]) shl 4) or hexVal(s[i + 2])).toByte())
            i += 3
        } else {
            val len = if (c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate()) 2 else 1
            for (b in s.substring(i, i + len).encodeToByteArray()) out.add(b)
            i += len
        }
    }
    return out.toByteArray().decodeToString()
}

/** application/x-www-form-urlencoded or a query string: `a=1&b=2` with encoded keys and values. */
internal fun formEncode(pairs: List<Pair<String, String>>): String =
    pairs.joinToString("&") { (k, v) -> pctEncode(k) + "=" + pctEncode(v) }

/** An ASCII-only JSON text: characters above 0x7E become \uXXXX (Dropbox's Dropbox-API-Arg header). */
internal fun asciiJson(json: String): String {
    val sb = StringBuilder(json.length)
    for (c in json) {
        val v = c.code
        if (v > 0x7E || v < 0x20) {
            sb.append("\\u").append(HEX_LOW[(v shr 12) and 15]).append(HEX_LOW[(v shr 8) and 15]).append(HEX_LOW[(v shr 4) and 15]).append(HEX_LOW[v and 15])
        } else {
            sb.append(c)
        }
    }
    return sb.toString()
}

internal fun hexOf(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        sb.append(HEX_LOW[v shr 4]).append(HEX_LOW[v and 15])
    }
    return sb.toString()
}

/** Base64 of [bytes]; [url] uses the URL-safe alphabet without padding (RFC 7636). */
internal fun base64Of(bytes: ByteArray, url: Boolean = false): String {
    val sb = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xFF
        val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
        val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
        sb.append(B64[b0 shr 2])
        sb.append(B64[((b0 and 3) shl 4) or (b1 shr 4)])
        if (i + 1 < bytes.size) sb.append(B64[((b1 and 15) shl 2) or (b2 shr 6)]) else if (!url) sb.append('=')
        if (i + 2 < bytes.size) sb.append(B64[b2 and 63]) else if (!url) sb.append('=')
        i += 3
    }
    val out = sb.toString()
    return if (url) out.replace('+', '-').replace('/', '_') else out
}

/** Decodes standard or URL-safe Base64 (padding optional); null when a character is not part of the alphabet. */
internal fun base64Decode(text: String): ByteArray? {
    val out = ArrayList<Byte>()
    var acc = 0
    var bits = 0
    for (ch in text) {
        if (ch == '=') break
        val c = when (ch) {
            '-' -> '+'
            '_' -> '/'
            else -> ch
        }
        val v = B64.indexOf(c)
        if (v < 0) return null
        acc = (acc shl 6) or v
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.add(((acc shr bits) and 0xFF).toByte())
        }
    }
    return out.toByteArray()
}

private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
    val y = if (m <= 2) y0 - 1 else y0
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era.toLong() * 146097 + doe - 719468
}

private fun digits(s: String, from: Int, count: Int): Int? {
    if (from + count > s.length) return null
    var v = 0
    for (k in 0 until count) {
        val c = s[from + k]
        if (c !in '0'..'9') return null
        v = v * 10 + (c - '0')
    }
    return v
}

/** `2026-10-15T12:34:56.789Z` (or with a +HH:MM offset) to epoch milliseconds; null when it is not that shape. */
internal fun parseIso(text: String?): Long? {
    val s = text ?: return null
    val y = digits(s, 0, 4) ?: return null
    if (s.length < 19 || s[4] != '-' || s[7] != '-' || (s[10] != 'T' && s[10] != 't' && s[10] != ' ') || s[13] != ':' || s[16] != ':') return null
    val mo = digits(s, 5, 2) ?: return null
    val d = digits(s, 8, 2) ?: return null
    val h = digits(s, 11, 2) ?: return null
    val mi = digits(s, 14, 2) ?: return null
    val se = digits(s, 17, 2) ?: return null
    var i = 19
    var ms = 0
    if (i < s.length && s[i] == '.') {
        i++
        var scale = 100
        while (i < s.length && s[i] in '0'..'9') {
            ms += (s[i] - '0') * scale
            scale /= 10
            i++
        }
    }
    var offsetMin = 0
    if (i < s.length) {
        val c = s[i]
        if (c == 'Z' || c == 'z') {
            i++
        } else if (c == '+' || c == '-') {
            val oh = digits(s, i + 1, 2) ?: return null
            val om = digits(s, i + 4, 2) ?: digits(s, i + 3, 2) ?: 0
            offsetMin = (oh * 60 + om) * (if (c == '-') -1 else 1)
            i = s.length
        }
    }
    val days = daysFromCivil(y, mo, d)
    return ((days * 24 + h) * 60 + mi - offsetMin) * 60_000L + se * 1000L + ms
}

private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

/** `Wed, 15 Oct 2026 12:00:00 GMT` (RFC 9110 IMF-fixdate) to epoch milliseconds; null otherwise. */
internal fun parseHttpDate(text: String?): Long? {
    val s = text?.trim() ?: return null
    val comma = s.indexOf(',')
    val rest = (if (comma >= 0) s.substring(comma + 1) else s).trim()
    val parts = rest.split(' ').filter { it.isNotEmpty() }
    if (parts.size < 5) return null
    val d = parts[0].toIntOrNull() ?: return null
    val mo = MONTHS.indexOf(parts[1].lowercase().take(3)) + 1
    if (mo == 0) return null
    val y = parts[2].toIntOrNull() ?: return null
    val t = parts[3].split(':')
    if (t.size != 3) return null
    val h = t[0].toIntOrNull() ?: return null
    val mi = t[1].toIntOrNull() ?: return null
    val se = t[2].toIntOrNull() ?: return null
    return ((daysFromCivil(y, mo, d) * 24 + h) * 60 + mi) * 60_000L + se * 1000L
}
