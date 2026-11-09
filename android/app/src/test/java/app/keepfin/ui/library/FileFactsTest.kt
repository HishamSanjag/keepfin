package app.keepfin.ui.library

import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import org.junit.Assert.assertEquals
import org.junit.Test

class FileFactsTest {
    private fun item(kind: LibraryItemKind, width: Int? = null, height: Int? = null, duration: Double? = null, size: Long = 1_000_000) =
        LibraryItem(id = "i", space = "s", uri = "content://x", folder = "", kind = kind, title = "t", duration = duration, size = size, modifiedAt = 0, addedAt = 0, width = width, height = height)

    @Test fun aVideoShowsContainerResolutionCodecsAndBitrate() {
        val facts = FileFacts("video/mp4", 1080, 1920, 30.0, "video/avc", "audio/mp4a-latm")
        // 10 MB over 20 s is 4000 kbit/s
        assertEquals(
            listOf("Container" to "MP4", "Resolution" to "1080x1920 · 30 fps", "Video codec" to "H.264", "Audio codec" to "AAC", "Bitrate" to "4 Mbit/s"),
            FileFactsWords.rows(item(LibraryItemKind.VIDEO, duration = 20.0, size = 10_000_000), facts),
        )
    }

    @Test fun theLibrarysOwnResolutionStandsInUntilTheFileAnswers() {
        assertEquals(listOf("Resolution" to "720x1280"), FileFactsWords.rows(item(LibraryItemKind.VIDEO, width = 720, height = 1280), null))
    }

    @Test fun aSongShowsNoVideoRowsAndAFractionalFrameRateKeepsTwoDecimals() {
        val song = FileFacts("audio/mpeg", null, null, null, "video/mjpeg", "audio/mpeg")
        assertEquals(
            listOf("Container" to "MP3", "Audio codec" to "MP3", "Bitrate" to "320 kbit/s"),
            FileFactsWords.rows(item(LibraryItemKind.AUDIO, duration = 100.0, size = 4_000_000), song),
        )
        assertEquals("Resolution" to "1280x720 · 29.97 fps", FileFactsWords.rows(item(LibraryItemKind.VIDEO), FileFacts(width = 1280, height = 720, fps = 29.97)).single())
    }

    @Test fun anUnknownContainerIsLeftOut() {
        assertEquals(null, FileFactsWords.container("video/x-unknown"))
        assertEquals("WEBM", FileFactsWords.container("video/webm; codecs=vp9"))
    }
}
