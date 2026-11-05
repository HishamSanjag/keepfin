package app.keepfin.ui

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.DownloadPreset
import app.keepfin.MusicVideoLookup
import app.keepfin.MusicVideoMatch
import app.keepfin.ui.settings.SettingsCategory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Also keep music videos where there is one" in a music collection's review, and the setting "Keep music videos automatically" that starts it (and
 * the Capture box) checked (dev-docs/plans/MUSIC-VIDEOS.md, phase 5). Nothing downloads by itself: the boxes only carry the person's choice.
 */
@RunWith(AndroidJUnit4::class)
class CollectionMusicVideosUiTest {
    @get:Rule val rule = createComposeRule()

    private val album = "https://music.youtube.com/playlist?list=OLAKblender"
    private val match = MusicVideoMatch("dQw4w9WgXcQ", "Agent 327 (Official Video)", "Blender Studio", 3_200_000_000L, 215.0)

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private fun openReview(h: Harness, link: String = album) {
        h.show(rule)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput(link)
        rule.waitForTag(UiTags.COLLECTION)
        rule.waitUntil(10_000) { h.vm.collection?.entries?.size == 60 && h.vm.collection?.discovering == false }
        rule.waitUntil(5_000) { h.vm.collection?.selectedCount == 60 }
    }

    @Test fun aMusicCollectionKeptAsAudioOffersBothUnchosenAndExplainsItOnceChosen() {
        val h = Harness("library")
        openReview(h)
        assertEquals("Audio", rule.formatShown())
        assertTrue(rule.formatOffers("Both"))
        assertFalse(rule.exists(UiTags.COLLECTION_VIDEO_NOTE))
        rule.chooseFormat("Both")
        rule.onNodeWithTag(UiTags.COLLECTION_VIDEO_NOTE, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Music videos are kept where there is one. They are large, about 50 to 100 MB each.").assertExists()
        rule.chooseFormat("Audio")
        rule.onNodeWithTag(UiTags.COLLECTION_DOWNLOAD).performClick()
        rule.waitUntil(5_000) { h.engine.enqueuedFindVideo.size == 1 }
        assertEquals("unchecked marks no song", listOf(false), h.engine.enqueuedFindVideo.toList())
    }

    @Test fun choosingBothMarksTheSongs() {
        val h = Harness("library")
        openReview(h)
        rule.chooseFormat("Both")
        assertEquals("Both", rule.formatShown())
        rule.onNodeWithTag(UiTags.COLLECTION_DOWNLOAD).performClick()
        rule.waitUntil(5_000) { h.engine.enqueuedFindVideo.size == 1 }
        assertEquals(listOf(true), h.engine.enqueuedFindVideo.toList())
    }

    @Test fun aVideoQualityIsNotBothAndSendsNoMark() {
        val h = Harness("library")
        openReview(h)
        rule.runOnIdle { h.vm.collection!!.findVideo = true; h.vm.collection!!.preset = DownloadPreset.BEST }
        assertEquals("Video", rule.formatShown())
        rule.onNodeWithTag(UiTags.COLLECTION_DOWNLOAD).performClick()
        rule.waitUntil(5_000) { h.engine.enqueuedFindVideo.size == 1 }
        assertEquals(listOf(false), h.engine.enqueuedFindVideo.toList())
    }

    @Test fun anOrdinaryPlaylistDoesNotOfferIt() {
        val h = Harness("library")
        openReview(h, "https://www.youtube.com/playlist?list=PLblender")
        assertFalse(rule.formatOffers("Both"))
    }

    @Test fun withTheLibraryOffThereIsNoBox() {
        val h = Harness("library")
        h.libraryOff()
        openReview(h)
        assertFalse(rule.formatOffers("Both"))
    }

    @Test fun theSettingStartsTheCollectionOnBothAndTheSongsAreMarkedOnlyWhenDownloadIsPressed() {
        val h = Harness("library")
        h.engine.updateSettings { it.copy(keepMusicVideos = true) }
        openReview(h)
        assertTrue("it never starts a download by itself", h.engine.enqueuedFindVideo.isEmpty() && h.engine.enqueuedEntries.isEmpty())
        assertEquals("Both", rule.formatShown())
        // still the person's choice for this download
        rule.chooseFormat("Audio")
        assertEquals("Audio", rule.formatShown())
        rule.onNodeWithTag(UiTags.COLLECTION_DOWNLOAD).performClick()
        rule.waitUntil(5_000) { h.engine.enqueuedFindVideo.size == 1 }
        assertEquals(listOf(false), h.engine.enqueuedFindVideo.toList())
    }

    @Test fun theSettingStartsASongOnBoth() {
        val h = Harness("library")
        h.engine.updateSettings { it.copy(keepMusicVideos = true) }
        h.engine.musicVideoLookup = MusicVideoLookup.Found(match)
        h.show(rule)
        rule.runOnIdle { h.vm.navigate(Destination.DOWNLOADS); h.vm.loadLink("https://open.spotify.com/track/agent327") }
        rule.waitForTag(UiTags.CAPTURE_VIDEO)
        rule.onNodeWithTag(UiTags.TAB_BOTH).assertIsSelected()
        assertTrue("nothing is queued by the setting", h.engine.enqueued.isEmpty() && h.engine.capturedWithVideo.isEmpty())
        rule.onNodeWithTag(UiTags.DOWNLOAD).performClick()
        rule.waitUntil(8_000) { h.engine.enqueued.isNotEmpty() }
        assertEquals(1, h.engine.capturedWithVideo.size)
    }

    @Test fun theSettingIsOffByDefaultAndTurnsOnFromSettings() {
        val h = Harness("library")
        assertFalse(h.engine.settings.value.keepMusicVideos)
        h.openSettings(SettingsCategory.DOWNLOADS)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_LIST)
        rule.onNodeWithTag(UiTags.SETTINGS_LIST).performScrollToNode(hasTestTag(UiTags.KEEP_VIDEOS_SWITCH))
        rule.onNodeWithText("Keep music videos automatically").assertExists()
        rule.onNodeWithText("Songs you download as audio also get their official music video, where there is one. Videos are large.").assertExists()
        rule.onNodeWithTag(UiTags.KEEP_VIDEOS_SWITCH).assertIsOff().performClick()
        rule.waitUntil(5_000) { h.engine.settings.value.keepMusicVideos }
        rule.onNodeWithTag(UiTags.KEEP_VIDEOS_SWITCH).assertIsOn()
    }
}
