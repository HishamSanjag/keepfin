package app.keepfin.core.sync

import java.security.MessageDigest

internal actual fun platformSha256Hex(data: ByteArray, from: Int, to: Int): String? {
    val md = MessageDigest.getInstance("SHA-256")
    md.update(data, from, to - from)
    val d = md.digest()
    val sb = StringBuilder(64)
    for (b in d) {
        val v = b.toInt() and 0xff
        sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 15])
    }
    return sb.toString()
}
