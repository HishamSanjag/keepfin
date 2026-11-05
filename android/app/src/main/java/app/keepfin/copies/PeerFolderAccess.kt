package app.keepfin.copies

import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderEntry
import app.keepfin.folder.FolderError
import app.keepfin.folder.FolderListing
import app.keepfin.folder.PublishResult
import app.keepfin.net.PeerCopyError
import app.keepfin.net.PeerCopyTransport
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** A place whose whole file list is read as one stream (a paired device), instead of folder by folder. */
internal interface StreamedInventory {
    fun scanAll(onFile: (path: String, size: Long, mtimeMs: Long) -> Unit): StreamedScan
}

internal class StreamedScan(val complete: Boolean, val unreadable: List<String>)

/** A place whose name-equivalence rule is known without probing it (a device's disk is not ours to write probes on). */
internal interface FixedRules {
    val fixedRules: RulesId
}

/**
 * A copy on a paired device, as the copies engine sees any copy: a [FolderAccess]. Two kinds of path:
 *  - the copy's own `.keepfin` data (its folder.json, names, catalogues, manifests, versions index) lives in [meta], a folder on this phone.
 *    The other device's `.keepfin` is its own library data and is never touched;
 *  - everything else is the other device's media folder, reached over [transport]. The other device's own executor, under its own lock,
 *    makes every change and keeps what it displaces in its own trash (`.keepfin/trash/net`); this phone never writes there directly.
 *
 * Because the other side keeps what it displaces, "moving a file into the copy's versions or trash" is a note ("retired") plus a marker in
 * [meta]; the file itself changes the moment the replacement arrives (a replace, conditional on the content id) or, if nothing replaces it,
 * when the lock is released (a trash, conditional on the content id). A file whose content id this phone has not seen is never changed.
 * Files that are sent are staged in [staging] first (one file in flight per worker, removed after every action) so their SHA-256 is known
 * before they leave this phone. The lock is the other device's own lease: `.keepfin/lock` is answered from it.
 */
