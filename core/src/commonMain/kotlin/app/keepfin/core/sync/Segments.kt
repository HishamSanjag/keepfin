package app.keepfin.core.sync

import app.keepfin.core.net.DOMAIN_SEGMENT
import app.keepfin.core.net.DeviceSigner
import app.keepfin.core.net.base64Decode
import app.keepfin.core.net.base64Encode
import app.keepfin.core.net.deviceIdOf
import app.keepfin.core.net.isP256Spki
import app.keepfin.core.net.signDomain
import app.keepfin.core.net.verifyDomain

// A segment is an immutable file holding one actor's ops first..last, contiguous, in seq order. Decoding checks
// the file and each op's envelope and canonical form. Op bodies are judged later, by admission (OpLog), so a
// segment holding an invalid or unknown op still decodes.

const val SEGMENT_MAGIC = "KFSEG 1"

/** Segments written by an actor that has a device key (network spec 9.1): the same layout plus a signature trailer line. */
const val SEGMENT_MAGIC_V2 = "KFSEG 2"
private const val SEGMENT_FORMAT_V2 = 2
private const val TRAILER_WORD = "KFSIG "
const val SEGMENT_MAX_OPS = 10_000
const val SEGMENT_MAX_BYTES = 16 * 1024 * 1024

/** `<first>-<last>.seg`, both zero-padded to 16 digits. */
fun segmentFileName(first: Long, last: Long): String =
    first.toString().padStart(16, '0') + "-" + last.toString().padStart(16, '0') + ".seg"

/**
 * Writes a segment for [actor]'s [ops], which must be non-empty (at most 10,000), contiguous and all by [actor]. With a
 * [signer] the file is `KFSEG 2`: the bytes of the v1 layout (magic line `KFSEG 2`, header, body), then one trailer line
 * `KFSIG {"alg":"ES256","device":<id>,"key":<SPKI, Base64>,"sig":<Base64 DER>}`. The signature covers every byte before the
 * trailer under the domain `keepfin-seg-v2`.
 */
fun encodeSegment(library: String, actor: String, ops: List<Op>, signer: DeviceSigner? = null): ByteArray {
    require(ops.isNotEmpty() && ops.size <= SEGMENT_MAX_OPS) { "a segment holds 1..$SEGMENT_MAX_OPS ops" }
    val first = ops[0].id.seq
    for ((i, op) in ops.withIndex()) {
        require(op.id.actor == actor) { "op ${op.id} is not by $actor" }
        require(op.id.seq == first + i) { "ops are not contiguous at ${op.id}" }
    }
    return assembleSegment(library, actor, ops.size.toLong(), first, ops.last().id.seq, ops.map { it.encode() }, signer = signer)
}

/** Appends the signature trailer to the v2 bytes [unsigned] (magic line to final body LF). */
internal fun signSegmentBytes(unsigned: ByteArray, signer: DeviceSigner): ByteArray {
    val sig = signDomain(signer, DOMAIN_SEGMENT, unsigned)
    val trailer = TRAILER_WORD + encodeCanonical(
        jObj(
            "alg" to JStr("ES256"),
            "device" to JStr(deviceIdOf(signer.spki)),
            "key" to JStr(base64Encode(signer.spki)),
            "sig" to JStr(base64Encode(sig)),
        ),
    ) + "\n"
    val t = trailer.encodeToByteArray()
    val out = ByteArray(unsigned.size + t.size)
    unsigned.copyInto(out)
    t.copyInto(out, unsigned.size)
    return out
}

/** Builds segment bytes from raw lines and header numbers (the checksum is computed unless [sha] is given). */
internal fun assembleSegment(
    library: String,
    actor: String,
    count: Long,
    first: Long,
    last: Long,
    lines: List<String>,
    sha: String? = null,
    signer: DeviceSigner? = null,
): ByteArray {
    val body = StringBuilder()
    for (l in lines) body.append(l).append('\n')
    val bodyText = body.toString()
    val header = jObj(
        "actor" to JStr(actor),
        "count" to JNum(count),
        "first" to JNum(first),
        "last" to JNum(last),
        "library" to JStr(library),
        "sha256" to JStr(sha ?: sha256Hex(bodyText)),
    )
    if (signer == null) return joinFrame("KFSEG", encodeCanonical(header), bodyText)
    return signSegmentBytes(joinFrame("KFSEG", encodeCanonical(header), bodyText, SEGMENT_FORMAT_V2), signer)
}

sealed class SegmentError {
    object BadMagic : SegmentError()

