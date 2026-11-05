package app.keepfin.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
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
 * Undo of library actions (the owner's request, 2026-10-23): after an action the notice has Undo, which reverts only what that one
 * action changed. Moves, a deleted collection, tags and a removal from the download history, through the real screens.
 */
@RunWith(AndroidJUnit4::class)
class UndoUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private val song = "content://media/external/audio/media/102"

    private fun spaceHarness(route: LibraryRoute): Harness {
        val h = Harness("spaces-v2")
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(route)
        h.show(rule)
        return h
    }

    private fun selectSong(h: Harness) {
        rule.waitForTag(UiTags.libItem(song))
        rule.onNodeWithTag(UiTags.libItem(song)).performTouchInput { longClick() }
        rule.waitForTag(UiTags.LIB_SELECTION_BAR)
    }

    /** Waits for the notice [text] and presses its Undo. */
    private fun pressUndo(text: String) {
        rule.waitUntil(5_000) { rule.hasText(text) }
        rule.onNodeWithText("Undo").performClick()
    }

    @Test fun undoOfAMoveSendsTheFileBackToItsSpace() {
        val h = spaceHarness(LibraryRoute.Space("music"))
        assertEquals("music", h.engine.fakeLibrary.item(song)!!.space)
        selectSong(h)
        rule.onNodeWithTag(UiTags.LIB_SELECTION_MOVE).performClick()
        rule.waitForTag("move-workout")
        rule.onNodeWithTag("move-workout").performClick()
        rule.waitUntil(5_000) { h.engine.fakeLibrary.item(song)!!.space == "workout" }

        pressUndo("Moved to Workout")
        rule.waitUntil(5_000) { h.engine.fakeLibrary.item(song)!!.space == "music" }
        assertTrue(h.engine.fakeLibrary.calls.toString(), h.engine.fakeLibrary.calls.contains("move:music"))
    }

    @Test fun aNoticeWithUndoGoesAwayByItselfAndTheActionStays() {
        val h = spaceHarness(LibraryRoute.Space("music"))
        selectSong(h)
        rule.onNodeWithTag(UiTags.LIB_SELECTION_MOVE).performClick()
        rule.waitForTag("move-workout")
        rule.onNodeWithTag("move-workout").performClick()
        rule.waitUntil(5_000) { rule.hasText("Moved to Workout") }
        // the notice's clock runs on the test's clock: move it a second at a time and count
        var shown = 0L
        while (rule.hasText("Moved to Workout") && shown < 30_000) {
            rule.mainClock.advanceTimeBy(1_000)
            rule.waitForIdle()
            shown += 1_000
        }
        assertTrue("shown for about 10 s ($shown ms)", shown in 8_000..12_000)
        assertEquals("workout", h.engine.fakeLibrary.item(song)!!.space)
    }

    private fun deleteForGood(h: Harness) {
        h.engine.fakeLibrary.keepsTrash = true
        selectSong(h)
        rule.onNodeWithTag(UiTags.LIB_SELECTION_DELETE).performClick()
        rule.waitForTag("delete-for-good")
        rule.onNodeWithTag("delete-for-good").performClick()
        rule.waitUntil(5_000) { rule.hasText("erased from your phone once the Undo notice closes") }
        rule.onNodeWithTag("delete-confirm").performClick()
        rule.waitUntil(5_000) { rule.hasText("for good. Undo works until this notice closes.") }
        assertTrue(h.engine.fakeLibrary.calls.toString(), h.engine.fakeLibrary.calls.contains("markErase:$song"))
    }

    @Test fun deleteForGoodErasesWhenTheUndoNoticeCloses() {
        val h = spaceHarness(LibraryRoute.Space("music"))
        deleteForGood(h)
        var waited = 0L
        while ("erase:$song" !in h.engine.fakeLibrary.calls && waited < 30_000) { rule.mainClock.advanceTimeBy(1_000); rule.waitForIdle(); waited += 1_000 }
        assertTrue("erased when the notice ran out (${waited} ms)", waited in 8_000..12_000)
        rule.waitUntil(5_000) { "erase:$song" in h.engine.fakeLibrary.calls }
    }

    @Test fun undoOfDeleteForGoodBringsTheFileBackAndErasesNothing() {
        val h = spaceHarness(LibraryRoute.Space("music"))
        deleteForGood(h)
        rule.onNodeWithText("Undo").performClick()
        rule.waitUntil(5_000) { h.engine.fakeLibrary.item(song) != null }
        rule.mainClock.advanceTimeBy(15_000)
        rule.waitForIdle()
        assertTrue(h.engine.fakeLibrary.calls.toString(), h.engine.fakeLibrary.calls.none { it.startsWith("erase:") })
    }

    @Test fun undoOfDeletingACollectionBringsItBackWithTheSameIdNameAndOrder() {
        val h = Harness("spaces-v2")
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(LibraryRoute.UserPlaylist("roadtrip"))
        h.show(rule)
        rule.waitForTag("playlist-menu")
        val before = h.engine.fakeLibrary.library.value.collections.first { it.id == "roadtrip" }
        rule.onNodeWithTag("playlist-menu").performClick()
        rule.onNodeWithTag("playlist-delete").performClick()
        rule.waitUntil(5_000) { rule.hasText("Delete playlist") }
        rule.onNodeWithText("Delete playlist").performClick()
        rule.waitUntil(5_000) { h.engine.fakeLibrary.library.value.collections.none { it.id == "roadtrip" } }

        pressUndo("Deleted “${before.name}”. The songs stay in your library.")
        rule.waitUntil(5_000) { h.engine.fakeLibrary.library.value.collections.any { it.id == "roadtrip" } }
        val back = h.engine.fakeLibrary.library.value.collections.first { it.id == "roadtrip" }
        assertEquals(before.name, back.name)
        assertEquals(before.itemIds, back.itemIds)
        assertEquals(before.space, back.space)
    }

    @Test fun undoOfTagsTakesBackOnlyTheTagsThatActionAdded() {
        val h = spaceHarness(LibraryRoute.Space("music"))
        val before = h.engine.fakeLibrary.item(song)!!.userTags
        selectSong(h)
        rule.onNodeWithTag(UiTags.LIB_SELECTION_TAGS).performClick()
        rule.waitForTag("tags-edit-sheet")
        rule.onNodeWithTag("tags-field").performTextInput("Gym Day")
        rule.onNodeWithTag("tags-add").performClick()
        rule.onNodeWithTag("tags-save").performClick()
        rule.waitUntil(5_000) { h.engine.fakeLibrary.item(song)!!.userTags.contains("gym day") }
        // a tag added by a later action stays when this one is undone
        h.engine.fakeLibrary.libraryTag(listOf(song), add = listOf("later"))

        pressUndo("Tags saved")
        rule.waitUntil(5_000) { !h.engine.fakeLibrary.item(song)!!.userTags.contains("gym day") }
        assertEquals(before + "later", h.engine.fakeLibrary.item(song)!!.userTags)
    }

    @Test fun undoOfAFavouriteAndOfAnAddToACollectionRevertOnlyThatChange() {
        val h = spaceHarness(LibraryRoute.Space("music"))
        h.vm.libraryActions.favorite(h.engine.fakeLibrary.item(song)!!)
        pressUndo("Added to favorites")
        rule.waitUntil(5_000) { !h.engine.fakeLibrary.item(song)!!.favorite }

        // 102 is in the collection already, 104 is not: Undo removes only 104
        val had = h.engine.fakeLibrary.library.value.collections.first { it.id == "roadtrip" }.itemIds
        val added = "content://media/external/audio/media/104"
        assertTrue(song in had && added !in had)
        h.vm.libraryActions.addToPlaylist("roadtrip", "Road trip", listOf(song, added))
        pressUndo("Added 2 songs to “Road trip”")
        rule.waitUntil(5_000) { h.engine.fakeLibrary.library.value.collections.first { it.id == "roadtrip" }.itemIds == had }
    }

    @Test fun undoOfRemovingFromHistoryBringsTheRecordBack() {
        val h = Harness("groups-failed")
        h.show(rule)
        rule.waitForTag(UiTags.group("g-ost"))
        rule.scrollListTo(UiTags.QUEUE, UiTags.group("g-ost"))
        rule.onNodeWithTag(UiTags.groupMenu("g-ost")).performClick()
        rule.onNodeWithTag(UiTags.GROUP_REMOVE_FAILED).performClick()
        rule.waitUntil(5_000) { h.engine.actions.contains("remove:g-ost-10") }

        pressUndo("Removed from history")
        rule.waitUntil(5_000) { h.engine.actions.contains("restore:g-ost-10") }
        rule.waitUntil(5_000) { h.engine.jobs.value.any { it.id == "g-ost-10" } }
    }

    @Test fun clearHistoryHasUndoThatBringsEveryRecordBack() {
        val h = Harness("groups-failed")
        h.show(rule)
        rule.waitForTag(UiTags.group("g-ost"))
        val before = h.engine.jobs.value.map { it.id }.toSet()
        rule.runOnIdle { h.vm.jobs.clearHistory() }
        rule.waitUntil(5_000) { h.engine.actions.contains("clearHistory") }
        assertTrue("something was cleared", h.engine.jobs.value.size < before.size)
        pressUndo("History cleared")
        rule.waitUntil(5_000) { h.engine.jobs.value.map { it.id }.toSet() == before }
    }
}
