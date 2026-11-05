package app.keepfin.merge

import app.keepfin.core.library.LibItem
import app.keepfin.core.library.LibraryMapping
import app.keepfin.core.merge.ArtFile
import app.keepfin.core.merge.Batch
import app.keepfin.core.merge.FileEvidence
import app.keepfin.core.merge.MergeConfig
import app.keepfin.core.merge.MergePlanner
import app.keepfin.core.merge.MergeWire
import app.keepfin.core.merge.NeedsHash
import app.keepfin.core.merge.SItem
import app.keepfin.core.merge.remainingDrafts
import app.keepfin.core.sync.Hlc
import app.keepfin.core.sync.JObj
import app.keepfin.core.sync.JStr
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import app.keepfin.folder.FolderLayout
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.OpDraft
import app.keepfin.folder.StoreOptions
import app.keepfin.folder.sql.SqlMergeState
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** The merge was stopped on request (the person paused it, or the app is closing): nothing is lost, it resumes from its journal. */
class MergePaused : RuntimeException("paused")

/** The folder cannot be reached right now (access revoked, card removed): recovery waits and never rolls anything back. */
class WaitingForFolder(cause: Throwable?) : RuntimeException("Waiting for the Keepfin folder", cause)

/** Something makes the merge unsafe to continue (the library changed under it, a plan file does not match its receipt). */
class MergeFailure(message: String) : RuntimeException(message)

/** T on this device while it is being staged: its own store, state and reader on the records root below `.keepfin/libraries/<id>`. */
class Staged(val store: KeepfinStore, val state: SqlMergeState, val reader: LibraryReader, val layout: FolderLayout, val replicaId: String, val actor: String) {
    fun close() { runCatching { store.close().get(60, TimeUnit.SECONDS) } }
}

object Staging {
    /** Opens (replays) the staged library of [j]. The state lives in the device directory of the profile T will get. */
    fun open(env: MergeEnv, folder: FolderAccess, j: MergeJournal, onRotate: (String, String) -> Unit = { _, _ -> }): Staged {
        val replica = j.tReplicaId ?: throw MergeFailure("T has not been joined yet.")
        val actor = j.tActor ?: throw MergeFailure("T has not been joined yet.")
        return openStore(env, folder, j.tLibraryId, replica, actor, env.profiles.deviceDir(env.filesDir, j.tProfileId), FolderLayout.forLibrary(j.tLibraryId), onRotate)
    }

    /** Opens any library of the folder on its own store: T while it is staged, S while Separate again writes back into it. */
    fun openStore(
        env: MergeEnv, folder: FolderAccess, libraryId: String, replica: String, actor: String, deviceDir: File, layout: FolderLayout,
        onRotate: (String, String) -> Unit = { _, _ -> },
    ): Staged {
        val signerId = env.signer?.let { app.keepfin.core.net.deviceIdOf(it.spki) }
        val state = SqlMergeState(File(deviceDir, "state/state.db").path, libraryId, replica, signerId, env.sqlHost)
        val store = try {
            KeepfinStore.open(
                StoreOptions(
                    folder = folder, libraryId = libraryId, actor = actor, deviceDir = deviceDir, outboxDir = File(deviceDir, "outbox"), newId = env::newId,
                    now = env::now, state = state, flushIdleMs = 500, appVersion = env.appVersion, deviceName = env.deviceName, onRotate = onRotate,
                    signer = env.signer, layout = layout,
                ),
            ).get(10, TimeUnit.MINUTES)
        } catch (e: Throwable) {
            runCatching { state.close() }
            throw e
        }
        return Staged(store, state, LibraryReader(store, state, replica), layout, replica, store.actorId)
    }
}

private val ZERO_ID = "00000000-0000-4000-8000-000000000000"

/** The ids the planner gives S's entities: the same formula, asked of a planner nobody feeds. Spaces and collections have no map file. */
class IdMapper(mergeId: String) {
    private val planner = MergePlanner(
        MergeConfig(mergeId, ZERO_ID, ZERO_ID), app.keepfin.core.merge.InMemoryTHashIndex(emptyList()),
        app.keepfin.core.merge.InMemoryMergeLedger(), app.keepfin.core.merge.CollectedMerge(),
    )

