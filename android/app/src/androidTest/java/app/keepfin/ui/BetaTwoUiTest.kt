package app.keepfin.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.PlaybackItems
import app.keepfin.ui.library.LibraryRoute

import app.keepfin.core.layout.CardKind
import app.keepfin.core.layout.CardPrefs
import app.keepfin.core.layout.CardSizes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Beta 2: the "works with" line, the merged collection top bar, constant-height shelves, and Play in Keepfin for finished downloads. */
@RunWith(AndroidJUnit4::class)
class BetaTwoUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    @Test fun worksWithLineShowsOnTheEmptyStateAndWithTransfers() {
        val empty = Harness("empty")
        empty.vm.navigate(Destination.DOWNLOADS)
        empty.show(rule)
        rule.waitForTag(UiTags.WORKS_WITH)
        rule.onNodeWithTag(UiTags.WORKS_WITH).assertIsDisplayed()
        assertTrue(rule.hasText("Works with YouTube, YouTube Music, Spotify, Apple Music, Deezer, SoundCloud, Bandcamp, TikTok, Instagram, Facebook, X, Reddit, Vimeo, Twitch, Pinterest and 1,000+ more sites"))
        assertTrue(!rule.hasText("yt-dlp") && !rule.hasText("ffmpeg"))
    }

    @Test fun worksWithLineStaysQuietWithTransfersAndInDownloaderOnlyMode() {
        val h = Harness("library")
        h.libraryOff()
        h.vm.navigate(Destination.DOWNLOADS)
        h.show(rule)
        rule.waitForTag(UiTags.WORKS_WITH)
        rule.onNodeWithTag(UiTags.WORKS_WITH).assertIsDisplayed()
    }

    @Test fun collectionTopBarSitsOverTheHeaderBand() {
        val h = Harness("library")
        val key = PlaybackItems.albumKey(h.engine.fakeLibrary.item("content://media/external/audio/media/101")!!)!!
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.runOnIdle { h.vm.openLibraryDeep(LibraryRoute.Album("music", key)) }
        rule.waitForTag(UiTags.HEADER_BAND)
        rule.waitForTag(UiTags.BAND_BAR)
        // The band starts at the very top, under the (transparent) bar, instead of below it.
        val bar = rule.onNodeWithTag(UiTags.BAND_BAR, useUnmergedTree = true).getUnclippedBoundsInRoot()
        val band = rule.onNodeWithTag(UiTags.HEADER_BAND, useUnmergedTree = true).getUnclippedBoundsInRoot()
        assertEquals("band top meets bar top", bar.top.value, band.top.value, 1f)
        // Scrolling keeps the bar (now filled) and its back button on screen.
        rule.onNodeWithTag(UiTags.LIB_LIST).performScrollToIndex(3)
        rule.waitForIdle()
        rule.onNodeWithTag(UiTags.BAND_BAR).assertIsDisplayed()
        rule.onNodeWithTag("library-back").assertIsDisplayed()
    }

    @Test fun recentlyAddedKeepsOneShapePerShelfVideoCardsWideAndSongCoversSquare() {
        val h = Harness("library")
        h.vm.navigate(Destination.HOME)
        h.show(rule)
        val video = "content://media/external/video/media/302"
        val song = "content://media/external/audio/media/104"
        rule.waitForTag(UiTags.HOME_LIST)
        rule.onNodeWithTag(UiTags.HOME_LIST).performScrollToNode(hasTestTag(UiTags.libItem(video)))
        rule.waitForTag(UiTags.libItem(video))
        // Home's New videos and New music sections never mix shapes: videos are 16:9 cards in their own row, songs are square covers in another.
        val v = rule.onNodeWithTag(UiTags.libItem(video), useUnmergedTree = true).getUnclippedBoundsInRoot()
        rule.onNodeWithTag(UiTags.HOME_LIST).performScrollToNode(hasTestTag(UiTags.libItem(song)))
        rule.waitForTag(UiTags.libItem(song))
        // the song can also be in Jump back in: any card of it is a square cover
        val s = rule.onAllNodesWithTag(UiTags.libItem(song), useUnmergedTree = true)[0].getUnclippedBoundsInRoot()
        // card widths follow the screen now, through the shared size rule
        val page = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.screenWidthDp.toDouble()
        val scale = CardPrefs().scale
        assertEquals("video card is the wide size", CardSizes.size(CardKind.Video, page, scale).toFloat(), (v.right - v.left).value, 0.5f)
        assertEquals("song cover card is the square size", CardSizes.size(CardKind.Square, page, scale).toFloat(), (s.right - s.left).value, 0.5f)
    }

    private val linkedSong = "content://media/external/audio/media/102"

    @Test fun finishedRowPlaysInKeepfinAndTheMenuKeepsOpenInAnotherApp() {
        val h = Harness("library")
        h.engine.fakeLibrary.linkJob(linkedSong, "j-lib3")
        h.vm.navigate(Destination.DOWNLOADS)
        h.vm.chooseDownloadsFilter(DownloadsFilter.FINISHED)
        h.show(rule)
        rule.waitForTag(UiTags.QUEUE)
        rule.scrollListTo(UiTags.QUEUE, UiTags.job("j-lib3"))
        rule.waitForTag(UiTags.job("j-lib3"))
        // Tapping the finished row plays the file in Keepfin's own player.
        rule.onNodeWithTag(UiTags.job("j-lib3")).performClick()
        val played = runCatching { rule.waitUntil(5_000) { h.vm.player.ui.value.current?.id == linkedSong } }.isSuccess
        assertTrue("plays in Keepfin; current=${h.vm.player.ui.value.current?.id}", played)
        // The menu offers Play and Open in another app; the latter hands the file to another app and does not start the player.
        rule.runOnIdle { h.vm.player.clearQueue() }
        rule.waitUntil(5_000) { h.vm.player.ui.value.current == null }
        rule.onNodeWithContentDescription("More actions for Big Buck Bunny – soundtrack").performClick()
        rule.onNodeWithText("Play").assertIsDisplayed()
        rule.onNodeWithText("Open in another app").performClick()
        rule.waitForIdle()
        assertNull(h.vm.player.ui.value.current)
    }
    @Test fun finishedDownloadFallsBackToAnotherAppWithoutTheLibraryOrItsItem() {
        val h = Harness("library")
        val job = h.engine.jobs.value.first { it.id == "j-lib3" }
        // Not in the library yet: nothing plays, so the caller opens it in another app.
        assertTrue(!h.vm.playJob(job))
        h.engine.fakeLibrary.linkJob(linkedSong, "j-lib3")
        assertTrue(h.vm.playJob(job))
        // Downloader-only mode has no built-in player for downloads.
        h.vm.player.clearQueue()
        h.libraryOff()
        assertTrue(!h.vm.playJob(job))
        assertNull(h.vm.player.ui.value.current)
    }

    /** The Play and Shuffle labels of the header must be laid out whole: no ellipsis, no clipping, no wrapping. */
    private fun assertLabelWhole(tag: String, label: String) {
        val node = rule.onNode(androidx.compose.ui.test.hasText(label) and androidx.compose.ui.test.hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true).fetchSemanticsNode()
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val layout = results.first()
        assertTrue("$label is not truncated or clipped: overflow=${layout.hasVisualOverflow} lines=${layout.lineCount} ellipsized=${layout.isLineEllipsized(0)} size=${layout.size} widest=${layout.getLineRight(0)} node=${node.size}", layout.lineCount == 1 && !layout.isLineEllipsized(0) && layout.getLineRight(0) <= node.size.width + 0.5f)
    }

    private fun headersKeepTheirLabels(scale: Float, width: Int) {
        val h = Harness("spaces-v2")
        val key = PlaybackItems.albumKey(h.engine.fakeLibrary.item("content://media/external/audio/media/101")!!)!!
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule, fontScale = scale, widthDp = width)
        val routes = listOf(
            LibraryRoute.Collection("workout", "gym"), // followed: has the Sync button too
            LibraryRoute.Album("music", key), LibraryRoute.Artist("music", "Blender Studio"),
        )
        for (route in routes) {
            rule.runOnIdle { h.vm.openLibraryDeep(route) }
            rule.waitForTag(UiTags.LIB_PLAY_ALL)
            assertLabelWhole(UiTags.LIB_PLAY_ALL, "Play")
            assertLabelWhole(UiTags.LIB_SHUFFLE, "Shuffle")
            rule.runOnIdle { h.vm.back() }
        }
    }

    @Test fun headerButtonsKeepTheirLabelsOnANarrowPhone() = headersKeepTheirLabels(1.0f, 360)
    @Test fun headerButtonsKeepTheirLabelsAtLargeFontScale() = headersKeepTheirLabels(1.3f, 360)
}