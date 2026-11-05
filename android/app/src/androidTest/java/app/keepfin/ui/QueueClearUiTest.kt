package app.keepfin.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.ui.player.SimulatedPlayerPort
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Clear upcoming" in the full player (as on Windows): only the songs after the current one go; the current keeps playing and the player stays open. */
@RunWith(AndroidJUnit4::class)
class QueueClearUiTest {
    @get:Rule val rule = createComposeRule()

    private val a = "content://media/external/audio/media/101"
    private val b = "content://media/external/audio/media/102"
    private val c = "content://media/external/audio/media/104"

    @Before fun setUp() { StudioEngine.override = null; SimulatedPlayerPort.ticks = false }
    @After fun tearDown() { StudioEngine.override = null; SimulatedPlayerPort.ticks = true }

    @Test fun clearUpcomingKeepsTheCurrentSongPlayingAndThePlayerOpen() {
        val h = Harness("library")
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.runOnIdle { h.vm.player.playNow(listOf(a, b, c), index = 1) }
        rule.runOnIdle { h.vm.fullPlayer = true }
        rule.waitForTag(UiTags.FULL_PLAYER)
        rule.onNodeWithTag(UiTags.FULL_QUEUE_TOGGLE).performClick()
        rule.waitForTag("queue-list")
        rule.onNodeWithTag("queue-clear").assertIsEnabled()
        rule.onNodeWithTag("queue-clear").performClick()
        rule.waitUntil(5_000) { h.vm.player.ui.value.queue.size == 2 }
        val s = h.vm.player.ui.value
        assertEquals("the played and current songs stay", listOf(a, b), s.queue.map { it.id })
        assertEquals(1, s.index)
        assertTrue("still playing", s.playing)
        assertTrue("the full player stays open", h.vm.fullPlayer)
        rule.waitForTag(UiTags.FULL_PLAYER)
        // nothing comes after the current song now
        rule.onNodeWithTag("queue-clear").assertIsNotEnabled()
    }

    @Test fun clearQueueInTheMenuStopsAndClosesThePlayer() {
        val h = Harness("library")
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.runOnIdle { h.vm.player.playNow(listOf(a, b, c), index = 1) }
        rule.runOnIdle { h.vm.fullPlayer = true }
        rule.waitForTag(UiTags.FULL_PLAYER)
        rule.onNodeWithTag("full-menu").performClick()
        rule.onNodeWithText("Clear queue").performClick()
        rule.waitUntil(5_000) { h.vm.player.ui.value.queue.isEmpty() }
        assertTrue("stopped", !h.vm.player.ui.value.playing)
        assertTrue("the full player closes", !h.vm.fullPlayer)
    }
}