    fun collection(sId: String): String = planner.entityId("playlist", sId)
    fun space(sId: String): String = planner.entityId("space", LibraryMapping.spaceWireId(sId))
    fun item(sId: String): String = planner.entityId("item", sId)
    fun art(kind: String, sId: String): String = planner.entityId("art:$kind", sId)
}

/** SHA-256 of a file read through the folder; the size is what was read, so a file that changed while it was read is described as it is now. */
object Hashing {
    class Result(val size: Long, val hash: String)

    fun of(folder: FolderAccess, path: String, cancelled: () -> Boolean): Result {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        folder.openRead(path).use { input ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                if (cancelled()) throw MergePaused()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                size += n
            }
        }
        val sb = StringBuilder("sha256:")
        for (b in digest.digest()) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 15])
        }
        return Result(size, sb.toString())
    }
}

/** What the planner needs to know of a linked (MediaStore) file: it is outside the folder, so only its size and the recorded hash are known. */
fun interface LinkedLookup {
    fun sizeOf(itemId: String): Long?
}

/**
 * Plans S into T (contract section 5): pages S from its SQL state, answers the hashes the planner asks for by reading the files through the
 * folder, and streams the planner's output to a work directory. A pass starts from nothing every time (fresh scratch data, fresh files), so
 * a crash during planning costs only time: the same inputs and merge id give the same batches.
 */
