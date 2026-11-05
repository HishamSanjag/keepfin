package app.keepfin.cloud

import org.json.JSONObject
import java.io.InputStream
import java.util.UUID

internal const val KEEPFIN_DIR = ".keepfin"

/** One name in a folder of the replica, with the name the library knows (decoded). */
internal class DirEntry(val name: String, val folder: Boolean, val size: Long, val mtimeMs: Long, val id: String?, val etag: String?)

internal class DirListing(val entries: List<DirEntry>, val complete: Boolean, val collisions: List<String>)

internal class Located(val ref: Ref, val parent: Ref, val name: String, val entry: CloudEntry?)

/**
 * The cloud copy's side of the copies engine (dev-docs/plans/KEEPFIN-COPIES-SPEC.md section 9, KEEPFIN-CLOUD-SPEC.md 7.1): library paths
 * in, provider objects out. A port of gui/desktop/cloud/transport.ts on top of [CloudClient]:
 *  - a listing says whether it was read to the end (a torn page, an incompleteSearch or a failed 207 resource makes it incomplete;
 *    a name collision is reported, never merged);
 *  - upload and download hash the bytes they move; replace overwrites only by compare-and-swap, and where that does not exist the
 *    displaced bytes move to `.keepfin/trash` by id and nothing is overwritten or deleted in place;
 *  - the lock object is advisory, generations are append-only records.
 */
