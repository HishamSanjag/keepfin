package app.keepfin.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.DownloadPreset
import app.keepfin.SongOfLink
import app.keepfin.core.lyrics.LyricsQuery
import app.keepfin.core.spaces.MusicVideoRows
import app.keepfin.core.spaces.PreviewWords
import app.keepfin.ui.library.LibraryRoute
import app.keepfin.ui.player.SimulatedPlayerPort
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

/**
 * A song's music video on the phone (dev-docs/plans/MUSIC-VIDEOS.md, the 2026-10-31 rules): its own shelf and rows (cover, title, artist, length, "Music video"),
 * played alone and deleted alone; the Song | Video switch going on from the same second; Capture's line for a song you have and the YouTube link kept as
 * its own video; Find lyrics for a song that has none.
 */
@RunWith(AndroidJUnit4::class)
class MusicVideoRowsUiTest {
    @get:Rule val rule = createComposeRule()

    private val sintel = "content://media/external/audio/media/102"
    private val bunny = "content://media/external/audio/media/101"
    private val rowId = MusicVideoRows.rowId(sintel)
    private val youtube = "https://www.youtube.com/watch?v=aqz-KE-bpKQ"
    private val youtubeMusic = "https://music.youtube.com/watch?v=aqz-KE-bpKQ"

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null; SimulatedPlayerPort.ticks = true }

    private fun openLibraryHome(withVideo: Boolean = true): Harness {
        val h = Harness("library")
        if (withVideo) h.engine.fakeLibrary.addSongWithMusicVideo(sintel)
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        return h
    }

    private fun openVideosPage(): Harness {
        val h = Harness("library")
        h.engine.fakeLibrary.addSongWithMusicVideo(sintel)
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryDetail(LibraryRoute.MusicVideos)
        h.show(rule)
        return h
    }

    // ---- the shelf and the rows ------------------------------------------------------------------------------------------------------------

    @Test fun theShelfShowsOnlyWhenSomeSongHasAVideoAndHasSeeAll() {
        openLibraryHome(withVideo = false)
        rule.waitForTag(UiTags.LIB_LIST)
        // the Collections shelf is above where the Music videos shelf would be: with it on screen, the next shelf head is the one after
        rule.scrollListTo(UiTags.LIB_LIST, "see-all-collections")
        rule.waitForIdle()
        assertFalse("no shelf without a video", rule.exists(UiTags.MUSIC_VIDEO_SHELF))
        assertFalse(rule.exists("see-all-music-videos"))
    }

    @Test fun theShelfHasTheTitleSeeAllAndOneCardPerVideo() {
        openLibraryHome()
        rule.waitForTag(UiTags.LIB_LIST)
        rule.scrollListTo(UiTags.LIB_LIST, UiTags.MUSIC_VIDEO_SHELF)
        rule.onNodeWithText(MusicVideoRows.SHELF).assertExists()
        rule.waitForTag(UiTags.musicVideoRow(rowId))
        rule.onNodeWithTag("see-all-music-videos").performClick()
        rule.waitForTag(UiTags.MUSIC_VIDEOS_LIST)
    }

    @Test fun aRowShowsTheLabelAndAMenuWithPlayGoToSongAndDeleteVideo() {
        openVideosPage()
        rule.waitForTag(UiTags.musicVideoRow(rowId))
        rule.onNodeWithText("Sintel Theme", useUnmergedTree = true).assertExists()
        // one spoken line: title, the label, the artist
        rule.onNodeWithTag(UiTags.musicVideoRow(rowId)).assert(SemanticsMatcher("says Music video") { node -> node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { MusicVideoRows.LABEL in it } == true })
        rule.onNodeWithTag(UiTags.musicVideoRow(rowId) + "-menu").performClick()
        rule.waitForTag("mv-play")
        rule.onNodeWithText("Play").assertExists()
        rule.onNodeWithText(MusicVideoRows.GO_TO_SONG).assertExists()
        rule.onNodeWithText(MusicVideoRows.DELETE).assertExists()
    }

    @Test fun aTapPlaysTheVideoAloneFromItsStartNotTheSongQueue() {
        val h = openVideosPage()
        rule.waitForTag(UiTags.musicVideoRow(rowId))
        // the song queue plays first; the row does not join it
        rule.runOnIdle { h.vm.play(listOf(h.vm.lib.item(bunny)!!, h.vm.lib.item(sintel)!!), 0) }
        rule.waitUntil(5_000) { h.vm.player.ui.value.current?.id == bunny }
        rule.onNodeWithTag(UiTags.musicVideoRow(rowId)).performClick()
        rule.waitUntil(5_000) { h.vm.video?.musicVideo == true }
        rule.runOnIdle {
            assertEquals(sintel, h.vm.video?.id)
            assertEquals("a queue of only that video", listOf(sintel), h.vm.player.ui.value.queue.map { it.id })
            assertTrue(h.vm.player.ui.value.current?.musicVideo == true)
            assertEquals("from its start", 0L, h.vm.player.position.value.positionMs)
            assertFalse("the Song | Video choice is left as it was", h.prefs.musicVideo)
        }
    }

    @Test fun deleteVideoAsksThenRemovesOnlyTheVideoAndTheSongStays() {
        val h = openVideosPage()
        rule.waitForTag(UiTags.musicVideoRow(rowId))
        rule.onNodeWithTag(UiTags.musicVideoRow(rowId) + "-menu").performClick()
        rule.waitForTag("mv-delete")
        rule.onNodeWithTag("mv-delete").performClick()
        rule.waitUntil(5_000) { rule.hasText(MusicVideoRows.DELETE_ASK) }
        assertTrue("nothing is deleted before the answer", h.engine.fakeLibrary.calls.none { it.startsWith("removeMusicVideo") })
        rule.onNodeWithTag("remove-music-video-confirm").performClick()
        rule.waitUntil(5_000) { h.engine.fakeLibrary.calls.contains("removeMusicVideo:$sintel") }
        rule.waitUntil(5_000) { !rule.exists(UiTags.musicVideoRow(rowId)) }
        assertNotNull("the song stays", h.vm.lib.item(sintel))
        assertEquals(false, h.vm.lib.item(sintel)?.musicVideo)
    }

    // ---- Song | Video from the same second ---------------------------------------------------------------------------------------------------

    @Test fun theSwitchKeepsTheSecondInBothDirectionsAndStopsBeforeTheEndOfTheShorterSide() {
        val h = Harness("library")
        h.engine.fakeLibrary.addSongWithMusicVideo(sintel)
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        val song = h.vm.lib.item(sintel)!!
        rule.runOnIdle { h.vm.play(listOf(song), 0) }
        rule.waitUntil(5_000) { h.vm.player.ui.value.current?.id == sintel }
        rule.runOnIdle { h.vm.player.seekTo(40_000) }
        rule.runOnIdle { h.vm.chooseSide(true) }
        rule.waitUntil(5_000) { h.vm.player.ui.value.current?.musicVideo == true }
        rule.runOnIdle { assertEquals("Song to Video keeps the second", 40_000L, h.vm.player.position.value.positionMs) }
        rule.runOnIdle { h.vm.player.seekTo(55_000) }
        rule.runOnIdle { h.vm.chooseSide(false) }
        rule.waitUntil(5_000) { h.vm.player.ui.value.current?.musicVideo == false }
        rule.runOnIdle { assertEquals("Video to Song keeps it too", 55_000L, h.vm.player.position.value.positionMs) }
        // the song is shorter than where the video is: it starts 1 s before its end and finishes there
        val length = song.duration!!
        rule.runOnIdle { h.vm.chooseSide(true) }
        rule.waitUntil(5_000) { h.vm.player.ui.value.current?.musicVideo == true }
        rule.runOnIdle { h.vm.player.seekTo(((length + 120) * 1000).toLong()) }
        rule.runOnIdle { h.vm.chooseSide(false) }
        rule.waitUntil(5_000) { h.vm.player.ui.value.current?.musicVideo == false }
        rule.runOnIdle { assertEquals(((length - 1.0) * 1000).toLong(), h.vm.player.position.value.positionMs) }
    }

    @Test fun theSwitchFromTheVideoScreenIsTheSameSwitch() {
        val h = Harness("library")
        h.engine.fakeLibrary.addSongWithMusicVideo(sintel)
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.runOnIdle { h.vm.playMusicVideo(sintel) }
        rule.waitForTag(UiTags.VIDEO_PLAYER)
        rule.runOnIdle { h.vm.player.seekTo(21_000) }
        rule.onNodeWithTag(UiTags.SIDE_SONG).performClick()
        rule.waitUntil(5_000) { h.vm.player.ui.value.current?.musicVideo == false }
        rule.runOnIdle { assertEquals(21_000L, h.vm.player.position.value.positionMs); assertNull(h.vm.video) }
    }

    // ---- Capture -----------------------------------------------------------------------------------------------------------------------------

    private fun capture(link: String, preset: DownloadPreset? = null, song: SongOfLink? = null): Harness {
        val h = Harness("library")
        preset?.let { p -> h.engine.updateSettings { it.copy(defaultPreset = p) } }
        h.engine.fakeLibrary.songOfLink = song
        h.show(rule)
        rule.runOnIdle { h.vm.navigate(Destination.DOWNLOADS); h.vm.loadLink(link) }
        rule.waitForTag(UiTags.REVIEW)
        return h
    }

    @Test fun theVideoTabSaysTheVideoWillBeAddedToASongYouHave() {
        capture(youtube, song = SongOfLink("song-1", "music", hasVideo = false))
        rule.waitForTag(UiTags.CAPTURE_SONG_NOTE)
        rule.onNodeWithTag(UiTags.CAPTURE_SONG_NOTE).assertTextEquals(MusicVideoRows.JOIN_NOTE)
        assertFalse("it is not 'already in' the library", rule.exists(UiTags.CAPTURE_EXISTING))
        // the line belongs to the Video tab
        rule.onNodeWithTag(UiTags.TAB_AUDIO).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.CAPTURE_SONG_NOTE) }
    }

    @Test fun aSongThatHasItsVideoSaysSo() {
        capture(youtube, song = SongOfLink("song-1", "music", hasVideo = true))
        rule.waitForTag(UiTags.CAPTURE_SONG_NOTE)
        rule.onNodeWithTag(UiTags.CAPTURE_SONG_NOTE).assertTextEquals(MusicVideoRows.HAS_VIDEO_NOTE)
    }

    @Test fun aLinkThatIsNoSongYouHaveHasNoLine() {
        capture(youtube, song = null)
        Thread.sleep(400)
        rule.waitForIdle()
        assertFalse(rule.exists(UiTags.CAPTURE_SONG_NOTE))
    }

    @Test fun aYouTubeLinksBothUsesTheLinkItselfAndSearchesNothing() {
        val h = capture(youtube, preset = DownloadPreset.MP3)
        rule.onNodeWithTag(UiTags.TAB_VIDEO).assertIsSelected()
        rule.onNodeWithTag(UiTags.TAB_BOTH).assertIsEnabled().assertIsNotSelected()
        assertTrue("nothing is searched", h.engine.musicVideoLookups.isEmpty())
        rule.onNodeWithTag(UiTags.TAB_BOTH).performClick()
        rule.waitForTag(UiTags.CAPTURE_LINK_VIDEO)
        rule.onNodeWithTag(UiTags.CAPTURE_LINK_VIDEO).assertTextEquals(PreviewWords.BOTH_LINK)
        rule.onNodeWithTag(UiTags.TAB_BOTH).assertIsSelected()
        rule.onNodeWithTag(UiTags.DOWNLOAD).performClick()
        rule.waitUntil(8_000) { h.engine.capturedWithVideo.isNotEmpty() }
        val (url, video) = h.engine.capturedWithVideo.single()
        assertEquals(youtube, url)
        assertEquals("aqz-KE-bpKQ", video.videoId)
        assertEquals("the link's own address", youtube, video.url)
        assertTrue(h.engine.musicVideoLookups.isEmpty())
    }

    @Test fun onAudioTheYouTubeLinkQueuesOnlyTheSong() {
        val h = capture(youtube, preset = DownloadPreset.MP3)
        rule.onNodeWithTag(UiTags.TAB_AUDIO).performClick()
        assertFalse(rule.exists(UiTags.CAPTURE_LINK_VIDEO))
        rule.onNodeWithTag(UiTags.DOWNLOAD).performClick()
        rule.waitUntil(8_000) { h.engine.enqueued.isNotEmpty() }
        assertTrue(h.engine.capturedWithVideo.isEmpty())
    }

    @Test fun aYouTubeMusicLinkKeepsItsLookAndOffersNoOwnVideoBox() {
        capture(youtubeMusic, preset = DownloadPreset.MP3)
        rule.onNodeWithTag(UiTags.TAB_AUDIO).assertIsSelected()
        Thread.sleep(400)
        rule.waitForIdle()
        assertFalse(rule.exists(UiTags.CAPTURE_LINK_VIDEO))
    }

    @Test fun withAnAudioDefaultAYouTubeLinkOpensOnVideo() {
        capture(youtube, preset = DownloadPreset.MP3)
        rule.onNodeWithTag(UiTags.TAB_VIDEO).assertIsSelected()
    }

    @Test fun aYouTubeMusicLinkOpensOnAudio() {
        capture("https://music.youtube.com/watch?v=dQw4w9WgXcQ", preset = DownloadPreset.BEST)
        rule.onNodeWithTag(UiTags.TAB_AUDIO).assertIsSelected()
    }

    // ---- Find lyrics -------------------------------------------------------------------------------------------------------------------------

    private fun songWithoutLyrics(): Pair<Harness, String> {
        val h = Harness("library")
        val id = h.engine.fakeLibrary.browse.top(app.keepfin.ItemQuery(songs = true), 200).first { !it.hasLyrics && it.artist != null }.id
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(LibraryRoute.Space("music"))
        h.show(rule)
        return h to id
    }

    private fun findFromMenu(h: Harness, id: String) {
        rule.waitForTag(UiTags.libMenu(id))
        rule.onNodeWithTag(UiTags.libMenu(id)).performClick()
        rule.waitForTag(UiTags.ACTION_SHEET)
        rule.onNodeWithTag(UiTags.itemAction("findlyrics")).performClick()
    }

    private fun waitForNotice(text: String) = rule.waitUntil(8_000) { rule.hasText(text) }

    @Test fun findLyricsIsOnTheMenuOfASongWithoutLyricsAndKeepsWhatItFinds() {
        val (h, id) = songWithoutLyrics()
        var asked: LyricsQuery? = null
        h.vm.songVideo.lyricsSearch = { asked = it; app.keepfin.LyricsLookup.Answer("[00:01.00] la", false) }
        findFromMenu(h, id)
        waitForNotice(app.keepfin.core.lyrics.Lyrics.outcomeWords("found"))
        assertNotNull(asked)
        assertTrue(h.engine.fakeLibrary.calls.contains("saveLyrics:$id:[00:01.00] la"))
        assertEquals(true, h.vm.lib.item(id)?.hasLyrics)
    }

    @Test fun noLyricsFoundIsSaidPlainlyAndNothingIsKept() {
        val (h, id) = songWithoutLyrics()
        h.vm.songVideo.lyricsSearch = { app.keepfin.LyricsLookup.Answer(null, false) }
        findFromMenu(h, id)
        waitForNotice("No lyrics found")
        assertTrue(h.engine.fakeLibrary.calls.none { it.startsWith("saveLyrics") })
    }

    @Test fun aServiceThatCouldNotBeReachedReadsDifferentlyFromNoLyrics() {
        val (h, id) = songWithoutLyrics()
        h.vm.songVideo.lyricsSearch = { app.keepfin.LyricsLookup.Answer(null, true) }
        findFromMenu(h, id)
        waitForNotice("Could not reach the lyrics service. Try again later.")
        assertFalse(rule.hasText("No lyrics found"))
        assertTrue(h.engine.fakeLibrary.calls.none { it.startsWith("saveLyrics") })
    }

    @Test fun aSongThatHasLyricsOffersNoFindLyrics() {
        val h = Harness("library")
        val id = h.engine.fakeLibrary.browse.top(app.keepfin.ItemQuery(songs = true), 200).first { it.hasLyrics }.id
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(LibraryRoute.Space("music"))
        h.show(rule)
        rule.waitForTag(UiTags.libMenu(id))
        rule.onNodeWithTag(UiTags.libMenu(id)).performClick()
        rule.waitForTag(UiTags.ACTION_SHEET)
        assertFalse(rule.exists(UiTags.itemAction("findlyrics")))
    }

    @Test fun thePlayersLyricsPanelOffersFindLyricsWhereItSaysThereAreNone() {
        val (h, id) = songWithoutLyrics()
        h.vm.songVideo.lyricsSearch = { app.keepfin.LyricsLookup.Answer("[00:01.00] la", false) }
        rule.runOnIdle { h.vm.play(listOf(h.vm.lib.item(id)!!), 0); h.vm.fullPlayer = true }
        rule.waitForTag(UiTags.FULL_PLAYER)
        rule.onNodeWithTag(UiTags.FULL_LYRICS_TOGGLE).performClick()
        rule.waitForTag(UiTags.FIND_LYRICS)
        rule.onNodeWithText("No lyrics for this song").assertExists()
        rule.onNodeWithTag(UiTags.FIND_LYRICS).performClick()
        rule.waitUntil(8_000) { h.engine.fakeLibrary.calls.any { it.startsWith("saveLyrics:$id") } }
    }
}
