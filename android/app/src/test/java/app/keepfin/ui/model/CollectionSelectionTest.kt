package app.keepfin.ui.model

import android.app.Application
import app.keepfin.EngineApi
import app.keepfin.LibraryApi
import app.keepfin.PlaylistEntry
import app.keepfin.ui.CollectionReview
import app.keepfin.ui.UiPreferences
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The selection of a collection under review: the flow reads and changes the review its host holds. */
class CollectionSelectionTest {
    private fun entry(id: String, unavailable: String? = null) =
        PlaylistEntry(url = "https://example.com/$id", title = id, thumbnail = null, uploader = null, duration = null, position = 1, unavailable = unavailable)

    private fun review(vararg entries: PlaylistEntry) = CollectionReview("https://music.youtube.com/playlist?list=x", "session", "Mix").apply { this.entries.addAll(entries) }

    @Test fun selectAllSelectsEveryAvailableEntryAndStopsAutoSelecting() {
        val review = review(entry("a"), entry("b"), entry("gone", unavailable = "Private video"))
        CollectionFlow(FakeHost(review)).selectAll(true)
        assertEquals(setOf("https://example.com/a", "https://example.com/b"), review.selected.filterValues { it }.keys)
        assertFalse(review.autoSelect)
    }

    @Test fun selectAllFalseClearsTheSelection() {
        val review = review(entry("a"), entry("b"))
        val flow = CollectionFlow(FakeHost(review))
        flow.selectAll(true)
        flow.selectAll(false)
        assertEquals(0, review.selectedCount)
    }

    @Test fun toggleSelectsAndThenDeselectsAnEntry() {
        val a = entry("a")
        val review = review(a)
        val flow = CollectionFlow(FakeHost(review))
        flow.toggleEntry(a)
        assertTrue(review.selected[a.url] == true)
        flow.toggleEntry(a)
        assertEquals(0, review.selectedCount)
    }

    @Test fun anUnavailableEntryCannotBeToggledOn() {
        val gone = entry("gone", unavailable = "Deleted video")
        val review = review(gone)
        CollectionFlow(FakeHost(review)).toggleEntry(gone)
        assertEquals(0, review.selectedCount)
    }

    @Test fun selectNewLeavesOutWhatTheSpaceAlreadyHas() {
        val review = review(entry("a"), entry("b"), entry("c"))
        review.existing.add("https://example.com/b")
        CollectionFlow(FakeHost(review)).selectNew()
        assertEquals(setOf("https://example.com/a", "https://example.com/c"), review.selected.filterValues { it }.keys)
    }

    @Test fun withNoCollectionOpenNothingHappens() {
        val host = FakeHost(null)
        val flow = CollectionFlow(host)
        flow.selectAll(true)
        flow.toggleEntry(entry("a"))
        flow.selectNew()
        assertNull(host.collection)
    }

    /** Holds the review; the selection uses nothing else of the ViewModel, so the rest fails loudly if it is ever used. */
    private class FakeHost(override var collection: CollectionReview?) : CollectionFlowHost {
        private fun unused(): Nothing = error("the selection does not use this")
        override val scope: CoroutineScope get() = unused()
        override val engine: EngineApi get() = unused()
        override val lib: LibraryApi get() = unused()
        override val prefs: UiPreferences get() = unused()
        override val libraryEnabled: Boolean get() = unused()
        override var collectionOpen: Boolean
            get() = unused()
            set(_) = unused()
        override var linkSpace: String?
            get() = unused()
            set(_) = unused()
        override fun <T : Application> getApplication(): T = unused()
        override fun invalidate() = unused()
        override fun saidRemovedFromHistory(ids: List<String>) = unused()
        override fun say(text: String, actionLabel: String?, action: (() -> Unit)?) = unused()
        override fun sayWithTwo(text: String, actionLabel: String?, action: (() -> Unit)?, secondLabel: String, secondAction: () -> Unit) = unused()
    }
}
