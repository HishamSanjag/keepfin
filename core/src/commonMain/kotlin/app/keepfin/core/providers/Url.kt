package app.keepfin.core.providers

// A small URL parser that follows the WHATWG URL Standard, the algorithm behind JavaScript's `new URL`.
// Desktop used `new URL` and Android used java.net.URI; they disagree on host case, ports, user info,
// percent-encoding, IDN hosts, IP literals and more. Both apps now parse with this one class, and
// fixtures/engine/providers/urls.json holds the cases (expected values come from `new URL`).
//
// It covers what the app needs: the special schemes (http, https, ftp, ws, wss), file: in a minimal form,
// and any other scheme as an opaque path or a plain host. International host names use a simplified
// UTS 46 mapping (NFKC, lower case, ignorable characters, ideographic dots) followed by Punycode.

private val specialSchemes = mapOf("http" to 80, "https" to 443, "ftp" to 21, "ws" to 80, "wss" to 443, "file" to -1)

private const val HEX_UP = "0123456789ABCDEF"

private fun isAsciiAlpha(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
private fun isAsciiDigit(c: Char) = c in '0'..'9'
private fun isAsciiHex(c: Char) = isAsciiDigit(c) || c in 'a'..'f' || c in 'A'..'F'

private fun lowerAscii(value: String): String {
    val out = StringBuilder(value.length)
    for (c in value) out.append(if (c in 'A'..'Z') c + 32 else c)
    return out.toString()
}

// ---- percent-encode sets -------------------------------------------------------------------------------

private fun inC0(c: Char) = c.code < 0x20 || c.code > 0x7E
private fun inFragmentSet(c: Char) = inC0(c) || c == ' ' || c == '"' || c == '<' || c == '>' || c == '`'
private fun inQuerySet(c: Char) = inC0(c) || c == ' ' || c == '"' || c == '#' || c == '<' || c == '>'
private fun inSpecialQuerySet(c: Char) = inQuerySet(c) || c == '\''
private fun inPathSet(c: Char) = inQuerySet(c) || c == '?' || c == '^' || c == '`' || c == '{' || c == '}'
private fun inUserinfoSet(c: Char) =
    inPathSet(c) || c == '/' || c == ':' || c == ';' || c == '=' || c == '@' || c == '[' || c == '\\' || c == ']' || c == '|'

private fun percentEncode(value: String, inSet: (Char) -> Boolean): String {
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c.code < 0x80) {
            if (inSet(c)) out.append('%').append(HEX_UP[c.code shr 4]).append(HEX_UP[c.code and 15]) else out.append(c)
            i++
            continue
        }
        // A non-ASCII code point (one char, or a surrogate pair) becomes its UTF-8 bytes.
        val width = if (c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()) 2 else 1
        for (byte in value.substring(i, i + width).encodeToByteArray()) {
            val b = byte.toInt() and 0xFF
            out.append('%').append(HEX_UP[b shr 4]).append(HEX_UP[b and 15])
        }
        i += width
    }
    return out.toString()
}

private fun hexValue(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    else -> c - 'A' + 10
}

/** Percent-decodes to bytes, then UTF-8 to text (malformed sequences become U+FFFD). */
internal fun percentDecode(value: String, plusIsSpace: Boolean = false): String {
    if (value.indexOf('%') < 0 && !(plusIsSpace && value.indexOf('+') >= 0) && value.all { it.code < 0x80 }) return value
    val bytes = ArrayList<Byte>(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%' && i + 2 < value.length && isAsciiHex(value[i + 1]) && isAsciiHex(value[i + 2])) {
            bytes.add((hexValue(value[i + 1]) * 16 + hexValue(value[i + 2])).toByte())
            i += 3
        } else if (c == '+' && plusIsSpace) {
            bytes.add(0x20)
            i++
        } else {
            val width = if (c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()) 2 else 1
            for (b in value.substring(i, i + width).encodeToByteArray()) bytes.add(b)
            i += width
        }
    }
    return bytes.toByteArray().decodeToString()
}

// ---- hosts ---------------------------------------------------------------------------------------------

private fun isForbiddenHostChar(c: Char) =
    c == '\u0000' || c == '\t' || c == '\n' || c == '\r' || c == ' ' || c == '#' || c == '/' || c == ':' || c == '<' ||
        c == '>' || c == '?' || c == '@' || c == '[' || c == '\\' || c == ']' || c == '^' || c == '|'

