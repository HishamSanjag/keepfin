package app.keepfin.core.sync

// The fast paths of the checkpoint file format (KEEPFIN-SYNC-SPEC sections 8 and 10).
//
// A checkpoint is megabytes long, so the reference route (state -> generic JSON tree -> canonical text -> UTF-8,
// and back again with a re-encode to prove canonical form) is too slow. These two classes do the same work in one
// pass over bytes:
//  - [StateWriter] writes the canonical bytes of a State directly. It must give exactly the bytes of
//    `encodeCanonical(StateCodec.toJson(state))`; tests compare them.
//  - [StateReader] reads those bytes straight into a State while checking that they are canonical. It accepts only
//    input that is byte-for-byte what the writer would produce for the state it reads, so it never accepts
//    anything the reference route rejects. It does not say why it refuses: on any doubt it returns null and the
//    caller runs the reference route, which keeps the exact error codes.

/** A growing byte buffer with the canonical JSON writers used by checkpoints. */
internal class ByteSink(initial: Int = 1 shl 16) {
    var buf = ByteArray(maxOf(initial, 16))
    var size = 0

    private fun grow(extra: Int) {
        val need = size + extra
        if (need <= buf.size) return
        var cap = buf.size.toLong() * 2
        if (cap < need) cap = need.toLong()
        buf = buf.copyOf(cap.toInt())
    }

    fun byte(c: Int) {
        if (size == buf.size) grow(1)
        buf[size++] = c.toByte()
    }

    fun bytes(a: ByteArray) {
        grow(a.size)
        a.copyInto(buf, size)
        size += a.size
    }

    /** [s] must hold only ASCII characters. */
    fun ascii(s: String) {
        grow(s.length)
        for (i in 0 until s.length) buf[size++] = s[i].code.toByte()
    }

    fun long(v: Long) = ascii(v.toString())

    /** `"` + the escaped body of [s] + `"`, exactly the text and bytes `jsonQuote(s)` gives. */
    fun quoted(s: String) {
        byte(34)
        quotedBody(s)
        byte(34)
    }

    /** The escaped text of [s] without its quotes (what sits between them). */
    fun quotedBody(s: String) {
        val n = s.length
        grow(n * 6)
        var i = 0
        while (i < n) {
            val c = s[i].code
            when {
                c == 34 -> { buf[size++] = 92; buf[size++] = 34 }
                c == 92 -> { buf[size++] = 92; buf[size++] = 92 }
                c == 8 -> { buf[size++] = 92; buf[size++] = 98 }
                c == 12 -> { buf[size++] = 92; buf[size++] = 102 }
                c == 10 -> { buf[size++] = 92; buf[size++] = 110 }
                c == 13 -> { buf[size++] = 92; buf[size++] = 114 }
                c == 9 -> { buf[size++] = 92; buf[size++] = 116 }
                c < 0x20 -> unitEscape(c)
                c < 0x80 -> buf[size++] = c.toByte()
                c < 0x800 -> {
                    buf[size++] = (0xC0 or (c shr 6)).toByte()
                    buf[size++] = (0x80 or (c and 0x3F)).toByte()
                }
                c >= 0xD800 && c <= 0xDBFF && i + 1 < n && s[i + 1].code >= 0xDC00 && s[i + 1].code <= 0xDFFF -> {
                    val cp = 0x10000 + ((c - 0xD800) shl 10) + (s[i + 1].code - 0xDC00)
                    buf[size++] = (0xF0 or (cp shr 18)).toByte()
                    buf[size++] = (0x80 or ((cp shr 12) and 0x3F)).toByte()
                    buf[size++] = (0x80 or ((cp shr 6) and 0x3F)).toByte()
                    buf[size++] = (0x80 or (cp and 0x3F)).toByte()
                    i++
                }
                c >= 0xD800 && c <= 0xDFFF -> unitEscape(c)
                else -> {
                    buf[size++] = (0xE0 or (c shr 12)).toByte()
                    buf[size++] = (0x80 or ((c shr 6) and 0x3F)).toByte()
                    buf[size++] = (0x80 or (c and 0x3F)).toByte()
                }
            }
            i++
        }
    }

