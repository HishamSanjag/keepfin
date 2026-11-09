package app.keepfin.core.formats

import app.keepfin.core.args.DownloadFormat
import app.keepfin.core.args.DownloadPlan
import app.keepfin.core.choice.Choice
import app.keepfin.core.choice.ChoiceMedia
import app.keepfin.core.choice.ChoiceVideo
import app.keepfin.core.choice.DownloadChoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversionTest {
    private fun served(height: Int, width: Int? = height, size: Long? = 10_000_000, hdr: Boolean = false, fps: Int? = 30, id: String = "f$height") =
        VideoOption("${height}p", height, width, fps, hdr, "h264", "mp4", id, true, size, false, 1000.0)

    private fun heights(options: List<VideoOption>) = options.map { it.height }

    @Test
    fun instagramLikeFiveFortyAndSevenTwentyOffersFourEightyAndThreeSixty() {
        val extra = Conversion.options(listOf(served(720), served(540)), 20.0)
        assertEquals(listOf(480, 360), heights(extra))
        assertEquals(listOf(540, 540), extra.map { it.convertedFrom })
        val first = extra[0]
        assertEquals("480p-converted", first.id)
        assertEquals("f540", first.formatId)
        assertEquals("h264", first.codec)
        assertEquals("mp4", first.ext)
        assertTrue(first.sizeApprox)
        assertTrue(first.hasAudio)
        assertEquals(480, first.width)
    }

    @Test
    fun aSizeWithinTenPercentOfTheLadderIsNotOffered() {
        // 284 / 364 / 728 / 1092: 1080 and 720 and 360 each have a served size within 10%, only 480 is not.
        val extra = Conversion.options(listOf(served(1092), served(728), served(364), served(284)), 30.0)
        assertEquals(listOf(480), heights(extra))
        assertEquals(728, extra[0].convertedFrom)
    }

    @Test
    fun aSiteWithAFullLadderGetsNone() {
        val ladder = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144).map { served(it) }
        assertEquals(emptyList(), Conversion.options(ladder, 60.0))
    }

    @Test
    fun hdrSourcesGetNone() {
        assertEquals(emptyList(), Conversion.options(listOf(served(720, hdr = true), served(540)), 20.0))
    }

    @Test
    fun neverUpscalesAndOffersAtMostThree() {
        assertEquals(emptyList(), Conversion.options(listOf(served(360)), 20.0))
        assertEquals(listOf(360), heights(Conversion.options(listOf(served(540), served(720)), 20.0)).takeLast(1))
        assertEquals(listOf(1080, 720, 480), heights(Conversion.options(listOf(served(2160)), 20.0)))
    }

    @Test
    fun fpsVariantsOfOneSizeCountOnce() {
        val extra = Conversion.options(listOf(served(720, fps = 60), served(720, fps = 30), served(540)), 20.0)
        assertEquals(listOf(480, 360), heights(extra))
    }

    @Test
    fun theWidthKeepsTheAspectAndIsEven() {
        val extra = Conversion.options(listOf(served(1080, width = 1920), served(720, width = 1280)), 20.0)
        // 1080 and 720 are served; 480 comes from 720 (1280x720 -> 854 -> 854), 360 from 720 (640).
        assertEquals(listOf(480, 360), heights(extra))
        assertEquals(listOf(854, 640), extra.map { it.width })
    }

    @Test
    fun estimateIsBitrateTimesDurationButNeverMoreThanTheSource() {
        val source = served(540, size = 50_000_000)
        // (1200 + 128) kbit/s * 20 s / 8 = 3,320,000 bytes
        assertEquals(3_320_000L, Conversion.estimate(480, source, 20.0))
        assertEquals(1_000L, Conversion.estimate(480, served(540, size = 1_000), 20.0))
    }

    @Test
    fun aConvertedSizeIsEstimatedLighterThanALowBitrateSource() {
        // X serves 720p at a low bitrate: 3,000,000 bytes over 20 s carries 1072 kbit/s of video, so 480p is made at 80% of it
        // (857), not at the 1200 of the table, and the estimate stays below the source instead of equalling it.
        // (857 + 128) * 1000 * 20 / 8 = 2,462,500
        assertEquals(2_462_500L, Conversion.estimate(480, served(720, size = 3_000_000), 20.0))
    }

    @Test
    fun estimateFallsBackToTheSourceBitrateForTheDuration() {
        // 10,000,000 bytes at 1000 kbit/s is 80 s; the source carries 872 kbit/s of video, 80% is 697 (below 700):
        // (697 + 128) * 1000 * 80 / 8 = 8,250,000
        assertEquals(8_250_000L, Conversion.estimate(360, served(540), null))
        assertNull(Conversion.estimate(360, VideoOption("540p", 540, 540, 30, false, "h264", "mp4", "f", true, null, true, null), null))
    }

    private fun lightAudio(size: Long = 188_207, abr: Double = 44.6) = AudioOption("a", "a", "aac", "m4a", abr, size, false, null, null)

    @Test
    fun withoutALengthTheAudioStreamSaysHowLongTheClipIs() {
        // An Instagram reel: the 720p video-only stream plus its audio weigh 1,885,997 bytes (a size covers the best audio) and the
        // manifest says 405 kbit/s (the clip really runs 33.6 s; the video alone would give 37.2 s, the audio gives 33.8 s: 188,207
        // bytes at 44.6 kbit/s). 480p is made at 80% of (1,885,997 * 8 / 33.76 s - 128) = 255 kbit/s and the light audio is kept:
        // 255 * 33.76 / 8 * 1000 + 188,207 = 1,264,000 bytes (the real conversion came to 1,363,243, 8% more).
        val source = VideoOption("720p", 720, 728, 30, false, "vp9", "mp4", "v", false, 1_885_997, false, 405.0)
        val size = Conversion.estimate(480, source, null, lightAudio())!!
        assertEquals(1_264_000L, size / 1000 * 1000)
    }

    @Test
    fun aManifestPeakBitrateDoesNotShortenTheEstimate() {
        // The video claims 3000 kbit/s but its 1,000,000 bytes (audio included) are spread over 24 s: worked out from the video the
        // clip is 2.7 s long and the estimate 443,000 bytes. The audio (134,400 bytes at 44.8 kbit/s = 24 s) gives the real length:
        // 80% of (1,000,000 * 8 / 24 - 128) = 164 kbit/s -> 164 * 24 / 8 * 1000 + 134,400 = 626,400 bytes.
        val source = VideoOption("540p", 540, 540, 30, false, "vp9", "mp4", "v", false, 1_000_000, false, 3000.0)
        val audio = lightAudio(size = 134_400, abr = 44.8)
        assertEquals(626_400L, Conversion.estimate(480, source, null, audio))
        assertTrue(Conversion.estimate(480, source, null)!! < 450_000)
    }
    @Test
    fun aSourceWithItsOwnAudioIsNotGivenTheSeparateStream() {
        assertEquals(Conversion.estimate(480, served(540, size = 50_000_000), 20.0), Conversion.estimate(480, served(540, size = 50_000_000), 20.0, lightAudio()))
    }

    @Test
    fun mediaInfoAddsConvertedSizesOnlyWhenAsked() {
        val info = "{\"title\":\"x\",\"duration\":20,\"formats\":[" +
            "{\"format_id\":\"a\",\"protocol\":\"https\",\"vcodec\":\"avc1\",\"acodec\":\"aac\",\"ext\":\"mp4\",\"width\":720,\"height\":1280,\"filesize\":9000000}," +
            "{\"format_id\":\"b\",\"protocol\":\"https\",\"vcodec\":\"avc1\",\"acodec\":\"aac\",\"ext\":\"mp4\",\"width\":540,\"height\":960,\"filesize\":6000000}]}"
        assertEquals(listOf(720, 540), Formats.mediaInfo("https://www.instagram.com/reel/x/", info).video.map { it.height })
        val media = Formats.mediaInfo("https://www.instagram.com/reel/x/", info, FormatOptions(convertSmaller = true))
        assertEquals(listOf(720, 540, 480, 360), media.video.map { it.height })
        assertEquals(listOf(null, null, 540, 540), media.video.map { it.convertedFrom })
        assertEquals("b", media.video[2].formatId)
    }

    @Test
    fun argumentsConvertToTheShortSideWithEvenDimensions() {
        val args = Conversion.args("in.mp4", "out.mp4", 360, 700)
        assertEquals(listOf("-hide_banner", "-nostdin", "-i", "in.mp4"), args.take(4))
        assertTrue("scale=w='if(gt(iw,ih),-2,360)':h='if(gt(iw,ih),360,-2)'" in args)
        for (pair in listOf("-c:v" to "libx264", "-preset" to "veryfast", "-b:v" to "700k", "-maxrate" to "1050k", "-bufsize" to "1400k", "-c:a" to "aac", "-b:a" to "128k", "-movflags" to "+faststart", "-progress" to "pipe:1")) {
            assertEquals(pair.second, args[args.indexOf(pair.first) + 1], pair.first)
        }
        assertTrue("-nostats" in args && "-y" in args)
        assertEquals("out.mp4", args.last())
        assertTrue("-movflags" !in Conversion.args("in.mp4", "out.mkv", 360, 700))
    }

    @Test
    fun aSmallSourceIsNotConvertedAboveItsOwnBitrate() {
        assertEquals(700, Conversion.kbpsFor(360, null, null))
        assertEquals(700, Conversion.kbpsFor(360, 20_000_000, 20.0))
        // 1,500,000 bytes over 20 s = 600 kbit/s, minus 128 audio = 472; 80% = 377
        assertEquals(377, Conversion.kbpsFor(360, 1_500_000, 20.0))
        assertEquals(100, Conversion.kbpsFor(360, 100_000, 20.0))
    }

    @Test
    fun theReportOfAFileGivesItsLengthAndWhetherItsAudioCanBeKept() {
        val report = "Input #0, mov,mp4\n  Duration: 00:01:07.55, start: 0.000000, bitrate: 149 kb/s\n" +
            "  Stream #0:0[0x1](und): Video: vp9, 540x960, 105 kb/s\n" +
            "  Stream #0:1[0x2](und): Audio: aac (HE-AAC) (mp4a / 0x6134706D), 44100 Hz, stereo, fltp, 37 kb/s (default)\n"
        assertEquals(Conversion.Probe(67.55, true), Conversion.probe(report))
        assertEquals(false, Conversion.probe(report.replace("37 kb/s", "320 kb/s")).copyAudio)
        assertEquals(false, Conversion.probe(report.replace("Audio: aac", "Audio: opus")).copyAudio)
        assertEquals(false, Conversion.probe(report.replace(", 37 kb/s", "")).copyAudio)
        assertEquals(Conversion.Probe(null, false), Conversion.probe("nothing here"))
        val kept = Conversion.args("in.mp4", "out.mp4", 360, 700, copyAudio = true)
        assertEquals("copy", kept[kept.indexOf("-c:a") + 1])
        assertTrue("-b:a" !in kept)
    }

    @Test
    fun progressLinesBecomeAPercentOfTheDuration() {
        assertEquals(50.0, Conversion.percent("out_time_us=5000000", 10.0))
        assertEquals(99.0, Conversion.percent("out_time_us=12000000", 10.0))
        assertNull(Conversion.percent("out_time_us=N/A", 10.0))
        assertNull(Conversion.percent("frame=3", 10.0))
        assertNull(Conversion.percent("out_time_us=5", 0.0))
    }

    @Test
    fun aConvertedChoiceRequestsItsSourceAndTheSmallerHeight() {
        val media = ChoiceMedia(20.0, listOf(
            ChoiceVideo("720p", 720, 30, false, "h264", true, "a", 9_000_000),
            ChoiceVideo("540p", 540, 30, false, "h264", true, "b", 6_000_000),
            ChoiceVideo("480p-converted", 480, 30, false, "h264", true, "b", 3_000_000, true, convertedFrom = 540),
        ))
        val request = DownloadChoice.request(media, Choice(videoId = "480p-converted")) as DownloadFormat.Video
        assertEquals("b", request.formatId)
        assertEquals(540, request.maxHeight)
        assertEquals(480, request.scaleHeight)
        assertNull((DownloadChoice.request(media, Choice(videoId = "540p")) as DownloadFormat.Video).scaleHeight)
        // presets never land on a converted size
        assertEquals("540p", DownloadChoice.applyPreset(media, Choice(), "480").videoId)
        assertEquals("720p", DownloadChoice.opening(media, app.keepfin.core.choice.Start("best", null)).videoId)
    }

    @Test
    fun scaleHeightMustNeedAnExactFormatAndGoSmaller() {
        DownloadPlan.validate(DownloadFormat.Video(maxHeight = 540, formatId = "b", scaleHeight = 480))
        assertFailsWith<IllegalArgumentException> { DownloadPlan.validate(DownloadFormat.Video(maxHeight = 540, scaleHeight = 480)) }
        assertFailsWith<IllegalArgumentException> { DownloadPlan.validate(DownloadFormat.Video(maxHeight = 540, formatId = "b", scaleHeight = 720)) }
    }
}
