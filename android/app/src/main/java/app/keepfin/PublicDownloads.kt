package app.keepfin

import android.Manifest
import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File

/** Publishes only the app's completed outputs; no broad media-library permission on Android 10+. */
internal class PublicDownloads(private val context: Context) {
    data class Saved(val uri: Uri, val path: String)
    val folder = "${Environment.DIRECTORY_DOWNLOADS}/Keepfin"
    val videoFolder = "${Environment.DIRECTORY_MOVIES}/Keepfin"
    val audioFolder = "${Environment.DIRECTORY_MUSIC}/Keepfin"
    val imageFolder = "${Environment.DIRECTORY_PICTURES}/Keepfin"

    /**
     * Where a job's files go inside a space: one root-relative folder per storage root, e.g.
     * music = "Music/Keepfin Music/Album", movies = "Movies/Keepfin Music/Album", download = "Download/Keepfin Music/Album".
     */
    data class Placement(val music: String, val movies: String, val download: String, val pictures: String? = null) {
        fun folderFor(root: StorageRoot) = when (root) { StorageRoot.MUSIC -> music; StorageRoot.MOVIES -> movies; StorageRoot.DOWNLOAD -> download }
    }

    /** [photos]: the file is one of a photo post, so an image belongs in Pictures (a cover or other sidecar image stays with its download). */
    fun folderFor(source: File, placement: Placement? = null, photos: Boolean = false): String {
        if (photos && mimeFor(source).startsWith("image/")) return placement?.pictures ?: imageFolder
        val root = StorageRoot.ofMime(mimeFor(source))
        if (placement != null) return placement.folderFor(root)
        return when (root) { StorageRoot.MOVIES -> videoFolder; StorageRoot.MUSIC -> audioFolder; StorageRoot.DOWNLOAD -> folder }
    }

    internal fun mimeFor(source: File): String = when (source.extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "m4a" -> "audio/mp4"
        "opus", "ogg" -> "audio/ogg"
        else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(source.extension.lowercase()) ?: "application/octet-stream"
    }

    internal fun collectionFor(source: File, photos: Boolean = false): Uri = when {
        mimeFor(source).startsWith("video/") -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        mimeFor(source).startsWith("audio/") -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        photos && mimeFor(source).startsWith("image/") -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }

