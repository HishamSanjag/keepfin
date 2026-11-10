package app.keepfin

import android.content.Context
import app.keepfin.core.errors.Errors
import app.keepfin.core.errors.Platform
import app.keepfin.core.signin.SignIn
import app.keepfin.core.signin.SignInSource
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * Typed Studio 2 engine: single source of truth for jobs (guarded by [lock], immutable records), a worker pool
 * sized by [EngineSettings.concurrency], throttled StateFlows (≤4 updates/s while transferring) and queue.json
 * persistence on structural or terminal changes only.
 */
class MediaEngine(internal val context: Context) : EngineApi, app.keepfin.setup.SetupEngine {
    private val lock = Any()
    internal val publishingLock = Any()
    private val store = JobStore(File(context.filesDir, "queue.json"))
    internal val output = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Keepfin")
    private val cookieFile = File(context.filesDir, "cookies.txt")
    /** The recordings the user picked for music links; the matcher uses them first. */
    internal val musicChoices = MusicChoiceStore(File(context.filesDir, "music-choices.json"))
    private val settingsPrefs = context.getSharedPreferences("engine-settings", Context.MODE_PRIVATE)
    internal val publicDownloads = PublicDownloads(context)
    internal val thumbnails = ThumbnailStore(context)
    internal val metadata = MetadataRequests()

    private var records: List<JobRecord> = emptyList()
    private val running = HashSet<String>()
    private val processes = HashMap<String, String>()

    private val jobsFlow = MutableStateFlow<List<Job>>(emptyList())
    private val settingsFlow = MutableStateFlow(loadSettings())
    private val cookiesFlow = MutableStateFlow(cookieFile.isFile)
    override val jobs: StateFlow<List<Job>> = jobsFlow.asStateFlow()
    override val settings: StateFlow<EngineSettings> = settingsFlow.asStateFlow()
    override val hasCookies: StateFlow<Boolean> = cookiesFlow.asStateFlow()

    internal val toolsUpdater = ToolsUpdater(context) { settingsFlow.value }
    private val libraryHost = object : LibraryHost {
        override val jobs get() = jobsFlow
        override val tagTools get() = mediaTools
        override val settings get() = settingsFlow
        override fun updateSettings(transform: (EngineSettings) -> EngineSettings) = this@MediaEngine.updateSettings(transform)
        override fun jobThumbnail(jobId: String) = thumbnails.loadJob(jobId)
        override fun sidecarUris(): Set<String> = synchronized(lock) { records.flatMap { r -> r.publicFiles.filter { it != r.job.publicUri } }.toSet() }
        override fun repointFile(uri: String, publicPath: String?) = this@MediaEngine.repointFile(uri, publicPath)
        override fun requestCheck(id: String?, review: Boolean) = FollowScheduler.checkNow(context, id, review)
        override fun followSettingsChanged() = FollowScheduler.schedule(context)
    }
    internal val mediaTools = MediaTools(context) { toolsUpdater.ensureInit() }
    /** The library on the Keepfin folder when a profile exists (profiles.json); otherwise null and [NoLibrary] answers until setup finishes. */
    @Volatile internal var folderLibrary: app.keepfin.library.repo.LibraryRepository? = app.keepfin.library.repo.LibrarySelector.create(context, libraryHost)
        private set
    override val libraryApi: LibraryApi get() = folderLibrary ?: NoLibrary

    // ---- the Keepfin folder (setup) ----------------------------------------------------------------

    override fun legacyLibrarySettings(): LibrarySettings = settingsFlow.value.library

    override fun legacySettingsJson(): String? = settingsPrefs.getString("json", null)

    /** Switches from [NoLibrary] to the library on the Keepfin folder (a profile exists) without restarting the app. */
    override fun attachFolderLibrary() {
        if (folderLibrary != null) return
        val repo = app.keepfin.library.repo.LibrarySelector.create(context, libraryHost) ?: return
        folderLibrary = repo
        repo.start()
    }

    /** Tests: the engine (and with it the playback service) runs on [repo], a library the test built; returns the one it had, to put back. */
    internal fun swapFolderLibrary(repo: app.keepfin.library.repo.LibraryRepository?): app.keepfin.library.repo.LibraryRepository? {
        val old = folderLibrary
        folderLibrary = repo
        return old
    }

    /** Closes the library on the Keepfin folder and opens the one the profile and `folder.json` name now (after combining or separating). */
    internal fun reopenFolderLibrary() {
        val old = folderLibrary
        old?.shutdown()
        folderLibrary = null
        val repo = app.keepfin.library.repo.LibrarySelector.create(context, libraryHost) ?: return
        folderLibrary = repo
        repo.start()
    }

    /** After a library switch the download queue refers to the new ids: `existing.itemId`, spaces and collections. */
    internal fun remapLibraryIds(maps: app.keepfin.merge.IdMaps) {
        synchronized(lock) {
            records = records.map { r ->
                val j = r.job
                val ex = j.existing?.let { e -> e.copy(itemId = e.itemId?.let { maps.item(it) }, space = maps.space(e.space) ?: e.space) }
                r.copy(job = j.copy(existing = ex, space = j.space?.let { maps.space(it) ?: it }, collectionId = j.collectionId?.let { maps.collection(it) ?: it }))
            }
            save()
        }
        publish()
    }

    /** The collections of the library in use (the follow check reads them). */
    internal fun collectionsNow(): List<LibraryCollection> = folderLibrary?.library?.value?.collections.orEmpty()

    private fun collectionIdFor(sourceUrl: String, preferred: String?): String? = folderLibrary?.collectionIdFor(sourceUrl, preferred)
    override val tools: StateFlow<List<ToolStatus>> get() = toolsUpdater.tools
    /** Shared with the application so the follow check, the app updater and this engine write into one list. */
    internal val activityStore: ActivityStore = (context.applicationContext as? KeepfinApplication)?.activity ?: ActivityStore(File(context.filesDir, "activity.json"))
    override val activity: ActivityFeed get() = activityStore
    internal val galleryEngine = GalleryEngine(context, toolsUpdater::ensureInit)
    internal val inspector = Inspector(this)
    internal val follow = FollowEngine(EngineFollowHost(this))
    internal val artistFollowing = ArtistFollowing(EngineFollowingHost(this), FileFollowingStore(File(context.filesDir, "following.json")))
    override val following: StateFlow<FollowingState> get() = artistFollowing.state
    override fun followArtist(name: String) { artistFollowing.follow(name)?.let { FollowScheduler.rememberArtists(context, true); FollowScheduler.checkArtists(context, it.id) } }
    override fun unfollowArtist(id: String) { artistFollowing.unfollow(id); FollowScheduler.rememberArtists(context, artistFollowing.any()) }
    override fun checkFollowing(id: String?) = FollowScheduler.checkArtists(context, id, manual = true)
    override fun keepRelease(id: String): Int = artistFollowing.keep(id)
    override fun markReleasesSeen() = artistFollowing.markSeen()
    private val runner = DownloadRunner(this)

    private fun named(name: String) = ThreadFactory { task -> Thread(task, name).apply { isDaemon = true } }
    private val pool = Executors.newCachedThreadPool(named("keepfin-download"))
    internal val background = Executors.newSingleThreadExecutor(named("keepfin-engine"))
    private val ticker = Executors.newSingleThreadScheduledExecutor(named("keepfin-progress"))
    @Volatile private var progressDirty = false
    /** True while at least one job waits for an automatic retry; the ticker then re-checks the queue. */
    @Volatile private var retryPending = false
    /** Collection id → epoch millis until which no new job of that collection starts (set when YouTube throttles one of its jobs). */
    private val groupHolds = HashMap<String, Long>()
    /** App-wide: until this time YouTube jobs are capped at [Pacing.RATE_LIMITED_CONCURRENCY]. Kept across restarts: the limit is per network, not per run. */
    @Volatile private var youtubeCapUntil = settingsPrefs.getLong("youtubeCapUntil", 0L)

