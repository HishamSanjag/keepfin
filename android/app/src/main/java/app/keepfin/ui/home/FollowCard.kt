package app.keepfin.ui.home

import app.keepfin.ArtistRelease
import app.keepfin.FollowingState
import app.keepfin.core.home.FeedGroup
import app.keepfin.core.home.FeedRelease
import app.keepfin.core.home.ReleaseCard
import app.keepfin.core.home.ReleaseFeed

/**
 * What Home's card and the Review sheet say, from the follow state. The rules are the shared core's ([ReleaseFeed]); this only maps the
 * phone's state to them and words the small lines the screens show.
 */
internal object FollowCard {
    fun feed(state: FollowingState): List<FeedRelease> = state.releases.map { it.feed(state) }

    private fun ArtistRelease.feed(state: FollowingState) = FeedRelease(id, state.artists.firstOrNull { it.id == artistId }?.key ?: "", title, foundAt, kept)

    /** Home's card: shown only while [enabled] and something unseen is live. */
    fun card(state: FollowingState, enabled: Boolean, now: Long): ReleaseCard = ReleaseFeed.card(feed(state), state.seenReleases.toSet(), enabled, now)

    /** The Review sheet's groups ("This week", "Earlier this month"): the unseen releases only. */
    fun review(state: FollowingState, now: Long): List<FeedGroup> = ReleaseFeed.groupUnseen(feed(state), state.seenReleases.toSet(), now)

    /** The artists behind the card, by name, in the card's order. */
    fun names(state: FollowingState, card: ReleaseCard): List<String> = card.artistKeys.mapNotNull { key -> state.artists.firstOrNull { it.key == key }?.name }

    /** "Lumen Harbor, Pale Cassette" / "Lumen Harbor, Pale Cassette and 2 more". */
    fun namesLine(names: List<String>): String = if (names.size <= 2) names.joinToString(", ") else "${names.take(2).joinToString(", ")} and ${names.size - 2} more"

    /** "Checked 5 min ago" in the artist list: the latest check of any followed artist. */
    fun checkedWords(state: FollowingState, now: Long): String {
        if (state.checking) return "Checking…"
        val latest = state.artists.maxOfOrNull { it.checkedAt ?: 0L } ?: 0L
        if (latest == 0L) return "Not checked yet"
        val minutes = ((now - latest).coerceAtLeast(0L) / 60_000L).toInt()
        val ago = when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 24 * 60 -> "${minutes / 60} h ago"
            else -> ReleaseFeed.ageWords(latest, now)
        }
        return "Checked $ago"
    }

    const val CHECK_FAILED_NOTE = "Couldn’t check · will try again"
}