    private fun unitEscape(c: Int) {
        buf[size++] = 92
        buf[size++] = 117
        var shift = 12
        while (shift >= 0) {
            val d = (c shr shift) and 15
            buf[size++] = (if (d < 10) 48 + d else 87 + d).toByte()
            shift -= 4
        }
    }

    /** Canonical text of a JSON value: keys sorted by UTF-16 code unit, no spaces. */
    fun jv(v: Jv) {
        when (v) {
            is JNull -> ascii("null")
            is JBool -> ascii(if (v.value) "true" else "false")
            is JNum -> long(v.value)
            is JStr -> quoted(v.value)
            is JArr -> {
                byte(91)
                var first = true
                for (item in v.items) {
                    if (!first) byte(44)
                    first = false
                    jv(item)
                }
                byte(93)
            }
            is JObj -> {
                byte(123)
                var first = true
                for (e in sortedEntries(v.members)) {
                    if (!first) byte(44)
                    first = false
                    quoted(e.key)
                    byte(58)
                    jv(e.value)
                }
                byte(125)
            }
        }
    }
}

/** Writes a [State] as the canonical JSON line of a checkpoint body (no trailing newline). */
internal class StateWriter private constructor() {
    // The registers of one op share one clock and one op id, and so do an item's `exists` tag: writing the same object
    // again repeats the bytes of the last one instead of formatting them anew.
    private var lastHlc: Hlc? = null
    private var lastHlcBytes = ByteArray(0)
    private var lastOp: OpId? = null
    private var lastOpBytes = ByteArray(0)

    companion object {
        fun write(out: ByteSink, s: State) = StateWriter().writeState(out, s)
    }

    private fun writeState(out: ByteSink, s: State) {
        out.ascii("{\"entries\":{")
        var first = true
        for (e in sortedEntries(s.entries)) {
            if (!first) out.byte(44)
            first = false
            out.quoted(e.key)
            out.ascii(":{\"item\":")
            out.quoted(e.value.item)
            out.ascii(",\"op\":")
            opId(out, e.value.op)
            out.ascii(",\"playlist\":")
            out.quoted(e.value.playlist)
            out.byte(125)
        }
        out.ascii("},\"entryKey\":")
        regs(out, s.entryKey)
        out.ascii(",\"findings\":")
        strs(out, s.findings)
        out.ascii(",\"items\":{")
        first = true
        for (e in sortedEntries(s.items)) {
            if (!first) out.byte(44)
            first = false
            out.quoted(e.key)
            val i = e.value
            out.ascii(":{\"deletedHeads\":")
            ids(out, i.deletedHeads)
            out.ascii(",\"exists\":")
            orSet(out, i.exists)
            out.ascii(",\"fields\":")
            regs(out, i.fields)
            out.ascii(",\"revisions\":")
            revisions(out, i.revisions)
            out.byte(125)
        }
        out.ascii("},\"placements\":")
        regMaps(out, s.placements)
        out.ascii(",\"places\":")
        regMaps(out, s.places)
        out.ascii(",\"playlists\":")
        entities(out, s.playlists)
        out.ascii(",\"profile\":")
        regs(out, s.profile)
        out.ascii(",\"removedEntries\":")
        strs(out, s.removedEntries)
        out.ascii(",\"sets\":{")
        first = true
        for (e in sortedEntries(s.sets)) {
            if (!first) out.byte(44)
            first = false
            out.quoted(e.key)
            out.ascii(":{")
            var firstEl = true
            for (el in sortedEntries(e.value)) {
                if (!firstEl) out.byte(44)
                firstEl = false
                out.quoted(el.key)
                out.byte(58)
                orSet(out, el.value)
            }
            out.byte(125)
        }
        out.ascii("},\"spaces\":")
        entities(out, s.spaces)
        out.byte(125)
    }