    /** A newer Keepfin made this file (for example `KFSEG 3`). Leave it untouched and ask for an update. */
    class NewerFormat(val found: String) : SegmentError()
    object TooLarge : SegmentError()
    object Bom : SegmentError()
    object MalformedUtf8 : SegmentError()
    object CarriageReturn : SegmentError()
    class BadHeader(val why: String) : SegmentError()
    class Bounds(val why: String) : SegmentError()
    class CountMismatch(val expected: Long, val found: Long) : SegmentError()
    object MissingFinalNewline : SegmentError()
    object TrailingData : SegmentError()
    object Checksum : SegmentError()
    class WrongLibrary(val found: String) : SegmentError()
    /** A v2 file whose trailer is missing, malformed or names a key that is not a P-256 key. */
    class BadTrailer(val why: String) : SegmentError()

    /** A v2 file whose signature does not verify under the key in its trailer. */
    object BadSignature : SegmentError()
    class BadOp(val line: Int) : SegmentError()
    class NonCanonical(val line: Int) : SegmentError()
    class DuplicateKey(val line: Int) : SegmentError()
    class WrongActor(val line: Int) : SegmentError()
    class SeqGap(val line: Int, val expected: Long, val found: Long) : SegmentError()
}

sealed class SegmentResult {
    /**
     * [signer] is the device id of the key whose signature verified (null for a v1 file), and [key] its SPKI. That the
     * actor may use this key is for admission to decide (Membership), not for the file.
     */
    class Ok(val actor: String, val first: Long, val last: Long, val ops: List<Op>, val signer: String? = null, val key: ByteArray? = null) : SegmentResult()
    class Err(val error: SegmentError) : SegmentResult()
}

private fun err(e: SegmentError): SegmentResult = SegmentResult.Err(e)

/** Decodes and verifies a segment. On any failure nothing from it is accepted. Line numbers count op lines from 1. */
fun decodeSegment(bytes: ByteArray, expectedLibrary: String): SegmentResult {
    // 0. a v2 file carries a signature trailer after the body: split it off, the rest is judged exactly like v1
    var content = bytes
    var format = FORMAT
    var trailer: TrailerInfo? = null
    if (isV2(bytes)) {
        if (bytes.size > SEGMENT_MAX_BYTES) return err(SegmentError.TooLarge)
        val t = splitTrailer(bytes)
        if (t is TrailerSplit.Bad) return err(SegmentError.BadTrailer(t.why))
        t as TrailerSplit.Ok
        content = t.unsigned
        format = SEGMENT_FORMAT_V2
        trailer = t.info
    }
    // 1. magic, format and file framing
    val frame = when (val f = splitFrame(content, "KFSEG", SEGMENT_MAX_BYTES, format)) {
        is FrameResult.Bad -> return err(
            when (f.problem) {
                FrameProblem.Bom -> SegmentError.Bom
                FrameProblem.TooLarge -> SegmentError.TooLarge
                FrameProblem.BadMagic -> SegmentError.BadMagic
                FrameProblem.NewerFormat -> SegmentError.NewerFormat(f.found)
                FrameProblem.MalformedUtf8 -> SegmentError.MalformedUtf8
                FrameProblem.CarriageReturn -> SegmentError.CarriageReturn
                FrameProblem.NoHeader -> SegmentError.BadHeader("no header line")
            },
        )
        is FrameResult.Ok -> f.frame
    }
    // 2. header
    val headerResult = decodeCanonical(frame.header)
    if (headerResult !is CanonicalResult.Ok) return err(SegmentError.BadHeader("header is not canonical JSON"))
    val h = (headerResult.value as? JObj)?.members ?: return err(SegmentError.BadHeader("header is not an object"))
    if (h.keys != setOf("actor", "count", "first", "last", "library", "sha256")) return err(SegmentError.BadHeader("wrong header fields"))
    val actor = (h["actor"] as? JStr)?.value
    val library = (h["library"] as? JStr)?.value
    val sha = (h["sha256"] as? JStr)?.value
    val count = (h["count"] as? JNum)?.value
    val first = (h["first"] as? JNum)?.value
    val last = (h["last"] as? JNum)?.value
    if (actor == null || library == null || sha == null || count == null || first == null || last == null) {
        return err(SegmentError.BadHeader("wrong header field types"))
    }
    if (first < 1 || last < first || count < 1 || count > SEGMENT_MAX_OPS) return err(SegmentError.Bounds("first, last or count out of range"))

    // 3. counts and line endings
    if (count != last - first + 1) return err(SegmentError.CountMismatch(last - first + 1, count))
    val body = frame.body
    if (body.isEmpty()) return err(SegmentError.CountMismatch(count, 0))
    if (body[body.length - 1] != '\n') return err(SegmentError.MissingFinalNewline)
    val lines = body.substring(0, body.length - 1).split('\n')
    if (lines.size.toLong() > count) return err(SegmentError.TrailingData)
    if (lines.size.toLong() < count) return err(SegmentError.CountMismatch(count, lines.size.toLong()))
    // 4. checksum
    if (sha256Hex(frame.bodyBytes) != sha) return err(SegmentError.Checksum)
    // 5. library
    if (library != expectedLibrary) return err(SegmentError.WrongLibrary(library))
    // 6. each line is canonical and an op envelope
    val ops = ArrayList<Op>(lines.size)
    for ((i, line) in lines.withIndex()) {
        when (val r = decodeCanonical(line)) {
            is CanonicalResult.Bad -> return err(
                when (r.problem) {
                    CanonicalProblem.DuplicateKey -> SegmentError.DuplicateKey(i + 1)
                    CanonicalProblem.NotCanonical, CanonicalProblem.BadNumber -> SegmentError.NonCanonical(i + 1)
                    else -> SegmentError.BadOp(i + 1)
                },
            )
            is CanonicalResult.Ok -> ops.add(opFromJson(r.value) ?: return err(SegmentError.BadOp(i + 1)))
        }
    }
    // 7. each op's actor matches the header
    for ((i, op) in ops.withIndex()) if (op.id.actor != actor) return err(SegmentError.WrongActor(i + 1))
    // 8. seqs are exactly first..last
    for ((i, op) in ops.withIndex()) {
        val expected = first + i
        if (op.id.seq != expected) return err(SegmentError.SeqGap(i + 1, expected, op.id.seq))
    }
    // 9. the signature, last: it costs the most and every cheaper check has passed
    if (trailer != null) {
        if (!verifyDomain(trailer.key, DOMAIN_SEGMENT, content, trailer.sig)) return err(SegmentError.BadSignature)
        return SegmentResult.Ok(actor, first, last, ops, trailer.device, trailer.key)
    }
    return SegmentResult.Ok(actor, first, last, ops)
}

