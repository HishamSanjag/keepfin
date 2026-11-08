package app.keepfin

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Engine-private job state: the public [Job] plus the intent needed to (re)run it. Immutable. */
internal data class JobRecord(
    val job: Job,
    val format: FormatRequest,
    val options: DownloadOptions,
    val outputTemplate: String? = null,
    val publicFiles: List<String> = emptyList(),
    /** Music resolver jobs: metadata from the source service used for matching and tagging. */
    val source: SourceTags? = null,
    val galleryRepairError: String? = null,
    /** A music video download: the song (library item id) whose video the finished file becomes (dev-docs/plans/MUSIC-VIDEOS.md). Null for every other job. */
    val attachTo: String? = null,
    /** A music video queued with its song from Capture: the song's job id (its library item does not exist yet). The video waits for that job, then is kept beside the song. */
    val attachToJob: String? = null,
    /** A song from a collection with "Also keep music videos where there is one": when it completes, Keepfin looks once for its official video (SongVideoLooks). */
    val findVideo: Boolean = false,
    /** "Add to playlist" chosen in the review: when the job is saved, its library item joins this playlist (PlaylistJoin). */
    val joinPlaylist: String? = null,
    /** A photo post: the private file name -> the public copy it was saved as. What "Save again" skips, and what the already-saved check reads. */
    val photoSaves: Map<String, String> = emptyMap(),
) {
    val id get() = job.id
    val status get() = job.status
}

/** Pure JSON codec for queue.json, including migration of v1 (Studio 0.3.x) JSON records. */
internal object JobCodec {
    const val VERSION = 2

