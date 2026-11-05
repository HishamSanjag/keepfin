package app.keepfin.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.ItemQuery
import app.keepfin.ui.library.LibraryRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Find duplicates: the menu entry opens the page, and removing an extra copy keeps every collection whole. */
@RunWith(AndroidJUnit4::class)
class DuplicatesUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    @Test fun aSpacesMenuFindsItsDuplicates() {
        val h = Harness("library")
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(LibraryRoute.Space("music"))
        h.show(rule)
        rule.waitForTag(UiTags.LIB_OVERFLOW)
        rule.onNodeWithTag(UiTags.LIB_OVERFLOW).performClick()
        rule.onNodeWithTag(UiTags.LIB_DUPLICATES).performClick()
        rule.waitForIdle()
        assertTrue(h.vm.libraryRoute is LibraryRoute.Duplicates)
        // the test library keeps no content fingerprints: nothing is the same file twice
        rule.waitUntil(5_000) { rule.exists(UiTags.EMPTY_STATE) }
        rule.onNodeWithText("No duplicates").assertExists()
    }

    @Test fun aMusicFolderBecomesACollectionFromItsMenu() {
        val h = Harness("library")
        val folder = h.vm.lib.browse.folderList("music", setOf(app.keepfin.LibraryItemKind.AUDIO)).first { it.count > 0 }
        val name = folder.path.substringAfterLast('/')
        val songs = h.vm.lib.browse.top(ItemQuery(spaces = listOf("music"), subtree = folder.path, kinds = setOf(app.keepfin.LibraryItemKind.AUDIO)), 5000).map { it.id }.toSet()
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(LibraryRoute.Space("music"))
        h.vm.openLibraryDetail(LibraryRoute.Folder("music", folder.path))
        h.show(rule)
        rule.waitForTag("list-menu")
        rule.onNodeWithTag("list-menu").performClick()
        rule.onNodeWithTag("list-menu-make-collection").performClick()
        rule.waitUntil(5_000) { h.vm.lib.library.value.collections.any { it.name == name } }
        assertEquals(songs, h.vm.lib.library.value.collections.first { it.name == name }.itemIds.toSet())
    }

    @Test fun removingAnExtraCopyPutsTheKeptOneInItsPlaceInEveryCollection() {
        val h = Harness("library")
        val ids = h.vm.lib.browse.ids(ItemQuery(spaces = listOf("music")), 10)
        assertTrue(ids.size >= 4)
        val (a, b, c, kept) = ids
        val mix = h.vm.lib.collectionSave(null, "Mix", listOf(a, b, c))
        h.show(rule)
        rule.runOnIdle { h.vm.libraryActions.removeDuplicates(mapOf(b to kept)) }
        rule.waitUntil(5_000) { h.vm.lib.collection(mix)?.itemIds == listOf(a, kept, c) }
        assertEquals(listOf(a, kept, c), h.vm.lib.collection(mix)?.itemIds)
    }
}
