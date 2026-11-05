package app.keepfin.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Preview frames for the seek bar, made from the file on this phone while the bar is being dragged. Nothing is opened until the first
 * [request]; extraction runs on an IO thread with the latest request winning, frames are cached in 2 s buckets, and [release] frees the
 * decoder. A file that is not on this device ([uri] null) or a frame that cannot be read leaves [frame] null: the bubble then shows time
 * and chapter only. Playback is never involved.
 */
internal class VideoThumbnails(private val context: Context, private val uri: Uri?, private val aspect: Float, private val width: Int = 160) {
    /** The frame for the latest request; null until one is ready. */
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set

    private val height = (width / aspect.coerceIn(0.4f, 3f)).toInt().coerceAtLeast(1)
    private val cache = LruCache<Long, Bitmap>(CACHE_FRAMES)
    private val requests = Channel<Long>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null
    @Volatile private var wanted = -1L
    @Volatile private var released = false

    /** Asks for the frame near [ms]; a frame already cached shows at once, otherwise the worker (started now) fetches it. */
    fun request(ms: Long) {
        if (uri == null || released) return
        val bucket = ms / BUCKET_MS
        wanted = bucket
        cache.get(bucket)?.let { frame = it.asImageBitmap(); return }
        if (worker == null) worker = scope.launch { run() }
        requests.trySend(bucket)
    }

    private suspend fun run() {
        var retriever: MediaMetadataRetriever? = null
        var failed = false
        try {
            for (bucket in requests) {
                if (failed) continue
                if (bucket != wanted) continue // a newer request is already waiting
                if (retriever == null) {
                    retriever = MediaMetadataRetriever()
                    if (!runCatching { retriever.setDataSource(context, uri) }.isSuccess) { failed = true; continue }
                }
                val bitmap = cache.get(bucket) ?: runCatching { extract(retriever, bucket * BUCKET_MS * 1000L) }.getOrNull()
                if (bitmap == null) { frame = null; continue }
                cache.put(bucket, bitmap)
                if (bucket == wanted && !released) frame = bitmap.asImageBitmap()
            }
        } finally {
            runCatching { retriever?.release() }
        }
    }

    private fun extract(retriever: MediaMetadataRetriever, us: Long): Bitmap? {
        if (Build.VERSION.SDK_INT >= 27) return retriever.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height)
        val full = retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        return Bitmap.createScaledBitmap(full, width, height, true).also { if (it !== full) full.recycle() }
    }

    fun release() {
        released = true
        requests.close()
        scope.cancel() // the worker releases the decoder in its finally
        frame = null
    }

    private companion object {
        const val BUCKET_MS = 2_000L
        const val CACHE_FRAMES = 60
    }
}
