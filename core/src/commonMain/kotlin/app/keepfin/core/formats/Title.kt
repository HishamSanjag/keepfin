package app.keepfin.core.formats

import app.keepfin.core.music.JS_WS
import app.keepfin.core.music.ci
import app.keepfin.core.music.jsTrim

/**
 * The "clean title" rule (fixtures/engine/formats/SCHEMA.md): posts use their whole caption as the title; rows,
 * notifications, file names and tags want a short one. The behaviour is the desktop app's (`gui/shared/title.ts`).
 */
object Title {
    const val MAX = 100
    private const val MIN_CUT = 60

    private const val WS = "[$JS_WS]"
    private val wsRuns = Regex("$WS+")
    private val lineBreak = Regex("\r\n|[\n\r  ]")
    private const val COUNT = "[0-9][0-9.,]*[KMBkmb]?"
    private val ITEM = "$COUNT (?:${ci("views?")}|${ci("reactions?")}|${ci("comments?")}|${ci("shares?")})"
    private val facebookPrefix = Regex("^$ITEM(?: ?· ?$ITEM)* ?\\| ?")
    private val urls = Regex("(?:${ci("https?")}://|${ci("www")}\\.)[^$JS_WS]+")
    private const val DECOR = "-–—|·•:"

    private fun collapse(value: String) = jsTrim(value.replace(wsRuns, " "))

    private fun firstLine(value: String): String {
        for (line in value.split(lineBreak)) {
            val text = collapse(line)
            if (text.isNotEmpty()) return text
        }
        return ""
    }

    private fun isTag(token: String) = (token.startsWith("#") || token.startsWith("@")) && token.length > 1

    /** Trims decorative separators and trailing ellipses at both ends, repeatedly. */
    private fun trimDecor(value: String): String {
        var text = value
        while (true) {
            val before = text
            text = jsTrim(text)
            if (text.endsWith("…")) text = text.dropLast(1)
            else if (text.endsWith("...")) text = text.dropLast(3)
            while (text.isNotEmpty() && text.last() in DECOR) text = text.dropLast(1)
            while (text.isNotEmpty() && text.first() in DECOR) text = text.drop(1)
            if (text == before) return jsTrim(text)
        }
    }

    /** Code points as strings; a lone surrogate counts as one, like JavaScript's Array.from. */
    private fun codePoints(value: String): List<String> {
        val out = ArrayList<String>(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()) {
                out.add(value.substring(i, i + 2))
                i += 2
            } else {
                out.add(c.toString())
                i++
            }
        }
        return out
    }

    /** Shortens to [MAX] code points at a word boundary. */
    private fun shorten(value: String): String {
        val chars = codePoints(value)
        if (chars.size <= MAX) return value
        var cut = MAX
        for (i in MAX downTo MIN_CUT) {
            if (chars[i] == " ") {
                cut = i
                break
            }
        }
        var head = chars.subList(0, cut).joinToString("")
        while (head.isNotEmpty() && (head.last() == ' ' || head.last() == ',' || head.last() == ';' || head.last() in DECOR)) head = head.dropLast(1)
        return "$head…"
    }

    /** Short display title for a media item or collection entry. [kind] is "video", "audio" or "post". */
    fun clean(raw: String?, uploader: String?, provider: String, kind: String = "video"): String {
        val by = if (uploader != null) collapse(uploader) else ""
        var text = firstLine(raw ?: "")
        // Any site: "1.2K views · 34 reactions | caption | Page" wrapping. The page-name suffix is only dropped in that
        // wrapped form (or on Facebook), so titles like "Song | Artist" elsewhere stay whole.
        val wrapped = facebookPrefix.containsMatchIn(text)
        text = text.replace(facebookPrefix, "")
        if (wrapped || provider == "Facebook") {
            if (by.isNotEmpty() && text.lowercase() == by.lowercase()) text = ""
            if (by.isNotEmpty() && text.lowercase().endsWith(" | " + by.lowercase())) {
                text = text.substring(0, maxOf(0, text.length - by.length - 3))
            }
        }
        text = collapse(text.replace(urls, " "))
        val tokens = (if (text.isEmpty()) emptyList() else text.split(' ')).toMutableList()
        if (tokens.isNotEmpty() && tokens.all(::isTag)) {
            text = tokens.take(3).joinToString(" ")
        } else {
            while (tokens.isNotEmpty() && isTag(tokens.last())) tokens.removeAt(tokens.size - 1)
            text = tokens.joinToString(" ")
        }
        text = trimDecor(text)
        if (text.isNotEmpty()) return shorten(text)
        val noun = if (kind == "post") "post" else if (kind == "audio") "audio" else "video"
        return "$provider $noun${if (by.isNotEmpty()) " by $by" else ""}"
    }
}
