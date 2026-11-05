package app.keepfin.core.net

// Byte helpers for the network core: hex and Base64 codecs and a constant-time comparison. No platform classes, so
// every target gives the same text.

private const val HEX = "0123456789abcdef"
private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

fun hexOf(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xff
        sb.append(HEX[v ushr 4]).append(HEX[v and 15])
    }
    return sb.toString()
}

/** Lower-case or upper-case hex to bytes; null for an odd length or a non-hex character. */
fun bytesOfHex(text: String): ByteArray? {
    if (text.length % 2 != 0) return null
    val out = ByteArray(text.length / 2)
    for (i in out.indices) {
        val hi = hexDigit(text[2 * i])
        val lo = hexDigit(text[2 * i + 1])
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'a'..'f' -> c - 'a' + 10
    in 'A'..'F' -> c - 'A' + 10
    else -> -1
}

/** Standard Base64 with padding. */
fun base64Encode(bytes: ByteArray): String {
    val sb = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xff
        val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0
        val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0
        sb.append(B64[b0 ushr 2]).append(B64[((b0 and 3) shl 4) or (b1 ushr 4)])
        sb.append(if (i + 1 < bytes.size) B64[((b1 and 15) shl 2) or (b2 ushr 6)] else '=')
        sb.append(if (i + 2 < bytes.size) B64[b2 and 63] else '=')
        i += 3
    }
    return sb.toString()
}

/** Strict standard Base64 (padding required, canonical trailing bits); null otherwise. */
fun base64Decode(text: String): ByteArray? {
    if (text.length % 4 != 0) return null
    var pad = 0
    if (text.isNotEmpty() && text[text.length - 1] == '=') pad = if (text.length >= 2 && text[text.length - 2] == '=') 2 else 1
    val out = ByteArray(text.length / 4 * 3 - pad)
    var o = 0
    var i = 0
    while (i < text.length) {
        var acc = 0
        var n = 0
        for (k in 0 until 4) {
            val c = text[i + k]
            val v = if (c == '=') {
                if (i + 4 != text.length || k < 4 - pad) return null
                0
            } else {
                val at = B64.indexOf(c)
                if (at < 0) return null
                at
            }
            acc = (acc shl 6) or v
            n++
        }
        val take = if (i + 4 == text.length) 3 - pad else 3
        if (take >= 1) out[o++] = (acc ushr 16).toByte()
        if (take >= 2) out[o++] = (acc ushr 8).toByte()
        if (take >= 3) out[o++] = acc.toByte()
        // trailing bits must be zero so that every byte string has one text
        if (i + 4 == text.length && pad == 1 && ((acc ushr 6) and 3) != 0) return null
        if (i + 4 == text.length && pad == 2 && ((acc ushr 12) and 15) != 0) return null
        i += 4
    }
    return out
}

/** Base64url without padding (the QR token's key and token fields). */
fun base64UrlEncode(bytes: ByteArray): String = base64Encode(bytes).trimEnd('=').replace('+', '-').replace('/', '_')

fun base64UrlDecode(text: String): ByteArray? {
    if (text.indexOf('=') >= 0 || text.indexOf('+') >= 0 || text.indexOf('/') >= 0) return null
    if (text.length % 4 == 1) return null
    val std = text.replace('-', '+').replace('_', '/')
    return base64Decode(std + "=".repeat((4 - std.length % 4) % 4))
}

/** Compares in time that does not depend on where the bytes differ. */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    var diff = a.size xor b.size
    val n = minOf(a.size, b.size)
    for (i in 0 until n) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}

fun concatBytes(vararg parts: ByteArray): ByteArray {
    var n = 0
    for (p in parts) n += p.size
    val out = ByteArray(n)
    var at = 0
    for (p in parts) {
        p.copyInto(out, at)
        at += p.size
    }
    return out
}
