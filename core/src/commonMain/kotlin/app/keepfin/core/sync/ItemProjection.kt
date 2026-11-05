package app.keepfin.core.sync

// The one place that decides what an item looks like to a reader: its status, its heads, the delete conflict, the
// play count and its fields. The in-memory views (ViewsJson.kt) and the SQL item projection (sqlstate) both use it,
// so desktop and Android cannot disagree about it.

/** An item as a reader sees it, already as the JSON texts a reader stores or sends. */
class ProjectedItem(
    /** `present`, `deleted` or `deleteConflict`; null when the item is unknown (no add seen yet), so it is not shown. */
    val status: String?,
    /** The item's fields as one canonical JSON object. */
    val fields: String,
    /** The current revision heads as a JSON array of `{content, op, size}`, in op order. */
    val heads: String,
    val plays: Long,
    /** More than one head. */
    val contentConflict: Boolean,
    /** The heads a delete did not see, as a JSON array of op ids (empty unless the status is `deleteConflict`). */
    val deleteConflictHeads: String,
    /** The revision events have no head (only a buggy writer makes this): the finding `revision-cycle:<item>`. */
    val cycle: Boolean,
)

private fun StringBuilder.q(s: String) = appendQuoted(this, s)

fun projectItem(item: ItemState): ProjectedItem {
    // a cycle is judged even for an unknown item, as State.hasRevisionCycle does
    var cycle = false
    if (item.revisions.isNotEmpty()) {
        val parents = HashSet<OpId>()
        for (e in item.revisions.values) parents.addAll(e.parents)
        cycle = item.revisions.keys.all { it in parents }
    }
    if (item.exists.adds.isEmpty()) return ProjectedItem(null, "{}", "[]", 0L, false, "[]", cycle)
    val present = item.exists.present
    val heads = headsOf(item)
    val conflict = if (present) emptyList() else heads.filter { it !in item.deletedHeads }
    val status = if (present) "present" else if (conflict.isNotEmpty()) "deleteConflict" else "deleted"

    val fields = StringBuilder()
    fields.append('{')
    var first = true
    var plays = 0L
    for (e in sortedEntries(item.fields)) {
        if (!first) fields.append(',')
        first = false
        fields.q(e.key)
        fields.append(':')
        writeCanonical(fields, e.value.value)
        if (e.key.startsWith(PLAYS_PREFIX)) {
            val v = e.value.value
            if (v is JNum) plays += v.value
        }
    }
    fields.append('}')

    val headText = StringBuilder()
    headText.append('[')
    first = true
    for (head in heads) {
        val e = item.revisions.getValue(head)
        if (!first) headText.append(',')
        first = false
        headText.append("{\"content\":")
        headText.q(e.content)
        headText.append(",\"op\":")
        headText.q(head.toString())
        headText.append(",\"size\":").append(JNum(e.size).value.toString()).append('}')
    }
    headText.append(']')

    val dch = StringBuilder()
    dch.append('[')
    first = true
    for (h in conflict) {
        if (!first) dch.append(',')
        first = false
        dch.q(h.toString())
    }
    dch.append(']')

    return ProjectedItem(status, fields.toString(), headText.toString(), JNum(plays).value, heads.size > 1, dch.toString(), cycle)
}
