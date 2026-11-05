package app.keepfin.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.ItemQuery
import app.keepfin.LibraryItem
import app.keepfin.ui.library.LibraryRoute
import app.keepfin.ui.settings.SettingsCategory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Trash page (Library > Trash): rows, Restore, Delete for good, Select, Empty trash and the empty state, through the real screens. */
@RunWith(AndroidJUnit4::class)
class TrashUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private fun harness(daysAgo: List<Int>): Triple<Harness, List<LibraryItem>, List<String>> {
        val h = Harness("library")
        val ids = h.vm.lib.browse.ids(ItemQuery(spaces = listOf("music")), 10).take(daysAgo.size)
        val items = ids.map { h.vm.lib.item(it)!! }
        // taken out of the library as a delete would, then put in the trash with their ages
        h.vm.lib.deleteItems(ids)
        items.forEachIndexed { i, item -> h.engine.fakeLibrary.putInTrash(item, daysAgo[i]) }
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(LibraryRoute.Trash)
        h.show(rule)
        return Triple(h, items, ids)
    }

    @Test fun theTrashRowLivesInSettingsStorageAndBackReturnsThere() {
        val h = Harness("library")
        val ids = h.vm.lib.browse.ids(ItemQuery(spaces = listOf("music")), 10).take(2)
        val items = ids.map { h.vm.lib.item(it)!! }
        h.vm.lib.deleteItems(ids)
        items.forEach { h.engine.fakeLibrary.putInTrash(it, 0) }
        // the Library home has no Trash row any more
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.waitForTag(UiTags.LIB_LIST)
        assertFalse(rule.exists(UiTags.LIB_TRASH_ROW))
        // Settings > Storage & copies has it, with the count and size
        rule.runOnIdle { h.openSettings(SettingsCategory.STORAGE) }
        rule.waitForTag(UiTags.LIB_TRASH_ROW)
        rule.waitUntil(5_000) { rule.hasText("2 files · ") }
        rule.onNodeWithTag(UiTags.LIB_TRASH_ROW).performClick()
        rule.waitForTag("trash-page")
        assertTrue(h.vm.libraryRoute is LibraryRoute.Trash)
        rule.runOnIdle { h.vm.back() }
        rule.waitUntil(5_000) { h.vm.destination == Destination.SETTINGS && h.vm.settingsPage == SettingsCategory.STORAGE }
        rule.waitForTag(UiTags.LIB_TRASH_ROW)
    }

    @Test fun anEmptyTrashRowSaysEmpty() {
        val h = Harness("library")
        h.engine.fakeLibrary.keepsTrash = true
        h.show(rule)
        rule.runOnIdle { h.openSettings(SettingsCategory.STORAGE) }
        rule.waitForTag(UiTags.LIB_TRASH_ROW)
        rule.waitUntil(5_000) { rule.hasText("Empty") }
    }

    @Test fun emptyTrashKeepsTheDialogOpenWithProgressUntilTheWorkEnds() {
        val (h, _, _) = harness(listOf(0, 1))
        h.engine.fakeLibrary.emptyTrashMs = 1_500
        rule.waitForTag(UiTags.TRASH_EMPTY_ALL)
        rule.onNodeWithTag(UiTags.TRASH_EMPTY_ALL).performClick()
        rule.onNodeWithTag(UiTags.TRASH_CONFIRM).performClick()
        rule.waitUntil(5_000) { rule.hasText("Emptying the trash…") }
        rule.onNodeWithTag(UiTags.TRASH_CONFIRM).assertIsNotEnabled()
        rule.waitUntil(10_000) { !rule.hasText("Emptying the trash…") }
        assertFalse(rule.exists(UiTags.TRASH_CONFIRM))
        rule.waitUntil(5_000) { rule.hasText("Emptied the trash: 2 files erased") }
    }

    @Test fun thePageListsEachFileWithItsSpaceSizeAndAge() {
        val (_, items, _) = harness(listOf(0, 1, 12, 28))
        rule.waitForTag(UiTags.TRASH_LIST)
        rule.waitUntil(5_000) { rule.hasText(items[0].title) }
        assertTrue(rule.hasText("Deleted today · 30 days left"))
        assertTrue(rule.hasText("Deleted yesterday · 29 days left"))
        assertTrue(rule.hasText("Deleted 12 days ago · 18 days left"))
        // three days or fewer: the line still says it in words (the amber mark is extra)
        assertTrue(rule.hasText("Deleted 28 days ago · 2 days left"))
        items.forEach { rule.onNodeWithTag(UiTags.trashRestore(it.id)).assertExists() }
    }

    @Test fun restoreBringsTheFileBackWithoutAskingAndSaysWhatCameBack() {
        val (h, items, _) = harness(listOf(0, 1))
        rule.waitForTag(UiTags.trashRestore(items[0].id))
        rule.onNodeWithTag(UiTags.trashRestore(items[0].id)).performClick()
        rule.waitUntil(5_000) { rule.hasText("Restored “${items[0].title}”") }
        assertTrue(h.engine.fakeLibrary.calls.toString(), h.engine.fakeLibrary.calls.contains("restore:${items[0].id}"))
        rule.waitUntil(5_000) { h.engine.fakeLibrary.item(items[0].id) != null }
        rule.waitUntil(5_000) { !rule.exists(UiTags.trashRow(items[0].id)) }
        rule.onNodeWithTag(UiTags.trashRow(items[1].id)).assertExists()
    }

    @Test fun deleteForGoodAsksFirstAndThenErasesThatFile() {
        val (h, items, _) = harness(listOf(0, 1))
        rule.waitForTag(UiTags.trashMore(items[0].id))
        rule.onNodeWithTag(UiTags.trashMore(items[0].id)).performClick()
        rule.onNodeWithTag(UiTags.TRASH_MENU_ERASE).performClick()
        rule.waitUntil(5_000) { rule.hasText("Delete “${items[0].title}” for good?") }
        // Cancel keeps it
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertTrue(h.engine.fakeLibrary.calls.none { it.startsWith("eraseTrash:") })
        rule.onNodeWithTag(UiTags.trashMore(items[0].id)).performClick()
        rule.onNodeWithTag(UiTags.TRASH_MENU_ERASE).performClick()
        rule.waitForTag(UiTags.TRASH_CONFIRM)
        rule.onNodeWithTag(UiTags.TRASH_CONFIRM).performClick()
        rule.waitUntil(5_000) { h.engine.fakeLibrary.calls.contains("eraseTrash:${items[0].id}") }
        rule.waitUntil(5_000) { !rule.exists(UiTags.trashRow(items[0].id)) }
        rule.onNodeWithTag(UiTags.trashRow(items[1].id)).assertExists()
    }

    @Test fun selectingOffersRestoreAndDeleteForGoodForSeveralAtOnce() {
        val (h, items, _) = harness(listOf(0, 1, 2))
        rule.waitForTag(UiTags.trashRow(items[0].id))
        rule.onNodeWithTag(UiTags.trashRow(items[0].id)).performTouchInput { longClick() }
        rule.waitForTag(UiTags.TRASH_BULK_RESTORE)
        rule.onNodeWithTag(UiTags.trashRow(items[1].id)).performClick()
        rule.waitUntil(5_000) { rule.hasText("2 selected") }
        rule.onNodeWithTag(UiTags.TRASH_BULK_RESTORE).performClick()
        rule.waitUntil(5_000) { rule.hasText("Restored 2 files") }
        rule.waitUntil(5_000) { items.take(2).all { h.engine.fakeLibrary.item(it.id) != null } }
        assertEquals(null, h.engine.fakeLibrary.item(items[2].id))
        rule.waitUntil(5_000) { !rule.exists(UiTags.trashRow(items[0].id)) && !rule.exists(UiTags.trashRow(items[1].id)) }
        rule.onNodeWithTag(UiTags.trashRow(items[2].id)).assertExists()
    }

    @Test fun emptyTrashAsksWithTheCountAndThenErasesEverything() {
        val (h, items, _) = harness(listOf(0, 1, 2))
        rule.waitForTag(UiTags.TRASH_EMPTY_ALL)
        rule.onNodeWithTag(UiTags.TRASH_EMPTY_ALL).performClick()
        rule.waitUntil(5_000) { rule.hasText("Erase 3 files (") && rule.hasText("for good? This can't be undone.") }
        rule.onNodeWithTag(UiTags.TRASH_CONFIRM).performClick()
        rule.waitUntil(5_000) { h.engine.fakeLibrary.calls.contains("emptyTrash") }
        rule.waitUntil(5_000) { rule.hasText("Trash is empty") }
        rule.onNodeWithText("Files deleted from your Keepfin folder appear here for 30 days.").assertExists()
        items.forEach { assertTrue(h.engine.fakeLibrary.item(it.id) == null) }
    }

    @Test fun anEmptyTrashSaysSo() {
        val h = Harness("library")
        h.engine.fakeLibrary.keepsTrash = true
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(LibraryRoute.Trash)
        h.show(rule)
        rule.waitUntil(5_000) { rule.hasText("Trash is empty") }
        rule.onNodeWithText("Files deleted from your Keepfin folder appear here for 30 days.").assertExists()
    }

    @Test fun withoutTheKeepfinFolderThePageSaysTheTrashBelongsToIt() {
        val h = Harness("library")
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(LibraryRoute.Trash)
        h.show(rule)
        rule.waitUntil(5_000) { rule.hasText("Trash belongs to the Keepfin folder") }
    }
}
