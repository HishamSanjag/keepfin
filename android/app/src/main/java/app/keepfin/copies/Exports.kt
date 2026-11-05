package app.keepfin.copies

import app.keepfin.core.copies.DesiredEntry
import app.keepfin.core.copies.Holds
import app.keepfin.core.copies.PlaceOptions
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Makes an MP3 of an audio file (the download engine's audio conversion). Metadata and cover art are kept as far as MP3 allows. */
internal fun interface Mp3Converter {
    fun convert(src: File, out: File, abort: () -> Boolean)
}

internal class ExportsReport {
    var written = 0
    var converted = 0
    var removed = 0
    var moved = 0

    /** Exports left undone, with the reason (a name that holds something else, a failed conversion). */
    val skipped = ArrayList<Pair<String, String>>()
}

internal class ExportPlaylist(val id: String, val name: String, val itemIds: List<String>)

internal class ExportItem(val media: String, val title: String)

/**
 * Derived exports of a copy on a folder or drive (dev-docs/plans/KEEPFIN-COPIES-SPEC.md sections 8 and 14): playlist files, a flat layout and
 * MP3 versions. They are made after a run from what the copy verifiably holds, and they are not library content: `.keepfin/exports.json`
 * records every derived file and is written BEFORE the files; the runner leaves recorded paths out of the copy's inventory, so they are
 * never planned or imported back; a derived file is rebuilt when its source changes, renamed when the same source appears under a new
 * name, and removed when it is no longer derived, but only while it is still what Keepfin wrote. A name that holds something Keepfin did
 * not write is never overwritten.
 */
internal object Exports {
    const val EXPORTS_DIR = "Keepfin Exports"
    const val PLAYLISTS_DIR = "Playlists"
    const val RECORD = ".keepfin/exports.json"
    private val AUDIO_NOT_MP3 = setOf("m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "wma", "aiff", "aif", "mka")

    private class Rec(val kind: String, val source: String, val size: Long, val mtimeMs: Long)

    private fun readRecord(copy: FolderAccess): LinkedHashMap<String, Rec> {
        val out = LinkedHashMap<String, Rec>()
        try {
            val o = JSONObject(copy.read(RECORD).toString(Charsets.UTF_8))
            val files = o.optJSONObject("files") ?: return out
            for (k in files.keys()) {
                val r = files.getJSONObject(k)
                out[k] = Rec(r.getString("kind"), r.getString("source"), r.optLong("size"), r.optLong("mtimeMs"))
            }
        } catch (e: FolderError.NotFound) {
            // none yet
        } catch (e: org.json.JSONException) {
            // unreadable: treated as none
        }
        return out
    }

    private fun writeRecord(copy: FolderAccess, files: Map<String, Rec>) {
        val f = JSONObject()
        for ((k, r) in files) f.put(k, JSONObject().put("kind", r.kind).put("source", r.source).put("size", r.size).put("mtimeMs", r.mtimeMs))
        copy.mkdirs(".keepfin")
        copy.replace(RECORD, JSONObject().put("version", 1).put("files", f).toString().toByteArray(Charsets.UTF_8))
    }

    /** The paths of every derived file the copy records. */
    fun recordedPaths(copy: FolderAccess): Set<String> = readRecord(copy).keys

    private fun extOf(p: String) = splitExt(p).second.removePrefix(".").lowercase()

    private fun m3u8(name: String, rows: List<Pair<String, String>>): String {
        val sb = StringBuilder("#EXTM3U\n#PLAYLIST:$name\n")
        for ((title, rel) in rows) sb.append("#EXTINF:-1,").append(title.replace(Regex("[\r\n]+"), " ")).append('\n').append("../").append(rel).append('\n')
        return sb.toString()
    }

    class Job(
        val copy: FolderAccess,
        val names: NameMap,
        val holds: Holds,
        val options: PlaceOptions,
        /** The entries the place holds (the desired set). */
        val entries: List<DesiredEntry>,
        val meta: (String) -> ExportItem,
        val playlists: () -> List<ExportPlaylist>,
        val converter: Mp3Converter?,
        val tmpDir: File,
        val abort: () -> Boolean = { false },
    )

    fun sync(job: Job): ExportsReport {
        val report = ExportsReport()
        val opts = job.options
        val record = readRecord(job.copy)
        val entries = job.entries.sortedBy { it.path }
        // only what the copy really holds, at the size it should have: a file still being copied or edited is not exported yet
        val held = LinkedHashMap<String, DesiredEntry>()
        for (e in entries) {
            val st = job.copy.stat(job.names.physical(e.path))
            if (st != null && !st.isDirectory && st.size == e.bytes && e.itemId !in held) held[e.itemId] = e
        }

        class Want(val kind: String, val source: String, val make: (String) -> Unit)

        val wants = LinkedHashMap<String, Want>()
        val taken = HashSet<String>()
        fun claim(wanted: String): String {
            var n = 1
            var candidate = wanted
            while (candidate.lowercase() in taken) candidate = withCounter(wanted, ++n)
            taken.add(candidate.lowercase())
            return candidate
        }

        if (opts.playlistFiles) {
            for (pl in job.playlists().filter { job.holds.all || it.id in job.holds.playlists }) {
                val name = pl.name.trim().ifEmpty { "Playlist" }
                val rows = pl.itemIds.mapNotNull { held[it] }.map { job.meta(it.itemId).title to it.path }
                val rel = claim("$PLAYLISTS_DIR/${safeComponent(name)}.m3u8")
                val text = m3u8(name, rows)
                wants[rel] = Want("playlist", sha256Text(text)) { dest -> job.copy.publish(dest, text.toByteArray(Charsets.UTF_8)) }
            }
        }
        if (opts.flat) {
            for (e in held.values) {
                val rel = claim("$EXPORTS_DIR/Flat/${safeComponent(leafName(e.path))}")
                wants[rel] = Want("flat", e.contentId) { dest -> job.copy.publishFrom(dest, GuardedInput(job.copy.openRead(job.names.physical(e.path)), job.abort)) }
            }
        }
        if (opts.mp3) {
            for (e in held.values) {
                val m = job.meta(e.itemId)
                val ext = extOf(e.path)
                if (ext !in AUDIO_NOT_MP3 || (m.media.isNotEmpty() && m.media != "audio")) continue
                val stem = splitExt(e.path).first.split('/').joinToString("/") { safeComponent(it) }
                val rel = claim("$EXPORTS_DIR/MP3/$stem.mp3")
                wants[rel] = Want("mp3", e.contentId) { dest ->
                    val converter = job.converter ?: throw IllegalStateException("No audio converter is available.")
                    job.tmpDir.mkdirs()
                    val src = File(job.tmpDir, "export-src-${System.nanoTime()}.${ext}")
                    val out = File(job.tmpDir, "export-out-${System.nanoTime()}.mp3")
                    try {
                        job.copy.openRead(job.names.physical(e.path)).use { i -> FileOutputStream(src).use { o -> i.copyTo(o) } }
                        converter.convert(src, out, job.abort)
                        FileInputStream(out).use { job.copy.publishFrom(dest, it) }
                    } finally {
                        src.delete()
                        out.delete()
                    }
                }
            }
        }

        // 1. the record first: everything about to be written is listed before it exists
        val next = LinkedHashMap(record)
        data class Todo(val rel: String, val want: Want)

        val todo = ArrayList<Todo>()
        val reuse = HashMap<String, String>() // source -> existing recorded path that still has it (a rename keeps the converted bytes)
        for ((rel, r) in record) if (r.kind != "playlist") reuse["${r.kind}|${r.source}"] = rel
        for ((rel, want) in wants) {
            val have = record[rel]
            val st = job.copy.stat(rel)?.takeIf { !it.isDirectory }
            if (have != null && have.kind == want.kind && have.source == want.source && st != null && st.size == have.size) continue
            if (have == null && st != null) {
                report.skipped.add(rel to "A file that Keepfin did not make is already there.")
                continue
            }
            if (have != null && st != null && (st.size != have.size || Math.abs(st.modifiedMs - have.mtimeMs) > 2000) && have.source != want.source) {
                // the old export was changed by someone: leave it, the new one cannot take its name
                report.skipped.add(rel to "The export was changed by hand, so it was left alone.")
                continue
            }
            todo.add(Todo(rel, want))
            next[rel] = Rec(want.kind, want.source, have?.size ?: 0, have?.mtimeMs ?: 0)
        }
        if (todo.isNotEmpty()) writeRecord(job.copy, next)

        // 2. make them
        for ((rel, want) in todo) {
            if (job.abort()) break
            try {
                job.copy.mkdirs(parentPath(rel))
                val old = record[rel]
                if (old != null && job.copy.stat(rel) != null) job.copy.remove(rel) // our own earlier export, replaced by its new version
                val prior = if (want.kind != "playlist") reuse["${want.kind}|${want.source}"] else null
                if (prior != null && prior != rel && prior !in wants && job.copy.stat(prior) != null) {
                    job.copy.mkdirs(parentPath(rel))
                    // the same content under a new name: no second encoding. A rename cannot cross folders, so it is a verified copy then a removal.
                    Versions.moveVerified(job.copy, prior, rel, null, job.abort)
                    next.remove(prior)
                    reuse.remove("${want.kind}|${want.source}")
                    report.moved++
                } else {
                    want.make(rel)
                    report.written++
                    if (want.kind == "mp3") report.converted++
                }
                val st = job.copy.stat(rel)
                next[rel] = Rec(want.kind, want.source, st?.size ?: 0, st?.modifiedMs ?: 0)
            } catch (e: SkipError) {
                if (e.reason == "paused") break
                next.remove(rel)
                report.skipped.add(rel to (e.message ?: e.reason))
            } catch (e: Exception) {
                next.remove(rel)
                report.skipped.add(rel to (e.message ?: e.javaClass.simpleName))
            }
        }

        // 3. no longer derived: removed while it is still what Keepfin wrote
        for ((rel, r) in record) {
            if (rel in wants || rel !in next) continue
            val st = job.copy.stat(rel)?.takeIf { !it.isDirectory }
            if (st != null && st.size == r.size && Math.abs(st.modifiedMs - r.mtimeMs) <= 2000) {
                job.copy.remove(rel)
                report.removed++
            }
            next.remove(rel)
        }
        val same = next.keys == record.keys && next.all { (k, v) -> record[k]?.let { it.kind == v.kind && it.source == v.source && it.size == v.size && it.mtimeMs == v.mtimeMs } == true }
        if (!same) writeRecord(job.copy, next)
        return report
    }
}
