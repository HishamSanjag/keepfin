package app.keepfin.core.sync

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Sha256AndEncodingTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun problemOf(bytes: ByteArray): CanonicalProblem =
        (decodeCanonicalBytes(bytes) as? CanonicalResult.Bad)?.problem ?: error("accepted")

    private fun problemOf(text: String) = problemOf(text.encodeToByteArray())

    @Test
    fun nistVectors() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(""))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc"))
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
        assertEquals(
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
            sha256Hex(ByteArray(1_000_000) { 'a'.code.toByte() }),
        )
    }

    @Test
    fun blockBoundaries() {
        val seen = HashSet<String>()
        for (n in 54..70) assertEquals(true, seen.add(sha256Hex(ByteArray(n) { 'x'.code.toByte() })))
        assertEquals("sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", contentIdOf("abc".encodeToByteArray()))
    }

    @Test
    fun literalEncodingVectors() {
        // supplementary characters are written as themselves, in UTF-8
        assertContentEquals(bytes(0x22, 0xF0, 0x9F, 0x98, 0x80, 0x22), encodeCanonicalBytes(JStr("\uD83D\uDE00")))
        // lone surrogates of both kinds are escaped, lower-case hex
        assertEquals("\"\\ud800\"", encodeCanonical(JStr("\uD800")))
        assertEquals("\"\\udc00\"", encodeCanonical(JStr("\uDC00")))
        assertEquals("\"\\udc00\\ud800\"", encodeCanonical(JStr("\uDC00\uD800"))) // reversed pair is two lone units
        assertEquals("\"a\\ud800b\"", encodeCanonical(JStr("a\uD800b")))
        // normalization-distinct strings stay distinct
        assertContentEquals(bytes(0x22, 0xC3, 0xA9, 0x22), encodeCanonicalBytes(JStr("\u00e9")))
        assertContentEquals(bytes(0x22, 0x65, 0xCC, 0x81, 0x22), encodeCanonicalBytes(JStr("e\u0301")))
        val composed = (decodeCanonicalBytes(bytes(0x22, 0xC3, 0xA9, 0x22)) as CanonicalResult.Ok).value
        val decomposed = (decodeCanonicalBytes(bytes(0x22, 0x65, 0xCC, 0x81, 0x22)) as CanonicalResult.Ok).value
        assertEquals(JStr("\u00e9"), composed)
        assertEquals(JStr("e\u0301"), decomposed)
        assertTrue(composed != decomposed)
        // controls and escapes
        assertEquals("\"\\b\\f\\n\\r\\t\\u0000\\u001f\\\"\\\\\"", encodeCanonical(JStr("\u0008\u000c\n\r\t\u0000\u001f\"\\")))
        assertEquals("\"\u007f\u00a0\u2028\"", encodeCanonical(JStr("\u007f\u00a0\u2028")))
        // keys sort by UTF-16 code unit (U+1F600 starts with D83D, which is below U+FF5E)
        assertEquals("{\"\uD83D\uDE00\":1,\"\uFF5E\":2}", encodeCanonical(jObj("\uFF5E" to JNum(2), "\uD83D\uDE00" to JNum(1))))
        assertEquals("{\"B\":1,\"a\":2}", encodeCanonical(jObj("a" to JNum(2), "B" to JNum(1))))
        // numbers
        assertEquals("9007199254740991", encodeCanonical(JNum(MAX_SAFE_INTEGER)))
        assertEquals("-9007199254740991", encodeCanonical(JNum(-MAX_SAFE_INTEGER)))
        assertEquals("0", encodeCanonical(JNum(0)))
        assertFailsWith<IllegalArgumentException> { JNum(MAX_SAFE_INTEGER + 1) }
        assertFailsWith<IllegalArgumentException> { JNum(-MAX_SAFE_INTEGER - 1) }
        assertEquals(JNum(MAX_SAFE_INTEGER), (decodeCanonical("9007199254740991") as CanonicalResult.Ok).value)
        assertEquals(JNum(-MAX_SAFE_INTEGER), (decodeCanonical("-9007199254740991") as CanonicalResult.Ok).value)
        assertEquals(CanonicalProblem.BadNumber, problemOf("9007199254740992"))
        assertEquals(CanonicalProblem.BadNumber, problemOf("-9007199254740992"))
        assertEquals(CanonicalProblem.BadNumber, problemOf("1.5"))
        assertEquals(CanonicalProblem.NotCanonical, problemOf("-0"))
        assertEquals(CanonicalProblem.NotCanonical, problemOf("1.0"))
        assertEquals(CanonicalProblem.NotCanonical, problemOf("1e2"))
        assertEquals(CanonicalProblem.NotCanonical, problemOf("1E2"))
        assertEquals(CanonicalProblem.Syntax, problemOf("+1"))
        assertEquals(CanonicalProblem.Syntax, problemOf("01"))
        // other escapes and whitespace
        assertEquals(CanonicalProblem.NotCanonical, problemOf("\"\\u00e9\""))
        assertEquals(CanonicalProblem.NotCanonical, problemOf("\"\\ud83d\\ude00\""))
        assertEquals(CanonicalProblem.NotCanonical, problemOf("\"\\uD800\""))
        assertEquals(CanonicalProblem.NotCanonical, problemOf("\"\\/\""))
        assertTrue(decodeCanonical("\"\\ud800\"") is CanonicalResult.Ok)
        assertTrue(decodeCanonical("\"\\ud800\\u0001\"") is CanonicalResult.Ok)
        assertEquals(CanonicalProblem.NotCanonical, problemOf("[1, 2]"))
        assertEquals(CanonicalProblem.NotCanonical, problemOf("{\"b\":1,\"a\":2}"))
        assertEquals(CanonicalProblem.NotCanonical, problemOf(" 1"))
        // duplicate keys, also spelled with an escape
        assertEquals(CanonicalProblem.DuplicateKey, problemOf("{\"a\":1,\"a\":2}"))
        assertEquals(CanonicalProblem.DuplicateKey, problemOf("{\"a\":1,\"\\u0061\":2}"))
        assertEquals(CanonicalProblem.DuplicateKey, problemOf("{\"x\":{\"a\":1,\"a\":1}}"))
        assertEquals(CanonicalProblem.Syntax, problemOf("{"))
    }

    @Test
    fun malformedUtf8AndBomAreRejected() {
        assertEquals(CanonicalProblem.MalformedUtf8, problemOf(bytes(0x22, 0xC3, 0x28, 0x22))) // bad continuation
        assertEquals(CanonicalProblem.MalformedUtf8, problemOf(bytes(0x22, 0xFF, 0x22)))
        assertEquals(CanonicalProblem.MalformedUtf8, problemOf(bytes(0x22, 0xC0, 0xAF, 0x22))) // overlong
        assertEquals(CanonicalProblem.MalformedUtf8, problemOf(bytes(0x22, 0xED, 0xA0, 0x80, 0x22))) // UTF-8 encoded surrogate
        assertEquals(CanonicalProblem.MalformedUtf8, problemOf(bytes(0x22, 0xF0, 0x9F, 0x98, 0x22))) // truncated
        assertEquals(CanonicalProblem.Bom, problemOf(bytes(0xEF, 0xBB, 0xBF, 0x31)))
        assertEquals(CanonicalProblem.Bom, problemOf(bytes(0xEF, 0xBB, 0xBF, 0x22, 0x22)))
        assertTrue(decodeCanonicalBytes(bytes(0x22, 0xE2, 0x82, 0xAC, 0x22)) is CanonicalResult.Ok) // euro sign
        assertTrue(decodeOp(bytes(0xFF)) is OpDecode.Bad)
    }

    @Test
    fun fixedSeedOpsEncodeToTheSameCanonicalTextOnEveryTarget() {
        val ops = OpGen(4242, 4).generate(300)
        val sb = StringBuilder()
        for (op in ops) sb.append(op.encode()).append('\n')
        val digest = sha256Hex(sb.toString())
        assertEquals(PARITY_DIGEST, digest, "update PARITY_DIGEST only for an intended format change")
        // each line decodes back to the same op, strictly
        for (op in ops) assertEquals(op, (decodeOp(op.encodeBytes()) as OpDecode.Ok).op)
    }

    companion object {
        const val PARITY_DIGEST = "2af3a2cd2040a52cdce09ad27ef56a2b59b46b335850f7df0aa798b005121929"
    }
}
