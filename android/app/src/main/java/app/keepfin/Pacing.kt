package app.keepfin

/**
 * Pure scheduling rules that keep bursts of YouTube / YouTube Music jobs gentle on the provider. YouTube jobs may use the
 * user's full concurrency until one of them hits a rate limit; from then on, for [CAP_MS], at most
 * [RATE_LIMITED_CONCURRENCY] of them transfer at once (the limit is per network, so the cap is app-wide). Other sites always
 * keep the user's concurrency. YouTube jobs also space their metadata requests, and a collection whose job was throttled
 * holds back new starts while it backs off.
 */
internal object Pacing {
    const val RATE_LIMITED_CONCURRENCY = 2
    /** How long the YouTube cap lasts after the last rate limit. */
    const val CAP_MS = 30 * 60_000L
    const val SLEEP_REQUESTS_SECONDS = "0.75"

    data class Item(val id: String, val youtube: Boolean, val groupId: String?)

    /** YouTube and YouTube Music links, and music-service tracks (Spotify, Apple Music, Deezer) that are matched there. */
    fun isYouTube(url: String, matched: Boolean): Boolean = matched || Providers.isYouTubeHost(url)

    /** Extra request spacing for YouTube jobs. */
    fun requestArgs(youtube: Boolean): List<Arg> = if (youtube) listOf(Arg("--sleep-requests", SLEEP_REQUESTS_SECONDS)) else emptyList()

    /**
     * Whether [candidate] may start now. [running] includes jobs already chosen in this pass, [holds] maps a collection id
     * to the time its hold ends. The caller stops scanning once the overall [limit] is reached.
     */
    fun canStart(candidate: Item, running: List<Item>, limit: Int, holds: Map<String, Long>, now: Long, cappedUntil: Long = 0L): Boolean {
        if (running.size >= limit) return false
        // A retrying job never holds back other downloads, even from the same collection.
        if (candidate.youtube && now < cappedUntil && running.count { it.youtube } >= RATE_LIMITED_CONCURRENCY) return false
        return true
    }

    /** When the YouTube cap ends after a rate limit seen at [now]; never earlier than a cap already in force. */
    fun capEnd(now: Long, current: Long = 0L): Long = maxOf(current, now + CAP_MS)

    const val CONCURRENCY_MARKER = "concurrencyDefaultV2"

    /** One-time move of the old default (2) to the new one (3). A stored 2 with the marker is the user's own choice and stays. */
    fun migratedConcurrency(stored: Int, markerPresent: Boolean): Int = if (!markerPresent && stored == 2) 3 else stored
}
