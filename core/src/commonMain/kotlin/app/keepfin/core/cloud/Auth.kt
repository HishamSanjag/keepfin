package app.keepfin.core.cloud

import app.keepfin.core.sync.sha256

// Sign-in logic (dev-docs/plans/KEEPFIN-CLOUD-SPEC.md section 3 and 7.1 #4, #5): OAuth 2.0 authorization code with PKCE (S256),
// described as URLs and requests. No client id lives here: it comes from build configuration. Expiry times come from the
// provider's answer (`expires_in`), never from a timer in the code. The platform opens the browser and stores the tokens.

private object Endpoints {
    fun authorize(provider: String, tenant: String): String = when (provider) {
        "drive" -> "https://accounts.google.com/o/oauth2/v2/auth"
        "graph" -> "https://login.microsoftonline.com/$tenant/oauth2/v2.0/authorize"
        "dropbox" -> "https://www.dropbox.com/oauth2/authorize"
        else -> throw IllegalArgumentException("'$provider' has no sign-in page (it uses an app password)")
    }

    fun token(provider: String, tenant: String): String = when (provider) {
        "drive" -> "https://oauth2.googleapis.com/token"
        "graph" -> "https://login.microsoftonline.com/$tenant/oauth2/v2.0/token"
        "dropbox" -> "https://api.dropboxapi.com/oauth2/token"
        else -> throw IllegalArgumentException("'$provider' has no token endpoint")
    }

    fun scopes(provider: String): String = when (provider) {
        "drive" -> "https://www.googleapis.com/auth/drive.file"
        "graph" -> "Files.ReadWrite.AppFolder offline_access"
        "dropbox" -> "files.content.read files.content.write files.metadata.read account_info.read"
        else -> ""
    }
}

private val FORM = mapOf("Content-Type" to "application/x-www-form-urlencoded")

/** RFC 7636 S256: BASE64URL(SHA-256(ASCII(verifier))) without padding. */
internal fun pkceChallenge(verifier: String): String = base64Of(sha256(verifier.encodeToByteArray()), url = true)

