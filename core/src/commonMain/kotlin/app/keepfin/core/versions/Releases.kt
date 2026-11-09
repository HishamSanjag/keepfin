package app.keepfin.core.versions

import app.keepfin.core.music.JS_WS
import app.keepfin.core.music.asciiLower
import app.keepfin.core.music.isJsWhitespace
import app.keepfin.core.music.jsTrim

// Release versions, update channels and release-notes highlights. The behaviour is the desktop app's
// (gui/shared/updates.ts). The core makes decisions only: each app still fetches its own release feed.

/** Which releases an app is offered: stable only, or the newest of stable and beta. */
enum class Channel(val wire: String) {
    STABLE("stable"),
    BETA("beta"),
    ;

    companion object {
        /** Missing or unknown values mean [STABLE]. */
        fun of(value: String?): Channel = entries.firstOrNull { it.wire == value } ?: STABLE
    }
}

/** The platform whose section of the release notes is shown. */
enum class Platform(val wire: String) {
    WINDOWS("windows"),
    ANDROID("android"),
    ;

    companion object {
        fun of(value: String?): Platform = entries.firstOrNull { it.wire == value } ?: WINDOWS
    }
}

object Releases {
    /** [beta] is null for a final release. */
    private class Version(val major: Long, val minor: Long, val patch: Long, val beta: Long?)

    private fun isDigits(text: String): Boolean = text.isNotEmpty() && text.all { it in '0'..'9' }

    private fun number(text: String): Long? = if (isDigits(text) && text.length <= 15) text.toLong() else null

    private fun isLineTerminator(c: Char): Boolean = c == '\n' || c == '\r' || c == ' ' || c == ' '

    /**
     * `X.Y.Z` or `X.Y.Z-beta.N`, the only two shapes that exist. A leading `v`, surrounding whitespace and a
     * `+build` suffix are tolerated (release tags are `vX.Y.Z`); anything else, such as `-rc.1`, is invalid.
     */
    private fun parse(value: String): Version? {
        var text = jsTrim(value)
        if (text.startsWith("v")) text = text.substring(1)
        val plus = text.indexOf('+')
        if (plus >= 0) {
            if (text.substring(plus + 1).any { isLineTerminator(it) }) return null
            text = text.substring(0, plus)
        }
        val dash = text.indexOf('-')
        val core = if (dash >= 0) text.substring(0, dash) else text
        val parts = core.split('.')
        if (parts.size != 3) return null
        val numbers = parts.map { number(it) ?: return null }
        var beta: Long? = null
        if (dash >= 0) {
            val pre = text.substring(dash + 1)
            if (!pre.startsWith("beta.")) return null
            beta = number(pre.substring(5)) ?: return null
        }
        return Version(numbers[0], numbers[1], numbers[2], beta)
    }

    fun isValid(value: String): Boolean = parse(value) != null

    /** True for `X.Y.Z-beta.N`; false for final releases and for text that is not a version. */
    fun isPrerelease(value: String): Boolean = parse(value)?.beta != null

    /**
     * Semver ordering: 0.4.2 < 0.5.0-beta.1 < 0.5.0-beta.2 < 0.5.0-beta.10 < 0.5.0 < 0.5.1.
     * Text that is not a version sorts lowest. Returns -1, 0 or 1.
     */
    fun compare(a: String, b: String): Int {
        val x = parse(a)
        val y = parse(b)
        if (x == null || y == null) return if (x != null) 1 else if (y != null) -1 else 0
        if (x.major != y.major) return if (x.major < y.major) -1 else 1
        if (x.minor != y.minor) return if (x.minor < y.minor) -1 else 1
        if (x.patch != y.patch) return if (x.patch < y.patch) -1 else 1
        val p = x.beta
        val q = y.beta
        if (p == null || q == null) return if (p == q) 0 else if (p != null) -1 else 1
        return if (p == q) 0 else if (p < q) -1 else 1
    }

    /**
     * Whether "What's new" is due: the version running ([current]) is newer than the last one whose notes were seen ([seen]).
     * Nothing stored (a fresh install), the same version, an older one (a downgrade) or an unreadable stored value show nothing.
     * It depends only on the stored version, never on how the new version was installed.
     */
    fun whatsNewDue(seen: String?, current: String): Boolean =
        seen != null && isValid(seen) && isValid(current) && compare(current, seen) > 0

