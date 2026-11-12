package app.keepfin

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * The gallery engine on Android: a pure-Python program run by the interpreter the download engine library already ships
 * (`libpython.so` in the native library folder, its packages extracted by the download engine's init). The program and its
 * pure-Python dependencies are one zip, `assets/gallery/gdl.zip` (built by scripts/build/fetch-gallery-python.mjs, described
 * by `gdl.json`), copied to `filesDir/gallery/` and put on PYTHONPATH (zipimport). Output is parsed by the shared core
 * (`app.keepfin.core.gallery.Gallery.read`), not here.
 */
internal class GalleryEngine(private val context: Context, private val ensureInit: () -> Unit) {
    data class Output(val exit: Int, val stdout: String, val stderr: String, val timedOut: Boolean = false, val cancelled: Boolean = false)

    private val directory get() = File(context.filesDir, "gallery")

    /** Copies the packaged zip into the app's data when it is missing or differs from the packaged version; returns it. */
    @Synchronized
    fun installPackage(): File {
        val target = File(directory, ZIP)
        val marker = File(directory, MANIFEST)
        val packaged = context.assets.open("gallery/$MANIFEST").use { it.readBytes().toString(Charsets.UTF_8) }
        val installed = marker.takeIf { it.isFile }?.readText()
        if (!GalleryLogic.needsInstall(GalleryLogic.packageSha(installed), GalleryLogic.packageSha(packaged), target.isFile)) return target
        directory.mkdirs()
        val temporary = File(directory, "$ZIP.tmp")
        context.assets.open("gallery/$ZIP").use { input -> temporary.outputStream().use { input.copyTo(it) } }
        // Never keep a corrupt copy: the packaged hash must match what was written.
        if (GalleryLogic.packageSha(packaged) != sha256(temporary)) { temporary.delete(); throw IOException("The gallery engine package is damaged.") }
        if (!temporary.renameTo(target)) { target.delete(); if (!temporary.renameTo(target)) throw IOException("The gallery engine package could not be installed.") }
        marker.writeText(packaged)
        return target
    }

    /**
     * Runs the gallery engine with [args] (after `-m gallery_dl`). [onLine] receives each stdout line as it arrives; stdout is also
     * collected (at most [GalleryLogic.MAX_STDOUT] bytes). [cancelled] is polled to stop the process early.
     */
    fun run(args: List<String>, timeoutSeconds: Long, onLine: ((String) -> Unit)? = null, cancelled: () -> Boolean = { false }): Output {
        val python = File(context.applicationInfo.nativeLibraryDir, "libpython.so")
        if (!python.isFile) throw IOException("The gallery engine is not available on this device.")
        ensureInit()
        val zip = installPackage()
        val builder = ProcessBuilder(GalleryLogic.command(python.absolutePath, args))
        builder.environment().putAll(GalleryLogic.environment(context.applicationInfo.nativeLibraryDir, context.noBackupFilesDir.absolutePath, context.filesDir.absolutePath, zip.absolutePath, context.cacheDir.absolutePath))
        val process = builder.start()
        process.outputStream.close()
        val stdout = Collector(GalleryLogic.MAX_STDOUT)
        val stderr = Collector(1_000_000)
        val readers = listOf(
            Thread { pump(process.inputStream, stdout, onLine) }.apply { isDaemon = true; start() },
            Thread { pump(process.errorStream, stderr, null) }.apply { isDaemon = true; start() },
        )
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        var timedOut = false
        var stopped = false
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (cancelled()) { stopped = true; break }
                if (System.nanoTime() > deadline) { timedOut = true; break }
            }
        } catch (interrupted: InterruptedException) {
            stopped = true
            Thread.currentThread().interrupt()
        }
        if (timedOut || stopped) { process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS) }
        readers.forEach { it.join(2000) }
        val exit = if (timedOut || stopped) -1 else process.exitValue()
        return Output(exit, stdout.text(), stderr.text(), timedOut, stopped)
    }

    /** The `-j` listing of [url] (up to 200 items), as the engine printed it; [cookiesFile] is a Netscape cookie file or null. */
    fun inspect(url: String, cookiesFile: String? = null, timeoutSeconds: Long = 90, cancelled: () -> Boolean = { false }): String {
        val output = run(GalleryLogic.inspectArgs(url, cookiesFile), timeoutSeconds, cancelled = cancelled)
        if (output.exit != 0) Log.w(TAG, "gallery inspect exited ${output.exit}: ${output.stderr.take(300)}")
        return output.stdout
    }

    private class Collector(private val limit: Int) {
        private val buffer = ByteArrayOutputStream()
        @Synchronized fun add(bytes: ByteArray, length: Int) { val room = limit - buffer.size(); if (room > 0) buffer.write(bytes, 0, minOf(room, length)) }
        @Synchronized fun text(): String = buffer.toString(Charsets.UTF_8.name())
    }

    private fun pump(stream: InputStream, into: Collector, onLine: ((String) -> Unit)?) {
        try {
            if (onLine == null) {
                val bytes = ByteArray(8192)
                while (true) { val n = stream.read(bytes); if (n < 0) break; into.add(bytes, n) }
            } else {
                stream.bufferedReader(Charsets.UTF_8).forEachLine { line ->
                    val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
                    into.add(bytes, bytes.size)
                    onLine(line)
                }
            }
        } catch (_: IOException) { }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(65536); while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val TAG = "KeepfinGallery"
        private const val ZIP = "gdl.zip"
        private const val MANIFEST = "gdl.json"
    }
}

