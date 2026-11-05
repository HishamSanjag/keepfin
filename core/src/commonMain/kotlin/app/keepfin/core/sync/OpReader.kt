package app.keepfin.core.sync

// The fast route for reading one op line (see Checkpoint codec for the same idea applied to checkpoints).
//
// The reference route (decodeOp) encodes the text to UTF-8, decodes it strictly, parses it into a generic tree,
// converts the tree and encodes it again to prove the text was canonical. [OpTextReader] reads the text once and
// accepts only a line that is exactly the canonical encoding of the op it reads. On any doubt it returns null and
// the caller runs the reference route, which keeps the exact problem codes. It never accepts a line the reference
// route rejects: tests compare the two on many damaged lines.

/**
 * The size of [text] in UTF-8, or -1 when it holds an unpaired surrogate (which UTF-8 cannot carry, so the
 * reference route must decide what that text means).
 */
internal fun utf8LengthOrMinusOne(text: String): Int {
    var n = 0
    var i = 0
    val len = text.length
    while (i < len) {
        val c = text[i].code
        if (c < 0x80) {
            n += 1
        } else if (c < 0x800) {
            n += 2
        } else if (c >= 0xD800 && c <= 0xDBFF) {
            if (i + 1 >= len) return -1
            val d = text[i + 1].code
            if (d < 0xDC00 || d > 0xDFFF) return -1
            n += 4
            i++
        } else if (c >= 0xDC00 && c <= 0xDFFF) {
            return -1
        } else {
            n += 3
        }
        i++
    }
    return n
}

internal class OpTextReader(private val s: String, private val sortedKeys: Boolean = true) {
    private var p = 0
    private val end = s.length
    private var actor: String? = null

    private class Bail : RuntimeException()

    private fun bail(): Nothing = throw BAIL

    /** The op, or null when the text is not exactly the canonical encoding of a well-formed op envelope. */
    fun read(): Op? {
        return try {
            lit("{\"body\":")
            if (cur() != 123) bail()
            val body = (readJv(0) as? JObj)?.members ?: bail()
            lit(",\"hlc\":")
            val hlc = readHlc()
            lit(",\"id\":")
            val id = readOpId()
            lit(",\"kind\":")
            val kind = readString()
            expect(125)
            if (p != end) null else Op(id, hlc, kind, body)
        } catch (e: Bail) {
            null
        }
    }

    /**
     * A JSON object as `JSON.stringify` writes it (no spaces, any key order, integers): its members in text order, or
     * null for anything else (the caller then uses the general parser, which gives the same members when it accepts).
     */
    fun readObjectOnly(): Map<String, Jv>? {
        return try {
            if (cur() != 123) bail()
            val body = (readJv(0) as? JObj)?.members ?: bail()
            if (p != end) null else body
        } catch (e: Bail) {
            null
        }
    }

    private fun cur(): Int = if (p < end) s[p].code else bail()

    private fun expect(c: Int) {
        if (p >= end || s[p].code != c) bail()
        p++
    }

    private fun lit(t: String) {
        val n = t.length
        if (p + n > end) bail()
        for (i in 0 until n) if (s[p + i] != t[i]) bail()
        p += n
    }

    private fun readString(): String {
        expect(34)
        val start = p
        var i = p
        while (i < end) {
            val c = s[i].code
            if (c == 34) {
                p = i + 1
                return s.substring(start, i)
            }
            if (c == 92) break
            if (c < 0x20) bail()
            i++
        }
        if (i >= end) bail()
        return readStringSlow(start, i)
    }

    private fun readStringSlow(start: Int, escapeAt: Int): String {
        val sb = StringBuilder()
        sb.append(s.substring(start, escapeAt))
        p = escapeAt
        var prevHighEscape = false
        while (true) {
            if (p >= end) bail()
            val c = s[p].code
            p++
            if (c == 34) return sb.toString()
            if (c < 0x20) bail()
            if (c != 92) {
                sb.append(c.toChar())
                prevHighEscape = false
                continue
            }
            if (p >= end) bail()
            val e = s[p].code
            p++
            when (e) {
                34 -> sb.append('"')
                92 -> sb.append(92.toChar())
                98 -> sb.append(8.toChar())
                102 -> sb.append(12.toChar())
                110 -> sb.append(10.toChar())
                114 -> sb.append(13.toChar())
                116 -> sb.append(9.toChar())
                117 -> {
                    val code = hex4()
                    if (code < 0x20) {
                        if (code == 8 || code == 9 || code == 10 || code == 12 || code == 13) bail()
                        prevHighEscape = false
                    } else if (code >= 0xD800 && code <= 0xDFFF) {
                        if (code >= 0xDC00 && prevHighEscape) bail()
                        prevHighEscape = code <= 0xDBFF
                    } else {
                        bail()
                    }
                    sb.append(code.toChar())
                    continue
                }
                else -> bail()
            }
            prevHighEscape = false
        }
    }

