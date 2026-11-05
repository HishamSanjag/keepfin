package app.keepfin.setup

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.keepfin.folder.KeepfinFolders
import java.io.File

/**
 * "Use the recommended place": Keepfin makes `Documents/Keepfin` itself and opens the system picker inside it, so the user only
 * confirms ("Use this folder", then "Allow"). Android grants a folder through the picker alone; this does everything else.
 *
 * Whatever happens, making the folder never blocks: if it cannot be made, the picker opens at `Documents` instead.
 * This object holds the decisions (pure, unit-tested); [PlatformMaker] does the platform calls.
 */
internal object RecommendedPlace {
    const val FOLDER = "Documents/Keepfin"
    const val DOCUMENT_ID = "primary:Documents/Keepfin"
    const val DOCUMENTS_ID = "primary:Documents"
    /** "Choose another place" and "I already have a Keepfin folder" open at the top of the storage. */
    const val STORAGE_ROOT_ID = "primary:"

    enum class Route { MEDIASTORE, FILE, ASK_PERMISSION }

    /** Android 10 and newer make the folder through MediaStore (no permission). 9 and older need the storage permission first. */
    fun route(sdk: Int, canWrite: Boolean): Route = when {
        sdk >= 29 -> Route.MEDIASTORE
        canWrite -> Route.FILE
        else -> Route.ASK_PERMISSION
    }

    sealed class Outcome {
        /** Open the picker inside this document. */
        data class Open(val documentId: String) : Outcome()
        /** Ask for the storage permission, then call [afterPermission]. */
        data object AskPermission : Outcome()
    }

    /** The two ways to make the folder, behind an interface so that the fallbacks can be tested. Each returns whether the folder now exists. */
    interface Maker {
        /**
         * A short `Documents/Keepfin/README.txt` through MediaStore, which makes the folder. (MediaStore renames a hidden `.keepfin` folder
         * to `_.keepfin`, so the note sits in the folder itself; Keepfin makes `.keepfin` through the granted folder later.)
         */
        fun viaMediaStore(): Boolean
        /** Android 9 and older with the storage permission: plain folders. */
        fun viaFile(): Boolean
    }

    private fun safe(block: () -> Boolean): Boolean = try { block() } catch (e: Exception) { false }

    fun prepare(sdk: Int, canWrite: Boolean, maker: Maker): Outcome {
        val created = when (route(sdk, canWrite)) {
            Route.MEDIASTORE -> safe(maker::viaMediaStore)
            Route.FILE -> safe(maker::viaFile)
            Route.ASK_PERMISSION -> return Outcome.AskPermission
        }
        return Outcome.Open(if (created) DOCUMENT_ID else DOCUMENTS_ID)
    }

    /** The answer of the storage permission (Android 9 and older): denied still opens the picker, at Documents. */
    fun afterPermission(granted: Boolean, maker: Maker): Outcome =
        if (granted) Outcome.Open(if (safe(maker::viaFile)) DOCUMENT_ID else DOCUMENTS_ID) else Outcome.Open(DOCUMENTS_ID)
}

/** Makes the recommended folder on a phone. */
internal class PlatformMaker(private val context: Context) : RecommendedPlace.Maker {
    private val cr get() = context.contentResolver

    override fun viaMediaStore(): Boolean = logged("folder note") { note("Documents/Keepfin/") }

    private fun logged(what: String, block: () -> Boolean): Boolean = try { block() } catch (e: Exception) { android.util.Log.w("KeepfinSetup", "recommended place: $what failed", e); throw e }

    override fun viaFile(): Boolean {
        @Suppress("DEPRECATION") val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        val data = File(documents, "Keepfin/.keepfin")
        if (!data.isDirectory && !data.mkdirs()) return false
        val readme = File(data.parentFile, "README.txt")
        if (!readme.exists()) readme.writeText(FOLDER_NOTE, Charsets.UTF_8)
        return data.isDirectory
    }

    /** True when the folder [relativePath] now exists on the disk. A note that is already there is left alone (a reinstall). */
    private fun note(relativePath: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val name = "README.txt"
        val there = cr.query(
            files, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} = ?", arrayOf(relativePath, name), null,
        )?.use { it.moveToFirst() } == true
        if (there || onDisk(relativePath, name)) return true
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = cr.insert(files, values) ?: return false
        try {
            cr.openOutputStream(uri)?.use { it.write(FOLDER_NOTE.toByteArray(Charsets.UTF_8)) } ?: throw java.io.IOException("no stream")
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { cr.delete(uri, null, null) }
            return false
        }
        // a note of an earlier install is invisible to this query: Android then names ours "README (1).txt", which Keepfin does not want
        val actual = cr.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        if (actual != null && actual != name) runCatching { cr.delete(uri, null, null) }
        return true
    }

    /** MediaStore does not list files in hidden folders to every query, so the disk is asked as well (a stat works for files of an earlier install too). */
    @Suppress("DEPRECATION")
    private fun onDisk(relativePath: String, name: String): Boolean = try {
        File(Environment.getExternalStorageDirectory(), relativePath + name).exists()
    } catch (e: Exception) {
        false
    }

    companion object {
        const val FOLDER_NOTE = "This is your Keepfin folder. Keepfin keeps your music, videos and library here. Moving or deleting it loses them."
        fun canWrite(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
}
