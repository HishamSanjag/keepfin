package app.keepfin.ui

import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.ui.library.LibraryRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Remove from" a collection of yours, the owner's report (2026-10-23): the X on every row is gone (it is in each row's ⋮ and the
 * selection bar), a second removal no longer brings back the first (the X used the list from when the page opened), and Undo puts the
 * songs back where they were.
 */
@RunWith(AndroidJUnit4::class)
class RemoveFromCollectionUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private fun song(n: Int) = "content://media/external/audio/media/$n"

    private fun order(h: Harness) = h.engine.fakeLibrary.library.value.collections.first { it.id == "roadtrip" }.itemIds

    private fun open(): Harness {
        val h = Harness("spaces-v2")
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(LibraryRoute.UserPlaylist("roadtrip"))
        h.show(rule)
        rule.waitForTag(UiTags.LIB_LIST)
        repeat(6) {
            if (rule.onAllNodesWithTag(UiTags.libMenu(song(103))).fetchSemanticsNodes().isNotEmpty()) return@repeat
            rule.onNodeWithTag(UiTags.LIB_LIST).performTouchInput { swipeUp(startY = bottom * 0.8f, endY = bottom * 0.4f) }
            rule.waitForIdle()
        }
        assertEquals(listOf(song(101), song(102), song(103)), order(h))
        return h
    }

    private fun removeWithMenu(id: String) {
        rule.onNodeWithTag(UiTags.libMenu(id)).performClick()
        rule.waitForTag(UiTags.itemAction("uncollect"))
        rule.onNodeWithTag(UiTags.itemAction("uncollect")).performClick()
        rule.waitForIdle()
    }

    /** Waits for the notice [text], presses its Undo and waits for the collection to read [expected]. */
    private fun undo(h: Harness, text: String, expected: List<String>) {
        rule.waitUntil(5_000) { rule.hasText(text) }
        rule.onNodeWithText("Undo").performClick()
        runCatching { rule.waitUntil(5_000) { order(h) == expected } }
        assertEquals("after Undo (library calls: ${h.engine.fakeLibrary.calls.filter { it.startsWith("collection") }})", expected, order(h))
    }

    @Test fun noRowHasAnXAndTwoRemovalsInARowBothStay() {
        val h = open()
        assertTrue("no remove button on the rows", rule.onAllNodesWithTag("playlist-remove-${song(101)}", useUnmergedTree = true).fetchSemanticsNodes().isEmpty())

        removeWithMenu(song(101))
        rule.waitUntil(5_000) { order(h) == listOf(song(102), song(103)) }
        // the second removal starts from what is saved: the first song does not come back
        removeWithMenu(song(103))
        rule.waitUntil(5_000) { order(h) == listOf(song(102)) }
        assertEquals(listOf(song(102)), order(h))

        // Undo of the last removal puts that song back at the end, where it was
        undo(h, "Removed from “Road trip”", listOf(song(102), song(103)))
    }

    @Test fun theSelectionBarRemovesAndUndoPutsThemBackInPlace() {
        val h = open()
        rule.onNodeWithTag(UiTags.libItem(song(101))).performTouchInput { longClick() }
        rule.onNodeWithTag(UiTags.libItem(song(102))).performClick()
        rule.waitForTag(UiTags.LIB_SELECTION_UNCOLLECT)
        rule.onNodeWithTag(UiTags.LIB_SELECTION_UNCOLLECT).performClick()
        rule.waitUntil(5_000) { order(h) == listOf(song(103)) }

        undo(h, "Removed 2 songs from “Road trip”", listOf(song(101), song(102), song(103)))
    }
}
