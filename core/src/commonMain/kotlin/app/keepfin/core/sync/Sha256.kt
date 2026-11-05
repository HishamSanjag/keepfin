package app.keepfin.core.sync

// SHA-256 (FIPS 180-4) in common code, so both targets and every platform agree without platform crypto.

private val K = uintArrayOf(
    0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
    0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
    0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
    0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
    0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
    0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
    0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
    0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u,
)

private fun rotr(x: Int, n: Int): Int = (x ushr n) or (x shl (32 - n))

/** The SHA-256 digest of [data] (32 bytes). */
fun sha256(data: ByteArray): ByteArray {
    val h = sha256Words(data, 0, data.size)
    val out = ByteArray(32)
    for (i in 0 until 8) for (j in 0 until 4) out[i * 4 + j] = (h[i] ushr (24 - 8 * j)).toByte()
    return out
}

/** The first, plain implementation, kept so tests can prove the fast one gives the same digests. */
internal fun sha256Reference(data: ByteArray): ByteArray {
    val n = data.size
    val total = ((n + 9 + 63) / 64) * 64
    val buf = ByteArray(total)
    data.copyInto(buf)
    buf[n] = 0x80.toByte()
    val bits = n.toLong() * 8L
    for (i in 0 until 8) buf[total - 1 - i] = ((bits ushr (8 * i)) and 0xff).toByte()

    val h = intArrayOf(
        0x6a09e667, 0xbb67ae85u.toInt(), 0x3c6ef372, 0xa54ff53au.toInt(),
        0x510e527f, 0x9b05688cu.toInt(), 0x1f83d9ab, 0x5be0cd19,
    )
    val w = IntArray(64)
    var off = 0
    while (off < total) {
        for (i in 0 until 16) {
            val p = off + i * 4
            w[i] = ((buf[p].toInt() and 0xff) shl 24) or ((buf[p + 1].toInt() and 0xff) shl 16) or
                ((buf[p + 2].toInt() and 0xff) shl 8) or (buf[p + 3].toInt() and 0xff)
        }
        for (i in 16 until 64) {
            val s0 = rotr(w[i - 15], 7) xor rotr(w[i - 15], 18) xor (w[i - 15] ushr 3)
            val s1 = rotr(w[i - 2], 17) xor rotr(w[i - 2], 19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
        var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
        for (i in 0 until 64) {
            val s1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + s1 + ch + K[i].toInt() + w[i]
            val s0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        off += 64
    }
    val out = ByteArray(32)
    for (i in 0 until 8) for (j in 0 until 4) out[i * 4 + j] = (h[i] ushr (24 - 8 * j)).toByte()
    return out
}

private const val HEX_DIGITS = "0123456789abcdef"

/** Lower-case hex SHA-256 of [data]. */
fun sha256Hex(data: ByteArray): String = sha256HexRange(data, 0, data.size)

internal fun sha256HexReference(data: ByteArray): String {
    val d = sha256Reference(data)
    val sb = StringBuilder(64)
    for (b in d) {
        val v = b.toInt() and 0xff
        sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 15])
    }
    return sb.toString()
}

/** Lower-case hex SHA-256 of the UTF-8 bytes of [text]. */
fun sha256Hex(text: String): String = sha256Hex(text.encodeToByteArray())
