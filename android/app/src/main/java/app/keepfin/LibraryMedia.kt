package app.keepfin

import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** One MediaStore row in a space folder. */
internal data class MediaRow(
    val id: Long,
    val uri: Uri,
    val kind: LibraryItemKind,
    /** "Music/Keepfin Music/Album/" */
    val relativePath: String,
    val name: String,
    val mime: String,
    val size: Long,
    val modifiedAt: Long,
    val addedAt: Long,
    val owned: Boolean,
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val track: Int?,
    val year: Int?,
) {
    val stem get() = name.substringBeforeLast('.', name)
    val extension get() = name.substringAfterLast('.', "").lowercase()
}

/** MediaStore access for space folders. Keepfin-owned rows need no permission; with media-read permission other rows appear too. */
internal class MediaRows(private val context: Context) {
    private val resolver get() = context.contentResolver

    fun collection(kind: LibraryItemKind): Uri = when {
        Build.VERSION.SDK_INT < 29 -> when (kind) { LibraryItemKind.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI; else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI }
        kind == LibraryItemKind.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        kind == LibraryItemKind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    }

    /** The collection a stored file of [mime] belongs to. */
    fun collectionForMime(mime: String): Uri = collection(when { mime.startsWith("audio/") -> LibraryItemKind.AUDIO; mime.startsWith("video/") -> LibraryItemKind.VIDEO; else -> LibraryItemKind.OTHER })

    private fun like(value: String) = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun columns(kind: LibraryItemKind): Array<String> = buildList {
        add(MediaStore.MediaColumns._ID); add(MediaStore.MediaColumns.DISPLAY_NAME); add(MediaStore.MediaColumns.MIME_TYPE)
        add(MediaStore.MediaColumns.SIZE); add(MediaStore.MediaColumns.DATE_MODIFIED); add(MediaStore.MediaColumns.DATE_ADDED)
        if (Build.VERSION.SDK_INT >= 29) { add(MediaStore.MediaColumns.RELATIVE_PATH); add(MediaStore.MediaColumns.OWNER_PACKAGE_NAME) }
        @Suppress("DEPRECATION") if (Build.VERSION.SDK_INT < 29) add(MediaStore.MediaColumns.DATA)
        when (kind) {
            LibraryItemKind.AUDIO -> { add(MediaStore.Audio.Media.TITLE); add(MediaStore.Audio.Media.ARTIST); add(MediaStore.Audio.Media.ALBUM)
                add(MediaStore.Audio.Media.DURATION); add(MediaStore.Audio.Media.TRACK); add(MediaStore.Audio.Media.YEAR) }
            LibraryItemKind.VIDEO -> { add(MediaStore.Video.Media.TITLE); add(MediaStore.Video.Media.DURATION)
                if (Build.VERSION.SDK_INT >= 29) { add(MediaStore.MediaColumns.WIDTH); add(MediaStore.MediaColumns.HEIGHT) } }
            else -> if (Build.VERSION.SDK_INT >= 29) { add(MediaStore.MediaColumns.WIDTH); add(MediaStore.MediaColumns.HEIGHT) }
        }
    }.toTypedArray()

    private fun relativeOf(cursor: Cursor): String {
        if (Build.VERSION.SDK_INT >= 29) return cursor.str(MediaStore.MediaColumns.RELATIVE_PATH).orEmpty()
        @Suppress("DEPRECATION") val path = cursor.str(MediaStore.MediaColumns.DATA).orEmpty()
        val base = @Suppress("DEPRECATION") Environment.getExternalStorageDirectory().absolutePath.trimEnd('/') + "/"
        return if (path.startsWith(base)) path.removePrefix(base).substringBeforeLast('/', "") + "/" else ""
    }

