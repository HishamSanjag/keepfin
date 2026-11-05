package app.keepfin.ui.model

import android.app.Application
import app.keepfin.CollectionLogic
import app.keepfin.DownloadPreset
import app.keepfin.JobGroup
import app.keepfin.EngineApi
import app.keepfin.JobStatus
import app.keepfin.LibraryApi
import app.keepfin.PlaylistDiscovery
import app.keepfin.PlaylistEntry
import app.keepfin.Providers
import app.keepfin.SyncRequest
import app.keepfin.ui.CollectionReview
import app.keepfin.ui.UiPreferences
import app.keepfin.ui.StudioEngine
import app.keepfin.ui.StudioViewModel.Companion.COLLECTION_PAGE
import app.keepfin.ui.StudioViewModel.Companion.friendly
import app.keepfin.ui.collectionKind
import app.keepfin.ui.isAudio
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** What [CollectionFlow] uses of the ViewModel; the review itself is its Compose state, read and written here. */
internal interface CollectionFlowHost : Notices {
    val scope: CoroutineScope
    val engine: EngineApi
    val lib: LibraryApi
    val prefs: UiPreferences
    val libraryEnabled: Boolean
    var collection: CollectionReview?
    var collectionOpen: Boolean
    var linkSpace: String?
    fun <T : Application> getApplication(): T
    fun invalidate()
    fun saidRemovedFromHistory(ids: List<String>)
}

/**
 * A collection under review (a playlist, album or channel): opening it, listing it page by page, the selection, adding the chosen items
 * to the queue, and the actions on a downloaded collection's group of jobs. The review itself is [host]'s `collection`.
 */
internal class CollectionFlow(private val host: CollectionFlowHost) {
    /** Whether the review offers "Keep in sync": playlists and channels, while the library is on. */
    fun syncOffered(review: CollectionReview): Boolean = host.libraryEnabled && CollectionLogic.syncOffered(review.url)

    fun openPlaylist(url: String) {
        host.invalidate()
        startCollection(url, null)
    }

    fun startCollection(url: String, title: String?) {
        host.collection?.let { stopCollection(it) }
        val review = CollectionReview(url, UUID.randomUUID().toString(), title)
        val settings = host.engine.settings.value
        review.music = Providers.defaultKind(url, settings.ytMusicAudio) == "audio"
        review.preset = if (review.music && !settings.defaultPreset.isAudio()) DownloadPreset.MP3 else settings.defaultPreset
        review.keepTogether = host.prefs.keepCollectionsTogether
        review.findVideo = settings.keepMusicVideos
        // From a space's "Add from a link": Save to starts on that space (the chip still lets the user change it).
        review.space = host.linkSpace?.takeIf { host.engine.settings.value.library.folderSpaces.any { s -> s.id == it } }
        host.linkSpace = null
        review.options = settings.options
        host.collection = review
        host.collectionOpen = true
        loadPage(review)
    }

    fun loadMore() { host.collection?.takeIf { !it.discovering && it.truncated }?.let { loadPage(it) } }

    private fun loadPage(review: CollectionReview) {
        val start = review.nextStart
        val end = start + COLLECTION_PAGE - 1
        review.discovering = true
        review.error = null
        val pending = ConcurrentLinkedQueue<PlaylistEntry>()
        fun flush() {
            if (host.collection !== review) return
            val known = review.entries.mapTo(HashSet()) { it.url }
            val fresh = generateSequence { pending.poll() }.filter { known.add(it.url) }.toList()
            if (fresh.isNotEmpty()) {
                review.entries.addAll(fresh)
                if (review.autoSelect) fresh.forEach { if (it.url !in review.existing && it.unavailable == null) review.selected[it.url] = true }
                markExisting(review, fresh)
            }
        }
        review.worker = host.scope.launch {
            val flusher = launch { while (true) { delay(150); flush() } }
            val result: PlaylistDiscovery? = try {
                withContext(Dispatchers.IO) { host.engine.discover(review.url, review.session, start, end) { pending.add(it) } }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                review.error = friendly(e, "Could not list this collection.")
                null
            } finally { flusher.cancel() }
            flush()
            if (host.collection !== review) return@launch
            if (result != null) {
                result.entries.forEach { pending.add(it) }
                flush()
                if (!result.title.isNullOrBlank()) review.title = result.title
                review.truncated = result.truncated && result.state != "cancelled"
                if (result.state == "failed") review.error = result.error ?: "Discovery stopped early. Found items are kept."
                else if (result.state == "cancelled") review.truncated = true
            }
            review.nextStart = end + 1
            review.discovering = false
        }
    }

