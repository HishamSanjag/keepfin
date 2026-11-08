package app.keepfin

import app.keepfin.core.signin.SignIn
import app.keepfin.core.signin.SignInCookie
import java.io.File

/**
 * Exports sign-in cookies from the in-app WebView (android.webkit.CookieManager) into a Netscape cookies.txt
 * that yt-dlp reads with --cookies. CookieManager only exposes name=value pairs, so domain/path/secure/expiry
 * are written with safe defaults that yt-dlp (http.cookiejar) accepts.
 */
object CookieExport {
    data class Cookie(val domain: String, val name: String, val value: String, val secure: Boolean = true, val expires: Long)

    /** Sign-in sites from the shared core table: page URLs to query and the cookie domain written for them. */
    val sites: List<Pair<String, String>> = SignIn.sites.flatMap { site ->
        site.domains.flatMap { domain -> listOf("www.", "", "m.").map { "https://$it${domain.removePrefix(".")}" to domain } }
    }

    const val HEADER = "# Netscape HTTP Cookie File\n# Exported by Keepfin from in-app sign-in. Keep private.\n"

    /** Parses CookieManager.getCookie output ("a=1; b=2"). */
    fun parse(header: String?, domain: String, expires: Long): List<Cookie> = header.orEmpty().split(';').mapNotNull { part ->
        val trimmed = part.trim()
        val index = trimmed.indexOf('=')
        if (index <= 0) return@mapNotNull null
        val name = trimmed.substring(0, index).trim()
        val value = trimmed.substring(index + 1).trim()
        if (name.isEmpty() || name.any { it == '\t' || it == '\n' || it == '\r' || it == ' ' } || value.any { it == '\t' || it == '\n' || it == '\r' }) null
        else Cookie(domain, name, value, true, expires)
    }

    fun line(cookie: Cookie) = listOf(cookie.domain, "TRUE", "/", if (cookie.secure) "TRUE" else "FALSE", cookie.expires.toString(), cookie.name, cookie.value).joinToString("\t")

    /** The keys of the sites a cookies.txt holds a live sign-in for (the core decides; only names, domains and expiry are used). */
    fun signedInSites(cookiesFileText: String, nowSeconds: Long): Set<String> {
        val cookies = cookiesFileText.lineSequence().mapNotNull { line ->
            val parts = line.removePrefix("#HttpOnly_").split('\t')
            val expiry = parts.getOrNull(4)?.toLongOrNull()
            if (line.startsWith("#") && !line.startsWith("#HttpOnly_") || parts.size < 7 || expiry == null) null else SignInCookie(parts[5], parts[0], expiry)
        }.toList()
        return SignIn.sites.filter { SignIn.signedIn(it, cookies, nowSeconds) }.map { it.key }.toSet()
    }

    /** Merges exported cookies with an existing file: lines for exported domains are replaced, others are kept. */
    fun merge(existing: String?, cookies: List<Cookie>): String {
        val domains = cookies.map { it.domain }.toSet()
        val kept = existing.orEmpty().lineSequence().filter { line ->
            line.isNotBlank() && !line.startsWith("#") && line.split('\t').let { it.size >= 7 && it[0].removePrefix("#HttpOnly_") !in domains && "." + it[0].removePrefix("#HttpOnly_").removePrefix(".") !in domains }
        }.toList()
        val unique = cookies.associateBy { it.domain to it.name }.values
        return HEADER + (kept + unique.map(::line)).joinToString("\n", postfix = "\n")
    }

    /** Reads WebView cookies. Call off the main thread after the sign-in WebView finished. Returns the cookie count written. */
    fun export(target: File): Int {
        val manager = android.webkit.CookieManager.getInstance()
        manager.flush()
        val expires = System.currentTimeMillis() / 1000 + 180L * 24 * 3600
        val cookies = sites.flatMap { (url, domain) -> parse(manager.getCookie(url), domain, expires) }
        if (cookies.isEmpty()) return 0
        val text = merge(target.takeIf { it.isFile }?.readText(), cookies)
        val temporary = File(target.parentFile, target.name + ".tmp")
        temporary.writeText(text)
        temporary.setReadable(false, false); temporary.setReadable(true, true); temporary.setWritable(false, false); temporary.setWritable(true, true)
        check(temporary.renameTo(target)) { "Could not save the sign-in session." }
        return cookies.distinctBy { it.domain to it.name }.size
    }
}