    private fun Cursor.str(column: String): String? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }
    private fun Cursor.long(column: String): Long? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getLong(it) }
    private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() && it != "<unknown>" }

    private fun row(cursor: Cursor, kind: LibraryItemKind, collection: Uri): MediaRow {
        val id = cursor.long(MediaStore.MediaColumns._ID)!!
        val mime = cursor.str(MediaStore.MediaColumns.MIME_TYPE).orEmpty()
        val owner = if (Build.VERSION.SDK_INT >= 29) cursor.str(MediaStore.MediaColumns.OWNER_PACKAGE_NAME) else null
        val trackRaw = cursor.long(MediaStore.Audio.Media.TRACK)?.toInt()
        val relative = relativeOf(cursor)
        // MediaStore names the album after the folder when a file has no album tag; that is not a real album.
        val folderName = relative.trim('/').substringAfterLast('/')
        val album = clean(cursor.str(MediaStore.Audio.Media.ALBUM))?.takeUnless { it.equals(folderName, ignoreCase = true) }
        return MediaRow(
            id = id, uri = ContentUris.withAppendedId(collection, id),
            kind = if (kind == LibraryItemKind.OTHER && mime.startsWith("image/")) LibraryItemKind.IMAGE else kind,
            relativePath = relative, name = cursor.str(MediaStore.MediaColumns.DISPLAY_NAME).orEmpty(), mime = mime,
            size = cursor.long(MediaStore.MediaColumns.SIZE) ?: 0L, modifiedAt = (cursor.long(MediaStore.MediaColumns.DATE_MODIFIED) ?: 0L) * 1000L,
            addedAt = (cursor.long(MediaStore.MediaColumns.DATE_ADDED) ?: 0L) * 1000L,
            owned = Build.VERSION.SDK_INT < 29 || owner == context.packageName,
            title = clean(cursor.str(MediaStore.Audio.Media.TITLE)), artist = clean(cursor.str(MediaStore.Audio.Media.ARTIST)),
            album = album, durationMs = cursor.long(MediaStore.Audio.Media.DURATION)?.takeIf { it > 0 },
            width = cursor.long(MediaStore.MediaColumns.WIDTH)?.toInt()?.takeIf { it > 0 }, height = cursor.long(MediaStore.MediaColumns.HEIGHT)?.toInt()?.takeIf { it > 0 },
            // MediaStore packs disc*1000 + track.
            track = trackRaw?.let { it % 1000 }?.takeIf { it > 0 }, year = cursor.long(MediaStore.Audio.Media.YEAR)?.toInt()?.takeIf { it > 0 },
        )
    }

    data class Result(val rows: List<MediaRow>, val complete: Boolean)

    /** All rows in the space folders (every collection). [complete] is false when any query failed: callers must not prune on it. */
    fun query(all: List<Space>): Result {
        // Smart spaces have no folder: an empty folder would match the whole storage root.
        val spaces = all.filter { it.type == SpaceType.FOLDER && it.folder.isNotEmpty() }
        if (spaces.isEmpty()) return Result(emptyList(), true)
        val rows = ArrayList<MediaRow>()
        var complete = true
        val kinds = if (Build.VERSION.SDK_INT >= 29) listOf(LibraryItemKind.AUDIO, LibraryItemKind.VIDEO, LibraryItemKind.OTHER) else listOf(LibraryItemKind.AUDIO, LibraryItemKind.VIDEO)
        for (kind in kinds) {
            val root = StorageRoot.of(kind)
            val collection = collection(kind)
            val clauses = ArrayList<String>()
            val args = ArrayList<String>()
            for (space in spaces) {
                if (Build.VERSION.SDK_INT >= 29) { clauses.add("${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'"); args.add(like("${root.dir}/${space.folder}/") + "%") }
                else { @Suppress("DEPRECATION") clauses.add("${MediaStore.MediaColumns.DATA} LIKE ? ESCAPE '\\'"); args.add("%/" + like("${root.dir}/${space.folder}/") + "%") }
            }
            var selection = clauses.joinToString(" OR ", "(", ")")
            if (kind == LibraryItemKind.OTHER) selection += " AND ${MediaStore.MediaColumns.MIME_TYPE} NOT LIKE 'audio/%' AND ${MediaStore.MediaColumns.MIME_TYPE} NOT LIKE 'video/%'"
            try {
                resolver.query(collection, columns(kind), selection, args.toTypedArray(), null)?.use { cursor ->
                    while (cursor.moveToNext()) rows.add(row(cursor, kind, collection))
                } ?: run { complete = false }
            } catch (error: Exception) { Log.w(TAG, "query $kind failed", error); complete = false }
        }
        return Result(rows, complete)
    }

    /** One row by uri (a just-saved download), or null. */
    fun queryOne(uri: Uri, kind: LibraryItemKind): MediaRow? = try {
        resolver.query(uri, columns(kind), null, null, null)?.use { if (it.moveToFirst()) row(it, kind, collection(kind)) else null }
    } catch (error: Exception) { Log.w(TAG, "queryOne failed", error); null }

    /** File names already used in a folder of a collection ([relativePath] with trailing slash). */
    fun names(collection: Uri, relativePath: String): Set<String> = try {
        if (Build.VERSION.SDK_INT >= 29) resolver.query(collection, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ?", arrayOf(relativePath), null)?.use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } } ?: emptySet()
        else @Suppress("DEPRECATION") File(Environment.getExternalStorageDirectory(), relativePath).list()?.toSet() ?: emptySet()
    } catch (_: Exception) { emptySet() }

    /** Sidecar rows (lyrics, subtitles) in Download roots below the space folders whose names match [select]. */
    fun sidecars(all: List<Space>, extensions: Set<String>, stem: String? = null): List<MediaRow> {
        val spaces = all.filter { it.type == SpaceType.FOLDER && it.folder.isNotEmpty() }
        if (Build.VERSION.SDK_INT < 29 || spaces.isEmpty()) return emptyList()
        val collection = collection(LibraryItemKind.OTHER)
        val clauses = spaces.joinToString(" OR ", "(", ")") { "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? ESCAPE '\\'" }
        val args = ArrayList<String>(spaces.map { like("Download/${it.folder}/") + "%" })
        var selection = clauses
        if (stem != null) { selection += " AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\'"; args.add(like(stem) + ".%") }
        val out = ArrayList<MediaRow>()
        try {
            resolver.query(collection, columns(LibraryItemKind.OTHER), selection, args.toTypedArray(), null)?.use { c ->
                while (c.moveToNext()) { val r = row(c, LibraryItemKind.OTHER, collection); if (r.extension in extensions) out.add(r) }
            }
        } catch (error: Exception) { Log.w(TAG, "sidecar query failed", error) }
        return out
    }

    /** Local file path of an item when the app may read it directly; null otherwise. */
    @Suppress("DEPRECATION")
    fun path(uri: Uri): File? = try {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) File(c.getString(0)).takeIf { it.isFile && it.canRead() } else null
        }
    } catch (_: Exception) { null }

    companion object { private const val TAG = "KeepfinLibrary" }
}

