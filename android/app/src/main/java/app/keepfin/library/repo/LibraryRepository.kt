package app.keepfin.library.repo

import app.keepfin.CollectionKind
import app.keepfin.ExistingMatch
import app.keepfin.FollowReport
import app.keepfin.FollowUpdate
import app.keepfin.ImportResult
import app.keepfin.LibraryAccess
import app.keepfin.LibraryApi
import app.keepfin.RestoreResult
import app.keepfin.LibraryCollection
import app.keepfin.LibraryHost
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.LibrarySnapshot
import app.keepfin.Lyrics
import app.keepfin.MediaInfo
import app.keepfin.PlaybackRules
import app.keepfin.PlaylistEntry
import app.keepfin.Providers
import app.keepfin.Recording
import app.keepfin.SourceTags
import app.keepfin.Job
import app.keepfin.SyncRequest
import app.keepfin.Space
import app.keepfin.SpaceKind
import app.keepfin.SpaceLogic
import app.keepfin.SubtitleFile
import app.keepfin.TagEdit
import app.keepfin.TagLogic
import app.keepfin.core.library.DiffContext
import app.keepfin.BrowseHolder
import app.keepfin.LibraryBrowse
import app.keepfin.core.library.LibCollection
import app.keepfin.core.library.LibItem
import app.keepfin.core.library.LibModel
import app.keepfin.core.library.LibRoutes
import app.keepfin.core.library.LibSpace
import app.keepfin.core.library.LibraryMapping
import app.keepfin.core.library.ObservedAnswer
import app.keepfin.core.library.ObservedLookup
import app.keepfin.core.library.ObservedTags
import app.keepfin.core.library.RecordingObserved
import app.keepfin.core.library.Song
import app.keepfin.core.sync.JArr
import app.keepfin.core.sync.JNum
import app.keepfin.core.sync.JObj
import app.keepfin.core.sync.JStr
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import app.keepfin.folder.KeepfinFolders
import app.keepfin.folder.KeepfinStore
import app.keepfin.folder.sql.BrowseEnv
import app.keepfin.folder.sql.BundledSql
import app.keepfin.folder.sql.IndexBrowse
import app.keepfin.folder.sql.IndexReader
import app.keepfin.folder.sql.LinkedView
import app.keepfin.folder.sql.SqlMergeState
import app.keepfin.folder.sql.StateViews
import app.keepfin.core.sqlstate.SqlDriver
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import app.keepfin.TrashEntry
import org.json.JSONObject
import app.keepfin.folder.OpDraft as StoreDraft

/** What the repository stands on: the open store, the file access and where this device is in the folder. */
internal class RepositoryBackend(
    val store: KeepfinStore,
    val folder: FolderAccess,
    /** This device's place id (folder.json), under which placements are written. */
    val replicaId: String,
    /** The merge state in SQLite (the store runs on it): the index the screens page through lives next to it. */
    val state: SqlMergeState,
    /** Opens a read-only connection to the state's file (the bundled driver on a phone). */
    val openReader: (String) -> SqlDriver = { BundledSql.openReader(it) },
    /** Turns a placement into the address the player opens. */
    val uriFor: (String) -> String = { "keepfin-folder:$it" },
) {
    /** Where this library keeps its records (trash, art) in the folder; a combined folder keeps them below `libraries/<id>`. */
    var layout: app.keepfin.folder.FolderLayout = app.keepfin.folder.FolderLayout.DEFAULT
}

/**
 * What the first screen needs before the Keepfin folder is open: the merge state (its index holds the library as it was last time, on
 * this phone) and how a placement becomes a file address. Opening the folder (listing it and reading new segments through the storage
 * provider) took seconds, and the library looked empty meanwhile; with this the screens show at once and catch up when the folder is open.
 * [open] receives it and uses the same state, so there is only one writer.
 */
internal class LocalStart(
    val state: SqlMergeState,
    val replicaId: String,
    val uriFor: (String) -> String,
    val openReader: (String) -> SqlDriver = { BundledSql.openReader(it) },
) {
    /** The repository let go of this view (it failed, or the folder is another one): its state is closed and never reused. */
    @Volatile var dropped = false
        internal set
}

/**
 * Thrown by [LibraryRepository]'s open when the folder is not the one the early view ([LocalStart]) was opened for: the repository
 * takes the view down first (so no screen reads the file while another state replaces it), then opens again.
 */
internal class EarlyViewMismatch : Exception("The Keepfin folder is not the one opened last time.")

/** Where picked documents come from (the content resolver on a phone). */
internal interface ImportSource {
    fun open(uri: String): InputStream
    fun displayName(uri: String): String
    fun mimeType(uri: String): String?
}

/** The file added by [LibraryRepository.addFile]. */
internal class AddedFile(val itemId: String, val duplicateOf: String?)

/**
 * The library on the Keepfin folder: [LibraryApi] over [KeepfinStore] and the shared mapping (`app.keepfin.core.library`).
 * Every mutation is a change of the library record turned into operations by the mapping. Nothing here holds the item list:
 * the items live in the store's SQLite state and its index, and screens read them in pages ([browse]); a mutation reads the few
 * items it changes, diffs just those, and the collections, spaces and routes (which are small) are kept in memory.
 * Files move through [FolderAccess] only. Active only when a profile exists (see [LibrarySelector]); without one [NoLibrary] stands in.
 * Blocking calls (everything that is not a flow or a plain getter) run off the main thread.
 */
