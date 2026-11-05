package app.keepfin

import app.keepfin.core.music.AlbumCandidate
import app.keepfin.core.music.AlbumMatching
import app.keepfin.core.music.AlbumPage
import app.keepfin.core.music.AlbumStep
import app.keepfin.core.music.Candidate
import app.keepfin.core.music.MusicMatching
import app.keepfin.core.music.MusicVideos
import app.keepfin.core.music.Origin
import app.keepfin.core.music.VideoCandidate
import app.keepfin.core.music.Search
import app.keepfin.core.music.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/*
 * Compiled into BOTH the JVM unit tests (src/test) and the device tests (src/androidTest): one implementation, two places that read
 * the fixtures. It uses org.json only (the org.json:json test dependency on the JVM, the platform's on a device).
 */

/** Read access to the fixtures folder. Paths are relative to `fixtures/`, for example `engine/music/pick.json`. */
interface FixtureFiles {
    fun read(path: String): String

    /** Names (not paths) of the entries of [dir], files and folders. */
    fun list(dir: String): List<String>
}

/** Fixtures on disk, for the JVM unit tests. [root] is the repository's `fixtures` folder. */
class DirectoryFixtureFiles(private val root: File) : FixtureFiles {
    override fun read(path: String): String = File(root, path).readText(Charsets.UTF_8)
    override fun list(dir: String): List<String> = File(root, dir).list()?.sorted() ?: emptyList()
}

/**
 * Runs every case of fixtures/engine/music through the shared core (app.keepfin.core.music) exactly as
 * fixtures/engine/music/SCHEMA.md says, and compares per fixtures/engine/README.md (subset; `minConfidence` is a floor).
 */
object MusicFixtureRunner {
    class Result(val ran: Int, val skipped: Int, val knownMismatches: Int, val failures: List<String>)

    private const val DIR = "engine/music"