/** Tags and artwork read from a media file. */
internal data class ReadTags(
    val title: String? = null, val artist: String? = null, val album: String? = null, val albumArtist: String? = null,
    val track: Int? = null, val disc: Int? = null, val year: Int? = null, val genre: String? = null,
    val duration: Double? = null, val width: Int? = null, val height: Int? = null, val art: String? = null,
)

/** Reads tags with MediaMetadataRetriever and stores artwork in the app-private art folder. */
internal class TagReader(private val context: Context, private val artDir: File) {
    private fun number(value: String?): Int? = value?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 }

    fun read(row: MediaRow, jobPoster: Bitmap?): ReadTags {
        if (row.kind == LibraryItemKind.OTHER || row.kind == LibraryItemKind.IMAGE) return readImage(row)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, row.uri)
            fun meta(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            val durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
            var width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            var height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) { val w = width; width = height; height = w }
            val year = meta(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4)?.toIntOrNull()
                ?: meta(MediaMetadataRetriever.METADATA_KEY_DATE)?.take(4)?.toIntOrNull()
            val art = when (row.kind) {
                LibraryItemKind.AUDIO -> retriever.embeddedPicture?.let { saveEmbedded(it) } ?: jobPoster?.let { savePoster(it, "a-${row.id}") }
                // the cover embedded in the file before a decoded frame: phones without a decoder for the video (AV1 at 1440p) get no frame
                else -> jobPoster?.let { savePoster(it, "v-${row.id}") } ?: retriever.embeddedPicture?.let { saveEmbedded(it) } ?: frame(retriever, durationMs, "v-${row.id}")
            }
            return ReadTags(
                title = meta(MediaMetadataRetriever.METADATA_KEY_TITLE), artist = meta(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album = meta(MediaMetadataRetriever.METADATA_KEY_ALBUM), albumArtist = meta(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                track = number(meta(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)), disc = number(meta(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                year = year?.takeIf { it > 0 }, genre = meta(MediaMetadataRetriever.METADATA_KEY_GENRE), duration = durationMs?.let { it / 1000.0 },
                width = width?.takeIf { it > 0 }, height = height?.takeIf { it > 0 }, art = art,
            )
        } catch (error: Exception) {
            Log.w(TAG, "tags unreadable for ${row.name}", error)
            return ReadTags(art = jobPoster?.let { savePoster(it, "p-${row.id}") })
        } finally { try { retriever.release() } catch (_: Exception) { } }
    }

    private fun readImage(row: MediaRow): ReadTags = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(row.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        ReadTags(width = bounds.outWidth.takeIf { it > 0 }, height = bounds.outHeight.takeIf { it > 0 })
    } catch (_: Exception) { ReadTags() }

    private fun frame(retriever: MediaMetadataRetriever, durationMs: Long?, name: String): String? = try {
        val time = ((durationMs ?: 0L) * 1000L * 0.10).toLong()
        val bitmap = if (Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 640, 640)
            else retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        bitmap?.let { try { savePoster(it, name) } finally { it.recycle() } }
    } catch (_: Exception) { null }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }.take(20)

    /** Cover bytes → downscaled JPEG named by content hash so every song of an album shares one file. */
    private fun saveEmbedded(bytes: ByteArray): String? {
        if (bytes.size > 12_000_000) return null
        val target = File(artDir, "${hash(bytes)}.jpg")
        if (target.isFile) return target.absolutePath
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        return try { writeJpeg(bitmap, target) } finally { bitmap.recycle() }
    }

    private fun savePoster(bitmap: Bitmap, name: String): String? = writeJpeg(bitmap, File(artDir, "$name.jpg"))

    private fun writeJpeg(bitmap: Bitmap, target: File): String? = try {
        artDir.mkdirs()
        val temp = File(artDir, target.name + ".tmp")
        FileOutputStream(temp).use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
        check(temp.renameTo(target)) { "art rename" }
        target.absolutePath
    } catch (error: Exception) { Log.w(TAG, "art write failed", error); null }

    companion object { private const val TAG = "KeepfinLibrary" }
}

