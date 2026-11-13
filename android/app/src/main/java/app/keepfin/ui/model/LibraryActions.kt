package app.keepfin.ui.model

import android.app.Application
import android.net.Uri
import app.keepfin.CollectionKind
import app.keepfin.CollectionLogic
import app.keepfin.FollowUpdate
import app.keepfin.LibraryAccess
import app.keepfin.LibraryApi
import app.keepfin.LibraryCollection
import app.keepfin.LibraryItem
import app.keepfin.NoticeAction
import app.keepfin.TagEdit
import app.keepfin.TrashEntry
import app.keepfin.UndoLogic
import app.keepfin.ui.Destination
import app.keepfin.ui.StudioViewModel.Companion.friendly
import app.keepfin.ui.UiMessage
import app.keepfin.ui.library.LibraryRoute
import app.keepfin.ui.library.PendingAccess
import app.keepfin.ui.library.collectionRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

/** Lives as long as the app: erases of Delete for good that must not stop with a screen. */
private val eraseScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

/**
 * What [LibraryActions] uses of the ViewModel. The system consent state and the other Compose state stay declared there (the screens
 * read it there); the flow reads and writes it through these.
 */
internal interface LibraryActionsHost : Notices {
    val scope: CoroutineScope
    val lib: LibraryApi
    val _messages: MutableSharedFlow<UiMessage>
    val libraryRoute: LibraryRoute
    var access: PendingAccess?
    var deleting: Boolean
    var trashEntries: List<TrashEntry>?
    var trashBusy: String?
    var removingDuplicates: Boolean
    var duplicatesProgress: Pair<Int, Int>?
    var libraryAdding: String?
    fun <T : Application> getApplication(): T
    fun spaceName(id: String?): String?
    fun navigate(target: Destination)
    fun closeSubPage()
    fun back(): Boolean
    fun openLibraryRoot(route: LibraryRoute)
    fun openLibraryDetail(route: LibraryRoute)
    fun showInLibrary(space: String?)
}

/**
 * The library actions of the ViewModel: moving, deleting (with the trash and Undo), duplicates, renaming, tags, favorites, playlists,
 * collections and what a follow check says. The system consent state and the other Compose state stay in the ViewModel and are
 * reached through [LibraryActionsHost].
 */
