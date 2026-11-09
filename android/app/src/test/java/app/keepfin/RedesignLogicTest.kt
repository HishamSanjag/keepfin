package app.keepfin

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.keepfin.ui.FormatChoice
import app.keepfin.ui.downloads.choiceSize
import app.keepfin.ui.library.Shelves
import app.keepfin.ui.library.ShelfShape
import app.keepfin.ui.library.coverInitials
import app.keepfin.ui.player.LIGHT_WASH_SCRIM
import app.keepfin.ui.player.OnWash
import app.keepfin.ui.player.OnWashDim
import app.keepfin.ui.player.WASH_SCRIM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic behind the Cobalt Atelier restyle: audio sizes, single-shape shelves, fallback covers and the player wash contrast. */
class RedesignLogicTest {
    private val MB = 1_000_000L

    private fun video(id: String, h: Int, fps: Int? = 30, hdr: Boolean = false, size: Long? = 10 * MB, approx: Boolean = false) =
        VideoOption(id, h, null, fps, hdr, "h264", "mp4", "f$id", false, size, approx, null)

    private fun audioOpt(id: String, abr: Double?, size: Long? = 4 * MB) = AudioOption(id, id, "opus", "webm", abr, size, false, null, null)

    private fun media(video: List<VideoOption>, audio: List<AudioOption> = emptyList(), duration: Double? = 240.0) = MediaInfo(
        url = "https://example.com/v", webpageUrl = null, title = "T", uploader = null, duration = duration, thumbnail = null, extractor = null,
        provider = "Example", isLive = false, music = null, video = video, audio = audio, subtitles = emptyList(), chapters = 0, playlistUrl = null,
    )

    @Test fun aSongOpensOnMp3At320WhateverTheSourceBitrate() {
        for (kbps in listOf(160.0, 320.0, null)) {
            val m = media(emptyList(), listOf(audioOpt("a251", kbps)))
            val choice = FormatChoice.initial(m, app.keepfin.DownloadPreset.MP3)
            assertEquals("mp3", choice.audioTarget); assertEquals("320", choice.audioQuality); assertTrue(choice.audioMode)
        }
    }

    @Test fun theDownloadButtonNamesTheSizeOfTheSelectedAudioRow() {
        val m = media(emptyList(), listOf(audioOpt("a251", 320.0, 9 * MB)), duration = 240.0)
        val mp3 = FormatChoice(audioMode = true, audioTarget = "mp3")
        assertEquals("about 9.2 MB", choiceSize(m, mp3.copy(audioQuality = "320")))
        assertEquals("about 3.7 MB", choiceSize(m, mp3.copy(audioQuality = "128")))
        // Best is the 245 kbps estimate, like the desktop's (one size rule, dev-docs/plans/DOWNLOAD-CHOICE.md)
        assertEquals("about 7.0 MB", choiceSize(m, mp3.copy(audioQuality = "best")))
        assertEquals("8.6 MB", choiceSize(m, FormatChoice(audioMode = true, audioTarget = "original")))
        assertNull(choiceSize(m, FormatChoice(audioMode = true, audioTarget = "flac")))
    }

    @Test fun downloadButtonSizeIsJustTheEngineEstimate() {
        val m = media(listOf(video("v1080", 1080, size = 145 * MB, approx = true)))
        assertEquals("about 138 MB", choiceSize(m, FormatChoice(videoId = "v1080")))
        assertNull(choiceSize(media(listOf(video("v1080", 1080, size = null))), FormatChoice(videoId = "v1080")))
    }

    private fun item(id: String, kind: LibraryItemKind) = LibraryItem(id = id, space = "s", uri = id, folder = "", kind = kind, title = id, size = 1, modifiedAt = 0, addedAt = 0)

