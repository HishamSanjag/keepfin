@file:OptIn(ExperimentalJsExport::class)

package app.keepfin.core.gallery

// The JavaScript surface of the gallery core (see gui/shared/core/gallery.ts). `kind` is "post", "failed" or "empty".

@JsExport
class JsGalleryItem(val position: Int, val url: String, val photo: Boolean, val cover: String?)

@JsExport
class JsGalleryRead(
    val kind: String,
    val message: String?,
    val title: String?,
    val uploader: String?,
    val category: String?,
    val provider: String?,
    val previews: Array<String>,
    val items: Array<JsGalleryItem>,
    val count: Int,
    val truncated: Boolean,
)

@JsExport
fun galleryRead(json: String, url: String, limit: Int): JsGalleryRead = when (val r = Gallery.read(json, url, limit)) {
    is GalleryRead.Post -> JsGalleryRead("post", null, r.title, r.uploader, r.category, r.provider, r.previews.toTypedArray(), r.items.map { JsGalleryItem(it.position, it.url, it.photo, it.cover) }.toTypedArray(), r.count, r.truncated)
    is GalleryRead.Failed -> JsGalleryRead("failed", r.message, null, null, null, null, emptyArray(), emptyArray(), 0, false)
    GalleryRead.Empty -> JsGalleryRead("empty", null, null, null, null, null, emptyArray(), emptyArray(), 0, false)
}

/** The engine range for the chosen 1-based positions, or null when all are chosen; throws on invalid input. */
@JsExport
fun galleryRange(chosen: Array<Int>, count: Int): String? = Gallery.range(chosen.toList(), count)

/** One finished photo job, for [galleryAlreadySaved]; [allItems] true means every photo of the post was asked for ([items] is then empty). */
@JsExport
class JsEarlierSave(val url: String, val count: Int, val items: Array<Int>, val allItems: Boolean, val complete: Boolean)

/** Index into [earlier] of the save that already holds the photos asked for, or -1. [allItems] true = every photo of the post. */
@JsExport
fun galleryAlreadySaved(url: String, count: Int, items: Array<Int>, allItems: Boolean, earlier: Array<JsEarlierSave>): Int =
    Gallery.alreadySaved(url, count, if (allItems) null else items.toList(), earlier.map { EarlierSave(it.url, it.count, if (it.allItems) null else it.items.toList(), it.complete) })

@JsExport
fun galleryLabel(photos: Int, videos: Int): String = Gallery.label(photos, videos)

@JsExport
fun gallerySavedLabel(saved: Int, expected: Int, label: String): String = Gallery.savedLabel(saved, expected, label)
