package app.keepfin.core

import app.keepfin.core.args.ArgsJson
import app.keepfin.core.args.DownloadFormat
import app.keepfin.core.args.DownloadPlan
import app.keepfin.core.args.LabelAudio
import app.keepfin.core.args.LabelVideo
import app.keepfin.core.args.Labels
import app.keepfin.core.args.PlanPlatform
import app.keepfin.core.args.SourceTags
import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNull
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue

// fixtures/engine/args: see SCHEMA.md there. The plan is read back from the argument list the core builds, so the cases
// check the arguments themselves.

private fun JsonObject.string(key: String): String? = (members[key] as? JsonString)?.value

private fun JsonObject.number(key: String): Double? = (members[key] as? JsonNumber)?.value

private fun JsonObject.flag(key: String): Boolean = (members[key] as? JsonBool)?.value == true

private fun jbool(value: Boolean): JsonValue = JsonBool(value)

private val valuedFlags = setOf(
    "-f", "-S", "--merge-output-format", "--remux-video", "--audio-format", "--audio-quality", "--sub-langs", "--convert-thumbnails",
    "--convert-subs", "--sponsorblock-mark", "--sponsorblock-remove", "--download-sections", "--ppa", "-o",
)

/** The semantic plan of an argument list (the reading in SCHEMA.md). */
private fun planOf(args: List<String>): JsonValue {
    val values = LinkedHashMap<String, String>()
    val flags = HashSet<String>()
    var i = 0
    while (i < args.size) {
        if (args[i] in valuedFlags) {
            values[args[i]] = args[i + 1]
            i += 2
        } else {
            flags.add(args[i])
            i++
        }
    }
    val audioFormat = values["--audio-format"]
    val target = if (audioFormat == null || audioFormat == "best") "original" else audioFormat
    val section = values["--download-sections"]?.removePrefix("*")?.split("-")
    return obj(
        "selector" to jstr(values["-f"]),
        "sort" to jstr(values["-S"]),
        "merge" to jstr(values["--merge-output-format"]),
        "remux" to jstr(values["--remux-video"]),
        "audio" to if ("-x" in flags) {
            obj("format" to jstr(target), "quality" to jstr(if (target in setOf("mp3", "m4a", "opus")) values["--audio-quality"] else null))
        } else JsonNull,
        "subtitles" to if ("--write-subs" in flags) {
            obj("langs" to jstr(values["--sub-langs"]), "auto" to jbool("--write-auto-subs" in flags), "embed" to jbool("--embed-subs" in flags))
        } else JsonNull,
        "embedThumbnail" to jbool("--embed-thumbnail" in flags),
        "squareCover" to jbool("--ppa" in values),
        "writeThumbnail" to jbool("--write-thumbnail" in flags),
        "embedMetadata" to jbool("--embed-metadata" in flags),
        "embedChapters" to jbool("--embed-chapters" in flags),
        "sponsorBlock" to jstr(if ("--sponsorblock-remove" in values) "remove" else if ("--sponsorblock-mark" in values) "mark" else null),
        "section" to if (section != null) {
            obj("start" to jnum(section[0].toDouble()), "end" to if (section[1] == "inf") JsonNull else jnum(section[1].toDouble()))
        } else JsonNull,
    )
}

private fun validity(block: () -> Unit): JsonValue = obj("valid" to jbool(runCatching { block() }.isSuccess))

private fun videos(value: JsonValue?): List<LabelVideo> = (value as? JsonArray)?.items.orEmpty().map {
    val o = it as JsonObject
    LabelVideo(o.string("formatId")!!, o.number("height")!!.toInt(), o.number("fps")?.toInt(), o.flag("hdr"), o.number("size")?.toLong(), o.flag("sizeApprox"), o.flag("hasAudio"))
}

private fun audios(value: JsonValue?): List<LabelAudio> = (value as? JsonArray)?.items.orEmpty().map {
    val o = it as JsonObject
    LabelAudio(o.string("formatId")!!, o.number("size")?.toLong(), o.flag("sizeApprox"), o.string("codec") ?: "other")
}

internal fun argsHandler(input: JsonObject, platform: PlanPlatform): JsonValue {
    fun format() = ArgsJson.format(show(input.members.getValue("format")))
    fun options() = ArgsJson.options(input.members["options"]?.let { show(it) })
    return when (val op = input.string("op")) {
        "plan", "args" -> {
            val plan = DownloadPlan.plan(format(), options(), platform, input.flag("squareCover"), input.string("chapterTemplate"))
            val args = DownloadPlan.args(plan)
            if (op == "plan") planOf(args) else arr(args.map { jstr(it) })
        }
        "validateFormat" -> validity { format() }
        "validateOptions" -> validity { options() }
        "preset" -> when (val r = DownloadPlan.preset(input.string("preset")!!, input.string("container") ?: "mp4")) {
            is DownloadFormat.Video -> obj("type" to jstr("video"), "maxHeight" to jnum(r.maxHeight?.toDouble()), "codec" to jstr(r.codec), "container" to jstr(r.container))
            is DownloadFormat.Audio -> obj("type" to jstr("audio"), "target" to jstr(r.target), "quality" to jstr(r.quality))
        }
        "presetValid" -> validity { DownloadPlan.preset(input.string("preset")!!) }
        "metadata" -> arr(DownloadPlan.metadataArgs(input.string("title")).map { jstr(it) })
        "tags" -> arr(
            DownloadPlan.tagArgs(
                SourceTags(input.string("title") ?: "", input.string("artist"), input.string("album"), input.number("track")?.toInt(), input.number("year")?.toInt()),
            ).map { jstr(it) },
        )
        "quote" -> {
            val withSize = (input.members["withSize"] as? JsonBool)?.value ?: true
            val q = Labels.quote(format(), videos(input.members["video"]), audios(input.members["audio"]), input.number("duration"), withSize)
            obj("label" to jstr(q.label), "expectedSize" to jnum(q.expectedSize?.toDouble()), "approx" to jbool(q.approx))
        }
        "bytes" -> jstr(Labels.formatBytes(input.number("bytes")!!))
        else -> error("unknown args op $op")
    }
}

/** The desktop always has the optional cover-art module. */
internal val desktopPlanPlatform = PlanPlatform(coverArtForOpusFlac = true)

/** Phones do not (ARGS-9). */
internal val androidPlanPlatform = PlanPlatform(coverArtForOpusFlac = false)
