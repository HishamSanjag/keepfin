package app.keepfin.net

import app.keepfin.copies.CONTENT_PREFIX
import app.keepfin.copies.CopyLock
import app.keepfin.copies.Generations
import app.keepfin.copies.SkipError
import app.keepfin.copies.Versions
import app.keepfin.copies.hashPath
import app.keepfin.copies.leafName
import app.keepfin.copies.parentPath
import app.keepfin.copies.skippedFile
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.Timer
import java.util.TimerTask
import org.json.JSONObject

/**
 * The peer side of a copy over the network (network spec 5, mirrors gui/desktop/net/peerExecutor.ts): the asking device streams bytes and
 * names an action; this device stages them beside the target, checks the pinned SHA-256, and publishes with the primitives the folder
 * copies use (the copy lock, no-replace publish, displaced bytes kept in `.keepfin/trash/net/`, generations). Nothing outside the folder
 * can be named, and a device never writes into another device's folder directly.
 */
class PeerExecutorImpl(
    private val folder: FolderAccess,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val deviceName: String = "phone",
    private val leaseMs: Long = LEASE_MS,
) : PeerExecutorPort {
    companion object {
        const val LEASE_MS = 60_000L
        private const val RESERVE_BYTES = 64L * 1024 * 1024
        private val CONTENT_ID = Regex("^sha256:[0-9a-f]{64}$")
        private val BAD_CHARS = Regex("[\\\\:*?\"<>|\u0000]")

        /** `size-mtime`: changes whenever the file is edited; it is the precondition of a replace when no content id is known. */
        fun revisionOf(size: Long, mtimeMs: Long) = "$size-$mtimeMs"

        fun validRelative(rel: String): Boolean {
            if (rel.isEmpty() || rel.length > 1024 || rel.startsWith("/") || BAD_CHARS.containsMatchIn(rel)) return false
            val segments = rel.split("/")
            if (segments.any { it.isEmpty() || it == "." || it == ".." || it.endsWith(".") || it.endsWith(" ") || it.length > 255 }) return false
            val first = segments[0].lowercase()
            if (first == ".keepfin" || first == "keepfin exports") return false
            return !skippedFile(segments.last())
        }
    }

    private class Lease(val owner: String, val token: String, val lock: CopyLock, var timer: Timer?)

    private var lease: Lease? = null
    private val random = SecureRandom()

    private fun abs(rel: String): String {
        if (!validRelative(rel)) throw HttpError(400, "path", "That name cannot be used in a copy.")
        return rel
    }

    @Synchronized override fun state(): JSONObject =
        JSONObject().put("generation", Generations.read(folder) ?: JSONObject.NULL).put("locked", lease != null)

    @Synchronized private fun mine(owner: String, token: String): Lease {
        val l = lease
        if (l == null || l.owner != owner || l.token != token) throw HttpError(409, "lock", "The copy is not locked by this device.")
        return l
    }

    private fun armTimer(l: Lease) {
        l.timer?.cancel()
        val t = Timer("keepfin-peer-lease", true)
        t.schedule(object : TimerTask() {
            override fun run() = expire(l.token)
        }, leaseMs)
        l.timer = t
    }

    @Synchronized override fun lock(owner: String, device: String, run: String, now: Long): JSONObject {
        lease?.let { held ->
            if (held.owner == owner) drop(held) else return JSONObject().put("ok", false).put("holder", "another device")
        }
        val token = UUID.randomUUID().toString()
        val result = CopyLock.acquire(folder, "$deviceName:${device.take(40)}", "net-$token", { if (now != 0L) now else this.now() })
        if (result !is CopyLock.Companion.Result.Ok) {
            val holder = (result as CopyLock.Companion.Result.Refused).holder?.device ?: "another run"
            return JSONObject().put("ok", false).put("holder", holder)
        }
        val l = Lease(owner, token, result.lock, null)
        armTimer(l)
        lease = l
        return JSONObject().put("ok", true).put("token", token)
    }

    @Synchronized private fun expire(token: String) {
        lease?.takeIf { it.token == token }?.let { drop(it) }
    }

    private fun drop(l: Lease) {
        l.timer?.cancel()
        if (lease === l) lease = null
        runCatching { l.lock.release() }
    }

    @Synchronized override fun heartbeat(owner: String, token: String): JSONObject {
        armTimer(mine(owner, token))
        return JSONObject().put("ok", true)
    }

    @Synchronized override fun unlock(owner: String, token: String): JSONObject {
        drop(mine(owner, token))
        return JSONObject().put("ok", true)
    }

    @Synchronized override fun release(owner: String) {
        lease?.takeIf { it.owner == owner }?.let { drop(it) }
    }

    @Synchronized override fun appendGeneration(owner: String, token: String, generation: Long, replica: String): JSONObject {
        mine(owner, token)
        if (generation < 0 || replica.isEmpty()) throw HttpError(400, "generation", "The generation is not valid.")
        val current = Generations.read(folder) ?: return JSONObject().put("ok", false).put("generation", JSONObject.NULL)
        if (current >= generation) return JSONObject().put("ok", false).put("generation", current)
        return JSONObject().put("ok", true).put("generation", Generations.bump(folder, token))
    }

    @Synchronized override fun put(owner: String, params: PutParams, body: InputStream): JSONObject {
        mine(owner, params.token)
        if (!CONTENT_ID.matches(params.contentId)) throw HttpError(400, "content-id", "The content id is not valid.")
        val dest = abs(params.path)
        val free = folder.freeBytes()
        if (free != null && free < params.size + RESERVE_BYTES) throw HttpError(507, "space", "There is not enough room on this device.")
        folder.mkdirs(parentPath(dest))
        val tmp = "$dest.kftmp-net-${UUID.randomUUID()}"
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        val guarded = object : FilterInputStream(body) {
            override fun read(): Int {
                val b = super.read()
                if (b >= 0) count(byteArrayOf(b.toByte()), 0, 1)
                return b
            }

            override fun read(buf: ByteArray, off: Int, len: Int): Int {
                val n = super.read(buf, off, len)
                if (n > 0) count(buf, off, n)
                return n
            }

            private fun count(buf: ByteArray, off: Int, n: Int) {
                received += n
                if (received > params.size) throw HttpError(413, "size", "More bytes arrived than were announced.")
                digest.update(buf, off, n)
            }
        }
        var staged = false
        try {
            try {
                folder.publishFrom(tmp, guarded)
                staged = true
            } catch (e: FolderError.DiskFull) {
                throw HttpError(507, "space", "There is not enough room on this device.")
            }
            if (received != params.size) throw HttpError(400, "size", "The file arrived short.")
            val id = CONTENT_PREFIX + hex(digest.digest())
            if (id != params.contentId) throw HttpError(422, "hash", "The bytes do not match the content id.")
            if (params.mode == "new") {
                try {
                    folder.rename(tmp, leafName(dest))
                } catch (e: FolderError.NameConflict) {
                    throw HttpError(409, "exists", "A file with that name is already there.")
                }
                staged = false
                return JSONObject().put("revision", revision(dest)).put("preservedAs", JSONObject.NULL)
            }
            val now = folder.stat(dest)?.takeUnless { it.isDirectory } ?: throw HttpError(412, "changed", "The file is gone.")
            precondition(dest, now.size, now.modifiedMs, params.revision, params.ifMatch)
            val preservedAs = preserve(dest, null)
            try {
                folder.rename(tmp, leafName(dest))
                staged = false
            } catch (e: Exception) {
                runCatching { Versions.moveVerified(folder, "$NET_TRASH/$preservedAs", dest, null) }
                throw e
            }
            return JSONObject().put("revision", revision(dest)).put("preservedAs", preservedAs)
        } finally {
            if (staged) runCatching { folder.remove(tmp) }
        }
    }

    private val NET_TRASH = ".keepfin/trash/net"

    private fun revision(path: String): String = folder.stat(path)?.let { revisionOf(it.size, it.modifiedMs) } ?: ""

    /** Content id decides when given (a touched file with the same bytes is no conflict); size and time only for callers that know nothing better. */
    private fun precondition(file: String, size: Long, mtime: Long, revision: String?, ifMatch: String?) {
        if (!ifMatch.isNullOrEmpty()) {
            if (!CONTENT_ID.matches(ifMatch)) throw HttpError(400, "if-match", "The content id is not valid.")
            if (hashPath(folder, file).contentId != ifMatch) throw HttpError(412, "changed", "The file was changed in the meantime.")
            return
        }
        if (revision == null || revisionOf(size, mtime) != revision) throw HttpError(412, "changed", "The file was changed in the meantime.")
    }

    @Synchronized override fun trash(owner: String, token: String, path: String, revision: String?, ifMatch: String?): JSONObject {
        mine(owner, token)
        val file = abs(path)
        val now = folder.stat(file)?.takeUnless { it.isDirectory } ?: throw HttpError(404, "gone", "The file is already gone.")
        precondition(file, now.size, now.modifiedMs, revision, ifMatch)
        return JSONObject().put("trashedAs", preserve(file, ifMatch))
    }

    @Synchronized override fun move(owner: String, token: String, from: String, to: String, revision: String?, ifMatch: String?): JSONObject {
        mine(owner, token)
        val source = abs(from)
        val dest = abs(to)
        val now = folder.stat(source)?.takeUnless { it.isDirectory } ?: throw HttpError(404, "gone", "The file is already gone.")
        precondition(source, now.size, now.modifiedMs, revision, ifMatch)
        if (folder.stat(dest) != null) throw HttpError(409, "exists", "A file with that name is already there.")
        folder.mkdirs(parentPath(dest))
        try {
            if (parentPath(source) == parentPath(dest)) folder.rename(source, leafName(dest))
            else Versions.moveVerified(folder, source, dest, ifMatch)
        } catch (e: FolderError.NameConflict) {
            throw HttpError(409, "exists", "A file with that name is already there.")
        } catch (e: SkipError) {
            throw if (e.reason == "copy-file-missing") HttpError(404, "gone", "The file is already gone.") else HttpError(409, "exists", "A file with that name is already there.")
        }
        return JSONObject().put("revision", revision(dest))
    }

    override fun open(path: String): OpenedCopyFile {
        val file = abs(path)
        val st = folder.stat(file)?.takeUnless { it.isDirectory } ?: throw HttpError(404, "gone", "That file is not here.")
        return OpenedCopyFile(st.size, st.modifiedMs) { offset ->
            val input = folder.openRead(file)
            var left = offset
            while (left > 0) {
                val n = input.skip(left)
                if (n <= 0) {
                    if (input.read() < 0) break
                    left--
                } else left -= n
            }
            input
        }
    }

    /** Moves a file into `.keepfin/trash/net/` under a name that cannot collide; never deletes the only copy before the bytes are safe. */
    private fun preserve(file: String, expected: String?): String {
        val rnd = ByteArray(4).also { random.nextBytes(it) }
        val name = "${now()}-${hex(rnd)}-${leafName(file)}"
        try {
            Versions.moveVerified(folder, file, "$NET_TRASH/$name", expected)
        } catch (e: SkipError) {
            throw if (e.reason == "copy-file-missing") HttpError(404, "gone", "The file is already gone.") else HttpError(412, "changed", "The file was changed in the meantime.")
        }
        return name
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
}
