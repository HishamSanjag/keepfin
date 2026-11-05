package app.keepfin.copies

import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import org.json.JSONObject
import java.time.Instant

/** Earlier versions and the trash of a copy (spec 5.5). Nothing is deleted outright: displaced bytes move aside first. */
internal enum class Store(val wire: String) {
    VERSIONS("versions"),
    TRASH("trash"),
}

internal const val TRASH_DAYS = 30
private const val DAY = 86_400_000L

internal data class CatalogueEntry(
    val id: String,
    val store: Store,
    val itemId: String?,
    val revision: String?,
    /** The library path the bytes had. */
    val path: String,
    val contentId: String,
    /** Relative to the copy's `.keepfin`. */
    val file: String,
    val archivedAt: Long,
    /** replaced, restored, edited, removed, deleted */
    val why: String,
    val run: String,
) {
    fun json(): JSONObject = JSONObject().put("id", id).put("store", store.wire).put("itemId", itemId ?: JSONObject.NULL).put("revision", revision ?: JSONObject.NULL)
        .put("path", path).put("contentId", contentId).put("file", file).put("archivedAt", archivedAt).put("why", why).put("run", run)

    companion object {
        fun of(o: JSONObject): CatalogueEntry = CatalogueEntry(
            o.getString("id"), if (o.optString("store") == "trash") Store.TRASH else Store.VERSIONS,
            if (o.isNull("itemId")) null else o.getString("itemId"), if (o.isNull("revision")) null else o.getString("revision"),
            o.getString("path"), o.getString("contentId"), o.getString("file"), o.optLong("archivedAt"), o.optString("why"), o.optString("run"),
        )
    }
}

internal fun catalogueFile(store: Store): String = if (store == Store.VERSIONS) ".keepfin/versions/catalogue.jsonl" else ".keepfin/trash/index.jsonl"

internal class ArchiveInfo(
    val itemId: String?,
    val revision: String?,
    val contentId: String,
    /** library path */
    val path: String,
    /** physical relative path on the copy (used for the trash layout) */
    val physical: String,
    val run: String,
    val n: Int,
    val at: Long,
    val why: String,
)

internal object Versions {
    /** Chooses the archive file (never an existing one) and the catalogue entry for it. Nothing is written yet. */
    fun planArchive(access: FolderAccess, store: Store, info: ArchiveInfo): CatalogueEntry {
        val rel = if (store == Store.VERSIONS) {
            val (_, ext) = splitExt(info.physical)
            val prefix = info.contentId.removePrefix(CONTENT_PREFIX).take(12)
            "versions/${safeComponent(info.itemId ?: "_unknown")}/${safeComponent(info.revision ?: "copy")}-$prefix$ext"
        } else {
            val day = Instant.ofEpochMilli(info.at).toString().take(10)
            "trash/$day/${info.run}-${info.n}/${info.physical}"
        }
        var n = 1
        var candidate = rel
        while (access.stat(".keepfin/$candidate") != null) {
            n++
            candidate = withCounter(rel, n)
        }
        return CatalogueEntry("${info.run}-${info.n}", store, info.itemId, info.revision, info.path, info.contentId, candidate, info.at, info.why, info.run)
    }

    /**
     * Moves [from] to [to] (both on [access]) without ever replacing anything: the bytes are copied, their hash must equal [expected]
     * (the precondition, checked on the bytes that are kept), the source must not have changed meanwhile, and only then is it removed.
     * A rename cannot cross folders through the document provider, and a crash in between leaves a duplicate that recovery settles.
     */
    fun moveVerified(access: FolderAccess, from: String, to: String, expected: String?, abort: () -> Boolean = { false }): String {
        // a cloud account moves the object on its own side (no bytes are sent again)
        if (access is ServerSideMove) return access.moveVerified(from, to, expected, abort)
        access.mkdirs(parentPath(to))
        if (access.stat(to) != null) throw SkipError("busy", "The archive name was taken.")
        val before = access.stat(from) ?: throw SkipError("copy-file-missing", "$from is no longer there.")
        val result = try {
            access.publishFrom(to, GuardedInput(access.openRead(from), abort))
        } catch (e: FolderError.NameConflict) {
            throw SkipError("busy", "The archive name was taken.")
        } catch (e: FolderError.NotFound) {
            throw SkipError("copy-file-missing", "$from is no longer there.")
        }
        val id = CONTENT_PREFIX + result.sha256
        val after = access.stat(from)
        if ((expected != null && id != expected) || after == null || after.size != before.size || after.modifiedMs != before.modifiedMs) {
            runCatching { access.remove(to) }
            throw SkipError("unexpected-destination", "$from changed since it was planned.")
        }
        access.remove(from)
        return id
    }

