package app.keepfin

import android.util.Log
import app.keepfin.core.formats.Conversion
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The converter process, as the conversion step needs it: output lines as they arrive, and a way to stop it. A seam for tests. */
internal interface ConvertTools {
    /** Runs the converter with [args]; null when it cannot start. [cancelled] is polled while it runs (the process is then destroyed). */
    fun run(args: List<String>, timeoutSeconds: Long, cancelled: () -> Boolean, onLine: (String) -> Unit): MediaTools.Output?
}

/** The conversion could not make the smaller copy; the download is kept. [raw] is for the log and the failure detail. */
internal class ConvertFailed(val raw: String) : Exception(ConvertStep.FAILED)

/** The job was cancelled while converting. */
internal class ConvertCancelled : Exception("Cancelled")

/**
 * The smaller sizes Keepfin makes by converting (the core's [Conversion] decides the bitrate and the arguments). The finished download is converted
 * into a temporary name beside it, and replaces the download only when that worked and came out smaller. Any failure keeps the downloaded file and
 * fails the job with a plain message: a smaller copy is never claimed that does not exist. Runs before the file is published anywhere.
 */
internal object ConvertStep {
    const val PHASE = "Converting"
    const val FAILED = "Keepfin could not make the smaller copy; the original size was kept."
    private const val TAG = "KeepfinConvert"

    /** The temporary name keeps the extension last (the converter picks the container from it); ".kftmp" makes copies and scans skip it. */
    fun tempFor(file: File): File {
        val ext = file.extension.let { if (it.isEmpty()) "" else ".$it" }
        return File(file.parentFile, "${file.nameWithoutExtension}.kftmp-converting$ext")
    }

    private fun log(message: String) { try { Log.i(TAG, message) } catch (_: Throwable) { } }

    /** Converts [file] down to [height] and replaces it with the result; returns the same file. [duration] is read from the file when null. */
    fun run(tools: ConvertTools, file: File, height: Int, duration: Double?, cancelled: () -> Boolean, onProgress: (Int) -> Unit): File {
        val temp = tempFor(file)
        try {
            val source = file.length()
            if (!file.isFile || source <= 0) throw ConvertFailed("The downloaded file is unavailable.")
            // The converter's report of the file (it exits with an error when given no output; the report is what is wanted).
            val report = tools.run(listOf("-i", file.absolutePath), 60, cancelled) { } ?: throw ConvertFailed("The converter could not start.")
            if (cancelled()) throw ConvertCancelled()
            val probe = Conversion.probe(report.text)
            val length = duration?.takeIf { it > 0 } ?: probe.duration
            val args = Conversion.args(file.absolutePath, temp.absolutePath, height, Conversion.kbpsFor(height, source, length), probe.copyAudio)
            // The args start with -hide_banner -nostdin, which the runner adds itself.
            val result = tools.run(args.drop(2), 6 * 3600L, cancelled) { line ->
                val percent = length?.let { Conversion.percent(line, it) }
                if (percent != null) onProgress(percent.toInt())
            } ?: throw ConvertFailed("The converter could not start.")
            if (cancelled()) throw ConvertCancelled()
            val made = temp.length()
            if (result.exit != 0 || !temp.isFile || made <= 0) throw ConvertFailed(result.text)
            // A "smaller" copy that is not smaller would not be what was asked for.
            if (made >= source) throw ConvertFailed("The converted file ($made bytes) was not smaller than the download ($source bytes).")
            try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            catch (error: Exception) { throw ConvertFailed("The converted file could not replace the download: ${error.message}") }
            log("converted to ${height}p: $source -> $made bytes")
            return file
        } catch (error: ConvertFailed) {
            temp.delete()
            log("conversion failed: ${error.raw.lineSequence().filter { it.isNotBlank() }.toList().takeLast(6).joinToString(" | ")}")
            throw error
        } catch (error: ConvertCancelled) {
            temp.delete()
            throw error
        } catch (error: Exception) {
            temp.delete()
            throw ConvertFailed(error.message ?: error.toString())
        }
    }
}
