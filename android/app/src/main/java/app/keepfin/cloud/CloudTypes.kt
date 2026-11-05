package app.keepfin.cloud

import app.keepfin.core.cloud.CloudApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

// Cloud accounts on Android (dev-docs/plans/KEEPFIN-CLOUD-SPEC.md). The shared core describes each request and reads each answer
// (app.keepfin.core.cloud.CloudApi); this package sends them with OkHttp. The names and shapes follow gui/desktop/cloud so that a
// phone and a computer can share one replica.

internal object Kind {
    const val NEEDS_SIGN_IN = "NeedsSignIn"
    const val RATE_LIMITED = "RateLimited"
    const val QUOTA_FULL = "QuotaFull"
    const val NOT_FOUND = "NotFound"
    const val CONFLICT = "Conflict"
    const val TRANSIENT = "Transient"
    const val FATAL = "Fatal"
}

/** A failed cloud call. [kind] is one of [Kind]; the core's error mapping decides it. */
internal class CloudError(
    val kind: String,
    val status: Int = 0,
    val code: String? = null,
    val info: String? = null,
    val retryAfterMs: Long? = null,
    /** A 401 whose token may only have expired: refresh once before showing NeedsSignIn. */
    val refresh: Boolean = false,
    val correctOffset: Long? = null,
    val op: String = "",
) : IOException("$kind${if (code != null) " ($code)" else ""}${if (info != null) ": $info" else ""}${if (op.isNotEmpty()) " during $op" else ""}") {
    companion object {
        fun of(e: JSONObject, op: String = "") = CloudError(
            e.optString("kind", Kind.FATAL), e.optInt("status"), e.optString("code").ifEmpty { null }, e.optString("message").ifEmpty { null },
            if (e.has("retryAfterMs")) e.optLong("retryAfterMs") else null, e.optBoolean("refresh"), if (e.has("correctOffset")) e.optLong("correctOffset") else null, op,
        )
    }
}

/** Where an object is addressed: by id (Drive) or by path (the others). */
internal data class Ref(val id: String? = null, val path: String? = null) {
    fun json(): JSONObject = JSONObject().also { o -> id?.let { o.put("id", it) }; path?.let { o.put("path", it) } }
}

internal class CloudEntry(
    val id: String?,
    val name: String,
    val folder: Boolean,
    val size: Long?,
    val mtimeMs: Long?,
    val createdMs: Long?,
    val etag: String?,
    val hashes: Map<String, String>,
    val parentId: String?,
    val path: String?,
) {
    companion object {
        fun of(o: JSONObject): CloudEntry {
            val h = HashMap<String, String>()
            o.optJSONObject("hashes")?.let { hs -> for (k in hs.keys()) h[k] = hs.optString(k) }
            fun str(k: String) = if (o.has(k) && !o.isNull(k)) o.optString(k).ifEmpty { null } else null
            fun lng(k: String) = if (o.has(k) && !o.isNull(k)) o.optLong(k) else null
            return CloudEntry(str("id"), o.optString("name"), o.optBoolean("folder"), lng("size"), lng("mtimeMs"), lng("createdMs"), str("etag"), h, str("parentId"), str("path"))
        }
    }
}

internal class UploadSession(val json: JSONObject) {
    val single: Boolean get() = json.optBoolean("single")
}

/** What the provider can do (the core's capability table, after the WebDAV probe). */
internal class Caps(val json: JSONObject) {
    val stableIds get() = json.optBoolean("stableIds")
    val duplicateNames get() = json.optBoolean("duplicateNames")
    val serverHash: String? get() = json.optJSONObject("serverHash")?.let { if (it.isNull("algorithm")) null else it.optString("algorithm") }
    val createOnlyByName get() = json.optJSONObject("createOnly")?.optBoolean("byName") == true
    val casReplace get() = json.optJSONObject("cas")?.optBoolean("replace") == true
    val casDelete get() = json.optJSONObject("cas")?.optBoolean("delete") == true
    val rangeDownload get() = json.optBoolean("rangeDownload")
    val chunkPreferred: Long get() = json.optJSONObject("upload")?.optLong("chunkPreferred") ?: 0L
    val resumable get() = json.optJSONObject("upload")?.optBoolean("resumable") == true
}

internal class CloudResponse(val status: Int, val headers: Map<String, String>, val body: String) {
    fun json(): JSONObject = JSONObject().put("status", status).put("headers", JSONObject(headers)).put("body", body)
}

internal class CloudRequest(val method: String, val url: String, val headers: Map<String, String>, val auth: Boolean, val body: String?, val bodyOffset: Long?, val bodyLength: Long?, val manualRedirect: Boolean) {
    companion object {
        fun of(o: JSONObject): CloudRequest {
            val h = LinkedHashMap<String, String>()
            o.optJSONObject("headers")?.let { hs -> for (k in hs.keys()) h[k] = hs.optString(k) }
            val ref = o.optJSONObject("bodyRef")
            return CloudRequest(
                o.getString("method"), o.getString("url"), h, o.optBoolean("auth", true), if (o.has("body") && !o.isNull("body")) o.getString("body") else null,
                ref?.optLong("offset"), ref?.optLong("length"), o.optString("redirect") == "manual",
            )
        }
    }
}

/** One call through the core's single door; throws [IllegalArgumentException] when the core refuses the input (a rule, not a network error). */
internal fun coreCall(provider: String, op: String, input: JSONObject): JSONObject = JSONObject(CloudApi.call(provider, op, input.toString()))

internal fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

internal fun hex(bytes: ByteArray): String {
    val chars = CharArray(bytes.size * 2)
    for ((i, b) in bytes.withIndex()) {
        val v = b.toInt() and 0xff
        chars[i * 2] = "0123456789abcdef"[v ushr 4]
        chars[i * 2 + 1] = "0123456789abcdef"[v and 15]
    }
    return String(chars)
}
