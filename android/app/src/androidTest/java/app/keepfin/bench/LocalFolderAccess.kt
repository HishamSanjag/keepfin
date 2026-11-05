package app.keepfin.bench

import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderEntry
import app.keepfin.folder.FolderError
import app.keepfin.folder.FolderListing
import app.keepfin.folder.PublishResult
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * A [FolderAccess] over plain files, for the scale run: it measures the merge state, the index and the lists, not the storage
 * provider (the provider's speed is in the phase 2 probes, section 6 of KEEPFIN-PHASE0-RESULTS.md).
 */
class LocalFolderAccess(private val root: File) : FolderAccess {
    init { root.mkdirs() }

    private fun file(path: String) = if (path.isEmpty()) root else File(root, path)

    override fun list(dir: String): FolderListing {
        val d = file(dir)
        if (!d.isDirectory) throw FolderError.NotFound(dir)
        val entries = d.listFiles().orEmpty().map { FolderEntry(it.name, it.isDirectory, if (it.isDirectory) -1 else it.length(), it.lastModified()) }
        return FolderListing(entries, false)
    }

    override fun stat(path: String): FolderEntry? = file(path).takeIf { it.exists() }?.let { FolderEntry(it.name, it.isDirectory, if (it.isDirectory) -1 else it.length(), it.lastModified()) }

    override fun read(path: String): ByteArray = try { file(path).readBytes() } catch (e: java.io.IOException) { throw FolderError.NotFound(path, e) }

    override fun openRead(path: String): InputStream = try { file(path).inputStream() } catch (e: java.io.IOException) { throw FolderError.NotFound(path, e) }

    override fun mkdirs(dir: String) { file(dir).mkdirs() }

    override fun publishFrom(path: String, source: InputStream, onProgress: ((Long) -> Unit)?): PublishResult {
        val target = file(path)
        if (target.exists()) throw FolderError.NameConflict(path)
        target.parentFile?.mkdirs()
        val staged = File(target.parentFile, target.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        staged.outputStream().use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = source.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n); digest.update(buffer, 0, n); size += n
            }
            out.fd.sync()
        }
        if (!staged.renameTo(target)) throw FolderError.Io("rename failed for $path")
        return PublishResult(size, digest.digest().joinToString("") { "%02x".format(it) })
    }

    override fun replace(path: String, bytes: ByteArray) {
        val target = file(path)
        target.parentFile?.mkdirs()
        val staged = File(target.parentFile, target.name + ".new")
        staged.writeBytes(bytes)
        target.delete()
        staged.renameTo(target)
    }

    override fun remove(path: String) { file(path).deleteRecursively() }

    override fun rename(path: String, newName: String) {
        val from = file(path)
        val to = File(from.parentFile, newName)
        if (to.exists() && !to.path.equals(from.path, ignoreCase = true)) throw FolderError.NameConflict(newName)
        if (!from.exists()) throw FolderError.NotFound(path)
        from.renameTo(to)
    }

    override fun freeBytes(): Long? = root.usableSpace
}
