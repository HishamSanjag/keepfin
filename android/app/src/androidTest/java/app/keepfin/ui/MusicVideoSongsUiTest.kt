package app.keepfin.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.allItems
import app.keepfin.ui.library.LibraryRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Part A of dev-docs/plans/MUSIC-VIDEOS.md: a video kept in a Music space is a song. It is listed with the songs (the space counted it
 * before but never showed it), carries a Video tag, plays in the music player, and its menu offers Watch video.
 */
@RunWith(AndroidJUnit4::class)
class MusicVideoSongsUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private fun open(): Pair<Harness, String> {
        val h = Harness("library")
        val video = h.engine.fakeLibrary.addMusicVideo()
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(LibraryRoute.Space("music"))
        h.show(rule)
        return h to video.id
    }

    @Test fun aVideoInMusicIsListedWithSongsAndTaggedVideo() {
        val (h, id) = open()
        rule.waitForTag(UiTags.libItem(id))
        assertTrue("the view model knows the music space", h.vm.isVideoSong(h.vm.lib.browse.item(id)!!))
        rule.waitForTag(UiTags.videoTag(id))
        rule.onNodeWithTag(UiTags.videoTag(id), useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Video", useUnmergedTree = true).assertExists()
        // a real song in the same list has no tag
        val plain = "content://media/external/audio/media/102"
        rule.waitForTag(UiTags.libItem(plain))
        assertFalse(rule.exists(UiTags.videoTag(plain)))
        assertTrue(h.vm.isVideoSong(h.vm.lib.browse.item(id)!!))
        assertFalse(h.vm.isVideoSong(h.vm.lib.browse.item(plain)!!))
    }

    @Test fun itsMenuOffersWatchVideoAndNotPlayAsAudio() {
        val (_, id) = open()
        rule.waitForTag(UiTags.libMenu(id))
        rule.onNodeWithTag(UiTags.libMenu(id)).performClick()
        rule.waitForTag(UiTags.ACTION_SHEET)
        rule.onNodeWithText("Watch video").assertExists()
        rule.onNodeWithText("Play as audio").assertDoesNotExist()
    }

    @Test fun tappingItPlaysItInTheMusicPlayerNotTheVideoPlayer() {
        val (h, id) = open()
        rule.waitForTag(UiTags.libItem(id))
        rule.onNodeWithTag(UiTags.libItem(id)).performClick()
        rule.waitForTag(UiTags.MINI_PLAYER)
        rule.runOnIdle { assertTrue("the video player stays closed", h.vm.video == null) }
    }

    @Test fun itsFullPlayerHasTheSongVideoSwitchBothWays() {
        val (h, id) = open()
        rule.waitForTag(UiTags.libItem(id))
        rule.onNodeWithTag(UiTags.libItem(id)).performClick()
        rule.waitForTag(UiTags.MINI_PLAYER)
        rule.runOnIdle { h.vm.fullPlayer = true }
        // the same switch as a song that keeps its music video: Video shows the picture of the same file
        rule.waitForTag(UiTags.SIDE_VIDEO)
        rule.onNodeWithTag(UiTags.SIDE_VIDEO).performClick()
        rule.waitUntil(5_000) { h.vm.songPicture == id }
        // the picture takes the cover's place; the full player and the video screen's state are as they were
        rule.waitForTag(UiTags.PLAYER_PICTURE)
        rule.runOnIdle { assertNull(h.vm.video); assertTrue(h.vm.fullPlayer) }
        // Song: the cover is back with the same file
        rule.onNodeWithTag(UiTags.SIDE_SONG).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.PLAYER_PICTURE) && h.vm.fullPlayer }
        rule.runOnIdle { assertTrue(h.vm.player.ui.value.current?.id == id) }
    }

    @Test fun videoAndSongKeepTheQueueThePlaceAndAPausedTrackPaused() {
        val (h, id) = open()
        val plain = "content://media/external/audio/media/102"
        val video = h.vm.lib.browse.item(id)!!
        rule.runOnIdle { h.vm.play(listOf(video, h.vm.lib.browse.item(plain)!!), 0); h.vm.fullPlayer = true; h.vm.player.seekTo(12_000); h.vm.player.pause() }
        rule.waitForTag(UiTags.SIDE_VIDEO)
        rule.onNodeWithTag(UiTags.SIDE_VIDEO).performClick()
        rule.waitForTag(UiTags.PLAYER_PICTURE)
        rule.runOnIdle {
            assertEquals(listOf(id, plain), h.vm.player.ui.value.queue.map { it.id })
            assertEquals(12_000L, h.vm.player.position.value.positionMs)
            assertFalse("a paused track stays paused", h.vm.player.ui.value.playing)
        }
        rule.onNodeWithTag(UiTags.SIDE_SONG).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.PLAYER_PICTURE) }
        rule.runOnIdle {
            assertEquals(listOf(id, plain), h.vm.player.ui.value.queue.map { it.id })
            assertEquals(12_000L, h.vm.player.position.value.positionMs)
            assertFalse(h.vm.player.ui.value.playing)
        }
    }

    @Test fun fullScreenOfAVideoKeptAsASongMovesThroughTheMusicQueue() {
        val (h, id) = open()
        val plain = "content://media/external/audio/media/102"
        val video = h.vm.lib.browse.item(id)!!
        rule.runOnIdle { h.vm.play(listOf(video, h.vm.lib.browse.item(plain)!!), 0); h.vm.fullPlayer = true }
        rule.waitForTag(UiTags.SIDE_VIDEO)
        rule.onNodeWithTag(UiTags.SIDE_VIDEO).performClick()
        rule.waitForTag(UiTags.PLAYER_PICTURE)
        rule.runOnIdle { h.vm.openPictureFullScreen() }
        rule.waitForTag(UiTags.VIDEO_PLAYER)
        rule.onNodeWithTag("video-next").assertIsEnabled()
        rule.onNodeWithTag("video-next").performClick()
        rule.waitUntil(5_000) { h.vm.player.ui.value.current?.id == plain }
        rule.runOnIdle { assertNull("the next song brings the full player back", h.vm.video); assertTrue(h.vm.fullPlayer) }
        assertEquals(listOf(id, plain), h.vm.player.ui.value.queue.map { it.id })
    }

    @Test fun watchFromTheMenuOpensTheFullPlayerWithThePicture() {
        val (h, id) = open()
        rule.waitForTag(UiTags.libMenu(id))
        rule.onNodeWithTag(UiTags.libMenu(id)).performClick()
        rule.waitForTag(UiTags.ACTION_SHEET)
        rule.onNodeWithText("Watch video").performClick()
        rule.waitForTag(UiTags.PLAYER_PICTURE)
        rule.runOnIdle { assertNull("no separate video screen", h.vm.video); assertTrue(h.vm.fullPlayer); assertTrue(h.vm.songPicture == id) }
    }

    @Test fun aVideoOutsideMusicIsNotASong() {
        val h = Harness("library")
        val lib = h.engine.fakeLibrary
        val outside = lib.allItems().first { it.kind == app.keepfin.LibraryItemKind.VIDEO && it.space == "videos" }
        assertFalse(h.vm.isVideoSong(outside))
        val songs = lib.browse.top(app.keepfin.ItemQuery(songs = true), 1_000)
        assertTrue(songs.none { it.kind == app.keepfin.LibraryItemKind.VIDEO })
    }
}