/** The pure parts of [GalleryEngine]: package version check, the command line and the process environment. */
internal object GalleryLogic {
    const val MAX_STDOUT = 50 * 1024 * 1024

    /** The `sha256` field of a `gdl.json` text, or null. */
    fun packageSha(manifest: String?): String? = manifest?.let { Regex("\"sha256\"\\s*:\\s*\"([0-9a-f]{64})\"").find(it)?.groupValues?.get(1) }

    /** True when the packaged zip has to be copied: not installed, no recorded version, or a different version. */
    fun needsInstall(installedSha: String?, packagedSha: String?, zipPresent: Boolean): Boolean =
        !zipPresent || installedSha == null || installedSha != packagedSha

    fun command(python: String, args: List<String>): List<String> = listOf(python, "-m", "gallery_dl") + args

    private fun base(cookiesFile: String?): List<String> =
        listOf("--config-ignore", "--retries", "4", "--http-timeout", "30") + (if (cookiesFile != null) listOf("--cookies", cookiesFile) else emptyList())

    fun inspectArgs(url: String, cookiesFile: String?): List<String> = base(cookiesFile) + listOf("-j", "--range", "1-200", "--", url)

    /** Downloads into [folder]; [range] selects positions (null = all). The engine prints each saved file's path ("# path" when already there). */
    fun downloadArgs(url: String, folder: String, range: String?, cookiesFile: String?): List<String> =
        base(cookiesFile) + listOf("-D", folder) + (if (range != null) listOf("--range", range) else emptyList()) + listOf("--", url)

    /** Saves direct file links (no site extractor) into [folder] under their own names. */
    fun directArgs(folder: String, urls: List<String>): List<String> =
        base(null) + listOf("-D", folder, "-f", "{filename}.{extension}", "--") + urls

    /** The engine stderr as the failure text the shared error rules read ("[site][error] x" becomes "ERROR: x"). */
    fun errorText(stderr: String, timedOut: Boolean): String = when {
        timedOut -> "The download timed out."
        stderr.isBlank() -> "No images were downloaded from this link."
        else -> Regex("""(?m)^\[[\w.-]+]\[error]\s*""").replace(stderr, "ERROR: ")
    }

    /** A path the engine printed for a saved file, or null for any other line. */
    fun savedPath(line: String): String? = line.removePrefix("#").trim().takeIf { it.startsWith("/") }

    fun environment(nativeLibraryDir: String, noBackupDir: String, filesDir: String, zipPath: String, cacheDir: String): Map<String, String> {
        val packages = "$noBackupDir/youtubedl-android/packages"
        val python = "$packages/python/usr"
        return mapOf(
            "PYTHONHOME" to python,
            "LD_LIBRARY_PATH" to "$python/lib:$packages/ffmpeg/usr/lib:$nativeLibraryDir",
            "SSL_CERT_FILE" to "$python/etc/tls/cert.pem",
            "PYTHONPATH" to zipPath,
            "PYTHONDONTWRITEBYTECODE" to "1",
            "PYTHONIOENCODING" to "utf-8",
            "HOME" to filesDir,
            "TMPDIR" to cacheDir,
        )
    }
}