    fun run(files: FixtureFiles): Result {
        val failures = ArrayList<String>()
        var ran = 0
        var skipped = 0
        var known = 0
        for (file in files.list(DIR).filter { it.endsWith(".json") }.sorted()) {
            val label = "music/${file.removeSuffix(".json")}"
            val cases = JSONObject(files.read("$DIR/$file")).getJSONArray("cases")
            val seen = HashSet<String>()
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val name = case.getString("name")
                require(seen.add(name)) { "duplicate case name \"$name\" in $label" }
                val platforms = case.optJSONArray("platforms")
                if (platforms != null && (0 until platforms.length()).none { platforms.optString(it) == "android" }) { skipped++; continue }
                ran++
                val mismatch = case.optString("suspectedMismatch", "").takeIf { it.isNotEmpty() }
                val failure = try { runCase(files, case); null } catch (e: Throwable) { e.message ?: e::class.java.simpleName }
                when {
                    mismatch != null && failure != null -> known++
                    mismatch != null -> failures.add("$label: $name now passes: remove its suspectedMismatch")
                    failure != null -> failures.add("$label: $name\n      $failure")
                }
            }
        }
        return Result(ran, skipped, known, failures)
    }

    private fun runCase(files: FixtureFiles, case: JSONObject) {
        val name = case.getString("name")
        val input = case.getJSONObject("input")
        val actual = execute(files, input)
        val expected = case.opt("expected")
        // minConfidence is a floor on the winner's confidence; everything else is a subset comparison.
        if (expected is JSONObject && expected.has("minConfidence")) {
            val floor = expected.getDouble("minConfidence")
            val confidence = (actual as? JSONObject)?.let { if (it.has("confidence") && !it.isNull("confidence")) it.getDouble("confidence") else null } ?: 0.0
            if (confidence < floor) throw AssertionError("$name: confidence $confidence < $floor")
            val rest = JSONObject(expected.toString())
            rest.remove("minConfidence")
            assertSubset(actual, rest, name)
        } else assertSubset(actual, expected, name)
    }

    // ---- input ---------------------------------------------------------------------------------------------

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    private fun JSONObject.dbl(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
    private fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun trackOf(input: JSONObject): Track {
        val t = input.getJSONObject("track")
        fun int(key: String): Int? = if (t.has(key) && !t.isNull(key)) t.getInt(key) else null
        return Track(t.getString("title"), t.optJSONArray("artists").strings(), t.str("album"), t.dbl("durationSec"), int("trackNumber"), int("trackCount"), int("year"))
    }

    /** The desktop runner's candidatesOf: url music.youtube.com/watch?v=<id>, origin "youtube" by default, artists from the channel without " - Topic". */
    private fun candidateOf(c: JSONObject): Candidate {
        val id = c.getString("id")
        val channel = c.str("channel")
        val artists = c.optJSONArray("artists")?.strings()
            ?: if (!channel.isNullOrEmpty()) listOf(channel.removeSuffix(" - Topic")) else emptyList()
        return Candidate(
            videoId = id, url = "https://music.youtube.com/watch?v=$id", title = c.getString("title"), artists = artists, channel = channel,
            album = c.str("album"), durationSec = c.dbl("durationSec"), origin = Origin.fromWire(c.str("origin") ?: "youtube"),
        )
    }

    private fun candidatesOf(list: JSONArray?): List<Candidate> = list?.objects()?.map(::candidateOf) ?: emptyList()

    private fun sampleOrData(files: FixtureFiles, input: JSONObject): String {
        val file = input.str("file")
        return if (file != null) files.read("$DIR/samples/$file") else input.get("data").toString()
    }

    // ---- output --------------------------------------------------------------------------------------------

    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().also { o -> pairs.forEach { (k, v) -> if (v != null) o.put(k, v) } }
    private fun arr(items: Iterable<Any?>) = JSONArray().also { a -> items.forEach { a.put(it ?: JSONObject.NULL) } }

    private fun candidateJson(c: Candidate, withUrl: Boolean) = obj(
        "id" to c.videoId, "url" to c.url.takeIf { withUrl }, "title" to c.title, "artists" to arr(c.artists), "channel" to c.channel,
        "album" to c.album, "durationSec" to c.durationSec, "origin" to c.origin.wire,
    )

    private fun videoJson(v: VideoCandidate) = obj(
        "id" to v.videoId, "type" to v.type, "title" to v.title, "views" to v.views, "durationSec" to v.durationSec,
        "artists" to arr(v.artists.map { a -> obj("name" to a.name, "browseId" to a.browseId) }),
    )

    private fun albumJson(a: AlbumCandidate) = obj("id" to a.browseId, "title" to a.title, "artists" to arr(a.artists), "year" to a.year, "kind" to a.kind)

    private fun pageJson(p: AlbumPage) = obj(
        "title" to p.title, "artists" to arr(p.artists), "year" to p.year, "trackCount" to p.trackCount,
        "tracks" to arr(p.tracks.map { obj("id" to it.videoId, "title" to it.title, "artists" to arr(it.artists), "durationSec" to it.durationSec, "position" to it.position) }),
    )

    private fun execute(files: FixtureFiles, input: JSONObject): Any? = when (val op = input.getString("op")) {
        "best" -> {
            val match = MusicMatching.best(trackOf(input), candidatesOf(input.optJSONArray("candidates")))
            obj("bestId" to match?.candidate?.videoId, "confidence" to match?.confidence)
        }
        "alternates" -> {
            val matches = MusicMatching.alternates(trackOf(input), candidatesOf(input.optJSONArray("candidates")), input.optJSONArray("exclude").strings().toSet())
            obj("ids" to arr(matches.map { it.candidate.videoId }))
        }
        "query" -> MusicMatching.query(trackOf(input))
        "albumSearch" -> obj("albums" to arr(AlbumMatching.parseSearch(sampleOrData(files, input)).map(::albumJson)))
        "albumPage" -> AlbumMatching.parsePage(sampleOrData(files, input))?.let(::pageJson) ?: JSONObject.NULL
        "albumQuery" -> trackOf(input).let { obj("applies" to AlbumMatching.applies(it), "query" to AlbumMatching.query(it).takeIf { _ -> AlbumMatching.applies(it) }) }
        "albumHint" -> obj("album" to AlbumMatching.hint(trackOf(input), candidatesOf(input.optJSONArray("candidates"))))
        "albumRank" -> {
            val albums = AlbumMatching.parseSearch(files.read("$DIR/samples/${input.getString("search")}"))
            obj("ids" to arr(AlbumMatching.rank(trackOf(input), albums).map { it.browseId }))
        }
        "album" -> {
            // The platform loop: ask for the next step, fetch (here: read the capture) what it names, ask again.
            val track = trackOf(input)
            val albums = AlbumMatching.parseSearch(files.read("$DIR/samples/${input.getString("search")}"))
            val pages = input.getJSONObject("pages")
            val fetched = LinkedHashMap<String, AlbumPage?>()
            var result: JSONObject? = null
            for (round in 0 until 6) {
                val step = AlbumMatching.step(track, albums, fetched)
                if (step is AlbumStep.Fetch) {
                    val file = pages.getString(step.browseId)
                    fetched[step.browseId] = if (file == "fail") null else AlbumMatching.parsePage(files.read("$DIR/samples/$file"))
                } else {
                    result = if (step is AlbumStep.Matched) obj(
                        "kind" to "matched", "browseId" to step.match.browseId, "albumTitle" to step.match.albumTitle, "position" to step.match.position,
                        "videoId" to step.match.candidate.videoId, "title" to step.match.candidate.title, "fetched" to arr(fetched.keys),
                    ) else obj("kind" to "none", "fetched" to arr(fetched.keys))
                    break
                }
            }
            result ?: throw AssertionError("the album steps did not finish")
        }
        "songsApi" -> obj("candidates" to arr(MusicMatching.parseSongsApi(sampleOrData(files, input)).map { candidateJson(it, withUrl = false) }))
        "flat" -> obj("candidates" to arr(
            MusicMatching.flatCandidates(sampleOrData(files, input), Origin.fromWire(input.getString("origin"))).map { candidateJson(it, withUrl = true) },
        ))
        "searches" -> arr(MusicMatching.searches(trackOf(input)).map { step ->
            when (step) {
                is Search.SongsApi -> obj("kind" to "songsApi", "query" to step.query)
                is Search.Flat -> obj("kind" to "flat", "target" to step.target, "origin" to step.origin.wire, "playlistEnd" to step.playlistEnd)
            }
        })
        "pick" -> {
            // Searches run in order, candidates accumulate, and the run stops once the best so far is confident.
            val track = trackOf(input)
            val searches = input.getJSONArray("searches")
            val found = ArrayList<Candidate>()
            var run = 0
            for (index in 0 until searches.length()) {
                run++
                val result = searches.get(index)
                if (result is JSONArray) {
                    found.addAll(candidatesOf(result))
                    if (MusicMatching.isConfident(MusicMatching.best(track, found))) break
                } else if (result != "fail") throw AssertionError("a search is a candidate list or \"fail\", got $result")
            }
            obj("bestId" to MusicMatching.best(track, found)?.candidate?.videoId, "searchesRun" to run)
        }
        "videosParse" -> obj("candidates" to arr(MusicVideos.parse(sampleOrData(files, input)).map { videoJson(it) }))
        "videoBest" -> {
            val candidates = MusicVideos.parse(sampleOrData(files, input))
            obj("videoId" to MusicVideos.best(input.getJSONArray("artists").strings(), input.getString("title"), candidates, input.optJSONArray("artistIds").strings())?.videoId)
        }
        else -> throw AssertionError("unknown music op $op")
    }

    // ---- comparison (fixtures/engine/README.md "Comparison rules") ---------------------------------------------

    private fun isNull(value: Any?) = value == null || value == JSONObject.NULL

    /** `expected` is a subset of `actual`: objects by expected keys, arrays by length then element, null matches null or absent. */
    fun assertSubset(actual: Any?, expected: Any?, where: String) {
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
                    assertSubset(actual.opt(key), expected.get(key), "$where.$key")
                }
            }
            expected is Number -> {
                if (actual !is Number || abs(actual.toDouble() - expected.toDouble()) > 1e-9) throw AssertionError("$where: expected $expected, got $actual")
            }
            else -> if (actual != expected) throw AssertionError("$where: expected $expected, got $actual")
        }
    }
}
