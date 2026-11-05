package app.keepfin.core.sync

import app.keepfin.core.net.SoftwareSigner
import app.keepfin.core.net.base64Encode
import app.keepfin.core.net.deviceIdOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** KFSEG v2: a signature trailer after the v1 layout. v1 files still decode. */
class SegmentSignedTest {
    private val lib = TEST_LIB
    private val actor = A

    private fun ops(n: Int, start: Int = 0): List<Op> = List(n) { i ->
        Op(OpId(actor, start + i + 1L), Hlc(1000L + i, 0, actor), "profile.set", mapOf("field" to JStr("name"), "value" to JStr("v$i")))
    }

    private fun errorOf(bytes: ByteArray): SegmentError = (decodeSegment(bytes, lib) as? SegmentResult.Err)?.error ?: fail("segment was accepted")

    @Test
    fun aSignedSegmentRoundTrips() {
        val signer = SoftwareSigner.generate()
        val input = ops(4)
        val bytes = encodeSegment(lib, actor, input, signer)
        val text = bytes.decodeToString()
        assertTrue(text.startsWith("KFSEG 2\n"))
        assertTrue(text.endsWith("\n") && text.lastIndexOf("\nKFSIG {") > 0)
        val r = decodeSegment(bytes, lib) as SegmentResult.Ok
        assertEquals(input, r.ops)
        assertEquals(deviceIdOf(signer.spki), r.signer)
        assertContentEquals(signer.spki, r.key)
        // the same ops unsigned decode with no signer, and the v1 layout is unchanged
        val v1 = decodeSegment(encodeSegment(lib, actor, input), lib) as SegmentResult.Ok
        assertEquals(input, v1.ops)
        assertNull(v1.signer)
        assertNull(v1.key)
    }

    @Test
    fun anAlteredSignedSegmentIsRefused() {
        val signer = SoftwareSigner.generate()
        val bytes = encodeSegment(lib, actor, ops(3), signer)
        val text = bytes.decodeToString()
        val cut = text.lastIndexOf("KFSIG ")
        // a body byte: the checksum catches it
        assertTrue(errorOf(text.replace("\"v1\"", "\"v9\"").encodeToByteArray()) is SegmentError.Checksum)
        // the trailer of another segment (same key, other bytes): only the signature catches it
        val other = encodeSegment(lib, actor, ops(3, 0).map { Op(it.id, Hlc(5000L + it.id.seq, 0, actor), it.kind, it.body) }, signer).decodeToString()
        val spliced = other.substring(0, other.lastIndexOf("KFSIG ")) + text.substring(cut)
        assertTrue(errorOf(spliced.encodeToByteArray()) is SegmentError.BadSignature)
        // a trailer naming another key: the signature does not verify under it
        val b = SoftwareSigner.generate()
        val swapped = text.replace(base64Encode(signer.spki), base64Encode(b.spki)).replace(deviceIdOf(signer.spki), deviceIdOf(b.spki))
        assertTrue(errorOf(swapped.encodeToByteArray()) is SegmentError.BadSignature)
        // trailer structure
        assertTrue(errorOf(text.substring(0, cut).encodeToByteArray()) is SegmentError.BadTrailer, "no trailer")
        assertTrue(errorOf(text.substring(0, text.length - 1).encodeToByteArray()) is SegmentError.BadTrailer, "no final newline")
        assertTrue(errorOf(text.replace("\"alg\":\"ES256\"", "\"alg\":\"ES999\"").encodeToByteArray()) is SegmentError.BadTrailer)
        assertTrue(errorOf(text.replace(deviceIdOf(signer.spki), deviceIdOf(b.spki)).encodeToByteArray()) is SegmentError.BadTrailer, "device is not the key's id")
        assertTrue(errorOf(text.replace("KFSIG {", "KFSIG {\"extra\":1,").encodeToByteArray()) is SegmentError.BadTrailer)
        assertTrue(errorOf(text.replace("KFSIG ", "KFSIX ").encodeToByteArray()) is SegmentError.BadTrailer)
        // a signed file that claims KFSEG 1 has trailing data
        assertTrue(errorOf(text.replaceFirst("KFSEG 2", "KFSEG 1").encodeToByteArray()) is SegmentError.TrailingData ||
            errorOf(text.replaceFirst("KFSEG 2", "KFSEG 1").encodeToByteArray()) is SegmentError.CountMismatch)
    }
}
