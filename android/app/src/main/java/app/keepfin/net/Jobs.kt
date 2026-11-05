package app.keepfin.net

import app.keepfin.folder.writeLocalAtomic
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

// Download on... (spec 6 and 9.1 #6): a paired device asks this one to download a link. Two halves. Port of gui/desktop/net/jobs.ts.
//
// The receiver ([JobsHost]) treats the request as untrusted input. It checks the link with the core's URL policy (the guard resolves
// every name and every redirect hop), applies the same capture rules as for the person's own downloads (format, options, an allowed
// space), the storage and queue limits, and keeps the request keyed by peer plus requestId, so a retry or reconnect returns the existing
// job and never makes a second one; the same id with a different payload is refused. The record is written (atomically) before the
// job is made and kept for 30 days. Progress goes back to the asking device as `{t:'job'}` notices over its event stream.
//
// The sender ([OutgoingJobs]) writes each request to disk before it is sent (the requestId is persisted first), so a crash or a lost
// connection resends the same id. Its jobs are shown as remote rows, not in the local queue.

private val UUID_RE = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
private val TERMINAL = setOf("completed", "failed", "cancelled", "refused")

fun isTerminalJob(state: String) = state in TERMINAL

/** What the receiver tells the asking device about a job. */
class RemoteJobView(
    /** queued | preparing | downloading | processing | completed | failed | cancelled | paused | consent */
    val state: String,
    val percent: Double,
    val title: String? = null,
    val label: String? = null,
    val speed: Long? = null,
    val eta: Long? = null,
    val totalBytes: Long? = null,
    val error: String? = null,
) {
    fun json(): JSONObject = JSONObject().put("state", state).put("percent", percent).apply {
        title?.let { put("title", it) }
        label?.let { put("label", it) }
        speed?.let { put("speed", it) }
        eta?.let { put("eta", it) }
        totalBytes?.let { put("totalBytes", it) }
        error?.let { put("error", it) }
    }

    companion object {
        fun of(o: JSONObject) = RemoteJobView(
            o.optString("state", "queued"), o.optDouble("percent", 0.0), o.optString("title").ifEmpty { null }, o.optString("label").ifEmpty { null },
            if (o.has("speed")) o.optLong("speed") else null, if (o.has("eta")) o.optLong("eta") else null, if (o.has("totalBytes")) o.optLong("totalBytes") else null,
            o.optString("error").ifEmpty { null },
        )
    }
}

/** What the download queue offers the receiver; the app wires the real queue to it. */
interface JobPort {
    fun spaces(): List<Pair<String, String>>

    /** Makes the job under [jobId] (the same id again changes nothing). Throws with words when the capture rules refuse it. */
    fun create(jobId: String, url: String, format: JSONObject, options: JSONObject?, space: String?)
    fun view(jobId: String): RemoteJobView?

    /** Called when anything about the queue changed (throttled by the queue). */
    fun subscribe(listener: () -> Unit): () -> Unit

    /** Free bytes where downloads are saved, or null when unknown. */
    fun freeBytes(): Long?
}

private class Entry(
    val peerId: String,
    var peerName: String,
    val requestId: String,
    val jobId: String,
    val hash: String,
    val createdAt: Long,
    var refusedError: String? = null,
    var refusedMessage: String? = null,
) {
    val refused: Boolean get() = refusedError != null
}

class JobAnswer(val status: Int, val body: JSONObject)

private fun answer(status: Int, body: JSONObject) = JobAnswer(status, body)

/** The identity of a request's payload: the same fields in the same order give the same hash. */
fun payloadHash(url: String, format: JSONObject, options: JSONObject?, space: String?): String {
    fun canon(v: Any?): String = when (v) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> v.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canon(v.get(it)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.get(it)) }
        is String -> JSONObject.quote(v)
        else -> v.toString()
    }
    val text = "{" + listOf("format" to canon(format), "options" to canon(options), "space" to canon(space), "url" to JSONObject.quote(url)).joinToString(",") { JSONObject.quote(it.first) + ":" + it.second } + "}"
    return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** Exact format ids belong to the device that inspected the link; the receiver chooses by height, codec and container. */
private fun portable(format: JSONObject): JSONObject {
    val copy = JSONObject(format.toString())
    copy.remove("formatId")
    copy.remove("audioFormatId")
    return copy
}

