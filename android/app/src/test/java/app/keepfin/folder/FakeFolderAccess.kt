package app.keepfin.folder

import java.io.InputStream
import java.security.MessageDigest

/** An in-memory [FolderAccess] with the failure modes the real provider has (an empty listing, a vanished volume). */
class FakeFolderAccess : FolderAccess {
    val files = LinkedHashMap<String, ByteArray>()
    private val dirs = HashSet<String>().apply { add("") }

    /** The modification time a directory reports (0, unknown, unless a test sets it). */
    val dirTimes = HashMap<String, Long>()

    /** The next [list] calls answer with an empty listing flagged incomplete. */
    @Volatile var emptyListings = false

    /** Files the provider says are only in the cloud (a virtual document): listed with [FolderEntry.onlineOnly]. */
    val onlineOnly = HashSet<String>()

    /** Added to the modified time every file reports (a time zone change on a card that keeps local time). */
    @Volatile var mtimeShiftMs = 0L

    /** Every call throws this. */
    @Volatile var failWith: FolderError? = null

    /** The next [publishFrom] leaves half of the bytes at the path and then fails (a provider that writes in place and dies midway). */
    @Volatile var tearNextPublish = false

    /** Reads of these paths fail with NotFound (a rename in flight). */
    val unreadable = HashSet<String>()

    private fun check() {
        failWith?.let { throw it }
    }

    override fun list(dir: String): FolderListing {
        check()
        if (dir !in dirs) throw FolderError.NotFound(dir)
        if (emptyListings) return FolderListing(emptyList(), true)
        val prefix = if (dir.isEmpty()) "" else "$dir/"
        val out = LinkedHashMap<String, FolderEntry>()
        for (d in dirs) if (d.startsWith(prefix) && d != dir) {
            val rest = d.substring(prefix.length)
            if (!rest.contains('/')) out[rest] = FolderEntry(rest, true, -1, dirTimes[d] ?: 0)
        }
        for ((p, b) in files) if (p.startsWith(prefix)) {
            val rest = p.substring(prefix.length)
            if (!rest.contains('/')) out[rest] = FolderEntry(rest, false, b.size.toLong(), mtimeShiftMs, p in onlineOnly)
        }
        return FolderListing(out.values.toList(), false)
    }

    override fun stat(path: String): FolderEntry? {
        check()
        if (path in dirs) return FolderEntry(nameOf(path), true, -1, dirTimes[path] ?: 0)
        return files[path]?.let { FolderEntry(nameOf(path), false, it.size.toLong(), mtimeShiftMs, path in onlineOnly) }
    }

    override fun read(path: String): ByteArray {
        check()
        if (path in unreadable) throw FolderError.NotFound(path)
        return files[path]?.copyOf() ?: throw FolderError.NotFound(path)
    }

    override fun openRead(path: String): InputStream = read(path).inputStream()

    override fun mkdirs(dir: String) {
        check()
        if (dir.isEmpty()) return
        var current = ""
        for (part in dir.split('/')) {
            current = join(current, part)
            dirs += current
        }
    }

    override fun publishFrom(path: String, source: InputStream, onProgress: ((Long) -> Unit)?): PublishResult {
        check()
        if (path in files || path in dirs) throw FolderError.NameConflict(path)
        mkdirs(parentOf(path))
        val bytes = source.readBytes()
        if (tearNextPublish) {
            tearNextPublish = false
            files[path] = bytes.copyOf(bytes.size / 2)
            throw FolderError.Io("The file could not be written.")
        }
        files[path] = bytes
        return PublishResult(bytes.size.toLong(), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    }

    override fun replace(path: String, bytes: ByteArray) {
        check()
        mkdirs(parentOf(path))
        files[path] = bytes.copyOf()
    }

    override fun remove(path: String) {
        check()
        files.remove(path)
        dirs.remove(path)
        files.keys.removeAll { it.startsWith("$path/") }
        dirs.removeAll { it.startsWith("$path/") }
    }

    override fun rename(path: String, newName: String) {
        check()
        val target = join(parentOf(path), newName)
        if (target in files && !target.equals(path, ignoreCase = true)) throw FolderError.NameConflict(target)
        val bytes = files.remove(path) ?: throw FolderError.NotFound(path)
        files[target] = bytes
    }

    override fun freeBytes(): Long? = null

    /** Flips a byte in a stored file (a damaged copy). */
    fun corrupt(path: String, at: Int) {
        val b = files.getValue(path).copyOf()
        b[at] = (b[at].toInt() xor 0x55).toByte()
        files[path] = b
    }

    fun segmentPaths(): List<String> = files.keys.filter { it.endsWith(".seg") }.sorted()
}
