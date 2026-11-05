package app.keepfin.merge

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** One line of the review the person reads (contract section 5): what was listed, never what failed. [title] is the name of the item, playlist or space (never an id). */
data class ReviewRow(val kind: String, val source: String, val target: String?, val field: String?, val title: String? = null)

/** The first answer to "Combine with this device?": which library it offers, and a plain reason when it cannot be done now. The dry run follows in [CombineState.preview]. */
data class CombinePreview(val deviceId: String, val targetName: String, val targetLibraryId: String, val mineName: String, val refusal: String?)

/** The dry run (nothing has changed yet): the counts of the review, the first rows with names, and how many days Separate again stays possible. */
data class CombineDryRun(
    val items: Long, val added: Long, val linked: Long, val keptDuplicates: Long, val missingFiles: Long, val differing: Long,
    val playlists: Long, val spaces: Long, val reviewTotal: Long,
    /** Items in the other library, or -1 when unknown. */
    val otherItems: Long = -1,
    val review: List<ReviewRow> = emptyList(),
    val separateDays: Int = 30,
)

/**
 * The stage a combine or separate is in, for the UI of M4. [done]/[total] are items (describing) or batches; total is -1 while unknown.
 * [review] holds the first lines of the review once planning finished; [reviewCount] is all of them.
 */
data class CombineState(
    val mergeId: String? = null,
    val stage: String = "idle",
    val separateStep: String? = null,
    val done: Long = 0,
    val total: Long = -1,
    val paused: Boolean = false,
    val waitingForFolder: Boolean = false,
    val peerUnreachable: Boolean = false,
    val review: List<ReviewRow> = emptyList(),
    val reviewCount: Long = 0,
    val error: String? = null,
    val keepUntil: Long? = null,
    /** The device whose library is being combined, and that library's name. */
    val peerDeviceId: String? = null,
    val libraryName: String = "",
    /** False until the person pressed Combine: only the review exists. */
    val started: Boolean = true,
    val preview: CombineDryRun? = null,
)

/**
 * The service M4's screens call (contract: `combinePreview`, `combineStart`, pause/resume, `separatePreview`, `separate`, `keepCombined`).
 * It owns the journal store, runs one merge at a time on a background thread, applies the fences and resumes whatever a restart left unfinished.
 */
