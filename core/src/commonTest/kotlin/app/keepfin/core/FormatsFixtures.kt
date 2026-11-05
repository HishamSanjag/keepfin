package app.keepfin.core

import app.keepfin.core.formats.FormatOptions
import app.keepfin.core.formats.Formats
import app.keepfin.core.formats.Title
import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNull
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.json.parseJson
import app.keepfin.core.json.jsonQuote

// fixtures/engine/formats: see SCHEMA.md there. The info is passed to the core as JSON text, like the apps do.

private fun JsonObject.string(key: String): String? = (members[key] as? JsonString)?.value

private fun JsonObject.number(key: String): Double? = (members[key] as? JsonNumber)?.value

private fun infoText(input: JsonObject): String {
    val file = input.string("infoFile")
    if (file != null) return readSample("formats", file)
    return writeJson(input.members.getValue("info"))
}

private fun writeJson(value: JsonValue): String = when (value) {
    is JsonNull -> "null"
    is JsonBool -> value.value.toString()
    is JsonNumber -> numberText(value.value)
    is JsonString -> jsonQuote(value.value)
    is JsonArray -> value.items.joinToString(",", "[", "]") { writeJson(it) }
    is JsonObject -> value.members.entries.joinToString(",", "{", "}") { jsonQuote(it.key) + ":" + writeJson(it.value) }
}

private fun jbool(value: Boolean): JsonValue = JsonBool(value)

private fun jlong(value: Long?): JsonValue = if (value == null) JsonNull else JsonNumber(value.toDouble())

internal fun formatsHandler(input: JsonObject, options: FormatOptions): JsonValue {
    return when (val op = input.string("op")) {
        "media" -> {
            val media = Formats.mediaInfo(input.string("url")!!, infoText(input), options)
            val video = media.video.map { v ->
                obj(
                    "id" to jstr(v.id), "height" to jnum(v.height), "fps" to jnum(v.fps?.toDouble()), "hdr" to jbool(v.hdr), "codec" to jstr(v.codec),
                    "formatId" to jstr(v.formatId), "hasAudio" to jbool(v.hasAudio), "size" to jlong(v.size), "sizeApprox" to jbool(v.sizeApprox),
                )
            }
            val audio = media.audio.map { a ->
                obj(
                    "formatId" to jstr(a.formatId), "codec" to jstr(a.codec), "language" to jstr(a.language),
                    "size" to jlong(a.size), "sizeApprox" to jbool(a.sizeApprox),
                )
            }
            val pickVideo = (input.members["pickVideo"] as? JsonArray)?.items?.map { (it as JsonString).value }
            val pickAudio = (input.members["pickAudio"] as? JsonArray)?.items?.map { (it as JsonString).value }
            val music = media.music
            obj(
                "provider" to jstr(media.provider), "title" to jstr(media.title), "uploader" to jstr(media.uploader),
                "duration" to jnum(media.duration), "isLive" to jbool(media.isLive), "chapters" to jnum(media.chapters),
                "playlistUrl" to jstr(media.playlistUrl), "thumbnail" to jstr(media.thumbnail),
                "music" to if (music == null) JsonNull else obj(
                    "artist" to jstr(music.artist), "album" to jstr(music.album), "track" to jstr(music.track),
                    "releaseYear" to jnum(music.releaseYear?.toDouble()),
                ),
                "videoIds" to arr(media.video.map { jstr(it.id) }),
                "video" to arr(pickVideo?.map { id -> video.firstOrNull { (it.members["id"] as JsonString).value == id } ?: JsonNull } ?: video),
                "audio" to arr(pickAudio?.map { id -> audio.firstOrNull { (it.members["formatId"] as JsonString).value == id } ?: JsonNull } ?: audio),
                "hasVideoOptions" to jbool(media.video.isNotEmpty()), "hasAudioOptions" to jbool(media.audio.isNotEmpty()),
            )
        }
        "size" -> {
            val size = Formats.sizeOf(writeJson(input.members.getValue("format")), input.number("duration"))
            obj("size" to jlong(size.bytes), "approx" to jbool(size.approx))
        }
        "codec" -> {
            val value = input.string("value")
            jstr(if (input.string("kind") == "video") Formats.videoCodec(value) else Formats.audioCodec(value))
        }
        "title" -> jstr(Title.clean(input.string("title"), input.string("uploader"), input.string("provider")!!, input.string("kind") ?: "video"))
        "subtitles" -> arr(Formats.subtitlesOf(infoText(input)).map { obj("lang" to jstr(it.lang), "auto" to jbool(it.auto)) })
        "pageUrl" -> jstr(Formats.mediaInfo(input.string("url")!!, infoText(input), options).webpageUrl)
        "heightLabel" -> jstr(Formats.heightLabel(input.number("height")!!.toInt()))
        else -> error("unknown formats op $op")
    }
}

internal val desktopFormatOptions = FormatOptions(codecPreference = "auto", skipDrm = false)

/** Phones: vp9 above av1, and formats flagged as DRM-protected are never offered. */
internal val androidFormatOptions = FormatOptions(codecPreference = "vp9", skipDrm = true)
