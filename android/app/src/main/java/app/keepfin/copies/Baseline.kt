package app.keepfin.copies

import app.keepfin.core.copies.BaselineEntry
import java.io.File

/**
 * The baseline L of one relationship (spec 4.1): a sorted JSON-lines file of [BaselineEntry]. Commits are journaled per action;
 * folding them into the file is a streaming merge, so neither the baseline nor the commits are held in memory.
 */
internal object Baseline {
    fun file(stateDir: File) = File(stateDir, "baseline.jsonl")

    /**
     * Applies the `committed` records of [journalFile] to the baseline (atomically replacing it). [refresh] may adjust the entries that
     * survive untouched (used to record a fresh stat for a file whose hash proved it unchanged). Applying the same journal twice gives
     * the same file.
     */
    fun foldCommits(stateDir: File, journalFile: File, rules: RulesId, tmpDir: File, refresh: ((BaselineEntry) -> BaselineEntry)? = null, chunk: Int = 50_000): Int {
        val sorter = ExternalSorter(tmpDir, chunk, "fold")
        var seq = 0L
        for (rec in Journal.records(journalFile)) {
            if (rec !is JournalRecord.Committed) continue
            for (op in rec.ops) sorter.add(SortRec(rules.key(op.path), op.path, seq++, op.entry?.let { Codec.baseline(it) } ?: "null"))
        }
        val file = file(stateDir)
        stateDir.mkdirs()
        val tmp = File(stateDir, "baseline.jsonl.${java.util.UUID.randomUUID().toString().take(6)}.tmp")
        val writer = LineWriter(tmp)
        val base = LineReader(file)
        var applied = 0

        class Keyed(val key: String, val entry: BaselineEntry)

        fun nextBase(): Keyed? {
            if (!base.hasNext()) return null
            val e = Codec.baselineOf(base.next())
            return Keyed(rules.key(e.path), e)
        }
        try {
            var b = nextBase()
            sorter.sorted().use { updates ->
                var u: SortRec? = if (updates.hasNext()) updates.next() else null
                while (b != null || u != null) {
                    val bk = b?.key
                    val uk = u?.key
                    if (uk == null || (bk != null && bk < uk)) {
                        val e = b!!.entry
                        writer.write(Codec.baseline(if (refresh != null) refresh(e) else e))
                        b = nextBase()
                        continue
                    }
                    // an update group at key uk (and possibly the baseline entry with the same key)
                    val group = ArrayList<SortRec>()
                    while (u != null && u.key == uk) {
                        group.add(u)
                        u = if (updates.hasNext()) updates.next() else null
                    }
                    group.sortBy { it.seq }
                    var current: BaselineEntry? = null
                    if (b != null && bk == uk) {
                        current = b.entry
                        b = nextBase()
                        while (b != null && b.key == uk) b = nextBase() // one entry per name key
                    }
                    for (g in group) {
                        current = if (g.text == "null") null else Codec.baselineOf(g.text)
                        applied++
                    }
                    if (current != null) writer.write(Codec.baseline(current))
                }
            }
            writer.finish(true)
        } catch (e: Throwable) {
            writer.close()
            tmp.delete()
            throw e
        } finally {
            base.close()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("The baseline could not be replaced.")
        }
        return applied
    }

    /**
     * Sorts the baseline file again under [rules] (the name rule changed since it was written). Entries are kept as they are; two that
     * now share a name key stay side by side, and the planner reports them as a name collision instead of merging them.
     */
    fun resort(stateDir: File, rules: RulesId, tmpDir: File, chunk: Int = 50_000): Int {
        val file = file(stateDir)
        if (!file.isFile) return 0
        val sorter = ExternalSorter(tmpDir, chunk, "resort")
        var seq = 0L
        val r = LineReader(file)
        try {
            while (r.hasNext()) {
                val line = r.next()
                val e = Codec.baselineOf(line)
                sorter.add(SortRec(rules.key(e.path), e.path, seq++, line))
            }
        } finally {
            r.close()
        }
        val tmp = File(stateDir, "baseline.jsonl.${java.util.UUID.randomUUID().toString().take(6)}.resort")
        val writer = LineWriter(tmp)
        try {
            sorter.sorted().use { it.forEach { rec -> writer.write(rec.text) } }
            writer.finish(true)
        } catch (e: Throwable) {
            writer.close()
            tmp.delete()
            throw e
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw java.io.IOException("The baseline could not be replaced.")
        }
        return seq.toInt()
    }

    /** Streams the baseline entries. */
    fun read(stateDir: File): Sequence<BaselineEntry> = sequence {
        val r = LineReader(file(stateDir))
        try {
            while (r.hasNext()) yield(Codec.baselineOf(r.next()))
        } finally {
            r.close()
        }
    }
}