internal class LibraryRepository(
    private val host: LibraryHost,
    private val open: () -> RepositoryBackend,
    private val deviceDir: File,
    private val imports: ImportSource? = null,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val log: (String, Throwable?) -> Unit = { _, _ -> },
    /** The platform side of linked spaces (MediaStore); null where there is none, then linked files cannot be changed. */
    private val linked: LinkedMedia? = null,
    /** The Android version (a parameter so tests can run the Android 11 and newer paths). */
    private val sdk: Int = android.os.Build.VERSION.SDK_INT,
    /** The library as it was last time, before the folder opens ([LocalStart]); null (or a null answer) waits for the folder. */
    private val openLocal: (() -> LocalStart?)? = null,
) : LibraryApi {
    /** Device-only addresses of the files of linked spaces (linked.json). */
    private val locators = LinkedStore(File(deviceDir, "linked.json"))

    /** Items of linked spaces whose file was not found on the last check (they stay in the library). */
    @Volatile private var missingLinked: Set<String> = emptySet()

    private val flow = MutableStateFlow(LibrarySnapshot())
    override val library: StateFlow<LibrarySnapshot> = flow.asStateFlow()
    private val holder = BrowseHolder()
    override val browse: LibraryBrowse = holder
    private val reports = kotlinx.coroutines.flow.MutableSharedFlow<FollowReport>(extraBufferCapacity = 8)
    override val followReports: SharedFlow<FollowReport> = reports

    private val ready = CompletableFuture<RepositoryBackend>()
    private val writeLock = Any()
    private val refreshLock = Any()

    @Volatile private var small = Small(emptyList(), LibRoutes("music", "downloads"), emptyList(), emptyMap(), emptyMap())
    @Volatile private var loaded = false
    @Volatile private var collectionsPending = false
    @Volatile private var deferCollections = true
    @Volatile private var reader: IndexReader? = null
    @Volatile private var index: IndexBrowse? = null
    @Volatile private var lastScan: Long? = null

    @Volatile private var follows: Map<String, FollowDevice> = emptyMap()
    private val counted = HashSet<String>()
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "keepfin-repo").also { it.isDaemon = true } }
    private val refreshQueued = AtomicBoolean(false)
    private var lastMirrored: Pair<List<Space>, Pair<String, String>>? = null
    private var unsubscribe: (() -> Unit)? = null

    /** What the index browse needs to know about the app around it. */
    private val env = object : BrowseEnv {
        override fun modelSpace(wire: String): String = small.modelIds[wire] ?: wire
        override fun spaceFolder(modelId: String): String = small.spaces.firstOrNull { it.id == modelId }?.folder.orEmpty()
        override fun uriFor(place: String): String = (ready.getNow(null)?.uriFor ?: warm?.uriFor)?.invoke(place) ?: "keepfin-folder:$place"
        override fun linked(id: String, place: String): LinkedView? {
            val where = locators.get(id) ?: return null
            return LinkedView(where.uri, linkedSub(place), where.size, where.mtime * 1000)
        }
        override fun jobIdFor(id: String): String? = downloadsPart.jobOfItem[id]
        override fun itemForJob(jobId: String): String? = downloadsPart.jobItems[jobId]
        override fun musicSpaces(): List<String> = small.spaces.filter { it.kind == SpaceKind.MUSIC.wire }.map { it.id }
    }

    /** What the parts below may use of this repository (see [RepoCore]); every member is one of this class's own. */
    private val core: RepoCore = object : RepoCore {
        override val host: LibraryHost get() = this@LibraryRepository.host
        override val deviceDir: File get() = this@LibraryRepository.deviceDir
        override val imports: ImportSource? get() = this@LibraryRepository.imports
        override val linked: LinkedMedia? get() = this@LibraryRepository.linked
        override val locators: LinkedStore get() = this@LibraryRepository.locators
        override val library: StateFlow<LibrarySnapshot> get() = this@LibraryRepository.library
        override val reports: MutableSharedFlow<FollowReport> get() = this@LibraryRepository.reports
        override var follows: Map<String, FollowDevice>
            get() = this@LibraryRepository.follows
            set(value) { this@LibraryRepository.follows = value }
        override val followJobs: MutableSet<String> get() = this@LibraryRepository.followJobs
        override val small: Small get() = this@LibraryRepository.small
        override var missingLinked: Set<String>
            get() = this@LibraryRepository.missingLinked
            set(value) { this@LibraryRepository.missingLinked = value }

        override fun now(): Long = this@LibraryRepository.now()
        override fun newId(): String = this@LibraryRepository.newId()
        override fun log(message: String, error: Throwable?) = this@LibraryRepository.log(message, error)
        override fun backend(): RepositoryBackend = this@LibraryRepository.backend()
        override fun rd(): IndexReader = this@LibraryRepository.rd()
        override fun publish() = this@LibraryRepository.publish()

        override fun readItems(ids: Collection<String>): Pair<List<LibItem>, Map<String, Long>> = this@LibraryRepository.readItems(ids)
        override fun libItem(id: String): LibItem? = this@LibraryRepository.libItem(id)
        override fun presentIds(ids: Collection<String>): Set<String> = this@LibraryRepository.presentIds(ids)
        override fun placeOf(id: String): String? = this@LibraryRepository.placeOf(id)
        override fun itemWithContentAt(path: String, contentId: String): String? = this@LibraryRepository.itemWithContentAt(path, contentId)
        override fun hasItem(id: String): Boolean = this@LibraryRepository.hasItem(id)

        override fun addItems(items: List<LibItem>, extra: List<app.keepfin.core.library.OpDraft>) = this@LibraryRepository.addItems(items, extra)
        override fun updateItems(ids: Collection<String>, transform: (LibItem) -> LibItem) = this@LibraryRepository.updateItems(ids, transform)
        override fun commitSpaces(fresh: Boolean, change: (List<LibSpace>, LibRoutes) -> Pair<List<LibSpace>, LibRoutes>): Boolean = this@LibraryRepository.commitSpaces(fresh, change)
        override fun commitCollections(change: (List<LibCollection>) -> List<LibCollection>): Boolean = this@LibraryRepository.commitCollections(change)
        override fun updateCollection(id: String, transform: (LibCollection) -> LibCollection): Boolean = this@LibraryRepository.updateCollection(id, transform)
        override fun removeItems(gone: Set<String>) = this@LibraryRepository.removeItems(gone)
        override fun addFile(spaceId: String, subfolder: String, fileName: String, source: InputStream, template: LibItem): AddedFile = this@LibraryRepository.addFile(spaceId, subfolder, fileName, source, template)
        override fun collectionAdd(id: String, itemIds: List<String>) = this@LibraryRepository.collectionAdd(id, itemIds)
        override fun collectionReorder(id: String, itemIds: List<String>) = this@LibraryRepository.collectionReorder(id, itemIds)

        override fun uniqueIn(b: RepositoryBackend, dir: String, wanted: String): String = this@LibraryRepository.uniqueIn(b, dir, wanted)
        override fun sidecarsOf(b: RepositoryBackend, place: String): List<String> = this@LibraryRepository.sidecarsOf(b, place)
        override fun moveFile(b: RepositoryBackend, from: String, to: String) = this@LibraryRepository.moveFile(b, from, to)
        override fun companionRecord(itemId: String, place: String, name: String, content: String, size: Long): StoreDraft? = this@LibraryRepository.companionRecord(itemId, place, name, content, size)
        override fun deleteLinked(items: List<LibItem>, gone: MutableSet<String>, fail: (String) -> Unit): LibraryAccess = this@LibraryRepository.deleteLinked(items, gone, fail)
        override fun recordCompanionsOf(b: RepositoryBackend, songs: List<Triple<String, String, String>>) = this@LibraryRepository.musicVideosPart.recordCompanionsOf(b, songs)
    }

    private val linkedPart = RepoLinked(core)
    private val trashPart = RepoTrash(core)
    private val importsPart = RepoImports(core)
    private val collectionsPart = RepoCollections(core)
    private val musicVideosPart = RepoMusicVideos(core)
    private val downloadsPart = RepoDownloads(core)

    // ------------------------------------------------------------------------------------------------ lifecycle

    /** Opens the store and builds the first snapshot on a background thread; mutations wait for it. */
    fun start() {
        scheduler.execute {
            try {
                follows = collectionsPart.readFollows()
                downloadsPart.jobItems = downloadsPart.readJobItems().also { downloadsPart.jobOfItem = it.entries.associate { e -> e.value to e.key } }
                val local = try { openLocal?.invoke() } catch (t: Throwable) { log("The library could not be shown before the folder opened.", t); null }
                if (local != null) try { showLocal(local) } catch (t: Throwable) { log("The library could not be shown before the folder opened.", t); dropLocal() }
                val backend = try {
                    open()
                } catch (e: EarlyViewMismatch) {
                    dropLocal()
                    open()
                }
                if (backend.state !== warm?.state) {
                    // the folder is not the one remembered (or nothing was shown early): its own state replaces the early view
                    dropLocal()
                    val rd = IndexReader(backend.state.statePath, { backend.state.epoch }, backend.openReader)
                    reader = rd
                    index = IndexBrowse(rd, env) { LibraryMapping.spaceWireId(it) }.also { holder.target = it }
                    backend.state.onChange { holder.bump() }
                    loaded = false
                }
                unsubscribe = backend.store.onChange { remote -> if (remote) scheduleRefresh() }
                ready.complete(backend)
                refreshNow()
                initialiseIfEmpty()
                runCatching { ensureKeptSpaces() }.onFailure { log("Favorites could not be added.", it) }
                runCatching { checkLinked() }
                // deleted files stay in the trash for 30 days; emptied a little after the open so it never slows the first screen
                scheduler.schedule({ runCatching { purgeTrash() }.onFailure { log("The Keepfin trash could not be emptied.", it) } }, 30, TimeUnit.SECONDS)
                // the lyrics, subtitles and covers already beside the items get their records, so copies carry them
                scheduler.schedule({ runCatching { recordCompanions() }.onFailure { log("Lyrics and subtitles could not be recorded.", it) } }, 20, TimeUnit.SECONDS)
            } catch (t: Throwable) {
                log("The Keepfin folder could not be opened.", t)
                // the early view goes too: its files are in the folder that could not be opened
                dropLocal()
                ready.completeExceptionally(t)
                flow.value = LibrarySnapshot(problems = listOf(t.message ?: "The Keepfin folder could not be opened."))
            }
        }
    }

    /** The early view ([LocalStart]) while the folder opens; it stays the state of the open library when [open] used the same one. */
    @Volatile private var warm: LocalStart? = null

    /** Home and the library show what the index on this phone holds, before the folder is open. */
    private fun showLocal(local: LocalStart) {
        warm = local
        val rd = IndexReader(local.state.statePath, { local.state.epoch }, local.openReader)
        reader = rd
        index = IndexBrowse(rd, env) { LibraryMapping.spaceWireId(it) }.also { holder.target = it }
        local.state.onChange { holder.bump() }
        // the collections come with the first screen here: nothing else waits on this thread yet
        deferCollections = false
        refreshFrom(local.state, local.replicaId)
    }

    /** The early view did not fit the folder: its reader closes (and its state, when the open library runs on another one). */
    private fun dropLocal() {
        val local = warm ?: return
        warm = null
        local.dropped = true
        holder.target = null
        index = null
        reader?.close()
        reader = null
        if (ready.getNow(null)?.state !== local.state) runCatching { local.state.close() }
        holder.bump()
    }

    /** The open store, state and folder (Combine libraries reads and writes through them). */
    internal fun mergeBackend(): RepositoryBackend = backend()
    internal val deviceDirectory: File get() = deviceDir

    /** Waits until the store is open and the first snapshot is built (tests, and callers that must not race the open). */
    fun awaitReady(timeoutSeconds: Long = 30, collections: Boolean = true) {
        ready.get(timeoutSeconds, TimeUnit.SECONDS)
        // the first refresh and initialisation run after `ready` completes, on the same thread
        scheduler.submit {}.get(timeoutSeconds, TimeUnit.SECONDS)
        // the collections are read right after the first snapshot: wait for that too
        if (collections) scheduler.submit {}.get(timeoutSeconds, TimeUnit.SECONDS)
    }

    /** Writes everything pending into segments and stops. */
    fun shutdown() {
        unsubscribe?.invoke()
        if (ready.isDone && !ready.isCompletedExceptionally) runCatching { ready.get().store.close().get(10, TimeUnit.SECONDS) }
        reader?.close()
        scheduler.shutdown()
    }

    /** Writes whatever is pending into segments now (a copy run starts from what the library holds). Blocking. */
    internal fun publishPending() {
        backend().store.flush().get(60, TimeUnit.SECONDS)
    }

    /** The app goes to the background: everything is in a segment. */
    fun onBackground() {
        if (ready.isDone && !ready.isCompletedExceptionally) {
            runCatching { ready.get().store.onBackground().get(10, TimeUnit.SECONDS) }
        }
    }

    private fun backend(): RepositoryBackend = ready.get(30, TimeUnit.SECONDS)

    private fun rd(): IndexReader = reader ?: throw IllegalStateException("The library is not open yet.")

    private fun views(): StateViews = StateViews(rd()::rows)

    private fun scheduleRefresh() {
        if (!refreshQueued.compareAndSet(false, true)) return
        scheduler.schedule({
            refreshQueued.set(false)
            try { refreshNow() } catch (t: Throwable) { log("refresh failed", t) }
        }, 100, TimeUnit.MILLISECONDS)
    }

    private fun initialiseIfEmpty() {
        if (small.spaces.isNotEmpty()) return
        val spaces = listOf(
            Space("downloads", "Downloads", SpaceKind.MIXED, "Downloads", app.keepfin.FolderLayout.COLLECTION, app.keepfin.SpaceNaming.TITLE, color = app.keepfin.SpaceColor.SLATE, icon = app.keepfin.SpaceIcon.FOLDER, pinned = true),
            Space("music", "Music", SpaceKind.MUSIC, "Music", app.keepfin.FolderLayout.COLLECTION, app.keepfin.SpaceNaming.TITLE, skipExisting = true, color = app.keepfin.SpaceColor.COBALT, icon = app.keepfin.SpaceIcon.MUSIC, pinned = true),
            Space("videos", "Videos", SpaceKind.VIDEO, "Videos", app.keepfin.FolderLayout.COLLECTION, app.keepfin.SpaceNaming.TITLE, color = app.keepfin.SpaceColor.VIOLET, icon = app.keepfin.SpaceIcon.VIDEO, pinned = true),
            app.keepfin.favoritesSpace(),
        )
        try {
            commitSpaces(fresh = true) { _, _ -> spaces.map { it.toLib() } to LibRoutes("music", "downloads") }
        } catch (t: Throwable) {
            log("The default spaces could not be written.", t)
        }
    }

    /**
     * Gives this Keepfin folder library every kept space it lacks (Favorites), whenever it opens: a library made before Favorites was
     * kept, one an older version removed it from, or one made again after Delete everything. Decided by id alone, so a renamed one is
     * left as it is and two devices that both add it make the same space (the same id, so the same wire id): the second device's
     * commit changes nothing once the first one's reaches it, and where both land the two records are one space.
     */
    private fun ensureKeptSpaces() {
        commitSpaces { spaces, routes ->
            val ensured = app.keepfin.withKeptSpaces(spaces.map { it.toSpace() })
            if (ensured.size == spaces.size) return@commitSpaces spaces to routes
            val byId = spaces.associateBy { it.id }
            ensured.map { byId[it.id] ?: it.toLib() } to routes
        }
    }

    // ------------------------------------------------------------------------------------------------ reading

    /** Re-reads what changed (spaces, routes, the collections touched) and publishes. Blocking; called after every change. */
    private fun refreshNow() {
        val b = backend()
        refreshFrom(b.state, b.replicaId)
    }

    private fun refreshFrom(state: SqlMergeState, replicaId: String): Unit = synchronized(refreshLock) {
        val t0 = System.nanoTime()
        val changes = state.takeChanges()
        val v = views()
        val base = v.baseViews()
        val (spaces, routes) = v.spacesAndRoutes(replicaId, base)
        val t1 = System.nanoTime()
        val previous = small
        val full = changes.all || !loaded
        var collections = previous.collections
        if (full && !loaded && deferCollections) {
            // the first screen does not wait for the collections of a big library: they are read right after, on this thread
            collectionsPending = true
        } else if (full) {
            collectionsPending = false
            collections = v.collections(null, replicaId, base)
        } else {
            val touched = LinkedHashSet<String>(changes.playlists)
            for (name in changes.sets) {
                val at = name.indexOf(':')
                if (at > 0 && name.substring(0, at) in setOf("seen", "new", "removed")) touched += name.substring(at + 1)
            }
            if (touched.isNotEmpty()) {
                val changed = v.collections(touched, replicaId, base)
                val byId = LinkedHashMap<String, LibCollection>()
                for (c in previous.collections) if (c.id !in touched) byId[c.id] = c
                for (c in changed) byId[c.id] = c
                collections = byId.values.sortedBy { it.id }
            }
        }
        val t2 = System.nanoTime()
        small = Small(spaces, routes, collections, base, spaces.associate { LibraryMapping.spaceWireId(it.id) to it.id })
        loaded = true
        publish()
        holder.bump()
        mirrorSettings()
        if (collectionsPending) scheduler.execute { try { collectionsPending = false; loaded = false; deferCollections = false; refreshNow() } catch (t: Throwable) { log("collections failed", t) } }
    }

    /** The collections and problems the UI shows; the items are read in pages ([browse]). */
    private fun publish() {
        val s = small
        val collections = s.collections.map { it.toCollection(follows) }
        val problems = ArrayList<String>()
        val rd = reader
        if (rd != null) {
            val unplaced = rd.rows("SELECT COUNT(*) FROM lib_index WHERE place IS NULL")[0][0] as Long
            var unlocated = 0
            var missingCount = 0
            val missing = missingLinked
            for (r in rd.rows("SELECT id FROM lib_index WHERE substr(place, 1, 7) = 'linked:'")) {
                val id = r[0] as String
                if (locators.get(id) == null) unlocated++ else if (id in missing) missingCount++
            }
            if (unplaced > 0) problems += "$unplaced items are in the library but their files are not on this device."
            if (unlocated > 0) problems += "$unlocated linked files must be found again on this phone. Open Settings > Storage and choose Find linked files."
            if (missingCount > 0) problems += "$missingCount linked files are not on this phone right now. They stay in your library."
        }
        if (lastScan == null) lastScan = now()
        flow.value = LibrarySnapshot(collections, scanning = false, scannedAt = lastScan, problems = problems)
    }

    /** The settings carry the spaces and routes the UI reads; the store is the source, the settings a mirror. */
    private fun mirrorSettings() {
        val s = small
        if (s.spaces.isEmpty()) return
        val projected = s.spaces.map { it.toSpace() } to (s.routes.music to s.routes.other)
        if (projected == lastMirrored) return
        lastMirrored = projected
        host.updateSettings { st ->
            val next = st.library.copy(spaces = projected.first, musicRoute = projected.second.first, otherRoute = projected.second.second)
            if (next == st.library) st else st.copy(library = next)
        }
    }

    /** A local settings edit (spaces, routes) becomes operations; only what differs from the last mirrored value counts. */
    private fun settingsToOps() {
        val lib = host.settings.value.library
        val current = lib.spaces to (lib.musicRoute to lib.otherRoute)
        val mirrored = lastMirrored ?: return
        if (current == mirrored) return
        if (lib.spaces.isEmpty()) return
        lastMirrored = current
        commitSpaces { spaces, _ -> lib.spaces.map { s -> s.toLib().keepLinked(spaces) } to LibRoutes(lib.musicRoute, lib.otherRoute) }
    }

    // ------------------------------------------------------------------------------------------------ item reads (a few at a time)

    private fun actorField(b: RepositoryBackend) = LibraryMapping.PLAYS_PREFIX + b.store.actorId

    /** The library records of [ids] and this actor's play counter of each. Unknown ids are absent. */
    private fun readItems(ids: Collection<String>): Pair<List<LibItem>, Map<String, Long>> {
        if (ids.isEmpty()) return emptyList<LibItem>() to emptyMap()
        val b = backend()
        return views().libItems(ids, b.replicaId, small.base, actorField(b))
    }

    private fun libItem(id: String): LibItem? = readItems(listOf(id)).first.firstOrNull()

    /** The item placed at [path] whose content is [contentId] (an earlier setup into the same folder copied it), or null. Indexed by content. */
    fun itemWithContentAt(path: String, contentId: String): String? =
        rd().rows("SELECT id, place FROM lib_index WHERE content = ?", listOf(contentId)).firstOrNull { it[1] == path }?.get(0) as String?

    private fun presentIds(ids: Collection<String>): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val out = HashSet<String>()
        for (chunk in ids.distinct().chunked(500)) {
            for (r in rd().rows("SELECT id FROM lib_index WHERE id IN (SELECT value FROM json_each(?))", listOf(jsonList(chunk)))) out += r[0] as String
        }
        return out
    }

    /** The placement of an item, or null (unknown, or not on this device). */
    private fun placeOf(id: String): String? = rd().rows("SELECT place FROM lib_index WHERE id = ?", listOf(id)).firstOrNull()?.get(0) as String?

    // ------------------------------------------------------------------------------------------------ writing

    private fun diffContext(b: RepositoryBackend, observed: ObservedLookup, plays: Map<String, Long>): DiffContext {
        val s = small
        return DiffContext(
            actor = b.store.actorId, replicaId = b.replicaId, newId = newId,
            // a playlist's songs are read when a reorder needs them, not kept for every playlist
            ownPlays = { plays[it] ?: 0L }, songs = { playlist -> views().songs(playlist).map { Song(it.first, it.second, it.third) } }, observed = observed,
        )
    }

    private fun answer(b: RepositoryBackend, q: RecordingObserved): ObservedLookup {
        val o = b.store.observed(q.items.toList(), q.playlists.toList(), q.spaces.toList(), q.setElements()).get(30, TimeUnit.SECONDS)
        return ObservedAnswer(
            items = o.items.mapValues { (_, v) -> ObservedTags(v.tags.map { it.toString() }, v.heads.map { it.toString() }) },
            playlists = o.playlists.mapValues { (_, v) -> v.map { it.toString() } },
            spaces = o.spaces.mapValues { (_, v) -> v.map { it.toString() } },
            setElements = o.setElements.mapValues { (_, byElement) -> byElement.mapValues { (_, v) -> v.map { it.toString() } } },
        )
    }

    /**
     * What a mutation changes: the items, collections, spaces and routes before and after. Only the records that change are listed;
     * the diff of the shared mapping runs over just those (a delete is a record that is only in [itemsBefore]).
     */
    private class Change(
        val itemsBefore: List<LibItem> = emptyList(),
        val itemsAfter: List<LibItem> = emptyList(),
        val collectionsBefore: List<LibCollection> = emptyList(),
        val collectionsAfter: List<LibCollection> = emptyList(),
        val spaces: Pair<List<LibSpace>, List<LibSpace>>? = null,
        val routes: Pair<LibRoutes, LibRoutes>? = null,
        val plays: Map<String, Long> = emptyMap(),
        val extra: List<app.keepfin.core.library.OpDraft> = emptyList(),
    )

    /**
     * Turns [change] into operations, applies them (durable in the outbox before this returns) and refreshes. Items that only a
     * collection names stand in as unchanged records, so the collection's entries are compared against them. Returns whether
     * anything was written.
     */
    private fun commit(fresh: Boolean = false, change: Change): Boolean = synchronized(writeLock) {
        val b = backend()
        val s = small
        val spacesBefore = change.spaces?.first ?: s.spaces
        val spacesAfter = change.spaces?.second ?: s.spaces
        val routesBefore = change.routes?.first ?: s.routes
        val routesAfter = change.routes?.second ?: s.routes
        val inItems = HashSet<String>()
        for (i in change.itemsBefore) inItems += i.id
        for (i in change.itemsAfter) inItems += i.id
        val stubIds = LinkedHashSet<String>()
        for (c in change.collectionsBefore) for (id in c.itemIds + c.newItemIds + c.removedItemIds) if (id !in inItems) stubIds += id
        for (c in change.collectionsAfter) for (id in c.itemIds + c.newItemIds + c.removedItemIds) if (id !in inItems) stubIds += id
        val stubs = stubIds.map { LibItem(id = it, space = "", kind = "other", title = "") }
        val before: LibModel? = if (fresh) null else LibModel(change.itemsBefore + stubs, change.collectionsBefore, spacesBefore, routesBefore)
        val after = LibModel(change.itemsAfter + stubs, change.collectionsAfter, spacesAfter, routesAfter)
        val drafts = ArrayList<app.keepfin.core.library.OpDraft>()
        drafts += LibraryMapping.diffObserving(before, after, { o -> diffContext(b, o, change.plays) }, { q -> answer(b, q) })
        drafts += change.extra
        if (drafts.isEmpty()) return false
        if (app.keepfin.merge.MergeFence.blocksWrites(b.store.libraryId)) throw app.keepfin.merge.LibraryBusyException()
        b.store.apply(drafts.map { StoreDraft(it.kind, it.body) }).get(60, TimeUnit.SECONDS)
        refreshNow()
        true
    }

    /** Changes spaces and routes (small, so always whole). */
    private fun commitSpaces(fresh: Boolean = false, change: (List<LibSpace>, LibRoutes) -> Pair<List<LibSpace>, LibRoutes>): Boolean = synchronized(writeLock) {
        val s = small
        val (spaces, routes) = change(s.spaces, s.routes)
        if (!fresh && spaces == s.spaces && routes == s.routes) return false
        commit(fresh, Change(spaces = s.spaces to spaces, routes = s.routes to routes))
    }

    /** Changes the collections ([change] gets all of them and returns all of them); only the ones that differ are diffed. */
    private fun commitCollections(change: (List<LibCollection>) -> List<LibCollection>): Boolean = synchronized(writeLock) {
        val current = small.collections
        val next = change(current)
        if (next == current) return false
        val currentById = current.associateBy { it.id }
        val nextById = next.associateBy { it.id }
        val before = current.filter { nextById[it.id] != it }
        val after = next.filter { currentById[it.id] != it }
        commit(change = Change(collectionsBefore = before, collectionsAfter = after))
    }

    /** Changes items; a big selection goes in slices of [SLICE], so memory and the size of one change stay bounded. */
    private fun updateItems(ids: Collection<String>, transform: (LibItem) -> LibItem) {
        synchronized(writeLock) {
            for (slice in ids.distinct().chunked(SLICE)) {
                val (items, plays) = readItems(slice)
                val changedBefore = ArrayList<LibItem>()
                val changedAfter = ArrayList<LibItem>()
                for (i in items) {
                    val n = transform(i)
                    if (n != i) { changedBefore += i; changedAfter += n }
                }
                if (changedAfter.isNotEmpty()) commit(change = Change(itemsBefore = changedBefore, itemsAfter = changedAfter, plays = plays))
            }
        }
    }

    private fun addItems(items: List<LibItem>, extra: List<app.keepfin.core.library.OpDraft> = emptyList()) {
        commit(change = Change(itemsAfter = items, extra = extra))
    }

    private fun updateCollection(id: String, transform: (LibCollection) -> LibCollection): Boolean = commitCollections { list ->
        list.map { c -> if (c.id == id) transform(c).let { n -> if (n != c) n.copy(updatedAt = now().toDouble()) else c } else c }
    }


    override fun rescan() {
        scheduler.execute {
            try {
                backend().store.rescan(true).get(60, TimeUnit.SECONDS)
                refreshNow()
                settingsToOps()
                runCatching { checkLinked() }
            } catch (t: Throwable) { log("rescan failed", t) }
        }
    }

    // ------------------------------------------------------------------------------------------------ linked spaces (RepoLinked)

    internal fun checkLinked() = linkedPart.checkLinked()

    internal fun linkedNeedingRelink(): Int = linkedPart.linkedNeedingRelink()

    internal fun linkedReadable(): Boolean = linkedPart.linkedReadable()

    internal fun relinkLinked(): RelinkReport = linkedPart.relinkLinked()

    override fun item(id: String): LibraryItem? = index?.item(id)

    override fun recordPlayback(id: String, position: Double?, finished: Boolean) {
        // while the library is being combined its writer takes only the combine's batches: the play is kept and written afterwards
        if (app.keepfin.merge.MergeFence.blocksWrites(backend().store.libraryId)) {
            app.keepfin.merge.MergeFence.bufferPlayback(backend().store.libraryId, id, position, finished)
            return
        }
        val current = libItem(id) ?: return
        val time = now()
        var plays = current.plays ?: 0.0
        var last = current.lastPlayedAt
        var resume: Double? = current.position
        var watched = current.watched
        val kind = LibraryItemKind.entries.firstOrNull { it.wire == current.kind } ?: LibraryItemKind.OTHER
        if (finished) {
            if (!counted.remove(id)) plays++
            last = time.toDouble()
            resume = null
            if (kind == LibraryItemKind.VIDEO) watched = true
        } else if (position == null) {
            resume = null
        } else {
            if (position < 5.0) counted.remove(id)
            if (position >= PlaybackRules.COUNT_AFTER_SECONDS && counted.add(id)) plays++
            if (position > 0.0) last = time.toDouble()
            if (PlaybackRules.watched(position, current.duration)) { watched = true; resume = null }
            else resume = if (PlaybackRules.resumes(kind, current.duration) && position >= 1.0) position else null
        }
        updateItems(listOf(id)) { it.copy(plays = plays, lastPlayedAt = last, position = resume, watched = watched) }
    }

    override fun setFavorite(id: String, favorite: Boolean) = updateItems(listOf(id)) { it.copy(favorite = favorite) }

    override fun libraryTag(ids: List<String>, add: List<String>, remove: List<String>) {
        updateItems(ids) { it.copy(userTags = TagLogic.editUserTags(it.userTags, add, remove)) }
    }

    override fun editInfo(id: String, edit: TagEdit): LibraryAccess {
        // The folder library keeps the metadata in the library; the tags inside the file are not rewritten yet.
        fun text(value: String?, old: String?) = if (value == null) old else value.trim().ifEmpty { null }
        fun number(value: Int?, old: Double?) = if (value == null) old else value.toDouble().takeIf { it > 0 }
        updateItems(listOf(id)) {
            it.copy(
                title = text(edit.title, it.title) ?: it.title, artist = text(edit.artist, it.artist), album = text(edit.album, it.album),
                albumArtist = text(edit.albumArtist, it.albumArtist), genre = text(edit.genre, it.genre),
                track = number(edit.track, it.track), disc = number(edit.disc, it.disc), year = number(edit.year, it.year),
            )
        }
        return LibraryAccess.Done
    }

    // ------------------------------------------------------------------------------------------------ LibraryApi: files

    /** The record of [name], a file beside the media file at [place], when the core counts it as one of the item's companions. */
    private fun companionRecord(itemId: String, place: String, name: String, content: String, size: Long): StoreDraft? {
        val suffix = app.keepfin.core.copies.companionSuffix(place.substringAfterLast('/'), name) ?: return null
        return StoreDraft("item.set", mapOf("item" to app.keepfin.core.sync.JStr(itemId), "field" to app.keepfin.core.sync.JStr(app.keepfin.core.copies.companionField(suffix)), "value" to app.keepfin.core.copies.companionValue(content, size)))
    }

    /**
     * Gives the lyrics, subtitles and covers beside each item their records (dev-docs/plans/COPIES-COMPANIONS.md), so copies carry
     * them: once per Keepfin folder (afterwards a download records its own, and a rename keeps them, a record being keyed by its
     * suffix). A folder listing is kept for the next items (a download's items sit together); a file is hashed only when it
     * has no record or its size differs from it.
     */
    internal fun recordCompanions() {
        val b = backend()
        // v2: music videos (`Song.video.mp4`) are companions too, so a folder looked at before them is looked at once more (only files without a record are read)
        val done = File(deviceDir, "companions-recorded-v2-${b.replicaId}")
        if (done.exists()) return
        var after = 0L
        val listings = object : LinkedHashMap<String, List<app.keepfin.folder.FolderEntry>>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<app.keepfin.folder.FolderEntry>>) = size > 64
        }
        val drafts = ArrayList<StoreDraft>()
        fun flush() {
            if (drafts.isEmpty()) return
            if (app.keepfin.merge.MergeFence.blocksWrites(b.store.libraryId)) throw app.keepfin.merge.LibraryBusyException()
            b.store.apply(drafts.toList()).get(60, TimeUnit.SECONDS)
            drafts.clear()
        }
        while (true) {
            val rows = rd().rows(
                "SELECT l.id, l.place, CASE WHEN instr(v.fields, '\"companion:') > 0 THEN v.fields END, l.n, l.media FROM lib_index l JOIN item_view v ON v.id = l.id " +
                    "WHERE v.status = 'present' AND l.place IS NOT NULL AND l.n > ? ORDER BY l.n LIMIT 1000",
                listOf(after),
            )
            if (rows.isEmpty()) break
            for (r in rows) {
                val id = r[0] as String
                val place = r[1] as String
                if (isLinkedPlace(place)) continue
                val dir = place.substringBeforeLast('/', "")
                val listed = listings.getOrPut(dir) { try { b.folder.list(dir).entries } catch (e: FolderError) { emptyList() } }
                val recorded = (r[2] as? String)?.let { text ->
                    runCatching { JSONObject(text) }.getOrNull()?.let { o -> o.keys().asSequence().associateWith { o.optJSONObject(it)?.optLong("size", -1L) ?: -1L } }
                } ?: emptyMap()
                for (e in listed) {
                    if (e.isDirectory) continue
                    val suffix = app.keepfin.core.copies.companionSuffix(place.substringAfterLast('/'), e.name) ?: continue
                    // only an audio item owns a music video
                    if (app.keepfin.core.copies.isVideoCompanion(suffix) && r[4] != "audio") continue
                    if (recorded[app.keepfin.core.copies.companionField(suffix)] == e.size) continue
                    val path = join(dir, e.name)
                    val sha = try { b.folder.openRead(path).use(::sha256Hex) } catch (t: Exception) { continue }
                    companionRecord(id, place, e.name, "sha256:$sha", e.size)?.let { drafts += it }
                    if (drafts.size >= 25) flush()
                }
            }
            after = rows.last()[3] as Long
            if (rows.size < 1000) break
        }
        flush()
        done.writeText("1")
    }

    /**
     * Lyrics and subtitles by their extension, and a music video by the core's rule (`Song.video.mp4`, only beside a song [audio]):
     * all of them move, rename and are deleted with their item.
     */
    private fun isSidecarName(name: String, stem: String, audio: Boolean) =
        name.substringAfterLast('.').lowercase() in app.keepfin.ALL_SIDECARS || (audio && name.length > stem.length && app.keepfin.core.copies.isVideoCompanion(name.substring(stem.length)))

    private fun sidecarsOf(b: RepositoryBackend, place: String): List<String> {
        if (isLinkedPlace(place)) return emptyList() // the files of linked spaces are not in the Keepfin folder
        val dir = place.substringBeforeLast('/', "")
        val stem = place.substringAfterLast('/').substringBeforeLast('.')
        val listing = try { b.folder.list(dir) } catch (e: FolderError.NotFound) { return emptyList() }
        val audio = kindOf(place, null) == "audio"
        return listing.entries.filter { !it.isDirectory && it.name.startsWith("$stem.") && it.name != place.substringAfterLast('/') && isSidecarName(it.name, stem, audio) }
            .map { if (dir.isEmpty()) it.name else "$dir/${it.name}" }
    }

    /** Copies [from] to [to] (verified by size and the read-back hash of the copy), then removes [from]. Never replaces. */
    private fun moveFile(b: RepositoryBackend, from: String, to: String) {
        val size = b.folder.stat(from)?.size ?: throw FolderError.NotFound(from)
        // the same name in another folder: the storage moves it in one call (a song to the trash took seconds when it was copied)
        if (to.substringAfterLast('/') == from.substringAfterLast('/') && b.folder.moveInto(from, app.keepfin.folder.parentOf(to))) return
        val result = b.folder.openRead(from).use { b.folder.publishFrom(to, it) }
        if (size >= 0 && result.size != size) {
            b.folder.remove(to)
            throw FolderError.Io("The copy of ${from.substringAfterLast('/')} did not match the original. Nothing was changed.")
        }
        b.folder.remove(from)
    }

    private fun uniqueIn(b: RepositoryBackend, dir: String, wanted: String): String {
        val names = try { b.folder.list(dir).entries.map { it.name }.toSet() } catch (e: FolderError.NotFound) { emptySet() }
        return SpaceLogic.uniqueName(names, wanted)
    }

    /**
     * Runs a change of linked files through MediaStore. Files from before a reinstall (or any Android will not let this app change) ask
     * the user first and the caller runs the call again after a yes; nothing has been touched when the question is returned.
     */
    private fun linkedAccess(ids: List<String>, uris: List<String>, owned: Map<String, Boolean>, delete: Boolean, operation: () -> Unit): LibraryAccess {
        val media = linked ?: throw IllegalStateException("Android didn't allow changing this file.")
        val foreign = uris.filter { owned[it] != true }
        val needs = if (foreign.isEmpty()) emptyList() else media.withoutWriteAccess(foreign)
        if (needs.isNotEmpty() && sdk >= 30) return LibraryAccess.NeedsConfirmation(media.consentRequest(needs, delete), ids)
        return try { operation(); LibraryAccess.Done } catch (error: SecurityException) {
            val sender = media.recoverable(error) ?: throw IllegalStateException("Android didn't allow changing this file. Allow access to music and videos, then try again.")
            LibraryAccess.NeedsConfirmation(sender, ids)
        }
    }

    private fun renameLinked(id: String, item: LibItem, place: String, name: String): LibraryAccess {
        val media = linked ?: throw IllegalStateException("This file is not on this phone.")
        val where = locators.get(id) ?: throw IllegalStateException("This file must be found again first. Open Settings > Storage and choose Find linked files.")
        val now = media.stat(where.uri) ?: throw IllegalStateException("This file is no longer on the phone.")
        val oldName = now.path.substringAfterLast('/')
        val extension = oldName.substringAfterLast('.', "")
        val stem = Providers.safeName(name.trim().removeSuffix(if (extension.isEmpty()) "" else ".$extension"), 120)
        val wanted = if (extension.isEmpty()) stem else "$stem.$extension"
        if (wanted == oldName) return LibraryAccess.Done
        val access = linkedAccess(listOf(id), listOf(where.uri), mapOf(where.uri to where.owned), false) { media.rename(where.uri, wanted) }
        if (access !is LibraryAccess.Done) return access
        val after = media.stat(where.uri)
        locators.putAll(mapOf(id to where.copy(path = after?.path ?: (now.path.substringBeforeLast('/', "").let { if (it.isEmpty()) wanted else "$it/$wanted" }), size = after?.size ?: where.size, mtime = after?.mtime ?: where.mtime)))
        val newPlace = join(place.substringBeforeLast('/', ""), after?.path?.substringAfterLast('/') ?: wanted) // MediaStore may have picked a free name
        updateItems(listOf(id)) { it.copy(place = newPlace) }
        return LibraryAccess.Done
    }

    override fun rename(id: String, name: String): LibraryAccess {
        val b = backend()
        val item = libItem(id) ?: throw IllegalArgumentException("This item is no longer in the library.")
        val place = item.place ?: throw IllegalStateException("This file is not on this device.")
        if (isLinkedPlace(place)) return renameLinked(id, item, place, name)
        val oldName = place.substringAfterLast('/')
        val extension = oldName.substringAfterLast('.', "")
        val stem = Providers.safeName(name.trim().removeSuffix(if (extension.isEmpty()) "" else ".$extension"), 120)
        val wanted = if (extension.isEmpty()) stem else "$stem.$extension"
        if (wanted == oldName) return LibraryAccess.Done
        val dir = place.substringBeforeLast('/', "")
        val oldStem = oldName.substringBeforeLast('.', oldName)
        val sidecars = sidecarsOf(b, place)
        try {
            b.folder.rename(place, wanted)
        } catch (e: FolderError.NameConflict) {
            throw IllegalArgumentException("A file with that name already exists in this folder.")
        }
        for (side in sidecars) {
            val sideName = side.substringAfterLast('/')
            try { b.folder.rename(side, stem + sideName.removePrefix(oldStem)) } catch (e: FolderError) { log("sidecar rename failed", e) }
        }
        val newPlace = join(dir, wanted)
        updateItems(listOf(id)) { it.copy(place = newPlace) }
        return LibraryAccess.Done
    }

    override fun move(itemIds: List<String>, spaceId: String): LibraryAccess {
        val b = backend()
        val target = small.spaces.firstOrNull { it.id == spaceId && it.type == "folder" }
            ?: throw IllegalArgumentException("Choose a folder space. That space no longer exists or only gathers items by rules.")
        val moved = HashMap<String, String>()
        val linkedItems = ArrayList<LibItem>()
        var failure: String? = null
        for (slice in itemIds.distinct().chunked(SLICE)) {
        val known = readItems(slice).first.associateBy { it.id }
        for (id in slice) {
            val item = known[id] ?: continue
            if (item.space == spaceId) continue
            val place = item.place ?: continue
            if (isLinkedPlace(place)) { linkedItems += item; continue }
            val currentSpace = small.spaces.firstOrNull { it.id == item.space }
            val sub = if (target.layout == "flat") "" else folderWithin(currentSpace?.folder ?: "", place)
            val dir = join(target.folder, sub)
            try {
                b.folder.mkdirs(dir)
                val name = uniqueIn(b, dir, place.substringAfterLast('/'))
                val sidecars = sidecarsOf(b, place)
                val oldStem = place.substringAfterLast('/').substringBeforeLast('.')
                moveFile(b, place, join(dir, name))
                val newStem = name.substringBeforeLast('.', name)
                for (side in sidecars) {
                    try { moveFile(b, side, join(dir, newStem + side.substringAfterLast('/').removePrefix(oldStem))) } catch (e: FolderError) { log("sidecar move failed", e) }
                }
                moved[id] = join(dir, name)
            } catch (e: FolderError) {
                if (failure == null) failure = e.message
            }
        }
        }
        if (moved.isNotEmpty()) updateItems(moved.keys) { it.copy(space = spaceId, place = moved.getValue(it.id)) }
        if (linkedItems.isNotEmpty()) {
            val access = moveLinked(linkedItems, target)
            if (access !is LibraryAccess.Done) return access
        }
        if (failure != null) throw IllegalStateException(if (moved.isEmpty()) failure else "Some files could not be moved: $failure")
        return LibraryAccess.Done
    }

    /** Linked files move to another linked space inside MediaStore (consent where Android asks); they never cross into the Keepfin folder here. */
    private fun moveLinked(items: List<LibItem>, target: LibSpace): LibraryAccess {
        if (!target.linked) throw IllegalStateException("Files in a linked space stay where they are. Choose another linked space, or bring them into the Keepfin folder first.")
        val media = linked ?: throw IllegalStateException("Android didn't allow changing this file.")
        val rows = items.mapNotNull { i -> locators.get(i.id)?.let { i to it } }
        if (rows.isEmpty()) throw IllegalStateException("These files must be found again first. Open Settings > Storage and choose Find linked files.")
        val targetSpace = target.toSpace()
        fun subOf(item: LibItem) = if (target.layout == "flat") "" else linkedSub(item.place!!)
        fun kindOf(item: LibItem) = LibraryItemKind.entries.firstOrNull { it.wire == item.kind } ?: LibraryItemKind.OTHER
        val access = linkedAccess(rows.map { it.first.id }, rows.map { it.second.uri }, rows.associate { it.second.uri to it.second.owned }, false) {
            for ((item, where) in rows) {
                if (media.stat(where.uri) == null) continue
                media.move(where.uri, SpaceLogic.folderPath(app.keepfin.StorageRoot.of(kindOf(item)), targetSpace, subOf(item)) + "/")
            }
        }
        if (access !is LibraryAccess.Done) return access
        val placed = HashMap<String, String>()
        val fresh = HashMap<String, LinkedLocator>()
        for ((item, where) in rows) {
            val after = media.stat(where.uri) ?: continue
            fresh[item.id] = where.copy(path = after.path, size = after.size, mtime = after.mtime)
            placed[item.id] = join("$LINKED_PREFIX${target.id}", join(subOf(item), after.path.substringAfterLast('/')))
        }
        locators.putAll(fresh)
        if (placed.isNotEmpty()) updateItems(placed.keys) { it.copy(space = target.id, place = placed.getValue(it.id)) }
        return LibraryAccess.Done
    }

    // ------------------------------------------------------------------------------------------------ the Trash and deletes (RepoTrash)

    override val keepsTrash: Boolean get() = trashPart.keepsTrash

    override fun trashedAmong(ids: List<String>): List<String> = trashPart.trashedAmong(ids)

    internal fun purgeTrash(): Int = trashPart.purgeTrash()

    override fun markForErase(ids: List<String>) = trashPart.markForErase(ids)

    override fun eraseTrashed(ids: List<String>): Int = trashPart.eraseTrashed(ids)

    override fun trashEntries(): List<TrashEntry> = trashPart.trashEntries()

    override fun eraseFromTrash(ids: List<String>): Int = trashPart.eraseFromTrash(ids)

    override fun emptyTrash(): Int = trashPart.emptyTrash()

    override fun trashableCount(ids: List<String>): Int = trashPart.trashableCount(ids)

    override fun restoreItems(ids: List<String>): RestoreResult = trashPart.restoreItems(ids)

    override fun deleteItems(ids: List<String>): LibraryAccess = trashPart.deleteItems(ids)

    private fun removeItems(gone: Set<String>) {
        synchronized(writeLock) {
            for (slice in gone.chunked(SLICE)) {
                val here = slice.toHashSet()
                val (items, plays) = readItems(slice)
                // the collections that name them stay as they are on both sides: the entries of the items that leave drop out of the diff
                val named = small.collections.filter { c -> c.itemIds.any { it in here } || c.newItemIds.any { it in here } || c.removedItemIds.any { it in here } }
                if (items.isNotEmpty()) commit(change = Change(itemsBefore = items, collectionsBefore = named, collectionsAfter = named, plays = plays))
            }
        }
        locators.remove(gone)
        if (missingLinked.any { it in gone }) missingLinked = missingLinked - gone
    }

    /**
     * Removes the files of linked items through MediaStore. [gone] collects the items whose file is now gone (the caller drops them from
     * the library); a file that was already gone counts too. Files Android wants a yes for are asked about in one request.
     */
    private fun deleteLinked(items: List<LibItem>, gone: MutableSet<String>, fail: (String) -> Unit): LibraryAccess {
        val media = linked
        val rows = ArrayList<Pair<LibItem, LinkedLocator>>()
        for (item in items) {
            val where = locators.get(item.id)
            if (where == null || media == null) { fail("These files must be found again first. Open Settings > Storage and choose Find linked files."); continue }
            rows += item to where
        }
        if (media == null || rows.isEmpty()) return LibraryAccess.Done
        val present = rows.filter { media.stat(it.second.uri) != null }
        for ((item, where) in rows) if ((item to where) !in present) gone += item.id
        if (present.isEmpty()) return LibraryAccess.Done
        val foreign = present.filter { !it.second.owned }.map { it.second.uri }
        // Android 11 and newer ask once for all of them; on 10 each refusal carries its own question
        val needs = if (foreign.isEmpty() || sdk < 30) emptyList() else media.withoutWriteAccess(foreign)
        val direct = present.filter { it.second.uri !in needs }
        for ((item, where) in direct) {
            try {
                if (media.delete(where.uri)) gone += item.id else fail("Android didn't allow deleting ${item.title}.")
            } catch (e: SecurityException) {
                val sender = media.recoverable(e) ?: run { fail("Android didn't allow deleting ${item.title}."); null }
                if (sender != null) return LibraryAccess.NeedsConfirmation(sender, listOf(item.id))
            }
        }
        if (needs.isNotEmpty()) {
            return LibraryAccess.NeedsConfirmation(media.consentRequest(needs, true), present.filter { it.second.uri in needs }.map { it.first.id })
        }
        return LibraryAccess.Done
    }

    /** After the system answered a delete question: the linked files that are gone leave the library; the rest stay. */
    override fun confirmationResult(ids: List<String>, granted: Boolean) {
        val media = linked
        if (granted && media != null) {
            val gone = readItems(ids).first.filter { i -> isLinkedPlace(i.place) && locators.get(i.id)?.let { media.stat(it.uri) == null } == true }.mapTo(HashSet()) { it.id }
            if (gone.isNotEmpty()) try { removeItems(gone) } catch (t: Throwable) { log("could not drop deleted linked files", t) }
        }
        rescan()
    }

    override fun libraryAddFrom(itemIds: List<String>, collectionIds: List<String>, spaceId: String, copy: Boolean): LibraryAccess {
        val target = small.spaces.firstOrNull { it.id == spaceId && it.type == "folder" }
            ?: throw IllegalArgumentException("Choose a folder space. That space no longer exists or only gathers items by rules.")
        val s = small
        val wanted = (itemIds + collectionIds.flatMap { id -> s.collections.firstOrNull { it.id == id }?.itemIds.orEmpty() }).distinct()
        val known = readItems(wanted).first.associateBy { it.id }
        val ids = wanted.filter { it in known }
        if (ids.isEmpty()) return LibraryAccess.Done
        if (!copy) return move(ids, spaceId)
        val b = backend()
        var failed = 0
        var firstError: String? = null
        val made = ArrayList<Pair<LibItem, app.keepfin.folder.PublishResult>>()
        val targets = ids.mapNotNull { known[it] }.filter { it.space != spaceId && it.place != null }
        for (item in targets) {
            val place = item.place!!
            val currentSpace = s.spaces.firstOrNull { it.id == item.space }
            val fromLinked = isLinkedPlace(place)
            val sub = if (target.layout == "flat") "" else if (fromLinked) linkedSub(place) else folderWithin(currentSpace?.folder ?: "", place)
            val dir = join(target.folder, sub)
            try {
                b.folder.mkdirs(dir)
                val name = uniqueIn(b, dir, place.substringAfterLast('/'))
                val source = if (!fromLinked) b.folder.openRead(place)
                    else (locators.get(item.id)?.let { w -> imports?.open(w.uri) } ?: throw FolderError.NotFound(place))
                val result = source.use { b.folder.publishFrom(join(dir, name), it) }
                made += item.copy(id = newId(), space = spaceId, place = join(dir, name), favorite = false, plays = null, userTags = item.userTags, addedAt = now().toDouble()) to result
            } catch (e: FolderError) {
                failed++
                if (firstError == null) firstError = e.message
            }
        }
        if (made.isNotEmpty()) {
            addItems(made.map { it.first }, made.map { (i, r) -> LibraryMapping.reviseDraft(i.id, "sha256:${r.sha256}", r.size, emptyList()) })
        }
        if (failed > 0) throw IllegalStateException(if (failed == targets.size) (firstError ?: "The files could not be copied.") else "$failed of ${targets.size} files could not be copied. The others are in ${target.name}.")
        return LibraryAccess.Done
    }

    // ------------------------------------------------------------------------------------------------ imports and new files (RepoImports)

    fun addFile(spaceId: String, subfolder: String, fileName: String, source: InputStream, template: LibItem): AddedFile = importsPart.addFile(spaceId, subfolder, fileName, source, template)

    override fun importFiles(uris: List<String>, spaceId: String): ImportResult = importsPart.importFiles(uris, spaceId)

    override fun earlierFilesPermissions(): List<String> = importsPart.earlierFilesPermissions()

    override fun adoptEarlierFiles(): Int = importsPart.adoptEarlierFiles()

    // ------------------------------------------------------------------------------------------------ spaces (RepoImports)

    override fun saveSpace(space: Space, moveExisting: Boolean) = importsPart.saveSpace(space, moveExisting)

    override fun deleteSpace(id: String) = importsPart.deleteSpace(id)

    override fun spaceReorder(ids: List<String>) = importsPart.spaceReorder(ids)

    override fun defaultSpaceFor(url: String, uploader: String?): String? = importsPart.defaultSpaceFor(url, uploader)

    // ------------------------------------------------------------------------------------------------ collections (RepoCollections)

    override fun collection(id: String): LibraryCollection? = collectionsPart.collection(id)

    override fun collectionSave(id: String?, name: String, itemIds: List<String>, space: String?): String = collectionsPart.collectionSave(id, name, itemIds, space)

    override fun collectionDelete(id: String) = collectionsPart.collectionDelete(id)

    override fun collectionAdd(id: String, itemIds: List<String>) = collectionsPart.collectionAdd(id, itemIds)

    override fun collectionRemove(id: String, itemIds: List<String>) = collectionsPart.collectionRemove(id, itemIds)

    override fun collectionReorder(id: String, itemIds: List<String>) = collectionsPart.collectionReorder(id, itemIds)

    override fun collectionOpened(id: String) = collectionsPart.collectionOpened(id)

    override fun collectionFollow(id: String, update: FollowUpdate) = collectionsPart.collectionFollow(id, update)

    override fun collectionCheck(id: String?) = collectionsPart.collectionCheck(id)

    override fun collectionReview(id: String) = collectionsPart.collectionReview(id)

    // ------------------------------------------------------------------------------------------------ follow checks and device-only follow state (RepoCollections)

    /** Jobs queued by a follow check: their items show up as new in the collection when they are saved (device-only, like the earlier library). */
    private val followJobs: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet<String>())

    fun markFollowJobs(jobIds: Collection<String>) = collectionsPart.markFollowJobs(jobIds)

    fun sourceKeysOf(itemId: String): Set<String> = collectionsPart.sourceKeysOf(itemId)

    fun report(report: FollowReport) = collectionsPart.report(report)

    fun collectionsNow(): List<LibraryCollection> = collectionsPart.collectionsNow()

    fun mutateCollection(id: String, transform: (LibraryCollection) -> LibraryCollection): LibraryCollection? = collectionsPart.mutateCollection(id, transform)

    fun recordFollowResult(id: String, checkedAt: Long?, error: String?) = collectionsPart.recordFollowResult(id, checkedAt, error)

    // ------------------------------------------------------------------------------------------------ sidecars and music videos (RepoMusicVideos)

    override fun lyrics(id: String): Lyrics? = musicVideosPart.lyrics(id)

    override fun chapters(id: String): List<app.keepfin.LibraryChapter> = musicVideosPart.chapters(id)

    override fun subtitles(id: String): List<SubtitleFile> = musicVideosPart.subtitles(id)

    override fun musicVideoUri(id: String): String? = musicVideosPart.musicVideoUri(id)

    override fun songOf(media: app.keepfin.MediaInfo): app.keepfin.SongOfLink? = downloadsPart.songOf(media)

    override fun musicVideoFacts(id: String): app.keepfin.MusicVideoFacts? = musicVideosPart.musicVideoFacts(id)

    override fun saveLyrics(id: String, text: String) = musicVideosPart.saveLyrics(id, text)

    internal fun attachMusicVideo(itemId: String, media: File): AttachedVideo? = musicVideosPart.attachMusicVideo(itemId, media)

    override fun removeMusicVideo(id: String): LibraryAccess = musicVideosPart.removeMusicVideo(id)

    /** A finished music video, kept beside its song: where it is and what plays it. */
    internal class AttachedVideo(val itemId: String, val path: String, val uri: String)

    // ------------------------------------------------------------------------------------------------ setup (migration of an earlier library)

    /** An item brought over from the earlier library: [contentId] is `sha256:<hex>` of the copy in the folder, or null for a linked file. */
    internal class MigratedItem(val item: LibItem, val contentId: String?, val size: Long, val locator: LinkedLocator? = null)

    fun hasItem(id: String): Boolean = rd().rows("SELECT 1 FROM lib_index WHERE id = ?", listOf(id)).isNotEmpty()

    /** The folder the library is stored in (the setup copies files through it). */
    fun folderAccess(): FolderAccess = backend().folder

    /** Writes the migrated spaces and routes (replacing the defaults). Idempotent: nothing is written when the model already has them. */
    fun migrateLayout(spaces: List<LibSpace>, routes: LibRoutes) {
        commitSpaces { _, _ -> spaces to routes }
    }

    /** Adds items (placement and first revision as operations). Items already in the library are skipped, so a repeated batch is harmless. Returns how many were added. */
    fun migrateItems(batch: List<MigratedItem>): Int {
        // where each linked file is on this phone goes first (device-only): a stop before the commit below only leaves an unused address
        locators.putAll(batch.mapNotNull { m -> m.locator?.let { m.item.id to it } }.toMap())
        val known = presentIds(batch.map { it.item.id })
        val fresh = batch.filter { it.item.id !in known }
        if (fresh.isEmpty()) return 0
        addItems(fresh.map { it.item }, fresh.filter { it.contentId != null }.map { LibraryMapping.reviseDraft(it.item.id, it.contentId!!, it.size, emptyList()) })
        return fresh.size
    }

    /** Adds the migrated collections and playlists (their item ids are the new ones). Existing ids are skipped. */
    fun migrateCollections(list: List<LibCollection>) {
        commitCollections { current ->
            val known = current.mapTo(HashSet()) { it.id }
            val add = list.filter { it.id !in known }
            if (add.isEmpty()) current else current + add
        }
    }

    // ------------------------------------------------------------------------------------------------ downloads (RepoDownloads)

    internal fun itemOfJob(jobId: String): String? = downloadsPart.itemOfJob(jobId)

    internal fun rememberJobItem(jobId: String, itemId: String) = downloadsPart.rememberJobItem(jobId, itemId)

    /** A finished download inside the Keepfin folder. [duplicate] is true when the library already held identical content (the new copy was removed). */
    internal class SavedDownload(val itemId: String, val uri: String, val path: String, val sidecarUris: List<String>, val sidecarProblems: List<String>, val duplicate: Boolean)

    internal fun saveDownload(job: Job, tags: SourceTags?, spaceId: String, subfolder: String, stem: String, media: File, sidecars: List<File>, chapters: List<app.keepfin.core.library.LibChapter>? = null): SavedDownload =
        downloadsPart.saveDownload(job, tags, spaceId, subfolder, stem, media, sidecars, chapters)

    internal fun collectionIdFor(sourceUrl: String, preferred: String?): String = downloadsPart.collectionIdFor(sourceUrl, preferred)

    internal fun ensureCollection(sourceUrl: String, name: String, kind: CollectionKind, space: String, seenKeys: Collection<String>, infinite: Boolean, preferredId: String?, sync: SyncRequest? = null): LibraryCollection =
        downloadsPart.ensureCollection(sourceUrl, name, kind, space, seenKeys, infinite, preferredId, sync)

    internal fun findExisting(job: Job, record: Recording): ExistingMatch? = downloadsPart.findExisting(job, record)

    override fun existing(spaceId: String, entries: List<PlaylistEntry>): Set<String> = downloadsPart.existing(spaceId, entries)

    override fun existingFor(spaceId: String, media: MediaInfo): ExistingMatch? = downloadsPart.existingFor(spaceId, media)

    // ------------------------------------------------------------------------------------------------ test and diagnostics hooks

    /** The model as projected from the store (the core record). */
    internal fun modelNow(): LibModel {
        val ids = ArrayList<String>()
        var after = ""
        while (true) {
            val page = rd().rows("SELECT id FROM lib_index WHERE id > ? ORDER BY id LIMIT 2000", listOf(after))
            if (page.isEmpty()) break
            page.mapTo(ids) { it[0] as String }
            after = ids.last()
        }
        return LibModel(readItems(ids).first, small.collections, small.spaces, small.routes)
    }

    // ------------------------------------------------------------------------------------------------ copies (dev-docs/plans/KEEPFIN-COPIES-SPEC.md)

    /** The pieces the copies engine stands on; blocking until the library is open. */
    internal fun copiesBackend(): CopiesBackend {
        val b = backend()
        return CopiesBackend(b.folder, b.replicaId, b.store.libraryId) { block -> block(app.keepfin.folder.sql.CopiesSql(rd()::rows)) }
    }

    /** Writes library operations on behalf of the copies engine (place registers, imports, revisions, deletions), then refreshes the screens. */
    internal fun applyCopiesOps(drafts: List<StoreDraft>) {
        if (drafts.isEmpty()) return
        if (app.keepfin.merge.MergeFence.blocksWrites(backend().store.libraryId)) throw app.keepfin.merge.LibraryBusyException()
        backend().store.apply(drafts).get(60, TimeUnit.SECONDS)
        refreshNow()
    }

    /** What an `item.delete` or `item.revise` must observe for [itemId]. */
    internal fun observedItem(itemId: String): app.keepfin.core.sync.ObservedItem? =
        backend().store.observed(items = listOf(itemId)).get(30, TimeUnit.SECONDS).items[itemId]

    /** Runs [block] with the library's version vector sum (cheap change detection for "sync when the library changed"). */
    internal fun versionSum(): Long = backend().store.versionVector().get(30, TimeUnit.SECONDS).values.sum()}
