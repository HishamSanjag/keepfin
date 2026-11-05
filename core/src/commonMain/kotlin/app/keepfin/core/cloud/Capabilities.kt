package app.keepfin.core.cloud

// What each provider can do (dev-docs/plans/KEEPFIN-COPIES-SPEC.md section 9, KEEPFIN-CLOUD-SPEC.md sections 2 and 7.1). A missing
// capability makes the executor conservative: no server hash means download and hash locally; no compare-and-swap (cas) means
// displaced bytes move to `.keepfin/trash` by id and nothing is overwritten or deleted in place.
// `unverified` lists facts taken from the provider's documentation that no test or live call has proven yet.

internal const val MIB = 1024L * 1024L
internal const val KIB = 1024L

private fun upload(multiple: Long, preferred: Long, max: Long, ttlDays: Long?, sequential: Boolean, resumeQuery: String): Map<String, Any?> =
    mapOfNotNull(
        "resumable" to true,
        "chunkMultiple" to multiple,
        "chunkPreferred" to preferred,
        "chunkMax" to max,
        "sessionTtlDays" to ttlDays,
        "sequential" to sequential,
        "resume" to resumeQuery,
    )

internal fun capabilitiesOf(provider: String, probe: J? = null): Map<String, Any?> = when (provider) {
    "drive" -> linkedMapOf(
        "provider" to "drive",
        "scope" to "drive.file",
        "listing" to linkedMapOf("paged" to true, "pageSize" to 1000, "incompleteFlag" to "incompleteSearch", "changeFeed" to "changes.list (a hint, never a deletion)"),
        "stableIds" to true,
        "identity" to "id",
        "duplicateNames" to true,
        "serverHash" to linkedMapOf("algorithm" to "sha256", "fallback" to "md5", "sha256WhenPresent" to true),
        "createOnly" to linkedMapOf("byName" to false, "mechanism" to "create, then list the folder: the earliest createdTime wins"),
        "cas" to linkedMapOf("replace" to false, "delete" to false, "probeNeeded" to false),
        "rangeDownload" to true,
        "upload" to upload(256 * KIB, 8 * MIB, 8 * MIB, 7, false, "PUT Content-Range bytes */total"),
        "moveToTrashById" to true,
        "quota" to true,
        "unverified" to listOf(
            "sha256Checksum is returned for every binary file (documented as 'if applicable')",
            "no content compare-and-swap (the spec marks this unverified until proven)",
        ),
    )
    "graph" -> linkedMapOf(
        "provider" to "graph",
        "scope" to "Files.ReadWrite.AppFolder offline_access",
        "listing" to linkedMapOf("paged" to true, "pageSize" to 200, "incompleteFlag" to null, "changeFeed" to "delta (optional; 410 means resync)"),
        "stableIds" to true,
        "identity" to "id",
        "duplicateNames" to false,
        "serverHash" to linkedMapOf("algorithm" to "quickXor", "fallback" to "sha1", "sha256WhenPresent" to false),
        "createOnly" to linkedMapOf("byName" to true, "mechanism" to "@microsoft.graph.conflictBehavior=fail (in the session's item; checked at the final commit)"),
        "cas" to linkedMapOf("replace" to true, "delete" to true, "probeNeeded" to false, "mechanism" to "If-Match with the eTag"),
        "rangeDownload" to true,
        "upload" to upload(320 * KIB, 10 * MIB, 60 * MIB, null, true, "GET uploadUrl: nextExpectedRanges"),
        "moveToTrashById" to true,
        "quota" to true,
        "unverified" to listOf(
            "upload sessions and listings work with Files.ReadWrite.AppFolder alone (7.1 #7)",
            "createUploadSession honours If-Match",
            "Range requests are honoured on the redirected download URL",
        ),
    )
    "dropbox" -> linkedMapOf(
        "provider" to "dropbox",
        "scope" to "files.content.read files.content.write files.metadata.read account_info.read",
        "listing" to linkedMapOf("paged" to true, "pageSize" to 2000, "incompleteFlag" to null, "changeFeed" to "list_folder/continue cursor (reset error means list again)"),
        "stableIds" to true,
        "identity" to "id",
        "duplicateNames" to false,
        "serverHash" to linkedMapOf("algorithm" to "dropbox", "fallback" to null, "sha256WhenPresent" to false),
        "createOnly" to linkedMapOf("byName" to true, "mechanism" to "mode=add, autorename=false, strict_conflict=true"),
        "cas" to linkedMapOf("replace" to true, "delete" to true, "probeNeeded" to false, "mechanism" to "mode=update with rev (+ strict_conflict); delete_v2 with parent_rev"),
        "rangeDownload" to true,
        "upload" to upload(4 * MIB, 8 * MIB, 150 * MIB, 7, true, "append_v2 at the known offset: incorrect_offset tells the right one"),
        "moveToTrashById" to true,
        "quota" to true,
        "unverified" to listOf(
            "files/download honours Range",
            "strict_conflict is accepted in the upload commit info",
            "a session lives 7 days",
        ),
    )
    else -> webdavCapabilities(probe)
}

