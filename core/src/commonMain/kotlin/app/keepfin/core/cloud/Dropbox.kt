package app.keepfin.core.cloud

import app.keepfin.core.json.jsonQuote

// Dropbox API v2, an app-folder app. Paths in requests are relative to the app folder; an `id:...` string works wherever a
// path does, so Keepfin addresses objects by id once it has one. Create-only is mode=add with autorename=false and
// strict_conflict=true; replace is mode=update with the rev (strict_conflict) and delete is delete_v2 with parent_rev, which
// is the compare-and-swap. Upload sessions take 8 MiB appends and finish with the commit info (the condition lives there).
// Changes are serial: Dropbox locks the whole account per write, so parallel writes fail with too_many_write_operations.

internal object Dropbox {
    private const val API = "https://api.dropboxapi.com/2"
    private const val CONTENT = "https://content.dropboxapi.com/2"
    private val JSON = mapOf("Content-Type" to "application/json")
    private val OCTET = "application/octet-stream"

    private fun pathOf(ref: J): String {
        val id = ref.s("id")
        if (id != null) return id
        val p = ref.s("path") ?: ""
        return if (p.isEmpty()) "" else "/" + p
    }

    private fun childPath(parent: J, name: String): String = pathOf(parent) + "/" + name

    private fun q(s: String) = jsonQuote(s)

    fun entry(f: J): Map<String, Any?> {
        val folder = f[".tag"].str == "folder"
        val hashes = LinkedHashMap<String, Any?>()
        f.s("content_hash")?.let { hashes["dropbox"] = it }
        return mapOfNotNull(
            "id" to f.s("id"),
            "name" to f.s("name"),
            "folder" to folder,
            "size" to f.l("size"),
            "mtimeMs" to parseIso(f.s("server_modified")),
            "etag" to f.s("rev"),
            "hashes" to hashes,
            "path" to f.s("path_display"),
        )
    }

    private fun unwrap(j: J): J = if (j.has("metadata")) j["metadata"] else j

    private fun apiArg(json: String) = asciiJson(json)

    private fun mode(a: J): String {
        val rev = a.s("etag")
        return if (a.s("mode") == "replace") "{\".tag\":\"update\",\"update\":${q(rev ?: throw IllegalArgumentException("'etag' is missing"))}}" else "\"add\""
    }

    private fun commit(path: String, a: J): String =
        "{\"path\":${q(path)},\"mode\":${mode(a)},\"autorename\":false,\"mute\":true,\"strict_conflict\":true}"

