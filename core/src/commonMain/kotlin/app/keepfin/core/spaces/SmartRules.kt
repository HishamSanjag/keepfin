package app.keepfin.core.spaces

import app.keepfin.core.music.WS
import app.keepfin.core.music.isJsWhitespace
import app.keepfin.core.music.jsTrim
import app.keepfin.core.music.nfkd
import app.keepfin.core.music.removeFirstAtEnd
import app.keepfin.core.providers.Providers

/**
 * One rule of a smart space. [field] is tag, collection, space, source, artist, album or kind (they use [values]),
 * addedWithin (uses [days]), favorite or unplayed.
 */
class SmartRule(val field: String, val values: List<String> = emptyList(), val days: Double = 0.0)

/** What a smart space gathers. [matchAll] false means any rule is enough. [onlyKind] and [excludeSpaces] apply after the rules. */
class RuleSet(
    val matchAll: Boolean,
    val rules: List<SmartRule>,
    val onlyKind: String? = null,
    val excludeSpaces: List<String> = emptyList(),
)

/** Lower-case, diacritic-free text, as the artist and album rules compare it. */
internal fun foldText(value: String): String {
    val decomposed = nfkd(value)
    val out = StringBuilder(decomposed.length)
    for (c in decomposed) {
        val category = c.category
        if (category == CharCategory.NON_SPACING_MARK || category == CharCategory.COMBINING_SPACING_MARK || category == CharCategory.ENCLOSING_MARK) continue
        out.append(c)
    }
    return out.toString().lowercase()
}

private fun isWordChar(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

/** A letter or number, like the Unicode classes \p{L} and \p{N}; characters outside the BMP are all kept. */
private fun isLetterOrNumber(c: Char): Boolean = when (c.category) {
    CharCategory.UPPERCASE_LETTER, CharCategory.LOWERCASE_LETTER, CharCategory.TITLECASE_LETTER, CharCategory.MODIFIER_LETTER, CharCategory.OTHER_LETTER,
    CharCategory.DECIMAL_DIGIT_NUMBER, CharCategory.LETTER_NUMBER, CharCategory.OTHER_NUMBER,
    -> true
    else -> false
}

private val TOPIC_SUFFIX = Regex("$WS+-$WS+topic")

/**
 * The primary artists of a credit line ("A feat. B & C" gives a, b, c): folded, " - Topic" and "vevo" suffixes removed, split on
 * , & ; / and the words and, x, feat, ft, featuring, with. Each part keeps only letters and numbers, separated by single spaces.
 */
internal fun artistNames(raw: String?): List<String> {
    if (raw.isNullOrEmpty()) return emptyList()
    var text = removeFirstAtEnd(TOPIC_SUFFIX, foldText(raw))
    if (text.endsWith("vevo")) text = text.dropLast(4)
    val parts = ArrayList<String>()
    var pieceStart = 0
    var at = 0
    while (at < text.length) {
        val end = separatorEnd(text, at)
        if (end < 0) {
            at++
            continue
        }
        // The match starts at the whitespace run in front of the separator.
        var runStart = at
        while (runStart > pieceStart && isJsWhitespace(text[runStart - 1])) runStart--
        parts.add(text.substring(pieceStart, runStart))
        var next = end
        while (next < text.length && isJsWhitespace(text[next])) next++
        pieceStart = next
        at = next
    }
    parts.add(text.substring(pieceStart))
    return parts.map { part ->
        val words = StringBuilder()
        var gap = false
        for (c in part) {
            if (isLetterOrNumber(c) || c.isSurrogate()) {
                if (gap && words.isNotEmpty()) words.append(' ')
                gap = false
                words.append(c)
            } else gap = true
        }
        jsTrim(words.toString())
    }.filter { it.isNotEmpty() }
}

/** End of the separator that starts at [at] (without surrounding whitespace), or -1. */
private fun separatorEnd(text: String, at: Int): Int {
    val c = text[at]
    if (c == ',' || c == '&' || c == ';' || c == '/') return at + 1
    if (at > 0 && isWordChar(text[at - 1])) return -1
    fun word(w: String): Boolean {
        if (!text.startsWith(w, at)) return false
        val after = at + w.length
        return after == text.length || !isWordChar(text[after])
    }
    fun prefix(w: String): Int {
        if (!text.startsWith(w, at)) return -1
        val after = at + w.length
        return if (after < text.length && text[after] == '.') after + 1 else after
    }
    if (word("and")) return at + 3
    if (word("x")) return at + 1
    prefix("feat").let { if (it >= 0) return it }
    prefix("ft").let { if (it >= 0) return it }
    if (word("with")) return at + 4
    return -1
}

object SmartRules {
    private const val DAY = 86_400_000.0

    private fun sourceNames(item: LibraryItem): List<String> {
        val names = ArrayList<String>()
        item.sourceUrl?.takeIf { it.isNotEmpty() }?.let { names.add(Providers.name(it).lowercase()) }
        val service = item.sourceId?.substringBefore(':')
        if (!service.isNullOrEmpty()) names.add(service.lowercase())
        return names
    }

    private fun ruleMatches(rule: SmartRule, item: LibraryItem, now: Long, collectionsOf: (String) -> Collection<String>): Boolean {
        when (rule.field) {
            "addedWithin" -> return item.addedAt >= now - maxOf(0.0, rule.days) * DAY
            "favorite" -> return item.favorite
            "unplayed" -> return item.plays == 0
        }
        val values = rule.values.map { jsTrim(it).lowercase() }.filter { it.isNotEmpty() }
        if (values.isEmpty()) return false
        return when (rule.field) {
            "tag" -> (item.tags + item.userTags).any { it in values }
            "collection" -> collectionsOf(item.id).any { it.lowercase() in values }
            "space" -> item.space.lowercase() in values
            "source" -> sourceNames(item).let { names -> values.any { it in names } }
            "artist" -> {
                val have = artistNames(item.artist ?: item.albumArtist)
                values.any { value -> artistNames(value).any { it in have } }
            }
            "album" -> item.album != null && item.album.isNotEmpty() && jsTrim(foldText(item.album)).let { album -> values.any { jsTrim(foldText(it)) == album } }
            "kind" -> item.kind in values
            else -> false
        }
    }

    /**
     * One item against a smart space's rules. No rules gather nothing. [collectionsOf] gives the ids of the collections that hold an
     * item. Value comparisons ignore case; artist and album also ignore accents.
     */
    fun matches(rules: RuleSet, item: LibraryItem, now: Long, collectionsOf: (String) -> Collection<String> = { emptyList() }): Boolean {
        if (rules.rules.isEmpty()) return false
        val test = { rule: SmartRule -> ruleMatches(rule, item, now, collectionsOf) }
        val hit = if (rules.matchAll) rules.rules.all(test) else rules.rules.any(test)
        if (!hit) return false
        if (rules.onlyKind != null && item.kind != rules.onlyKind) return false
        if (item.space in rules.excludeSpaces) return false
        return true
    }
}