private class TrailerInfo(val device: String, val key: ByteArray, val sig: ByteArray)

private sealed class TrailerSplit {
    class Ok(val unsigned: ByteArray, val info: TrailerInfo) : TrailerSplit()
    class Bad(val why: String) : TrailerSplit()
}

private fun isV2(bytes: ByteArray): Boolean {
    val magic = "$SEGMENT_MAGIC_V2\n".encodeToByteArray()
    if (bytes.size < magic.size) return false
    for (i in magic.indices) if (bytes[i] != magic[i]) return false
    return true
}

private fun splitTrailer(bytes: ByteArray): TrailerSplit {
    if (bytes[bytes.size - 1] != 10.toByte()) return TrailerSplit.Bad("no trailer line")
    var p = bytes.size - 2
    while (p >= 0 && bytes[p] != 10.toByte()) p--
    if (p < 0) return TrailerSplit.Bad("no trailer line")
    val line = bytes.decodeToString(p + 1, bytes.size - 1)
    if (!line.startsWith(TRAILER_WORD)) return TrailerSplit.Bad("no signature trailer")
    val parsed = decodeCanonical(line.substring(TRAILER_WORD.length))
    if (parsed !is CanonicalResult.Ok) return TrailerSplit.Bad("trailer is not canonical JSON")
    val m = (parsed.value as? JObj)?.members ?: return TrailerSplit.Bad("trailer is not an object")
    if (m.keys != setOf("alg", "device", "key", "sig")) return TrailerSplit.Bad("wrong trailer fields")
    if ((m["alg"] as? JStr)?.value != "ES256") return TrailerSplit.Bad("unknown algorithm")
    val device = (m["device"] as? JStr)?.value ?: return TrailerSplit.Bad("wrong trailer field types")
    val key = base64Decode((m["key"] as? JStr)?.value ?: return TrailerSplit.Bad("wrong trailer field types")) ?: return TrailerSplit.Bad("key is not Base64")
    val sig = base64Decode((m["sig"] as? JStr)?.value ?: return TrailerSplit.Bad("wrong trailer field types")) ?: return TrailerSplit.Bad("sig is not Base64")
    if (!isP256Spki(key)) return TrailerSplit.Bad("key is not a P-256 key")
    if (deviceIdOf(key) != device) return TrailerSplit.Bad("device is not the key's id")
    if (sig.isEmpty() || sig.size > 80) return TrailerSplit.Bad("signature size")
    return TrailerSplit.Ok(bytes.copyOfRange(0, p + 1), TrailerInfo(device, key, sig))
}
