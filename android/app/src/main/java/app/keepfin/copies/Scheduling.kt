package app.keepfin.copies

import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import app.keepfin.folder.KeepfinFolders
import app.keepfin.folder.OpenFolderResult

/**
 * When a background (scheduled or automatic) run waits. dev-docs/plans/KEEPFIN-COPIES-SPEC.md section 8 sets no number, so the line is drawn
 * here: a run that has to put more than 500 MB or 2,000 files on the copy is "large" and waits until the phone is charging or on an
 * unmetered network; a small run goes at once. A person who taps Sync now is never held.
 */
internal object Scheduling {
    const val LARGE_BYTES = 500L * 1024 * 1024
    const val LARGE_FILES = 2_000

    fun isLarge(files: Int, bytes: Long, largeBytes: Long = LARGE_BYTES, largeFiles: Int = LARGE_FILES) = bytes > largeBytes || files > largeFiles

    /** True when a background run of this size must wait. */
    fun hold(files: Int, bytes: Long, charging: Boolean, unmetered: Boolean, largeBytes: Long = LARGE_BYTES, largeFiles: Int = LARGE_FILES) =
        isLarge(files, bytes, largeBytes, largeFiles) && !(charging || unmetered)
}

/** Finds a copy again when its card or drive came back under another folder address (dev-docs/plans/KEEPFIN-FOLDER-ANDROID-SPEC.md 11.1). */
internal object Recognition {
    /**
     * Returns the first of [candidates] (folder addresses with a persisted permission) that holds the copy with [replica] of [libraryId],
     * skipping addresses [taken] by other copies. A candidate that cannot be opened is skipped; nothing is written.
     */
    fun find(replica: String, libraryId: String, candidates: List<String>, taken: Set<String>, open: (String) -> FolderAccess): String? {
        for (c in candidates) {
            if (c in taken) continue
            val opened = try {
                KeepfinFolders.open(open(c))
            } catch (e: FolderError) {
                continue
            } catch (e: Exception) {
                continue
            }
            if (opened is OpenFolderResult.Ok && opened.folder.replicaId == replica && opened.folder.libraryId == libraryId) return c
        }
        return null
    }
}
