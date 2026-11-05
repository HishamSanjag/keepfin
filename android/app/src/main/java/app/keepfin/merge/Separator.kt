package app.keepfin.merge

import app.keepfin.core.library.LibraryMapping
import app.keepfin.core.library.ObservedLookup
import app.keepfin.core.library.ObservedTags
import app.keepfin.core.merge.KeepChoice
import app.keepfin.core.merge.MapHow
import app.keepfin.core.merge.MapLine
import app.keepfin.core.merge.SeparatePlanner
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import app.keepfin.folder.FolderJson
import app.keepfin.folder.FolderLayout
import app.keepfin.folder.KeepfinFolders
import app.keepfin.folder.OpenFolderResult
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit

/** What the person is shown before Separate again (contract 7.1). */
class SeparatePreview(
    /** Items this device brought into the main library (added or kept as duplicates; linked ones were there before). */
    val brought: Long,
    /** Of those, items another device holds a file of. */
    val heldByPeer: Long,
    /** Items this device added to the main library since combining. */
    val addedSince: Long,
    val peerReachable: Boolean,
    val keepUntil: Long?,
)

/** No device holding the main library can be reached: the person may "Separate anyway" (contract 7.3). */
class PeerUnreachable : RuntimeException("The main library's device cannot be reached.")

/**
 * Separate again (contract section 7), journaled in steps: fence, T-side removals, publish to the peer and wait for its durable
 * acknowledgement, the reverse plan into S, the pointer back, the membership withdrawal, then the profile, the outside ids and the trash.
 * Nothing here ever deletes a media file: the T-side removals are metadata, and B's executor for T never runs again.
 */
