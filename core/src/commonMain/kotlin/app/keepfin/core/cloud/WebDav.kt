package app.keepfin.core.cloud

// WebDAV (RFC 4918) with a URL and an app password. Objects are identified by path only. Create-only is `If-None-Match: *`.
// Compare-and-swap (`If-Match`) is offered only after the probe proved the server refuses a deliberately wrong tag (7.1 #1).
// PROPFIND is not paginated: callers list folder by folder and shard big media folders; per-resource failures in the 207
// body make the listing incomplete. Uploads are whole-file PUTs (Nextcloud v2 chunking has its own ops).

internal object WebDav {
    private val XML = mapOf("Content-Type" to "application/xml; charset=utf-8")
    private const val PROPS = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/><d:getetag/><d:getlastmodified/></d:prop></d:propfind>"
    private const val QUOTA = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:quota-available-bytes/><d:quota-used-bytes/></d:prop></d:propfind>"

    private fun url(a: J, path: String?, folder: Boolean = false): String {
        val base = a.need("base").trimEnd('/')
        val p = (path ?: "").trim('/')
        return if (p.isEmpty()) "$base/" else base + "/" + pctEncodePath(p) + (if (folder) "/" else "")
    }

    private fun refPath(ref: J): String = ref.s("path") ?: ""

    private fun basePath(base: String): String {
        val afterScheme = base.substringAfter("://", base)
        val slash = afterScheme.indexOf('/')
        val p = if (slash < 0) "" else afterScheme.substring(slash)
        return pctDecode(p).trimEnd('/')
    }

    private fun relPath(href: String, base: String): String {
        var h = href.trim()
        if (h.contains("://")) h = h.substringAfter("://").let { s -> val i = s.indexOf('/'); if (i < 0) "/" else s.substring(i) }
        h = pctDecode(h.substringBefore('?'))
        val bp = basePath(base)
        if (bp.isNotEmpty() && h.startsWith(bp)) h = h.substring(bp.length)
        return h.trim('/')
    }

    private fun statusOk(text: String?): Boolean {
        val code = text?.trim()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: return true
        return code in 200..299
    }

    /** One <response> as an entry, or null with `failed` set when its status says it could not be read. */
    private fun entryOf(resp: XNode, base: String): Pair<Map<String, Any?>?, String?> {
        val href = resp.child("href")?.content() ?: return null to null
        val path = relPath(href, base)
        val own = resp.child("status")?.content()
        if (!statusOk(own)) return null to path
        var prop: XNode? = null
        for (ps in resp.all("propstat")) {
            if (statusOk(ps.child("status")?.content())) {
                val p = ps.child("prop")
                if (p != null && prop == null) prop = p
            }
        }
        val isCollection = prop?.child("resourcetype")?.child("collection") != null
        val size = prop?.child("getcontentlength")?.content()?.toLongOrNull()
        val etag = prop?.child("getetag")?.content()?.ifEmpty { null }
        val m = parseHttpDate(prop?.child("getlastmodified")?.content()) ?: parseIso(prop?.child("getlastmodified")?.content())
        val name = path.substringAfterLast('/')
        return mapOfNotNull(
            "id" to null,
            "name" to name,
            "folder" to isCollection,
            "size" to (if (isCollection) null else size),
            "mtimeMs" to m,
            "etag" to etag,
            "hashes" to LinkedHashMap<String, Any?>(),
            "path" to path,
        ) to null
    }

    private fun parseMulti(a: J, includeSelf: Boolean): Map<String, Any?> {
        val r = Resp.of(a["response"])
        if (r.status != 207) return fail("webdav", r, "list", a.l("nowMs"))
        val root = try {
            parseXml(r.body)
        } catch (e: XmlException) {
            return linkedMapOf("ok" to false, "error" to linkedMapOf("kind" to "Fatal", "code" to "bad-multistatus", "message" to (e.message ?: "")))
        }
        val self = (a.s("folder") ?: a.s("path") ?: "").trim('/')
        val entries = ArrayList<Map<String, Any?>>()
        val failed = ArrayList<String>()
        for (resp in root.all("response")) {
            val (entry, bad) = entryOf(resp, a.need("base"))
            if (bad != null) failed.add(bad)
            if (entry == null) continue
            val path = entry["path"] as String
            if (!includeSelf && path == self) continue
            entries.add(entry)
        }
        return linkedMapOf("ok" to true, "entries" to entries, "nextCursor" to null, "incomplete" to failed.isNotEmpty(), "failed" to failed)
    }

