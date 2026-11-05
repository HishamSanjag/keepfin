package app.keepfin

import app.keepfin.core.args.DownloadFormat
import app.keepfin.core.choice.Choice
import app.keepfin.core.choice.ChoiceAudio
import app.keepfin.core.choice.ChoiceMedia
import app.keepfin.core.choice.ChoiceVideo
import app.keepfin.core.choice.DownloadChoice
import app.keepfin.core.choice.Start
import org.json.JSONArray
import org.json.JSONObject

/*
 * Compiled into BOTH the JVM unit tests (src/test) and the device tests (src/androidTest), like PlaybackFixtureRunner.
 * Runs every case of fixtures/engine/choice through the shared core (app.keepfin.core.choice.DownloadChoice), the code the
 * phone's preview calls through ui/Formatting.kt, exactly as fixtures/engine/choice/SCHEMA.md says, and compares per
 * fixtures/engine/README.md (subset).
 */
object ChoiceFixtureRunner {
    private const val DIR = "engine/choice"

    fun run(files: FixtureFiles): MusicFixtureRunner.Result {
        val failures = ArrayList<String>()
        var ran = 0
        var skipped = 0
        for (file in files.list(DIR).filter { it.endsWith(".json") }.sorted()) {
            val label = "choice/${file.removeSuffix(".json")}"
            val cases = JSONObject(files.read("$DIR/$file")).getJSONArray("cases")
            val seen = HashSet<String>()
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val name = case.getString("name")
                require(seen.add(name)) { "duplicate case name \"$name\" in $label" }
                val platforms = case.optJSONArray("platforms")
                if (platforms != null && (0 until platforms.length()).none { platforms.optString(it) == "android" }) { skipped++; continue }
                ran++
                try {
                    MusicFixtureRunner.assertSubset(execute(case.getJSONObject("input")), case.opt("expected"), name)
                } catch (e: Throwable) {
                    failures.add("$label: $name\n      ${e.message ?: e::class.java.simpleName}")
                }
            }
        }
        return MusicFixtureRunner.Result(ran, skipped, 0, failures)
    }

    private fun JSONObject.num(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
    private fun JSONObject.text(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    private fun JSONObject.items(key: String): List<JSONObject> = optJSONArray(key)?.let { list -> (0 until list.length()).map { list.getJSONObject(it) } }.orEmpty()

    private fun media(input: JSONObject): ChoiceMedia {
        val media = input.getJSONObject("media")
        return ChoiceMedia(
            duration = media.num("duration"),
            video = media.items("video").map {
                val id = it.getString("id")
                ChoiceVideo(
                    id = id, height = it.optInt("height", 0), fps = it.num("fps")?.toInt(), hdr = it.optBoolean("hdr"), codec = it.optString("codec", "other"),
                    hasAudio = it.optBoolean("hasAudio"), formatId = it.optString("formatId", id), size = it.num("size")?.toLong(), sizeApprox = it.optBoolean("sizeApprox"),
                )
            },
            audio = media.items("audio").map { ChoiceAudio(it.getString("formatId"), it.optString("codec", "other"), it.num("size")?.toLong(), it.optBoolean("sizeApprox")) },
        )
    }

    private fun choice(input: JSONObject): Choice {
        val c = input.optJSONObject("choice") ?: return Choice()
        val base = Choice()
        return Choice(
            mode = c.optString("mode", base.mode), videoId = c.text("videoId"), container = c.optString("container", base.container),
            target = c.optString("target", base.target), quality = c.optString("quality", base.quality), audioTrack = c.text("audioTrack"), audioSource = c.text("audioSource"),
        )
    }

    private fun start(input: JSONObject): Start {
        val start = input.getJSONObject("start")
        return Start(start.getString("preset"), start.text("url"), start.optBoolean("music"), start.optString("youtubeMusic", "audio") == "audio")
    }

    private fun choiceJson(choice: Choice) = JSONObject()
        .put("mode", choice.mode).put("videoId", choice.videoId ?: JSONObject.NULL).put("container", choice.container).put("target", choice.target)
        .put("quality", choice.quality).put("audioTrack", choice.audioTrack ?: JSONObject.NULL).put("audioSource", choice.audioSource ?: JSONObject.NULL)

    private fun execute(input: JSONObject): Any? = when (val op = input.getString("op")) {
        "offer" -> JSONObject().put(
            "containers",
            JSONArray(DownloadChoice.offer(media(input), choice(input)).containers.map {
                JSONObject().put("value", it.value).put("allowed", it.allowed).put("reason", it.reason ?: JSONObject.NULL)
            }),
        )
        "pickVideo" -> choiceJson(DownloadChoice.pickVideo(media(input), choice(input), input.getString("videoId")))
        "size" -> DownloadChoice.size(media(input), choice(input)).let { JSONObject().put("bytes", it.bytes ?: JSONObject.NULL).put("approx", it.approx) }
        "opening" -> choiceJson(DownloadChoice.opening(media(input), start(input)))
        "forList" -> when (val format = DownloadChoice.forList(input.getString("preset"))) {
            is DownloadFormat.Audio -> JSONObject().put("type", "audio").put("target", format.target).put("quality", format.quality)
            is DownloadFormat.Video -> JSONObject().put("type", "video").put("maxHeight", format.maxHeight ?: JSONObject.NULL).put("container", format.container).put("codec", format.codec)
        }
        "applyPreset" -> choiceJson(DownloadChoice.applyPreset(media(input), choice(input), input.getString("preset")))
        "pickTarget" -> choiceJson(DownloadChoice.pickTarget(choice(input), input.getString("target")))
        "modes" -> JSONArray(DownloadChoice.modes(media(input)))
        "request" -> when (val format = DownloadChoice.request(media(input), choice(input))) {
            is DownloadFormat.Audio -> JSONObject().put("type", "audio").put("target", format.target).put("quality", format.quality).put("formatId", format.formatId ?: JSONObject.NULL)
            is DownloadFormat.Video -> JSONObject().put("type", "video").put("maxHeight", format.maxHeight ?: JSONObject.NULL).put("formatId", format.formatId ?: JSONObject.NULL)
                .put("audioFormatId", format.audioFormatId ?: JSONObject.NULL).put("fps", format.fps ?: JSONObject.NULL).put("codec", format.codec).put("container", format.container)
        }
        "videoLabel" -> DownloadChoice.videoLabel(input.getInt("height"), input.num("fps")?.toInt())
        else -> throw AssertionError("unknown choice op $op")
    }
}
