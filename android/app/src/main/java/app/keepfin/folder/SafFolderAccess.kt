package app.keepfin.folder

import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.SyncFailedException
import java.security.MessageDigest
import java.util.UUID

/**
 * [FolderAccess] over one persisted SAF tree grant (`DocumentsContract` + `ContentResolver`, never `DocumentFile`).
 * Only the local external-storage provider is supported. Document ids are a cache keyed by relative path and are dropped
 * below any renamed or removed directory. All methods may be called from any thread (the cache is locked), but the store
 * calls them from its own thread.
 */
class SafFolderAccess private constructor(
    private val cr: ContentResolver,
    val tree: Uri,
    private val volumePathOverride: String?,
) : FolderAccess {
    private val rootId: String = DocumentsContract.getTreeDocumentId(tree)
    private val lock = Any()
    private val ids = HashMap<String, String>() // relative path -> document id
    private val baseline = HashMap<String, Int>() // directory -> child count of the last listing
    private var rootFlags = 0

    companion object {
        const val LOCAL_AUTHORITY = "com.android.externalstorage.documents"
        private const val CHUNK = 256 * 1024
        private const val SYNC_EVERY = 4L * 1024 * 1024
        private const val OCTET = "application/octet-stream"

        /**
         * Opens [tree]. Refuses providers other than the local one ([FolderError.UnsupportedProvider]), a tree without a
         * persisted read/write grant ([FolderError.NeedsAccess]) and a volume that is not there ([FolderError.Unavailable]).
         */
        fun open(cr: ContentResolver, tree: Uri, volumePath: String? = null): SafFolderAccess {
            val authority = tree.authority ?: ""
            if (authority != LOCAL_AUTHORITY) throw FolderError.UnsupportedProvider(authority)
            val granted = cr.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission }
            if (!granted) throw FolderError.NeedsAccess("The Keepfin folder needs access again.")
            val access = SafFolderAccess(cr, tree, volumePath)
            access.probeRoot()
            return access
        }
    }

    // ---------------------------------------------------------------- errors

    private fun mapError(e: Throwable, path: String): FolderError {
        if (e is FolderError) return e
        var t: Throwable? = e
        var noRoot = false
        while (t != null) {
            if (t.message?.contains("No root") == true) noRoot = true
            t = t.cause
        }
        return when {
            e is SecurityException -> FolderError.NeedsAccess("The Keepfin folder needs access again.", e)
            noRoot -> FolderError.Unavailable("The Keepfin folder is not available (card removed?).", e)
            e is SyncFailedException -> FolderError.DiskFull("There is not enough space left.", e)
            e is FileNotFoundException -> FolderError.NotFound(path, e)
            e is IllegalArgumentException -> FolderError.NotFound(path, e)
            e is IOException && (e.message?.contains("ENOSPC") == true || e.message?.contains("No space left") == true) ->
                FolderError.DiskFull("There is not enough space left.", e)
            else -> FolderError.Io(e.message ?: e.javaClass.simpleName, e)
        }
    }

    private inline fun <T> guard(path: String, block: () -> T): T =
        try {
            block()
        } catch (e: FolderError) {
            throw e
        } catch (e: Exception) {
            throw mapError(e, path)
        }

    // ---------------------------------------------------------------- ids

    private fun docUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private fun cached(path: String): String? = if (path.isEmpty()) rootId else synchronized(lock) { ids[path] }

    private fun putId(path: String, id: String) {
        if (path.isNotEmpty()) synchronized(lock) { ids[path] = id }
    }

    /** Drops [path] and everything below it (a renamed or removed directory changes every child's id). */
    private fun dropPrefix(path: String) {
        synchronized(lock) {
            ids.remove(path)
            val below = "$path/"
            ids.keys.removeAll { it.startsWith(below) }
        }
    }

    private class Row(val name: String, val id: String, val dir: Boolean, val size: Long, val modified: Long)

    private val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)

    private fun rowOf(c: android.database.Cursor) = Row(
        name = c.getString(1) ?: "",
        id = c.getString(0),
        dir = c.getString(2) == Document.MIME_TYPE_DIR,
        size = if (c.isNull(3)) -1L else c.getLong(3),
        modified = if (c.isNull(4)) 0L else c.getLong(4),
    )

    private fun queryChildren(dirId: String, dirPath: String): List<Row> {
        val out = ArrayList<Row>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirId)
        cr.query(uri, columns, null, null, null)?.use { c -> while (c.moveToNext()) out += rowOf(c) }
            ?: throw unlistable(dirPath)
        // refresh the id cache for this directory
        synchronized(lock) {
            val prefix = if (dirPath.isEmpty()) "" else "$dirPath/"
            val fresh = HashMap<String, String>()
            for (r in out) fresh[prefix + r.name] = r.id
            val stale = ids.entries.filter { (k, v) -> k.startsWith(prefix) && !k.substring(prefix.length).contains('/') && fresh[k] != v }.map { it.key }
            for (k in stale) {
                ids.remove(k)
                val below = "$k/"
                ids.keys.removeAll { it.startsWith(below) }
            }
            ids.putAll(fresh)
        }
        return out
    }

    private fun queryDoc(id: String): Row? {
        cr.query(docUri(id), columns, null, null, null)?.use { c -> if (c.moveToFirst()) return rowOf(c) }
        return null
    }

    /**
     * A listing the provider answered with no cursor at all. Right after a card is removed the provider can answer that way
     * instead of failing with "No root", so the root is asked again: gone means the folder is unavailable, never an empty folder.
     */
    private fun unlistable(dirPath: String): FolderError {
        val root = runCatching { cr.query(docUri(rootId), arrayOf(Document.COLUMN_FLAGS), null, null, null)?.use { it.moveToFirst() } }
        return if (root.getOrNull() != true) FolderError.Unavailable("The Keepfin folder is not available (card removed?).", root.exceptionOrNull())
        else FolderError.Io("The folder $dirPath could not be listed.")
    }

    /** The document id of [path], found by listing parents as needed; null when it does not exist. */
    private fun resolve(path: String): String? {
        cached(path)?.let { return it }
        val parentId = resolve(parentOf(path)) ?: return null
        queryChildren(parentId, parentOf(path))
        return cached(path)
    }

    private fun probeRoot() = guard("") {
        val row = cr.query(docUri(rootId), arrayOf(Document.COLUMN_FLAGS), null, null, null)?.use { c -> if (c.moveToFirst()) c.getInt(0) else null }
            ?: throw FolderError.Unavailable("The Keepfin folder is not available.")
        rootFlags = row
        if (row and Document.FLAG_DIR_SUPPORTS_CREATE == 0) throw FolderError.Io("This folder cannot be written to.")
    }

    // ---------------------------------------------------------------- reading

    override fun list(dir: String): FolderListing = guard(dir) {
        var id = resolve(dir) ?: throw FolderError.NotFound(dir)
        var rows = try {
            queryChildren(id, dir)
        } catch (e: Exception) {
            if (mapError(e, dir) !is FolderError.NotFound) throw e
            dropPrefix(dir)
            id = resolve(dir) ?: throw FolderError.NotFound(dir)
            queryChildren(id, dir)
        }
        val previous = synchronized(lock) { baseline[dir] }
        val suspicious = previous != null && previous > 0 && rows.size < previous
        if (rows.isEmpty() || suspicious) {
            // An unmounted volume lists as empty without an error; the root query tells it from an empty folder.
            probeRoot()
            val again = queryChildren(id, dir)
            if (again.size > rows.size) rows = again
        }
        synchronized(lock) { baseline[dir] = rows.size }
        val incomplete = previous != null && previous > 0 && rows.size < previous
        FolderListing(rows.map { FolderEntry(it.name, it.dir, it.size, it.modified) }, incomplete)
    }

    /**
     * The document URI of [path] for playback, built without any I/O. On the local external-storage provider (the only one
     * supported) a document id is the tree id plus the relative path, so a stale URI is re-built from the path, never stored as identity.
     */
    fun documentUriFor(path: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, if (path.isEmpty()) rootId else "$rootId/$path")

    override fun stat(path: String): FolderEntry? = guard(path) {
        if (path.isEmpty()) return@guard FolderEntry("", true, -1, 0)
        val id = resolve(path) ?: return@guard null
        val row = try {
            queryDoc(id)
        } catch (e: Exception) {
            val mapped = mapError(e, path)
            if (mapped is FolderError.NotFound) null else throw mapped
        }
        if (row == null) {
            dropPrefix(path)
            null
        } else FolderEntry(row.name, row.dir, row.size, row.modified)
    }

    override fun openRead(path: String): InputStream = guard(path) {
        var id = resolve(path) ?: throw FolderError.NotFound(path)
        try {
            cr.openInputStream(docUri(id)) ?: throw FolderError.NotFound(path)
        } catch (e: Exception) {
            val mapped = mapError(e, path)
            if (mapped !is FolderError.NotFound) throw mapped
            dropPrefix(path)
            id = resolve(path) ?: throw FolderError.NotFound(path)
            cr.openInputStream(docUri(id)) ?: throw FolderError.NotFound(path)
        }
    }

    override fun read(path: String): ByteArray = guard(path) { openRead(path).use { it.readBytes() } }

    // ---------------------------------------------------------------- writing

    /** Two workers creating the same folder at once make the provider fail the second `mkdir` (it exists by then); folder creation is serialised and the failure re-checked. */
    private val mkdirLock = Any()

    override fun mkdirs(dir: String) = guard(dir) {
        if (dir.isEmpty()) return@guard
        var current = ""
        for (part in dir.split('/')) {
            val next = join(current, part)
            if (resolve(next) == null) synchronized(mkdirLock) {
                if (resolve(next) != null) return@synchronized
                val parentId = resolve(current) ?: throw FolderError.NotFound(current)
                val created = try {
                    DocumentsContract.createDocument(cr, docUri(parentId), Document.MIME_TYPE_DIR, part)
                } catch (e: Exception) {
                    // it may have been made by someone else a moment ago
                    dropPrefix(next)
                    if (resolve(next) != null) null else throw e
                }
                if (created == null) {
                    dropPrefix(next)
                    if (resolve(next) == null) throw FolderError.Io("The folder $next could not be created.")
                    return@synchronized
                }
                val row = queryDoc(DocumentsContract.getDocumentId(created))
                if (row?.name != part) {
                    // a concurrent creator won; the provider made "name (1)": remove ours and use the existing one
                    runCatching { DocumentsContract.deleteDocument(cr, created) }
                    synchronized(lock) { dropPrefix(next) }
                    if (resolve(next) == null) throw FolderError.NameConflict(next)
                } else putId(next, row.id)
            }
            current = next
        }
    }

    private class Staged(val id: String, val uri: Uri, val name: String, val size: Long, val sha256: String)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Writes [source] to a new temporary file in [dir], fsyncing as it goes, and verifies it by reading it back. */
    private fun stage(dir: String, finalName: String, source: InputStream, onProgress: ((Long) -> Unit)?): Staged {
        val dirId = resolve(dir) ?: throw FolderError.NotFound(dir)
        val tempName = "$finalName.${UUID.randomUUID().toString().take(8)}.tmp"
        val created = DocumentsContract.createDocument(cr, docUri(dirId), OCTET, tempName)
            ?: throw FolderError.Io("A file could not be created in $dir.")
        val tempId = DocumentsContract.getDocumentId(created)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            var sinceSync = 0L
            cr.openFileDescriptor(created, "wt")?.use { pfd ->
                val fd = pfd.fileDescriptor
                // the descriptor is closed once, by pfd.use
                val out = FileOutputStream(fd)
                val buf = ByteArray(CHUNK)
                while (true) {
                    val n = source.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    total += n
                    sinceSync += n
                    if (sinceSync >= SYNC_EVERY) {
                        fd.sync()
                        sinceSync = 0
                    }
                    onProgress?.invoke(total)
                }
                out.flush()
                fd.sync()
            } ?: throw FolderError.Io("A file could not be opened for writing in $dir.")
            val expected = hex(digest.digest())
            // read back: the size and the hash must be what was written
            val check = MessageDigest.getInstance("SHA-256")
            var seen = 0L
            val input = cr.openInputStream(created) ?: throw FolderError.Io("The written file could not be read back.")
            input.use { ins ->
                val buf = ByteArray(CHUNK)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    check.update(buf, 0, n)
                    seen += n
                }
            }
            if (seen != total || hex(check.digest()) != expected) {
                throw FolderError.Io("The file was not saved completely (it did not read back the same).")
            }
            return Staged(tempId, created, tempName, total, expected)
        } catch (e: Throwable) {
            runCatching { DocumentsContract.deleteDocument(cr, created) }
            throw if (e is FolderError) e else mapError(e as Exception, join(dir, finalName))
        }
    }

    /** Renames a document and checks the name the provider returned. Returns the new id, or null when the name differs (the result is then undone). */
    private fun renameChecked(uri: Uri, requested: String): String? {
        val renamed = DocumentsContract.renameDocument(cr, uri, requested) ?: throw FolderError.Io("The file could not be renamed.")
        val newId = DocumentsContract.getDocumentId(renamed)
        val row = queryDoc(newId)
        return if (row?.name == requested) newId else null
    }

    override fun publishFrom(path: String, source: InputStream, onProgress: ((Long) -> Unit)?): PublishResult = guard(path) {
        val dir = parentOf(path)
        val name = nameOf(path)
        mkdirs(dir)
        if (stat(path) != null) throw FolderError.NameConflict(path)
        val staged = stage(dir, name, source, onProgress)
        try {
            val newId = renameChecked(staged.uri, name)
            if (newId == null) {
                // The provider picked another name ("x (1)"): the name was taken meanwhile. Remove our copy.
                synchronized(lock) { dropPrefix(path) }
                runCatching { deleteByName(dir, staged) }
                throw FolderError.NameConflict(path)
            }
            putId(path, newId)
            synchronized(lock) { ids.remove(join(dir, staged.name)) }
            PublishResult(staged.size, staged.sha256)
        } catch (e: Throwable) {
            if (e !is FolderError.NameConflict) runCatching { removeStaged(dir, staged) }
            throw if (e is FolderError) e else mapError(e as Exception, path)
        }
    }

    private fun removeStaged(dir: String, staged: Staged) {
        val exact = runCatching { DocumentsContract.deleteDocument(cr, docUri(staged.id)) }.getOrDefault(false)
        if (!exact) deleteByName(dir, staged)
    }

    /** After a refused rename the provider may have renamed our file to a different name; find a leftover by listing. */
    private fun deleteByName(dir: String, staged: Staged) {
        val dirId = resolve(dir) ?: return
        val stem = staged.name.removeSuffix(".tmp")
        for (r in queryChildren(dirId, dir)) {
            if (r.name == staged.name || r.name.startsWith(stem)) {
                if (r.size == staged.size || r.name == staged.name) runCatching { DocumentsContract.deleteDocument(cr, docUri(r.id)) }
            }
        }
    }

    override fun replace(path: String, bytes: ByteArray) { guard<Unit>(path) {
        val dir = parentOf(path)
        val name = nameOf(path)
        mkdirs(dir)
        if (stat(path) == null) {
            publish(path, bytes)
            return@guard
        }
        val staged = stage(dir, name, bytes.inputStream(), null)
        val asideName = "$name.${UUID.randomUUID().toString().take(8)}.old"
        val targetId = resolve(path) ?: run {
            // vanished meanwhile: just publish the staged file under its name
            val id = renameChecked(staged.uri, name) ?: throw FolderError.NameConflict(path)
            putId(path, id)
            return@guard
        }
        val asideId = renameChecked(docUri(targetId), asideName)
        if (asideId == null) {
            runCatching { removeStaged(dir, staged) }
            synchronized(lock) { dropPrefix(path) }
            throw FolderError.NameConflict(path)
        }
        synchronized(lock) { ids.remove(path) }
        val newId = try {
            renameChecked(staged.uri, name)
        } catch (e: Exception) {
            null
        }
        if (newId == null) {
            // put the old file back
            runCatching { renameChecked(docUri(asideId), name)?.let { putId(path, it) } }
            runCatching { removeStaged(dir, staged) }
            throw FolderError.NameConflict(path)
        }
        putId(path, newId)
        runCatching { DocumentsContract.deleteDocument(cr, docUri(asideId)) }
        synchronized(lock) { ids.remove(join(dir, staged.name)) }
    } }

    override fun remove(path: String) { guard<Unit>(path) {
        val id = resolve(path)
        if (id != null) {
            try {
                DocumentsContract.deleteDocument(cr, docUri(id))
            } catch (e: Exception) {
                if (mapError(e, path) !is FolderError.NotFound) throw e
            }
        }
        dropPrefix(path)
        synchronized(lock) {
            baseline.remove(parentOf(path))
            baseline.remove(path)
        }
    } }

    override fun rename(path: String, newName: String) { guard<Unit>(path) {
        val id = resolve(path) ?: throw FolderError.NotFound(path)
        val dir = parentOf(path)
        val target = join(dir, newName)
        val oldName = nameOf(path)
        if (oldName == newName) return@guard
        val caseOnly = oldName.equals(newName, ignoreCase = true)
        if (!caseOnly && stat(target) != null) throw FolderError.NameConflict(target)
        var current = docUri(id)
        var currentName = oldName
        if (caseOnly) {
            val temp = "$oldName.${UUID.randomUUID().toString().take(8)}.tmp"
            val tempId = renameChecked(current, temp) ?: throw FolderError.Io("The file could not be renamed.")
            dropPrefix(path)
            putId(join(dir, temp), tempId)
            current = docUri(tempId)
            currentName = temp
        }
        val newId = renameChecked(current, newName)
        if (newId == null) {
            // undo: the rename landed on a different name
            runCatching {
                val back = DocumentsContract.renameDocument(cr, current, currentName)
                if (back != null) {
                    dropPrefix(join(dir, currentName))
                    putId(path, DocumentsContract.getDocumentId(back))
                }
            }
            dropPrefix(path)
            dropPrefix(target)
            throw FolderError.NameConflict(target)
        }
        dropPrefix(path)
        if (currentName != oldName) dropPrefix(join(dir, currentName))
        dropPrefix(target)
        putId(target, newId)
        synchronized(lock) {
            baseline.remove(dir)
            baseline.remove(path)
        }
    } }

    /** The storage moves the document itself (one call, no bytes copied); false where it cannot, and the caller copies instead. */
    override fun moveInto(path: String, toDir: String): Boolean = guard(path) {
        if (android.os.Build.VERSION.SDK_INT < 24) return@guard false
        val id = resolve(path) ?: throw FolderError.NotFound(path)
        val dir = parentOf(path)
        val fromParent = resolve(dir) ?: return@guard false
        val toParent = resolve(toDir) ?: return@guard false
        val target = join(toDir, nameOf(path))
        if (stat(target) != null) return@guard false
        val moved = try { DocumentsContract.moveDocument(cr, docUri(id), docUri(fromParent), docUri(toParent)) } catch (e: Exception) { null } ?: return@guard false
        dropPrefix(path)
        dropPrefix(target)
        putId(target, DocumentsContract.getDocumentId(moved))
        synchronized(lock) {
            baseline.remove(dir)
            baseline.remove(toDir)
            baseline.remove(path)
        }
        true
    }

    // ---------------------------------------------------------------- space

    override fun freeBytes(): Long? {
        val path = volumePathOverride ?: run {
            val volume = rootId.substringBefore(':')
            if (volume == "primary") Environment.getExternalStorageDirectory().path else "/storage/$volume"
        }
        return runCatching { StatFs(path).availableBytes }.getOrNull()
    }
}