private fun isForbiddenDomainChar(c: Char) = isForbiddenHostChar(c) || c.code <= 0x1F || c == '%' || c.code == 0x7F

private fun parseIpv4Number(input: String): Long? {
    if (input.isEmpty()) return null
    var s = input
    var radix = 10
    if (s.length >= 2 && (s.startsWith("0x") || s.startsWith("0X"))) {
        s = s.substring(2)
        radix = 16
    } else if (s.length >= 2 && s[0] == '0') {
        s = s.substring(1)
        radix = 8
    }
    if (s.isEmpty()) return 0
    var value = 0L
    for (c in s) {
        val digit = when {
            isAsciiDigit(c) -> c - '0'
            radix == 16 && isAsciiHex(c) -> hexValue(c)
            else -> return null
        }
        if (digit >= radix) return null
        value = value * radix + digit
        if (value > 0xFFFFFFFFFFL) value = 0xFFFFFFFFFFL // far beyond any valid part; keeps the arithmetic bounded
    }
    return value
}

private fun endsInNumber(input: String): Boolean {
    val parts = input.split('.').toMutableList()
    if (parts.last().isEmpty()) {
        if (parts.size == 1) return false
        parts.removeAt(parts.size - 1)
    }
    val last = parts.last()
    if (last.isNotEmpty() && last.all { isAsciiDigit(it) }) return true
    return parseIpv4Number(last) != null
}

private fun parseIpv4(input: String): String? {
    val parts = input.split('.').toMutableList()
    if (parts.last().isEmpty() && parts.size > 1) parts.removeAt(parts.size - 1)
    if (parts.size > 4) return null
    val numbers = ArrayList<Long>()
    for (part in parts) numbers.add(parseIpv4Number(part) ?: return null)
    for (i in 0 until numbers.size - 1) if (numbers[i] > 255) return null
    var limit = 1L
    repeat(5 - numbers.size) { limit *= 256 }
    if (numbers.last() >= limit) return null
    var ipv4 = numbers.last()
    for (i in 0 until numbers.size - 1) {
        var factor = 1L
        repeat(3 - i) { factor *= 256 }
        ipv4 += numbers[i] * factor
    }
    return "${(ipv4 shr 24) and 255}.${(ipv4 shr 16) and 255}.${(ipv4 shr 8) and 255}.${ipv4 and 255}"
}

private fun parseIpv6(input: String): String? {
    val address = IntArray(8)
    var pieceIndex = 0
    var compress = -1
    var pointer = 0
    val n = input.length
    fun at(i: Int): Char = if (i < n) input[i] else '\u0000'
    if (at(pointer) == ':') {
        if (at(pointer + 1) != ':') return null
        pointer += 2
        pieceIndex++
        compress = pieceIndex
    }
    while (pointer < n) {
        if (pieceIndex == 8) return null
        if (at(pointer) == ':') {
            if (compress != -1) return null
            pointer++
            pieceIndex++
            compress = pieceIndex
            continue
        }
        var value = 0
        var length = 0
        while (length < 4 && pointer < n && isAsciiHex(at(pointer))) {
            value = value * 16 + hexValue(at(pointer))
            pointer++
            length++
        }
        if (pointer < n && at(pointer) == '.') {
            if (length == 0) return null
            pointer -= length
            if (pieceIndex > 6) return null
            var numbersSeen = 0
            while (pointer < n) {
                var ipv4Piece = -1
                if (numbersSeen > 0) {
                    if (at(pointer) == '.' && numbersSeen < 4) pointer++ else return null
                }
                if (pointer >= n || !isAsciiDigit(at(pointer))) return null
                while (pointer < n && isAsciiDigit(at(pointer))) {
                    val number = at(pointer) - '0'
                    ipv4Piece = if (ipv4Piece == -1) number else if (ipv4Piece == 0) return null else ipv4Piece * 10 + number
                    if (ipv4Piece > 255) return null
                    pointer++
                }
                address[pieceIndex] = address[pieceIndex] * 0x100 + ipv4Piece
                numbersSeen++
                if (numbersSeen == 2 || numbersSeen == 4) pieceIndex++
            }
            if (numbersSeen != 4) return null
            break
        } else if (pointer < n && at(pointer) == ':') {
            pointer++
            if (pointer >= n) return null
        } else if (pointer < n) {
            return null
        }
        address[pieceIndex] = value
        pieceIndex++
    }
    if (compress != -1) {
        var swaps = pieceIndex - compress
        pieceIndex = 7
        while (pieceIndex != 0 && swaps > 0) {
            val other = compress + swaps - 1
            val tmp = address[pieceIndex]
            address[pieceIndex] = address[other]
            address[other] = tmp
            pieceIndex--
            swaps--
        }
    } else if (pieceIndex != 8) return null
    // Serialise: the first longest run of two or more zero pieces becomes "::".
    var bestStart = -1
    var bestLen = 0
    var i = 0
    while (i < 8) {
        if (address[i] == 0) {
            var j = i
            while (j < 8 && address[j] == 0) j++
            if (j - i > bestLen) {
                bestStart = i
                bestLen = j - i
            }
            i = j
        } else i++
    }
    if (bestLen < 2) bestStart = -1
    val out = StringBuilder()
    var ignore0 = false
    for (k in 0 until 8) {
        if (ignore0 && address[k] == 0) continue
        ignore0 = false
        if (bestStart == k) {
            out.append(if (k == 0) "::" else ":")
            ignore0 = true
            continue
        }
        out.append(address[k].toString(16))
        if (k != 7) out.append(':')
    }
    return out.toString()
}

