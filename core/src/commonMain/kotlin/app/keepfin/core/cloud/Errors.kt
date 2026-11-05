package app.keepfin.core.cloud

// The error mapping of dev-docs/plans/KEEPFIN-CLOUD-SPEC.md section 2 and 7.1 #12. A failed response becomes one of seven kinds:
//   NeedsSignIn  the account must sign in again (401; `refresh` says a token refresh should be tried first)
//   RateLimited  slow down (429, Drive rate-limit reasons, Graph throttled/activityLimitReached/509, Dropbox write contention)
//   QuotaFull    the account has no room
//   NotFound     the object, or (context "upload") the upload session, is gone
//   Conflict     the name exists, a condition failed (412) or an offset was wrong
//   Transient    try again later (5xx, locked); a 503 is NOT assumed to be throttling
//   Fatal        do not retry; `code` says why (ResetRequired means a cursor or page token is no longer valid)
// `retryAfterMs` carries the server's Retry-After when it sent one.

internal fun retryAfterMs(r: Resp, nowMs: Long?, bodyJson: J? = null): Long? {
    val h = r.header("retry-after")?.trim()
    if (h != null) {
        val secs = h.toLongOrNull()
        if (secs != null && secs >= 0) return secs * 1000
        val at = parseHttpDate(h)
        if (at != null && nowMs != null) return if (at > nowMs) at - nowMs else 0
    }
    val inBody = bodyJson?.get("error")?.get("retry_after")?.long
    if (inBody != null && inBody >= 0) return inBody * 1000
    return null
}

private fun short(text: String?): String? = text?.let { if (it.length > 200) it.substring(0, 200) else it }

internal fun mapError(provider: String, r: Resp, ctx: String = "", nowMs: Long? = null): Map<String, Any?> {
    val body = r.json()
    var kind: String
    var code: String? = null
    var message: String? = null
    var refresh: Boolean? = null
    var correctOffset: Long? = null
    val status = r.status
    val retry = retryAfterMs(r, nowMs, body)
    val upload = ctx == "upload"
    when (provider) {
        "drive" -> {
            val e = body["error"]
            message = short(e.s("message"))
            val reason = e["errors"][0].s("reason") ?: e.s("status")
            code = reason
            kind = when {
                status == 401 -> { refresh = true; "NeedsSignIn" }
                status == 403 && (reason == "rateLimitExceeded" || reason == "userRateLimitExceeded" || reason == "sharingRateLimitExceeded" || reason == "dailyLimitExceeded") -> "RateLimited"
                status == 403 && reason == "storageQuotaExceeded" -> "QuotaFull"
                status == 403 && reason == "insufficientPermissions" -> "NeedsSignIn"
                status == 404 || (upload && status == 410) -> { if (upload) code = "SessionExpired"; "NotFound" }
                status == 409 -> "Conflict"
                status == 412 -> { code = "precondition"; "Conflict" }
                status == 429 -> "RateLimited"
                status == 400 && ctx == "list-continue" -> { code = "ResetRequired"; "Fatal" }
                status == 408 || status == 500 || status == 502 || status == 503 || status == 504 -> "Transient"
                else -> "Fatal"
            }
        }
        "graph" -> {
            val e = body["error"]
            message = short(e.s("message"))
            val gc = e.s("code")
            code = gc
            kind = when {
                status == 401 -> { refresh = true; "NeedsSignIn" }
                gc == "throttled" || gc == "TooManyRequests" || gc == "activityLimitReached" || status == 429 || status == 509 -> "RateLimited"
                status == 507 || gc == "quotaLimitReached" || gc == "insufficientStorage" -> "QuotaFull"
                status == 404 || (upload && status == 410) -> { if (upload) code = "SessionExpired"; "NotFound" }
                status == 410 -> { code = "ResetRequired"; "Fatal" }
                status == 409 -> "Conflict"
                status == 412 -> { code = "precondition"; "Conflict" }
                status == 416 && upload -> { code = "ResumeNeeded"; "Transient" }
                status == 423 -> { code = "locked"; "Transient" }
                status == 500 || status == 502 || status == 503 || status == 504 -> "Transient"
                else -> "Fatal"
            }
        }
        "dropbox" -> {
            val summary = body.s("error_summary") ?: ""
            val tag = body["error"][".tag"].str
            message = short(summary.ifEmpty { null } ?: r.body.take(200).ifEmpty { null })
            val head = summary.substringBefore('/')
            code = tag ?: head.ifEmpty { null }
            kind = when {
                status == 401 -> { refresh = tag == "expired_access_token"; "NeedsSignIn" }
                status == 429 || head == "too_many_write_operations" || head == "too_many_requests" -> "RateLimited"
                status == 403 && (tag == "missing_scope") -> { code = "missing_scope"; "NeedsSignIn" }
                status == 409 && summary.contains("insufficient_space") -> "QuotaFull"
                // append_v2 answers "incorrect_offset/..", finish answers "lookup_failed/incorrect_offset/.." with the offset one level
                // down; both mean "the server holds a different amount, continue from its offset", never an expired session
                status == 409 && (head == "incorrect_offset" || tag == "incorrect_offset" || summary.contains("/incorrect_offset")) -> {
                    correctOffset = body["error"].l("correct_offset") ?: body["error"]["lookup_failed"].l("correct_offset")
                    code = "incorrect_offset"
                    "Conflict"
                }
                status == 409 && head == "reset" -> { code = "ResetRequired"; "Fatal" }
                status == 409 && upload && (head == "lookup_failed" || summary.contains("not_found")) -> { code = "SessionExpired"; "NotFound" }
                status == 409 && summary.contains("not_found") -> { code = "not_found"; "NotFound" }
                status == 409 && (summary.contains("conflict") || head == "path" && summary.contains("conflict")) -> { code = "conflict"; "Conflict" }
                status == 500 || status == 502 || status == 503 || status == 504 -> "Transient"
                else -> "Fatal"
            }
        }
        else -> { // webdav
            message = short(r.body.take(200).ifEmpty { null })
            kind = when (status) {
                401 -> "NeedsSignIn"
                404 -> "NotFound"
                405 -> { code = "exists-or-unsupported"; "Conflict" }
                409 -> { code = "missing-parent"; "Conflict" }
                412 -> { code = "precondition"; "Conflict" }
                423 -> { code = "locked"; "Transient" }
                429 -> "RateLimited"
                507 -> "QuotaFull"
                500, 502, 503, 504 -> "Transient"
                else -> "Fatal"
            }
        }
    }
    return mapOfNotNull(
        "kind" to kind,
        "status" to status,
        "code" to code,
        "message" to message,
        "retryAfterMs" to (if (kind == "RateLimited" || kind == "Transient") retry else null),
        "refresh" to refresh,
        "correctOffset" to correctOffset,
    )
}

/** The failure result every parser returns: `{ok:false, error:{...}}`. */
internal fun fail(provider: String, r: Resp, ctx: String = "", nowMs: Long? = null): Map<String, Any?> =
    linkedMapOf("ok" to false, "error" to mapError(provider, r, ctx, nowMs))
