package app.keepfin.net

import java.net.InetAddress

/**
 * Where an unrequested pairing request may come from: this local network or Tailscale, and nowhere else. Loopback is not allowed
 * (a request from the same device is never a person on another device). Port of `isPairSource` in gui/desktop/net/pairing.ts.
 */
object PairSource {
    private val IPV4 = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")
    private val IPV6_CHARS = Regex("^[0-9a-fA-F:.]+$")

    fun isLocalOrTailscale(address: String): Boolean {
        var text = address.trim()
        if (text.startsWith("[") && text.endsWith("]")) text = text.substring(1, text.length - 1)
        val zone = text.indexOf('%')
        if (zone >= 0) text = text.substring(0, zone)
        val bytes = parse(text) ?: return false
        return if (bytes.size == 4) v4(bytes) else v6(bytes)
    }

    /** Only literal addresses are parsed (never a name, so nothing is looked up). IPv4-mapped IPv6 becomes the IPv4 address. */
    private fun parse(text: String): ByteArray? {
        if (text.isEmpty()) return null
        val literal = if (text.contains(':')) text.matches(IPV6_CHARS) else text.matches(IPV4)
        if (!literal) return null
        return try {
            InetAddress.getByName(text).address
        } catch (e: Exception) {
            null
        }
    }

    private fun v4(b: ByteArray): Boolean {
        val a = b[0].toInt() and 0xff
        val c = b[1].toInt() and 0xff
        return a == 10 ||
            (a == 172 && c in 16..31) ||
            (a == 192 && c == 168) ||
            (a == 169 && c == 254) ||
            (a == 100 && c in 64..127) // Tailscale 100.64.0.0/10
    }

    private fun v6(b: ByteArray): Boolean {
        val first = b[0].toInt() and 0xff
        val second = b[1].toInt() and 0xff
        if (first == 0xfe && (second and 0xc0) == 0x80) return true // fe80::/10 link-local
        // fc00::/7 unique local; Tailscale's fd7a:115c:a1e0::/48 is inside it
        return (first and 0xfe) == 0xfc
    }
}
