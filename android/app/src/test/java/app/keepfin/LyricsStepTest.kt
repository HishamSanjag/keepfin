package app.keepfin

import app.keepfin.core.lyrics.LyricsQuery
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LyricsStepTest {
    @get:Rule val folder = TemporaryFolder()

    private fun record(kind: MediaKind = MediaKind.AUDIO, source: SourceTags? = null, duration: Double? = 244.0, uploader: String? = "M83 - Topic") = JobRecord(
        Job(id = "1", url = "https://music.youtube.com/watch?v=x", title = "Midnight City", thumbnail = null, uploader = uploader, duration = duration, provider = null, label = "",
            kind = kind, status = JobStatus.PROCESSING, percent = 99f, speed = null, eta = null, downloadedBytes = null, totalBytes = null, expectedSize = null, fileSize = null,
            phase = null, error = null, file = null, publicUri = null, publicPath = null, saveError = null, createdAt = 0, completedAt = null, attempt = 1),
        FormatRequest.Audio("mp3", "320"), DownloadOptions(), source = source,
    )

    /** ffmpeg stand-in: copies the input to the output path and reports the lyrics tag only on the rewritten copy. */
    private class FakeTools(var usable: Boolean = true, var existingLyrics: Boolean = false, var breakEmbed: Boolean = false) : TagTools {
        override fun available() = usable
        override fun metadata(file: File): Map<String, String>? = when {
            !usable -> null
            file.name.contains("lyrics-tmp") -> mapOf("lyrics" to "x")
            existingLyrics -> mapOf("lyrics" to "already here")
            else -> mapOf("title" to "Midnight City", "artist" to "M83")
        }
        override fun run(args: List<String>, timeoutSeconds: Long): MediaTools.Output? {
            if (breakEmbed) return MediaTools.Output(1, "failed")
            val input = File(args[args.indexOf("-i") + 1]); File(args.last()).writeBytes(input.readBytes()); return MediaTools.Output(0, "")
        }
    }

    private fun song() = File(folder.root, "Midnight City.mp3").also { it.writeBytes(ByteArray(2000) { 7 }) }

    @Test fun aFailedLookupNeverThrowsAndLeavesTheFileAlone() {
        val file = song()
        val result = LyricsStep.run(record(), file, "Midnight City", true, FakeTools()) { throw IllegalStateException("offline") }
        assertEquals(LyricsStep.Result.FAILED, result)
        assertEquals(2000L, file.length()); assertFalse(File(folder.root, "Midnight City.lrc").exists())
    }

    @Test fun noLyricsFoundIsQuiet() {
        assertEquals(LyricsStep.Result.NOT_FOUND, LyricsStep.run(record(), song(), "Midnight City", true, FakeTools()) { null })
    }

    @Test fun offVideosAndSongsThatAlreadyHaveLyricsAreSkippedWithoutLookingUp() {
        var asked = 0
        val find: (LyricsQuery) -> String? = { asked++; "[00:01.00] a" }
        assertEquals(LyricsStep.Result.SKIPPED, LyricsStep.run(record(), song(), "Midnight City", false, FakeTools(), find))
        assertEquals(LyricsStep.Result.SKIPPED, LyricsStep.run(record(kind = MediaKind.VIDEO), song(), "Midnight City", true, FakeTools(), find))
        assertEquals(LyricsStep.Result.SKIPPED, LyricsStep.run(record(), song(), "Midnight City", true, FakeTools(existingLyrics = true), find))
        File(folder.root, "Midnight City.lrc").writeText("[00:00.00] sidecar")
        assertEquals(LyricsStep.Result.SKIPPED, LyricsStep.run(record(), song(), "Midnight City", true, FakeTools(), find))
        assertEquals(0, asked)
    }

    @Test fun lyricsAreEmbeddedWhenTheFileTakesTheTag() {
        var query: LyricsQuery? = null
        val file = song()
        val result = LyricsStep.run(record(source = SourceTags("Midnight City", "M83", "Hurry Up")), file, "Midnight City", true, FakeTools()) { query = it; "[00:01.00] a" }
        assertEquals(LyricsStep.Result.EMBEDDED, result)
        assertEquals(LyricsQuery("Midnight City", "M83", "Hurry Up", 244.0), query)
        assertTrue(file.isFile); assertFalse(File(folder.root, "Midnight City.lrc").exists())
        assertEquals(listOf("Midnight City.mp3"), folder.root.list()!!.toList())
    }

    @Test fun aFileThatCannotTakeTheTagGetsALrcSidecar() {
        val file = song()
        assertEquals(LyricsStep.Result.SIDECAR, LyricsStep.run(record(), file, "Midnight City", true, FakeTools(breakEmbed = true)) { "[00:01.00] a" })
        assertEquals("[00:01.00] a", File(folder.root, "Midnight City.lrc").readText())
        assertEquals(2000L, file.length())
        assertEquals(LyricsStep.Result.SIDECAR, LyricsStep.run(record(), song().also { File(folder.root, "Midnight City.lrc").delete() }, "Midnight City", true, FakeTools(usable = false)) { "plain words" })
        assertNotNull(File(folder.root, "Midnight City.lrc").takeIf { it.isFile })
    }

    @Test fun theLookupUsesTheFilesTagsWhenTheJobHasNoSourceMetadata() {
        val query = LyricsStep.queryFor(record(uploader = null), mapOf("title" to "Tagged Title", "artist" to "Tagged Artist", "album" to "Tagged Album"))
        assertEquals(LyricsQuery("Tagged Title", "Tagged Artist", "Tagged Album", 244.0), query)
        assertEquals(null, LyricsStep.queryFor(record(uploader = null), null))
    }

    @Test fun lyricsSettingDefaultsToOnAndOldSavedSettingsGetOn() {
        assertTrue(EngineSettings().lyrics)
        assertTrue(LyricsLookup.enabledIn(JSONObject("""{"organization":"platform","concurrency":3,"wifiOnly":false}""")))
        assertTrue(LyricsLookup.enabledIn(null))
        assertFalse(LyricsLookup.enabledIn(JSONObject("""{"lyrics":false}""")))
        assertTrue(LyricsLookup.enabledIn(JSONObject("""{"lyrics":true}""")))
    }
}