    /**
     * The newest release [channel] offers from a list of release versions (any order): Stable ignores
     * pre-releases, Beta takes the newest of both. Returns null unless it is newer than [current], so a
     * build is never offered a downgrade. The returned string is the list entry as given.
     */
    fun pick(versions: List<String>, channel: Channel, current: String): String? {
        var best: String? = null
        for (version in versions) {
            if (!isValid(version) || (channel == Channel.STABLE && isPrerelease(version))) continue
            if (best == null || compare(version, best) >= 0) best = version
        }
        return if (best != null && compare(best, current) > 0) best else null
    }

    // -- Release-notes highlights -------------------------------------------------------------------------

    private val IMAGE = Regex("!\\[[^\\]]*\\]\\([^)]*\\)")
    private val LINK = Regex("\\[([^\\]]+)\\]\\([^)]*\\)")
    private val TAG = Regex("<[^>]+>")
    private val MARKS = Regex("(\\*\\*|__|\\*|_|`|~~)")
    private val SPACES = Regex("[$JS_WS]+")

    private fun plainText(value: String): String {
        var text = IMAGE.replace(value, "")
        text = LINK.replace(text) { it.groupValues[1] }
        text = TAG.replace(text, "")
        text = MARKS.replace(text, "")
        text = SPACES.replace(text, " ")
        return jsTrim(text)
    }

    private fun trimEnd(value: String): String {
        var end = value.length
        while (end > 0 && isJsWhitespace(value[end - 1])) end--
        return value.substring(0, end)
    }

    /** Index after 1 to 6 `#` and the whitespace that must follow them, starting at [from]; -1 when the text there is not that. */
    private fun headingMarks(line: String, from: Int): Int {
        var i = from
        while (i < line.length && line[i] == '#') i++
        val marks = i - from
        if (marks !in 1..6) return -1
        val spaceStart = i
        while (i < line.length && isJsWhitespace(line[i])) i++
        return if (i > spaceStart) i else -1
    }

    /** True when some line starts with 1 to 6 `#`, whitespace and then a visible character. */
    private fun hasHeadings(notes: String): Boolean {
        var start = 0
        while (true) {
            val after = headingMarks(notes, start)
            if (after in 0 until notes.length && !isJsWhitespace(notes[after])) return true
            var next = start
            while (next < notes.length && !isLineTerminator(notes[next])) next++
            if (next >= notes.length) return false
            start = next + 1
        }
    }

    private fun lineTail(line: String, from: Int): String? {
        val tail = line.substring(from)
        return if (tail.any { isLineTerminator(it) }) null else tail
    }

    private fun mentions(heading: String, vararg words: String): Boolean {
        val lower = asciiLower(heading)
        return words.any { lower.contains(it) }
    }

    /**
     * The few bullets worth showing in the "update available" dialog. Release notes carry one section per
     * platform ("## Windows", "## Android"); only the bullets of the platform asked for are used, or every
     * bullet when the notes have no platform headings. At most [limit] short plain-text lines are returned.
     */
    fun highlights(notes: String?, platform: Platform, limit: Int): List<String> {
        if (notes.isNullOrEmpty()) return emptyList()
        val windows = platform == Platform.WINDOWS
        val wanted = if (windows) arrayOf("windows", "desktop") else arrayOf("android", "phone")
        val other = if (windows) arrayOf("android", "phone") else arrayOf("windows", "desktop")
        var inSection: Boolean? = if (hasHeadings(notes)) null else true
        val own = ArrayList<String>()
        val shared = ArrayList<String>()
        for (raw in notes.split('\n')) {
            val line = raw.removeSuffix("\r")
            val headingAt = headingMarks(line, 0)
            val heading = if (headingAt >= 0) lineTail(line, headingAt) else null
            if (heading != null) {
                inSection = if (mentions(heading, *wanted)) true else if (mentions(heading, *other)) false else null
                continue
            }
            var i = 0
            while (i < line.length && isJsWhitespace(line[i])) i++
            if (i >= line.length || line[i] !in "-*+") continue
            var j = i + 1
            while (j < line.length && isJsWhitespace(line[j])) j++
            if (j == i + 1) continue
            val bullet = lineTail(line, j) ?: continue
            val text = plainText(bullet)
            if (text.isEmpty()) continue
            val clipped = if (text.length > 140) trimEnd(text.substring(0, 137)) + "…" else text
            if (inSection == true) own.add(clipped) else if (inSection == null) shared.add(clipped)
        }
        return (own + shared).take(maxOf(limit, 0))
    }
}
