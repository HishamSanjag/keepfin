package app.keepfin.ui.model

import android.net.Uri
import app.keepfin.EngineApi
import app.keepfin.EngineSettings
import app.keepfin.LibraryApi
import app.keepfin.LibraryItem
import app.keepfin.Space
import app.keepfin.UndoLogic
import app.keepfin.ui.StudioViewModel.Companion.friendly
import app.keepfin.ui.library.LibraryRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What [SpaceActions] uses of the ViewModel, including the pending "Saved to Music" notice and the finished jobs waiting for it. */
internal interface SpaceActionsHost : Notices {
    val scope: CoroutineScope
    val engine: EngineApi
    val lib: LibraryApi
    val libraryRoute: LibraryRoute
    var announceJob: kotlinx.coroutines.Job?
    val finishedNow: ArrayList<app.keepfin.Job>
    fun spaceName(id: String?): String?
    fun updateSettings(transform: (EngineSettings) -> EngineSettings)
    fun play(items: List<LibraryItem>, index: Int)
    fun openLibraryRoot(route: LibraryRoute)
    fun showInLibrary(space: String?)
    fun undoCall(work: LibraryApi.() -> Unit)
}

/** The actions on spaces: importing files, saving, ordering, pinning and removing a space, and the "Saved to Music" notice. */
internal class SpaceActions(private val host: SpaceActionsHost) {
    /** Home shows pinned spaces; this flips one. Works for built-ins too. */
    fun pinSpace(id: String, pinned: Boolean) = host.updateSettings { settings ->
        val library = settings.library
        settings.copy(library = library.copy(spaces = library.spaces.map { if (it.id == id) it.copy(pinned = pinned) else it }))
    }

    /** Copies picked documents into [space]; reports what happened in plain words. */
    fun importFiles(uris: List<Uri>, space: String) {
        if (uris.isEmpty()) return
        host.scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { host.lib.importFiles(uris.map { it.toString() }, space) }
                val name = host.spaceName(space) ?: "the space"
                host.say(buildString {
                    if (result.added > 0) append(if (result.added == 1) "Added 1 file to $name" else "Added ${result.added} files to $name")
                    if (result.duplicates > 0) { if (isNotEmpty()) append(". "); append(if (result.duplicates == 1) "1 was already in your library" else "${result.duplicates} were already in your library") }
                    if (result.failed > 0) { if (isNotEmpty()) append(". "); append(result.errors.firstOrNull() ?: "${result.failed} could not be added") }
                    if (isEmpty()) append("Nothing was added")
                })
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, "Could not add these files.")) }
        }
    }

    fun adoptEarlierFiles() {
        host.scope.launch {
            try {
                val count = withContext(Dispatchers.IO) { host.lib.adoptEarlierFiles() }
                host.say(if (count == 0) "No earlier Keepfin files were found" else if (count == 1) "Found 1 earlier file" else "Found $count earlier files")
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, "Could not look for earlier files.")) }
        }
    }

    /** Validates and saves a space; [onResult] gets null on success or a readable problem. */
    fun saveSpace(space: Space, moveExisting: Boolean, onResult: (String?) -> Unit) {
        host.scope.launch {
            val problem = try { withContext(Dispatchers.IO) { host.lib.saveSpace(space, moveExisting) }; null }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { e.message ?: "Check the space and try again." }
            catch (e: Exception) { friendly(e, "Could not save this space.") }
            onResult(problem)
        }
    }

    /** Persists the Spaces order shown on Library Home (the order of the spaces list in the library settings). */
    fun reorderSpaces(order: List<String>) = host.updateSettings { settings ->
        val library = settings.library
        try { settings.copy(library = library.copy(spaces = app.keepfin.SpaceLogic.reorder(library.spaces, order))) } catch (_: IllegalArgumentException) { settings }
    }

    /**
     * Removes a space (its files stay). The space as it is now, its place in the order and the routes are taken first: Undo puts the
     * space back with the same id and settings, at the same place, and points the routes at it again if they still read what the
     * removal made of them.
     */
    fun removeSpace(id: String, name: String) {
        // Downloads, Music, Videos and Favorites are kept: they can be renamed but never removed
        if (app.keepfin.core.spaces.Spaces.isKept(id)) { host.say("Downloads, Music, Videos and Favorites can be renamed but not removed."); return }
        val now = host.engine.settings.value.library
        val space = now.spaces.firstOrNull { it.id == id }
        val order = now.spaces.map { it.id }
        val routes = now.musicRoute to now.otherRoute
        val afterRemoval = UndoLogic.routesAfterRemoval(now.spaces.filter { it.id != id }, now.musicRoute, now.otherRoute, id)
        host.scope.launch {
            try {
                withContext(Dispatchers.IO) { host.lib.deleteSpace(id) }
                val text = "Removed the space “$name”. Its files stay where they are."
                if (space == null) host.say(text) else host.say(text, "Undo") { restoreSpace(space, order, routes, afterRemoval) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, "Could not remove this space.")) }
        }
        val route = host.libraryRoute
        if (route is LibraryRoute.Space && route.id == id || route is LibraryRoute.Folder && route.space == id) host.openLibraryRoot(LibraryRoute.Home)
    }

    private fun restoreSpace(space: Space, order: List<String>, routes: Pair<String, String>, afterRemoval: Pair<String, String>) = host.undoCall {
        saveSpace(space)
        val current = host.engine.settings.value.library
        val ids = current.spaces.map { it.id }.let { if (space.id in it) it else it + space.id }
        spaceReorder(UndoLogic.reinsert(order, ids, space.id))
        val target = UndoLogic.routesAfterRestore(space.id, routes, afterRemoval, current.musicRoute to current.otherRoute)
        if (target != current.musicRoute to current.otherRoute) host.engine.updateSettings { s -> s.copy(library = s.library.copy(musicRoute = target.first, otherRoute = target.second)) }
    }

    fun announceSaved(job: app.keepfin.Job) {
        synchronized(host.finishedNow) { host.finishedNow.add(job) }
    }

    fun flushSaved() {
        host.announceJob?.cancel()
        host.announceJob = host.scope.launch {
            delay(1200)
            val done = synchronized(host.finishedNow) { host.finishedNow.toList().also { host.finishedNow.clear() } }
            val space = done.mapNotNull { it.space }.distinct().singleOrNull()
            val name = host.spaceName(space)
            if (done.isEmpty() || space == null || name == null) return@launch
            val item = withContext(Dispatchers.IO) { done.singleOrNull()?.let { j -> host.lib.browse.byJob(j.id) } }
            host.sayWithTwo(
                if (done.size == 1) "Saved to $name" else "Saved ${done.size} items to $name",
                actionLabel = if (item != null) "Play" else null, action = if (item != null) ({ host.play(listOf(item), 0) }) else null,
                secondLabel = "Show in Library", secondAction = { host.showInLibrary(space) },
            )
        }
    }
}