    override val outputFolder: String get() = output.absolutePath

    init {
        output.mkdirs()
        records = loadRecords()
        publish()
        thumbnails.retainJobs(records.map { it.id }.toSet())
        ticker.scheduleWithFixedDelay({
            if (progressDirty) { progressDirty = false; publish() }
            if (retryPending) pump()
        }, PROGRESS_MS, PROGRESS_MS, TimeUnit.MILLISECONDS)
        toolsUpdater.start()
        app.keepfin.merge.MergeFence.onRelease { pump() }
        folderLibrary?.start()
        app.keepfin.merge.MergeRuntime.afterOpen(context)
        background.execute { try { repairGallery() } catch (error: Exception) { Log.w(TAG, "gallery repair", error) } }
        background.execute { settleVideos() }
        background.execute { resumeVideoLooks() }
        watchNetwork()
    }

    // ---- persistence & state -----------------------------------------------------------------

    private fun loadRecords(): List<JobRecord> {
        val file = File(context.filesDir, "queue.json")
        if (!file.exists()) return emptyList()
        return try {
            val text = file.readText()
            val loaded = JobCodec.decodeFile(text)
            if (text.trimStart().startsWith("[")) {
                // Keep the Studio 0.3 history byte-for-byte before converting it.
                File(context.filesDir, "queue.v1.json").takeIf { !it.exists() }?.writeText(text)
                val sized = loaded.map { r -> if (r.status == JobStatus.COMPLETED && r.job.fileSize == null) r.copy(job = r.job.copy(fileSize = r.job.file?.let { File(it).takeIf(File::isFile)?.length() })) else r }
                store.save(sized)
                sized
            } else loaded
        } catch (error: Exception) {
            Log.e(TAG, "queue.json unreadable; keeping a copy", error)
            file.copyTo(File(context.filesDir, "queue.corrupt-${System.currentTimeMillis()}.json"), overwrite = true)
            emptyList()
        }
    }

    private fun save() = store.save(records)

    private fun publish() { jobsFlow.value = synchronized(lock) { records.map { it.job } } }

    internal fun record(id: String): JobRecord? = synchronized(lock) { records.firstOrNull { it.id == id } }

    /** Every job of the queue and history, for checks that compare one job with the others. */
    internal fun allRecords(): List<JobRecord> = synchronized(lock) { records }

    private fun find(id: String): JobRecord = records.firstOrNull { it.id == id } ?: error("Download not found.")

    /** Atomically replaces one record. Returning null from [transform] leaves it unchanged. */
    internal fun mutate(id: String, persist: Boolean = false, transform: (JobRecord) -> JobRecord?): JobRecord? {
        val next = synchronized(lock) {
            val index = records.indexOfFirst { it.id == id }
            if (index < 0) return null
            val next = transform(records[index]) ?: return null
            records = records.toMutableList().also { it[index] = next }
            if (persist) try { save() } catch (error: Exception) { Log.e(TAG, "queue save failed", error) }
            next
        }
        publish()
        return next
    }

    /** Progress ticks change memory only; the ticker publishes at most every [PROGRESS_MS]. */
    internal fun progress(id: String, snapshot: ProgressParser.Snapshot) {
        var structural = false
        synchronized(lock) {
            val index = records.indexOfFirst { it.id == id }
            if (index < 0) return
            val record = records[index]
            if (record.status !in setOf(JobStatus.PREPARING, JobStatus.DOWNLOADING, JobStatus.PROCESSING)) return
            val status = if (snapshot.processing) JobStatus.PROCESSING else JobStatus.DOWNLOADING
            val job = record.job.copy(status = status, percent = maxOf(snapshot.percent, if (status == record.status) record.job.percent else 0f).coerceAtMost(99f),
                speed = snapshot.speed, eta = snapshot.eta, downloadedBytes = snapshot.downloadedBytes ?: record.job.downloadedBytes,
                totalBytes = snapshot.totalBytes ?: record.job.totalBytes, phase = snapshot.phase)
            structural = job.status != record.job.status || job.phase != record.job.phase
            if (job != record.job) records = records.toMutableList().also { it[index] = record.copy(job = job) }
        }
        if (structural) publish() else progressDirty = true
    }

    internal fun registerProcess(id: String, process: String): Boolean = synchronized(lock) {
        val status = records.firstOrNull { it.id == id }?.status
        if (status == null || !status.active) return false
        processes[id] = process
        true
    }

    internal fun unregisterProcess(id: String) = synchronized(lock) { processes.remove(id) }

    // ---- settings --------------------------------------------------------------------------------

    private fun loadSettings(): EngineSettings {
        val legacyOrganization = context.getSharedPreferences("downloads", Context.MODE_PRIVATE).getString("organization", "platform") ?: "platform"
        val text = settingsPrefs.getString("json", null) ?: return EngineSettings(organization = legacyOrganization)
        return try {
            val json = JSONObject(text)
            sanitize(EngineSettings(
                organization = json.optString("organization", legacyOrganization), concurrency = Pacing.migratedConcurrency(json.optInt("concurrency", 3), json.has(Pacing.CONCURRENCY_MARKER)),
                fragments = json.optInt("fragments", 8), defaultPreset = DownloadPreset.of(json.optString("defaultPreset")),
                options = JobCodec.decodeOptions(json.optJSONObject("options")), channel = json.optString("channel", "stable"),
                autoUpdate = json.optBoolean("autoUpdate", true), wifiOnly = json.optBoolean("wifiOnly", false), turbo = json.optBoolean("turbo", false),
                ytMusicAudio = json.optBoolean("ytMusicAudio", true), lyrics = LyricsLookup.enabledIn(json),
                keepMusicVideos = json.optBoolean("keepMusicVideos", false), newReleasesOnHome = json.optBoolean("newReleasesOnHome", true), autoSignIn = json.optBoolean("autoSignIn", true),
                library = SpaceLogic.decode(json.optJSONObject("library")),
            )).also { loaded ->
                // The one-time default change is written back with its marker, so a later explicit choice (even 2) sticks.
                if (!json.has(Pacing.CONCURRENCY_MARKER)) settingsPrefs.edit().putString("json", json.put("concurrency", loaded.concurrency).put(Pacing.CONCURRENCY_MARKER, true).toString()).apply()
            }
        } catch (_: Exception) { EngineSettings(organization = legacyOrganization) }
    }

    private fun sanitize(value: EngineSettings): EngineSettings {
        ArgsPolicy.validate(value.options)
        return value.copy(
            organization = if (value.organization == "flat") "flat" else "platform", concurrency = value.concurrency.coerceIn(1, 4),
            fragments = value.fragments.coerceIn(1, 16), channel = if (value.channel == "nightly") "nightly" else "stable",
            // spaces on the Keepfin folder are relative folders that the MediaStore rules would refuse; the folder library validates them itself
            library = if (folderLibrary != null) value.library.copy(crossfade = value.library.crossfade.coerceIn(0, 12)) else SpaceLogic.sanitize(value.library),
        )
    }

