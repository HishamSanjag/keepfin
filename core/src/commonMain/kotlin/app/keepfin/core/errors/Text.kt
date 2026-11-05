package app.keepfin.core.errors

import app.keepfin.core.music.JS_WS
import app.keepfin.core.music.NOT_EOL
import app.keepfin.core.music.WS
import app.keepfin.core.music.asciiLower
import app.keepfin.core.music.ci
import app.keepfin.core.music.jsTrim

// Text handling for failure output: matching form, last meaningful line, tool-name scrubbing, details.
// The behaviour is the desktop's (gui/shared/errors.ts); no regex shorthands, so every platform runs it identically.

internal fun isWordChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

/** The form rules match against: ASCII lower case with typographic apostrophes straightened. */
internal fun matchingForm(text: String): String = asciiLower(text).replace('’', '\'').replace('‘', '\'')

/** JavaScript `split(/\r?\n/)`. */
internal fun splitLines(text: String): List<String> = text.split("\n").map { if (it.endsWith("\r")) it.dropLast(1) else it }

private fun startsAt(lower: String, index: Int, word: String): Boolean = lower.startsWith(word, index)

private fun isSpace(c: Char): Boolean = jsTrim(c.toString()).isEmpty()

/** Index of the first non-whitespace character at or after [from]. */
private fun skipWs(text: String, from: Int): Int {
    var i = from
    while (i < text.length && isSpace(text[i])) i++
    return i
}

/** The most informative line: the last ERROR line, else the last non-empty line (at most 500 characters). */
internal fun lastErrorLine(stderr: String): String? {
    val lines = splitLines(stderr).map { jsTrim(it) }.filter { it.isNotEmpty() }
    val error = lines.lastOrNull { asciiLower(it).let { l -> l.startsWith("error") || l.startsWith("[error]") } }
    return (error ?: lines.lastOrNull())?.take(500)
}

private val seeLink = Regex("$WS*[Ss][Ee][Ee]$WS+[Hh][Tt][Tt][Pp][Ss]?://[^$JS_WS]+$WS+[Ff][Oo][Rr]$NOT_EOL*")

private fun cleanDetail(line: String?, platform: Platform): String? {
    if (line == null) return null
    var text = line
    if (asciiLower(text).startsWith("error:")) text = text.substring(6).let { it.substring(skipWs(it, 0)) }
    val at = asciiLower(text).indexOf("please report this issue")
    if (at >= 0) {
        var j = at
        while (j > 0 && isSpace(text[j - 1])) j--
        if (j > 0 && text[j - 1] == ';') {
            j--
            while (j > 0 && isSpace(text[j - 1])) j--
        }
        text = text.substring(0, j)
    }
    text = seeLink.replace(text, "")
    return Scrub.scrub(jsTrim(text), platform)
}

internal fun detailLine(stderr: String, platform: Platform): String? = cleanDetail(lastErrorLine(stderr), platform)

