package app.keepfin.copies

import app.keepfin.core.sync.SegmentCompaction
import app.keepfin.core.sync.SegmentInfo
import app.keepfin.core.sync.SegmentResult
import app.keepfin.core.sync.decodeSegment
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import app.keepfin.net.SegInfo
import app.keepfin.net.SegmentFiles

/**
 * Segment files a larger file of the same actor already holds (dev-docs/plans/KEEPFIN-DATA-COMPACTION.md R5, R6). On a copy they are
 * removed for good (never moved to versions or trash) once the covering file on that replica has been read back and holds the same
 * ops line for line.
 */
internal object SegmentPrune {
    private fun info(s: SegInfo) = SegmentInfo(s.actor, s.first, s.last, s.size, null)

    /** The files of [files] that a larger file in [files] covers by name (not yet checked op for op). */
    fun coveredByName(files: List<SegInfo>): List<Pair<SegInfo, SegInfo>> =
        SegmentCompaction.subsumed(files.map(::info)).map { files[it.index] to files[it.by] }

    /**
     * Removes, from [folder]'s ops, the segment files that a larger file there covers and that [keep] does not protect (a copy keeps
     * what the main folder still lists). Each removal is preceded by reading both files and comparing the covered ops; an actor with a
     * file that does not decode is left alone. Returns how many were removed.
     */
    fun pruneCovered(folder: FolderAccess, libraryId: String, keep: (SegInfo) -> Boolean = { false }): Int {
        val files = SegmentFiles.list(folder)
        if (files.size < 2) return 0
        val found = coveredByName(files).filter { !keep(it.first) }
        if (found.isEmpty()) return 0
        val covers = HashMap<String, SegmentResult?>()
        val broken = HashSet<String>()
        var removed = 0
        for ((covered, by) in found) {
            if (covered.actor in broken) continue
            val large = covers.getOrPut(by.path) { read(folder, by, libraryId) } as? SegmentResult.Ok
            val small = read(folder, covered, libraryId) as? SegmentResult.Ok
            if (large == null || small == null || large.actor != small.actor || small.first < large.first || small.last > large.last) {
                broken += covered.actor
                continue
            }
            var same = true
            var seq = small.first
            while (seq <= small.last && same) {
                same = small.ops[(seq - small.first).toInt()].encode() == large.ops[(seq - large.first).toInt()].encode()
                seq++
            }
            if (!same) continue
            try {
                folder.prune(covered.path)
                removed++
            } catch (e: FolderError) {
                // left for the next run
            }
        }
        return removed
    }

    private fun read(folder: FolderAccess, s: SegInfo, libraryId: String): SegmentResult? =
        try {
            decodeSegment(folder.read(s.path), libraryId)
        } catch (e: FolderError) {
            null
        }
}
