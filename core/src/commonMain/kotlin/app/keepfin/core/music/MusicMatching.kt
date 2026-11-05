package app.keepfin.core.music

import app.keepfin.core.json.jsonQuote
import app.keepfin.core.json.parseJson

/**
 * Matching a recording from a music service to a YouTube Music upload, shared by the desktop and Android apps.
 * The platforms only perform the requests listed by [searches]; every decision is made here.
 *
 * The behaviour is the desktop app's (gui/desktop/engine/music.ts), with three deliberate, portable changes:
 * confidences are rounded to 0.01 before they are compared, durations of zero count as unknown, and
 * word checks scan the normalised text instead of using a regex word boundary.
 */
object MusicMatching {
    const val MIN_CONFIDENCE = 0.45
    const val CONFIDENT = 0.8
    const val ALTERNATE_MIN_CONFIDENCE = 0.6
    const val ALTERNATE_MAX_DELTA_SEC = 7.0
    const val MAX_ALTERNATES = 3
    const val SEARCH_PAGE_SIZE = 8

    const val songsApiUrl: String = "https://music.youtube.com/youtubei/v1/search?prettyPrint=false"

    private const val SONGS_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA=="
    private const val BROWSER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Safari/537.36"

    /** Headers for the songs API request, in the order the desktop app sends them. */
    val songsApiHeaders: List<Pair<String, String>> = listOf(
        "User-Agent" to BROWSER_AGENT,
        "Accept-Language" to "en-US,en;q=0.9",
        "Content-Type" to "application/json",
        "Origin" to "https://music.youtube.com",
        "Referer" to "https://music.youtube.com/",
    )

    /** The JSON body of the songs API request for [query]. */
    fun songsApiBody(query: String): String =
        "{\"context\":{\"client\":{\"clientName\":\"WEB_REMIX\",\"clientVersion\":\"1.20250101.01.00\",\"hl\":\"en\",\"gl\":\"US\"}}," +
            "\"query\":${jsonQuote(query.take(300))},\"params\":${jsonQuote(SONGS_FILTER)}}"

    /** Candidates from an innertube search response (songs filter). Throws when [json] is not valid JSON. */
    fun parseSongsApi(json: String): List<Candidate> = songsFrom(parseJson(json))

    /** Candidates from the engine's flat-search JSON (`{"entries": [...]}`). Throws when [json] is not valid JSON. */
    fun flatCandidates(json: String, origin: Origin): List<Candidate> = flatFrom(parseJson(json), origin)

    /** The text to search for: the first two artists, then the title without featuring credits and edition suffixes. */
    fun query(track: Track): String = jsTrim("${track.artists.take(2).joinToString(" ")} ${coreTitle(track.title)}")

    /** The searches to run, in order: YouTube Music songs, YouTube Music flat, YouTube flat. */
    fun searches(track: Track): List<Search> {
        val query = query(track)
        return listOf(
            Search.SongsApi(query),
            Search.Flat("https://music.youtube.com/search?q=${encodeURIComponent(query)}#songs", Origin.YTM_FLAT, SEARCH_PAGE_SIZE),
            Search.Flat("ytsearch5:$query", Origin.YOUTUBE, SEARCH_PAGE_SIZE),
        )
    }

    // -- text ---------------------------------------------------------------------------------------------------

    private val COMBINING_MARKS = Regex("[\u0300-\u036F]")
    private val NOT_LETTER_OR_NUMBER = Regex("[^\\p{L}\\p{N}]+")

    /** Lower case, accents removed, "&" spelled out, and everything but letters and digits turned into single spaces. */
    fun normalize(value: String): String =
        jsTrim(
            nfkd(value)
                .replace(COMBINING_MARKS, "")
                .lowercase()
                .replace("&", " and ")
                .replace(NOT_LETTER_OR_NUMBER, " "),
        )

    internal fun tokens(value: String): Set<String> = normalize(value).split(' ').filter { it.isNotEmpty() }.toSet()

    private val FEAT_BRACKET = Regex("$WS*[(\\[](?:${ci("feat")}|${ci("ft")}|${ci("with")})\\.?$WS[^)\\]]*[)\\]]")
    private val FEAT_TAIL = Regex("$WS+(?:${ci("feat")}|${ci("ft")})\\.?$WS$NOT_EOL*")
    private val EDITION_SUFFIX = Regex(
        "$WS*-$WS*(?:${ci("remaster")}(?:${ci("ed")})?|[0-9]{4} ${ci("remaster")}(?:${ci("ed")})?|" +
            "${ci("single version")}|${ci("radio edit")}|${ci("mono")}|${ci("stereo")})[^-]*",
    )

    /** The title without featuring credits and edition suffixes, which services format differently. */
    fun coreTitle(title: String): String {
        var text = title.replace(FEAT_BRACKET, "")
        text = removeFirstAtEnd(FEAT_TAIL, text)
        text = removeFirstAtEnd(EDITION_SUFFIX, text)
        return jsTrim(text)
    }

