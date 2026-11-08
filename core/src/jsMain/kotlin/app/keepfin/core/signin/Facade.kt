@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.signin

// The JavaScript surface of the sign-in core (see gui/shared/core/signin.ts). Cookies arrive as parallel arrays.

@JsExport
class JsSignInSite(val key: String, val name: String, val domains: Array<String>, val sessionCookies: Array<String>, val loginUrl: String, val hint: String?)

@JsExport
class JsSignInSource(val kind: String, val browser: String?, val profile: String?, val label: String?)

private fun SignInSite.toJs() = JsSignInSite(key, name, domains.toTypedArray(), sessionCookies.toTypedArray(), loginUrl, hint)

@JsExport
fun signInSiteOf(url: String): JsSignInSite? = SignIn.siteOf(url)?.toJs()

/** Every site Keepfin can sign in to, in display order. */
@JsExport
fun signInSiteKeys(): Array<String> = SignIn.sites.map { it.key }.toTypedArray()

@JsExport
fun signInSite(key: String): JsSignInSite? = SignIn.site(key)?.toJs()

/** Whether the cookies (names, domains and expiry seconds, same length) hold a live sign-in for the site `key`. */
@JsExport
fun signInIsSignedIn(key: String, names: Array<String>, domains: Array<String>, expiries: Array<Double>, nowSeconds: Double): Boolean {
    val site = SignIn.site(key) ?: return false
    val cookies = names.indices.map { SignInCookie(names[it], domains[it], expiries[it].toLong()) }
    return SignIn.signedIn(site, cookies, nowSeconds.toLong())
}

/** The positions of `sources` in display order. */
@JsExport
fun signInOrder(sources: Array<JsSignInSource>): Array<Int> =
    SignIn.orderIndexes(sources.map { SignInSource(it.kind, it.browser, it.profile, it.label) }).toTypedArray()

@JsExport
fun signInNotice(source: JsSignInSource): String = SignIn.notice(SignInSource(source.kind, source.browser, source.profile, source.label))

/** The position in `available` of the sign-in to retry with, or -1 for none (see [SignIn.retry]). */
@JsExport
fun signInRetry(url: String, errorAction: String?, autoOn: Boolean, manualOverride: Boolean, available: Array<JsSignInSource>, tried: Array<JsSignInSource>): Int {
    fun core(a: JsSignInSource) = SignInSource(a.kind, a.browser, a.profile, a.label)
    val list = available.map(::core)
    val chosen = SignIn.retry(url, errorAction, autoOn, manualOverride, list, tried.map(::core)) ?: return -1
    return list.indexOf(chosen)
}

/** Whether the page is Google's sign-in, which refuses app windows (see [SignIn.isGoogleSignIn]). */
@JsExport
fun signInIsGoogle(url: String): Boolean = SignIn.isGoogleSignIn(url)

/** What to tell the person when the sign-in window reaches Google's sign-in. */
@JsExport
fun signInGoogleMessage(): String = SignIn.GOOGLE_BLOCKED_MESSAGE
