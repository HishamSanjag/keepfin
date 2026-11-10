package app.keepfin.core.copies

// What a sync the person started (Sync all, Sync now) did, said once when every copy in it has finished: under the buttons and
// as a notification (both apps). A sync that found nothing to do still says so; silence reads as "nothing happened".

/** [warn]: something in it needs a look (files not copied, a copy not reached, a copy waiting for an OK). */
data class SyncReport(val title: String, val detail: String, val warn: Boolean)

private fun files(n: Int): String = if (n == 1) "1 file" else "$n files"

/** "Media", "Media and Backup", "Media, Backup and USB". */
private fun names(list: List<String>): String = if (list.size <= 1) list.joinToString("") else list.dropLast(1).joinToString(", ") + " and " + list.last()

/**
 * [synced] copies that ran to the end; [changed] files they added, updated or removed; [problems] files they could not copy;
 * [notReached] copies that could not be reached (not plugged in, offline, stopped); [needOk] copies waiting for the person's OK.
 * [cloud]: the copy not reached is a cloud account, which Keepfin retries by itself.
 */
fun syncReport(synced: Int, changed: Int, problems: Int, notReached: List<String>, needOk: List<String>, cloud: Boolean = false): SyncReport {
    val after = buildList {
        if (needOk.isNotEmpty()) add("${names(needOk)} ${if (needOk.size == 1) "needs" else "need"} your OK first.")
        if (notReached.isNotEmpty()) add("${names(notReached)} could not be reached.")
    }
    val tail = if (after.isEmpty()) "" else " " + after.joinToString(" ")
    if (synced == 0) {
        if (needOk.isNotEmpty() && notReached.isEmpty()) return SyncReport("A copy needs your OK", after.joinToString(" "), true)
        val title = if (notReached.size == 1 && needOk.isEmpty()) "${notReached[0]} could not be reached" else "No copy could be synced"
        val detail = when {
            notReached.size != 1 || needOk.isNotEmpty() -> after.joinToString(" ")
            cloud -> "Keepfin will try again by itself. If it keeps failing, check your internet connection."
            else -> "Check that it is plugged in or online, then try again."
        }
        return SyncReport(title, detail, true)
    }
    if (problems > 0) {
        val done = if (changed > 0) "${files(changed)} updated. " else ""
        return SyncReport(copyProblemTitle(problems), done + COPY_PROBLEM_RETRY + tail, true)
    }
    if (changed > 0) {
        val where = if (synced == 1) "." else " across $synced copies."
        return SyncReport(if (synced == 1) "Copy synced" else "Copies synced", files(changed) + " updated" + where + tail, after.isNotEmpty())
    }
    val detail = if (synced == 1) "Nothing needed copying." else "All $synced copies already match your library."
    return SyncReport("Everything is up to date", detail + tail, after.isNotEmpty())
}
