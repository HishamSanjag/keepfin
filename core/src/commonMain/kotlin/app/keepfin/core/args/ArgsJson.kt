package app.keepfin.core.args

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonBool
import app.keepfin.core.json.JsonNull
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.json.parseJson
import kotlin.math.floor

/**
 * Untyped input (the desktop renderer's IPC payload, a fixture) to a validated [DownloadFormat] or [PlanOptions], with the
 * desktop's rules: a key with the wrong type is an error, null and missing mean "not given", unknown keys are dropped.
 * Failures throw [IllegalArgumentException] with the message the user sees.
 */
object ArgsJson {
    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)

    private fun JsonValue?.field(key: String): JsonValue? = (this as? JsonObject)?.members?.get(key)

    /** A missing or null key is "not given". */
    private fun given(value: JsonValue?): JsonValue? = if (value == null || value === JsonNull) null else value

    private fun text(value: JsonValue?, allowed: List<String>, message: String): String {
        val s = (value as? JsonString)?.value
        if (s != null && s in allowed) return s
        fail(message)
    }

    private fun optionalId(value: JsonValue?): String? {
        val v = given(value) ?: return null
        val s = (v as? JsonString)?.value
        if (s != null && s.isEmpty()) return null
        if (s != null && DownloadPlan.isFormatId(s)) return s
        fail("Choose a format from the inspected list.")
    }

    private fun optionalInt(value: JsonValue?, min: Int, max: Int, message: String): Int? {
        val v = given(value) ?: return null
        val n = (v as? JsonNumber)?.value
        if (n != null && n.isFinite() && n == floor(n) && n >= min && n <= max) return n.toInt()
        fail(message)
    }

    /** The request, validated. The "gallery" kind is not a download plan; callers handle it before this. */
    fun format(json: String): DownloadFormat {
        val input = parseJson(json)
        if (input !is JsonObject) fail("Choose a download format.")
        val type = (input.members["type"] as? JsonString)?.value
        val request: DownloadFormat = when (type) {
            "video" -> DownloadFormat.Video(
                maxHeight = optionalInt(input.members["maxHeight"], 100, 8640, "Choose a supported video quality."),
                formatId = optionalId(input.members["formatId"]),
                audioFormatId = optionalId(input.members["audioFormatId"]),
                fps = optionalInt(input.members["fps"], 1, 240, "Choose a supported frame rate."),
                codec = if (input.members["codec"] == null) "auto" else text(input.members["codec"], listOf("auto", "h264", "vp9", "av1", "hevc"), "Choose a supported video codec."),
                container = if (input.members["container"] == null) "mp4" else text(input.members["container"], listOf("mp4", "mkv", "webm"), "Choose MP4, MKV or WebM."),
                scaleHeight = optionalInt(input.members["scaleHeight"], 100, 8640, "Choose a supported video quality."),
            )
            "audio" -> DownloadFormat.Audio(
                target = text(input.members["target"], listOf("mp3", "m4a", "opus", "flac", "wav", "original"), "Choose a supported audio format."),
                quality = if (input.members["quality"] == null) "best" else text(input.members["quality"], listOf("best", "320", "256", "192", "128"), "Choose a supported audio quality."),
                formatId = optionalId(input.members["formatId"]),
            )
            else -> fail("Choose a download format.")
        }
        return DownloadPlan.validate(request)
    }

    private fun flag(value: JsonValue?): Boolean? = (value as? JsonBool)?.value

    private fun seconds(value: JsonValue?): Double? {
        val v = given(value) ?: return null
        val n = (v as? JsonNumber)?.value
        if (n != null && n.isFinite() && n >= 0 && n <= 604_800.0) return n
        fail("Use a time range within the media duration.")
    }

    /** The options, validated. Null or an empty text is "no options". */
    fun options(json: String?): PlanOptions {
        if (json == null) return PlanOptions()
        val input = parseJson(json)
        if (input === JsonNull) return PlanOptions()
        if (input !is JsonObject) fail("Invalid download options.")
        var subtitles: SubtitleSettings? = null
        val s = input.members["subtitles"]
        if (s != null) {
            if (s !is JsonObject && s !is JsonArray) fail("Invalid subtitle options.")
            val languages = (s.field("languages") as? JsonString)?.value ?: ""
            subtitles = SubtitleSettings(
                enabled = flag(s.field("enabled")) == true, languages = languages,
                auto = flag(s.field("auto")) == true, embed = flag(s.field("embed")) == true,
            )
        }
        var start: Double? = null
        var end: Double? = null
        val section = given(input.members["section"])
        if (section != null) {
            if (section !is JsonObject && section !is JsonArray) fail("Invalid time range.")
            start = seconds(section.field("start"))
            end = seconds(section.field("end"))
        }
        val sponsor = input.members["sponsorBlock"]
        val options = PlanOptions(
            subtitles = subtitles,
            embedThumbnail = flag(input.members["embedThumbnail"]), embedMetadata = flag(input.members["embedMetadata"]),
            embedChapters = flag(input.members["embedChapters"]), writeThumbnail = flag(input.members["writeThumbnail"]),
            writeDescription = flag(input.members["writeDescription"]), writeInfoJson = flag(input.members["writeInfoJson"]),
            splitChapters = flag(input.members["splitChapters"]),
            sponsorBlock = if (sponsor == null) null else text(sponsor, listOf("off", "mark", "remove"), "Choose a SponsorBlock mode."),
            sectionStart = start, sectionEnd = end,
        )
        return DownloadPlan.validate(options)
    }
}
