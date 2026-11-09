package app.keepfin.move

import app.keepfin.BringInResult
import app.keepfin.KeepfinApplication
import app.keepfin.LibraryAccess
import app.keepfin.MediaEngine
import app.keepfin.OutsideSpace
import app.keepfin.folder.writeLocalAtomic
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

enum class BringStage { NONE, RUNNING, INTERRUPTED, DONE }

/** What the Bring them in dialog shows. [asks] are the questions Android has before it removes originals it did not make (one per space). */
data class BringInUi(
    val stage: BringStage = BringStage.NONE,
    val spaces: List<OutsideSpace> = emptyList(),
    val done: Int = 0,
    val total: Int = 0,
    val current: String = "",
    val moved: Int = 0,
    val kept: Int = 0,
    val failed: List<String> = emptyList(),
    val asks: List<LibraryAccess.NeedsConfirmation> = emptyList(),
    val error: String? = null,
)

/** One space of a run: its id and whether its files move in (Move in) or stay (Leave). */
data class BringChoice(val spaceId: String, val move: Boolean)

/** The wording of a result, one place for the screen and the tests. */
object BringInWords {
    fun summary(moved: Int, kept: Int, failed: Int, asks: Int): String = when {
        failed > 0 -> "Brought $moved ${files(moved)} into your Keepfin folder. $failed ${files(failed)} stayed where ${if (failed == 1) "it was" else "they were"}."
        moved > 0 -> "Brought $moved ${files(moved)} into your Keepfin folder." + if (asks > 0) " Android asks before it removes the originals." else ""
        kept > 0 -> "New files now go into your Keepfin folder. The files you left stay where they are."
        else -> "New files now go into your Keepfin folder."
    }

    private fun files(n: Int) = if (n == 1) "file" else "files"
}

/**
 * "Bring them in" for the screens (dev-docs/plans/KEEPFIN-FOLDER-MOVE.md, D4): the linked spaces, a Move in or Leave for each, and the run in the
 * foreground service. The choices are kept in `bring-in.json` until each space is done, so a run that was killed continues with what is left.
 */
internal class BringInController(private val app: KeepfinApplication) : ForegroundJob {
    private val journal = File(app.filesDir, "bring-in.json")
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "keepfin-bring-in").also { it.isDaemon = true } }
    private val stopFlag = AtomicBoolean(false)
    private val state = MutableStateFlow(BringInUi(stage = if (journal.isFile) BringStage.INTERRUPTED else BringStage.NONE))
    val ui: StateFlow<BringInUi> = state.asStateFlow()

    private val library get() = (app.engineApi as? MediaEngine)?.libraryApi

    /** Asks the library which spaces are outside the folder (blocking work: runs on the worker). */
    fun load() {
        worker.execute { state.update { it.copy(spaces = runCatching { library?.outsideSpaces().orEmpty() }.getOrDefault(emptyList())) } }
    }

    fun start(choices: List<BringChoice>) {
        if (state.value.stage == BringStage.RUNNING || choices.isEmpty()) return
        writeJournal(choices)
        state.value = state.value.copy(stage = BringStage.RUNNING, done = 0, total = 0, current = "", moved = 0, kept = 0, failed = emptyList(), asks = emptyList(), error = null)
        FolderMoveService.start(app, KIND)
    }

    /** Continue what a killed run left. */
    fun resume() {
        if (state.value.stage == BringStage.RUNNING || !journal.isFile) return
        state.update { it.copy(stage = BringStage.RUNNING, error = null) }
        FolderMoveService.start(app, KIND)
    }

    override fun runBlocking() {
        val lib = library
        val choices = readJournal()
        if (lib == null || choices.isEmpty()) { journal.delete(); state.update { it.copy(stage = BringStage.NONE) }; return }
        stopFlag.set(false)
        var moved = state.value.moved
        var kept = state.value.kept
        val failed = ArrayList(state.value.failed)
        val asks = ArrayList(state.value.asks)
        var stopped = false
        try {
            for (choice in choices) {
                if (stopFlag.get()) { stopped = true; break }
                val result: BringInResult = lib.bringIn(choice.spaceId, choice.move, { done, total, current -> state.update { it.copy(done = done, total = total, current = current) } }, { stopFlag.get() })
                moved += result.moved; kept += result.kept; failed += result.failed
                result.confirm?.let { asks += it }
                // a space that was cut short stays in the journal so the next run finishes it
                if (stopFlag.get()) { stopped = true; break }
                writeJournal(readJournal().filter { it.spaceId != choice.spaceId })
                state.update { it.copy(moved = moved, kept = kept, failed = failed.toList(), asks = asks.toList()) }
            }
            if (!stopped) journal.delete()
            state.update { it.copy(stage = if (stopped) BringStage.INTERRUPTED else BringStage.DONE, moved = moved, kept = kept, failed = failed.toList(), asks = asks.toList(), spaces = runCatching { lib.outsideSpaces() }.getOrDefault(emptyList())) }
        } catch (e: Exception) {
            state.update { it.copy(stage = BringStage.INTERRUPTED, error = e.message ?: "Keepfin could not bring the files in.", moved = moved, kept = kept, failed = failed.toList()) }
        }
    }

    override fun requestStop() { stopFlag.set(true) }

    override val title: String get() = "Bringing files into your Keepfin folder"

    override fun notice(): Pair<String, Int?> {
        val s = state.value
        if (s.total == 0) return "Getting ready" to null
        return "${s.done} of ${s.total} files" to (s.done * 100 / s.total).coerceIn(0, 100)
    }

    override fun finishedNotice(): Pair<String, String> = when (state.value.stage) {
        BringStage.DONE -> "Files brought in" to BringInWords.summary(state.value.moved, state.value.kept, state.value.failed.size, state.value.asks.size)
        else -> "Bringing files in stopped" to "Open Keepfin to carry on where it stopped."
    }

    /** The system answered the question about removing originals: the library settles, and the next question (if any) is asked. */
    fun answered(ask: LibraryAccess.NeedsConfirmation, granted: Boolean) {
        worker.execute {
            runCatching { library?.confirmationResult(ask.ids, granted) }
            state.update { it.copy(asks = it.asks.filter { a -> a !== ask }, spaces = runCatching { library?.outsideSpaces().orEmpty() }.getOrDefault(it.spaces)) }
        }
    }

    /** Leaves the finished dialog. */
    fun dismiss() { if (state.value.stage == BringStage.DONE) state.value = BringInUi(spaces = state.value.spaces) }

    private fun writeJournal(choices: List<BringChoice>) {
        if (choices.isEmpty()) { journal.delete(); return }
        val array = JSONArray()
        for (c in choices) array.put(JSONObject().put("id", c.spaceId).put("move", c.move))
        writeLocalAtomic(journal, JSONObject().put("spaces", array).toString().toByteArray(Charsets.UTF_8))
    }

    private fun readJournal(): List<BringChoice> = try {
        val array = JSONObject(journal.readText(Charsets.UTF_8)).getJSONArray("spaces")
        (0 until array.length()).map { BringChoice(array.getJSONObject(it).getString("id"), array.getJSONObject(it).optBoolean("move", true)) }
    } catch (e: Exception) {
        emptyList()
    }

    companion object { const val KIND = "bring" }
}