class ForwardPlanner(
    private val env: MergeEnv,
    private val folder: FolderAccess,
    private val s: OpenLibrary,
    private val staged: Staged,
    private val mergeId: String,
    private val dir: File,
    private val linked: LinkedLookup = LinkedLookup { null },
    private val keepMine: Boolean = false,
    /** Non-null for Separate again: describes what B added to T since activation into the library [staged] holds (contract 7.4). */
    private val reverse: Reverse? = null,
    /** The dry run of the review: counts and the first review rows with titles, nothing else written (no batches, map or artwork files). */
    private val dryRun: Boolean = false,
    /** Hash results shared by the dry run and the real run. */
    private val hashes: HashCache? = null,
    private val cancelled: () -> Boolean,
    private val progress: (done: Long, total: Long) -> Unit,
) {
    /** [bActorInT] added the items; only those with `item.add` after [activationSeq] come back. [spaceBack] turns a planned space id into the one S already has. */
    class Reverse(val bActorInT: String, val activationSeq: Long, val spaceBack: (String) -> String?, val fallbackSpace: (media: String?) -> String)

    private val sReader = LibraryReader(s.store, s.state, s.replicaId)

    /** What the last [run] counted (forward planning only). */
    var counts = PlanCounts()
        private set

    fun run(): PlanInfo {
        PlanFiles.clear(dir)
        val scratch = Scratch(env.sqlHost, File(dir, "scratch.db").path)
        val writer: PlanWriter? = if (dryRun) null else PlanWriter(dir)
        val titles = HashMap<String, String>()
        val counting = CountingSink(
            if (writer == null) null else if (reverse == null) writer else SpaceRewriteSink(writer, reverse),
            counts, { titles[it] }, File(dir, "review-view.jsonl").bufferedWriter(Charsets.UTF_8),
        )
        counts = counting.counts
        val sink: app.keepfin.core.merge.MergeSink = counting
        try {
            // 1. the order of S: the wall time of each item's oldest register, then its id
            var after = ""
            while (true) {
                if (cancelled()) throw MergePaused()
                val page = if (reverse == null) sReader.orderPage(after, 2000) else sReader.addedAfterPage(reverse.bActorInT, reverse.activationSeq, after, 2000)
                if (page.isEmpty()) break
                scratch.addOrder(page)
                after = page.last().second
            }
            val total = scratch.orderCount()
            val tSpaces = if (reverse == null) staged.reader.spacesAndRoutes().first else emptyList()
            val planner = MergePlanner(MergeConfig(mergeId, staged.actor, staged.replicaId, tSpaces.size.toLong()), TargetIndex(staged.reader), scratch, sink)

            // 2. the items, in order, 200 at a time
            var key: Pair<Long, String>? = null
            var done = 0L
            while (true) {
                if (cancelled()) throw MergePaused()
                val page = scratch.orderPage(key, PAGE)
                if (page.isEmpty()) break
                val ids = page.map { it.second }
                val items = sReader.items(ids).associateBy { it.id }
                titles.clear()
                for (item in items.values) titles[item.id] = item.title
                val contents = sReader.contents(ids)
                val clocks = sReader.clocks(ids)
                val rows = page.mapNotNull { (wall, id) ->
                    val item = items[id] ?: return@mapNotNull null
                    Row(item, contents[id], addClockOf(wall), clocks[id].orEmpty())
                }
                submit(planner, rows)
                done += page.size
                key = page.last()
                progress(done, total)
            }

            if (reverse != null) {
                planner.finish()
                writer!!.close()
                return PlanInfo(writer.batches, total, writer.mapLines, writer.reviews, writer.arts, true)
            }

            // 3. spaces, playlists, artwork (the review shows their names)
            val (spaces, routes) = sReader.spacesAndRoutes()
            titles.clear()
            for (sp in spaces) titles[sp.id] = sp.name
            planner.spaces(spaces)
            val collections = sReader.collections()
            for (c in collections) titles[c.id] = c.name
            planner.playlists(collections)
            counting.counts.spaces = spaces.size.toLong()
            counting.counts.playlists = collections.size.toLong()
            val arts = artFiles(planner, collections.map { it.id }.toSet(), spaces.map { it.id }.toSet(), scratch)
            planner.artwork(arts)
            planner.finish()
            var batches = writer?.batches ?: 0

            // 4. "keep my library's" routing: B's default places for music and other downloads, as the combined library's defaults
            if (keepMine && writer != null) {
                val value = JObj(linkedMapOf("music" to JStr(planner.entityId("space", LibraryMapping.spaceWireId(routes.music))), "other" to JStr(planner.entityId("space", LibraryMapping.spaceWireId(routes.other)))))
                val draft = app.keepfin.core.library.OpDraft("profile.set", mapOf("field" to JStr("routes"), "value" to value))
                File(File(dir, "batches"), batches.toString().padStart(6, '0') + ".json").writeText(MergeWire.batch(Batch.of(batches, listOf(draft))), Charsets.UTF_8)
                batches++
            }
            writer?.close()
            return PlanInfo(batches, total, writer?.mapLines ?: counting.counts.mapLines, writer?.reviews ?: counting.counts.reviewTotal, writer?.arts ?: 0, true)
        } finally {
            runCatching { writer?.close() }
            runCatching { counting.close() }
            scratch.close()
        }
    }

    private class Row(val item: LibItem, val content: Pair<String?, Long>?, val added: Hlc, val clocks: Map<String, Hlc>)

    private fun submit(planner: MergePlanner, rows: List<Row>) {
        val placements = sReader.placements(rows.map { it.item.id })
        val addSeqs = if (reverse != null) sReader.addSeqs(rows.map { it.item.id }, reverse.bActorInT) else emptyMap()
        val evidence = HashMap<String, FileEvidence>()
        fun build() = rows.map { sItem(it, evidence[it.item.id], placements[it.item.id].orEmpty(), addSeqs[it.item.id] ?: 0L) }
        var page = build()
        // the planner names the files whose hash it needs; they are read now and the same page goes back
        for (round in 0 until 3) {
            val result = if (reverse == null) planner.items(page) else planner.reverseItems(page, reverse.bActorInT, reverse.activationSeq)
            if (result.accepted) return
            for (need in result.needsHash) evidence[need.s] = readEvidence(need, rows.first { it.item.id == need.s })
            page = build()
        }
        throw MergeFailure("A file kept changing while it was read. Try again.")
    }

    /** Stat and, where the planner asked, hash. A file that cannot be read becomes "Not on this device" and is still added. */
    private fun statOnly(row: Row): FileEvidence {
        val place = row.item.place ?: return FileEvidence(false)
        if (isLinked(place)) return FileEvidence(true, size = row.content?.second, mtime = null, hash = row.content?.first)
        return try {
            val e = folder.stat(place)
            if (e == null || e.isDirectory) FileEvidence(false) else FileEvidence(true, e.size, e.modifiedMs)
        } catch (e: FolderError.NotFound) {
            FileEvidence(false)
        } catch (e: FolderError.Io) {
            FileEvidence(false)
        }
    }

    private fun readEvidence(need: NeedsHash, row: Row): FileEvidence {
        val place = row.item.place ?: return FileEvidence(false)
        if (isLinked(place)) return FileEvidence(false)
        return try {
            val stat = folder.stat(place) ?: return FileEvidence(false)
            hashes?.get(place, stat.size, stat.modifiedMs)?.let { return FileEvidence(true, stat.size, stat.modifiedMs, it) }
            val h = Hashing.of(folder, place, cancelled)
            // a file that changed while it was read is not remembered (the size read is not the size listed)
            if (h.size == stat.size) hashes?.put(place, stat.size, stat.modifiedMs, h.hash)
            FileEvidence(true, h.size, stat.modifiedMs, h.hash)
        } catch (e: FolderError.NotFound) {
            FileEvidence(false)
        } catch (e: FolderError.Io) {
            FileEvidence(false)
        }
    }

    private fun isLinked(place: String) = place.startsWith("linked:")

    /**
     * The record's hash is trusted when the file's size matches the record and the file was not touched after the item was added (the file's
     * modification time is not later than `addedAt`): linking deletes nothing, so a hint is enough (contract 5). Anything else is re-read.
     */
    private fun sItem(row: Row, fresh: FileEvidence?, placements: Map<String, String>, addSeq: Long): SItem {
        val item = row.item
        val evidence = fresh ?: statOnly(row)
        val recordHash = row.content?.first
        val recordSize = row.content?.second
        var mtimeHint: Long? = null
        if (fresh == null && evidence.readable && recordHash != null && evidence.hash == null && evidence.size == recordSize) {
            val mtime = evidence.mtime
            val addedAt = item.addedAt?.toLong()
            if (mtime != null && addedAt != null && mtime <= addedAt + SLACK_MS) mtimeHint = mtime
        }
        return SItem(
            fields = item, added = row.added, revisionHash = recordHash, size = recordSize, mtimeHint = mtimeHint, pathOnB = item.place,
            file = evidence, placements = placements, fieldClocks = row.clocks,
            addActor = reverse?.bActorInT ?: row.added.actor, addSeq = addSeq,
        )
    }

    /** Artwork files in S's `art/` named after their owner: items (mapped), playlists and spaces. */
    private fun artFiles(planner: MergePlanner, playlists: Set<String>, spaces: Set<String>, scratch: Scratch): List<ArtFile> {
        val artDir = s.layout.art
        val listing = try { folder.list(artDir).entries } catch (e: FolderError.NotFound) { return emptyList() }
        val out = ArrayList<ArtFile>()
        for (e in listing) {
            if (e.isDirectory) continue
            val stem = e.name.substringBeforeLast('.')
            val kind = when {
                scratch.mapped(stem) != null -> "item"
                stem in playlists -> "playlist"
                stem in spaces -> "space"
                else -> continue
            }
            out += ArtFile(kind, stem, "$artDir/${e.name}", e.name)
        }
        return out
    }

    companion object {
        const val PAGE = 200
        const val SLACK_MS = 5_000L
    }
}

