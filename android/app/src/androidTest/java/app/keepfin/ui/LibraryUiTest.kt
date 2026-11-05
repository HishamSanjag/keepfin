package app.keepfin.ui

import app.keepfin.allItems
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.up
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.ExistingMatch
import app.keepfin.JobStatus
import app.keepfin.ui.preview.Fixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Library, Save to, Settings, onboarding and player-state flows against FakeEngine / FakeLibrary (fixtures cannot play audio). */
@RunWith(AndroidJUnit4::class)
class LibraryUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private fun reveal(tag: String) {
        rule.waitForIdle()
        // the review is a sheet: its own scrolling area brings the control into view above the Download bar
        rule.onNodeWithTag(tag).performScrollTo()
        rule.waitForIdle()
    }

    private val song = "content://media/external/audio/media/102"

    @Test fun libraryOpensWithoutCrashingAfterEverySongWasDeleted() {
        // Regression: deleting every file left Library Home with its empty state inside a lazy list (unbounded height), which crashed on open.
        val h = Harness("library")
        val lib = h.engine.fakeLibrary
        lib.deleteItems(lib.allItems().map { it.id })
        h.show(rule)
        rule.onNodeWithTag(UiTags.nav(Destination.LIBRARY)).performClick()
        rule.waitForTag(UiTags.LIB_EMPTY)
        rule.onNodeWithText("Your library is empty").assertExists()
        // Searching lives in the Search tab only: the Library home has no search icon or field.
        assertFalse(rule.exists(UiTags.LIB_SEARCH_TOGGLE))
        assertFalse(rule.exists(UiTags.LIB_SEARCH_FIELD))
    }

    @Test fun downloadsFiltersSplitActiveFinishedAndFailed() {
        val h = Harness("default")
        h.show(rule)
        rule.waitForTag(UiTags.DOWNLOADS_FILTERS)
        rule.onNodeWithTag(UiTags.downloadsFilter("ACTIVE")).performClick()
        rule.waitForTag(UiTags.job("j-dl"))
        assertFalse(rule.exists(UiTags.job("j-done1")))
        rule.onNodeWithTag(UiTags.downloadsFilter("FINISHED")).performClick()
        rule.waitForTag(UiTags.job("j-done1"))
        assertFalse(rule.exists(UiTags.job("j-dl")))
        rule.onNodeWithTag(UiTags.downloadsFilter("FAILED")).performClick()
        rule.waitForTag(UiTags.job("j-failed"))
        assertFalse(rule.exists(UiTags.job("j-done1")))
    }

    @Test fun bottomNavigationStaysThreeAndLibraryHomeIsTheSpacesHub() {
        val h = Harness("library-spaces")
        h.show(rule)
        rule.onNodeWithTag(UiTags.nav(Destination.LIBRARY)).performClick()
        rule.waitForTag(UiTags.LIB_SPACES_GRID)
        // Music, Videos, Downloads, then the spaces you added; no horizontal chip row any more.
        for (id in listOf("music", "videos", "downloads", "watch-later")) rule.onNodeWithTag(UiTags.spaceCard(id)).assertExists()
        // "New space" lives in edit mode only.
        assertFalse(rule.exists(UiTags.LIB_SPACE_NEW))
        rule.onNodeWithTag(UiTags.LIB_SPACES_EDIT).assertIsDisplayed()
        assertFalse(rule.exists("library-chips"))
        rule.onNodeWithText("Continue watching").assertExists()
        rule.onNodeWithTag(UiTags.nav(Destination.DOWNLOADS)).assertExists()
        rule.onNodeWithTag(UiTags.nav(Destination.SETTINGS)).assertExists()
    }

    @Test fun spacesGridOpensASpaceAndLongPressOffersEditAddFilesAndRemoveForUserSpaces() {
        val h = Harness("library-spaces")
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.waitForTag(UiTags.spaceCard("music"))
        rule.onNodeWithTag(UiTags.spaceCard("music")).performTouchInput { longClick() }
        rule.waitForTag(UiTags.LIB_SPACE_MENU_EDIT)
        rule.onNodeWithTag(UiTags.LIB_SPACE_MENU_ADD).assertExists()
        assertFalse("Built-in spaces cannot be removed", rule.exists(UiTags.LIB_SPACE_MENU_REMOVE))
        rule.onNodeWithTag(UiTags.LIB_SPACE_MENU_EDIT).performClick()
        rule.waitForTag(UiTags.SPACE_EDITOR)
        rule.onNodeWithText("Cancel").performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.SPACE_EDITOR) }
        rule.onNodeWithTag(UiTags.spaceCard("watch-later")).performTouchInput { longClick() }
        rule.waitForTag(UiTags.LIB_SPACE_MENU_REMOVE)
        rule.onNodeWithTag(UiTags.LIB_SPACE_MENU_REMOVE).performClick()
        rule.onNodeWithTag("space-remove-confirm").performClick()
        rule.waitUntil(5_000) { h.engine.fakeLibrary.calls.contains("deleteSpace:watch-later") }
        // Tapping a card opens the space page with a back arrow.
        rule.onNodeWithTag(UiTags.spaceCard("music")).performClick()
        rule.waitForTag(UiTags.libTab("songs"))
        rule.onNodeWithTag("library-back").performClick()
        rule.waitForTag(UiTags.LIB_SPACES_GRID)
    }

    @Test fun selectAllCoversExactlyTheVisibleItemsAndTogglesToSelectNone() {
        val h = Harness("library")
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.waitForTag(UiTags.spaceCard("music"))
        rule.onNodeWithTag(UiTags.spaceCard("music")).performClick()
        rule.waitForTag(UiTags.libItem(song))
        rule.onNodeWithTag(UiTags.libItem(song)).performTouchInput { longClick() }
        rule.waitForTag(UiTags.LIB_SELECT_ALL)
        rule.onNodeWithText("1 selected").assertExists()
        rule.onNodeWithTag(UiTags.LIB_SELECTION_ACTIONS).assertExists()
        rule.onNodeWithText("Move to…").assertExists(); rule.onNodeWithText("Playlist").assertExists(); rule.onNodeWithText("Play").assertExists(); rule.onNodeWithText("Delete…").assertExists()
        // The bottom navigation gives way to the action bar.
        assertFalse(rule.exists(UiTags.nav(Destination.LIBRARY)))
        rule.onNodeWithTag(UiTags.LIB_SELECT_ALL).assertTextEquals("Select all")
        rule.onNodeWithTag(UiTags.LIB_SELECT_ALL).performClick()
        rule.onNodeWithText("8 selected").assertExists()
        rule.onNodeWithTag(UiTags.LIB_SELECT_ALL).assertTextEquals("Select none")
        rule.onNodeWithTag(UiTags.LIB_SELECT_ALL).performClick()
        rule.onNodeWithText("Select items").assertExists()
        rule.onNodeWithTag(UiTags.LIB_SELECT_CLOSE).performClick()
        rule.waitForTag(UiTags.nav(Destination.LIBRARY))
        // A search narrows what Select all covers.
        rule.onNodeWithTag(UiTags.LIB_SEARCH_TOGGLE).performClick()
        rule.onNodeWithTag(UiTags.LIB_SEARCH_FIELD).performTextInput("Spring")
        rule.waitForTag(UiTags.libItem("content://media/external/audio/media/107"))
        rule.onNodeWithTag(UiTags.libItem("content://media/external/audio/media/107")).performTouchInput { longClick() }
        // The one visible item is already selected, so the button offers Select none.
        rule.onNodeWithTag(UiTags.LIB_SELECT_ALL).assertTextEquals("Select none")
        rule.onNodeWithText("1 selected").assertExists()
    }

    @Test fun musicSpaceHasFilterChipsAndPlayAllAndShuffle() {
        val h = Harness("library")
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(app.keepfin.ui.library.LibraryRoute.Space("music"))
        h.show(rule)
        for (tab in listOf("songs", "collections", "albums", "artists")) rule.onNodeWithTag(UiTags.libTab(tab)).assertExists()
        rule.onNodeWithTag(UiTags.LIB_PLAY_ALL).assertExists()
        rule.onNodeWithTag(UiTags.LIB_SHUFFLE).assertExists()
        rule.onNodeWithText("8 songs", substring = true).assertExists()
        rule.onNodeWithTag(UiTags.LIB_OVERFLOW).performClick()
        rule.onNodeWithTag(UiTags.LIB_SPACE_MENU_EDIT).assertExists()
        rule.onNodeWithTag(UiTags.LIB_ADD_FILES).assertExists()
        rule.onNodeWithTag(UiTags.LIB_DENSITY_COMFORTABLE).performClick()
        rule.waitUntil(5_000) { !h.prefs.libraryCompact("music") }
        assertTrue("Compact is the default", h.prefs.libraryCompact("videos"))
    }

    @Test fun videoSpaceShowsOnlyThePresentKindsAsFilterChips() {
        val h = Harness("library")
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(app.keepfin.ui.library.LibraryRoute.Space("downloads"))
        h.show(rule)
        rule.waitForTag("kind-filter-all")
        rule.onNodeWithTag("kind-filter-video").assertExists()
        rule.onNodeWithTag("kind-filter-audio").assertExists()
        assertFalse(rule.exists("kind-filter-image"))
    }

    @Test fun musicSpaceAlbumsAndAlbumDetail() {
        val h = Harness("library")
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.waitForTag(UiTags.spaceCard("music"))
        rule.onNodeWithTag(UiTags.spaceCard("music")).performClick()
        rule.waitForTag(UiTags.libTab("songs"))
        rule.onNodeWithText("Sintel Theme").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.libTab("albums")).performClick()
        rule.waitForTag(UiTags.album("blender studio|open movies ost"))
        rule.onNodeWithTag(UiTags.album("blender studio|open movies ost")).performClick()
        rule.waitForTag(UiTags.LIB_PLAY_ALL)
        rule.onNodeWithText("Tears of Steel Suite").assertIsDisplayed()
        rule.onNodeWithText("4 songs", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("library-back").performClick()
        rule.waitForTag(UiTags.libTab("albums"))
    }

    @Test fun collectionsTabListsCollectionsAndQuickLists() {
        val h = Harness("library")
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.onNodeWithTag(UiTags.spaceCard("music")).performClick()
        rule.onNodeWithTag(UiTags.libTab("collections")).performClick()
        rule.waitForTag(UiTags.collectionRow("pl-1"))
        rule.onNodeWithText("Focus").assertExists()
        rule.onNodeWithTag(UiTags.LIB_LIST).performScrollToNode(hasTestTag("smart-FAVORITES"))
        rule.onNodeWithTag("smart-FAVORITES").performClick()
        rule.waitForTag(UiTags.libItem("content://media/external/audio/media/101"))
    }

    @Test fun multiSelectDeleteAsksForConfirmation() {
        val h = Harness("library")
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.onNodeWithTag(UiTags.spaceCard("music")).performClick()
        rule.waitForTag(UiTags.libItem(song))
        rule.onNodeWithTag(UiTags.libItem(song)).performTouchInput { longClick() }
        rule.waitForTag(UiTags.LIB_SELECTION_BAR)
        rule.onNodeWithTag(UiTags.LIB_SELECTION_DELETE).performClick()
        rule.waitForTag(UiTags.LIB_CONFIRM_DELETE)
        assertTrue(h.engine.fakeLibrary.calls.none { it.startsWith("delete:") })
        rule.onNodeWithTag("delete-confirm").performClick()
        rule.waitUntil(5_000) { h.engine.fakeLibrary.calls.any { it == "delete:$song" } }
    }

    @Test fun saveToChipShowsDestinationAndExistingBadge() {
        val h = Harness("empty")
        h.engine.fakeLibrary.existingTitles = setOf("Sintel Theme")
        val link = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"
        h.engine.inspectResults[link] = app.keepfin.InspectResult.Media(Fixtures.spotifyTrack(link).copy(title = "Sintel Theme"))
        h.show(rule)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput(link)
        rule.waitForTag(UiTags.REVIEW)
        reveal(UiTags.CAPTURE_EXISTING)
        rule.onNodeWithText("Already in Music").assertExists()
        assertTrue(rule.onAllNodesWithTag(UiTags.SAVE_TO, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.hasText("Save to: Music"))
        assertTrue(rule.hasText("Download anyway"))
        rule.onAllNodesWithTag(UiTags.SAVE_TO).onFirst().performClick()
        rule.onNodeWithTag(UiTags.saveToOption("downloads")).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.CAPTURE_EXISTING) }
        assertTrue(rule.hasText("Save to: Downloads"))
        rule.onNodeWithTag(UiTags.DOWNLOAD).performClick()
        rule.waitUntil(5_000) { h.engine.enqueued.size == 1 }
        assertEquals("downloads", h.engine.enqueuedSpaces.single())
    }

    @Test fun saveToMenuEndsWithChangeDefaults() {
        val h = Harness("empty")
        h.show(rule)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput("https://www.youtube.com/watch?v=aqz-KE-bpKQ")
        rule.waitForTag(UiTags.REVIEW)
        rule.onAllNodesWithTag(UiTags.SAVE_TO).onFirst().performClick()
        rule.onNodeWithTag(UiTags.SAVE_TO_CHANGE_DEFAULTS).performClick()
        rule.waitForTag(UiTags.SETTINGS_LIST)
        rule.waitForTag(UiTags.SETTING_MODE_FULL)
    }

    @Test fun collectionDeselectsSongsAlreadyInTheSpace() {
        val h = Harness("empty")
        h.engine.fakeLibrary.existingTitles = setOf("Big Buck Bunny")
        h.show(rule)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput("https://music.youtube.com/playlist?list=PLblender")
        rule.waitForTag(UiTags.COLLECTION)
        val first = "https://www.youtube.com/watch?v=aqz-KE-bpKQ&index=1"
        rule.waitForTag(UiTags.entryExisting(first))
        rule.waitForTag(UiTags.COLLECTION_EXISTING_NOTE)
        // The playlist opens selected, minus the songs already in Music.
        rule.waitUntil(5_000) { h.vm.collection!!.selected[first] != true && h.vm.collection!!.selectedCount > 0 }
        assertFalse(h.vm.collection!!.selected[first] == true)
        rule.onNodeWithTag(UiTags.COLLECTION_SELECT_ANYWAY).performClick()
        rule.waitUntil(5_000) { h.vm.collection!!.selected[first] == true }
    }

    @Test fun existingJobOffersPlayAndDownloadAnyway() {
        val h = Harness("empty")
        val job = Fixtures.job("j-ex", "Sintel Theme", null, JobStatus.COMPLETED, kind = app.keepfin.MediaKind.AUDIO).copy(space = "music", existing = ExistingMatch(null, "content://x", "music", "metadata"))
        h.engine.setJobs(listOf(job))
        h.show(rule)
        rule.waitForTag(UiTags.jobExisting("j-ex"))
        rule.onNodeWithText("Already in Music").assertExists()
        rule.onNodeWithTag(UiTags.jobAnyway("j-ex")).performClick()
        rule.waitUntil(5_000) { h.engine.actions.contains("anyway:j-ex") }
    }

    @Test fun settingsLibraryGroupAndLibraryOffMode() {
        val h = Harness("library")
        h.openSettings(app.keepfin.ui.settings.SettingsCategory.LIBRARY)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_LIST)
        // Keepfin mode is set only here, at the top of Library & playback
        rule.onNodeWithTag(UiTags.SETTINGS_LIST).performScrollToNode(hasTestTag(UiTags.SETTING_MODE_FULL))
        rule.onNodeWithText("Keepfin Complete").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.SETTINGS_LIST).performScrollToNode(hasTestTag(UiTags.setting("player-other")))
        rule.onNodeWithTag(UiTags.setting("player-other")).performClick()
        rule.waitUntil(5_000) { !h.engine.settings.value.library.builtInPlayer }
        rule.onNodeWithTag(UiTags.SETTINGS_LIST).performScrollToNode(hasTestTag(UiTags.spaceRow("music")))
        rule.onNodeWithTag(UiTags.spaceRow("music")).performClick()
        rule.waitForTag(UiTags.SPACE_EDITOR)
        rule.onNodeWithTag(UiTags.SPACE_MORE).performScrollTo()
        rule.onNodeWithTag(UiTags.SPACE_MORE).performClick()
        rule.onNodeWithText("Skip songs already in this space").assertExists()
        // Library off: the Library tab is the history view again, with no chips and no Save to.
        rule.runOnIdle { h.libraryOff(); h.vm.navigate(Destination.LIBRARY) }
        rule.waitForTag(UiTags.LIBRARY_LIST)
        assertFalse(rule.exists(UiTags.LIB_SPACES_GRID))
    }

    @Test fun miniPlayerExpandsToFullPlayerState() {
        val h = Harness("library")
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.onNodeWithTag(UiTags.spaceCard("music")).performClick()
        rule.waitForTag(UiTags.libItem(song))
        rule.onNodeWithTag(UiTags.libItem(song)).performClick()
        rule.waitForTag(UiTags.MINI_PLAYER)
        rule.onNodeWithTag(UiTags.MINI_PLAY).performClick()
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performClick()
        rule.waitForTag(UiTags.FULL_PLAYER)
        rule.onNodeWithTag(UiTags.FULL_QUEUE_TOGGLE).performClick()
        rule.waitForTag("queue-list")
        rule.onNodeWithTag(UiTags.FULL_COLLAPSE).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.FULL_PLAYER) }
        rule.onNodeWithTag(UiTags.MINI_PLAYER).assertIsDisplayed()
    }
}