class JobsHostOptions(
    val file: File,
    val port: JobPort,
    val now: () -> Long = { System.currentTimeMillis() },
    /** Tests replace the link check; the app uses the guard. */
    val checkUrl: (String) -> String = { UrlGuard.guard(it) },
    val notify: (peerId: String, message: JSONObject) -> Unit,
    /** Requests waiting or running for one device at most. */
    val perPeerActive: Int = 500,
    /** Do not accept a job when less than this is free. */
    val minFreeBytes: Long = 512L * 1024 * 1024,
)

class JobsHost private constructor(private val options: JobsHostOptions) {
    private val entries = LinkedHashMap<String, Entry>()
    private val byJob = HashMap<String, Entry>()
    private val lastSent = HashMap<String, String>()
    private var off: (() -> Unit)? = null

    companion object {
        fun open(options: JobsHostOptions): JobsHost {
            val host = JobsHost(options)
            try {
                val saved = JSONObject(options.file.readText(Charsets.UTF_8))
                if (saved.optInt("v") == 1) {
                    val arr = saved.optJSONArray("entries") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val e = arr.optJSONObject(i) ?: continue
                        val peerId = e.optString("peerId")
                        val requestId = e.optString("requestId")
                        val jobId = e.optString("jobId")
                        if (peerId.isEmpty() || requestId.isEmpty() || jobId.isEmpty()) continue
                        host.add(Entry(peerId, e.optString("peerName", "A device"), requestId, jobId, e.optString("hash"), e.optLong("createdAt"), e.optString("refusedError").ifEmpty { null }, e.optString("refusedMessage").ifEmpty { null }))
                    }
                }
            } catch (e: Exception) {
                // no file yet, or one that cannot be read: nothing is remembered
            }
            host.off = options.port.subscribe { host.push() }
            return host
        }
    }

    fun close() {
        off?.invoke()
    }

    @Synchronized private fun add(entry: Entry) {
        entries["${entry.peerId}:${entry.requestId}"] = entry
        byJob[entry.jobId] = entry
    }

    /** The device that asked for this job (the queue marks such jobs). */
    @Synchronized fun originOf(jobId: String): String? = byJob[jobId]?.takeIf { !it.refused }?.peerName

    @get:Synchronized val size: Int get() = entries.size

    @Synchronized private fun save() {
        val cutoff = options.now() - JOB_RETENTION_MS
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val (key, entry) = it.next()
            val view = options.port.view(entry.jobId)
            if (entry.createdAt < cutoff && (entry.refused || view == null || isTerminalJob(view.state))) {
                it.remove()
                byJob.remove(entry.jobId)
                lastSent.remove(key)
            }
        }
        val arr = JSONArray()
        for (e in entries.values) {
            arr.put(JSONObject().put("peerId", e.peerId).put("peerName", e.peerName).put("requestId", e.requestId).put("jobId", e.jobId).put("hash", e.hash).put("createdAt", e.createdAt)
                .apply { e.refusedError?.let { put("refusedError", it); put("refusedMessage", e.refusedMessage ?: "") } })
        }
        writeLocalAtomic(options.file, JSONObject().put("v", 1).put("entries", arr).toString().toByteArray(Charsets.UTF_8))
    }

    @Synchronized private fun active(peerId: String): Int =
        entries.values.count { e -> e.peerId == peerId && !e.refused && (options.port.view(e.jobId)?.let { v -> !isTerminalJob(v.state) } ?: true) }

    fun handle(callerId: String, callerName: String, body: JSONObject): JobAnswer {
        val requestId = body.optString("requestId").lowercase()
        if (!UUID_RE.matches(requestId)) return answer(400, err("request-id", "The request needs an id."))
        val url = body.optString("url").trim()
        if (url.isEmpty() || url.length > 4096) return answer(400, err("url", "The request needs a link."))
        val rawFormat = body.optJSONObject("format") ?: return answer(400, err("format", "The request needs a format."))
        val options = if (body.has("options") && !body.isNull("options")) (body.optJSONObject("options") ?: return answer(400, err("options", "The options are not valid."))) else null
        val space = if (body.has("space") && !body.isNull("space")) (body.opt("space") as? String ?: return answer(400, err("space", "The space is not valid."))) else null
        if (space != null && space.length > 100) return answer(400, err("space", "The space is not valid."))
        val format = portable(rawFormat)
        val hash = payloadHash(url, format, options, space)
        val key = "$callerId:$requestId"

        synchronized(this) {
            val known = entries[key]
            if (known != null) {
                known.peerName = callerName
                if (known.hash != hash) return answer(409, err("payload-changed", "This request id was already used for a different download."))
                if (known.refused) return answer(422, err(known.refusedError!!, known.refusedMessage ?: "").put("requestId", requestId))
                // an earlier answer may have been lost, or the record was written and the job was not made: make it, the same id changes nothing
                if (this.options.port.view(known.jobId) == null) {
                    try {
                        this.options.port.create(known.jobId, url, format, options, space)
                    } catch (e: Exception) {
                        return refuse(known, e.message ?: "The download was not accepted.", "refused")
                    }
                }
                return answer(200, JSONObject().put("ok", true).put("requestId", requestId).put("view", this.options.port.view(known.jobId)?.json() ?: JSONObject.NULL))
            }
            if (active(callerId) >= this.options.perPeerActive) return answer(429, err("queue-full", "This device already has many downloads from you waiting. Try again when some have finished."))
            if (space != null && this.options.port.spaces().none { it.first == space }) return record(callerId, callerName, requestId, hash, "space", "That space is not available on this device.")
        }
        try {
            this.options.checkUrl(url)
        } catch (e: UrlRefused) {
            return synchronized(this) { record(callerId, callerName, requestId, hash, "url", e.message ?: "That link was not accepted.") }
        }
        val free = try { this.options.port.freeBytes() } catch (e: Exception) { null }
        if (free != null && free < this.options.minFreeBytes) return answer(507, err("storage", "There is not enough room on this device."))
        synchronized(this) {
            val raced = entries[key]
            if (raced != null) return answer(200, JSONObject().put("ok", true).put("requestId", requestId).put("view", this.options.port.view(raced.jobId)?.json() ?: JSONObject.NULL))
            val entry = Entry(callerId, callerName, requestId, UUID.randomUUID().toString(), hash, this.options.now())
            add(entry)
            save() // the record exists before the job does
            try {
                this.options.port.create(entry.jobId, url, format, options, space)
            } catch (e: Exception) {
                return refuse(entry, e.message ?: "The download was not accepted.", "refused")
            }
            return answer(201, JSONObject().put("ok", true).put("requestId", requestId).put("view", this.options.port.view(entry.jobId)?.json() ?: JSONObject.NULL))
        }
    }

    private fun err(code: String, message: String) = JSONObject().put("error", code).put("message", message)

    /** A request refused for what it asks: remembered, so asking again gets the same answer. */
    private fun record(callerId: String, callerName: String, requestId: String, hash: String, error: String, message: String): JobAnswer {
        val entry = Entry(callerId, callerName, requestId, UUID.randomUUID().toString(), hash, options.now(), error, message)
        add(entry)
        save()
        return answer(422, err(error, message).put("requestId", requestId))
    }

    private fun refuse(entry: Entry, message: String, error: String): JobAnswer {
        entry.refusedError = error
        entry.refusedMessage = message
        save()
        return answer(422, err(error, message).put("requestId", entry.requestId))
    }

    /** The queue changed: tell each asking device what is new about its jobs (only what changed). */
    @Synchronized private fun push() {
        for ((key, entry) in entries.entries.toList()) {
            if (entry.refused) continue
            val view = options.port.view(entry.jobId) ?: continue
            val text = view.json().apply { remove("speed"); remove("eta"); put("percent", Math.round(view.percent)) }.toString()
            if (lastSent[key] == text) continue
            lastSent[key] = text
            options.notify(entry.peerId, JSONObject().put("t", "job").put("requestId", entry.requestId).put("view", view.json()))
        }
    }

    /** A device connected (again): its events from before the connection are gone, so everything is sent once. */
    @Synchronized fun resend(peerId: String) {
        for ((key, entry) in entries) if (entry.peerId == peerId) lastSent.remove(key)
        push()
    }
}

