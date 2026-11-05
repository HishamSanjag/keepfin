package app.keepfin.cloud

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.keepfin.copies.writeFileAtomic
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypts a small string for this phone and app. The Android Keystore in the app; a software key in JVM tests. Secrets never leave the device. */
internal interface SecretBox {
    fun available(): Boolean
    fun encrypt(plain: String): ByteArray
    fun decrypt(blob: ByteArray): String
}

/** AES-GCM with a non-exportable key in the Android Keystore. The blob is the 12-byte IV followed by the ciphertext and tag. */
internal class KeystoreBox(private val alias: String = "keepfin-cloud-v1") : SecretBox {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return gen.generateKey()
    }

    override fun available() = try { key(); true } catch (e: Exception) { false }

    override fun encrypt(plain: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
    }

    override fun decrypt(blob: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
        return String(c.doFinal(blob, 12, blob.size - 12), Charsets.UTF_8)
    }
}

/** The public client ids this build was made with (Gradle properties / environment variables of the same names). A provider without one is "Not available in this build". */
internal class CloudIds(val google: String = "", val microsoft: String = "", val dropbox: String = "") {
    fun has(provider: String) = when (provider) {
        "drive" -> google.isNotBlank()
        "graph" -> microsoft.isNotBlank()
        "dropbox" -> dropbox.isNotBlank()
        else -> true
    }

    fun of(provider: String) = when (provider) { "drive" -> google; "graph" -> microsoft; "dropbox" -> dropbox; else -> "" }

    companion object {
        fun fromBuild() = CloudIds(app.keepfin.BuildConfig.KEEPFIN_GOOGLE_CLIENT_ID, app.keepfin.BuildConfig.KEEPFIN_MS_CLIENT_ID, app.keepfin.BuildConfig.KEEPFIN_DROPBOX_APP_KEY)
    }
}

internal class TokenSet(val accessToken: String, val refreshToken: String?, val expiresAtMs: Long?)

/** What the screens show of an account: never a token or a password. */
internal class AccountView(
    val id: String,
    val provider: String,
    val label: String,
    val needsSignIn: Boolean,
    val server: String? = null,
    val user: String? = null,
    /** Copies on this account may use mobile data (off: unmetered networks only). */
    val mobileData: Boolean = false,
)

internal class RevokeResult(val revoked: Boolean, val note: String, val url: String? = null)

internal const val MS_PERMISSIONS_URL = "https://account.live.com/consent/Manage"

/** Sends the token-endpoint requests (sign-in exchange and refresh). Nothing here is logged or stored. */
internal class TokenClient(private val rewrite: ((String) -> String)? = null, private val now: () -> Long = System::currentTimeMillis) {
    fun call(provider: String, request: JSONObject, previousRefreshToken: String? = null): TokenSet {
        val req = CloudRequest.of(request)
        val b = Request.Builder().url(rewrite?.invoke(req.url) ?: req.url)
        for ((k, v) in req.headers) b.header(k, v)
        b.method(req.method, req.body?.toRequestBody(null))
        val response = try {
            Http.shared.newCall(b.build()).execute().use { r ->
                val h = JSONObject()
                for (n in r.headers.names()) h.put(n.lowercase(), r.headers.values(n).joinToString(", "))
                JSONObject().put("status", r.code).put("headers", h).put("body", r.body?.string() ?: "")
            }
        } catch (e: java.io.IOException) {
            throw CloudError(Kind.TRANSIENT, code = "network", info = e.message, op = "token")
        }
        val inp = JSONObject().put("provider", provider).put("response", response).put("nowMs", now())
        previousRefreshToken?.let { inp.put("previousRefreshToken", it) }
        val parsed = coreCall(provider, "parseToken", inp)
        if (!parsed.optBoolean("ok")) throw CloudError.of(parsed.getJSONObject("error"), "token")
        return TokenSet(
            parsed.getString("accessToken"), parsed.optString("refreshToken").ifEmpty { null },
            if (parsed.has("expiresAtMs") && !parsed.isNull("expiresAtMs")) parsed.getLong("expiresAtMs") else null,
        )
    }
}

