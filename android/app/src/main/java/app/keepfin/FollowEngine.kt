package app.keepfin

import android.net.ConnectivityManager
import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/** What a follow check needs from the download engine (the engine implements it; tests use a small fake). */
internal interface FollowHost {
    val settings: StateFlow<EngineSettings>
    val library: FollowEngine.FollowLibrary
    fun discover(url: String, session: String, start: Int, end: Int): PlaylistDiscovery
    fun lastProfile(collectionId: String): Pair<FormatRequest, DownloadOptions>?
    fun enqueue(session: String, entries: List<PlaylistEntry>, format: FormatRequest?, options: DownloadOptions, group: JobGroup, space: String, collectionId: String): List<String>
    fun dropSession(session: String)
    /** A check held [count] new items for review. */
    fun heldForReview(c: LibraryCollection, follow: Follow, count: Int, noun: String)
    /** A check queued [ids]. */
    fun found(c: LibraryCollection, follow: Follow, ids: List<String>, noun: String)
    fun metered(): Boolean
}

/**
 * The follow check (dev-docs/architecture/SPACES-V2.md): re-runs paged discovery on a collection's source, treats ids outside `seen` as new, applies
 * the confirmAbove gate and queues the new items into the collection's space with its format profile. Blocking: call it off the main thread.
 * Scheduling lives in [FollowScheduler]; this class never schedules anything itself.
 */
