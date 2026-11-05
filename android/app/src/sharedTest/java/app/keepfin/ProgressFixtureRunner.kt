package app.keepfin

import app.keepfin.core.progress.ProgressParser
import app.keepfin.core.progress.ProgressPlatform
import org.json.JSONObject
import kotlin.math.abs

/*
 * Compiled into BOTH the JVM unit tests (src/test) and the device tests (src/androidTest), like MusicFixtureRunner.
 * Runs every case of fixtures/engine/progress that is marked for Android through the shared core's progress parser
 * (app.keepfin.core.progress), exactly as fixtures/engine/progress/SCHEMA.md says.
 */
object ProgressFixtureRunner {
    class Result(val ran: Int, val skipped: Int, val failures: List<String>)

    private const val DIR = "engine/progress"

    fun run(files: FixtureFiles): Result {
        val failures = ArrayList<String>()
        var ran = 0
        var skipped = 0
        for (file in files.list(DIR).filter { it.endsWith(".json") }.sorted()) {
            val label = "progress/${file.removeSuffix(".json")}"
            val cases = JSONObject(files.read("$DIR/$file")).getJSONArray("cases")
            val seen = HashSet<String>()
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val name = case.getString("name")
                require(seen.add(name)) { "duplicate case name \"$name\" in $label" }
                val platforms = case.optJSONArray("platforms")
                if (platforms != null && (0 until platforms.length()).none { platforms.optString(it) == "android" }) { skipped++; continue }
                ran++
                try { runCase(case) } catch (e: Throwable) { failures.add("$label: $name\n      ${e.message ?: e::class.java.simpleName}") }
            }
        }
        return Result(ran, skipped, failures)
    }

    private fun isNull(obj: JSONObject, key: String) = !obj.has(key) || obj.isNull(key)

    /** Android's engine template: `[keepfin] status|downloaded|total|estimate|speed|eta|formatId`; a missing number is `NA`. */
    private fun render(step: JSONObject): String {
        fun number(key: String): String = if (isNull(step, key)) "NA" else step.getDouble(key).toLong().toString()
        return "[keepfin] ${step.getString("status")}|${number("downloaded")}|${number("total")}|${number("estimate")}|${number("speed")}|${number("eta")}|${step.getString("formatId")}"
    }

    private fun runCase(case: JSONObject) {
        val input = case.getJSONObject("input")
        // Android knows the job's inspected size as one number: the sum of its streams.
        val sizes = input.optJSONObject("streamSizes")
        val expectedSize = sizes?.let { s -> s.keys().asSequence().sumOf { s.getDouble(it) } }
        val parser = ProgressParser(ProgressPlatform.ANDROID, expectedSize = expectedSize)
        val steps = input.getJSONArray("steps")
        val snapshots = ArrayList<JSONObject>()
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            parser.accept(if (step.getString("type") == "line") step.getString("text") else render(step))
            val s = parser.snapshot
            snapshots.add(JSONObject().apply {
                put("percent", s.percent)
                put("downloadedBytes", s.downloadedBytes ?: JSONObject.NULL)
                put("totalBytes", s.totalBytes ?: JSONObject.NULL)
                put("speed", s.speed ?: JSONObject.NULL)
                put("eta", s.eta ?: JSONObject.NULL)
                put("phase", s.phase ?: JSONObject.NULL)
                put("processing", s.processing)
            })
        }
        val expected = case.getJSONArray("expected")
        for (i in 0 until expected.length()) {
            val want = expected.getJSONObject(i)
            val after = want.getInt("after")
            val got = snapshots.getOrNull(after) ?: throw AssertionError("no step $after")
            val keys = want.keys().asSequence().filter { it != "after" }.toList()
            for (key in keys) {
                val value = want.get(key)
                val actual = got.opt(key)
                val where = "step $after $key"
                when {
                    value == JSONObject.NULL -> if (actual != null && actual != JSONObject.NULL) throw AssertionError("$where: expected null, got $actual")
                    value is Number && key in setOf("percent", "speed", "eta") ->
                        if (actual !is Number || abs(actual.toDouble() - value.toDouble()) > 1) throw AssertionError("$where: $actual vs $value")
                    value is Number -> if (actual !is Number || abs(actual.toDouble() - value.toDouble()) > 1e-9) throw AssertionError("$where: expected $value, got $actual")
                    else -> if (actual != value) throw AssertionError("$where: expected $value, got $actual")
                }
            }
        }
    }
}
