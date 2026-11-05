package app.keepfin.core.cloud

import app.keepfin.core.json.jsonQuote

// Google Drive v3 with the drive.file scope (the files this app created, under a `Keepfin` folder). Objects are identified by id,
// never by name: Drive allows duplicate names. Drive has no content compare-and-swap, so replace and delete-in-place are
// refused; displaced bytes move to the Keepfin trash folder by id. Uploads are resumable (8 MiB chunks, multiples of 256 KiB).

internal object Drive {
    private const val API = "https://www.googleapis.com/drive/v3"
    private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
    private const val FOLDER = "application/vnd.google-apps.folder"
    private const val FIELDS = "id,name,mimeType,size,md5Checksum,sha256Checksum,modifiedTime,createdTime,version,parents,trashed"
    private val JSON = mapOf("Content-Type" to "application/json; charset=UTF-8")

    private fun q(s: String): String = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    private fun idOf(ref: J): String = ref.s("id") ?: (if (ref.s("path").isNullOrEmpty()) "root" else throw IllegalArgumentException("Drive addresses objects by id"))

    fun entry(f: J): Map<String, Any?> {
        val hashes = LinkedHashMap<String, Any?>()
        f.s("sha256Checksum")?.let { hashes["sha256"] = it }
        f.s("md5Checksum")?.let { hashes["md5"] = it }
        return mapOfNotNull(
            "id" to f.s("id"),
            "name" to f.s("name"),
            "folder" to (f.s("mimeType") == FOLDER),
            "size" to f.numberLike("size"),
            "mtimeMs" to parseIso(f.s("modifiedTime")),
            "createdMs" to parseIso(f.s("createdTime")),
            "etag" to f.s("version"),
            "hashes" to hashes,
            "parentId" to f["parents"][0].str,
            "trashed" to (if (f.bool("trashed")) true else null),
        )
    }

    private fun J.bool(key: String): Boolean = this[key].bool == true

    private fun fieldsQuery(): String = "fields=" + pctEncode(FIELDS, ",")

    private fun contentRange(offset: Long, length: Long, total: Long): String =
        if (total == 0L) "bytes */0" else "bytes $offset-${offset + length - 1}/$total"

