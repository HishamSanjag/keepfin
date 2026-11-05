package app.keepfin.core.dedupe

import app.keepfin.core.music.NOT_EOL
import app.keepfin.core.music.WS
import app.keepfin.core.music.isJsWhitespace
import app.keepfin.core.music.jsTrim
import app.keepfin.core.music.nfkd
import app.keepfin.core.music.removeFirstAtEnd

// The text rules behind "is this the same recording": title and artist normalisation. They are the desktop app's
// (gui/shared/library.ts, formerly) written without regex features whose meaning differs between platforms: no word-boundary
// shorthand, no end-of-input anchor on a pattern that has alternatives, no case-insensitive flag.

/** Words that make a recording a different version; they must survive normalisation. */
internal val VERSION_WORDS: List<String> =
    listOf("live", "remix", "acoustic", "instrumental", "extended", "slowed", "sped up", "nightcore", "karaoke", "cover")

private const val NOISE_WORDS =
    "(?:official|new|full|hd|hq|4k|8k|1080p|720p|lyrics?|lyric|audio|video|music|mv|m/v|visuali[sz]er|clip|oficial|officiel|with lyrics|explicit|clean|" +
        "remaster(?:ed)?(?: [0-9]{4})?|[0-9]{4} remaster(?:ed)?)"

// Bracketed text that is only release noise: "(Official Video)", "[HD]", "(Remastered 2011)".
private val NOISE_GROUP = Regex("^(?:$NOISE_WORDS(?:$WS+|$))+$")
private val FEAT_GROUP = Regex("^(?:feat\\.?|ft\\.?|featuring|with|prod\\.?)$WS")
private val BRACKETED = Regex("[(\\[{【「『]([^()\\[\\]{}【】「」『』]*)[)\\]}】」』]")

// Runs to the end of the text; used with removeFirstAtEnd. "lyric video" comes before "lyrics?" so the longer word is tried first.
private val TRAILING_NOISE = Regex(
    "$WS*[-|:–—]$WS*(?:official$WS+)?(?:music$WS+)?(?:video|audio|lyric video|lyrics?|visuali[sz]er)$WS*",
)
private val FEAT_TAIL = Regex("${WS}(?:feat\\.?|ft\\.?|featuring)$WS$NOT_EOL*")
private val TOPIC_SUFFIX = Regex("$WS+-$WS+topic")
private val MARKS = Regex("\\p{M}+")
private val NOT_LETTER_OR_NUMBER = Regex("[^\\p{L}\\p{N}]+")

/** Lower case, accents and other combining marks removed (compatibility decomposition first). */
internal fun fold(value: String): String = nfkd(value).replace(MARKS, "").lowercase()

private fun isWordChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_'

private fun isLeadSeparator(c: Char): Boolean = c == '-' || c == '–' || c == '—' || c == ':' || c == '|'

/** Removes "<lead> <separator> " from the start of [text] when it is there. */
private fun dropLead(text: String, lead: String): String {
    if (lead.isEmpty() || !text.startsWith(lead)) return text
    var i = lead.length
    while (i < text.length && isJsWhitespace(text[i])) i++
    if (i >= text.length || !isLeadSeparator(text[i])) return text
    i++
    while (i < text.length && isJsWhitespace(text[i])) i++
    return text.substring(i)
}

/** Lower-case, diacritic-free title with release noise removed. Version words are kept. */
internal fun normalizeTitle(raw: String, artist: String? = null): String {
    var text = fold(raw)
    text = BRACKETED.replace(text) { match ->
        val content = jsTrim(match.groupValues[1])
        if (NOISE_GROUP.containsMatchIn(content) || FEAT_GROUP.containsMatchIn(content)) " " else match.value
    }
    text = removeFirstAtEnd(FEAT_TAIL, removeFirstAtEnd(TRAILING_NOISE, text))
    val lead = if (artist != null) jsTrim(removeFirstAtEnd(TOPIC_SUFFIX, fold(artist))) else ""
    text = dropLead(text, lead)
    return jsTrim(text.replace("&", " and ").replace(NOT_LETTER_OR_NUMBER, " "))
}

/** The length of the credit separator that starts at [i] in [t] (", & ; /", "and", "x", "feat", "ft", "with"), or 0. */
private fun separatorAt(t: String, i: Int): Int {
    val c = t[i]
    if (c == ',' || c == '&' || c == ';' || c == '/') return 1
    if (i > 0 && isWordChar(t[i - 1])) return 0
    fun word(w: String) = t.startsWith(w, i) && (i + w.length == t.length || !isWordChar(t[i + w.length]))
    fun dotted(w: String): Int = w.length + (if (i + w.length < t.length && t[i + w.length] == '.') 1 else 0)
    if (word("and")) return 3
    if (word("x")) return 1
    // "featuring" is a whole word; "feat" and "ft" end at a dot or a word boundary, so "Feather" stays one name (DED-5).
    if (word("featuring")) return 9
    if (word("feat") || t.startsWith("feat.", i)) return dotted("feat")
    if (word("ft") || t.startsWith("ft.", i)) return dotted("ft")
    if (word("with")) return 4
    return 0
}

/** Primary artists of a credit line ("A feat. B & C" gives a, b, c), normalised. */
internal fun normalizeArtists(raw: String?): List<String> {
    if (raw.isNullOrEmpty()) return emptyList()
    val text = removeFirstAtEnd(TOPIC_SUFFIX, fold(raw)).removeSuffix("vevo")
    val parts = ArrayList<String>()
    var last = 0
    var i = 0
    while (i < text.length) {
        val length = separatorAt(text, i)
        if (length > 0) {
            parts.add(text.substring(last, i))
            i += length
            last = i
        } else {
            i++
        }
    }
    parts.add(text.substring(last))
    return parts.map { jsTrim(it.replace(NOT_LETTER_OR_NUMBER, " ")) }.filter { it.isNotEmpty() }
}

/** The version words a normalised title carries, in the order of [VERSION_WORDS]. */
internal fun versionsOf(normalizedTitle: String): List<String> {
    val padded = " $normalizedTitle "
    return VERSION_WORDS.filter { padded.contains(" $it ") }
}
