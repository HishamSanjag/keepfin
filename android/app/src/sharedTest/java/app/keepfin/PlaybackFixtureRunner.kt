package app.keepfin

import app.keepfin.core.playback.Playback
import app.keepfin.core.playback.SkipRun
import org.json.JSONArray
import org.json.JSONObject

/*
 * Compiled into BOTH the JVM unit tests (src/test) and the device tests (src/androidTest), like VersionsFixtureRunner.
 * Runs every case of fixtures/engine/playback through the shared core (app.keepfin.core.playback.Playback), the code the
 * phone's video player calls through ui/player/VideoLogic.kt, exactly as fixtures/engine/playback/SCHEMA.md says, and
 * compares per fixtures/engine/README.md (subset). The phone's millisecond wrappers are covered by VideoLogicTest.
 */
object PlaybackFixtureRunner {
    private const val DIR = "engine/playback"

    fun run(files: FixtureFiles): MusicFixtureRunner.Result {
        val failures = ArrayList<String>()
        var ran = 0
        var skipped = 0
        for (file in files.list(DIR).filter { it.endsWith(".json") }.sorted()) {
            val label = "playback/${file.removeSuffix(".json")}"
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
    private fun JSONObject.numbers(key: String): List<Double> = getJSONArray(key).let { list -> (0 until list.length()).map { list.getDouble(it) } }
    private fun JSONObject.strings(key: String): List<String>? = if (has(key) && !isNull(key)) getJSONArray(key).let { list -> (0 until list.length()).map { list.getString(it) } } else null

    private fun execute(input: JSONObject): Any? = when (val op = input.getString("op")) {
        "skips" -> {
            val steps = input.getJSONArray("steps")
            var run: SkipRun? = null
            JSONArray((0 until steps.length()).map { index ->
                val step = steps.getJSONObject(index)
                run = Playback.accumulateSkip(run, step.getDouble("delta"), step.getDouble("now"))
                Playback.skipLabel(run!!.total)
            })
        }
        "announce" -> Playback.skipAnnouncement(input.getDouble("total"))
        "resume" -> Playback.resumeFrom(input.num("position"), input.num("duration"), input.getBoolean("watched"))
        "chapterAt" -> Playback.chapterAt(input.numbers("starts"), input.getDouble("time"))
        "chapterTarget" -> Playback.chapterTarget(input.numbers("starts"), input.getDouble("time"), input.getInt("dir"))
        "crossed" -> Playback.crossedChapter(input.numbers("starts"), input.getDouble("from"), input.getDouble("to"))
        "list" -> JSONArray(Playback.playbackList(input.strings("context")!!, input.strings("folder")))
        "neighbour" -> Playback.neighbour(input.strings("list")!!, input.getString("id"), input.getInt("dir"))
        "folderOrder" -> {
            val items = input.getJSONArray("items")
            val all = (0 until items.length()).map { items.getJSONObject(it) }
            JSONArray(Playback.folderOrder(all.map { it.getString("id") }, all.map { it.getString("title") }))
        }
        "clock" -> Playback.clock(input.getDouble("seconds"))
        "timeLeft" -> Playback.timeLeft(input.getDouble("seconds"))
        "speeds" -> JSONArray(Playback.SPEEDS)
        else -> throw AssertionError("unknown playback op $op")
    }
}
