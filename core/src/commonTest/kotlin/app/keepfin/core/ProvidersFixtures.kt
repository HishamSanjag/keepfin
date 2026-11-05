package app.keepfin.core

import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.providers.MusicLink
import app.keepfin.core.providers.Naming
import app.keepfin.core.providers.PathJob
import app.keepfin.core.providers.Providers
import app.keepfin.core.providers.SpaceContext
import app.keepfin.core.providers.Url

// fixtures/engine/providers: see SCHEMA.md there.

private fun JsonObject.string(key: String): String? = (members[key] as? JsonString)?.value

private fun JsonObject.number(key: String): Double? = (members[key] as? JsonNumber)?.value

private fun JsonObject.bool(key: String): Boolean? = (members[key] as? JsonBool)?.value

private fun JsonObject.obj(key: String): JsonObject? = members[key] as? JsonObject

private fun pathJobOf(input: JsonObject): PathJob {
    val job = input.obj("job")!!
    val music = job.obj("music")
    return PathJob(
        url = job.string("url") ?: "",
        title = job.string("title")!!,
        uploader = job.string("uploader"),
        createdAt = (job.number("createdAt") ?: 0.0).toLong(),
        groupTitle = job.obj("group")?.string("title"),
        musicArtist = music?.string("artist"),
        musicAlbum = music?.string("album"),
        musicTrack = music?.string("track"),
    )
}

private fun spaceContextOf(input: JsonObject): SpaceContext {
    val ctx = input.obj("context")!!
    return SpaceContext(
        title = ctx.string("title")!!,
        uploader = ctx.string("uploader"),
        artist = ctx.string("artist"),
        album = ctx.string("album"),
        url = ctx.string("url") ?: "",
        groupTitle = ctx.string("groupTitle"),
        groupKind = ctx.string("groupKind"),
        audio = ctx.bool("audio") ?: false,
        dateMillis = (ctx.number("dateMillis") ?: 0.0).toLong(),
    )
}

private fun musicLinkJson(link: MusicLink?): JsonValue =
    if (link == null) jstr(null) else obj("service" to jstr(link.service), "type" to jstr(link.type), "id" to jstr(link.id), "storefront" to jstr(link.storefront))

internal fun providersHandler(input: JsonObject): JsonValue {
    val url = input.string("url") ?: ""
    return when (val op = input.string("op")) {
        "name" -> jstr(Providers.name(url, input.string("extractor")))
        "folder" -> jstr(Providers.folder(url))
        "musicService" -> jstr(Providers.musicService(url))
        "musicLink" -> musicLinkJson(Providers.musicLink(url))
        "collection" -> JsonBool(Providers.isCollection(url))
        "playlistUrl" -> jstr(Providers.playlistUrl(url))
        "startsAsVideo" -> JsonBool(Providers.startsAsVideo(url, (input.string("youtubeMusic") ?: "audio") == "audio"))
        "preferAudio" -> JsonBool(Providers.prefersAudio(url, (input.string("youtubeMusic") ?: "audio") == "audio"))
        "gallery" -> JsonBool(Providers.isGallery(url))
        "videoId" -> jstr(Providers.videoId(url))
        "safeName" -> jstr(Providers.safeName(input.string("value")!!, (input.number("max") ?: 80.0).toInt()))
        "validateUrl" -> {
            val check = Providers.validateUrl(input.string("value")!!)
            obj("valid" to JsonBool(check.valid), "href" to jstr(check.href))
        }
        "url" -> {
            val parsed = Url.parse(input.string("value")!!)
            if (parsed == null) {
                jstr(null)
            } else {
                obj(
                    "scheme" to jstr(parsed.scheme),
                    "username" to jstr(parsed.username),
                    "password" to jstr(parsed.password),
                    "host" to jstr(parsed.host),
                    "port" to jnum(parsed.port?.toDouble()),
                    "path" to jstr(parsed.path),
                    "query" to jstr(parsed.query?.ifEmpty { null }),
                    "fragment" to jstr(parsed.fragment?.ifEmpty { null }),
                    "href" to jstr(parsed.href),
                )
            }
        }
        "isoDate" -> jstr(Naming.isoDate(input.number("millis")!!.toLong()))
        "channelArtist" -> jstr(Naming.channelArtist(input.string("value")))
        "outputSubfolders" -> arr(Naming.subfolders(input.string("layout")!!, pathJobOf(input)).map { jstr(it) })
        "outputStem" -> jstr(Naming.stem(input.string("naming")!!, pathJobOf(input)))
        "spaceSubfolder" -> jstr(Naming.spaceSubfolder(input.string("layout")!!, spaceContextOf(input)))
        "spaceStem" -> jstr(Naming.spaceStem(input.string("naming")!!, spaceContextOf(input)))
        else -> error("unknown providers op $op")
    }
}
