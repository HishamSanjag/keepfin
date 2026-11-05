package app.keepfin.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.ui.preview.Fixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a collection opens with: albums and playlists start fully selected (including entries that arrive later) until
 * the user changes the selection; channels and radio mixes start empty.
 */
@RunWith(AndroidJUnit4::class)
class CollectionSelectUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private fun open(h: Harness, link: String) {
        h.show(rule)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput(link)
        rule.waitForTag(UiTags.COLLECTION)
    }

    /** The fake engine only recognises playlist-style links when inspecting, so channels and mixes open the review directly. */
    private fun openDirect(h: Harness, link: String) {
        h.show(rule)
        rule.runOnIdle { h.vm.collections.openPlaylist(link) }
        rule.waitForTag(UiTags.COLLECTION)
    }

    private fun settled(h: Harness, size: Int) =
        rule.waitUntil(15_000) { h.vm.collection?.entries?.size == size && h.vm.collection?.discovering == false }

    @Test fun aPlaylistOpensWithEverythingSelected() {
        val h = Harness("empty")
        open(h, "https://www.youtube.com/playlist?list=PLblender")
        settled(h, 60)
        rule.waitUntil(5_000) { h.vm.collection?.selectedCount == 60 }
        rule.waitForTag(UiTags.COLLECTION_SELECT_NONE)
    }

    @Test fun anUnavailableEntrySaysWhyAndIsNeverSelected() {
        val h = Harness("empty")
        open(h, "https://www.youtube.com/playlist?list=PLgone")
        settled(h, 60)
        rule.waitUntil(5_000) { h.vm.collection?.selectedCount == 59 }
        val gone = h.vm.collection!!.entries.first { it.position == 3 }
        rule.onNodeWithTag(UiTags.entry(gone.url)).assertExists()
        rule.onNodeWithText(app.keepfin.core.formats.Unavailable.LABEL).assertExists()
        rule.onNodeWithTag(UiTags.entry(gone.url)).performClick()
        assertEquals("tapping it does nothing", 59, h.vm.collection!!.selectedCount)
        rule.runOnIdle { h.vm.collections.selectAll(true) }
        assertEquals("Select all leaves it out", 59, h.vm.collection!!.selectedCount)
        // all the usable entries are selected, so the bar offers Select none
        rule.waitForTag(UiTags.COLLECTION_SELECT_NONE)
    }

    @Test fun anAlbumOpensWithEverythingSelected() {
        val h = Harness("empty")
        open(h, "https://music.youtube.com/playlist?list=OLAKblender")
        settled(h, 60)
        rule.waitUntil(5_000) { h.vm.collection?.selectedCount == 60 }
    }

    @Test fun aChannelOpensWithNothingSelected() {
        val h = Harness("empty")
        openDirect(h, "https://www.youtube.com/@blender/videos")
        settled(h, 60)
        assertEquals(0, h.vm.collection!!.selectedCount)
        rule.waitForTag(UiTags.COLLECTION_SELECT_ALL)
        assertFalse(rule.exists(UiTags.COLLECTION_SELECT_NONE))
    }

    @Test fun aRadioMixOpensWithNothingSelected() {
        val h = Harness("empty")
        openDirect(h, "https://www.youtube.com/watch?v=aqz-KE-bpKQ&list=RDaqz-KE-bpKQ")
        settled(h, 60)
        assertEquals(0, h.vm.collection!!.selectedCount)
        rule.waitForTag(UiTags.COLLECTION_SELECT_ALL)
    }

    @Test fun laterPagesAreSelectedToo_untilTheUserChangesTheSelection() {
        val h = Harness("empty")
        h.engine.collectionSize = 450
        h.engine.discoverEntryDelayMs = 0
        open(h, "https://www.youtube.com/playlist?list=PLblender")
        settled(h, StudioViewModel.COLLECTION_PAGE)
        rule.waitUntil(5_000) { h.vm.collection?.selectedCount == StudioViewModel.COLLECTION_PAGE }
        h.vm.collections.loadMore()
        settled(h, StudioViewModel.COLLECTION_PAGE * 2)
        assertEquals("the second page is selected as it arrives", StudioViewModel.COLLECTION_PAGE * 2, h.vm.collection!!.selectedCount)
    }

    @Test fun untickingOneEntryStopsAutoSelectingLaterPages() {
        val h = Harness("empty")
        h.engine.collectionSize = 450
        h.engine.discoverEntryDelayMs = 0
        open(h, "https://www.youtube.com/playlist?list=PLblender")
        settled(h, StudioViewModel.COLLECTION_PAGE)
        rule.waitUntil(5_000) { h.vm.collection?.selectedCount == StudioViewModel.COLLECTION_PAGE }
        val first = Fixtures.entry(1).url
        rule.waitForTag(UiTags.entry(first))
        rule.onNodeWithTag(UiTags.entry(first)).performClick()
        rule.waitUntil(5_000) { h.vm.collection?.selectedCount == StudioViewModel.COLLECTION_PAGE - 1 }
        h.vm.collections.loadMore()
        settled(h, StudioViewModel.COLLECTION_PAGE * 2)
        assertEquals("page two stays unticked", StudioViewModel.COLLECTION_PAGE - 1, h.vm.collection!!.selectedCount)
        assertFalse(h.vm.collection!!.selected[first] == true)
    }

    @Test fun songsAlreadyInTheSpaceStayUnticked() {
        val h = Harness("empty")
        h.engine.fakeLibrary.existingTitles = setOf("Big Buck Bunny")
        open(h, "https://music.youtube.com/playlist?list=PLblender")
        settled(h, 60)
        val review = h.vm.collection!!
        rule.waitUntil(5_000) { review.existing.isNotEmpty() && review.existing.none { review.selected[it] == true } }
        assertTrue(review.selectedCount > 0)
        assertEquals(review.entries.size - review.existing.size, review.selectedCount)
    }
}
