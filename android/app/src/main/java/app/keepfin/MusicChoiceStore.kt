package app.keepfin

import org.json.JSONObject
import java.io.File
import java.net.URI

/**
 * Which YouTube Music recording the user picked for a Spotify / Apple Music / Deezer link. A small JSON file in app storage
 * (the desktop app keeps the same in userData/music-choices.json). Everything read from the file is validated, and a damaged
 * file is ignored (the next change rewrites it).
 */
class MusicChoiceStore(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    data class Choice(val videoId: String, val title: String? = null, val durationSec: Double? = null, val at: Long = 0)

    companion object {
        private const val MAX_CHOICES = 2000
        private val videoId = Regex("[A-Za-z0-9_-]{11}")

        /** The link a choice is stored under: the canonical track link of the service, without share parameters. */
        fun key(url: String): String {
            MusicParsers.spotifyRef(url)?.let { (type, id) -> if (type == "track") return "https://open.spotify.com/track/$id" }
            MusicParsers.deezerRef(url)?.let { (type, id) -> if (type == "track") return "https://www.deezer.com/track/$id" }
            return url.substringBefore('#')
        }

        private fun validKey(url: String): Boolean = try {
            val uri = URI(url)
            uri.scheme == "https" && uri.userInfo == null && url.length <= 2048
        } catch (_: Exception) { false }

        fun validChoice(json: JSONObject?): Choice? {
            json ?: return null
            val id = json.optString("videoId", "").takeIf { videoId.matches(it) } ?: return null
            val title = json.optString("title", "").trim().take(300).takeIf { json.has("title") && !json.isNull("title") && it.isNotEmpty() }
            val seconds = json.optDouble("durationSec", 0.0).takeIf { it.isFinite() && it > 0 && it < 86_400 }
            return Choice(id, title, seconds, json.optLong("at", 0))
        }
    }

    private val lock = Any()
    private var choices: LinkedHashMap<String, Choice>? = null

    private fun load(): LinkedHashMap<String, Choice> = choices ?: LinkedHashMap<String, Choice>().also { map ->
        try {
            val stored = JSONObject(file.readText(Charsets.UTF_8)).optJSONObject("choices")
            stored?.keys()?.forEach { key ->
                val choice = validChoice(stored.optJSONObject(key))
                if (choice != null && validKey(key)) map[key] = choice
            }
        } catch (_: Exception) { /* missing or damaged: start empty */ }
        choices = map
    }

    /** The choice for a source link, if the user made one. */
    fun get(sourceUrl: String): Choice? = synchronized(lock) { load()[key(sourceUrl)] }

    /** Remembers [videoId] for [sourceUrl]. Throws [IllegalArgumentException] when either is not valid. */
    fun set(sourceUrl: String, videoId: String, title: String? = null, durationSec: Double? = null) {
        require(Providers.musicSource(sourceUrl) != null) { "This is not a music service link." }
        val key = key(sourceUrl)
        require(validKey(key)) { "This link cannot be remembered." }
        require(Regex("[A-Za-z0-9_-]{11}").matches(videoId)) { "This recording cannot be remembered." }
        synchronized(lock) {
            val map = load()
            map.remove(key)
            map[key] = Choice(videoId, title?.trim()?.take(300)?.takeIf { it.isNotEmpty() }, durationSec?.takeIf { it.isFinite() && it > 0 && it < 86_400 }, clock())
            while (map.size > MAX_CHOICES) map.remove(map.keys.first())
            save(map)
        }
    }

    private fun save(map: Map<String, Choice>) {
        val root = JSONObject().put("version", 1)
        val stored = JSONObject()
        map.forEach { (key, choice) -> stored.put(key, JSONObject().put("videoId", choice.videoId).put("title", choice.title).put("durationSec", choice.durationSec).put("at", choice.at)) }
        root.put("choices", stored)
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(root.toString(), Charsets.UTF_8)
        if (!temporary.renameTo(file)) { file.delete(); check(temporary.renameTo(file)) { "The choice could not be saved." } }
    }
}
