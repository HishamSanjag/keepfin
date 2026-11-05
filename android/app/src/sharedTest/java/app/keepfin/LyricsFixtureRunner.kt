package app.keepfin

import app.keepfin.core.lyrics.Lyrics
import app.keepfin.core.lyrics.LyricsQuery
import org.json.JSONObject

/*
 * Compiled into BOTH the JVM unit tests (src/test) and the device tests (src/androidTest), like PlaybackFixtureRunner.
 * Runs every case of fixtures/engine/lyrics through the shared core (app.keepfin.core.lyrics.Lyrics), the code LyricsLookup
 * calls, exactly as fixtures/engine/lyrics/SCHEMA.md says, and compares per fixtures/engine/README.md (subset).
 */
object LyricsFixtureRunner {
    private const val DIR = "engine/lyrics"

    fun run(files: FixtureFiles): MusicFixtureRunner.Result {
        val failures = ArrayList<String>()
        var ran = 0
        var skipped = 0
        for (file in files.list(DIR).filter { it.endsWith(".json") }.sorted()) {
            val label = "lyrics/${file.removeSuffix(".json")}"
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

    private fun JSONObject.text(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    private fun JSONObject.num(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null

    private fun execute(input: JSONObject): Any? {
        val query = LyricsQuery(input.text("title") ?: "", input.text("artist") ?: "", input.text("album"), input.num("duration"))
        return when (val op = input.getString("op")) {
            "normalize" -> Lyrics.normalizeKey(input.getString("value"))
            "clean" -> Lyrics.cleanTitle(input.getString("title"), input.text("artist"))
            "getUrl" -> Lyrics.getUrl(query)
            "searchUrl" -> Lyrics.searchUrl(query)
            "exact" -> Lyrics.exactText(input.getJSONObject("record").toString())
            "search" -> Lyrics.searchText(query, input.getJSONArray("records").toString())
            "retry" -> Lyrics.retryAfterMs(input.num("status")?.toInt(), input.getInt("attempt"))
            "busy" -> Lyrics.isBusy(input.num("status")?.toInt())
            "outcome" -> Lyrics.outcome(input.getBoolean("found"), input.getBoolean("gaveUpOnBusy"))
            "isTimed" -> Lyrics.isTimed(input.getString("text"))
            "preferTimed" -> Lyrics.preferTimed(input.optString("exact").ifEmpty { null }, input.optString("found").ifEmpty { null })
            "outcomeWords" -> Lyrics.outcomeWords(input.getString("outcome"))
            "findWords" -> Lyrics.FIND
            else -> throw AssertionError("unknown lyrics op $op")
        }
    }
}