/** What a planning pass counted: the numbers of the review. */
class PlanCounts {
    var mapLines = 0L
    var added = 0L
    var linked = 0L
    var keptDuplicates = 0L
    var missingFiles = 0L
    var differing = 0L
    var reviewTotal = 0L
    var playlists = 0L
    var spaces = 0L
}

/**
 * Counts what the planner emits and keeps the first review rows with the title of what they are about (`review-view.jsonl`, never ids), then
 * passes everything to [inner] (null for a dry run, which writes nothing else).
 */
private class CountingSink(
    private val inner: app.keepfin.core.merge.MergeSink?, val counts: PlanCounts, private val titleOf: (String) -> String?, private val view: java.io.BufferedWriter,
) : app.keepfin.core.merge.MergeSink, AutoCloseable {
    private var rows = 0

    override fun batch(batch: Batch) { inner?.batch(batch) }

    override fun map(line: app.keepfin.core.merge.MapLine) {
        counts.mapLines++
        when (line.how) {
            app.keepfin.core.merge.MapHow.Linked -> counts.linked++
            app.keepfin.core.merge.MapHow.Added -> counts.added++
            app.keepfin.core.merge.MapHow.KeptDuplicate -> counts.keptDuplicates++
        }
        inner?.map(line)
    }

    override fun review(entry: app.keepfin.core.merge.ReviewEntry) {
        counts.reviewTotal++
        if (entry.kind == app.keepfin.core.merge.ReviewKind.MissingFile) counts.missingFiles++
        if (entry.kind == app.keepfin.core.merge.ReviewKind.DifferingField) counts.differing++
        if (rows < VIEW_ROWS) {
            rows++
            val o = org.json.JSONObject().put("kind", entry.kind.wire).put("s", entry.s)
            entry.t?.let { o.put("t", it) }
            entry.field?.let { o.put("field", it) }
            titleOf(entry.s)?.let { o.put("title", it) }
            view.write(o.toString()); view.write("\n")
        }
        inner?.review(entry)
    }

    override fun art(copy: app.keepfin.core.merge.ArtCopy) { inner?.art(copy) }

    override fun close() { view.close() }

    companion object { const val VIEW_ROWS = 200 }
}

