package app.keepfin.core.net

import app.keepfin.core.providers.Url

// The Download on... URL policy (network spec 9.1 #6). A link from another device is untrusted input: only http and
// https, no credentials, and no destination on a loopback, private, link-local, multicast or otherwise special address.
// The address check happens on the RESOLVED addresses (after DNS, and again on every redirect), so DNS rebinding cannot
// get through. Pure functions: the caller resolves the name and passes the addresses in.

class UrlDecision private constructor(val allowed: Boolean, val reason: String, val href: String?, val host: String?) {
    companion object {
        fun allow(href: String, host: String) = UrlDecision(true, "ok", href, host)
        fun deny(reason: String) = UrlDecision(false, reason, null, null)
    }
}

/** True when [host] (as serialised by the URL parser; IPv6 in brackets) is an address literal and not a name. */
fun isAddressLiteral(host: String): Boolean = host.startsWith("[") || parseIpv4Strict(host) != null

/**
 * The static checks, before any DNS: parses [text], then refuses a scheme other than http/https, credentials, a missing
 * host, names that always mean this machine, and an address literal in a denied range. A name passes here and must go
 * through [checkResolved] with its addresses.
 */
fun checkDownloadUrl(text: String): UrlDecision {
    val url = Url.parse(text) ?: return UrlDecision.deny("bad-url")
    return checkParsed(url)
}

private fun checkParsed(url: Url): UrlDecision {
    if (url.scheme != "http" && url.scheme != "https") return UrlDecision.deny("scheme")
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return UrlDecision.deny("credentials")
    val host = url.host
    if (host == null || host.isEmpty()) return UrlDecision.deny("no-host")
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) return UrlDecision.deny("local-name")
    if (isAddressLiteral(host)) {
        val bare = if (host.startsWith("[")) host.substring(1, host.length - 1) else host
        val why = deniedAddressClass(bare)
        if (why != null) return UrlDecision.deny("address:$why")
    }
    return UrlDecision.allow(url.href, host)
}

/**
 * The check on the addresses a name resolved to: every address must be public (one private answer among public ones is
 * a rebinding attempt), and there must be at least one. Address literals in the URL pass through [checkDownloadUrl] only.
 */
fun checkResolved(addresses: List<String>): UrlDecision {
    if (addresses.isEmpty()) return UrlDecision.deny("address:unresolved")
    for (a in addresses) {
        val why = deniedAddressClass(a.removePrefix("[").removeSuffix("]"))
        if (why != null) return UrlDecision.deny("address:$why")
    }
    return UrlDecision.allow("", "")
}

/**
 * The check for one redirect hop: [location] is resolved against [current], then the static checks run on it, and
 * [resolved] (the addresses the new host just resolved to; ignored for an address literal) must all be public.
 * [hops] counts the redirects followed so far.
 */
fun checkRedirect(current: String, location: String, resolved: List<String>, hops: Int, maxHops: Int = 10): UrlDecision {
    if (hops >= maxHops) return UrlDecision.deny("too-many-redirects")
    val base = Url.parse(current) ?: return UrlDecision.deny("bad-url")
    val target = resolveLocation(base, location) ?: return UrlDecision.deny("bad-url")
    val first = checkParsed(target)
    if (!first.allowed) return first
    val host = first.host!!
    if (!isAddressLiteral(host)) {
        val r = checkResolved(resolved)
        if (!r.allowed) return r
    }
    return first
}

private fun resolveLocation(base: Url, location: String): Url? {
    val loc = location.trim()
    if (loc.isEmpty()) return null
    if (loc.indexOf("://") in 1..10 && loc.substring(0, loc.indexOf("://")).all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '+' || it == '-' || it == '.' }) return Url.parse(loc)
    val origin = base.scheme + "://" + (base.host ?: return null) + (if (base.port != null) ":" + base.port else "")
    if (loc.startsWith("//")) return Url.parse(base.scheme + ":" + loc)
    if (loc.startsWith("/")) return Url.parse(origin + loc)
    if (loc.startsWith("?")) return Url.parse(origin + base.path + loc)
    if (loc.startsWith("#")) return Url.parse(origin + base.path + (if (base.query != null) "?" + base.query else "") + loc)
    val dir = base.path.substring(0, base.path.lastIndexOf('/') + 1)
    return Url.parse(origin + dir + loc)
}

// ---- addresses --------------------------------------------------------------------------------------------------

/** Four decimal octets, no leading zeros beyond a single digit; null otherwise. */
internal fun parseIpv4Strict(text: String): IntArray? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    val out = IntArray(4)
    for (i in 0 until 4) {
        val p = parts[i]
        if (p.isEmpty() || p.length > 3 || !p.all { it in '0'..'9' } || (p.length > 1 && p[0] == '0')) return null
        val v = p.toInt()
        if (v > 255) return null
        out[i] = v
    }
    return out
}

