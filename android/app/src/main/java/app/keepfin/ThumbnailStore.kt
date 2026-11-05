package app.keepfin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Offline artwork, separate from the evictable RAM cache and Android's cache directory. */
internal class ThumbnailStore(context: Context, directory: File = File(context.filesDir, "media-previews")) {
    private val root = directory.also {
        require(it.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
    }
    private val temporary = File(root, "links")
    private val retained = File(root, "saved")

    fun loadUrl(url: String): Bitmap? = if (url.isBlank()) null else load(temporary, url)
    fun loadJob(id: String): Bitmap? = load(retained, id)
    fun saveUrl(url: String, bitmap: Bitmap) { if (url.isNotBlank()) save(temporary, url, bitmap, true) }
    fun saveJob(id: String, bitmap: Bitmap) { save(retained, id, bitmap, false) }
    fun promote(url: String, id: String) { loadUrl(url)?.let { try { saveJob(id, it) } finally { it.recycle() } } }
    fun removeJob(id: String) = synchronized(lock) { AtomicFile(file(retained, id)).delete() }
    fun retainJobs(ids: Set<String>) = synchronized(lock) {
        val keep = ids.map { file(retained, it).name }.toSet()
        retained.listFiles()?.filter { it.isFile && it.name.matches(Regex("[a-f0-9]{64}\\.jpg")) && it.name !in keep }
            ?.forEach { AtomicFile(it).delete() }
    }

    private fun file(directory: File, key: String): File {
        val hash = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(directory, "$hash.jpg")
    }

    private fun load(directory: File, key: String): Bitmap? = synchronized(lock) {
        val target = file(directory, key)
        val atomic = AtomicFile(target)
        try {
            val data = atomic.openRead().use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(out.size() + count <= MAX_IMAGE_BYTES)
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
            check(bounds.outWidth in 1..MAX_EDGE && bounds.outHeight in 1..MAX_EDGE)
            BitmapFactory.decodeByteArray(data, 0, data.size)?.also { target.setLastModified(System.currentTimeMillis()) }
        } catch (_: java.io.FileNotFoundException) { null }
        catch (_: Exception) { atomic.delete(); null }
    }

    private fun save(directory: File, key: String, original: Bitmap, prune: Boolean) = synchronized(lock) {
        if (!directory.isDirectory && !directory.mkdirs()) return@synchronized
        val ratio = minOf(1f, MAX_EDGE.toFloat() / maxOf(original.width, original.height))
        val bitmap = if (ratio < 1f) Bitmap.createScaledBitmap(original,
            (original.width * ratio).toInt().coerceAtLeast(1), (original.height * ratio).toInt().coerceAtLeast(1), true) else original
        try {
            var quality = 84
            var data: ByteArray
            do {
                val encoded = ByteArrayOutputStream()
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, encoded))
                data = encoded.toByteArray()
                quality -= 15
            } while (data.size > MAX_IMAGE_BYTES && quality >= 39)
            check(data.size <= MAX_IMAGE_BYTES)
            val atomic = AtomicFile(file(directory, key))
            val stream = atomic.startWrite()
            try { stream.write(data); atomic.finishWrite(stream) }
            catch (error: Exception) { atomic.failWrite(stream); throw error }
            if (prune) pruneLinks()
        } catch (_: Exception) { /* A cache write must never prevent a download or its preview from rendering. */ }
        finally { if (bitmap !== original) bitmap.recycle() }
    }

    private fun pruneLinks() {
        val files = temporary.listFiles()?.filter { it.isFile && it.name.matches(Regex("[a-f0-9]{64}\\.jpg")) }
            ?.sortedByDescending { it.lastModified() } ?: return
        var bytes = 0L
        files.forEachIndexed { index, file ->
            bytes += file.length()
            if (index >= MAX_LINK_IMAGES || bytes > MAX_LINK_BYTES) AtomicFile(file).delete()
        }
    }

    companion object {
        private val lock = Any()
        private const val MAX_EDGE = 480
        private const val MAX_IMAGE_BYTES = 256 * 1024
        private const val MAX_LINK_IMAGES = 160
        private const val MAX_LINK_BYTES = 20 * 1024 * 1024
    }
}
