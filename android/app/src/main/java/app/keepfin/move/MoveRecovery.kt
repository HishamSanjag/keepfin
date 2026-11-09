package app.keepfin.move

import android.content.Context
import app.keepfin.folder.ProfileStore
import java.io.File

/**
 * What a killed move leaves, settled before the library opens (like a combine). The journal is written SWITCHED before the profile changes, so a
 * run killed in between finishes the switch here: the library then opens from the new place, where everything was already checked. A move that
 * had not reached that point changes nothing: the library opens from the old place and the move waits to be continued or stopped.
 */
object MoveRecovery {
    /** True when the profile was switched now. */
    fun settle(store: MoveStore, profiles: ProfileStore): Boolean {
        val move = store.read()?.takeIf { it.phase == MovePhase.SWITCHED } ?: return false
        val profile = profiles.load().profiles.firstOrNull { it.libraryId == move.libraryId } ?: return false
        if (profile.folderTreeUri == move.toTree) return false
        profiles.add(profile.copy(folderTreeUri = move.toTree), makeCurrent = false)
        return true
    }

    /**
     * Called when the app starts: a move left in the journal is read once the engine exists, so a finished switch repoints the download history
     * and an unfinished move is known to Storage (which shows Continue). Nothing starts by itself.
     */
    internal fun afterOpen(app: app.keepfin.KeepfinApplication) {
        if (!File(app.filesDir, "folder-move.json").isFile) return
        Thread {
            var waited = 0
            while (!app.engineCreated && waited++ < 600) Thread.sleep(50)
            if (app.engineCreated) runCatching { app.folderMove }
        }.also { it.isDaemon = true }.start()
    }

    /** Called by the library selector before it reads the profile. */
    fun beforeOpen(context: Context) {
        val file = File(context.filesDir, "folder-move.json")
        if (!file.isFile) return
        try { settle(MoveStore(file), ProfileStore(File(context.filesDir, "profiles.json"))) } catch (e: Throwable) { android.util.Log.w("KeepfinMove", "recovery before open stopped", e) }
    }
}
