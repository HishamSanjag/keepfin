package app.keepfin.move

import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest

/** A stop was asked for: the run ends between two reads, with everything copied so far kept. */
class MoveStopped : RuntimeException("The move was stopped.")

/** True when [e] is a stop that a provider call wrapped on its way out. */
fun isStop(e: Throwable): Boolean {
    var t: Throwable? = e
    while (t != null) { if (t is MoveStopped) return true; t = t.cause }
    return false
}

/** One file or folder of the old place: [size] is -1 for a folder. */
class MoveEntry(val rel: String, val size: Long, val modifiedMs: Long, val isDir: Boolean)

/**
 * The copying and checking of one move, over two [FolderAccess]es (the old and the new place). It never changes the old place. Every file is
 * written under a temporary name, read back and only then given its name (`publishFrom`), so a stop or a crash leaves whole files or none, and
 * a second run copies only what is missing. [cutoff] is when the move began (media changed after that are copied again).
 */
class FolderMover(
    private val from: FolderAccess,
    private val to: FolderAccess,
    private val cutoff: Long,
    private val stopped: () -> Boolean,
    private val progress: (MoveProgress) -> Unit,
) {
    /** Everything in the old place, folders first within each folder. A listing the provider doubts is asked again; a doubt that stays is an error. */
    fun inventory(): List<MoveEntry> {
        val out = ArrayList<MoveEntry>()
        fun walk(dir: String) {
            if (stopped()) throw MoveStopped()
            var listing = from.list(dir)
            repeat(2) { if (listing.possiblyIncomplete) listing = from.list(dir) }
            if (listing.possiblyIncomplete) throw FolderError.Io("Keepfin could not read the whole folder just now. Try again in a moment.")
            for (e in listing.entries.sortedBy { !it.isDirectory }) {
                val rel = if (dir.isEmpty()) e.name else "$dir/${e.name}"
                out += MoveEntry(rel, if (e.isDirectory) -1 else e.size, e.modifiedMs, e.isDirectory)
                if (e.isDirectory) walk(rel)
            }
        }
        walk("")
        return out
    }

    /**
     * Copies what the new place does not hold yet. [records] false leaves the library's own files for the last pass. [onCreated] is told the top-level
     * name of the first thing made under it (for a clean stop). Progress counts the files of this pass.
     */
    fun copy(entries: List<MoveEntry>, records: Boolean, phase: String, onCreated: (String) -> Unit) {
        val todo = entries.filter { records || !MovePlanning.isRecord(it.rel) }
        val files = todo.filter { !it.isDir }
        val total = files.sumOf { it.size.coerceAtLeast(0) }
        var bytesDone = 0L
        var done = 0
        for (e in todo) {
            if (stopped()) throw MoveStopped()
            if (e.isDir) {
                if (to.stat(e.rel) == null) { onCreated(MovePlanning.topOf(e.rel)); to.mkdirs(e.rel) }
                continue
            }
            progress(MoveProgress(phase, done, files.size, bytesDone, total, MovePlanning.shownName(e.rel)))
            val there = to.stat(e.rel)
            if (there != null && there.isDirectory) throw FolderError.Io("${e.rel} is a folder in the new place.")
            if (there == null || !sameAlready(e, there.size)) {
                onCreated(MovePlanning.topOf(e.rel))
                if (there != null) to.remove(e.rel)
                copyOne(e) { bytes -> progress(MoveProgress(phase, done, files.size, bytesDone + bytes, total, MovePlanning.shownName(e.rel))) }
            }
            bytesDone += e.size.coerceAtLeast(0)
            done++
        }
        progress(MoveProgress(phase, done, files.size, bytesDone, total, null))
    }

    /**
     * A run that was killed mid-file leaves the staged name of that file (`name.1a2b3c4d.tmp`) in the new place. Whatever the last listing of the old
     * place does not hold and looks like one is removed (the folder never publishes a name like that as a result).
     */
    fun clearLeftovers(entries: List<MoveEntry>) {
        val known = entries.mapTo(HashSet()) { it.rel }
        val dirs = entries.filter { it.isDir }.map { it.rel } + ""
        for (dir in dirs) {
            val listing = try { to.list(dir) } catch (e: FolderError.NotFound) { continue }
            for (e in listing.entries) {
                val rel = if (dir.isEmpty()) e.name else "$dir/${e.name}"
                if (!e.isDirectory && rel !in known && STAGED.containsMatchIn(e.name)) to.remove(rel)
            }
        }
    }

    private fun sameAlready(e: MoveEntry, thereSize: Long): Boolean {
        if (!MovePlanning.alreadyCopied(e.rel, e.size, e.modifiedMs, thereSize, cutoff)) {
            // a mutable record of the same size is equal only when its bytes are
            if (MovePlanning.isMutableRecord(e.rel) && thereSize == e.size) return sameBytes(e.rel)
            return false
        }
        return true
    }

    private fun sameBytes(rel: String): Boolean = from.read(rel).contentEquals(to.read(rel))

    private fun copyOne(e: MoveEntry, onBytes: (Long) -> Unit) {
        val source = object : FilterInputStream(from.openRead(e.rel)) {
            override fun read(): Int { if (stopped()) throw MoveStopped(); return super.read() }
            override fun read(b: ByteArray, off: Int, len: Int): Int { if (stopped()) throw MoveStopped(); return super.read(b, off, len) }
        }
        try {
            source.use { to.publishFrom(e.rel, it, onBytes) }
        } catch (err: FolderError) {
            if (isStop(err)) throw MoveStopped()
            throw err
        }
    }

    /**
     * Compares the new place with [entries] (a fresh listing of the old place): every file there with the same size (a library record that is
     * rewritten in place: the same bytes) and every folder there. Returns a plain sentence about the first problems, or null when all match.
     */
    fun verify(entries: List<MoveEntry>): String? {
        val bad = ArrayList<String>()
        for (e in entries) {
            if (stopped()) throw MoveStopped()
            val there = to.stat(e.rel)
            val ok = when {
                there == null -> false
                e.isDir -> there.isDirectory
                there.isDirectory -> false
                MovePlanning.existenceOnly(e.rel) -> true
                there.size != e.size -> false
                MovePlanning.isMutableRecord(e.rel) -> sameBytes(e.rel)
                else -> true
            }
            if (!ok) bad += MovePlanning.shownName(e.rel)
        }
        if (bad.isEmpty()) return null
        return "${bad.size} ${if (bad.size == 1) "file was" else "files were"} not copied correctly (${bad.take(3).joinToString(", ")}). Your Keepfin folder is still where it was."
    }

    companion object {
        private val STAGED = Regex("\\.[0-9a-f]{8}\\.tmp$")

        /** SHA-256 of [input], for tests that compare a copy with its original. */
        fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