    fun handle(op: String, a: J): Any? = when (op) {
        "capabilities" -> capabilitiesOf("drive")
        "list" -> {
            val cursor = a.s("cursor")
            val folder = idOf(a["folder"])
            var query = q(folder) + " in parents and trashed=false"
            a.s("name")?.let { query += " and name=" + q(it) }
            if (a.bool("foldersOnly") == true) query += " and mimeType=" + q(FOLDER)
            val params = StringBuilder("q=" + pctEncode(query))
            params.append("&fields=").append(pctEncode("nextPageToken,incompleteSearch,files($FIELDS)", ",()"))
            params.append("&pageSize=1000&spaces=drive")
            if (cursor != null) params.append("&pageToken=").append(pctEncode(cursor))
            linkedMapOf("request" to request("GET", "$API/files?$params"))
        }
        "parseList" -> {
            val r = Resp.of(a["response"])
            if (!r.ok) fail("drive", r, if (a.has("cursor")) "list-continue" else "list", a.l("nowMs")) else {
                val j = r.json()
                linkedMapOf(
                    "ok" to true,
                    "entries" to j["files"].list.map { entry(it) },
                    "nextCursor" to j.s("nextPageToken"),
                    "incomplete" to (j["incompleteSearch"].bool == true),
                )
            }
        }
        "stat" -> linkedMapOf("request" to request("GET", "$API/files/${pctEncode(idOf(a["ref"]))}?${fieldsQuery()}"))
        "createFolder" -> {
            val body = "{\"name\":${jsonQuote(a.need("name"))},\"mimeType\":\"$FOLDER\",\"parents\":[${jsonQuote(idOf(a["parent"]))}]}"
            linkedMapOf("request" to request("POST", "$API/files?${fieldsQuery()}", JSON, body))
        }
        // Drive has no create-only by name: the object is created, then the folder is listed and the earliest createdTime wins.
        "createSmall" -> {
            val content = a.s("content") ?: ""
            val meta = "{\"name\":${jsonQuote(a.need("name"))},\"parents\":[${jsonQuote(idOf(a["parent"]))}]}"
            var boundary = "keepfin_boundary_7a1f3c"
            while (content.contains(boundary) || meta.contains(boundary)) boundary += "x"
            val body = "--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n$meta\r\n--$boundary\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n$content\r\n--$boundary--"
            linkedMapOf("request" to request("POST", "$UPLOAD/files?uploadType=multipart&${fieldsQuery()}", mapOf("Content-Type" to "multipart/related; boundary=$boundary"), body))
        }
        // Only the advisory lock object (written by this run, under its own id) may be rewritten in place.
        "replaceSmall" -> if (a.bool("advisory") == true) {
            linkedMapOf("request" to request("PATCH", "$UPLOAD/files/${pctEncode(idOf(a["ref"]))}?uploadType=media&${fieldsQuery()}", mapOf("Content-Type" to "application/octet-stream"), a.s("content") ?: ""))
        } else {
            unsupported("Drive has no content compare-and-swap: displaced bytes move to the Keepfin trash by id")
        }
        "deleteCas" -> unsupported("Drive has no conditional delete: move the object to the Keepfin trash by id")
        "move" -> {
            val params = StringBuilder(fieldsQuery())
            a.s("newParentId")?.let { params.append("&addParents=").append(pctEncode(it)) }
            a.s("fromParentId")?.let { params.append("&removeParents=").append(pctEncode(it)) }
            val body = if (a.has("newName")) "{\"name\":${jsonQuote(a.need("newName"))}}" else "{}"
            linkedMapOf("request" to request("PATCH", "$API/files/${pctEncode(idOf(a["ref"]))}?$params", JSON, body))
        }
        "trash" -> linkedMapOf("request" to request("PATCH", "$API/files/${pctEncode(idOf(a["ref"]))}?${fieldsQuery()}", JSON, "{\"trashed\":true}"))
        "quota" -> linkedMapOf("request" to request("GET", "$API/about?fields=" + pctEncode("storageQuota,user(displayName,emailAddress)", ",()")))
        "parseQuota" -> {
            val r = Resp.of(a["response"])
            if (!r.ok) fail("drive", r) else {
                val sq = r.json()["storageQuota"]
                val limit = sq.numberLike("limit")
                val usage = sq.numberLike("usage")
                mapOfNotNull(
                    "ok" to true,
                    "usedBytes" to usage,
                    "totalBytes" to limit,
                    "freeBytes" to (if (limit != null && usage != null) limit - usage else null),
                    "account" to (r.json()["user"].s("emailAddress") ?: r.json()["user"].s("displayName")),
                )
            }
        }
        "download" -> {
            val start = a.l("start") ?: 0L
            val headers = LinkedHashMap<String, String>()
            rangeHeader(start, a.l("end"))?.let { headers["Range"] = it }
            linkedMapOf("request" to request("GET", "$API/files/${pctEncode(idOf(a["ref"]))}?alt=media", headers))
        }
        "parseDownload" -> parseDownload("drive", a)
        "uploadStart" -> {
            if (a.s("mode") == "replace") {
                unsupported("Drive never overwrites in place")
            } else {
                val total = a.needLong("size")
                val body = "{\"name\":${jsonQuote(a.need("name"))},\"parents\":[${jsonQuote(idOf(a["parent"]))}]}"
                val headers = mapOf(
                    "Content-Type" to "application/json; charset=UTF-8",
                    "X-Upload-Content-Type" to "application/octet-stream",
                    "X-Upload-Content-Length" to total.toString(),
                )
                linkedMapOf("request" to request("POST", "$UPLOAD/files?uploadType=resumable&${fieldsQuery()}", headers, body))
            }
        }
        "parseUploadStart" -> {
            val r = Resp.of(a["response"])
            val location = r.header("location")
            if (!r.ok || location == null) fail("drive", r, "upload") else {
                val now = a.l("nowMs")
                mapOfNotNull(
                    "ok" to true,
                    "session" to linkedMapOf("kind" to "drive", "url" to location),
                    "nextOffset" to 0L,
                    "expiresAtMs" to (if (now != null) now + 7L * 24 * 3600 * 1000 else null),
                    "chunk" to linkedMapOf("multiple" to 256 * KIB, "preferred" to 8 * MIB),
                )
            }
        }
        "uploadAppend" -> {
            val offset = a.needLong("offset")
            val length = a.needLong("length")
            val total = a.needLong("total")
            require(offset + length <= total) { "the chunk runs past the end of the file" }
            // 7.1 #8 / Drive docs: every chunk but the last is a multiple of 256 KiB.
            require(offset + length == total || (length > 0 && length % (256 * KIB) == 0L)) { "Drive chunks are multiples of 256 KiB except the last" }
            val headers = mapOf("Content-Length" to length.toString(), "Content-Range" to contentRange(offset, length, total), "Content-Type" to "application/octet-stream")
            requireHost(a["session"].need("url"), "googleapis.com")
            linkedMapOf("request" to request("PUT", a["session"].need("url"), headers, bodyRef = if (length > 0) offset to length else null))
        }
        "uploadQuery" -> {
            val total = a.needLong("total")
            requireHost(a["session"].need("url"), "googleapis.com")
            linkedMapOf("request" to request("PUT", a["session"].need("url"), mapOf("Content-Length" to "0", "Content-Range" to "bytes */$total")))
        }
        "parseUploadAppend", "parseUploadQuery" -> {
            val r = Resp.of(a["response"])
            when {
                r.status == 308 -> {
                    // `Range: bytes=0-N` is what Drive holds; no header means nothing yet.
                    val range = r.header("range")
                    val next = range?.substringAfter('-', "")?.trim()?.toLongOrNull()?.plus(1) ?: 0L
                    linkedMapOf("ok" to true, "done" to false, "nextOffset" to next)
                }
                r.ok -> linkedMapOf("ok" to true, "done" to true, "entry" to entry(r.json()))
                else -> fail("drive", r, "upload", a.l("nowMs"))
            }
        }
        "parseEntry" -> {
            val r = Resp.of(a["response"])
            if (!r.ok) fail("drive", r, a.s("context") ?: "", a.l("nowMs")) else linkedMapOf("ok" to true, "entry" to entry(r.json()))
        }
        "parseDone" -> {
            val r = Resp.of(a["response"])
            if (!r.ok) fail("drive", r, a.s("context") ?: "", a.l("nowMs")) else linkedMapOf("ok" to true)
        }
        else -> throw IllegalArgumentException("unknown drive op '$op'")
    }
}

