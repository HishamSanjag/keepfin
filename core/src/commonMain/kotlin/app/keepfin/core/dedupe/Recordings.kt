package app.keepfin.core.dedupe

import app.keepfin.core.music.WS
import app.keepfin.core.music.jsTrim
import app.keepfin.core.music.splitByRegex
import app.keepfin.core.providers.Providers

/**
 * A recording as skip-existing sees it: a download about to start, a collection entry, an inspected link, or a library item.
 * [duration] is in seconds. [sourceIds] are the stable ids of every link that names the recording (see [Recordings.sourceIds]);
 * [contentKey] identifies the bytes (decided where the bytes are known); [isrc] is the recording code, when a service gave one.
 */
data class Recording(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val duration: Double? = null,
    val sourceIds: Set<String> = emptySet(),
    val contentKey: String? = null,
    val isrc: String? = null,
)

/** How two recordings were found to be the same, strictest first. */
enum class Tier(val wire: String) {
    SOURCE("source"),
    ISRC("isrc"),
    CONTENT("content"),
    METADATA("metadata"),
}

/** Skip-existing: when two recordings are the same, and which known recording a new one repeats. */
object Recordings {
    /** Durations further apart than this many seconds are different recordings. */
    const val MAX_DURATION_DELTA = 2.0

    /**
     * Every stable id a link names: "youtube:<id>", "spotify:<id>", "apple-music:<id>", "deezer:<id>". Collections, albums and
     * unknown sites name none. Links are read by the core's own providers, the same way on both apps.
     */
    fun sourceIds(url: String?): Set<String> {
        if (url.isNullOrBlank()) return emptySet()
        val out = LinkedHashSet<String>()
        Providers.videoId(url)?.let { out.add("youtube:$it") }
        Providers.musicLink(url)?.let { if (it.type == "track") out.add("${it.service}:${it.id}") }
        return out
    }

    /** The first of [sourceIds], or null. */
    fun sourceId(url: String?): String? = sourceIds(url).firstOrNull()

    fun normalizeTitle(raw: String, artist: String? = null): String = app.keepfin.core.dedupe.normalizeTitle(raw, artist)

    fun normalizeArtists(raw: String?): List<String> = app.keepfin.core.dedupe.normalizeArtists(raw)

    fun versionsOf(normalizedTitle: String): List<String> = app.keepfin.core.dedupe.versionsOf(normalizedTitle)

    private fun sameArtists(a: List<String>, b: List<String>): Boolean =
        a.isNotEmpty() && b.isNotEmpty() && (a[0] == b[0] || (a.size == b.size && a.all { it in b }))

    /**
     * Metadata tier: same artist and title (non-empty after noise removal), durations within [MAX_DURATION_DELTA] seconds, album
     * equal or missing on either side, and the same version words. An upload titled "Artist - Title" without an artist tag is
     * compared by the two halves of its title.
     */
    fun sameRecording(a: Recording, b: Recording): Boolean {
        if (sameTagged(a, b)) return true
        if (versionsOf(normalizeTitle(a.title)) != versionsOf(normalizeTitle(b.title))) return false
        val splitA = splitArtistTitle(a)
        val splitB = splitArtistTitle(b)
        return (splitA != null && sameTagged(splitA, b)) ||
            (splitB != null && sameTagged(a, splitB)) ||
            (splitA != null && splitB != null && sameTagged(splitA, splitB))
    }

    private val TITLE_SPLIT = Regex("$WS[-–—]$WS")

    private fun splitArtistTitle(r: Recording): Recording? {
        val parts = splitByRegex(r.title, TITLE_SPLIT)
        if (parts.size < 2) return null
        val artist = jsTrim(parts[0])
        val title = jsTrim(parts.drop(1).joinToString(" - "))
        if (artist.isEmpty() || title.isEmpty()) return null
        return r.copy(artist = artist, title = title)
    }

    private fun sameTagged(a: Recording, b: Recording): Boolean {
        val titleA = normalizeTitle(a.title, a.artist)
        val titleB = normalizeTitle(b.title, b.artist)
        if (titleA.isEmpty() || titleA != titleB) return false
        if (!sameArtists(normalizeArtists(a.artist), normalizeArtists(b.artist))) return false
        val da = a.duration
        val db = b.duration
        if (da == null || db == null || kotlin.math.abs(da - db) > MAX_DURATION_DELTA) return false
        val albumA = if (a.album.isNullOrEmpty()) "" else normalizeTitle(a.album)
        val albumB = if (b.album.isNullOrEmpty()) "" else normalizeTitle(b.album)
        if (albumA.isNotEmpty() && albumB.isNotEmpty() && albumA != albumB) return false
        return versionsOf(titleA) == versionsOf(titleB)
    }

    /** An ISRC without hyphens or spaces, upper case; null unless it has the 12 letters and digits of a real one. */
    fun cleanIsrc(raw: String?): String? {
        if (raw == null) return null
        val out = StringBuilder()
        for (c in raw) {
            if (c == '-' || c == ' ') continue
            out.append(if (c in 'a'..'z') c - 32 else c)
        }
        val text = out.toString()
        return if (text.length == 12 && text.all { it in 'A'..'Z' || it in '0'..'9' }) text else null
    }

    /**
     * The strictest way [a] and [b] are the same recording, or null. A shared source id comes first, then the same ISRC, then the
     * same bytes, then the metadata test. Differing ISRCs do not rule out a metadata match (a remaster has its own code).
     */
    fun compare(a: Recording, b: Recording): Tier? {
        if (a.sourceIds.isNotEmpty() && b.sourceIds.isNotEmpty() && a.sourceIds.any { it in b.sourceIds }) return Tier.SOURCE
        val isrc = cleanIsrc(a.isrc)
        if (isrc != null && isrc == cleanIsrc(b.isrc)) return Tier.ISRC
        if (a.contentKey != null && a.contentKey == b.contentKey) return Tier.CONTENT
        return if (sameRecording(a, b)) Tier.METADATA else null
    }

    /**
     * The first known recording that matches [candidate] with the strictest tier: a source id match anywhere in the list beats
     * everything, otherwise the earliest match of the best tier wins. Callers leave out items that must not match (a file that is gone).
     */
    fun <T> find(candidate: Recording, known: Iterable<Pair<T, Recording>>): Pair<T, Tier>? {
        var best: Pair<T, Tier>? = null
        for ((item, recording) in known) {
            val tier = compare(candidate, recording) ?: continue
            if (tier == Tier.SOURCE) return item to tier
            if (best == null || tier.ordinal < best.second.ordinal) best = item to tier
        }
        return best
    }
}
