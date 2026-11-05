package app.keepfin

import app.keepfin.core.dedupe.Recording
import app.keepfin.core.dedupe.Recordings
import org.json.JSONArray
import org.json.JSONObject

/*
 * Compiled into BOTH the JVM unit tests (src/test) and the device tests (src/androidTest), like MusicFixtureRunner.
 * Runs every case of fixtures/engine/dedupe through the shared core (app.keepfin.core.dedupe.Recordings) exactly as
 * fixtures/engine/dedupe/SCHEMA.md says, and compares per fixtures/engine/README.md (subset).
 */
object DedupeFixtureRunner {
    private const val DIR = "engine/dedupe"

    fun run(files: FixtureFiles): MusicFixtureRunner.Result {
        val failures = ArrayList<String>()
        var ran = 0
        var skipped = 0
        for (file in files.list(DIR).filter { it.endsWith(".json") }.sorted()) {
            val label = "dedupe/${file.removeSuffix(".json")}"
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
    private fun JSONObject.dbl(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
    private fun JSONObject.bool(key: String): Boolean? = if (has(key) && !isNull(key)) getBoolean(key) else null
    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().also { o -> pairs.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) } }
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun recording(r: JSONObject) = Recording(
        title = r.str("title") ?: "", artist = r.str("artist"), album = r.str("album"), duration = r.dbl("duration"),
        sourceIds = r.optJSONArray("sourceIds")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty(),
        contentKey = r.str("contentKey"), isrc = r.str("isrc"),
    )

    private fun execute(input: JSONObject): Any? = when (val op = input.getString("op")) {
        "sourceId" -> Recordings.sourceId(input.str("url"))
        "normalizeTitle" -> Recordings.normalizeTitle(input.getString("title"), input.str("artist"))
        "normalizeArtists" -> JSONArray(Recordings.normalizeArtists(input.str("artist")))
        "same" -> obj("match" to Recordings.sameRecording(recording(input.getJSONObject("a")), recording(input.getJSONObject("b"))))
        "compare" -> obj("tier" to Recordings.compare(recording(input.getJSONObject("a")), recording(input.getJSONObject("b")))?.wire)
        "existing" -> {
            val known = input.getJSONArray("items").objects().filter { it.bool("missing") != true }.map { i ->
                i.getString("id") to Recording(
                    title = i.getString("title"), artist = i.str("artist") ?: i.str("albumArtist"), album = i.str("album"),
                    duration = i.dbl("duration"), sourceIds = setOfNotNull(i.str("sourceId")), isrc = i.str("isrc"),
                )
            }
            val probe = input.getJSONObject("probe")
            val r = probe.optJSONObject("recording")
            val candidate = Recording(
                title = r?.str("title") ?: "", artist = r?.str("artist"), album = r?.str("album"), duration = r?.dbl("duration"),
                sourceIds = setOfNotNull(probe.str("sourceId")), isrc = probe.str("isrc") ?: r?.str("isrc"),
            )
            val found = Recordings.find(candidate, known)
            obj("itemId" to found?.first, "by" to found?.second?.wire)
        }
        else -> throw AssertionError("unknown dedupe op $op")
    }
}