    // -- scoring ------------------------------------------------------------------------------------------------

    internal fun overlap(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var shared = 0
        for (token in a) if (token in b) shared++
        return shared.toDouble() / a.size
    }

    private val UNWANTED = listOf(
        "live", "cover", "remix", "sped up", "slowed", "karaoke", "instrumental", "acoustic", "nightcore", "8d",
        "reverb", "lyrics", "reaction", "mashup", "extended",
        // Other versions of the same song that reissues list with an identical length (e.g. "(Drumless Edition)").
        "drumless", "acapella", "a cappella", "demo",
    )
    private val TOPIC_CHANNEL = Regex("$WS-$WS*${ci("topic")}")

    internal fun known(seconds: Double?): Boolean = seconds != null && seconds > 0

    /** 0 to 1. Duration, title and artist dominate; YouTube Music songs and Topic channels earn a bonus. Not rounded. */
    fun score(track: Track, candidate: Candidate): Double {
        val sourceTitle = normalize(track.title)
        val title = overlap(tokens(coreTitle(track.title)), tokens(candidate.title))
        val artistTokens = tokens(track.artists.joinToString(" "))
        val candidateArtists = tokens((candidate.artists + (candidate.channel ?: "")).joinToString(" "))
        val primary = tokens(track.artists.firstOrNull() ?: "")
        val artist = if (track.artists.isNotEmpty()) {
            maxOf(
                overlap(primary, candidateArtists),
                0.8 * overlap(artistTokens, candidateArtists),
                overlap(primary, tokens(candidate.title)) * 0.8,
            )
        } else 0.5
        val bothKnown = known(track.durationSec) && known(candidate.durationSec)
        var duration = 0.5
        if (bothKnown) {
            val delta = kotlin.math.abs(track.durationSec!! - candidate.durationSec!!)
            duration = if (delta <= 2) 1.0 else if (delta <= 7) 0.9 else if (delta <= 15) 0.6 else if (delta <= 30) 0.3 else 0.0
        }
        var score = 0.35 * title + 0.25 * artist + 0.3 * duration
        val channel = candidate.channel ?: ""
        if (candidate.origin == Origin.YTM_SONG) score += 0.1
        else if (matchStartAtEnd(TOPIC_CHANNEL, channel) >= 0 || asciiLower(channel).endsWith("vevo")) score += 0.08
        else {
            val lowerTitle = asciiLower(candidate.title)
            if (lowerTitle.contains("official audio") || lowerTitle.contains("official video")) score += 0.05
        }
        val candidateTitle = normalize(candidate.title)
        for (word in UNWANTED) {
            if (containsJsWord(candidateTitle, word) && !containsJsWord(sourceTitle, word)) score -= 0.25
        }
        if (bothKnown && kotlin.math.abs(track.durationSec!! - candidate.durationSec!!) > 60) score -= 0.3
        return maxOf(0.0, minOf(1.0, score))
    }

    internal fun confidence(track: Track, candidate: Candidate): Double = round2(score(track, candidate))

    /** Every candidate scoring at least [MIN_CONFIDENCE], once per video (first occurrence), best first; ties keep their order. */
    fun ranked(track: Track, candidates: List<Candidate>): List<Match> {
        val seen = HashSet<String>()
        val matches = ArrayList<Match>()
        for (candidate in candidates) {
            if (!seen.add(candidate.videoId)) continue
            val confidence = confidence(track, candidate)
            if (confidence >= MIN_CONFIDENCE) matches.add(Match(candidate, confidence))
        }
        return matches.sortedByDescending { it.confidence }
    }

    fun best(track: Track, candidates: List<Candidate>): Match? = ranked(track, candidates).firstOrNull()

    /** True when [best] is good enough to stop searching. */
    fun isConfident(best: Match?): Boolean = best != null && best.confidence >= CONFIDENT

    /** Other uploads of the same recording worth trying, best first. Excludes the original and videos already tried. */
    fun alternates(track: Track, candidates: List<Candidate>, exclude: Set<String>, limit: Int = MAX_ALTERNATES): List<Match> {
        val seen = HashSet<String>()
        val matches = ArrayList<Match>()
        for (candidate in candidates) {
            val id = candidate.videoId
            if (id in exclude || id in seen) continue
            seen.add(id)
            val confidence = confidence(track, candidate)
            if (confidence < ALTERNATE_MIN_CONFIDENCE) continue
            if (known(track.durationSec) && known(candidate.durationSec) &&
                kotlin.math.abs(track.durationSec!! - candidate.durationSec!!) > ALTERNATE_MAX_DELTA_SEC
            ) continue
            matches.add(Match(candidate, confidence))
        }
        return matches.sortedByDescending { it.confidence }.take(limit)
    }
}
