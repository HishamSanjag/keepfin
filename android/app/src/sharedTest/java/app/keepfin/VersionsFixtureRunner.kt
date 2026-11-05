package app.keepfin

import app.keepfin.core.versions.Channel
import app.keepfin.core.versions.Platform
import app.keepfin.core.versions.Releases
import org.json.JSONArray
import org.json.JSONObject

/*
 * Compiled into BOTH the JVM unit tests (src/test) and the device tests (src/androidTest), like MusicFixtureRunner.
 * Runs every case of fixtures/engine/versions through the shared core (app.keepfin.core.versions.Releases) exactly as
 * fixtures/engine/versions/SCHEMA.md says, and compares per fixtures/engine/README.md (subset).
 */
object VersionsFixtureRunner {
    private const val DIR = "engine/versions"

    fun run(files: FixtureFiles): MusicFixtureRunner.Result {
        val failures = ArrayList<String>()
        var ran = 0
        var skipped = 0
        for (file in files.list(DIR).filter { it.endsWith(".json") }.sorted()) {
            val label = "versions/${file.removeSuffix(".json")}"
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

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null

    private fun execute(input: JSONObject): Any? = when (val op = input.getString("op")) {
        "compare" -> Releases.compare(input.getString("a"), input.getString("b"))
        "pick" -> {
            val list = input.getJSONArray("versions")
            Releases.pick((0 until list.length()).map { list.getString(it) }, Channel.of(input.getString("channel")), input.getString("current"))
        }
        "valid" -> Releases.isValid(input.getString("value"))
        "highlights" -> {
            val limit = if (input.has("limit")) input.getInt("limit") else 3
            JSONArray(Releases.highlights(input.str("notes"), Platform.of(input.str("platform")), limit))
        }
        else -> throw AssertionError("unknown versions op $op")
    }
}