    /** `"actor:seq"`, the text of `OpId.toString()` quoted. */
    private fun opId(out: ByteSink, id: OpId) {
        if (id !== lastOp) {
            val tmp = ByteSink(64)
            tmp.byte(34)
            tmp.quotedBody(id.actor)
            tmp.byte(58)
            tmp.long(id.seq)
            tmp.byte(34)
            lastOp = id
            lastOpBytes = tmp.buf.copyOf(tmp.size)
        }
        out.bytes(lastOpBytes)
    }

    private fun ids(out: ByteSink, set: Set<OpId>) {
        out.byte(91)
        val list: Collection<OpId> = if (set.size > 1) set.sorted() else set
        var first = true
        for (id in list) {
            if (!first) out.byte(44)
            first = false
            opId(out, id)
        }
        out.byte(93)
    }

    private fun strs(out: ByteSink, set: Set<String>) {
        out.byte(91)
        val list: Collection<String> = if (set.size > 1) set.sorted() else set
        var first = true
        for (x in list) {
            if (!first) out.byte(44)
            first = false
            out.quoted(x)
        }
        out.byte(93)
    }

    private fun orSet(out: ByteSink, s: OrSet) {
        out.ascii("{\"adds\":")
        ids(out, s.adds)
        out.ascii(",\"removes\":")
        ids(out, s.removes)
        out.byte(125)
    }

    private fun reg(out: ByteSink, r: Register) {
        out.ascii("{\"hlc\":")
        if (r.hlc !== lastHlc) {
            val tmp = ByteSink(96)
            tmp.quoted(r.hlc.wire())
            lastHlc = r.hlc
            lastHlcBytes = tmp.buf.copyOf(tmp.size)
        }
        out.bytes(lastHlcBytes)
        out.ascii(",\"op\":")
        opId(out, r.op)
        out.ascii(",\"value\":")
        out.jv(r.value)
        out.byte(125)
    }

    private fun regs(out: ByteSink, m: Map<String, Register>) {
        out.byte(123)
        var first = true
        for (e in sortedEntries(m)) {
            if (!first) out.byte(44)
            first = false
            out.quoted(e.key)
            out.byte(58)
            reg(out, e.value)
        }
        out.byte(125)
    }

    private fun regMaps(out: ByteSink, m: Map<String, Map<String, Register>>) {
        out.byte(123)
        var first = true
        for (e in sortedEntries(m)) {
            if (!first) out.byte(44)
            first = false
            out.quoted(e.key)
            out.byte(58)
            regs(out, e.value)
        }
        out.byte(125)
    }

    private fun entities(out: ByteSink, m: Map<String, EntityState>) {
        out.byte(123)
        var first = true
        for (e in sortedEntries(m)) {
            if (!first) out.byte(44)
            first = false
            out.quoted(e.key)
            out.ascii(":{\"exists\":")
            orSet(out, e.value.exists)
            out.ascii(",\"fields\":")
            regs(out, e.value.fields)
            out.byte(125)
        }
        out.byte(125)
    }

    private fun revisions(out: ByteSink, m: Map<OpId, RevisionEvent>) {
        out.byte(123)
        if (m.isNotEmpty()) {
            // the keys are the op ids as text, in text order (which is not OpId order)
            val rows = ArrayList<Pair<String, RevisionEvent>>(m.size)
            for ((k, v) in m) rows.add(Pair(k.toString(), v))
            if (rows.size > 1) rows.sortWith { a, b -> a.first.compareTo(b.first) }
            var first = true
            for ((key, e) in rows) {
                if (!first) out.byte(44)
                first = false
                out.quoted(key)
                out.ascii(":{\"content\":")
                out.quoted(e.content)
                out.ascii(",\"parents\":")
                ids(out, e.parents)
                out.ascii(",\"size\":")
                require(e.size >= -MAX_SAFE_INTEGER && e.size <= MAX_SAFE_INTEGER) { "number out of range: ${e.size}" }
                out.long(e.size)
                out.byte(125)
            }
        }
        out.byte(125)
    }
}

