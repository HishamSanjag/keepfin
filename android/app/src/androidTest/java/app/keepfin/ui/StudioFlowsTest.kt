package app.keepfin.ui

import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.FormatRequest
import app.keepfin.JobStatus
import app.keepfin.ui.preview.Fixtures
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import app.keepfin.ui.settings.SettingsCategory
import org.junit.runner.RunWith

/** Compose UI flows against the debug FakeEngine; no network, no service. */
@RunWith(AndroidJUnit4::class)
class StudioFlowsTest {
    @get:Rule val rule = createComposeRule()
    private lateinit var h: Harness
    private val link = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private fun capture(scene: String = "empty") {
        h = Harness(scene)
        h.show(rule)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput(link)
        rule.waitForTag(UiTags.REVIEW)
    }

    /** Scrolls a review control into view inside the sheet (its scrolling area ends above the Download bar). */
    private fun reveal(tag: String? = null, text: String? = null) {
        val node = if (tag != null) rule.onNodeWithTag(tag) else rule.onNodeWithText(text!!)
        node.performScrollTo()
        rule.waitForIdle()
    }

    private fun downloadNode(): androidx.compose.ui.test.SemanticsNodeInteraction {
        rule.waitForIdle()
        return rule.onNodeWithTag(UiTags.DOWNLOAD)
    }

    @Test fun captureReviewChooseFormatEnqueues() {
        capture()
        reveal(UiTags.quality("v720")); rule.onNodeWithTag(UiTags.quality("v720")).performClick()
        downloadNode().assertTextContains("Download", substring = true)
        downloadNode().performClick()
        rule.waitUntil(5_000) { h.engine.enqueued.size == 1 }
        val (media, request, options) = h.engine.enqueued.single()
        assertEquals(link, media.url)
        val video = request as FormatRequest.Video
        assertEquals(720, video.maxHeight)
        assertEquals("298", video.formatId)
        assertEquals(60, video.fps)
        assertEquals("h264", video.codec)
        assertEquals("mp4", video.container)
        assertTrue("default options carry through", options.embedMetadata && !options.subtitles.enabled && options.sponsorBlock == "off")
        rule.waitUntil(5_000) { !rule.exists(UiTags.REVIEW) }
        assertTrue(h.engine.jobs.value.any { it.status == JobStatus.QUEUED })
    }

    @Test fun reviewShowsRealFormatSizes() {
        capture()
        reveal(UiTags.quality("v1080")); rule.onNodeWithTag(UiTags.quality("v1080")).assertTextContains("145", substring = true)
        reveal(UiTags.quality("v2160")); rule.onNodeWithTag(UiTags.quality("v2160")).assertTextContains("≈", substring = true)
    }

    @Test fun sheetOptionsReachTheEngine() {
        capture()
        rule.openExtras()
        reveal("option-subtitles"); rule.onNodeWithTag("option-subtitles").performClick()
        rule.onNodeWithText("Mark").performScrollTo().performClick()
        downloadNode().performClick()
        rule.waitUntil(5_000) { h.engine.enqueued.size == 1 }
        val options = h.engine.enqueued.single().third
        assertTrue(options.subtitles.enabled)
        assertEquals("mark", options.sponsorBlock)
    }
    @Test fun audioTabEnqueuesAudioRequest() {
        capture()
        reveal(UiTags.TAB_AUDIO); rule.onNodeWithTag(UiTags.TAB_AUDIO).performClick()
        reveal(UiTags.audioTarget("m4a")); rule.onNodeWithTag(UiTags.audioTarget("m4a")).performClick()
        downloadNode().performClick()
        rule.waitUntil(5_000) { h.engine.enqueued.size == 1 }
        val request = h.engine.enqueued.single().second as FormatRequest.Audio
        assertEquals("m4a", request.target)
    }

    @Test fun downloadStaysAboveTheNavigationBar() {
        capture()
        val node = downloadNode().fetchSemanticsNode()
        val bottom = node.positionOnScreen.y + node.size.height
        val metrics = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(WindowManager::class.java).currentWindowMetrics
        val limit = metrics.bounds.height() - metrics.windowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
        assertTrue("Download ends at $bottom, past $limit", bottom <= limit)
    }

    @Test fun readingALinkOpensTheReviewSheetInItsLoadingStateThenFillsItIn() {
        h = Harness("empty")
        h.engine.inspectDelayMs = 1_500
        h.show(rule)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput(link)
        rule.waitForTag(UiTags.REVIEW_SHEET)
        rule.waitForTag(UiTags.INSPECTING)
        assertTrue(rule.hasText("Reading formats from"))
        assertTrue(rule.exists(UiTags.CANCEL_INSPECTION))
        assertFalse(rule.exists(UiTags.DOWNLOAD))
        rule.waitUntil(10_000) { rule.exists(UiTags.DOWNLOAD) }
        assertFalse(rule.exists(UiTags.INSPECTING))
        assertTrue(rule.exists(UiTags.REVIEW_SHEET))
    }

