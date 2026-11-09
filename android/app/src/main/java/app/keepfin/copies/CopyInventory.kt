package app.keepfin.copies

import app.keepfin.core.copies.InvLine
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import java.io.File

internal class InventoryResult(
    val file: File,
    val count: Int,
    /** Every folder was read. Anything else is principle 5: no destructive action for this place. */
    val complete: Boolean,
    /** Folders or files that could not be read. */
    val unreadable: List<String>,
    val startedAtMs: Long,
    /** Files only in the cloud (the provider says they have no bytes here): never listed as files, never read; the side is not fully known. */
    val onlineOnly: List<String> = emptyList(),
)

private val SKIPPED_ROOT_DIRS = setOf(".keepfin", "keepfin exports")

/** The sorted stream of (relativePath, size, mtimeMs) for one root (spec section 3), kept on disk. */
internal object CopyInventory {
    /** How long a listing that looks too small is given before it is listed again. */
    var relistDelayMs = 300L

    /**
     * Scans [access] into the sorted JSON-lines file [outFile] (ordered by the planner's `(name key, path)`). A root that cannot be
     * read at all is a disconnect. An empty or shrunken listing the provider distrusts is listed again; a folder that still
     * cannot be read with confidence makes the inventory incomplete.
     */
    fun scan(
        access: FolderAccess,
        outFile: File,
        rules: RulesId,
        tmpDir: File,
        names: NameMap? = null,
        filter: ((String) -> Boolean)? = null,
        abort: () -> Boolean = { false },
        chunk: Int = 50_000,
    ): InventoryResult {
        val startedAtMs = System.currentTimeMillis()
        val unreadable = ArrayList<String>()
        val onlineOnly = ArrayList<String>()
        val sorter = ExternalSorter(tmpDir, chunk, "inv")
        var seq = 0L
        val stack = ArrayList<String>().apply { add("") }
        if (access is StreamedInventory) {
            // a paired device sends its whole file list as one stream
            val streamed = try {
                access.scanAll { physical, size, mtime ->
                    if (abort()) throw SkipError("paused")
                    val parts = physical.split('/')
                    if (parts.size > 1 && parts[0].lowercase() in SKIPPED_ROOT_DIRS) return@scanAll
                    if (parts.any { it.isEmpty() } || skippedFile(parts.last())) return@scanAll
                    if (size < 0) {
                        unreadable.add(physical)
                        return@scanAll
                    }
                    val libPath = names?.library(physical) ?: physical
                    if (filter != null && !filter(libPath)) return@scanAll
                    sorter.add(SortRec(rules.key(libPath), libPath, seq++, Codec.inv(InvLine(libPath, size, mtime))))
                }
            } catch (e: FolderError) {
                val away = awayOf(e)
                if (away != null) throw DisconnectedError(away, "The copy is not reachable.", e)
                throw e
            }
            unreadable.addAll(streamed.unreadable)
            if (!streamed.complete) unreadable.add(".")
            stack.clear()
        }
        while (stack.isNotEmpty()) {
            if (abort()) throw SkipError("paused")
            val dir = stack.removeAt(stack.size - 1)
            val listing = try {
                var l = access.list(dir)
                var attempt = 0
                while (l.possiblyIncomplete && attempt < 2) {
                    attempt++
                    Thread.sleep(relistDelayMs)
                    l = access.list(dir)
                }
                if (l.possiblyIncomplete) {
                    unreadable.add(dir.ifEmpty { "." })
                    continue
                }
                l
            } catch (e: FolderError) {
                val away = awayOf(e)
                if (away != null) throw DisconnectedError(away, "The copy is not reachable.", e)
                if (dir.isEmpty()) throw DisconnectedError(Away.UNAVAILABLE, "The copy is not reachable.", e)
                unreadable.add(dir)
                continue
            }
            for (e in listing.entries) {
                if (e.isDirectory) {
                    if (dir.isEmpty() && e.name.lowercase() in SKIPPED_ROOT_DIRS) continue
                    stack.add(joinPath(dir, e.name))
                } else {
                    if (skippedFile(e.name)) continue
                    val physical = joinPath(dir, e.name)
                    if (e.onlineOnly) {
                        onlineOnly.add(names?.library(physical) ?: physical)
                        continue
                    }
                    if (e.size < 0) {
                        unreadable.add(physical)
                        continue
                    }
                    val libPath = names?.library(physical) ?: physical
                    if (filter != null && !filter(libPath)) continue
                    sorter.add(SortRec(rules.key(libPath), libPath, seq++, Codec.inv(InvLine(libPath, e.size, e.modifiedMs))))
                }
            }
        }
        val tmp = File(outFile.parentFile, outFile.name + ".tmp")
        outFile.parentFile?.mkdirs()
        var count = 0
        val writer = LineWriter(tmp)
        sorter.sorted().use { it.forEach { rec -> writer.write(rec.text); count++ } }
        writer.finish(false)
        if (!tmp.renameTo(outFile)) { outFile.delete(); tmp.renameTo(outFile) }
        val marker = File(outFile.parentFile, outFile.name + ".complete")
        val complete = unreadable.isEmpty() && onlineOnly.isEmpty()
        if (complete) marker.writeText(count.toString()) else marker.delete()
        return InventoryResult(outFile, count, complete, unreadable, startedAtMs, onlineOnly)
    }

    /**
     * Probes the name rule of the volume holding [access] once: `UNICODE` when `A` and `a` are one name to the provider (FAT, exFAT and NTFS
     * cards), `NFC` when they are different files. Either way a composed and a decomposed accent are one name. An unreachable probe is
     * treated conservatively as `UNICODE`.
     */
    fun probeRules(access: FolderAccess): RulesId {
        (access as? FixedRules)?.let { return it.fixedRules }
        val stamp = System.currentTimeMillis().toString(36)
        val upper = ".keepfin/kf-probe-$stamp-A.kftmp"
        val lower = ".keepfin/kf-probe-$stamp-a.kftmp"
        return try {
            access.mkdirs(".keepfin")
            access.publish(upper, ByteArray(0))
            try {
                access.publish(lower, ByteArray(0))
                RulesId.NFC
            } catch (e: FolderError.NameConflict) {
                RulesId.UNICODE
            }
        } catch (e: Exception) {
            RulesId.UNICODE
        } finally {
            runCatching { access.remove(upper) }
            runCatching { access.remove(lower) }
        }
    }
}
