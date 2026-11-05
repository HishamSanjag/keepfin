package app.keepfin.copies

import app.keepfin.core.copies.Action
import app.keepfin.core.copies.ActionKind
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import java.io.File

internal class RecoveryReport {
    /** Actions that had an intent but no outcome. */
    var unresolved = 0
    var rolledForward = 0
    var rolledBack = 0

    /** Staging files removed. */
    var cleaned = 0

    /** Things recovery could not decide alone: nothing was touched. */
    val findings = ArrayList<String>()

    /** The runs the journal recorded, with the generation each one bumped the copy to. */
    val runs = ArrayList<Pair<String, Long>>()
}

private class Open(val action: Action, val tmp: String?, val dest: String?, var preserved: JournalRecord.Preserved? = null)

/**
 * Crash recovery (spec 5.1): reconciles the actual files against the journal. It only ever moves things back to a state the planner
 * understands, or forward when the bytes are proven; it never overwrites. Baseline entries are never invented: after recovery the next
 * plan sees the real files and converges (a copy that did publish is a "converge").
 */
internal object Recovery {
    fun recover(journalFile: File, copy: FolderAccess, main: FolderAccess): RecoveryReport {
        val report = RecoveryReport()
        val open = LinkedHashMap<String, Open>()
        val key = { run: String, n: Int -> "$run#$n" }
        val catalogued = HashMap<Store, CatalogueWriter>()
        val archived = LinkedHashMap<String, JournalRecord.Preserved>()
        val undone = HashSet<String>()
        for (r in Journal.records(journalFile)) {
            when (r) {
                is JournalRecord.Run -> report.runs.add(r.run to r.generation)
                is JournalRecord.Intent -> open[key(r.run, r.n)] = Open(r.action, r.tmp, r.dest)
                is JournalRecord.Preserved -> {
                    open[key(r.run, r.n)]?.preserved = r
                    if (!r.onMain) archived[key(r.run, r.n)] = r
                }
                is JournalRecord.Committed -> open.remove(key(r.run, r.n))
                is JournalRecord.Skipped -> { open.remove(key(r.run, r.n)); undone.add(key(r.run, r.n)) }
                is JournalRecord.Failed -> { open.remove(key(r.run, r.n)); undone.add(key(r.run, r.n)) }
                else -> {}
            }
        }
        // the journal is the durable record of an archive: a catalogue line the batched flush never reached is put back, for an archive that exists
        val liveIds = HashMap<Store, Set<String>>()
        for ((k, p) in archived) {
            if (k in undone || k in open) continue
            val live = liveIds.getOrPut(p.entry.store) { Versions.liveEntries(copy, p.entry.store).mapTo(HashSet()) { it.id } }
            if (p.entry.id !in live && copy.stat(p.archive) != null) catalogued.getOrPut(p.entry.store) { CatalogueWriter(copy, p.entry.store) }.append(p.entry)
        }
        report.unresolved = open.size
        for (o in open.values) {
            try {
                settle(o, copy, main, report, catalogued)
            } catch (e: FolderError) {
                if (awayOf(e) != null) throw DisconnectedError(awayOf(e)!!, "The copy is not reachable.", e)
                report.findings.add("${o.action.path}: ${e.message}")
            }
        }
        for (w in catalogued.values) w.flush()
        return report
    }

