package app.keepfin.setup

import app.keepfin.LibraryCodec
import app.keepfin.LibraryCollection
import app.keepfin.core.library.LibCollection
import app.keepfin.core.library.LibItem
import app.keepfin.core.library.LibRoutes
import app.keepfin.core.library.LibSource
import app.keepfin.core.library.LibSpace
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import app.keepfin.library.repo.LibraryRepository
import app.keepfin.library.repo.LinkedLocator
import app.keepfin.library.repo.toLib
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest

/** A subtitle or lyrics file next to an earlier item; [suffix] is what follows the media file's name stem (".en.vtt"). */
internal class LegacySidecar(val uri: String, val suffix: String)

internal enum class DeleteOutcome { DELETED, GONE, NEEDS_CONSENT, FAILED }

/** The earlier files (MediaStore on a phone). Everything here may be called from a worker thread. */
internal interface LegacyFiles {
    /** The size now, or null when the file is gone. */
    fun size(uri: String): Long?
    fun open(uri: String): InputStream
    fun sidecars(rec: LibraryCodec.Rec): List<LegacySidecar>
    fun delete(uri: String): DeleteOutcome
    fun exists(uri: String): Boolean = size(uri) != null
    fun openArt(path: String): InputStream?

    /** Where the file is in MediaStore now (relative path, size, modified time): the address a linked file is found by on this phone. Null when unknown. */
    fun locate(uri: String): LinkedLocator? = null
}

/** The new library (the repository on a phone). */
internal interface MigrationTarget {
    val folder: FolderAccess
    fun layout(spaces: List<LibSpace>, routes: LibRoutes)
    fun items(batch: List<LibraryRepository.MigratedItem>): Int
    fun collections(list: List<LibCollection>)
    /** The address of a file in the Keepfin folder, or null when unknown. */
    fun uriFor(path: String): String? = null
    /** The id of the item this library already has for the file at [path] with [contentId] (an earlier setup into the same folder), or null. */
    fun existingAt(path: String, contentId: String): String? = null
}

internal class RepositoryTarget(private val repo: LibraryRepository, override val folder: FolderAccess) : MigrationTarget {
    override fun layout(spaces: List<LibSpace>, routes: LibRoutes) = repo.migrateLayout(spaces, routes)
    override fun items(batch: List<LibraryRepository.MigratedItem>): Int = repo.migrateItems(batch)
    override fun collections(list: List<LibCollection>) = repo.migrateCollections(list)
    override fun uriFor(path: String): String? = (folder as? app.keepfin.folder.SafFolderAccess)?.documentUriFor(path)?.toString()
    override fun existingAt(path: String, contentId: String): String? = repo.itemWithContentAt(path, contentId)
}

internal enum class StopReason { NO_SPACE, NEEDS_ACCESS, UNAVAILABLE }

internal class ConsentBatch(val ids: List<String>)

internal class Progress(val phase: String, val done: Int, val total: Int, val bytesDone: Long, val bytesTotal: Long, val current: String?)

internal class ReportEntry(val title: String, val reason: String)

internal class MigrationReport(
    val runId: String,
    val moved: Int,
    val linked: Int,
    val failed: List<ReportEntry>,
    val kept: List<ReportEntry>,
    val bytesCopied: Long,
    val sidecarsCopied: Int,
    val sidecarsFailed: Int,
    val spaces: List<PlanSpace>,
    val playlists: Int,
    val downloaded: Int,
    val favourites: Int,
) {
    fun toText(): String = buildString {
        appendLine("Keepfin setup report")
        appendLine("Moved into the Keepfin folder: $moved")
        appendLine("Left where they are (linked): $linked")
        appendLine("Playlists and collections brought over: ${playlists + downloaded}")
        appendLine("Favourites brought over: $favourites")
        if (failed.isNotEmpty()) { appendLine("Not brought over: ${failed.size}"); failed.forEach { appendLine("  ${it.title}: ${it.reason}") } }
        if (kept.isNotEmpty()) { appendLine("Originals left in place: ${kept.size}"); kept.forEach { appendLine("  ${it.title}: ${it.reason}") } }
    }

    fun toJson(): String {
        val o = org.json.JSONObject().put("runId", runId).put("moved", moved).put("linked", linked).put("bytesCopied", bytesCopied)
            .put("sidecarsCopied", sidecarsCopied).put("sidecarsFailed", sidecarsFailed).put("playlists", playlists).put("downloaded", downloaded).put("favourites", favourites)
        fun entries(list: List<ReportEntry>) = org.json.JSONArray().also { a -> list.forEach { a.put(org.json.JSONObject().put("title", it.title).put("reason", it.reason)) } }
        return o.put("failed", entries(failed)).put("kept", entries(kept)).toString(2)
    }
}

