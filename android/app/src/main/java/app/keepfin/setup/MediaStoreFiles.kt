package app.keepfin.setup

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import app.keepfin.LibraryCodec
import java.io.File
import java.io.InputStream

/** The earlier library's files through the content resolver (MediaStore rows, and the downloaded subtitles and lyrics beside them). */
internal class MediaStoreFiles(private val context: Context) : LegacyFiles {
    private val cr = context.contentResolver

    override fun size(uri: String): Long? = try {
        cr.query(Uri.parse(uri), arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    } catch (e: SecurityException) {
        throw e
    } catch (e: Exception) {
        null
    }

    override fun open(uri: String): InputStream = cr.openInputStream(Uri.parse(uri)) ?: throw java.io.FileNotFoundException(uri)

    override fun delete(uri: String): DeleteOutcome = try {
        // Android removes a row of this app directly; any other row throws and needs the user's yes first.
        if (cr.delete(Uri.parse(uri), null, null) > 0) DeleteOutcome.DELETED else if (size(uri) == null) DeleteOutcome.GONE else DeleteOutcome.FAILED
    } catch (e: SecurityException) {
        DeleteOutcome.NEEDS_CONSENT
    } catch (e: Exception) {
        DeleteOutcome.FAILED
    }

    override fun openArt(path: String): InputStream? = File(path).takeIf { it.isFile }?.inputStream()

    override fun locate(uri: String): app.keepfin.library.repo.LinkedLocator? = app.keepfin.library.repo.MediaStoreLinked.statOf(cr, uri)

    override fun sidecars(rec: LibraryCodec.Rec): List<LegacySidecar> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val name = rec.name.ifBlank { return emptyList() }
        val stem = name.substringBeforeLast('.')
        if (stem.isEmpty()) return emptyList()
        val out = ArrayList<LegacySidecar>()
        val files = MediaStore.Files.getContentUri("external")
        val like = stem.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + ".%"
        try {
            cr.query(
                files, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? ESCAPE '\\' AND ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf(like, "Download/%"), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val display = c.getString(1) ?: continue
                    if (display.substringAfterLast('.').lowercase() in app.keepfin.ALL_SIDECARS) out += LegacySidecar(ContentUris.withAppendedId(files, c.getLong(0)).toString(), display.substring(stem.length))
                }
            }
        } catch (e: Exception) {
            return emptyList()
        }
        return out
    }
}

/** What the system asks the user before originals can be removed. */
internal sealed class ConsentRequest {
    /** Launch with `StartIntentSenderForResult`. */
    class Sender(val intentSender: IntentSender) : ConsentRequest()

    /** Android 9 and older: the storage permission is the consent. */
    class Permission(val permission: String) : ConsentRequest()
}

internal object Consent {
    /** Files per request: Android 11+ accepts a long list; Android 10 asks one file at a time. */
    val batchSize: Int get() = if (Build.VERSION.SDK_INT == 29) 1 else MigrationRunner.BATCH

    fun request(context: Context, uris: List<String>): ConsentRequest? {
        val cr = context.contentResolver
        val parsed = uris.map(Uri::parse)
        return when {
            Build.VERSION.SDK_INT >= 30 -> ConsentRequest.Sender(MediaStore.createDeleteRequest(cr, parsed).intentSender)
            Build.VERSION.SDK_INT == 29 -> try {
                cr.delete(parsed.first(), null, null)
                null
            } catch (e: RecoverableSecurityException) {
                ConsentRequest.Sender(e.userAction.actionIntent.intentSender)
            } catch (e: Exception) {
                null
            }
            else -> ConsentRequest.Permission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }
}