/** Reverse planning names S's spaces afresh; items that belong in a space S already has are put back there. Rebuilds each batch with its new hash. */
private class SpaceRewriteSink(private val inner: PlanWriter, private val reverse: ForwardPlanner.Reverse) : app.keepfin.core.merge.MergeSink {
    override fun batch(batch: Batch) {
        val drafts = batch.drafts.map { d ->
            if (d.kind != "item.add") return@map d
            val fields = (d.body["fields"] as? JObj)?.members ?: return@map d
            val planned = (fields["space"] as? JStr)?.value ?: return@map d
            val back = reverse.spaceBack(planned) ?: reverse.fallbackSpace((fields["media"] as? JStr)?.value)
            app.keepfin.core.library.OpDraft(d.kind, d.body + ("fields" to JObj(fields + ("space" to JStr(back)))))
        }
        inner.batch(Batch.of(batch.index, drafts))
    }

    override fun map(line: app.keepfin.core.merge.MapLine) = inner.map(line)
    override fun review(entry: app.keepfin.core.merge.ReviewEntry) = inner.review(entry)
    override fun art(copy: app.keepfin.core.merge.ArtCopy) = inner.art(copy)
}

/**
 * Applies the planned batches of a work directory to a store with the receipt protocol of contract section 3: the journal is told what is
 * about to be applied (`actorSeqBefore`, the draft count, their hash) before a batch is, so a crash is resolved by counting the actor's
 * own operations since: m of n drafts exist, apply the rest.
 */
object BatchApplier {
    class State(var applied: Int, var receipt: Receipt?)

    fun apply(
        store: KeepfinStore,
        reader: LibraryReader,
        actor: () -> String,
        dir: File,
        total: Int,
        state: State,
        save: (State) -> Unit,
        cancelled: () -> Boolean,
        progress: (applied: Int, total: Int) -> Unit,
    ) {
        while (state.applied < total) {
            if (cancelled()) throw MergePaused()
            val k = state.applied
            val batch = PlanFiles.batch(dir, k)
            val receipt = state.receipt
            val remaining = if (receipt != null && receipt.batch == k) {
                if (receipt.draftsHash != batch.draftsHash || receipt.drafts != batch.drafts.size) throw MergeFailure("A planned batch does not match its receipt.")
                val m = (reader.actorSeq(actor()) - receipt.actorSeqBefore).coerceIn(0L, receipt.drafts.toLong()).toInt()
                remainingDrafts(batch, m)
            } else {
                state.receipt = Receipt(k, reader.actorSeq(actor()), batch.drafts.size, batch.draftsHash)
                save(state)
                batch.drafts
            }
            if (remaining.isNotEmpty()) store.apply(remaining.map { OpDraft(it.kind, it.body) }).get(120, TimeUnit.SECONDS)
            state.applied = k + 1
            state.receipt = null
            save(state)
            progress(state.applied, total)
        }
    }
}

/** The reasons a combine does not start, in the words of the Windows app. */
object MergeRefusals {
    const val OWN_UNREADABLE = "Part of this library could not be read yet. Wait a moment and try again."
    const val OTHER_UNREADABLE = "Part of the other library could not be read yet. Wait a moment and try again."

    /** A library whose store reports a segment it could not read yet (a missing or half-copied part) has an unresolved review: do not combine it. */
    fun unreadable(findings: Collection<String>): Boolean = findings.any { it.startsWith("unreadable-segment") }
}
