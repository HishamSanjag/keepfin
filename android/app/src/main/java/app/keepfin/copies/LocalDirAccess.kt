package app.keepfin.copies

import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderEntry
import app.keepfin.folder.FolderError
import app.keepfin.folder.FolderListing
import app.keepfin.folder.PublishResult
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * A [FolderAccess] over a plain directory of this phone's own storage. A copy on a paired device keeps its own `.keepfin` data (its
 * folder.json, names, catalogues, manifests) here: the other device's `.keepfin` is its own library data and is never touched.
 * Nothing is overwritten in place: a new file is staged under a temporary name, synced, and renamed.
 */
internal class LocalDirAccess(private val root: File) : FolderAccess {
    init {
        root.mkdirs()
    }

    private fun file(path: String): File = if (path.isEmpty()) root else File(root, path)

    private fun entry(f: File) = FolderEntry(f.name, f.isDirectory, if (f.isDirectory) -1 else f.length(), f.lastModified())

    override fun list(dir: String): FolderListing {
        val d = file(dir)
        if (!d.isDirectory) throw FolderError.NotFound(dir)
        return FolderListing(d.listFiles().orEmpty().map { entry(it) }, false)
    }

    override fun stat(path: String): FolderEntry? = file(path).takeIf { it.exists() }?.let { entry(it) }

    override fun read(path: String): ByteArray = try {
        file(path).readBytes()
    } catch (e: IOException) {
        throw FolderError.NotFound(path, e)
    }

    override fun openRead(path: String): InputStream = try {
        file(path).inputStream()
    } catch (e: IOException) {
        throw FolderError.NotFound(path, e)
    }

    override fun mkdirs(dir: String) {
        if (dir.isNotEmpty()) file(dir).mkdirs()
    }

    override fun publishFrom(path: String, source: InputStream, onProgress: ((Long) -> Unit)?): PublishResult {
        val target = file(path)
        if (target.exists()) throw FolderError.NameConflict(path)
        target.parentFile?.mkdirs()
        val staged = File(target.parentFile, target.name + ".kftmp-" + java.lang.Long.toString(System.nanoTime(), 36))
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            staged.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = source.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    size += n
                    onProgress?.invoke(size)
                }
                out.fd.sync()
            }
            if (target.exists()) throw FolderError.NameConflict(path)
            if (!staged.renameTo(target)) throw FolderError.Io("The file could not be put in place: $path")
        } catch (e: IOException) {
            throw if (e.message?.contains("ENOSPC") == true) FolderError.DiskFull("This phone is full.", e) else FolderError.Io(e.message ?: "The file could not be written.", e)
        } finally {
            if (staged.exists()) staged.delete()
        }
        return PublishResult(size, hexOf(digest.digest()))
    }

    override fun replace(path: String, bytes: ByteArray) {
        val target = file(path)
        target.parentFile?.mkdirs()
        val staged = File(target.parentFile, target.name + ".kftmp-" + java.lang.Long.toString(System.nanoTime(), 36))
        try {
            staged.outputStream().use { it.write(bytes); it.fd.sync() }
            try {
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(staged.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            throw FolderError.Io(e.message ?: "The file could not be written.", e)
        } finally {
            if (staged.exists()) staged.delete()
        }
    }

    override fun remove(path: String) {
        file(path).deleteRecursively()
    }

    override fun rename(path: String, newName: String) {
        val from = file(path)
        if (!from.exists()) throw FolderError.NotFound(path)
        val to = File(from.parentFile, newName)
        if (to.exists() && !to.canonicalFile.equals(from.canonicalFile)) throw FolderError.NameConflict(newName)
        if (!from.renameTo(to)) throw FolderError.Io("The file could not be renamed: $path")
    }

    override fun freeBytes(): Long? = root.usableSpace
}