internal class PeerFolderAccess(
    private val transport: PeerCopyTransport,
    private val meta: FolderAccess,
    private val staging: File,
    private val now: () -> Long = System::currentTimeMillis,
) : FolderAccess, ServerSideMove, StreamedInventory, FixedRules {
    override val fixedRules: RulesId get() = RulesId.ASCII

    private class Retired(val contentId: String, val archive: String)

    private class Staged(val file: File, val size: Long, val contentId: String)

    /** Content ids this phone has seen, by path: what a conditional change is made against. */
    private val known = ConcurrentHashMap<String, String>()
    private val retired = ConcurrentHashMap<String, Retired>()
    private val staged = ConcurrentHashMap<String, Staged>()
    private val counter = AtomicLong()

    @Volatile private var lockBody: ByteArray? = null
    @Volatile private var holder: String? = null
    @Volatile private var generationAt = 0L
    @Volatile private var generation = 0L

    init {
        // files left by a run that died never held the only copy of anything
        staging.deleteRecursively()
        staging.mkdirs()
    }

    // ------------------------------------------------------------------------------------------------------------- helpers

    private fun isMeta(path: String): Boolean {
        val first = path.substringBefore('/').lowercase()
        return first == ".keepfin" || first == "keepfin exports"
    }

    private fun fe(e: PeerCopyError, path: String): FolderError = when (e.kind) {
        PeerCopyError.Kind.UNREACHABLE -> FolderError.Unavailable(e.message ?: "That device is not connected right now.", e)
        PeerCopyError.Kind.DENIED -> FolderError.NeedsAccess(e.message ?: "That device does not let this device keep a copy on it.", e)
        PeerCopyError.Kind.NOT_FOUND -> FolderError.NotFound(path, e)
        PeerCopyError.Kind.EXISTS -> FolderError.NameConflict(path)
        PeerCopyError.Kind.SPACE -> FolderError.DiskFull("There is not enough room on that device.", e)
        PeerCopyError.Kind.LOCKED -> FolderError.Unavailable("That device is busy with another copy.", e)
        PeerCopyError.Kind.CHANGED, PeerCopyError.Kind.HASH, PeerCopyError.Kind.OTHER -> FolderError.Io(e.message ?: "That device refused.", e)
    }

    /** Runs a request; a change that lost the race becomes a skipped action (the next plan decides again), anything else a typed failure. */
    private inline fun <T> guard(path: String, block: () -> T): T = try {
        block()
    } catch (e: PeerCopyError) {
        when (e.kind) {
            PeerCopyError.Kind.CHANGED -> throw SkipError("unexpected-destination", "$path changed on the other device since it was planned.")
            PeerCopyError.Kind.HASH -> throw SkipError("hash-mismatch", "The bytes that arrived do not match their content id.")
            else -> throw fe(e, path)
        }
    }

    private fun leaf(path: String) = leafName(path)

    private fun entryOf(path: String, size: Long, mtimeMs: Long) = FolderEntry(leaf(path), false, size, mtimeMs)

    private fun remember(path: String, id: String) {
        if (known.size > 50_000) known.clear()
        known[path] = id
    }

    /** The content id of a file on the other device: what was seen, else its bytes hashed here (the only way to be sure). */
    private fun contentIdOf(path: String, abort: () -> Boolean = { false }): String {
        known[path]?.let { return it }
        return hashStream(openRead(path), abort).contentId.also { remember(path, it) }
    }

    // ----------------------------------------------------------------------------------------------------------- inventory

    override fun scanAll(onFile: (path: String, size: Long, mtimeMs: Long) -> Unit): StreamedScan = guard("") {
        val r = transport.scan { path, size, mtime -> if (!retired.containsKey(path)) onFile(path, size, mtime) }
        StreamedScan(r.complete, r.unreadable)
    }

    // ------------------------------------------------------------------------------------------------------------- reading

    override fun list(dir: String): FolderListing {
        if (isMeta(dir)) return meta.list(dir)
        if (dir.isEmpty()) {
            // the run's first look: is the other device there, and does it let this one keep a copy on it? (the files are read as a stream, see [scanAll])
            guard("") { transport.readGeneration() }
            return FolderListing(emptyList(), false)
        }
        val prefix = "$dir/"
        val names = LinkedHashMap<String, FolderEntry>()
        val scan = scanAll { path, size, mtime ->
            if (path.startsWith(prefix)) {
                val rest = path.substring(prefix.length)
                val first = rest.substringBefore('/')
                names.putIfAbsent(first, if (rest.contains('/')) FolderEntry(first, true, -1, 0) else FolderEntry(first, false, size, mtime))
            }
        }
        return FolderListing(names.values.toList(), !scan.complete)
    }

    override fun stat(path: String): FolderEntry? {
        if (path == LOCK) return lockBody?.let { FolderEntry("lock", false, it.size.toLong(), now()) }
        if (isMeta(path)) return meta.stat(path)
        staged[path]?.let { return FolderEntry(leaf(path), false, it.size, it.file.lastModified()) }
        if (retired.containsKey(path)) return null
        return guard(path) { transport.stat(path)?.let { entryOf(path, it.size, it.mtimeMs) } }
    }

    override fun read(path: String): ByteArray {
        if (path == LOCK) return lockBody ?: holder?.let { JSONObject().put("device", it).put("run", "held-by-the-other-device").put("heartbeatAt", now()).toString().toByteArray(Charsets.UTF_8) } ?: throw FolderError.NotFound(path)
        if (path == FOLDER_JSON) return withPeerGeneration(meta.read(path))
        if (isMeta(path)) return meta.read(path)
        return openRead(path).use { it.readBytes() }
    }

    /** folder.json carries the generation; the other device's generation records may be higher (another device appended one). */
    private fun withPeerGeneration(bytes: ByteArray): ByteArray {
        val json = try { JSONObject(bytes.toString(Charsets.UTF_8).removePrefix("﻿")) } catch (e: Exception) { return bytes }
        val t = now()
        if (t - generationAt > 5_000) {
            generation = guard(FOLDER_JSON) { transport.readGeneration() }
            generationAt = t
        }
        return if (generation > json.optLong("generation", 0L)) json.put("generation", generation).toString(2).toByteArray(Charsets.UTF_8) else bytes
    }

    override fun openRead(path: String): InputStream {
        if (isMeta(path)) return meta.openRead(path)
        staged[path]?.let { return it.file.inputStream() }
        if (retired.containsKey(path)) throw FolderError.NotFound(path)
        val raw = guard(path) { transport.openRead(path) }
        // reading a whole file teaches its content id: a change made right after (a move, a trash) is conditional on it
        val digest = MessageDigest.getInstance("SHA-256")
        return object : InputStream() {
            private var done = false

            private fun fed(n: Int, b: ByteArray?, off: Int): Int {
                if (n < 0) {
                    if (!done) {
                        done = true
                        remember(path, CONTENT_PREFIX + hexOf(digest.digest()))
                    }
                } else if (b != null) digest.update(b, off, n)
                return n
            }

            override fun read(): Int {
                val one = ByteArray(1)
                while (true) {
                    val n = read(one, 0, 1)
                    if (n < 0) return -1
                    if (n > 0) return one[0].toInt() and 0xff
                }
            }

            override fun read(b: ByteArray, off: Int, len: Int): Int = try {
                fed(raw.read(b, off, len), b, off)
            } catch (e: PeerCopyError) {
                throw fe(e, path)
            }

            override fun close() = raw.close()
        }
    }

    // ------------------------------------------------------------------------------------------------------------- writing

    override fun mkdirs(dir: String) {
        if (dir.isNotEmpty() && isMeta(dir)) meta.mkdirs(dir) // the other device makes the folders its files need
    }

    override fun publish(path: String, bytes: ByteArray): PublishResult {
        if (path == LOCK) return acquire(bytes)
        return publishFrom(path, bytes.inputStream(), null)
    }

    override fun publishFrom(path: String, source: InputStream, onProgress: ((Long) -> Unit)?): PublishResult {
        if (isMeta(path)) return meta.publishFrom(path, source, onProgress)
        if (staged.containsKey(path)) throw FolderError.NameConflict(path)
        staging.mkdirs()
        val file = File(staging, "s-${counter.incrementAndGet()}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            file.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    size += n
                    onProgress?.invoke(size)
                }
            }
        } catch (e: java.io.IOException) {
            file.delete()
            throw if (e.message?.contains("ENOSPC") == true) FolderError.DiskFull("This phone is full.", e) else FolderError.Io(e.message ?: "The file could not be staged.", e)
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        val id = CONTENT_PREFIX + hexOf(digest.digest())
        staged[path] = Staged(file, size, id)
        if (!path.contains(".kftmp")) {
            // a file published straight to its name goes at once
            try {
                send(path, null)
            } finally {
                staged.remove(path)?.file?.delete()
            }
        }
        return PublishResult(size, id.removePrefix(CONTENT_PREFIX))
    }

    /** Sends the staged file at [staged] under [path] to the other device: a new file, or a replacement of exactly [ifMatch]. */
    private fun send(path: String, ifMatch: String?, finalPath: String = path) {
        val s = staged[path] ?: throw FolderError.NotFound(path)
        guard(finalPath) { s.file.inputStream().use { transport.put(finalPath, it, s.size, s.contentId, ifMatch) } }
        remember(finalPath, s.contentId)
    }

    override fun replace(path: String, bytes: ByteArray) {
        if (path == LOCK) {
            guard(path) { transport.heartbeat() }
            return
        }
        if (isMeta(path)) {
            meta.replace(path, bytes)
            if (path == FOLDER_JSON && transport.holdsLock) {
                // the generation is also an append-only record on the other device (the desktop reads those)
                runCatching {
                    val json = JSONObject(bytes.toString(Charsets.UTF_8))
                    val g = json.optLong("generation", 0L)
                    val replica = json.optString("replicaId")
                    if (g > 0 && replica.isNotEmpty() && transport.appendGeneration(g, replica)) {
                        generation = g
                        generationAt = now()
                    }
                }
            }
            return
        }
        val id = sha256Bytes(bytes)
        val had = guard(path) { transport.stat(path) } != null
        val tmp = "$path.kftmp-bytes-${counter.incrementAndGet()}"
        publishFrom(tmp, bytes.inputStream(), null)
        try {
            send(tmp, if (had) contentIdOf(path) else null, path)
        } finally {
            staged.remove(tmp)?.file?.delete()
        }
        remember(path, id)
    }

    private fun sha256Bytes(bytes: ByteArray): String = CONTENT_PREFIX + hexOf(MessageDigest.getInstance("SHA-256").digest(bytes))

    override fun remove(path: String) {
        if (path == LOCK) {
            release()
            return
        }
        if (isMeta(path)) {
            meta.remove(path)
            return
        }
        staged.remove(path)?.let {
            it.file.delete()
            return
        }
        if (retired.remove(path) != null) return
        // a file the copy no longer wants: the other device moves it to its trash, only while it still has the content seen
        val id = try { contentIdOf(path) } catch (e: FolderError.NotFound) { return }
        try {
            guard(path) { transport.trash(path, id) }
        } catch (e: FolderError.NotFound) {
            return
        }
        known.remove(path)
    }

    override fun rename(path: String, newName: String) {
        if (path == LOCK) throw FolderError.NotFound(path) // a lease is never taken over: the other device ends it itself
        if (isMeta(path)) {
            meta.rename(path, newName)
            return
        }
        val target = if (parentPath(path).isEmpty()) newName else parentPath(path) + "/" + newName
        val s = staged[path]
        if (s != null) {
            // the staged file is now put at its name: a replacement of the content that was retired there, else a new file
            val old = retired[target]
            send(path, old?.contentId, target)
            retired.remove(target)
            staged.remove(path)
            s.file.delete()
            return
        }
        val id = contentIdOf(path)
        try {
            guard(path) { transport.move(path, target, id) }
        } catch (e: FolderError.NotFound) {
            throw FolderError.NotFound(path)
        }
        known.remove(path)
        remember(target, id)
    }

    override fun freeBytes(): Long? = null

    // ------------------------------------------------------------------------------------------------ server-side moves

    override fun moveVerified(from: String, to: String, expected: String?, abort: () -> Boolean): String {
        val fromMeta = isMeta(from)
        val toMeta = isMeta(to)
        if (fromMeta && toMeta) return Versions.moveVerified(meta, from, to, expected, abort)
        if (fromMeta) {
            // the displaced bytes are "put back": nothing left the other device, so the note is dropped
            val entry = retired.entries.firstOrNull { it.value.archive == from && it.key == to } ?: throw SkipError("copy-file-missing", "$from is no longer there.")
            retired.remove(entry.key)
            runCatching { meta.remove(from) }
            return entry.value.contentId
        }
        if (toMeta) {
            // preserve: the other device keeps what it displaces, so the file stays until its replacement arrives (a replace) or the lock is released (a trash)
            if (meta.stat(to) != null) throw SkipError("busy", "The archive name was taken.")
            if (transport.holdsLock.not()) throw SkipError("copy-file-missing", "The copy is not locked.")
            val st = guard(from) { transport.stat(from) } ?: throw SkipError("copy-file-missing", "$from is no longer there.")
            val id = expected ?: known[from] ?: try { contentIdOf(from, abort) } catch (e: FolderError.NotFound) { throw SkipError("copy-file-missing", "$from is no longer there.") }
            if (st.size < 0) throw SkipError("unexpected-destination")
            meta.mkdirs(parentPath(to))
            try {
                meta.publish(to, JSONObject().put("note", "kept by the other device").put("path", from).put("contentId", id).toString().toByteArray(Charsets.UTF_8))
            } catch (e: FolderError.NameConflict) {
                throw SkipError("busy", "The archive name was taken.")
            }
            retired[from] = Retired(id, to)
            remember(from, id)
            return id
        }
        // one place to another on the other device
        if (guard(to) { transport.stat(to) } != null) throw SkipError("busy", "The archive name was taken.")
        val id = expected ?: try { contentIdOf(from, abort) } catch (e: FolderError.NotFound) { throw SkipError("copy-file-missing", "$from is no longer there.") }
        try {
            guard(from) { transport.move(from, to, id) }
        } catch (e: FolderError.NotFound) {
            throw SkipError("copy-file-missing", "$from is no longer there.")
        } catch (e: FolderError.NameConflict) {
            throw SkipError("busy", "The archive name was taken.")
        }
        known.remove(from)
        remember(to, id)
        return id
    }

    // ------------------------------------------------------------------------------------------------------------- the lock

    private fun acquire(body: ByteArray): PublishResult {
        val run = try { JSONObject(body.toString(Charsets.UTF_8)).optString("run") } catch (e: Exception) { "" }
        val busy = guard(LOCK) { transport.lock(run) }
        if (busy != null) {
            holder = busy
            throw FolderError.NameConflict(LOCK)
        }
        holder = null
        lockBody = body
        return PublishResult(body.size.toLong(), "")
    }

    /** Settles what the run retired (the other device trashes it), then gives the lock back. */
    private fun release() {
        try {
            settleRetired()
        } finally {
            lockBody = null
            transport.unlock()
        }
    }

    /** A file the copy no longer wants and nothing replaced: the other device moves it to its trash, conditional on the content id seen. */
    private fun settleRetired() {
        for ((path, r) in retired.entries.toList()) {
            try {
                transport.trash(path, r.contentId)
                retired.remove(path)
                known.remove(path)
            } catch (e: PeerCopyError) {
                // gone already, or edited meanwhile (it stays, and the next plan sees it as it is); an unreachable device is tried again next time
                if (e.kind == PeerCopyError.Kind.NOT_FOUND || e.kind == PeerCopyError.Kind.CHANGED) retired.remove(path)
            }
        }
    }

    companion object {
        const val LOCK = ".keepfin/lock"
        const val FOLDER_JSON = ".keepfin/folder.json"
    }
}
