package app.keepfin.core.sync

// A SHA-256 that hashes a range of a byte array in place (no padded copy of the input) with plain Int arrays and
// local variables, which Kotlin/JS compiles to tight JavaScript. It gives exactly the digest of Sha256.kt (tests
// compare them); the checkpoint code uses it because a checkpoint is megabytes long.

private val FAST_K = intArrayOf(
    0x428a2f98, 0x71374491, 0xb5c0fbcfu.toInt(), 0xe9b5dba5u.toInt(), 0x3956c25b, 0x59f111f1, 0x923f82a4u.toInt(), 0xab1c5ed5u.toInt(),
    0xd807aa98u.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1feu.toInt(), 0x9bdc06a7u.toInt(), 0xc19bf174u.toInt(),
    0xe49b69c1u.toInt(), 0xefbe4786u.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152u.toInt(), 0xa831c66du.toInt(), 0xb00327c8u.toInt(), 0xbf597fc7u.toInt(), 0xc6e00bf3u.toInt(), 0xd5a79147u.toInt(), 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92eu.toInt(), 0x92722c85u.toInt(),
    0xa2bfe8a1u.toInt(), 0xa81a664bu.toInt(), 0xc24b8b70u.toInt(), 0xc76c51a3u.toInt(), 0xd192e819u.toInt(), 0xd6990624u.toInt(), 0xf40e3585u.toInt(), 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814u.toInt(), 0x8cc70208u.toInt(), 0x90befffau.toInt(), 0xa4506cebu.toInt(), 0xbef9a3f7u.toInt(), 0xc67178f2u.toInt(),
)

private const val FAST_HEX = "0123456789abcdef"

/** A platform's own SHA-256 over `data[from, to)` as lower-case hex, installed by the JavaScript facade on Node (see NativeSha.kt). */
internal var sha256Native: ((ByteArray, Int, Int) -> String)? = null

/** Lower-case hex SHA-256 of `data[from, to)`. */
internal fun sha256HexRange(data: ByteArray, from: Int, to: Int): String {
    if (to - from >= 8192) {
        val native = sha256Native
        if (native != null) return native(data, from, to)
        val platform = platformSha256Hex(data, from, to)
        if (platform != null) return platform
    }
    val h0 = sha256Words(data, from, to)
    val sb = StringBuilder(64)
    for (i in 0 until 8) {
        val word = h0[i]
        for (j in 0 until 8) sb.append(FAST_HEX[(word ushr (28 - 4 * j)) and 15])
    }
    return sb.toString()
}

/** The eight state words of the SHA-256 of `data[from, to)`. */
internal fun sha256Words(data: ByteArray, from: Int, to: Int): IntArray {
    val k = FAST_K
    val h0 = intArrayOf(
        0x6a09e667, 0xbb67ae85u.toInt(), 0x3c6ef372, 0xa54ff53au.toInt(),
        0x510e527f, 0x9b05688cu.toInt(), 0x1f83d9ab, 0x5be0cd19,
    )
    val w = IntArray(64)
    val n = to - from
    val full = n / 64
    var p = from
    for (block in 0 until full) {
        compressBlock(data, p, w, k, h0)
        p += 64
    }
    // the tail: the leftover bytes, 0x80, zeros and the 64-bit length (one or two blocks)
    val rest = n - full * 64
    val tailLen = if (rest + 9 <= 64) 64 else 128
    val tail = ByteArray(tailLen)
    for (i in 0 until rest) tail[i] = data[p + i]
    tail[rest] = 0x80.toByte()
    val bits = n.toLong() * 8L
    for (i in 0 until 8) tail[tailLen - 1 - i] = ((bits ushr (8 * i)) and 0xff).toByte()
    compressBlock(tail, 0, w, k, h0)
    if (tailLen == 128) compressBlock(tail, 64, w, k, h0)
    return h0
}

private fun compressBlock(buf: ByteArray, off: Int, w: IntArray, k: IntArray, h: IntArray) {
    for (i in 0 until 16) {
        val p = off + i * 4
        w[i] = ((buf[p].toInt() and 0xff) shl 24) or ((buf[p + 1].toInt() and 0xff) shl 16) or
            ((buf[p + 2].toInt() and 0xff) shl 8) or (buf[p + 3].toInt() and 0xff)
    }
    for (i in 16 until 64) {
        val x = w[i - 15]
        val y = w[i - 2]
        val s0 = ((x ushr 7) or (x shl 25)) xor ((x ushr 18) or (x shl 14)) xor (x ushr 3)
        val s1 = ((y ushr 17) or (y shl 15)) xor ((y ushr 19) or (y shl 13)) xor (y ushr 10)
        w[i] = w[i - 16] + s0 + w[i - 7] + s1
    }
    var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
    var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
    // (a loop, not 64 unrolled rounds: a method that large is never compiled by the JVM and would run interpreted)
    for (i in 0 until 64) {
        val s1 = ((e ushr 6) or (e shl 26)) xor ((e ushr 11) or (e shl 21)) xor ((e ushr 25) or (e shl 7))
        val ch = (e and f) xor (e.inv() and g)
        val t1 = hh + s1 + ch + k[i] + w[i]
        val s0 = ((a ushr 2) or (a shl 30)) xor ((a ushr 13) or (a shl 19)) xor ((a ushr 22) or (a shl 10))
        val maj = (a and b) xor (a and c) xor (b and c)
        hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + s0 + maj
    }
    h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
}
