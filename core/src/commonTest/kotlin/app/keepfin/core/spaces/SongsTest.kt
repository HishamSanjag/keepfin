package app.keepfin.core.spaces

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SongsTest {
    @Test
    fun audioIsASongAnywhere() {
        for (space in listOf("music", "video", "mixed", null)) assertTrue(Songs.isSong("audio", space))
    }

    @Test
    fun aVideoIsASongOnlyInAMusicSpace() {
        assertTrue(Songs.isSong("video", "music"))
        assertTrue(Songs.isVideoSong("video", "music"))
        for (space in listOf("video", "mixed", null)) {
            assertFalse(Songs.isSong("video", space))
            assertFalse(Songs.isVideoSong("video", space))
        }
        assertFalse(Songs.isVideoSong("audio", "music"))
    }

    @Test
    fun picturesAndOtherFilesAreNeverSongs() {
        for (kind in listOf("image", "other")) assertFalse(Songs.isSong(kind, "music"))
    }
}
