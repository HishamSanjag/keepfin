package app.keepfin.folder.sql

import app.keepfin.SmartField
import app.keepfin.SmartMatch
import app.keepfin.SmartRule
import app.keepfin.SmartRules

/**
 * Smart rules as a WHERE clause on the index. Rules that SQL decides exactly (tag, collection, space, kind, added within, favorite,
 * unplayed) are compiled as they are; a rule it cannot decide (artist, album, source) compiles to its widest form (everything) and
 * the result is [exact] = false, so the caller checks the rows against the rules. Values compare lower-case, like the core rules do.
 */
internal object SmartSql {
    class Compiled(val sql: String, val params: List<Any?>, val exact: Boolean)

    private const val DAY = 86_400_000.0

    private fun json(values: List<String>): String {
        val sb = StringBuilder("[")
        values.forEachIndexed { i, v ->
            if (i > 0) sb.append(',')
            sb.append('"')
            for (c in v) when (c) { '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); else -> sb.append(c) }
            sb.append('"')
        }
        return sb.append(']').toString()
    }

    fun compile(smart: SmartRules, wire: (String) -> String, now: Long = System.currentTimeMillis()): Compiled {
        if (smart.rules.isEmpty()) return Compiled("0", emptyList(), true) // no rules gather nothing
        val params = ArrayList<Any?>()
        var exact = true
        val parts = ArrayList<String>()
        for (rule in smart.rules) {
            when (rule) {
                is SmartRule.AddedWithin -> { parts += "i.added >= ?"; params += (now - maxOf(0, rule.days) * DAY).toLong() }
                SmartRule.Favorite -> parts += "i.fav = 1"
                SmartRule.Unplayed -> parts += "i.plays = 0"
                is SmartRule.Values -> {
                    val values = rule.values.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                    if (values.isEmpty()) { parts += "0"; continue }
                    when (rule.field) {
                        SmartField.TAG -> { parts += "i.id IN (SELECT t.id FROM lib_tags t WHERE t.tag IN (SELECT value FROM json_each(?)))"; params += json(values) }
                        SmartField.COLLECTION -> {
                            parts += "i.id IN (SELECT e.item FROM entries e WHERE lower(e.playlist) IN (SELECT value FROM json_each(?)) AND e.entry NOT IN (SELECT entry FROM removed_entries))"
                            params += json(values)
                        }
                        SmartField.SPACE -> { parts += "lower(i.space) IN (SELECT value FROM json_each(?))"; params += json(values.map { wire(it).lowercase() }) }
                        SmartField.KIND -> { parts += "i.media IN (SELECT value FROM json_each(?))"; params += json(values) }
                        else -> { parts += "1"; exact = false }
                    }
                }
            }
        }
        val joiner = if (smart.match == SmartMatch.ALL) " AND " else " OR "
        var sql = parts.joinToString(joiner, "(", ")")
        // In "any" mode a rule that is not decided makes the whole clause "everything"; "all" mode narrows by the others.
        if (!exact && smart.match == SmartMatch.ANY) { sql = "1"; params.clear() }
        if (smart.onlyKind != null) { sql += " AND i.media = ?"; params += smart.onlyKind!!.wire }
        if (smart.excludeSpaces.isNotEmpty()) { sql += " AND i.space NOT IN (SELECT value FROM json_each(?))"; params += json(smart.excludeSpaces.map { wire(it) }) }
        return Compiled(sql, params, exact)
    }
}
