package app.keepfin.core.cloud

import app.keepfin.core.json.jsonQuote

// Microsoft Graph v1.0, OneDrive app folder (/drive/special/approot, scope Files.ReadWrite.AppFolder). Objects have ids and
// eTags: replace and delete are compare-and-swap with If-Match. Create-only is `@microsoft.graph.conflictBehavior=fail` (inside
// the upload session's `item`, checked at the final commit). Upload fragments are sent in order, multiples of 320 KiB (10 MiB
// preferred, under 60 MiB), to the pre-authenticated uploadUrl, which must NOT receive the Authorization header.

internal fun requireHost(url: String, domain: String) {
    val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#').substringAfter('@').substringBefore(':').lowercase()
    require(url.startsWith("https://") && (host == domain || host.endsWith(".$domain"))) { "the authorization header only goes to $domain" }
}

internal object Graph {
    private const val BASE = "https://graph.microsoft.com/v1.0"
    private const val SELECT = "id,name,size,eTag,cTag,file,folder,fileSystemInfo,lastModifiedDateTime,createdDateTime,parentReference"
    private val JSON = mapOf("Content-Type" to "application/json")
    private const val APPROOT = "/me/drive/special/approot"

    /** The item's address without a trailing colon, and whether it is in path form (`approot:/a/b`). */
    private fun addr(ref: J): Pair<String, Boolean> {
        val id = ref.s("id")
        if (id != null) return "/me/drive/items/${pctEncode(id)}" to false
        val path = ref.s("path") ?: ""
        return if (path.isEmpty()) APPROOT to false else "$APPROOT:/${pctEncodePath(path)}" to true
    }

    private fun sub(ref: J, tail: String): String {
        val (a, pathForm) = addr(ref)
        return if (pathForm) "$a:$tail" else a + tail
    }

    /** Address of a child that may not exist yet: always path form (`.../items/{id}:/name`). */
    private fun child(parent: J, name: String): String {
        val (a, _) = addr(parent)
        val n = pctEncode(name)
        return if (a == APPROOT) "$APPROOT:/$n" else if (a.startsWith("$APPROOT:/")) "$a/$n" else "$a:/$n"
    }

    fun entry(f: J): Map<String, Any?> {
        val hashes = LinkedHashMap<String, Any?>()
        val h = f["file"]["hashes"]
        h.s("quickXorHash")?.let { hashes["quickXor"] = it }
        h.s("sha1Hash")?.let { hashes["sha1"] = it }
        h.s("sha256Hash")?.let { hashes["sha256"] = it }
        return mapOfNotNull(
            "id" to f.s("id"),
            "name" to f.s("name"),
            "folder" to f.has("folder"),
            "size" to f.l("size"),
            "mtimeMs" to parseIso(f["fileSystemInfo"].s("lastModifiedDateTime") ?: f.s("lastModifiedDateTime")),
            "createdMs" to parseIso(f["fileSystemInfo"].s("createdDateTime") ?: f.s("createdDateTime")),
            "etag" to f.s("eTag"),
            "hashes" to hashes,
            "parentId" to f["parentReference"].s("id"),
        )
    }

    private fun ifMatch(etag: String?): Map<String, String> = if (etag != null) mapOf("If-Match" to etag) else emptyMap()

    private fun firstExpected(ranges: List<J>): Long? = ranges.firstOrNull()?.str?.substringBefore('-')?.trim()?.toLongOrNull()

