package app.keepfin.ui

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import app.keepfin.ActivityKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** The Activity centre: bell with badge, the page, filters, actions into existing navigation, swipe to remove, and the "while you were away" notice. */
@RunWith(AndroidJUnit4::class)
class ActivityUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private fun unread(h: Harness) = h.engine.activity.entries.value.count { !it.read }

    @Test fun theBellShowsHowManyAreUnreadInDownloadsAndLibrary() {
        val h = Harness("activity")
        h.show(rule)
        rule.waitForTag(UiTags.ACTIVITY_BELL)
        rule.onNodeWithTag(UiTags.ACTIVITY_BELL).assertContentDescriptionEquals("Activity, 4 unread")
        rule.onNodeWithTag(UiTags.ACTIVITY_BADGE, useUnmergedTree = true).assertIsDisplayed()
        rule.runOnIdle { h.vm.navigate(Destination.LIBRARY) }
        rule.waitUntil(5_000) { h.vm.destination == Destination.LIBRARY }
        rule.waitForTag(UiTags.ACTIVITY_BELL)
        rule.onNodeWithTag(UiTags.ACTIVITY_BELL).assertContentDescriptionEquals("Activity, 4 unread")
    }

    @Test fun noBadgeWhenNothingIsUnread() {
        val h = Harness("empty")
        h.show(rule)
        rule.waitForTag(UiTags.ACTIVITY_BELL)
        rule.onNodeWithTag(UiTags.ACTIVITY_BELL).assertContentDescriptionEquals("Activity")
        assertFalse(rule.exists(UiTags.ACTIVITY_BADGE))
    }

    @Test fun openingTheListMarksEverythingReadButKeepsTheDotsForThisVisit() {
        val h = Harness("activity")
        h.show(rule)
        rule.onNodeWithTag(UiTags.ACTIVITY_BELL).performClick()
        rule.waitForTag(UiTags.ACTIVITY_SCREEN)
        rule.waitUntil(5_000) { unread(h) == 0 }
        // Dots stay for the entries that were new when the page opened.
        assertTrue(rule.exists(UiTags.activityDot("a-sync")))
        assertFalse(rule.exists(UiTags.activityDot("a-batch")))
        rule.onNodeWithText("Unread (4)").assertIsDisplayed()
        // Leaving and coming back: nothing is unread, the bell is quiet.
        rule.onNodeWithTag(UiTags.ACTIVITY_BACK).performClick()
        rule.waitUntil(5_000) { h.vm.subPage == null }
        rule.waitForTag(UiTags.ACTIVITY_BELL)
        rule.onNodeWithTag(UiTags.ACTIVITY_BELL).assertContentDescriptionEquals("Activity")
        rule.onNodeWithTag(UiTags.ACTIVITY_BELL).performClick()
        rule.waitForTag(UiTags.ACTIVITY_SCREEN)
        assertFalse(rule.exists(UiTags.activityDot("a-sync")))
    }

    @Test fun usingAnEntryClearsItsDotWithoutMarkAllRead() {
        val h = Harness("activity")
        h.vm.openActivity()
        h.show(rule)
        rule.waitForTag(UiTags.activityDot("a-failed"))
        rule.onNodeWithTag(UiTags.activityAction("a-failed", "DETAILS")).performClick()
        rule.runOnIdle { h.vm.openActivity() }
        rule.waitForTag(UiTags.ACTIVITY_SCREEN)
        rule.waitForIdle()
        assertFalse(rule.exists(UiTags.activityDot("a-failed")))
    }

    @Test fun filtersNarrowTheListAndCountTheirEntries() {
        val h = Harness("activity")
        h.vm.openActivity()
        h.show(rule)
        rule.waitForTag(UiTags.activityEntry("a-sync"))
        rule.onNodeWithText("Problems (2)").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.activityChip("problems")).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.activityEntry("a-sync")) }
        assertTrue(rule.exists(UiTags.activityEntry("a-failed")))
        assertTrue(rule.exists(UiTags.activityEntry("a-signin")))
        rule.onNodeWithTag(UiTags.activityChip("unread")).performClick()
        rule.waitUntil(5_000) { rule.exists(UiTags.activityEntry("a-sync")) && !rule.exists(UiTags.activityEntry("a-signin")) }
        assertTrue(rule.exists(UiTags.activityEntry("a-update")))
        assertFalse(rule.exists(UiTags.activityEntry("a-batch")))
        rule.onNodeWithTag(UiTags.activityChip("all")).performClick()
        rule.waitUntil(5_000) { rule.exists(UiTags.activityEntry("a-batch")) }
    }

    @Test fun anActionUsesTheExistingNavigation() {
        val h = Harness("activity")
        h.vm.openActivity()
        h.show(rule)
        rule.waitForTag(UiTags.activityAction("a-sync", "OPEN"))
        rule.onNodeWithTag(UiTags.activityAction("a-sync", "OPEN")).performClick()
        rule.waitUntil(5_000) { h.vm.subPage == null && h.vm.destination == Destination.LIBRARY }
        assertEquals("gym", (h.vm.libraryRoute as? app.keepfin.ui.library.LibraryRoute.Collection)?.id)
    }

    @Test fun retryAsksTheEngineToRetryTheFailedItem() {
        val h = Harness("activity")
        h.vm.openActivity()
        h.show(rule)
        rule.waitForTag(UiTags.activityAction("a-failed", "RETRY"))
        rule.onNodeWithTag(UiTags.activityAction("a-failed", "RETRY")).performClick()
        rule.waitUntil(5_000) { h.engine.actions.any { it == "retry:g-ost-10" } }
    }

    @Test fun anActionWhoseTargetIsGoneSaysSo() {
        val h = Harness("activity")
        h.engine.fakeActivity.record(app.keepfin.ActivityEntry("a-gone", ActivityKind.SYNC_FOUND, System.currentTimeMillis(), "Old Mix got 2 new songs", "All 2 saved",
            target = app.keepfin.ActivityTarget(collectionId = "deleted")))
        h.vm.openActivity()
        h.show(rule)
        rule.waitForTag(UiTags.activityAction("a-gone", "OPEN"))
        rule.onNodeWithTag(UiTags.activityAction("a-gone", "OPEN")).performClick()
        rule.waitUntil(5_000) { rule.hasText("That collection is gone") }
        assertEquals(app.keepfin.ui.SubPage.ACTIVITY, h.vm.subPage)
    }

    @Test fun swipingAnEntryAwayRemovesIt() {
        val h = Harness("activity")
        h.vm.openActivity()
        h.show(rule)
        rule.waitForTag(UiTags.activityEntry("a-batch"))
        rule.onNodeWithTag(UiTags.activityEntry("a-batch")).performTouchInput { swipeLeft() }
        rule.waitUntil(5_000) { h.engine.activity.entries.value.none { it.id == "a-batch" } }
        rule.waitUntil(5_000) { !rule.exists(UiTags.activityEntry("a-batch")) }
        assertTrue(h.engine.activity.entries.value.any { it.id == "a-sync" })
    }

    @Test fun markAllReadClearsTheDots() {
        val h = Harness("activity")
        h.vm.openActivity()
        h.show(rule)
        rule.waitForTag(UiTags.activityDot("a-sync"))
        rule.onNodeWithTag(UiTags.ACTIVITY_MARK_ALL).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.activityDot("a-sync")) }
        rule.onNodeWithText("Unread (0)").assertIsDisplayed()
    }

    @Test fun anEmptyListSaysWhatShowsUpHere() {
        val h = Harness("empty")
        h.vm.openActivity()
        h.show(rule)
        rule.waitForTag(UiTags.ACTIVITY_EMPTY)
        rule.onNodeWithText("Nothing yet. Downloads, synced playlists and updates show up here.").assertIsDisplayed()
    }

    @Test fun whatHappenedWhileAwayIsAnnouncedOnceWithAShowAction() {
        val h = Harness("activity-away")
        h.show(rule)
        rule.waitUntil(10_000) { rule.hasText("4 things happened while you were away") }
        rule.onNodeWithText("Show").performClick()
        rule.waitForTag(UiTags.ACTIVITY_SCREEN)
        assertEquals(app.keepfin.ui.SubPage.ACTIVITY, h.vm.subPage)
        // Once per start: asking again finds nothing.
        assertEquals(0, h.engine.activity.takeAwayCount())
    }

    @Test fun noNoticeWhenNothingHappenedWhileAway() {
        val h = Harness("activity")
        h.show(rule)
        rule.waitForTag(UiTags.ACTIVITY_BELL)
        Thread.sleep(1_200)
        assertFalse(rule.hasText("happened while you were away"))
    }
}
