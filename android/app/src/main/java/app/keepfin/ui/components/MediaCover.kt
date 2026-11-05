package app.keepfin.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import app.keepfin.LocalMediaPreview
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The picture for a saved song or video without an artwork file: the system thumbnail, else the cover embedded in the file,
 * else a frame of a video ([LocalMediaPreview]). A plain video Uri would only get a decoded frame, which phones without a
 * decoder for the video (AV1 at 1440p) cannot make, and a song's Uri gets nothing at all. [stamp] changes when the file does
 * (its modification time), so an edited cover is read again.
 */
data class MediaCover(val uri: Uri, val stamp: Long = 0L)

/**
 * Reads a cover once and keeps it as a small JPEG in the app's cache (a file without any cover is remembered too), so lists,
 * the player and app restarts do not open the media file again. Covers are one fixed size and not "sampled", so the memory cache
 * serves a tile, a shrinking player and the full player from the same picture without reloading (no flash of the letters).
 */
internal class MediaCoverFetcher(private val context: Context, private val cover: MediaCover) : Fetcher {
    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        val name = key(cover)
        val file = File(dir, "$name.jpg")
        val none = File(dir, "$name.none")
        // "no cover" is remembered for a day only: a file that could not be read for a moment (no permission yet) is tried again
        if (none.isFile && System.currentTimeMillis() - none.lastModified() < DAY_MS) return@withContext null
        BitmapFactory.decodeFile(file.path)?.let {
            touch(file)
            return@withContext ImageFetchResult(it.asImage(), isSampled = false, dataSource = DataSource.DISK)
        }
        val bitmap = LocalMediaPreview.load(context, cover.uri, null, SIZE)
        if (bitmap == null) {
            // a new day starts with the marker's time set again (createNewFile alone keeps an expired marker's old time)
            runCatching { none.createNewFile(); none.setLastModified(System.currentTimeMillis()) }
            counted(dir)
            return@withContext null
        }
        save(dir, file, bitmap)
        ImageFetchResult(bitmap.asImage(), isSampled = false, dataSource = DataSource.DISK)
    }

    class Factory(private val context: Context) : Fetcher.Factory<MediaCover> {
        override fun create(data: MediaCover, options: Options, imageLoader: ImageLoader): Fetcher = MediaCoverFetcher(context, data)
    }

    internal companion object {
        const val DIR = "media-covers"
        const val SIZE = 640
        private const val DAY_MS = 24L * 60 * 60 * 1000
        // the cache keeps the covers seen most recently: at most MAX files, trimmed to KEEP now and then
        private const val MAX = 4000
        private const val KEEP = 3000
        private val writes = AtomicInteger()

        fun key(cover: MediaCover): String = MessageDigest.getInstance("SHA-1").digest("${cover.uri}|${cover.stamp}".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }.take(24)

        private fun save(dir: File, file: File, bitmap: Bitmap) {
            try {
                val tmp = File(dir, file.name + ".tmp")
                tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                if (!tmp.renameTo(file)) tmp.delete()
            } catch (_: Exception) { /* the cover still shows; it is read again next time */ }
            counted(dir)
        }

        /** Covers and "no cover" markers both count towards the trim, so a library without covers cannot fill the cache either. */
        private fun counted(dir: File) {
            if (writes.incrementAndGet() % 100 == 0) trim(dir)
        }

        /** A cover read from the cache is marked recent (at most once a day), so the trim drops the ones not seen for longest. */
        private fun touch(file: File) {
            val now = System.currentTimeMillis()
            if (now - file.lastModified() > DAY_MS) runCatching { file.setLastModified(now) }
        }

        private fun trim(dir: File) {
            val files = dir.listFiles() ?: return
            if (files.size <= MAX) return
            files.sortedBy { it.lastModified() }.take(files.size - KEEP).forEach { it.delete() }
        }
    }
}

/** Memory-caches covers by the file and its version, so a tile scrolled back into view does not read anything again. */
internal class MediaCoverKeyer : Keyer<MediaCover> {
    override fun key(data: MediaCover, options: Options): String = "media-cover:${data.uri}|${data.stamp}"
}