private fun hexToBytes(hex: String): ByteArray? {
    if (hex.length % 2 != 0) return null
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        val hi = "0123456789abcdef".indexOf(hex[2 * i].lowercaseChar())
        val lo = "0123456789abcdef".indexOf(hex[2 * i + 1].lowercaseChar())
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

internal fun authOp(op: String, a: J): Any? {
    val provider = a.s("provider") ?: ""
    val tenant = a.s("tenant") ?: "consumers"
    return when (op) {
        // A verifier from caller-supplied random bytes (hex, 32 to 96 bytes): 43 to 128 URL-safe characters.
        "pkce" -> {
            val random = hexToBytes(a.need("randomHex")) ?: throw IllegalArgumentException("randomHex is not hex")
            require(random.size in 32..96) { "PKCE needs 32 to 96 random bytes" }
            val verifier = base64Of(random, url = true)
            linkedMapOf("verifier" to verifier, "challenge" to pkceChallenge(verifier), "method" to "S256")
        }
        "pkceChallenge" -> linkedMapOf("challenge" to pkceChallenge(a.need("verifier")), "method" to "S256")
        "authUrl" -> {
            val params = ArrayList<Pair<String, String>>()
            params.add("client_id" to a.need("clientId"))
            params.add("response_type" to "code")
            params.add("redirect_uri" to a.need("redirectUri"))
            params.add("scope" to (a.s("scopes") ?: Endpoints.scopes(provider)))
            params.add("state" to a.need("state"))
            params.add("code_challenge" to a.need("codeChallenge"))
            params.add("code_challenge_method" to "S256")
            when (provider) {
                "drive" -> { params.add("access_type" to "offline"); params.add("prompt" to "consent") }
                "graph" -> params.add("response_mode" to "query")
                "dropbox" -> params.add("token_access_type" to "offline")
            }
            linkedMapOf("url" to Endpoints.authorize(provider, tenant) + "?" + formEncode(params))
        }
        "tokenRequest" -> {
            val params = arrayListOf(
                "grant_type" to "authorization_code",
                "code" to a.need("code"),
                "redirect_uri" to a.need("redirectUri"),
                "client_id" to a.need("clientId"),
                "code_verifier" to a.need("verifier"),
            )
            // Google's installed-app clients may still demand a client secret in the exchange (it is not a secret there).
            a.s("clientSecret")?.let { params.add("client_secret" to it) }
            if (provider == "graph") params.add("scope" to (a.s("scopes") ?: Endpoints.scopes(provider)))
            linkedMapOf("request" to request("POST", Endpoints.token(provider, tenant), FORM, formEncode(params), auth = false))
        }
        "refreshRequest" -> {
            val params = arrayListOf(
                "grant_type" to "refresh_token",
                "refresh_token" to a.need("refreshToken"),
                "client_id" to a.need("clientId"),
            )
            a.s("clientSecret")?.let { params.add("client_secret" to it) }
            if (provider == "graph") params.add("scope" to (a.s("scopes") ?: Endpoints.scopes(provider)))
            linkedMapOf("request" to request("POST", Endpoints.token(provider, tenant), FORM, formEncode(params), auth = false))
        }
        "revokeRequest" -> when (provider) {
            "drive" -> linkedMapOf("request" to request("POST", "https://oauth2.googleapis.com/revoke", FORM, formEncode(listOf("token" to a.need("token"))), auth = false))
            "dropbox" -> linkedMapOf("request" to request("POST", "https://api.dropboxapi.com/2/auth/token/revoke", emptyMap(), null, auth = true))
            // Microsoft has no revocation endpoint for personal accounts: removing the grant is done at account.microsoft.com.
            else -> linkedMapOf("request" to null, "reason" to "The provider has no revocation endpoint; the grant is removed in the account settings.")
        }
        "parseToken" -> {
            val r = Resp.of(a["response"])
            val body = r.json()
            val nowMs = a.l("nowMs")
            val access = body.s("access_token")
            if (r.status in 200..299 && access != null) {
                val expiresIn = body.numberLike("expires_in")
                mapOfNotNull(
                    "ok" to true,
                    "accessToken" to access,
                    "refreshToken" to (body.s("refresh_token") ?: a.s("previousRefreshToken")),
                    // The expiry is the provider's: now + the expires_in it sent. Unknown stays null (refresh on a 401).
                    "expiresAtMs" to (if (expiresIn != null && nowMs != null) nowMs + expiresIn * 1000 else null),
                    "scope" to body.s("scope"),
                    "tokenType" to body.s("token_type"),
                )
            } else {
                val err = body.s("error")
                val error: Map<String, Any?> = if (err == "invalid_grant" || err == "invalid_token" || err == "interaction_required" || err == "invalid_client" && r.status == 401) {
                    mapOfNotNull("kind" to "NeedsSignIn", "status" to r.status, "code" to err, "message" to (body.s("error_description")?.take(200)), "refresh" to false)
                } else {
                    mapError(provider, r, "token", nowMs)
                }
                linkedMapOf("ok" to false, "error" to error)
            }
        }
        // What the loopback or app-link callback delivered: the code, only when `state` is the one we sent.
        "parseCallback" -> {
            val url = a.need("url")
            val q = url.substringAfter('?', "").substringBefore('#')
            val map = LinkedHashMap<String, String>()
            for (part in q.split('&')) {
                if (part.isEmpty()) continue
                val k = pctDecode(part.substringBefore('=').replace('+', ' '))
                val v = pctDecode(part.substringAfter('=', "").replace('+', ' '))
                if (!map.containsKey(k)) map[k] = v
            }
            val expected = a.need("expectedState")
            when {
                map["state"] == null || !constantTimeEquals(map["state"]!!, expected) -> linkedMapOf("ok" to false, "reason" to "state-mismatch")
                map["error"] != null -> mapOfNotNull("ok" to false, "reason" to "denied", "error" to map["error"], "description" to map["error_description"])
                map["code"] == null -> linkedMapOf("ok" to false, "reason" to "no-code")
                else -> linkedMapOf("ok" to true, "code" to map["code"])
            }
        }
        else -> throw IllegalArgumentException("unknown auth op '$op'")
    }
}

internal fun constantTimeEquals(a: String, b: String): Boolean {
    var diff = a.length xor b.length
    val n = if (a.length < b.length) a.length else b.length
    for (i in 0 until n) diff = diff or (a[i].code xor b[i].code)
    return diff == 0
}
