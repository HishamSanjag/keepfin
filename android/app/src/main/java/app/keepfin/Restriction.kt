package app.keepfin

import app.keepfin.core.providers.Naming
import app.keepfin.core.providers.Providers as CoreProviders

/**
 * YouTube "Topic" art tracks (and a few other uploads) are sometimes marked unplayable for anonymous requests from a region
 * on every client: the engine only reports "Video unavailable". The anonymous watch page still names the video, so a pure
 * parser can tell that apart from a transient failure, and music-like items can be saved from another upload of the song.
 */
object Restriction {
    /** Source of a [MusicMatch] that points at another upload of the same song. */
    const val SOURCE = "youtube"
    /** Marker put in raw failure text so [ErrorMessages] classifies it as permanent and words it. */
    const val MARKER_MUSIC = app.keepfin.core.errors.Messages.MARKER_MUSIC
    const val MARKER_OTHER = app.keepfin.core.errors.Messages.MARKER_OTHER
    const val MUSIC_MESSAGE = app.keepfin.core.errors.Messages.RESTRICTED_MUSIC
    const val OTHER_MESSAGE = app.keepfin.core.errors.Messages.RESTRICTED_OTHER
    const val SAVED_FROM_OTHER = "Saved from another upload of this song"

    const val MIN_CONFIDENCE = 0.6
    const val MAX_ALTERNATES = 3

    data class Probe(val status: String, val reason: String?, val videoId: String?, val title: String?, val author: String?, val lengthSeconds: Double?)
    enum class Verdict { RESTRICTED, PLAYABLE, UNKNOWN }

    private val unavailable = Regex("video unavailable|this video is (not available|unavailable)", RegexOption.IGNORE_CASE)
    private val regional = Regex("in your country|geo.?restrict|from your location|blocked it", RegexOption.IGNORE_CASE)

    /** A generic unavailable failure that is not a removal, region, sign-in or throttling message already handled elsewhere. */
    fun isUnavailable(raw: String?): Boolean {
        val text = raw.orEmpty()
        return unavailable.containsMatchIn(text) && !regional.containsMatchIn(text) && !ErrorMessages.throttled(text) && !ErrorMessages.explicitlyPermanent(text)
    }

    private val playability = Regex("\"playabilityStatus\"\\s*:\\s*\\{\\s*\"status\"\\s*:\\s*\"([A-Z_]+)\"(?:\\s*,\\s*\"reason\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\")?")
    private val details = Regex("\"videoDetails\"\\s*:\\s*\\{\\s*\"videoId\"\\s*:\\s*\"([A-Za-z0-9_-]{11})\"\\s*,\\s*\"title\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*,\\s*\"lengthSeconds\"\\s*:\\s*\"(\\d+)\"")
    private val author = Regex("\"author\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")

    private fun unescape(value: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c != '\\' || i + 1 >= value.length) { out.append(c); i++; continue }
            when (val n = value[i + 1]) {
                'n' -> out.append('\n'); 't' -> out.append('\t'); 'r' -> out.append('\r')
                'u' -> {
                    val code = value.substring(minOf(i + 2, value.length), minOf(i + 6, value.length)).toIntOrNull(16)
                    if (code != null && i + 6 <= value.length) { out.append(code.toChar()); i += 6; continue } else out.append('u')
                }
                else -> out.append(n)
            }
            i += 2
        }
        return out.toString()
    }

    /** Pure: playability status/reason and video details from an anonymous watch page. Null when the page has no player response. */
    fun parse(html: String): Probe? {
        val status = playability.find(html) ?: return null
        val start = details.find(html)
        val tail = start?.let { html.substring(it.range.last, minOf(html.length, it.range.last + 4000)) }
        return Probe(
            status = status.groupValues[1], reason = status.groupValues[2].takeIf { it.isNotEmpty() }?.let(::unescape),
            videoId = start?.groupValues?.get(1), title = start?.groupValues?.get(2)?.let(::unescape),
            author = tail?.let { author.find(it)?.groupValues?.get(1) }?.let(::unescape),
            lengthSeconds = start?.groupValues?.get(3)?.toDoubleOrNull()?.takeIf { it > 0 },
        )
    }

    /** UNPLAYABLE with video details is deterministic; OK means the failure was transient; anything else is left to the normal path. */
    fun verdict(probe: Probe?): Verdict = when {
        probe == null -> Verdict.UNKNOWN
        probe.status == "UNPLAYABLE" && probe.videoId != null && probe.title != null -> Verdict.RESTRICTED
        probe.status == "OK" -> Verdict.PLAYABLE
        else -> Verdict.UNKNOWN
    }

    /** The 11-character video id of a YouTube / YouTube Music link, else null. */
    fun videoId(url: String): String? = CoreProviders.videoId(url)

    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id&hl=en"
    fun coverUrl(id: String) = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    /** Anonymous watch page probe (15 s timeout, call from a worker thread). Null when the page can't be read. */
    internal fun fetch(id: String): Probe? = try { parse(MusicResolver.get(watchUrl(id)).second) } catch (_: Exception) { null }

    /** host music.youtube.com, a "- Topic" channel, or a collection that came from YouTube Music. */
    fun isMusicLike(url: String, author: String?, groupUrl: String? = null): Boolean =
        Providers.isYouTubeMusic(url) || author?.trim()?.endsWith(" - Topic") == true || (groupUrl != null && Providers.isYouTubeMusic(groupUrl))

    fun artistOf(author: String?): String? = Naming.artistOf(author)

    /** What the original song is: the job's own music metadata wins over the watch-page details. */
    fun sourceTrack(probe: Probe, originalUrl: String, own: SourceTags?, ownDuration: Double?): SourceTrack {
        val title = own?.title?.takeIf { it.isNotBlank() } ?: probe.title.orEmpty()
        val artist = own?.artist?.takeIf { it.isNotBlank() } ?: artistOf(probe.author)
        return SourceTrack(title, listOfNotNull(artist), own?.album, probe.lengthSeconds ?: ownDuration, originalUrl, probe.videoId?.let(::coverUrl), own?.track, own?.year)
    }

    /** Raw failure text for a permanent restriction: classified by [ErrorMessages], listing what was tried in the details. */
    fun failure(music: Boolean, title: String?, tried: List<String>): String = buildList {
        add(if (music) MARKER_MUSIC else MARKER_OTHER)
        add("YouTube reports this video as unavailable for anonymous requests from your region" + (title?.let { ": “$it”" } ?: ""))
        if (music) add(if (tried.isEmpty()) "No other upload of this song matched closely enough." else "Other uploads tried:")
        tried.forEach { add("- $it") }
    }.joinToString("\n")

    /** Small caption for a recorded match, shared by the review sheet and the queue rows. */
    fun matchCaption(match: MusicMatch): String =
        if (match.source == SOURCE) SAVED_FROM_OTHER else "Matched from YouTube Music" + (match.confidence?.let { " · ${(it * 100).toInt()}%" }.orEmpty())
}

/** The link the engine actually downloads: another upload of the song when one was recorded, else the job's own link. */
internal val JobRecord.downloadUrl: String
    get() = job.match?.takeIf { it.source == Restriction.SOURCE }?.matchedUrl ?: job.url
