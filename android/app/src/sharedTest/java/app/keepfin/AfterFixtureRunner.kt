package app.keepfin

import app.keepfin.core.after.AfterDownload
import app.keepfin.core.after.Facts
import org.json.JSONArray
import org.json.JSONObject

/*
 * Compiled into BOTH the JVM unit tests (src/test) and the device tests (src/androidTest), like ChoiceFixtureRunner.
 * Runs every case of fixtures/engine/after through the shared core (app.keepfin.core.after.AfterDownload), the code the phone's
 * AfterDownloadSteps runs, exactly as fixtures/engine/after/SCHEMA.md says, and compares per fixtures/engine/README.md (subset).
 */
object AfterFixtureRunner {
    private const val DIR = "engine/after"

    fun run(files: FixtureFiles): MusicFixtureRunner.Result {
        val failures = ArrayList<String>()
        var ran = 0
        var skipped = 0
        for (file in files.list(DIR).filter { it.endsWith(".json") }.sorted()) {
            val label = "after/${file.removeSuffix(".json")}"
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

    private fun execute(input: JSONObject): Any? = when (val op = input.getString("op")) {
        "plan" -> {
            val facts = Facts(
                audio = input.optBoolean("audio"), hasFile = input.optBoolean("hasFile", true), existing = input.optBoolean("existing"),
                video = input.optString("video", "none"), playlist = input.optBoolean("playlist"), lyrics = input.optBoolean("lyrics"),
                library = input.optString("library", "folder"),
            )
            JSONObject().put("steps", JSONArray(AfterDownload.plan(facts).map { JSONObject().put("kind", it.kind).put("onFailure", it.onFailure).put("fallback", it.fallback) }))
        }
        else -> throw AssertionError("unknown after op $op")
    }
}
