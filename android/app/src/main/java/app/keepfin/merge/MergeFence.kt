package app.keepfin.merge

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The fences of contract section 4, held process-wide while a combine or a separate runs on this device:
 *  - the fenced library is not served or synced (its [app.keepfin.net.StoreNetLibrary] reports closed) and its executors stop;
 *  - its writer accepts only the merge's own batches ([blocksWrites]);
 *  - new downloads wait ([downloadsWait]; those already running finish) and copies do not start ([copiesWait]);
 *  - play counts are buffered and written afterwards ([bufferPlayback], [drainPlayback]); playback itself goes on.
 * Nothing here is persisted: a restart starts unfenced, and recovery fences again before the library opens when a merge is unfinished.
 */
object MergeFence {
    private val libraries: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Libraries whose writer and executors are quiet but whose segments still go to other devices (Separate again publishes to the peer). */
    private val quiet: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val releaseListeners = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var all = false

    class Play(val libraryId: String, val itemId: String, val position: Double?, val finished: Boolean)
    private val plays = ArrayList<Play>()

    /** Fences [libraryIds] (and the app-wide download and copy pauses). */
    fun enter(vararg libraryIds: String) {
        libraries.addAll(libraryIds)
        all = true
    }

    /** Like [enter] but the library is still served and synced: its writer takes only the merge's batches and its executors stop. */
    fun enterQuiet(vararg libraryIds: String) {
        quiet.addAll(libraryIds)
        all = true
    }

    /** Lifts the fences of [libraryIds]; the app-wide pauses lift with the last one. Listeners (the download queue) run so waiting work starts. */
    fun exit(vararg libraryIds: String) {
        libraries.removeAll(libraryIds.toSet())
        quiet.removeAll(libraryIds.toSet())
        if (libraries.isEmpty() && quiet.isEmpty()) all = false
        for (l in releaseListeners) runCatching { l() }
    }

    fun clear() {
        libraries.clear()
        quiet.clear()
        all = false
        synchronized(plays) { plays.clear() }
    }

    fun isFenced(libraryId: String): Boolean = libraryId in libraries

    /** True while a library of this device is fenced: the library writer takes only the merge's own batches. */
    fun blocksWrites(libraryId: String): Boolean = libraryId in libraries || libraryId in quiet

    /** True while the library's executors (a peer writing a copy into this folder, a download placing files) must not run. */
    fun blocksExecutors(libraryId: String): Boolean = libraryId in libraries || libraryId in quiet

    val downloadsWait: Boolean get() = all
    val copiesWait: Boolean get() = all
    val active: Boolean get() = all

    /** Registers something to run when a fence lifts (the download queue starts what waited). Returns the remover. */
    fun onRelease(listener: () -> Unit): () -> Unit {
        releaseListeners += listener
        return { releaseListeners -= listener }
    }

    fun bufferPlayback(libraryId: String, itemId: String, position: Double?, finished: Boolean) {
        synchronized(plays) {
            // many progress ticks of one item are one: only the last matters, a finished play is kept
            plays.removeAll { it.libraryId == libraryId && it.itemId == itemId && !it.finished }
            plays += Play(libraryId, itemId, position, finished)
        }
    }

    /** The buffered plays of [libraryId], oldest first, removed from the buffer. */
    fun drainPlayback(libraryId: String): List<Play> = synchronized(plays) {
        val mine = plays.filter { it.libraryId == libraryId }
        plays.removeAll { it.libraryId == libraryId }
        mine
    }

    /** Takes every buffered play out (they are re-keyed to another library by the caller). */
    fun drainAllPlayback(): List<Play> = synchronized(plays) { plays.toList().also { plays.clear() } }
}

/** A write was refused because the library is being combined or separated. */
class LibraryBusyException : IllegalStateException("Keepfin is combining libraries. Try again when it has finished.")
