package app.keepfin.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.LibraryItem
import app.keepfin.allItems
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Video chapters (menu list, seek, ticks on the bar) and Take screenshot in the phone's video player. */
@RunWith(AndroidJUnit4::class)
class VideoChaptersUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private fun video(h: Harness, n: Int): LibraryItem = h.engine.fakeLibrary.allItems().first { it.id.endsWith("/video/media/$n") }

    private fun openVideo(h: Harness, n: Int) {
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        val item = video(h, n)
        rule.runOnIdle { h.vm.playVideo(item, startOver = true) }
        rule.waitForTag(UiTags.VIDEO_PLAYER)
        rule.waitForTag(UiTags.VIDEO_MORE)
    }

    private fun openOptions() {
        rule.onNodeWithTag(UiTags.VIDEO_MORE).performClick()
        rule.waitForTag("video-more-sheet")
    }

    private fun openChapters() {
        rule.waitForTag(UiTags.VIDEO_CHAPTER_BUTTON)
        rule.onNodeWithTag(UiTags.VIDEO_CHAPTER_BUTTON).performClick()
        rule.waitForTag("chapters-sheet")
    }

    @Test fun chaptersListSeeksAndTheBarHasTicks() {
        val h = Harness("library")
        openVideo(h, 203)
        rule.waitForTag("video-chapter-segments") // the chapters at 2:15 and 6:40 (the one at 0:00 is just the start)
        openChapters() // the chapter title under the bar opens the list; the options sheet no longer carries it
        rule.waitForTag("chapter-0"); rule.waitForTag("chapter-1"); rule.waitForTag("chapter-2")
        assertTrue(rule.hasText("Chapters")); assertTrue(rule.hasText("The bridge")); assertTrue(rule.hasText("2:15"))
        rule.onNodeWithTag("chapter-1").performClick()
        rule.waitUntil(5_000) { !rule.exists("chapters-sheet") }
        val at = h.vm.player.position.value.positionMs
        assertTrue("position $at ms should be at the chapter (135 s)", kotlin.math.abs(at - 135_000) < 1_500)
        // the chapter is now the current one: the menu marks it
        rule.waitForTag(UiTags.VIDEO_MORE)
        openChapters()
        assertTrue(rule.exists("chapter-1"))
        assertFalse(rule.exists("video-more-sheet"))
    }

    @Test fun noChaptersMeansNoChaptersEntryButScreenshotIsThere() {
        val h = Harness("library")
        openVideo(h, 204)
        openOptions()
        rule.waitForTag("video-screenshot")
        assertFalse(rule.exists(UiTags.VIDEO_CHAPTER_BUTTON))
        assertFalse(rule.exists("chapter-0"))
        assertFalse(rule.hasText("Chapters"))
        assertFalse(rule.exists("video-chapter-segments"))
        assertEquals(0, h.vm.lib.chapters(video(h, 204).id).size)
    }

    @Test fun screenshotWithoutAVideoSurfaceSaysSo() {
        // the simulated player draws no video, so there is nothing to copy: the notice must say that and never claim a save
        val h = Harness("library")
        openVideo(h, 204)
        openOptions()
        rule.onNodeWithTag("video-screenshot").performClick()
        rule.waitUntil(8_000) { rule.hasText("Couldn't capture this frame.") }
        assertFalse(rule.hasText("Frame saved"))
    }
}