    override fun updateSettings(transform: (EngineSettings) -> EngineSettings) {
        val next = synchronized(lock) {
            val next = sanitize(transform(settingsFlow.value))
            settingsPrefs.edit().putString("json", JSONObject().put("organization", next.organization).put("concurrency", next.concurrency).put(Pacing.CONCURRENCY_MARKER, true)
                .put("fragments", next.fragments).put("defaultPreset", next.defaultPreset.wire).put("options", JobCodec.encodeOptions(next.options))
                .put("channel", next.channel).put("autoUpdate", next.autoUpdate).put("wifiOnly", next.wifiOnly).put("turbo", next.turbo).put("ytMusicAudio", next.ytMusicAudio).put("lyrics", next.lyrics).put("keepMusicVideos", next.keepMusicVideos).put("newReleasesOnHome", next.newReleasesOnHome).put("autoSignIn", next.autoSignIn)
                .put("library", SpaceLogic.encode(next.library)).toString()).apply()
            context.getSharedPreferences("downloads", Context.MODE_PRIVATE).edit().putString("organization", next.organization).apply()
            val previous = settingsFlow.value
            settingsFlow.value = next
            Triple(next, previous.library != next.library, previous.library.normalize != next.library.normalize)
        }
        if (next.first.wifiOnly) watchNetwork()
        if (next.second || next.third) libraryApi.rescan()
        pump()
    }

    // ---- network (Wi-Fi only) ---------------------------------------------------------------------

    private var networkWatched = false

    private fun networkPermission() = context.checkSelfPermission(android.Manifest.permission.ACCESS_NETWORK_STATE) == PackageManager.PERMISSION_GRANTED

