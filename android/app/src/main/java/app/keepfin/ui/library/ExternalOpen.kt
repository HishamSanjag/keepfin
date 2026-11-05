package app.keepfin.ui.library

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind

/** "Open in another app" for library items; also used when Settings › Library › Open media with is set to another app. */
object ExternalOpen {
    fun mime(context: Context, item: LibraryItem): String =
        runCatching { context.contentResolver.getType(Uri.parse(item.uri)) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: when (item.kind) { LibraryItemKind.AUDIO -> "audio/*"; LibraryItemKind.VIDEO -> "video/*"; LibraryItemKind.IMAGE -> "image/*"; LibraryItemKind.OTHER -> "*/*" }

    /** Returns an error message or null on success. */
    fun open(context: Context, item: LibraryItem): String? = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(item.uri), mime(context, item))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        null
    } catch (_: ActivityNotFoundException) { "No other app on this phone can open this file." }
    catch (error: Exception) { error.message ?: "This file is unavailable." }

    fun share(context: Context, item: LibraryItem): String? = try {
        val send = Intent(Intent.ACTION_SEND).setType(mime(context, item)).putExtra(Intent.EXTRA_STREAM, Uri.parse(item.uri))
            .putExtra(Intent.EXTRA_TITLE, item.title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, "Share ${item.title}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (error: Exception) { error.message ?: "This file is unavailable." }
}
