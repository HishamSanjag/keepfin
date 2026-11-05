package app.keepfin.cloud

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest as AppAuthRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The platform's side of a sign-in (dev-docs/plans/KEEPFIN-CLOUD-SPEC.md 7.1 #4):
 *  - Google: the Android AuthorizationClient (Play services) for the `drive.file` scope. The app is registered with its package name and
 *    signing-certificate SHA-1, because custom-scheme redirects are not allowed for Google. There is no refresh token; Play services hands
 *    out a new access token without any screen while the grant stands.
 *  - Microsoft and Dropbox: the system browser (Custom Tabs through AppAuth), PKCE S256, `state` checked, the redirect `app.keepfin:/oauth`.
 * The code exchange and every expiry come from the shared core's requests and the provider's answers.
 */
internal class CloudSignIn(private val context: Context, private val ids: CloudIds, private val tokens: TokenClient) {
    private val pending = HashMap<String, OAuthStart>()

    companion object {
        const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

        private fun googleRequest() = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_FILE))).build()

        /** A new Google access token without any screen, or null when the person has to agree again. Blocks: not for the main thread. */
        fun googleSilent(context: Context): TokenSet? = try {
            val r = Tasks.await(Identity.getAuthorizationClient(context).authorize(googleRequest()), 30, TimeUnit.SECONDS)
            if (r.hasResolution()) null else r.accessToken?.let { TokenSet(it, null, null) }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------------------------------------------------- begin

    /** A screen to launch ([step]), or for Google a token that needed no screen ([googleToken]: the grant already stands). */
    class Begin(val step: SignInStep?, val googleToken: TokenSet? = null)

    fun begin(provider: String): Begin {
        if (!ids.has(provider)) return Begin(SignInStep.Failed("This provider is not available in this build."))
        return try {
            if (provider == "drive") beginGoogle() else Begin(beginBrowser(provider))
        } catch (e: SignInException) {
            Begin(SignInStep.Failed(e.message ?: "The sign-in could not be started."))
        } catch (e: Exception) {
            Begin(SignInStep.Failed("The sign-in could not be started. ${e.message ?: ""}".trim()))
        }
    }

    private fun beginGoogle(): Begin {
        val r: AuthorizationResult = Tasks.await(Identity.getAuthorizationClient(context).authorize(googleRequest()), 60, TimeUnit.SECONDS)
        r.pendingIntent?.takeIf { r.hasResolution() }?.let { return Begin(SignInStep.LaunchSender(it.intentSender)) }
        val token = r.accessToken ?: return Begin(SignInStep.Failed("Google did not give Keepfin access."))
        return Begin(null, TokenSet(token, null, null))
    }

    private fun beginBrowser(provider: String): SignInStep {
        val start = OAuthSignIn.begin(provider, ids)
        pending[provider] = start
        val uri = Uri.parse(start.url)
        val auth = uri.buildUpon().clearQuery().build()
        val config = AuthorizationServiceConfiguration(auth, auth)
        val b = AppAuthRequest.Builder(config, ids.of(provider), ResponseTypeValues.CODE, Uri.parse(start.redirectUri))
            .setScope(uri.getQueryParameter("scope"))
            .setState(start.state)
            .setCodeVerifier(start.verifier, uri.getQueryParameter("code_challenge"), "S256")
        uri.getQueryParameter("response_mode")?.let { b.setResponseMode(it) }
        val reserved = setOf("client_id", "response_type", "redirect_uri", "scope", "state", "code_challenge", "code_challenge_method", "response_mode", "prompt")
        val extra = HashMap<String, String>()
        for (name in uri.queryParameterNames) if (name !in reserved) uri.getQueryParameter(name)?.let { extra[name] = it }
        b.setAdditionalParameters(extra)
        val service = AuthorizationService(context)
        return SignInStep.Launch(service.getAuthorizationRequestIntent(b.build()))
    }

    // --------------------------------------------------------------------------------------------------------- finish

    /** Google's answer: the access token (the runtime stores the account). */
    fun finishGoogle(data: Intent?): TokenSet? {
        data ?: return null
        val r = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        return r.accessToken?.let { TokenSet(it, null, null) }
    }

    /** The browser's answer for Microsoft or Dropbox. Throws [SignInException] with words for the person. */
    fun finishBrowser(provider: String, data: Intent?): TokenSet {
        val start = pending.remove(provider) ?: throw SignInException("This sign-in expired. Start it again.")
        val response = data?.let { AuthorizationResponse.fromIntent(it) }
        val error = data?.let { AuthorizationException.fromIntent(it) }
        if (response == null) {
            if (error == null || error.type == AuthorizationException.TYPE_GENERAL_ERROR && error.code == AuthorizationException.GeneralErrors.USER_CANCELED_AUTH_FLOW.code) throw SignInException("The sign-in was cancelled.")
            throw SignInException("The provider refused the sign-in (${error.errorDescription ?: error.error ?: "error ${error.code}"}).")
        }
        val callback = start.redirectUri + "?code=" + URLEncoder.encode(response.authorizationCode ?: "", "UTF-8") + "&state=" + URLEncoder.encode(response.state ?: "", "UTF-8")
        return OAuthSignIn.finish(start, callback, ids, tokens)
    }
}
