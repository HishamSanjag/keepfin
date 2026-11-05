package app.keepfin.ui

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.JobStatus
import app.keepfin.ui.library.LibraryRoute
import app.keepfin.ui.preview.Fixtures
import app.keepfin.ui.theme.ThemeMode
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device screenshots for the opt-in follow work. Run with `-Pandroid.testInstrumentationRunnerArguments.screenshots=true`; files land in /data/local/tmp/sync-*.png. */
@RunWith(AndroidJUnit4::class)
class SyncScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Before fun only() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        StudioEngine.override = null
    }

    private fun shot(name: String) {
        Thread.sleep(900)
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/sync-$name.png").close()
        Thread.sleep(600)
    }

    private fun harness(scene: String, dark: Boolean): Harness = Harness(scene).also { it.prefs.chooseTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) }

    @Test fun miniPlayerLight() = mini(false)
    @Test fun miniPlayerDark() = mini(true)
    @Test fun reviewLight() = review(false)
    @Test fun reviewDark() = review(true)
    @Test fun checkNowLight() = detail(false)
    @Test fun checkNowDark() = detail(true)
    @Test fun addSheetLight() = addSheet(false)
    @Test fun addSheetDark() = addSheet(true)

    private fun mini(dark: Boolean) {
        val mode = if (dark) "dark" else "light"
        val h = harness("library", dark)
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.onNodeWithTag(UiTags.spaceCard("music")).performClick()
        rule.waitForTag(UiTags.libItem("content://media/external/audio/media/102"))
        rule.onNodeWithTag(UiTags.libItem("content://media/external/audio/media/102")).performClick()
        rule.mainClock.autoAdvance = false
        repeat(40) { if (!rule.exists(UiTags.MINI_PLAYER)) { rule.mainClock.advanceTimeBy(250); Thread.sleep(50) } }
        rule.mainClock.advanceTimeBy(1_000)
        shot("mini-$mode")
    }

    private fun review(dark: Boolean) {
        val mode = if (dark) "dark" else "light"
        val h = harness("empty", dark)
        h.show(rule)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput("https://www.youtube.com/playlist?list=PLblender")
        rule.waitForTag(UiTags.COLLECTION)
        rule.waitUntil(10_000) { h.vm.collection?.entries?.size == 60 && h.vm.collection?.discovering == false }
        rule.waitUntil(5_000) { h.vm.collection?.selectedCount == 60 }
        shot("review-off-$mode")
        rule.onNodeWithTag(UiTags.SYNC_SWITCH).performClick()
        rule.waitForTag(UiTags.SYNC_OPTIONS)
        rule.onNodeWithTag(UiTags.SYNC_WIFI).performScrollTo()
        shot("review-on-$mode")
    }

    private fun detail(dark: Boolean) {
        val mode = if (dark) "dark" else "light"
        val h = harness("spaces-v2", dark)
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(LibraryRoute.Space("workout"))
        h.vm.openLibraryDetail(LibraryRoute.Collection("workout", "gym"))
        h.show(rule)
        rule.waitForTag(UiTags.COLLECTION_DETAIL)
        h.engine.fakeLibrary.nextCheckFound = 3
        rule.onNodeWithTag(UiTags.LIB_LIST).performScrollToNode(hasTestTag(UiTags.FOLLOW_CARD))
        rule.onNodeWithTag(UiTags.FOLLOW_CHECK).performClick()
        rule.waitUntil(8_000) { rule.hasText("Found 3 new songs in Gym Mix") }
        shot("check-now-$mode")
        fun job(id: String, title: String, status: JobStatus, percent: Float) = Fixtures.job(id, title, null, status, percent = percent, ageMinutes = 1).copy(collectionId = "gym")
        h.engine.setJobs(listOf(job("n1", "Night Drive", JobStatus.COMPLETED, 100f), job("n2", "Sunrise Run", JobStatus.DOWNLOADING, 58f), job("n3", "Cooldown", JobStatus.QUEUED, 0f)))
        rule.waitForTag(UiTags.FOLLOW_PROGRESS)
        shot("progress-$mode")
    }

    private fun addSheet(dark: Boolean) {
        val mode = if (dark) "dark" else "light"
        val h = harness("spaces-v2", dark)
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(LibraryRoute.Space("workout"))
        h.show(rule)
        rule.waitForTag(UiTags.LIB_OVERFLOW)
        rule.onNodeWithTag(UiTags.LIB_OVERFLOW).performClick()
        rule.onNodeWithTag(UiTags.LIB_ADD_FILES).performClick()
        rule.waitForTag(UiTags.ADD_SHEET)
        shot("add-sheet-$mode")
        rule.onNodeWithTag(UiTags.ADD_LINK).performClick()
        rule.waitForTag(UiTags.LINK_SHEET)
        shot("link-sheet-$mode")
    }
}
