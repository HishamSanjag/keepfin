package app.keepfin.core.copies

/** Canonical composition (NFC): "e" plus a combining acute and the single letter "é" are one name to a person. Each platform has its own normalizer. */
internal expect fun nfc(value: String): String

private fun isAscii(path: String): Boolean {
    for (c in path) if (c.code >= 128) return false
    return true
}

/** The name as a person reads it: composed, so a decomposed spelling is the same name (a copy never holds a second, visually identical file). */
internal fun composedKey(path: String): String = if (isAscii(path)) path else nfc(path)

/**
 * Lower-case folding of one character that never changes a name's length: a character whose lower case is longer (a dotted capital I)
 * stays as it is, and so does anything outside the Basic Multilingual Plane. The same on every platform.
 */
private fun foldChar(c: Char): Char {
    if (c in 'A'..'Z') return Char(c.code + 32)
    if (c.code < 128 || c.isSurrogate()) return c
    val lower = c.toString().lowercase()
    return if (lower.length == 1) lower[0] else c
}

/** [composedKey] with letter case folded: what a volume that ignores case (NTFS, FAT, exFAT, most cloud accounts) calls the same name. */
internal fun foldedKey(path: String): String {
    val composed = composedKey(path)
    var changed = false
    val sb = StringBuilder(composed.length)
    for (c in composed) {
        val f = foldChar(c)
        if (f != c) changed = true
        sb.append(f)
    }
    return if (changed) sb.toString() else composed
}
