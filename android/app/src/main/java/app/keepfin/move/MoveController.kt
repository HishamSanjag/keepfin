package app.keepfin.move

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import app.keepfin.KeepfinApplication
import app.keepfin.folder.FolderError
import app.keepfin.folder.SafFolderAccess
import app.keepfin.setup.FolderPlaces
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class MoveStage { NONE, RUNNING, INTERRUPTED, FAILED, DONE }

/**
 * What Settings > Storage shows of the move. [check] is the picked folder (Where and Review); [stage] is the run. After [MoveStage.DONE] the
 * old folder is still there ([oldPending]) until the person deletes it or keeps it.
 */
data class MoveUi(
    val stage: MoveStage = MoveStage.NONE,
    val check: MoveCheck? = null,
    val checking: Boolean = false,
    val error: String? = null,
    val progress: MoveProgress? = null,
    val message: String? = null,
    val fromLabel: String = "",
    val toLabel: String = "",
    val oldPending: Boolean = false,
    val deleting: Boolean = false,
    val note: String? = null,
)

/**
 * The move of the Keepfin folder for the screens: picking and checking the new place, starting the run in the foreground service, stopping,
 * continuing after an interruption, and the person's decision about the old folder. All work runs on one worker thread or in [FolderMoveService].
 */
internal class FolderMoveController(private val app: KeepfinApplication, private val host: AndroidMoveHost = AndroidMoveHost(app)) : ForegroundJob {
    private val store = MoveStore(File(app.filesDir, "folder-move.json"))
    private val runner = MoveRunner(host, store)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "keepfin-move").also { it.isDaemon = true } }
    private val stopFlag = AtomicBoolean(false)
    @Volatile private var discardAfterStop = false
    @Volatile private var grantTaken: Uri? = null
    @Volatile private var running = false

    private val state = MutableStateFlow(MoveUi())
    val ui: StateFlow<MoveUi> = state.asStateFlow()

    init { settle() }

    private fun label(tree: String): String = runCatching { FolderPlaces.label(DocumentsContract.getTreeDocumentId(Uri.parse(tree))) }.getOrDefault(tree)

    /** Reads the journal: what an earlier run left is shown as it is. After a finished switch the history follows the files once more. */
    private fun settle() {
        val move = store.read() ?: return
        val shown = MoveUi(fromLabel = label(move.fromTree), toLabel = label(move.toTree))
        if (move.phase == MovePhase.SWITCHED) {
            state.value = shown.copy(stage = MoveStage.DONE, oldPending = true)
            worker.execute { runCatching { host.relocate(move) } }
        } else state.value = shown.copy(stage = MoveStage.INTERRUPTED, message = "Moving your Keepfin folder stopped before it finished. Your Keepfin folder is still where it was.")
    }

    // ---------------------------------------------------------------- Where and Review

    /** The system picker answered: checks the folder (not inside the current one, empty, supported, room) and what would move. */
    fun check(picked: Uri?) {
        if (picked == null || state.value.checking) return
        state.update { it.copy(checking = true, error = null, check = null) }
        worker.execute {
            val took = grantIfNew(picked)
            try {
                val profile = host.profiles.current() ?: throw IllegalStateException("There is no Keepfin folder to move.")
                if (!FolderPlaces.supportedAuthority(picked.authority)) throw FolderError.UnsupportedProvider(picked.authority ?: "")
                val toDoc = DocumentsContract.getTreeDocumentId(picked)
                val fromTree = Uri.parse(profile.folderTreeUri)
                val fromDoc = DocumentsContract.getTreeDocumentId(fromTree)
                val target = SafFolderAccess.open(app.contentResolver, picked)
                val label = FolderPlaces.label(toDoc)
                val problem = MovePlanning.problem(fromDoc, toDoc, target.list("").entries.isNotEmpty())
                if (problem != null) {
                    if (took) release(picked)
                    state.update { it.copy(checking = false, check = MoveCheck(picked.toString(), label, 0, 0, target.freeBytes(), problem)) }
                    return@execute
                }
                val source = host.open(profile.folderTreeUri)
                val entries = FolderMover(source, source, 0, { false }, {}).inventory()
                grantTaken = if (took) picked else grantTaken
                state.update {
                    it.copy(checking = false, fromLabel = FolderPlaces.label(fromDoc), toLabel = label,
                        check = MoveCheck(picked.toString(), label, entries.count { e -> !e.isDir }, entries.sumOf { e -> e.size.coerceAtLeast(0) }, target.freeBytes(), null))
                }
            } catch (e: Exception) {
                if (took) release(picked)
                state.update { it.copy(checking = false, error = MoveRunner.describe(e)) }
            }
        }
    }

    private fun grantIfNew(uri: Uri): Boolean {
        val cr = app.contentResolver
        val had = cr.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        if (had) return false
        return try {
            cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    private fun release(uri: Uri) {
        if (host.profiles.load().profiles.any { it.folderTreeUri == uri.toString() }) return
        runCatching { app.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }

    /** Back to Where: the picked folder is forgotten (and its access given back when this check was the one that took it). */
    fun clearCheck() {
        if (state.value.stage != MoveStage.NONE) return
        grantTaken?.let { release(it) }
        grantTaken = null
        state.update { it.copy(check = null, error = null) }
    }

    // ---------------------------------------------------------------- running

    /** Start: saves the move and runs it in the foreground service (it goes on with the screen off). */
    fun start() {
        val check = state.value.check ?: return
        if (check.problem != null || !check.fits || state.value.stage != MoveStage.NONE) return
        host.refusal()?.let { why -> state.update { it.copy(error = why) }; return }
        val profile = host.profiles.current() ?: return
        val move = FolderMove(
            UUID.randomUUID().toString(), profile.libraryId, profile.folderTreeUri, check.toTree,
            DocumentsContract.getTreeDocumentId(Uri.parse(profile.folderTreeUri)), DocumentsContract.getTreeDocumentId(Uri.parse(check.toTree)),
            MovePhase.COPYING, emptyList(), host.now(),
        )
        store.write(move)
        grantTaken = null // the move owns the new place now
        state.value = MoveUi(stage = MoveStage.RUNNING, fromLabel = label(move.fromTree), toLabel = label(move.toTree))
        FolderMoveService.start(app, KIND)
    }

    /** Continue after an interruption or a failure. */
    fun resume() {
        val move = store.read() ?: return
        if (running || move.phase == MovePhase.SWITCHED) return
        host.refusal()?.let { why -> state.update { it.copy(error = why) }; return }
        state.update { it.copy(stage = MoveStage.RUNNING, error = null, message = null, progress = null) }
        FolderMoveService.start(app, KIND)
    }

    /** Stop: before the switch this gives the move up. What was copied into the new place is removed; the Keepfin folder never changed. */
    fun stop() {
        if (running) { discardAfterStop = true; stopFlag.set(true); return }
        worker.execute { discard() }
    }

    private fun discard() {
        val move = store.read() ?: return
        val problem = runner.discard(move)
        if (problem == null) {
            runCatching { release(Uri.parse(move.toTree)) }
            state.value = MoveUi()
        } else state.update { it.copy(stage = MoveStage.FAILED, message = problem) }
    }

    override fun runBlocking() {
        val move = store.read() ?: return
        stopFlag.set(false)
        discardAfterStop = false
        running = true
        state.update { it.copy(stage = MoveStage.RUNNING, error = null) }
        try {
            when (val outcome = runner.run(move, { p -> state.update { it.copy(progress = p) } }, { stopFlag.get() })) {
                MoveOutcome.Done -> state.update { it.copy(stage = MoveStage.DONE, oldPending = true, progress = null, message = null) }
                MoveOutcome.Stopped -> if (discardAfterStop) discard()
                    else state.update { it.copy(stage = MoveStage.INTERRUPTED, message = "The move was paused. Your Keepfin folder is still where it was.") }
                is MoveOutcome.Failed -> state.update { it.copy(stage = MoveStage.FAILED, message = outcome.message) }
            }
        } finally {
            running = false
        }
    }

    override fun requestStop() { stopFlag.set(true) }

    override fun notice(): Pair<String, Int?> {
        val p = state.value.progress ?: return "Getting ready" to null
        val percent = if (p.bytesTotal > 0) (p.bytesDone * 100 / p.bytesTotal).toInt().coerceIn(0, 100) else null
        return (if (p.phase == "switching") "Opening the new place" else "${p.done} of ${p.total} files") to percent
    }

    override val title: String get() = "Moving your Keepfin folder"

    override fun finishedNotice(): Pair<String, String> = when (state.value.stage) {
        MoveStage.DONE -> "Your Keepfin folder has moved" to "Open Keepfin to see the new place."
        MoveStage.FAILED -> "The move stopped" to (state.value.message ?: "Open Keepfin to try again.")
        else -> "Continue moving your Keepfin folder" to "Open Keepfin to carry on where it stopped."
    }

    // ---------------------------------------------------------------- the old folder

    /** After a finished move: deletes the old folder. The caller has asked the person; nothing else ever deletes it. */
    fun deleteOld() {
        val move = store.read()?.takeIf { it.phase == MovePhase.SWITCHED } ?: return
        state.update { it.copy(deleting = true, error = null) }
        worker.execute {
            val error = try {
                val profile = host.profiles.current()
                if (profile?.folderTreeUri != move.toTree) "Keepfin is not using the new place yet, so the old folder stays."
                else if (host.open(move.toTree).stat(".keepfin/folder.json") == null) "The new place does not hold your library, so the old folder stays."
                else deleteTree(Uri.parse(move.fromTree))
            } catch (e: Exception) {
                MoveRunner.describe(e)
            }
            if (error == null) {
                runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(move.fromTree), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
                store.clear()
                state.update { it.copy(deleting = false, oldPending = false, note = "The old folder was deleted.") }
            } else state.update { it.copy(deleting = false, error = error) }
        }
    }

    /** Keep the old folder: it stays as it is, and Keepfin stops asking. */
    fun keepOld() {
        store.clear()
        state.update { it.copy(oldPending = false, note = "The old folder stays where it is. It still holds a copy of your library, so do not open it as your Keepfin folder.") }
    }

    /** The move is over for the screens (Done tapped): back to the plain Storage page. */
    fun dismiss() {
        if (state.value.stage == MoveStage.DONE && !state.value.oldPending) state.value = MoveUi()
    }

    /** Null when the old folder is gone, otherwise a sentence about what remains. */
    private fun deleteTree(tree: Uri): String? {
        val cr = app.contentResolver
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val gone = runCatching { DocumentsContract.deleteDocument(cr, root) }.getOrDefault(false)
        if (gone) return null
        return try {
            val access = SafFolderAccess.open(cr, tree)
            for (entry in access.list("").entries) access.remove(entry.name)
            null
        } catch (e: FolderError.NotFound) {
            null
        } catch (e: Exception) {
            "The old folder could not be fully deleted (${e.message ?: "no access"}). Part of it may already be gone. It is safe to try again."
        }
    }

    companion object { const val KIND = "move" }
}
