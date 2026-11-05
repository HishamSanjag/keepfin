package app.keepfin.net

import app.keepfin.AudioOption
import app.keepfin.DownloadOptions
import app.keepfin.EngineApi
import app.keepfin.FormatRequest
import app.keepfin.InspectResult
import app.keepfin.MediaInfo
import app.keepfin.SubtitleOptions
import app.keepfin.VideoOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The queue behind "Download on..." for the receiving phone (spec 6): a job from another device is checked again right before the
 * download engine starts (every redirect hop, every address), inspected for its title and size, and, when it is large (over 1 GiB) or
 * the connection is metered, it waits for the person here ("consent"). Then it is handed to the same engine as the person's own
 * downloads, under the same capture rules. Mirrors the desktop manager's remote-job handling.
 */
class EngineJobPort(
    private val engine: EngineApi,
    private val metered: () -> Boolean,
    private val free: () -> Long?,
    private val guard: (String) -> String = { UrlGuard.guard(it) },
    private val consentBytes: Long = REMOTE_CONSENT_BYTES,
    private val now: () -> Long = { System.currentTimeMillis() },
) : JobPort {
    class Consent(val reason: String, val bytes: Long?)

    private class Rec(val jobId: String, val url: String, val format: JSONObject, val options: JSONObject?, val space: String?, val startedAt: Long) {
        @Volatile var state: String = "preparing"
        @Volatile var engineJobId: String? = null
        @Volatile var title: String? = null
        @Volatile var label: String? = null
        @Volatile var error: String? = null
        @Volatile var expected: Long? = null
        @Volatile var consent: Consent? = null
        @Volatile var consented = false
        @Volatile var media: MediaInfo? = null
        @Volatile var declined = false
    }

    private val recs = ConcurrentHashMap<String, Rec>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "keepfin-remote-job").also { it.isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val watching = AtomicBoolean(false)

    override fun spaces(): List<Pair<String, String>> = engine.settings.value.library.spaces.map { it.id to it.name }

    override fun freeBytes(): Long? = free()

    override fun subscribe(listener: () -> Unit): () -> Unit {
        listeners += listener
        if (watching.compareAndSet(false, true)) scope.launch { engine.jobs.collect { fire() } }
        return { listeners -= listener }
    }

    private fun fire() {
        for (l in listeners) runCatching { l() }
    }

    fun close() {
        scope.cancel()
        pool.shutdownNow()
    }

    override fun create(jobId: String, url: String, format: JSONObject, options: JSONObject?, space: String?) {
        if (recs.containsKey(jobId)) return
        val rec = Rec(jobId, url, format, options, space, now())
        recs[jobId] = rec
        pool.execute { prepare(rec) }
        fire()
    }

    private fun prepare(rec: Rec) {
        try {
            if (rec.media == null) {
                guard(rec.url)
                val result = engine.inspect(rec.url, "remote-${rec.jobId}")
                if (result !is InspectResult.Media) throw IllegalStateException("Only a single link can be sent to another device. Send the items of a collection one by one.")
                val media = result.media
                rec.media = media
                rec.title = media.title.ifEmpty { null }
                val request = formatOf(rec.format)
                rec.label = labelOf(request)
                rec.expected = estimate(media, request)
            }
            if (!rec.consented) {
                val isMetered = try { metered() } catch (e: Exception) { false }
                val big = (rec.expected ?: 0L) > consentBytes
                if (isMetered || big) {
                    rec.consent = Consent(if (isMetered) "metered" else "size", rec.expected)
                    rec.state = "consent"
                    fire()
                    return
                }
            }
            start(rec)
        } catch (e: Exception) {
            rec.state = "failed"
            rec.error = e.message ?: "The download could not start."
            fire()
        }
    }

    private fun start(rec: Rec) {
        val media = rec.media ?: return
        val before = engine.jobs.value.map { it.id }.toSet()
        engine.enqueue(media, formatOf(rec.format), optionsOf(rec.options), rec.space)
        // the engine makes its own id: the job is the new one for this link
        val deadline = System.currentTimeMillis() + 8000
        while (System.currentTimeMillis() < deadline) {
            val claimed = recs.values.mapNotNull { it.engineJobId }.toSet()
            val found = engine.jobs.value.firstOrNull { it.id !in before && it.id !in claimed && (it.url == media.url || it.url == rec.url || it.url == media.webpageUrl) }
            if (found != null) {
                rec.engineJobId = found.id
                rec.state = "queued"
                fire()
                return
            }
            Thread.sleep(100)
        }
        rec.state = "failed"
        rec.error = "The download did not start."
        fire()
    }

    /** The person here allows a job that was waiting. */
    fun allow(jobId: String) {
        val rec = recs[jobId] ?: return
        if (rec.state != "consent") return
        rec.consented = true
        rec.consent = null
        rec.state = "preparing"
        fire()
        pool.execute { prepare(rec) }
    }

    fun decline(jobId: String) {
        val rec = recs[jobId] ?: return
        if (rec.state == "consent") {
            rec.declined = true
            rec.consent = null
            rec.state = "cancelled"
            rec.error = "Declined on the other device."
            fire()
        } else rec.engineJobId?.let { engine.cancel(it) }
    }

    override fun view(jobId: String): RemoteJobView? {
        val rec = recs[jobId] ?: return null
        val job = rec.engineJobId?.let { id -> engine.jobs.value.firstOrNull { it.id == id } }
        if (job == null) {
            // the engine job was removed from the queue by the person here: the request is over
            if (rec.engineJobId != null && rec.state != "failed" && rec.state != "cancelled" && rec.state != "completed") {
                return RemoteJobView("cancelled", 0.0, rec.title, rec.label, null, null, rec.expected, "Removed on the other device.")
            }
            return RemoteJobView(rec.state, 0.0, rec.title, rec.label, null, null, rec.expected, rec.error)
        }
        return RemoteJobView(job.status.wire, job.percent.toDouble(), job.title.takeIf { it != job.url }, job.label, job.speed, job.eta, job.totalBytes ?: job.expectedSize, job.error)
    }

    /** What the Downloads page shows for the jobs other devices asked this phone for. */
    fun rows(originOf: (String) -> String?): List<IncomingJobRow> = recs.values.sortedByDescending { it.startedAt }.mapNotNull { rec ->
        val device = originOf(rec.jobId) ?: return@mapNotNull null
        val v = view(rec.jobId) ?: return@mapNotNull null
        IncomingJobRow(rec.jobId, device, rec.title ?: rec.url, v.state, v.percent, rec.consent, rec.error ?: v.error)
    }

    // ---- translating the request ----------------------------------------------------------------------------------------------

    private fun formatOf(f: JSONObject): FormatRequest = when (f.optString("type")) {
        "audio" -> FormatRequest.Audio(f.optString("target", "mp3"), f.optString("quality", "best"), null)
        else -> FormatRequest.Video(
            maxHeight = if (f.has("maxHeight") && !f.isNull("maxHeight")) f.optInt("maxHeight") else null,
            formatId = null,
            audioFormatId = null,
            fps = if (f.has("fps") && !f.isNull("fps")) f.optInt("fps") else null,
            codec = f.optString("codec", "auto").takeIf { it in setOf("auto", "h264", "vp9", "av1", "hevc") } ?: "auto",
            container = f.optString("container", "mp4").takeIf { it in setOf("mp4", "mkv", "webm") } ?: "mp4",
        )
    }

    private fun optionsOf(o: JSONObject?): DownloadOptions {
        if (o == null) return engine.settings.value.options
        val base = engine.settings.value.options
        val sub = o.optJSONObject("subtitles")
        val languages = sub?.optString("languages").orEmpty()
        return base.copy(
            subtitles = if (sub != null && Regex("^[a-zA-Z0-9.*,\\-_]{1,100}$").matches(languages)) SubtitleOptions(sub.optBoolean("enabled"), languages, sub.optBoolean("auto"), sub.optBoolean("embed", true)) else base.subtitles,
            embedThumbnail = if (o.has("embedThumbnail")) o.optBoolean("embedThumbnail") else base.embedThumbnail,
            embedMetadata = if (o.has("embedMetadata")) o.optBoolean("embedMetadata") else base.embedMetadata,
            embedChapters = if (o.has("embedChapters")) o.optBoolean("embedChapters") else base.embedChapters,
            sponsorBlock = o.optString("sponsorBlock", base.sponsorBlock).takeIf { it in setOf("off", "mark", "remove") } ?: base.sponsorBlock,
        )
    }

    private fun labelOf(f: FormatRequest): String = when (f) {
        is FormatRequest.Audio -> f.target.uppercase()
        is FormatRequest.Video -> f.maxHeight?.let { "${it}p" } ?: "Best quality"
    }

    private fun estimate(media: MediaInfo, f: FormatRequest): Long? = when (f) {
        is FormatRequest.Audio -> media.audio.mapNotNull(AudioOption::size).maxOrNull()
        is FormatRequest.Video -> {
            val fit = media.video.filter { f.maxHeight == null || it.height <= f.maxHeight }
            (fit.maxByOrNull(VideoOption::height)?.size) ?: media.video.mapNotNull(VideoOption::size).minOrNull()
        }
    }
}

class IncomingJobRow(val jobId: String, val deviceName: String, val title: String, val state: String, val percent: Double, val consent: EngineJobPort.Consent?, val error: String?)
