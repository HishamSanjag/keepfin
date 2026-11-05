package app.keepfin.ui.player

import app.keepfin.LyricLine
import app.keepfin.Lyrics
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsPreviewTest {
    private val synced = Lyrics(synced = true, lines = listOf(LyricLine(1_000, "One"), LyricLine(2_000, ""), LyricLine(3_000, "Two"), LyricLine(4_000, "Three")))

    @Test fun showsTheLineBeingSungAndTheNextOne() = assertEquals(listOf("Two", "Three"), previewLines(synced, 3_500))

    @Test fun startsAtTheFirstLineBeforeTheSinging() = assertEquals(listOf("One", "Two"), previewLines(synced, 0))

    @Test fun anInstrumentalGapShowsTheNextWordsInsteadOfNothing() = assertEquals(listOf("Two", "Three"), previewLines(synced, 2_500))

    @Test fun theLastLineStandsAlone() = assertEquals(listOf("Three"), previewLines(synced, 9_000))

    @Test fun untimedLyricsStayOnTheirFirstLines() {
        val untimed = Lyrics(synced = false, lines = listOf(LyricLine(null, "One"), LyricLine(null, "Two"), LyricLine(null, "Three")))
        assertEquals(listOf("One", "Two"), previewLines(untimed, 60_000))
    }
}