internal class LibraryActions(private val host: LibraryActionsHost) {
    fun libraryCall(failure: String, work: LibraryApi.() -> Unit) {
        host.scope.launch {
            try { withContext(Dispatchers.IO) { host.lib.work() } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, failure)) }
        }
    }

    /** [prefix] starts the notice when the call fails ("Could not undo: " for an Undo). */
    fun withAccess(failure: String, ids: List<String>, delete: Boolean, done: () -> Unit, prefix: String = "", call: LibraryApi.() -> LibraryAccess) {
        host.scope.launch {
            try {
                when (val result = withContext(Dispatchers.IO) { host.lib.call() }) {
                    LibraryAccess.Done -> done()
                    is LibraryAccess.NeedsConfirmation -> host.access = PendingAccess(result.intentSender, result.ids, delete) { withAccess(failure, ids, delete, done, prefix, call) }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(prefix + friendly(e, failure)) }
        }
    }

    /** Runs the Undo of an action off the main thread; a failure is said as "Could not undo: <reason>". */
    fun undoCall(work: LibraryApi.() -> Unit) {
        host.scope.launch {
            try { withContext(Dispatchers.IO) { host.lib.work() } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { host.say("Could not undo: " + friendly(e, "Something went wrong.")) }
        }
    }

    /** The system dialog was answered. Writes run again on approval; deletes tell the library either way. */
    fun accessResult(granted: Boolean) {
        val pending = host.access ?: return
        host.access = null
        if (pending.delete) {
            host.scope.launch {
                try { withContext(Dispatchers.IO) { host.lib.confirmationResult(pending.ids, granted) } } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                // the files Android asked about are removed from the phone itself (no Keepfin trash), so no Undo
                host.say(if (granted) UndoLogic.deletedNotice(emptyList(), pending.ids.size, 0) else "Nothing was deleted")
                host.deleting = false
                pending.afterDelete?.invoke(granted)
            }
        } else if (granted) pending.retry() else host.say("Nothing was changed")
    }

    /**
     * Moves files to a space. The space each one is in is read before the move runs, never from the screen, so Undo can send every
     * file back where it came from (a system consent may be asked again, as for the move).
     */
    fun moveItems(all: List<String>, space: String) {
        val ids = all.filter { !app.keepfin.isDeviceItem(it) }
        // songs already on the phone stay in place (DEVICE-MUSIC M7)
        val stay = if (ids.size < all.size) "Songs from this phone stay where they are." else ""
        if (ids.isEmpty()) { host.say(stay); return }
        val name = host.spaceName(space) ?: "that space"
        host.scope.launch {
            val origins = try { withContext(Dispatchers.IO) { host.lib.browse.byIds(ids).associate { it.id to it.space as String? } } } catch (e: CancellationException) { throw e } catch (_: Exception) { emptyMap() }
            val moving = origins.filter { it.value != null && it.value != space }
            val text = (if (ids.size == 1) "Moved to $name" else "Moved ${ids.size} files to $name") + if (stay.isEmpty()) "" else " $stay"
            withAccess("Could not move these files.", ids, false, { if (moving.isEmpty()) host.say(text) else host.say(text, "Undo") { undoMove(moving, space) } }) { move(ids, space) }
        }
    }

    /** Undo of a move: the files that are still in [target] go back to the space each came from; a file moved again since stays. */
    private fun undoMove(origins: Map<String, String?>, target: String) {
        host.scope.launch {
            try {
                val groups = withContext(Dispatchers.IO) {
                    val now = host.lib.browse.byIds(origins.keys.toList()).associate { it.id to it.space }
                    UndoLogic.moveBack(origins, target) { now[it] }
                }
                moveBackEach(groups.toList())
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host.say("Could not undo: " + friendly(e, "Something went wrong.")) }
        }
    }

    /** One origin after the other, so a system consent for one is answered before the next is asked. */
    private fun moveBackEach(groups: List<Pair<String, List<String>>>) {
        val (origin, ids) = groups.firstOrNull() ?: return
        withAccess("Something went wrong.", ids, false, { moveBackEach(groups.drop(1)) }, "Could not undo: ") { move(ids, origin) }
    }

    /**
     * Deletes files. Files of the Keepfin folder go to its trash, with Undo; [forGood] erases them from the trash as soon as the
     * Undo notice goes (and at the next purge if the app closes first), instead of keeping them there for 30 days.
     */
    fun deleteItems(ids: List<String>, forGood: Boolean = false) {
        if (host.deleting || ids.isEmpty()) return
        host.deleting = true
        host.scope.launch {
            try {
                // the title is read before the delete (the item is gone after it)
                val titles = if (ids.size == 1) withContext(Dispatchers.IO) { host.lib.browse.byIds(ids).map { it.title } } else emptyList()
                when (val result = withContext(Dispatchers.IO) { host.lib.deleteItems(ids) }) {
                    LibraryAccess.Done -> {
                        // Files of the Keepfin folder went to its trash and can come back; the others are gone for good, and the notice says so
                        val back = withContext(Dispatchers.IO) { host.lib.trashedAmong(ids).also { if (forGood) host.lib.markForErase(it) } }
                        val text = if (forGood) UndoLogic.deletedForGoodNotice(titles, ids.size) else UndoLogic.deletedNotice(titles, ids.size, back.size)
                        when {
                            back.isEmpty() -> host.say(text)
                            forGood -> host._messages.tryEmit(UiMessage(text, "Undo", { restoreDeleted(back) }, onGone = { eraseForGood(back) }))
                            else -> host.say(text, "Undo") { restoreDeleted(back) }
                        }
                    }
                    is LibraryAccess.NeedsConfirmation -> host.access = PendingAccess(result.intentSender, result.ids, true) { }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, "Could not delete these files.")) }
            finally { if (host.access == null) host.deleting = false }
        }
    }

    /** The Undo of a Delete for good ran out: its files leave the Keepfin trash now (a failure leaves the mark for the next purge). */
    private fun eraseForGood(ids: List<String>) {
        // not the screen's scope: the erase must finish even when the notice ended because the screen is going away
        val lib = host.lib
        eraseScope.launch { runCatching { lib.eraseTrashed(ids) } }
    }

    /** Undo of a delete: the files come back from the Keepfin trash with their item (tags, favourite, collections); what cannot is said. */
    private fun restoreDeleted(ids: List<String>) {
        host.scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { host.lib.restoreItems(ids) }
                UndoLogic.restoreProblem(ids.size, result)?.let { host.say(it) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host.say("Could not undo: " + friendly(e, "Something went wrong.")) }
        }
    }

    private var trashRead: Job? = null

    /**
     * Reads the trash again (off the main thread). Settings › Storage & copies and the Trash page call it when the library changes; a burst of
     * changes (an import, a scan) makes one read, after the last of them.
     */
    fun refreshTrash() {
        trashRead?.cancel()
        trashRead = host.scope.launch {
            delay(300)
            val lib = host.lib
            val read = withContext(Dispatchers.IO) { if (lib.keepsTrash) runCatching { lib.trashEntries() }.getOrDefault(emptyList()) else emptyList() }
            host.trashEntries = read
        }
    }

    /** Brings entries back (no question asked); the notice says what came back, or why one could not. */
    fun restoreFromTrash(entries: List<app.keepfin.TrashEntry>) {
        if (entries.isEmpty()) return
        host.scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { host.lib.restoreItems(entries.map { it.id }) }
                val problems = result.problems.map { it.replace("another file has its name now", "a new file has its name now") }
                val what = when {
                    result.restored.isEmpty() -> null
                    result.restored.size == 1 && entries.size == 1 -> "Restored “${entries[0].title}”"
                    else -> "Restored ${result.restored.size} files"
                }
                host.say(listOfNotNull(what, problems.firstOrNull(),problems.drop(1).takeIf { it.isNotEmpty() }?.let { "and ${it.size} more could not come back" }).joinToString(". "))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host.say("Could not restore: " + friendly(e, "Something went wrong.")) }
            refreshTrash()
        }
    }

    /** Delete for good, from the trash: the entries' folders are erased now (the page asked first). */
    fun eraseFromTrash(entries: List<app.keepfin.TrashEntry>) {
        if (entries.isEmpty()) return
        host.trashBusy = "Deleting…"
        host.scope.launch {
            try {
                withContext(Dispatchers.IO) { host.lib.eraseFromTrash(entries.map { it.id }) }
                host.say(if (entries.size == 1) "Deleted “${entries[0].title}” for good" else "Deleted ${entries.size} files for good")
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host.say("Could not delete: " + friendly(e, "Something went wrong.")) }
            finally { host.trashBusy = null }
            refreshTrash()
        }
    }

    /** Erases everything the trash lists (the page asked with the count and size). */
    fun emptyTrash(count: Int) {
        host.trashBusy = "Emptying the trash…"
        host.scope.launch {
            try {
                withContext(Dispatchers.IO) { host.lib.emptyTrash() }
                host.say(if (count == 1) "Emptied the trash: 1 file erased" else "Emptied the trash: $count files erased")
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host.say("Could not empty the trash: " + friendly(e, "Something went wrong.")) }
            finally { host.trashBusy = null }
            refreshTrash()
        }
    }

    /**
     * Removes extra copies, five at a time. [onRemoved] hears each set of copies that is really gone (the page hides only those, so
     * a copy that could not be removed stays listed). When Android asks for a yes, the rest carries on after the answer.
     */
    fun removeDuplicates(removedToKept: Map<String, String>, onRemoved: (List<String>) -> Unit = {}) {
        if (host.deleting || host.removingDuplicates || host.access != null || removedToKept.isEmpty()) return
        host.removingDuplicates = true
        host.scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    for (c in host.lib.library.value.collections) {
                        if (c.itemIds.none { it in removedToKept }) continue
                        val order = c.itemIds.map { removedToKept[it] ?: it }.distinct()
                        val missing = order.filter { it !in c.itemIds }
                        if (missing.isNotEmpty()) host.lib.collectionAdd(c.id, missing)
                        host.lib.collectionReorder(c.id, order)
                        // the removed copies leave the collection now, not only when their files are gone
                        host.lib.collectionRemove(c.id, c.itemIds.filter { it in removedToKept })
                    }
                }
            } catch (e: CancellationException) { host.removingDuplicates = false; throw e } catch (e: Exception) { host.removingDuplicates = false; host.say(friendly(e, "Could not update the collections.")); return@launch }
            // a few at a time, so the page can say how far it is
            val ids = removedToKept.keys.toList()
            var done = 0
            var failed = 0
            var failure: Exception? = null
            host.duplicatesProgress = 0 to ids.size
            try {
                val chunks = ids.chunked(5)
                for ((index, chunk) in chunks.withIndex()) {
                    val result = try { withContext(Dispatchers.IO) { host.lib.deleteItems(chunk) } } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        // this set stays listed (the library refreshes what did go); the others are still tried
                        failed += chunk.size; failure = failure ?: e; null
                    }
                    when (result) {
                        null -> {}
                        LibraryAccess.Done -> { done += chunk.size; onRemoved(chunk); host.duplicatesProgress = done to ids.size }
                        is LibraryAccess.NeedsConfirmation -> {
                            // files Android wants a yes for: its request takes over, and after the answer the rest carries on
                            val rest = chunks.drop(index + 1).flatten().toSet()
                            host.access = PendingAccess(result.intentSender, result.ids, true, retry = {}, afterDelete = { granted ->
                                if (granted) onRemoved(chunk)
                                if (rest.isNotEmpty()) removeDuplicates(removedToKept.filterKeys { it in rest }, onRemoved)
                            })
                            break
                        }
                    }
                }
                when {
                    failed > 0 -> host.say(friendly(failure!!, if (failed == 1) "Could not remove 1 copy." else "Could not remove $failed copies."))
                    done > 0 -> host.say(if (done == 1) "Removed 1 extra copy" else "Removed $done extra copies")
                }
            } finally { host.removingDuplicates = false; host.duplicatesProgress = null }
        }
    }

    /** "Make a collection from this": a new collection named after the list (a folder, an album…) holding its songs, ready to open. */
    fun makeCollection(name: String, ids: List<String>, capped: Boolean = false) {
        if (ids.isEmpty()) return
        host.scope.launch {
            try {
                val id = withContext(Dispatchers.IO) { host.lib.collectionSave(null, name, ids) }
                host.sayWithTwo(
                    if (capped) "Made the playlist “$name” with its first ${ids.size} songs" else "Made the playlist “$name”",
                    "Undo", { undoMakeCollection(id) }, "Open", { host.navigate(Destination.LIBRARY); host.openLibraryDetail(LibraryRoute.UserPlaylist(id)) },
                )
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, "Could not make the playlist.")) }
        }
    }

    /** Renames a file. The old name is read before, so Undo can put it back (the same way a rename is made). */
    fun renameItem(id: String, name: String) {
        host.scope.launch {
            val old = try { withContext(Dispatchers.IO) { host.lib.item(id)?.let { fileStem(it) } } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            withAccess("Could not rename this file.", listOf(id), false, { if (old == null) host.say("Renamed") else host.say("Renamed", "Undo") { undoRenameItem(id, old) } }) { rename(id, name) }
        }
    }

    /** The file name of a library item without its extension, as the rename takes it. */
    private fun fileStem(item: LibraryItem): String {
        val name = if (item.uri.startsWith("keepfin-folder:")) item.uri.removePrefix("keepfin-folder:").substringAfterLast('/')
        else runCatching {
            host.getApplication<Application>().contentResolver.query(Uri.parse(item.uri), arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
        return (name ?: item.title).let { it.substringBeforeLast('.', it) }
    }

    private fun undoRenameItem(id: String, old: String) = withAccess("Something went wrong.", listOf(id), false, {}, "Could not undo: ") { rename(id, old) }

    /** Saves tags into the file. What the item held before is read first; Undo writes it back (not offered when the cover was replaced, which cannot be put back exactly). */
    fun editInfo(id: String, edit: TagEdit) {
        host.scope.launch {
            val inverse = try { withContext(Dispatchers.IO) { host.lib.browse.byIds(listOf(id)).firstOrNull()?.let { UndoLogic.editInverse(it, edit) } } } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            withAccess("Could not save these changes.", listOf(id), false, { if (inverse == null) host.say("Info saved") else host.say("Info saved", "Undo") { undoEditInfo(id, inverse) } }) { editInfo(id, edit) }
        }
    }

    private fun undoEditInfo(id: String, inverse: TagEdit) = withAccess("Something went wrong.", listOf(id), false, {}, "Could not undo: ") { editInfo(id, inverse) }

    /** Flips the favourite flag as it is saved now (not as the screen showed it); Undo sets it back. */
    fun favorite(item: LibraryItem) {
        host.scope.launch {
            try {
                val was = withContext(Dispatchers.IO) { (host.lib.browse.byIds(listOf(item.id)).firstOrNull()?.favorite ?: item.favorite).also { host.lib.setFavorite(item.id, !it) } }
                host.say(if (was) "Removed from favorites" else "Added to favorites", "Undo") { undoCall { setFavorite(item.id, was) } }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, "Could not change favorites.")) }
        }
    }
    /** Turns "Music on this phone" on or off (the permission was asked for before). */
    fun setDeviceMusic(on: Boolean) {
        host.scope.launch(Dispatchers.IO) { runCatching { host.lib.setDeviceMusic(on) } }
    }

    fun setDeviceLeftOut(folders: List<String>) {
        host.scope.launch(Dispatchers.IO) { runCatching { host.lib.setDeviceLeftOut(folders) } }
    }

    fun unhideDevice() {
        host.scope.launch(Dispatchers.IO) { runCatching { host.lib.unhideDevice() } }
    }

    /** "Remove from Keepfin" for music already on the phone: the files stay; Settings › Library shows them again. */
    fun hideDevice(ids: List<String>) {
        host.scope.launch {
            try {
                withContext(Dispatchers.IO) { host.lib.hideDeviceItems(ids) }
                host.say(if (ids.size == 1) "Removed from Keepfin. The file stays on your phone." else "Removed ${ids.size} songs from Keepfin. The files stay on your phone.")
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, "Could not remove it.")) }
        }
    }

    fun createPlaylist(name: String, ids: List<String>, space: String? = null, onDone: (String) -> Unit = {}) {
        host.scope.launch {
            try {
                val id = withContext(Dispatchers.IO) { host.lib.collectionSave(null, name.trim(), ids, space) }
                host.say("Made the playlist “${name.trim()}”", "Undo") { undoMakeCollection(id) }
                onDone(id)
            }
            catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, "Could not create the playlist.")) }
        }
    }

    /** Undo of a new collection: it is deleted (its songs stay in the library); if its page is open, the page closes. */
    private fun undoMakeCollection(id: String) {
        undoCall { collectionDelete(id) }
        leaveCollectionPage(id)
    }

    private fun leaveCollectionPage(id: String) {
        val route = host.libraryRoute
        if (route is LibraryRoute.Collection && route.id == id || route is LibraryRoute.UserPlaylist && route.id == id) host.back()
    }
    fun renamePlaylist(id: String, name: String, ids: List<String>) = renameCollection(id, name, ids)
    fun reorderPlaylist(id: String, name: String, ids: List<String>) = libraryCall("Could not reorder the playlist.") { savePlaylist(id, name, ids) }
    fun deletePlaylist(id: String) = removeCollection(id, null, leave = false)
    /**
     * Takes [ids] out of the collection [id] only (the files and the library keep them). The collection is read when this runs, never
     * from a list the page held, so a second removal or one after a drag starts from what is saved. Undo puts them back where they were.
     */
    fun removeFromCollection(id: String, ids: List<String>) {
        val c = host.lib.library.value.collections.firstOrNull { it.id == id } ?: return
        val removed = ids.filter { it in c.itemIds }.toSet()
        if (removed.isEmpty()) return
        val before = c.itemIds
        host.scope.launch {
            try {
                withContext(Dispatchers.IO) { host.lib.collectionRemove(id, removed.toList()) }
                host.say(if (removed.size == 1) "Removed from “${c.name}”" else "Removed ${removed.size} songs from “${c.name}”", "Undo") { restoreCollection(id, before, removed) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, "Could not remove from the playlist.")) }
        }
    }

    /** Undo of a removal: [removed] go back to their places in [before]; what changed in the collection since stays. */
    private fun restoreCollection(id: String, before: List<String>, removed: Set<String>) = libraryCall("Could not put them back.") {
        val now = library.value.collections.firstOrNull { it.id == id }?.itemIds ?: return@libraryCall
        val present = now.toSet()
        collectionAdd(id, removed.filter { it !in present })
        collectionReorder(id, CollectionLogic.restoreOrder(before, now, removed))
    }

    /** Adds songs to a collection. The songs already in it are noted first: Undo removes only the ones this added. */
    fun addToPlaylist(playlist: String, name: String, ids: List<String>) {
        host.scope.launch {
            try {
                val added = withContext(Dispatchers.IO) {
                    val had = host.lib.collection(playlist)?.itemIds.orEmpty().toHashSet()
                    host.lib.addToPlaylist(playlist, ids)
                    ids.distinct().filter { it !in had }
                }
                val text = if (ids.size == 1) "Added to “$name”" else "Added ${ids.size} songs to “$name”"
                if (added.isEmpty()) host.say(text) else host.say(text, "Undo") { undoCall { collectionRemove(playlist, added) } }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, "Could not add to the playlist.")) }
        }
    }

    fun rescan() = libraryCall("Could not refresh the library.") { rescan() }

    /** Adds and removes user tags. Each item's tags are read first; Undo gives every item back exactly what this changed on it. */
    fun tagItems(ids: List<String>, add: List<String>, remove: List<String>) {
        if (ids.isEmpty() || (add.isEmpty() && remove.isEmpty())) return
        host.scope.launch {
            try {
                val steps = withContext(Dispatchers.IO) {
                    val before = host.lib.browse.byIds(ids).associate { it.id to it.userTags }
                    host.lib.libraryTag(ids, add, remove)
                    UndoLogic.tagInverse(before, add, remove)
                }
                val text = if (ids.size == 1) "Tags saved" else "Tags saved on ${ids.size} items"
                if (steps.isEmpty()) host.say(text) else host.say(text, "Undo") { undoCall { steps.forEach { libraryTag(it.ids, it.add, it.remove) } } }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, "Could not change the tags.")) }
        }
    }

    /** Changes follow settings; [saved] is said only when the library accepted them (own lists and radio mixes refuse). */
    fun followCollection(id: String, update: FollowUpdate, saved: String? = null) {
        host.scope.launch {
            try { withContext(Dispatchers.IO) { host.lib.collectionFollow(id, update) }; saved?.let(host::say) }
            catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, "Could not change follow settings.")) }
        }
    }

    fun checkCollection(id: String, name: String) {
        libraryCall("Could not check $name.") { collectionCheck(id) }
        host.say("Checking $name for new items. Keepfin tells you what it finds.")
    }

    fun reviewCollection(id: String, count: Int) {
        libraryCall("Could not start these downloads.") { collectionReview(id) }
        host.say(if (count == 1) "Downloading 1 new item" else "Downloading $count new items")
    }

    /** The result of a follow check, said in the app: what was found (and that it is downloading), nothing new, or the review prompt. */
    fun announceCheck(report: app.keepfin.FollowReport) {
        val notice = CollectionLogic.checkNotice(report) ?: return
        when (notice.action) {
            NoticeAction.SHOW -> host.say(notice.text, "Show", { showCollection(report.collectionId) })
            NoticeAction.REVIEW -> host.say(notice.text, "Review", { reviewCollection(report.collectionId, report.held) })
            null -> host.say(notice.text)
        }
    }

    /** Opens a downloaded collection from anywhere (the notice's Show). */
    fun showCollection(id: String) {
        val c = host.lib.library.value.collections.firstOrNull { it.id == id } ?: return
        host.closeSubPage(); host.navigate(Destination.LIBRARY)
        host.openLibraryRoot(LibraryRoute.Space(c.space))
        host.openLibraryDetail(collectionRoute(c))
    }

    fun deleteCollection(id: String, name: String) = removeCollection(id, name, leave = true)

    /**
     * Deletes a collection (the files stay). What it was is read before: a collection of the user's own can be brought back by Undo
     * with the same id, name, songs in order and space; a downloaded one (source link, follow settings) cannot, and says so by having no Undo.
     */
    private fun removeCollection(id: String, name: String?, leave: Boolean) {
        host.scope.launch {
            try {
                val c = host.lib.library.value.collections.firstOrNull { it.id == id }
                withContext(Dispatchers.IO) { host.lib.collectionDelete(id) }
                if (c != null && c.kind == CollectionKind.OWN) host.say("Deleted “${c.name}”. The songs stay in your library.", "Undo") { undoDeleteCollection(c) }
                else host.say("Removed “${c?.name ?: name ?: "the playlist"}”. The files stay in your library.")
                if (leave) leaveCollectionPage(id)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, "Could not remove the playlist.")) }
        }
    }

    /** Undo of deleting a collection of your own: it is saved again under its old id (nothing happens if that id is in use again). */
    private fun undoDeleteCollection(c: LibraryCollection) = undoCall {
        if (collection(c.id) == null) collectionSave(c.id, c.name, c.itemIds, c.space)
    }

    /**
     * Renames a collection. The songs are the ones saved now (never a list the page held, so a change made meanwhile is not undone by
     * the rename); Undo puts the old name back unless the collection was renamed again since.
     */
    fun renameCollection(id: String, name: String, ids: List<String>) {
        val clean = name.trim()
        host.scope.launch {
            try {
                val old = withContext(Dispatchers.IO) {
                    val c = host.lib.collection(id) ?: throw IllegalStateException("This playlist is gone.")
                    host.lib.collectionSave(id, clean, c.itemIds, c.space)
                    c.name
                }
                if (old != clean) host.say("Renamed to “$clean”", "Undo") { undoRenameCollection(id, old, clean) }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, "Could not rename the playlist.")) }
        }
    }

    private fun undoRenameCollection(id: String, old: String, renamedTo: String) = undoCall {
        val c = collection(id) ?: throw IllegalStateException("The playlist is gone.")
        if (c.name != renamedTo) throw IllegalStateException("It was renamed again since.")
        collectionSave(id, old, c.itemIds, c.space)
    }

    /** Moves (or copies) library items and the items of collections into a folder space, with progress and a result message. */
    fun addFromLibrary(itemIds: List<String>, collectionIds: List<String>, space: String, copy: Boolean, count: Int, onDone: () -> Unit = {}) {
        if (host.libraryAdding != null) return
        val name = host.spaceName(space) ?: "that space"
        host.libraryAdding = if (copy) "Copying $count to $name…" else "Moving $count to $name…"
        host.scope.launch {
            try {
                // a move can be undone: where each file is now is read before it runs (a copy leaves the originals, so it has nothing to undo)
                val origins = if (copy) emptyMap() else withContext(Dispatchers.IO) {
                    val wanted = (itemIds + collectionIds.flatMap { id -> host.lib.collection(id)?.itemIds.orEmpty() }).distinct()
                    host.lib.browse.byIds(wanted).filter { it.space != space }.associate { it.id to it.space as String? }
                }
                when (val result = withContext(Dispatchers.IO) { host.lib.libraryAddFrom(itemIds, collectionIds, space, copy) }) {
                    LibraryAccess.Done -> {
                        if (copy) host.say("Copied $count to $name", actionLabel = "Show", action = { host.showInLibrary(space) })
                        else if (origins.isEmpty()) host.say("Moved $count to $name", actionLabel = "Show", action = { host.showInLibrary(space) })
                        else host.sayWithTwo("Moved $count to $name", "Show", { host.showInLibrary(space) }, "Undo") { undoMove(origins, space) }
                        onDone()
                    }
                    is LibraryAccess.NeedsConfirmation -> host.access = PendingAccess(result.intentSender, result.ids, false) {
                        addFromLibrary(itemIds, collectionIds, space, copy, count, onDone)
                    }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                host.say(friendly(e, "Could not add these items to $name."))
            } finally { host.libraryAdding = null }
        }
    }

    fun collectionOpened(id: String) { host.scope.launch(Dispatchers.IO) { runCatching { host.lib.collectionOpened(id) } } }
}