// ---- sender ----------------------------------------------------------------------------------------------------------------------

class OutgoingJob(
    val requestId: String,
    val deviceId: String,
    var deviceName: String,
    val url: String,
    val format: JSONObject,
    val options: JSONObject?,
    val space: String?,
    val createdAt: Long,
    var updatedAt: Long,
    /** sending | refused | queued | preparing | downloading | processing | completed | failed | cancelled | paused | consent */
    var state: String,
    var percent: Double = 0.0,
    var title: String? = null,
    var label: String? = null,
    var speed: Long? = null,
    var eta: Long? = null,
    var totalBytes: Long? = null,
    var error: String? = null,
) {
    fun json(): JSONObject = JSONObject().put("requestId", requestId).put("deviceId", deviceId).put("deviceName", deviceName).put("url", url).put("format", format)
        .put("createdAt", createdAt).put("updatedAt", updatedAt).put("state", state).put("percent", percent).apply {
            options?.let { put("options", it) }
            space?.let { put("space", it) }
            title?.let { put("title", it) }
            label?.let { put("label", it) }
            speed?.let { put("speed", it) }
            eta?.let { put("eta", it) }
            totalBytes?.let { put("totalBytes", it) }
            error?.let { put("error", it) }
        }

    companion object {
        fun of(o: JSONObject) = OutgoingJob(
            o.getString("requestId"), o.optString("deviceId"), o.optString("deviceName"), o.optString("url"), o.optJSONObject("format") ?: JSONObject(), o.optJSONObject("options"),
            o.optString("space").ifEmpty { null }, o.optLong("createdAt"), o.optLong("updatedAt"), o.optString("state", "sending"), o.optDouble("percent", 0.0),
            o.optString("title").ifEmpty { null }, o.optString("label").ifEmpty { null }, if (o.has("speed")) o.optLong("speed") else null, if (o.has("eta")) o.optLong("eta") else null,
            if (o.has("totalBytes")) o.optLong("totalBytes") else null, o.optString("error").ifEmpty { null },
        )
    }
}

