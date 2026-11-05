package app.keepfin.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.LibraryItem
import app.keepfin.allItems
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The phone's video player: lock and unlock, the Autoplay switch, the chapter label under the bar and the skip bubble adding up. */
@RunWith(AndroidJUnit4::class)
class VideoPlayerUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private fun video(h: Harness, n: Int): LibraryItem = h.engine.fakeLibrary.allItems().first { it.id.endsWith("/video/media/$n") }

    private fun openVideo(h: Harness, n: Int) {
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        val item = video(h, n)
        rule.runOnIdle { h.vm.playVideo(item, startOver = true) }
        rule.waitForTag(UiTags.VIDEO_PLAYER)
        rule.waitForTag(UiTags.VIDEO_MORE)
    }

    @Test fun lockHidesTheControlsAndATapOffersUnlock() {
        val h = Harness("library")
        openVideo(h, 204)
        rule.onNodeWithTag(UiTags.VIDEO_MORE).performClick()
        rule.waitForTag("video-more-sheet")
        rule.onNodeWithTag(UiTags.VIDEO_LOCK).performClick()
        rule.waitUntil(5_000) { !rule.exists("video-more-sheet") && !rule.exists(UiTags.VIDEO_MORE) }
        assertFalse(rule.exists(UiTags.VIDEO_PLAY))
        // a double-tap is ignored while locked: it neither skips nor shows the bubble
        rule.onNodeWithTag(UiTags.VIDEO_PLAYER).performTouchInput { doubleClick(Offset(width * .75f, height / 2f)) }
        assertFalse(rule.exists(UiTags.VIDEO_SKIP_HUD))
        // a tap shows the pill with the unlock button
        rule.onNodeWithTag(UiTags.VIDEO_PLAYER).performTouchInput { click(Offset(width / 2f, height / 2f)) }
        rule.waitForTag(UiTags.VIDEO_LOCKED_PILL)
        assertTrue(rule.hasText("Screen locked"))
        rule.onNodeWithTag(UiTags.VIDEO_UNLOCK).performClick()
        rule.waitForTag(UiTags.VIDEO_MORE)
        assertFalse(rule.exists(UiTags.VIDEO_LOCKED_PILL))
    }

    @Test fun autoplaySwitchIsRememberedAndOnByDefault() {
        val h = Harness("library")
        assertTrue(h.vm.prefs.videoAutoplay)
        openVideo(h, 204)
        rule.onNodeWithTag(UiTags.VIDEO_AUTOPLAY).assertIsDisplayed().performClick()
        rule.runOnIdle { assertFalse(h.vm.prefs.videoAutoplay) }
        rule.onNodeWithTag(UiTags.VIDEO_AUTOPLAY).performClick()
        rule.runOnIdle { assertTrue(h.vm.prefs.videoAutoplay) }
    }

    @Test fun theChapterTitleSitsUnderTheBarAndIsAButton() {
        val h = Harness("library")
        openVideo(h, 203)
        rule.waitForTag(UiTags.VIDEO_CHAPTER_BUTTON)
        rule.onNodeWithTag(UiTags.VIDEO_CHAPTER_BUTTON).assertIsDisplayed()
    }

    @Test fun repeatedDoubleTapsOnOneSideAddUpInTheBubble() {
        val h = Harness("library")
        openVideo(h, 204)
        // the right edge above the centre row: on a portrait phone the middle of the right half is the +10 / next buttons
        val spot = { w: Float, h: Float -> Offset(w * .92f, h * .3f) }
        rule.onNodeWithTag(UiTags.VIDEO_PLAYER).performTouchInput { doubleClick(spot(width.toFloat(), height.toFloat())) }
        rule.waitForTag(UiTags.VIDEO_SKIP_HUD)
        // a second double-tap inside the run is two more taps: +10, then +20, +30 (YouTube)
        rule.onNodeWithTag(UiTags.VIDEO_PLAYER).performTouchInput { doubleClick(spot(width.toFloat(), height.toFloat())) }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("+30 s", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }
}
