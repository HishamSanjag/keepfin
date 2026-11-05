package app.keepfin.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.down
import androidx.compose.ui.test.up
import androidx.compose.ui.test.moveTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Motion phase 1: the mini player grows into the full player as one surface; play/pause morph and the seek bar keep working. */
@RunWith(AndroidJUnit4::class)
class PlayerMotionUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private val song = "content://media/external/audio/media/102"

    private fun playSong(): Harness {
        val h = Harness("library")
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.onNodeWithTag(UiTags.spaceCard("music")).performClick()
        rule.waitForTag(UiTags.libItem(song))
        rule.onNodeWithTag(UiTags.libItem(song)).performClick()
        rule.waitForTag(UiTags.MINI_PLAYER)
        return h
    }

    @Test fun tappingTheBarOpensTheFullPlayerAndTheCloseButtonCollapsesIt() {
        val h = playSong()
        assertFalse(h.vm.fullPlayer)
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performClick()
        rule.waitForTag(UiTags.FULL_PLAYER)
        rule.waitUntil(5_000) { h.vm.fullPlayer }
        rule.onNodeWithTag(UiTags.FULL_PLAYER).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.FULL_COLLAPSE).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.FULL_PLAYER) }
        assertFalse(h.vm.fullPlayer)
        rule.onNodeWithTag(UiTags.MINI_PLAYER).assertIsDisplayed()
    }

    @Test fun draggingTheBarUpOpensTheFullPlayer() {
        val h = playSong()
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performTouchInput { swipeUp(startY = bottom - 4f, endY = bottom - 900f) }
        rule.waitUntil(5_000) { h.vm.fullPlayer }
        rule.waitForTag(UiTags.FULL_PLAYER)
        rule.onNodeWithTag(UiTags.FULL_PLAYER).assertIsDisplayed()
    }

    @Test fun aShortDragUpOnTheBarSpringsBackWithoutOpening() {
        val h = playSong()
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performTouchInput { swipeUp(startY = centerY, endY = centerY - 30f, durationMillis = 800) }
        rule.waitForIdle()
        assertFalse(h.vm.fullPlayer)
        assertFalse(rule.exists(UiTags.FULL_PLAYER))
        rule.onNodeWithTag(UiTags.MINI_PLAYER).assertIsDisplayed()
    }

    @Test fun draggingTheFullPlayerDownCollapsesItAndAShortDragSpringsBack() {
        val h = playSong()
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performClick()
        rule.waitForTag(UiTags.FULL_PLAYER)
        // A short, slow drag stays open.
        rule.onNodeWithTag(UiTags.FULL_PLAYER).performTouchInput { swipeDown(startY = top + 120f, endY = top + 160f, durationMillis = 800) }
        rule.waitForIdle()
        assertTrue(h.vm.fullPlayer)
        rule.onNodeWithTag(UiTags.FULL_PLAYER).assertIsDisplayed()
        // A long one collapses; the bar stays and the music keeps playing.
        rule.onNodeWithTag(UiTags.FULL_PLAYER).performTouchInput { swipeDown(startY = top + 60f, endY = bottom - 40f) }
        rule.waitUntil(5_000) { !rule.exists(UiTags.FULL_PLAYER) }
        assertFalse(h.vm.fullPlayer)
        rule.onNodeWithTag(UiTags.MINI_PLAYER).assertIsDisplayed()
    }

    private fun openFull(h: Harness) {
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performClick()
        rule.waitForTag(UiTags.FULL_PLAYER)
        rule.waitUntil(5_000) { h.vm.fullPlayer }
        rule.waitForIdle()
    }

    @Test fun aSlowCarefulDragDownStillClosesThePlayer() {
        // every step is below touch slop: an ancestor that consumed the moves used to cancel the close before it started
        val h = playSong()
        openFull(h)
        rule.onNodeWithTag("cover-swipe").performTouchInput { swipeDown(startY = top + 40f, endY = bottom + 600f, durationMillis = 4_000) }
        rule.waitUntil(5_000) { !rule.exists(UiTags.FULL_PLAYER) }
        assertFalse(h.vm.fullPlayer)
    }

    @Test fun aSwipeDownThatStartsOnTheSeekBarClosesInsteadOfSeeking() {
        val h = playSong()
        openFull(h)
        rule.waitUntil(5_000) { h.vm.player.position.value.durationMs > 0 }
        val before = h.vm.player.position.value.positionMs
        rule.onNodeWithTag(UiTags.FULL_SEEK).performTouchInput { swipeDown(startY = centerY, endY = centerY + 1_200f, durationMillis = 600) }
        rule.waitUntil(5_000) { !rule.exists(UiTags.FULL_PLAYER) }
        assertFalse(h.vm.fullPlayer)
        // not a seek: the position only moved on with playback
        assertTrue(h.vm.player.position.value.positionMs - before < 5_000)
    }

    @Test fun aSwipeDownOnTheQueueAtItsTopClosesThePlayer() {
        val h = playSong()
        openFull(h)
        rule.onNodeWithTag(UiTags.FULL_QUEUE_TOGGLE).performClick()
        rule.waitForTag("queue-list")
        rule.onNodeWithTag("queue-list").performTouchInput { swipeDown(startY = top + 20f, endY = bottom + 900f, durationMillis = 700) }
        rule.waitUntil(5_000) { !rule.exists(UiTags.FULL_PLAYER) }
        assertFalse(h.vm.fullPlayer)
    }

    @Test fun playPauseTogglesFromTheBarAndFromTheFullPlayer() {
        val h = playSong()
        val before = h.vm.player.ui.value.playing
        rule.onNodeWithTag(UiTags.MINI_PLAY).performClick()
        rule.waitUntil(5_000) { h.vm.player.ui.value.playing != before }
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performClick()
        rule.waitForTag(UiTags.FULL_PLAYER)
        val mid = h.vm.player.ui.value.playing
        rule.onNodeWithTag(UiTags.FULL_PLAY).performClick()
        rule.waitUntil(5_000) { h.vm.player.ui.value.playing != mid }
        rule.onNodeWithTag(UiTags.FULL_PLAY).performClick()
        rule.waitUntil(5_000) { h.vm.player.ui.value.playing == mid }
    }

    @Test fun pressingTheSeekBarSeeksOnReleaseAndKeepsTheSong() {
        val h = playSong()
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performClick()
        rule.waitForTag(UiTags.FULL_PLAYER)
        val first = h.vm.player.ui.value.current?.id
        val duration = h.vm.player.position.value.durationMs
        rule.waitUntil(5_000) { h.vm.player.position.value.durationMs > 0 }
        // Press at about three quarters, hold, slide a little, release: the position commits on release.
        rule.onNodeWithTag(UiTags.FULL_SEEK).performTouchInput {
            down(percentOffset(0.75f, 0.5f)); moveTo(percentOffset(0.76f, 0.5f)); up()
        }
        rule.waitForIdle()
        val total = h.vm.player.position.value.durationMs
        val position = h.vm.player.position.value.positionMs
        assertTrue("position $position of $total", total > 0 && position in (total * 0.65).toLong()..(total * 0.85).toLong())
        assertEquals(first, h.vm.player.ui.value.current?.id)
        assertTrue(duration >= 0)
    }
}