/**
 * Cloud accounts on this phone (dev-docs/plans/KEEPFIN-CLOUD-SPEC.md sections 3 and 7.1 #5). Tokens, and a WebDAV app password (the one
 * credential Keepfin keeps), are encrypted with [box] and never synced: each device signs in on its own. A port of
 * gui/desktop/cloud/accounts.ts:
 *  - a refresh is serialised per account and the new tokens replace the old ones atomically;
 *  - an expired access token is refreshed before NeedsSignIn is ever shown; NeedsSignIn is set only when the refresh itself is refused;
 *  - Disconnect deletes the local tokens only; Revoke is separate (it also removes the grant for the other devices where the provider can).
 * Google on Android has no refresh token: [googleRefresh] asks Play services for a new access token without any screen, and answers
 * null when it needs the person (then NeedsSignIn).
 */
internal class CloudAccounts(
    private val file: File,
    private val box: SecretBox,
    private val ids: CloudIds,
    private val tokens: TokenClient = TokenClient(),
    private val now: () -> Long = System::currentTimeMillis,
    private val googleRefresh: (() -> TokenSet?)? = null,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val tenant: String = "consumers",
) {
    private class Meta(val id: String, val provider: String, var label: String, var needsSignIn: Boolean, val server: String?, val user: String?, var mobileData: Boolean, val play: Boolean)
    private class Secret(var accessToken: String? = null, var refreshToken: String? = null, var expiresAtMs: Long? = null, var password: String? = null)
    private class Item(val meta: Meta, var secret: Secret)

    private var items: LinkedHashMap<String, Item>? = null
    private val refreshLocks = HashMap<String, Any>()

    @Synchronized private fun load(): LinkedHashMap<String, Item> {
        items?.let { return it }
        val map = LinkedHashMap<String, Item>()
        try {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("accounts") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                var needs = a.optBoolean("needsSignIn")
                var secret = Secret()
                try {
                    val s = JSONObject(box.decrypt(java.util.Base64.getDecoder().decode(a.getString("secret"))))
                    secret = Secret(s.optString("accessToken").ifEmpty { null }, s.optString("refreshToken").ifEmpty { null }, if (s.has("expiresAtMs") && !s.isNull("expiresAtMs")) s.getLong("expiresAtMs") else null, s.optString("password").ifEmpty { null })
                } catch (e: Exception) {
                    // the key changed (a restored backup, a new lock screen setup): the tokens are unreadable, so sign in again
                    needs = true
                }
                val meta = Meta(a.getString("id"), a.getString("provider"), a.optString("label"), needs, a.optString("server").ifEmpty { null }, a.optString("user").ifEmpty { null }, a.optBoolean("mobileData"), a.optBoolean("play"))
                map[meta.id] = Item(meta, secret)
            }
        } catch (e: Exception) {
            // none yet
        }
        items = map
        return map
    }

    @Synchronized private fun save() {
        val map = items ?: return
        if (!box.available()) throw IllegalStateException("Secure storage is not available on this phone, so Keepfin cannot keep a sign-in.")
        val arr = JSONArray()
        for (it in map.values) {
            val s = JSONObject()
            it.secret.accessToken?.let { v -> s.put("accessToken", v) }
            it.secret.refreshToken?.let { v -> s.put("refreshToken", v) }
            it.secret.expiresAtMs?.let { v -> s.put("expiresAtMs", v) }
            it.secret.password?.let { v -> s.put("password", v) }
            val m = it.meta
            val o = JSONObject().put("id", m.id).put("provider", m.provider).put("label", m.label).put("needsSignIn", m.needsSignIn).put("mobileData", m.mobileData).put("play", m.play)
            m.server?.let { v -> o.put("server", v) }
            m.user?.let { v -> o.put("user", v) }
            o.put("secret", java.util.Base64.getEncoder().encodeToString(box.encrypt(s.toString())))
            arr.put(o)
        }
        file.parentFile?.mkdirs()
        writeFileAtomic(file, JSONObject().put("version", 1).put("accounts", arr).toString().toByteArray(Charsets.UTF_8))
    }

    private fun view(m: Meta) = AccountView(m.id, m.provider, m.label, m.needsSignIn, m.server, m.user, m.mobileData)

    @Synchronized fun list(): List<AccountView> = load().values.map { view(it.meta) }

    @Synchronized fun get(id: String): AccountView? = load()[id]?.let { view(it.meta) }

    /** Stores the tokens of a new sign-in. [play] marks a Google account that Play services keeps signed in (no refresh token). */
    @Synchronized
    fun addOAuth(provider: String, t: TokenSet, label: String, play: Boolean = false): AccountView {
        val id = newId()
        val m = Meta(id, provider, label, false, null, null, false, play)
        load()[id] = Item(m, Secret(t.accessToken, t.refreshToken, t.expiresAtMs))
        save()
        return view(m)
    }

    @Synchronized
    fun addWebdav(server: String, user: String, password: String, label: String): AccountView {
        val id = newId()
        val m = Meta(id, "webdav", label, false, server, user, false, false)
        load()[id] = Item(m, Secret(password = password))
        save()
        return view(m)
    }

    /** A new sign-in for an existing account (Sign in again): replaces the tokens and clears NeedsSignIn. */
    @Synchronized
    fun replaceTokens(id: String, t: TokenSet): AccountView {
        val a = load()[id] ?: throw IllegalStateException("This account is not on this phone any more.")
        a.secret.accessToken = t.accessToken
        a.secret.refreshToken = t.refreshToken ?: a.secret.refreshToken
        a.secret.expiresAtMs = t.expiresAtMs
        a.meta.needsSignIn = false
        save()
        return view(a.meta)
    }

    @Synchronized
    fun setLabel(id: String, label: String) {
        val a = load()[id] ?: return
        if (a.meta.label == label) return
        a.meta.label = label
        save()
    }

    @Synchronized
    fun setMobileData(id: String, allowed: Boolean) {
        val a = load()[id] ?: return
        if (a.meta.mobileData == allowed) return
        a.meta.mobileData = allowed
        save()
    }

    @Synchronized
    fun setPassword(id: String, password: String): AccountView {
        val a = load()[id]
        if (a == null || a.meta.provider != "webdav") throw IllegalStateException("This account does not use a password.")
        a.secret = Secret(password = password)
        a.meta.needsSignIn = false
        save()
        return view(a.meta)
    }

    /** Disconnect on this phone: the local tokens are deleted and nothing else happens. The cloud files stay. */
    @Synchronized
    fun disconnect(id: String) {
        if (load().remove(id) != null) save()
    }

    /**
     * Revoke: asks the provider to end the grant, which signs out the user's other devices too, then disconnects here. Where the
     * provider has no endpoint (Microsoft personal accounts, WebDAV, Google through Play services) only the local part happens and the
     * answer says where to go.
     */
    fun revoke(id: String): RevokeResult {
        val a = synchronized(this) { load()[id] } ?: throw IllegalStateException("This account is not on this phone any more.")
        val p = a.meta.provider
        var out: RevokeResult
        if (p == "graph") out = RevokeResult(false, "Microsoft does not let an app end this sign-in for personal accounts. Remove Keepfin in your Microsoft account's app permissions.", MS_PERMISSIONS_URL)
        else if (p == "webdav") out = RevokeResult(false, "A WebDAV password is changed or removed on the server. Keepfin has forgotten it on this phone.")
        else if (a.meta.play) out = RevokeResult(false, "Google keeps this sign-in in your Google account. Remove Keepfin under \"Third-party apps and services\" there. Keepfin has forgotten it on this phone.", "https://myaccount.google.com/connections")
        else {
            out = RevokeResult(false, "Keepfin could not reach the provider to end the sign-in. It has been forgotten on this phone; remove Keepfin in the provider's account settings.")
            try {
                ensureFresh(id, false)
                val token = a.secret.accessToken
                val built = coreCall(p, "revokeRequest", JSONObject().put("provider", p).put("token", (if (p == "drive") a.secret.refreshToken else null) ?: token ?: ""))
                if (!built.isNull("request")) {
                    val req = CloudRequest.of(built.getJSONObject("request"))
                    val b = Request.Builder().url(req.url)
                    for ((k, v) in req.headers) b.header(k, v)
                    if (req.auth && token != null) b.header("Authorization", "Bearer $token")
                    b.method(req.method, (req.body ?: "").toRequestBody(null))
                    Http.shared.newCall(b.build()).execute().use { r -> if (r.isSuccessful) out = RevokeResult(true, "The sign-in was ended at the provider, so it is signed out on your other devices too.") }
                }
            } catch (e: Exception) {
                // keep the default note
            }
        }
        disconnect(id)
        return out
    }

    // --------------------------------------------------------------------------------------------------------- tokens

    private fun needsRefresh(s: Secret): Boolean = s.accessToken == null || (s.expiresAtMs != null && now() >= s.expiresAtMs!! - 60_000)

    private fun lockFor(id: String): Any = synchronized(refreshLocks) { refreshLocks.getOrPut(id) { Any() } }

    /** Refreshes the access token if it has expired (or [force]); one refresh per account at a time. Throws [CloudError]. */
    private fun ensureFresh(id: String, force: Boolean) {
        synchronized(lockFor(id)) {
            val a = synchronized(this) { load()[id] } ?: throw CloudError(Kind.NEEDS_SIGN_IN, code = "no-account", op = "token")
            if (a.meta.provider == "webdav") return
            if (!force && !needsRefresh(a.secret)) return
            if (a.meta.play) {
                val t = googleRefresh?.invoke()
                if (t == null) {
                    markNeedsSignIn(id)
                    throw CloudError(Kind.NEEDS_SIGN_IN, code = "needs-consent", op = "token")
                }
                synchronized(this) { a.secret.accessToken = t.accessToken; a.secret.expiresAtMs = t.expiresAtMs; save() }
                return
            }
            val refreshToken = a.secret.refreshToken
            if (refreshToken == null || !ids.has(a.meta.provider)) {
                markNeedsSignIn(id)
                throw CloudError(Kind.NEEDS_SIGN_IN, code = if (refreshToken != null) "unavailable" else "no-refresh-token", op = "token")
            }
            val built = coreCall(a.meta.provider, "refreshRequest", JSONObject().put("provider", a.meta.provider).put("refreshToken", refreshToken).put("clientId", ids.of(a.meta.provider)).put("tenant", tenant))
            val fresh = try {
                tokens.call(a.meta.provider, built.getJSONObject("request"), refreshToken)
            } catch (e: CloudError) {
                // Only a refused refresh means "sign in again"; a network or server hiccup does not.
                if (e.kind == Kind.NEEDS_SIGN_IN) markNeedsSignIn(id)
                throw e
            }
            // Refresh-token rotation: the new one replaces the old one in the same atomic write; if the provider sent none, the old stays.
            synchronized(this) {
                a.secret.accessToken = fresh.accessToken
                a.secret.refreshToken = fresh.refreshToken ?: refreshToken
                a.secret.expiresAtMs = fresh.expiresAtMs
                save()
            }
        }
    }

    @Synchronized private fun markNeedsSignIn(id: String) {
        val a = load()[id] ?: return
        if (a.meta.needsSignIn) return
        a.meta.needsSignIn = true
        runCatching { save() }
    }

    /** The Authorization header for [id]. Refreshes first when the access token has expired. */
    fun authorization(id: String): String {
        val a = synchronized(this) { load()[id] } ?: throw CloudError(Kind.NEEDS_SIGN_IN, code = "no-account", op = "token")
        if (a.meta.needsSignIn) throw CloudError(Kind.NEEDS_SIGN_IN, code = "signed-out", op = "token")
        if (a.meta.provider == "webdav") {
            return coreCall("webdav", "basicAuth", JSONObject().put("user", a.meta.user ?: "").put("password", a.secret.password ?: "")).getString("authorization")
        }
        ensureFresh(id, false)
        return "Bearer ${a.secret.accessToken}"
    }

    /** After a 401: a new access token (true), or false when the account cannot get one (it is marked NeedsSignIn if the refresh was refused). */
    fun refreshNow(id: String): Boolean = try {
        ensureFresh(id, true)
        true
    } catch (e: CloudError) {
        if (e.kind == Kind.NEEDS_SIGN_IN) false else throw e
    }

    /** An account for [CloudHttp] that lives in this process. */
    fun account(id: String, provider: String): CloudAccount = object : CloudAccount {
        override val id = id
        override val provider = provider
        override fun authorization() = this@CloudAccounts.authorization(id)
        override fun refresh() = refreshNow(id)
    }

    /** The server address and user of a WebDAV account. */
    @Synchronized fun webdav(id: String): Pair<String, String>? = load()[id]?.meta?.takeIf { it.provider == "webdav" && it.server != null }?.let { it.server!! to (it.user ?: "") }
}
