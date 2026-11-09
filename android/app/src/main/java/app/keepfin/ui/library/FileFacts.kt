package app.keepfin.ui.library

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.core.formats.MediaDetails

/** What a file says about its own contents, as Android names it (media types such as `video/avc`). Null for what it does not say. */
data class FileFacts(
    val containerType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Double? = null,
    val videoType: String? = null,
    val audioType: String? = null,
)

/** Reads one file for its Details sheet: the track list of the file and its container, nothing is decoded. */
object FileFactsReader {
    fun read(context: Context, uri: String): FileFacts {
        val extractor = MediaExtractor()
        var facts = FileFacts()
        try {
            extractor.setDataSource(context, Uri.parse(uri), null)
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val type = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (type.startsWith("video/") && facts.videoType == null) {
                    var width = format.intOrNull(MediaFormat.KEY_WIDTH)
                    var height = format.intOrNull(MediaFormat.KEY_HEIGHT)
                    // A phone video is stored sideways with a rotation: people see it turned.
                    val turn = format.intOrNull(MediaFormat.KEY_ROTATION) ?: 0
                    if (turn == 90 || turn == 270) { val w = width; width = height; height = w }
                    facts = facts.copy(videoType = type, width = width, height = height, fps = format.fpsOrNull())
                } else if (type.startsWith("audio/") && facts.audioType == null) facts = facts.copy(audioType = type)
            }
        } catch (_: Exception) {
            // an unreadable file shows what the library already knows
        } finally { try { extractor.release() } catch (_: Exception) { } }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(uri))
            facts = facts.copy(containerType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE))
        } catch (_: Exception) {
        } finally { try { retriever.release() } catch (_: Exception) { } }
        return facts
    }

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    private fun MediaFormat.fpsOrNull(): Double? {
        if (!containsKey(MediaFormat.KEY_FRAME_RATE)) return null
        return runCatching { getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }.recoverCatching { getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() }.getOrNull()?.takeIf { it > 0 }
    }
}

/** The Details rows that come from the file itself, worded like Windows (the shared core names the codecs and the bitrate). */
object FileFactsWords {
    private val containers = mapOf(
        "video/mp4" to "MP4", "video/webm" to "WEBM", "video/x-matroska" to "MKV", "video/3gpp" to "3GP", "video/quicktime" to "MOV",
        "audio/mp4" to "M4A", "audio/mpeg" to "MP3", "audio/flac" to "FLAC", "audio/ogg" to "OGG", "audio/opus" to "OPUS", "audio/x-wav" to "WAV", "audio/wav" to "WAV",
        "audio/aac" to "AAC", "audio/x-matroska" to "MKA", "audio/webm" to "WEBM", "audio/amr" to "AMR",
    )

    fun container(type: String?): String? = type?.let { containers[it.lowercase().substringBefore(';').trim()] }

    /** Container, resolution, codecs and bitrate (the whole file's: its size over its length); only what is known. */
    fun rows(item: LibraryItem, facts: FileFacts?): List<Pair<String, String>> {
        val width = facts?.width ?: item.width
        val height = facts?.height ?: item.height
        val bitrate = item.duration?.takeIf { it > 0 && item.size > 0 }?.let { item.size * 8.0 / it / 1000 }
        return listOfNotNull(
            container(facts?.containerType)?.let { "Container" to it },
            MediaDetails.resolution(width, height)?.let { "Resolution" to it + (facts?.fps?.let { fps -> " · ${if (fps % 1.0 == 0.0) fps.toInt().toString() else "%.2f".format(java.util.Locale.ROOT, fps)} fps" } ?: "") },
            facts?.videoType?.takeIf { item.kind == LibraryItemKind.VIDEO }?.let { MediaDetails.codecName(it) }?.let { "Video codec" to it },
            facts?.audioType?.takeIf { item.kind == LibraryItemKind.VIDEO || item.kind == LibraryItemKind.AUDIO }?.let { MediaDetails.codecName(it) }?.let { "Audio codec" to it },
            bitrate?.let { MediaDetails.bitrate(it) }?.takeIf { it.isNotEmpty() }?.let { "Bitrate" to it },
        )
    }
}
