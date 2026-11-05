package app.keepfin.ui

import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText as hasTextNode
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.MusicVideoLookup
import app.keepfin.MusicVideoMatch
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
 * A song and its official music video (dev-docs/plans/MUSIC-VIDEOS.md), on the phone: "Get the music video" on a song's menu (found, none, could
 * not look), a song that keeps one offers Watch and Remove, and the full player's Song | Video switch that goes on from the same second.
 */
@RunWith(AndroidJUnit4::class)
class MusicVideoUiTest {
    @get:Rule val rule = createComposeRule()

    private val sintel = "content://media/external/audio/media/102"
    private val bunny = "content://media/external/audio/media/101"
    private val match = MusicVideoMatch("dQw4w9WgXcQ", "Sintel Theme (Official Video)", "Blender Studio", 3_200_000_000L, 215.0)

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null; SimulatedPlayerPort.ticks = true }

    private fun openMusic(): Harness {
        val h = Harness("library")
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(LibraryRoute.Space("music"))
        h.show(rule)
        return h
    }

    private fun openMenu(id: String) {
        rule.waitForTag(UiTags.libMenu(id))
        rule.onNodeWithTag(UiTags.libMenu(id)).performClick()
        rule.waitForTag(UiTags.ACTION_SHEET)
    }

    @Test fun gettingTheMusicVideoShowsWhatWasFoundAndDownloadsOnlyWhenAsked() {
        val h = openMusic()
        h.engine.musicVideoLookup = MusicVideoLookup.Found(match)
        h.engine.musicVideoDelayMs = 600
        openMenu(sintel)
        rule.onNodeWithTag(UiTags.itemAction("getmusicvideo")).performClick()
        rule.waitForTag(UiTags.MUSIC_VIDEO_SHEET)
        rule.onNodeWithText("Looking for the official music video…").assertExists()
        rule.waitForTag(UiTags.MUSIC_VIDEO_DOWNLOAD)
        rule.onNodeWithText("Sintel Theme (Official Video)").assertExists()
        rule.onNode(hasTextNode("Blender Studio") and hasAnyAncestor(hasTestTag(UiTags.MUSIC_VIDEO_SHEET))).assertExists()
        rule.onNodeWithText("3.2B views · 3:35").assertExists()
        // the look used the song's artist and title, and nothing is queued before Download
        assertEquals(listOf(listOf("Blender Studio") to "Sintel Theme"), h.engine.musicVideoLookups.toList())
        assertTrue(h.engine.musicVideosQueued.isEmpty())
        rule.onNodeWithTag(UiTags.MUSIC_VIDEO_DOWNLOAD).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.MUSIC_VIDEO_SHEET) }
        assertEquals(listOf(match to sintel), h.engine.musicVideosQueued.toList())
    }

    @Test fun cancellingTheSheetQueuesNothing() {
        val h = openMusic()
        h.engine.musicVideoLookup = MusicVideoLookup.Found(match)
        openMenu(sintel)
        rule.onNodeWithTag(UiTags.itemAction("getmusicvideo")).performClick()
        rule.waitForTag(UiTags.MUSIC_VIDEO_CANCEL)
        rule.onNodeWithTag(UiTags.MUSIC_VIDEO_CANCEL).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.MUSIC_VIDEO_SHEET) }
        assertTrue(h.engine.musicVideosQueued.isEmpty())
    }

    @Test fun noOfficialVideoIsSaidPlainlyWithoutAGuess() {
        val h = openMusic()
        h.engine.musicVideoLookup = MusicVideoLookup.None
        openMenu(sintel)
        rule.onNodeWithTag(UiTags.itemAction("getmusicvideo")).performClick()
        rule.waitForTag(UiTags.MUSIC_VIDEO_CLOSE)
        rule.onNodeWithText("No official music video was found for Sintel Theme.").assertExists()
        assertFalse(rule.exists(UiTags.MUSIC_VIDEO_DOWNLOAD))
        rule.onNodeWithTag(UiTags.MUSIC_VIDEO_CLOSE).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.MUSIC_VIDEO_SHEET) }
        assertTrue(h.engine.musicVideosQueued.isEmpty())
    }

    @Test fun aLookThatCouldNotBeMadeIsToldApartFromNoneAndCanBeTriedAgain() {
        val h = openMusic()
        h.engine.musicVideoLookup = MusicVideoLookup.Failed("Couldn't look for the music video. Check your connection and try again.")
        openMenu(sintel)
        rule.onNodeWithTag(UiTags.itemAction("getmusicvideo")).performClick()
        rule.waitForTag(UiTags.MUSIC_VIDEO_RETRY)
        rule.onNodeWithText("Couldn't look for the music video. Check your connection and try again.").assertExists()
        rule.onNodeWithText("No official music video was found for Sintel Theme.").assertDoesNotExist()
        h.engine.musicVideoLookup = MusicVideoLookup.Found(match)
        rule.onNodeWithTag(UiTags.MUSIC_VIDEO_RETRY).performClick()
        rule.waitForTag(UiTags.MUSIC_VIDEO_DOWNLOAD)
        assertEquals(2, h.engine.musicVideoLookups.size)
    }

    @Test fun aSongWithAVideoOffersWatchAndRemoveAndNotGet() {
        val h = Harness("library")
        h.engine.fakeLibrary.addSongWithMusicVideo(sintel)
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(LibraryRoute.Space("music"))
        h.show(rule)
        openMenu(sintel)
        rule.onNodeWithTag(UiTags.itemAction("watchmusicvideo")).assertExists()
        rule.onNodeWithTag(UiTags.itemAction("getmusicvideo")).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.itemAction("removemusicvideo")).performClick()
        rule.waitUntil(5_000) { rule.hasText(app.keepfin.core.spaces.MusicVideoRows.DELETE_ASK) }
        rule.onNodeWithText("Keep").performClick()
        assertTrue(h.engine.fakeLibrary.calls.none { it.startsWith("removeMusicVideo") })
        openMenu(sintel)
        rule.onNodeWithTag(UiTags.itemAction("removemusicvideo")).performClick()
        rule.waitUntil(5_000) { rule.hasText(app.keepfin.core.spaces.MusicVideoRows.DELETE_ASK) }
        rule.onNodeWithTag("remove-music-video-confirm").performClick()
        rule.waitUntil(5_000) { h.engine.fakeLibrary.calls.contains("removeMusicVideo:$sintel") }
        rule.waitUntil(5_000) { h.vm.lib.item(sintel)?.musicVideo == false }
    }

    @Test fun theSwitchOnlyAppearsOnASongThatKeepsAVideoAndGoesOnFromTheSameSecond() {
        val h = Harness("library")
        h.engine.fakeLibrary.addSongWithMusicVideo(sintel)
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        val song = h.vm.lib.item(sintel)!!
        val plain = h.vm.lib.item(bunny)!!
        // a song without a video has no switch
        rule.runOnIdle { h.vm.play(listOf(plain), 0) }
        rule.waitForTag(UiTags.MINI_PLAYER)
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performClick()
        rule.waitForTag(UiTags.FULL_PLAYER)
        assertFalse(rule.exists(UiTags.SIDE_SWITCH))
        rule.runOnIdle { h.vm.play(listOf(song), 0) }
        rule.waitForTag(UiTags.SIDE_SWITCH)
        // the song plays; it is at 30 s when Video is chosen
        rule.runOnIdle { h.vm.player.seekTo(30_000) }
        rule.onNodeWithTag(UiTags.SIDE_VIDEO).performClick()
        rule.waitUntil(5_000) { h.vm.player.ui.value.current?.musicVideo == true }
        rule.runOnIdle {
            assertNull("the picture is in the cover's place, not on the video screen", h.vm.video)
            assertTrue("the full player stays open", h.vm.fullPlayer)
            assertEquals("the video goes on from the song's 30 s", 30_000L, h.vm.player.position.value.positionMs)
            assertTrue("the choice is remembered", h.prefs.musicVideo)
            h.vm.player.seekTo(7_000)
        }
        rule.waitForTag(UiTags.PLAYER_PICTURE)
        rule.onNodeWithTag(UiTags.FULL_PLAYER).assertExists()
        // the full-screen button opens the video screen for the same playback; back returns to the picture in place
        rule.onNodeWithTag(UiTags.PLAYER_PICTURE_FULLSCREEN).performClick()
        rule.waitForTag(UiTags.VIDEO_PLAYER)
        rule.runOnIdle { assertEquals(7_000L, h.vm.player.position.value.positionMs); assertTrue(h.vm.player.ui.value.current?.musicVideo == true) }
        rule.runOnIdle { h.vm.closeVideo() }
        rule.waitUntil(5_000) { h.vm.video == null }
        rule.waitForTag(UiTags.PLAYER_PICTURE)
        rule.runOnIdle { assertTrue(h.vm.fullPlayer); assertTrue(h.vm.player.ui.value.current?.musicVideo == true) }
        // back to the song: the cover is back, from the second the video was at (the places are one)
        rule.onNodeWithTag(UiTags.SIDE_SONG).performClick()
        rule.waitUntil(5_000) { h.vm.player.ui.value.current?.musicVideo == false }
        rule.waitUntil(5_000) { !rule.exists(UiTags.PLAYER_PICTURE) }
        rule.runOnIdle {
            assertEquals(7_000L, h.vm.player.position.value.positionMs)
            assertFalse(h.prefs.musicVideo)
        }
        rule.runOnIdle { h.vm.chooseSide(true) }
        rule.waitUntil(5_000) { h.vm.player.ui.value.current?.musicVideo == true }
        rule.runOnIdle { assertEquals(7_000L, h.vm.player.position.value.positionMs) }
        // the queue is the same one on both sides
        assertEquals(listOf(sintel), h.vm.player.ui.value.queue.map { it.id })
    }

    @Test fun whileVideoIsOnASongThatHasOneStartsAsItsVideo() {
        val h = Harness("library")
        h.engine.fakeLibrary.addSongWithMusicVideo(sintel)
        h.engine.fakeLibrary.addSongWithMusicVideo(bunny)
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        val first = h.vm.lib.item(sintel)!!
        val second = h.vm.lib.item(bunny)!!
        // Watch music video from a menu turns Video on and plays it, as the picture in the full player (no video screen)
        rule.runOnIdle { h.vm.watchMusicVideo(first) }
        rule.waitUntil(5_000) { h.vm.player.ui.value.current?.let { it.id == sintel && it.musicVideo } == true }
        rule.waitForTag(UiTags.PLAYER_PICTURE)
        rule.runOnIdle { assertTrue(h.prefs.musicVideo); assertNull(h.vm.video); assertTrue(h.vm.fullPlayer) }
        // another song with a video, played from a list, starts as its video too
        rule.runOnIdle { h.vm.chooseSide(true) }
        rule.runOnIdle { h.vm.play(listOf(second), 0) }
        rule.waitUntil(5_000) { h.vm.player.ui.value.current?.id == bunny && h.vm.player.ui.value.current?.musicVideo == true }
        // it starts as its picture, shown in place when the full player is open (no video screen)
        rule.runOnIdle { assertNull(h.vm.video); h.vm.fullPlayer = true }
        rule.waitForTag(UiTags.PLAYER_PICTURE)
        // full screen and back
        rule.runOnIdle { h.vm.openPictureFullScreen() }
        rule.waitUntil(5_000) { h.vm.video?.fromPlayer == true }
        rule.runOnIdle { h.vm.closeVideo() }
        rule.waitUntil(5_000) { h.vm.video == null }
        rule.runOnIdle { assertTrue(h.vm.player.ui.value.current?.musicVideo == true); assertTrue(h.vm.fullPlayer) }
        // Song brings the cover back with the queue kept
        rule.runOnIdle { h.vm.chooseSide(false) }
        rule.waitUntil(5_000) { !rule.exists(UiTags.PLAYER_PICTURE) }
        rule.runOnIdle {
            assertFalse(h.vm.player.ui.value.current?.musicVideo ?: true)
            assertEquals(bunny, h.vm.player.ui.value.current?.id)
        }
        assertNull(h.vm.video)
    }

    @Test fun backFromFullScreenAfterAHandoverStillReturnsToThePicture() {
        val h = Harness("library")
        h.engine.fakeLibrary.addSongWithMusicVideo(sintel)
        h.engine.fakeLibrary.addSongWithMusicVideo(bunny)
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        val first = h.vm.lib.item(sintel)!!
        val second = h.vm.lib.item(bunny)!!
        rule.runOnIdle { h.vm.play(listOf(first, second), 0) }
        rule.waitUntil(5_000) { h.vm.player.ui.value.current?.id == sintel }
        rule.runOnIdle { h.vm.chooseSide(true); h.vm.fullPlayer = true }
        rule.waitForTag(UiTags.PLAYER_PICTURE)
        rule.runOnIdle { h.vm.openPictureFullScreen() }
        rule.waitUntil(5_000) { h.vm.video?.fromPlayer == true }
        // full screen moves on to the next song with a music video: the request keeps where it came from
        rule.runOnIdle { h.vm.player.next() }
        rule.waitUntil(5_000) { h.vm.video?.id == bunny }
        rule.runOnIdle { assertTrue("the handover keeps fromPlayer", h.vm.video?.fromPlayer == true) }
        rule.runOnIdle { h.vm.closeVideo() }
        rule.waitUntil(5_000) { h.vm.video == null }
        rule.runOnIdle {
            assertTrue(h.vm.fullPlayer)
            assertEquals("Back does not switch recordings", true, h.vm.player.ui.value.current?.musicVideo)
            assertEquals(bunny, h.vm.player.ui.value.current?.id)
            assertTrue("still playing", h.vm.player.ui.value.playing)
        }
    }
}