internal class CloudTransport(
    private val client: CloudClient,
    /** The replica's folder on the account, relative to the app area (`Keepfin/Library`; for WebDAV the folder the user named). */
    rootPath: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val randomId: () -> String = { UUID.randomUUID().toString().take(6) },
) {
    private val provider = client.provider
    private val rootSegments = rootPath.split('/').filter { it.isNotEmpty() }
    private val encodeCache = HashMap<String, String>()
    private val folders = HashMap<String, Ref>()

    val caps: Caps get() = client.capabilities()

    @Synchronized private fun enc(name: String): String = encodeCache.getOrPut(name) { coreCall(provider, "nameEncode", JSONObject().put("name", name)).getString("encoded") }

    private fun dec(stored: String): String = coreCall(provider, "nameDecode", JSONObject().put("stored", stored)).getString("name")

    private fun key(name: String): String = coreCall(provider, "nameKey", JSONObject().put("name", name)).getString("key")

    private fun segments(lib: String) = lib.split('/').filter { it.isNotEmpty() }

    private fun pathRef(segs: List<String>): Ref = Ref(path = (rootSegments + segs.map { enc(it) }).joinToString("/"))

    // ------------------------------------------------------------------------------------------------------- addressing

    /** The folder at [segs] under the root; with [create] the missing ones are made. Drive resolves ids by listing. Null when it is not there. */
    @Synchronized
    fun folderRef(segs: List<String>, create: Boolean): Ref? {
        if (provider != "drive") {
            if (create) ensurePath(segs)
            return pathRef(segs)
        }
        var ref = Ref(id = "root")
        var key = ""
        for (raw in rootSegments + segs) {
            key += "/$raw"
            val cached = folders[key]
            if (cached != null) {
                ref = cached
                continue
            }
            val stored = enc(raw)
            val found = client.list(ref, stored).entries.filter { it.folder && it.name == stored }
            if (found.size > 1) throw CloudError(Kind.CONFLICT, code = "duplicate-names", info = "Two folders named $raw exist; resolve them by hand.", op = "resolve")
            if (found.isEmpty()) {
                if (!create) return null
                ref = Ref(id = client.createFolder(ref, stored).id)
            } else {
                ref = Ref(id = found[0].id)
            }
            folders[key] = ref
        }
        return ref
    }

    private fun ensurePath(segs: List<String>) {
        val all = rootSegments + segs.map { enc(it) }
        for (i in 1..all.size) {
            val p = all.subList(0, i).joinToString("/")
            if (folders.containsKey(p)) continue
            val parent = Ref(path = all.subList(0, i - 1).joinToString("/"))
            try {
                client.createFolder(parent, all[i - 1])
            } catch (e: CloudError) {
                // exists (or a racing writer made it): fine. Anything else is real.
                if (e.kind != Kind.CONFLICT) throw e
            }
            folders[p] = Ref(path = p)
        }
    }

    @Synchronized fun forgetFolders() = folders.clear()

    /** The object (file or folder) at library path [lib]. Null when it does not exist. */
    fun locate(lib: String, create: Boolean = false): Located? {
        val parts = segments(lib).toMutableList()
        if (parts.isEmpty()) {
            val root = folderRef(emptyList(), create) ?: return null
            val entry = if (provider == "drive") null else client.stat(root) ?: return null
            return Located(root, root, "", entry)
        }
        val leaf = parts.removeAt(parts.size - 1)
        val name = enc(leaf)
        val parent = folderRef(parts, create) ?: return null
        if (provider == "drive") {
            val found = client.list(parent, name).entries.filter { it.name == name }
            if (found.size > 1) throw CloudError(Kind.CONFLICT, code = "duplicate-names", info = "Two items named $leaf exist.", op = "resolve")
            if (found.isEmpty()) return Located(Ref(), parent, name, null)
            return Located(Ref(id = found[0].id), parent, name, found[0])
        }
        val ref = Ref(path = listOf(parent.path ?: "", name).filter { it.isNotEmpty() }.joinToString("/"))
        val entry = client.stat(ref)
        return Located(ref, parent, name, entry)
    }

    /** True when something exists at the located address. */
    private fun Located.exists() = entry != null

    // ------------------------------------------------------------------------------------------------------------ listing

    /** The names directly under [lib], read to the end if possible. Throws NotFound when [lib] is not there. */
    fun list(lib: String): DirListing {
        val ref = folderRef(segments(lib), false) ?: throw CloudError(Kind.NOT_FOUND, 404, op = "list")
        val res = client.list(ref)
        val out = ArrayList<DirEntry>()
        val collisions = ArrayList<String>()
        val seen = HashMap<String, String>()
        for (e in res.entries) {
            val name = dec(e.name)
            if (lib.isEmpty() && e.name == KEEPFIN_DIR && !e.folder) continue
            val k = key(name)
            val clash = seen[k]
            if (clash != null && (provider == "drive" || clash != name)) collisions.add(if (lib.isEmpty()) name else "$lib/$name")
            seen[k] = name
            out.add(DirEntry(name, e.folder, e.size ?: 0L, e.mtimeMs ?: 0L, e.id, e.etag))
        }
        return DirListing(out, res.complete && collisions.isEmpty(), collisions)
    }

    /** The object at [lib] as a listing would show it, or null. */
    fun stat(lib: String): DirEntry? {
        val loc = locate(lib) ?: return null
        val e = loc.entry ?: return null
        return DirEntry(segments(lib).lastOrNull() ?: "", e.folder, e.size ?: 0L, e.mtimeMs ?: 0L, e.id, e.etag)
    }

    // -------------------------------------------------------------------------------------------------------------- bytes

    /** What is needed to read an object again: its address and size. */
    class Remote(val ref: Ref, val size: Long?)

    fun remote(lib: String): Remote {
        val loc = locate(lib)
        if (loc == null || loc.entry == null || loc.entry.folder) throw CloudError(Kind.NOT_FOUND, 404, op = "download")
        return Remote(loc.ref, loc.entry.size)
    }

    /** A stream of the object's bytes that carries on from where it broke (Range) and never appends an answer that ignored the Range. */
    fun openRead(lib: String, abort: () -> Boolean = { false }): InputStream = RemoteInput(client, remote(lib), abort)

    fun readBytes(lib: String, max: Long = 64L * 1024 * 1024): ByteArray {
        val r = remote(lib)
        return client.readBytes(r.ref, max)
    }

    /** The text of a small file, or null when it does not exist. */
    fun readSmall(lib: String): String? = try {
        val loc = locate(lib)
        if (loc == null || loc.entry == null || loc.entry.folder) null else client.readText(loc.ref)
    } catch (e: CloudError) {
        if (e.kind == Kind.NOT_FOUND) null else throw e
    }

    /** Sends a new object under a name that is free; refuses ('exists') when it is taken. */
    fun uploadNew(source: InputStream, size: Long, lib: String, pinned: String?, abort: () -> Boolean = { false }, onBytes: (Long) -> Unit = {}): UploadResult {
        val loc = locate(lib, true) ?: throw CloudError(Kind.NOT_FOUND, 404, op = "upload")
        if (loc.entry != null) throw CloudError(Kind.CONFLICT, code = "exists", op = "upload")
        val result = client.http.transfer { client.upload(source, size, pinned, loc.parent, loc.name, null, abort, onBytes) }
        if (caps.duplicateNames) {
            // Drive has no create-only: if a racing writer made the same name, the earliest object keeps it and ours goes to the trash.
            val mine = client.list(loc.parent, loc.name).entries.filter { it.name == loc.name && !it.folder }.sortedWith(compareBy({ it.createdMs ?: 0L }, { it.id ?: "" }))
            if (mine.size > 1 && mine[0].id != result.entry.id) {
                client.trash(Ref(id = result.entry.id))
                throw CloudError(Kind.CONFLICT, code = "exists", op = "upload")
            }
        }
        return result
    }

    /**
     * Replaces the object only by compare-and-swap against [revision]; without one (or on a provider that has none) the displaced bytes
     * move to `.keepfin/trash` first and the new bytes are created anew. Never overwrites in place.
     */
    fun replace(bytes: ByteArray, lib: String, revision: String?, abort: () -> Boolean = { false }): UploadResult {
        val loc = locate(lib)
        if (caps.casReplace && revision != null && loc != null && loc.entry != null && !loc.entry.folder) {
            val ref = if (loc.entry.id != null) Ref(id = loc.entry.id) else loc.ref
            return client.http.transfer { client.upload(bytes.inputStream(), bytes.size.toLong(), null, null, null, ref to revision, abort) }
        }
        if (loc != null && loc.entry != null && !loc.entry.folder) {
            try {
                moveToTrash(lib, revision)
            } catch (e: CloudError) {
                if (e.kind != Kind.NOT_FOUND) throw e
            }
        }
        return uploadNew(bytes.inputStream(), bytes.size.toLong(), lib, null, abort)
    }

    /** Overwrites a small advisory object (the lock heartbeat) where the provider can do it safely; elsewhere withdraws and creates anew. */
    fun replaceAdvisory(bytes: ByteArray, lib: String) {
        val loc = locate(lib) ?: throw CloudError(Kind.NOT_FOUND, 404, op = "lock")
        val e = loc.entry ?: throw CloudError(Kind.NOT_FOUND, 404, op = "lock")
        val text = bytes.toString(Charsets.UTF_8)
        when {
            caps.casReplace && e.etag != null -> client.replaceSmall(if (e.id != null) Ref(id = e.id) else loc.ref, e.etag, text)
            provider == "drive" -> client.replaceSmall(Ref(id = e.id), e.etag ?: "", text, advisory = true)
            else -> {
                removeForGood(lib, e.etag)
                client.createSmall(loc.parent, loc.name, text)
            }
        }
    }

    /** Moves the object to `.keepfin/trash` under a name that carries a stamp (nothing is deleted). */
    fun moveToTrash(lib: String, revision: String?): String {
        val loc = locate(lib)
        if (loc == null || loc.entry == null) throw CloudError(Kind.NOT_FOUND, 404, op = "trash")
        val stamp = "${now()}-${randomId()}"
        val trashName = "$stamp-${loc.name}"
        val trashRef = folderRef(listOf(KEEPFIN_DIR, "trash"), true)!!
        val cas = if (caps.casReplace && revision != null) revision else null
        when (provider) {
            "drive" -> client.move(loc.ref, newParentId = trashRef.id, fromParentId = loc.parent.id, newName = trashName)
            "graph" -> client.move(loc.ref, newParentId = client.stat(trashRef)!!.id, newName = trashName, etag = cas)
            "dropbox" -> client.move(loc.ref, toPath = listOf(trashRef.path ?: "", trashName).filter { it.isNotEmpty() }.joinToString("/"))
            else -> client.move(loc.ref, toPath = listOf(trashRef.path ?: "", trashName).filter { it.isNotEmpty() }.joinToString("/"), etag = cas)
        }
        if (loc.entry.folder) forgetFolders()
        return "$KEEPFIN_DIR/trash/$trashName"
    }

    /** Ends an object for good: the end of life of bytes that were already preserved elsewhere (expired trash, our own scratch files). */
    fun removeForGood(lib: String, revision: String?) {
        val loc = locate(lib)
        if (loc == null || loc.entry == null) return
        try {
            client.trash(loc.ref, if (caps.casDelete && revision != null) revision else null)
        } catch (e: CloudError) {
            if (e.kind != Kind.NOT_FOUND) throw e
        }
        if (loc.entry.folder) forgetFolders()
    }

    /**
     * Renames or moves an object by id to another library path (no bytes are sent). Never replaces anything at [toLib]
     * ([Kind.CONFLICT] 'exists' when the name is taken).
     */
    fun moveTo(fromLib: String, toLib: String, revision: String?): CloudEntry {
        val loc = locate(fromLib)
        if (loc == null || loc.entry == null) throw CloudError(Kind.NOT_FOUND, 404, op = "move")
        val parts = segments(toLib).toMutableList()
        val leaf = enc(parts.removeAt(parts.size - 1))
        val destParent = folderRef(parts, true) ?: throw CloudError(Kind.NOT_FOUND, 404, op = "move")
        if (locate(toLib)?.entry != null) throw CloudError(Kind.CONFLICT, code = "exists", op = "move")
        val cas = if (caps.casReplace && revision != null) revision else null
        val moved = when (provider) {
            "drive" -> client.move(loc.ref, newParentId = destParent.id, fromParentId = loc.parent.id, newName = leaf)
            "graph" -> client.move(loc.ref, newParentId = client.stat(destParent)!!.id, newName = leaf, etag = cas)
            "dropbox" -> client.move(loc.ref, toPath = listOf(destParent.path ?: "", leaf).filter { it.isNotEmpty() }.joinToString("/"))
            else -> client.move(loc.ref, toPath = listOf(destParent.path ?: "", leaf).filter { it.isNotEmpty() }.joinToString("/"), etag = cas)
        }
        if (loc.entry.folder) forgetFolders()
        return moved
    }

    // ----------------------------------------------------------------------------------------------- the replica itself

    /** Creates the replica's folder and its `.keepfin` folder when they are not there yet. */
    fun ensureRoot() {
        folderRef(listOf(KEEPFIN_DIR), true)
    }

    fun mkdirs(lib: String) {
        folderRef(segments(lib), true)
    }

    /** The names of the folders directly under [lib] (decoded), or null when [lib] does not exist. */
    fun listChildFolders(lib: String): List<String>? {
        val ref = folderRef(segments(lib), false) ?: return null
        val res = try { client.list(ref) } catch (e: CloudError) { if (e.kind == Kind.NOT_FOUND) return null else throw e }
        return res.entries.filter { it.folder }.map { dec(it.name) }
    }

    // ----------------------------------------------------------------------------------------------------- generations

    /** The highest generation recorded on the replica (`.keepfin/generations/<n>-<replicaId>.json`), 0 when none. */
    fun readGeneration(): Long {
        val dir = folderRef(listOf(KEEPFIN_DIR, "generations"), false) ?: return 0
        val res = try { client.list(dir) } catch (e: CloudError) { if (e.kind == Kind.NOT_FOUND) return 0 else throw e }
        if (!res.complete) throw CloudError(Kind.TRANSIENT, code = "incomplete-listing", op = "generation")
        var max = 0L
        for (e in res.entries) {
            val n = Regex("^(\\d+)-").find(dec(e.name))?.groupValues?.get(1)?.toLongOrNull() ?: continue
            if (n > max) max = n
        }
        return max
    }

    /** Appends the generation as a record. False when that record is already there. */
    fun appendGeneration(generation: Long, replicaId: String): Boolean {
        val dir = folderRef(listOf(KEEPFIN_DIR, "generations"), true)!!
        val name = enc("${generation.toString().padStart(10, '0')}-$replicaId.json")
        if (provider == "drive" && client.list(dir, name).entries.any { it.name == name }) return false
        return try {
            client.createSmall(dir, name, JSONObject().put("generation", generation).put("replicaId", replicaId).toString())
            true
        } catch (e: CloudError) {
            if (e.kind == Kind.CONFLICT) false else throw e
        }
    }
}