/** Tool-name scrubbing for text that may reach the user. */
object Scrub {
    private fun replaceWhere(text: String, replacement: String, matchLength: (String, Int) -> Int): String {
        val lower = asciiLower(text)
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val length = if (i == 0 || !isWordChar(text[i - 1])) matchLength(lower, i) else 0
            if (length > 0) {
                out.append(replacement)
                i += length
            } else {
                out.append(text[i])
                i++
            }
        }
        return out.toString()
    }

    private fun wordEnd(lower: String, end: Int): Boolean = end >= lower.length || !isWordChar(lower[end])

    private fun dashOrWordEnd(lower: String, end: Int): Boolean = end >= lower.length || !(isWordChar(lower[end]) || lower[end] == '-')

    /** Length of `name(\.exe)?` followed by [ok], or 0. */
    private fun withExe(lower: String, i: Int, nameLength: Int, ok: (String, Int) -> Boolean): Int {
        val end = i + nameLength
        if (startsAt(lower, end, ".exe") && ok(lower, end + 4)) return nameLength + 4
        return if (ok(lower, end)) nameLength else 0
    }

    private fun gallery(lower: String, i: Int): Int = if (startsAt(lower, i, "gallery-dl") && wordEnd(lower, i + 10)) 10 else 0

    private fun downloadEngine(lower: String, i: Int): Int {
        for (name in listOf("yt-dlp", "yt_dlp", "ytdlp", "youtube-dl", "youtubedl")) {
            if (startsAt(lower, i, name)) withExe(lower, i, name.length, ::dashOrWordEnd).let { if (it > 0) return it }
        }
        return 0
    }

    private fun mediaProcessor(lower: String, i: Int): Int {
        for (name in listOf("ffmpeg", "ffprobe")) {
            if (startsAt(lower, i, name)) withExe(lower, i, name.length, ::wordEnd).let { if (it > 0) return it }
        }
        return 0
    }

    private fun aria(lower: String, i: Int): Int {
        if (!startsAt(lower, i, "aria2")) return 0
        if (startsAt(lower, i + 5, "c") && wordEnd(lower, i + 6)) return 6
        return if (wordEnd(lower, i + 5)) 5 else 0
    }

    /** Of a run of "the the the " (any case, starting at a word boundary) keeps only the last "the ". */
    private fun collapseRepeatedThe(text: String): String {
        val lower = asciiLower(text)
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            if ((i == 0 || !isWordChar(text[i - 1])) && startsAt(lower, i, "the ")) {
                var j = i
                while (startsAt(lower, j, "the ")) j += 4
                if (j - i >= 8) {
                    i = j - 4
                    continue
                }
            }
            out.append(text[i])
            i++
        }
        return out.toString()
    }

    private val androidTags = Regex("\\[[A-Za-z0-9_:]+\\]$WS*")
    private val androidModules = Regex("(${ci("yt")}[-_]?${ci("dlp")}|${ci("gallery")}[-_]${ci("dl")})(\\.[A-Za-z0-9_]+)+")
    private val androidExtras = Regex("${ci("python")}[0-9]*|${ci("mutagen")}")
    private val runs = Regex("$WS{2,}")

    private fun stripLeadingLabel(text: String): String {
        val start = skipWs(text, 0)
        val lower = asciiLower(text)
        val label = listOf("error:", "warning:").firstOrNull { startsAt(lower, start, it) } ?: return text
        return text.substring(skipWs(text, start + label.length))
    }

    private fun stripLeadingBracket(text: String): String {
        if (!text.startsWith("[")) return text
        var i = 1
        while (i < text.length && (text[i] in 'A'..'Z' || text[i] in 'a'..'z' || text[i] in '0'..'9' || text[i] in ":_.-")) i++
        if (i == 1 || i >= text.length || text[i] != ']') return text
        return text.substring(skipWs(text, i + 1))
    }

    /**
     * Removes tool names and raw log prefixes from text that may reach the UI. The Android build also strips
     * "ERROR:" and "[extractor]" tags anywhere, module paths of the engine, and the names of its runtime and tagger.
     */
    fun scrub(raw: String, platform: Platform = Platform.DESKTOP): String {
        var text = raw
        if (platform == Platform.ANDROID) {
            text = text.replace("ERROR:", "").replace(androidTags, "")
            text = androidModules.replace(text) { it.groupValues[1] }
            text = androidExtras.replace(text, "the download engine")
        }
        text = stripLeadingBracket(stripLeadingLabel(text))
        text = replaceWhere(text, "the gallery engine", ::gallery)
        text = replaceWhere(text, "the download engine", ::downloadEngine)
        text = replaceWhere(text, "the media processor", ::mediaProcessor)
        text = replaceWhere(text, "Turbo", ::aria)
        text = collapseRepeatedThe(text)
        for (name in listOf("download engine", "gallery engine", "media processor")) {
            if (text.startsWith("the $name")) text = "The " + text.substring(4)
        }
        return jsTrim(runs.replace(text, " "))
    }
}
