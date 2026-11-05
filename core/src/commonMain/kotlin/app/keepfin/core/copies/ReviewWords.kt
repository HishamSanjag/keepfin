package app.keepfin.core.copies

// Words for Needs a decision (both apps): what a group of files on a copy are, by their kind, so a person reads
// "52 lyrics files and 1 video" instead of a list of names.

private val kinds = listOf(
    setOf("lrc") to ("lyrics file" to "lyrics files"),
    setOf("srt", "vtt", "ass", "ssa") to ("subtitle file" to "subtitle files"),
    setOf("mp3", "m4a", "flac", "opus", "ogg", "oga", "wav", "aac", "wma") to ("song" to "songs"),
    setOf("mp4", "m4v", "mkv", "webm", "mov", "avi") to ("video" to "videos"),
    setOf("jpg", "jpeg", "png", "webp", "gif") to ("picture" to "pictures"),
)

private fun extensionOf(path: String): String {
    val name = path.substring(maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1)
    val dot = name.lastIndexOf('.')
    return if (dot <= 0) "" else name.substring(dot + 1).lowercase()
}

/** "52 lyrics files and 1 video", "3 songs, 2 pictures and 1 other file": the largest kind first. */
fun filesSummary(paths: List<String>): String {
    val counts = LinkedHashMap<Pair<String, String>, Int>()
    for (path in paths) {
        val ext = extensionOf(path)
        val words = kinds.firstOrNull { ext in it.first }?.second ?: ("other file" to "other files")
        counts[words] = (counts[words] ?: 0) + 1
    }
    val parts = counts.entries.sortedByDescending { it.value }.map { (words, n) -> "$n ${if (n == 1) words.first else words.second}" }
    return when (parts.size) {
        0 -> "No files"
        1 -> parts[0]
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }
}
