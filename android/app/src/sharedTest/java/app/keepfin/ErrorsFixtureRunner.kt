package app.keepfin

import app.keepfin.core.errors.Errors
import app.keepfin.core.errors.Platform
import org.json.JSONObject

/*
 * Compiled into BOTH the JVM unit tests (src/test) and the device tests (src/androidTest), like MusicFixtureRunner.
 * Runs every case of fixtures/engine/errors through the shared core (app.keepfin.core.errors) as Android.
 */
object ErrorsFixtureRunner {
    class Result(val ran: Int, val skipped: Int, val failures: List<String>)

    private const val DIR = "engine/errors"
    private val toolNames = Regex("yt-?dlp|yt_dlp|youtube-?dl|ffmpeg|ffprobe|gallery-dl|aria2", RegexOption.IGNORE_CASE)

    fun run(files: FixtureFiles): Result {
        val failures = ArrayList<String>()
        var ran = 0
        var skipped = 0
        for (file in files.list(DIR).filter { it.endsWith(".json") }.sorted()) {
            val label = "errors/${file.removeSuffix(".json")}"
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

    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().also { o -> pairs.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) } }

    private fun runCase(case: JSONObject) {
        val name = case.getString("name")
        val input = case.getJSONObject("input")
        val text = input.getString("text")
        val actual: Any = when (val op = input.getString("op")) {
            "scrub" -> {
                val noToolNames = !toolNames.containsMatchIn(ErrorMessages.scrub(text)) && !toolNames.containsMatchIn(ErrorMessages.detail(text))
                if (!noToolNames) throw AssertionError("$name: a user-facing message names an external tool")
                obj("noToolNames" to noToolNames)
            }
            "classify" -> {
                val analysis = Errors.analyze(text, Platform.ANDROID)
                val noToolNames = !toolNames.containsMatchIn(analysis.message) && !toolNames.containsMatchIn(ErrorMessages.detail(text)) &&
                    !toolNames.containsMatchIn(analysis.detail.orEmpty())
                if (!noToolNames) throw AssertionError("$name: a user-facing message names an external tool: ${analysis.message}")
                obj("class" to analysis.errorClass.wire, "retry" to analysis.retry.wire, "noToolNames" to noToolNames)
            }
            "retry" -> {
                val decision = RetryPolicy.next(input.optInt("retriesUsed", 0), text, input.optBoolean("crashed", false))
                if (decision == null) JSONObject.NULL
                else obj(
                    "retry" to decision.retry, "delayMs" to decision.delayMs, "engineRelated" to decision.engineRelated,
                    "rateLimited" to decision.rateLimited, "refreshEngine" to decision.refreshEngine,
                )
            }
            else -> error("unknown errors op $op")
        }
        MusicFixtureRunner.assertSubset(actual, case.opt("expected"), name)
    }

    /** The shared core's own proof, run again where the platform's regex engine differs: no message names a tool. */
    fun everyFixedMessageIsFree(): List<String> =
        Errors.allMessages(Platform.ANDROID).filter { toolNames.containsMatchIn(it) }

}
