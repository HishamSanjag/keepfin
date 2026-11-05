package app.keepfin.core.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The fast checkpoint routes (byte writer, one-pass strict reader, in-place SHA-256, single-pass UTF-8 scan) against
 * the reference routes they replace: same bytes out, same files accepted, same files rejected with the same error.
 */
class CheckpointFastTest {
    private val lib = TEST_LIB

    // ---- SHA-256 ----

    @Test
    fun inPlaceShaMatchesTheReferenceForEveryTailLength() {
        val rnd = Random(7)
        for (n in 0..300) {
            val data = rnd.nextBytes(n)
            assertEquals(sha256HexReference(data), sha256HexRange(data, 0, n), "length $n")
            if (n >= 5) {
                val inner = data.copyOfRange(2, n - 1)
                assertEquals(sha256HexReference(inner), sha256HexRange(data, 2, n - 1), "range of length ${inner.size}")
            }
        }
        val big = rnd.nextBytes(300_017)
        assertEquals(sha256HexReference(big), sha256HexRange(big, 0, big.size))
        assertContentEquals(sha256Reference(big), sha256(big))
        // the known vector for "abc"
        val abc = "abc".encodeToByteArray()
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256HexRange(abc, 0, 3))
    }

    // ---- UTF-8 ----

    private fun strictOk(bytes: ByteArray) = strictUtf8(bytes) != null

    private fun check(bytes: ByteArray) {
        val ours = scanUtf8(bytes, 0, bytes.size)
        val expected = strictOk(bytes)
        assertEquals(expected, ours != UTF8_MALFORMED, "bytes " + bytes.joinToString(",") { (it.toInt() and 0xff).toString(16) })
    }

    @Test
    fun theUtf8ScanAgreesWithTheStrictDecoder() {
        for (a in 0..255) check(byteArrayOf(a.toByte()))
        for (a in 0x80..255) for (b in 0..255) check(byteArrayOf(a.toByte(), b.toByte()))
        val seconds = listOf(0x00, 0x7F, 0x80, 0x8F, 0x90, 0x9F, 0xA0, 0xBF, 0xC0, 0xFF)
        for (a in 0xC0..0xFF) for (b in seconds) for (c in seconds) {
            check(byteArrayOf(a.toByte(), b.toByte(), c.toByte()))
            for (d in seconds) check(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()))
        }
        // well-formed text with all lengths, and the same truncated at every byte
        val text = "a é ߿ ࠀ ퟿  ￿ 😀 􏿿 x"
        val bytes = text.encodeToByteArray()
        check(bytes)
        for (cut in 0 until bytes.size) check(bytes.copyOfRange(0, cut))
        assertEquals(UTF8_HAS_CR, scanUtf8("a\rb".encodeToByteArray(), 0, 3))
        assertEquals(UTF8_MALFORMED, scanUtf8(byteArrayOf(13, 0xC0.toByte(), 0x80.toByte()), 0, 3))
        assertEquals(UTF8_OK, scanUtf8("a\nb".encodeToByteArray(), 0, 3))
    }

    // ---- the writer ----

    private fun weirdLog(): OpLog {
        val weird = "q \" \\ / \u0001 \u001f \u007f   é ߿ ࠀ ￿ 😀 lone \uD800 and \uDC00 x 􏿿 \uD83D"
        val ops = ArrayList<Op>()
        ops.add(add(A, 1, fields = mapOf("media" to JStr("audio"), "title" to JStr(weird))))
        ops.add(setField(A, 2, 2, "artist", JStr(weird)))
        ops.add(setField(B, 1, 3, weird, jObj("z" to JNum(-5), weird to JArr(listOf(JNum(0), JNum(MAX_SAFE_INTEGER), JNum(-MAX_SAFE_INTEGER), JBool(true), JNull)), "a" to JObj(emptyMap()))))
        ops.add(op(B, 2, 4, "profile.set", "field" to JStr("name"), "value" to JStr(weird)))
        return logOf(ops)
    }

    private fun states(): List<Pair<String, State>> {
        val out = ArrayList<Pair<String, State>>()
        out.add("empty" to State.EMPTY)
        out.add("weird" to weirdLog().state)
        for (seed in 1..40) out.add("gen$seed" to logOf(OpGen(seed, 4).generate(60 + seed * 4)).state)
        return out
    }

    @Test
    fun theWriterGivesTheReferenceBytes() {
        for ((name, state) in states()) {
            val sink = ByteSink(16) // a tiny start proves growth
            StateWriter.write(sink, state)
            val expected = encodeCanonical(StateCodec.toJson(state)).encodeToByteArray()
            assertContentEquals(expected, sink.buf.copyOf(sink.size), name)
        }
    }

    @Test
    fun encodedCheckpointsMatchTheReferenceFileAndRoundTrip() {
        for ((name, state) in states()) {
            val cp = Checkpoint(lib, SEMANTICS, mapOf(A to 3L, B to 9L), Hlc(5, 1, A), state)
            val bytes = encodeCheckpoint(cp)
            val body = encodeCanonical(StateCodec.toJson(state)) + "\n"
            val header = jObj(
                "coverage" to JObj(cp.coverage.mapValues { JNum(it.value) }),
                "library" to JStr(cp.library),
                "maxHlc" to JStr(cp.maxHlc!!.wire()),
                "semantics" to JNum(1),
                "sha256" to JStr(sha256HexReference(body.encodeToByteArray())),
            )
            assertContentEquals(joinFrame("KFCKP", encodeCanonical(header), body), bytes, name)
            val fast = decodeCheckpoint(bytes, lib) as CheckpointResult.Ok
            val slow = decodeCheckpointReference(bytes, lib) as CheckpointResult.Ok
            assertEquals(cp, fast.checkpoint, name)
            assertEquals(cp, slow.checkpoint, name)
        }
    }

    /** The fast route really is the one taking canonical files (not the fallback). */
    @Test
    fun theFastReaderAcceptsEveryEncodedState() {
        for ((name, state) in states()) {
            val sink = ByteSink()
            StateWriter.write(sink, state)
            val read = StateReader(sink.buf, 0, sink.size).read()
            assertEquals(state, assertNotNull(read, name), name)
        }
    }

    // ---- strictness ----

    private fun sample(): ByteArray {
        val cp = logOf(OpGen(11, 3).generate(150)).checkpoint(lib)
        val bytes = encodeCheckpoint(cp)
        val text = bytes.decodeToString()
        assertTrue(text.contains("\"revisions\":{\""), "sample has revisions")
        assertTrue(text.contains("\"entries\":{\""), "sample has entries")
        assertTrue(text.contains("\"sets\":{\""), "sample has sets")
        return bytes
    }

    /** Replaces the body line of a valid file with [body] and fixes the checksum, so only the body is at issue. */
    private fun withBody(file: ByteArray, body: ByteArray): ByteArray {
        var n1 = 0
        while (file[n1] != 10.toByte()) n1++
        var n2 = n1 + 1
        while (file[n2] != 10.toByte()) n2++
        val header = file.decodeToString(n1 + 1, n2)
        val old = header.substringAfter("\"sha256\":\"").substringBefore("\"")
        val fixed = header.replace(old, sha256HexReference(body))
        return (file.decodeToString(0, n1 + 1) + fixed + "\n").encodeToByteArray() + body
    }

    private fun bodyOf(file: ByteArray): ByteArray {
        var n = 0
        var lines = 0
        while (lines < 2) {
            if (file[n] == 10.toByte()) lines++
            n++
        }
        return file.copyOfRange(n, file.size)
    }

    private fun sameOutcome(file: ByteArray, label: String): CheckpointResult {
        val fast = decodeCheckpoint(file, lib)
        val slow = decodeCheckpointReference(file, lib)
        when (slow) {
            is CheckpointResult.Ok -> {
                if (fast !is CheckpointResult.Ok) fail("$label: the fast route rejected a file the reference accepts (${(fast as CheckpointResult.Err).error})")
                assertEquals(slow.checkpoint, fast.checkpoint, label)
            }
            is CheckpointResult.Err -> {
                if (fast !is CheckpointResult.Err) fail("$label: the fast route accepted a file the reference rejects (${slow.error})")
                assertEquals(slow.error::class, fast.error::class, label)
            }
        }
        return fast
    }

    /** Rewrites the first match of [pattern] in [text] with [f]; the text is unchanged when nothing matches. */
    private fun rx(text: String, pattern: String, f: (String) -> String): String {
        val m = Regex(pattern).find(text) ?: return text
        return text.substring(0, m.range.first) + f(m.value) + text.substring(m.range.last + 1)
    }

    private fun mutateBody(file: ByteArray, transform: (String) -> String): ByteArray? {
        val body = bodyOf(file).decodeToString()
        val changed = transform(body)
        if (changed == body) return null
        return withBody(file, changed.encodeToByteArray())
    }

    @Test
    fun theUnmutatedFileIsAccepted() {
        assertTrue(sameOutcome(sample(), "plain") is CheckpointResult.Ok)
    }

    @Test
    fun nonCanonicalFormsAreRejectedExactlyAsBefore() {
        val file = sample()
        val cases = LinkedHashMap<String, (String) -> String>()
        cases["space after colon"] = { it.replaceFirst("\"entries\":", "\"entries\": ") }
        cases["space after comma"] = { it.replaceFirst("\"findings\":[", "\"findings\": [").replaceFirst(",\"items\"", ", \"items\"") }
        cases["newline in the body"] = { it.replaceFirst(",\"items\"", ",\n\"items\"") }
        cases["tab"] = { it.replaceFirst(",\"items\"", ",\t\"items\"") }
        cases["leading space"] = { " $it" }
        cases["key order: items before findings"] = {
            val a = it.indexOf("\"findings\":")
            val b = it.indexOf("\"items\":")
            val c = it.indexOf("\"placements\":")
            it.substring(0, a) + it.substring(b, c) + it.substring(a, b) + it.substring(c)
        }
        cases["key order inside an or-set"] = { it.replaceFirst("{\"adds\":[", "{\"removes\":[],\"adds\":[") }
        cases["duplicate key"] = { it.replaceFirst("\"entryKey\":{", "\"entryKey\":{},\"entryKey\":{") }
        cases["duplicate key at the end"] = { it.dropLast(2) + ",\"spaces\":{}}\n" }
        cases["extra key"] = { it.replaceFirst("{\"entries\":", "{\"aaa\":1,\"entries\":") }
        cases["missing key"] = { it.replaceFirst("\"findings\":[],", "") }
        cases["number 1.0"] = { rx(it, "\"size\":[0-9]+") { m -> m + ".0" } }
        cases["number 01"] = { rx(it, "\"size\":[1-9]") { m -> m.replace(":", ":0") } }
        cases["number -0"] = { rx(it, "\"size\":0") { m -> m.replace(":", ":-") } }
        cases["number 1e2"] = { rx(it, "\"size\":[1-9]") { m -> m + "e2" } }
        cases["number +1"] = { rx(it, "\"size\":[1-9]") { m -> m.replace(":", ":+") } }
        cases["number out of range"] = { rx(it, "\"size\":[0-9]+") { "\"size\":9007199254740992" } }
        cases["number too long"] = { rx(it, "\"size\":[0-9]+") { "\"size\":12345678901234567890" } }
        cases["upper-case uuid"] = { rx(it, "\"op\":\"[0-9a-f]{3}") { "\"op\":\"ABC" } }
        cases["seq with a leading zero"] = { rx(it, "\"op\":\"[0-9a-f-]{36}:[1-9]") { m -> m.dropLast(1) + "0" + m.last() } }
        cases["seq zero"] = { rx(it, "\"op\":\"[0-9a-f-]{36}:[1-9][0-9]*") { m -> m.substring(0, m.indexOf(':') + 1) + "0" } }
        cases["hlc with a short counter"] = { rx(it, "\"hlc\":\"[0-9]{13}\\.0") { m -> m.dropLast(1) } }
        cases["hlc with an upper-case actor"] = { rx(it, "\"hlc\":\"[0-9]{13}\\.[0-9]{6}\\.[0-9a-f]{3}") { m -> m.dropLast(3) + "ABC" } }
        cases["escape of a plain letter"] = { it.replaceFirst("\"content\":\"s", "\"content\":\"\\u0073") }
        cases["escaped slash"] = { it.replaceFirst("\"content\":\"sha256:", "\"content\":\"sha256\\/") }
        cases["escaped control as long form"] = { it.replaceFirst("\"media\"", "\"med\\u0069a\"") }
        cases["upper-case hex in an escape"] = { it.replace("\\ud800", "\\uD800") }
        cases["surrogate pair written as escapes"] = { it.replaceFirst("\"entries\":{", "\"entries\":{").replace("😀", "\\ud83d\\ude00") }
        cases["raw control character in a string"] = { it.replaceFirst("\"content\":\"s", "\"content\":\"\u0001s") }
        cases["bad escape"] = { it.replaceFirst("\"content\":\"s", "\"content\":\"\\qs") }
        cases["unsorted array"] = {
            val m = Regex("\"adds\":\\[(\"[^\"]+\"),(\"[^\"]+\")").find(it)
            if (m == null) it else it.replaceFirst(m.value, "\"adds\":[" + m.groupValues[2] + "," + m.groupValues[1])
        }
        cases["duplicate array element"] = {
            val m = Regex("\"adds\":\\[(\"[^\"]+\")").find(it)
            if (m == null) it else it.replaceFirst(m.value, m.value + "," + m.groupValues[1])
        }
        cases["unsorted findings"] = { it.replaceFirst("\"findings\":[]", "\"findings\":[\"b\",\"a\"]") }
        cases["trailing garbage in the line"] = { it.dropLast(1) + "x\n" }
        cases["trailing comma"] = { it.replaceFirst("\"findings\":[]", "\"findings\":[],") }
        cases["BOM at the start of the body"] = { "﻿$it" }
        cases["body not an object"] = { "[]\n" }
        cases["empty object"] = { "{}\n" }
        cases["null"] = { "null\n" }
        var rejected = 0
        for ((label, transform) in cases) {
            val mutated = mutateBody(file, transform)
            if (mutated == null) {
                // a case whose pattern is absent from this sample proves nothing; make sure it is rare
                continue
            }
            val r = sameOutcome(mutated, label)
            if (r is CheckpointResult.Err) rejected++
        }
        assertTrue(rejected >= 30, "only $rejected cases were applied and rejected")
    }

    @Test
    fun malformedBytesInTheBodyAreRejectedExactlyAsBefore() {
        val file = sample()
        val body = bodyOf(file)
        val spot = body.decodeToString().indexOf("\"content\":\"s") + 11
        for (bad in listOf(
            byteArrayOf(0xC0.toByte(), 0x80.toByte()), byteArrayOf(0xC3.toByte()), byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte()),
            byteArrayOf(0xF4.toByte(), 0x90.toByte(), 0x80.toByte(), 0x80.toByte()), byteArrayOf(0xFF.toByte()), byteArrayOf(0x80.toByte()),
        )) {
            val mutated = withBody(file, body.copyOfRange(0, spot) + bad + body.copyOfRange(spot, body.size))
            assertTrue(sameOutcome(mutated, "bad bytes " + bad.size) is CheckpointResult.Err)
        }
        // a BOM character inside a string is ordinary well-formed text, and both routes accept it
        val feff = body.copyOfRange(0, spot) + byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + body.copyOfRange(spot, body.size)
        assertTrue(sameOutcome(withBody(file, feff), "feff inside a string") is CheckpointResult.Ok)
        // a carriage return and a bad checksum
        val cr = body.copyOfRange(0, spot) + byteArrayOf(13) + body.copyOfRange(spot, body.size)
        assertTrue(sameOutcome(withBody(file, cr), "cr") is CheckpointResult.Err)
        val flipped = file.copyOf()
        flipped[flipped.size - 3] = if (flipped[flipped.size - 3] == 49.toByte()) 50 else 49
        assertTrue(sameOutcome(flipped, "checksum") is CheckpointResult.Err)
    }

    @Test
    fun randomByteDamageIsDecidedTheSameWayByBothRoutes() {
        val file = sample()
        val body = bodyOf(file)
        val rnd = Random(2024)
        val alphabet = byteArrayOf(32, 34, 44, 58, 91, 93, 123, 125, 92, 48, 49, 45, 97, 117, 0xC3.toByte(), 0xA9.toByte(), 10, 13, 46, 101)
        var accepted = 0
        var rejected = 0
        for (round in 0 until 600) {
            val b = body.toMutableList()
            repeat(1 + rnd.nextInt(2)) {
                val at = rnd.nextInt(b.size - 1) // never the final newline
                when (rnd.nextInt(5)) {
                    0 -> b.removeAt(at)
                    1 -> b[at] = alphabet[rnd.nextInt(alphabet.size)]
                    2 -> b.add(at, alphabet[rnd.nextInt(alphabet.size)])
                    3 -> b.add(at, b[at])
                    else -> if (at + 1 < b.size - 1) { val t = b[at]; b[at] = b[at + 1]; b[at + 1] = t }
                }
            }
            val r = sameOutcome(withBody(file, b.toByteArray()), "round $round")
            if (r is CheckpointResult.Ok) accepted++ else rejected++
        }
        assertTrue(rejected > 400, "rejected $rejected, accepted $accepted")
    }

    @Test
    fun weirdValuesRoundTripThroughTheFastReader() {
        val cp = Checkpoint(lib, SEMANTICS, mapOf(A to 2L, B to 1L), Hlc(4, 0, B), weirdLog().state)
        val bytes = encodeCheckpoint(cp)
        val fast = decodeCheckpoint(bytes, lib) as CheckpointResult.Ok
        assertEquals(cp, fast.checkpoint)
        // and the reader itself accepted it (it did not fall back)
        val body = bodyOf(bytes)
        assertNotNull(StateReader(body, 0, body.size - 1).read())
    }
}
