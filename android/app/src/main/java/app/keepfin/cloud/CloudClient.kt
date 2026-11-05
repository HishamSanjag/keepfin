package app.keepfin.cloud

import app.keepfin.core.cloud.DropboxContentHash
import app.keepfin.core.cloud.QuickXorHash
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

internal class ListResult(val entries: List<CloudEntry>, val complete: Boolean, val failed: List<String>)

/** What the account says about itself and its room. */
internal class Quota(val usedBytes: Long?, val totalBytes: Long?, val freeBytes: Long?, val account: String?)

internal class UploadResult(val entry: CloudEntry, val contentId: String)

/** Hashes a stream the way a provider reports it (md5 / quickXor / Dropbox blocks), plus Keepfin's SHA-256. */
internal class ProviderHasher(private val want: String?) {
    val sha256: MessageDigest = MessageDigest.getInstance("SHA-256")
    private val md5: MessageDigest? = if (want == "md5") MessageDigest.getInstance("MD5") else null
    private val quick: QuickXorHash? = if (want == "quickXor") QuickXorHash() else null
    private val dropbox: DropboxContentHash? = if (want == "dropbox") DropboxContentHash() else null

    fun update(buf: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        sha256.update(buf, off, len)
        md5?.update(buf, off, len)
        quick?.update(buf, off, off + len)
        dropbox?.update(buf, off, off + len)
    }

    /** The SHA-256 of what has been read so far, without ending the hash. */
    fun shaNow(): String = hex((sha256.clone() as MessageDigest).digest())

    /** The provider's own hash of everything, or null when none is wanted. Call once, after the last update. */
    fun providerHash(): String? = when (want) {
        "md5" -> hex(md5!!.digest())
        "quickXor" -> quick!!.digest()
        "dropbox" -> dropbox!!.digest()
        else -> null
    }
}

internal class ContentMismatch(val expected: String, val got: String) : IOException("The bytes do not match the pinned content (${got.take(15)}).")

/**
 * Typed operations over one cloud account: the core describes each request and reads each answer, [CloudHttp] sends it. Failures
 * throw [CloudError]. Nothing here decides what a copy does with the answer: that is [CloudTransport] and the copies runner.
 * A port of gui/desktop/cloud/client.ts.
 */
