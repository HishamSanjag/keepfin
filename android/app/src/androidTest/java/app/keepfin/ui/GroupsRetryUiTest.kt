package app.keepfin.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.JobStatus
import app.keepfin.ui.preview.Fixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Collections kept together, automatic-retry row text, the Updates section, one-shot reveal and music defaults. */
@RunWith(AndroidJUnit4::class)
class GroupsRetryUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private fun openCollection(h: Harness) {
        h.show(rule)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput("https://www.youtube.com/playlist?list=PLblender")
        rule.waitForTag(UiTags.COLLECTION)
        rule.waitUntil(10_000) { h.vm.collection?.entries?.size == 60 && h.vm.collection?.discovering == false }
        // A playlist opens with every entry selected.
        rule.waitUntil(5_000) { h.vm.collection?.selectedCount == 60 }
    }

    // ---- Feature 1: keep together vs separate -----------------------------------------------------

    @Test fun collectionDefaultsToKeepTogetherAndSendsAGroup() {
        val h = Harness("empty")
        openCollection(h)
        rule.openExtras()
        rule.onNodeWithTag(UiTags.COLLECTION_KEEP_TOGETHER).assertIsSelected().assertIsDisplayed()
        rule.onNodeWithText("One collection card", substring = true).assertExists()
        rule.closeExtras()
        rule.onNodeWithTag(UiTags.COLLECTION_DOWNLOAD).performClick()
        rule.waitUntil(5_000) { h.engine.enqueuedGroups.size == 1 }
        val group = h.engine.enqueuedGroups.single()
        assertNotNull("Kept together jobs carry a group descriptor", group)
        assertEquals("Blender Open Movies", group!!.title)
        assertEquals("https://www.youtube.com/playlist?list=PLblender", group.url)
        assertTrue(h.engine.jobs.value.all { it.group?.id == group.id })
    }

    @Test fun separateItemsSendsNoGroupAndIsRemembered() {
        val prefs = UiPreferences(androidx.test.core.app.ApplicationProvider.getApplicationContext(), "ui-test-" + UUID.randomUUID())
        val h = Harness("empty", prefs = prefs)
        openCollection(h)
        rule.openExtras()
        rule.onNodeWithTag(UiTags.COLLECTION_SEPARATE).performClick()
        rule.onNodeWithTag(UiTags.COLLECTION_SEPARATE).assertIsSelected()
        rule.onNodeWithText("Every item is queued on its own", substring = true).assertExists()
        rule.closeExtras()
        rule.onNodeWithTag(UiTags.COLLECTION_DOWNLOAD).performClick()
        rule.waitUntil(5_000) { h.engine.enqueuedGroups.size == 1 }
        assertNull(h.engine.enqueuedGroups.single())
        assertTrue(h.engine.jobs.value.all { it.group == null })
        assertFalse("The last choice is remembered", prefs.keepCollectionsTogether)
    }

    @Test fun groupedCardExpandsCollapsesAndShowsCounts() {
        val h = Harness("groups")
        h.show(rule)
        rule.waitForTag(UiTags.group("g-live"))
        rule.onNodeWithTag(UiTags.groupCounts("g-live"), useUnmergedTree = true).assertTextContains("8 of 12 done · 1 failed", substring = true)
        rule.onNodeWithTag(UiTags.groupProgress("g-live"), useUnmergedTree = true).assertExists()
        // Something is still downloading, so the list starts open: members by state (failed first, then in progress, then the saved ones in playlist order).
        rule.scrollListTo(UiTags.QUEUE, UiTags.job("g-live-1"))
        rule.waitForTag(UiTags.job("g-live-1"))
        rule.scrollListTo(UiTags.QUEUE, UiTags.job("g-live-9"))
        rule.onNodeWithTag(UiTags.job("g-live-9")).assertIsDisplayed()
        rule.scrollListTo(UiTags.QUEUE, UiTags.group("g-live"))
        rule.onNodeWithTag(UiTags.groupToggle("g-live")).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.job("g-live-1")) }
        rule.onNodeWithTag(UiTags.groupToggle("g-live")).performClick()
        rule.scrollListTo(UiTags.QUEUE, UiTags.job("g-live-9"))
        rule.waitForTag(UiTags.job("g-live-9"))
    }

    @Test fun finishedListStartsClosedAndOpensWithTheChevron() {
        val h = Harness("groups")
        h.show(rule)
        rule.scrollListTo(UiTags.QUEUE, UiTags.group("g-done"))
        assertFalse("Everything is saved: the list starts closed", rule.exists(UiTags.job("g-done-1")))
        rule.onNodeWithTag(UiTags.groupToggle("g-done")).performClick()
        rule.waitForTag(UiTags.job("g-done-1"))
        rule.onNodeWithTag(UiTags.groupToggle("g-done")).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.job("g-done-1")) }
    }

    @Test fun aListItemAndASingleDownloadAreTheSameRow() {
        val h = Harness("groups")
        h.show(rule)
        rule.scrollListTo(UiTags.QUEUE, UiTags.group("g-done"))
        rule.onNodeWithTag(UiTags.groupToggle("g-done")).performClick()
        rule.waitForTag(UiTags.job("g-done-1"))
        rule.scrollListTo(UiTags.QUEUE, UiTags.job("j-single"))
        rule.waitForTag(UiTags.job("j-single"))
        fun size(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.let { it.width to it.height }
        assertEquals("The same picture size", size(UiTags.jobThumb("j-single")), size(UiTags.jobThumb("g-done-1")))
        for (tag in listOf(UiTags::jobOpen, UiTags::jobMenu)) {
            rule.onNodeWithTag(tag("j-single"), useUnmergedTree = true).assertExists()
            rule.onNodeWithTag(tag("g-done-1"), useUnmergedTree = true).assertExists()
        }
        // The same details line: size and where it was saved.
        rule.onNodeWithTag(UiTags.job("g-done-1")).assertTextContains("Saved to", substring = true)
        rule.onNodeWithTag(UiTags.job("j-single")).assertTextContains("Saved to", substring = true)
    }

    @Test fun groupMenuActionsReachEveryMember() {
        val h = Harness("groups")
        h.show(rule)
        rule.waitForTag(UiTags.group("g-live"))
        fun menu(item: String) { rule.onNodeWithTag(UiTags.groupMenu("g-live")).performClick(); rule.onNodeWithTag(item).performClick() }
        menu(UiTags.GROUP_PAUSE)
        rule.waitUntil(5_000) { h.engine.actions.count { it.startsWith("pause:g-live-") } == 3 }
        menu(UiTags.GROUP_RESUME)
        rule.waitUntil(5_000) { h.engine.actions.count { it.startsWith("resume:g-live-") } == 3 }
        menu(UiTags.GROUP_RETRY)
        rule.waitUntil(5_000) { h.engine.actions.contains("retry:g-live-11") }
        menu(UiTags.GROUP_CANCEL)
        rule.waitUntil(5_000) { h.engine.actions.count { it.startsWith("cancel:g-live-") } >= 3 }
        assertTrue("Completed members are untouched", h.engine.actions.none { it == "cancel:g-live-1" })
    }

    @Test fun finishedGroupIsRemovedFromHistoryKeepingMediaAndActiveGroupsRejectRemoval() {
        val h = Harness("groups")
        h.show(rule)
        rule.scrollListTo(UiTags.QUEUE, UiTags.group("g-done"))
        rule.onNodeWithTag(UiTags.groupMenu("g-done")).performClick()
        rule.onNodeWithTag(UiTags.GROUP_REMOVE).performClick()
        rule.waitUntil(5_000) { h.engine.actions.count { it.startsWith("remove:g-done-") } == 5 }
        assertTrue(h.engine.jobs.value.none { it.group?.id == "g-done" })
        // An active collection never offers removal.
        rule.scrollListTo(UiTags.QUEUE, UiTags.group("g-live"))
        rule.onNodeWithTag(UiTags.groupMenu("g-live")).performClick()
        assertFalse(rule.exists(UiTags.GROUP_REMOVE))
        rule.onNodeWithTag(UiTags.GROUP_PAUSE).assertExists()
        h.vm.collections.removeGroup("g-live")
        rule.waitForIdle()
        assertTrue("Rejected while members are active", h.engine.jobs.value.count { it.group?.id == "g-live" } == 12)
    }

    @Test fun libraryShowsOneCardPerFinishedCollection() {
        val h = Harness("groups-library")
        h.libraryOff(); h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.waitForTag(UiTags.group("g-done"))
        assertFalse(rule.exists(UiTags.job("g-done-1")))
        rule.onNodeWithTag(UiTags.groupToggle("g-done")).performClick()
        rule.waitForTag(UiTags.job("g-done-1"))
    }

    // ---- Collection card: failures first ------------------------------------------------------------------

    private fun top(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    /** The given tags in list order, as the list composes its rows (a row that is still animating into place has no reliable position). */
    private fun listOrder(vararg tags: String): List<String> =
        rule.onNodeWithTag(UiTags.QUEUE).fetchSemanticsNode().children
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.filter { it in tags }

    @Test fun collectionWithFailuresShowsAsSoonAsNothingIsDownloadingEvenWhileAMemberWaitsForARetry() {
        val h = Harness("groups-failed")
        h.show(rule)
        rule.waitForTag(UiTags.group("g-ost"))
        // The waiting-for-retry member does not keep the collection in "In progress": it sits below the running download.
        rule.waitForTag(UiTags.job("j-dl"))
        assertTrue(top(UiTags.group("g-ost")) > top(UiTags.job("j-dl")))
        rule.onNodeWithTag(UiTags.groupCounts("g-ost"), useUnmergedTree = true).assertTextContains("9 saved · 1 failed · 1 waiting", substring = true)
        rule.onNodeWithTag(UiTags.groupRetryButton("g-ost"), useUnmergedTree = true).assertExists()
        assertFalse("No progress bar while nothing is downloading", rule.exists(UiTags.groupProgress("g-ost")))
        // It is also listed under Finished, with its failures.
        rule.onNodeWithTag(UiTags.downloadsFilter("FINISHED")).performClick()
        rule.waitForTag(UiTags.group("g-ost"))
        rule.onNodeWithTag(UiTags.downloadsFilter("FAILED")).performClick()
        rule.waitForTag(UiTags.group("g-ost"))
    }

    @Test fun expandedCollectionListsNeedsAttentionFirstThenSavedInPlaylistOrder() {
        val h = Harness("groups-failed")
        h.show(rule)
        rule.waitForTag(UiTags.group("g-ost"))
        rule.scrollListTo(UiTags.QUEUE, UiTags.group("g-ost"))
        rule.waitForTag(UiTags.groupAttention("g-ost"))
        rule.scrollListTo(UiTags.QUEUE, UiTags.job("g-ost-10"))
        rule.waitForTag(UiTags.job("g-ost-11"))
        assertEquals("Needs attention, the failed one, then the one waiting for a retry", listOf(UiTags.groupAttention("g-ost"), UiTags.job("g-ost-10"), UiTags.job("g-ost-11")), listOrder(UiTags.groupAttention("g-ost"), UiTags.job("g-ost-10"), UiTags.job("g-ost-11")))
        rule.onNodeWithTag(UiTags.jobMenu("g-ost-10"), useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(UiTags.jobRetry("g-ost-10"), useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Not available in your region").assertExists()
        rule.onNodeWithTag(UiTags.QUEUE).performScrollToNode(androidx.compose.ui.test.hasText("Saved · 9"))
        rule.onNodeWithText("Saved · 9").assertExists()
        rule.scrollListTo(UiTags.QUEUE, UiTags.job("g-ost-1"))
        rule.onNodeWithTag(UiTags.job("g-ost-1")).assertExists()
        rule.scrollListTo(UiTags.QUEUE, UiTags.job("g-ost-9"))
        rule.onNodeWithTag(UiTags.job("g-ost-9")).assertExists()
    }

    @Test fun retryFailedOnTheCardRetriesOnlyTheFailedMember() {
        val h = Harness("groups-failed")
        h.show(rule)
        rule.waitForTag(UiTags.groupRetryButton("g-ost"))
        rule.onNodeWithTag(UiTags.groupRetryButton("g-ost")).performClick()
        rule.waitUntil(5_000) { h.engine.actions.contains("retry:g-ost-10") }
        assertTrue(h.engine.actions.none { it == "retry:g-ost-1" || it == "retry:g-ost-11" })
    }

    // ---- Feature 2: retrying row text -------------------------------------------------------------

    @Test fun retryingRowsAreTruthful() {
        val h = Harness("retrying")
        h.show(rule)
        rule.waitForTag(UiTags.jobStatus("j-retry"))
        rule.onNodeWithTag(UiTags.jobStatus("j-retry"), useUnmergedTree = true).assertTextContains("Retrying in", substring = true).assertTextContains("(2 of 3)", substring = true)
        rule.onNodeWithTag(UiTags.jobStatus("j-retry-engine"), useUnmergedTree = true).assertTextContains("Updating engine, then retrying (2 of 3)", substring = true)
        // The countdown ticks down instead of freezing.
        val before = rule.onNodeWithTag(UiTags.jobStatus("j-retry"), useUnmergedTree = true).fetchSemanticsNode().config.toString()
        rule.waitUntil(5_000) { rule.onNodeWithTag(UiTags.jobStatus("j-retry"), useUnmergedTree = true).fetchSemanticsNode().config.toString() != before }
    }

    // ---- Feature 3: updates ----------------------------------------------------------------------

    @Test fun downloadsHeaderHasNoEngineChipAndSettingsHasOneUpdatesSection() {
        val h = Harness("default")
        h.show(rule)
        rule.waitForTag(UiTags.QUEUE)
        assertFalse("The always-visible engine chip is gone", rule.exists(UiTags.ENGINE_CHIP))
        assertFalse(rule.hasText("Engine 2026"))
        assertFalse("A healthy engine shows nothing on the main screen", rule.exists(UiTags.ENGINE_NOTICE))
        rule.runOnIdle { h.openSettings(app.keepfin.ui.settings.SettingsCategory.ABOUT) }
        rule.waitForTag(UiTags.SETTINGS_LIST)
        rule.scrollListTo(UiTags.SETTINGS_LIST, UiTags.UPDATES_APP)
        // About › Keepfin is a heading and the app row's name: either is fine
        rule.onAllNodesWithText("Keepfin").onFirst().assertExists()
        rule.onNodeWithTag(UiTags.UPDATE_CHECK).assertExists()
        rule.onNodeWithTag(UiTags.UPDATE_WHATS_NEW).assertExists()
        rule.scrollListTo(UiTags.SETTINGS_LIST, UiTags.UPDATES_ENGINE)
        rule.onNodeWithText("Download engine — updates automatically").assertExists()
        rule.onNodeWithTag(UiTags.ENGINE_CHECK_NOW).assertExists().performClick()
        rule.waitUntil(5_000) { h.engine.toolUpdates.get() == 1 }
        assertFalse("External tool names never reach the UI", rule.hasText("yt-dlp") || rule.hasText("ffmpeg") || rule.hasText("Media processor") || rule.hasText("Runtime"))
        assertEquals("One Updates section replaces the separate Engine group", 0, if (rule.hasText("Release channel")) 1 else 0)
    }

    @Test fun brokenEngineShowsFixAndUpdatingEngineShowsWhileJobsWait() {
        val broken = Harness("engine-broken")
        broken.show(rule)
        rule.waitForTag(UiTags.ENGINE_NOTICE)
        rule.onNodeWithTag(UiTags.ENGINE_FIX).performClick()
        rule.waitUntil(5_000) { broken.engine.toolUpdates.get() == 1 }
    }

    @Test fun updatingEngineNoticeShowsOnlyWhileADownloadWaits() {
        val h = Harness("engine-updating")
        h.show(rule)
        rule.waitForTag(UiTags.ENGINE_NOTICE)
        rule.onNodeWithText("Updating the download engine", substring = true).assertExists()
        h.engine.setJobs(h.engine.jobs.value.filter { it.status == JobStatus.COMPLETED })
        rule.waitUntil(5_000) { !rule.exists(UiTags.ENGINE_NOTICE) }
    }

    // ---- One-shot reveal --------------------------------------------------------------------------

    @Test fun notificationRevealScrollsOnceThenLetsTheUserScrollFreely() {
        val h = Harness("empty")
        val many = (1..40).map { n -> Fixtures.job("r-$n", "Clip number $n", null, JobStatus.COMPLETED, publicPath = "/storage/emulated/0/Movies/Keepfin/c$n.mp4", fileSize = 10_000_000L, ageMinutes = n.toLong()) }
        h.engine.setJobs(many)
        h.libraryOff(); h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.waitForTag(UiTags.LIBRARY_LIST)
        rule.runOnIdle { h.vm.openFromNotification("History", "r-35") }
        rule.waitUntil(8_000) { rule.exists(UiTags.job("r-35")) }
        rule.onNodeWithTag(UiTags.job("r-35")).assertIsDisplayed()
        // The event is consumed after the brief highlight.
        rule.waitUntil(8_000) { h.vm.reveal == null }
        // The user scrolls away; list changes (e.g. progress ticks of other jobs) must not pull the list back.
        rule.onNodeWithTag(UiTags.LIBRARY_LIST).performScrollToIndex(0)
        rule.waitUntil(5_000) { rule.exists(UiTags.job("r-1")) }
        h.engine.updateJob("r-20") { it.copy(title = "Clip number 20 (renamed)") }
        Thread.sleep(1500)
        rule.waitForIdle()
        rule.onNodeWithTag(UiTags.job("r-1")).assertIsDisplayed()
        assertFalse("No snap-back to the revealed item", rule.exists(UiTags.job("r-35")))
        // Tapping another notification works again (a new event).
        rule.runOnIdle { h.vm.openFromNotification("History", "r-30") }
        rule.waitUntil(8_000) { rule.exists(UiTags.job("r-30")) }
        rule.onNodeWithTag(UiTags.job("r-30")).assertIsDisplayed()
        rule.waitUntil(8_000) { h.vm.reveal == null }
    }

    @Test fun groupNotificationTargetRevealsAndExpandsTheCollectionOnce() {
        val h = Harness("groups-library")
        h.libraryOff(); h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.waitForTag(UiTags.group("g-done"))
        rule.runOnIdle { h.vm.openFromNotification("History", "group:g-done") }
        rule.waitForTag(UiTags.job("g-done-1"))
        rule.waitUntil(8_000) { h.vm.reveal == null }
        rule.onNodeWithTag(UiTags.groupToggle("g-done")).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.job("g-done-1")) }
        Thread.sleep(800)
        assertFalse("A consumed target never re-expands the card", rule.exists(UiTags.job("g-done-1")))
    }

    // ---- YouTube Music defaults -------------------------------------------------------------------

    @Test fun youTubeMusicLinksOpenAsAudioAndCanSwitchToVideo() {
        val h = Harness("empty")
        h.show(rule)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput("https://music.youtube.com/watch?v=aqz-KE-bpKQ")
        rule.waitForTag(UiTags.REVIEW)
        rule.runOnIdle { assertTrue("YouTube Music opens on Audio only", h.vm.choice.audioMode) }
        rule.onNodeWithTag(UiTags.TAB_AUDIO).assertIsSelected()
        assertNull("No stream is picked: null is the best one, as the core ranks them", h.vm.choice.audioFormatId)
        rule.onNodeWithTag(UiTags.TAB_VIDEO).performClick()
        rule.runOnIdle { assertFalse("The user can still switch to Video", h.vm.choice.audioMode) }
    }

    @Test fun regularYouTubeStaysVideoAndTheSettingCanFlipYouTubeMusicToVideo() {
        val h = Harness("empty")
        h.show(rule)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput("https://www.youtube.com/watch?v=aqz-KE-bpKQ")
        rule.waitForTag(UiTags.REVIEW)
        rule.runOnIdle { assertFalse(h.vm.choice.audioMode) }
        rule.onNodeWithTag(UiTags.TAB_VIDEO).assertIsSelected()
        h.engine.updateSettings { it.copy(ytMusicAudio = false) }
        rule.runOnIdle { h.vm.loadLink("https://music.youtube.com/watch?v=aqz-KE-bpKQ") }
        rule.waitUntil(8_000) { h.vm.capture is Capture.Single && (h.vm.capture as Capture.Single).media.url.contains("music.youtube.com") }
        rule.runOnIdle { assertFalse("Video preference is honoured", h.vm.choice.audioMode) }
    }

    @Test fun youTubeMusicCollectionsDefaultToAnAudioPresetAndAudioPlaceholders() {
        val h = Harness("empty")
        h.show(rule)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput("https://music.youtube.com/playlist?list=OLAKblender")
        rule.waitForTag(UiTags.COLLECTION)
        rule.runOnIdle {
            assertTrue(h.vm.collection!!.music)
            assertTrue("Batch quality defaults to the audio preset", h.vm.collection!!.preset.wire.startsWith("audio-"))
        }
    }

    @Test fun settingsOffersYouTubeMusicAudioOrVideo() {
        val h = Harness("default")
        h.openSettings(app.keepfin.ui.settings.SettingsCategory.DOWNLOADS)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_LIST)
        rule.scrollListTo(UiTags.SETTINGS_LIST, UiTags.YTMUSIC_AUDIO)
        rule.onNodeWithTag(UiTags.setting("ytmusic-audio")).assertIsSelected()
        rule.onNodeWithTag(UiTags.setting("ytmusic-video")).performClick()
        rule.waitUntil(5_000) { !h.engine.settings.value.ytMusicAudio }
    }

    // ---- Empty states -----------------------------------------------------------------------------

    @Test fun emptyDownloadsIsCentredWithOneFilledActionAndHint() {
        val h = Harness("empty")
        h.show(rule)
        rule.waitForTag(UiTags.EMPTY_STATE)
        rule.onNodeWithText("Nothing downloading yet").assertIsDisplayed()
        rule.onNodeWithText("or share a link from any app").assertIsDisplayed()
        val action = rule.onNodeWithTag(UiTags.EMPTY_ACTION).assertIsDisplayed()
        action.assertTextContains("Paste link")
        val list = rule.onNodeWithTag(UiTags.QUEUE).fetchSemanticsNode().boundsInRoot
        val empty = rule.onNodeWithTag(UiTags.EMPTY_STATE).fetchSemanticsNode().boundsInRoot
        val content = rule.onNodeWithText("Nothing downloading yet").fetchSemanticsNode().boundsInRoot
        assertTrue("Empty block ends above the bottom of the list (not under the bar)", empty.bottom <= list.bottom + 1f)
        val mid = (empty.top + empty.bottom) / 2f
        assertTrue("Title sits near the vertical middle of the remaining space", kotlin.math.abs((content.top + content.bottom) / 2f - mid) < (empty.bottom - empty.top) * 0.30f)
    }

    @Test fun emptyLibraryAndNoResultsHaveTheirOwnActions() {
        val h = Harness("empty")
        h.libraryOff(); h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.waitForTag(UiTags.EMPTY_STATE)
        rule.onNodeWithText("Your library is empty").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.EMPTY_ACTION).assertTextContains("Go to Downloads").performClick()
        rule.waitUntil(5_000) { h.vm.destination == Destination.DOWNLOADS }
    }

    @Test fun libraryWithNoMatchesOffersClearFilters() {
        val filled = Harness("library")
        filled.libraryOff(); filled.vm.navigate(Destination.LIBRARY)
        filled.show(rule)
        rule.waitForTag(UiTags.LIBRARY_LIST)
        rule.onNodeWithTag(UiTags.LIBRARY_SEARCH).performTextInput("zzzz-no-such-title")
        rule.waitForTag(UiTags.EMPTY_STATE)
        rule.onNodeWithText("Nothing matches").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.EMPTY_ACTION).assertTextContains("Clear filters").performClick()
        rule.waitForTag(UiTags.LIBRARY_LIST)
    }

    @Test fun collectionWithNoEntriesShowsAnEmptyState() {
        val h = Harness("empty")
        h.engine.collectionSize = 0
        h.show(rule)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput("https://www.youtube.com/playlist?list=PLempty")
        rule.waitForTag(UiTags.COLLECTION)
        rule.waitForTag(UiTags.EMPTY_STATE)
        rule.onNodeWithText("Nothing to download here").assertIsDisplayed()
    }

    private fun openOst(h: Harness) {
        h.show(rule)
        rule.waitForTag(UiTags.group("g-ost"))
        rule.scrollListTo(UiTags.QUEUE, UiTags.group("g-ost"))
        rule.waitForTag(UiTags.groupAttention("g-ost"))
    }

    @Test fun aFailedMemberCanBeRemovedFromItsRowMenuAndTheCountsUpdate() {
        val h = Harness("groups-failed")
        openOst(h)
        rule.scrollListTo(UiTags.QUEUE, UiTags.job("g-ost-10"))
        rule.onNodeWithTag(UiTags.jobMenu("g-ost-10"), useUnmergedTree = true).performClick()
        rule.onNodeWithTag(UiTags.jobMenuDetails("g-ost-10")).assertExists()
        rule.onNodeWithTag(UiTags.jobRemove("g-ost-10")).performClick()
        rule.waitUntil(5_000) { h.engine.actions.contains("remove:g-ost-10") }
        assertTrue("A failed item needs no cancel first", h.engine.actions.none { it == "cancel:g-ost-10" })
        rule.waitUntil(5_000) { !rule.exists(UiTags.job("g-ost-10")) }
        rule.scrollListTo(UiTags.QUEUE, UiTags.group("g-ost"))
        rule.onNodeWithTag(UiTags.groupCounts("g-ost"), useUnmergedTree = true).assertTextContains("9 saved · 1 waiting", substring = true)
        rule.waitUntil(5_000) { rule.hasText("Removed from history") }
    }

    @Test fun removingAMemberWaitingForARetryCancelsTheRetryFirst() {
        val h = Harness("groups-failed")
        openOst(h)
        rule.scrollListTo(UiTags.QUEUE, UiTags.job("g-ost-11"))
        rule.onNodeWithTag(UiTags.jobMenu("g-ost-11"), useUnmergedTree = true).performClick()
        rule.onNodeWithTag(UiTags.jobRemove("g-ost-11")).performClick()
        rule.waitUntil(5_000) { h.engine.actions.contains("remove:g-ost-11") }
        assertTrue(h.engine.actions.indexOf("cancel:g-ost-11") in 0 until h.engine.actions.indexOf("remove:g-ost-11"))
    }

    @Test fun removeFailedInTheGroupMenuRemovesEveryFailedItemButKeepsTheRest() {
        val h = Harness("groups-failed")
        h.show(rule)
        rule.waitForTag(UiTags.group("g-ost"))
        rule.scrollListTo(UiTags.QUEUE, UiTags.group("g-ost"))
        rule.onNodeWithTag(UiTags.groupMenu("g-ost")).performClick()
        rule.onNodeWithTag(UiTags.GROUP_REMOVE_FAILED).performClick()
        rule.waitUntil(5_000) { h.engine.actions.contains("remove:g-ost-10") }
        assertTrue("Waiting and saved items stay", h.engine.actions.none { it == "remove:g-ost-11" || it == "remove:g-ost-1" })
        rule.waitUntil(5_000) { rule.hasText("Removed from history") }
    }
}