    fun handle(op: String, a: J): Any? = when (op) {
        "capabilities" -> capabilitiesOf("graph")
        "list" -> {
            val cursor = a.s("cursor")
            if (cursor != null) {
                requireHost(cursor, "graph.microsoft.com")
                linkedMapOf("request" to request("GET", cursor))
            } else {
                linkedMapOf("request" to request("GET", BASE + sub(a["folder"], "/children") + "?\$top=200&\$select=$SELECT"))
            }
        }
        "parseList" -> {
            val r = Resp.of(a["response"])
            if (!r.ok) fail("graph", r, if (a.has("cursor")) "list-continue" else "list", a.l("nowMs")) else {
                val j = r.json()
                linkedMapOf(
                    "ok" to true,
                    "entries" to j["value"].list.filter { !it.has("deleted") }.map { entry(it) },
                    "nextCursor" to j.s("@odata.nextLink"),
                    "incomplete" to false,
                )
            }
        }
        "stat" -> linkedMapOf("request" to request("GET", BASE + addr(a["ref"]).first + "?\$select=$SELECT"))
        "createFolder" -> {
            val body = "{\"name\":${jsonQuote(a.need("name"))},\"folder\":{},\"@microsoft.graph.conflictBehavior\":\"fail\"}"
            linkedMapOf("request" to request("POST", BASE + sub(a["parent"], "/children"), JSON, body))
        }
        "createSmall" -> {
            val content = a.s("content") ?: ""
            require(content.encodeToByteArray().size <= 4 * MIB) { "simple uploads are limited to 4 MiB: use an upload session" }
            val url = BASE + child(a["parent"], a.need("name")) + ":/content?@microsoft.graph.conflictBehavior=fail"
            linkedMapOf("request" to request("PUT", url, mapOf("Content-Type" to "application/octet-stream"), content))
        }
        "replaceSmall" -> {
            val content = a.s("content") ?: ""
            require(content.encodeToByteArray().size <= 4 * MIB) { "simple uploads are limited to 4 MiB: use an upload session" }
            val etag = a.need("etag")
            linkedMapOf("request" to request("PUT", BASE + sub(a["ref"], "/content"), mapOf("Content-Type" to "application/octet-stream") + ifMatch(etag), content))
        }
        "deleteCas" -> linkedMapOf("request" to request("DELETE", BASE + addr(a["ref"]).first, ifMatch(a.need("etag"))))
        "move" -> {
            val members = ArrayList<String>()
            a.s("newName")?.let { members.add("\"name\":${jsonQuote(it)}") }
            a.s("newParentId")?.let { members.add("\"parentReference\":{\"id\":${jsonQuote(it)}}") }
            linkedMapOf("request" to request("PATCH", BASE + addr(a["ref"]).first, JSON + ifMatch(a.s("etag")), "{" + members.joinToString(",") + "}"))
        }
        "trash" -> linkedMapOf("request" to request("DELETE", BASE + addr(a["ref"]).first, ifMatch(a.s("etag"))))
        "quota" -> linkedMapOf("request" to request("GET", "$BASE/me/drive?\$select=quota"))
        "parseQuota" -> {
            val r = Resp.of(a["response"])
            if (!r.ok) fail("graph", r) else {
                val qj = r.json()["quota"]
                mapOfNotNull("ok" to true, "usedBytes" to qj.l("used"), "totalBytes" to qj.l("total"), "freeBytes" to qj.l("remaining"), "state" to qj.s("state"))
            }
        }
        // The download URL is pre-authenticated and short-lived; Range requests go to it, not to /content.
        "downloadResolve" -> linkedMapOf("request" to request("GET", BASE + sub(a["ref"], "/content"), redirect = "manual"))
        "parseDownloadResolve" -> {
            val r = Resp.of(a["response"])
            val location = r.header("location")
            if ((r.status == 302 || r.status == 301 || r.status == 307 || r.status == 308) && location != null) {
                linkedMapOf("ok" to true, "url" to location)
            } else {
                fail("graph", r, "download", a.l("nowMs"))
            }
        }
        "download" -> {
            val start = a.l("start") ?: 0L
            val headers = LinkedHashMap<String, String>()
            rangeHeader(start, a.l("end"))?.let { headers["Range"] = it }
            val url = a.s("url")
            if (url != null) {
                linkedMapOf("request" to request("GET", url, headers, auth = false))
            } else {
                require(start == 0L && !a.has("end")) { "ranged downloads need the resolved URL (downloadResolve)" }
                linkedMapOf("request" to request("GET", BASE + sub(a["ref"], "/content")))
            }
        }
        "parseDownload" -> parseDownload("graph", a)
        "uploadStart" -> {
            val total = a.needLong("size")
            require(total > 0) { "Graph upload sessions cannot carry an empty file: use createSmall" }
            if (a.s("mode") == "replace") {
                val body = "{\"item\":{\"@microsoft.graph.conflictBehavior\":\"replace\"}}"
                linkedMapOf("request" to request("POST", BASE + sub(a["ref"], "/createUploadSession"), JSON + ifMatch(a.need("etag")), body))
            } else {
                val name = a.need("name")
                val body = "{\"item\":{\"@microsoft.graph.conflictBehavior\":\"fail\",\"name\":${jsonQuote(name)}}}"
                linkedMapOf("request" to request("POST", BASE + child(a["parent"], name) + ":/createUploadSession", JSON, body))
            }
        }
        "parseUploadStart" -> {
            val r = Resp.of(a["response"])
            val j = r.json()
            val url = j.s("uploadUrl")
            if (!r.ok || url == null) fail("graph", r, "upload", a.l("nowMs")) else {
                mapOfNotNull(
                    "ok" to true,
                    "session" to linkedMapOf("kind" to "graph", "url" to url),
                    "nextOffset" to (firstExpected(j["nextExpectedRanges"].list) ?: 0L),
                    "expiresAtMs" to parseIso(j.s("expirationDateTime")),
                    "chunk" to linkedMapOf("multiple" to 320 * KIB, "preferred" to 10 * MIB, "max" to 60 * MIB),
                )
            }
        }
        "uploadAppend" -> {
            val offset = a.needLong("offset")
            val length = a.needLong("length")
            val total = a.needLong("total")
            require(length > 0 && offset + length <= total) { "the fragment runs past the end of the file" }
            require(length <= 60 * MIB) { "a Graph fragment is under 60 MiB" }
            require(offset + length == total || length % (320 * KIB) == 0L) { "Graph fragments are multiples of 320 KiB except the last" }
            val headers = mapOf("Content-Length" to length.toString(), "Content-Range" to "bytes $offset-${offset + length - 1}/$total", "Content-Type" to "application/octet-stream")
            linkedMapOf("request" to request("PUT", a["session"].need("url"), headers, bodyRef = offset to length, auth = false))
        }
        "uploadQuery" -> linkedMapOf("request" to request("GET", a["session"].need("url"), auth = false))
        "parseUploadAppend", "parseUploadQuery" -> {
            val r = Resp.of(a["response"])
            val j = r.json()
            when {
                r.status == 202 || (r.status == 200 && j.has("nextExpectedRanges")) -> mapOfNotNull(
                    "ok" to true, "done" to false,
                    "nextOffset" to (firstExpected(j["nextExpectedRanges"].list) ?: 0L),
                    "expiresAtMs" to parseIso(j.s("expirationDateTime")),
                )
                r.ok -> linkedMapOf("ok" to true, "done" to true, "entry" to entry(j))
                else -> fail("graph", r, "upload", a.l("nowMs"))
            }
        }
        "parseEntry" -> {
            val r = Resp.of(a["response"])
            if (!r.ok) fail("graph", r, a.s("context") ?: "", a.l("nowMs")) else linkedMapOf("ok" to true, "entry" to entry(r.json()))
        }
        "parseDone" -> {
            val r = Resp.of(a["response"])
            if (!r.ok) fail("graph", r, a.s("context") ?: "", a.l("nowMs")) else linkedMapOf("ok" to true)
        }
        else -> throw IllegalArgumentException("unknown graph op '$op'")
    }
}
