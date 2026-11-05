package app.keepfin.library.repo

import android.content.ContentUris
import android.content.Context
import android.content.ContentValues
import android.content.IntentSender
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import app.keepfin.folder.writeLocalAtomic
import java.io.File
import org.json.JSONObject

// Linked spaces (setup "Leave"): their files stay in MediaStore. A placement `linked:<space>/<sub>/<name>` in the library says where the
// item sits; THIS device's address of the file (content uri, relative path, size, modified time) lives only here, in `linked.json`
// of the device directory, and is never synced (dev-docs/plans/KEEPFIN-FOLDER-ANDROID-SPEC.md sections 1, 5 and 7).

internal const val LINKED_PREFIX = "linked:"

internal fun isLinkedPlace(place: String?): Boolean = place != null && place.startsWith(LINKED_PREFIX)

/** The folder of a linked placement inside its space: `linked:music/Rock/a.mp3` is `Rock`. */
internal fun linkedSub(place: String): String = place.removePrefix(LINKED_PREFIX).substringAfter('/', "").substringBeforeLast('/', "")

internal fun linkedName(place: String): String = place.substringAfterLast('/')

/**
 * Where one linked file is on this device. [path] is the MediaStore relative path plus the file name (`Music/Keepfin/a.mp3`), [size]
 * in bytes, [mtime] in seconds (0 when unknown). [owned] is true for files this install created (changing them needs no consent).
 */
internal data class LinkedLocator(val uri: String, val path: String, val size: Long, val mtime: Long, val owned: Boolean = false) {
    /** A found file is the one we looked for when the path matches and, where both sides know them, the size and the modified time do too. */
    fun sameFileAs(found: LinkedLocator): Boolean {
        if (!path.equals(found.path, ignoreCase = true)) return false
        if (size > 0 && found.size > 0 && size != found.size) return false
        if (mtime > 0 && found.mtime > 0 && mtime != found.mtime) return false
        return true
    }
}

/** The device-only table item id to [LinkedLocator], kept in `linked.json` and written atomically. */
internal class LinkedStore(private val file: File) {
    @Volatile private var map: Map<String, LinkedLocator> = read()