class MergeService(private val env: MergeEnv, val journals: JournalStore = JournalStore(File(env.filesDir, "merges"))) {
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "keepfin-merge").also { it.isDaemon = true } }
    private val stop = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val flow = MutableStateFlow(CombineState())
    val state: StateFlow<CombineState> = flow.asStateFlow()

    init { publish(journals.unfinished() ?: journals.active() ?: journals.all().firstOrNull()) }

    private fun publish(j: MergeJournal?, done: Long = flow.value.done, total: Long = flow.value.total, error: String? = j?.error, waiting: Boolean = false, peer: Boolean = false) {
        if (j == null) { flow.value = CombineState(); return }
        val view = File(journals.workDir(j.mergeId), "review-view.jsonl")
        val rows = if ((j.preview != null || j.plan?.done == true) && view.isFile) PlanFiles.lines(view).take(200).map { line ->
            val o = JSONObject(line)
            ReviewRow(o.optString("kind"), o.optString("s"), o.optString("t").ifEmpty { null }, o.optString("field").ifEmpty { null }, o.optString("title").ifEmpty { null })
        }.toList() else emptyList()
        val dry = j.preview?.let { CombineDryRun(it.items, it.added, it.linked, it.keptDuplicates, it.missingFiles, it.differing, it.playlists, it.spaces, it.reviewTotal, it.otherItems, rows) }
        flow.value = CombineState(
            j.mergeId, j.stage.wire, j.separate?.step?.wire, done, total, j.paused, waiting, peer, rows, j.plan?.reviews ?: j.preview?.reviewTotal ?: 0, error, j.keepUntil,
            j.peerDeviceId, j.tName, j.started, dry,
        )
    }

    // ---- combine ---------------------------------------------------------------------------------------------------

    private fun ownUnreadable(): Boolean =
        runCatching { MergeRefusals.unreadable(env.openLibrary().store.findings().get(30, java.util.concurrent.TimeUnit.SECONDS)) }.getOrDefault(false)

    /**
     * "Combine with this device?": checks that it can be done now and, when it can, starts the review in the background. The review joins the
     * other library in beside this one (this library stays in charge and usable) and plans it as a dry run (nothing is written); the numbers and
     * the first rows arrive in [state] as [CombineState.preview]. Asking again for the same device continues or retries the review.
     */
    fun combinePreview(deviceId: String): CombinePreview {
        val offer = env.offer(deviceId)
        val mine = env.currentProfile()
        var existing = journals.unfinished()
        if (existing != null && !existing.started && existing.peerDeviceId != deviceId) {
            // a review of another device that was never confirmed: dropped, it is not a combine
            drop(existing)
            existing = null
        }
        if (existing != null && !existing.started && existing.peerDeviceId == deviceId) {
            if (existing.paused || existing.error != null) journals.update(existing.mergeId) { it.copy(paused = false, error = null) }.also { publish(it) }
            if (existing.preview == null || existing.paused || existing.error != null) launch(existing.mergeId, "preview")
            return CombinePreview(deviceId, existing.tName, existing.tLibraryId, existing.sName, null)
        }
        val refusal = when {
            mine == null -> "This phone has no Keepfin folder yet, so its library cannot be combined. Open Settings › Storage & copies and create the Keepfin folder, then try again."
            offer == null -> env.connectedName(deviceId)?.let { name ->
                when (env.offerGap(deviceId)) {
                    "none" -> "$name has no Keepfin folder yet, so its library cannot be combined. On $name, open Settings › Storage & copies and create the Keepfin folder, then try again."
                    "unshared" -> "$name has not shared its library with this phone yet. On $name, open Settings › Devices, open this phone and choose Share, then try again."
                    "held" -> "$name already shares this library with this phone, so there is nothing to combine."
                    "denied" -> "$name does not let this phone read its library. On $name, open Settings › Devices and allow this phone to see the library, then try again."
                    else -> null
                }
            } ?: "That device is not offering its library right now. Make sure it is turned on and connected."
            offer.first == mine.libraryId -> "Both devices already hold the same library."
            env.profiles.load().profiles.any { it.libraryId == offer.first } -> "This device already has ${offer.second}."
            existing != null -> "Another combine is waiting to finish."
            ownUnreadable() -> MergeRefusals.OWN_UNREADABLE
            else -> env.refusal()
        }
        val result = CombinePreview(deviceId, offer?.second.orEmpty(), offer?.first.orEmpty(), mine?.name.orEmpty(), refusal)
        if (refusal == null) {
            val j = newJournal(result, keepMine = false, started = false)
            publish(j, 0, -1)
            launch(j.mergeId, "preview")
        }
        return result
    }

    private fun newJournal(preview: CombinePreview, keepMine: Boolean, started: Boolean): MergeJournal {
        val mine = env.currentProfile()!!
        val s = env.openLibrary()
        val j = MergeJournal(
            mergeId = env.newId(), stage = MergeStage.Planned, peerDeviceId = preview.deviceId, keepMine = keepMine, treeUri = mine.folderTreeUri,
            sProfileId = mine.id, tProfileId = env.newId(), sLibraryId = s.libraryId, sReplicaId = s.replicaId, sActor = s.store.actorId, sName = mine.name,
            sRecords = s.layout.recordsPrefix, tLibraryId = preview.targetLibraryId, tName = preview.targetName, startedAt = env.now(), started = started,
        )
        journals.write(j)
        return j
    }

    /**
     * The person pressed Combine on the review. [keepMine]: the combined library keeps this device's name and default places. The review's
     * staged copy of the other library is used as it is; this library is described afresh (it is fenced now), reusing the hash results.
     */
    fun combineStart(deviceId: String, keepMine: Boolean): String {
        val existing = journals.unfinished()
        if (existing != null && !existing.started && existing.peerDeviceId == deviceId) {
            env.refusal()?.let { throw IllegalStateException(it) }
            val j = journals.update(existing.mergeId) {
                it.copy(started = true, keepMine = keepMine, plan = null, receipt = null, applied = 0, artCopied = 0, paused = false, error = null)
            }
            MergeFence.enter(j.sLibraryId)
            publish(j, 0, -1)
            launch(j.mergeId, "combine")
            return j.mergeId
        }
        val preview = combinePreview(deviceId)
        preview.refusal?.let { throw IllegalStateException(it) }
        val j = journals.unfinished() ?: throw IllegalStateException("The review could not be started.")
        return combineStart(j.peerDeviceId, keepMine)
    }

    /** Leaves before the work reached the other library (a review, or a combine still planning): the staged records of the other library are removed. */
    fun combineCancel() {
        stop.set(true)
        idle(120_000)
        val j = journals.unfinished() ?: run { publish(null); return }
        if (j.stage != MergeStage.Planned && j.stage != MergeStage.Joined) throw IllegalStateException("This can no longer be cancelled. Use Separate again instead.")
        drop(j)
    }

    /** Removes what a review or an unfinished start staged: only records no folder.json names, and the device-only state of the staged library. */
    private fun drop(j: MergeJournal) {
        val tActor = j.tActor
        if (tActor != null) runCatching { env.peer(j)?.withdraw(j.tLibraryId, tActor, 0) }
        runCatching {
            val folder = env.folder(j.treeUri)
            val named = (app.keepfin.folder.KeepfinFolders.open(folder) as? app.keepfin.folder.OpenFolderResult.Ok)?.folder?.let { f ->
                f.libraryId == j.tLibraryId || f.libraries.any { it.libraryId == j.tLibraryId }
            } ?: true
            if (!named) {
                val t = app.keepfin.folder.FolderLayout.forLibrary(j.tLibraryId)
                folder.remove(t.root)
                folder.remove(app.keepfin.net.JoinFile.pathOf(t))
                env.profiles.deviceDir(env.filesDir, j.tProfileId).deleteRecursively()
            }
        }
        journals.update(j.mergeId) { it.copy(stage = MergeStage.Cleaned, paused = false, error = null) }
        journals.deleteWork(j.mergeId)
        MergeFence.exit(j.sLibraryId, j.tLibraryId)
        publish(null)
    }
    fun combinePause() { stop.set(true); journals.unfinished()?.let { id -> journals.update(id.mergeId) { it.copy(paused = true) }.also { publish(it) } } }

    fun combineResume() {
        val j = journals.unfinished() ?: return
        val next = journals.update(j.mergeId) { it.copy(paused = false, error = null) }
        if (!next.started) { publish(next); launch(next.mergeId, "preview"); return }
        MergeFence.enter(next.sLibraryId)
        launch(next.mergeId, if (next.stage == MergeStage.Separating) "separate" else "combine")
    }

    // ---- separate ----------------------------------------------------------------------------------------------------

    fun separatePreview(): SeparatePreview {
        val j = journals.active() ?: throw IllegalStateException("There is no combined library to separate.")
        return Separator(env, journals, { false }) { _, _, _ -> }.preview(j)
    }

    /** Starts Separate again. [keepOnPeer]: the items the other device holds files of stay there (the default); [anyway]: skip publishing (it cannot be reached). */
    fun separate(keepOnPeer: Boolean = true, anyway: Boolean = false): String {
        val j = journals.active() ?: journals.all().firstOrNull { it.stage == MergeStage.Separating } ?: throw IllegalStateException("There is no combined library to separate.")
        env.refusal()?.let { if (j.stage == MergeStage.Active) throw IllegalStateException(it) }
        journals.update(j.mergeId) { it.copy(separate = (it.separate ?: SeparateState()).copy(keepOnPeer = keepOnPeer, anyway = anyway), paused = false, error = null) }
        MergeFence.enterQuiet(j.tLibraryId)
        launch(j.mergeId, "separate")
        return j.mergeId
    }

    /** "Keep combined": the person gives up the Undo early; the frozen records of the library left behind are removed. */
    fun keepCombined() {
        val j = journals.active() ?: return
        val done = Separator(env, journals, { false }) { _, _, _ -> }.cleanup(j)
        publish(done)
    }

    /** Cleans up what the 30 days left behind (called at start). */
    fun cleanupDue() {
        val j = journals.active() ?: return
        val until = j.keepUntil ?: return
        if (env.now() > until) runCatching { keepCombined() }
    }

    // ---- running ------------------------------------------------------------------------------------------------------

    private val pending = java.util.concurrent.atomic.AtomicReference<Pair<String, String>?>(null)

    /** Runs [kind] for [mergeId] on the background thread; asked while another run is going, it runs right after it (Combine pressed during the review). */
    private fun launch(mergeId: String, kind: String) {
        pending.set(mergeId to kind)
        drain()
    }

    private fun drain() {
        if (!running.compareAndSet(false, true)) return
        stop.set(false)
        io.execute {
            try {
                while (true) {
                    val next = pending.getAndSet(null) ?: break
                    // a run that failed or was paused meanwhile does not continue by itself
                    if (journals.read(next.first)?.paused == true) continue
                    work(next.first, next.second)
                }
            } finally {
                running.set(false)
            }
            // a launch that came in between the last check and the flag
            if (pending.get() != null) drain()
        }
    }
    /** Waits for the background run to finish (tests, recovery before the library opens). */
    fun idle(timeoutMs: Long = 600_000): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (running.get() && System.currentTimeMillis() < end) Thread.sleep(20)
        return !running.get()
    }

    private fun work(mergeId: String, kind: String) {
        var j = journals.read(mergeId) ?: return
        val progress = { cur: MergeJournal, done: Long, total: Long -> publish(cur, done, total) }
        try {
            j = when (kind) {
                "separate" -> Separator(env, journals, { stop.get() }, progress).run(mergeId, j.separate?.keepOnPeer ?: true, j.separate?.anyway ?: false)
                "preview" -> Combiner(env, journals, { stop.get() }, progress).preview(mergeId)
                else -> Combiner(env, journals, { stop.get() }, progress).run(mergeId)
            }
            publish(j)
        } catch (e: MergePaused) {
            publish(journals.update(mergeId) { it.copy(paused = true) })
        } catch (e: WaitingForFolder) {
            publish(journals.update(mergeId) { it.copy(paused = true, error = "Waiting for the Keepfin folder") }, waiting = true)
        } catch (e: PeerUnreachable) {
            publish(journals.update(mergeId) { it.copy(paused = true, error = e.message) }, peer = true)
        } catch (e: Throwable) {
            android.util.Log.w("KeepfinMerge", "merge stopped", e)
            publish(journals.update(mergeId) { it.copy(paused = true, error = e.message ?: e.javaClass.simpleName) })
        }
    }

    // ---- recovery -----------------------------------------------------------------------------------------------------

    /**
     * Runs at app start, before the library opens (contract section 3). Every unfinished merge fences its libraries again first. The stages
     * that decide which library opens (the switch and what follows it, and Separate again from the pointer on) finish right here; the rest
     * resume in the background once the library is open. Lost folder access pauses ("Waiting for the Keepfin folder") and changes nothing.
     */
    fun recoverBeforeOpen() {
        val j = journals.running() ?: journals.active()?.takeIf { !it.profileSwitched || !it.outsideRemapped } ?: return
        MergeFence.enter(j.sLibraryId)
        if (j.stage == MergeStage.Separating) MergeFence.enterQuiet(j.tLibraryId)
        if (j.paused) { publish(j); return }
        val early = when (j.stage) {
            MergeStage.Verified, MergeStage.Activating, MergeStage.Active -> true
            MergeStage.Separating -> (j.separate?.step?.ordinal ?: -1) >= SeparateStep.Reversed.ordinal
            else -> false
        }
        if (!early) return
        if (running.compareAndSet(false, true)) {
            stop.set(false)
            try { work(j.mergeId, if (j.stage == MergeStage.Separating) "separate" else "combine") } finally { running.set(false) }
        }
    }

    /** After the library is open: continues a merge that was left in an earlier stage. */
    fun recoverAfterOpen() {
        val j = journals.running() ?: return
        if (j.paused) return
        launch(j.mergeId, if (j.stage == MergeStage.Separating) "separate" else "combine")
    }
}
