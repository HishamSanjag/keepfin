package app.keepfin.ui.player

import app.keepfin.ui.library.VideoRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicVideoLogicTest {
    private fun entry(id: String, musicVideo: Boolean = false, resolved: Boolean = true) =
        QueueEntry(id, "T", "A", 1000L, video = musicVideo, musicVideo = musicVideo, resolved = resolved)

    @Test fun aSwitchGoesOnFromTheSameSecondAndStopsAtTheEndOfTheShorterSide() {
        // the rule is the core's (Songs.switchStart) and runs from the fixtures on both apps; this pins how the phone calls it
        assertEquals(75.0, app.keepfin.core.spaces.Songs.switchStart(75.0, 240.0), 0.0)
        assertEquals(75.0, app.keepfin.core.spaces.Songs.switchStart(75.0, null), 0.0)
        // the other side is shorter: it plays its last second and finishes, so the shorter side ends the pair
        assertEquals(179.0, app.keepfin.core.spaces.Songs.switchStart(200.0, 180.0), 0.0)
        assertEquals(0.0, app.keepfin.core.spaces.Songs.switchStart(-3.0, 180.0), 0.0)
    }

    @Test fun theVideoScreenFollowsTheQueue() {
        val video = VideoRequest("a", 0, false, musicVideo = true)
        // a song that starts as its video opens it for someone in a player, and not for someone elsewhere in the app
        // with no video screen open the picture shows in place in the full player: nothing to open
        assertEquals(MusicVideoLogic.Follow.Nothing, MusicVideoLogic.follow(entry("a", true), null, onPlayer = true))
        assertEquals(MusicVideoLogic.Follow.Nothing, MusicVideoLogic.follow(entry("a", true), null, onPlayer = false))
        // already showing it
        assertEquals(MusicVideoLogic.Follow.Nothing, MusicVideoLogic.follow(entry("a", true), video, onPlayer = true))
        // the queue moved on to another song with a video: its video opens
        assertEquals(MusicVideoLogic.Follow.Open("b"), MusicVideoLogic.follow(entry("b", true), video, onPlayer = false))
        // the next song has none (or the file is missing): back to the music player
        assertEquals(MusicVideoLogic.Follow.Close, MusicVideoLogic.follow(entry("b", false), video, onPlayer = false))
        // an ordinary video is not touched, and a placeholder says nothing about itself
        assertEquals(MusicVideoLogic.Follow.Nothing, MusicVideoLogic.follow(entry("v", false), VideoRequest("v", 0, false), onPlayer = true))
        assertEquals(MusicVideoLogic.Follow.Nothing, MusicVideoLogic.follow(entry("b", false, resolved = false), video, onPlayer = true))
        assertEquals(MusicVideoLogic.Follow.Nothing, MusicVideoLogic.follow(null, video, onPlayer = true))
    }

    @Test fun viewsAndLengthReadLikeTheSheetShowsThem() {
        assertEquals("3.2B views", MusicVideoLogic.views(3_200_000_000L))
        assertEquals("12M views", MusicVideoLogic.views(12_400_000L))
        assertEquals("1.5M views", MusicVideoLogic.views(1_500_000L))
        assertEquals("512K views", MusicVideoLogic.views(512_300L))
        assertEquals("1K views", MusicVideoLogic.views(1_000L))
        assertEquals("812 views", MusicVideoLogic.views(812L))
        assertEquals("1 view", MusicVideoLogic.views(1L))
        assertNull(MusicVideoLogic.views(null))
        assertEquals("3:35", MusicVideoLogic.length(215.4))
        assertEquals("0:09", MusicVideoLogic.length(9.0))
        assertEquals("1:02:03", MusicVideoLogic.length(3723.0))
        assertNull(MusicVideoLogic.length(null))
        assertNull(MusicVideoLogic.length(0.0))
    }
}