    private fun read(): Map<String, LinkedLocator> {
        if (!file.isFile) return emptyMap()
        return try {
            val items = JSONObject(file.readText(Charsets.UTF_8)).optJSONObject("items") ?: return emptyMap()
            items.keys().asSequence().associateWith { id ->
                val o = items.getJSONObject(id)
                LinkedLocator(o.getString("u"), o.optString("p"), o.optLong("z"), o.optLong("m"), o.optBoolean("w", false))
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun get(itemId: String): LinkedLocator? = map[itemId]
    fun all(): Map<String, LinkedLocator> = map
    val size: Int get() = map.size

    @Synchronized fun putAll(entries: Map<String, LinkedLocator>) {
        if (entries.isEmpty() || entries.all { map[it.key] == it.value }) return
        map = map + entries
        write()
    }

    @Synchronized fun remove(ids: Collection<String>) {
        if (ids.none { it in map }) return
        map = map - ids.toSet()
        write()
    }

    private fun write() {
        val items = JSONObject()
        for ((id, l) in map) items.put(id, JSONObject().put("u", l.uri).put("p", l.path).put("z", l.size).put("m", l.mtime).put("w", l.owned))
        writeLocalAtomic(file, JSONObject().put("version", 1).put("items", items).toString().toByteArray(Charsets.UTF_8))
    }
}

/** The platform side of linked files (MediaStore on a phone, a fake in tests). May be called from any worker thread. */
internal interface LinkedMedia {
    /** The file now, or null when it is gone. */
    fun stat(uri: String): LinkedLocator?

    /** True when [canRead] is granted, so that "not found" really means not there. */
    fun canRead(): Boolean

    /** Which of [uris] are still there, or null when that cannot be told (the media permission is missing). */
    fun existing(uris: Collection<String>): Set<String>?

    /** Looks up files by `relative path + name` (lowercase keys). Only the found ones are returned. Needs [canRead]. */
    fun find(keys: Collection<String>): Map<String, LinkedLocator>

    /** The subset of [uris] Keepfin may not change without the user's yes. */
    fun withoutWriteAccess(uris: List<String>): List<String>

    /** The system question for changing ([delete] false) or removing ([delete] true) these files. */
    fun consentRequest(uris: List<String>, delete: Boolean): IntentSender

    /** The question behind a refusal Android allows the user to overrule (Android 10), or null. */
    fun recoverable(error: SecurityException): IntentSender?

    fun rename(uri: String, newName: String)

    /** Moves the file to another MediaStore folder (`Music/Rock/`). */
    fun move(uri: String, relativePath: String)

    /** Removes the file. True when it is gone. */
    fun delete(uri: String): Boolean
}

internal class MediaStoreLinked(private val context: Context) : LinkedMedia {
    private val cr = context.contentResolver

    override fun stat(uri: String): LinkedLocator? = statOf(cr, uri)

    override fun canRead(): Boolean = missingReadPermissions(context).isEmpty()

    private fun idOf(uri: String): Long? = uri.substringAfterLast('/').toLongOrNull()

    override fun existing(uris: Collection<String>): Set<String>? {
        if (!canRead()) return null
        val wanted = HashMap<Long, MutableList<String>>()
        for (u in uris) idOf(u)?.let { wanted.getOrPut(it) { ArrayList(1) } += u }
        val out = HashSet<String>()
        try {
            cr.query(MediaStore.Files.getContentUri("external"), arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { c ->
                while (c.moveToNext()) wanted[c.getLong(0)]?.let { out.addAll(it) }
            } ?: return null
        } catch (e: Exception) {
            return null
        }
        return out
    }

    override fun find(keys: Collection<String>): Map<String, LinkedLocator> {
        val want = keys.mapTo(HashSet()) { it.lowercase() }
        val out = HashMap<String, LinkedLocator>()
        if (want.isEmpty() || Build.VERSION.SDK_INT < 29) return out
        val files = MediaStore.Files.getContentUri("external")
        cr.query(
            files,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val path = (c.getString(1) ?: continue) + (c.getString(2) ?: continue)
                val key = path.lowercase()
                if (key in want) out[key] = LinkedLocator(ContentUris.withAppendedId(files, c.getLong(0)).toString(), path, c.getLong(3), c.getLong(4))
            }
        }
        return out
    }

    private fun writable(uri: Uri) = context.checkUriPermission(uri, android.os.Process.myPid(), android.os.Process.myUid(), android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED

    override fun withoutWriteAccess(uris: List<String>): List<String> = uris.filter { !writable(Uri.parse(it)) }

    override fun consentRequest(uris: List<String>, delete: Boolean): IntentSender {
        check(Build.VERSION.SDK_INT >= 30) { "Android didn't allow changing files from before the reinstall on this version." }
        val parsed = uris.map(Uri::parse)
        return (if (delete) MediaStore.createDeleteRequest(cr, parsed) else MediaStore.createWriteRequest(cr, parsed)).intentSender
    }

    override fun recoverable(error: SecurityException): IntentSender? =
        if (Build.VERSION.SDK_INT >= 29 && error is android.app.RecoverableSecurityException) error.userAction.actionIntent.intentSender else null

    override fun rename(uri: String, newName: String) {
        check(cr.update(Uri.parse(uri), ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) }, null, null) == 1) { "Android could not rename this file." }
    }

    override fun move(uri: String, relativePath: String) {
        check(Build.VERSION.SDK_INT >= 29) { "Moving files needs Android 10 or newer." }
        check(cr.update(Uri.parse(uri), ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath) }, null, null) == 1) { "Android could not move this file." }
    }

    override fun delete(uri: String): Boolean = cr.delete(Uri.parse(uri), null, null) > 0 || statOf(cr, uri) == null

    companion object {
        /** The MediaStore row behind [uri] as a locator, or null when there is none. A missing media permission surfaces as a SecurityException. */
        fun statOf(cr: android.content.ContentResolver, uri: String): LinkedLocator? {
            val projection = if (Build.VERSION.SDK_INT >= 29)
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
            else arrayOf("_data", MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
            return try {
                cr.query(Uri.parse(uri), projection, null, null, null)?.use { c ->
                    if (!c.moveToFirst()) return null
                    val name = c.getString(1).orEmpty()
                    val path = if (Build.VERSION.SDK_INT >= 29) (c.getString(0).orEmpty() + name) else c.getString(0).orEmpty().substringAfter("/storage/emulated/0/", name)
                    LinkedLocator(uri, path, if (c.isNull(2)) 0 else c.getLong(2), if (c.isNull(3)) 0 else c.getLong(3))
                }
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

        /** The read permissions linked files need (the same ones the setup asks for). Empty when granted. */
        fun missingReadPermissions(context: Context): List<String> {
            val needed = if (Build.VERSION.SDK_INT >= 33) listOf(android.Manifest.permission.READ_MEDIA_AUDIO, android.Manifest.permission.READ_MEDIA_VIDEO) else listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            return needed.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        }
    }
}

/** What "Find linked files" did. */
internal class RelinkReport(val matched: Int, val unmatched: Int)
