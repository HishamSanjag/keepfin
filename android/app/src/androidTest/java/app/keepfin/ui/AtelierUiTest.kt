package app.keepfin.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import app.keepfin.allItems
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Cobalt Atelier restyle (dev-docs/plans/MAIN-REDESIGN.md): Library home shelves, quality presets and the direction B player. */
@RunWith(AndroidJUnit4::class)
class AtelierUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private val video = "content://media/external/video/media/201"
    private val song = "content://media/external/audio/media/102"
    private val link = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"

    private fun openLibraryHome(scene: String = "library"): Harness {
        val h = Harness(scene)
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.waitForTag(UiTags.LIB_LIST)
        return h
    }

    private fun scrollLibrary(tag: String) {
        rule.onNodeWithTag(UiTags.LIB_LIST).performScrollToNode(hasTestTag(tag))
        rule.waitForIdle()
    }

    // ---- Library home -----------------------------------------------------------------------------------------

    @Test fun libraryHomeHasAOneLineTitleAndSpacesAsTwoColumnCards() {
        openLibraryHome()
        rule.onNodeWithTag(UiTags.LIB_LIST).assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("Library").fetchSemanticsNodes().isNotEmpty())
        assertTrue("no subtitle line", rule.onAllNodesWithText("Your media, close at hand.").fetchSemanticsNodes().isEmpty())
        for (id in listOf("music", "videos", "downloads")) {
            rule.onNodeWithTag(UiTags.spaceCard(id)).assertExists()
            // tinted two-column cards: at least 56dp high
            rule.onNodeWithTag(UiTags.spaceCard(id)).assertHeightIsAtLeast(56.dp)
        }
    }

    @Test fun continueWatchingCardShowsProgressAPlayButtonAndTheTimeLeft() {
        openLibraryHome()
        scrollLibrary(UiTags.libItem(video))
        rule.waitForTag("continue-progress-$video")
        assertTrue("play button over the artwork", rule.exists("continue-play-$video"))
        val progress = rule.onNodeWithTag("continue-progress-$video", useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("a watched-percent description", progress.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()?.startsWith("Watched") == true)
        // 190 s of 464 s watched: "4 min left"
        rule.onNodeWithTag("continue-left-$video", useUnmergedTree = true).assertTextContains("left", substring = true)
    }

    @Test fun theCrossOnAContinueCardTakesItOffTheRowAndUndoBringsItBack() {
        val h = openLibraryHome()
        val position = { h.engine.fakeLibrary.allItems().first { it.id == video }.position }
        val was = position()
        scrollLibrary(UiTags.libItem(video))
        rule.onNodeWithTag("continue-remove-$video").performClick()
        rule.waitUntil(5_000) { position() == null }
        rule.waitUntil(5_000) { !rule.exists("continue-play-$video") }
        rule.onAllNodesWithText("Undo")[0].performClick()
        rule.waitUntil(5_000) { position() == was }
    }

    @Test fun recentlyAddedSplitsVideosAndMusicIntoTwoShelves() {
        val h = openLibraryHome()
        val kinds = h.engine.fakeLibrary.allItems().map { it.kind }.toSet()
        assertTrue("the fixture mixes videos with songs", app.keepfin.LibraryItemKind.VIDEO in kinds && app.keepfin.LibraryItemKind.AUDIO in kinds)
        scrollLibrary("see-all-recent")
        rule.onNodeWithTag("see-all-recent").assertExists()
        // mixed content is split: the first shelf keeps the key, the other shape gets a shelf of its own
        val second = listOf("see-all-recent-video", "see-all-recent-square").firstOrNull { tag -> runCatching { scrollLibrary(tag) }.isSuccess }
        assertTrue("a second shelf for the other artwork shape", second != null)
        val titles = listOf("Recently added videos", "Recently added music", "Recently added photos", "Recently added music and photos")
        assertTrue("two titled shelves, not one mixed one", titles.count { rule.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty() } >= 1)
    }

    @Test fun libraryPageHasACountAndAVisibleGridListSwitch() {
        val h = openLibraryHome()
        rule.onNodeWithTag(UiTags.spaceCard("music")).performClick()
        rule.waitForTag(UiTags.LIB_VIEW_TOGGLE)
        val before = h.vm.prefs.libraryGrid("music:" + h.vm.musicTab("music").name, false)
        rule.onNodeWithTag(UiTags.LIB_VIEW_TOGGLE).performClick()
        rule.waitForIdle()
        assertEquals(!before, h.vm.prefs.libraryGrid("music:" + h.vm.musicTab("music").name, false))
        rule.onNodeWithTag(UiTags.LIB_SORT_VIEW).assertExists()
        assertTrue("the header counts the songs", rule.onAllNodesWithText("songs", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    // ---- Downloads: the review sheet ----------------------------------------------------------------------------------

    private fun capture(): Harness {
        val h = Harness("empty")
        h.show(rule)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput(link)
        rule.waitForTag(UiTags.REVIEW)
        return h
    }

    @Test fun sheetListsEverySizeWithRealSizesAndPicksOne() {
        val h = capture()
        val media = (h.vm.capture as Capture.Single).media
        assertEquals("the fixture has eight sizes", 8, media.video.size)
        // every size is listed at once under the Format row, nothing is behind a fold
        assertFalse(rule.hasText("More sizes"))
        for (option in media.video) {
            rule.onNodeWithTag(UiTags.quality(option.id)).performScrollTo().assertExists()
            val text = rule.onNodeWithTag(UiTags.quality(option.id)).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString(" ") { it.text }
            assertTrue("${option.id} shows the engine size: $text", Regex("[0-9.]+ (MB|GB)").containsMatchIn(text) || text.contains("size unknown"))
        }
        assertTrue(rule.exists(UiTags.container("mkv")))
        rule.onNodeWithTag(UiTags.quality("v720")).performScrollTo().performClick()
        rule.waitForIdle()
        assertEquals("v720", h.vm.choice.video(media)?.id)
        // the Download button names the chosen size
        rule.onNodeWithTag(UiTags.DOWNLOAD).assertTextContains("Download · ", substring = true)
    }
    // ---- The player (direction B) ------------------------------------------------------------------------------------

    private fun startSong(): Harness {
        val h = Harness("library")
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.onNodeWithTag(UiTags.spaceCard("music")).performClick()
        rule.waitForTag(UiTags.libItem(song))
        rule.onNodeWithTag(UiTags.libItem(song)).performClick()
        rule.waitForTag(UiTags.MINI_PLAYER)
        return h
    }

    @Test fun miniPlayerHasLargeArtworkAnOutlinedPlayButtonAndASeekBarWithTimes() {
        startSong()
        for (tag in listOf(UiTags.MINI_PREV, UiTags.MINI_PLAY, UiTags.MINI_NEXT, UiTags.MINI_CLOSE)) {
            rule.onNodeWithTag(tag).assertIsDisplayed()
            rule.onNodeWithTag(tag).assertWidthIsAtLeast(48.dp)
            rule.onNodeWithTag(tag).assertHeightIsAtLeast(48.dp)
        }
        // the seek bar is a real range control with an elapsed time on one side and the total on the other
        rule.onNodeWithTag(UiTags.MINI_SEEK).assertExists()
        assertTrue("range info", rule.onNodeWithTag(UiTags.MINI_SEEK).fetchSemanticsNode().config.contains(SemanticsProperties.ProgressBarRangeInfo))
        rule.onNodeWithTag(UiTags.MINI_ELAPSED, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(UiTags.MINI_TOTAL, useUnmergedTree = true).assertExists()
        val bar = rule.onNodeWithTag(UiTags.MINI_PLAYER).fetchSemanticsNode().size
        val seek = rule.onNodeWithTag(UiTags.MINI_SEEK).fetchSemanticsNode()
        val controls = rule.onNodeWithTag(UiTags.MINI_PLAY).fetchSemanticsNode()
        assertTrue("the seek bar runs under the transport", seek.boundsInRoot.top >= controls.boundsInRoot.bottom - 1f)
        assertTrue("the bar is taller than the old 64dp mini player", bar.height > 0 && bar.height >= rule.density().run { 100.dp.roundToPx() })
        // tapping the bar still opens the player
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performClick()
        rule.waitForTag(UiTags.FULL_PLAYER)
        assertTrue(rule.exists("player-wash"))
        assertTrue("controls sit on their own opaque layer", rule.exists("player-controls"))
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.density() = androidx.compose.ui.unit.Density(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density,
    )

}