    /** Relocate only an owned legacy entry. MediaStore performs a collision-safe move of the same file. */
    fun repairGallery(saved: Saved, source: File): Saved {
        if (Build.VERSION.SDK_INT < 29) return saved
        val mime = mimeFor(source)
        if (!mime.startsWith("video/") && !mime.startsWith("audio/")) return saved
        require(source.isFile) { "The retained downloaded file is unavailable." }
        require(saved.uri.scheme == "content" && saved.uri.authority == MediaStore.AUTHORITY) { "Not an Android media entry." }
        val resolver = context.contentResolver
        val targetFolder = folderFor(source)
        val mediaUri = ContentUris.withAppendedId(collectionFor(source), ContentUris.parseId(saved.uri))
        fun ownedFolder(uri: Uri): String? = resolver.query(uri, arrayOf(MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)?.use {
            if (!it.moveToFirst()) return@use null
            check(it.getString(0) == context.packageName) { "Only this app's saved files can be organized." }
            it.getString(1)
        }
        // A crash after the OS move but before queue persistence can leave the old Downloads URI.
        val oldFolder = ownedFolder(saved.uri) ?: ownedFolder(mediaUri)
            ?: error("The saved file is no longer on the phone.")
        require(oldFolder in setOf("$folder/", "$targetFolder/")) { "The saved file was moved by the user." }
        val values = LocalMediaPreview.metadata(source, mime).apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$targetFolder/")
        }
        check(resolver.update(mediaUri, values, null, null) == 1) { "Android could not organize this saved media." }
        val path = resolver.query(mediaUri, arrayOf(MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
            check(it.moveToFirst()) { "Android could not find the organized media." }
            it.getString(0) + it.getString(1)
        } ?: error("Android could not find the organized media.")
        return Saved(mediaUri, path)
    }

    /**
     * Publishes [files] into [placement] (legacy Keepfin folders when null). [fromStem] is the stem the private files carry;
     * [stem] is the public file name stem. Name collisions get " (2)" suffixes across all files of one publish.
     * [photos]: the files are a photo post's (images go to Pictures/Keepfin, a video to Movies/Keepfin).
     */
    fun publish(files: List<File>, stem: String = files.first().nameWithoutExtension, placement: Placement? = null, fromStem: String = stem,
                photos: Boolean = false, cancelled: () -> Boolean = { false }): Map<File, Saved> {
        val saved = linkedMapOf<File, Saved>()
        try {
            val names = files.distinctBy { folderFor(it, placement, photos) }.flatMap { source ->
                val targetFolder = folderFor(source, placement, photos)
                if (Build.VERSION.SDK_INT >= 29) context.contentResolver.query(
                    collectionFor(source, photos), arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf("$targetFolder/"), null
                )?.use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } } ?: emptyList()
                else {
                    @Suppress("DEPRECATION")
                    val directory = File(Environment.getExternalStorageDirectory(), targetFolder)
                    directory.list()?.toList() ?: emptyList()
                }
            }.toSet()
            var suffix = 0
            fun name(source: File): String = (if (suffix == 0) stem else "$stem (${suffix + 1})") + source.name.removePrefix(fromStem)
            while (files.any { name(it) in names }) suffix++
            files.forEach {
                check(!cancelled()) { "Download cancelled." }
                saved[it] = publishOne(it, name(it), cancelled, placement, photos)
            }
            check(!cancelled()) { "Download cancelled." }
            return saved
        } catch (error: Exception) {
            // Roll back only copies created by this attempt. Originals remain available in the app.
            discard(saved.values)
            throw error
        }
    }

    /** Whether the saved copy at [uri] (a MediaStore or Keepfin folder document) can still be opened. */
    fun exists(uri: String): Boolean = try { context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false } catch (_: Exception) { false }

    fun discard(saved: Collection<Saved>) {
        saved.forEach {
            try {
                if (it.uri.scheme == "content") context.contentResolver.delete(it.uri, null, null)
                else it.uri.path?.let { path -> File(path).delete() }
            } catch (_: Exception) { }
        }
    }

    private fun copy(source: File, out: java.io.OutputStream, cancelled: () -> Boolean) {
        source.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                check(!cancelled()) { "Download cancelled." }
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
        }
    }

    private fun publishOne(source: File, requestedName: String, cancelled: () -> Boolean, placement: Placement?, photos: Boolean): Saved {
        require(source.isFile) { "The downloaded file is unavailable." }
        val mime = mimeFor(source)
        val targetFolder = folderFor(source, placement, photos)
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, requestedName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$targetFolder/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    putAll(LocalMediaPreview.metadata(source, mime))
                }
            }
            val uri = resolver.insert(collectionFor(source, photos), values)
                ?: error("Android could not create the download in $targetFolder.")
            try {
                resolver.openOutputStream(uri, "w")?.use { out -> copy(source, out, cancelled) }
                    ?: error("Android could not write the download.")
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
                    { "Android could not finish saving the download." }
                val name = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else requestedName
                } ?: requestedName
                return Saved(uri, "$targetFolder/$name")
            } catch (error: Exception) {
                try { resolver.delete(uri, null, null) } catch (_: Exception) { }
                throw error
            }
        }
        check(context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            "Allow storage access to save downloads to $targetFolder. Your downloaded file is safe in the app."
        }
        @Suppress("DEPRECATION")
        val directory = File(Environment.getExternalStorageDirectory(), targetFolder)
        check(directory.isDirectory || directory.mkdirs()) { "Could not create $targetFolder." }
        var suffix = 0
        var target: File
        do {
            val name = if (suffix == 0) requestedName else "${File(requestedName).nameWithoutExtension} (${suffix + 1}).${source.extension}"
            target = File(directory, name)
            suffix++
        } while (!target.createNewFile())
        try {
            target.outputStream().use { out -> copy(source, out, cancelled) }
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
            return Saved(Uri.fromFile(target), "$targetFolder/${target.name}")
        } catch (error: Exception) {
            target.delete()
            throw error
        }
    }
}