internal class FollowEngine(
    private val host: FollowHost,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What one call did. [queued] are the job ids it enqueued (the worker waits for them). */
    data class Outcome(val checked: Int = 0, val queued: List<String> = emptyList(), val held: Int = 0, val errors: Int = 0) {
        operator fun plus(other: Outcome) = Outcome(checked + other.checked, queued + other.queued, held + other.held, errors + other.errors)
    }

    /** What a check needs from the library in use: the Keepfin folder library, or nothing before it exists. */
    internal interface FollowLibrary {
        fun collectionsNow(): List<LibraryCollection>
        fun collection(id: String): LibraryCollection?
        fun mutateCollection(id: String, transform: (LibraryCollection) -> LibraryCollection): LibraryCollection?
        fun markFollowJobs(jobIds: Collection<String>)
        fun sourceKeysOf(itemId: String): Set<String>
        fun report(report: FollowReport)
    }

    private val library: FollowLibrary get() = host.library

    fun metered(): Boolean = host.metered()

    /** Checks every collection that is due now (automatic, interval elapsed, Wi-Fi-only ones skipped on a metered network). */
    fun checkDue(): Outcome {
        val due = CollectionLogic.due(library.collectionsNow(), clock(), metered())
        var total = Outcome()
        for (c in due) total += check(c.id, review = false, manual = false)
        return total
    }

    /**
     * Checks one collection. [manual] is "Check now": it ignores the schedule and the Wi-Fi-only setting (the user asked) but never a radio mix.
     * [review] accepts everything a previous check held back.
     */
    fun check(id: String, review: Boolean, manual: Boolean): Outcome {
        val start = library.collection(id) ?: return Outcome()
        if (!CollectionLogic.followable(start)) return Outcome()
        val follow = start.follow ?: Follow()
        if (!manual && follow.wifiOnly && metered()) return Outcome()
        val source = start.source ?: return Outcome()
        val session = "follow-${UUID.randomUUID()}"
        val noun = if (host.settings.value.library.folderSpaces.firstOrNull { it.id == start.space }?.kind == SpaceKind.MUSIC) "song" else "item"
        try {
            val discovery = try { discover(source.url, session) } catch (error: Exception) {
                return Outcome(checked = 1, errors = 1).also { fail(id, failureText(error), start.name, manual, noun) }
            }
            if (discovery == null || (discovery.state != "completed" && discovery.entries.isEmpty())) {
                fail(id, discovery?.error ?: "Keepfin couldn't read this playlist right now. It will try again later.", start.name, manual, noun)
                return Outcome(checked = 1, errors = 1)
            }
            // A failed or capped page keeps what was found so far; only a complete listing can prove an item left the source.
            val complete = discovery.state == "completed" && !discovery.truncated
            val entries = discovery.entries.sortedBy { it.position }
            val keys = entries.map { CollectionLogic.itemKey(it.url) }
            val current = library.collection(id) ?: return Outcome()
            val fresh = CollectionLogic.newKeys(keys, current.seen)
            val removed = if (complete) CollectionLogic.removedUpstream(current.itemIds, library::sourceKeysOf, keys.toSet()) else current.removedItemIds
            val decision = CollectionLogic.decide(fresh, follow.confirmAbove, confirmed = review)
            val now = clock()
            return when (decision) {
                FollowDecision.NothingNew -> {
                    library.mutateCollection(id) { it.copy(follow = (it.follow ?: follow).copy(lastCheckedAt = now, lastError = null, pending = null), removedItemIds = removed) }
                    library.report(FollowReport(id, current.name, 0, 0, null, manual, noun))
                    Outcome(checked = 1)
                }
                is FollowDecision.NeedsReview -> {
                    library.mutateCollection(id) { it.copy(follow = (it.follow ?: follow).copy(lastCheckedAt = now, lastError = null, pending = PendingCheck(decision.count, now)), removedItemIds = removed) }
                    host.heldForReview(current, follow, decision.count, noun)
                    library.report(FollowReport(id, current.name, 0, decision.count, null, manual, noun))
                    Outcome(checked = 1, held = decision.count)
                }
                is FollowDecision.Download -> {
                    val wanted = decision.keys.toSet()
                    val chosen = entries.filter { CollectionLogic.itemKey(it.url) in wanted }
                    val ids = queue(current, session, chosen)
                    // Seen only after the jobs are really queued, so a full queue or a crash never turns new items into "seen" ones.
                    library.mutateCollection(id) {
                        it.copy(seen = (it.seen + decision.keys).distinct(), removedItemIds = removed,
                            follow = (it.follow ?: follow).copy(lastCheckedAt = now, lastError = null, pending = null))
                    }
                    library.markFollowJobs(ids)
                    if (ids.isNotEmpty()) host.found(current, follow, ids, noun)
                    library.report(FollowReport(id, current.name, ids.size, 0, null, manual, noun))
                    Outcome(checked = 1, queued = ids)
                }
            }
        } catch (error: Exception) {
            Log.w(TAG, "follow check failed for $id", error)
            fail(id, failureText(error), start.name, manual, noun)
            return Outcome(checked = 1, errors = 1)
        } finally {
            host.dropSession(session)
        }
    }

    /** Pages of [CollectionLogic.PAGE_SIZE] until the source is exhausted or [CollectionLogic.DISCOVERY_CAP] entries were read. */
    private fun discover(url: String, session: String): PlaylistDiscovery? {
        var start = 1
        var last: PlaylistDiscovery? = null
        while (start <= CollectionLogic.DISCOVERY_CAP) {
            val end = start + CollectionLogic.PAGE_SIZE - 1
            val page = host.discover(url, session, start, end)
            last = page
            if (page.state != "completed" || !page.truncated) break
            start = end + 1
        }
        return last
    }

    private fun queue(c: LibraryCollection, session: String, entries: List<PlaylistEntry>): List<String> {
        val space = host.settings.value.library.folderSpaces.let { spaces -> spaces.firstOrNull { it.id == c.space } ?: spaces.firstOrNull { it.id == "downloads" } ?: spaces.first() }
        val (format, options) = host.lastProfile(c.id) ?: (ArgsPolicy.preset(if (space.kind == SpaceKind.MUSIC) DownloadPreset.MP3 else DownloadPreset.BEST) to host.settings.value.options)
        val group = JobGroup(id = c.id, title = c.name, url = c.source?.url.orEmpty(), kind = c.kind.wire.takeIf { it != "own" } ?: "playlist")
        return host.enqueue(session, entries, format, options, group, space.id, c.id)
    }

    private fun fail(id: String, message: String, name: String, manual: Boolean, noun: String) {
        library.mutateCollection(id) { it.copy(follow = (it.follow ?: Follow()).copy(lastCheckedAt = clock(), lastError = message)) }
        library.report(FollowReport(id, name, 0, 0, message, manual, noun))
    }

    private fun failureText(error: Exception): String {
        val text = error.message.orEmpty()
        return when {
            text.startsWith("Cookies changed") -> "Your sign-in changed during the check. Keepfin will try again later."
            text.contains("Queue is full") -> "The download queue is full. Keepfin will try again later."
            else -> ErrorMessages.friendly(text)
        }
    }

    companion object { private const val TAG = "KeepfinFollow" }
}

