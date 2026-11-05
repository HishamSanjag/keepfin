package app.keepfin.copies

import app.keepfin.core.copies.Action
import app.keepfin.core.copies.BaselineEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** A change to one baseline entry; a null [entry] removes it. */
internal class BaselineOp(val path: String, val entry: BaselineEntry?)

/** Fault injection for tests: called at every named step; throwing [CrashError] there stops the "process" dead. */
internal fun interface FaultHook {
    fun at(step: String, n: Int, action: Action?)
}

/** What the journal knows of one record (the record types of spec 5.1). */
internal sealed class JournalRecord {
    class Run(val run: String, val at: Long, val generation: Long) : JournalRecord()
    class Intent(val run: String, val n: Int, val action: Action, val tmp: String?, val dest: String?) : JournalRecord()
    class Staged(val run: String, val n: Int, val tmp: String, val contentId: String) : JournalRecord()
    class Preserved(val run: String, val n: Int, val from: String, val archive: String, val entry: CatalogueEntry, val onMain: Boolean) : JournalRecord()
    class Published(val run: String, val n: Int) : JournalRecord()
    class Committed(val run: String, val n: Int, val ops: List<BaselineOp>) : JournalRecord()
    class Skipped(val run: String, val n: Int, val reason: String) : JournalRecord()
    class Failed(val run: String, val n: Int, val reason: String) : JournalRecord()
    class End(val run: String) : JournalRecord()
}

/**
 * The run journal (spec 5.1): `journal.jsonl` on the phone's own storage, append-only, one JSON object per line, torn-tail tolerant.
 * Each action writes `intent`, `staged`, (`preserved`), `published`, `committed` (or `skipped` / `failed`). `intent`, `preserved` and
 * `run` are fsynced; the others are covered by the next durable line and checked against the real files by recovery (spec 5.7 rule 1).
 */
internal class Journal private constructor(val file: File) {
    private var out: FileOutputStream? = null
    @Volatile private var dead = false

    companion object {
        fun open(file: File): Journal {
            file.parentFile?.mkdirs()
            // a torn last line must not swallow the first record written after it
            val torn = file.isFile && file.length() > 0 && java.io.RandomAccessFile(file, "r").use { r -> r.seek(r.length() - 1); r.read() != '\n'.code }
            return Journal(file).also {
                it.out = FileOutputStream(file, true)
                if (torn) it.out!!.write('\n'.code)
            }
        }

        fun encode(r: JournalRecord): String = when (r) {
            is JournalRecord.Run -> JSONObject().put("t", "run").put("run", r.run).put("at", r.at).put("generation", r.generation)
            is JournalRecord.Intent -> JSONObject().put("t", "intent").put("run", r.run).put("n", r.n).put("action", Codec.actionJson(r.action)).put("tmp", r.tmp ?: JSONObject.NULL).put("dest", r.dest ?: JSONObject.NULL)
            is JournalRecord.Staged -> JSONObject().put("t", "staged").put("run", r.run).put("n", r.n).put("tmp", r.tmp).put("contentId", r.contentId)
            is JournalRecord.Preserved -> JSONObject().put("t", "preserved").put("run", r.run).put("n", r.n).put("from", r.from).put("archive", r.archive).put("entry", r.entry.json()).put("onMain", r.onMain)
            is JournalRecord.Published -> JSONObject().put("t", "published").put("run", r.run).put("n", r.n)
            is JournalRecord.Committed -> JSONObject().put("t", "committed").put("run", r.run).put("n", r.n).put(
                "ops", JSONArray(r.ops.map { op -> JSONObject().put("path", op.path).put("entry", op.entry?.let { JSONObject(Codec.baseline(it)) } ?: JSONObject.NULL) }),
            )
            is JournalRecord.Skipped -> JSONObject().put("t", "skipped").put("run", r.run).put("n", r.n).put("reason", r.reason)
            is JournalRecord.Failed -> JSONObject().put("t", "failed").put("run", r.run).put("n", r.n).put("reason", r.reason)
            is JournalRecord.End -> JSONObject().put("t", "end").put("run", r.run)
        }.toString()

        fun decode(line: String): JournalRecord? = try {
            val o = JSONObject(line)
            val run = o.optString("run")
            when (o.getString("t")) {
                "run" -> JournalRecord.Run(run, o.getLong("at"), o.getLong("generation"))
                "intent" -> JournalRecord.Intent(run, o.getInt("n"), Codec.actionOf(o.getJSONObject("action")), if (o.isNull("tmp")) null else o.getString("tmp"), if (o.isNull("dest")) null else o.getString("dest"))
                "staged" -> JournalRecord.Staged(run, o.getInt("n"), o.getString("tmp"), o.getString("contentId"))
                "preserved" -> JournalRecord.Preserved(run, o.getInt("n"), o.getString("from"), o.getString("archive"), CatalogueEntry.of(o.getJSONObject("entry")), o.optBoolean("onMain"))
                "published" -> JournalRecord.Published(run, o.getInt("n"))
                "committed" -> {
                    val arr = o.getJSONArray("ops")
                    JournalRecord.Committed(run, o.getInt("n"), (0 until arr.length()).map { i ->
                        val x = arr.getJSONObject(i)
                        BaselineOp(x.getString("path"), if (x.isNull("entry")) null else Codec.baselineOf(x.getJSONObject("entry").toString()))
                    })
                }
                "skipped" -> JournalRecord.Skipped(run, o.getInt("n"), o.optString("reason"))
                "failed" -> JournalRecord.Failed(run, o.getInt("n"), o.optString("reason"))
                "end" -> JournalRecord.End(run)
                else -> null
            }
        } catch (e: Exception) {
            null
        }

        /** Streams a journal record by record. A torn last line (a crash while writing) and any invalid line are skipped. */
        fun records(file: File): Sequence<JournalRecord> = sequence {
            val reader = LineReader(file)
            try {
                while (reader.hasNext()) decode(reader.next())?.let { yield(it) }
            } finally {
                reader.close()
            }
        }
    }

    val isDead: Boolean get() = dead

    /** Simulates a killed process: every later append throws and nothing more reaches the disk. */
    fun kill() {
        dead = true
    }

    @Synchronized
    fun append(rec: JournalRecord, durable: Boolean) {
        if (dead) throw CrashError("journal-dead")
        val o = out ?: throw CrashError("journal-closed")
        o.write((encode(rec) + "\n").toByteArray(Charsets.UTF_8))
        if (durable) o.fd.sync()
    }

    @Synchronized
    fun close() {
        runCatching { out?.fd?.sync() }
        runCatching { out?.close() }
        out = null
    }
}