    @Test fun cancelStopsInspection() {
        h = Harness("empty")
        h.engine.inspectDelayMs = 5_000
        h.show(rule)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput(link)
        rule.waitForTag(UiTags.INSPECTING)
        rule.onNodeWithTag(UiTags.CANCEL_INSPECTION).performClick()
        rule.waitUntil(5_000) { h.engine.cancelledInspections.isNotEmpty() }
        assertFalse(rule.exists(UiTags.REVIEW))
    }

    @Test fun progressTickRecomposesOnlyItsRowAndNeverReloadsArtwork() {
        h = Harness("default")
        h.show(rule)
        rule.waitForTag(UiTags.job("j-dl"))
        rule.waitForIdle()
        val visible = h.rowRenders.keys.toList()
        assertTrue("several rows are on screen: $visible", visible.size >= 3 && "j-dl" in visible)
        val thumb = Fixtures.thumb("aqz-KE-bpKQ")
        rule.waitUntil(5_000) { h.requests(thumb) >= 1 }
        val before = visible.associateWith { h.renders(it) }
        val requestsBefore = h.requests(thumb)
        repeat(12) { i ->
            h.engine.updateJob("j-dl") { it.copy(percent = 43f + i, downloadedBytes = (it.totalBytes!! * (43 + i) / 100), speed = 6_000_000L + i * 1000L) }
            rule.waitForIdle()
        }
        assertTrue("the ticking row recomposed", h.renders("j-dl") >= before.getValue("j-dl") + 6)
        visible.filter { it != "j-dl" }.forEach { id -> assertEquals("row $id must not recompose", before.getValue(id), h.renders(id)) }
        assertEquals("artwork request stays one per model", requestsBefore, h.requests(thumb))
    }

    @Test fun queueActionsReachTheEngine() {
        h = Harness("default")
        h.show(rule)
        rule.waitForTag(UiTags.job("j-dl"))
        val title = h.engine.jobs.value.first { it.id == "j-dl" }.title
        rule.onNodeWithContentDescription("Pause $title").performClick()
        rule.waitUntil(5_000) { "pause:j-dl" in h.engine.actions }
        val paused = h.engine.jobs.value.first { it.id == "j-paused" }.title
        rule.onNodeWithContentDescription("Resume $paused").performScrollTo().performClick()
        rule.waitUntil(5_000) { "resume:j-paused" in h.engine.actions }
    }

    @Test fun failedJobOffersRetry() {
        h = Harness("default")
        h.show(rule)
        rule.scrollListTo(UiTags.QUEUE, UiTags.job("j-failed"))
        rule.onNodeWithText("Retry").performScrollTo().performClick()
        rule.waitUntil(5_000) { "retry:j-failed" in h.engine.actions }
    }

    @Test fun saveErrorOffersSaveAgain() {
        h = Harness("default")
        h.show(rule)
        rule.scrollListTo(UiTags.QUEUE, UiTags.job("j-saveerr"))
        rule.onNodeWithText("Save again").performScrollTo().performClick()
        rule.waitUntil(5_000) { "save:j-saveerr" in h.engine.actions }
    }

    @Test fun shareLinkInspectsAutomaticallyWithoutDownloading() {
        h = Harness("empty")
        h.show(rule)
        h.vm.loadLink("Look at this https://www.youtube.com/watch?v=aqz-KE-bpKQ great video", fromShare = true)
        rule.waitForTag(UiTags.REVIEW)
        assertEquals(listOf(link), h.engine.inspectCalls.toList())
        assertTrue("sharing never starts a download", h.engine.enqueued.isEmpty() && h.engine.jobs.value.isEmpty())
    }

