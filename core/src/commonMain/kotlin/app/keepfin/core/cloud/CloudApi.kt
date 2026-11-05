package app.keepfin.core.cloud

import app.keepfin.core.json.JsonException
import app.keepfin.core.json.parseJson

/**
 * The one door of the cloud providers (dev-docs/plans/KEEPFIN-CLOUD-SPEC.md section 2): [call] takes JSON text and returns JSON
 * text. Providers are "drive", "graph", "dropbox", "webdav". Builders return `{request: {method,url,headers,auth,body?,bodyRef?}}`;
 * parsers (`parse*`) take `{response:{status,headers,body}}` and return `{ok:true,...}` or `{ok:false,error:{kind,...}}`.
 * Shared ops: name* (NameRules), pkce/authUrl/tokenRequest/refreshRequest/revokeRequest/parseToken/parseCallback (sign-in),
 * dropboxHash / quickXorHash (of hex bytes, for tests), hashBlocks (combine Dropbox block digests).
 */
object CloudApi {
    fun call(provider: String, op: String, input: String): String {
        val a = try {
            J(parseJson(input))
        } catch (e: JsonException) {
            throw IllegalArgumentException("the input is not JSON: ${e.message}")
        }
        val out: Any? = when {
            op.startsWith("name") || op == "pathCheck" -> namesOp(op, J(withProvider(a, provider)))
            op == "pkce" || op == "pkceChallenge" || op == "authUrl" || op == "tokenRequest" || op == "refreshRequest" || op == "revokeRequest" ||
                op == "parseToken" || op == "parseCallback" -> authOp(op, J(withProvider(a, provider)))
            op == "quickXorHash" -> linkedMapOf("hash" to QuickXorHash.of(hexBytes(a.need("hex"))))
            op == "dropboxHash" -> linkedMapOf("hash" to DropboxContentHash.of(hexBytes(a.need("hex"))))
            op == "hashBlocks" -> linkedMapOf("hash" to DropboxContentHash.combineBlockDigests(a["digests"].list.map { hexBytes(it.str ?: "") }))
            else -> when (provider) {
                "drive" -> Drive.handle(op, a)
                "graph" -> Graph.handle(op, a)
                "dropbox" -> Dropbox.handle(op, a)
                "webdav" -> WebDav.handle(op, a)
                else -> throw IllegalArgumentException("unknown provider '$provider'")
            }
        }
        return jsonOut(out)
    }

    private fun withProvider(a: J, provider: String): app.keepfin.core.json.JsonValue {
        val members = LinkedHashMap<String, app.keepfin.core.json.JsonValue>()
        (a.v as? app.keepfin.core.json.JsonObject)?.members?.let { members.putAll(it) }
        if (!members.containsKey("provider")) members["provider"] = app.keepfin.core.json.JsonString(provider)
        return app.keepfin.core.json.JsonObject(members)
    }

    private fun hexBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) out[i] = ((("0123456789abcdef".indexOf(hex[2 * i].lowercaseChar())) shl 4) or "0123456789abcdef".indexOf(hex[2 * i + 1].lowercaseChar())).toByte()
        return out
    }
}
