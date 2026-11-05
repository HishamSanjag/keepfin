package app.keepfin

import org.junit.Assert.*
import org.junit.Test

class ProgressParserTest {
    private fun line(status: String, done: Long, total: String, estimate: String, speed: String, eta: String, format: String) =
        "${ArgsPolicy.PROGRESS_PREFIX} $status|$done|$total|$estimate|$speed|$eta|$format"

    @Test fun nativeTemplateSingleStream() {
        val parser = ProgressParser()
        assertTrue(parser.accept(line("downloading", 5_000_000, "10000000", "NA", "2500000.5", "2", "18")))
        val s = parser.snapshot
        assertEquals(50f, s.percent, 0.5f)
        assertTrue(Math.abs(s.speed!! - 2_500_000L) <= 1)
        assertEquals(2L, s.eta)
        assertEquals(5_000_000L, s.downloadedBytes)
        assertEquals(10_000_000L, s.totalBytes)
        assertFalse(s.processing)
        assertTrue(parser.sawTransfer)
    }

    @Test fun twoStreamsAggregate() {
        val parser = ProgressParser(expectedSize = null)
        parser.accept("[info] abc: Downloading 1 format(s): 299+140")
        parser.accept("[download] Destination: /x/a.f299.mp4")
        parser.accept(line("downloading", 100, "200", "NA", "10", "10", "299"))
        parser.accept(line("finished", 200, "200", "NA", "NA", "NA", "299"))
        parser.accept("[download] Destination: /x/a.f140.m4a")
        parser.accept(line("downloading", 25, "100", "NA", "10", "10", "140"))
        val s = parser.snapshot
        // Weighted by bytes once every stream's size is known: 225 of 300, not (1 + 0.25) / 2.
        assertEquals(75f, s.percent, 1f)
        assertEquals(225L, s.downloadedBytes)
    }

    @Test fun expectedSizeDrivesPercentAndEta() {
        val parser = ProgressParser(expectedSize = 1_000_000)
        parser.accept(line("downloading", 250_000, "NA", "NA", "50000", "NA", "299"))
        val s = parser.snapshot
        assertEquals(25f, s.percent, 0.5f)
        assertEquals(15L, s.eta)
        assertEquals(1_000_000L, s.totalBytes)
    }

    @Test fun percentNeverGoesBackwardsAndCapsAt99() {
        val parser = ProgressParser(expectedSize = 100)
        parser.accept(line("downloading", 60, "NA", "NA", "1", "NA", "1"))
        parser.accept(line("downloading", 40, "NA", "NA", "1", "NA", "1"))
        assertEquals(60f, parser.snapshot.percent, 0.1f)
        parser.accept(line("downloading", 100, "NA", "NA", "1", "NA", "1"))
        assertEquals(99f, parser.snapshot.percent, 0.1f)
    }

    @Test fun aria2cLines() {
        val parser = ProgressParser()
        assertTrue(parser.accept("[#2089b0 12MiB/150MiB(8%) CN:8 DL:5.2MiB ETA:26s]"))
        val s = parser.snapshot
        assertTrue(parser.usedAria2c)
        assertEquals(12L * 1024 * 1024, s.downloadedBytes)
        assertEquals(150L * 1024 * 1024, s.totalBytes)
        assertTrue(Math.abs(s.speed!! - (5.2 * 1024 * 1024).toLong()) <= 2)
        assertEquals(26L, s.eta)
        assertEquals(8f, s.percent, 0.5f)
        assertTrue(parser.accept("[#2089b0 1.5GiB/2GiB(75%) CN:8 DL:12MiB ETA:1m2s]"))
        assertEquals(62L, parser.snapshot.eta)
    }

    @Test fun phases() {
        val parser = ProgressParser()
        parser.accept(line("finished", 10, "10", "NA", "NA", "NA", "1"))
        assertTrue(parser.accept("[Merger] Merging formats into \"/x/a.mp4\""))
        assertEquals("Merging", parser.snapshot.phase)
        assertTrue(parser.snapshot.processing)
        assertNull(parser.snapshot.speed)
        assertTrue(parser.accept("[ExtractAudio] Destination: /x/a.mp3"))
        assertEquals("Converting to MP3", parser.snapshot.phase)
        parser.accept("[EmbedThumbnail] ffmpeg: Adding thumbnail")
        assertEquals("Embedding cover art", parser.snapshot.phase)
        assertFalse(parser.accept("[youtube] Extracting URL: https://x"))
    }

    @Test fun ignoresGarbage() {
        val parser = ProgressParser()
        assertFalse(parser.accept(""))
        assertFalse(parser.accept("WARNING: something"))
        assertFalse(parser.accept("${ArgsPolicy.PROGRESS_PREFIX} too|few"))
        assertFalse(parser.sawTransfer)
        assertNull(parser.snapshot.downloadedBytes)
    }
}
