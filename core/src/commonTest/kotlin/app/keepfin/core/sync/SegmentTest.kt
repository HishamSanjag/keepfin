package app.keepfin.core.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class SegmentTest {
    private val lib = TEST_LIB
    private val actor = A

    private fun ops(n: Int): List<Op> = List(n) { i ->
        Op(
            OpId(actor, i + 1L), Hlc(1000L + i, 0, actor), "profile.set",
            mapOf("field" to JStr("name"), "value" to JStr("café 😀 \uD800 $i")),
        )
    }

    private fun errorOf(bytes: ByteArray, library: String = lib): SegmentError =
        (decodeSegment(bytes, library) as? SegmentResult.Err)?.error ?: fail("segment was accepted")

    private fun errorOf(text: String, library: String = lib): SegmentError = errorOf(text.encodeToByteArray(), library)

    private fun rebuilt(lines: List<String>, count: Long = lines.size.toLong(), first: Long = 1, last: Long = first + count - 1) =
        assembleSegment(lib, actor, count, first, last, lines)

    private fun good(n: Int = 3) = encodeSegment(lib, actor, ops(n))

    @Test
    fun roundTripAndFileName() {
        val input = ops(5)
        val bytes = encodeSegment(lib, actor, input)
        assertTrue(bytes.decodeToString().startsWith("KFSEG 1\n"))
        val r = decodeSegment(bytes, lib) as SegmentResult.Ok
        assertEquals(input, r.ops)
        assertEquals(actor, r.actor)
        assertEquals(1L, r.first)
        assertEquals(5L, r.last)
        assertEquals("0000000000000001-0000000000000005.seg", segmentFileName(1, 5))
        assertEquals("0000000000000042-9007199254740991.seg", segmentFileName(42, MAX_SAFE_INTEGER))
    }

    @Test
    fun aSegmentWithAnInvalidOrUnknownOpStillDecodes() {
        val lines = listOf(
            op(actor, 1, 1, "future.thing", "x" to JNum(1)),
            op(actor, 2, 2, "item.set", "item" to JNum(5)),
            op(actor, 3, 3, "entry.move", "entry" to JStr(PL), "key" to JStr("a00")),
        ).map { it.encode() }
        val r = decodeSegment(rebuilt(lines), lib) as SegmentResult.Ok
        assertEquals(3, r.ops.size)
        val log = OpLog()
        assertTrue(log.admit(r.ops[0]) is Admission.Kept)
        assertTrue(log.admit(r.ops[1]) is Admission.Invalid)
        assertTrue(log.admit(r.ops[2]) is Admission.Invalid)
        assertEquals(mapOf(actor to 3L), log.versionVector)
    }

    @Test
    fun magicAndFormat() {
        val text = good().decodeToString()
        assertTrue(errorOf("KFXXX 1" + text.substring(7)) is SegmentError.BadMagic)
        assertTrue(errorOf("") is SegmentError.BadMagic)
        assertTrue(errorOf("KFSEG 1") is SegmentError.BadMagic)
        val newer = errorOf("KFSEG 3" + text.substring(7))
        assertTrue(newer is SegmentError.NewerFormat)
        assertEquals("3", newer.found)
        assertTrue(errorOf("KFSEG x" + text.substring(7)) is SegmentError.BadMagic)
        assertTrue(errorOf("KFCKP 1" + text.substring(7)) is SegmentError.BadMagic)
    }

    @Test
    fun fileLevelFramingErrors() {
        val text = good().decodeToString()
        // BOM
        assertTrue(errorOf(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.encodeToByteArray()) is SegmentError.Bom)
        // malformed UTF-8 in the body
        val bad = text.encodeToByteArray()
        val at = text.indexOf("name")
        bad[at] = 0xFF.toByte()
        assertTrue(errorOf(bad) is SegmentError.MalformedUtf8)
        // CR
        assertTrue(errorOf("KFSEG 1\n" + text.substring(8).replace("\n", "\r\n")) is SegmentError.CarriageReturn)
        // missing final LF
        assertTrue(errorOf(text.dropLast(1)) is SegmentError.MissingFinalNewline)
        // trailing data: an extra line, or an extra empty line
        val lines = ops(3).map { it.encode() }
        assertTrue(errorOf(assembleSegment(lib, actor, 3, 1, 3, lines + ops(4)[3].encode())) is SegmentError.TrailingData)
        assertTrue(errorOf(assembleSegment(lib, actor, 3, 1, 3, lines + "")) is SegmentError.TrailingData)
        // too large
        val huge = "KFSEG 1\n".encodeToByteArray() + ByteArray(SEGMENT_MAX_BYTES)
        assertTrue(errorOf(huge) is SegmentError.TooLarge)
    }

    @Test
    fun headerAndBounds() {
        val text = good().decodeToString()
        val lines = text.split("\n")
        val rest = lines.drop(2).joinToString("\n")
        assertTrue(errorOf(lines[0] + "\n{not json}\n" + rest) is SegmentError.BadHeader)
        assertTrue(errorOf(lines[0] + "\n" + lines[1].replace("\"count\":3,", "") + "\n" + rest) is SegmentError.BadHeader)
        assertTrue(errorOf(lines[0] + "\n" + lines[1].replace("\"count\":3", "\"count\":\"3\"") + "\n" + rest) is SegmentError.BadHeader)
        assertTrue(errorOf("KFSEG 1\n") is SegmentError.BadHeader)
        val l = ops(2).map { it.encode() }
        assertTrue(errorOf(assembleSegment(lib, actor, 2, 0, 1, l)) is SegmentError.Bounds)
        assertTrue(errorOf(assembleSegment(lib, actor, 2, 3, 2, l)) is SegmentError.Bounds)
        assertTrue(errorOf(assembleSegment(lib, actor, 0, 1, 0, emptyList())) is SegmentError.Bounds)
        assertTrue(errorOf(assembleSegment(lib, actor, SEGMENT_MAX_OPS + 1L, 1, SEGMENT_MAX_OPS + 1L, l)) is SegmentError.Bounds)
        assertTrue(errorOf(assembleSegment(lib, actor, 2, MAX_SAFE_INTEGER, MAX_SAFE_INTEGER + 0, l)) is SegmentError.CountMismatch)
        assertTrue(errorOf(assembleSegment(lib, actor, 2, 1, 5, l)) is SegmentError.CountMismatch)
    }

    @Test
    fun countMismatch() {
        val lines = ops(3).map { it.encode() }
        assertTrue(errorOf(assembleSegment(lib, actor, 3, 1, 3, lines.take(2))) is SegmentError.CountMismatch)
        assertTrue(errorOf(assembleSegment(lib, actor, 3, 1, 3, emptyList())) is SegmentError.CountMismatch)
    }

    @Test
    fun checksumAndLibrary() {
        val text = good().decodeToString()
        val at = text.lastIndexOf("name")
        val flipped = text.substring(0, at) + "namf" + text.substring(at + 4)
        assertTrue(errorOf(flipped) is SegmentError.Checksum)
        val e = errorOf(good(), "00000000-0000-0000-0000-0000000000bb")
        assertTrue(e is SegmentError.WrongLibrary)
    }

    @Test
    fun opLines() {
        val lines = ops(3).map { it.encode() }.toMutableList()
        // not an op at all
        val notOp = lines.toMutableList()
        notOp[0] = "{\"hello\":1}"
        assertTrue(errorOf(rebuilt(notOp)) is SegmentError.BadOp)
        val garbage = lines.toMutableList()
        garbage[1] = "not json"
        val g = errorOf(rebuilt(garbage))
        assertTrue(g is SegmentError.BadOp)
        assertEquals(2, g.line)
        // non-canonical
        val spaced = lines.toMutableList()
        spaced[1] = spaced[1].replace("\"kind\":", "\"kind\": ")
        val e = errorOf(rebuilt(spaced))
        assertTrue(e is SegmentError.NonCanonical)
        assertEquals(2, e.line)
        val reordered = "{\"id\":\"$actor:1\",\"body\":{\"field\":\"n\",\"value\":null},\"hlc\":\"${Hlc(1, 0, actor).wire()}\",\"kind\":\"profile.set\"}"
        assertTrue(errorOf(rebuilt(listOf(reordered))) is SegmentError.NonCanonical)
        val float = lines.take(1).toMutableList()
        float[0] = float[0].replace("\"value\":\"", "\"x\":1.5,\"value\":\"")
        assertTrue(errorOf(rebuilt(float)) is SegmentError.NonCanonical)
        // duplicate key
        val doubled = lines.take(1).toMutableList()
        doubled[0] = doubled[0].replace("\"kind\":\"profile.set\"", "\"kind\":\"profile.set\",\"kind\":\"profile.set\"")
        val d = errorOf(rebuilt(doubled))
        assertTrue(d is SegmentError.DuplicateKey)
        assertEquals(1, d.line)
    }

    @Test
    fun wrongActorAndSeqGap() {
        val lines = ops(3).map { it.encode() }.toMutableList()
        lines[1] = Op(OpId(B, 2), Hlc(1, 0, B), "profile.set", mapOf("field" to JStr("n"), "value" to JNull)).encode()
        val w = errorOf(rebuilt(lines))
        assertTrue(w is SegmentError.WrongActor)
        assertEquals(2, w.line)
        val all = ops(4)
        val gap = errorOf(rebuilt(listOf(all[0], all[1], all[3]).map { it.encode() }))
        assertTrue(gap is SegmentError.SeqGap)
        assertEquals(3L, gap.expected)
        assertEquals(4L, gap.found)
    }
}
