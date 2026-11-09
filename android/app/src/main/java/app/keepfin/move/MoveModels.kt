package app.keepfin.move

import app.keepfin.core.spaces.folderMoveProblem
import app.keepfin.folder.writeLocalAtomic
import app.keepfin.setup.FolderPlaces
import app.keepfin.setup.SetupTexts
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

// Change location of the Keepfin folder (dev-docs/plans/KEEPFIN-FOLDER-MOVE.md, D4). The phone has no rename for picked folders, so the
// folder is copied file by file, checked, and the profile switches to the new place only after everything is verified. The old folder
// is never touched by the move; the person decides about it afterwards.

/** Where a move is. COPYING and FINISHING can be repeated after a stop or a crash; SWITCHED means the profile points at the new place. */
enum class MovePhase(val wire: String) {
    COPYING("copying"), FINISHING("finishing"), SWITCHED("switched");

    companion object { fun of(wire: String?) = entries.firstOrNull { it.wire == wire } ?: COPYING }
}

/**
 * One move, kept in `folder-move.json` until the person has decided about the old place. [created] are the top-level names this move made
 * in the new folder (it had to be empty): a stop removes exactly those.
 */
data class FolderMove(
    val id: String,
    val libraryId: String,
    val fromTree: String,
    val toTree: String,
    /** The tree document ids, for example `primary:Keepfin`: what the shared core's folder rules compare. */
    val fromDoc: String,
    val toDoc: String,
    val phase: MovePhase,
    val created: List<String>,
    val startedAt: Long,
)

/** The move file in `filesDir`, written atomically. */
class MoveStore(private val file: File) {
    @Synchronized fun read(): FolderMove? = try {
        val o = JSONObject(file.readText(Charsets.UTF_8))
        val made = o.optJSONArray("created") ?: JSONArray()
        FolderMove(
            o.getString("id"), o.getString("libraryId"), o.getString("fromTree"), o.getString("toTree"), o.getString("fromDoc"), o.getString("toDoc"),
            MovePhase.of(o.optString("phase")), (0 until made.length()).map { made.getString(it) }, o.optLong("startedAt"),
        )
    } catch (e: Exception) {
        null
    }

    @Synchronized fun write(move: FolderMove) {
        val json = JSONObject().put("id", move.id).put("libraryId", move.libraryId).put("fromTree", move.fromTree).put("toTree", move.toTree)
            .put("fromDoc", move.fromDoc).put("toDoc", move.toDoc).put("phase", move.phase.wire).put("created", JSONArray(move.created)).put("startedAt", move.startedAt)
        writeLocalAtomic(file, json.toString(2).toByteArray(Charsets.UTF_8))
    }

    @Synchronized fun clear() { file.delete() }
}

/** What the screens show while a move copies. [phase]: `copying`, `finishing` (library paused, last pass), `switching`. */
data class MoveProgress(val phase: String, val done: Int, val total: Int, val bytesDone: Long, val bytesTotal: Long, val current: String?)

/** The answer to a picked folder: what would move, and a plain reason when it cannot. */
data class MoveCheck(val toTree: String, val label: String, val files: Int, val bytes: Long, val freeBytes: Long?, val problem: String?) {
    val fits: Boolean get() = MovePlanning.fits(bytes, freeBytes)
}

/** Pure rules of a move (tested on the JVM). */
object MovePlanning {
    /** Room kept free besides the files, for the one file being staged and the system. */
    const val MARGIN_BYTES = 256L shl 20

    const val NOT_EMPTY = "Choose an empty folder, or a new one."

    /** Why the folder at [toDoc] cannot take the folder at [fromDoc] (by where they are and what is in the new one), or null when it can. */
    fun problem(fromDoc: String, toDoc: String, targetHasEntries: Boolean): String? = when {
        FolderPlaces.forbidden(toDoc) -> SetupTexts.FORBIDDEN
        else -> folderMoveProblem(fromDoc, toDoc) ?: if (targetHasEntries) NOT_EMPTY else null
    }

    fun fits(bytes: Long, freeBytes: Long?): Boolean = freeBytes == null || bytes + MARGIN_BYTES <= freeBytes

    private const val RECORDS = ".keepfin"
    private val immutableDirs = setOf("ops", "checkpoints", "art", "trash", "merge")

    fun isRecord(rel: String): Boolean = rel == RECORDS || rel.startsWith("$RECORDS/")

    /** The library's own files, shown under one name (their names mean nothing to people). */
    fun shownName(rel: String): String = if (isRecord(rel)) "Your library records" else rel

    /** Segments, checkpoints, artwork, trashed files and merge records have unique names and never change: the same size means the same file. */
    fun isImmutableRecord(rel: String): Boolean = isRecord(rel) && rel.split('/').drop(1).dropLast(1).any { it in immutableDirs }

    /** The few small records that are rewritten in place (folder.json, manifests): equal only when their bytes are. */
    fun isMutableRecord(rel: String): Boolean = isRecord(rel) && !isImmutableRecord(rel) && !existenceOnly(rel)

    /** The system may fill a new `.nomedia` with text: only that it exists counts. */
    fun existenceOnly(rel: String): Boolean = rel.substringAfterLast('/') == ".nomedia"

    /**
     * Whether the file already in the new place ([there] bytes) is the copy of the file in the old one ([size], [modifiedMs]), so it is not copied again.
     * Mutable records are compared by content by the caller. [cutoff] is when the move began: a media file changed after that is copied again.
     */
    fun alreadyCopied(rel: String, size: Long, modifiedMs: Long, there: Long, cutoff: Long): Boolean = when {
        existenceOnly(rel) -> true
        there != size -> false
        isImmutableRecord(rel) -> true
        isMutableRecord(rel) -> false
        else -> modifiedMs <= cutoff
    }

    /** The name directly inside the Keepfin folder that [rel] belongs to. */
    fun topOf(rel: String): String = rel.substringBefore('/')
}