/** The follow check on the real engine. */
internal class EngineFollowHost(private val engine: MediaEngine) : FollowHost {
    override val settings get() = engine.settings
    override val library: FollowEngine.FollowLibrary
        get() = engine.folderLibrary?.let { repo ->
            object : FollowEngine.FollowLibrary {
                override fun collectionsNow() = repo.collectionsNow()
                override fun collection(id: String) = repo.collection(id)
                override fun mutateCollection(id: String, transform: (LibraryCollection) -> LibraryCollection) = repo.mutateCollection(id, transform)
                override fun markFollowJobs(jobIds: Collection<String>) = repo.markFollowJobs(jobIds)
                override fun sourceKeysOf(itemId: String) = repo.sourceKeysOf(itemId)
                override fun report(report: FollowReport) = repo.report(report)
            }
        } ?: NoFollowLibrary

    /** Before the Keepfin folder exists: nothing follows. */
    private object NoFollowLibrary : FollowEngine.FollowLibrary {
        override fun collectionsNow() = emptyList<LibraryCollection>()
        override fun collection(id: String): LibraryCollection? = null
        override fun mutateCollection(id: String, transform: (LibraryCollection) -> LibraryCollection): LibraryCollection? = null
        override fun markFollowJobs(jobIds: Collection<String>) {}
        override fun sourceKeysOf(itemId: String) = emptySet<String>()
        override fun report(report: FollowReport) {}
    }

    override fun discover(url: String, session: String, start: Int, end: Int) = engine.discover(url, session, start, end) { }
    override fun lastProfile(collectionId: String) = engine.lastProfile(collectionId)
    override fun enqueue(session: String, entries: List<PlaylistEntry>, format: FormatRequest?, options: DownloadOptions, group: JobGroup, space: String, collectionId: String) =
        engine.enqueueCollectionEntries(session, entries, DownloadPreset.BEST, format, options, group, space, collectionId)
    override fun dropSession(session: String) = engine.inspector.dropSession(session)

    override fun heldForReview(c: LibraryCollection, follow: Follow, count: Int, noun: String) {
        if (follow.notify) engine.notifications()?.followNews(c, CollectionLogic.reviewText(count, c.name), CollectionLogic.newText(count, c.name))
        engine.activityStore.recordSyncReview(c.id, c.name, count, follow.confirmAbove, noun == "song")
    }

    override fun found(c: LibraryCollection, follow: Follow, ids: List<String>, noun: String) {
        if (follow.notify) engine.notifications()?.followNews(c, CollectionLogic.newText(ids.size, c.name), "Downloading now.")
        val libraryOn = engine.settings.value.library.enabled
        val spaceName = if (libraryOn) engine.settings.value.library.spaces.firstOrNull { it.id == c.space }?.name else null
        engine.activityStore.recordSyncFound(c.id, c.name, ids, noun == "song", spaceName, engine.jobs.value)
    }

    override fun metered(): Boolean {
        val permitted = engine.context.checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!permitted) return true
        return try { engine.context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true } catch (_: Exception) { true }
    }
}