internal fun webdavCapabilities(probe: J?): Map<String, Any?> {
    val cas = probe?.get("cas")?.bool == true
    val range = probe?.get("range")?.bool == true
    val createOnly = probe?.get("createOnly")?.bool == true
    val noReplaceMove = probe?.get("noReplaceMove")?.bool == true
    return linkedMapOf(
        "provider" to "webdav",
        "scope" to "account url + app password",
        "listing" to linkedMapOf("paged" to false, "pageSize" to 0, "incompleteFlag" to "per-resource 207 failures", "changeFeed" to null, "shardAdvice" to "PROPFIND Depth 1 is not paginated: shard media folders"),
        "stableIds" to false,
        "identity" to "path",
        "duplicateNames" to false,
        "serverHash" to linkedMapOf("algorithm" to null, "fallback" to null, "sha256WhenPresent" to false),
        "createOnly" to linkedMapOf("byName" to createOnly, "mechanism" to "If-None-Match: * (only after the probe); else a temporary name, then MOVE with Overwrite: F when the probe proved that", "noReplaceMove" to noReplaceMove),
        "cas" to linkedMapOf("replace" to cas, "delete" to cas, "probeNeeded" to true, "probed" to (probe != null), "mechanism" to "If-Match with a strong ETag, only after the probe passed"),
        "rangeDownload" to range,
        "upload" to linkedMapOf("resumable" to false, "chunkMultiple" to 1, "chunkPreferred" to 0, "chunkMax" to 0, "sequential" to true, "resume" to "restart the file (Nextcloud v2 chunking when announced)"),
        "moveToTrashById" to false,
        "quota" to true,
        "unverified" to listOf(
            "ETags are strong on this server (only the probe can tell)",
            "quota-available-bytes is answered (RFC 4331 is optional)",
        ),
    )
}

/**
 * The WebDAV probe (7.1 #1, #9). The platform runs it against a scratch object and hands the observations back:
 *   `put`        {status, etag}   a plain PUT of the scratch object
 *   `wrongMatch` {status}         a PUT with If-Match set to a deliberately wrong tag
 *   `rightMatch` {status}         a PUT with If-Match set to the tag from `put`
 *   `range`      {status, contentRange}  a GET with Range: bytes=1-2
 *   `noneMatch`  {status}         a PUT of the existing scratch object with If-None-Match: * (must be refused with 412)
 *   `moveOver`   {status}         a MOVE onto an existing object with Overwrite: F (must be refused with 412)
 * Compare-and-swap is granted only when the wrong tag was refused (412), the right one accepted and the tag was strong.
 */
internal fun webdavProbeEvaluate(a: J): Map<String, Any?> {
    val etag = a["put"].s("etag")
    val strong = etag != null && !etag.startsWith("W/") && etag.length >= 2
    val wrongRefused = a["wrongMatch"].l("status") == 412L
    val rightOk = (a["rightMatch"].l("status") ?: 0L) in 200L..299L
    val ignoredIfMatch = (a["wrongMatch"].l("status") ?: 0L) in 200L..299L
    val rangeStatus = a["range"].l("status")
    val rangeOk = rangeStatus == 206L
    val cas = strong && wrongRefused && rightOk
    return linkedMapOf(
        "cas" to cas,
        "range" to rangeOk,
        "createOnly" to (a["noneMatch"].l("status") == 412L),
        "noReplaceMove" to (a["moveOver"].l("status") == 412L),
        "strongEtag" to strong,
        "ifMatchIgnored" to ignoredIfMatch,
        "reason" to (when {
            cas -> "ok"
            ignoredIfMatch -> "The server ignored a wrong If-Match: no safe overwrite or delete"
            !strong -> "The server gave no strong ETag"
            else -> "The server did not answer If-Match as expected"
        }),
    )
}