/** Content fingerprint: file size plus SHA-256 of the first and last MiB. Identical files share it; edits to the middle of huge files are not detected. */
object Fingerprint {
    const val WINDOW = 1024 * 1024

    /** [read] returns up to `length` bytes from `offset`. */
    fun of(size: Long, read: (offset: Long, length: Int) -> ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(read(0, minOf(size, WINDOW.toLong()).toInt()))
        if (size > WINDOW) {
            val tail = minOf(size - WINDOW, WINDOW.toLong()).toInt()
            digest.update(read(size - tail, tail))
        }
        return "$size:" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun of(file: File): String = java.io.RandomAccessFile(file, "r").use { raf ->
        of(raf.length()) { offset, length -> ByteArray(length).also { raf.seek(offset); raf.readFully(it) } }
    }
}

/** ffmetadata / ebur128 parsing, kept pure for tests. */
object FfOutput {
    /** Parses `-f ffmetadata` output into lower-cased keys (first occurrence wins). Handles `\=`, `\;`, `\#`, `\\` and backslash-newline continuations. */
    fun metadata(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val lines = text.replace("\r\n", "\n").split('\n')
        var index = 0
        while (index < lines.size) {
            var line = lines[index++]
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("[")) continue
            while (line.endsWith("\\") && !line.endsWith("\\\\") && index < lines.size) line = line.dropLast(1) + "\n" + lines[index++]
            var split = -1
            var i = 0
            while (i < line.length) { if (line[i] == '\\') { i += 2; continue }; if (line[i] == '=') { split = i; break }; i++ }
            if (split <= 0) continue
            fun unescape(value: String) = Regex("\\\\(.)", RegexOption.DOT_MATCHES_ALL).replace(value) { it.groupValues[1] }
            out.putIfAbsent(unescape(line.substring(0, split)).lowercase(), unescape(line.substring(split + 1)))
        }
        return out
    }

    /** Integrated loudness in LUFS from ebur128's summary, or null. */
    fun integratedLoudness(log: String): Double? =
        Regex("I:\\s+(-?\\d+(?:\\.\\d+)?)\\s+LUFS").findAll(log).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull()

