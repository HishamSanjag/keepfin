package app.keepfin.ui

import app.keepfin.allItems
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.ui.library.LibraryRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Beta 2 device feedback: the guide, "+ Add" on spaces, Move/Copy to space for collections, and the player swipes. */
@RunWith(AndroidJUnit4::class)
class FeedbackUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private val song = "content://media/external/audio/media/102"

    // ---- the guide ----------------------------------------------------------------------------------

    @Test fun guideOpensFromSettingsSearchFiltersAndTryItNavigates() {
        val h = Harness("library")
        h.openSettings(app.keepfin.ui.settings.SettingsCategory.HELP)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_LIST)
        rule.scrollListTo(UiTags.SETTINGS_LIST, UiTags.SETTINGS_GUIDE_ROW)
        rule.onNodeWithTag(UiTags.SETTINGS_GUIDE_ROW).performClick()
        rule.waitForTag(UiTags.GUIDE)
        assertEquals(SubPage.GUIDE, h.vm.subPage)
        // Android features only: the desktop-only ones never show, shared ones do.
        rule.onNodeWithTag(UiTags.GUIDE).performScrollToNode(hasTestTag(UiTags.guideCard("paste")))
        rule.onNodeWithTag(UiTags.guideCard("paste")).assertIsDisplayed()
        // Search narrows the list to matching cards.
        rule.onNodeWithTag(UiTags.GUIDE_SEARCH).performTextInput("untick")
        rule.waitUntil(5_000) { !rule.exists(UiTags.guideCard("paste")) }
        rule.onNodeWithTag(UiTags.guideCard("collections-download")).assertIsDisplayed()
        // Expanding shows numbered steps and Try it, which leaves the guide for the destination.
        rule.onNodeWithTag(UiTags.guideCard("collections-download")).performClick()
        rule.waitForTag(UiTags.guideTry("collections-download"))
        assertTrue(rule.hasText("1."))
        rule.onNodeWithTag(UiTags.guideTry("collections-download")).performClick()
        rule.waitUntil(5_000) { h.vm.subPage == null }
        assertEquals(Destination.DOWNLOADS, h.vm.destination)
    }

    @Test fun guideSearchWithNoMatchSaysSo() {
        val h = Harness("library")
        h.vm.open(SubPage.GUIDE)
        h.show(rule)
        rule.waitForTag(UiTags.GUIDE_SEARCH)
        rule.onNodeWithTag(UiTags.GUIDE_SEARCH).performTextInput("zzzz nothing")
        rule.waitForTag("guide-empty")
    }

    @Test fun guideTryItOpensTheLibraryAndTheSpacesEditor() {
        val h = Harness("library")
        h.vm.open(SubPage.GUIDE)
        h.show(rule)
        rule.waitForTag(UiTags.GUIDE_SEARCH)
        h.vm.guideTry("library-edit")
        rule.waitUntil(5_000) { h.vm.libraryRoute == LibraryRoute.Manage }
        assertEquals(Destination.LIBRARY, h.vm.destination)
        h.vm.guideTry("library")
        assertEquals(LibraryRoute.Home, h.vm.libraryRoute)
    }

    @Test fun guideOpensFromSettingsHelpAndFromWhatsNewAndNotFromTheLibraryHeader() {
        val h = Harness("library")
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.waitForTag(UiTags.LIB_LIST)
        assertFalse("the help icon left the Library header", rule.exists(UiTags.LIB_HELP))
        rule.runOnIdle { h.openSettings(app.keepfin.ui.settings.SettingsCategory.HELP) }
        rule.scrollListTo(UiTags.SETTINGS_LIST, UiTags.SETTINGS_GUIDE_ROW)
        rule.onNodeWithTag(UiTags.SETTINGS_GUIDE_ROW).performClick()
        rule.waitForTag(UiTags.GUIDE)
        h.vm.closeSubPage()
        rule.waitUntil(5_000) { !rule.exists(UiTags.GUIDE) }
        h.vm.updatesFlow.showWhatsNew()
        rule.waitForTag(UiTags.WHATS_NEW_GUIDE)
        rule.onNodeWithTag(UiTags.WHATS_NEW_GUIDE).performClick()
        rule.waitForTag(UiTags.GUIDE, 15_000)
        assertNotNull(h.vm.subPage)
    }

    // ---- + Add and Move/Copy to space ----------------------------------------------------------------------------

    private fun spaceHarness(route: LibraryRoute): Harness {
        val h = Harness("spaces-v2")
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(route)
        h.show(rule)
        return h
    }

    @Test fun spaceTopBarHasAVisibleAddButtonThatOpensTheLibraryPicker() {
        spaceHarness(LibraryRoute.Space("workout"))
        rule.waitForTag(UiTags.LIB_SPACE_ADD)
        rule.onNodeWithContentDescription("Add to Workout").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.LIB_SPACE_ADD).performClick()
        rule.waitForTag(UiTags.ADD_SHEET)
        rule.onNodeWithTag(UiTags.addTab("collections")).assertExists()
    }

    @Test fun addButtonOnASmartSpaceOpensItsRules() {
        spaceHarness(LibraryRoute.Space("arabic"))
        rule.waitForTag(UiTags.SMART_SPACE)
        rule.onNodeWithTag(UiTags.LIB_SPACE_ADD).performClick()
        rule.waitForTag(UiTags.RULES_EDITOR)
    }

    @Test fun collectionMenuMovesAWholeCollectionToAnotherSpaceWithAResultNotice() {
        val h = spaceHarness(LibraryRoute.Collection("workout", "gym"))
        rule.waitForTag(UiTags.COLLECTION_DETAIL)
        rule.onNodeWithTag("collection-menu").performClick()
        rule.onNodeWithTag("collection-move-space").performClick()
        rule.waitForTag(UiTags.SPACE_PICKER)
        assertTrue(rule.hasText("Move to space"))
        // The space it is already in is not offered.
        assertFalse(rule.exists(UiTags.spacePick("workout")))
        rule.onNodeWithTag(UiTags.spacePick("music")).performClick()
        rule.waitUntil(5_000) { h.engine.fakeLibrary.calls.contains("addFrom:music:move") }
        rule.waitUntil(5_000) { rule.hasText("Moved 6 to Music") }
        assertEquals("music", h.engine.fakeLibrary.item("content://media/external/audio/media/505")!!.space)
    }

    @Test fun longPressOnACollectionRowCopiesItToASpace() {
        val h = spaceHarness(LibraryRoute.Collections)
        rule.waitForTag(UiTags.collectionRow("khaleeji"))
        rule.onNodeWithTag(UiTags.collectionRow("khaleeji")).performTouchInput { longClick() }
        rule.onNodeWithText("Copy to space…").performClick()
        rule.waitForTag(UiTags.SPACE_PICKER)
        rule.onNodeWithTag(UiTags.spacePick("workout")).performClick()
        rule.waitUntil(5_000) { h.engine.fakeLibrary.calls.contains("addFrom:workout:copy") }
        rule.waitUntil(5_000) { rule.hasText("Copied 2 to Workout") }
        assertEquals("The original stays", "music", h.engine.fakeLibrary.item("content://media/external/audio/media/107")!!.space)
    }

    @Test fun aSongMenuOffersCopyToSpace() {
        val h = spaceHarness(LibraryRoute.Space("music"))
        rule.waitForTag(UiTags.libItem(song))
        rule.onNodeWithTag(UiTags.libMenu(song)).performClick()
        rule.onNodeWithText("Copy to space…").performClick()
        rule.waitForTag(UiTags.SPACE_PICKER)
        rule.onNodeWithTag(UiTags.spacePick("workout")).performClick()
        rule.waitUntil(5_000) { h.engine.fakeLibrary.calls.contains("addFrom:workout:copy") }
        rule.waitUntil(5_000) { rule.hasText("Copied 1 to Workout") }
    }

    // ---- swipes -------------------------------------------------------------------------------------------------------

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

    private fun currentId(h: Harness) = h.vm.player.ui.value.current?.id

    @Test fun miniPlayerSwipesBetweenSongsAndDownHidesIt() {
        val h = playSong()
        val first = currentId(h)
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performTouchInput { swipeLeft() }
        rule.waitUntil(5_000) { currentId(h) != first }
        val second = currentId(h)
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performTouchInput { swipeRight() }
        rule.waitUntil(5_000) { currentId(h) == first }
        assertNotEquals(first, second)
        // A swipe past touch slop but short of the threshold follows the finger, then snaps back and changes nothing (and is not a tap).
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performTouchInput { swipeLeft(startX = centerX, endX = centerX - 90f) }
        rule.waitForIdle()
        assertEquals(first, currentId(h))
        assertFalse(h.vm.fullPlayer)
        // Down hides the bar and stops playback, like the close button.
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performTouchInput { swipeDown(startY = 8f, endY = height + 160f) }
        rule.waitUntil(5_000) { !rule.exists(UiTags.MINI_PLAYER) }
        assertEquals(null, h.vm.player.ui.value.current)
    }

    @Test fun miniPlayerCloseButtonStillWorks() {
        val h = playSong()
        rule.onNodeWithTag(UiTags.MINI_CLOSE).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.MINI_PLAYER) }
        assertEquals(null, h.vm.player.ui.value.current)
    }

    @Test fun fullPlayerCoverSwipesSongsAndDownCollapsesButTheSeekSliderKeepsItsDrag() {
        val h = playSong()
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performClick()
        rule.waitForTag(UiTags.FULL_PLAYER)
        val first = currentId(h)
        rule.onNodeWithTag("cover-swipe").performTouchInput { swipeLeft() }
        rule.waitUntil(5_000) { currentId(h) != first }
        rule.onNodeWithTag("cover-swipe").performTouchInput { swipeRight() }
        rule.waitUntil(5_000) { currentId(h) == first }
        // Dragging the seek slider sideways must not skip the song.
        // (Not all the way to the end: the simulated player moves on by itself when a song finishes, which would look like a skip.)
        rule.onNodeWithTag(UiTags.FULL_SEEK).performTouchInput { swipeRight(startX = width * 0.1f, endX = width * 0.6f) }
        rule.waitForIdle()
        assertEquals(first, currentId(h))
        assertTrue("the drag seeked", h.vm.player.position.value.positionMs > 50_000)
        rule.onNodeWithTag("cover-swipe").performTouchInput { swipeDown() }
        rule.waitUntil(5_000) { !rule.exists(UiTags.FULL_PLAYER) }
        assertFalse(h.vm.fullPlayer)
        // The bar stays: collapsing does not stop the music.
        rule.onNodeWithTag(UiTags.MINI_PLAYER).assertIsDisplayed()
    }

    private fun index(h: Harness) = h.vm.player.ui.value.index

    @Test fun manySwipesInARowEachSkipAndRightGoesStraightToThePreviousSong() {
        val h = playSong()
        rule.runOnIdle { h.vm.player.skipTo(0) }
        rule.waitUntil(5_000) { index(h) == 0 }
        val size = h.vm.player.ui.value.queue.size
        assertTrue("the music space queues several songs", size >= 4)
        // Quick swipes, each started while the previous one may still be animating: every one counts.
        repeat(3) { n ->
            rule.onNodeWithTag(UiTags.MINI_PLAYER).performTouchInput { swipeLeft() }
            rule.waitUntil(5_000) { index(h) == n + 1 }
        }
        // Well into a song, a swipe right goes to the previous song instead of restarting this one (the button restarts).
        rule.runOnIdle { h.vm.player.seekTo(30_000) }
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performTouchInput { swipeRight() }
        rule.waitUntil(5_000) { index(h) == 2 }
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performTouchInput { swipeRight() }
        rule.waitUntil(5_000) { index(h) == 1 }
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performTouchInput { swipeRight() }
        rule.waitUntil(5_000) { index(h) == 0 }
        // At the first song a swipe right says so, and nothing changes.
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performTouchInput { swipeRight() }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("That's the first song in the queue")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, index(h))
        // At the last song a swipe left says so.
        rule.runOnIdle { h.vm.player.skipTo(size - 1) }
        rule.waitUntil(5_000) { index(h) == size - 1 }
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performTouchInput { swipeLeft() }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("That's the last song in the queue")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(size - 1, index(h))
    }

    @Test fun mediaCardTapShowsTheFullPlayerForASong() {
        val h = playSong()
        assertFalse(h.vm.fullPlayer)
        h.vm.openCurrentPlayer()
        rule.waitForTag(UiTags.FULL_PLAYER)
        assertTrue(h.vm.fullPlayer)
    }

    // ---- options on every library screen ----------------------------------------------------------------------

    @Test fun collectionsScreenHasNewCollectionAndAKindFilterAndSort() {
        val h = spaceHarness(LibraryRoute.Collections)
        rule.waitForTag(UiTags.collectionRow("khaleeji"))
        rule.onNodeWithContentDescription("New playlist").assertIsDisplayed()
        // Kind filter: Albums leaves only the album.
        rule.onNodeWithTag("collections-menu").performClick()
        rule.onNodeWithTag("collections-filter-ALBUMS").performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.collectionRow("khaleeji")) }
        rule.onNodeWithTag(UiTags.collectionRow("rumours")).assertIsDisplayed()
        // A kind with nothing says so instead of showing a blank page.
        rule.onNodeWithTag("collections-menu").performClick()
        rule.onNodeWithTag("collections-filter-CHANNELS").performClick()
        rule.waitForTag("collections-filtered-empty")
        rule.onNodeWithTag("collections-menu").performClick()
        rule.onNodeWithTag("collections-filter-ALL").performClick()
        rule.onNodeWithTag("collections-menu").performClick()
        rule.onNodeWithTag("collections-sort-NAME").performClick()
        rule.waitForTag(UiTags.collectionRow("khaleeji"))
        // New playlist opens the name dialog.
        rule.onNodeWithTag("collections-new").performClick()
        rule.waitForTag("name-field")
        rule.onNodeWithTag("name-field").performTextInput("Road music")
        rule.onNodeWithTag("name-confirm").performClick()
        rule.waitUntil(5_000) { h.engine.fakeLibrary.library.value.collections.any { it.name == "Road music" } }
    }

    @Test fun allSpacesOffersNewSpaceAndEdit() {
        val h = spaceHarness(LibraryRoute.AllSpaces)
        rule.waitForTag("all-spaces-new")
        rule.onNodeWithTag("all-spaces-edit").performClick()
        rule.waitUntil(5_000) { h.vm.libraryRoute == LibraryRoute.Manage }
        h.vm.openLibraryRoot(LibraryRoute.AllSpaces)
        rule.waitForTag("all-spaces-new")
        rule.onNodeWithTag("all-spaces-new").performClick()
        rule.waitForTag(UiTags.SPACE_EDITOR)
    }

    @Test fun albumMenuOffersPlayShuffleQueueCollectionAndSelect() {
        val h = spaceHarness(LibraryRoute.Home)
        val song = h.engine.fakeLibrary.allItems().first { it.album == "Agent 327" }
        h.vm.openLibraryDetail(LibraryRoute.Album(song.space, app.keepfin.PlaybackItems.albumKey(song).orEmpty()))
        rule.waitForTag("album-menu")
        rule.onNodeWithTag("album-menu").performClick()
        listOf("play", "shuffle", "queue", "collection", "select").forEach { rule.onNodeWithTag("album-menu-$it").assertExists() }
        rule.onNodeWithTag("album-menu-select").performClick()
        rule.waitUntil(5_000) { h.vm.librarySelecting }
    }

    @Test fun homeSeeAllListsOfferPlayAllShuffleAndSelect() {
        val h = spaceHarness(LibraryRoute.Home)
        h.vm.openLibraryDetail(LibraryRoute.Section(app.keepfin.ui.library.HomeSection.RECENTLY_ADDED))
        rule.waitForTag("section-menu")
        rule.onNodeWithTag("section-menu").performClick()
        listOf("play", "shuffle", "select").forEach { rule.onNodeWithTag("section-menu-$it").assertExists() }
        assertFalse(rule.exists("section-menu-queue"))
    }
}