    /** The space a collection will be saved to (the user's pick or the default for its link). */
    fun collectionSpace(review: CollectionReview): String? =
        if (!host.libraryEnabled) null else (review.space ?: host.lib.defaultSpaceFor(review.url, review.entries.firstOrNull()?.uploader))?.takeIf { id -> host.engine.settings.value.library.folderSpaces.any { it.id == id } }

    fun chooseCollectionSpace(review: CollectionReview, space: String) {
        review.space = space
        review.existing.clear()
        markExisting(review, review.entries.toList())
    }

    private fun markExisting(review: CollectionReview, entries: List<PlaylistEntry>) {
        val target = collectionSpace(review) ?: return
        if (host.engine.settings.value.library.spaces.firstOrNull { it.id == target }?.skipExisting != true || entries.isEmpty()) return
        host.scope.launch {
            val found = try { withContext(Dispatchers.IO) { host.lib.existing(target, entries) } } catch (e: CancellationException) { throw e } catch (_: Exception) { emptySet() }
            if (host.collection === review && collectionSpace(review) == target) {
                review.existing.addAll(found)
                found.forEach { review.selected.remove(it) }
            }
        }
    }

    /** Select all keeps songs already in the space out; [includeExisting] is the explicit override. */
    fun selectNew() {
        val review = host.collection ?: return
        review.autoSelect = false
        review.entries.forEach { if (it.url !in review.existing && it.unavailable == null) review.selected[it.url] = true }
    }

    fun toggleEntry(entry: PlaylistEntry) {
        val review = host.collection ?: return
        // private, deleted or gone: downloading it can only fail
        if (entry.unavailable != null) return
        review.autoSelect = false
        if (review.selected[entry.url] == true) review.selected.remove(entry.url) else review.selected[entry.url] = true
    }

    fun selectAll(value: Boolean) {
        val review = host.collection ?: return
        review.autoSelect = false
        if (value) review.entries.forEach { if (it.unavailable == null) review.selected[it.url] = true } else review.selected.clear()
    }

    fun stopDiscovery() {
        val review = host.collection ?: return
        if (!review.discovering) return
        host.scope.launch(Dispatchers.IO) { runCatching { host.engine.cancelDiscovery(review.session) } }
    }

    /** Whether the review offers Both (the songs with their music videos where there is one): a music collection (YouTube Music or a music service), with the library on. */
    fun offersCollectionVideos(review: CollectionReview): Boolean =
        host.libraryEnabled && (Providers.musicSource(review.url) != null || Providers.isYouTubeMusic(review.url))

