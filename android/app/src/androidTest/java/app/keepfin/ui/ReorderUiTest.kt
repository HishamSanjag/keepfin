package app.keepfin.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.ui.library.LibraryRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drag to reorder in a collection, the owner's report (2026-10-23): the first drag worked, every drag after it moved rows the wrong way
 * or not at all, because the drag kept working on the list from before the save. Each drag here starts after the previous one is saved.
 */
@RunWith(AndroidJUnit4::class)
class ReorderUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private fun song(n: Int) = "content://media/external/audio/media/$n"

    private fun order(h: Harness) = h.engine.fakeLibrary.library.value.collections.first { it.id == "roadtrip" }.itemIds

    /** Drags [id]'s handle by [rows] rows (negative: up), in small steps like a finger, and lifts. */
    private fun drag(id: String, rows: Float, rowPx: Float) {
        rule.onNodeWithTag("playlist-handle-$id", useUnmergedTree = true).performTouchInput {
            down(center)
            val steps = 24
            repeat(steps) { moveBy(Offset(0f, rows * rowPx / steps)); advanceEventTime(16) }
            up()
        }
        rule.waitForIdle()
    }

    private fun rowPx(): Float {
        val a = rule.onNodeWithTag("playlist-handle-${song(101)}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.y
        val b = rule.onNodeWithTag("playlist-handle-${song(102)}", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.y
        return kotlin.math.abs(b - a)
    }

    @Test fun everyDragAfterTheFirstMovesTheRowItIsDragging() {
        val h = Harness("spaces-v2")
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(LibraryRoute.UserPlaylist("roadtrip"))
        h.show(rule)
        rule.waitForTag(UiTags.LIB_LIST)
        // the collection's header fills the screen: the rows come into view first
        repeat(6) {
            if (rule.onAllNodesWithTag("playlist-handle-${song(103)}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()) return@repeat
            rule.onNodeWithTag(UiTags.LIB_LIST).performTouchInput { swipeUp(startY = bottom * 0.8f, endY = bottom * 0.4f) }
            rule.waitForIdle()
        }
        assertEquals(listOf(song(101), song(102), song(103)), order(h))
        val row = rowPx()

        // the first song goes down one place
        drag(song(101), 1.2f, row)
        rule.waitUntil(5_000) { order(h) == listOf(song(102), song(101), song(103)) }

        // then the last one goes to the top: the drag works on the saved order, not on the one from before
        drag(song(103), -2.2f, row)
        rule.waitUntil(5_000) { order(h) == listOf(song(103), song(102), song(101)) }

        // and once more, the middle one down to the end
        drag(song(102), 1.2f, row)
        rule.waitUntil(5_000) { order(h) == listOf(song(103), song(101), song(102)) }
        assertEquals(listOf(song(103), song(101), song(102)), order(h))
    }
}
