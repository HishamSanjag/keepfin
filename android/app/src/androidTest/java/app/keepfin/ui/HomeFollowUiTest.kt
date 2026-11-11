package app.keepfin.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.ArtistRelease
import app.keepfin.FollowedArtist
import app.keepfin.FollowingState
import app.keepfin.ui.library.LibraryRoute
import app.keepfin.ui.library.MusicTab
import app.keepfin.ui.preview.FakeLibrary.FakeSnapshot
import app.keepfin.ui.settings.SettingsCategory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Following, option B (GitHub #51): Home's slim new-releases card, its Review sheet, the "Don't show on Home" setting, and the artist
 * list's Follow toggle, Following filter and calm failed-check note. Invented artists and releases only.
 */
@RunWith(AndroidJUnit4::class)
class HomeFollowUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private val day = 86_400_000L
    private val link1 = "https://music.youtube.com/playlist?list=OLAKfake1"
    private val link2 = "https://music.youtube.com/playlist?list=OLAKfake2"
    private val link3 = "https://music.youtube.com/playlist?list=OLAKfake3"

    private fun seeded(h: Harness, releases: Boolean = true, failed: Boolean = false) {
        val now = System.currentTimeMillis()
        val artists = listOf("Blender Studio", "Pale Cassette", "Oren Vale").mapIndexed { n, name ->
            FollowedArtist("fa-$n", name, name.lowercase(), checkedOnce = true, checkedAt = now - 5 * 60_000L, error = if (failed && n == 1) "raw engine text" else null)
        }
        val found = if (releases) listOf(
            ArtistRelease(link1, "fa-0", "Salt Season", "Album", link1, null, now - 3 * day),
            ArtistRelease(link2, "fa-2", "Night Ferry", "Single", link2, null, now - 10 * day),
        ) else emptyList()
        h.engine.fakeFollowing.value = FollowingState(artists, found)
    }

    private fun home(seed: Boolean = true, releases: Boolean = true): Harness {
        val h = Harness("library")
        if (seed) seeded(h, releases)
        h.vm.navigate(Destination.HOME)
        h.show(rule)
        rule.waitForTag(UiTags.HOME_SCREEN)
        return h
    }

    // The card is a section lower on Home, so the list has to reach it first.
    private fun showReleaseCard() {
        rule.waitUntil(10_000) { runCatching { rule.onNodeWithTag(UiTags.HOME_LIST).performScrollToNode(hasTestTag(UiTags.RELEASE_CARD)) }.isSuccess }
        rule.waitForTag(UiTags.RELEASE_CARD)
    }

    private fun artists(h: Harness, failed: Boolean = false) {
        seeded(h, releases = false, failed = failed)
        h.vm.chooseMusicTab("music", MusicTab.ARTISTS)
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(LibraryRoute.Space("music"))
        h.show(rule)
        rule.waitForTag(UiTags.ARTIST_FILTER_ALL)
    }

    @Test fun noCardWhenNothingIsNew() {
        home(releases = false)
        rule.waitForIdle()
        assertFalse(rule.exists(UiTags.RELEASE_CARD))
    }

    @Test fun nobodyFollowedMeansNoCardAndNoFeedOrFollowingRowOnHome() {
        home(seed = false)
        rule.waitForIdle()
        assertFalse(rule.exists(UiTags.RELEASE_CARD))
        assertFalse(rule.exists("home-releases"))
        assertFalse(rule.exists("home-following"))
    }

    @Test fun theCardNamesTheCountAndReviewKeepsOnlyOnTheTap() {
        val h = home()
        showReleaseCard()
        rule.onNodeWithText("2 new releases from artists you follow").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.RELEASE_REVIEW).performClick()
        rule.waitForTag(UiTags.RELEASES_SHEET)
        rule.waitForTag(UiTags.homeKeep(link1))
        rule.onNodeWithText("THIS WEEK").assertIsDisplayed()
        rule.onNodeWithText("EARLIER THIS MONTH").assertIsDisplayed()
        assertTrue("nothing kept before the tap", h.engine.keptReleases.isEmpty())
        rule.onNodeWithTag(UiTags.homeKeep(link1)).performClick()
        rule.waitUntil(5_000) { h.engine.keptReleases.isNotEmpty() }
        assertEquals(listOf(link1), h.engine.keptReleases.toList())
        // a kept release leaves the list
        rule.waitUntil(5_000) { !rule.exists(UiTags.homeKeep(link1)) }
    }

    @Test fun theXHidesTheCardUntilANewerReleaseArrives() {
        val h = home()
        showReleaseCard()
        rule.onNodeWithTag(UiTags.RELEASE_DISMISS).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.RELEASE_CARD) }
        assertEquals(2, h.engine.fakeFollowing.value.seenReleases.size)
        // a newer release brings it back, counting only itself
        h.engine.fakeFollowing.value = h.engine.fakeFollowing.value.let { it.copy(releases = it.releases + ArtistRelease(link3, "fa-1", "Hollow Bloom", "Single", link3, null, System.currentTimeMillis())) }
        showReleaseCard()
        rule.onNodeWithText("1 new release from artists you follow").assertIsDisplayed()
    }

    @Test fun dontShowOnHomeTurnsTheCardOffAndSettingsTurnsItBackOn() {
        val h = home()
        showReleaseCard()
        assertTrue(h.engine.settings.value.newReleasesOnHome)
        rule.onNodeWithTag(UiTags.RELEASE_HIDE).performClick()
        rule.waitUntil(5_000) { !h.engine.settings.value.newReleasesOnHome }
        rule.waitUntil(5_000) { !rule.exists(UiTags.RELEASE_CARD) }
    }

    @Test fun theSettingOnLibrarySettingsTurnsTheCardBackOn() {
        val h = Harness("library")
        seeded(h)
        h.engine.updateSettings { it.copy(newReleasesOnHome = false) }
        h.openSettings(SettingsCategory.LIBRARY)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_LIST)
        rule.onNodeWithTag(UiTags.SETTINGS_LIST).performScrollToNode(hasTestTag(UiTags.SETTING_HOME_RELEASES))
        rule.onNodeWithText("Show new releases on Home").assertExists()
        rule.onNodeWithTag(UiTags.SETTING_HOME_RELEASES).assertIsOff().performClick()
        rule.waitUntil(5_000) { h.engine.settings.value.newReleasesOnHome }
        rule.onNodeWithTag(UiTags.SETTING_HOME_RELEASES).assertIsOn()
        h.vm.navigate(Destination.HOME)
        showReleaseCard()
    }

    @Test fun markAllSeenClosesTheSheetAndTheCardGoes() {
        val h = home()
        showReleaseCard()
        rule.onNodeWithTag(UiTags.RELEASE_REVIEW).performClick()
        rule.waitForTag(UiTags.RELEASES_SHEET)
        rule.onNodeWithTag(UiTags.REVIEW_MARK_ALL).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.RELEASES_SHEET) && !rule.exists(UiTags.RELEASE_CARD) }
        assertEquals(2, h.engine.fakeFollowing.value.seenReleases.size)
    }

    @Test fun manageWhoYouFollowOpensTheArtistListOnFollowing() {
        home()
        showReleaseCard()
        rule.onNodeWithTag(UiTags.RELEASE_REVIEW).performClick()
        rule.waitForTag(UiTags.RELEASES_SHEET)
        rule.onNodeWithTag(UiTags.REVIEW_MANAGE).performClick()
        rule.waitForTag(UiTags.ARTIST_FILTER_FOLLOWING)
        rule.onNodeWithTag(UiTags.ARTIST_FILTER_FOLLOWING).assertIsSelected()
        assertFalse(rule.exists(UiTags.RELEASES_SHEET))
    }

    @Test fun everyArtistHasAOneTapFollowToggle() {
        val h = Harness("library")
        artists(h)
        h.engine.fakeFollowing.value = FollowingState()
        rule.waitForTag(UiTags.artistToggle("Blender Studio"))
        rule.onNodeWithText("Following (0)").assertExists()
        rule.onNodeWithTag(UiTags.artistToggle("Blender Studio")).performClick()
        rule.waitUntil(5_000) { h.engine.fakeFollowing.value.isFollowing("Blender Studio") }
        rule.onNodeWithText("Following (1)").assertExists()
        rule.onNodeWithTag(UiTags.artistToggle("Blender Studio")).performClick()
        rule.waitUntil(5_000) { !h.engine.fakeFollowing.value.isFollowing("Blender Studio") }
    }

    @Test fun theFollowingFilterListsFollowedArtistsAndOnesNotInTheLibraryYet() {
        val h = Harness("library")
        artists(h)
        rule.onNodeWithTag(UiTags.ARTIST_FILTER_FOLLOWING).performClick()
        rule.waitForTag(UiTags.ARTIST_CHECK_NOW)
        rule.waitForTag("artist-Blender Studio")
        assertFalse("an artist nobody follows is not listed", rule.exists("artist-Ludwig Göransson"))
        rule.waitForTag("artist-Pale Cassette")
        rule.onAllNodesWithText("Not in your library yet").assertCountEquals(2)
        rule.onNodeWithText("Checked 5 min ago", substring = true).assertExists()
        rule.onNodeWithTag(UiTags.ARTIST_CHECK_NOW).performClick()
        rule.waitUntil(5_000) { h.engine.followChecks.isNotEmpty() }
        assertEquals(null, h.engine.followChecks.first())
        // back to All lists the whole library again
        rule.onNodeWithTag(UiTags.ARTIST_FILTER_ALL).performClick()
        rule.waitForTag("artist-Ludwig Göransson")
    }

    @Test fun aFailedCheckIsACalmNoteNeverTheEngineText() {
        val h = Harness("library")
        artists(h, failed = true)
        rule.onNodeWithTag(UiTags.ARTIST_FILTER_FOLLOWING).performClick()
        rule.waitForTag(UiTags.artistCheckFailed("Pale Cassette"))
        rule.onNodeWithText("Couldn’t check · will try again").assertExists()
        assertFalse(rule.exists(UiTags.artistCheckFailed("Blender Studio")))
        rule.onNodeWithText("raw engine text").assertDoesNotExist()
    }

    @Test fun aNewLibraryOffersNoCard() {
        val h = Harness("empty")
        h.engine.fakeLibrary.setSnapshot(FakeSnapshot(items = emptyList(), collections = emptyList(), scannedAt = System.currentTimeMillis()))
        h.vm.navigate(Destination.HOME)
        h.show(rule)
        rule.waitForTag(UiTags.HOME_EMPTY)
        assertFalse(rule.exists(UiTags.RELEASE_CARD))
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExist() = assertDoesNotExist()
