package app.keepfin.merge

import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import app.keepfin.folder.FolderJson
import app.keepfin.folder.FolderLayout
import app.keepfin.folder.FolderLibrary
import app.keepfin.folder.KeepfinFolders
import app.keepfin.folder.OpenFolderResult
import app.keepfin.folder.Profile
import java.io.File
import java.time.Instant
import org.json.JSONObject

/**
 * The combine of contract sections 2, 3, 5 and 6, run from whatever stage the journal is in: join T into its records root, describe S into T
 * in receipted batches, verify, switch the pointer, switch the profile, remap what lives outside the library, reopen. Every step writes the
 * journal first or after, so a crash at any write resumes here and each effect happens once.
 */
class Combiner(
    private val env: MergeEnv,
    private val journals: JournalStore,
    private val cancelled: () -> Boolean,
    private val listener: (MergeJournal, done: Long, total: Long) -> Unit,
) {
    private var staged: Staged? = null

    /** Runs until the combine is active (and switched), or stops with an exception: [MergePaused], [WaitingForFolder], [MergeFailure]. */
    fun run(mergeId: String): MergeJournal {
        var j = journals.read(mergeId) ?: throw MergeFailure("The merge journal is missing.")
        try {
            while (true) {
                if (cancelled()) throw MergePaused()
                j = when (j.stage) {
                    MergeStage.Planned -> join(j)
                    MergeStage.Joined, MergeStage.Describing -> describe(j)
                    MergeStage.Verified, MergeStage.Activating -> activate(j)
                    MergeStage.Active -> return finishActivation(j, reopen = env.engineReady)
                    else -> return j
                }
            }
        } finally {
            closeStaged()
        }
    }

    // ---- 1. the review (the dry run) -------------------------------------------------------------------------------------

    /**
     * Joins T in beside S (S stays in charge and fully usable) and plans S into T as a dry run: the numbers and the first review rows are kept
     * with the journal, nothing is applied and no batch is written. The staged T stays on disk for [run] to use when the person presses Combine.
     */
    fun preview(mergeId: String): MergeJournal {
        var j = journals.read(mergeId) ?: throw MergeFailure("The merge journal is missing.")
        try {
            if (j.stage == MergeStage.Planned) j = join(j)
            return dryRun(j)
        } finally {
            closeStaged()
        }
    }

    private fun dryRun(start: MergeJournal): MergeJournal {
        val folder = folderOf(start)
        val st = try { open(start) } catch (e: FolderError) { throw WaitingForFolder(e) }
        if (MergeRefusals.unreadable(st.store.findings().get(60, java.util.concurrent.TimeUnit.SECONDS))) throw MergeFailure(MergeRefusals.OTHER_UNREADABLE)
        val s = env.openLibrary()
        if (s.libraryId != start.sLibraryId) throw MergeFailure("The library that is open is not the one being combined.")
        val other = st.reader.presentCount()
        val hashes = HashCache(env.sqlHost, journals.hashFile(start.mergeId).path)
        try {
            listener(start, 0, -1)
            val planner = ForwardPlanner(env, folder, s, st, start.mergeId, journals.workDir(start.mergeId), keepMine = start.keepMine, dryRun = true, hashes = hashes, cancelled = cancelled) { done, total -> listener(start, done, total) }
            val plan = planner.run()
            val c = planner.counts
            val numbers = PreviewNumbers(plan.items, c.added, c.linked, c.keptDuplicates, c.missingFiles, c.differing, c.playlists, c.spaces, c.reviewTotal, other)
            return journals.update(start.mergeId) { it.copy(preview = numbers, error = null) }
        } finally {
            hashes.close()
        }
    }

    private fun closeStaged() {
        staged?.close()
        staged = null
    }

    private fun folderOf(j: MergeJournal): FolderAccess = env.folder(j.treeUri)

    // ---- 2. join -------------------------------------------------------------------------------------------------------

    private fun join(j: MergeJournal): MergeJournal {
        val out = env.join(j, { stage, segments -> listener(j, segments.toLong(), -1L) }, cancelled)
        return journals.update(j.mergeId) {
            it.copy(tReplicaId = out.tReplicaId, tActor = out.tActor, tName = out.name.ifEmpty { it.tName }, stage = MergeStage.Joined, error = null)
        }
    }

    // ---- 3. describe -----------------------------------------------------------------------------------------------------

    private fun open(j: MergeJournal): Staged = staged ?: Staging.open(env, folderOf(j), j) { _, actor ->
        journals.update(j.mergeId) { it.copy(tActor = actor) }
    }.also { staged = it }

    private fun describe(start: MergeJournal): MergeJournal {
        val folder = folderOf(start)
        val st = try { open(start) } catch (e: FolderError) { throw WaitingForFolder(e) }
        val s = env.openLibrary()
        if (s.libraryId != start.sLibraryId) throw MergeFailure("The library that is open is not the one being combined.")
        val dir = journals.workDir(start.mergeId)
        var j = start
        if (j.plan?.done != true) {
            listener(j, 0, -1)
            val hashes = HashCache(env.sqlHost, journals.hashFile(j.mergeId).path)
            val plan = try {
                ForwardPlanner(env, folder, s, st, j.mergeId, dir, keepMine = j.keepMine, hashes = hashes, cancelled = cancelled) { done, total -> listener(j, done, total) }.run()
            } finally {
                hashes.close()
            }
            j = journals.update(j.mergeId) { it.copy(stage = MergeStage.Describing, plan = plan, applied = 0, receipt = null, artCopied = 0, error = null) }
        }
        val plan = j.plan!!
        val state = BatchApplier.State(j.applied, j.receipt)
        BatchApplier.apply(st.store, st.reader, { st.store.actorId }, dir, plan.batches, state, save = { x ->
            j = journals.update(j.mergeId) { it.copy(stage = MergeStage.Describing, applied = x.applied, receipt = x.receipt) }
        }, cancelled = cancelled) { applied, total -> listener(j, applied.toLong(), total.toLong()) }
        copyArt(j, st, folder, dir).let { copied -> j = journals.update(j.mergeId) { it.copy(artCopied = copied) } }
        st.store.flush().get(2, java.util.concurrent.TimeUnit.MINUTES)
        verify(j, st, s)
        publishMap(j, st, folder, dir)
        return journals.update(j.mergeId) { it.copy(stage = MergeStage.Verified, mapPublished = true) }
    }

    /** Artwork is copied into T's `art/` (never moved); a missing source is skipped, a copy that is already there is kept. */
    private fun copyArt(j: MergeJournal, st: Staged, folder: FolderAccess, dir: File): Int {
        var index = 0
        var copied = j.artCopied
        for (line in PlanFiles.lines(File(dir, "art.jsonl"))) {
            if (index++ < j.artCopied) continue
            if (cancelled()) throw MergePaused()
            val o = JSONObject(line)
            val source = o.getString("source")
            val target = "${st.layout.root}/${o.getString("target")}"
            try {
                if (folder.stat(target) == null && folder.stat(source) != null) {
                    folder.mkdirs(app.keepfin.folder.parentOf(target))
                    folder.openRead(source).use { folder.publishFrom(target, it) }
                }
            } catch (e: FolderError.NameConflict) {
                // another run copied it first
            } catch (e: FolderError.NotFound) {
                // the source went away: art is optional
            }
            copied = index
            if (copied % 25 == 0) journals.update(j.mergeId) { it.copy(artCopied = copied) }
        }
        return copied
    }

    /** Every S item has a map line, S did not change while it was described, and the items that were added exist in T (contract section 6). */
    private fun verify(j: MergeJournal, st: Staged, s: OpenLibrary) {
        val plan = j.plan ?: throw MergeFailure("There is no plan to verify.")
        if (plan.mapLines != plan.items) throw MergeFailure("Not every item of the library was accounted for.")
        val now = LibraryReader(s.store, s.state, s.replicaId).presentCount()
        if (now != plan.items) throw MergeFailure("The library changed while it was being combined. Start again.")
        val sample = ArrayList<String>()
        val every = maxOf(1L, plan.mapLines / 200L)
        var n = 0L
        for (line in PlanFiles.mapLines(File(journals.workDir(j.mergeId), "map.jsonl"))) {
            if (n++ % every == 0L && line.how != app.keepfin.core.merge.MapHow.Linked) sample += line.t
        }
        val found = st.reader.items(sample).map { it.id }.toSet()
        if (!found.containsAll(sample)) throw MergeFailure("Some items that were added are missing from the main library.")
    }

    /** The lasting S-to-T map and the review travel with the folder, in T's records (contract section 3). */
    private fun publishMap(j: MergeJournal, st: Staged, folder: FolderAccess, dir: File) {
        val target = st.layout.mergeDir(j.mergeId)
        folder.mkdirs(target)
        for (name in listOf("map.jsonl", "review.jsonl")) {
            val local = File(dir, name)
            if (!local.isFile) continue
            val path = "$target/$name"
            if (folder.stat(path) != null) folder.remove(path)
            local.inputStream().use { folder.publishFrom(path, it) }
        }
    }

    // ---- 6. activation ------------------------------------------------------------------------------------------------------

    private fun activate(start: MergeJournal): MergeJournal {
        val folder = folderOf(start)
        var j = start
        val st = try { open(j) } catch (e: FolderError) { throw WaitingForFolder(e) }
        if (j.stage == MergeStage.Verified) {
            st.store.flush().get(2, java.util.concurrent.TimeUnit.MINUTES)
            val seq = st.reader.actorSeq(st.store.actorId)
            j = journals.update(j.mergeId) { it.copy(stage = MergeStage.Activating, activationSeq = seq) }
        }
        val current = when (val opened = KeepfinFolders.open(folder)) {
            is OpenFolderResult.Ok -> opened.folder
            else -> throw WaitingForFolder(null)
        }
        if (current.libraryId != j.tLibraryId) {
            if (current.libraryId != j.sLibraryId) throw MergeFailure("This folder no longer holds the library that was being combined.")
            writePointer(folder, current, j)
        }
        val back = (KeepfinFolders.open(folder) as? OpenFolderResult.Ok)?.folder
        if (back?.libraryId != j.tLibraryId) throw MergeFailure("The switch to the main library could not be confirmed.")
        val now = env.now()
        return journals.update(j.mergeId) { it.copy(stage = MergeStage.Active, activatedAt = now, keepUntil = now + KEEP_MS, error = null) }
    }

    private fun writePointer(folder: FolderAccess, current: FolderJson, j: MergeJournal) {
        val tLayout = FolderLayout.forLibrary(j.tLibraryId)
        val now = Instant.ofEpochMilli(env.now()).toString()
        val keepUntil = Instant.ofEpochMilli(env.now() + KEEP_MS).toString()
        val previous = FolderLibrary(current.libraryId, current.replicaId, current.records, "previous", keepUntil = keepUntil, mergeId = j.mergeId)
        val active = FolderLibrary(j.tLibraryId, j.tReplicaId!!, tLayout.recordsPrefix, "active", since = now, mergeId = j.mergeId)
        val libraries = current.libraries.filter { it.libraryId != previous.libraryId && it.libraryId != active.libraryId } + previous + active
        val next = FolderJson(2, j.tLibraryId, j.tReplicaId, current.createdAt, tLayout.recordsPrefix, libraries)
        folder.replace(KeepfinFolders.FOLDER_JSON, KeepfinFolders.folderJsonText(next).toByteArray(Charsets.UTF_8))
    }

    /**
     * After the pointer says T: the profile, the places outside the library, and (when the app is running) the library itself. Every step is
     * flagged in the journal, so a crash here is finished by recovery without repeating anything that matters.
     */
    fun finishActivation(start: MergeJournal, reopen: Boolean): MergeJournal {
        var j = start
        if (!j.profileSwitched) {
            val name = if (j.keepMine) j.sName else j.tName
            env.profiles.add(Profile(j.tProfileId, name.ifEmpty { "Library" }, j.treeUri, j.tLibraryId, j.tActor!!, Instant.ofEpochMilli(env.now()).toString()), makeCurrent = true)
            j = journals.update(j.mergeId) { it.copy(profileSwitched = true) }
        }
        if (!j.outsideRemapped) {
            remapOutside(j)
            j = journals.update(j.mergeId) { it.copy(outsideRemapped = true) }
        }
        closeStaged()
        if (reopen) {
            env.reopen()
            val maps = forwardMaps(j)
            try {
                env.replayPlays(j.tLibraryId, MergeFence.drainPlayback(j.sLibraryId), maps.first)
            } finally {
                maps.second.close()
            }
        }
        MergeFence.exit(j.sLibraryId, j.tLibraryId)
        return j
    }

    private fun mapFile(j: MergeJournal): File = File(journals.workDir(j.mergeId), "map.jsonl")

    /** The S-to-T lookups (kept in a scratch file the caller closes). Falls back to the copy in T's records when this device lost the work files. */
    private fun forwardMaps(j: MergeJournal): Pair<IdMaps, MapLookup> {
        val file = mapFile(j)
        if (!file.isFile) {
            file.parentFile?.mkdirs()
            val remote = "${FolderLayout.forLibrary(j.tLibraryId).mergeDir(j.mergeId)}/map.jsonl"
            try { folderOf(j).openRead(remote).use { input -> file.outputStream().use { input.copyTo(it) } } } catch (e: FolderError.NotFound) { file.delete() }
        }
        val lookup = MapLookup(env.sqlHost, file, File(journals.workDir(j.mergeId), "lookup.db").path)
        val ids = IdMapper(j.mergeId)
        return IdMaps(item = { lookup.tOf(it) }, collection = { ids.collection(it) }, space = { ids.space(it) }) to lookup
    }

    private fun remapOutside(j: MergeJournal) {
        val (maps, lookup) = forwardMaps(j)
        try {
            val from = env.profiles.deviceDir(env.filesDir, j.sProfileId)
            val to = env.profiles.deviceDir(env.filesDir, j.tProfileId)
            DeviceFiles.copyRemapped(from, to, maps)
            env.remapOutside(maps)
        } finally {
            lookup.close()
        }
    }

    companion object {
        /** Separate again stays possible for 30 days. */
        const val KEEP_MS = 30L * 24 * 60 * 60 * 1000
    }
}
