package app.keepfin.cloud

import org.json.JSONObject
import java.security.SecureRandom

/** A sign-in the person has to finish in the browser. Nothing here is secret except [verifier], which stays in memory until the code comes back. */
internal class OAuthStart(val provider: String, val url: String, val verifier: String, val state: String, val redirectUri: String)

internal class SignInException(message: String) : Exception(message)

/**
 * The OAuth 2.0 authorization-code flow with PKCE (S256) for Microsoft and Dropbox (dev-docs/plans/KEEPFIN-CLOUD-SPEC.md section 3): the
 * shared core builds the authorization URL, checks `state` and describes the token request; the platform opens the browser (AppAuth with
 * Custom Tabs) and delivers the redirect. Expiry times come from the provider's answer.
 */
internal object OAuthSignIn {
    const val REDIRECT = "app.keepfin:/oauth"

    fun begin(provider: String, ids: CloudIds, redirectUri: String = REDIRECT, tenant: String = "consumers", random: SecureRandom = SecureRandom()): OAuthStart {
        if (!ids.has(provider)) throw SignInException("This provider is not available in this build.")
        val verifierRandom = ByteArray(48).also { random.nextBytes(it) }
        val pkce = coreCall(provider, "pkce", JSONObject().put("randomHex", hex(verifierRandom)))
        val state = hex(ByteArray(16).also { random.nextBytes(it) })
        val url = coreCall(
            provider, "authUrl",
            JSONObject().put("provider", provider).put("clientId", ids.of(provider)).put("redirectUri", redirectUri).put("state", state).put("codeChallenge", pkce.getString("challenge")).put("tenant", tenant),
        ).getString("url")
        return OAuthStart(provider, url, pkce.getString("verifier"), state, redirectUri)
    }

    /** The redirect the browser was sent back to becomes tokens, only if `state` is the one that was sent. */
    fun finish(start: OAuthStart, callbackUrl: String, ids: CloudIds, tokens: TokenClient, tenant: String = "consumers"): TokenSet {
        val cb = coreCall(start.provider, "parseCallback", JSONObject().put("url", callbackUrl).put("expectedState", start.state))
        if (!cb.optBoolean("ok")) {
            throw SignInException(
                when (cb.optString("reason")) {
                    "denied" -> "The sign-in was cancelled."
                    "state-mismatch" -> "The sign-in did not come from this request, so it was ignored. Try again."
                    else -> "The provider did not send a sign-in code. Try again."
                },
            )
        }
        val built = coreCall(
            start.provider, "tokenRequest",
            JSONObject().put("provider", start.provider).put("code", cb.getString("code")).put("redirectUri", start.redirectUri).put("clientId", ids.of(start.provider)).put("verifier", start.verifier).put("tenant", tenant),
        )
        return tokens.call(start.provider, built.getJSONObject("request"))
    }
}
