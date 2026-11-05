package app.keepfin.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import app.keepfin.EngineApi
import app.keepfin.Job
import app.keepfin.MediaKind
import java.io.File

/** Contextual file actions. The public MediaStore copy is preferred; the private original is the fallback. */
object FileActions {
    fun mime(job: Job, file: File?): String {
        val ext = file?.extension?.lowercase().orEmpty()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: if (job.kind == MediaKind.AUDIO) "audio/*" else "video/*"
    }

    private fun resolve(context: Context, engine: EngineApi, job: Job): Pair<Uri, String> {
        val file = runCatching { engine.file(job.id) }.getOrNull()?.takeIf { it.isFile }
        val public = job.publicUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        val uri = public ?: file?.let { FileProvider.getUriForFile(context, "${context.packageName}.files", it) }
            ?: error("This file is no longer on your phone. Download it again from its source.")
        return uri to mime(job, file ?: job.file?.let(::File))
    }

    /** Returns an error message or null on success. */
    fun open(context: Context, engine: EngineApi, job: Job): String? = try {
        val (uri, mime) = resolve(context, engine, job)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (_: ActivityNotFoundException) { "No app on this phone can open this file. Try Share or Save a copy." }
    catch (e: Exception) { e.message ?: "This file is unavailable." }

    fun share(context: Context, engine: EngineApi, job: Job): String? = try {
        val (uri, mime) = resolve(context, engine, job)
        val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TITLE, job.title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share ${job.title}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (e: Exception) { e.message ?: "This file is unavailable." }

    fun showSource(context: Context, url: String): String? = try {
        val uri = Uri.parse(url)
        require(uri.scheme == "https" || uri.scheme == "http") { "This source link cannot be opened." }
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (_: ActivityNotFoundException) { "No browser is available." }
    catch (e: Exception) { e.message ?: "This source link cannot be opened." }

    fun copyLink(context: Context, url: String): String? = try {
        context.getSystemService(android.content.ClipboardManager::class.java)
            .setPrimaryClip(android.content.ClipData.newPlainText("Keepfin link", url))
        "Link copied"
    } catch (e: Exception) { e.message ?: "Could not copy the link." }

    fun shareLink(context: Context, title: String, url: String): String? = try {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url).putExtra(Intent.EXTRA_SUBJECT, title)
        context.startActivity(Intent.createChooser(send, "Share $title").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (e: Exception) { e.message ?: "Could not share the link." }

    /** Copies the saved media to a user-chosen document (Save a copy to…). Blocking. */
    fun copyTo(context: Context, engine: EngineApi, job: Job, target: Uri) {
        val file = engine.file(job.id)
        val input = if (file.isFile) file.inputStream() else job.publicUri?.let { context.contentResolver.openInputStream(Uri.parse(it)) }
        (input ?: error("This file is no longer on your phone.")).use { source ->
            (context.contentResolver.openOutputStream(target) ?: error("Cannot write to this location.")).use { source.copyTo(it) }
        }
    }

    fun suggestedName(engine: EngineApi, job: Job): String =
        runCatching { engine.file(job.id).name }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: job.file?.let { File(it).name } ?: job.title
}
