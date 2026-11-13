package app.keepfin.devicemusic

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import app.keepfin.core.devicemusic.DeviceAudioRow
import app.keepfin.core.devicemusic.DeviceMusic

/** One audio file the device lists (a MediaStore row). The rules for what counts as music live in the shared core. */
internal data class DeviceFile(
    val volume: String,
    val mediaId: Long,
    val uri: String,
    val relativePath: String,
    val displayName: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val track: Int?,
    val year: Int?,
    val durationMs: Long,
    val size: Long,
    val dateModified: Long,
    val dateAdded: Long,
    val mime: String,
    val isMusic: Boolean = false,
    val isRingtone: Boolean = false,
    val isNotification: Boolean = false,
    val isAlarm: Boolean = false,
    val isRecording: Boolean = false,
    val isPodcast: Boolean = false,
    val isAudiobook: Boolean = false,
) {
    val id: String get() = DeviceMusic.idOf(volume, mediaId.toString())

    fun audioRow() = DeviceAudioRow(
        relativePath, displayName, durationMs, mime, isMusic, isRingtone, isNotification, isAlarm, isRecording, isPodcast, isAudiobook,
    )
}

/** Where the device's audio is listed from (MediaStore on a phone, a fake in tests). Blocking: call off the main thread. */
internal interface DeviceAudioSource {
    fun volumes(): List<String>

    /** Up to [limit] files of [volume] with a media id above [afterId], by id. */
    fun batch(volume: String, afterId: Long, limit: Int): List<DeviceFile>
}

/** What the library needs from the phone: the audio list, the permission, change notices and the folders Keepfin owns. */
internal interface DeviceMusicPlatform {
    val source: DeviceAudioSource

    /** The permissions to ask for before the list can be read, empty when granted. */
    fun missingPermissions(): List<String>

    /** Calls [onChange] whenever the device's audio changes; the result stops watching. */
    fun watch(onChange: () -> Unit): () -> Unit

    /** Relative folders (`Music/Keepfin`, any case) whose files Keepfin saved itself and already lists. */
    val ownRoots: List<String>
}

internal class MediaStoreAudioSource(private val context: Context) : DeviceAudioSource {
    private val cr: ContentResolver = context.contentResolver

    override fun volumes(): List<String> =
        if (Build.VERSION.SDK_INT >= 29) MediaStore.getExternalVolumeNames(context).toList() else listOf("external")

    @Suppress("DEPRECATION")
    override fun batch(volume: String, afterId: Long, limit: Int): List<DeviceFile> {
        val base = MediaStore.Audio.Media.getContentUri(volume)
        val modern = Build.VERSION.SDK_INT >= 29
        val projection = ArrayList<String>().apply {
            addAll(listOf("_id", "_display_name", "title", "artist", "album", "album_artist", "track", "year", "duration", "_size", "date_modified", "date_added", "mime_type", "is_music", "is_ringtone", "is_notification", "is_alarm"))
            if (modern) add("relative_path") else add("_data")
            if (modern) { add("is_podcast"); add("is_audiobook") }
            if (Build.VERSION.SDK_INT >= 31) add("is_recording")
        }
        val out = ArrayList<DeviceFile>(limit)
        val args = arrayOf(afterId.toString(), DeviceMusic.MIN_MS.toString())
        // the limit goes in the query arguments: a LIMIT inside the sort order is refused from Android 11
        val query = android.os.Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "_id > ? AND duration >= ? AND mime_type LIKE 'audio/%'")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "_id ASC")
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
        }
        cr.query(base, projection.toTypedArray(), query, null)?.use { c ->
            val at = projection.withIndex().associate { it.value to it.index }
            fun text(name: String): String? = at[name]?.let { i -> if (c.isNull(i)) null else c.getString(i) }
            fun num(name: String): Long = at[name]?.let { i -> if (c.isNull(i)) 0L else c.getLong(i) } ?: 0L
            while (c.moveToNext()) {
                val id = num("_id")
                val name = text("_display_name").orEmpty()
                val rel = if (modern) text("relative_path").orEmpty() else legacyFolder(text("_data").orEmpty())
                val rawTrack = num("track").toInt().takeIf { it > 0 }
                out += DeviceFile(
                    volume = volume, mediaId = id, uri = ContentUris.withAppendedId(base, id).toString(), relativePath = rel, displayName = name,
                    title = text("title")?.takeIf { it.isNotBlank() }, artist = text("artist")?.takeIf { it.isNotBlank() && it != "<unknown>" },
                    album = text("album")?.takeIf { it.isNotBlank() }, albumArtist = text("album_artist")?.takeIf { it.isNotBlank() },
                    track = rawTrack, year = num("year").toInt().takeIf { it > 0 }, durationMs = num("duration"), size = num("_size"),
                    dateModified = num("date_modified"), dateAdded = num("date_added"), mime = text("mime_type").orEmpty(),
                    isMusic = num("is_music") != 0L, isRingtone = num("is_ringtone") != 0L, isNotification = num("is_notification") != 0L, isAlarm = num("is_alarm") != 0L,
                    isRecording = num("is_recording") != 0L, isPodcast = num("is_podcast") != 0L, isAudiobook = num("is_audiobook") != 0L,
                )
            }
        }
        return out
    }

    /** Before Android 10 only the file path is known: its folder under the storage root, with a closing slash. */
    private fun legacyFolder(path: String): String = path.substringAfter("/storage/emulated/0/", path).substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
}

/** [keepfinFolder] is the Keepfin folder's own place on the storage (`Keepfin`), whose files the library already lists. */
internal class AndroidDeviceMusicPlatform(private val context: Context, keepfinFolder: String? = null) : DeviceMusicPlatform {
    override val source: DeviceAudioSource = MediaStoreAudioSource(context)

    override fun missingPermissions(): List<String> {
        val needed = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        return if (androidx.core.content.ContextCompat.checkSelfPermission(context, needed) == android.content.pm.PackageManager.PERMISSION_GRANTED) emptyList() else listOf(needed)
    }

    override fun watch(onChange: () -> Unit): () -> Unit {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        val uri: Uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.registerContentObserver(uri, true, observer)
        return { context.contentResolver.unregisterContentObserver(observer) }
    }

    override val ownRoots: List<String> = listOf("Music/Keepfin", "Movies/Keepfin", "Download/Keepfin") + listOfNotNull(keepfinFolder?.trim('/')?.takeIf { it.isNotEmpty() }?.let { "$it/" })
}
