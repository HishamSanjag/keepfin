package app.keepfin.net

import app.keepfin.core.net.checkDownloadUrl
import app.keepfin.core.net.checkRedirect
import app.keepfin.core.net.checkResolved
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

// The network destination check for links that came from another device (spec 6 and 9.1 #6). A link is untrusted input: only http and
// https, no credentials, and no destination on a loopback, private, link-local or multicast address. The rules are the core's
// (UrlPolicy: checkDownloadUrl, checkResolved, checkRedirect); this file adds the I/O: it resolves each name and follows the redirects
// one hop at a time, checking every hop's addresses before a byte is sent there, and connecting only to an address it checked (so a name
// that answers differently the second time, DNS rebinding, cannot move the connection somewhere else). Port of gui/desktop/net/guard.ts.
//
// What it cannot do: the download engine resolves names again by itself. The guard runs right before the engine starts for such a job,
// but a name that rebinds in the milliseconds between the two is not caught (network spec 9.4).

class UrlRefused(val reason: String, message: String) : Exception(message)

private val WORDS = mapOf(
    "bad-url" to "That is not a valid link.",
    "scheme" to "Only http and https links can be downloaded.",
    "credentials" to "Links with a name and password in them are not accepted.",
    "no-host" to "The link has no address.",
    "local-name" to "The link points to a name on a private network.",
    "too-many-redirects" to "The link redirects too many times.",
)

fun wordsFor(reason: String): String =
    if (reason.startsWith("address:")) "The link leads to an address on a private network, so it was not opened." else WORDS[reason] ?: "That link was not accepted."

class Hop(val status: Int, val location: String?)

class GuardDeps(
    /** All addresses a name resolves to (DNS). Tests replace it. */
    val resolve: (String) -> List<String> = { host -> InetAddress.getAllByName(host).map { it.hostAddress.orEmpty().substringBefore('%') } },
    /** Looks at one hop: the status and the Location header of the answer to a request that was sent to [address]. Tests replace it. */
    val hop: ((HttpUrl, String) -> Hop)? = null,
    val maxRedirects: Int = 8,
    val timeoutMs: Long = 10_000,
)

object UrlGuard {
    private fun defaultHop(url: HttpUrl, address: String, timeoutMs: Long): Hop {
        val pinned = InetAddress.getByName(address)
        val client = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .proxy(java.net.Proxy.NO_PROXY)
            // the connection goes to the address that was checked, whatever the name says now
            .dns(object : Dns { override fun lookup(hostname: String) = listOf(pinned) })
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder().url(url).header("Range", "bytes=0-0").header("User-Agent", "Mozilla/5.0").header("Accept", "*/*").build()
        client.newCall(request).execute().use { response -> return Hop(response.code, response.header("Location")) }
    }

    private fun addressesOf(url: String, deps: GuardDeps): List<String> {
        val host = (url.toHttpUrlOrNull()?.host ?: throw UrlRefused("bad-url", wordsFor("bad-url"))).removePrefix("[").removeSuffix("]")
        if (Regex("^[0-9.]+$").matches(host) || host.contains(':')) return listOf(host)
        return try {
            deps.resolve(host)
        } catch (e: Exception) {
            throw UrlRefused("address:unresolved", "The link's address could not be found.")
        }
    }

    /**
     * Checks [text] and everything it redirects to. Returns the final link; throws [UrlRefused] with a plain reason. A hop that cannot be
     * reached is not a refusal (the link may simply be down): the check ends there with what it has, because the engine will fail the
     * same way, and nothing past that hop was ever contacted.
     */
    fun guard(text: String, deps: GuardDeps = GuardDeps()): String {
        val first = checkDownloadUrl(text)
        val firstHref = first.href
        if (!first.allowed || firstHref == null) throw UrlRefused(first.reason, wordsFor(first.reason))
        var current: String = firstHref
        var hops = 0
        while (true) {
            val url = current.toHttpUrlOrNull() ?: throw UrlRefused("bad-url", wordsFor("bad-url"))
            val addresses = addressesOf(current, deps)
            val resolved = checkResolved(addresses)
            if (!resolved.allowed) throw UrlRefused(resolved.reason, wordsFor(resolved.reason))
            val answer = try {
                deps.hop?.invoke(url, addresses[0]) ?: defaultHop(url, addresses[0], deps.timeoutMs)
            } catch (e: Exception) {
                return current // unreachable: nothing beyond this hop was contacted
            }
            val location = answer.location
            if (answer.status < 300 || answer.status >= 400 || location == null) return current
            // the next hop: its own addresses must be public too, before anything is sent there
            val target = url.resolve(location)?.toString() ?: throw UrlRefused("bad-url", wordsFor("bad-url"))
            val next = checkDownloadUrl(target)
            val nextHref = next.href
            if (!next.allowed || nextHref == null) throw UrlRefused(next.reason, wordsFor(next.reason))
            val nextAddresses = addressesOf(nextHref, deps)
            val redirect = checkRedirect(current, location, nextAddresses, hops + 1)
            val redirectHref = redirect.href
            if (!redirect.allowed || redirectHref == null) throw UrlRefused(redirect.reason, wordsFor(redirect.reason))
            if (hops + 1 > deps.maxRedirects) throw UrlRefused("too-many-redirects", wordsFor("too-many-redirects"))
            current = redirectHref
            hops++
        }
    }
}
