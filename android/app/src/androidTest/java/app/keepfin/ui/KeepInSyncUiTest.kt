package app.keepfin.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.JobStatus
import app.keepfin.SyncRequest
import app.keepfin.ui.library.LibraryRoute
import app.keepfin.ui.preview.Fixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in following: Keep in sync on the review, visible check results, the progress strip, and Add from a link. */
@RunWith(AndroidJUnit4::class)
class KeepInSyncUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private fun openReview(h: Harness, link: String = "https://www.youtube.com/playlist?list=PLblender") {
        h.show(rule)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput(link)
        rule.waitForTag(UiTags.COLLECTION)
        rule.waitUntil(10_000) { h.vm.collection?.entries?.size == 60 && h.vm.collection?.discovering == false }
        // A playlist opens with every entry selected.
        rule.waitUntil(5_000) { h.vm.collection?.selectedCount == 60 }
    }

    private fun library(open: LibraryRoute): Harness {
        val h = Harness("spaces-v2")
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(LibraryRoute.Space("workout"))
        h.vm.openLibraryDetail(open)
        h.show(rule)
        return h
    }

    // ---- 0: previous button ---------------------------------------------------------------------------------

    @Test fun miniPlayerHasAPreviousButtonBeforePlayPause() {
        val h = Harness("library")
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.onNodeWithTag(UiTags.spaceCard("music")).performClick()
        val song = "content://media/external/audio/media/102"
        rule.waitForTag(UiTags.libItem(song))
        rule.onNodeWithTag(UiTags.libItem(song)).performClick()
        rule.waitForTag(UiTags.MINI_PLAYER)
        rule.onNodeWithTag(UiTags.MINI_PREV).assertIsDisplayed()
        rule.onNodeWithContentDescription("Previous song").assertExists()
        rule.onNodeWithTag(UiTags.MINI_PLAY).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.MINI_NEXT).assertIsDisplayed()
        // Disabled exactly when there is no previous song, like Next.
        if (h.vm.player.ui.value.hasPrevious) rule.onNodeWithTag(UiTags.MINI_PREV).assertIsEnabled() else rule.onNodeWithTag(UiTags.MINI_PREV).assertIsNotEnabled()
        // The title still shows next to the five controls.
        rule.onNodeWithTag(UiTags.MINI_PLAYER).assertIsDisplayed()
    }

    // ---- 2: Keep in sync on the review -----------------------------------------------------------------------

    @Test fun keepInSyncIsOffByDefaultAndSendsNothing() {
        val h = Harness("empty")
        openReview(h)
        rule.onNodeWithTag(UiTags.SYNC_ROW).assertExists()
        rule.onNodeWithText("Keep in sync").assertExists()
        rule.onNodeWithText("New ones download by themselves").assertExists()
        assertFalse("The options stay hidden while it is off", rule.exists(UiTags.SYNC_OPTIONS))
        // it is on the main tab and never in Extras
        rule.openExtras()
        assertFalse(rule.exists(UiTags.SYNC_ROW))
        rule.closeExtras()
        rule.onNodeWithTag(UiTags.COLLECTION_DOWNLOAD).performClick()
        rule.waitUntil(5_000) { h.engine.enqueuedSyncs.size == 1 }
        assertNull(h.engine.enqueuedSyncs.single())
    }

    @Test fun turningKeepInSyncOnRevealsTheOptionsAndSendsThem() {
        val h = Harness("empty")
        openReview(h)
        rule.onNodeWithTag(UiTags.SYNC_SWITCH).performClick()
        rule.waitForTag(UiTags.SYNC_OPTIONS)
        // Defaults: every 6 hours, only on Wi-Fi.
        rule.onNodeWithText("Every hour").assertExists()
        rule.onNodeWithText("12 hours").assertExists()
        rule.onNodeWithText("Day").assertExists()
        rule.onNodeWithText("Week").assertExists()
        assertEquals(6, h.vm.collection!!.syncHours)
        assertTrue(h.vm.collection!!.syncWifiOnly)
        rule.onNodeWithTag(UiTags.syncEvery(24)).performScrollTo().performClick()
        rule.onNodeWithTag(UiTags.SYNC_WIFI).performScrollTo().performClick()
        rule.onNodeWithTag(UiTags.COLLECTION_DOWNLOAD).performClick()
        rule.waitUntil(5_000) { h.engine.enqueuedSyncs.size == 1 }
        assertEquals(SyncRequest(intervalHours = 24, wifiOnly = false), h.engine.enqueuedSyncs.single())
    }

    @Test fun keepInSyncIsNotOfferedForAlbumsOrMixes() {
        val album = Harness("empty")
        openReview(album, "https://music.youtube.com/playlist?list=OLAKblender")
        assertFalse(rule.exists(UiTags.SYNC_ROW))
        rule.onNodeWithTag(UiTags.COLLECTION_DOWNLOAD).assertExists()
    }

    // ---- 3: Check now is visible ------------------------------------------------------------------------------

    @Test fun checkNowSaysWhatItFoundAndShowOpensTheCollection() {
        val h = library(LibraryRoute.Collection("workout", "gym"))
        h.engine.fakeLibrary.nextCheckFound = 3
        rule.waitForTag(UiTags.COLLECTION_DETAIL)
        rule.onNodeWithTag(UiTags.LIB_LIST).performScrollToNodeTag(UiTags.FOLLOW_CARD)
        rule.onNodeWithTag(UiTags.FOLLOW_CHECK).performClick()
        rule.waitUntil(8_000) { rule.hasText("Found 3 new songs in Gym Mix · Downloading") }
        rule.onNodeWithText("Show").assertIsDisplayed()
    }

    @Test fun checkNowWithNothingNewSaysSo() {
        val h = library(LibraryRoute.Collection("workout", "gym"))
        rule.waitForTag(UiTags.COLLECTION_DETAIL)
        rule.onNodeWithTag(UiTags.LIB_LIST).performScrollToNodeTag(UiTags.FOLLOW_CARD)
        rule.onNodeWithTag(UiTags.FOLLOW_CHECK).performClick()
        rule.waitUntil(8_000) { rule.hasText("No new songs in Gym Mix") }
        assertTrue(h.engine.fakeLibrary.calls.contains("collectionCheck:gym"))
    }

    @Test fun aHeldBackCheckShowsTheReviewPrompt() {
        val h = library(LibraryRoute.Collection("workout", "gym"))
        h.engine.fakeLibrary.nextCheckHeld = 28
        rule.waitForTag(UiTags.COLLECTION_DETAIL)
        rule.onNodeWithTag(UiTags.LIB_LIST).performScrollToNodeTag(UiTags.FOLLOW_CARD)
        rule.onNodeWithTag(UiTags.FOLLOW_CHECK).performClick()
        rule.waitUntil(8_000) { rule.hasText("28 new in Gym Mix · Review") }
        rule.onNodeWithText("Review").assertExists()
    }

    @Test fun theProgressStripShowsWhileTheCollectionsDownloadsRunAndGoesWhenDone() {
        val h = library(LibraryRoute.Collection("workout", "gym"))
        rule.waitForTag(UiTags.COLLECTION_DETAIL)
        assertFalse("No strip without running downloads", rule.exists(UiTags.FOLLOW_PROGRESS))
        fun job(id: String, status: JobStatus, percent: Float) = Fixtures.job(id, "New song $id", null, status, percent = percent, ageMinutes = 1).copy(collectionId = "gym")
        h.engine.setJobs(listOf(job("n1", JobStatus.COMPLETED, 100f), job("n2", JobStatus.DOWNLOADING, 50f), job("n3", JobStatus.QUEUED, 0f)))
        rule.waitForTag(UiTags.FOLLOW_PROGRESS)
        assertTrue(rule.hasText("Downloading 1 of 3 new"))
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(UiTags.FOLLOW_ACTIVE_ROW).fetchSemanticsNodes().size == 2 }
        assertTrue("The New section counts the running ones", rule.hasText("New · 5"))
        assertTrue(rule.hasText("Downloading 50%"))
        assertTrue(rule.hasText("Waiting to download"))
        h.engine.setJobs(listOf(job("n1", JobStatus.COMPLETED, 100f), job("n2", JobStatus.COMPLETED, 100f), job("n3", JobStatus.COMPLETED, 100f)))
        rule.waitUntil(5_000) { !rule.exists(UiTags.FOLLOW_PROGRESS) }
    }

    // ---- 4: Add a link to a space -------------------------------------------------------------------------------

    @Test fun addFromALinkReviewsItWithSaveToOnThatSpace() {
        val h = Harness("spaces-v2")
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(LibraryRoute.Space("workout"))
        h.show(rule)
        rule.waitForTag(UiTags.LIB_OVERFLOW)
        rule.onNodeWithTag(UiTags.LIB_OVERFLOW).performClick()
        rule.onNodeWithTag(UiTags.LIB_ADD_FILES).performClick()
        rule.waitForTag(UiTags.ADD_SHEET)
        rule.onNodeWithTag(UiTags.ADD_DEVICE).assertExists()
        rule.onNodeWithTag(UiTags.ADD_LINK).performClick()
        rule.waitForTag(UiTags.LINK_SHEET)
        rule.onNodeWithTag(UiTags.LINK_PASTE).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.LINK_GO).assertIsNotEnabled()
        rule.onNodeWithTag(UiTags.ADD_LINK_FIELD).performTextInput("https://www.youtube.com/playlist?list=PLblender")
        rule.onNodeWithTag(UiTags.LINK_GO).assertIsEnabled().performClick()
        rule.waitForTag(UiTags.COLLECTION)
        rule.waitUntil(10_000) { h.vm.collection?.discovering == false && (h.vm.collection?.entries?.size ?: 0) > 0 }
        assertEquals("workout", h.vm.collection!!.space)
        assertEquals("workout", h.vm.collections.collectionSpace(h.vm.collection!!))
        assertTrue("Save to shows the space", rule.hasText("Workout"))
        rule.onNodeWithTag(UiTags.SYNC_ROW).assertExists()
        assertEquals(0, h.engine.enqueuedSpaces.size)
    }

    @Test fun aLinkThatIsNotALinkIsRefusedInline() {
        val h = Harness("spaces-v2")
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(LibraryRoute.Space("workout"))
        h.show(rule)
        rule.waitForTag(UiTags.LIB_OVERFLOW)
        rule.onNodeWithTag(UiTags.LIB_OVERFLOW).performClick()
        rule.onNodeWithTag(UiTags.LIB_ADD_FILES).performClick()
        rule.waitForTag(UiTags.ADD_SHEET)
        rule.onNodeWithTag(UiTags.ADD_LINK).performClick()
        rule.waitForTag(UiTags.LINK_SHEET)
        rule.onNodeWithTag(UiTags.ADD_LINK_FIELD).performTextInput("not a link")
        rule.onNodeWithTag(UiTags.LINK_GO).performClick()
        rule.waitUntil(5_000) { rule.hasText("Paste a full link that starts with https://") }
        assertTrue(rule.exists(UiTags.LINK_SHEET))
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.performScrollToNodeTag(tag: String) = performScrollToNode(hasTestTag(tag))