    @Test fun shelvesNeverMixVideoAndSquareArtwork() {
        val items = listOf(item("v1", LibraryItemKind.VIDEO), item("s1", LibraryItemKind.AUDIO), item("v2", LibraryItemKind.VIDEO), item("p1", LibraryItemKind.IMAGE))
        val groups = Shelves.split(items)
        assertEquals(listOf(ShelfShape.VIDEO, ShelfShape.SQUARE), groups.map { it.shape })
        assertEquals(listOf("v1", "v2"), groups[0].items.map { it.id })
        assertEquals(listOf("s1", "p1"), groups[1].items.map { it.id })
        assertEquals("videos", groups[0].noun)
        assertEquals("music and photos", groups[1].noun)
        assertTrue(groups.all { g -> g.items.all { Shelves.shapeOf(it) == g.shape } })
    }

    @Test fun aSingleKindStaysOneShelfInItsOrder() {
        assertEquals(1, Shelves.split(listOf(item("a", LibraryItemKind.AUDIO), item("b", LibraryItemKind.AUDIO))).size)
        assertEquals("music", Shelves.split(listOf(item("a", LibraryItemKind.AUDIO))).single().noun)
        assertEquals("photos", Shelves.split(listOf(item("a", LibraryItemKind.IMAGE))).single().noun)
        assertTrue(Shelves.split(emptyList()).isEmpty())
    }

    @Test fun fallbackCoversUseInitials() {
        assertEquals("GH", coverInitials("Glass Harbour"))
        assertEquals("B", coverInitials("Blue"))
        assertEquals("AW", coverInitials("A Walk Through Kyoto"))
        assertEquals("42", coverInitials("4 2"))
        assertNull(coverInitials("  --  "))
    }

    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble(); val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    @Test fun playerTextClearsAaOverTheBrightestPossibleWash() {
        // Worst case: a pure white cover under the constant scrim.
        val level = 1f - WASH_SCRIM
        val worst = Color(level, level, level)
        assertTrue("primary text ${contrast(OnWash, worst)}", contrast(OnWash, worst) >= 4.5)
        assertTrue("secondary text ${contrast(OnWashDim, worst)}", contrast(OnWashDim, worst) >= 4.5)
        // A dimmed lyric line (alpha floor 0.7 of OnWash) over that same worst case.
        val dimmed = Color(
            0.7f * OnWash.red + 0.3f * worst.red, 0.7f * OnWash.green + 0.3f * worst.green, 0.7f * OnWash.blue + 0.3f * worst.blue,
        )
        assertTrue("dim lyric line ${contrast(dimmed, worst)}", contrast(dimmed, worst) >= 4.5)
    }

    @Test fun playerTextClearsAaOverTheDarkestPossibleLightWash() {
        // Worst case in the light theme: a pure black cover under the surface-colour scrim; text colours are the light scheme's onSurface and onSurfaceVariant.
        val surface = Color(0xFFF5F6F8)
        val worst = Color(surface.red * LIGHT_WASH_SCRIM, surface.green * LIGHT_WASH_SCRIM, surface.blue * LIGHT_WASH_SCRIM)
        assertTrue("primary text ${contrast(Color(0xFF151821), worst)}", contrast(Color(0xFF151821), worst) >= 4.5)
        assertTrue("secondary text ${contrast(Color(0xFF4A5263), worst)}", contrast(Color(0xFF4A5263), worst) >= 4.5)
    }

    @Test fun aDirectFileHasNoHeightToAskFor() {
        val option = VideoOption("original", 0, null, null, false, "other", "mp4", "mp4", true, null, true, null)
        val file = app.keepfin.MediaInfo("https://example.com/clip.mp4", null, "clip", null, null, null, null, "Link", false, null, listOf(option), emptyList(), emptyList(), 0, null)
        val request = FormatChoice(videoId = "original").request(file) as app.keepfin.FormatRequest.Video
        assertNull(request.maxHeight)
        assertEquals("Original", app.keepfin.ui.Format.videoLabel(option))
    }
}