        private fun settle(o: Open, copy: FolderAccess, main: FolderAccess, report: RecoveryReport, catalogued: MutableMap<Store, CatalogueWriter>) {
        val a = o.action
        // where the staged file and the destination live: the copy, except for actions that write the main folder
        val onMain = a.kind == ActionKind.RestoreFromCopy || a.kind == ActionKind.ImportNew || a.kind == ActionKind.ReviseFromCopy || a.kind == ActionKind.RestoreEditedDeleted
        val work = if (onMain) main else copy
        val tmpExists = o.tmp != null && work.stat(o.tmp) != null
        val destExists = o.dest != null && work.stat(o.dest) != null
        val p = o.preserved
        if (p != null && !onMain) {
            val archiveExists = copy.stat(p.archive) != null
            if (archiveExists && !destExists) {
                // the displaced bytes are aside and nothing sits at the destination
                val staged = tmpExists && a.kind == ActionKind.CopyTo && a.contentId != null && o.dest != null && hashPath(copy, o.tmp!!).contentId == a.contentId
                if (staged) {
                    try {
                        copy.rename(o.tmp!!, leafName(o.dest!!))
                        keepEntry(copy, p, catalogued)
                        report.rolledForward++
                    } catch (e: FolderError.NameConflict) {
                        // something appeared meanwhile: leave both
                    }
                } else if (a.kind == ActionKind.RemoveFromCopy) {
                    keepEntry(copy, p, catalogued)
                    report.rolledForward++ // the removal completed: the bytes are in the trash
                } else if (o.dest != null) {
                    try {
                        copy.mkdirs(parentPath(o.dest))
                        Versions.moveVerified(copy, p.archive, o.dest, null)
                        voidEntry(copy, p, catalogued)
                        report.rolledBack++
                    } catch (e: SkipError) {
                        report.findings.add("${a.path}: the displaced bytes are in ${p.archive}")
                    } catch (e: FolderError.NameConflict) {
                        report.findings.add("${a.path}: the displaced bytes are in ${p.archive}")
                    }
                }
            } else if (archiveExists && destExists) {
                // the archive copy was made but the original was not removed yet (the displaced bytes are twice on the copy), or it was published already
                val destId = hashPath(copy, o.dest!!).contentId
                if (a.kind == ActionKind.CopyTo && a.contentId != null && destId == a.contentId) {
                    keepEntry(copy, p, catalogued)
                    report.rolledForward++ // published; the old bytes stay in the archive
                } else if (destId == p.entry.contentId) {
                    if (a.kind == ActionKind.RemoveFromCopy) {
                        copy.remove(o.dest)
                        keepEntry(copy, p, catalogued)
                        report.rolledForward++
                    } else {
                        copy.remove(p.archive) // the move never completed: the original is still where it was
                        voidEntry(copy, p, catalogued)
                        report.rolledBack++
                    }
                } else report.findings.add("${a.path}: both the archived bytes and a different file at the destination exist; nothing was touched")
            } else if (!archiveExists && destExists) {
                voidEntry(copy, p, catalogued) // the move never happened
                report.rolledBack++
            } else {
                report.findings.add("${a.path}: neither the archived bytes nor the destination exist")
            }
        } else if (!onMain && tmpExists && o.dest != null && !destExists && a.kind == ActionKind.CopyTo && a.contentId != null && hashPath(copy, o.tmp!!).contentId == a.contentId) {
            try {
                copy.rename(o.tmp!!, leafName(o.dest))
                report.rolledForward++
            } catch (e: FolderError.NameConflict) {
                // appeared meanwhile
            }
        }
        // a staging file is our own temporary: it never holds the only copy of anything
        if (o.tmp != null && work.stat(o.tmp) != null) {
            work.remove(o.tmp)
            report.cleaned++
        }
    }

    private fun voidEntry(copy: FolderAccess, p: JournalRecord.Preserved, catalogued: MutableMap<Store, CatalogueWriter>) {
        catalogued.getOrPut(p.entry.store) { CatalogueWriter(copy, p.entry.store) }.void(p.entry.id)
    }

    /** The archive stays: the catalogue must list it. */
    private fun keepEntry(copy: FolderAccess, p: JournalRecord.Preserved, catalogued: MutableMap<Store, CatalogueWriter>) {
        if (Versions.liveEntries(copy, p.entry.store).none { it.id == p.entry.id }) catalogued.getOrPut(p.entry.store) { CatalogueWriter(copy, p.entry.store) }.append(p.entry)
    }
    /** Keeps the finished journal as `journal.prev.jsonl` (one generation) so the next run starts clean. */
    fun archiveJournal(journalFile: File) {
        val prev = File(journalFile.parentFile, "journal.prev.jsonl")
        prev.delete()
        if (journalFile.isFile) journalFile.renameTo(prev)
    }
}