    private fun hex4(): Int {
        if (p + 4 > end) bail()
        var code = 0
        for (k in 0 until 4) {
            val c = s[p + k].code
            val d = when {
                c >= 48 && c <= 57 -> c - 48
                c >= 97 && c <= 102 -> c - 87
                else -> bail()
            }
            code = code * 16 + d
        }
        p += 4
        return code
    }

    private fun readLong(): Long {
        var neg = false
        if (cur() == 45) {
            neg = true
            p++
        }
        val first = cur()
        if (first < 48 || first > 57) bail()
        if (first == 48) {
            p++
            if (neg) bail()
            if (p < end && s[p].code >= 48 && s[p].code <= 57) bail()
            return 0L
        }
        var d = 0.0
        var digits = 0
        while (p < end) {
            val c = s[p].code
            if (c < 48 || c > 57) break
            d = d * 10.0 + (c - 48)
            digits++
            if (digits > 16) bail()
            p++
        }
        if (d > 9007199254740991.0) bail()
        val v = d.toLong()
        return if (neg) -v else v
    }

    private fun uuidAt(at: Int): String {
        if (at + 36 > end) bail()
        val known = actor
        if (known != null) {
            var same = true
            for (i in 0 until 36) if (known[i] != s[at + i]) { same = false; break }
            if (same) return known
        }
        for (i in 0 until 36) {
            val c = s[at + i].code
            val dash = i == 8 || i == 13 || i == 18 || i == 23
            val ok = if (dash) c == 45 else (c >= 48 && c <= 57) || (c >= 97 && c <= 102)
            if (!ok) bail()
        }
        val fresh = s.substring(at, at + 36)
        actor = fresh
        return fresh
    }

    private fun readOpId(): OpId {
        expect(34)
        val a = uuidAt(p)
        if (p + 37 >= end || s[p + 36].code != 58) bail()
        var q = p + 37
        val first = s[q].code
        if (first < 49 || first > 57) bail()
        var d = 0.0
        var digits = 0
        while (q < end) {
            val c = s[q].code
            if (c < 48 || c > 57) break
            d = d * 10.0 + (c - 48)
            digits++
            if (digits > 16) bail()
            q++
        }
        if (d > 9007199254740991.0) bail()
        p = q
        expect(34)
        return OpId(a, d.toLong())
    }

    private fun readHlc(): Hlc {
        expect(34)
        if (p + 57 >= end) bail()
        var wall = 0.0
        for (i in 0 until 13) {
            val c = s[p + i].code
            if (c < 48 || c > 57) bail()
            wall = wall * 10.0 + (c - 48)
        }
        if (s[p + 13].code != 46) bail()
        var counter = 0
        for (i in 14 until 20) {
            val c = s[p + i].code
            if (c < 48 || c > 57) bail()
            counter = counter * 10 + (c - 48)
        }
        if (s[p + 20].code != 46) bail()
        val a = uuidAt(p + 21)
        p += 57
        expect(34)
        return Hlc(wall.toLong(), counter, a)
    }

    private fun readJv(depth: Int): Jv {
        if (depth > 200) bail()
        return when (cur()) {
            123 -> {
                p++
                val out = LinkedHashMap<String, Jv>()
                if (cur() == 125) {
                    p++
                } else {
                    var prev: String? = null
                    while (true) {
                        val k = readString()
                        if (sortedKeys) {
                            if (prev != null && k.compareTo(prev) <= 0) bail()
                            prev = k
                        }
                        expect(58)
                        // a repeated key means the general parser's last-wins rule applies: leave it to that
                        if (out.put(k, readJv(depth + 1)) != null) bail()
                        val c = cur()
                        p++
                        if (c == 125) break
                        if (c != 44) bail()
                    }
                }
                JObj(out)
            }
            91 -> {
                p++
                val items = ArrayList<Jv>()
                if (cur() == 93) {
                    p++
                } else {
                    while (true) {
                        items.add(readJv(depth + 1))
                        val c = cur()
                        p++
                        if (c == 93) break
                        if (c != 44) bail()
                    }
                }
                JArr(items)
            }
            34 -> JStr(readString())
            116 -> { lit("true"); JBool(true) }
            102 -> { lit("false"); JBool(false) }
            110 -> { lit("null"); JNull }
            else -> JNum(readLong())
        }
    }

    private companion object {
        val BAIL = Bail()
    }
}