    fun handle(op: String, a: J): Any? = when (op) {
        "capabilities" -> capabilitiesOf("webdav", if (a.has("probe")) a["probe"] else null)
        "list" -> linkedMapOf("request" to request("PROPFIND", url(a, refPath(a["folder"]), true), XML + mapOf("Depth" to "1"), PROPS))
        "parseList" -> parseMulti(a, false)
        "stat" -> linkedMapOf("request" to request("PROPFIND", url(a, refPath(a["ref"])), XML + mapOf("Depth" to "0"), PROPS))
        "parseStat" -> {
            val m = parseMulti(a, true)
            if (m["ok"] == true) {
                @Suppress("UNCHECKED_CAST")
                val list = m["entries"] as List<Map<String, Any?>>
                if (list.isEmpty()) linkedMapOf("ok" to false, "error" to linkedMapOf("kind" to "NotFound", "status" to 207)) else linkedMapOf("ok" to true, "entry" to list[0])
            } else {
                m
            }
        }
        "createFolder" -> linkedMapOf("request" to request("MKCOL", url(a, refPath(a["parent"]).trim('/') + "/" + a.need("name"), true)))
        "createSmall" -> linkedMapOf("request" to request("PUT", url(a, refPath(a["parent"]).trim('/') + "/" + a.need("name")), mapOf("If-None-Match" to "*", "Content-Type" to "application/octet-stream"), a.s("content") ?: ""))
        "replaceSmall" -> if (a["cas"].bool != true) {
            unsupported("The probe did not prove that this server honours If-Match")
        } else {
            linkedMapOf("request" to request("PUT", url(a, refPath(a["ref"])), mapOf("If-Match" to a.need("etag"), "Content-Type" to "application/octet-stream"), a.s("content") ?: ""))
        }
        "deleteCas" -> if (a["cas"].bool != true) {
            unsupported("The probe did not prove that this server honours If-Match")
        } else {
            linkedMapOf("request" to request("DELETE", url(a, refPath(a["ref"])), mapOf("If-Match" to a.need("etag"))))
        }
        // MOVE never replaces: Overwrite: F.
        "move" -> {
            val headers = LinkedHashMap<String, String>()
            headers["Destination"] = url(a, a.need("toPath"))
            headers["Overwrite"] = "F"
            if (a["cas"].bool == true) a.s("etag")?.let { headers["If-Match"] = it }
            linkedMapOf("request" to request("MOVE", url(a, refPath(a["ref"])), headers))
        }
        "trash" -> linkedMapOf("request" to request("DELETE", url(a, refPath(a["ref"]))))
        "quota" -> linkedMapOf("request" to request("PROPFIND", url(a, "", true), XML + mapOf("Depth" to "0"), QUOTA))
        "parseQuota" -> {
            val r = Resp.of(a["response"])
            if (r.status != 207) fail("webdav", r) else {
                val root = try { parseXml(r.body) } catch (e: XmlException) { null }
                val avail = root?.find("quota-available-bytes")?.content()?.toLongOrNull()
                val used = root?.find("quota-used-bytes")?.content()?.toLongOrNull()
                // A negative value means "unknown" or "unlimited" (RFC 4331).
                val free = if (avail != null && avail >= 0) avail else null
                mapOfNotNull("ok" to true, "usedBytes" to (if (used != null && used >= 0) used else null), "freeBytes" to free, "totalBytes" to (if (free != null && used != null && used >= 0) free + used else null))
            }
        }
        "download" -> {
            val headers = LinkedHashMap<String, String>()
            rangeHeader(a.l("start") ?: 0L, a.l("end"))?.let { headers["Range"] = it }
            linkedMapOf("request" to request("GET", url(a, refPath(a["ref"])), headers))
        }
        "parseDownload" -> parseDownload("webdav", a)
        // Whole-file PUT: no session, no resume; a restart sends the file again.
        "uploadStart" -> linkedMapOf("request" to null, "session" to linkedMapOf("kind" to "webdav", "single" to true), "nextOffset" to 0L)
        "uploadAppend" -> {
            val offset = a.needLong("offset")
            val length = a.needLong("length")
            require(offset == 0L && length == a.needLong("total")) { "WebDAV uploads send the whole file in one PUT" }
            val replace = a.s("mode") == "replace"
            val headers = LinkedHashMap<String, String>()
            headers["Content-Type"] = "application/octet-stream"
            headers["Content-Length"] = length.toString()
            if (replace) {
                require(a["cas"].bool == true) { "replacing needs a passed If-Match probe" }
                headers["If-Match"] = a.need("etag")
            } else {
                headers["If-None-Match"] = "*"
            }
            val path = if (replace) refPath(a["ref"]) else refPath(a["parent"]).trim('/') + "/" + a.need("name")
            linkedMapOf("request" to request("PUT", url(a, path), headers, bodyRef = if (length > 0) 0L to length else null))
        }
        "parseUploadAppend" -> {
            val r = Resp.of(a["response"])
            if (r.ok) linkedMapOf("ok" to true, "done" to true, "entry" to mapOfNotNull("name" to a.s("name"), "etag" to r.header("etag"), "folder" to false)) else fail("webdav", r, "upload", a.l("nowMs"))
        }
        "parseEntry" -> {
            val r = Resp.of(a["response"])
            if (!r.ok) fail("webdav", r, a.s("context") ?: "", a.l("nowMs")) else linkedMapOf("ok" to true, "entry" to mapOfNotNull("name" to a.s("name"), "etag" to r.header("etag"), "folder" to (a.s("kind") == "folder")))
        }
        "parseDone" -> {
            val r = Resp.of(a["response"])
            if (!r.ok) fail("webdav", r, a.s("context") ?: "", a.l("nowMs")) else linkedMapOf("ok" to true)
        }
        // ---- the probe (scratch object at `path`): put, wrong If-Match, right If-Match, ranged GET
        "probePut" -> linkedMapOf("request" to request("PUT", url(a, a.need("path")), mapOf("Content-Type" to "application/octet-stream"), "keepfin-probe-0123456789"))
        "probeWrong" -> linkedMapOf("request" to request("PUT", url(a, a.need("path")), mapOf("If-Match" to "\"keepfin-probe-nomatch\"", "Content-Type" to "application/octet-stream"), "keepfin-probe-wrong"))
        "probeRight" -> linkedMapOf("request" to request("PUT", url(a, a.need("path")), mapOf("If-Match" to a.need("etag"), "Content-Type" to "application/octet-stream"), "keepfin-probe-right"))
        "probeNoneMatch" -> linkedMapOf("request" to request("PUT", url(a, a.need("path")), mapOf("If-None-Match" to "*", "Content-Type" to "application/octet-stream"), "keepfin-probe-new"))
        "probeMoveOver" -> linkedMapOf("request" to request("MOVE", url(a, a.need("path")), mapOf("Destination" to url(a, a.need("path2")), "Overwrite" to "F")))
        "probeRange" -> linkedMapOf("request" to request("GET", url(a, a.need("path")), mapOf("Range" to "bytes=1-2")))
        "probeEvaluate" -> webdavProbeEvaluate(a)
        "basicAuth" -> linkedMapOf("authorization" to "Basic " + base64Of((a.need("user") + ":" + a.need("password")).encodeToByteArray()))
        // ---- Nextcloud v2 chunked upload: MKCOL uploads/<user>/<id>, numbered PUTs, final MOVE to .file with Destination
        "ncChunkStart" -> linkedMapOf("request" to request("MKCOL", a.need("uploadsBase").trimEnd('/') + "/" + pctEncode(a.need("uploadId")), mapOf("Destination" to a.need("destination"))))
        "ncChunkPut" -> {
            val number = a.needLong("number")
            require(number in 1..10000) { "chunk numbers run 1..10000" }
            val length = a.needLong("length")
            val last = a.bool("last")
            require(last || length >= 5 * MIB) { "a Nextcloud chunk is at least 5 MiB except the last" }
            require(length <= 5 * 1024 * MIB) { "a Nextcloud chunk is at most 5 GiB" }
            val name = number.toString().padStart(5, '0')
            linkedMapOf("request" to request("PUT", a.need("uploadsBase").trimEnd('/') + "/" + pctEncode(a.need("uploadId")) + "/" + name, mapOf("Destination" to a.need("destination"), "Content-Length" to length.toString()), bodyRef = a.needLong("offset") to length))
        }
        "ncChunkFinish" -> linkedMapOf(
            "request" to request("MOVE", a.need("uploadsBase").trimEnd('/') + "/" + pctEncode(a.need("uploadId")) + "/.file", mapOf("Destination" to a.need("destination"), "OC-Total-Length" to a.needLong("total").toString(), "Overwrite" to "F")),
            "note" to "upload folders expire after 24 hours",
        )
        else -> throw IllegalArgumentException("unknown webdav op '$op'")
    }

    private fun J.bool(key: String): Boolean = this[key].bool == true
}
