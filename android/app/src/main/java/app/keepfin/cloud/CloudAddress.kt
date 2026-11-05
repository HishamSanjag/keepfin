package app.keepfin.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Where a cloud copy lives, written the way the copies engine stores a folder address (`treeUri`): `keepfin-cloud://<accountId>/<root>`.
 * The root is the replica's folder on the account (`Keepfin/<name>`; for WebDAV the folder under the server address that the person
 * named). The account itself (tokens, password) is separate and never part of the address.
 */
internal object CloudAddress {
    const val SCHEME = "keepfin-cloud"

    fun isCloud(address: String?): Boolean = address != null && address.startsWith("$SCHEME://")

    fun make(accountId: String, root: String): String =
        "$SCHEME://${enc(accountId)}/" + root.split('/').filter { it.isNotEmpty() }.joinToString("/") { enc(it) }

    /** (account id, root path) or null when [address] is not a cloud address. */
    fun parse(address: String): Pair<String, String>? {
        if (!isCloud(address)) return null
        val rest = address.removePrefix("$SCHEME://")
        val slash = rest.indexOf('/')
        val id = if (slash < 0) rest else rest.substring(0, slash)
        val path = if (slash < 0) "" else rest.substring(slash + 1)
        return dec(id) to path.split('/').filter { it.isNotEmpty() }.joinToString("/") { dec(it) }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun dec(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
}

/** The network rule of cloud copies (dev-docs/plans/KEEPFIN-CLOUD-SPEC.md section 4): unmetered networks only, unless the person allowed mobile data for the account. */
internal object NetworkRule {
    /** Why requests must wait right now, or null when they may go. */
    fun reason(context: Context, allowMobile: Boolean): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val net = cm.activeNetwork ?: return "There is no network connection."
        val caps = cm.getNetworkCapabilities(net) ?: return "There is no network connection."
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return "There is no network connection."
        if (!allowMobile && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return "Waiting for Wi-Fi. Allow mobile data for this account to sync now."
        return null
    }
}

/** An https address (http only for this phone itself, which tests and a local server use); a trailing slash is removed. */
internal object UrlPolicy {
    fun validateWebdav(url: String): String {
        val u = try {
            java.net.URI(url.trim())
        } catch (e: Exception) {
            throw SignInException("Enter the full address of the server, starting with https://")
        }
        val host = u.host ?: throw SignInException("Enter the full address of the server, starting with https://")
        val local = host == "localhost" || host == "127.0.0.1" || host == "[::1]" || host == "::1"
        if (u.scheme != "https" && !(u.scheme == "http" && local)) throw SignInException("Use an https:// address. Keepfin will not send a password over an unencrypted connection.")
        if (u.userInfo != null) throw SignInException("Leave the user name and password out of the address; enter them in their own boxes.")
        return (u.scheme + "://" + u.rawAuthority + (u.rawPath ?: "")).trimEnd('/')
    }

    fun hostOf(server: String?): String = try { java.net.URI(server ?: "").host ?: (server ?: "") } catch (e: Exception) { server ?: "" }
}
