package app.keepfin

import org.junit.Assert.*
import org.junit.Test

/**
 * The decisions behind the arguments are the shared core's and are covered by fixtures/engine/args (SharedFixturesTest and the
 * core's own tests). These tests cover what is Android's: the mapping of its types to the core, and its own flags.
 */
class ArgsPolicyTest {
    private fun List<Arg>.flat() = flatMap { listOfNotNull(it.name, it.value) }

    private val noExtras = DownloadOptions(embedThumbnail = false, embedMetadata = false, embedChapters = false)

    @Test fun bestVideoSelector() {
        val args = ArgsPolicy.download(FormatRequest.Video(), noExtras)
        assertEquals("bv*+ba/b", args.first { it.name == "-f" }.value)
        assertTrue(args.any { it.name == "--merge-output-format" && it.value == "mp4" })
    }

    @Test fun heightCapAndFps() {
        val args = ArgsPolicy.download(FormatRequest.Video(maxHeight = 1080, fps = 30, codec = "av1", container = "mkv"), noExtras)
        // The cap is a sort preference (degrades gracefully when no stream fits), never a selector filter.
        assertEquals("bv*+ba/b", args.first { it.name == "-f" }.value)
        assertEquals("res:1080,fps:30,hdr:sdr,vcodec:av01", args.first { it.name == "-S" }.value)
        assertEquals("mkv", args.first { it.name == "--merge-output-format" }.value)
    }

    @Test fun explicitFormatIds() {
        assertEquals("299+140/299/bv*+ba/b", ArgsPolicy.download(FormatRequest.Video(formatId = "299", audioFormatId = "140"), noExtras).first { it.name == "-f" }.value)
    }

    @Test fun audioTargets() {
        assertTrue(ArgsPolicy.download(FormatRequest.Audio("mp3", "320"), noExtras).flat().containsAll(listOf("-x", "--audio-format", "mp3", "--audio-quality", "320K")))
        // flac, wav and original ignore the quality, and the original stream is kept as it is.
        val flac = ArgsPolicy.download(FormatRequest.Audio("flac", "320"), noExtras).flat()
        assertTrue(flac.containsAll(listOf("--audio-format", "flac")))
        assertFalse(flac.contains("--audio-quality"))
        val original = ArgsPolicy.download(FormatRequest.Audio("original"), noExtras).flat()
        assertTrue(original.contains("-x"))
        assertFalse(original.contains("--audio-format"))
        assertTrue(ArgsPolicy.download(FormatRequest.Audio("m4a"), noExtras).first { it.name == "-f" }.value!!.startsWith("ba[ext=m4a]"))
    }

    @Test fun rejectsUnsafeInput() {
        val bad = listOf(
            FormatRequest.Video(formatId = "137; rm -rf"), FormatRequest.Video(formatId = "--exec"), FormatRequest.Video(audioFormatId = "a b"),
            FormatRequest.Video(maxHeight = 0), FormatRequest.Video(container = "avi"), FormatRequest.Video(codec = "xvid"),
            FormatRequest.Audio("ogg"), FormatRequest.Audio("mp3", "999"), FormatRequest.Audio(formatId = "--x"),
        )
        bad.forEach { request ->
            assertThrows(request.toString(), IllegalArgumentException::class.java) { ArgsPolicy.validate(request) }
            assertThrows(request.toString(), IllegalArgumentException::class.java) { ArgsPolicy.download(request, noExtras) }
        }
        assertThrows(IllegalArgumentException::class.java) { ArgsPolicy.validate(DownloadOptions(sponsorBlock = "exec")) }
        assertThrows(IllegalArgumentException::class.java) { ArgsPolicy.validate(DownloadOptions(subtitles = SubtitleOptions(true, "en; id"))) }
        assertThrows(IllegalArgumentException::class.java) { ArgsPolicy.validate(DownloadOptions(sectionStart = 10.0, sectionEnd = 5.0)) }
    }

    @Test fun messagesAreReadable() {
        val message = assertThrows(IllegalArgumentException::class.java) { ArgsPolicy.validate(FormatRequest.Video(formatId = "a b")) }.message.orEmpty()
        assertEquals("Choose a format from the inspected list.", message)
    }

    @Test fun subtitleLanguagesAreCheckedOnlyWhileSubtitlesAreOn() {
        ArgsPolicy.validate(DownloadOptions(subtitles = SubtitleOptions(enabled = false, languages = "half typed;")))
        assertThrows(IllegalArgumentException::class.java) { ArgsPolicy.validate(DownloadOptions(subtitles = SubtitleOptions(enabled = true, languages = "half typed;"))) }
    }

