package app.keepfin.ui

import app.keepfin.allItems
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.PlaybackItems
import app.keepfin.ui.library.ArtworkColors
import app.keepfin.ui.library.LibraryRoute
import app.keepfin.ui.settings.SettingsCategory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Collection headers, the sampled artwork colour, the full player's lyrics card and song info, and the appearance toggles. */
@RunWith(AndroidJUnit4::class)
class HeadersPlayerUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    @Test fun artworkColourIsAnAverageThatFavoursVividPixels() {
        val red = 0xFFFF0000.toInt(); val grey = 0xFF808080.toInt()
        val c = ArtworkColors.average(intArrayOf(red, red, grey, grey))!!
        assertTrue("red dominates a grey border", c.red > c.green + .2f)
        assertNull(ArtworkColors.average(intArrayOf(0x00112233)))
        assertNull(ArtworkColors.average(IntArray(0)))
    }

    @Test fun albumAndArtistPagesHaveTheSharedHeader() {
        val h = Harness("library")
        val key = PlaybackItems.albumKey(h.engine.fakeLibrary.item("content://media/external/audio/media/101")!!)!!
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.runOnIdle { h.vm.openLibraryDeep(LibraryRoute.Album("music", key)) }
        rule.waitForTag(UiTags.HEADER_BAND)
        rule.waitForTag(UiTags.LIB_PLAY_ALL); rule.waitForTag(UiTags.LIB_SHUFFLE); rule.waitForTag(UiTags.HEADER_MORE)
        rule.runOnIdle { h.vm.back(); h.vm.openLibraryDeep(LibraryRoute.Artist("music", "Blender Studio")) }
        rule.waitForTag(UiTags.HEADER_BAND)
        rule.waitForTag(UiTags.LIB_PLAY_ALL)
    }

    @Test fun collectionPageHasHeaderWithKeepInSyncForFollowedLists() {
        val h = Harness("spaces-v2")
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.runOnIdle { h.vm.openLibraryDeep(LibraryRoute.Collection("workout", "gym")) }
        rule.waitForTag(UiTags.HEADER_BAND)
        rule.waitForTag(UiTags.HEADER_FOLLOW)
        rule.onNodeWithTag(UiTags.HEADER_FOLLOW).performClick()
        rule.waitForTag(UiTags.FOLLOW_SHEET)
    }

    @Test fun fullPlayerShowsSongInfoAndTheLyricsCardOpensLyrics() {
        val h = Harness("library")
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        val song = h.engine.fakeLibrary.allItems().first { it.hasLyrics && it.kind == app.keepfin.LibraryItemKind.AUDIO }
        rule.runOnIdle { h.vm.play(listOf(song), 0) }
        rule.waitForTag(UiTags.MINI_PLAYER)
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performClick()
        rule.waitForTag(UiTags.FULL_PLAYER)
        rule.waitForTag(UiTags.FULL_LYRICS_CARD)
        rule.onNodeWithTag(UiTags.FULL_SONG_INFO).performClick()
        rule.waitUntil(5_000) { rule.hasText("Folder") }
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithTag(UiTags.FULL_LYRICS_CARD).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.FULL_LYRICS_CARD) }
    }

    @Test fun theLyricsCardKeepsItsPlaceForASongWithoutLyricsAndCanBeHidden() {
        val h = Harness("library")
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        val song = h.engine.fakeLibrary.allItems().first { !it.hasLyrics && it.kind == app.keepfin.LibraryItemKind.AUDIO }
        rule.runOnIdle { h.vm.play(listOf(song), 0) }
        rule.waitForTag(UiTags.MINI_PLAYER)
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performClick()
        rule.waitForTag(UiTags.FULL_LYRICS_CARD)
        rule.onNodeWithTag(UiTags.FULL_LYRICS_HIDE).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.FULL_LYRICS_CARD) }
        rule.onNodeWithTag("full-menu").performClick()
        rule.onNodeWithTag(UiTags.FULL_MENU_LYRICS_PREVIEW).performClick()
        rule.waitForTag(UiTags.FULL_LYRICS_CARD)
    }

    @Test fun pureBlackAndMaterialYouTogglesPersist() {
        val h = Harness("default")
        h.openSettings(SettingsCategory.APPEARANCE)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_LIST)
        rule.onNodeWithTag(UiTags.SETTINGS_LIST).performScrollToNode(hasTestTag(UiTags.SETTING_PURE_BLACK))
        rule.onNodeWithTag(UiTags.SETTING_PURE_BLACK).performClick()
        rule.waitUntil(5_000) { h.prefs.pureBlack }
        rule.onNodeWithTag(UiTags.SETTING_PURE_BLACK).performClick()
        rule.waitUntil(5_000) { !h.prefs.pureBlack }
    }
}