internal class CloudClient(
    val http: CloudHttp,
    /** WebDAV: the account's root collection URL. */
    private val base: String? = null,
    private val journal: UploadJournal = MemoryJournal(),
    private val now: () -> Long = System::currentTimeMillis,
    /** Where a WebDAV upload is staged (one file in flight; removed afterwards). */
    private val stagingDir: File = File(System.getProperty("java.io.tmpdir"), "keepfin-cloud-staging"),
) {
    val provider: String = http.account.provider
    private var caps: Caps = Caps(coreCall(provider, "capabilities", JSONObject()))
    @Volatile private var casVerified = false
    @Volatile private var rangeVerified = false
    @Volatile private var createOnlyVerified = false
    @Volatile private var noReplaceMoveVerified = false

    fun capabilities(): Caps = caps

    private fun refreshCaps() {
        caps = Caps(coreCall(provider, "capabilities", if (provider == "webdav") JSONObject().put("probe", JSONObject().put("cas", casVerified).put("range", rangeVerified).put("createOnly", createOnlyVerified).put("noReplaceMove", noReplaceMoveVerified)) else JSONObject()))
    }

    private fun input(o: JSONObject = JSONObject()): JSONObject {
        if (provider != "webdav") return o
        base?.let { o.put("base", it) }
        o.put("cas", casVerified)
        return o
    }

    /** Builds, sends and parses one operation; throws [CloudError] for a failed answer. */
    private fun exec(build: String, parse: String, inp: JSONObject, parseExtra: JSONObject = JSONObject(), io: SendIo = SendIo(), accept: List<Int> = emptyList()): JSONObject {
        val built = coreCall(provider, build, input(inp))
        if (built.optBoolean("unsupported")) throw CloudError(Kind.FATAL, code = "unsupported", info = built.optString("reason"), op = build)
        val response = http.send(CloudRequest.of(built.getJSONObject("request")), io, accept)
        parseExtra.put("response", response.json())
        val parsed = coreCall(provider, parse, input(parseExtra))
        if (!parsed.optBoolean("ok")) throw CloudError.of(parsed.getJSONObject("error"), build)
        return parsed
    }

    // ----------------------------------------------------------------------------------------------------------- listing

    /** Lists one folder to the end, page by page. A failed page makes the result incomplete (and stops it); entries read so far stay. */
    fun list(folder: Ref, name: String? = null): ListResult {
        val entries = ArrayList<CloudEntry>()
        val failed = ArrayList<String>()
        var complete = true
        var cursor: String? = null
        for (pages in 0 until 100_000) {
            val page: JSONObject = try {
                val inp = JSONObject().put("folder", folder.json())
                cursor?.let { inp.put("cursor", it) }
                name?.let { inp.put("name", it) }
                val extra = JSONObject()
                cursor?.let { extra.put("cursor", it) }
                if (provider == "webdav") extra.put("folder", folder.path ?: "")
                exec("list", "parseList", inp, extra)
            } catch (e: CloudError) {
                // A torn listing: keep what was read, say it is not complete. A missing folder is not a listing either.
                if (pages == 0 || e.kind == Kind.NEEDS_SIGN_IN) throw e
                complete = false
                failed.add(folder.path ?: folder.id ?: "")
                break
            }
            val arr = page.optJSONArray("entries") ?: JSONArray()
            for (i in 0 until arr.length()) entries.add(CloudEntry.of(arr.getJSONObject(i)))
            if (page.optBoolean("incomplete")) complete = false
            page.optJSONArray("failed")?.let { failed.addAll(it.strings()) }
            cursor = if (page.has("nextCursor") && !page.isNull("nextCursor")) page.getString("nextCursor") else null
            if (cursor == null) break
        }
        return ListResult(entries, complete, failed)
    }

    fun stat(ref: Ref): CloudEntry? = try {
        CloudEntry.of(exec("stat", if (provider == "webdav") "parseStat" else "parseEntry", JSONObject().put("ref", ref.json())).getJSONObject("entry"))
    } catch (e: CloudError) {
        if (e.kind == Kind.NOT_FOUND) null else throw e
    }

    fun createFolder(parent: Ref, name: String): CloudEntry {
        val extra = if (provider == "webdav") JSONObject().put("name", name).put("kind", "folder") else JSONObject()
        return CloudEntry.of(exec("createFolder", "parseEntry", JSONObject().put("parent", parent.json()).put("name", name), extra).getJSONObject("entry"))
    }

    /** Create-only small object (locks, generation records). Conflict means the name is taken (not for Drive: see the transport). */
    fun createSmall(parent: Ref, name: String, content: String): CloudEntry {
        if (provider == "webdav" && !createOnlyVerified) return createViaTemp(parent, name) { temp -> rawCreateSmall(parent, temp, content) }
        return rawCreateSmall(parent, name, content)
    }

    private fun rawCreateSmall(parent: Ref, name: String, content: String): CloudEntry =
        CloudEntry.of(exec("createSmall", "parseEntry", JSONObject().put("parent", parent.json()).put("name", name).put("content", content), JSONObject().put("name", name)).getJSONObject("entry"))

    /** Compare-and-swap replace of a small object; refused (Fatal 'unsupported') where the provider has none. */
    fun replaceSmall(ref: Ref, etag: String, content: String, advisory: Boolean = false): CloudEntry =
        CloudEntry.of(
            exec(
                "replaceSmall", "parseEntry", JSONObject().put("ref", ref.json()).put("etag", etag).put("content", content).put("advisory", advisory),
                JSONObject().also { o -> ref.path?.substringAfterLast('/')?.let { o.put("name", it) } },
            ).getJSONObject("entry"),
        )

    /** Moves an object. Providers address it by id (Drive, Graph: [newParentId], [fromParentId]) or by path (Dropbox, WebDAV: [toPath]). */
    fun move(ref: Ref, newParentId: String? = null, fromParentId: String? = null, newName: String? = null, toPath: String? = null, etag: String? = null): CloudEntry {
        val extra = if (provider == "webdav") JSONObject().put("name", toPath?.substringAfterLast('/') ?: "").put("kind", "file") else JSONObject()
        val inp = JSONObject().put("ref", ref.json())
        newParentId?.let { inp.put("newParentId", it) }
        fromParentId?.let { inp.put("fromParentId", it) }
        newName?.let { inp.put("newName", it) }
        toPath?.let { inp.put("toPath", it) }
        etag?.let { inp.put("etag", it) }
        return CloudEntry.of(exec("move", "parseEntry", inp, extra).getJSONObject("entry"))
    }

    /** Moves an object to the provider's own trash (Drive) or deletes it (the others: only used for scratch objects such as the WebDAV probe). */
    fun trash(ref: Ref, etag: String? = null) {
        val inp = JSONObject().put("ref", ref.json())
        etag?.let { inp.put("etag", it) }
        exec("trash", "parseDone", inp)
    }

    fun quota(): Quota {
        val q = exec("quota", "parseQuota", JSONObject())
        fun lng(k: String) = if (q.has(k) && !q.isNull(k)) q.optLong(k) else null
        return Quota(lng("usedBytes"), lng("totalBytes"), lng("freeBytes"), q.optString("account").ifEmpty { null })
    }

    // ------------------------------------------------------------------------------------------------------------ WebDAV

    /**
     * The If-Match probe (7.1 #1, #9): on a scratch object, a wrong If-Match must be refused and the right one accepted, the tag
     * must be strong, and a ranged GET must answer 206. A server that ignores If-Match never gets compare-and-swap rights.
     */
    fun probeWebdav(scratchPath: String): JSONObject {
        require(provider == "webdav") { "only WebDAV is probed" }
        val second = "$scratchPath-2"
        val any = listOf(200, 201, 204, 206, 304, 400, 403, 405, 409, 412, 415, 501)
        fun send(op: String, extra: JSONObject = JSONObject()): CloudResponse {
            val built = coreCall("webdav", op, input(JSONObject().put("path", scratchPath).put("path2", second)).also { o -> for (k in extra.keys()) o.put(k, extra.get(k)) })
            return http.send(CloudRequest.of(built.getJSONObject("request")), SendIo(), any)
        }
        val put = send("probePut")
        val etag = put.headers["etag"] ?: stat(Ref(path = scratchPath))?.etag
        val range = send("probeRange")
        val wrong = send("probeWrong")
        val right = if (etag != null) send("probeRight", JSONObject().put("etag", etag)) else null
        val none = send("probeNoneMatch")
        val builtSecond = coreCall("webdav", "probeNoneMatch", input(JSONObject().put("path", second)))
        http.send(CloudRequest.of(builtSecond.getJSONObject("request")), SendIo(), any)
        val move = send("probeMoveOver")
        val verdict = coreCall(
            "webdav", "probeEvaluate",
            JSONObject()
                .put("put", JSONObject().put("status", put.status).also { if (etag != null) it.put("etag", etag) })
                .put("wrongMatch", JSONObject().put("status", wrong.status))
                .put("rightMatch", JSONObject().put("status", right?.status ?: 0))
                .put("range", JSONObject().put("status", range.status))
                .put("noneMatch", JSONObject().put("status", none.status))
                .put("moveOver", JSONObject().put("status", move.status)),
        )
        for (p in listOf(scratchPath, second)) runCatching {
            val built = coreCall("webdav", "trash", input(JSONObject().put("ref", Ref(path = p).json())))
            http.send(CloudRequest.of(built.getJSONObject("request")), SendIo(), listOf(404))
        }
        casVerified = verdict.optBoolean("cas")
        rangeVerified = verdict.optBoolean("range")
        createOnlyVerified = verdict.optBoolean("createOnly")
        noReplaceMoveVerified = verdict.optBoolean("noReplaceMove")
        refreshCaps()
        return verdict
    }

    /**
     * WebDAV without a proven create-only: write a temporary name, then MOVE it onto the real name with Overwrite: F. A server that
     * honours that refuses a taken name (412); one that does not leaves a small race that the advisory lock narrows (it cannot close it).
     */
    private fun createViaTemp(parent: Ref, name: String, write: (String) -> CloudEntry): CloudEntry {
        val dir = parent.path ?: ""
        val finalPath = listOf(dir, name).filter { it.isNotEmpty() }.joinToString("/")
        if (stat(Ref(path = finalPath)) != null) throw CloudError(Kind.CONFLICT, 412, "precondition", "exists")
        val temp = ".keepfin-tmp-${java.lang.Long.toString(now(), 36)}-${java.lang.Long.toString((Math.random() * 1e9).toLong(), 36)}"
        val tempPath = listOf(dir, temp).filter { it.isNotEmpty() }.joinToString("/")
        val entry = write(temp)
        try {
            val moved = move(Ref(path = tempPath), toPath = finalPath)
            return CloudEntry(moved.id ?: entry.id, name, false, moved.size ?: entry.size, moved.mtimeMs ?: entry.mtimeMs, entry.createdMs, moved.etag ?: entry.etag, entry.hashes, entry.parentId, entry.path)
        } catch (e: CloudError) {
            runCatching { trash(Ref(path = tempPath)) }
            throw e
        }
    }

    // -------------------------------------------------------------------------------------------------------------- upload

    private fun readFully(input: InputStream, buf: ByteArray, length: Int): Int {
        var total = 0
        while (total < length) {
            val n = input.read(buf, total, length - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /**
     * Uploads [size] bytes of [source] as a new object [name] under [parent], or as a new revision of [replace]. The bytes that go
     * out are hashed on the way; the last chunk is held back until the whole stream hashed to [pinned] (when given), so bytes that
     * are not what was pinned are never committed. A restart resumes the journaled session: the part already sent is read again and
     * compared with the journal before anything more goes out. At most one chunk (10 MiB) is held in memory.
     */
    fun upload(source: InputStream, size: Long, pinned: String?, parent: Ref? = null, name: String? = null, replace: Pair<Ref, String>? = null, abort: () -> Boolean = { false }, onBytes: (Long) -> Unit = {}): UploadResult {
        if (provider == "webdav" && replace == null && !createOnlyVerified) {
            val result = arrayOfNulls<UploadResult>(1)
            val entry = createViaTemp(parent!!, name!!) { temp ->
                val r = uploadDirect(source, size, pinned, parent, temp, null, abort, onBytes)
                result[0] = r
                r.entry
            }
            return UploadResult(entry, result[0]!!.contentId)
        }
        return uploadDirect(source, size, pinned, parent, name, replace, abort, onBytes)
    }

    private fun uploadDirect(source: InputStream, size: Long, pinned: String?, parent: Ref?, name: String?, replace: Pair<Ref, String>?, abort: () -> Boolean, onBytes: (Long) -> Unit): UploadResult {
        if (size == 0L && provider == "graph") {
            val empty = "sha256:" + hex(MessageDigest.getInstance("SHA-256").digest())
            if (pinned != null && pinned != empty) throw ContentMismatch(pinned, empty)
            val entry = if (replace != null) replaceSmall(replace.first, replace.second, "") else createSmall(parent!!, name!!, "")
            return UploadResult(entry, empty)
        }
        val algo = caps.serverHash
        val hasher = ProviderHasher(if (algo == "sha256") null else algo)
        val single = provider == "webdav"
        var spool: File? = null
        var stream = source
        try {
            if (single) {
                // a whole-file PUT can be sent again: the bytes are staged once (one file in flight) and read from there
                stagingDir.mkdirs()
                val f = File.createTempFile("up-", ".kftmp", stagingDir)
                spool = f
                f.outputStream().use { out ->
                    val buf = ByteArray(256 * 1024)
                    var left = size
                    while (left > 0) {
                        if (abort()) throw CloudError(Kind.TRANSIENT, code = "paused")
                        val n = stream.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                        if (n < 0) throw IOException("The source ended early.")
                        hasher.update(buf, 0, n)
                        out.write(buf, 0, n)
                        left -= n
                    }
                }
                val got = "sha256:" + hasher.shaNow()
                if (pinned != null && got != pinned) throw ContentMismatch(pinned, got)
                stream = f.inputStream()
            }
            val key = "${http.account.id}|${if (replace != null) "r:" + (replace.first.id ?: replace.first.path) else (parent?.id ?: parent?.path ?: "") + "/" + name}|$size|${pinned ?: ""}"
            var session: JSONObject? = null
            var offset = 0L
            if (!single) {
                val saved = journal.get(key)
                if (saved != null && saved.total == size && (saved.expiresAtMs == null || saved.expiresAtMs > now())) {
                    val q = resume(saved.session, saved.offset, size)
                    if (q != null && q > 0 && q <= size) {
                        session = saved.session
                        offset = q
                    } else journal.remove(key)
                }
            }
            if (session == null) {
                val started = startSession(parent, name, replace, size)
                session = started.first
                if (!single) journal.put(key, JournalEntry(session, 0, size, hex(MessageDigest.getInstance("SHA-256").digest()), now(), started.second))
            }
            // Read again what an earlier run already sent, so the whole-stream check still covers every byte (and shows a changed source).
            if (!single && offset > 0) {
                val buf = ByteArray(1024 * 1024)
                var at = 0L
                while (at < offset) {
                    if (abort()) throw CloudError(Kind.TRANSIENT, code = "paused")
                    val n = stream.read(buf, 0, minOf(buf.size.toLong(), offset - at).toInt())
                    if (n < 0) throw CloudError(Kind.TRANSIENT, code = "source-changed", info = "The source is shorter than what was already sent.")
                    hasher.update(buf, 0, n)
                    at += n
                }
                val saved = journal.get(key)
                if (saved != null && saved.prefixSha.isNotEmpty() && saved.offset == offset && saved.prefixSha != hasher.shaNow()) {
                    journal.remove(key)
                    throw CloudError(Kind.TRANSIENT, code = "source-changed", info = "The source changed since part of it was sent.")
                }
            }
            val chunk = (if (single) size else (caps.chunkPreferred.takeIf { it > 0 } ?: size)).coerceAtLeast(1).coerceAtMost(Int.MAX_VALUE.toLong())
            val buf = if (single) ByteArray(0) else ByteArray(minOf(chunk, maxOf(size - offset, 1L)).toInt())
            var entry: CloudEntry? = null
            var stalls = 0
            while (entry == null) {
                if (abort()) throw CloudError(Kind.TRANSIENT, code = "paused")
                val length = if (single) size else minOf(chunk, size - offset)
                if (!single) {
                    val got = readFully(stream, buf, length.toInt())
                    if (got < length) throw IOException("The source ended early.")
                    hasher.update(buf, 0, length.toInt())
                }
                val last = offset + length >= size
                if (last && !single) {
                    val got = "sha256:" + hasher.shaNow()
                    if (pinned != null && got != pinned) throw ContentMismatch(pinned, got)
                }
                val inp = JSONObject().put("session", session).put("offset", offset).put("length", length).put("total", size)
                if (replace != null) inp.put("mode", "replace").put("ref", replace.first.json()).put("etag", replace.second)
                parent?.let { inp.put("parent", it.json()) }
                name?.let { inp.put("name", it) }
                val built = coreCall(provider, "uploadAppend", input(inp))
                if (built.optBoolean("unsupported")) throw CloudError(Kind.FATAL, code = "unsupported", info = built.optString("reason"), op = "upload")
                val sliceBody: (Long, Long) -> RequestBody = if (single) { _, l -> FileSliceBody(spool!!, 0, l, abort) } else { o, l ->
                    if (o != offset) throw IllegalStateException("The core asked for another slice than the one held.")
                    buf.toRequestBody(null, 0, l.toInt())
                }
                val response = http.send(CloudRequest.of(built.getJSONObject("request")), SendIo(body = sliceBody, abort = abort, context = "upload"), listOf(308))
                val pe = JSONObject().put("response", response.json()).put("offset", offset).put("length", length).put("total", size)
                name?.let { pe.put("name", it) }
                val parsed = coreCall(provider, "parseUploadAppend", input(pe))
                if (!parsed.optBoolean("ok")) {
                    val err = CloudError.of(parsed.getJSONObject("error"), "upload")
                    if (err.code == "incorrect_offset" && err.correctOffset != null && stalls++ < 3) {
                        // The provider holds a different amount than we do: the journal learns it, and the next attempt carries on from there.
                        journal.update(key, err.correctOffset, "")
                        throw CloudError(Kind.TRANSIENT, code = "resume", info = "The provider holds a different part of the file; it will be continued.", op = "upload")
                    }
                    if (err.code == "ResumeNeeded") throw CloudError(Kind.TRANSIENT, code = "resume", op = "upload")
                    if (err.code == "SessionExpired") journal.remove(key)
                    throw err
                }
                onBytes(length)
                if (parsed.optBoolean("done")) {
                    entry = CloudEntry.of(parsed.getJSONObject("entry"))
                } else {
                    val next = if (parsed.has("nextOffset") && !parsed.isNull("nextOffset")) parsed.getLong("nextOffset") else offset + length
                    if (!single) journal.update(key, next, hasher.shaNow())
                    if (next != offset + length) throw CloudError(Kind.TRANSIENT, code = "resume", op = "upload")
                    offset = next
                    if (offset >= size) throw CloudError(Kind.FATAL, code = "not-committed", op = "upload")
                }
            }
            val contentId = "sha256:" + hasher.shaNow()
            val reported = when (algo) {
                "sha256" -> entry.hashes["sha256"]
                "md5" -> entry.hashes["md5"]
                "quickXor" -> entry.hashes["quickXor"]
                "dropbox" -> entry.hashes["dropbox"]
                else -> null
            }
            if (algo == "sha256" && reported != null && "sha256:$reported" != contentId) throw CloudError(Kind.FATAL, code = "hash-mismatch", info = "The provider stored different bytes.", op = "upload")
            if (algo != null && algo != "sha256" && reported != null && hasher.providerHash() != reported) throw CloudError(Kind.FATAL, code = "hash-mismatch", info = "The provider stored different bytes.", op = "upload")
            journal.remove(key)
            return UploadResult(entry, contentId)
        } finally {
            if (single) runCatching { stream.close() }
            spool?.delete()
        }
    }

    private fun startSession(parent: Ref?, name: String?, replace: Pair<Ref, String>?, size: Long): Pair<JSONObject, Long?> {
        val inp = JSONObject().put("size", size)
        parent?.let { inp.put("parent", it.json()) }
        name?.let { inp.put("name", it) }
        if (replace != null) inp.put("mode", "replace").put("ref", replace.first.json()).put("etag", replace.second)
        val built = coreCall(provider, "uploadStart", input(inp))
        if (built.optBoolean("unsupported")) throw CloudError(Kind.FATAL, code = "unsupported", info = built.optString("reason"), op = "upload")
        if (!built.has("request") || built.isNull("request")) return built.getJSONObject("session") to null
        val response = http.send(CloudRequest.of(built.getJSONObject("request")), SendIo(context = "upload"))
        val pe = JSONObject().put("response", response.json()).put("nowMs", now())
        if (built.has("commit")) pe.put("commit", built.get("commit"))
        val parsed = coreCall(provider, "parseUploadStart", input(pe))
        if (!parsed.optBoolean("ok")) throw CloudError.of(parsed.getJSONObject("error"), "upload")
        return parsed.getJSONObject("session") to (if (parsed.has("expiresAtMs") && !parsed.isNull("expiresAtMs")) parsed.getLong("expiresAtMs") else null)
    }

    /** Asks where an old session stands. Null when it is gone (the caller starts again). */
    private fun resume(session: JSONObject, offset: Long, total: Long): Long? {
        if (session.optBoolean("single")) return 0
        val built = coreCall(provider, "uploadQuery", input(JSONObject().put("session", session).put("total", total).put("offset", offset)))
        val response = http.send(CloudRequest.of(built.getJSONObject("request")), SendIo(context = "upload"), listOf(308))
        val parsed = coreCall(provider, "parseUploadQuery", input(JSONObject().put("response", response.json()).put("offset", offset)))
        if (!parsed.optBoolean("ok")) {
            val e = CloudError.of(parsed.getJSONObject("error"), "upload")
            if (e.kind == Kind.NOT_FOUND) return null
            throw e
        }
        return if (parsed.optBoolean("done")) null else parsed.optLong("nextOffset", 0)
    }

    // ------------------------------------------------------------------------------------------------------------ download

    /**
     * Opens the body of [ref] from byte [start]. [ignoredRange] is true when the provider answered 200 to a ranged request: the
     * body then starts at byte 0 and the caller skips [start] bytes (an answer like that is never appended to a partial file).
     */
    class Download(val body: okhttp3.ResponseBody, val response: okhttp3.Response, val ignoredRange: Boolean, val total: Long?)

    fun openDownload(ref: Ref, start: Long, abort: () -> Boolean = { false }): Download {
        var url: String? = null
        for (restart in 0..2) {
            if (provider == "graph" && url == null) url = exec("downloadResolve", "parseDownloadResolve", JSONObject().put("ref", ref.json())).getString("url")
            val inp = JSONObject().put("ref", ref.json()).put("start", start)
            url?.let { inp.put("url", it) }
            val built = coreCall(provider, "download", input(inp))
            val res = try {
                http.stream(CloudRequest.of(built.getJSONObject("request")), SendIo(abort = abort, context = "download"))
            } catch (e: CloudError) {
                if (provider == "graph" && url != null && (e.status == 401 || e.status == 403 || e.status == 404) && restart < 2) {
                    url = null
                    continue
                }
                throw e
            }
            val headers = JSONObject()
            for (n in res.headers.names()) headers.put(n.lowercase(), res.headers.values(n).joinToString(", "))
            val verdict = coreCall(provider, "parseDownload", input(JSONObject().put("response", JSONObject().put("status", res.code).put("headers", headers).put("body", "")).put("start", start)))
            if (!verdict.optBoolean("ok")) {
                res.close()
                if (verdict.optBoolean("ignoredRange")) throw CloudError(Kind.FATAL, res.code, "range-ignored", op = "download")
                val code = verdict.optJSONObject("error")?.optString("code")?.ifEmpty { null }
                throw CloudError(Kind.FATAL, res.code, code, op = "download")
            }
            val ignored = start > 0 && res.code == 200
            return Download(res.body!!, res, ignored, if (verdict.has("total") && !verdict.isNull("total")) verdict.optLong("total") else null)
        }
        throw CloudError(Kind.FATAL, code = "download", op = "download")
    }

    /** The bytes of a small object (locks, generation records, folder.json). */
    fun readBytes(ref: Ref, maxBytes: Long = 64L * 1024 * 1024): ByteArray {
        val d = openDownload(ref, 0)
        d.response.use {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            val ins = d.body.byteStream()
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > maxBytes) throw CloudError(Kind.FATAL, code = "too-large", op = "download")
            }
            return out.toByteArray()
        }
    }

    fun readText(ref: Ref, maxBytes: Long = 1_000_000): String = readBytes(ref, maxBytes).toString(Charsets.UTF_8)
}