    // ---- the catalogue ----

    private fun readText(access: FolderAccess, file: String): String = try {
        access.read(file).toString(Charsets.UTF_8)
    } catch (e: FolderError.NotFound) {
        ""
    }

    /** The live entries of a catalogue (voided and expired ones removed). */
    fun liveEntries(access: FolderAccess, store: Store): List<CatalogueEntry> {
        val dead = HashSet<String>()
        val all = ArrayList<CatalogueEntry>()
        for (line in readText(access, catalogueFile(store)).split('\n')) {
            if (line.isBlank()) continue
            try {
                val o = JSONObject(line)
                when {
                    o.has("void") -> dead.add(o.getString("void"))
                    o.has("gone") -> dead.add(o.getString("gone"))
                    o.has("id") && o.has("file") -> all.add(CatalogueEntry.of(o))
                }
            } catch (e: Exception) {
                // a torn tail
            }
        }
        return all.filter { it.id !in dead }
    }

    private fun expire(access: FolderAccess, store: Store, due: (CatalogueEntry) -> Boolean): Int {
        val gone = ArrayList<String>()
        for (e in liveEntries(access, store)) {
            if (!due(e)) continue
            try {
                access.remove(".keepfin/${e.file}")
            } catch (err: FolderError.NotFound) {
                // already gone
            }
            gone.add(e.id)
        }
        if (gone.isNotEmpty()) {
            val w = CatalogueWriter(access, store)
            for (id in gone) w.gone(id)
            w.flush()
        }
        return gone.size
    }

    /** The one place bytes are removed for good: trash older than 30 days. */
    fun purgeTrash(access: FolderAccess, now: Long, days: Int = TRASH_DAYS): Int = expire(access, Store.TRASH) { now - it.archivedAt >= days * DAY }

    /** Replaced versions older than [keepDays]. Deleted items are kept until an explicit Clean up, and unresolved conflicts never expire. */
    fun pruneVersions(access: FolderAccess, now: Long, keepDays: Int = 90): Int =
        expire(access, Store.VERSIONS) { it.why == "replaced" && now - it.archivedAt >= keepDays * DAY }

    /** An explicit Clean up of everything archived for items that were deleted. */
    fun cleanUpDeleted(access: FolderAccess): Int = expire(access, Store.VERSIONS) { it.why == "deleted" || it.why == "removed" }
}

/**
 * Appends to a catalogue in batches: a copy offers no append, so every flush rewrites the file. The durable record of an archive
 * is the run's journal (it carries the whole entry); recovery re-appends an entry the flush did not reach.
 */
internal class CatalogueWriter(private val access: FolderAccess, private val store: Store) {
    private val pending = ArrayList<String>()

    @Synchronized
    fun append(entry: CatalogueEntry) {
        pending.add(entry.json().toString())
    }

    @Synchronized
    fun void(id: String) {
        pending.add(JSONObject().put("void", id).toString())
    }

    @Synchronized
    fun gone(id: String) {
        pending.add(JSONObject().put("gone", id).toString())
    }

    @Synchronized
    fun pendingCount(): Int = pending.size

    @Synchronized
    fun flush() {
        if (pending.isEmpty()) return
        val file = catalogueFile(store)
        val old = try {
            access.read(file).toString(Charsets.UTF_8)
        } catch (e: FolderError.NotFound) {
            ""
        }
        val sb = StringBuilder(old)
        if (old.isNotEmpty() && !old.endsWith("\n")) sb.append('\n')
        for (l in pending) sb.append(l).append('\n')
        access.mkdirs(parentPath(file))
        access.replace(file, sb.toString().toByteArray(Charsets.UTF_8))
        pending.clear()
    }
}
