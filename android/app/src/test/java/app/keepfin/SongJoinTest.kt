package app.keepfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A video download of a song you have joins that song (dev-docs/plans/MUSIC-VIDEOS.md). The match itself is tested in MusicVideoRepositoryTest. */
class SongJoinTest {
    private val song = SongOfLink("item-1", "downloads", hasVideo = false)
    private val video = FormatRequest.Video(maxHeight = 1080, container = "mkv")
    private val options = DownloadOptions(
        subtitles = SubtitleOptions(enabled = true, languages = "en"), sectionStart = 10.0, sectionEnd = 50.0, writeThumbnail = true, embedThumbnail = true,
    )

    @Test fun aVideoOfASongYouHaveIsKeptBesideThatSongInItsSpace() {
        val join = SongJoin.of(song, videoComing = false, video, options)!!
        assertEquals("item-1", join.itemId)
        assertEquals("downloads", join.space)
    }

    @Test fun anMkvBecomesAnMp4AndNothingThatWritesExtraFilesOrCutsTheVideoIsKept() {
        val join = SongJoin.of(song, false, video, options)!!
        assertEquals("mp4", join.format.container)
        assertEquals(1080, join.format.maxHeight)
        assertEquals(SubtitleOptions(), join.options.subtitles)
        assertNull(join.options.sectionStart)
        assertNull(join.options.sectionEnd)
        assertEquals(false, join.options.writeThumbnail)
        assertTrue("the rest of the user's options stay", join.options.embedThumbnail)
        assertEquals("webm stays webm", "webm", SongJoin.of(song, false, FormatRequest.Video(container = "webm"), options)!!.format.container)
    }

    @Test fun anythingElseIsNotAJoin() {
        assertNull(SongJoin.of(null, false, video, options))
        assertNull(SongJoin.of(song, false, FormatRequest.Audio(), options))
    }

    @Test fun aSongThatHasItsVideoOrIsGettingOneRefusesAnotherInWords() {
        val has = assertThrows(IllegalStateException::class.java) { SongJoin.of(song.copy(hasVideo = true), false, video, options) }
        assertEquals("This song already has its music video.", has.message)
        assertEquals("This song already has its music video.", assertThrows(IllegalStateException::class.java) { SongJoin.of(song, videoComing = true, video, options) }.message)
        assertNotNull(SongJoin.of(song, false, video, options))
    }
}