    @Test fun libraryAndSearchFilters() {
        h = Harness("library")
        h.libraryOff(); h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.waitForTag(UiTags.LIBRARY_LIST)
        rule.onNodeWithTag(UiTags.LIBRARY_SEARCH).performTextInput("Sintel")
        rule.waitUntil(5_000) { rule.exists(UiTags.job("j-lib2")) && !rule.exists(UiTags.job("j-lib1")) }
        rule.onNodeWithTag(UiTags.LIBRARY_SEARCH).performTextClearance()
        rule.onNodeWithTag(UiTags.filter("audio")).performClick()
        rule.waitUntil(5_000) { rule.exists(UiTags.job("j-audio")) && !rule.exists(UiTags.job("j-lib2")) }
        rule.onNodeWithTag(UiTags.filter("video")).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.job("j-audio")) }
        rule.onNodeWithTag(UiTags.LIBRARY_VIEW_TOGGLE).performClick()
        assertTrue(h.prefs.libraryGrid)
    }

    @Test fun libraryDeleteAsksForConfirmationAndRemoveKeepsFile() {
        h = Harness("library")
        h.libraryOff(); h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.waitForTag(UiTags.LIBRARY_LIST)
        val title = h.engine.jobs.value.first { it.id == "j-lib1" }.title
        rule.onNodeWithTag(UiTags.LIBRARY_SEARCH).performTextInput("Charge")
        rule.waitUntil(5_000) { rule.exists(UiTags.job("j-lib1")) && !rule.exists(UiTags.job("j-lib2")) }
        rule.onNodeWithContentDescription("More actions for $title").performClick()
        rule.onNodeWithText("Delete file…").performClick()
        rule.onNodeWithText("Delete this file?").assertExists()
        assertFalse("nothing is deleted before confirming", "delete:j-lib1" in h.engine.actions)
        rule.onNodeWithText("Keep").performClick()
        rule.onNodeWithContentDescription("More actions for $title").performClick()
        rule.onNodeWithText("Remove from history").performClick()
        rule.waitUntil(5_000) { "remove:j-lib1" in h.engine.actions }
        assertFalse("remove must not delete media", "delete:j-lib1" in h.engine.actions)
    }

    @Test fun clearHistoryConfirms() {
        h = Harness("library")
        h.libraryOff(); h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.onNodeWithContentDescription("More library actions").performClick()
        rule.onNodeWithTag(UiTags.LIBRARY_CLEAR).performClick()
        assertFalse("clearHistory" in h.engine.actions)
        rule.onNodeWithText("Clear history").performClick()
        rule.waitUntil(5_000) { "clearHistory" in h.engine.actions }
    }

    @Test fun settingsTogglesCallTheEngine() {
        h = Harness("default")
        // Performance and Network are one collapsed "Speed and connections" group in Downloads now; the old ids still open it
        h.openSettings("PERFORMANCE")
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_LIST)
        val turbo0 = h.engine.settings.value.turbo
        rule.scrollListTo(UiTags.SETTINGS_LIST, UiTags.setting("turbo")); rule.onNodeWithTag(UiTags.setting("turbo")).performClick()
        rule.waitUntil(5_000) { h.engine.settings.value.turbo != turbo0 }
        rule.scrollListTo(UiTags.SETTINGS_LIST, UiTags.setting("wifi")); rule.onNodeWithTag(UiTags.setting("wifi")).performClick()
        rule.waitUntil(5_000) { h.engine.settings.value.wifiOnly }
        rule.runOnIdle { h.vm.openSettings("UPDATES") }
        rule.waitForTag(UiTags.SETTINGS_LIST)
        rule.scrollListTo(UiTags.SETTINGS_LIST, UiTags.setting("channel-nightly")); rule.onNodeWithTag(UiTags.setting("channel-nightly")).performClick()
        rule.waitUntil(5_000) { h.engine.settings.value.channel == "nightly" }
        rule.scrollListTo(UiTags.SETTINGS_LIST, UiTags.ENGINE_CHECK_NOW); rule.onNodeWithTag(UiTags.ENGINE_CHECK_NOW).performClick()
        rule.waitUntil(5_000) { h.engine.toolUpdates.get() == 1 }
        rule.runOnIdle { h.vm.openSettings("GENERAL") }
        rule.waitForTag(UiTags.setting("theme-dark")); rule.onNodeWithTag(UiTags.setting("theme-dark")).performClick()
        rule.waitUntil(5_000) { h.prefs.theme.wire == "dark" }
    }

    @Test fun signInRowOpensTheSignInPage() {
        h = Harness("default")
        h.openSettings("ACCOUNTS")
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_LIST)
        rule.scrollListTo(UiTags.SETTINGS_LIST, UiTags.SIGN_IN_ROW); rule.onNodeWithTag(UiTags.SIGN_IN_ROW).performClick()
        rule.waitForTag(UiTags.SIGN_IN_DONE)
        assertEquals(SubPage.SIGN_IN, h.vm.subPage)
    }

    @Test fun aboutKeepfinOpensTheReleaseNotesOfAnAvailableUpdate() {
        h = Harness("default", updates = app.keepfin.ui.preview.FakeUpdates.scene("available"))
        h.openSettings("ABOUT")
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_LIST)
        rule.scrollListTo(UiTags.SETTINGS_LIST, "update-release-notes"); rule.onNodeWithTag("update-release-notes").performClick()
        rule.waitForTag(UiTags.RELEASE_NOTES_BODY)
    }

}