internal sealed class RunOutcome {
    /** Stopped on request (time limit, cancel); the next run continues from the journal. */
    data object Paused : RunOutcome()

    /** Stopped by a condition of the Keepfin folder; nothing is lost and the next run continues. */
    class Stopped(val reason: StopReason, val message: String) : RunOutcome()

    /** Everything is in the library; these originals wait for the user's yes. */
    class NeedsConsent(val batches: List<ConsentBatch>) : RunOutcome()

    class Completed(val report: MigrationReport) : RunOutcome()
}

private class PauseRun : RuntimeException()
private class StopRun(val reason: StopReason, message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Raises when a stop was requested while a large file is being read, so the copy ends (and its staged file is removed). */
private class StoppableStream(source: InputStream, private val shouldStop: () -> Boolean) : FilterInputStream(source) {
    override fun read(): Int { if (shouldStop()) throw PauseRun(); return super.read() }
    override fun read(b: ByteArray, off: Int, len: Int): Int { if (shouldStop()) throw PauseRun(); return super.read(b, off, len) }
}

/**
 * Moves an earlier library into the Keepfin folder (dev-docs/plans/KEEPFIN-FOLDER-ANDROID-SPEC.md section 5). Every step is journaled
 * before the next action, so a stop at any point resumes without loss or duplicates:
 *
 * 1. layout (spaces, routes), then per item: copy (staged, fsynced, read back, hashed) -> COPIED, then commit in batches -> COMMITTED;
 * 2. collections, then artwork;
 * 3. originals are removed only after their copy is verified and committed, and each is checked again just before: owned rows directly,
 *    the others through [RunOutcome.NeedsConsent] batches of at most [BATCH] files. A refusal leaves the originals and lists them.
 *
 * Leave spaces are linked: their items are committed with a linked placement and no file is touched.
 */
internal class MigrationRunner(
    private val plan: MigrationPlan,
    private val inventory: LegacyInventory,
    private val files: LegacyFiles,
    private val target: MigrationTarget,
    private val journal: MigrationJournal,
    private val shouldStop: () -> Boolean = { false },
    private val onProgress: (Progress) -> Unit = {},
    private val commitEvery: Int = 25,
    private val consentBatch: Int = BATCH,
    /** A moved file is in its new place: (old uri, new document uri, path in the Keepfin folder). The download history follows it. */
    private val relocated: (String, String, String) -> Unit = { _, _, _ -> },
) {
    companion object {
        const val BATCH = 1000
        private val STAGING = Regex("^(.+)\\.[0-9a-f]{8}\\.tmp$")
    }

    private val fold get() = journal.fold
    private val recs: Map<String, LibraryCodec.Rec> = inventory.items.associateBy { it.item.id }
    private val titles: Map<String, String> = inventory.items.associate { it.item.id to it.item.title }
    private val totalBytes = plan.bytesToCopy
    private var bytesDone = 0L
    private var done = 0

    private fun progress(phase: String, current: String? = null) = onProgress(Progress(phase, done, plan.items.size, bytesDone, totalBytes, current))

    fun run(): RunOutcome {
        done = plan.items.count { fold.stateOf(it.oldId) != ItemState.PLANNED }
        bytesDone = plan.items.sumOf { if (fold.stateOf(it.oldId) != ItemState.PLANNED && it.choice == Choice.MOVE) fold.files[it.oldId]?.size ?: 0L else 0L }
        if (!fold.run.started || fold.run.stopped != null) journal.run("started")
        sweepStaleStaging()
        try {
            if (!fold.run.layout) {
                target.layout(layoutSpaces(), LibRoutes(inventory.settings.musicRoute, inventory.settings.otherRoute))
                journal.run("layout")
            }
            if (copyAndCommit()) { sweepStaleStaging(); return RunOutcome.Paused }
            if (!fold.run.collections) {
                target.collections(mappedCollections())
                journal.run("collections")
            }
            if (!fold.run.committed) journal.run("committed")
            if (!fold.run.art) { copyArt(); journal.run("art") }
            reconcile()
            if (removeOriginals()) return RunOutcome.Paused
            val waiting = consentIds()
            if (waiting.isNotEmpty()) return RunOutcome.NeedsConsent(waiting.chunked(consentBatch).map { ConsentBatch(it) })
            if (!fold.run.done) journal.run("done")
            sweepStaleStaging()
            return RunOutcome.Completed(report())
        } catch (e: PauseRun) {
            return RunOutcome.Paused
        } catch (e: StopRun) {
            journal.run("stopped", e.reason.name)
            return RunOutcome.Stopped(e.reason, e.message ?: "")
        } catch (e: FolderError) {
            val stop = stopOf(e) ?: throw e
            journal.run("stopped", stop.reason.name)
            return RunOutcome.Stopped(stop.reason, stop.message ?: "")
        }
    }

    private fun stopOf(e: FolderError): StopRun? = when (e) {
        is FolderError.DiskFull -> StopRun(StopReason.NO_SPACE, "The Keepfin folder is full.", e)
        is FolderError.NeedsAccess -> StopRun(StopReason.NEEDS_ACCESS, "Keepfin needs access to its folder again.", e)
        is FolderError.Unavailable -> StopRun(StopReason.UNAVAILABLE, "The Keepfin folder is not available. Is the memory card back in?", e)
        else -> null
    }

    // ------------------------------------------------------------------------------------------------ layout

    private fun layoutSpaces(): List<LibSpace> {
        val byId = plan.spaces.associateBy { it.id }
        return inventory.settings.spaces.map { s ->
            val planned = byId[s.id]
            if (planned == null) s.toLib() else s.toLib().copy(folder = planned.newFolder, linked = planned.choice == Choice.LEAVE)
        }
    }

    // ------------------------------------------------------------------------------------------------ copy and commit

    private fun copyAndCommit(): Boolean {
        val pending = ArrayList<PlanItem>()
        for (p in plan.items) {
            when (fold.stateOf(p.oldId)) {
                ItemState.DONE, ItemState.FAILED, ItemState.COMMITTED, ItemState.REMOVABLE -> continue
                ItemState.COPIED -> {
                    val f = fold.files.getValue(p.oldId)
                    val there = f.dest?.let { target.folder.stat(it) }
                    if (there == null || there.size != f.size) {
                        // the copy was lost (a card swap, a cleaned folder): copy again
                        if (!copyOne(p)) { if (shouldStop()) { flush(pending); return true }; continue }
                    }
                }
                ItemState.PLANNED -> if (p.choice == Choice.MOVE) {
                    if (!copyOne(p)) { if (shouldStop()) { flush(pending); return true }; continue }
                }
            }
            pending += p
            if (pending.size >= commitEvery) flush(pending)
            done++
            progress("copying", p.name)
            if (shouldStop()) { flush(pending); return true }
        }
        flush(pending)
        return false
    }

    private fun flush(pending: MutableList<PlanItem>) {
        if (pending.isEmpty()) return
        val batch = pending.map { migrated(it) }
        target.items(batch)
        journal.committed(pending.map { it.oldId })
        pending.forEach { relocate(it) }
        pending.clear()
        progress("committing")
    }

    /** Points the history rows that named the original at the copy. Safe to repeat. */
    private fun relocate(p: PlanItem) {
        if (p.choice != Choice.MOVE) return
        val dest = fold.files[p.oldId]?.dest ?: p.dest
        val uri = try { target.uriFor(dest) } catch (e: Exception) { null } ?: return
        try { relocated(p.oldId, uri, dest) } catch (e: Exception) { /* the history is repaired when the setup ends */ }
    }

    private fun migrated(p: PlanItem): LibraryRepository.MigratedItem {
        val rec = recs.getValue(p.oldId)
        val f = fold.files[p.oldId]
        val place = if (p.choice == Choice.MOVE) f?.dest ?: p.dest else p.dest
        val size = if (p.choice == Choice.MOVE) f?.size ?: p.size else p.size
        val locator = if (p.choice == Choice.LEAVE) locatorOf(rec, p) else null
        val item = MigrationMapping.libItem(rec, p, place)
        // the folder's library already lists this file: its record stays (the library skips ids it has), nothing is added twice
        val reused = reusedId(p)
        return LibraryRepository.MigratedItem(if (reused != null) item.copy(id = reused) else item, f?.sha?.let { "sha256:$it" }, size, locator)
    }

    private val reused = HashMap<String, String?>()

    /** The record an earlier setup into the same folder made for this very file (same place, same content), or null. */
    private fun reusedId(p: PlanItem): String? {
        if (p.choice != Choice.MOVE) return null
        val f = fold.files[p.oldId] ?: return null
        val dest = f.dest ?: return null
        val sha = f.sha ?: return null
        // "none" is remembered too (getOrPut would ask again for a null): once this run has added its own record for the file, a
        // second look would find that record and take it for an earlier one (and its artwork would be skipped)
        if (reused.containsKey(p.oldId)) return reused[p.oldId]
        val found = try { target.existingAt(dest, "sha256:$sha") } catch (e: Exception) { null }
        return found?.takeIf { it != p.newId }.also { reused[p.oldId] = it }
    }

    /** The id the item has in the new library: its new id, or the record that was already there. */
    private fun idOf(p: PlanItem): String = reusedId(p) ?: p.newId

    /** The address of a left-in-place file on this phone: what MediaStore says now, else what the earlier library knew. */
    private fun locatorOf(rec: LibraryCodec.Rec, p: PlanItem): LinkedLocator {
        val now = try { files.locate(p.oldId) } catch (e: Exception) { null }
        val uri = rec.item.uri.ifBlank { p.oldId }
        return (now ?: LinkedLocator(uri, "", p.size, rec.item.modifiedAt.let { if (it > 100_000_000_000L) it / 1000 else it })).copy(uri = now?.uri ?: uri, owned = p.owned)
    }

    private fun free(wanted: String): String {
        val dir = wanted.substringBeforeLast('/', "")
        val name = wanted.substringAfterLast('/')
        val stem = name.substringBeforeLast('.', name)
        val ext = if ('.' in name) "." + name.substringAfterLast('.') else ""
        var candidate = wanted
        var n = 2
        while (target.folder.stat(candidate) != null) {
            val next = "$stem ($n)$ext"
            candidate = if (dir.isEmpty()) next else "$dir/$next"
            n++
        }
        return candidate
    }

    private fun sha256(stream: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        stream.use { while (true) { val n = it.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Copies one item. Returns false when it failed (journaled) or a stop was requested. A stop for the folder's sake throws [StopRun]. */
    private fun copyOne(p: PlanItem): Boolean {
        val id = p.oldId
        val rec = recs.getValue(id)
        val sourceSize = try { files.size(id) } catch (e: SecurityException) {
            journal.failed(id, "Keepfin is not allowed to read this file. Allow access to music and videos, then continue.", "denied"); return false
        }
        if (sourceSize == null) { journal.failed(id, "The file is no longer on this phone.", "gone"); return false }
        var attempt = 0
        while (true) {
            attempt++
            val previous = fold.files[id]?.intent
            val dest: String
            if (previous == null) {
                // an earlier setup into this folder may have put the same file there: the check below uses it when the bytes match,
                // instead of a second copy as "name (2)"
                val there = target.folder.stat(p.dest)
                if (there != null && there.size == sourceSize) { journal.intent(id, p.dest); continue }
                dest = free(p.dest)
                journal.intent(id, dest)
            } else {
                dest = previous
                // a finished copy may be there when the stop came between the copy and its journal line (a copy is staged and
                // renamed, so a file at the name is either complete or not ours)
                val there = target.folder.stat(dest)
                if (there != null) {
                    val destSha = if (there.size == sourceSize) sha256(target.folder.openRead(dest)) else null
                    if (destSha != null && sha256(files.open(id)) == destSha) {
                        val extras = copySidecars(rec, dest)
                        journal.copied(id, dest, destSha, sourceSize, extras.first, extras.second)
                        bytesDone += sourceSize
                        return true
                    }
                    journal.intent(id, free(dest))
                    continue
                }
            }
            try {
                target.folder.mkdirs(app.keepfin.folder.parentOf(dest))
                val result = try {
                    StoppableStream(files.open(id), shouldStop).use { target.folder.publishFrom(dest, it) }
                } catch (e: FileNotFoundException) {
                    journal.failed(id, "The file is no longer on this phone.", "gone"); return false
                } catch (e: SecurityException) {
                    journal.failed(id, "Keepfin is not allowed to read this file. Allow access to music and videos, then continue.", "denied"); return false
                }
                val after = files.size(id)
                if (after != result.size) {
                    // the source changed while it was copied: the copy is not the file
                    target.folder.remove(dest)
                    if (attempt < 2) continue
                    journal.failed(id, "The file changed while it was being copied.", "changed"); return false
                }
                val extras = copySidecars(rec, dest)
                journal.copied(id, dest, result.sha256, result.size, extras.first, extras.second)
                bytesDone += result.size
                return true
            } catch (e: PauseRun) {
                throw e
            } catch (e: FolderError) {
                if (shouldStop()) throw PauseRun()
                stopOf(e)?.let { throw it }
                if (e is FolderError.NameConflict && attempt < 6) {
                    // the name was taken since it was planned: take the next free one
                    journal.intent(id, free(dest))
                    continue
                }
                if (attempt < 2) continue
                journal.failed(id, e.message ?: "The copy failed.", "io"); return false
            } catch (e: RuntimeException) {
                if (shouldStop()) throw PauseRun()
                throw e
            }
        }
    }

    /** Copies subtitles and lyrics next to the new file. Best effort; returns the copied sources and the number that failed. */
    private fun copySidecars(rec: LibraryCodec.Rec, dest: String): Pair<List<String>, Int> {
        val list = try { files.sidecars(rec) } catch (e: Exception) { emptyList() }
        if (list.isEmpty()) return emptyList<String>() to 0
        val stem = dest.substringBeforeLast('.', dest)
        val copied = ArrayList<String>()
        var failed = 0
        for (sc in list) {
            val to = stem + sc.suffix
            try {
                val size = files.size(sc.uri)
                val there = target.folder.stat(to)
                if (there != null && there.size == size) { copied += sc.uri; continue }
                if (there != null) target.folder.remove(to)
                StoppableStream(files.open(sc.uri), shouldStop).use { target.folder.publishFrom(to, it) }
                copied += sc.uri
            } catch (e: PauseRun) {
                throw e
            } catch (e: FolderError) {
                stopOf(e)?.let { throw it }
                failed++
            } catch (e: Exception) {
                failed++
            }
        }
        return copied to failed
    }

    // ------------------------------------------------------------------------------------------------ collections and artwork

    private fun mappedCollections(): List<LibCollection> {
        val items = plan.items.filter { fold.stateOf(it.oldId) != ItemState.FAILED }.associate { it.oldId to idOf(it) }
        fun ids(list: List<String>) = list.mapNotNull { items[it] }
        return inventory.collections.map { c ->
            LibCollection(
                id = plan.collectionIds.getValue(c.id), name = c.name, kind = c.kind.wire, space = c.space, itemIds = ids(c.itemIds), seen = c.seen,
                createdAt = c.createdAt.toDouble(), updatedAt = c.updatedAt.toDouble(), coverItemId = c.coverItemId?.let { items[it] },
                source = c.source?.let { LibSource(it.url, it.provider, it.id) }, follow = c.follow?.toLib(),
                newItemIds = ids(c.newItemIds), removedItemIds = ids(c.removedItemIds),
            )
        }
    }

    /** Saved artwork is copied (never moved, so the old library stays restorable). Best effort: art is rebuilt from the files otherwise. */
    private fun copyArt() {
        for (p in plan.items) {
            if (fold.stateOf(p.oldId) == ItemState.FAILED || reusedId(p) != null) continue // a kept record keeps its own artwork
            val art = recs[p.oldId]?.item?.art ?: continue
            val dest = "${app.keepfin.folder.FolderLayout.DEFAULT.art}/${p.newId}.${art.substringAfterLast('.', "jpg").take(5)}"
            try {
                if (target.folder.stat(dest) != null) continue
                files.openArt(art)?.use { target.folder.publishFrom(dest, it) }
            } catch (e: FolderError) {
                stopOf(e)?.let { throw it }
            } catch (e: Exception) {
                // art is optional
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ originals

    private fun reconcile() {
        for ((id, f) in fold.files.entries.toList()) if (f.state == ItemState.REMOVABLE && !files.exists(id)) journal.done(id)
    }

    /** Returns true when a stop was requested. */
    private fun removeOriginals(): Boolean {
        for (p in plan.items) {
            val f = fold.files[p.oldId] ?: continue
            if (f.state != ItemState.COMMITTED) continue
            if (p.choice == Choice.LEAVE) { journal.done(p.oldId); continue }
            val copy = f.dest?.let { target.folder.stat(it) }
            if (copy == null || copy.size != f.size) { journal.done(p.oldId, kept = true, reason = "The copy could not be checked, so the original stays."); continue }
            relocate(p)
            val now = try { files.size(p.oldId) } catch (e: SecurityException) { journal.done(p.oldId, kept = true, reason = "Keepfin is not allowed to read the original, so it stays."); continue }
            if (now == null) { journal.done(p.oldId); continue }
            if (now != f.size) { journal.done(p.oldId, kept = true, reason = "The file changed after it was copied, so the original stays."); continue }
            when (files.delete(p.oldId)) {
                DeleteOutcome.DELETED, DeleteOutcome.GONE -> { removeSidecars(f); journal.done(p.oldId) }
                DeleteOutcome.NEEDS_CONSENT -> journal.removable(p.oldId)
                DeleteOutcome.FAILED -> journal.done(p.oldId, kept = true, reason = "Android did not let Keepfin remove the original.")
            }
            if (shouldStop()) return true
        }
        return false
    }

    private fun removeSidecars(f: ItemFold) {
        for (uri in f.sidecars) try { files.delete(uri) } catch (e: Exception) { /* left behind; harmless */ }
    }

    /** The originals that wait for consent, in plan order. */
    fun consentIds(): List<String> = plan.items.filter { fold.stateOf(it.oldId) == ItemState.REMOVABLE }.map { it.oldId }

    /** Journals that a request for [ids] is about to be shown; returns its number. */
    fun beginConsent(ids: List<String>): Int {
        val n = fold.run.nextBatch
        journal.batch(n, "launched", ids)
        return n
    }

    /**
     * The user answered a request. After a yes every original is checked again (gone, or removed now); after a no, or when Android
     * still refuses, the originals stay and the report lists them.
     */
    fun consentResult(batch: Int, granted: Boolean) {
        val ids = fold.batches[batch]?.ids ?: return
        journal.batch(batch, if (granted) "granted" else "refused")
        for (id in ids) {
            val f = fold.files[id] ?: continue
            if (f.state != ItemState.REMOVABLE) continue
            if (!granted) { journal.done(id, kept = true, reason = "You chose to keep the original."); continue }
            if (!files.exists(id)) { removeSidecars(f); journal.done(id); continue }
            when (files.delete(id)) {
                DeleteOutcome.DELETED, DeleteOutcome.GONE -> { removeSidecars(f); journal.done(id) }
                else -> journal.done(id, kept = true, reason = "Android did not let Keepfin remove the original.")
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ stale staging files

    /**
     * Removes staging files (`<name>.<8 hex>.tmp`) that an interrupted copy left next to its destination. Only the folders this plan writes
     * to are looked at, and only names whose stem is a file (or the stem of a subtitle or lyrics file) of this plan; a user's own
     * `*.tmp` is never touched. Nothing is being written when this runs (start, pause and finish), but [active] paths are skipped anyway.
     */
    internal fun sweepStaleStaging(active: Set<String> = emptySet()): Int {
        val names = HashSet<String>()
        for (p in plan.items) {
            if (p.choice != Choice.MOVE) continue
            names += p.dest
            fold.files[p.oldId]?.let { f -> f.intent?.let { names += it }; f.dest?.let { names += it } }
        }
        val byDir = names.groupBy { app.keepfin.folder.parentOf(it) }
        var removed = 0
        for ((dir, paths) in byDir) {
            val stems = paths.map { app.keepfin.folder.nameOf(it) }
            val bases = stems.map { it.substringBeforeLast('.', it) }
            val entries = try { target.folder.list(dir).entries } catch (e: FolderError) { continue } catch (e: Exception) { continue }
            for (e in entries) {
                if (e.isDirectory) continue
                val m = STAGING.matchEntire(e.name) ?: continue
                val stem = m.groupValues[1]
                val ours = stem in stems || bases.any { stem.startsWith("$it.") }
                if (!ours) continue
                val path = app.keepfin.folder.join(dir, e.name)
                if (path in active) continue
                try {
                    target.folder.remove(path)
                    removed++
                    try { android.util.Log.i("KeepfinSetup", "removed a leftover staging file: $path") } catch (t: Throwable) { /* not on a device */ }
                } catch (x: FolderError) {
                    stopOf(x)?.let { throw it }
                } catch (x: Exception) { /* left for the next run */ }
            }
        }
        return removed
    }

    // ------------------------------------------------------------------------------------------------ report

    fun report(): MigrationReport {
        val failed = ArrayList<ReportEntry>()
        val kept = ArrayList<ReportEntry>()
        var moved = 0
        var linked = 0
        var bytes = 0L
        var copiedSidecars = 0
        var failedSidecars = 0
        for (p in plan.items) {
            val f = fold.files[p.oldId]
            val title = titles[p.oldId] ?: p.name
            when {
                f?.state == ItemState.FAILED -> failed += ReportEntry(title, f.reason ?: "It could not be brought over.")
                f != null && (f.state == ItemState.COMMITTED || f.state == ItemState.REMOVABLE || f.state == ItemState.DONE) -> {
                    if (p.choice == Choice.LEAVE) linked++ else { moved++; bytes += f.size; copiedSidecars += f.sidecars.size; failedSidecars += f.sidecarsFailed }
                    if (f.kept) kept += ReportEntry(title, f.reason ?: "The original stays.")
                }
            }
        }
        return MigrationReport(plan.runId, moved, linked, failed, kept, bytes, copiedSidecars, failedSidecars, plan.spaces, inventory.playlists, inventory.downloaded, inventory.favourites)
    }
}

internal object MigrationMapping {
    private fun d(v: Int?): Double? = v?.toDouble()

    /** The new item for an earlier one. Numbers keep their value; empty lists and false flags are left out like the shared mapping expects. */
    fun libItem(rec: LibraryCodec.Rec, plan: PlanItem, place: String): LibItem {
        val i = rec.item
        return LibItem(
            id = plan.newId, space = plan.space, kind = i.kind.wire, title = i.title, artist = i.artist, album = i.album, albumArtist = i.albumArtist,
            track = d(i.track), disc = d(i.disc), year = d(i.year), genre = i.genre, duration = i.duration, width = d(i.width), height = d(i.height),
            gain = i.gain, hasLyrics = if (i.hasLyrics) true else null, sourceUrl = i.sourceUrl, sourceId = i.sourceId,
            addedAt = i.addedAt.takeIf { it > 0 }?.toDouble(), tags = i.tags.takeIf { it.isNotEmpty() },
            lastPlayedAt = i.lastPlayedAt?.toDouble(), position = i.position, watched = if (i.watched) true else null,
            favorite = i.favorite, userTags = i.userTags, plays = i.plays.takeIf { it > 0 }?.toDouble(), place = place,
        )
    }
}