    fun handle(op: String, a: J): Any? = when (op) {
        "capabilities" -> capabilitiesOf("dropbox")
        "list" -> {
            val cursor = a.s("cursor")
            if (cursor != null) {
                linkedMapOf("request" to request("POST", "$API/files/list_folder/continue", JSON, "{\"cursor\":${q(cursor)}}"))
            } else {
                val body = "{\"path\":${q(pathOf(a["folder"]))},\"recursive\":false,\"include_deleted\":false,\"include_non_downloadable_files\":true,\"limit\":2000}"
                linkedMapOf("request" to request("POST", "$API/files/list_folder", JSON, body))
            }
        }
        "parseList" -> {
            val r = Resp.of(a["response"])
            if (!r.ok) fail("dropbox", r, if (a.has("cursor")) "list-continue" else "list", a.l("nowMs")) else {
                val j = r.json()
                linkedMapOf(
                    "ok" to true,
                    "entries" to j["entries"].list.filter { it[".tag"].str != "deleted" }.map { entry(it) },
                    "nextCursor" to (if (j["has_more"].bool == true) j.s("cursor") else null),
                    // The cursor of a finished listing is a hint for list_folder/continue; it is never a deletion record.
                    "feedCursor" to (if (j["has_more"].bool == true) null else j.s("cursor")),
                    "incomplete" to false,
                )
            }
        }
        "stat" -> linkedMapOf("request" to request("POST", "$API/files/get_metadata", JSON, "{\"path\":${q(pathOf(a["ref"]))}}"))
        "createFolder" -> linkedMapOf("request" to request("POST", "$API/files/create_folder_v2", JSON, "{\"path\":${q(childPath(a["parent"], a.need("name")))},\"autorename\":false}", serial = true))
        "createSmall" -> {
            val headers = mapOf("Dropbox-API-Arg" to apiArg(commit(childPath(a["parent"], a.need("name")), a)), "Content-Type" to OCTET)
            linkedMapOf("request" to request("POST", "$CONTENT/files/upload", headers, a.s("content") ?: "", serial = true))
        }
        "replaceSmall" -> {
            val headers = mapOf("Dropbox-API-Arg" to apiArg(commit(pathOf(a["ref"]), JsonMode.replace(a))), "Content-Type" to OCTET)
            linkedMapOf("request" to request("POST", "$CONTENT/files/upload", headers, a.s("content") ?: "", serial = true))
        }
        "deleteCas" -> linkedMapOf("request" to request("POST", "$API/files/delete_v2", JSON, "{\"path\":${q(pathOf(a["ref"]))},\"parent_rev\":${q(a.need("etag"))}}", serial = true))
        "move" -> {
            val from = pathOf(a["ref"])
            val to = a.s("toPath") ?: childPath(a["newParent"], a.s("newName") ?: a.need("name"))
            linkedMapOf("request" to request("POST", "$API/files/move_v2", JSON, "{\"from_path\":${q(from)},\"to_path\":${q(to)},\"allow_shared_folder\":false,\"autorename\":false,\"allow_ownership_transfer\":false}", serial = true))
        }
        "trash" -> linkedMapOf("request" to request("POST", "$API/files/delete_v2", JSON, "{\"path\":${q(pathOf(a["ref"]))}}", serial = true))
        "quota" -> linkedMapOf("request" to request("POST", "$API/users/get_space_usage"))
        "parseQuota" -> {
            val r = Resp.of(a["response"])
            if (!r.ok) fail("dropbox", r) else {
                val j = r.json()
                val used = j.l("used")
                val total = j["allocation"].l("allocated")
                mapOfNotNull("ok" to true, "usedBytes" to used, "totalBytes" to total, "freeBytes" to (if (used != null && total != null) total - used else null))
            }
        }
        "download" -> {
            val headers = LinkedHashMap<String, String>()
            headers["Dropbox-API-Arg"] = apiArg("{\"path\":${q(pathOf(a["ref"]))}}")
            rangeHeader(a.l("start") ?: 0L, a.l("end"))?.let { headers["Range"] = it }
            linkedMapOf("request" to request("POST", "$CONTENT/files/download", headers))
        }
        "parseDownload" -> parseDownload("dropbox", a)
        "uploadStart" -> {
            val path = if (a.s("mode") == "replace") pathOf(a["ref"]) else childPath(a["parent"], a.need("name"))
            val headers = mapOf("Dropbox-API-Arg" to "{\"close\":false}", "Content-Type" to OCTET)
            linkedMapOf(
                "request" to request("POST", "$CONTENT/files/upload_session/start", headers),
                // The commit condition travels with the session (it is needed at finish, possibly after a restart).
                "commit" to commitMap(path, a),
            )
        }
        "parseUploadStart" -> {
            val r = Resp.of(a["response"])
            val id = r.json().s("session_id")
            if (!r.ok || id == null) fail("dropbox", r, "upload", a.l("nowMs")) else {
                val now = a.l("nowMs")
                mapOfNotNull(
                    "ok" to true,
                    "session" to linkedMapOf("kind" to "dropbox", "id" to id, "commit" to a["commit"].v?.let { jsonValueToPlain(a["commit"]) }),
                    "nextOffset" to 0L,
                    "expiresAtMs" to (if (now != null) now + 7L * 24 * 3600 * 1000 else null),
                    "chunk" to linkedMapOf("multiple" to 4 * MIB, "preferred" to 8 * MIB, "max" to 150 * MIB),
                )
            }
        }
        "uploadAppend" -> {
            val offset = a.needLong("offset")
            val length = a.needLong("length")
            val total = a.needLong("total")
            require(offset + length <= total) { "the chunk runs past the end of the file" }
            require(length <= 150 * MIB) { "an append is at most 150 MiB" }
            val cursor = "{\"session_id\":${q(a["session"].need("id"))},\"offset\":$offset}"
            val final = offset + length == total
            val url: String
            val arg: String
            if (final) {
                url = "$CONTENT/files/upload_session/finish"
                val c = a["session"]["commit"]
                arg = "{\"cursor\":$cursor,\"commit\":{\"path\":${q(c.need("path"))},\"mode\":${commitModeJson(c)},\"autorename\":false,\"mute\":true,\"strict_conflict\":true}}"
            } else {
                url = "$CONTENT/files/upload_session/append_v2"
                arg = "{\"cursor\":$cursor,\"close\":false}"
            }
            val headers = mapOf("Dropbox-API-Arg" to apiArg(arg), "Content-Type" to OCTET)
            linkedMapOf("request" to request("POST", url, headers, bodyRef = if (length > 0) offset to length else null, serial = final))
        }
        // No query endpoint exists: an append of nothing at the known offset answers with the right offset when it is wrong.
        "uploadQuery" -> {
            val offset = a.needLong("offset")
            val arg = "{\"cursor\":{\"session_id\":${q(a["session"].need("id"))},\"offset\":$offset},\"close\":false}"
            linkedMapOf("request" to request("POST", "$CONTENT/files/upload_session/append_v2", mapOf("Dropbox-API-Arg" to apiArg(arg), "Content-Type" to OCTET)))
        }
        "parseUploadAppend" -> {
            val r = Resp.of(a["response"])
            val offset = a.needLong("offset")
            val length = a.needLong("length")
            val total = a.l("total")
            if (r.ok && total != null && offset + length == total) {
                linkedMapOf("ok" to true, "done" to true, "entry" to entry(unwrap(r.json())))
            } else if (r.ok) {
                linkedMapOf("ok" to true, "done" to false, "nextOffset" to offset + length)
            } else {
                fail("dropbox", r, "upload", a.l("nowMs"))
            }
        }
        "parseUploadQuery" -> {
            val r = Resp.of(a["response"])
            if (r.ok) {
                linkedMapOf("ok" to true, "done" to false, "nextOffset" to a.needLong("offset"))
            } else {
                val e = mapError("dropbox", r, "upload", a.l("nowMs"))
                val correct = e["correctOffset"]
                if (correct is Long) linkedMapOf("ok" to true, "done" to false, "nextOffset" to correct) else linkedMapOf("ok" to false, "error" to e)
            }
        }
        "parseEntry" -> {
            val r = Resp.of(a["response"])
            if (!r.ok) fail("dropbox", r, a.s("context") ?: "", a.l("nowMs")) else linkedMapOf("ok" to true, "entry" to entry(unwrap(r.json())))
        }
        "parseDone" -> {
            val r = Resp.of(a["response"])
            if (!r.ok) fail("dropbox", r, a.s("context") ?: "", a.l("nowMs")) else linkedMapOf("ok" to true)
        }
        else -> throw IllegalArgumentException("unknown dropbox op '$op'")
    }

    private fun commitMap(path: String, a: J): Map<String, Any?> {
        val replace = a.s("mode") == "replace"
        return mapOfNotNull("path" to path, "mode" to (if (replace) "update" else "add"), "rev" to (if (replace) a.need("etag") else null))
    }

    private fun commitModeJson(c: J): String =
        if (c.s("mode") == "update") "{\".tag\":\"update\",\"update\":${q(c.need("rev"))}}" else "\"add\""

    private fun jsonValueToPlain(j: J): Any? = when {
        j.str != null -> j.str
        j.bool != null -> j.bool
        j.long != null -> j.long
        j.isObject -> LinkedHashMap<String, Any?>().also { m -> for (k in j.keys) m[k] = jsonValueToPlain(j[k]) }
        else -> null
    }

    /** A view of [a] with mode forced to replace (replaceSmall always conditions on the rev). */
    private object JsonMode {
        fun replace(a: J): J = J(
            app.keepfin.core.json.JsonObject(
                LinkedHashMap<String, app.keepfin.core.json.JsonValue>().also { m ->
                    (a.v as? app.keepfin.core.json.JsonObject)?.members?.let { m.putAll(it) }
                    m["mode"] = app.keepfin.core.json.JsonString("replace")
                },
            ),
        )
    }
}
