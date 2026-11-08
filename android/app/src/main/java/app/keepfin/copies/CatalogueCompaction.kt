package app.keepfin.copies

import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import org.json.JSONObject

/**
 * Compacts a copy's append-only catalogues (`.keepfin/versions/catalogue.jsonl`, `.keepfin/trash/index.jsonl`) once they pass about 2,000
 * lines (dev-docs/plans/KEEPFIN-DATA-COMPACTION.md; mirrors gui/desktop/copies/catalogueCompact.ts). Voided and expired ("gone") entries and
 * the lines that void them are dropped; every other line is kept as it was written, so a reader of any release sees exactly the entries
 * it saw before. The file is switched in one step by [FolderAccess.replace] (staged under a new name, then renamed), so a crash leaves
 * either the old file or the new one.
 */
internal object CatalogueCompaction {
    const val THRESHOLD = 2000

    class Result(val before: Int, val after: Int)

    private class Line(val kind: Int, val id: String, val text: String)

    private const val ENTRY = 0
    private const val DEAD = 1
    private const val OTHER = 2

    private fun parse(text: String): Line = try {
        val o = JSONObject(text)
        when {
            o.has("void") -> Line(DEAD, o.getString("void"), text)
            o.has("gone") -> Line(DEAD, o.getString("gone"), text)
            o.has("id") && o.has("file") -> Line(ENTRY, CatalogueEntry.of(o).id, text)
            else -> Line(OTHER, "", text)
        }
    } catch (e: Exception) {
        Line(OTHER, "", text) // a torn line is skipped by every reader
    }

    /** Rewrites one catalogue without its dead lines when it holds more than [threshold] lines. Null when nothing was done. */
    fun compact(access: FolderAccess, store: Store, threshold: Int = THRESHOLD): Result? {
        val file = catalogueFile(store)
        val bytes = try {
            access.read(file)
        } catch (e: FolderError.NotFound) {
            return null
        }
        val lines = bytes.toString(Charsets.UTF_8).split('\n').map { it.trimEnd('\r') }.filter { it.isNotBlank() }.map(::parse)
        if (lines.size <= threshold) return null
        val dead = lines.filter { it.kind == DEAD }.mapTo(HashSet()) { it.id }
        // a live entry is kept once per identical line (the newest); the other live lines stay in their order
        val seen = HashSet<String>()
        val keep = ArrayList<String>()
        for (line in lines.asReversed()) {
            if (line.kind != ENTRY || line.id in dead || !seen.add(line.text)) continue
            keep.add(line.text)
        }
        keep.reverse()
        if (keep.size == lines.size) return null
        // a line appended since the file was read would be lost by the switch: leave the file for the next run
        val again = try { access.read(file) } catch (e: FolderError.NotFound) { return null }
        if (!again.contentEquals(bytes)) return null
        access.replace(file, (if (keep.isEmpty()) "" else keep.joinToString("\n", postfix = "\n")).toByteArray(Charsets.UTF_8))
        return Result(lines.size, keep.size)
    }

    /** Both catalogues of a copy. A failure of one never stops the other, and never harms the file. */
    fun compactAll(access: FolderAccess): Int = Store.values().count { store -> runCatching { compact(access, store) }.getOrNull() != null }
}