    private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) optString(key).takeIf { it.isNotEmpty() } else null
    private fun JSONObject.long(key: String): Long? = if (has(key) && !isNull(key)) optLong(key).takeIf { it > 0 } else null
    private fun JSONObject.dbl(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() && it > 0 } else null
    private fun JSONArray?.strings(): List<String> = this?.let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotEmpty) } } ?: emptyList()

    fun decodeFile(text: String): List<JobRecord> {
        val trimmed = text.trimStart()
        if (trimmed.isEmpty()) return emptyList()
        if (trimmed.startsWith("[")) {
            val array = JSONArray(trimmed)
            return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::migrateV1) }
        }
        val root = JSONObject(trimmed)
        val array = root.optJSONArray("jobs") ?: JSONArray()
        return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let { runCatching { decode(it) }.getOrNull() } }
    }

    fun encodeFile(records: List<JobRecord>): String = JSONObject().put("version", VERSION)
        .put("jobs", JSONArray().also { array -> records.forEach { array.put(encode(it)) } }).toString()

    // ---- v1 migration -------------------------------------------------------------------------

    private val interruptedMessage = "App closed before completion. Tap retry."

    fun migrateV1(json: JSONObject): JobRecord? {
        val id = json.str("id") ?: return null
        val choice = json.optJSONObject("choice")
        val args = choice?.optJSONArray("args").strings().joinToString(" ")
        val audio = choice?.optString("kind") == "audio" || args.contains("-x") || args.contains("--audio-format")
        val format: FormatRequest = if (audio) FormatRequest.Audio("mp3", "best")
            else FormatRequest.Video(maxHeight = Regex("height<?=(\\d{3,4})").find(args)?.groupValues?.get(1)?.toIntOrNull())
        val extras = json.optJSONObject("extras") ?: JSONObject()
        val languages = extras.optString("subtitleLanguages", "en.*,ar.*").takeIf { ArgsPolicy.isSubtitleLanguages(it) } ?: "en.*"
        val options = DownloadOptions(
            subtitles = SubtitleOptions(enabled = extras.optBoolean("subtitles") || extras.optBoolean("autoSubtitles"), languages = languages,
                auto = extras.optBoolean("autoSubtitles"), embed = false),
            embedThumbnail = false, embedMetadata = extras.optBoolean("metadata"), embedChapters = false,
            writeThumbnail = extras.optBoolean("thumbnail"),
        )
        val stored = JobStatus.of(json.optString("status"))
        val interrupted = stored.active
        val status = if (interrupted) JobStatus.FAILED else stored
        val url = json.str("url") ?: return null
        val job = Job(
            id = id, url = url, title = json.str("title") ?: "Untitled", thumbnail = json.str("thumbnail"), uploader = json.str("uploader"),
            duration = json.dbl("duration"), provider = Providers.name(url), label = json.str("label") ?: choice?.str("label") ?: if (audio) "Audio · MP3" else "Video",
            kind = if (audio) MediaKind.AUDIO else MediaKind.VIDEO, status = status,
            percent = if (status == JobStatus.COMPLETED) 100f else json.optDouble("percent", 0.0).toFloat().coerceIn(0f, 100f),
            speed = null, eta = null, downloadedBytes = null, totalBytes = null, expectedSize = null, fileSize = null, phase = null,
            error = if (interrupted) interruptedMessage else json.str("error"), file = json.str("file"), publicUri = json.str("publicUri"),
            publicPath = json.str("publicPath"), saveError = json.str("saveError"), createdAt = json.long("createdAt") ?: 0L, completedAt = if (status == JobStatus.COMPLETED) json.long("createdAt") else null,
            attempt = json.optInt("attempt", 1).coerceAtLeast(1),
        )
        return JobRecord(job, format, options, outputTemplate = json.str("outputTemplate"), publicFiles = json.optJSONArray("publicFiles").strings(),
            galleryRepairError = json.str("galleryRepairError"))
    }

    // ---- v2 ---------------------------------------------------------------------------------

    fun encodeFormat(format: FormatRequest): JSONObject = when (format) {
        is FormatRequest.Video -> JSONObject().put("type", "video").put("maxHeight", format.maxHeight).put("formatId", format.formatId)
            .put("audioFormatId", format.audioFormatId).put("fps", format.fps).put("codec", format.codec).put("container", format.container)
        is FormatRequest.Audio -> JSONObject().put("type", "audio").put("target", format.target).put("quality", format.quality).put("formatId", format.formatId)
        is FormatRequest.Gallery -> JSONObject().put("type", "gallery").put("count", format.count).put("items", format.items?.let { JSONArray(it) })
    }

    fun decodeFormat(json: JSONObject?): FormatRequest = when (json?.optString("type")) {
        "audio" -> FormatRequest.Audio(json.optString("target", "mp3"), json.optString("quality", "best"), json.str("formatId"))
        "gallery" -> FormatRequest.Gallery(json.optInt("count", 1), json.optJSONArray("items")?.let { items -> (0 until items.length()).map { items.getInt(it) } })
        else -> FormatRequest.Video(json?.optInt("maxHeight")?.takeIf { it > 0 }, json?.str("formatId"), json?.str("audioFormatId"),
            json?.optInt("fps")?.takeIf { it > 0 }, json?.optString("codec", "auto") ?: "auto", json?.optString("container", "mp4") ?: "mp4")
    }

    fun encodeOptions(options: DownloadOptions): JSONObject = JSONObject()
        .put("subtitles", JSONObject().put("enabled", options.subtitles.enabled).put("languages", options.subtitles.languages)
            .put("auto", options.subtitles.auto).put("embed", options.subtitles.embed))
        .put("embedThumbnail", options.embedThumbnail).put("embedMetadata", options.embedMetadata).put("embedChapters", options.embedChapters)
        .put("sponsorBlock", options.sponsorBlock).put("sectionStart", options.sectionStart).put("sectionEnd", options.sectionEnd)
        .put("writeThumbnail", options.writeThumbnail)

    fun decodeOptions(json: JSONObject?): DownloadOptions {
        if (json == null) return DownloadOptions()
        val subs = json.optJSONObject("subtitles")
        return DownloadOptions(
            subtitles = SubtitleOptions(subs?.optBoolean("enabled") ?: false, subs?.optString("languages", "en.*") ?: "en.*", subs?.optBoolean("auto") ?: false, subs?.optBoolean("embed", true) ?: true),
            embedThumbnail = json.optBoolean("embedThumbnail", true), embedMetadata = json.optBoolean("embedMetadata", true),
            embedChapters = json.optBoolean("embedChapters", true), sponsorBlock = json.optString("sponsorBlock", "off"),
            sectionStart = if (json.has("sectionStart") && !json.isNull("sectionStart")) json.optDouble("sectionStart").takeIf { it.isFinite() && it >= 0 } else null,
            sectionEnd = json.dbl("sectionEnd"), writeThumbnail = json.optBoolean("writeThumbnail"),
        )
    }

    private fun encodeMatch(match: MusicMatch?) = match?.let {
        JSONObject().put("source", it.source).put("sourceUrl", it.sourceUrl).put("matchedUrl", it.matchedUrl).put("matchedTitle", it.matchedTitle).put("confidence", it.confidence)
            .put("method", it.method).put("album", it.album).put("position", it.position).put("durationSec", it.durationSec)
            .put("alternates", JSONArray().also { list ->
                it.alternates.forEach { alt -> list.put(JSONObject().put("url", alt.url).put("title", alt.title).put("durationSec", alt.durationSec).put("confidence", alt.confidence)) }
            })
    }

    private fun decodeMatch(json: JSONObject?) = json?.let {
        MusicMatch(
            it.optString("source"), it.optString("sourceUrl"), it.str("matchedUrl"), it.str("matchedTitle"), it.dbl("confidence"),
            method = it.str("method")?.takeIf { m -> m == "album" || m == "search" || m == "chosen" }, album = it.str("album"),
            position = it.optInt("position").takeIf { n -> n > 0 }, durationSec = it.dbl("durationSec"),
            alternates = it.optJSONArray("alternates")?.let { list ->
                (0 until list.length()).mapNotNull { index -> list.optJSONObject(index) }.mapNotNull { alt ->
                    val url = alt.str("url") ?: return@mapNotNull null
                    MusicAlternate(url, alt.str("title") ?: return@mapNotNull null, alt.dbl("durationSec"), alt.dbl("confidence") ?: return@mapNotNull null)
                }.take(3)
            } ?: emptyList(),
        )
    }

    fun encodeGroup(group: JobGroup?) = group?.let {
        JSONObject().put("id", it.id).put("title", it.title).put("url", it.url).put("uploader", it.uploader).put("thumbnail", it.thumbnail).put("kind", it.kind)
    }

    /** Old records have no "group"; a malformed one is ignored rather than dropping the job. */
    fun decodeGroup(json: JSONObject?): JobGroup? {
        val id = json?.str("id") ?: return null
        return JobGroup(id, json.str("title") ?: "Collection", json.str("url") ?: "", json.str("uploader"), json.str("thumbnail"), json.str("kind") ?: "playlist")
    }

    fun encode(record: JobRecord): JSONObject {
        val job = record.job
        return JSONObject().put("id", job.id).put("url", job.url).put("title", job.title).put("thumbnail", job.thumbnail)
            .put("uploader", job.uploader).put("duration", job.duration).put("provider", job.provider).put("label", job.label)
            .put("kind", job.kind.wire).put("status", job.status.wire).put("percent", job.percent.toDouble())
            .put("downloadedBytes", job.downloadedBytes).put("totalBytes", job.totalBytes).put("expectedSize", job.expectedSize)
            .put("fileSize", job.fileSize).put("error", job.error).put("file", job.file).put("publicUri", job.publicUri)
            .put("publicPath", job.publicPath).put("saveError", job.saveError).put("createdAt", job.createdAt).put("completedAt", job.completedAt)
            .put("attempt", job.attempt).put("match", encodeMatch(job.match))
            .put("group", encodeGroup(job.group)).put("retryCount", job.retryCount).put("retryAt", job.retryAt).put("errorDetail", job.errorDetail)
            .put("space", job.space).put("force", job.force).put("filesRemoved", job.filesRemoved)
            .put("collectionId", job.collectionId).put("collectionIndex", job.collectionIndex).put("tags", JSONArray(job.tags)).put("signedIn", job.signedIn)
            .put("existing", job.existing?.let { JSONObject().put("itemId", it.itemId).put("uri", it.uri).put("space", it.space).put("by", it.by) })
            .put("format", encodeFormat(record.format)).put("options", encodeOptions(record.options))
            .put("outputTemplate", record.outputTemplate).put("publicFiles", JSONArray(record.publicFiles))
            .put("galleryRepairError", record.galleryRepairError).put("attachTo", record.attachTo).put("attachToJob", record.attachToJob).put("findVideo", record.findVideo).put("joinPlaylist", record.joinPlaylist).put("photoSaves", JSONObject(record.photoSaves))
            // the one phase a finished job keeps: a music video waiting for its song is settled again after a restart
            .put("phase", job.phase?.takeIf { it == MusicVideoLink.WAITING })
            .put("source", record.source?.let { JSONObject().put("title", it.title).put("artist", it.artist).put("album", it.album).put("track", it.track).put("year", it.year).put("trackCount", it.trackCount) })
    }

    fun decode(json: JSONObject): JobRecord {
        val stored = JobStatus.of(json.optString("status"))
        // Interrupted transfers keep their partial files and resume from them.
        val status = if (stored.active) JobStatus.PAUSED else stored
        val job = Job(
            id = json.getString("id"), url = json.getString("url"), title = json.str("title") ?: "Untitled", thumbnail = json.str("thumbnail"),
            uploader = json.str("uploader"), duration = json.dbl("duration"), provider = json.str("provider"), label = json.str("label") ?: "",
            kind = MediaKind.of(json.optString("kind")), status = status, percent = json.optDouble("percent", 0.0).toFloat().coerceIn(0f, 100f),
            speed = null, eta = null, downloadedBytes = json.long("downloadedBytes"), totalBytes = json.long("totalBytes"),
            expectedSize = json.long("expectedSize"), fileSize = json.long("fileSize"), phase = if (stored.active) "Interrupted · tap resume" else json.str("phase")?.takeIf { it == MusicVideoLink.WAITING },
            error = json.str("error"), file = json.str("file"), publicUri = json.str("publicUri"), publicPath = json.str("publicPath"),
            saveError = json.str("saveError"), createdAt = json.optLong("createdAt"), completedAt = json.long("completedAt"),
            attempt = json.optInt("attempt", 1).coerceAtLeast(1), match = decodeMatch(json.optJSONObject("match")),
            group = decodeGroup(json.optJSONObject("group")), retryCount = json.optInt("retryCount", 0).coerceIn(0, RetryPolicy.MAX),
            // A restart cancels any pending countdown: the job comes back paused (see `status` above) and resumes manually.
            retryAt = null, errorDetail = json.str("errorDetail"),
            // Absent in 0.4.x records: no space (today's folders), nothing existing, no override.
            space = json.str("space"), force = json.optBoolean("force"), filesRemoved = json.optBoolean("filesRemoved"),
            collectionId = json.str("collectionId"), collectionIndex = if (json.has("collectionIndex") && !json.isNull("collectionIndex")) json.optInt("collectionIndex").takeIf { it > 0 } else null,
            tags = json.optJSONArray("tags").strings(), signedIn = json.str("signedIn"),
            existing = json.optJSONObject("existing")?.let { e -> json.optJSONObject("existing")?.str("uri")?.let { uri -> ExistingMatch(e.str("itemId"), uri, e.str("space") ?: "", e.str("by") ?: "metadata") } },
        )
        val source = json.optJSONObject("source")?.let {
            SourceTags(it.optString("title"), it.str("artist"), it.str("album"), it.optInt("track").takeIf { n -> n > 0 }, it.optInt("year").takeIf { n -> n > 0 }, it.optInt("trackCount").takeIf { n -> n > 0 })
        }
        return JobRecord(job, decodeFormat(json.optJSONObject("format")), decodeOptions(json.optJSONObject("options")), json.str("outputTemplate"),
            json.optJSONArray("publicFiles").strings(), source, json.str("galleryRepairError"), json.str("attachTo"), json.str("attachToJob"), json.optBoolean("findVideo"), json.str("joinPlaylist"),
            photoSaves = json.optJSONObject("photoSaves")?.let { saves -> saves.keys().asSequence().mapNotNull { name -> saves.optString(name).takeIf(String::isNotEmpty)?.let { name to it } }.toMap() } ?: emptyMap())
    }
}

/** queue.json persistence. Written only on structural or terminal changes, never on progress ticks. */
internal class JobStore(file: File) {
    private val atomic = AtomicFile(file)

    fun load(): List<JobRecord> = if (!atomic.baseFile.exists()) emptyList()
        else JobCodec.decodeFile(String(atomic.readFully(), Charsets.UTF_8))

    fun save(records: List<JobRecord>) {
        val bytes = JobCodec.encodeFile(records).toByteArray(Charsets.UTF_8)
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) } catch (error: Exception) { atomic.failWrite(stream); throw error }
    }
}
