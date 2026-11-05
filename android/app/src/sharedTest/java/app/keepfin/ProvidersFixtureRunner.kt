package app.keepfin

import app.keepfin.core.providers.MusicLink
import app.keepfin.core.providers.Naming
import app.keepfin.core.providers.PathJob
import app.keepfin.core.providers.SpaceContext
import app.keepfin.core.providers.Url
import org.json.JSONArray
import org.json.JSONObject
import app.keepfin.core.providers.Providers as Core

/*
 * Compiled into BOTH the JVM unit tests (src/test) and the device tests (src/androidTest), like MusicFixtureRunner.
 */

/**
 * Runs every case of fixtures/engine/providers through the shared core (app.keepfin.core.providers) exactly as
 * fixtures/engine/providers/SCHEMA.md says. The core implements both apps' naming, so cases limited to either platform run too
 * (desktop-only ones cover logic the desktop uses, Android-only ones the library naming the app uses).
 */
object ProvidersFixtureRunner {
    class Result(val ran: Int, val failures: List<String>)

    private const val DIR = "engine/providers"

    fun run(files: FixtureFiles): Result {
        val failures = ArrayList<String>()
        var ran = 0
        for (file in files.list(DIR).filter { it.endsWith(".json") }.sorted()) {
            val label = "providers/${file.removeSuffix(".json")}"
            val cases = JSONObject(files.read("$DIR/$file")).getJSONArray("cases")
            val seen = HashSet<String>()
            for (index in 0 until cases.length()) {
                val case = cases.getJSONObject(index)
                val name = case.getString("name")
                require(seen.add(name)) { "duplicate case name \"$name\" in $label" }
                ran++
                val failure = try {
                    MusicFixtureRunner.assertSubset(execute(case.getJSONObject("input")), case.opt("expected"), name)
                    null
                } catch (e: Throwable) {
                    e.message ?: e::class.java.simpleName
                }
                if (failure != null) failures.add("$label: $name\n      $failure")
            }
        }
        return Result(ran, failures)
    }

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    private fun JSONObject.dbl(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
    private fun JSONObject.bool(key: String): Boolean? = if (has(key) && !isNull(key)) getBoolean(key) else null
    private fun obj(vararg pairs: Pair<String, Any?>) = JSONObject().also { o -> pairs.forEach { (k, v) -> o.put(k, v ?: JSONObject.NULL) } }
    private fun arr(items: Iterable<Any?>) = JSONArray().also { a -> items.forEach { a.put(it ?: JSONObject.NULL) } }

    private fun pathJobOf(input: JSONObject): PathJob {
        val job = input.getJSONObject("job")
        val music = job.optJSONObject("music")
        return PathJob(
            url = job.str("url") ?: "", title = job.getString("title"), uploader = job.str("uploader"), createdAt = (job.dbl("createdAt") ?: 0.0).toLong(),
            groupTitle = job.optJSONObject("group")?.str("title"), musicArtist = music?.str("artist"), musicAlbum = music?.str("album"), musicTrack = music?.str("track"),
        )
    }

    private fun spaceContextOf(input: JSONObject): SpaceContext {
        val c = input.getJSONObject("context")
        return SpaceContext(
            title = c.getString("title"), uploader = c.str("uploader"), artist = c.str("artist"), album = c.str("album"), url = c.str("url") ?: "",
            groupTitle = c.str("groupTitle"), groupKind = c.str("groupKind"), audio = c.bool("audio") ?: false, dateMillis = (c.dbl("dateMillis") ?: 0.0).toLong(),
        )
    }

    private fun musicLinkJson(link: MusicLink?): Any? =
        link?.let { obj("service" to it.service, "type" to it.type, "id" to it.id, "storefront" to it.storefront) }

    private fun execute(input: JSONObject): Any? {
        val url = input.str("url") ?: ""
        return when (val op = input.getString("op")) {
            "name" -> Core.name(url, input.str("extractor"))
            "folder" -> Core.folder(url)
            "musicService" -> Core.musicService(url)
            "musicLink" -> musicLinkJson(Core.musicLink(url))
            "collection" -> Core.isCollection(url)
            "playlistUrl" -> Core.playlistUrl(url)
            "startsAsVideo" -> Core.startsAsVideo(url, (input.str("youtubeMusic") ?: "audio") == "audio")
            "preferAudio" -> Core.prefersAudio(url, (input.str("youtubeMusic") ?: "audio") == "audio")
            "gallery" -> Core.isGallery(url)
            "videoId" -> Core.videoId(url)
            "safeName" -> Core.safeName(input.getString("value"), if (input.has("max")) input.getInt("max") else 80)
            "validateUrl" -> {
                val check = Core.validateUrl(input.getString("value"))
                obj("valid" to check.valid, "href" to check.href)
            }
            "url" -> Url.parse(input.getString("value"))?.let {
                obj(
                    "scheme" to it.scheme, "username" to it.username, "password" to it.password, "host" to it.host, "port" to it.port, "path" to it.path,
                    "query" to it.query?.ifEmpty { null }, "fragment" to it.fragment?.ifEmpty { null }, "href" to it.href,
                )
            }
            "isoDate" -> Naming.isoDate(input.getLong("millis"))
            "channelArtist" -> Naming.channelArtist(input.str("value"))
            "outputSubfolders" -> arr(Naming.subfolders(input.getString("layout"), pathJobOf(input)))
            "outputStem" -> Naming.stem(input.getString("naming"), pathJobOf(input))
            "spaceSubfolder" -> Naming.spaceSubfolder(input.getString("layout"), spaceContextOf(input))
            "spaceStem" -> Naming.spaceStem(input.getString("naming"), spaceContextOf(input))
            else -> throw AssertionError("unknown providers op $op")
        }
    }
}
