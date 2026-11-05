package app.keepfin.core

import app.keepfin.core.args.DownloadFormat
import app.keepfin.core.choice.Choice
import app.keepfin.core.choice.ChoiceAudio
import app.keepfin.core.choice.ChoiceMedia
import app.keepfin.core.choice.ChoiceVideo
import app.keepfin.core.choice.DownloadChoice
import app.keepfin.core.choice.Start
import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue

// fixtures/engine/choice: see SCHEMA.md there.

private fun JsonObject.string(key: String): String? = (members[key] as? JsonString)?.value
private fun JsonObject.number(key: String): Double? = (members[key] as? JsonNumber)?.value
private fun JsonObject.flag(key: String): Boolean = (members[key] as? JsonBool)?.value == true
private fun JsonObject.items(key: String): List<JsonObject> = (members[key] as? JsonArray)?.items?.map { it as JsonObject }.orEmpty()

private fun mediaOf(input: JsonObject): ChoiceMedia {
    val media = input.members.getValue("media") as JsonObject
    return ChoiceMedia(
        duration = media.number("duration"),
        video = media.items("video").map {
            val id = it.string("id")!!
            ChoiceVideo(
                id = id, height = it.number("height")?.toInt() ?: 0, fps = it.number("fps")?.toInt(), hdr = it.flag("hdr"),
                codec = it.string("codec") ?: "other", hasAudio = it.flag("hasAudio"), formatId = it.string("formatId") ?: id,
                size = it.number("size")?.toLong(), sizeApprox = it.flag("sizeApprox"),
            )
        },
        audio = media.items("audio").map {
            ChoiceAudio(it.string("formatId")!!, it.string("codec") ?: "other", it.number("size")?.toLong(), it.flag("sizeApprox"))
        },
    )
}

private fun choiceOf(input: JsonObject): Choice {
    val choice = input.members["choice"] as? JsonObject ?: return Choice()
    val base = Choice()
    return Choice(
        mode = choice.string("mode") ?: base.mode, videoId = choice.string("videoId"), container = choice.string("container") ?: base.container,
        target = choice.string("target") ?: base.target, quality = choice.string("quality") ?: base.quality,
        audioTrack = choice.string("audioTrack"), audioSource = choice.string("audioSource"),
    )
}

private fun startOf(input: JsonObject): Start {
    val start = input.members.getValue("start") as JsonObject
    return Start(start.string("preset")!!, start.string("url"), start.flag("music"), (start.string("youtubeMusic") ?: "audio") == "audio")
}

private fun choiceJson(choice: Choice): JsonValue = obj(
    "mode" to jstr(choice.mode), "videoId" to jstr(choice.videoId), "container" to jstr(choice.container), "target" to jstr(choice.target),
    "quality" to jstr(choice.quality), "audioTrack" to jstr(choice.audioTrack), "audioSource" to jstr(choice.audioSource),
)

internal fun choiceHandler(input: JsonObject): JsonValue = when (val op = input.string("op")) {
    "offer" -> obj(
        "containers" to arr(
            DownloadChoice.offer(mediaOf(input), choiceOf(input)).containers.map {
                obj("value" to jstr(it.value), "allowed" to JsonBool(it.allowed), "reason" to jstr(it.reason))
            },
        ),
    )
    "pickVideo" -> choiceJson(DownloadChoice.pickVideo(mediaOf(input), choiceOf(input), input.string("videoId")!!))
    "size" -> DownloadChoice.size(mediaOf(input), choiceOf(input)).let { obj("bytes" to jnum(it.bytes?.toDouble()), "approx" to JsonBool(it.approx)) }
    "opening" -> choiceJson(DownloadChoice.opening(mediaOf(input), startOf(input)))
    "forList" -> when (val format = DownloadChoice.forList(input.string("preset")!!)) {
        is DownloadFormat.Audio -> obj("type" to jstr("audio"), "target" to jstr(format.target), "quality" to jstr(format.quality))
        is DownloadFormat.Video -> obj("type" to jstr("video"), "maxHeight" to jnum(format.maxHeight?.toDouble()), "container" to jstr(format.container), "codec" to jstr(format.codec))
    }
    "applyPreset" -> choiceJson(DownloadChoice.applyPreset(mediaOf(input), choiceOf(input), input.string("preset")!!))
    "pickTarget" -> choiceJson(DownloadChoice.pickTarget(choiceOf(input), input.string("target")!!))
    "modes" -> arr(DownloadChoice.modes(mediaOf(input)).map { jstr(it) })
    "request" -> when (val format = DownloadChoice.request(mediaOf(input), choiceOf(input))) {
        is DownloadFormat.Audio -> obj("type" to jstr("audio"), "target" to jstr(format.target), "quality" to jstr(format.quality), "formatId" to jstr(format.formatId))
        is DownloadFormat.Video -> obj(
            "type" to jstr("video"), "maxHeight" to jnum(format.maxHeight?.toDouble()), "formatId" to jstr(format.formatId), "audioFormatId" to jstr(format.audioFormatId),
            "fps" to jnum(format.fps?.toDouble()), "codec" to jstr(format.codec), "container" to jstr(format.container),
        )
    }
    "videoLabel" -> jstr(DownloadChoice.videoLabel(input.number("height")!!.toInt(), input.number("fps")?.toInt()))
    else -> error("unknown choice op $op")
}
