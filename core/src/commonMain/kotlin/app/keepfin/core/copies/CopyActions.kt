package app.keepfin.core.copies

import app.keepfin.core.groupDigits

// The two questions a copy asks before it does something that cannot be undone (both apps): Free up space… on a backup and
// Remove copy…. The actions and every word of their confirmation are decided here; each app only draws them.

object CopyActions {
    const val FREE_UP = "Free up space…"
    const val FREE_UP_CONFIRM = "Free up space"
    const val REMOVE = "Remove copy…"
    const val REMOVE_CONFIRM = "Remove copy"
    const val CANCEL = "Cancel"
    const val OK = "OK"

    /** "Free up space on Backup?" */
    fun freeUpTitle(copy: String): String = "Free up space on $copy?"

    /** Said when a backup keeps nothing to free. */
    const val NOTHING_TO_FREE = "There is nothing to free up: this backup keeps no earlier versions, and nothing from files you deleted."

    /** True when a backup has nothing to free up: [versions] kept entries, [deletedFiles] of them for files deleted since. */
    fun nothingToFree(versions: Int, deletedFiles: Int): Boolean = versions == 0 && deletedFiles == 0

    private fun count(n: Int, one: String, many: String = one + "s") = "${groupDigits(n.toLong())} ${if (n == 1) one else many}"

    /** "This deletes 3 earlier versions and the 2 files kept after you deleted them from your library from this backup." */
    fun freeUpSummary(versions: Int, deletedFiles: Int): String {
        val parts = listOf(
            if (versions > 0) count(versions, "earlier version") else "",
            if (deletedFiles > 0) "${count(deletedFiles, "file")} kept after you deleted ${if (deletedFiles == 1) "it" else "them"} from your library" else "",
        ).filter { it.isNotEmpty() }
        return "This deletes ${parts.joinToString(" and the ")} from this backup."
    }

    const val FREE_UP_KEEPS = "The backup keeps its copy of everything in your library. Nothing in your library is touched."

    /** "Remove Backup?" */
    fun removeTitle(copy: String): String = "Remove $copy?"

    const val REMOVE_LEAD = "Keepfin stops keeping this copy up to date."

    /** What happens to the files of a copy that is not in a folder on this device (a cloud account, a paired device), and on a phone, where there is no bin. */
    const val REMOVE_FILES_STAY = "Its files stay where they are."
}
