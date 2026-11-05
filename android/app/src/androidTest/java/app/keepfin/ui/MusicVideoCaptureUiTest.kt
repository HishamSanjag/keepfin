package app.keepfin.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.core.spaces.PreviewWords
import app.keepfin.MusicVideoLookup
import app.keepfin.MusicVideoMatch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Also keep the music video" in Capture (dev-docs/plans/MUSIC-VIDEOS.md): a song kept as audio looks for its official video in the background and offers an
 * unchecked box with what it found; no video, or a look that failed, shows nothing and never holds up the download.
 */
@RunWith(AndroidJUnit4::class)
class MusicVideoCaptureUiTest {
    @get:Rule val rule = createComposeRule()

    private val spotify = "https://open.spotify.com/track/agent327"
    private val match = MusicVideoMatch("dQw4w9WgXcQ", "Agent 327 (Official Video)", "Blender Studio", 3_200_000_000L, 215.0)

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private fun inspectSong(lookup: MusicVideoLookup, delayMs: Long = 0, link: String = spotify): Harness {
        val h = Harness("library")
        h.engine.musicVideoLookup = lookup
        h.engine.musicVideoDelayMs = delayMs
        h.show(rule)
        rule.runOnIdle { h.vm.navigate(Destination.DOWNLOADS); h.vm.loadLink(link) }
        rule.waitForTag(UiTags.REVIEW)
        return h
    }

    private fun waitForLook(h: Harness) {
        rule.waitUntil(8_000) { h.engine.musicVideoLookups.isNotEmpty() }
        rule.waitUntil(8_000) { !rule.exists(UiTags.CAPTURE_VIDEO_LOOKING) }
    }

    @Test fun aFoundVideoMakesBothAvailableAfterAQuietLookingLine() {
        val h = inspectSong(MusicVideoLookup.Found(match), delayMs = 1_200)
        rule.waitForTag(UiTags.CAPTURE_VIDEO_LOOKING)
        rule.onNodeWithText("Looking for the music video…").assertExists()
        rule.onNodeWithTag(UiTags.TAB_BOTH).assertIsNotEnabled()
        assertFalse(rule.exists(UiTags.CAPTURE_VIDEO))
        waitForLook(h)
        rule.onNodeWithTag(UiTags.TAB_BOTH).assertIsEnabled().assertIsNotSelected()
        rule.onNodeWithTag(UiTags.TAB_AUDIO).assertIsSelected()
        // Both shows the found video as a small card: picture, title, channel, views and length
        rule.onNodeWithTag(UiTags.TAB_BOTH).performClick()
        rule.onNodeWithTag(UiTags.CAPTURE_VIDEO).assertIsDisplayed()
        rule.onNodeWithText("Agent 327 (Official Video)").assertExists()
        rule.onNodeWithTag(UiTags.CAPTURE_VIDEO_DETAILS, useUnmergedTree = true).assertTextContains("Blender Studio · 3.2B views · 3:35")
        rule.onNodeWithTag(UiTags.CAPTURE_LINK_VIDEO).assertTextEquals(PreviewWords.BOTH_FOUND)
        // the look used the inspected artist and track title, once
        assertEquals(listOf(listOf("Blender Studio") to "Agent 327 – Theme"), h.engine.musicVideoLookups.toList())
        assertTrue("nothing is queued by looking", h.engine.capturedWithVideo.isEmpty() && h.engine.enqueued.isEmpty())
    }

    @Test fun downloadingOnBothQueuesTheSongWithItsVideo() {
        val h = inspectSong(MusicVideoLookup.Found(match))
        waitForLook(h)
        rule.onNodeWithTag(UiTags.TAB_BOTH).performClick()
        rule.onNodeWithTag(UiTags.TAB_BOTH).assertIsSelected()
        rule.onNodeWithTag(UiTags.DOWNLOAD).performClick()
        rule.waitUntil(8_000) { h.engine.enqueued.isNotEmpty() }
        assertEquals(1, h.engine.enqueued.size)
        assertEquals(listOf(spotify to match), h.engine.capturedWithVideo.toList())
    }

    @Test fun downloadingOnAudioQueuesOnlyTheSong() {
        val h = inspectSong(MusicVideoLookup.Found(match))
        waitForLook(h)
        rule.onNodeWithTag(UiTags.DOWNLOAD).performClick()
        rule.waitUntil(8_000) { h.engine.enqueued.isNotEmpty() }
        assertEquals(1, h.engine.enqueued.size)
        assertTrue(h.engine.capturedWithVideo.isEmpty())
    }

    @Test fun noOfficialVideoTurnsBothOffWithItsReasonAndDownloadStillWorks() {
        val h = inspectSong(MusicVideoLookup.None)
        waitForLook(h)
        assertFalse(rule.exists(UiTags.CAPTURE_VIDEO))
        rule.onNodeWithTag(UiTags.TAB_BOTH).assertIsNotEnabled()
        rule.onNodeWithTag(UiTags.BOTH_OFF_REASON).assertTextEquals(PreviewWords.BOTH_NONE)
        rule.onNodeWithTag(UiTags.DOWNLOAD).performClick()
        rule.waitUntil(8_000) { h.engine.enqueued.isNotEmpty() }
        assertTrue(h.engine.capturedWithVideo.isEmpty())
    }

    @Test fun aLookThatFailedOnlyTurnsBothOffAndNeverBlocksTheDownload() {
        val h = inspectSong(MusicVideoLookup.Failed("Couldn't look for the music video. Check your connection and try again."))
        waitForLook(h)
        assertFalse(rule.exists(UiTags.CAPTURE_VIDEO))
        rule.onNodeWithTag(UiTags.TAB_BOTH).assertIsNotEnabled()
        rule.onNodeWithText("Couldn't look for the music video. Check your connection and try again.").assertDoesNotExist()
        rule.onNodeWithTag(UiTags.DOWNLOAD).performClick()
        rule.waitUntil(8_000) { h.engine.enqueued.isNotEmpty() }
    }

    @Test fun aVideoSavedToAMusicSpaceSaysItIsASongYouCanAlsoWatch() {
        val h = inspectSong(MusicVideoLookup.None, link = "https://www.youtube.com/watch?v=aqz-KE-bpKQ")
        assertFalse("a video saved to a video space is just a video", rule.exists(UiTags.KEPT_AS_SONG))
        rule.runOnIdle { h.vm.chooseSaveTo("music") }
        rule.waitForTag(UiTags.KEPT_AS_SONG)
        rule.onNodeWithTag(UiTags.KEPT_AS_SONG, useUnmergedTree = true).assertTextContains("as a song you can also watch", substring = true)
        // as audio it is a song file: the line goes
        rule.onNodeWithTag(UiTags.TAB_AUDIO).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.KEPT_AS_SONG) }
    }

    @Test fun aPlainVideoIsNotASongSoNothingIsLookedFor() {
        val h = inspectSong(MusicVideoLookup.Found(match), link = "https://www.youtube.com/watch?v=aqz-KE-bpKQ")
        Thread.sleep(500)
        rule.waitForIdle()
        assertTrue(h.engine.musicVideoLookups.isEmpty())
        assertFalse(rule.exists(UiTags.CAPTURE_VIDEO) || rule.exists(UiTags.CAPTURE_VIDEO_LOOKING))
    }
}