/**
 * Reads the canonical JSON line of a checkpoint body from `bytes[from, to)` (already known to be well-formed UTF-8)
 * into a [State]. Returns null unless the text is exactly the canonical encoding of the state it describes:
 * the same keys in the same order, strictly ascending map keys, strictly ascending sorted sets, integers in
 * canonical form, strings escaped exactly as `jsonQuote` escapes them, and no whitespace.
 */
internal class StateReader(private val b: ByteArray, from: Int, private val end: Int) {
    private var p = from
    private var chars = CharArray(128)
    private val actors = ArrayList<String>()

    private class Bail : RuntimeException()

    private fun bail(): Nothing = throw BAIL

    fun read(): State? {
        return try {
            val s = readState()
            if (p == end) s else null
        } catch (e: Bail) {
            null
        } catch (e: IndexOutOfBoundsException) {
            null
        }
    }

    // ---- tokens ----

    private fun cur(): Int = if (p < end) b[p].toInt() and 0xff else bail()

    private fun expect(c: Int) {
        if (p >= end || (b[p].toInt() and 0xff) != c) bail()
        p++
    }

    /** The exact ASCII text [s] (property names with their quotes and colon, or punctuation). */
    private fun lit(s: String) {
        val n = s.length
        if (p + n > end) bail()
        for (i in 0 until n) if ((b[p + i].toInt() and 0xff) != s[i].code) bail()
        p += n
    }

    // ---- strings ----

    private fun readString(): String {
        expect(34)
        val start = p
        var i = p
        while (i < end) {
            val c = b[i].toInt()
            if (c == 34) {
                p = i + 1
                return ascii(start, i)
            }
            if (c == 92 || c < 0) break
            if (c < 0x20) bail()
            i++
        }
        if (i >= end) bail()
        return readStringSlow(start, i)
    }

    /** Short strings (field names, small values) repeat constantly; the last string seen in each slot is reused. */
    private val interned = arrayOfNulls<String>(4096)

    private fun ascii(from: Int, to: Int): String {
        val n = to - from
        if (n == 0) return ""
        var slot = -1
        if (n <= 40) {
            var h = n
            for (k in from until to) h = h * 31 + b[k]
            slot = (h xor (h ushr 12)) and 4095
            val known = interned[slot]
            if (known != null && known.length == n) {
                var same = true
                for (k in 0 until n) if (known[k].code != b[from + k].toInt()) { same = false; break }
                if (same) return known
            }
        }
        val made = asciiString(b, from, to)
        if (slot >= 0) interned[slot] = made
        return made
    }

    /** A string with an escape or a non-ASCII character: [plainEnd] is where the plain run from [start] stopped. */
    private fun readStringSlow(start: Int, plainEnd: Int): String {
        val sb = StringBuilder()
        for (k in start until plainEnd) sb.append(Char(b[k].toInt() and 0x7f))
        p = plainEnd
        var prevHighEscape = false
        while (true) {
            if (p >= end) bail()
            val c = b[p].toInt() and 0xff
            p++
            if (c == 34) return sb.toString()
            if (c < 0x20) bail()
            if (c < 0x80) {
                if (c != 92) {
                    sb.append(c.toChar())
                    prevHighEscape = false
                    continue
                }
                if (p >= end) bail()
                val e = b[p].toInt() and 0xff
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
                            // the short forms are what the encoder writes for these
                            if (code == 8 || code == 9 || code == 10 || code == 12 || code == 13) bail()
                            prevHighEscape = false
                        } else if (code >= 0xD800 && code <= 0xDFFF) {
                            // only a lone surrogate is escaped; a pair is written as the character itself
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
                continue
            }
            prevHighEscape = false
            // a multi-byte character; the bytes are known to be well-formed
            if (c < 0xE0) {
                if (p >= end) bail()
                sb.append((((c and 0x1F) shl 6) or (b[p].toInt() and 0x3F)).toChar())
                p += 1
            } else if (c < 0xF0) {
                if (p + 1 >= end) bail()
                sb.append((((c and 0x0F) shl 12) or ((b[p].toInt() and 0x3F) shl 6) or (b[p + 1].toInt() and 0x3F)).toChar())
                p += 2
            } else {
                if (p + 2 >= end) bail()
                val cp = ((c and 0x07) shl 18) or ((b[p].toInt() and 0x3F) shl 12) or ((b[p + 1].toInt() and 0x3F) shl 6) or (b[p + 2].toInt() and 0x3F)
                val v = cp - 0x10000
                sb.append((0xD800 + (v shr 10)).toChar())
                sb.append((0xDC00 + (v and 0x3FF)).toChar())
                p += 3
            }
        }
    }