// Punycode (RFC 3492)
private const val PUNY_BASE = 36
private const val PUNY_TMIN = 1
private const val PUNY_TMAX = 26
private const val PUNY_SKEW = 38
private const val PUNY_DAMP = 700
private const val PUNY_INITIAL_BIAS = 72
private const val PUNY_INITIAL_N = 128

private fun punyAdapt(delta: Int, numPoints: Int, first: Boolean): Int {
    var d = if (first) delta / PUNY_DAMP else delta / 2
    d += d / numPoints
    var k = 0
    while (d > ((PUNY_BASE - PUNY_TMIN) * PUNY_TMAX) / 2) {
        d /= PUNY_BASE - PUNY_TMIN
        k += PUNY_BASE
    }
    return k + (PUNY_BASE - PUNY_TMIN + 1) * d / (d + PUNY_SKEW)
}

private fun punyDigit(d: Int): Char = if (d < 26) 'a' + d else '0' + (d - 26)

private fun codePointsOf(value: String): IntArray {
    val out = ArrayList<Int>()
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()) {
            out.add(0x10000 + ((c.code - 0xD800) shl 10) + (value[i + 1].code - 0xDC00))
            i += 2
        } else {
            out.add(c.code)
            i++
        }
    }
    return out.toIntArray()
}

private fun punycodeEncode(input: String): String {
    val cps = codePointsOf(input)
    val out = StringBuilder()
    for (cp in cps) if (cp < 0x80) out.append(cp.toChar())
    val basic = out.length
    var handled = basic
    if (basic > 0) out.append('-')
    var n = PUNY_INITIAL_N
    var delta = 0
    var bias = PUNY_INITIAL_BIAS
    while (handled < cps.size) {
        var m = Int.MAX_VALUE
        for (cp in cps) if (cp >= n && cp < m) m = cp
        delta += (m - n) * (handled + 1)
        n = m
        for (cp in cps) {
            if (cp < n) delta++
            if (cp == n) {
                var q = delta
                var k = PUNY_BASE
                while (true) {
                    val t = if (k <= bias) PUNY_TMIN else if (k >= bias + PUNY_TMAX) PUNY_TMAX else k - bias
                    if (q < t) break
                    out.append(punyDigit(t + (q - t) % (PUNY_BASE - t)))
                    q = (q - t) / (PUNY_BASE - t)
                    k += PUNY_BASE
                }
                out.append(punyDigit(q))
                bias = punyAdapt(delta, handled + 1, handled == basic)
                delta = 0
                handled++
            }
        }
        delta++
        n++
    }
    return out.toString()
}