    fun downloadCollection() {
        val review = host.collection ?: return
        val chosen = review.entries.filter { review.selected[it.url] == true }
        if (chosen.isEmpty() || review.adding) return
        review.adding = true
        host.scope.launch {
            try {
                // Discovery stops first so the engine is not listing and downloading the same source.
                if (review.discovering) {
                    withContext(Dispatchers.IO) { runCatching { host.engine.cancelDiscovery(review.session) } }
                    withTimeoutOrNull(10_000) { review.worker?.join() }
                }
                val group = if (review.keepTogether) JobGroup(
                    id = UUID.randomUUID().toString(), title = review.title?.takeIf { it.isNotBlank() } ?: "Collection", url = review.url,
                    uploader = chosen.firstNotNullOfOrNull { it.uploader ?: it.artist }, thumbnail = chosen.firstNotNullOfOrNull { it.thumbnail },
                    kind = collectionKind(review.url),
                ) else null
                val count = withContext(Dispatchers.IO) {
                    StudioEngine.withServiceWork(host.getApplication()) { host.engine.enqueueEntries(review.session, chosen, review.preset, review.options, group, review.space, if (syncOffered(review) && review.sync) SyncRequest(review.syncHours, review.syncWifiOnly) else null, offersCollectionVideos(review) && review.preset.isAudio() && review.findVideo, review.playlist.takeIf { host.libraryEnabled }) }
                }
                host.say("Added $count ${if (count == 1) "download" else "downloads"} to the queue")
                if (host.collection === review) closeCollection()
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                host.say(friendly(e, "Could not add the selected items."))
            } finally { review.adding = false }
        }
    }

    fun chooseKeepTogether(value: Boolean) {
        host.prefs.chooseKeepTogether(value)
        host.collection?.keepTogether = value
    }

    // Group actions: built on the per-job engine calls so every rule (settling, rejection of active jobs) stays in one place.
    fun groupJobs(groupId: String) = host.engine.jobs.value.filter { it.group?.id == groupId }
    private fun groupAction(label: String, groupId: String, work: (List<app.keepfin.Job>) -> Unit) {
        host.scope.launch {
            try { withContext(Dispatchers.IO) { work(groupJobs(groupId)) } }
            catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, "Could not $label.")) }
        }
    }
    fun pauseGroup(id: String) = groupAction("pause the collection", id) { jobs -> jobs.filter { it.status.active }.forEach { runCatching { host.engine.pause(it.id) } } }
    fun resumeGroup(id: String) = groupAction("resume the collection", id) { jobs ->
        StudioEngine.withServiceWork(host.getApplication()) { jobs.filter { it.status == JobStatus.PAUSED }.forEach { runCatching { host.engine.resume(it.id) } } }
    }
    fun cancelGroup(id: String) = groupAction("cancel the collection", id) { jobs -> jobs.filter { it.status.active || it.status == JobStatus.PAUSED }.forEach { runCatching { host.engine.cancel(it.id) } } }
    fun retryGroupFailed(id: String) = groupAction("retry failed items", id) { jobs ->
        StudioEngine.withServiceWork(host.getApplication()) {
            jobs.filter { it.status == JobStatus.FAILED }.forEach { runCatching { host.engine.retry(it.id) } }
            // A download that finished but could not be saved to its folder is retried by saving it again.
            jobs.filter { it.status == JobStatus.COMPLETED && it.saveError != null }.forEach { runCatching { host.engine.retryPublicSave(it.id) } }
        }
    }
    /** Removes history only; rejected while any member is still active or paused. Saved media stays. */
    fun removeGroup(id: String) {
        host.scope.launch {
            try {
                val removed = withContext(Dispatchers.IO) {
                    val jobs = groupJobs(id)
                    check(jobs.none { it.status.active || it.status == JobStatus.PAUSED }) { "Finish or cancel this collection before removing it from history." }
                    jobs.forEach { host.engine.remove(it.id) }
                    (host.getApplication<Application>() as? app.keepfin.KeepfinApplication)?.notifications?.dismissGroup(id)
                    jobs.map { it.id }
                }
                host.saidRemovedFromHistory(removed)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { host.say(friendly(e, "Could not remove the collection from history.")) }
        }
    }

    fun closeCollection() {
        host.collection?.let { stopCollection(it) }
        host.collection = null
        host.collectionOpen = false
    }

    fun stopCollection(review: CollectionReview) {
        if (review.discovering) host.scope.launch(Dispatchers.IO) { runCatching { host.engine.cancelDiscovery(review.session) } }
    }
}
