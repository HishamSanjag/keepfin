package app.keepfin.core

import app.keepfin.core.progress.PhaseKey
import app.keepfin.core.progress.ProgressChange
import app.keepfin.core.progress.ProgressParser
import app.keepfin.core.progress.ProgressPlatform
import app.keepfin.core.progress.parseCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Behaviour the fixtures do not spell out: number reading, the wire formats' details and what a line reports as changed. */
class ProgressParserTest {
    private fun desktop(vararg sizes: Pair<String, Double>) = ProgressParser(ProgressPlatform.DESKTOP, streamSizes = mapOf(*sizes))

    @Test
    fun countsReadLikeJavaScriptNumbers() {
        assertEquals(12.0, parseCount("12"))
        assertEquals(0.5, parseCount(".5"))
        assertEquals(5.0, parseCount("5."))
        assertEquals(1.5e-5, parseCount("1.5e-05"))
        assertEquals(7.0, parseCount(" 7 "))
        for (bad in listOf("", "NA", "None", "-1", "abc", "1e", "1.2.3", "Infinity", "NaN", "0x10", "1e999")) assertNull(parseCount(bad), bad)
    }

    @Test
    fun desktopLineIsReadByPosition() {
        val parser = desktop()
        assertEquals(ProgressChange.PROGRESS, parser.accept("KEEPFIN-P|18|downloading|250|1000|NA|50.5|15|2|10"))
        val s = parser.snapshot
        assertEquals(25.0, s.percent)
        assertEquals(250.0, s.downloadedBytes)
        assertEquals(1000.0, s.totalBytes)
        assertEquals(50.5, s.speed)
        assertEquals((1000.0 - 250.0) / 50.5, s.eta)  // known size and speed: the core recomputes the eta
    }

    @Test
    fun androidStatusIsTrimmedSoFinishedStreamsAreFinished() {
        val parser = ProgressParser(ProgressPlatform.ANDROID)
        parser.accept("[info] a: Downloading 1 format(s): 1+2")
        parser.accept("[keepfin] finished|10|NA|NA|NA|NA|1")
        // Stream 1 finished without a known size, stream 2 has not started: half.
        assertEquals(50.0, parser.snapshot.percent)
    }

    @Test
    fun androidLineNeedsSevenFields() {
        val parser = ProgressParser(ProgressPlatform.ANDROID)
        assertEquals(ProgressChange.NONE, parser.accept("[keepfin] too|few"))
        assertEquals(false, parser.sawTransfer)
        assertNull(parser.snapshot.downloadedBytes)
    }

    @Test
    fun androidWithoutAFormatIdIsOneStream() {
        val parser = ProgressParser(ProgressPlatform.ANDROID)
        parser.accept("[keepfin] downloading|50|100|NA|NA|NA|NA")
        parser.accept("[keepfin] downloading|60|100|NA|NA|NA|NA")
        assertEquals(60.0, parser.snapshot.percent)
    }

    @Test
    fun formatLineVariants() {
        val parser = desktop("299" to 800.0, "140" to 200.0)
        assertEquals(ProgressChange.NONE, parser.accept("[info] abc: Downloading 1 format(s): 299+140"))
        parser.accept("KEEPFIN-P|299|downloading|400|NA|NA|NA|NA|NA|NA")
        assertEquals(40.0, parser.snapshot.percent)
        // The id part may itself contain a colon; a line that is not a formats line changes nothing.
        val other = desktop()
        other.accept("[info] a: b: Downloading 2 format(s): 1+2")
        other.accept("[info] abc: Downloading format(s): 1+2")
        other.accept("KEEPFIN-P|1|downloading|50|100|NA|NA|NA|NA|NA")
        assertEquals(25.0, other.snapshot.percent)
    }

    @Test
    fun aLineReportsWhatItChanged() {
        val parser = desktop()
        assertEquals(ProgressChange.NONE, parser.accept(""))
        assertEquals(ProgressChange.NONE, parser.accept("plain log text"))
        assertEquals(ProgressChange.NONE, parser.accept("[youtube] Extracting URL: https://x"))
        assertEquals(ProgressChange.PROGRESS, parser.accept("KEEPFIN-P|1|finished|10|10|NA|NA|NA|NA|NA"))
        assertEquals(ProgressChange.PHASE, parser.accept("[Merger] Merging formats into \"/x/a.mp4\""))
        assertEquals(ProgressChange.NONE, parser.accept("[Merger] Merging formats into \"/x/a.mp4\""))
        assertEquals(PhaseKey.MERGE, parser.snapshot.phase)
        assertTrue(parser.snapshot.processing)
        assertNull(parser.snapshot.speed)
    }

    @Test
    fun audioConversionNamesItsExtension() {
        val parser = ProgressParser(ProgressPlatform.ANDROID)
        parser.accept("[ExtractAudio] Destination: /x/a.mp3")
        assertEquals(PhaseKey.EXTRACT_AUDIO, parser.snapshot.phase)
        assertEquals("mp3", parser.snapshot.phaseDetail)
        assertEquals(ProgressChange.PHASE, parser.accept("[ExtractAudio] Not converting audio /x/a.m4a; the file is already in a compatible format"))
        assertNull(parser.snapshot.phaseDetail)
    }

    @Test
    fun androidDestinationClearsThePhase() {
        val parser = ProgressParser(ProgressPlatform.ANDROID)
        parser.accept("[Merger] Merging formats into \"/x/a.mp4\"")
        assertEquals(ProgressChange.PHASE, parser.accept("[download] Destination: /x/b.m4a"))
        assertNull(parser.snapshot.phase)
        assertEquals(ProgressChange.NONE, parser.accept("[download] Destination: /x/c.m4a"))
    }

    @Test
    fun percentStopsBelowOneHundred() {
        val parser = desktop("1" to 100.0)
        parser.accept("[info] a: Downloading 1 format(s): 1")
        parser.accept("KEEPFIN-P|1|finished|100|100|NA|NA|NA|NA|NA")
        assertEquals(99.5, parser.snapshot.percent)
    }
}