/** Decodes the part after "xn--"; null when it is not valid Punycode. */
private fun punycodeDecode(input: String): IntArray? {
    val output = ArrayList<Int>()
    val basicEnd = input.lastIndexOf('-')
    var start = 0
    if (basicEnd > 0) {
        for (k in 0 until basicEnd) {
            if (input[k].code >= 0x80) return null
            output.add(input[k].code)
        }
        start = basicEnd + 1
    }
    var n = PUNY_INITIAL_N
    var i = 0
    var bias = PUNY_INITIAL_BIAS
    var idx = start
    while (idx < input.length) {
        val oldi = i
        var w = 1
        var k = PUNY_BASE
        while (true) {
            if (idx >= input.length) return null
            val c = input[idx++]
            val digit = when (c) {
                in 'a'..'z' -> c - 'a'
                in 'A'..'Z' -> c - 'A'
                in '0'..'9' -> c - '0' + 26
                else -> return null
            }
            if (digit > (Int.MAX_VALUE - i) / w) return null
            i += digit * w
            val t = if (k <= bias) PUNY_TMIN else if (k >= bias + PUNY_TMAX) PUNY_TMAX else k - bias
            if (digit < t) break
            if (w > Int.MAX_VALUE / (PUNY_BASE - t)) return null
            w *= PUNY_BASE - t
            k += PUNY_BASE
        }
        bias = punyAdapt(i - oldi, output.size + 1, oldi == 0)
        if (i / (output.size + 1) > Int.MAX_VALUE - n) return null
        n += i / (output.size + 1)
        i %= output.size + 1
        if (n > 0x10FFFF) return null
        output.add(i, n)
        i++
    }
    return output.toIntArray()
}

/** Simplified UTS 46 processing: map, normalise, lower-case, then Punycode every label that is not ASCII. */
private fun domainToAscii(domain: String): String? {
    var mapped = domain
    if (mapped.any { it.code >= 0x80 }) {
        // Each code point is mapped on its own, so a Greek capital sigma never becomes a final sigma (UTS 46 has no context rules).
        val sb = StringBuilder()
        var at = 0
        while (at < mapped.length) {
            val width = if (mapped[at].isHighSurrogate() && at + 1 < mapped.length && mapped[at + 1].isLowSurrogate()) 2 else 1
            val piece = mapped.substring(at, at + width)
            at += width
            when (piece[0].code) {
                0x3002, 0xFF0E, 0xFF61 -> sb.append('.')
                0x00AD, 0x200B, 0x2060, 0xFEFF, 0x034F -> {}
                in 0x80..0x9F, 0xFFFD -> return null // disallowed in UTS 46
                in 0xD800..0xDFFF -> if (width == 2) sb.append(nfkc(piece).lowercase()) else return null // lone surrogate
                else -> sb.append(nfkc(piece).lowercase())
            }
        }
        mapped = nfkc(sb.toString())
    } else {
        mapped = lowerAscii(mapped)
    }
    val labels = mapped.split('.')
    val out = ArrayList<String>(labels.size)
    for (label in labels) {
        if (label.startsWith("xn--")) {
            val decoded = punycodeDecode(label.substring(4)) ?: return null
            if (decoded.isEmpty() || decoded.all { it < 0x80 } || decoded.any { it in 0x80..0x9F }) return null
            // A valid label is what the mapping would produce again, and encodes back to the same text.
            val text = StringBuilder()
            for (cp in decoded) {
                if (cp >= 0x10000) {
                    text.append((0xD800 + ((cp - 0x10000) shr 10)).toChar()).append((0xDC00 + ((cp - 0x10000) and 0x3FF)).toChar())
                } else text.append(cp.toChar())
            }
            val decodedText = text.toString()
            if (nfkc(decodedText).lowercase() != decodedText || lowerAscii(punycodeEncode(decodedText)) != label.substring(4)) return null
            out.add(label)
        } else if (label.any { it.code >= 0x80 }) {
            out.add("xn--" + punycodeEncode(label))
        } else out.add(label)
    }
    return out.joinToString(".")
}

/** The serialised host of a special URL, or null when it is invalid. */
private fun parseSpecialHost(input: String): String? {
    if (input.startsWith("[")) {
        if (!input.endsWith("]")) return null
        val v6 = parseIpv6(input.substring(1, input.length - 1)) ?: return null
        return "[$v6]"
    }
    val domain = percentDecode(input)
    val ascii = domainToAscii(domain) ?: return null
    if (ascii.isEmpty() || ascii.any { isForbiddenDomainChar(it) }) return null
    if (endsInNumber(ascii)) return parseIpv4(ascii)
    return ascii
}

private fun parseOpaqueHost(input: String): String? {
    if (input.startsWith("[")) {
        if (!input.endsWith("]")) return null
        val v6 = parseIpv6(input.substring(1, input.length - 1)) ?: return null
        return "[$v6]"
    }
    if (input.any { isForbiddenHostChar(it) }) return null
    return percentEncode(input) { inC0(it) }
}

