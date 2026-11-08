package app.keepfin.core.signin

import app.keepfin.core.providers.Providers

/**
 * Which sign-ins Keepfin can use for the site of a link. The site table is written once here: the link's host,
 * the cookie domains and the session cookie names that prove a real sign-in. It makes decisions only; the desktop
 * reads browser cookie stores and the phone reads its WebView cookies.
 */
class SignInSite(
    val key: String,
    val name: String,
    /** Hosts a link can have (a host matches itself and its sub-domains: `m.`, `music.`). */
    val hosts: List<String>,
    /** Cookie domains that hold the site's sign-in, with the leading dot. */
    val domains: List<String>,
    /** Cookie names of which any one proves a live sign-in (tracking cookies do not). */
    val sessionCookies: List<String>,
    val loginUrl: String,
    /** One plain line shown next to the site when its sign-in cannot happen in Keepfin's own window, or null. */
    val hint: String? = null,
)

/** A cookie as stores list it, without its value. `expiry` is Unix seconds; 0 is a session cookie. */
class SignInCookie(val name: String, val domain: String, val expiry: Long)

/** Keepfin's own sign-in (`kind` "keepfin") or a browser's (`kind` "browser", with the browser id, profile and label). */
class SignInSource(val kind: String, val browser: String?, val profile: String?, val label: String?)

object SignIn {
    val sites: List<SignInSite> = listOf(
        SignInSite("instagram", "Instagram", listOf("instagram.com"), listOf(".instagram.com"), listOf("sessionid"), "https://www.instagram.com/accounts/login/"),
        SignInSite("facebook", "Facebook", listOf("facebook.com", "fb.watch"), listOf(".facebook.com"), listOf("c_user"), "https://www.facebook.com/login/"),
        SignInSite("x", "X", listOf("x.com", "twitter.com"), listOf(".x.com", ".twitter.com"), listOf("auth_token"), "https://x.com/i/flow/login"),
        SignInSite("tiktok", "TikTok", listOf("tiktok.com"), listOf(".tiktok.com"), listOf("sessionid"), "https://www.tiktok.com/login"),
        SignInSite("reddit", "Reddit", listOf("reddit.com", "redd.it"), listOf(".reddit.com"), listOf("reddit_session"), "https://www.reddit.com/login/"),
        SignInSite("youtube", "YouTube", listOf("youtube.com", "youtu.be"), listOf(".youtube.com", ".google.com"), listOf("SAPISID", "__Secure-3PSID"), "https://accounts.google.com/ServiceLogin?service=youtube", hint = "Needs Firefox: Google blocks sign-in here"),
        SignInSite("vimeo", "Vimeo", listOf("vimeo.com"), listOf(".vimeo.com"), listOf("vimeo"), "https://vimeo.com/log_in"),
        SignInSite("soundcloud", "SoundCloud", listOf("soundcloud.com"), listOf(".soundcloud.com"), listOf("oauth_token"), "https://soundcloud.com/signin"),
        SignInSite("pinterest", "Pinterest", listOf("pinterest.com", "pin.it"), listOf(".pinterest.com"), listOf("_pinterest_sess"), "https://www.pinterest.com/login/"),
    )

    fun site(key: String): SignInSite? = sites.firstOrNull { it.key == key }

    /** The site a link belongs to, or null for any other site. */
    fun siteOf(url: String): SignInSite? {
        val host = Providers.host(url)
        if (host.isEmpty()) return null
        return sites.firstOrNull { s -> s.hosts.any { inDomain(host, it) } }
    }

    /** Whether the cookies hold a live sign-in for the site: a session cookie of the site's, on its domains, not expired. */
    fun signedIn(site: SignInSite, cookies: List<SignInCookie>, nowSeconds: Long): Boolean = cookies.any { c ->
        c.name in site.sessionCookies && (c.expiry <= 0L || c.expiry > nowSeconds) &&
            site.domains.any { inDomain(c.domain.removePrefix("."), it.removePrefix(".")) }
    }

    /** What Keepfin's sign-in window says when a page reaches Google's sign-in, which refuses sign-in in app windows. */
    const val GOOGLE_BLOCKED_MESSAGE =
        "Google doesn't allow signing in from this window. Sign in to YouTube in Firefox, and Keepfin will use that sign-in automatically."

    /** Whether the page is Google's sign-in (accounts.google.com), also when another site's login redirects there. Other single sign-on pages are not affected. */
    fun isGoogleSignIn(url: String): Boolean = Providers.host(url).removePrefix("www.") == "accounts.google.com"

    private fun inDomain(host: String, domain: String): Boolean = host == domain || host.endsWith(".$domain")

    private val browserOrder = listOf("firefox", "chrome", "edge", "brave", "opera", "vivaldi", "chromium")

    private fun rank(source: SignInSource): Int {
        if (source.kind != "browser") return -1
        val i = browserOrder.indexOf(source.browser)
        return if (i < 0) browserOrder.size else i
    }

    /** The positions of `sources` in order: Keepfin's own sign-in first, then browsers in a fixed order (Firefox first); equals keep their order. */
    fun orderIndexes(sources: List<SignInSource>): List<Int> =
        sources.indices.sortedWith(compareBy({ rank(sources[it]) }, { it }))

    fun order(sources: List<SignInSource>): List<SignInSource> = orderIndexes(sources).map { sources[it] }

    /** The text of a row for the source. */
    fun notice(source: SignInSource): String =
        if (source.kind == "browser") "Signed in with " + (source.label ?: source.browser ?: "your browser") else "Signed in"

    private fun same(a: SignInSource, b: SignInSource) = a.kind == b.kind && a.browser == b.browser && a.profile == b.profile

    /**
     * The sign-in to retry a failed request with, or null for none. A request first goes without any sign-in; only a
     * failure whose action is sign-in (`errorAction` "signIn") may be retried, with each source at most once, and only while the
     * automatic use is on, no manual sign-in is set, and the link is on a known site. `available` holds the sources that
     * hold a live sign-in for the link's site, `tried` those already used.
     */
    fun retry(url: String, errorAction: String?, autoOn: Boolean, manualOverride: Boolean, available: List<SignInSource>, tried: List<SignInSource>): SignInSource? {
        if (!autoOn || manualOverride || errorAction != "signIn" || siteOf(url) == null) return null
        return order(available).firstOrNull { a -> tried.none { same(it, a) } }
    }
}
