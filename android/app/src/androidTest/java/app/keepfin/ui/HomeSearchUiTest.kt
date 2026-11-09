package app.keepfin.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.ui.preview.FakeLibrary.FakeSnapshot
import app.keepfin.PlaybackItems
import app.keepfin.ui.library.LibraryRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 0.5.5 Home and Search, and the one item action sheet that every surface opens. */
@RunWith(AndroidJUnit4::class)
class HomeSearchUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private val waltz = "content://media/external/audio/media/104"

    private fun emptyLibrary(h: Harness) =
        h.engine.fakeLibrary.setSnapshot(FakeSnapshot(items = emptyList(), collections = emptyList(), scannedAt = System.currentTimeMillis()))

    private fun home(scene: String = "library"): Harness {
        val h = Harness(scene)
        h.vm.navigate(Destination.HOME)
        h.show(rule)
        rule.waitForTag(UiTags.HOME_SCREEN)
        return h
    }

    // ---- Home ----------------------------------------------------------------------------------------------

    @Test fun aNewLibraryShowsOneFriendlyEmptyStateUnderTheBox() {
        val h = Harness("empty")
        emptyLibrary(h)
        h.vm.navigate(Destination.HOME)
        h.show(rule)
        rule.waitForTag(UiTags.HOME_EMPTY)
        rule.onNodeWithTag(UiTags.HOME_BOX).assertIsDisplayed()
        for (row in listOf("continue", "recent", "downloading")) assertFalse("$row hides when empty", rule.exists(UiTags.homeRow(row)))
        assertFalse("no mixes", rule.exists(UiTags.HOME_MIXES))
        assertFalse(rule.exists(UiTags.HOME_GRID))
    }

    @Test fun rowsShowWithDataAndEachHidesWhenItsDataIsMissing() {
        home()
        rule.waitForTag(UiTags.HOME_GRID)
        rule.waitForTag(UiTags.homeRow("continue"))
        // The fixtures hold five songs played lately and eight added: those two music rows show; fewer than five never-played and no forgotten ones do not.
        rule.onNodeWithTag(UiTags.HOME_LIST).performScrollToNode(hasTestTag(UiTags.homeMix("on-repeat")))
        rule.waitForTag(UiTags.homeMix("on-repeat"))
        rule.onNodeWithTag(UiTags.HOME_LIST).performScrollToNode(hasTestTag(UiTags.homeMix("recent")))
        rule.waitForTag(UiTags.homeMix("recent"))
        assertFalse(rule.exists(UiTags.homeMix("never")))
        assertFalse(rule.exists(UiTags.homeMix("forgotten")))
        assertFalse("no transfers, no Downloading now", rule.exists(UiTags.homeRow("downloading")))
        assertFalse(rule.exists(UiTags.HOME_EMPTY))
    }

    @Test fun removingTheDataHidesEveryRowAgain() {
        val h = home()
        rule.waitForTag(UiTags.homeRow("continue"))
        rule.runOnIdle { emptyLibrary(h) }
        rule.waitForTag(UiTags.HOME_EMPTY)
        assertFalse(rule.exists(UiTags.homeRow("continue")))
        assertFalse(rule.exists(UiTags.HOME_GRID))
    }

    @Test fun theChipsFilterTheRowsAndAreRemembered() {
        val h = home()
        rule.waitForTag(UiTags.homeRow("continue"))
        rule.onNodeWithTag(UiTags.homeChip("music")).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.homeRow("continue")) }
        assertEquals("music", h.prefs.homeFilter)
        rule.onNodeWithTag(UiTags.HOME_LIST).performScrollToNode(hasTestTag(UiTags.homeMix("on-repeat")))
        rule.waitForTag(UiTags.homeMix("on-repeat"))
        rule.onNodeWithTag(UiTags.homeChip("videos")).performClick()
        rule.waitUntil(5_000) { rule.exists(UiTags.homeRow("continue")) && !rule.exists(UiTags.homeMix("on-repeat")) }
        rule.onNodeWithTag(UiTags.homeChip("photos")).performClick()
        rule.waitForTag(UiTags.HOME_EMPTY)
        rule.onNodeWithTag(UiTags.homeChip("all")).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.HOME_EMPTY) }
    }

    @Test fun downloadingNowShowsActiveTransfersAndLinksToDownloads() {
        val h = home("live")
        val active = h.engine.jobs.value.first { it.status.active }
        rule.onNodeWithTag(UiTags.HOME_LIST).performScrollToNode(hasTestTag(UiTags.homeRow("downloading")))
        rule.waitForTag(UiTags.homeRow("downloading"))
        rule.waitForTag("home-download-${active.id}")
        rule.onNodeWithTag("see-all-downloading").performClick()
        rule.waitUntil(5_000) { h.vm.destination == Destination.DOWNLOADS }
    }

    // ---- Search --------------------------------------------------------------------------------------------

    private fun search(query: String): Harness {
        val h = Harness("library")
        h.vm.navigate(Destination.SEARCH)
        h.show(rule)
        rule.waitForTag(UiTags.SEARCH_FIELD)
        rule.onNodeWithTag(UiTags.SEARCH_FIELD).performTextInput(query)
        rule.waitUntil(5_000) { h.vm.searchQuery == query }
        return h
    }

    @Test fun searchShowsYourLibraryFirstThenYouTubeAndFiltersByKind() {
        search("sintel")
        rule.waitForTag(UiTags.SEARCH_LIBRARY)
        rule.waitForTag(UiTags.libItem("content://media/external/audio/media/102"))
        rule.waitForTag(UiTags.libItem("content://media/external/video/media/202"))
        rule.onNodeWithTag(UiTags.SEARCH_SCREEN).performScrollToNode(hasTestTag(UiTags.SEARCH_ONLINE))
        rule.waitForTag(UiTags.SEARCH_ONLINE)
        rule.onNodeWithTag(UiTags.SEARCH_SCREEN).performScrollToNode(hasTestTag(UiTags.searchChip("songs")))
        rule.onNodeWithTag(UiTags.searchChip("songs")).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.libItem("content://media/external/video/media/202")) }
        rule.waitForTag(UiTags.libItem("content://media/external/audio/media/102"))
        rule.onNodeWithTag(UiTags.searchChip("videos")).performClick()
        rule.waitUntil(5_000) { rule.exists(UiTags.libItem("content://media/external/video/media/202")) && !rule.exists(UiTags.libItem("content://media/external/audio/media/102")) }
    }

    @Test fun nothingMatchingSaysSoAndStillOffersYouTube() {
        search("zzzz-no-such-thing")
        rule.waitForTag(UiTags.SEARCH_NONE)
        rule.onNodeWithTag(UiTags.SEARCH_SCREEN).performScrollToNode(hasTestTag(UiTags.SEARCH_ONLINE))
        rule.waitForTag(UiTags.SEARCH_ONLINE)
    }

    @Test fun searchYouTubeOpensTheDownloadFlowOnTheResults() {
        val h = search("lofi beats")
        rule.onNodeWithTag(UiTags.SEARCH_SCREEN).performScrollToNode(hasTestTag(UiTags.SEARCH_ONLINE))
        rule.onNodeWithTag(UiTags.SEARCH_ONLINE).performClick()
        rule.waitUntil(5_000) { h.vm.destination == Destination.DOWNLOADS }
        rule.waitUntil(5_000) { h.engine.inspectCalls.any { it.startsWith("https://www.youtube.com/results?search_query=lofi") } }
    }

    @Test fun aLinkInTheSearchFieldGoesToCapture() {
        val link = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        val h = search(link)
        rule.waitForTag(UiTags.SEARCH_ONLINE + "-link")
        rule.onNodeWithTag(UiTags.SEARCH_ONLINE + "-link").performClick()
        rule.waitUntil(5_000) { h.vm.destination == Destination.DOWNLOADS }
        rule.waitUntil(5_000) { h.engine.inspectCalls.contains(link) }
    }

    @Test fun anEmptySearchIsAHint() {
        val h = Harness("library")
        h.vm.navigate(Destination.SEARCH)
        h.show(rule)
        rule.waitForTag(UiTags.EMPTY_STATE)
        assertFalse(rule.exists(UiTags.SEARCH_LIBRARY))
    }

    // ---- one action sheet ----------------------------------------------------------------------------------

    private val expectedActions = listOf("play", "getmusicvideo", "findlyrics", "next", "queue", "playlist", "favorite", "album", "artist", "follow", "showfile", "edit", "move", "copy", "rename", "external", "share", "delete").map(UiTags::itemAction)

    private fun sheetActions(): List<String> {
        rule.waitForTag(UiTags.ACTION_SHEET)
        rule.waitForTag(UiTags.itemAction("play"))
        return rule.onAllNodes(SemanticsMatcher("an item action") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.let { it.startsWith("item-action-") && it != UiTags.ACTION_SHEET } == true
        }).fetchSemanticsNodes().mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }
    }

    @Test fun theActionSheetFromHomeHasEveryAction() {
        val h = Harness("library")
        h.vm.navigate(Destination.HOME)
        h.show(rule)
        rule.waitForTag("home-quick-i-$waltz")
        rule.onNodeWithTag("home-quick-i-$waltz").performTouchInput { longClick() }
        assertEquals(expectedActions, sheetActions())
    }

    @Test fun theActionSheetFromTheLibraryIsTheSame() {
        val h = Harness("library")
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.onNodeWithTag(UiTags.spaceCard("music")).performClick()
        rule.waitForTag(UiTags.libMenu(waltz))
        rule.onNodeWithTag(UiTags.libMenu(waltz)).performClick()
        assertEquals(expectedActions, sheetActions())
    }

    @Test fun theActionSheetFromSearchIsTheSame() {
        search("Cosmos")
        rule.waitForTag(UiTags.libMenu(waltz))
        rule.onNodeWithTag(UiTags.libMenu(waltz)).performClick()
        assertEquals(expectedActions, sheetActions())
    }

    @Test fun theActionSheetFromAnAlbumPageIsTheSame() {
        val h = Harness("library")
        val key = PlaybackItems.albumKey(h.engine.fakeLibrary.item(waltz)!!)!!
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.runOnIdle { h.vm.openLibraryDeep(LibraryRoute.Album("music", key)) }
        rule.waitForTag(UiTags.libMenu(waltz))
        rule.onNodeWithTag(UiTags.libMenu(waltz)).performClick()
        assertEquals(expectedActions, sheetActions())
    }

    @Test fun aVideoCanDoWhatASongCanAndSaysWhatIsMissing() {
        val video = "content://media/external/video/media/202"
        search("Sintel - Open")
        rule.waitForTag(UiTags.libMenu(video))
        rule.onNodeWithTag(UiTags.libMenu(video)).performClick()
        sheetActions()
        for (name in listOf("play", "audio", "next", "queue", "playlist", "favorite", "edit", "delete")) rule.onNodeWithTag(UiTags.itemAction(name)).assertIsEnabled()
        for (name in listOf("album", "artist")) rule.onNodeWithTag(UiTags.itemAction(name)).assertIsNotEnabled()
        assertTrue(rule.hasText("No album tag"))
        assertTrue(rule.hasText("No artist tag"))
        assertFalse(rule.hasText("Only songs can do this."))
    }

    @Test fun favoritesHoldSongsAndVideosEachKindUnderItsOwnChip() {
        val h = Harness("library")
        h.vm.lib.setFavorite("content://media/external/video/media/202", true)
        val favorites = h.vm.lib.browse.top(app.keepfin.ItemQuery(favorite = true), 100)
        val song = favorites.first { it.kind == app.keepfin.LibraryItemKind.AUDIO }
        val video = favorites.first { it.kind == app.keepfin.LibraryItemKind.VIDEO }
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.runOnIdle { h.vm.openLibraryDeep(LibraryRoute.SmartList(app.keepfin.ui.library.LibraryViews.Smart.FAVORITES, null)) }
        rule.waitForTag("kind-filter-video")
        rule.waitForTag("kind-filter-audio")
        rule.waitForTag(UiTags.libMenu(song.id))
        rule.onNodeWithTag("kind-filter-video").performClick()
        rule.waitUntil(5_000) { rule.exists(UiTags.libMenu(video.id)) && !rule.exists(UiTags.libMenu(song.id)) }
        rule.onNodeWithTag("kind-filter-audio").performClick()
        rule.waitUntil(5_000) { rule.exists(UiTags.libMenu(song.id)) && !rule.exists(UiTags.libMenu(video.id)) }
    }

    @Test fun playAsAudioPutsTheVideoInTheMusicQueueWithoutOpeningTheVideoScreen() {
        val video = "content://media/external/video/media/202"
        val h = search("Sintel - Open")
        rule.waitForTag(UiTags.libMenu(video))
        rule.onNodeWithTag(UiTags.libMenu(video)).performClick()
        sheetActions()
        rule.onNodeWithTag(UiTags.itemAction("audio")).performClick()
        rule.waitUntil(5_000) { h.vm.player.ui.value.queue.map { it.id } == listOf(video) }
        assertEquals(null, h.vm.video)
    }

    @Test fun goToAlbumOpensTheAlbumAndBackReturnsToWhereItStarted() {
        val h = search("Cosmos")
        rule.waitForTag(UiTags.libMenu(waltz))
        rule.onNodeWithTag(UiTags.libMenu(waltz)).performClick()
        sheetActions()
        rule.onNodeWithTag(UiTags.itemAction("album")).performClick()
        rule.waitUntil(5_000) { h.vm.destination == Destination.LIBRARY && h.vm.libraryRoute is LibraryRoute.Album }
        rule.runOnIdle { h.vm.back() }
        rule.waitUntil(5_000) { h.vm.destination == Destination.SEARCH }
    }
}
