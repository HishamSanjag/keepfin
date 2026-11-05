package app.keepfin.ui.player

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import app.keepfin.MusicVideoFacts

/**
 * The length of a song's music video file, in seconds. The row shows it and Song | Video needs it to stop at the end of the shorter side. It is read from
 * the file once (also for a video attached before lengths were kept) and remembered by the file's size, so a replaced file is read again.
 */
object MusicVideoLengths {
    private const val FILE = "music-video-lengths"

    /** Blocking (opens the file the first time): call off the main thread. Null when the file cannot be read. */
    fun of(context: Context, itemId: String, facts: MusicVideoFacts): Double? {
        val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        prefs.getString(itemId, null)?.split(':')?.takeIf { it.size == 2 && it[0].toLongOrNull() == facts.size }?.let { return it[1].toDoubleOrNull() }
        val seconds = read(context, facts.uri) ?: return null
        runCatching { prefs.edit().putString(itemId, "${facts.size}:$seconds").apply() }
        return seconds
    }

    private fun read(context: Context, uri: String): Double? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, Uri.parse(uri))
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }?.let { it / 1000.0 }
        } catch (_: Exception) { null } finally { runCatching { retriever.release() } }
    }
}
