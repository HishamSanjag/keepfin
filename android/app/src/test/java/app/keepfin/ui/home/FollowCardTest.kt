package app.keepfin.ui.home

import app.keepfin.ArtistRelease
import app.keepfin.EngineSettings
import app.keepfin.FollowedArtist
import app.keepfin.FollowingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's card and the Review sheet from the follow state: the shared core's rules wired to the phone's state. Invented artists only. */
class FollowCardTest {
    private val day = 86_400_000L
    private val now = 1_800_000_000_000L
    private val artists = listOf(
        FollowedArtist("fa-0", "Lumen Harbor", "lumen harbor", checkedAt = now),
        FollowedArtist("fa-1", "Pale Cassette", "pale cassette", checkedAt = now - 90 * 60_000L),
    )

    private fun release(id: String, artist: String, ago: Long, kept: Boolean = false) =
        ArtistRelease("https://music.youtube.com/playlist?list=$id", artist, "Title $id", "Album", "https://music.youtube.com/playlist?list=$id", null, now - ago, kept)

    private fun state(vararg releases: ArtistRelease, seen: List<String> = emptyList()) = FollowingState(artists, releases.toList(), seenReleases = seen)

    @Test fun noCardWhenNothingIsNew() {
        assertFalse(FollowCard.card(FollowingState(artists), true, now).show)
        assertFalse(FollowCard.card(state(release("a", "fa-0", 40 * day)), true, now).show)
    }

    @Test fun theCardNamesTheCountAndTheArtistsAndCapsNothingButItsCovers() {
        val s = state(release("a", "fa-0", day), release("b", "fa-1", 2 * day), release("c", "fa-0", 3 * day))
        val card = FollowCard.card(s, true, now)
        assertTrue(card.show)
        assertEquals("3 new releases from artists you follow", card.title)
        assertEquals(listOf("Lumen Harbor", "Pale Cassette"), FollowCard.names(s, card))
        assertEquals("Lumen Harbor, Pale Cassette", FollowCard.namesLine(FollowCard.names(s, card)))
        assertEquals("A, B and 2 more", FollowCard.namesLine(listOf("A", "B", "C", "D")))
    }

    @Test fun theSettingOffRemovesTheCardAndAKeptReleaseDoesNotCount() {
        val s = state(release("a", "fa-0", day), release("b", "fa-1", day, kept = true))
        assertFalse(FollowCard.card(s, false, now).show)
        assertEquals("1 new release from artists you follow", FollowCard.card(s, true, now).title)
        assertTrue(EngineSettings().newReleasesOnHome)
    }

    @Test fun seenReleasesHideTheCardUntilANewerOneArrives() {
        val first = state(release("a", "fa-0", day), release("b", "fa-1", 2 * day))
        val seen = app.keepfin.core.home.ReleaseFeed.markSeen(FollowCard.feed(first), emptySet(), now)
        val hidden = first.copy(seenReleases = seen)
        assertFalse(FollowCard.card(hidden, true, now).show)
        assertTrue(FollowCard.review(hidden, now).isEmpty())
        val newer = hidden.copy(releases = hidden.releases + release("c", "fa-0", 0))
        val card = FollowCard.card(newer, true, now)
        assertEquals(1, card.count)
        assertEquals(listOf("This week"), FollowCard.review(newer, now).map { it.label })
    }

    @Test fun reviewGroupsThisWeekAndEarlierThisMonth() {
        val s = state(release("a", "fa-0", 3 * day), release("b", "fa-1", 12 * day), release("c", "fa-0", 8 * day))
        val groups = FollowCard.review(s, now)
        assertEquals(listOf("This week", "Earlier this month"), groups.map { it.label })
        assertEquals(1, groups[0].ids.size)
        assertEquals(2, groups[1].ids.size)
    }

    @Test fun checkedWordsReadTheLatestCheck() {
        assertEquals("Checked just now", FollowCard.checkedWords(FollowingState(artists), now))
        assertEquals("Checked 1 h ago", FollowCard.checkedWords(FollowingState(artists.drop(1)), now))
        assertEquals("Not checked yet", FollowCard.checkedWords(FollowingState(listOf(FollowedArtist("x", "X", "x"))), now))
        assertEquals("Checking…", FollowCard.checkedWords(FollowingState(artists, checking = true), now))
    }
}
