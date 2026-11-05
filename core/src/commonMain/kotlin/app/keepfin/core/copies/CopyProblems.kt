package app.keepfin.core.copies

import app.keepfin.core.groupDigits

// Words for a copy that could not copy some files (both apps). A failed file is not a decision for the person: it is a problem
// with a cause, said in plain words, and Keepfin tries again at every sync. The cause arrives as whatever the system said
// (Node: "EISDIR: illegal operation on a directory, link '...'", Android: "open failed: ENOSPC (No space left on device)",
// or the copy engine's own sentence); it never reaches the screen, only the sentence chosen here.

const val FALLBACK_PROBLEM = "Something stopped the copy."

private val inUseWords = listOf("EBUSY", "ETXTBSY", "IS IN USE", "BEING USED", "SHARING VIOLATION", "RESOURCE BUSY", "TEXT FILE BUSY", "LOCKED BY")
private val fullWords = listOf("ENOSPC", "NO SPACE LEFT", "NOT ENOUGH SPACE", "DISK FULL", "DISK IS FULL", "QUOTA", "EDQUOT")
private val tooLargeWords = listOf("EFBIG", "FILE TOO LARGE", "LARGER THAN THE COPY CAN HOLD")
private val nameWords = listOf("ENAMETOOLONG", "NAME TOO LONG", "NAME IS TOO LONG", "PATH TOO LONG", "PATH IS TOO LONG")
private val permissionWords = listOf("EACCES", "EPERM", "EROFS", "PERMISSION DENIED", "ACCESS IS DENIED", "ACCESS DENIED", "NOT PERMITTED", "READ-ONLY", "READ ONLY", "SECURITYEXCEPTION")
private val goneWords = listOf(
    "ENOENT", "ENODEV", "ENXIO", "ENOTCONN", "ENETUNREACH", "EHOSTUNREACH", "NO SUCH FILE", "NO SUCH DEVICE", "FILENOTFOUND", "IS NO LONGER ON THE COPY",
    "NOT CONNECTED", "NOT FOUND", "NOT AVAILABLE",
)
private val unsupportedWords = listOf("EISDIR", "ENOTSUP", "EOPNOTSUPP", "EXDEV", "ENOSYS", "NOT SUPPORTED", "ILLEGAL OPERATION", "CROSS-DEVICE", "FUNCTION NOT IMPLEMENTED")
private val sourceWords = listOf("THE SOURCE", "NO LONGER MATCHES", "CHANGED SINCE IT WAS PLANNED", "ON THE COPY CHANGED", "HASH")
private val takenWords = listOf("EEXIST", "ALREADY ON THE COPY", "APPEARED ON", "IS TAKEN ON THE COPY", "ALREADY EXISTS")

/** The part of [reason] before any quoted path: names of songs must never decide the cause. */
private fun saying(reason: String): String {
    var end = reason.length
    for (q in listOf('\'', '"', '`')) {
        val at = reason.indexOf(q)
        if (at in 0 until end) end = at
    }
    return reason.substring(0, end).uppercase()
}

private fun hits(text: String, words: List<String>): Boolean = words.any { text.contains(it) }

/** One sentence for why a file could not be copied; never a code, never a file name. */
fun copyFailureReason(reason: String): String {
    val text = saying(reason)
    return when {
        hits(text, inUseWords) -> "A file there is in use by another program."
        hits(text, fullWords) -> "The drive is full."
        hits(text, tooLargeWords) -> "A file is larger than this drive can hold."
        hits(text, nameWords) -> "A file name is too long for this drive."
        hits(text, unsupportedWords) -> "This drive does not support how Keepfin saves files."
        hits(text, permissionWords) -> "Keepfin is not allowed to write there."
        hits(text, goneWords) -> "The drive or folder is no longer there."
        hits(text, sourceWords) -> "A file in your library changed while it was being copied."
        hits(text, takenWords) -> "A file with that name appeared on the copy meanwhile."
        else -> FALLBACK_PROBLEM
    }
}

/** A reason and how many files had it. */
data class ProblemGroup(val text: String, val count: Int)

/** The failures grouped by their plain reason, the most common first (ties keep the order they first appeared in). */
fun copyProblemGroups(reasons: List<String>): List<ProblemGroup> {
    val counts = LinkedHashMap<String, Int>()
    for (r in reasons) {
        val text = copyFailureReason(r)
        counts[text] = (counts[text] ?: 0) + 1
    }
    return counts.entries.withIndex().sortedWith(compareBy({ -it.value.value }, { it.index })).map { ProblemGroup(it.value.key, it.value.value) }
}

/** "1 file could not be copied", "50 files could not be copied": the headline of the alert on the copy. */
fun copyProblemTitle(count: Int): String = "${groupDigits(count.toLong())} ${if (count == 1) "file" else "files"} could not be copied"

/** "1 file not copied", "50 files not copied": the short status on the copy's card. */
fun copyProblemShort(count: Int): String = "${groupDigits(count.toLong())} ${if (count == 1) "file" else "files"} not copied"

/** The promise under the alert. */
const val COPY_PROBLEM_RETRY = "Keepfin tries again at every sync."
