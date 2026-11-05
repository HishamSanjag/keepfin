package app.keepfin

import app.keepfin.core.formats.Title
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/*
 * Compiled into BOTH the JVM unit tests (src/test) and the device tests (src/androidTest), like MusicFixtureRunner.
 */

/**
 * Runs every case of fixtures/engine/formats that applies to Android through the app's [Formats] (which delegates to the shared
 * core, app.keepfin.core.formats) exactly as fixtures/engine/formats/SCHEMA.md says. Cases limited to the desktop are skipped.
 */
object FormatsFixtureRunner {
    class Result(val ran: Int, val skipped: Int, val failures: List<String>)

    private const val DIR = "engine/formats"

    fun run(files: FixtureFiles): Result {
        val failures = ArrayList<String>()
        var ran = 0
        var skipped = 0
        for (file in files.list(DIR).filter { it.endsWith(".json") }.sorted()) {
            val label = "formats/${file.removeSuffix(".json")}"
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
                    assertSubset(execute(files, case.getJSONObject("input")), case.opt("expected"), name)
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
    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().also { o -> pairs.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) } }
    private fun arr(items: Iterable<Any?>) = JSONArray().also { a -> items.forEach { a.put(it ?: JSONObject.NULL) } }

    private fun infoOf(files: FixtureFiles, input: JSONObject): JSONObject =
        input.optJSONObject("info") ?: JSONObject(files.read("$DIR/samples/${input.getString("infoFile")}"))

    private fun execute(files: FixtureFiles, input: JSONObject): Any? = when (val op = input.getString("op")) {
        "media" -> {
            val media = Formats.mediaInfo(input.getString("url"), infoOf(files, input))
            val video = media.video.map { v ->
                obj("id" to v.id, "height" to v.height, "fps" to v.fps, "hdr" to v.hdr, "codec" to v.codec, "formatId" to v.formatId,
                    "hasAudio" to v.hasAudio, "size" to v.size, "sizeApprox" to v.sizeApprox)
            }
            val audio = media.audio.map { a -> obj("formatId" to a.formatId, "codec" to a.codec, "language" to a.language, "size" to a.size, "sizeApprox" to a.sizeApprox) }
            val pickVideo = input.optJSONArray("pickVideo")?.strings()
            val pickAudio = input.optJSONArray("pickAudio")?.strings()
            obj(
                "provider" to media.provider, "title" to media.title, "uploader" to media.uploader, "duration" to media.duration, "isLive" to media.isLive,
                "chapters" to media.chapters, "playlistUrl" to media.playlistUrl, "thumbnail" to media.thumbnail,
                "music" to media.music?.let { m -> obj("artist" to m.artist, "album" to m.album, "track" to m.track, "releaseYear" to m.releaseYear) },
                "videoIds" to arr(media.video.map { it.id }),
                "video" to arr(pickVideo?.map { id -> video.firstOrNull { it.getString("id") == id } } ?: video),
                "audio" to arr(pickAudio?.map { id -> audio.firstOrNull { it.getString("formatId") == id } } ?: audio),
                "hasVideoOptions" to media.video.isNotEmpty(), "hasAudioOptions" to media.audio.isNotEmpty(),
            )
        }
        "size" -> Formats.size(input.getJSONObject("format"), input.dbl("duration")).let { obj("size" to it.bytes, "approx" to it.approx) }
        "codec" -> if (input.getString("kind") == "video") Formats.videoCodec(input.str("value")) else Formats.audioCodec(input.str("value"))
        "title" -> Title.clean(input.str("title"), input.str("uploader"), input.getString("provider"), input.str("kind") ?: "video")
        "subtitles" -> arr(Formats.subtitles(infoOf(files, input)).map { obj("lang" to it.lang, "auto" to it.auto) })
        "pageUrl" -> Formats.mediaInfo(input.getString("url"), infoOf(files, input)).webpageUrl
        "heightLabel" -> app.keepfin.core.formats.Formats.heightLabel(input.getInt("height"))
        else -> error("unknown formats op $op")
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
