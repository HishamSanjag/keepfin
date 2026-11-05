package app.keepfin.ui.player

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/** Take screenshot in the video player: the current frame from the video surface, saved as a PNG to Pictures/Keepfin. */
object FrameCapture {
    const val FOLDER = "Pictures/Keepfin"

    /** The visible video surface under [root] (the largest one, there is normally just the player's). */
    fun findSurface(root: View): SurfaceView? {
        var best: SurfaceView? = null
        fun walk(v: View) {
            if (v is SurfaceView && v.isShown && v.width > 0 && v.height > 0 && (best == null || v.width * v.height > best!!.width * best!!.height)) best = v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root.rootView)
        return best
    }

    /** The frame the surface shows now, or null when it cannot be copied (no frame yet, a protected stream, the surface is gone). */
    suspend fun capture(surface: SurfaceView): Bitmap? {
        if (!surface.holder.surface.isValid || surface.width <= 0 || surface.height <= 0) return null
        val bitmap = try { Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888) } catch (_: Throwable) { return null }
        val done = suspendCancellableCoroutine { cont ->
            try {
                PixelCopy.request(surface, bitmap, { result -> if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS) }, Handler(Looper.getMainLooper()))
            } catch (_: Throwable) { if (cont.isActive) cont.resume(false) }
        }
        if (!done) { bitmap.recycle(); return null }
        return bitmap
    }

    /**
     * Writes [bitmap] as a PNG named [name] into Pictures/Keepfin and returns its content uri. Throws when it could not be written
     * (nothing is left behind). Android 10 and later use MediaStore (pending until written); 8 and 9 need the storage permission the
     * app holds for its public downloads, and say so when it is missing.
     */
    fun save(context: Context, bitmap: Bitmap, name: String): Uri {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$FOLDER/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Android could not create the picture.")
            try {
                resolver.openOutputStream(uri, "w")?.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "The picture could not be encoded." } } ?: error("Android could not write the picture.")
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) { "Android could not finish saving the picture." }
                return uri
            } catch (error: Exception) {
                try { resolver.delete(uri, null, null) } catch (_: Exception) { }
                throw error
            }
        }
        check(context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) { "Allow storage access to save pictures." }
        @Suppress("DEPRECATION")
        val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Keepfin")
        check(directory.isDirectory || directory.mkdirs()) { "Could not create $FOLDER." }
        var suffix = 0
        var target: File
        do {
            target = File(directory, if (suffix == 0) name else "${name.substringBeforeLast('.')} (${suffix + 1}).png")
            suffix++
        } while (!target.createNewFile())
        try {
            target.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "The picture could not be encoded." } }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, target.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                @Suppress("DEPRECATION") put(MediaStore.MediaColumns.DATA, target.absolutePath)
            }
            return resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Android could not add the picture.")
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }

    /** Puts the saved picture on the clipboard (only ever called from the "Copy image" button). */
    fun copy(context: Context, uri: Uri): Boolean = try {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "Frame", uri))
        true
    } catch (_: Exception) { false }

    /** "<title> <h-mm-ss>.png": characters a file name cannot hold become spaces. */
    fun fileName(title: String, positionMs: Long): String {
        val total = (positionMs.coerceAtLeast(0L) / 1000)
        val clock = "${total / 3600}-${"%02d".format((total / 60) % 60)}-${"%02d".format(total % 60)}"
        val clean = title.map { if (it in "\\/:*?\"<>|" || it.code < 32) ' ' else it }.joinToString("").trim().take(100).trim()
        return "${clean.ifEmpty { "Frame" }} $clock.png"
    }
}
