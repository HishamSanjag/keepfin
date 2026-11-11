package app.keepfin.ui.updates

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import org.json.JSONObject

/** One swipeable What's new page: a top change with its picture (assets `whats-new/<id>-android.png`). */
data class WhatsNewPage(val id: String, val title: String, val text: String)

/** Reads the pages of one version from `pages.json`; a version mismatch or unreadable text means no pages. */
object WhatsNewPages {
    fun parse(json: String, version: String): List<WhatsNewPage> = runCatching {
        val root = JSONObject(json)
        if (root.optString("version") != version) return emptyList()
        val array = root.optJSONArray("pages") ?: return emptyList()
        (0 until array.length()).mapNotNull { index ->
            val page = array.optJSONObject(index) ?: return@mapNotNull null
            val android = page.optJSONObject("android") ?: return@mapNotNull null
            val id = page.optString("id").trim()
            val title = android.optString("title").trim()
            val text = android.optString("text").trim()
            if (id.isEmpty() || title.isEmpty() || text.isEmpty()) null else WhatsNewPage(id, title, text)
        }
    }.getOrDefault(emptyList())

    /** The bundled pages for the running version; empty when the build has none. */
    fun bundled(context: Context, version: String): List<WhatsNewPage> = runCatching {
        parse(context.assets.open("whats-new/pages.json").bufferedReader(Charsets.UTF_8).use { it.readText() }, version)
    }.getOrDefault(emptyList())

    /** The picture of a page, or null when the build has none or it cannot be decoded. */
    fun picture(context: Context, id: String): ImageBitmap? = runCatching {
        context.assets.open("whats-new/$id-android.png").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
    }.getOrNull()
}
