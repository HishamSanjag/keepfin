package app.keepfin

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import java.io.File

/** Call on a worker thread. Local frames stay available when provider thumbnails disappear. */
internal object LocalMediaPreview {
    fun load(context: Context, uri: Uri?, file: File? = null, size: Int = 320): Bitmap? {
        require(size in 32..1024)
        if (Build.VERSION.SDK_INT >= 29 && uri?.scheme == "content") {
            try { return context.contentResolver.loadThumbnail(uri, Size(size, size), null) }
            catch (_: Exception) { /* Unsupported/missing media falls back to the retained local file. */ }
        }
        val retriever = MediaMetadataRetriever()
        try {
            if (file?.isFile == true) retriever.setDataSource(file.absolutePath)
            else if (uri != null) retriever.setDataSource(context, uri)
            else return null
            retriever.embeddedPicture?.takeIf { it.size <= 4_000_000 }?.let { bytes ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > size * 2 || bounds.outHeight / sample > size * 2) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.let { return it }
            }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val time = minOf(1_000_000L, duration * 500L)
            if (Build.VERSION.SDK_INT >= 27) return retriever.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, size, size)
            val bitmap = retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
            val ratio = size.toFloat() / maxOf(bitmap.width, bitmap.height)
            if (ratio >= 1f) return bitmap
            return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
                (bitmap.height * ratio).toInt().coerceAtLeast(1), true).also { if (it !== bitmap) bitmap.recycle() }
        } catch (_: Exception) { return null }
        finally { try { retriever.release() } catch (_: Exception) { } }
    }

    fun metadata(source: File, mime: String): ContentValues {
        val values = ContentValues()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }?.let {
                values.put(MediaStore.Video.Media.DURATION, it)
            }
            if (mime.startsWith("video/")) {
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.takeIf { it > 0 }?.let {
                    values.put(MediaStore.MediaColumns.WIDTH, it)
                }
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.takeIf { it > 0 }?.let {
                    values.put(MediaStore.MediaColumns.HEIGHT, it)
                }
            }
        } catch (_: Exception) { /* Android may index a codec its retriever cannot decode. Publishing must still succeed. */ }
        finally { try { retriever.release() } catch (_: Exception) { } }
        return values
    }
}