    private fun hex4(): Int {
        if (p + 4 > end) bail()
        var code = 0
        for (k in 0 until 4) {
            val c = b[p + k].toInt() and 0xff
            val d = when {
                c >= 48 && c <= 57 -> c - 48
                c >= 97 && c <= 102 -> c - 87
                else -> bail() // the encoder writes lower-case digits only
            }
            code = code * 16 + d
        }
        p += 4
        return code
    }

    // ---- numbers ----

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
            if (neg) bail() // "-0" is not canonical
            if (p < end && b[p] >= 48 && b[p] <= 57) bail() // no leading zeros
            return 0L
        }
        var d = 0.0
        var digits = 0
        while (p < end) {
            val c = b[p].toInt()
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

    // ---- ids and clocks ----

    private fun hexOrDash(i: Int, c: Int): Boolean {
        val dash = i == 8 || i == 13 || i == 18 || i == 23
        return if (dash) c == 45 else (c >= 48 && c <= 57) || (c >= 97 && c <= 102)
    }

    /** The 36-character lower-case UUID at [at]; the same few actors repeat, so their strings are shared. */
    private fun uuidAt(at: Int): String {
        if (at + 36 > end) bail()
        var k = actors.size - 1
        while (k >= 0) {
            val s = actors[k]
            var same = true
            for (i in 0 until 36) {
                if (s[i].code != (b[at + i].toInt() and 0xff)) { same = false; break }
            }
            if (same) return s
            k--
        }
        for (i in 0 until 36) if (!hexOrDash(i, b[at + i].toInt() and 0xff)) bail()
        val s = ascii(at, at + 36)
        if (actors.size < 64) actors.add(s)
        return s
    }

    /** `actor:seq` at the cursor, without quotes (the text `OpId.parse` accepts). */
    private fun opIdBody(): OpId {
        val actor = uuidAt(p)
        if (p + 37 >= end || b[p + 36].toInt() != 58) bail()
        var q = p + 37
        val first = b[q].toInt()
        if (first < 49 || first > 57) bail() // a seq starts with 1-9
        var d = 0.0
        var digits = 0
        while (q < end) {
            val c = b[q].toInt()
            if (c < 48 || c > 57) break
            d = d * 10.0 + (c - 48)
            digits++
            if (digits > 16) bail()
            q++
        }
        if (d > 9007199254740991.0) bail()
        p = q
        return OpId(actor, d.toLong())
    }

    private var lastOp: OpId? = null
    private var lastOpStart = 0
    private var lastOpLen = 0
    private var lastHlc: Hlc? = null
    private var lastHlcStart = 0

    private fun sameBytes(a: Int, c: Int, n: Int): Boolean {
        for (i in 0 until n) if (b[a + i] != b[c + i]) return false
        return true
    }

    private fun readOpId(): OpId {
        expect(34)
        val start = p
        val last = lastOp
        // the same bytes as the op id just read (an item's fields and tags usually come from one op): the same id
        if (last != null && start + lastOpLen < end && b[start + lastOpLen].toInt() == 34 && sameBytes(lastOpStart, start, lastOpLen)) {
            p = start + lastOpLen
            expect(34)
            return last
        }
        val id = opIdBody()
        expect(34)
        lastOp = id
        lastOpStart = start
        lastOpLen = p - 1 - start
        return id
    }

    private fun readHlc(): Hlc {
        expect(34)
        if (p + 13 + 1 + 6 + 1 + 36 >= end) bail()
        val prev = lastHlc
        if (prev != null && sameBytes(lastHlcStart, p, 57)) {
            p += 57
            expect(34)
            return prev
        }
        val startAt = p
        var wall = 0.0
        for (i in 0 until 13) {
            val c = b[p + i].toInt()
            if (c < 48 || c > 57) bail()
            wall = wall * 10.0 + (c - 48)
        }
        if (b[p + 13].toInt() != 46) bail()
        var counter = 0
        for (i in 14 until 20) {
            val c = b[p + i].toInt()
            if (c < 48 || c > 57) bail()
            counter = counter * 10 + (c - 48)
        }
        if (b[p + 20].toInt() != 46) bail()
        val actor = uuidAt(p + 21)
        p += 57
        expect(34)
        val h = Hlc(wall.toLong(), counter, actor)
        lastHlc = h
        lastHlcStart = startAt
        return h
    }

    // ---- sets ----

    private fun readIds(): Set<OpId> {
        expect(91)
        if (cur() == 93) {
            p++
            return emptySet()
        }
        val out = LinkedHashSet<OpId>()
        var prev: OpId? = null
        while (true) {
            val id = readOpId()
            if (prev != null && id.compareTo(prev) <= 0) bail() // ascending and unique, as the encoder sorts them
            prev = id
            out.add(id)
            val c = cur()
            p++
            if (c == 93) return compactSet(out)
            if (c != 44) bail()
        }
    }

    private fun readStrs(): Set<String> {
        expect(91)
        if (cur() == 93) {
            p++
            return emptySet()
        }
        val out = LinkedHashSet<String>()
        var prev: String? = null
        while (true) {
            val s = readString()
            if (prev != null && s.compareTo(prev) <= 0) bail()
            prev = s
            out.add(s)
            val c = cur()
            p++
            if (c == 93) return out
            if (c != 44) bail()
        }
    }

    private fun readOrSet(): OrSet {
        lit("{\"adds\":")
        val adds = readIds()
        lit(",\"removes\":")
        val removes = readIds()
        expect(125)
        return OrSet(adds, removes)
    }

    // ---- maps with arbitrary keys in strictly ascending order ----

    private inline fun members(each: (String) -> Unit) {
        expect(123)
        if (cur() == 125) {
            p++
            return
        }
        var prev: String? = null
        while (true) {
            val k = readString()
            if (prev != null && k.compareTo(prev) <= 0) bail()
            prev = k
            expect(58)
            each(k)
            val c = cur()
            p++
            if (c == 125) return
            if (c != 44) bail()
        }
    }

    private fun readReg(): Register {
        lit("{\"hlc\":")
        val hlc = readHlc()
        lit(",\"op\":")
        val op = readOpId()
        lit(",\"value\":")
        val value = readJv(0)
        expect(125)
        return Register(hlc, op, value)
    }

    private fun readRegs(): Map<String, Register> {
        val out = LinkedHashMap<String, Register>()
        members { k -> out[k] = readReg() }
        return compactMap(out)
    }

    private fun readRegMaps(): Map<String, Map<String, Register>> {
        val out = LinkedHashMap<String, Map<String, Register>>()
        members { k -> out[k] = readRegs() }
        return out
    }

    private fun readEntities(): Map<String, EntityState> {
        val out = LinkedHashMap<String, EntityState>()
        members { k ->
            lit("{\"exists\":")
            val exists = readOrSet()
            lit(",\"fields\":")
            val fields = readRegs()
            expect(125)
            out[k] = EntityState(exists, fields)
        }
        return out
    }

    /** Revision keys are op ids (ASCII), so ascending text order is plain byte order. */
    private fun readRevisions(): Map<OpId, RevisionEvent> {
        val out = LinkedHashMap<OpId, RevisionEvent>()
        expect(123)
        if (cur() == 125) {
            p++
            return out
        }
        var prevStart = -1
        var prevEnd = -1
        while (true) {
            expect(34)
            val ks = p
            val id = opIdBody()
            val ke = p
            expect(34)
            if (prevStart >= 0 && compareRanges(ks, ke, prevStart, prevEnd) <= 0) bail()
            prevStart = ks
            prevEnd = ke
            expect(58)
            lit("{\"content\":")
            val content = readString()
            lit(",\"parents\":")
            val parents = readIds()
            lit(",\"size\":")
            val size = readLong()
            expect(125)
            out[id] = RevisionEvent(content, size, parents)
            val c = cur()
            p++
            if (c == 125) return compactMap(out)
            if (c != 44) bail()
        }
    }

    private fun compareRanges(aFrom: Int, aTo: Int, bFrom: Int, bTo: Int): Int {
        val n = minOf(aTo - aFrom, bTo - bFrom)
        for (i in 0 until n) {
            val x = b[aFrom + i].toInt() and 0xff
            val y = b[bFrom + i].toInt() and 0xff
            if (x != y) return x - y
        }
        return (aTo - aFrom) - (bTo - bFrom)
    }

    // ---- generic JSON values (a register's value) ----

    private fun readJv(depth: Int): Jv {
        if (depth > 200) bail() // deeper than any real value; the reference route decides
        return when (cur()) {
            123 -> {
                val out = LinkedHashMap<String, Jv>()
                members { k -> out[k] = readJv(depth + 1) }
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

    // ---- the state ----

    private fun readState(): State {
        lit("{\"entries\":")
        val entries = LinkedHashMap<String, EntryFacts>()
        members { k ->
            lit("{\"item\":")
            val item = readString()
            lit(",\"op\":")
            val op = readOpId()
            lit(",\"playlist\":")
            val playlist = readString()
            expect(125)
            entries[k] = EntryFacts(playlist, item, op)
        }
        lit(",\"entryKey\":")
        val entryKey = readRegs()
        lit(",\"findings\":")
        val findings = readStrs()
        lit(",\"items\":")
        val items = LinkedHashMap<String, ItemState>(((end - p) / 700).coerceIn(8, 1 shl 20))
        members { k ->
            lit("{\"deletedHeads\":")
            val deletedHeads = readIds()
            lit(",\"exists\":")
            val exists = readOrSet()
            lit(",\"fields\":")
            val fields = readRegs()
            lit(",\"revisions\":")
            val revisions = readRevisions()
            expect(125)
            items[k] = ItemState(exists, fields, revisions, deletedHeads)
        }
        lit(",\"placements\":")
        val placements = readRegMaps()
        lit(",\"places\":")
        val places = readRegMaps()
        lit(",\"playlists\":")
        val playlists = readEntities()
        lit(",\"profile\":")
        val profile = readRegs()
        lit(",\"removedEntries\":")
        val removedEntries = readStrs()
        lit(",\"sets\":")
        val sets = LinkedHashMap<String, Map<String, OrSet>>()
        members { k ->
            val els = LinkedHashMap<String, OrSet>()
            members { e -> els[e] = readOrSet() }
            sets[k] = els
        }
        lit(",\"spaces\":")
        val spaces = readEntities()
        expect(125)
        return State(
            items = items,
            sets = sets,
            playlists = playlists,
            spaces = spaces,
            entries = entries,
            entryKey = entryKey,
            removedEntries = removedEntries,
            placements = placements,
            places = places,
            profile = profile,
            findings = findings,
        )
    }

    private companion object {
        val BAIL = Bail()
    }
}
