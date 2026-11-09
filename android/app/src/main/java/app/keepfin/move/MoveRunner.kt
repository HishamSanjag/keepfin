package app.keepfin.move

import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError

/** What a move needs from the app around it (the phone's implementation is [AndroidMoveHost]; tests stand in a fake). */
interface MoveHost {
    fun open(tree: String): FolderAccess
    fun now(): Long

    /** Stops the library writing and puts what it holds into the folder: downloads and edits wait until [release]. */
    fun quiesce(libraryId: String)

    /** Lifts what [quiesce] did. Safe to call when nothing is held. */
    fun release(libraryId: String)

    /** The profile now points at the new place (one atomic write). Safe to repeat. */
    fun switchProfile(move: FolderMove)

    /** The library opens from the new place, and everything that remembered an address in the old place follows. Safe to repeat. */
    fun reopen(move: FolderMove)
}

sealed class MoveOutcome {
    /** The profile points at the new place; the old folder is untouched and waits for the person's decision. */
    object Done : MoveOutcome()

    /** A stop was asked for; the journal stays, so the move can continue or be discarded. */
    object Stopped : MoveOutcome()

    class Failed(val message: String) : MoveOutcome()
}

/**
 * The move as a sequence that survives being killed at any point:
 *  1. COPYING: everything but the library's records is copied while the app is still in use.
 *  2. FINISHING: the library is paused and flushed, then a last pass copies what changed and the records; every file is checked.
 *  3. SWITCHED (written first), then the profile switches and the library reopens from the new place.
 * The journal says which step to repeat. A run killed before step 3 never touched the profile; one killed after the journal write finishes the
 * switch at the next start. The old folder is not changed by any of it.
 */
class MoveRunner(private val host: MoveHost, private val store: MoveStore) {
    fun run(start: FolderMove, progress: (MoveProgress) -> Unit, stopped: () -> Boolean): MoveOutcome {
        var move = start
        if (move.phase == MovePhase.SWITCHED) return finish(move, progress)
        var held = false
        try {
            val mover = FolderMover(host.open(move.fromTree), host.open(move.toTree), move.startedAt, stopped, progress)
            val note = { top: String -> if (top !in move.created) { move = move.copy(created = move.created + top); store.write(move) } }
            mover.copy(mover.inventory(), records = false, phase = "copying", onCreated = note)
            held = true
            host.quiesce(move.libraryId)
            move = move.copy(phase = MovePhase.FINISHING)
            store.write(move)
            val last = mover.inventory()
            mover.copy(last, records = true, phase = "finishing", onCreated = note)
            mover.clearLeftovers(last)
            mover.verify(last)?.let { return MoveOutcome.Failed(it) }
            move = move.copy(phase = MovePhase.SWITCHED)
            store.write(move)
            held = false // finish lifts it after the library has reopened
            return finish(move, progress)
        } catch (e: Throwable) {
            if (isStop(e)) return MoveOutcome.Stopped
            return MoveOutcome.Failed(describe(e))
        } finally {
            if (held) host.release(move.libraryId)
        }
    }

    private fun finish(move: FolderMove, progress: (MoveProgress) -> Unit): MoveOutcome {
        progress(MoveProgress("switching", 0, 0, 0, 0, null))
        try {
            host.switchProfile(move)
            host.reopen(move)
            return MoveOutcome.Done
        } catch (e: Throwable) {
            return MoveOutcome.Failed(describe(e))
        } finally {
            host.release(move.libraryId)
        }
    }

    /**
     * Gives the move up before the switch: what it copied into the new place is removed (only the names it made there) and the journal goes.
     * The old folder is as it was. Returns a sentence when the new place could not be tidied (the journal stays so it is tried again).
     */
    fun discard(move: FolderMove): String? {
        check(move.phase != MovePhase.SWITCHED) { "The move is finished." }
        host.release(move.libraryId)
        try {
            val to = host.open(move.toTree)
            for (name in move.created) to.remove(name)
        } catch (e: FolderError.NeedsAccess) {
            // the new place is gone for good: nothing left to tidy
        } catch (e: FolderError.Unavailable) {
            return "The new place is not available right now, so the files copied there could not be removed. Try again when it is back."
        } catch (e: Exception) {
            return "The files copied into the new place could not all be removed: ${describe(e)}"
        }
        store.clear()
        return null
    }

    companion object {
        fun describe(e: Throwable): String = when (e) {
            is FolderError.NeedsAccess -> "Keepfin lost access to a folder. Choose it again in Storage."
            is FolderError.Unavailable -> "A folder is not available right now (is the memory card in?). Nothing is missing."
            is FolderError.DiskFull -> "There is not enough room in the new place."
            else -> e.message ?: "Something went wrong."
        }
    }
}
