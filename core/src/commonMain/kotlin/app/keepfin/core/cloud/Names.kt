package app.keepfin.core.cloud

import app.keepfin.core.providers.nfkc

// Name rules per provider (dev-docs/plans/KEEPFIN-CLOUD-SPEC.md 7.1 #6). A library name that a provider cannot store is escaped
// reversibly: every character that must change becomes %XX (its UTF-16 code in two hex digits) and a real % always becomes
// %25, so decode(encode(name)) == name for every valid name. A name that still cannot be stored (too long, an unpaired
// surrogate) goes through the name map of the copies spec 5.4; a collision of case or Unicode form is a NameCollision for review.

internal class NameRules(
    val provider: String,
    /** Characters the provider rejects anywhere in a name. */
    val forbidden: String,
    val caseInsensitive: Boolean,
    /** Longest stored name: UTF-16 units (Graph) or UTF-8 bytes (the others). */
    val maxName: Int,
    val maxNameInBytes: Boolean,
    /** Longest full decoded path in UTF-16 units, or 0 for no stated limit. */
    val maxPath: Int,
    /** Device names that are reserved with or without an extension (case-insensitive). */
    val deviceNames: Boolean,
    /** Names (case-insensitive) the provider hides, ignores or refuses. */
    val reservedNames: Set<String>,
    val reservedPrefixes: List<String>,
    val reservedSuffixes: List<String>,
    val reservedInfixes: List<String>,
    val trailingDotOrSpace: Boolean,
    val leadingSpace: Boolean,
)

private val DEVICES = setOf("con", "prn", "aux", "nul") + (0..9).flatMap { listOf("com$it", "lpt$it") }

private fun lowerAscii(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s) sb.append(if (c in 'A'..'Z') (c.code + 32).toChar() else c)
    return sb.toString()
}

internal object NameTables {
    private val control = (0..31).map { it.toChar() }.joinToString("")
    val drive = NameRules("drive", "", false, 255, true, 0, false, emptySet(), emptyList(), emptyList(), emptyList(), false, false)
    val graph = NameRules(
        "graph", "\"*:<>?/\\|$control", true, 255, false, 400, true,
        setOf(".lock", "desktop.ini"), listOf("~$"), emptyList(), listOf("_vti_"), true, true,
    )
    val dropbox = NameRules(
        "dropbox", "<>:\"/\\|?*$control", true, 255, true, 0, false,
        setOf("desktop.ini", "thumbs.db", ".ds_store", ".dropbox", ".dropbox.attr", ".dropbox.cache", "icon\r"), listOf("~$"), emptyList(), emptyList(), true, false,
    )
    val webdav = NameRules(
        "webdav", "\\/<>:\"|?*$control", true, 255, true, 0, false,
        setOf(".htaccess"), emptyList(), listOf(".part", ".filepart"), emptyList(), true, true,
    )

    fun of(provider: String): NameRules = when (provider) {
        "drive" -> drive
        "graph" -> graph
        "dropbox" -> dropbox
        "webdav" -> webdav
        else -> throw IllegalArgumentException("unknown provider '$provider'")
    }
}

private fun percentCode(c: Char): String {
    val v = c.code
    return "%" + "0123456789ABCDEF"[(v shr 4) and 15] + "0123456789ABCDEF"[v and 15]
}

/** The name as stored on the provider. */
internal fun encodeName(rules: NameRules, name: String): String {
    val chars = name.toCharArray()
    val escape = BooleanArray(chars.size)
    for (i in chars.indices) {
        val c = chars[i]
        if (c == '%' || rules.forbidden.indexOf(c) >= 0) escape[i] = true
    }
    if (chars.isNotEmpty()) {
        val lower = lowerAscii(name)
        if (rules.deviceNames) {
            val base = lower.substringBefore('.')
            if (base in DEVICES) escape[0] = true
        }
        if (lower in rules.reservedNames) escape[0] = true
        if (rules.reservedPrefixes.any { lower.startsWith(it) }) escape[0] = true
        for (s in rules.reservedSuffixes) if (lower.endsWith(s)) escape[chars.size - 1] = true
        for (s in rules.reservedInfixes) {
            val at = lower.indexOf(s)
            if (at >= 0) escape[at] = true
        }
        if (rules.leadingSpace && chars[0] == ' ') escape[0] = true
        if (rules.trailingDotOrSpace) {
            var last = chars.size - 1
            while (last >= 0 && (chars[last] == ' ' || chars[last] == '.')) {
                escape[last] = true
                last--
            }
        }
    }
    val sb = StringBuilder(name.length + 8)
    for (i in chars.indices) if (escape[i]) sb.append(percentCode(chars[i])) else sb.append(chars[i])
    return sb.toString()
}

private fun hexDigit(c: Char): Int = when (c) {
    in '0'..'9' -> c - '0'
    in 'A'..'F' -> c - 'A' + 10
    in 'a'..'f' -> c - 'a' + 10
    else -> -1
}

/** The library name for a stored name (the inverse of [encodeName]). */
internal fun decodeName(stored: String): String {
    if (stored.indexOf('%') < 0) return stored
    val sb = StringBuilder(stored.length)
    var i = 0
    while (i < stored.length) {
        val c = stored[i]
        if (c == '%' && i + 2 < stored.length && hexDigit(stored[i + 1]) >= 0 && hexDigit(stored[i + 2]) >= 0) {
            sb.append(((hexDigit(stored[i + 1]) shl 4) or hexDigit(stored[i + 2])).toChar())
            i += 3
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}

private fun hasLoneSurrogate(s: String): Boolean {
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c.isHighSurrogate()) {
            if (i + 1 >= s.length || !s[i + 1].isLowSurrogate()) return true
            i += 2
            continue
        }
        if (c.isLowSurrogate()) return true
        i++
    }
    return false
}

/** Why [name] cannot be stored even after escaping, or null when it can. */
internal fun nameProblem(rules: NameRules, name: String): String? {
    if (name.isEmpty()) return "empty"
    if (name == "." || name == "..") return "dot-name"
    if (hasLoneSurrogate(name)) return "invalid-unicode"
    val stored = encodeName(rules, name)
    val size = if (rules.maxNameInBytes) stored.encodeToByteArray().size else stored.length
    if (size > rules.maxName) return "too-long"
    return null
}

/** The comparison key for collisions: Unicode compatibility form, then lower case on providers that fold case. */
internal fun nameKey(rules: NameRules, name: String): String {
    val form = nfkc(name)
    return if (rules.caseInsensitive) form.lowercase() else form
}

internal fun pathProblem(rules: NameRules, decodedPath: String): String? {
    if (rules.maxPath > 0 && decodedPath.length > rules.maxPath) return "path-too-long"
    return null
}

internal fun namesOp(op: String, a: J): Any? {
    val rules = NameTables.of(a.need("provider"))
    return when (op) {
        "nameEncode" -> linkedMapOf("encoded" to encodeName(rules, a.need("name")))
        "nameDecode" -> linkedMapOf("name" to decodeName(a.need("stored")))
        "nameCheck" -> {
            val name = a.need("name")
            val problem = nameProblem(rules, name)
            mapOfNotNull("ok" to (problem == null), "reason" to problem, "stored" to (if (problem == null) encodeName(rules, name) else null))
        }
        "nameKey" -> linkedMapOf("key" to nameKey(rules, a.need("name")))
        "pathCheck" -> {
            val problem = pathProblem(rules, a.need("path"))
            mapOfNotNull("ok" to (problem == null), "reason" to problem)
        }
        else -> throw IllegalArgumentException("unknown names op '$op'")
    }
}
