package app.keepfin.cloud

import app.keepfin.copies.ServerSideMove
import app.keepfin.copies.SizedStream
import app.keepfin.copies.SkipError
import app.keepfin.copies.hashPath
import app.keepfin.copies.parentPath
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderEntry
import app.keepfin.folder.FolderError
import app.keepfin.folder.FolderListing
import app.keepfin.folder.PublishResult
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * A cloud account as a Keepfin folder: the copies engine runs over it exactly as over a card or a drive (no local mirror of the media).
 * Paths are library paths. What differs from a drive:
 *  - nothing is overwritten or deleted in place: a replaced or removed object moves to `.keepfin/trash` first; only the end of life of
 *    bytes that were already preserved elsewhere (expired trash and versions, our own scratch files, the lock) is deleted;
 *  - the lock object is `.keepfin/lock.json` and generations are also written as append-only records, the layout the desktop uses,
 *    so a phone and a computer see each other's locks and generations on one replica.
 */
internal class CloudFolderAccess(
    private val transport: CloudTransport,
    private val client: CloudClient,
    private val spoolDir: File,
    private val now: () -> Long = System::currentTimeMillis,
) : FolderAccess, ServerSideMove {
    private var quotaAt = 0L
    private var quotaFree: Long? = null
    private var folderJsonAt = 0L
    private var folderJsonStat: FolderEntry? = null

    // ------------------------------------------------------------------------------------------------------------- errors

    private fun map(e: CloudError, path: String): FolderError = when (e.kind) {
        Kind.NEEDS_SIGN_IN -> FolderError.NeedsAccess("Sign in again to keep using this account.", e)
        Kind.NOT_FOUND -> FolderError.NotFound(path, e)
        Kind.CONFLICT -> if (e.code == "duplicate-names") FolderError.Io(e.info ?: "Two items with the same name are on the account.", e) else FolderError.NameConflict(path)
        Kind.QUOTA_FULL -> FolderError.DiskFull("The account is full.", e)
        Kind.TRANSIENT, Kind.RATE_LIMITED ->
            if (e.code == "resume" || e.code == "source-changed") FolderError.Io(e.info ?: "The upload will be continued on the next sync.", e)
            else FolderError.Unavailable(e.info ?: "The account could not be reached.", e)
        else -> FolderError.Io(e.message ?: "The account refused the request.", e)
    }

    private inline fun <T> guard(path: String, block: () -> T): T = try {
        block()
    } catch (e: CloudError) {
        throw map(e, path)
    } catch (e: ContentMismatch) {
        throw FolderError.Io(e.message ?: "The bytes do not match.", SkipError("hash-mismatch", "The source no longer matches its pinned content."))
    }

    // ----------------------------------------------------------------------------------------------------------- aliases

    private fun alias(path: String): String = if (path == LOCK) LOCK_OBJECT else path

    private fun entryOf(e: DirEntry) = FolderEntry(e.name, e.folder, e.size, e.mtimeMs)

    private fun purgeable(path: String): Boolean =
        path == KEEPFIN_DIR || path.startsWith("$KEEPFIN_DIR/trash/") || path.startsWith("$KEEPFIN_DIR/versions/") || path.contains(".kftmp") || path == LOCK || path.startsWith("$LOCK.stale-") || path.startsWith("$KEEPFIN_DIR/kf-probe")

    // ----------------------------------------------------------------------------------------------------------- reading

    override fun list(dir: String): FolderListing = guard(dir) {
        val l = transport.list(dir)
        FolderListing(l.entries.map { entryOf(it) }, !l.complete)
    }

    override fun stat(path: String): FolderEntry? = guard(path) {
        if (path.isEmpty()) return@guard if (transport.folderRef(emptyList(), false) != null) FolderEntry("", true, 0, 0) else null
        if (path == FOLDER_JSON) {
            val t = now()
            if (t - folderJsonAt < 10_000 && folderJsonStat != null) return@guard folderJsonStat
            val s = transport.stat(path)?.let { entryOf(it) }
            folderJsonStat = s
            folderJsonAt = t
            return@guard s
        }
        transport.stat(alias(path))?.let { entryOf(it) }
    }

    override fun read(path: String): ByteArray = guard(path) {
        val bytes = transport.readBytes(alias(path))
        if (path != FOLDER_JSON) return@guard bytes
        // folder.json carries the generation; the replica's generation records may be higher (another device appended one)
        val json = try { JSONObject(bytes.toString(Charsets.UTF_8)) } catch (e: Exception) { return@guard bytes }
        val recorded = runCatching { transport.readGeneration() }.getOrDefault(0L)
        if (recorded > json.optLong("generation", 0L)) json.put("generation", recorded).toString(2).toByteArray(Charsets.UTF_8) else bytes
    }

    override fun openRead(path: String): InputStream = guard(path) { transport.openRead(alias(path)) }

    // ----------------------------------------------------------------------------------------------------------- writing

    override fun mkdirs(dir: String) {
        if (dir.isEmpty()) return
        guard(dir) { transport.mkdirs(dir) }
    }

    override fun publish(path: String, bytes: ByteArray): PublishResult = publishSized(path, bytes.inputStream(), bytes.size.toLong(), null)

    override fun publishFrom(path: String, source: InputStream, onProgress: ((Long) -> Unit)?): PublishResult {
        val sized = source as? SizedStream
        val size = sized?.knownSize
        if (size != null) return publishSized(path, source, size, sized.pinnedContentId, onProgress)
        // the size is not known: stage the bytes in a scratch file (one file in flight) to learn it
        spoolDir.mkdirs()
        val f = File.createTempFile("sp-", ".kftmp", spoolDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            f.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    val n = source.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    out.write(buf, 0, n)
                }
            }
            return f.inputStream().use { publishSized(path, it, f.length(), "sha256:" + hex(digest.digest()), onProgress) }
        } finally {
            f.delete()
        }
    }

    private fun publishSized(path: String, source: InputStream, size: Long, pinned: String?, onProgress: ((Long) -> Unit)? = null): PublishResult = guard(path) {
        try {
            val r = transport.uploadNew(source, size, alias(path), pinned, onBytes = { onProgress?.invoke(it) })
            if (path == FOLDER_JSON) folderJsonAt = 0
            PublishResult(size, r.contentId.removePrefix("sha256:"))
        } catch (e: IOException) {
            if (e is CloudError || e is ContentMismatch) throw e
            throw FolderError.Io(e.message ?: "The file could not be read.", e)
        }
    }

    override fun replace(path: String, bytes: ByteArray) {
        guard(path) {
            val p = alias(path)
            if (path == LOCK) {
                transport.replaceAdvisory(bytes, p)
                return@guard
            }
            val current = transport.stat(p)
            if (current == null) transport.uploadNew(bytes.inputStream(), bytes.size.toLong(), p, null) else transport.replace(bytes, p, current.etag)
            if (path == FOLDER_JSON) {
                folderJsonAt = 0
                // the generation is also an append-only record (the desktop reads those)
                runCatching {
                    val json = JSONObject(bytes.toString(Charsets.UTF_8))
                    val g = json.optLong("generation", 0L)
                    val replica = json.optString("replicaId")
                    if (g > 0 && replica.isNotEmpty()) transport.appendGeneration(g, replica)
                }
            }
        }
    }

    override fun remove(path: String) {
        guard(path) {
            val p = alias(path)
            val cur = transport.stat(p) ?: return@guard
            if (purgeable(path)) transport.removeForGood(p, cur.etag) else transport.moveToTrash(p, cur.etag)
            if (path == FOLDER_JSON) folderJsonAt = 0
        }
    }

    override fun rename(path: String, newName: String) {
        guard(path) {
            val from = alias(path)
            val to = if (parentPath(from).isEmpty()) newName else parentPath(from) + "/" + newName
            val cur = transport.stat(from) ?: throw CloudError(Kind.NOT_FOUND, 404, op = "rename")
            val taken = transport.stat(to)
            if (taken != null && !(taken.id != null && taken.id == cur.id)) throw FolderError.NameConflict(to)
            transport.moveTo(from, to, cur.etag)
            if (path == FOLDER_JSON) folderJsonAt = 0
        }
    }

    override fun freeBytes(): Long? {
        val t = now()
        if (quotaAt != 0L && t - quotaAt < 10 * 60_000) return quotaFree
        quotaAt = t
        quotaFree = try { client.quota().freeBytes } catch (e: Exception) { null }
        return quotaFree
    }

    // ------------------------------------------------------------------------------------------------ server-side moves

    override fun moveVerified(from: String, to: String, expected: String?, abort: () -> Boolean): String = guard(from) {
        transport.mkdirs(parentPath(to))
        if (transport.stat(to) != null) throw SkipError("busy", "The archive name was taken.")
        val before = transport.stat(from) ?: throw SkipError("copy-file-missing", "$from is no longer there.")
        val id: String = when {
            // a provider that reports a real SHA-256 (Drive) settles identity without a download
            expected != null && sha256Of(from)?.let { "sha256:$it" } == expected -> expected
            else -> {
                val h = hashPath(this, from, abort).contentId
                if (expected != null && h != expected) throw SkipError("unexpected-destination", "$from changed since it was planned.")
                val after = transport.stat(from)
                if (after == null || after.size != before.size || after.mtimeMs != before.mtimeMs) throw SkipError("unexpected-destination", "$from changed since it was planned.")
                h
            }
        }
        try {
            transport.moveTo(from, to, before.etag)
        } catch (e: CloudError) {
            if (e.kind == Kind.CONFLICT && e.code == "exists") throw SkipError("busy", "The archive name was taken.")
            if (e.kind == Kind.NOT_FOUND) throw SkipError("copy-file-missing", "$from is no longer there.")
            throw e
        }
        id
    }

    /** The provider-reported SHA-256 of [path], when the provider has one (Drive does for binary files). */
    private fun sha256Of(path: String): String? {
        if (client.capabilities().serverHash != "sha256") return null
        val loc = transport.locate(path) ?: return null
        return loc.entry?.hashes?.get("sha256")
    }

    companion object {
        const val LOCK = "$KEEPFIN_DIR/lock"
        const val LOCK_OBJECT = "$KEEPFIN_DIR/lock.json"
        const val FOLDER_JSON = "$KEEPFIN_DIR/folder.json"
    }
}