// ---- the URL -------------------------------------------------------------------------------------------

class Url internal constructor(
    /** Lower-case scheme without the colon. */
    val scheme: String,
    val username: String,
    val password: String,
    /** The serialised host name (IPv6 in brackets), or null when the URL has no authority. */
    val host: String?,
    val port: Int?,
    /** The path as serialised (`pathname`). */
    val path: String,
    /** The query without the `?`, or null when there is none. */
    val query: String?,
    val fragment: String?,
) {
    /** `hostname` in JavaScript: empty when there is no host. */
    val hostname: String get() = host ?: ""

    val isSpecial: Boolean get() = scheme in specialSchemes

    val href: String
        get() {
            val out = StringBuilder(scheme).append(':')
            if (host != null) {
                out.append("//")
                if (username.isNotEmpty() || password.isNotEmpty()) {
                    out.append(username)
                    if (password.isNotEmpty()) out.append(':').append(password)
                    out.append('@')
                }
                out.append(host)
                if (port != null) out.append(':').append(port)
            }
            out.append(path)
            if (query != null) out.append('?').append(query)
            if (fragment != null) out.append('#').append(fragment)
            return out.toString()
        }

    /** The first value of a query parameter, like `URLSearchParams.get` ("+" is a space, percent escapes decoded). */
    fun queryParam(name: String): String? {
        val q = query ?: return null
        for (piece in q.split('&')) {
            if (piece.isEmpty()) continue
            val eq = piece.indexOf('=')
            val key = if (eq < 0) piece else piece.substring(0, eq)
            val value = if (eq < 0) "" else piece.substring(eq + 1)
            if (percentDecode(key, plusIsSpace = true) == name) return percentDecode(value, plusIsSpace = true)
        }
        return null
    }

    companion object {
        private fun isDoubleDot(s: String): Boolean {
            val l = lowerAscii(s)
            return l == ".." || l == ".%2e" || l == "%2e." || l == "%2e%2e"
        }

        private fun isSingleDot(s: String): Boolean {
            val l = lowerAscii(s)
            return l == "." || l == "%2e"
        }

        private fun serialisePath(rest: String, special: Boolean): String {
            var remainder = rest
            if (remainder.startsWith("/") || (special && remainder.startsWith("\\"))) remainder = remainder.substring(1)
            val segments = if (special) remainder.split('/', '\\') else remainder.split('/')
            val path = ArrayList<String>()
            for ((index, segment) in segments.withIndex()) {
                val last = index == segments.size - 1
                when {
                    isDoubleDot(segment) -> {
                        if (path.isNotEmpty()) path.removeAt(path.size - 1)
                        if (last) path.add("")
                    }
                    isSingleDot(segment) -> if (last) path.add("")
                    else -> path.add(percentEncode(segment) { inPathSet(it) })
                }
            }
            return "/" + path.joinToString("/")
        }

        /** Parses an absolute URL like `new URL(input)`; null when JavaScript would throw. */
        fun parse(input: String): Url? {
            var start = 0
            var end = input.length
            while (start < end && input[start].code <= 0x20) start++
            while (end > start && input[end - 1].code <= 0x20) end--
            // Like JavaScript, work on well-formed text: a lone surrogate becomes U+FFFD.
            val cleaned = StringBuilder(end - start)
            var i = start
            while (i < end) {
                val c = input[i]
                if (c.isHighSurrogate() && i + 1 < end && input[i + 1].isLowSurrogate()) {
                    cleaned.append(c).append(input[i + 1])
                    i += 2
                    continue
                }
                if (c.isSurrogate()) cleaned.append(0xFFFD.toChar()) else if (c != '\t' && c != '\n' && c != '\r') cleaned.append(c)
                i++
            }
            val text = cleaned.toString()

            if (text.isEmpty() || !isAsciiAlpha(text[0])) return null
            var colon = 1
            while (colon < text.length && (isAsciiAlpha(text[colon]) || isAsciiDigit(text[colon]) || text[colon] == '+' || text[colon] == '-' || text[colon] == '.')) colon++
            if (colon >= text.length || text[colon] != ':') return null
            val scheme = lowerAscii(text.substring(0, colon))
            var rest = text.substring(colon + 1)
            val special = scheme in specialSchemes

            var fragment: String? = null
            val hash = rest.indexOf('#')
            if (hash >= 0) {
                fragment = percentEncode(rest.substring(hash + 1)) { inFragmentSet(it) }
                rest = rest.substring(0, hash)
            }
            var query: String? = null
            val question = rest.indexOf('?')
            if (question >= 0) {
                val raw = rest.substring(question + 1)
                query = percentEncode(raw) { if (special) inSpecialQuerySet(it) else inQuerySet(it) }
                rest = rest.substring(0, question)
            }

            fun isSlash(c: Char) = c == '/' || (special && c == '\\')

            if (scheme == "file") {
                var host = ""
                var pathPart = rest
                if (rest.length >= 2 && isSlash(rest[0]) && isSlash(rest[1])) {
                    val after = rest.substring(2)
                    val cut = after.indexOfFirst { isSlash(it) }
                    val authority = if (cut < 0) after else after.substring(0, cut)
                    pathPart = if (cut < 0) "" else after.substring(cut)
                    if (authority.isNotEmpty()) {
                        host = parseSpecialHost(authority) ?: return null
                        if (host == "localhost") host = ""
                    }
                }
                return Url(scheme, "", "", host, null, serialisePath(pathPart, true), query, fragment)
            }

            if (special) {
                var i = 0
                while (i < rest.length && isSlash(rest[i])) i++
                rest = rest.substring(i)
            } else if (rest.startsWith("//")) {
                rest = rest.substring(2)
            } else {
                // No authority: an absolute path, or an opaque path (javascript:, mailto:, data:, spotify:...).
                val path = if (rest.startsWith("/")) {
                    serialisePath(rest, false)
                } else {
                    val encoded = percentEncode(rest) { inC0(it) }
                    // Trailing spaces of an opaque path are kept as %20 when a query or fragment follows.
                    if (query == null && fragment == null) encoded else encoded.trimEnd(' ') + "%20".repeat(encoded.length - encoded.trimEnd(' ').length)
                }
                return Url(scheme, "", "", null, null, path, query, fragment)
            }

            val cut = rest.indexOfFirst { isSlash(it) }
            val authority = if (cut < 0) rest else rest.substring(0, cut)
            val pathPart = if (cut < 0) "" else rest.substring(cut)

            var username = ""
            var password = ""
            var hostPort = authority
            val at = authority.lastIndexOf('@')
            if (at >= 0) {
                val userinfo = authority.substring(0, at)
                hostPort = authority.substring(at + 1)
                if (hostPort.isEmpty()) return null
                val colonAt = userinfo.indexOf(':')
                username = percentEncode(if (colonAt < 0) userinfo else userinfo.substring(0, colonAt)) { inUserinfoSet(it) }
                password = if (colonAt < 0) "" else percentEncode(userinfo.substring(colonAt + 1)) { inUserinfoSet(it) }
            }

            var hostText = hostPort
            var portText = ""
            var inBrackets = false
            for ((i, c) in hostPort.withIndex()) {
                if (c == '[') inBrackets = true
                if (c == ']') inBrackets = false
                if (c == ':' && !inBrackets) {
                    hostText = hostPort.substring(0, i)
                    portText = hostPort.substring(i + 1)
                    break
                }
            }
            var port: Int? = null
            if (hostPort.length > hostText.length) {
                if (!portText.all { isAsciiDigit(it) }) return null
                if (portText.isNotEmpty()) {
                    var value = 0L
                    for (c in portText) {
                        value = value * 10 + (c - '0')
                        if (value > 65535) return null
                    }
                    port = if (specialSchemes[scheme] == value.toInt()) null else value.toInt()
                }
            }
            if (hostText.isEmpty()) {
                if (special || username.isNotEmpty() || password.isNotEmpty() || hostPort.length > hostText.length) return null
                return Url(scheme, username, password, "", port, if (pathPart.isEmpty()) "" else serialisePath(pathPart, false), query, fragment)
            }
            val host = (if (special) parseSpecialHost(hostText) else parseOpaqueHost(hostText)) ?: return null
            return Url(scheme, username, password, host, port, if (!special && pathPart.isEmpty()) "" else serialisePath(pathPart, special), query, fragment)
        }
    }
}

/** Unicode compatibility composition (NFKC); the common standard library has no normaliser. */
internal expect fun nfkc(value: String): String