class OutgoingJobs private constructor(private val file: File, private val now: () -> Long) {
    private val jobs = LinkedHashMap<String, OutgoingJob>()

    companion object {
        fun open(file: File, now: () -> Long = { System.currentTimeMillis() }): OutgoingJobs {
            val store = OutgoingJobs(file, now)
            try {
                val saved = JSONObject(file.readText(Charsets.UTF_8))
                if (saved.optInt("v") == 1) {
                    val arr = saved.optJSONArray("jobs") ?: JSONArray()
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { runCatching { OutgoingJob.of(it) }.getOrNull() }?.let { store.jobs[it.requestId] = it }
                }
            } catch (e: Exception) {
                // none yet
            }
            return store
        }
    }

    @Synchronized fun list(): List<OutgoingJob> = jobs.values.sortedByDescending { it.createdAt }

    @Synchronized fun get(requestId: String): OutgoingJob? = jobs[requestId]

    /** Waiting to be delivered or still running on the other device. */
    @Synchronized fun openFor(deviceId: String): List<OutgoingJob> = list().filter { it.deviceId == deviceId && !isTerminalJob(it.state) }

    /** Written to disk before it is sent. */
    @Synchronized fun create(deviceId: String, deviceName: String, url: String, format: JSONObject, options: JSONObject?, space: String?): OutgoingJob {
        val job = OutgoingJob(UUID.randomUUID().toString(), deviceId, deviceName, url, format, options, space, now(), now(), "sending")
        jobs[job.requestId] = job
        save()
        return job
    }

    @Synchronized fun update(requestId: String, change: (OutgoingJob) -> Unit) {
        val job = jobs[requestId] ?: return
        if (isTerminalJob(job.state)) return
        change(job)
        job.updatedAt = now()
        save()
    }

    /** Applies what the other device said about the job. */
    fun applyView(requestId: String, view: RemoteJobView) = update(requestId) {
        it.state = view.state
        it.percent = view.percent
        it.title = view.title
        it.label = view.label
        it.speed = view.speed
        it.eta = view.eta
        it.totalBytes = view.totalBytes
        it.error = view.error
    }

    @Synchronized fun dismiss(requestId: String) {
        val job = jobs[requestId] ?: return
        if (!isTerminalJob(job.state)) return
        jobs.remove(requestId)
        save()
    }

    private fun save() {
        val cutoff = now() - JOB_RETENTION_MS
        jobs.entries.removeAll { it.value.updatedAt < cutoff && isTerminalJob(it.value.state) }
        val arr = JSONArray()
        for (j in jobs.values) arr.put(j.json())
        writeLocalAtomic(file, JSONObject().put("v", 1).put("jobs", arr).toString().toByteArray(Charsets.UTF_8))
    }
}