    /** Attenuation (≤ 0 dB) that brings [lufs] to the -14 LUFS target. */
    fun gainFor(lufs: Double): Double = (TARGET_LUFS - lufs).coerceIn(-30.0, 0.0)

    /** Attenuation from a ReplayGain track gain (reference -18 LUFS) moved to the -14 LUFS target. */
    fun gainFromReplayGain(trackGainDb: Double): Double = (trackGainDb + (TARGET_LUFS - REPLAYGAIN_REFERENCE)).coerceIn(-30.0, 0.0)

    fun replayGain(tags: Map<String, String>): Double? =
        (tags["replaygain_track_gain"] ?: tags["replaygain_album_gain"])?.let { Regex("-?\\d+(?:\\.\\d+)?").find(it)?.value?.toDoubleOrNull() }

    fun lyrics(tags: Map<String, String>): String? =
        (tags["lyrics"] ?: tags["unsyncedlyrics"] ?: tags["uslt"] ?: tags["syncedlyrics"] ?: tags.entries.firstOrNull { it.key.startsWith("lyrics-") }?.value)
            ?.takeIf { it.isNotBlank() }

    const val TARGET_LUFS = -14.0
    const val REPLAYGAIN_REFERENCE = -18.0
}

/** The bundled engine's ffmpeg executable, used for loudness, embedded lyrics and tag rewriting. Null-safe: everything degrades to "not available". */
/** What the lyrics step needs from the bundled ffmpeg; a seam for tests. */
internal interface TagTools {
    fun available(): Boolean
    fun metadata(file: File): Map<String, String>?
    fun run(args: List<String>, timeoutSeconds: Long = 120): MediaTools.Output?
}

internal class MediaTools(private val context: Context, private val ensureInit: () -> Unit) : TagTools {
    data class Output(val exit: Int, val text: String)

    private val executable: File? get() = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so").takeIf { it.isFile }

    @Volatile private var usable: Boolean? = null

    private fun environment(builder: ProcessBuilder) {
        val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
        builder.environment()["LD_LIBRARY_PATH"] = listOf(File(packages, "ffmpeg/usr/lib"), File(packages, "python/usr/lib")).filter { it.isDirectory }.joinToString(":") { it.absolutePath }
        builder.environment()["TMPDIR"] = context.cacheDir.absolutePath
    }

    /** Runs ffmpeg with [args]; null when the binary cannot start. Output is stdout+stderr. */
    override fun run(args: List<String>, timeoutSeconds: Long): Output? {
        val binary = executable ?: return null
        try { ensureInit() } catch (error: Throwable) { Log.w(TAG, "engine init failed", error); return null }
        return try {
            val builder = ProcessBuilder(listOf(binary.absolutePath, "-hide_banner", "-nostdin") + args).redirectErrorStream(true)
            environment(builder)
            val process = builder.start()
            val buffer = ByteArrayOutputStream()
            val reader = Thread { try { process.inputStream.copyTo(buffer) } catch (_: Exception) { } }.apply { isDaemon = true; start() }
            if (!process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); reader.join(2000); return Output(-1, "timed out") }
            reader.join(5000)
            Output(process.exitValue(), buffer.toString(Charsets.UTF_8.name()))
        } catch (error: Exception) { Log.w(TAG, "ffmpeg failed to start", error); null }
    }

    /** True when the binary starts on this device (checked once). */
    override fun available(): Boolean = usable ?: (run(listOf("-version"), 20)?.let { it.exit == 0 && it.text.contains("ffmpeg version") } == true).also { usable = it }

    override fun metadata(file: File): Map<String, String>? =
        run(listOf("-v", "error", "-i", file.absolutePath, "-f", "ffmetadata", "-"), 60)?.takeIf { it.exit == 0 }?.let { FfOutput.metadata(it.text) }

    fun loudness(file: File): Double? =
        run(listOf("-nostats", "-i", file.absolutePath, "-vn", "-af", "ebur128=framelog=quiet", "-f", "null", "-"), 180)?.takeIf { it.exit == 0 }?.let { FfOutput.integratedLoudness(it.text) }

    companion object { private const val TAG = "KeepfinLibrary" }
}
