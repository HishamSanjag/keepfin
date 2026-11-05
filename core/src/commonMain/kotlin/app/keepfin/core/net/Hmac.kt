package app.keepfin.core.net

import app.keepfin.core.sync.sha256

/** HMAC-SHA-256 (RFC 2104) over the core's portable SHA-256. */
fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
    val block = 64
    var k = if (key.size > block) sha256(key) else key
    if (k.size < block) k = k.copyOf(block)
    val inner = ByteArray(block)
    val outer = ByteArray(block)
    for (i in 0 until block) {
        inner[i] = (k[i].toInt() xor 0x36).toByte()
        outer[i] = (k[i].toInt() xor 0x5c).toByte()
    }
    return sha256(concatBytes(outer, sha256(concatBytes(inner, message))))
}
