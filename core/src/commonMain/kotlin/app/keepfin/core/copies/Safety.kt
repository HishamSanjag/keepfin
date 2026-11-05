package app.keepfin.core.copies

import app.keepfin.core.groupDigits

// The sentence at the top of Storage (both apps): is every file kept in more than one place? It names what has only one copy
// and offers Keep a copy… when those files are here, where a copy can be made from.

/** Files of one kind that only one place holds: [kind] is songs, videos or other; [here] is this device. */
data class OnePlace(val kind: String, val count: Int, val place: String, val here: Boolean)

/** [tone]: safe, warn or quiet. [keepCopy]: offer Keep a copy… (some of the files are here). */
data class Safety(val tone: String, val title: String, val detail: String, val keepCopy: Boolean)

private fun count(n: Int, one: String, many: String) = "${groupDigits(n.toLong())} ${if (n == 1) one else many}"

private fun kindWords(kind: String, n: Int) = when (kind) {
    "songs" -> count(n, "song", "songs")
    "videos" -> count(n, "video", "videos")
    else -> count(n, "other file", "other files")
}

/**
 * [places]: how many places keep part of the library, this device included; [files]: the library's files; [only]: what only one
 * place holds; [deviceName]: this device in words ("this PC", "this phone").
 */
fun librarySafety(places: Int, files: Int, only: List<OnePlace>, deviceName: String): Safety {
    if (files == 0) return Safety("quiet", "Nothing to keep safe yet", "What you add is kept in your main storage first.", false)
    if (places <= 1) {
        return Safety(
            "warn", "Your library is only on $deviceName",
            "Keep a copy on a drive, in the cloud or on another device, so one broken disk cannot lose it.", true,
        )
    }
    val counted = only.filter { it.count > 0 }
    if (counted.isEmpty()) return Safety("safe", "Your library is in $places places", "Every file is in at least two of them.", false)
    val total = counted.sumOf { it.count }
    val title = if (total == 1) "1 file has only one copy" else "${groupDigits(total.toLong())} files have only one copy"
    val byPlace = LinkedHashMap<Pair<String, Boolean>, MutableList<OnePlace>>()
    for (entry in counted.sortedByDescending { it.here }) byPlace.getOrPut(entry.place to entry.here) { mutableListOf() }.add(entry)
    val detail = byPlace.entries.joinToString(" ") { (key, list) ->
        val words = list.map { kindWords(it.kind, it.count) }
        val what = if (words.size == 1) words[0] else words.dropLast(1).joinToString(", ") + " and " + words.last()
        val verb = if (list.sumOf { it.count } == 1) "is" else "are"
        val where = if (key.second) "in your main storage on $deviceName" else "on ${key.first}"
        "$what $verb only $where."
    }
    return Safety("warn", title, detail, counted.any { it.here })
}
