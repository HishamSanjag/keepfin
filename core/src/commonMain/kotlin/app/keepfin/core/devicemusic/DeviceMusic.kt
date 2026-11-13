package app.keepfin.core.devicemusic

import app.keepfin.core.music.asciiLower

/** One audio file a device lists: Android's MediaStore row, or a Windows file with its folder relative to the root. */
data class DeviceAudioRow(
    val relativePath: String,
    val displayName: String,
    val durationMs: Long,
    val mime: String,
    val isMusic: Boolean = false,
    val isRingtone: Boolean = false,
    val isNotification: Boolean = false,
    val isAlarm: Boolean = false,
    val isRecording: Boolean = false,
    val isPodcast: Boolean = false,
    val isAudiobook: Boolean = false,
)

/** What counts as "Music on this device": decided once for both apps (fixtures/engine/devicemusic). */
object DeviceMusic {
    const val MIN_MS = 30_000L

    val DEFAULT_LEFT_OUT_NAMES: List<String> = listOf(
        "ringtones", "notifications", "alarms", "recordings", "voice recorder", "voice notes",
        "call recordings", "call recorder", "whatsapp audio", "whatsapp voice notes", "sounds", "ui sounds",
    )

    private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "alac", "aiff", "aif", "ape", "wv", "mka")

    fun include(row: DeviceAudioRow, leftOut: List<String> = emptyList()): Boolean {
        if (row.isRingtone || row.isNotification || row.isAlarm || row.isRecording || row.isPodcast || row.isAudiobook) return false
        if (row.durationMs < MIN_MS) return false
        if (!isAudio(row)) return false
        if (leftOutFolder(row.relativePath, leftOut)) return false
        if (!row.isMusic && hasLeftOutName(row.relativePath)) return false
        return true
    }

    /** True when the folder is inside one of the owner's left-out folders (prefix on whole segments, any case). */
    fun leftOutFolder(relativePath: String, leftOut: List<String>): Boolean {
        val path = asciiLower(withSlash(relativePath))
        return leftOut.any { entry ->
            val e = asciiLower(withSlash(entry))
            e != "/" && path.startsWith(e)
        }
    }

    /** An id safe for the media protocol ([A-Za-z0-9_-], at most 100 characters): "dm-<volume>-<key>". */
    fun idOf(volume: String, key: String): String = ("dm-" + safe(volume) + "-" + safe(key)).take(MAX_ID)

    private const val MAX_ID = 100

    private fun safe(text: String): String = buildString(text.length) {
        for (c in text) append(if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_') c else '_')
    }

    private fun isAudio(row: DeviceAudioRow): Boolean {
        val mime = asciiLower(row.mime)
        if (mime.isNotEmpty()) return mime.startsWith("audio/")
        val dot = row.displayName.lastIndexOf('.')
        return dot >= 0 && asciiLower(row.displayName.substring(dot + 1)) in AUDIO_EXTENSIONS
    }

    private fun hasLeftOutName(relativePath: String): Boolean =
        relativePath.split('/').any { it.isNotEmpty() && asciiLower(it) in DEFAULT_LEFT_OUT_NAMES }

    private fun withSlash(path: String): String = if (path.endsWith("/")) path else "$path/"
}