internal fun unsupported(reason: String): Map<String, Any?> = linkedMapOf("unsupported" to true, "reason" to reason)

internal fun rangeHeader(start: Long, end: Long?): String? = when {
    end != null -> "bytes=$start-$end"
    start > 0 -> "bytes=$start-"
    else -> null
}

/**
 * Reads the answer to a ranged download (7.1 #8). A 206 must start where we asked; a 200 to a request that carried a Range
 * header means the server ignored it, and that body is never appended to a partial file (`ignoredRange`).
 */
internal fun parseDownload(provider: String, a: J): Map<String, Any?> {
    val r = Resp.of(a["response"])
    val start = a.l("start") ?: 0L
    val ranged = start > 0 || a.has("end")
    if (r.status == 206) {
        val cr = r.header("content-range")?.trim()
        // bytes S-E/T
        val spec = cr?.substringAfter("bytes ", "")?.substringBefore('/')
        val s = spec?.substringBefore('-')?.trim()?.toLongOrNull()
        val e = spec?.substringAfter('-', "")?.trim()?.toLongOrNull()
        val total = cr?.substringAfter('/', "")?.trim()?.toLongOrNull()
        if (s == null || e == null || s != start) {
            return mapOfNotNull("ok" to false, "ignoredRange" to true, "error" to linkedMapOf("kind" to "Fatal", "code" to "range-mismatch", "status" to 206))
        }
        return mapOfNotNull("ok" to true, "start" to s, "end" to e, "length" to (e - s + 1), "total" to total)
    }
    if (r.status == 200) {
        if (ranged) return mapOfNotNull("ok" to false, "ignoredRange" to true, "error" to linkedMapOf("kind" to "Fatal", "code" to "range-ignored", "status" to 200))
        return mapOfNotNull("ok" to true, "start" to 0L, "length" to r.header("content-length")?.toLongOrNull(), "total" to r.header("content-length")?.toLongOrNull())
    }
    if (r.status == 416) {
        return mapOfNotNull("ok" to false, "error" to linkedMapOf("kind" to "Fatal", "code" to "range-not-satisfiable", "status" to 416), "total" to r.header("content-range")?.substringAfter("*/", "")?.trim()?.toLongOrNull())
    }
    return fail(provider, r, "download", a.l("nowMs"))
}
