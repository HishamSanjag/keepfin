package app.keepfin.core

import app.keepfin.core.gallery.EarlierSave
import app.keepfin.core.gallery.Gallery
import app.keepfin.core.gallery.GalleryRead
import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue

// fixtures/engine/gallery: see SCHEMA.md there. The engine output is a string, like the process output the apps pass.

internal fun galleryHandler(input: JsonObject): JsonValue {
    if ("chosen" in input.members) return galleryRangeHandler(input)
    if ("rule" in input.members) return gallerySavedHandler(input)
    val output = (input.members.getValue("output") as JsonString).value
    val url = (input.members.getValue("url") as JsonString).value
    val limit = (input.members["limit"] as? JsonNumber)?.value?.toInt() ?: 200
    return when (val r = Gallery.read(output, url, limit)) {
        is GalleryRead.Post -> obj(
            "kind" to jstr("post"), "title" to jstr(r.title), "uploader" to jstr(r.uploader), "category" to jstr(r.category),
            "provider" to jstr(r.provider), "previews" to arr(r.previews.map { jstr(it) }),
            "items" to arr(r.items.map { obj("position" to jnum(it.position), "url" to jstr(it.url), "photo" to JsonBool(it.photo)).let { item -> if (it.cover == null) item else JsonObject(LinkedHashMap(item.members + ("cover" to jstr(it.cover)))) } }), "count" to jnum(r.count),
            "truncated" to JsonBool(r.truncated),
        )
        is GalleryRead.Failed -> obj("kind" to jstr("failed"), "message" to jstr(r.message))
        GalleryRead.Empty -> obj("kind" to jstr("empty"))
    }
}

private fun ints(value: JsonValue?): List<Int>? = (value as? JsonArray)?.items?.map { (it as JsonNumber).value.toInt() }

private fun gallerySavedHandler(input: JsonObject): JsonValue = when ((input.members.getValue("rule") as JsonString).value) {
    "savedLabel" -> obj("label" to jstr(Gallery.savedLabel(
        (input.members.getValue("saved") as JsonNumber).value.toInt(), (input.members.getValue("expected") as JsonNumber).value.toInt(), (input.members.getValue("label") as JsonString).value,
    )))
    "already" -> {
        val earlier = (input.members.getValue("earlier") as JsonArray).items.map {
            val e = it as JsonObject
            EarlierSave((e.members.getValue("url") as JsonString).value, (e.members.getValue("count") as JsonNumber).value.toInt(), ints(e.members["items"]), (e.members.getValue("complete") as JsonBool).value)
        }
        obj("index" to jnum(Gallery.alreadySaved(
            (input.members.getValue("url") as JsonString).value, (input.members.getValue("count") as JsonNumber).value.toInt(), ints(input.members["items"]), earlier,
        )))
    }
    else -> obj("label" to jstr(Gallery.label((input.members.getValue("photos") as JsonNumber).value.toInt(), (input.members.getValue("videos") as JsonNumber).value.toInt())))
}

private fun galleryRangeHandler(input: JsonObject): JsonValue {
    val chosen = (input.members.getValue("chosen") as JsonArray).items.map { (it as JsonNumber).value.toInt() }
    val count = (input.members.getValue("count") as JsonNumber).value.toInt()
    return try {
        obj("range" to jstr(Gallery.range(chosen, count)))
    } catch (_: IllegalArgumentException) {
        obj("invalid" to JsonBool(true))
    }
}
