package app.keepfin

import app.keepfin.core.args.DownloadFormat
import app.keepfin.core.args.LabelAudio
import app.keepfin.core.args.LabelVideo
import app.keepfin.core.args.Labels
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/*
 * Compiled into BOTH the JVM unit tests (src/test) and the device tests (src/androidTest), like FormatsFixtureRunner.
 */

/**
 * Runs every case of fixtures/engine/args that applies to Android through the app's [ArgsPolicy] (which delegates to the shared
 * core, app.keepfin.core.args) exactly as fixtures/engine/args/SCHEMA.md says. Cases limited to the desktop are skipped.
 */
object ArgsFixtureRunner {
    class Result(val ran: Int, val skipped: Int, val failures: List<String>)

    private const val DIR = "engine/args"

    fun run(files: FixtureFiles): Result {
        val failures = ArrayList<String>()
        var ran = 0
        var skipped = 0
        for (file in files.list(DIR).filter { it.endsWith(".json") }.sorted()) {
            val label = "args/${file.removeSuffix(".json")}"
            val cases = JSONObject(files.read("$DIR/$file")).getJSONArray("cases")
            val seen = HashSet<String>()
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val name = case.getString("name")
                require(seen.add(name)) { "duplicate case name \"$name\" in $label" }
                val platforms = case.optJSONArray("platforms")
                if (platforms != null && (0 until platforms.length()).none { platforms.getString(it) == "android" }) {
                    skipped++
                    continue
                }
                ran++
                val failure = try {
                    assertSubset(execute(case.getJSONObject("input")), case.opt("expected"), name)
                    null
                } catch (e: Throwable) {
                    e.message ?: e::class.java.simpleName
                }
                if (failure != null) failures.add("$label: $name\n      $failure")
            }
        }
        return Result(ran, skipped, failures)
    }

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    private fun JSONObject.dbl(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
    private fun JSONObject.int(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
    private fun JSONObject.bool(key: String): Boolean? = if (has(key) && !isNull(key)) getBoolean(key) else null
    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().also { o -> pairs.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) } }
    private fun arr(items: Iterable<Any?>) = JSONArray().also { a -> items.forEach { a.put(it ?: JSONObject.NULL) } }

    private val valuedFlags = setOf(
        "-f", "-S", "--merge-output-format", "--remux-video", "--audio-format", "--audio-quality", "--sub-langs", "--convert-thumbnails",
        "--convert-subs", "--sponsorblock-mark", "--sponsorblock-remove", "--download-sections", "--ppa", "-o",
    )

    private fun request(format: JSONObject): FormatRequest = when (format.getString("type")) {
        "video" -> FormatRequest.Video(
            format.int("maxHeight"), format.str("formatId"), format.str("audioFormatId"), format.int("fps"),
            format.str("codec") ?: "auto", format.str("container") ?: "mp4",
        )
        "audio" -> FormatRequest.Audio(format.getString("target"), format.str("quality") ?: "best", format.str("formatId"))
        else -> error("unknown format type")
    }

    private fun downloadOptions(o: JSONObject?): DownloadOptions {
        val s = o?.optJSONObject("subtitles")
        val section = o?.optJSONObject("section")
        return DownloadOptions(
            subtitles = if (s != null) SubtitleOptions(s.bool("enabled") == true, s.str("languages") ?: "en.*", s.bool("auto") == true, s.bool("embed") == true)
            else SubtitleOptions(false, "en.*", false, false),
            embedThumbnail = o?.bool("embedThumbnail") == true, embedMetadata = o?.bool("embedMetadata") == true,
            embedChapters = o?.bool("embedChapters") == true, writeThumbnail = o?.bool("writeThumbnail") == true,
            sponsorBlock = o?.str("sponsorBlock") ?: "off", sectionStart = section?.dbl("start"), sectionEnd = section?.dbl("end"),
        )
    }

    /** The semantic plan of the arguments (the reading in SCHEMA.md). */
    private fun planOf(tokens: List<Arg>): JSONObject {
        val values = HashMap<String, String?>()
        val flags = HashSet<String>()
        for (t in tokens) {
            if (t.name in valuedFlags) values[t.name] = t.value else flags.add(t.name)
        }
        val section = values["--download-sections"]?.removePrefix("*")?.split("-")
        val audioFormat = values["--audio-format"]
        val target = if (audioFormat == null || audioFormat == "best") "original" else audioFormat
        return obj(
            "selector" to values["-f"], "sort" to values["-S"], "merge" to values["--merge-output-format"], "remux" to values["--remux-video"],
            "audio" to if ("-x" in flags) obj("format" to target, "quality" to if (target in setOf("mp3", "m4a", "opus")) values["--audio-quality"] else null) else null,
            "subtitles" to if ("--write-subs" in flags) obj("langs" to values["--sub-langs"], "auto" to ("--write-auto-subs" in flags), "embed" to ("--embed-subs" in flags)) else null,
            "embedThumbnail" to ("--embed-thumbnail" in flags), "squareCover" to ("--ppa" in values), "writeThumbnail" to ("--write-thumbnail" in flags),
            "embedMetadata" to ("--embed-metadata" in flags), "embedChapters" to ("--embed-chapters" in flags),
            "sponsorBlock" to when { "--sponsorblock-remove" in values -> "remove"; "--sponsorblock-mark" in values -> "mark"; else -> null },
            "section" to section?.let { obj("start" to it[0].toDouble(), "end" to it[1].takeIf { e -> e != "inf" }?.toDouble()) },
        )
    }

    private fun flat(args: List<Arg>): JSONArray = arr(args.flatMap { listOfNotNull(it.name, it.value) })

    private fun videos(a: JSONArray?): List<LabelVideo> = (0 until (a?.length() ?: 0)).map {
        val o = a!!.getJSONObject(it)
        LabelVideo(o.getString("formatId"), o.getInt("height"), o.int("fps"), o.bool("hdr") == true, if (o.isNull("size")) null else o.getLong("size"), o.bool("sizeApprox") == true, o.bool("hasAudio") == true)
    }

    private fun audios(a: JSONArray?): List<LabelAudio> = (0 until (a?.length() ?: 0)).map {
        val o = a!!.getJSONObject(it)
        LabelAudio(o.getString("formatId"), if (o.isNull("size")) null else o.getLong("size"), o.bool("sizeApprox") == true, o.optString("codec", "other"))
    }

    private fun toCore(r: FormatRequest): DownloadFormat = when (r) {
        is FormatRequest.Video -> DownloadFormat.Video(r.maxHeight, r.formatId, r.audioFormatId, r.fps, r.codec, r.container)
        is FormatRequest.Audio -> DownloadFormat.Audio(r.target, r.quality, r.formatId)
        is FormatRequest.Gallery -> error("a photo request has no download plan")
    }

    private fun valid(block: () -> Unit) = obj("valid" to runCatching { block() }.isSuccess)

    private fun execute(input: JSONObject): Any? = when (val op = input.getString("op")) {
        // Android's engine has no optional cover-art module here (mutagen = false), like the "android" plan platform.
        "plan" -> planOf(ArgsPolicy.download(request(input.getJSONObject("format")), downloadOptions(input.optJSONObject("options")), input.optBoolean("squareCover", false), mutagen = false))
        "args" -> flat(ArgsPolicy.download(request(input.getJSONObject("format")), downloadOptions(input.optJSONObject("options")), input.optBoolean("squareCover", false), mutagen = false))
        "validateFormat" -> valid { ArgsPolicy.validate(request(input.getJSONObject("format"))) }
        "validateOptions" -> valid { ArgsPolicy.validate(downloadOptions(input.optJSONObject("options"))) }
        "preset" -> when (val r = ArgsPolicy.preset(DownloadPreset.of(input.getString("preset")), input.str("container") ?: "mp4")) {
            is FormatRequest.Video -> obj("type" to "video", "maxHeight" to r.maxHeight, "codec" to r.codec, "container" to r.container)
            is FormatRequest.Audio -> obj("type" to "audio", "target" to r.target, "quality" to r.quality)
            is FormatRequest.Gallery -> error("a preset is never a photo request")
        }
        "presetValid" -> valid { require(DownloadPreset.entries.any { it.wire == input.getString("preset") }) { "unknown preset" } }
        "metadata" -> arr(ArgsPolicy.metadata(input.str("title")).flatMap { listOfNotNull(it.name, it.value) })
        "tags" -> flat(ArgsPolicy.tags(SourceTags(input.getString("title"), input.str("artist"), input.str("album"), input.int("track"), input.int("year"))))
        "quote" -> Labels.quote(
            toCore(request(input.getJSONObject("format"))), videos(input.optJSONArray("video")), audios(input.optJSONArray("audio")),
            input.dbl("duration"), input.optBoolean("withSize", true),
        ).let { obj("label" to it.label, "expectedSize" to it.expectedSize, "approx" to it.approx) }
        "bytes" -> Labels.formatBytes(input.getDouble("bytes"))
        else -> error("unknown args op $op")
    }

    private fun isNull(value: Any?) = value == null || value == JSONObject.NULL

    /** `expected` is a subset of `actual` (fixtures/engine/README.md); a `size` may differ by one byte. */
    private fun assertSubset(actual: Any?, expected: Any?, where: String) {
        when {
            isNull(expected) -> if (!isNull(actual)) throw AssertionError("$where: expected null, got $actual")
            expected is JSONArray -> {
                if (actual !is JSONArray) throw AssertionError("$where: expected an array, got $actual")
                if (actual.length() != expected.length()) throw AssertionError("$where: array length ${actual.length()} vs ${expected.length()} (actual $actual)")
                for (i in 0 until expected.length()) assertSubset(actual.opt(i), expected.opt(i), "$where[$i]")
            }
            expected is JSONObject -> {
                if (actual !is JSONObject) throw AssertionError("$where: expected an object, got $actual")
                val keys = expected.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val want = expected.get(key)
                    val got = actual.opt(key)
                    if (key == "size" && want is Number && got is Number) {
                        if (abs(got.toDouble() - want.toDouble()) > 1) throw AssertionError("$where.size: $got vs $want")
                    } else assertSubset(got, want, "$where.$key")
                }
            }
            expected is Number -> if (actual !is Number || abs(actual.toDouble() - expected.toDouble()) > 1e-9) throw AssertionError("$where: expected $expected, got $actual")
            else -> if (actual != expected) throw AssertionError("$where: expected $expected, got $actual")
        }
    }
}
