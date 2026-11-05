package app.keepfin.ui.downloads

import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.FormatRequest
import app.keepfin.InspectResult
import app.keepfin.ui.Capture
import app.keepfin.ui.Harness
import app.keepfin.ui.StudioEngine
import app.keepfin.ui.UiTags
import app.keepfin.ui.closeExtras
import app.keepfin.ui.exists
import app.keepfin.ui.formatOffers
import app.keepfin.ui.formatShown
import app.keepfin.ui.hasText
import app.keepfin.ui.openExtras
import app.keepfin.ui.preview.Fixtures
import app.keepfin.ui.waitForTag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The download preview (dev-docs/plans/DOWNLOAD-PREVIEW.md): one full-height sheet over Downloads, built the same way in every case. One test or
 * more for each case of the plan's table; the song's video and Keep in sync have their own suites.
 */
@RunWith(AndroidJUnit4::class)
class DownloadScreensUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private val video = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"
    private val song = "https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val screenHeight get() = context.resources.displayMetrics.heightPixels.toFloat()

    private fun openSheet(link: String = video, fontScale: Float? = null): Harness {
        val h = Harness("empty")
        h.engine.inspectResults[song] = InspectResult.Media(Fixtures.spotifyTrack(song))
        h.show(rule, fontScale = fontScale)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput(link)
        rule.waitForTag(UiTags.REVIEW)
        rule.waitUntil(5_000) { rule.exists(UiTags.DOWNLOAD) }
        settle()
        return h
    }

    /** The sheet animates in; wait until the artwork stops moving. */
    private fun settle() {
        var last = -1f
        rule.waitUntil(8_000) {
            rule.waitForIdle()
            val top = rule.onNodeWithTag(UiTags.REVIEW_ARTWORK).fetchSemanticsNode().boundsInRoot.top
            val steady = top == last
            last = top
            steady
        }
    }

    private fun artworkBounds() = rule.onNodeWithTag(UiTags.REVIEW_ARTWORK).fetchSemanticsNode().boundsInRoot

    // ---- One video ----------------------------------------------------------------------------------------------

    @Test fun aVideoOpensAFullHeightSheetWithHeaderTabsSizesAndDownload() {
        val h = openSheet()
        val art = artworkBounds()
        assertTrue("full height from the start: the cover is near the top, at ${art.top} of $screenHeight", art.top < screenHeight * 0.2f)
        assertEquals("a video's artwork is 16:9", 16f / 9f, art.width / art.height, 0.05f)
        rule.onNodeWithTag(UiTags.REVIEW_CLOSE).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.PREVIEW_TAB_MAIN).assertIsSelected()
        rule.onNodeWithText("What to save").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.PREVIEW_TAB_EXTRAS).assertIsDisplayed()
        rule.onNodeWithText("Extras").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.TAB_VIDEO).assertIsSelected()
        // one list, no fold: the Format row and the first size are on the tab
        rule.onNodeWithTag(UiTags.container("mp4")).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.quality((h.vm.capture as Capture.Single).media.video.maxByOrNull { it.height }!!.id)).assertIsDisplayed()
        assertFalse(rule.hasText("More sizes"))
        rule.onNodeWithTag(UiTags.DOWNLOAD).assertTextContains("Download · ", substring = true)
        // everything else is behind the one list and Extras
        rule.onNodeWithTag(UiTags.reviewSection("cut")).assertDoesNotExist()
        assertFalse("nothing is inserted into the Downloads list", rule.exists(UiTags.REVIEW_SUMMARY))
    }

    @Test fun webMIsOfferedOnlyForVp9AndAv1AndAQualityThatCannotUseItReturnsToMp4() {
        openSheet()
        rule.onNodeWithTag(UiTags.quality("v2160")).performScrollTo().performClick()
        rule.onNodeWithTag(UiTags.container("webm")).assertIsEnabled().performClick()
        rule.onNodeWithTag(UiTags.container("webm")).assertIsSelected()
        assertFalse("nothing is greyed out for VP9", rule.exists(UiTags.CONTAINER_REASON))
        rule.onNodeWithTag(UiTags.quality("v1080")).performScrollTo().performClick()
        rule.onNodeWithTag(UiTags.container("mp4")).assertIsSelected()
        rule.onNodeWithTag(UiTags.container("webm")).assertIsNotEnabled()
        rule.onNodeWithTag(UiTags.CONTAINER_REASON).assertTextContains("WebM needs VP9 or AV1 video; this quality is H.264.")
    }

    @Test fun everySizeIsReachableFromTheListAndExtrasHoldTheRest() {
        val h = openSheet()
        val media = (h.vm.capture as Capture.Single).media
        for (option in media.video) rule.onNodeWithTag(UiTags.quality(option.id)).performScrollTo().assertExists()
        rule.openExtras()
        for (name in listOf("subtitles", "cut", "sponsor", "file", "beside")) rule.onNodeWithTag(UiTags.reviewSection(name)).assertExists()
        for (group in listOf("Subtitles", "Cut a part", "Sponsor parts", "What goes in the file", "Also save beside the file")) rule.onNodeWithText(group).assertExists()
        rule.onNodeWithText("Keep").assertExists(); rule.onNodeWithText("Mark").assertExists(); rule.onNodeWithText("Remove").assertExists()
        rule.onNodeWithText("Cover").assertExists(); rule.onNodeWithText("Tags").assertExists(); rule.onNodeWithText("Chapters").assertExists()
        rule.onNodeWithText("Cover image").assertExists()
        rule.onNodeWithTag(UiTags.EXTRAS_DEFAULT).performScrollTo().assertExists()
        rule.onNodeWithTag(UiTags.DOWNLOAD).assertIsDisplayed()
    }

    @Test fun extrasCountsWhatWasChangedAndKeepInSyncIsNeverInThem() {
        openSheet()
        rule.onNodeWithText("Extras").assertExists()
        rule.openExtras()
        rule.onNodeWithText("Mark").performScrollTo().performClick()
        rule.onNodeWithText("Extras · 1").assertExists()
        rule.onNodeWithText("Cover").performScrollTo().performClick()
        rule.onNodeWithText("Extras · 2").assertExists()
        // a change back to the default is not a change
        rule.onNodeWithText("Keep").performScrollTo().performClick()
        rule.onNodeWithText("Extras · 1").assertExists()
        assertFalse(rule.exists(UiTags.SYNC_ROW))
    }

    @Test fun downloadStartsTheJobWithTheChosenSizeAndExtras() {
        val h = openSheet()
        rule.onNodeWithTag(UiTags.quality("v720")).performScrollTo().performClick()
        rule.openExtras()
        rule.onNodeWithText("Remove").performScrollTo().performClick()
        rule.closeExtras()
        rule.onNodeWithTag(UiTags.DOWNLOAD).performClick()
        rule.waitUntil(5_000) { h.engine.enqueued.size == 1 }
        val (media, request, options) = h.engine.enqueued.single()
        assertEquals(video, media.url)
        assertEquals(720, (request as FormatRequest.Video).maxHeight)
        assertEquals("remove", options.sponsorBlock)
        rule.waitUntil(5_000) { !rule.exists(UiTags.REVIEW) }
        assertFalse("a started download leaves no review behind", rule.exists(UiTags.REVIEW_SUMMARY))
    }

    @Test fun extrasAsMyDefaultSavesThem() {
        val h = openSheet()
        rule.openExtras()
        rule.onNodeWithText("Mark").performScrollTo().performClick()
        rule.onNodeWithTag(UiTags.EXTRAS_DEFAULT).performScrollTo().performClick()
        rule.waitUntil(5_000) { h.engine.settings.value.options.sponsorBlock == "mark" }
        rule.onNodeWithText("Extras").assertExists()
    }

    // ---- Closing ------------------------------------------------------------------------------------------------

    @Test fun closeKeepsTheReviewAndItReopensWithTheSameChoices() {
        val h = openSheet()
        rule.onNodeWithTag(UiTags.quality("v720")).performScrollTo().performClick()
        val before = h.vm.choice
        rule.onNodeWithTag(UiTags.REVIEW_CLOSE).performClick()
        rule.waitUntil(8_000) { !rule.exists(UiTags.REVIEW) }
        assertTrue("the review is kept", h.vm.capture is Capture.Single)
        assertTrue("the link stays in the box", h.vm.draft.isNotBlank())
        rule.waitForTag(UiTags.REVIEW_SUMMARY)
        rule.onNodeWithTag(UiTags.REVIEW_REOPEN).performClick()
        rule.waitForTag(UiTags.REVIEW)
        assertEquals("same choices", before, h.vm.choice)
        assertTrue(h.engine.enqueued.isEmpty())
    }

    @Test fun swipingTheSheetDownClosesItWithoutDiscardingTheReview() {
        val h = openSheet()
        rule.onNodeWithTag(UiTags.REVIEW_ARTWORK).performTouchInput { swipeDown(startY = centerY, endY = centerY + screenHeight * 0.6f, durationMillis = 300) }
        rule.waitUntil(8_000) { !rule.exists(UiTags.REVIEW) }
        assertTrue(h.vm.capture is Capture.Single)
        rule.waitForTag(UiTags.REVIEW_SUMMARY)
    }

    @Test fun backKeepsTheReviewToo() {
        val h = openSheet()
        Espresso.pressBack()
        rule.waitUntil(8_000) { !rule.exists(UiTags.REVIEW) }
        assertTrue(h.vm.capture is Capture.Single)
        rule.waitForTag(UiTags.REVIEW_SUMMARY)
    }

    @Test fun clearDiscardsTheReview() {
        val h = openSheet()
        rule.onNodeWithTag(UiTags.REVIEW_CLOSE).performClick()
        rule.waitForTag(UiTags.REVIEW_SUMMARY)
        rule.onNodeWithContentDescription("Discard this preview").performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.REVIEW_SUMMARY) }
        assertTrue(h.vm.capture is Capture.Empty)
        assertEquals("", h.vm.draft)
    }

    // ---- A song -------------------------------------------------------------------------------------------------

    @Test fun aSongHasSquareArtworkAndOpensOnAudioWithTheFormatRowAndItsQualities() {
        val h = openSheet(song)
        val art = artworkBounds()
        assertEquals("a song's cover is square", 1f, art.width / art.height, 0.05f)
        rule.onNodeWithTag(UiTags.TAB_AUDIO).assertIsSelected()
        for (target in listOf("mp3", "m4a", "opus", "flac", "wav")) rule.onNodeWithTag(UiTags.audioTarget(target)).performScrollTo().assertExists()
        // MP3 is open: every quality is one row with a size, 320 is the default and selected
        for (quality in listOf("best", "320", "256", "192", "128")) {
            val text = rule.onNodeWithTag(UiTags.audioQuality(quality)).performScrollTo().fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString(" ") { it.text }
            assertTrue("$quality names its size: $text", text.contains("MB") || text.contains("size unknown"))
        }
        rule.onNodeWithTag(UiTags.audioQuality("320")).assertIsSelected()
        rule.onNodeWithTag(UiTags.audioTarget("flac")).performClick()
        assertFalse("FLAC has no list of qualities", rule.exists(UiTags.audioQuality("320")))
        assertTrue(h.vm.choice.audioMode)
    }

    @Test fun aYouTubeMusicLinkOpensOnAudio() {
        openSheet("https://music.youtube.com/watch?v=aqz-KE-bpKQ")
        rule.onNodeWithTag(UiTags.TAB_AUDIO).assertIsSelected()
    }

    @Test fun aSongYouAlreadyHaveSaysSoOffersPlayAndDownloadAnyway() {
        val h = Harness("empty")
        h.engine.fakeLibrary.existingTitles = setOf("Sintel Theme")
        h.engine.inspectResults[song] = InspectResult.Media(Fixtures.spotifyTrack(song).copy(title = "Sintel Theme"))
        h.show(rule)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput(song)
        rule.waitForTag(UiTags.CAPTURE_EXISTING)
        rule.onNodeWithText("Already in Music").assertExists()
        rule.onNodeWithTag(UiTags.CAPTURE_PLAY_EXISTING).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.DOWNLOAD).assertTextContains("Download anyway", substring = true)
    }

    // ---- A link that cannot be read, or is being read ------------------------------------------------------------

    @Test fun aLinkThatCouldNotBeReadSaysWhyInTheSameSheetWithTryAgain() {
        val h = Harness("empty")
        h.engine.inspectFailure = "This video is private. Sign in to see it."
        h.show(rule)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput(video)
        rule.waitForTag(UiTags.CAPTURE_ERROR)
        rule.onNodeWithText("Couldn't read this link").assertIsDisplayed()
        rule.onNodeWithText("Try again").assertIsDisplayed()
        rule.onNodeWithText("Sign in").assertIsDisplayed()
        assertFalse(rule.exists(UiTags.DOWNLOAD))
        h.engine.inspectFailure = null
        rule.onNodeWithText("Try again").performClick()
        rule.waitForTag(UiTags.DOWNLOAD)
    }

    // ---- Several links ------------------------------------------------------------------------------------------

    @Test fun severalLinksShareOneSheetWithOneQualityAndDownloadAll() {
        val h = Harness("empty")
        h.engine.inspectResults[song] = InspectResult.Media(Fixtures.spotifyTrack(song))
        h.show(rule)
        rule.runOnIdle { h.vm.loadLink("$video\n$song") }
        rule.waitUntil(10_000) { (h.vm.capture as? Capture.Batch)?.running == false }
        rule.waitForTag(UiTags.REVIEW)
        rule.onNodeWithText("Links · 2").assertIsDisplayed()
        rule.onNodeWithText("2 links").assertExists()
        rule.onNodeWithTag(UiTags.PREVIEW_TAB_EXTRAS).assertIsDisplayed()
        rule.onNodeWithText("Quality for all").assertExists()
        rule.onNodeWithText("Dismiss all").assertExists()
        assertFalse("several links have no Both", rule.formatOffers("Both"))
        assertEquals("Video", rule.formatShown())
        rule.onNodeWithTag(UiTags.DOWNLOAD).assertTextContains("Download 2 links")
        rule.onNodeWithTag(UiTags.DOWNLOAD).performClick()
        rule.waitUntil(8_000) { h.engine.enqueued.size == 2 }
    }

    // ---- A playlist, album or channel ---------------------------------------------------------------------------

    private fun openCollection(link: String, size: Int = 60, fontScale: Float? = null): Harness {
        val h = Harness("empty")
        h.engine.collectionSize = size
        h.engine.discoverEntryDelayMs = 0
        h.show(rule, fontScale = fontScale)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput(link)
        rule.waitForTag(UiTags.COLLECTION)
        rule.waitUntil(15_000) { h.vm.collection?.discovering == false && (h.vm.collection?.entries?.size ?: 0) > 0 }
        return h
    }

    @Test fun videoRowsHaveSixteenByNineArtworkAndTheFooterShowsTheCount() {
        val h = openCollection("https://www.youtube.com/playlist?list=PLblender")
        val first = Fixtures.entry(1).url
        rule.waitForTag(UiTags.entryArtwork(first))
        val art = rule.onNodeWithTag(UiTags.entryArtwork(first), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(16f / 9f, art.width / art.height, 0.05f)
        rule.onNodeWithTag(UiTags.COLLECTION_HEADER).assertIsDisplayed()
        assertTrue("the header counts the videos", rule.hasText("60 videos"))
        rule.onNodeWithText("Videos · 60").assertIsDisplayed()
        assertEquals("Video", rule.formatShown())
        rule.onNodeWithText("Quality for all", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(UiTags.COLLECTION_DOWNLOAD).assertTextContains("Download 60", substring = true)
        assertEquals(60, h.vm.collection!!.selectedCount)
    }

    @Test fun songRowsHaveSquareArtworkAndOpenOnAudio() {
        openCollection("https://music.youtube.com/playlist?list=PLblender")
        val first = Fixtures.entry(1).url
        rule.waitForTag(UiTags.entryArtwork(first))
        val art = rule.onNodeWithTag(UiTags.entryArtwork(first), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("square cover", 1f, art.width / art.height, 0.05f)
        assertTrue("the header counts the songs", rule.hasText("60 songs"))
        rule.onNodeWithText("Songs · 60").assertIsDisplayed()
        assertEquals("Audio", rule.formatShown())
    }

    @Test fun formatAndQualityForAllAreTwoDropdownsOnOneLineAndThePlaylistChipIsInTheFooter() {
        val h = openCollection("https://www.youtube.com/playlist?list=PLblender")
        val format = rule.onNodeWithTag(UiTags.FORMAT_FOR_ALL).fetchSemanticsNode().boundsInRoot
        val quality = rule.onNodeWithTag("collection-preset").fetchSemanticsNode().boundsInRoot
        assertEquals("one line", format.top, quality.top, 4f)
        assertTrue("two compact dropdowns, not rows of chips", format.height <= 80f * context.resources.displayMetrics.density)
        rule.onNodeWithTag(UiTags.ADD_TO_PLAYLIST).assertIsDisplayed()
        assertEquals(60, h.vm.collection!!.selectedCount)
    }

    @Test fun inTheQueueIsAnExtraAndTheCountFollowsIt() {
        openCollection("https://music.youtube.com/playlist?list=PLblender")
        rule.openExtras()
        rule.onNodeWithText("In the queue").assertExists()
        rule.onNodeWithTag(UiTags.COLLECTION_KEEP_TOGETHER).assertExists()
        rule.onNodeWithTag(UiTags.COLLECTION_SEPARATE).performClick()
        rule.onNodeWithText("Extras · 1").assertExists()
        rule.closeExtras()
    }

    @Test fun selectAllSaysItCoversOnlyWhatIsLoadedAndNeverExtendsSilently() {
        val h = openCollection("https://www.youtube.com/playlist?list=PLblender", size = 450)
        rule.waitUntil(8_000) { h.vm.collection?.truncated == true }
        assertEquals(StudioViewModel_PAGE, h.vm.collection!!.entries.size)
        rule.waitForTag(UiTags.COLLECTION_SCOPE)
        assertTrue(rule.hasText("200 loaded"))
        rule.onNodeWithTag(UiTags.COLLECTION_SELECT_NONE).performClick()
        rule.waitForTag(UiTags.COLLECTION_SELECT_ALL)
        rule.onNodeWithTag(UiTags.COLLECTION_SELECT_ALL).assertTextContains("Select all 200 loaded", substring = true)
        rule.onNodeWithTag(UiTags.COLLECTION_SELECT_ALL).performClick()
        rule.waitUntil(5_000) { h.vm.collection?.selectedCount == StudioViewModel_PAGE }
        assertEquals("nothing past the loaded page is selected", StudioViewModel_PAGE, h.vm.collection!!.selectedCount)
        rule.onNodeWithTag(UiTags.COLLECTION_DOWNLOAD).assertTextContains("Download 200", substring = true)
    }

    @Test fun closingTheCollectionKeepsItAndASummaryReopensIt() {
        val h = openCollection("https://www.youtube.com/playlist?list=PLblender")
        rule.onNodeWithTag(UiTags.REVIEW_CLOSE).performClick()
        rule.waitUntil(8_000) { !rule.exists(UiTags.COLLECTION) }
        rule.waitForTag(UiTags.COLLECTION_RESUME)
        assertNotNull(h.vm.collection)
        rule.onNodeWithText("Review").performClick()
        rule.waitForTag(UiTags.COLLECTION)
    }

    // ---- Sizes and the bottom -----------------------------------------------------------------------------------

    private fun assertAboveTheNavigationBar(tag: String) {
        val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
        val bottom = node.positionOnScreen.y + node.size.height
        val metrics = context.getSystemService(WindowManager::class.java).currentWindowMetrics
        val limit = metrics.bounds.height() - metrics.windowInsets.getInsets(WindowInsets.Type.navigationBars()).bottom
        assertTrue("Download ends at $bottom, past $limit", bottom <= limit)
    }

    @Test fun downloadStaysAboveTheNavigationBarForALink() {
        openSheet()
        assertAboveTheNavigationBar(UiTags.DOWNLOAD)
    }

    @Test fun downloadStaysAboveTheNavigationBarForAList() {
        openCollection("https://www.youtube.com/playlist?list=PLblender")
        assertAboveTheNavigationBar(UiTags.COLLECTION_DOWNLOAD)
    }

    @Test fun saveToShowsWhereItSaves() {
        openSheet()
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNode(androidx.compose.ui.test.hasText("Save to:", substring = true), useUnmergedTree = true).fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        results.single().let { org.junit.Assert.assertFalse("the name of the space is cut off", it.didOverflowHeight || it.isLineEllipsized(0)) }
    }

    @Test fun theBottomStaysReachableAtALargeFontScale() {
        openSheet(fontScale = 2f)
        rule.onNodeWithTag(UiTags.DOWNLOAD).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.REVIEW_CLOSE).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.PREVIEW_TAB_EXTRAS).assertIsDisplayed()
    }

    private val StudioViewModel_PAGE get() = app.keepfin.ui.StudioViewModel.COLLECTION_PAGE
}
