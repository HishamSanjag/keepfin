package app.keepfin.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.ArtistRelease
import app.keepfin.FollowedArtist
import app.keepfin.FollowingState
import app.keepfin.ui.preview.FakeLibrary.FakeSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Home's "New from artists you follow" and Following manager (GitHub #35, option B). Invented artists and releases only. */
@RunWith(AndroidJUnit4::class)
class HomeFollowUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private val day = 86_400_000L
    private val link1 = "https://music.youtube.com/playlist?list=OLAKfake1"
    private val link2 = "https://music.youtube.com/playlist?list=OLAKfake2"

    private fun seeded(h: Harness) {
        val now = System.currentTimeMillis()
        val artists = listOf("Lumen Harbor", "Pale Cassette", "Oren Vale").mapIndexed { n, name ->
            FollowedArtist("fa-$n", name, name.lowercase(), checkedOnce = true, checkedAt = now)
        }
        val releases = listOf(
            ArtistRelease(link1, "fa-0", "Salt Season", "Album", link1, null, now - 3 * day),
            ArtistRelease(link2, "fa-2", "Night Ferry", "Single", link2, null, now - 10 * day),
        )
        h.engine.fakeFollowing.value = FollowingState(artists, releases)
    }

    private fun home(seed: Boolean): Harness {
        val h = Harness("library")
        if (seed) seeded(h)
        h.vm.navigate(Destination.HOME)
        h.show(rule)
        rule.waitForTag(UiTags.HOME_SCREEN)
        return h
    }

    private fun scrollTo(tag: String) = rule.onNodeWithTag(UiTags.HOME_LIST).performScrollToNode(hasTestTag(tag))

    @Test fun followingNobodyShowsTheEmptyStateAndNoRelease() {
        home(seed = false)
        scrollTo(UiTags.HOME_NO_FOLLOWING)
        rule.onNodeWithTag(UiTags.HOME_NO_FOLLOWING).assertIsDisplayed()
        assertFalse(rule.exists(UiTags.HOME_RELEASES))
        assertFalse(rule.exists(UiTags.HOME_FOLLOWING))
    }

    @Test fun theFeedIsDatedAndKeepItDownloadsOnlyOnTheTap() {
        val h = home(seed = true)
        scrollTo(UiTags.HOME_RELEASES)
        rule.waitForTag(UiTags.homeKeep(link1))
        rule.onNodeWithText("THIS WEEK").assertIsDisplayed()
        assertTrue("nothing kept before the tap", h.engine.keptReleases.isEmpty())
        rule.onNodeWithTag(UiTags.homeKeep(link1)).performClick()
        rule.waitUntil(5_000) { h.engine.keptReleases.isNotEmpty() }
        assertEquals(listOf(link1), h.engine.keptReleases.toList())
        // a kept release leaves the feed
        rule.waitUntil(5_000) { !rule.exists(UiTags.homeKeep(link1)) }
    }

    @Test fun manageUnfollowsAnArtistAndTheirReleasesGo() {
        val h = home(seed = true)
        scrollTo(UiTags.HOME_FOLLOWING)
        rule.onNodeWithTag(UiTags.HOME_MANAGE).performClick()
        rule.waitForTag(UiTags.FOLLOWING_SHEET)
        rule.onNodeWithTag(UiTags.followingUnfollow("fa-2")).performClick()
        rule.waitUntil(5_000) { h.engine.fakeFollowing.value.artists.none { it.id == "fa-2" } }
        assertTrue(h.engine.fakeFollowing.value.releases.none { it.artistId == "fa-2" })
        rule.onNodeWithTag(UiTags.followingCheck("fa-0")).performClick()
        rule.waitUntil(5_000) { h.engine.followChecks.isNotEmpty() }
        assertEquals("fa-0", h.engine.followChecks.first())
        rule.onNodeWithTag(UiTags.FOLLOWING_DONE).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.FOLLOWING_SHEET) }
    }

    @Test fun aNewLibraryOffersNoFollowSections() {
        val h = Harness("empty")
        h.engine.fakeLibrary.setSnapshot(FakeSnapshot(items = emptyList(), collections = emptyList(), scannedAt = System.currentTimeMillis()))
        h.vm.navigate(Destination.HOME)
        h.show(rule)
        rule.waitForTag(UiTags.HOME_EMPTY)
        assertFalse(rule.exists(UiTags.HOME_RELEASES))
        assertFalse(rule.exists(UiTags.HOME_NO_FOLLOWING))
    }
}
