package app.keepfin.core.spaces

import app.keepfin.core.dedupe.Recordings
import app.keepfin.core.music.asciiLower
import app.keepfin.core.music.jsTrim
import app.keepfin.core.providers.Providers
import app.keepfin.core.providers.Url
import app.keepfin.core.providers.percentDecode

/** What to do with newly found items: [NONE] nothing, [ENQUEUE] download them, [CONFIRM] wait for the user. */
enum class Gate(val wire: String) { NONE("none"), ENQUEUE("enqueue"), CONFIRM("confirm") }

/**
 * Collections: source links and kinds, new-item detection, the confirm gate, ordering, following and migration.
 * Links are read by the core's own URL parser (app.keepfin.core.providers), so both apps see the same source.
 */
object Collections {
    const val DEFAULT_INTERVAL_HOURS = 6
    const val DEFAULT_CONFIRM_ABOVE = 20
    private val intervals = setOf(1, 6, 12, 24, 168)
    private const val HOUR_MS = 3_600_000L

    // ---- source identity ----------------------------------------------------------------------------------------

    private fun segmentsOf(path: String): List<String> = path.split('/').filter { it.isNotEmpty() }

    private fun isListId(value: String): Boolean =
        value.length in 2..80 && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }

    private fun isTracking(key: String): Boolean {
        val lower = asciiLower(key)
        if (lower in setOf("si", "feature", "pp", "fbclid", "gclid", "igshid", "ref", "context")) return true
        if (!lower.startsWith("utm_") || lower.length == 4) return false
        return lower.substring(4).none { it == '\n' || it == '\r' || it == ' ' || it == ' ' }
    }

    private const val HEX = "0123456789ABCDEF"

    /** application/x-www-form-urlencoded, as `URLSearchParams` writes a query. */
    internal fun formEncode(value: String): String {
        val out = StringBuilder()
        for (byte in value.encodeToByteArray()) {
            val b = byte.toInt() and 0xFF
            val c = b.toChar()
            when {
                b == 0x20 -> out.append('+')
                b < 128 && (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '*' || c == '-' || c == '.' || c == '_') -> out.append(c)
                else -> out.append('%').append(HEX[b shr 4]).append(HEX[b and 15])
            }
        }
        return out.toString()
    }

    /** The query without tracking parameters, written the way a browser's `URLSearchParams` writes it; unchanged when there are none. */
    private fun withoutTracking(query: String?): String? {
        if (query == null) return null
        val pairs = ArrayList<Pair<String, String>>()
        for (piece in query.split('&')) {
            if (piece.isEmpty()) continue
            val eq = piece.indexOf('=')
            val name = if (eq < 0) piece else piece.substring(0, eq)
            val value = if (eq < 0) "" else piece.substring(eq + 1)
            pairs.add(percentDecode(name, plusIsSpace = true) to percentDecode(value, plusIsSpace = true))
        }
        if (pairs.none { isTracking(it.first) }) return query
        val kept = pairs.filter { !isTracking(it.first) }
        return if (kept.isEmpty()) null else kept.joinToString("&") { "${formEncode(it.first)}=${formEncode(it.second)}" }
    }

    private val youtubeHosts = setOf("youtube.com", "youtu.be", "youtube-nocookie.com")

    private fun stripSubdomain(host: String): String =
        listOf("www.", "m.", "music.").firstOrNull { host.startsWith(it) }?.let { host.substring(it.length) } ?: host

    /**
     * One link per source: playlist ids on YouTube, channel handles, music-service links; tracking parameters, fragments, "www." and a
     * trailing slash removed. A link that cannot be parsed is returned as it is.
     */
    fun canonicalUrl(url: String): String {
        val parsed = Url.parse(url) ?: return url
        val host = parsed.hostname.lowercase()
        if (stripSubdomain(host) in youtubeHosts) {
            val list = parsed.queryParam("list")
            if (list != null && isListId(list)) return "https://www.youtube.com/playlist?list=$list"
            val parts = segmentsOf(parsed.path)
            val first = parts.getOrNull(0) ?: ""
            if (first.startsWith("@")) return "https://www.youtube.com/$first"
            if ((first == "channel" || first == "c" || first == "user") && parts.size > 1) return "https://www.youtube.com/$first/${parts[1]}"
        }
        Providers.musicLink(url)?.let { return Providers.musicLinkUrl(it) }
        val cleanHost = if (parsed.host == null) null else host.removePrefix("www.")
        val href = Url(parsed.scheme, parsed.username, parsed.password, cleanHost, parsed.port, parsed.path, withoutTracking(parsed.query), null).href
        return if (href.endsWith("/")) href.dropLast(1) else href
    }

    /** The canonical link and the id of the playlist, channel or album it names (null when it names none). */
    fun canonicalSource(url: String): CollectionSource {
        val canonical = canonicalUrl(url)
        val parsed = Url.parse(canonical) ?: return CollectionSource(canonical, null)
        val list = parsed.queryParam("list")
        if (!list.isNullOrEmpty()) return CollectionSource(canonical, list)
        Providers.musicLink(canonical)?.let { return CollectionSource(canonical, it.id) }
        return CollectionSource(canonical, segmentsOf(parsed.path).lastOrNull())
    }

    // ---- kinds --------------------------------------------------------------------------------------------------

    /** Radio mixes never end, so they are bounded by paging and never followed. Curated RDCLAK/RDAMPL lists are finite. */
    fun isInfinite(url: String): Boolean {
        val parsed = Url.parse(url) ?: return false
        val host = parsed.hostname.lowercase()
        if (youtubeHosts.none { host == it || host.endsWith(".$it") }) return false
        val list = asciiLower(parsed.queryParam("list") ?: "")
        return list.startsWith("rd") && !list.startsWith("rdclak") && !list.startsWith("rdampl")
    }

    /** playlist, album or channel, read from the link text. */
    fun kindOf(url: String): String {
        val text = asciiLower(url)
        if (text.contains("/album/") || text.contains("?list=olak") || text.contains("&list=olak") || text.contains("/browse/mpre")) return "album"
        if (text.contains("/channel/") || text.contains("/c/") || text.contains("/user/")) return "channel"
        var at = text.indexOf("/@")
        while (at >= 0) {
            if (at + 2 < text.length && text[at + 2] != '/') return "channel"
            at = text.indexOf("/@", at + 1)
        }
        return "playlist"
    }

    // ---- following ----------------------------------------------------------------------------------------------

    /** A sync interval that is offered, else the default. */
    fun interval(hours: Int): Int = if (hours in intervals) hours else DEFAULT_INTERVAL_HOURS

    fun isOfferedInterval(hours: Int): Boolean = hours in intervals

    /** Only playlists and channels that are not radio mixes can be kept in sync (albums, mixes and own lists never follow). */
    fun canKeepInSync(kind: String, infinite: Boolean): Boolean = (kind == "playlist" || kind == "channel") && !infinite

    /**
     * Following a new collection is opt-in: off unless the user turned on "Keep in sync" ([keepInSyncHours], an offered interval)
     * for a playlist or channel that is not a radio mix; then automatic at that interval.
     */
    fun defaultFollow(kind: String, url: String?, keepInSyncHours: Int? = null): Follow {
        val on = canKeepInSync(kind, url != null && isInfinite(url)) && keepInSyncHours != null && isOfferedInterval(keepInSyncHours)
        return Follow(if (on) "auto" else "off", if (on) keepInSyncHours else DEFAULT_INTERVAL_HOURS, wifiOnly = true, notify = true, confirmAbove = DEFAULT_CONFIRM_ABOVE)
    }

    /** Whether a collection can be checked against its source at all. */
    fun isFollowable(kind: String, sourceUrl: String?): Boolean = kind != "own" && sourceUrl != null && !isInfinite(sourceUrl)

    /**
     * Whether the schedule should check a collection at [now]: automatic, followable, not waiting for a review, and at least its
     * interval since the last check. With [metered], collections that wait for Wi-Fi are skipped.
     */
    fun isDue(collection: LibraryCollection, now: Long, metered: Boolean = false): Boolean {
        val follow = collection.follow ?: return false
        if (follow.mode != "auto" || !isFollowable(collection.kind, collection.sourceUrl) || follow.pending) return false
        if (follow.wifiOnly && metered) return false
        return now - (follow.lastCheckedAt ?: 0L) >= interval(follow.intervalHours) * HOUR_MS
    }

    // ---- new items ----------------------------------------------------------------------------------------------

    /** The identity of a discovered entry across checks: its recording id (the dedupe source id), else its link without fragment and trailing slashes. */
    fun itemKey(url: String): String = Recordings.sourceId(url) ?: jsTrim(url).substringBefore('#').trimEnd('/')

    /** Positions in [urls] of the entries that [seen] does not know, once each, in source order. */
    fun newIndexes(urls: List<String>, seen: Collection<String>): List<Int> {
        // Seen keys recorded before keys dropped fragments and trailing slashes still count.
        val known = HashSet<String>()
        for (id in seen) known.add(itemKey(id))
        val out = ArrayList<Int>()
        urls.forEachIndexed { index, url ->
            if (known.add(itemKey(url))) out.add(index)
        }
        return out
    }

    /** Keys of the entries that [seen] does not know, once each, in source order. */
    fun newKeys(urls: List<String>, seen: Collection<String>): List<String> = newIndexes(urls, seen).map { itemKey(urls[it]) }

    /** Up to [confirmAbove] new items download; more wait for the user unless [approved]. */
    fun gate(count: Int, confirmAbove: Int, approved: Boolean = false): Gate = when {
        count <= 0 -> Gate.NONE
        approved || count <= confirmAbove -> Gate.ENQUEUE
        else -> Gate.CONFIRM
    }

    /** Members none of whose source ids is in the discovered listing; members without a source id are never judged. */
    fun removedFromSource(ids: List<String>, sourceIdsOf: (String) -> Collection<String>, present: Set<String>): List<String> =
        ids.filter { id ->
            val keys = sourceIdsOf(id)
            keys.isNotEmpty() && keys.none { it in present }
        }

    // ---- order and migration ------------------------------------------------------------------------------------

    /** Orders ids by their place in the source; ids without one keep their order, after the rest. */
    fun sortBySourceOrder(ids: List<String>, indexOf: (String) -> Int?): List<String> =
        ids.withIndex().sortedWith(compareBy({ indexOf(it.value) ?: Int.MAX_VALUE }, { it.index })).map { it.value }

    /** A user playlist from before collections, as a collection of kind own. */
    fun fromPlaylist(id: String, name: String, itemIds: List<String>, createdAt: Long, updatedAt: Long, space: String): LibraryCollection =
        LibraryCollection(id, name, "own", space, itemIds.toList(), emptyList(), null, null, createdAt, updatedAt)
}
