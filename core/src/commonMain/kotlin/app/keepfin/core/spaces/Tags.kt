package app.keepfin.core.spaces

import app.keepfin.core.music.isJsWhitespace
import app.keepfin.core.music.jsTrim

/** A tag with how many items in scope carry it. */
class ScopeTag(val tag: String, val count: Int, val user: Boolean)

/** The tags of one item, for [Tags.inScope]. */
class TaggedItem(val tags: List<String> = emptyList(), val userTags: List<String> = emptyList())

object Tags {
    const val MAX_SOURCE_TAGS = 15
    const val MAX_TAG_LENGTH = 30

    // A value whose last label is one of these names a site (example.com, example.com/a): a link, not a tag.
    private val siteSuffixes = setOf("com", "net", "org", "io", "co", "tv", "fm", "me", "app", "ly", "gg", "xyz", "info", "be", "de", "fr", "ru", "jp", "uk", "us")

    private fun isLabelChar(c: Char) = c in 'a'..'z' || c in '0'..'9' || c == '-'

    /** "scheme://" at the start. */
    private fun startsWithScheme(tag: String): Boolean {
        val at = tag.indexOf("://")
        if (at < 1 || tag[0] !in 'a'..'z') return false
        for (i in 1 until at) {
            val c = tag[i]
            if (!(c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '.' || c == '-')) return false
        }
        return true
    }

    /** `name.tld` anywhere in a lower-case value, the tld being a whole label (not followed by a letter, digit or dash). */
    private fun hasSiteName(tag: String): Boolean {
        for (dot in tag.indices) {
            if (tag[dot] != '.' || dot == 0 || !isLabelChar(tag[dot - 1])) continue
            var end = dot + 1
            while (end < tag.length && isLabelChar(tag[end])) end++
            if (tag.substring(dot + 1, end) in siteSuffixes) return true
        }
        return false
    }

    /** One tag: whitespace collapsed, leading #s dropped, trimmed, lower case. Null for empty values, links and values over 30 characters. */
    fun clean(part: String): String? {
        val collapsed = StringBuilder()
        var inSpace = false
        for (c in part) {
            if (isJsWhitespace(c)) {
                if (!inSpace) collapsed.append(' ')
                inSpace = true
            } else {
                collapsed.append(c)
                inSpace = false
            }
        }
        var text = collapsed.toString()
        var start = 0
        while (start < text.length && text[start] == '#') start++
        text = text.substring(start)
        val tag = jsTrim(text).lowercase()
        if (tag.isEmpty() || tag.length > MAX_TAG_LENGTH || startsWithScheme(tag) || tag.startsWith("www.") || hasSiteName(tag)) return null
        return tag
    }

    /**
     * Lower case, trimmed, whitespace-collapsed, deduplicated tags. Every value is split on commas and semicolons; empty values, links and
     * anything over 30 characters are dropped; at most [limit] are kept in their original order. Null values are skipped.
     */
    fun normalize(raw: List<String?>, limit: Int = MAX_SOURCE_TAGS): List<String> {
        val out = ArrayList<String>()
        for (value in raw) {
            if (value == null) continue
            var from = 0
            while (true) {
                var to = from
                while (to < value.length && value[to] != ',' && value[to] != ';') to++
                val tag = clean(value.substring(from, to))
                if (tag != null && tag !in out) out.add(tag)
                if (out.size >= limit) return out
                if (to >= value.length) break
                from = to + 1
            }
        }
        return out
    }

    /**
     * Tags worth a chip in a scope: those on at least [minItems] items, plus every user tag. Most used first, then by code unit
     * (the desktop app used the browser's locale order here; only non-ASCII or punctuation-heavy tags can sort differently).
     */
    fun inScope(items: List<TaggedItem>, minItems: Int = 2): List<ScopeTag> {
        val counts = LinkedHashMap<String, IntArray>() // [count, user]
        for (item in items) {
            val own = LinkedHashSet<String>()
            own.addAll(item.tags)
            for (tag in item.userTags) {
                own.add(tag)
                counts.getOrPut(tag) { IntArray(2) }[1] = 1
            }
            for (tag in own) counts.getOrPut(tag) { IntArray(2) }[0]++
        }
        return counts.filter { (_, v) -> v[1] == 1 || v[0] >= minItems }
            .map { (tag, v) -> ScopeTag(tag, v[0], v[1] == 1) }
            .sortedWith(Comparator { a, b -> if (a.count != b.count) b.count - a.count else a.tag.compareTo(b.tag) })
    }
}