    private fun watchNetwork() {
        if (networkWatched || !networkPermission()) return
        try {
            context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = pump()
                override fun onCapabilitiesChanged(network: Network, capabilities: android.net.NetworkCapabilities) = pump()
            })
            networkWatched = true
        } catch (error: Exception) { Log.w(TAG, "network callback unavailable", error) }
    }

    /** null when allowed, else the reason the queue waits. */
    private fun networkBlock(): String? {
        if (!settingsFlow.value.wifiOnly) return null
        if (!networkPermission()) return "Wi-Fi only needs network-status access, which this build lacks. Turn off Wi-Fi only to download."
        val metered = try { context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true } catch (_: Exception) { true }
        return if (metered) "Waiting for Wi-Fi" else null
    }

    // ---- scheduling ------------------------------------------------------------------------------

    /** Starts queued jobs (oldest first) until [EngineSettings.concurrency] transfers run. */
    internal fun pump() {
        // combining libraries: downloads already running finish, new ones wait (the fence calls pump again when it lifts)
        if (app.keepfin.merge.MergeFence.downloadsWait) return
        val block = networkBlock()
        val start = mutableListOf<String>()
        var changed = false
        synchronized(lock) {
            val limit = settingsFlow.value.concurrency
            val now = System.currentTimeMillis()
            val queued = records.filter { it.status == JobStatus.QUEUED && it.id !in running }.sortedBy { it.job.createdAt }
            groupHolds.entries.removeAll { it.value <= now }
            retryPending = queued.any { it.job.retryAt != null } || queued.any { it.job.group?.id in groupHolds }
            val runningItems = records.filter { it.id in running }.map(::pacingItem)
            val chosen = ArrayList<Pacing.Item>()
            for (record in queued) {
                // An automatic retry waits for its backoff (and for a pending engine refresh) without holding a worker slot.
                val retryAt = record.job.retryAt
                if (retryAt != null && (retryAt > now || (record.job.phase == ENGINE_PHASE && toolsUpdater.busy()))) continue
                if (block != null) {
                    if (record.job.phase != block) { replace(record.copy(job = record.job.copy(phase = block))); changed = true }
                    continue
                }
                val item = pacingItem(record)
                if (!Pacing.canStart(item, runningItems + chosen, limit, groupHolds, now, youtubeCapUntil)) { if (runningItems.size + chosen.size >= limit) break else continue }
                chosen.add(item)
                running.add(record.id); start.add(record.id)
            }
        }
        if (changed) publish()
        start.forEach { id -> pool.execute { runJob(id) } }
    }

    private fun pacingItem(record: JobRecord) = Pacing.Item(record.id, Pacing.isYouTube(record.job.url, record.job.match != null), record.job.group?.id)

    /**
     * True when no worker can still touch the record. A COMPLETED/FAILED record is final the moment it is published
     * (the worker only unwinds afterwards); a CANCELLED/PAUSED one must wait until its process has really stopped.
     */
    private fun settled(record: JobRecord) = record.id !in running || record.status == JobStatus.COMPLETED || record.status == JobStatus.FAILED

    private fun replace(record: JobRecord) { records = records.map { if (it.id == record.id) record else it } }

    private fun runJob(id: String) {
        try {
            val started = mutate(id) {
                if (it.status != JobStatus.QUEUED) null
                else it.copy(job = it.job.copy(status = JobStatus.PREPARING, phase = if (it.job.retryCount > 0) "Retry ${it.job.retryCount} of ${RetryPolicy.MAX}" else null,
                    error = null, errorDetail = null, speed = null, eta = null, retryAt = null))
            }
            if (started != null) runner.run(started)
        } catch (error: Throwable) {
            Log.e(TAG, "job $id crashed", error)
            failOrRetry(id, error.message ?: "The download engine stopped unexpectedly.", crashed = true)
        } finally {
            synchronized(lock) { running.remove(id); processes.remove(id) }
            pump()
        }
    }

    private fun ensureService() {
        try { DownloadService.beginWork(context).close() }
        catch (error: Exception) { Log.w(TAG, "foreground service unavailable; downloads continue while the app is open", error) }
    }

    // ---- terminal transitions --------------------------------------------------------------------

    internal fun fail(id: String, message: String, raw: String? = null) {
        val job = mutate(id, persist = true) {
            if (!it.status.active) null else it.copy(job = it.job.copy(status = JobStatus.FAILED, error = message,
                errorDetail = raw?.let { text -> ErrorMessages.detail(text) }?.takeIf(String::isNotBlank), speed = null, eta = null, phase = null, retryAt = null))
        }?.job ?: return
        notifyFinished(job)
        background.execute { settleVideos() }
    }

    /**
     * Final-failure gate: retryable failures are re-queued with a backoff (no notification, the service stays alive because the
     * job is QUEUED); permanent ones, or an exhausted budget, fail for real through [fail]. Paused/cancelled jobs are never touched.
     */
    internal fun failOrRetry(id: String, raw: String?, crashed: Boolean = false) {
        if (!crashed && ErrorMessages.throttled(raw)) noteRateLimit()
        val used = record(id)?.job?.retryCount ?: RetryPolicy.MAX
        val decision = RetryPolicy.next(used, raw, crashed)
        if (decision == null) { fail(id, ErrorMessages.friendly(raw, exhausted = true), raw); return }
        val refresh = decision.refreshEngine && toolsUpdater.updateIfStale(RetryPolicy.ENGINE_STALE_MS)
        val scheduled = mutate(id, persist = true) {
            if (it.status != JobStatus.PREPARING && it.status != JobStatus.DOWNLOADING && it.status != JobStatus.PROCESSING) null
            else it.copy(job = it.job.copy(status = JobStatus.QUEUED, retryCount = decision.retry, retryAt = System.currentTimeMillis() + decision.delayMs,
                phase = if (refresh) ENGINE_PHASE else if (decision.rateLimited) RATE_LIMIT_PHASE else "Retrying", error = null, errorDetail = null, speed = null, eta = null))
        }
        if (scheduled == null) { fail(id, ErrorMessages.friendly(raw, exhausted = true), raw); return }
        if (decision.rateLimited) scheduled.job.group?.id?.let { group ->
            synchronized(lock) { groupHolds[group] = maxOf(groupHolds[group] ?: 0L, System.currentTimeMillis() + decision.delayMs) }
        }
        retryPending = true
        Log.i(TAG, "job $id automatic retry ${decision.retry}/${RetryPolicy.MAX} in ${decision.delayMs} ms (engine refresh=$refresh)")
    }

    /** A rate limit or bot check: cap YouTube transfers for [Pacing.CAP_MS], app-wide. Running jobs finish; new starts obey the cap. */
    private fun noteRateLimit() {
        val end = synchronized(lock) { Pacing.capEnd(System.currentTimeMillis(), youtubeCapUntil).also { youtubeCapUntil = it } }
        settingsPrefs.edit().putLong("youtubeCapUntil", end).apply()
    }

    internal fun complete(id: String) {
        val record = mutate(id, persist = true) {
            if (!it.status.active) null else it.copy(job = it.job.copy(status = JobStatus.COMPLETED, percent = 100f, speed = null, eta = null, phase = it.job.phase?.takeIf { p -> p == MusicVideoLink.WAITING },
                completedAt = System.currentTimeMillis(), downloadedBytes = it.job.fileSize ?: it.job.downloadedBytes, retryAt = null))
        } ?: return
        notifyFinished(record.job)
    }

    /** After a song is saved (or was already there): videos that waited for it are kept beside it, and a song that asked for its video looks for it. */
    internal fun settleLater(record: JobRecord) {
        background.execute { settleVideos() }
        videoLooks.completed(record)
    }

    /**
     * A music video still on its way for the library song [itemId]: queued, running, paused or finished and waiting, whether it was asked for on the
     * song or is linked to the job that made it.
     */
    private fun videoComingFor(itemId: String): Boolean {
        val coming = synchronized(lock) { records.filter { it.status.active || it.status == JobStatus.PAUSED || MusicVideoLink.waiting(it) } }
        val folder = folderLibrary
        return coming.any { r -> r.attachTo == itemId || (r.attachToJob != null && folder != null && songItem(folder, r.attachToJob) == itemId) }
    }

    /** Looks that never ran (the app closed with some still waiting) run once the library is open. */
    private fun resumeVideoLooks() {
        folderLibrary?.let { f -> if (runCatching { f.awaitReady(60, collections = false) }.isFailure) return }
        synchronized(lock) { records.filter { it.findVideo && it.status == JobStatus.COMPLETED } }.forEach(videoLooks::completed)
    }

    /** Looks for the official video of each saved song that asked for one, one look at a time (see [SongVideoLooks]). */
    private val videoLooks = SongVideoLooks(
        executor = Executors.newSingleThreadExecutor(named("keepfin-video-look")),
        song = { r ->
            val folder = folderLibrary
            val itemId = folder?.let { songItem(it, r.id) }
            val item = itemId?.let { folder.item(it) }
            val artists = listOfNotNull((item?.artist ?: item?.albumArtist ?: r.source?.artist)?.takeIf { it.isNotBlank() })
            val title = (item?.title ?: r.source?.title)?.takeIf { it.isNotBlank() }
            if (itemId == null || artists.isEmpty() || title == null) null else SongVideoLooks.Song(itemId, artists, title)
        },
        hasVideo = { itemId -> folderLibrary?.musicVideoUri(itemId) != null },
        linked = { songJob -> synchronized(lock) { records.any { it.attachToJob == songJob } } },
        find = ::findMusicVideo,
        // checked again just before: "Get the music video" may have queued one for this song meanwhile
        queue = { song, saved, video -> if (!videoComingFor(saved.itemId)) add(listOf(videoJob(video, song.job.space, songJob = song.id))) },
        log = { message, error -> if (error == null) Log.i(TAG, message) else Log.w(TAG, message, error) },
        done = { id -> mutate(id, persist = true) { if (it.findVideo) it.copy(findVideo = false) else null } },
        hold = { DownloadService.beginWork(context) },
    )

    /** The library item a finished song job became (or already was); null when it has none. */
    internal fun songItem(folder: app.keepfin.library.repo.LibraryRepository, songJob: String): String? = folder.itemOfJob(songJob) ?: record(songJob)?.job?.existing?.itemId

    /**
     * Music videos that finished before their song: when the song is done (saved, failed or cancelled, also after a restart) each is kept beside it,
     * or as an ordinary download when the song did not make it. A video still waiting for a song that is downloading or paused stays as it is.
     */
    internal fun settleVideos() {
        // which song a finished download became is read when the library opens: settling before that would take the song for a missing one
        val folder = folderLibrary
        if (folder != null && runCatching { folder.awaitReady(60, collections = false) }.isFailure) return
        val ready = synchronized(lock) { records.filter { r -> MusicVideoLink.waiting(r) && r.attachToJob?.let { id -> records.firstOrNull { it.id == id }?.let { s -> s.status.active || s.status == JobStatus.PAUSED } } != true } }
        ready.forEach { r ->
            // settled once: a video already kept (or still waiting) is not published again
            if (record(r.id)?.let(MusicVideoLink::waiting) != true) return@forEach
            try { EngineAfterSteps(this, r.id).saveAgain() } catch (error: Exception) { Log.w(TAG, "music video not settled", error) }
        }
    }

    internal fun notifications() = (context.applicationContext as? KeepfinApplication)?.notifications

    internal fun notifyFinished(job: Job) {
        try {
            notifications()?.finished(job, jobs = { jobsFlow.value }, isCurrent = {
                synchronized(lock) { records.any { it.id == job.id && it.job.attempt == job.attempt && it.status == job.status } }
            })
        } catch (_: Exception) { /* OS notification failures never change a persisted outcome. */ }
        // Activity is independent of notifications: turning them off never hides it. Recorded only here, after automatic retries are spent.
        try { activityStore.onJobTerminal(job, jobsFlow.value, settingsFlow.value.library) } catch (error: Exception) { Log.w(TAG, "activity not recorded", error) }
    }

    // ---- inspection & collections ---------------------------------------------------------------

    override fun inspect(url: String, session: String): InspectResult = inspector.inspect(url, session)
    fun inspect(url: String, session: String, timeoutMs: Long): InspectResult = inspector.inspect(url, session, timeoutMs)
    override fun cancelInspection(session: String) = metadata.cancel(session)
    override fun discover(url: String, session: String, start: Int, end: Int, onEntry: (PlaylistEntry) -> Unit): PlaylistDiscovery =
        inspector.discover(url, session, start, end, onEntry)
    override fun cancelDiscovery(session: String) = inspector.cancelDiscovery(session)

    // ---- queue -----------------------------------------------------------------------------------

    private fun newJob(url: String, title: String, thumbnail: String?, uploader: String?, duration: Double?, provider: String?, format: FormatRequest,
                       media: MediaInfo?, match: MusicMatch?) = Job(
        id = UUID.randomUUID().toString(), url = url, title = title.take(1000).ifBlank { "Untitled" }, thumbnail = thumbnail, uploader = uploader,
        duration = duration, provider = provider, label = Labels.label(format, media), kind = ArgsPolicy.kind(format), status = JobStatus.QUEUED,
        percent = 0f, speed = null, eta = null, downloadedBytes = null, totalBytes = null, expectedSize = Labels.expectedSize(format, media),
        fileSize = null, phase = null, error = null, file = null, publicUri = null, publicPath = null, saveError = null,
        createdAt = System.currentTimeMillis(), completedAt = null, attempt = 1, match = match,
    )

    private fun add(added: List<JobRecord>, afterCommit: () -> Unit = {}) {
        if (added.isEmpty()) return
        ensureService()
        synchronized(lock) {
            require(records.count { it.status.active } + added.size <= MAX_ACTIVE) { "Queue is full. Wait for some downloads to finish." }
            val previous = records
            // Newest first; creation order is preserved inside one batch.
            records = added.reversed() + previous
            try { save() } catch (error: Exception) { records = previous; throw error }
            afterCommit()
        }
        added.forEach { thumbnails.promote(it.job.thumbnail.orEmpty(), it.id) }
        publish()
        pump()
    }

    /** The space a new job saves to: the requested one if it exists, else the route for the link. Null while the library is off. */
    private fun spaceFor(requested: String?, url: String, uploader: String?, groupUrl: String? = null): String? {
        val library = settingsFlow.value.library
        if (!library.enabled) return null
        return requested?.takeIf { id -> library.folderSpaces.any { it.id == id } } ?: SpaceLogic.route(library, url, uploader, groupUrl)
    }

    override fun enqueue(media: MediaInfo, format: FormatRequest, options: DownloadOptions, space: String?) = enqueue(media, format, options, space, null, null)

    override fun enqueue(media: MediaInfo, format: FormatRequest, options: DownloadOptions, space: String?, musicVideo: MusicVideoMatch?) = enqueue(media, format, options, space, musicVideo, null)

    override fun enqueue(media: MediaInfo, format: FormatRequest, options: DownloadOptions, space: String?, musicVideo: MusicVideoMatch?, playlist: String?) {
        val url = Providers.validateUrl(media.url)
        ArgsPolicy.validate(format); ArgsPolicy.validate(options)
        require(musicVideo == null || Regex("[A-Za-z0-9_-]{11}").matches(musicVideo.videoId)) { "This isn't a music video Keepfin can download." }
        val match = media.match
        // Another upload of the same song keeps the job on the song the user asked for; the runner downloads match.matchedUrl (JobRecord.downloadUrl).
        val downloadUrl = if (match?.source == Restriction.SOURCE) url else match?.matchedUrl?.let(Providers::validateUrl) ?: url
        require(Providers.musicSource(downloadUrl) == null || match == null) { "Inspect this music link again." }
        val source = match?.let { SourceTags(media.music?.track ?: media.title, media.music?.artist ?: media.uploader, media.music?.album, null, media.music?.releaseYear) }
        // a video of a song you have joins that song (any space) instead of becoming a second item
        val join = songJoin(media, format, options)
        val job = newJob(downloadUrl, media.title, media.thumbnail, media.uploader, media.duration, media.provider, join?.format ?: format, media, match)
            .copy(space = join?.space ?: spaceFor(space, media.url, media.uploader), signedIn = media.signedIn)
        add(listOf(JobRecord(job, join?.format ?: format, join?.options ?: options, source = source, attachTo = join?.itemId, joinPlaylist = playlist.takeIf { settingsFlow.value.library.enabled })))
        // the music video is its own job, linked to the song's job (its library item does not exist yet), queued after it: a full queue refuses only the video
        musicVideo?.let { runCatching { add(listOf(videoJob(it, job.space, job.id))) }.onFailure { e -> Log.w(TAG, "music video not queued", e) } }
    }

    /** The song a video download of [media] joins; null for any other download. Throws, in words, when that song has its video. */
    private fun songJoin(media: MediaInfo, format: FormatRequest, options: DownloadOptions): SongJoin.Joined? {
        val folder = folderLibrary?.takeIf { settingsFlow.value.library.enabled && format is FormatRequest.Video } ?: return null
        val song = folder.songOf(media) ?: return null
        return SongJoin.of(song, videoComingFor(song.itemId), format, options)
    }

    /** A music video download: up to 1080p, mp4. [song] is the library item it is kept beside, or [songJob] the job that is still making it. */
    private fun videoJob(video: MusicVideoMatch, space: String?, songJob: String? = null, song: String? = null): JobRecord {
        val format = FormatRequest.Video(maxHeight = 1080, container = "mp4")
        val options = DownloadOptions(embedThumbnail = false, embedChapters = false)
        val job = newJob(Providers.validateUrl(video.url), video.title, video.thumbnail, video.channel, video.durationSec, "YouTube Music", format, null, null).copy(space = space)
        return JobRecord(job, format, options, attachTo = song, attachToJob = songJob)
    }

    override fun enqueueEntries(session: String, entries: List<PlaylistEntry>, preset: DownloadPreset, options: DownloadOptions, group: JobGroup?, space: String?, sync: SyncRequest?, findVideo: Boolean, playlist: String?): Int =
        enqueueCollectionEntries(session, entries, preset, null, options, group, space, null, sync, findVideo, playlist).size

    /**
     * Queues discovered entries. Into a folder space this also creates (or reuses, by canonical source link) the library collection: its `seen` list
     * receives every id the session discovered, including the deselected ones, and the jobs carry the collection id and the source position.
     * [formatOverride] is the collection's remembered profile for follow checks; [collectionId] pins the collection a follow check belongs to.
     * Returns the new job ids.
     */
    internal fun enqueueCollectionEntries(session: String, entries: List<PlaylistEntry>, preset: DownloadPreset, formatOverride: FormatRequest?, options: DownloadOptions,
                                          group: JobGroup?, space: String?, collectionId: String?, sync: SyncRequest? = null, findVideo: Boolean = false, playlist: String? = null): List<String> {
        ArgsPolicy.validate(options)
        formatOverride?.let { ArgsPolicy.validate(it) }
        val authorized = inspector.authorized(session, entries.map { runCatching { Providers.validateUrl(it.url) }.getOrDefault(it.url) })
        check(authorized.isNotEmpty()) { "Playlist review expired or these items are already queued. Inspect again." }
        val sorted = authorized.sortedBy { it.entry.position }
        val first = sorted.first().entry
        val librarySettings = settingsFlow.value.library
        val targetSpace = spaceFor(space, first.url, first.uploader ?: first.artist, group?.url)
        val info = inspector.sessionInfo(session)
        val plan = if (librarySettings.enabled && info != null && targetSpace != null && librarySettings.folderSpaces.any { it.id == targetSpace }) collectionIdFor(info.url, collectionId ?: group?.id) else null
        val records = sorted.mapIndexed { index, (entry, source) ->
            val music = source != null
            val format = formatOverride ?: ArgsPolicy.preset(preset).let { if (music && it is FormatRequest.Video) FormatRequest.Audio("mp3", "320") else it }
            val job = newJob(entry.url, entry.title ?: "Item ${entry.position}", entry.thumbnail, entry.uploader ?: entry.artist, entry.duration,
                if (music) "YouTube Music" else Providers.name(entry.url), format, null,
                if (music) MusicMatch(Providers.musicSource(entry.url) ?: "spotify", entry.url, null, null, null) else null)
            JobRecord(job.copy(group = group, createdAt = job.createdAt + index, space = spaceFor(space, entry.url, entry.uploader ?: entry.artist, group?.url),
                collectionId = plan, collectionIndex = entry.position.takeIf { plan != null && it > 0 }), format, options, source = source,
                // only a song kept as audio in the library has a video to keep beside it
                findVideo = findVideo && format is FormatRequest.Audio && librarySettings.enabled, joinPlaylist = playlist.takeIf { librarySettings.enabled })
        }
        add(records) {
            if (plan != null && info != null) {
                val title = group?.title ?: info.title ?: "Collection"
                val kind = CollectionLogic.kindOf(info.url, group?.kind)
                val infinite = CollectionLogic.infinite(info.url, group?.kind)
                folderLibrary?.ensureCollection(info.url, title, kind, targetSpace!!, info.keys, infinite, plan, sync)
            }
            inspector.consume(session, authorized.map { it.entry.url })
        }
        return records.map { it.id }
    }

    /** The last job's format request and options for a collection (its remembered profile), or null when it has no jobs yet. */
    internal fun lastProfile(collectionId: String): Pair<FormatRequest, DownloadOptions>? = synchronized(lock) {
        records.filter { it.job.collectionId == collectionId || it.job.group?.id == collectionId }.maxByOrNull { it.job.createdAt }?.let { it.format to it.options }
    }

    override fun cancel(id: String) = cancel(id, null)

    fun cancel(id: String, reason: String?) {
        var wasRunning = false
        val record = synchronized(lock) {
            val record = find(id)
            if (!record.status.active && record.status != JobStatus.PAUSED) return
            val next = record.copy(job = record.job.copy(status = JobStatus.CANCELLED, error = reason, speed = null, eta = null, phase = null, retryAt = null))
            replace(next)
            try { save() } catch (error: Exception) { Log.e(TAG, "queue save failed", error) }
            processes[id]?.let { YoutubeDL.getInstance().destroyProcessById(it) }
            wasRunning = id in running
            next
        }
        publish()
        if (!wasRunning) background.execute { cleanupPartials(record) }
        notifyFinished(record.job)
        background.execute { settleVideos() }
    }

    override fun downloadAnyway(id: String) {
        ensureService()
        synchronized(lock) {
            val record = find(id)
            require(record.status == JobStatus.COMPLETED && record.job.existing != null) { "Only recordings that were already in the library can be downloaded again." }
            replace(record.copy(job = record.job.copy(status = JobStatus.QUEUED, percent = 0f, attempt = record.job.attempt + 1, existing = null, force = true,
                error = null, errorDetail = null, completedAt = null, phase = null, speed = null, eta = null, retryCount = 0, retryAt = null)))
            save()
        }
        notifications()?.dismiss(id)
        publish()
        pump()
    }

    /** Job start: the match when the space skips existing recordings and one is already there. */
    internal fun existingMatch(record: JobRecord): ExistingMatch? = if (record.attachTo != null || record.attachToJob != null) null else try { folderLibrary?.findExisting(record.job, record.recording()) } catch (error: Exception) { Log.w(TAG, "existing check failed", error); null }


    /** History paths follow a moved file; a deleted file (publicPath null) drops its reference but keeps the history entry. */
    internal fun repointFile(uri: String, publicPath: String?) {
        var changed = false
        synchronized(lock) {
            records = records.map { r ->
                if (r.job.publicUri == uri) {
                    changed = true
                    if (publicPath != null) r.copy(job = r.job.copy(publicPath = publicPath))
                    else r.copy(job = r.job.copy(publicUri = null, publicPath = null, filesRemoved = true), publicFiles = r.publicFiles.filter { it != uri })
                } else if (publicPath == null && uri in r.publicFiles) { changed = true; r.copy(publicFiles = r.publicFiles.filter { it != uri }) } else r
            }
            if (changed) try { save() } catch (error: Exception) { Log.e(TAG, "queue save failed", error) }
        }
        if (changed) publish()
    }

    /** The setup moved a file into the Keepfin folder: the history rows that pointed at it follow it (a moved file is not a removed one). */
    override fun relocateFile(oldUri: String, newUri: String, path: String) {
        var changed = false
        synchronized(lock) {
            records = records.map { r ->
                if (r.job.publicUri == oldUri || oldUri in r.publicFiles) {
                    changed = true
                    r.copy(
                        job = if (r.job.publicUri == oldUri) r.job.copy(publicUri = newUri, publicPath = path, filesRemoved = false) else r.job,
                        publicFiles = r.publicFiles.map { if (it == oldUri) newUri else it },
                    )
                } else r
            }
            if (changed) try { save() } catch (error: Exception) { Log.e(TAG, "queue save failed", error) }
        }
        if (changed) publish()
    }

    /** The Keepfin folder moved: history rows that name a file in the old place follow it ([newUri] answers null for any other address). Safe to repeat. */
    internal fun relocateFolderFiles(newUri: (String) -> String?) {
        var changed = false
        synchronized(lock) {
            records = records.map { r ->
                val main = r.job.publicUri?.let(newUri)
                val files = r.publicFiles.map { newUri(it) ?: it }
                if (main == null && files == r.publicFiles) r else {
                    changed = true
                    r.copy(job = if (main != null) r.job.copy(publicUri = main) else r.job, publicFiles = files)
                }
            }
            if (changed) try { save() } catch (error: Exception) { Log.e(TAG, "queue save failed", error) }
        }
        if (changed) publish()
    }

    /** Repair: a row the move left marked "file removed" follows its library item (matched by the job the item came from). */
    override fun relocateJob(jobId: String, newUri: String, path: String): Boolean {
        var changed = false
        synchronized(lock) {
            records = records.map { r ->
                if (r.job.id == jobId && r.status == JobStatus.COMPLETED && (r.job.filesRemoved || r.job.publicUri == null)) {
                    changed = true
                    r.copy(job = r.job.copy(publicUri = newUri, publicPath = path, filesRemoved = false), publicFiles = listOf(newUri))
                } else r
            }
            if (changed) try { save() } catch (error: Exception) { Log.e(TAG, "queue save failed", error) }
        }
        if (changed) publish()
        return changed
    }

    override fun pause(id: String) {
        synchronized(lock) {
            val record = find(id)
            if (record.status == JobStatus.PAUSED) return
            require(record.status in setOf(JobStatus.QUEUED, JobStatus.PREPARING, JobStatus.DOWNLOADING)) {
                if (record.status == JobStatus.PROCESSING) "Almost done: this download is being processed and can't be paused." else "Only active downloads can be paused."
            }
            replace(record.copy(job = record.job.copy(status = JobStatus.PAUSED, speed = null, eta = null, phase = null, retryAt = null)))
            try { save() } catch (error: Exception) { Log.e(TAG, "queue save failed", error) }
            processes[id]?.let { YoutubeDL.getInstance().destroyProcessById(it) }
        }
        publish()
        pump()
    }

    override fun resume(id: String) {
        ensureService()
        synchronized(lock) {
            val record = find(id)
            require(record.status == JobStatus.PAUSED) { "Only paused downloads can be resumed." }
            replace(record.copy(job = record.job.copy(status = JobStatus.QUEUED, phase = null, error = null, errorDetail = null)))
            save()
        }
        publish()
        pump()
    }

    override fun retry(id: String) {
        ensureService()
        synchronized(lock) {
            val record = find(id)
            require(settled(record)) { "Cancellation is finishing. Retry in a moment." }
            require(record.status in setOf(JobStatus.FAILED, JobStatus.CANCELLED)) { "Only failed or cancelled downloads can be retried." }
            // A failed attempt keeps its partial files and resumes them; a cancelled one starts over under a new name.
            val keepPartials = record.status == JobStatus.FAILED
            replace(record.copy(
                job = record.job.copy(status = JobStatus.QUEUED, percent = 0f, attempt = record.job.attempt + 1, error = null, errorDetail = null, file = null,
                    publicUri = null, publicPath = null, filesRemoved = false, saveError = null, fileSize = null, completedAt = null, phase = null, speed = null, eta = null,
                    downloadedBytes = null, totalBytes = null, retryCount = 0, retryAt = null),
                outputTemplate = if (keepPartials) record.outputTemplate else null, publicFiles = emptyList(), photoSaves = emptyMap(), galleryRepairError = null,
            ))
            save()
        }
        notifications()?.dismiss(id)
        publish()
        pump()
    }

    override fun remove(id: String) {
        val removed = synchronized(lock) {
            val record = find(id)
            require(record.status in setOf(JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED, JobStatus.PAUSED) && settled(record)) {
                "Finish or cancel this download before removing it from history."
            }
            // its record is the only link to its song (the file is still private): it goes once the song is done
            require(!MusicVideoLink.waiting(record)) { "This music video is waiting for its song. Remove it once the song is done." }
            val previous = records
            records = previous.filter { it.id != id }
            try { save() } catch (error: Exception) { records = previous; throw error }
            record
        }
        publish()
        notifications()?.dismiss(id)
        // The artwork (and a paused download's partial files) stay while Undo can still bring the record back; see [purgeRemoved].
        removedHistory.hold(id, removed, System.currentTimeMillis()).forEach { cleanRemoved(it) }
        runCatching { ticker.schedule({ purgeRemoved() }, RemovedHistory.KEEP_MS + 5_000, TimeUnit.MILLISECONDS) }
    }

    /** Records removed from the history, held for Undo. */
    private val removedHistory = RemovedHistory<JobRecord>()

    /** What a removed record leaves behind, unless it came back: its saved artwork and, for a paused download, the partial files. */
    private fun cleanRemoved(record: JobRecord) {
        if (synchronized(lock) { records.any { it.id == record.id } }) return
        thumbnails.removeJob(record.id)
        if (record.status == JobStatus.PAUSED) background.execute { cleanupPartials(record) }
    }

    /** Lets go of removed records whose Undo has run out ([now]: tests pass a later time). */
    internal fun purgeRemoved(now: Long = System.currentTimeMillis()) {
        try { removedHistory.expire(now).forEach { cleanRemoved(it) } } catch (error: Exception) { Log.w(TAG, "purge of removed history", error) }
    }

    override fun restoreRemoved(ids: List<String>): List<String> {
        val back = removedHistory.take(ids, System.currentTimeMillis())
        if (back.isEmpty()) return emptyList()
        val restored = synchronized(lock) {
            val previous = records
            val present = previous.mapTo(HashSet()) { it.id }
            val fresh = back.filter { it.id !in present }
            // newest first, like the list the engine keeps: each record goes in front of the first one that is not newer
            var next = previous
            for (record in fresh.sortedByDescending { it.job.createdAt }) {
                val at = next.indexOfFirst { it.job.createdAt <= record.job.createdAt }.let { if (it < 0) next.size else it }
                next = next.subList(0, at) + record + next.subList(at, next.size)
            }
            records = next
            try { save() } catch (error: Exception) { records = previous; back.forEach { removedHistory.hold(it.id, it, System.currentTimeMillis()) }; throw error }
            fresh.map { it.id }
        }
        publish()
        return restored
    }

    override fun clearHistory(): List<String> {
        val removed = synchronized(lock) {
            // a music video waiting for its song stays: its record is the only link to it
            val (gone, keep) = records.partition { it.status in setOf(JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED) && settled(it) && !MusicVideoLink.waiting(it) }
            records = keep
            save()
            gone
        }
        publish()
        removed.forEach { notifications()?.dismiss(it.id) }
        // as for one removal: the records and their artwork are held while Undo can still bring them back (see [purgeRemoved])
        val now = System.currentTimeMillis()
        removed.forEach { record -> removedHistory.hold(record.id, record, now).forEach { cleanRemoved(it) } }
        if (removed.isNotEmpty()) runCatching { ticker.schedule({ purgeRemoved() }, RemovedHistory.KEEP_MS + 5_000, TimeUnit.MILLISECONDS) }
        return removed.map { it.id }
    }

    override fun deleteFile(id: String) {
        val record = synchronized(lock) {
            find(id).also { require(!it.status.active && settled(it)) { "Cancel this download before deleting its file." } }
        }
        val uris = (record.publicFiles + listOfNotNull(record.job.publicUri)).distinct()
        val failures = mutableListOf<String>()
        uris.forEach { value ->
            try {
                val uri = Uri.parse(value)
                when (uri.scheme) {
                    "content" -> if (android.provider.DocumentsContract.isDocumentUri(context, uri)) android.provider.DocumentsContract.deleteDocument(context.contentResolver, uri) else context.contentResolver.delete(uri, null, null)
                    "file" -> uri.path?.let { File(it).delete() }
                }
            } catch (error: Exception) { failures.add(error.message ?: value) }
        }
        check(failures.isEmpty()) {
            "Android didn't allow deleting the saved copy${record.job.publicPath?.let { " in $it" } ?: ""}. Delete it from your gallery or Files app, then try again."
        }
        record.job.file?.let { path -> File(path).takeIf { inOutput(it) }?.let { file -> stemFiles(file.parentFile, record.outputTemplate, file).forEach { it.delete() }; file.delete() } }
        cleanupPartials(record)
        synchronized(lock) {
            records = records.filter { it.id != id }
            save()
        }
        publish()
        notifications()?.dismiss(id)
        thumbnails.removeJob(id)
        libraryApi.rescan()
    }

    internal fun rememberThumbnail(id: String, bitmap: Bitmap) = synchronized(lock) {
        // A late network/frame callback must not recreate artwork after its history entry was removed.
        if (records.any { it.id == id }) thumbnails.saveJob(id, bitmap)
    }

    // ---- files -----------------------------------------------------------------------------------

    internal fun inOutput(file: File) = file.canonicalPath.startsWith(output.canonicalPath + File.separator)

    override fun file(id: String): File = synchronized(lock) {
        val record = find(id)
        require(record.status == JobStatus.COMPLETED) { "This download isn't finished." }
        val file = File(record.job.file ?: error("This download has no saved file."))
        require(inOutput(file) && file.isFile) { "The downloaded file is no longer in the app. Open the saved copy instead." }
        file
    }

    internal fun stem(template: String?, file: File?): String? =
        template?.let { File(it).name.substringBefore(".%(ext)s") } ?: file?.nameWithoutExtension

    internal fun stemFiles(folder: File?, template: String?, file: File?): List<File> {
        val stem = stem(template, file) ?: return emptyList()
        return folder?.listFiles()?.filter { it.isFile && it.name.startsWith("$stem.") } ?: emptyList()
    }

    /**
     * Deletes only the cached cover image(s) of a job. The provider occasionally serves a truncated image; yt-dlp reuses an
     * existing file as "already present", so a cover retry must start from a fresh download.
     */
    internal fun discardCoverImages(record: JobRecord) {
        val template = record.outputTemplate ?: return
        val folder = File(template).parentFile ?: return
        if (!inOutput(File(folder, "x"))) return
        stemFiles(folder, template, null).filter { it.extension.lowercase() in setOf("webp", "jpg", "jpeg", "png") }.forEach { it.delete() }
    }

    /** Removes partial/intermediate files of an unfinished job; never touches completed media of other jobs. */
    internal fun cleanupPartials(record: JobRecord) {
        val template = record.outputTemplate ?: return
        val folder = File(template).parentFile ?: return
        if (!inOutput(File(folder, "x"))) return
        val finished = record.job.file?.takeIf { record.status == JobStatus.COMPLETED }
        stemFiles(folder, template, null).filter { it.absolutePath != finished }.forEach { it.delete() }
    }

    /** Collision-safe output template, reused across pause/resume/retry so partial downloads continue. */
    internal fun reserveTemplate(id: String): String = synchronized(lock) {
        val record = find(id)
        record.outputTemplate?.takeIf { inOutput(File(it)) }?.let { return it }
        val folder = if (settingsFlow.value.organization == "platform") File(output, Providers.folder(record.job.url)) else output
        folder.mkdirs()
        val existing = folder.list()?.toList() ?: emptyList()
        val name = Providers.safeName(record.job.title)
        var suffix = 0
        var template: String
        while (true) {
            val stem = if (suffix == 0) name else "$name (${suffix + 1})"
            template = File(folder, "$stem.%(ext)s").absolutePath
            val reserved = records.any { it.id != id && it.outputTemplate == template }
            if (!reserved && existing.none { it.startsWith("$stem.") }) break
            suffix++
        }
        replace(record.copy(outputTemplate = template))
        try { save() } catch (error: Exception) { Log.e(TAG, "queue save failed", error) }
        template
    }

    /** The engine's own cache of YouTube's player script and solved challenges. Without it the library turns caching off and every link fetches and solves them again. */
    internal fun useEngineCache(request: YoutubeDLRequest) { request.addOption("--cache-dir", File(context.cacheDir, "engine").absolutePath) }

    internal fun cookieSnapshot(): File? {
        if (!cookieFile.isFile) return null
        // yt-dlp writes cookies back on exit; each run gets its own copy so imports never race running jobs.
        val copy = File(context.cacheDir, "cookies-${UUID.randomUUID()}.txt")
        return try { cookieFile.copyTo(copy, overwrite = true) } catch (_: Exception) { null }
    }

    /** Keepfin's own sign-in covers the site of [url]: a saved session with a live session cookie for it. */
    private fun signInCovers(url: String): Boolean {
        val site = SignIn.siteOf(url) ?: return false
        val text = synchronized(lock) { if (cookieFile.isFile) cookieFile.readText() else null } ?: return false
        return site.key in CookieExport.signedInSites(text, System.currentTimeMillis() / 1000)
    }

    /**
     * The sign-in to retry [url] with after a failure (null: none). Requests go signed out first; only a sign-in failure is retried, once,
     * with Keepfin's own sign-in when it covers the site and the setting is on. The decision is the shared core's ([SignIn.retry]).
     * [signInWall] says the failure is already known to be a sign-in wall (a photo post's listing).
     */
    internal fun signInFor(url: String, failure: String, alreadySigned: Boolean, signInWall: Boolean = false): SignInSource? {
        val action = if (signInWall) "signIn" else Errors.analyze(failure, Platform.ANDROID).action.wire
        val available = if (signInCovers(url)) listOf(SignInSource("keepfin", null, null, null)) else emptyList()
        return SignIn.retry(url, action, settingsFlow.value.autoSignIn, false, available, if (alreadySigned) available else emptyList())
    }

    /** A running job failed with [failure]: when a sign-in may be retried, the job records it (its row says so) and true is returned. */
    internal fun useSignIn(id: String, failure: String): Boolean {
        val job = record(id)?.job ?: return false
        val source = signInFor(job.url, failure, alreadySigned = job.signedIn != null) ?: return false
        return mutate(id, persist = true) { if (!it.status.active) null else it.copy(job = it.job.copy(signedIn = SignIn.notice(source))) } != null
    }

    internal fun stopped(id: String): Boolean = record(id)?.status?.active != true

    override fun retryPublicSave(id: String) {
        require(record(id)?.status == JobStatus.COMPLETED) { "This download isn't finished." }
        EngineAfterSteps(this, id).saveAgain()
    }

    /** Upgrades only this app's old public copies; no duplicate download or access to other apps' media. */
    fun repairGallery(): Triple<Int, Int, Int> = synchronized(publishingLock) {
        val candidates = synchronized(lock) {
            records.filter { it.status == JobStatus.COMPLETED && it.job.publicUri != null && it.job.publicPath?.startsWith("${publicDownloads.folder}/") == true }
        }
        var repaired = 0
        var failed = 0
        candidates.forEach { record ->
            try {
                val file = File(record.job.file ?: error("missing file"))
                require(inOutput(file) && file.isFile)
                val oldUri = record.job.publicUri!!
                val fixed = publicDownloads.repairGallery(PublicDownloads.Saved(Uri.parse(oldUri), record.job.publicPath!!), file)
                mutate(record.id) {
                    it.copy(job = it.job.copy(publicUri = fixed.uri.toString(), publicPath = fixed.path),
                        publicFiles = it.publicFiles.map { uri -> if (uri == oldUri) fixed.uri.toString() else uri }, galleryRepairError = null)
                }
                if (fixed.path.startsWith(publicDownloads.folderFor(file) + "/")) repaired++
            } catch (error: Exception) {
                failed++
                mutate(record.id) { it.copy(galleryRepairError = "Could not organize the saved media: ${error.message ?: "storage unavailable"}. The retained downloaded file is safe in the app.") }
            }
        }
        if (candidates.isNotEmpty()) synchronized(lock) { try { save() } catch (error: Exception) { Log.e(TAG, "queue save failed", error) } }
        Triple(repaired, failed, candidates.size)
    }

    internal fun galleryRepairError(id: String) = record(id)?.galleryRepairError

    // ---- auth & tools ----------------------------------------------------------------------------

    override fun importCookies(text: String) {
        require(text.length < 2_000_000 && (text.startsWith("# Netscape HTTP Cookie File") || text.startsWith("# HTTP Cookie File"))) { "Choose a Netscape-format cookies.txt file." }
        synchronized(lock) {
            cookieFile.writeText(text)
            cookieFile.setReadable(false, false); cookieFile.setReadable(true, true)
        }
        cookiesFlow.value = true
        inspector.invalidate()
    }

    override fun importSignInSession(): Int {
        val count = synchronized(lock) { CookieExport.export(cookieFile) }
        if (count > 0) { cookiesFlow.value = true; inspector.invalidate() }
        return count
    }

    override fun setMusicChoice(sourceUrl: String, matchedUrl: String, title: String?, durationSec: Double?) {
        val url = Providers.validateUrl(sourceUrl)
        val videoId = Restriction.videoId(Providers.validateUrl(matchedUrl)) ?: throw IllegalArgumentException("Choose a YouTube or YouTube Music recording.")
        musicChoices.set(url, videoId, title, durationSec)
    }

    override fun musicAlternates(sourceUrl: String, exclude: List<String>): List<MusicAlternate> {
        val url = Providers.validateUrl(sourceUrl)
        require(Providers.musicSource(url) != null) { "This is not a music service link." }
        val track = inspector.resolver.resolve(url, 1, 1).tracks.firstOrNull() ?: return emptyList()
        val skip = exclude.mapNotNull { Restriction.videoId(it) ?: it.takeIf { id -> Regex("[A-Za-z0-9_-]{11}").matches(id) } }.toSet()
        return inspector.resolver.alternates(track, skip).map { MusicAlternate(it.candidate.url, it.candidate.title, it.candidate.durationSec, it.confidence) }
    }

    override fun findMusicVideo(artists: List<String>, title: String): MusicVideoLookup = try {
        val found = inspector.resolver.findVideo(artists, title)
        if (found == null) MusicVideoLookup.None
        else MusicVideoLookup.Found(MusicVideoMatch(found.videoId, found.title, found.artists.joinToString(", ") { it.name }, found.views, found.durationSec))
    } catch (error: Exception) {
        Log.w(TAG, "music video lookup failed", error)
        MusicVideoLookup.Failed("Couldn't look for the music video. Check your connection and try again.")
    }

    override fun enqueueMusicVideo(video: MusicVideoMatch, songId: String, space: String?) {
        require(Regex("[A-Za-z0-9_-]{11}").matches(video.videoId)) { "This isn't a music video Keepfin can download." }
        // one download per song (asked for here, or by its playlist): a second finished video would be a stray file
        require(!videoComingFor(songId)) { "The music video for this song is already downloading." }
        add(listOf(videoJob(video, spaceFor(space, video.url, video.channel), song = songId)))
    }

    override fun clearCookies() {
        synchronized(lock) { cookieFile.delete() }
        try { android.webkit.CookieManager.getInstance().removeAllCookies(null) } catch (_: Throwable) { /* No WebView provider. */ }
        cookiesFlow.value = false
        inspector.invalidate()
    }

    override fun updateTools(force: Boolean) = toolsUpdater.update(force)

    fun settingsOrganization() = settingsFlow.value.organization

    /** Test/teardown helper for isolated engines: stops schedulers and workers, then waits for pending background work. */
    internal fun shutdown() {
        ticker.shutdownNow()
        folderLibrary?.shutdown()
        synchronized(lock) { processes.values.forEach { YoutubeDL.getInstance().destroyProcessById(it) } }
        pool.shutdown()
        background.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)
        background.awaitTermination(30, TimeUnit.SECONDS)
    }

    /** Test/teardown helper: waits for running jobs to stop. */
    internal fun awaitIdle(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (synchronized(lock) { running.isEmpty() }) return true
            Thread.sleep(50)
        }
        return false
    }

    companion object {
        private const val TAG = "KeepfinEngine"
        const val PROGRESS_MS = 250L
        private const val MAX_ACTIVE = 500
        /** Phase text of a job whose automatic retry waits for an engine refresh. */
        const val ENGINE_PHASE = "Updating engine, then retrying"
        const val RATE_LIMIT_PHASE = "Waiting out a YouTube limit"
    }
}
