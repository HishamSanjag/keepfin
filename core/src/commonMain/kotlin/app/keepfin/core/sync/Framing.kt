package app.keepfin.core.sync

// The shared envelope of segment and checkpoint files:
//   <MAGIC> <FORMAT>\n
//   <header, one line of canonical JSON>\n
//   <body>
// All lines end with LF; there is no CR, no BOM and nothing after the body.

internal enum class FrameProblem { Bom, TooLarge, BadMagic, NewerFormat, MalformedUtf8, CarriageReturn, NoHeader }

internal class Frame(val header: String, val bodyBytes: ByteArray, val bodyLineFeeds: Int = -1) {
    /** The body as text, decoded on first use (a checkpoint reader that works on [bodyBytes] never needs it). */
    val body: String by lazy(LazyThreadSafetyMode.NONE) { bodyBytes.decodeToString() }
}

internal sealed class FrameResult {
    class Ok(val frame: Frame) : FrameResult()
    class Bad(val problem: FrameProblem, val found: String = "") : FrameResult()
}

private const val LF: Byte = 10

private fun indexOfLf(bytes: ByteArray, from: Int): Int {
    for (i in from until bytes.size) if (bytes[i] == LF) return i
    return -1
}

internal fun splitFrame(bytes: ByteArray, word: String, maxBytes: Int, format: Int = FORMAT): FrameResult {
    if (hasBomBytes(bytes)) return FrameResult.Bad(FrameProblem.Bom)
    if (bytes.size > maxBytes) return FrameResult.Bad(FrameProblem.TooLarge)
    val n1 = indexOfLf(bytes, 0)
    if (n1 < 0) return FrameResult.Bad(FrameProblem.BadMagic)
    var ascii = true
    for (i in 0 until n1) if (bytes[i] < 0) ascii = false
    if (!ascii) return FrameResult.Bad(FrameProblem.BadMagic)
    val magic = bytes.decodeToString(0, n1)
    val prefix = "$word "
    if (magic != "$prefix$format") {
        val rest = if (magic.startsWith(prefix)) magic.substring(prefix.length) else ""
        return if (rest.isNotEmpty() && rest.all { it in '0'..'9' }) FrameResult.Bad(FrameProblem.NewerFormat, rest)
        else FrameResult.Bad(FrameProblem.BadMagic)
    }
    // Everything after the magic line is checked in one pass over the bytes: well-formed UTF-8, then no CR, then no BOM.
    val lineFeeds = IntArray(1)
    val scan = scanUtf8(bytes, n1 + 1, bytes.size, lineFeeds)
    if (scan == UTF8_MALFORMED) return FrameResult.Bad(FrameProblem.MalformedUtf8)
    if (scan == UTF8_HAS_CR) return FrameResult.Bad(FrameProblem.CarriageReturn)
    if (bytes.size >= n1 + 4 && bytes[n1 + 1] == 0xEF.toByte() && bytes[n1 + 2] == 0xBB.toByte() && bytes[n1 + 3] == 0xBF.toByte()) {
        return FrameResult.Bad(FrameProblem.Bom)
    }
    val n2 = indexOfLf(bytes, n1 + 1)
    if (n2 < 0) return FrameResult.Bad(FrameProblem.NoHeader)
    val header = bytes.decodeToString(n1 + 1, n2)
    val bodyBytes = bytes.copyOfRange(n2 + 1, bytes.size)
    // the header line ends with one of the line feeds seen; the rest are the body's
    return FrameResult.Ok(Frame(header, bodyBytes, lineFeeds[0] - 1))
}

/** The bytes of a file: magic line, header line, then the body (which already ends with its LF). */
internal fun joinFrame(word: String, headerText: String, body: String, format: Int = FORMAT): ByteArray =
    "$word $format\n$headerText\n$body".encodeToByteArray()

/** The bytes of a file whose body is already UTF-8 bytes (`body[0, bodyLen)`, ending with its LF). */
internal fun joinFrameBytes(word: String, headerText: String, body: ByteArray, bodyLen: Int): ByteArray {
    val head = "$word $FORMAT\n$headerText\n".encodeToByteArray()
    val out = ByteArray(head.size + bodyLen)
    head.copyInto(out)
    body.copyInto(out, head.size, 0, bodyLen)
    return out
}

internal const val UTF8_OK = 0
internal const val UTF8_MALFORMED = 1
internal const val UTF8_HAS_CR = 2

private fun isContinuation(b: Byte): Boolean = b >= -128 && b <= -65 // 0x80..0xBF as signed bytes

/**
 * One pass over `bytes[from, to)`: [UTF8_MALFORMED] unless it is well-formed UTF-8 (shortest forms only, no
 * surrogates, nothing above U+10FFFF: the strict decoder's rule), else [UTF8_HAS_CR] when it holds a carriage
 * return, else [UTF8_OK]. It builds no string and copies nothing.
 */
internal fun scanUtf8(bytes: ByteArray, from: Int, to: Int, lineFeeds: IntArray? = null): Int {
    var i = from
    var cr = false
    var lf = 0
    while (i < to) {
        val raw = bytes[i]
        if (raw >= 0) {
            if (raw.toInt() == 13) cr = true else if (raw.toInt() == 10) lf++
            i++
            continue
        }
        val b = raw.toInt() and 0xff
        val need = when {
            b >= 0xC2 && b <= 0xDF -> 1
            b >= 0xE0 && b <= 0xEF -> 2
            b >= 0xF0 && b <= 0xF4 -> 3
            else -> return UTF8_MALFORMED
        }
        if (i + need >= to) return UTF8_MALFORMED
        // the second byte's range depends on the lead byte (no overlongs, no surrogates, nothing past U+10FFFF)
        val b1 = bytes[i + 1].toInt() and 0xff
        val lo = if (b == 0xE0) 0xA0 else if (b == 0xF0) 0x90 else 0x80
        val hi = if (b == 0xED) 0x9F else if (b == 0xF4) 0x8F else 0xBF
        if (b1 < lo || b1 > hi) return UTF8_MALFORMED
        if (need >= 2 && !isContinuation(bytes[i + 2])) return UTF8_MALFORMED
        if (need == 3 && !isContinuation(bytes[i + 3])) return UTF8_MALFORMED
        i += need + 1
    }
    if (lineFeeds != null) lineFeeds[0] = lf
    return if (cr) UTF8_HAS_CR else UTF8_OK
}