    @Test fun optionsMapToFlags() {
        val flags = ArgsPolicy.download(FormatRequest.Video(), DownloadOptions(
            subtitles = SubtitleOptions(true, "en.*,ar.*", auto = true, embed = true), sponsorBlock = "remove", sectionStart = 10.0, sectionEnd = 20.5, writeThumbnail = true,
        )).flat()
        assertTrue(flags.containsAll(listOf("--write-subs", "--write-auto-subs", "--sub-langs", "en.*,ar.*", "--embed-subs", "--embed-thumbnail", "--embed-metadata", "--embed-chapters")))
        assertTrue(flags.contains("--sponsorblock-remove"))
        assertTrue(flags.contains("*10-20.5"))
        assertTrue(flags.contains("--write-thumbnail"))
    }

    @Test fun subtitleSidecarsAreConvertedToSrt() {
        val flags = ArgsPolicy.download(FormatRequest.Video(), DownloadOptions(subtitles = SubtitleOptions(true, "en", embed = false))).flat()
        assertTrue(flags.containsAll(listOf("--write-subs", "--convert-subs", "srt")))
        assertFalse(flags.contains("--embed-subs"))
    }

    @Test fun audioSkipsSubtitlesAndWavExtras() {
        val flags = ArgsPolicy.download(FormatRequest.Audio("wav"), DownloadOptions(subtitles = SubtitleOptions(true))).flat()
        assertFalse(flags.contains("--write-subs"))
        assertFalse(flags.contains("--embed-chapters"))
        assertFalse("wav cannot carry cover art", flags.contains("--embed-thumbnail"))
        assertTrue(ArgsPolicy.download(FormatRequest.Audio("mp3"), DownloadOptions()).flat().contains("--embed-thumbnail"))
        assertFalse(ArgsPolicy.download(FormatRequest.Audio("opus"), DownloadOptions()).flat().contains("--embed-thumbnail"))
        assertTrue(ArgsPolicy.download(FormatRequest.Audio("opus"), DownloadOptions(), mutagen = true).flat().contains("--embed-thumbnail"))
        assertFalse(ArgsPolicy.download(FormatRequest.Video(container = "webm"), DownloadOptions()).flat().contains("--embed-thumbnail"))
    }

    @Test fun reliabilityAndFragments() {
        val args = ArgsPolicy.reliability(8)
        assertEquals("8", args.first { it.name == "--concurrent-fragments" }.value)
        assertEquals("16", ArgsPolicy.reliability(99).first { it.name == "--concurrent-fragments" }.value)
        assertEquals("10", args.first { it.name == "--retries" }.value)
        assertTrue(args.any { it.name == "--retry-sleep" && it.value == "exp=1:20" })
        assertTrue(args.any { it.name == "--socket-timeout" && it.value == "20" })
        assertTrue(args.any { it.name == "--continue" })
    }

    @Test fun aria2cOnlyForHttp() {
        val args = ArgsPolicy.aria2c("/data/ca cert.pem", 8)
        assertTrue(args.any { it.name == "--downloader" && it.value == "http:libaria2c.so" })
        assertTrue(args.any { it.name == "--downloader" && it.value == "m3u8:native" })
        assertTrue(args.any { it.name == "--downloader" && it.value == "dash:native" })
        val extra = args.first { it.name == "--downloader-args" }.value!!
        assertTrue(extra.contains("-x 8 -s 8"))
        assertTrue(extra.contains("--ca-certificate=/data/ca\\ cert.pem"))
    }

    @Test fun tagsAreEscaped() {
        val flags = ArgsPolicy.tags(SourceTags("Title: A 100%", "Art\\ist", "Album", 3, 1999)).filter { it.name == "--parse-metadata" }.map { it.value!! }
        assertTrue(flags.toString(), flags.contains("Title\\: A 100%%:%(meta_title)s"))
        assertTrue(flags.contains("Art\\\\ist:%(meta_artist)s"))
        assertTrue("a one-word value is a template with a default, never a field name", flags.contains("%(keepfin_literal|Album)s:%(meta_album)s"))
        assertTrue(flags.contains("3:%(meta_track)s"))
        assertTrue(flags.contains("1999:%(meta_date)s"))
        assertTrue(flags.contains("Art\\\\ist:%(meta_album_artist)s"))
    }

    @Test fun outputNeverRunsCommands() {
        val out = ArgsPolicy.output("/data/x/%(title)s.%(ext)s")
        assertTrue(out.any { it.name == "--no-playlist" })
        assertTrue(out.any { it.name == "-o" && it.value == "/data/x/%(title)s.%(ext)s" })
        assertTrue(out.none { it.name == "--exec" })
    }

    @Test fun presets() {
        assertEquals(FormatRequest.Video(maxHeight = 720), ArgsPolicy.preset(DownloadPreset.P720))
        assertEquals(FormatRequest.Audio("mp3", "320"), ArgsPolicy.preset(DownloadPreset.MP3))
        assertEquals(MediaKind.AUDIO, ArgsPolicy.kind(ArgsPolicy.preset(DownloadPreset.FLAC)))
        DownloadPreset.entries.forEach { ArgsPolicy.download(ArgsPolicy.preset(it), DownloadOptions()) }
    }
}
