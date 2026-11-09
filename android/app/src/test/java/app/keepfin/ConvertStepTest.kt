package app.keepfin

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConvertStepTest {
    private val report = listOf(
        "  Duration: 00:00:20.00, start: 0.000000, bitrate: 600 kb/s",
        "  Stream #0:0[0x1](und): Video: vp9 (Profile 0), yuv420p, 540x960, 105 kb/s, 29.97 fps",
        "  Stream #0:1[0x2](und): Audio: aac (HE-AAC) (mp4a / 0x6134706D), 44100 Hz, stereo, fltp, 37 kb/s (default)",
    ).joinToString("\n")

    /** The first call is the report of the file; the second writes [bytes] to the output (its last argument) unless [exit] is not 0. */
    private class Fake(private val report: String, private val bytes: Int, private val exit: Int = 0, private val cancelAfterProgress: (() -> Unit)? = null) : ConvertTools {
        val calls = ArrayList<List<String>>()
        override fun run(args: List<String>, timeoutSeconds: Long, cancelled: () -> Boolean, onLine: (String) -> Unit): MediaTools.Output? {
            calls += args
            if ("-y" !in args) return MediaTools.Output(1, report)
            onLine("out_time_us=5000000")
            onLine("out_time_us=10000000")
            cancelAfterProgress?.invoke()
            if (cancelled()) { File(args.last()).writeBytes(ByteArray(10)); return MediaTools.Output(-1, "cancelled") }
            if (exit == 0) File(args.last()).writeBytes(ByteArray(bytes) { 2 })
            return MediaTools.Output(exit, if (exit == 0) "" else "Error opening input")
        }
    }

    private fun folder(): Pair<File, File> {
        val dir = Files.createTempDirectory("keepfin-convert").toFile()
        val file = File(dir, "Reel.mp4").also { it.writeBytes(ByteArray(10_000) { 1 }) }
        return dir to file
    }

    @Test fun aSuccessfulConversionReplacesTheDownloadAndLeavesNoTemporaryFile() {
        val (dir, file) = folder()
        val tools = Fake(report, 4_000)
        val percents = ArrayList<Int>()
        val result = ConvertStep.run(tools, file, 360, 20.0, { false }) { percents += it }
        assertEquals(file, result)
        assertEquals(4_000L, file.length())
        assertEquals(listOf("Reel.mp4"), dir.list()!!.toList())
        assertEquals(listOf(25, 50), percents)
        val args = tools.calls[1]
        assertEquals(file.absolutePath, args[args.indexOf("-i") + 1])
        assertTrue(args.last().endsWith(".kftmp-converting.mp4"))
        assertTrue(args.any { it.contains("360") })
        assertEquals("light AAC audio is kept", "copy", args[args.indexOf("-c:a") + 1])
    }

    @Test fun theLengthIsReadFromTheFileWhenTheJobDidNotKnowIt() {
        val (_, file) = folder()
        val percents = ArrayList<Int>()
        ConvertStep.run(Fake(report, 4_000), file, 480, null, { false }) { percents += it }
        assertEquals(listOf(25, 50), percents)
    }

    @Test fun aFailedConversionKeepsTheDownloadAndFailsWithAPlainMessage() {
        val (dir, file) = folder()
        try { ConvertStep.run(Fake(report, 0, exit = 1), file, 360, 20.0, { false }) {}; fail() } catch (error: ConvertFailed) {
            assertEquals("Keepfin could not make the smaller copy; the original size was kept.", error.message)
            assertFalse(error.message!!.contains("ffmpeg", ignoreCase = true))
        }
        assertEquals(10_000L, file.length())
        assertEquals(listOf("Reel.mp4"), dir.list()!!.toList())
    }

    @Test fun aResultThatIsNotSmallerIsNotUsed() {
        val (dir, file) = folder()
        try { ConvertStep.run(Fake(report, 12_000), file, 360, 20.0, { false }) {}; fail() } catch (_: ConvertFailed) { }
        assertEquals(10_000L, file.length())
        assertEquals(listOf("Reel.mp4"), dir.list()!!.toList())
    }

    @Test fun aConverterThatCannotStartFailsTheSameWay() {
        val (_, file) = folder()
        val none = object : ConvertTools { override fun run(args: List<String>, timeoutSeconds: Long, cancelled: () -> Boolean, onLine: (String) -> Unit): MediaTools.Output? = null }
        try { ConvertStep.run(none, file, 360, 20.0, { false }) {}; fail() } catch (_: ConvertFailed) { }
        assertEquals(10_000L, file.length())
    }

    @Test fun cancellingRemovesTheTemporaryFileAndKeepsTheDownload() {
        val (dir, file) = folder()
        var stop = false
        try { ConvertStep.run(Fake(report, 4_000, cancelAfterProgress = { stop = true }), file, 360, 20.0, { stop }) {}; fail() } catch (_: ConvertCancelled) { }
        assertEquals(10_000L, file.length())
        assertEquals(listOf("Reel.mp4"), dir.list()!!.toList())
    }

    @Test fun theTemporaryNameKeepsTheExtensionLastAndIsSkippedByCopies() {
        val name = ConvertStep.tempFor(File("/x/My Reel.mp4")).name
        assertEquals("My Reel.kftmp-converting.mp4", name)
        assertTrue(name.contains(".kftmp"))
    }
}