class Separator(
    private val env: MergeEnv,
    private val journals: JournalStore,
    private val cancelled: () -> Boolean,
    private val listener: (MergeJournal, done: Long, total: Long) -> Unit,
) {
    private fun mapFile(j: MergeJournal) = File(journals.workDir(j.mergeId), "map.jsonl")

    /** The forward map is on this device; if the work files were lost it is read from T's records. */
    private fun ensureMap(j: MergeJournal, folder: FolderAccess): File {
        val file = mapFile(j)
        if (file.isFile) return file
        file.parentFile?.mkdirs()
        val remote = "${FolderLayout.forLibrary(j.tLibraryId).mergeDir(j.mergeId)}/map.jsonl"
        folder.openRead(remote).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    fun preview(j: MergeJournal): SeparatePreview {
        val folder = env.folder(j.treeUri)
        val t = env.openLibrary()
        val tr = LibraryReader(t.store, t.state, t.replicaId)
        var brought = 0L
        var held = 0L
        val batch = ArrayList<String>()
        fun flush() {
            if (batch.isEmpty()) return
            for ((_, places) in tr.placements(batch)) if (places.any { (replica, path) -> replica != t.replicaId && path.isNotEmpty() }) held++
            batch.clear()
        }
        for (line in PlanFiles.mapLines(ensureMap(j, folder))) {
            if (line.how == MapHow.Linked) continue
            brought++
            batch += line.t
            if (batch.size >= 500) flush()
        }
        flush()
        var added = 0L
        var after = ""
        val activation = j.activationSeq ?: 0L
        while (true) {
            val page = tr.addedAfterPage(j.tActor!!, activation, after, 2000)
            if (page.isEmpty()) break
            added += page.size
            after = page.last().second
        }
        return SeparatePreview(brought, held, added, env.peer(j)?.reachable() == true, j.keepUntil)
    }

    /** Starts (or resumes) Separate again. [keepOnPeer] decides the items the peer holds files of; [anyway] skips the publication. */
    fun run(mergeId: String, keepOnPeer: Boolean, anyway: Boolean): MergeJournal {
        var j = journals.read(mergeId) ?: throw MergeFailure("The merge journal is missing.")
        if (j.stage == MergeStage.Active) {
            j = journals.update(mergeId) { it.copy(stage = MergeStage.Separating, separate = SeparateState(null, keepOnPeer, anyway), error = null) }
        } else if (j.stage != MergeStage.Separating) {
            return j
        } else if (anyway && j.separate?.anyway != true) {
            j = journals.update(mergeId) { it.copy(separate = (it.separate ?: SeparateState()).copy(anyway = true)) }
        }
        val folder = env.folder(j.treeUri)
        val dirT = File(journals.workDir(mergeId), "sep-t")
        val dirS = File(journals.workDir(mergeId), "sep-s")
        var sStaged: Staged? = null
        try {
            while (true) {
                if (cancelled()) throw MergePaused()
                val sep = j.separate ?: SeparateState()
                when (sep.step) {
                    null -> j = step(j) { it.copy(step = SeparateStep.Fenced) }
                    SeparateStep.Fenced -> j = removeFromT(j, folder, dirT)
                    SeparateStep.Written -> j = publish(j)
                    SeparateStep.Published -> {
                        val s = sStaged ?: openS(j, folder).also { sStaged = it }
                        j = reverse(j, folder, s, dirS)
                    }
                    SeparateStep.Reversed -> {
                        val s = sStaged ?: openS(j, folder).also { sStaged = it }
                        j = pointerBack(j, folder, s)
                    }
                    SeparateStep.PointerBack -> j = withdraw(j)
                    SeparateStep.Withdrawn -> {
                        val s = sStaged ?: openS(j, folder).also { sStaged = it }
                        j = finish(j, folder, s, dirS)
                    }
                    SeparateStep.Remapped -> {
                        sStaged?.close()
                        sStaged = null
                        if (env.engineReady) {
                            env.reopen()
                            env.replayPlays(j.sLibraryId, MergeFence.drainPlayback(j.tLibraryId), null)
                        }
                        MergeFence.exit(j.sLibraryId, j.tLibraryId)
                        return journals.update(mergeId) { it.copy(stage = MergeStage.Separated, error = null) }
                    }
                }
            }
        } finally {
            sStaged?.close()
        }
    }

    private fun step(j: MergeJournal, change: (SeparateState) -> SeparateState): MergeJournal =
        journals.update(j.mergeId) { it.copy(separate = change(it.separate ?: SeparateState())) }

    private fun openS(j: MergeJournal, folder: FolderAccess): Staged = try {
        Staging.openStore(env, folder, j.sLibraryId, j.sReplicaId, j.sActor, env.profiles.deviceDir(env.filesDir, j.sProfileId), FolderLayout(j.sRecords))
    } catch (e: FolderError) {
        throw WaitingForFolder(e)
    }

    // ---- 7.2 the T-side removals ----------------------------------------------------------------------------------------

    private fun removeFromT(start: MergeJournal, folder: FolderAccess, dir: File): MergeJournal {
        var j = start
        val t = env.openLibrary()
        if (t.libraryId != j.tLibraryId) throw MergeFailure("The main library is not the one that is open.")
        val tr = LibraryReader(t.store, t.state, t.replicaId)
        val sep = j.separate!!
        if (sep.tBatches == 0 && sep.tApplied == 0 && sep.tReceipt == null) {
            val total = planRemovals(j, folder, dir, t, tr, sep.keepOnPeer)
            j = step(j) { it.copy(tBatches = total, tApplied = 0, tReceipt = null) }
        }
        val s0 = j.separate!!
        val state = BatchApplier.State(s0.tApplied, s0.tReceipt)
        BatchApplier.apply(t.store, tr, { t.store.actorId }, dir, s0.tBatches, state, save = { x ->
            j = step(j) { it.copy(tApplied = x.applied, tReceipt = x.receipt) }
        }, cancelled = cancelled) { applied, total -> listener(j, applied.toLong(), total.toLong()) }
        t.store.flush().get(2, TimeUnit.MINUTES)
        val seq = tr.actorSeq(t.store.actorId)
        return step(j) { it.copy(step = SeparateStep.Written, publishedSeq = seq) }
    }

    private fun planRemovals(j: MergeJournal, folder: FolderAccess, dir: File, t: OpenLibrary, tr: LibraryReader, keepOnPeer: Boolean): Int {
        PlanFiles.clear(dir)
        val writer = PlanWriter(dir)
        val cache = HashMap<String, ObservedTags>()
        val observed = object : ObservedLookup {
            override fun item(id: String): ObservedTags = cache[id] ?: ObservedTags(emptyList(), emptyList())
            override fun playlist(id: String): List<String> = emptyList()
            override fun space(id: String): List<String> = emptyList()
            override fun member(set: String, element: String): List<String> = emptyList()
        }
        val planner = SeparatePlanner(t.replicaId, TargetIndex(tr), observed, writer)
        val choice: (String) -> KeepChoice = { if (keepOnPeer) KeepChoice.Keep else KeepChoice.Remove }
        val all = PlanFiles.mapLines(ensureMap(j, folder))
        val page = ArrayList<MapLine>(PAGE)
        fun flush() {
            if (page.isEmpty()) return
            if (cancelled()) throw MergePaused()
            val present = tr.items(page.map { it.t }).map { it.id }.toSet()
            val live = page.filter { it.t in present }
            val seen = t.store.observed(items = live.map { it.t }).get(60, TimeUnit.SECONDS)
            val usable = live.filter { line -> seen.items[line.t]?.tags?.isNotEmpty() == true }
            for (line in usable) seen.items[line.t]?.let { cache[line.t] = ObservedTags(it.tags.map { id -> id.toString() }, it.heads.map { id -> id.toString() }) }
            planner.items(usable, choice)
            for (line in usable) cache.remove(line.t)
            page.clear()
        }
        for (line in all) {
            page += line
            if (page.size >= PAGE) flush()
        }
        flush()
        planner.finish()
        writer.close()
        return writer.batches
    }

    // ---- 7.3 publish ------------------------------------------------------------------------------------------------------

    private fun publish(j: MergeJournal): MergeJournal {
        val sep = j.separate!!
        if (!sep.anyway) {
            val peer = env.peer(j)
            if (peer == null || !peer.reachable() || !peer.publishAndWait(j.tLibraryId, j.tActor!!, sep.publishedSeq)) throw PeerUnreachable()
        }
        return step(j) { it.copy(step = SeparateStep.Published) }
    }

    // ---- 7.4 reverse plan into S ------------------------------------------------------------------------------------------

    private fun reverse(start: MergeJournal, folder: FolderAccess, s: Staged, dir: File): MergeJournal {
        var j = start
        val t = env.openLibrary()
        val sep = j.separate!!
        if (sep.sBatches == 0 && sep.sApplied == 0 && sep.sReceipt == null) {
            val fwd = IdMapper(j.mergeId)
            val back = IdMapper(j.mergeId + ":back")
            val (spaces, routes) = s.reader.spacesAndRoutes()
            val dictionary = spaces.associate { sp -> back.space(fwd.space(sp.id)) to LibraryMapping.spaceWireId(sp.id) }
            val reverse = ForwardPlanner.Reverse(
                bActorInT = j.tActor!!, activationSeq = j.activationSeq ?: 0L, spaceBack = { dictionary[it] },
                fallbackSpace = { media -> LibraryMapping.spaceWireId(if (media == "audio") routes.music else routes.other) },
            )
            val plan = ForwardPlanner(env, folder, t, s, j.mergeId + ":back", dir, reverse = reverse, cancelled = cancelled) { done, total -> listener(j, done, total) }.run()
            j = step(j) { it.copy(sBatches = plan.batches, sApplied = 0, sReceipt = null, sActorSeqBefore = s.reader.actorSeq(s.store.actorId)) }
        }
        val s0 = j.separate!!
        val state = BatchApplier.State(s0.sApplied, s0.sReceipt)
        BatchApplier.apply(s.store, s.reader, { s.store.actorId }, dir, s0.sBatches, state, save = { x ->
            j = step(j) { it.copy(sApplied = x.applied, sReceipt = x.receipt) }
        }, cancelled = cancelled) { applied, total -> listener(j, applied.toLong(), total.toLong()) }
        s.store.flush().get(2, TimeUnit.MINUTES)
        return step(j) { it.copy(step = SeparateStep.Reversed) }
    }

    // ---- 7.5 the pointer goes back --------------------------------------------------------------------------------------------

    private fun pointerBack(j: MergeJournal, folder: FolderAccess, s: Staged): MergeJournal {
        val current = when (val opened = KeepfinFolders.open(folder)) {
            is OpenFolderResult.Ok -> opened.folder
            else -> throw WaitingForFolder(null)
        }
        if (current.libraryId != j.sLibraryId) {
            val now = Instant.ofEpochMilli(env.now()).toString()
            val known = current.libraries.map {
                when (it.libraryId) {
                    j.sLibraryId -> it.copy(role = "active", since = now, keepUntil = null)
                    j.tLibraryId -> it.copy(role = "left", keepUntil = null)
                    else -> it
                }
            }
            val libraries = if (known.any { it.libraryId == j.sLibraryId }) known else known + app.keepfin.folder.FolderLibrary(j.sLibraryId, j.sReplicaId, j.sRecords, "active", since = now, mergeId = j.mergeId)
            val next = FolderJson(2, j.sLibraryId, j.sReplicaId, current.createdAt, j.sRecords, libraries)
            folder.replace(KeepfinFolders.FOLDER_JSON, KeepfinFolders.folderJsonText(next).toByteArray(Charsets.UTF_8))
        }
        val back = (KeepfinFolders.open(folder) as? OpenFolderResult.Ok)?.folder
        if (back?.libraryId != j.sLibraryId) throw MergeFailure("The switch back to your own library could not be confirmed.")
        return step(j) { it.copy(step = SeparateStep.PointerBack) }
    }

    // ---- 7.6 withdraw ------------------------------------------------------------------------------------------------------

    private fun withdraw(j: MergeJournal): MergeJournal {
        val sep = j.separate!!
        var done = false
        if (!sep.anyway) {
            val peer = env.peer(j)
            done = peer != null && runCatching { peer.withdraw(j.tLibraryId, j.tActor!!, sep.publishedSeq) }.getOrDefault(false)
        }
        return step(j) { it.copy(step = SeparateStep.Withdrawn, withdrawn = done) }
    }

    // ---- 7.7 profile, outside ids, trash ---------------------------------------------------------------------------------------------

    private fun finish(start: MergeJournal, folder: FolderAccess, s: Staged, dirS: File): MergeJournal {
        val j = start
        val fwd = IdMapper(j.mergeId)
        val ids = IdMaps(item = { null }, collection = { null }, space = { null })
        val sColl = s.reader.collections().associate { fwd.collection(it.id) to it.id }
        val sSpace = s.reader.spacesAndRoutes().first.associate { fwd.space(it.id) to it.id }
        val forward = MapLookup(env.sqlHost, ensureMap(j, folder), File(journals.workDir(j.mergeId), "lookup-back.db").path)
        val extra = File(dirS, "map.jsonl")
        val reverseLookup = if (extra.isFile) MapLookup(env.sqlHost, extra, File(journals.workDir(j.mergeId), "lookup-rev.db").path) else null
        try {
            val maps = IdMaps(
                item = { tItem -> reverseLookup?.tOf(tItem) ?: forward.sOf(tItem) },
                collection = { sColl[it] }, space = { sSpace[it] },
            )
            restoreTrash(j, folder, s, forward)
            env.profiles.remove(j.tProfileId, fallback = j.sProfileId)
            env.profiles.setCurrent(j.sProfileId)
            DeviceFiles.copyRemapped(env.profiles.deviceDir(env.filesDir, j.tProfileId), env.profiles.deviceDir(env.filesDir, j.sProfileId), maps, merge = true)
            env.remapOutside(maps)
            return step(j) { it.copy(step = SeparateStep.Remapped) }.also { ids.item("") }
        } finally {
            forward.close()
            reverseLookup?.close()
        }
    }

    /**
     * Files deleted from this device since combining (they went to T's trash) come back to where S's records say they were, when they are still
     * there; the ones that are not are written to `restore.jsonl` for the person.
     */
    private fun restoreTrash(j: MergeJournal, folder: FolderAccess, s: Staged, forward: MapLookup) {
        val trash = FolderLayout.forLibrary(j.tLibraryId).trash
        val missing = File(journals.workDir(j.mergeId), "restore.jsonl").bufferedWriter(Charsets.UTF_8)
        try {
            val lines = PlanFiles.mapLines(mapFile(j)).iterator()
            while (lines.hasNext()) {
                if (cancelled()) throw MergePaused()
                val page = ArrayList<MapLine>(PAGE)
                while (lines.hasNext() && page.size < PAGE) page += lines.next()
                val places = s.reader.placements(page.map { it.s })
                for (line in page) {
                    val place = places[line.s]?.get(s.replicaId) ?: continue
                    if (place.startsWith("linked:")) continue
                    if (folder.stat(place) != null) continue
                    val dir = "$trash/${line.t}"
                    val entry = try { folder.list(dir).entries.firstOrNull { !it.isDirectory } } catch (e: FolderError.NotFound) { null }
                    if (entry == null) { missing.write("{\"path\":${org.json.JSONObject.quote(place)}}\n"); continue }
                    try {
                        app.keepfin.copies.Versions.moveVerified(folder, "$dir/${entry.name}", place, null)
                    } catch (e: Exception) {
                        missing.write("{\"path\":${org.json.JSONObject.quote(place)}}\n")
                    }
                }
            }
        } finally {
            missing.close()
        }
    }

    // ---- cleanup after the 30 days or "Keep combined" -----------------------------------------------------------------------------

    /**
     * Removes the frozen records of the library that was left (contract end of section 7): the sync records of S while T is active, or the
     * whole records root of T after a separate. Trash and artwork stay; media is never touched. folder.json keeps format 2.
     */
    fun cleanup(j: MergeJournal): MergeJournal {
        val folder = env.folder(j.treeUri)
        val current = (KeepfinFolders.open(folder) as? OpenFolderResult.Ok)?.folder ?: throw WaitingForFolder(null)
        when (j.stage) {
            MergeStage.Active -> {
                if (current.libraryId != j.tLibraryId) throw MergeFailure("The main library is not in charge.")
                val s = FolderLayout(j.sRecords)
                for (path in listOf(s.ops, s.checkpoints, s.actors, s.membership)) folder.remove(path)
                if (j.sRecords.isNotEmpty()) folder.remove(s.root)
                val libraries = current.libraries.filter { it.libraryId != j.sLibraryId }
                folder.replace(KeepfinFolders.FOLDER_JSON, KeepfinFolders.folderJsonText(current.copy(libraries = libraries)).toByteArray(Charsets.UTF_8))
            }
            MergeStage.Separated -> {
                if (current.libraryId != j.sLibraryId) throw MergeFailure("Your own library is not in charge.")
                val t = FolderLayout.forLibrary(j.tLibraryId)
                folder.remove(t.root)
                folder.remove(app.keepfin.net.JoinFile.pathOf(t))
                val libraries = current.libraries.filter { it.libraryId != j.tLibraryId }
                folder.replace(KeepfinFolders.FOLDER_JSON, KeepfinFolders.folderJsonText(current.copy(libraries = libraries)).toByteArray(Charsets.UTF_8))
                env.profiles.deviceDir(env.filesDir, j.tProfileId).deleteRecursively()
            }
            else -> return j
        }
        return journals.update(j.mergeId) { it.copy(stage = MergeStage.Cleaned) }
    }

    companion object {
        const val PAGE = 200
    }
}