/**
 * The bytes of an object as a stream. A break in the connection carries on from the byte reached with a Range request; an answer that
 * ignored the Range (HTTP 200) is read from the start and the part already delivered is skipped, never appended twice.
 */
internal class RemoteInput(private val client: CloudClient, private val remote: CloudTransport.Remote, private val abort: () -> Boolean) : InputStream() {
    private var current: CloudClient.Download? = null
    private var stream: InputStream? = null
    private var pos = 0L
    private var failures = 0

    private fun open() {
        close0()
        val d = client.openDownload(remote.ref, pos, abort)
        current = d
        val s = d.body.byteStream()
        if (d.ignoredRange) {
            var skip = pos
            val buf = ByteArray(64 * 1024)
            while (skip > 0) {
                val n = s.read(buf, 0, minOf(buf.size.toLong(), skip).toInt())
                if (n < 0) throw java.io.IOException("The answer ended before the part already read.")
                skip -= n
            }
        }
        stream = s
    }

    private fun close0() {
        runCatching { stream?.close() }
        runCatching { current?.response?.close() }
        stream = null
        current = null
    }

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n < 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (true) {
            if (abort()) throw CloudError(Kind.TRANSIENT, code = "paused")
            if (remote.size != null && pos >= remote.size) return -1
            if (stream == null) open()
            try {
                val n = stream!!.read(b, off, len)
                if (n < 0) {
                    if (remote.size != null && pos < remote.size) throw java.io.IOException("The connection ended early.")
                    return -1
                }
                pos += n
                failures = 0
                return n
            } catch (e: java.io.IOException) {
                if (e is CloudError) throw e
                close0()
                if (++failures > 5) throw CloudError(Kind.TRANSIENT, code = "network", info = e.message, op = "download")
                Thread.sleep(200L * failures)
            }
        }
    }

    override fun close() = close0()
}
