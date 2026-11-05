package app.keepfin.core.spaces

/**
 * The words of smart rules ("Tag is lofi or chill", "Not played yet"), the counts around them ("58 songs") and the rules and name of
 * "Save as smart space" in the tag filter. Both apps show exactly these words. The wording is the phone's. Collection and space ids
 * become names through [nameOf]: the core looks nothing up.
 */
object RuleWords {
    fun fieldLabel(field: String): String = when (field) {
        "tag" -> "Tag"
        "collection" -> "Collection"
        "space" -> "Space"
        "source" -> "Source site"
        "artist" -> "Artist / channel"
        "album" -> "Album"
        "kind" -> "Kind"
        else -> field
    }

    fun kindLabel(wire: String): String = when (wire) {
        "audio" -> "Songs"
        "video" -> "Videos"
        "image" -> "Photos"
        else -> "Other files"
    }

    /** Words for one stored value: collection and space ids become names, kinds become Songs / Videos / Photos. */
    fun valueLabel(field: String, value: String, nameOf: (String, String) -> String? = { _, _ -> null }): String =
        nameOf(field, value) ?: if (field == "kind") kindLabel(value) else value

    private fun join(parts: List<String>, word: String): String = when (parts.size) {
        0 -> ""
        1 -> parts[0]
        else -> parts.dropLast(1).joinToString(", ") + " " + word + " " + parts.last()
    }

    fun describe(rule: SmartRule, nameOf: (String, String) -> String? = { _, _ -> null }): String = when (rule.field) {
        "addedWithin" -> {
            val days = rule.days.toInt()
            "Added in the last " + (if (days % 30 == 0 && days >= 60) "${days / 30} months" else if (days == 1) "day" else "$days days")
        }
        "favorite" -> "Favorite"
        "unplayed" -> "Not played yet"
        else -> fieldLabel(rule.field) + " is " + join(rule.values.map { valueLabel(rule.field, it, nameOf) }, "or")
    }

    /** "Tag is lofi or chill" for one rule; several rules are joined with "and" or "or" following the match mode. */
    fun describe(rules: RuleSet, nameOf: (String, String) -> String? = { _, _ -> null }): String {
        if (rules.rules.isEmpty()) return "No rules yet"
        val parts = rules.rules.map { describe(it, nameOf) }
        val base = if (parts.size == 1) parts[0] else parts.joinToString(if (rules.matchAll) " and " else " or ")
        return if (rules.onlyKind != null) base + " · only " + kindLabel(rules.onlyKind).lowercase() else base
    }

    /** "58 songs", "1 video", "12 items": the noun follows the kinds actually matched. [total] is the number of items. */
    fun countLabel(total: Int, audio: Int, video: Int, image: Int, other: Int): String {
        val only = listOf(audio, video, image, other).withIndex().filter { it.value > 0 }.singleOrNull()?.index
        val one = total == 1
        return when (only) {
            0 -> if (one) "1 song" else "$total songs"
            1 -> if (one) "1 video" else "$total videos"
            2 -> if (one) "1 photo" else "$total photos"
            else -> if (one) "1 item" else "$total items"
        }
    }

    /** "From Music (51) and Downloads (7)": where the matches live, biggest first, at most four. Spaces with no items are left out. */
    fun origin(bySpace: Map<String, Int>, nameOf: (String) -> String?): String {
        val parts = bySpace.entries.filter { it.value > 0 }.sortedByDescending { it.value }.map { (nameOf(it.key) ?: it.key) + " (" + it.value + ")" }
        return if (parts.isEmpty()) "" else "From " + join(parts.take(4), "and")
    }

    /**
     * The rules "Save as smart space" makes from a tag filter: tags in A to Z order, once each. Any of the tags is one tag rule, all of
     * the tags is one rule per tag. With a [space] the filter keeps to that space (a music space's video songs stay in) and every rule
     * must match; otherwise the match mode follows [matchAll]. [onlyKind] keeps a filter to one kind.
     */
    fun tagFilterRules(tags: List<String>, matchAll: Boolean, space: String? = null, onlyKind: String? = null): RuleSet {
        val sorted = tags.distinct().sorted()
        val tagRules = if (matchAll) sorted.map { SmartRule("tag", listOf(it)) } else listOf(SmartRule("tag", sorted))
        return if (space != null) RuleSet(true, tagRules + SmartRule("space", listOf(space)), onlyKind)
        else RuleSet(matchAll, tagRules, onlyKind)
    }

    /** The name suggested for that smart space: "Arabic + Pop", the first three tags in A to Z order, at most 40 characters. */
    fun tagFilterName(tags: List<String>): String =
        tags.distinct().sorted().take(3).joinToString(" + ") { it.replaceFirstChar { c -> c.uppercase() } }.take(40)
}