/** Eight 16-bit groups of an IPv6 text (compressed, with an embedded IPv4 tail allowed); null when malformed or with a zone id. */
internal fun parseIpv6Groups(text: String): IntArray? {
    if (text.isEmpty() || text.indexOf('%') >= 0) return null
    val dc = text.indexOf("::")
    if (dc >= 0 && text.indexOf("::", dc + 1) >= 0) return null
    fun side(s: String): List<Int>? {
        if (s.isEmpty()) return emptyList()
        val out = ArrayList<Int>()
        val pieces = s.split(':')
        for ((i, p) in pieces.withIndex()) {
            if (i == pieces.size - 1 && p.indexOf('.') >= 0) {
                val v4 = parseIpv4Strict(p) ?: return null
                out.add((v4[0] shl 8) or v4[1])
                out.add((v4[2] shl 8) or v4[3])
            } else {
                if (p.isEmpty() || p.length > 4) return null
                var v = 0
                for (c in p) {
                    val d = when (c) {
                        in '0'..'9' -> c - '0'
                        in 'a'..'f' -> c - 'a' + 10
                        in 'A'..'F' -> c - 'A' + 10
                        else -> return null
                    }
                    v = (v shl 4) or d
                }
                out.add(v)
            }
        }
        return out
    }
    val groups = IntArray(8)
    if (dc < 0) {
        val all = side(text) ?: return null
        if (all.size != 8) return null
        for (i in 0 until 8) groups[i] = all[i]
    } else {
        val left = side(text.substring(0, dc)) ?: return null
        val right = side(text.substring(dc + 2)) ?: return null
        if (left.size + right.size > 7) return null
        for (i in left.indices) groups[i] = left[i]
        for (i in right.indices) groups[8 - right.size + i] = right[i]
    }
    return groups
}

private fun ipv4Class(a: IntArray): String? {
    val b0 = a[0]
    val b1 = a[1]
    return when {
        b0 == 0 -> "unspecified"
        b0 == 10 -> "private"
        b0 == 100 && b1 in 64..127 -> "private" // carrier-grade NAT, also Tailscale
        b0 == 127 -> "loopback"
        b0 == 169 && b1 == 254 -> "link-local"
        b0 == 172 && b1 in 16..31 -> "private"
        b0 == 192 && b1 == 0 && a[2] == 0 -> "reserved"
        b0 == 192 && b1 == 0 && a[2] == 2 -> "reserved"
        b0 == 192 && b1 == 168 -> "private"
        b0 == 198 && b1 in 18..19 -> "reserved"
        b0 == 198 && b1 == 51 && a[2] == 100 -> "reserved"
        b0 == 203 && b1 == 0 && a[2] == 113 -> "reserved"
        b0 in 224..239 -> "multicast"
        b0 >= 240 -> "reserved"
        else -> null
    }
}

/**
 * Why [address] (an IPv4 dotted quad or an IPv6 text, without brackets) is not a public destination, or null when it is.
 * Classes: unspecified, loopback, private, link-local, multicast, reserved, malformed. IPv4-mapped (::ffff:a.b.c.d), NAT64
 * (64:ff9b::/96) and 6to4 (2002::/16) addresses are judged by the IPv4 address they carry; only global unicast
 * (2000::/3) outside the special ranges passes.
 */
fun deniedAddressClass(address: String): String? {
    val v4 = parseIpv4Strict(address)
    if (v4 != null) return ipv4Class(v4)
    val g = parseIpv6Groups(address) ?: return "malformed"
    fun embedded(hi: Int, lo: Int) = intArrayOf(hi ushr 8, hi and 255, lo ushr 8, lo and 255)
    val first96zero = g[0] == 0 && g[1] == 0 && g[2] == 0 && g[3] == 0 && g[4] == 0
    if (first96zero && g[5] == 0xffff) return ipv4Class(embedded(g[6], g[7])) // IPv4-mapped
    if (first96zero && g[5] == 0) {
        if (g[6] == 0 && g[7] == 0) return "unspecified"
        if (g[6] == 0 && g[7] == 1) return "loopback"
        return "reserved" // IPv4-compatible
    }
    if (g[0] == 0x64 && g[1] == 0xff9b && g[2] == 0 && g[3] == 0 && g[4] == 0 && g[5] == 0) return ipv4Class(embedded(g[6], g[7]))
    if (g[0] == 0x2002) return ipv4Class(embedded(g[1], g[2]))
    if ((g[0] and 0xff00) == 0xff00) return "multicast"
    if ((g[0] and 0xffc0) == 0xfe80) return "link-local"
    if ((g[0] and 0xfe00) == 0xfc00) return "private"
    if ((g[0] and 0xe000) != 0x2000) return "reserved" // not global unicast (includes fec0::/10, 100::/8, ...)
    if (g[0] == 0x2001 && g[1] == 0) return "reserved" // Teredo
    if (g[0] == 0x2001 && g[1] == 0x0db8) return "reserved" // documentation
    if (g[0] == 0x2001 && (g[1] and 0xfff0) == 0x0010) return "reserved" // ORCHID
    return null
}
