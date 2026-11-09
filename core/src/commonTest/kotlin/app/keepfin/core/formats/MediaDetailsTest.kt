package app.keepfin.core.formats

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaDetailsTest {
    @Test
    fun codecsAreNamedTheSameWhicheverAppReadThem() {
        assertEquals("H.264", MediaDetails.codecName("h264"))
        assertEquals("H.264", MediaDetails.codecName("video/avc"))
        assertEquals("H.265 (HEVC)", MediaDetails.codecName("hevc"))
        assertEquals("H.265 (HEVC)", MediaDetails.codecName("video/hevc"))
        assertEquals("VP9", MediaDetails.codecName("video/x-vnd.on2.vp9"))
        assertEquals("VP9", MediaDetails.codecName("vp9"))
        assertEquals("AV1", MediaDetails.codecName("video/av01"))
        assertEquals("AAC", MediaDetails.codecName("audio/mp4a-latm"))
        assertEquals("AAC", MediaDetails.codecName("aac (LC)"))
        assertEquals("AAC", MediaDetails.codecName("mp4a.40.2"))
        assertEquals("Opus", MediaDetails.codecName("audio/opus"))
        assertEquals("MP3", MediaDetails.codecName("audio/mpeg"))
        assertEquals("PCM", MediaDetails.codecName("pcm_s16le"))
    }

    @Test
    fun anUnknownCodecIsShownInCapitalsAndNothingIsShownForNothing() {
        assertEquals("WMV3", MediaDetails.codecName("wmv3"))
        assertEquals("XYZ", MediaDetails.codecName("video/xyz"))
        assertNull(MediaDetails.codecName(""))
        assertNull(MediaDetails.codecName(null))
    }

    @Test
    fun resolutionIsWidthByHeight() {
        assertEquals("1080x1920", MediaDetails.resolution(1080, 1920))
        assertNull(MediaDetails.resolution(1080, null))
        assertNull(MediaDetails.resolution(0, 1920))
    }

    @Test
    fun bitrateIsInKilobitsUnderAThousandAndMegabitsAbove() {
        assertEquals("320 kbit/s", MediaDetails.bitrate(320.0))
        assertEquals("999 kbit/s", MediaDetails.bitrate(998.6))
        assertEquals("2.4 Mbit/s", MediaDetails.bitrate(2400.0))
        assertEquals("2.5 Mbit/s", MediaDetails.bitrate(2450.0))
        assertEquals("8 Mbit/s", MediaDetails.bitrate(8000.0))
        assertEquals("", MediaDetails.bitrate(0.0))
    }
}
