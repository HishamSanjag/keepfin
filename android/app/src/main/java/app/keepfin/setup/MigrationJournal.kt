package app.keepfin.setup

import java.io.File
import java.io.FileOutputStream
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Per-item states of the migration (the desktop's vocabulary, `gui/desktop/store/migration/journal.ts`).
 * `PLANNED` is implicit: every plan item starts there without a journal line.
 *
 * - COPIED: the copy is in the Keepfin folder, fsynced and read back (size and hash).
 * - COMMITTED: the item and its placement are in the library (operations durable on this device).
 * - REMOVABLE: the original waits for the user's consent to be removed.
 * - DONE: finished; [ItemFold.kept] says the original was left in place.
 */
internal enum class ItemState { PLANNED, COPIED, COMMITTED, REMOVABLE, DONE, FAILED }

internal class ItemFold(var state: ItemState = ItemState.PLANNED) {
    var failedFrom: ItemState? = null
    var dest: String? = null
    var sha: String? = null
    var size: Long = 0
    var reason: String? = null
    var code: String? = null
    var kept: Boolean = false

    /** The destination chosen before the copy started (to adopt or clear a half-finished one after a crash). */
    var intent: String? = null

    /** Earlier sidecar files copied next to the item (removed with the original). */
    var sidecars: List<String> = emptyList()
    var sidecarsFailed: Int = 0
}

internal class BatchFold(val ids: List<String>) {
    var state: String = "launched"
}

internal class RunFold {
    var started = false
    var layout = false
    var collections = false
    var art = false
    var committed = false
    var done = false
    var stopped: String? = null
    var nextBatch = 0
}

internal class Fold {
    val files = HashMap<String, ItemFold>()
    val batches = HashMap<Int, BatchFold>()
    val run = RunFold()
    var torn = false
    var corrupt = 0
    var lines = 0

    fun stateOf(id: String): ItemState = files[id]?.state ?: ItemState.PLANNED
}

/** Thrown by the crash hook so tests can stop a run exactly before or after a journal write. */
internal class InjectedCrash(message: String = "Injected crash") : RuntimeException(message)

internal class CrashPlan(val before: Int? = null, val after: Int? = null)

/**
 * The append-only journal of a migration run: one durable line per transition, written before the next action
 * (`migration/<runId>/journal.jsonl`). A torn last line (a killed process) is ignored. Not thread-safe; one runner owns it.
 */
internal class MigrationJournal private constructor(private val file: File, val fold: Fold, private val crash: CrashPlan?, private val now: () -> Long) {
    var writes = 0
        private set

    companion object {
        fun open(runDir: File, crash: CrashPlan? = null, now: () -> Long = System::currentTimeMillis): MigrationJournal {
            runDir.mkdirs()
            val file = File(runDir, "journal.jsonl")
            val text = if (file.isFile) file.readText(Charsets.UTF_8) else ""
            val fold = fold(text)
            // close off a torn last line so that new lines never join it
            if (text.isNotEmpty() && !text.endsWith("\n")) appendRaw(file, "\n")
            return MigrationJournal(file, fold, crash, now)
        }

        fun fold(text: String): Fold {
            val out = Fold()
            val clean = text.isEmpty() || text.endsWith("\n")
            val lines = text.split('\n').toMutableList()
            if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
            lines.forEachIndexed { index, line ->
                if (line.isBlank()) return@forEachIndexed
                val record = try {
                    JSONObject(line)
                } catch (e: JSONException) {
                    if (index == lines.size - 1 && !clean) out.torn = true else out.corrupt++
                    return@forEachIndexed
                }
                out.lines++
                apply(out, record)
            }
            return out
        }

        private fun appendRaw(file: File, text: String) {
            FileOutputStream(file, true).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
        }

        private fun strings(a: JSONArray?): List<String> = a?.let { (0 until it.length()).map { i -> it.getString(i) } } ?: emptyList()

        internal fun apply(fold: Fold, r: JSONObject) {
            when (r.optString("k")) {
                "run" -> {
                    val run = fold.run
                    when (r.optString("event")) {
                        "started" -> run.started = true
                        "layout" -> run.layout = true
                        "collections" -> run.collections = true
                        "art" -> run.art = true
                        "committed" -> run.committed = true
                        "done" -> run.done = true
                    }
                    run.stopped = if (r.optString("event") == "stopped") r.optString("reason", "stopped") else null
                }
                "intent" -> fold.files.getOrPut(r.getString("id")) { ItemFold() }.intent = r.getString("dest")
                "file" -> {
                    val file = fold.files.getOrPut(r.getString("id")) { ItemFold() }
                    val state = ItemState.valueOf(r.getString("state"))
                    if (state == ItemState.FAILED) {
                        if (file.state != ItemState.FAILED) file.failedFrom = file.state
                        file.state = ItemState.FAILED
                        file.reason = r.optString("reason").takeIf { it.isNotEmpty() }
                        file.code = r.optString("code").takeIf { it.isNotEmpty() }
                        return
                    }
                    file.state = state
                    if (r.has("dest")) file.dest = r.getString("dest")
                    if (r.has("sha")) file.sha = r.getString("sha")
                    if (r.has("size")) file.size = r.getLong("size")
                    if (r.has("sc")) file.sidecars = strings(r.getJSONArray("sc"))
                    if (r.has("scFailed")) file.sidecarsFailed = r.getInt("scFailed")
                    if (r.has("kept")) { file.kept = r.getBoolean("kept"); file.reason = r.optString("reason").takeIf { it.isNotEmpty() } }
                }
                "retry" -> fold.files[r.getString("id")]?.let { f ->
                    if (f.state == ItemState.FAILED) { f.state = f.failedFrom ?: ItemState.PLANNED; f.failedFrom = null; f.reason = null; f.code = null }
                }
                "batch" -> {
                    val n = r.getInt("n")
                    val batch = fold.batches.getOrPut(n) { BatchFold(strings(r.optJSONArray("ids"))) }
                    batch.state = r.getString("state")
                    if (n >= fold.run.nextBatch) fold.run.nextBatch = n + 1
                }
            }
        }
    }

    private val legalNext = mapOf(
        ItemState.PLANNED to setOf(ItemState.COPIED, ItemState.COMMITTED, ItemState.FAILED),
        ItemState.COPIED to setOf(ItemState.COPIED, ItemState.COMMITTED, ItemState.FAILED),
        ItemState.COMMITTED to setOf(ItemState.REMOVABLE, ItemState.DONE, ItemState.FAILED),
        ItemState.REMOVABLE to setOf(ItemState.DONE, ItemState.FAILED),
        ItemState.DONE to emptySet(),
        ItemState.FAILED to emptySet(),
    )

    /** Appends records in one durable write. A transition that is not allowed is a programming error. */
    fun append(records: List<JSONObject>) {
        if (records.isEmpty()) return
        for (r in records) if (r.optString("k") == "file") {
            val current = fold.stateOf(r.getString("id"))
            val next = ItemState.valueOf(r.getString("state"))
            check(next in legalNext.getValue(current)) { "Illegal migration transition $current -> $next" }
        }
        writes++
        if (crash?.before == writes) throw InjectedCrash("before write $writes")
        val at = now()
        appendRaw(file, records.joinToString("") { it.put("at", at).toString() + "\n" })
        for (r in records) apply(fold, r)
        if (crash?.after == writes) throw InjectedCrash("after write $writes")
    }

    fun append(record: JSONObject) = append(listOf(record))

    // ---- record builders (one place for the wire format)

    fun run(event: String, reason: String? = null) = append(JSONObject().put("k", "run").put("event", event).also { if (reason != null) it.put("reason", reason) })

    fun intent(id: String, dest: String) = append(JSONObject().put("k", "intent").put("id", id).put("dest", dest))

    fun copied(id: String, dest: String, sha: String, size: Long, sidecars: List<String>, sidecarsFailed: Int) = append(
        JSONObject().put("k", "file").put("id", id).put("state", "COPIED").put("dest", dest).put("sha", sha).put("size", size)
            .put("sc", JSONArray(sidecars)).put("scFailed", sidecarsFailed),
    )

    fun committed(ids: List<String>) = append(ids.map { JSONObject().put("k", "file").put("id", it).put("state", "COMMITTED") })

    fun removable(id: String) = append(JSONObject().put("k", "file").put("id", id).put("state", "REMOVABLE"))

    fun done(id: String, kept: Boolean = false, reason: String? = null) = append(
        JSONObject().put("k", "file").put("id", id).put("state", "DONE").also { if (kept) { it.put("kept", true); if (reason != null) it.put("reason", reason) } },
    )

    fun failed(id: String, reason: String, code: String? = null) = append(
        JSONObject().put("k", "file").put("id", id).put("state", "FAILED").put("reason", reason).also { if (code != null) it.put("code", code) },
    )

    fun retry(id: String) = append(JSONObject().put("k", "retry").put("id", id))

    fun batch(n: Int, state: String, ids: List<String>? = null) = append(
        JSONObject().put("k", "batch").put("n", n).put("state", state).also { if (ids != null) it.put("ids", JSONArray(ids)) },
    )
}
