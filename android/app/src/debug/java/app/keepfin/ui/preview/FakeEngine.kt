package app.keepfin.ui.preview

import android.content.Context
import app.keepfin.AudioOption
import app.keepfin.DownloadOptions
import app.keepfin.DownloadPreset
import app.keepfin.EngineApi
import app.keepfin.EngineSettings
import app.keepfin.FormatRequest
import app.keepfin.InspectResult
import app.keepfin.Job
import app.keepfin.JobGroup
import app.keepfin.JobStatus
import app.keepfin.MediaInfo
import app.keepfin.MediaKind
import app.keepfin.MusicAlternate
import app.keepfin.MusicMatch
import app.keepfin.MusicMetadata
import app.keepfin.PlaylistDiscovery
import app.keepfin.PlaylistEntry
import app.keepfin.SubtitleTrack
import app.keepfin.FolderLayout
import app.keepfin.Space
import app.keepfin.SpaceKind
import app.keepfin.SpaceNaming
import app.keepfin.ToolStatus
import app.keepfin.VideoOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Debug-only [EngineApi] with synthetic fixtures for previews, screenshots and Compose UI tests.
 * Titles and artwork reference Blender open movies; every figure is illustrative, not a provider claim.
 */
class FakeEngine(private val context: Context? = null) : EngineApi {
    private val _jobs = MutableStateFlow<List<Job>>(emptyList())
    private val _settings = MutableStateFlow(EngineSettings())
    private val _tools = MutableStateFlow(Fixtures.tools)
    private val _cookies = MutableStateFlow(false)
    override val jobs: StateFlow<List<Job>> = _jobs
    override val settings: StateFlow<EngineSettings> = _settings
    override val tools: StateFlow<List<ToolStatus>> = _tools
    override val hasCookies: StateFlow<Boolean> = _cookies

    // Followed artists, kept in memory: tests seed [fakeFollowing] and read what the screens asked for.
    val fakeFollowing = MutableStateFlow(app.keepfin.FollowingState())
    override val following: StateFlow<app.keepfin.FollowingState> get() = fakeFollowing
    val keptReleases = CopyOnWriteArrayList<String>()
    val followChecks = CopyOnWriteArrayList<String?>()
    override fun followArtist(name: String) {
        val key = app.keepfin.core.home.ReleaseFeed.artistKey(name) ?: return
        fakeFollowing.update { s -> if (s.artists.any { it.key == key }) s else s.copy(artists = s.artists + app.keepfin.FollowedArtist("f-${s.artists.size + 1}", name.trim(), key)) }
    }
    override fun unfollowArtist(id: String) { fakeFollowing.update { s -> s.copy(artists = s.artists.filterNot { it.id == id }, releases = s.releases.filterNot { it.artistId == id }) } }
    override fun checkFollowing(id: String?) { followChecks += id }
    override fun keepRelease(id: String): Int {
        keptReleases += id
        fakeFollowing.update { s -> s.copy(releases = s.releases.map { if (it.id == id) it.copy(kept = true) else it }) }
        return 1
    }
    val fakeLibrary = FakeLibrary({ _settings.value.library.spaces }) { transform -> _settings.update { s -> s.copy(library = s.library.copy(spaces = transform(s.library.spaces))) } }
    override val libraryApi: app.keepfin.LibraryApi get() = fakeLibrary
    /** The real Activity store, in memory only; scenes seed it and tests read or record into it. */
    val fakeActivity = app.keepfin.ActivityStore(null, foreground = { true })
    override val activity: app.keepfin.ActivityFeed get() = fakeActivity
    override val outputFolder: String get() = context?.getExternalFilesDir("Download")?.absolutePath ?: "/tmp"

    // Recorded calls for tests.
    val inspectCalls = CopyOnWriteArrayList<String>()
    val cancelledInspections = CopyOnWriteArrayList<String>()
    val enqueued = CopyOnWriteArrayList<Triple<MediaInfo, FormatRequest, DownloadOptions>>()
    /** Space passed with each enqueue / enqueueEntries call. */
    val enqueuedSpaces = CopyOnWriteArrayList<String?>()
    val enqueuedEntries = CopyOnWriteArrayList<Pair<List<PlaylistEntry>, DownloadPreset>>()
    /** Group passed with each enqueueEntries call (null = "Separate items"). */
    val enqueuedGroups = CopyOnWriteArrayList<JobGroup?>()
    /** Keep-in-sync request passed with each enqueueEntries call (null = off). */
    val enqueuedSyncs = CopyOnWriteArrayList<app.keepfin.SyncRequest?>()
    /** The "also keep music videos" flag passed with each enqueueEntries call. */
    val enqueuedFindVideo = CopyOnWriteArrayList<Boolean>()
    val discoverCalls =CopyOnWriteArrayList<Pair<Int, Int>>()
    val cancelledDiscoveries = CopyOnWriteArrayList<String>()
    val actions = CopyOnWriteArrayList<String>()
    val settingsUpdates = AtomicInteger()
    val toolUpdates = AtomicInteger()

    // Behaviour knobs.
    @Volatile var inspectDelayMs = 350L
    val inspectResults = ConcurrentHashMap<String, InspectResult>()
    @Volatile var inspectFailure: String? = null
    /** A site (for example "Instagram") that shows every link only to signed-in people: inspection fails as it does on the real engine. */
    @Volatile var inspectSignInSite: String? = null
    @Volatile var collectionSize = 60
    @Volatile var discoverEntryDelayMs = 25L
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val counter = AtomicInteger(100)
    @Volatile private var ticker: Thread? = null

    fun setJobs(list: List<Job>) { _jobs.value = list }
    fun updateJob(id: String, transform: (Job) -> Job) = _jobs.update { list -> list.map { if (it.id == id) transform(it) else it } }
    fun setTools(list: List<ToolStatus>) { _tools.value = list }

    fun applyScene(scene: String) {
        ticker?.interrupt(); ticker = null
        fakeActivity.entries.value.forEach { fakeActivity.remove(it.id) }
        when (scene) {
            "empty" -> setJobs(emptyList())
            "activity", "activity-away" -> {
                setJobs(Fixtures.groupFailed()); fakeLibrary.loadSpacesV2()
                _settings.update { s -> s.copy(library = s.library.copy(spaces = FakeLibraryData.spaces(), musicRoute = "music", otherRoute = "downloads")) }
                seedActivity(away = scene == "activity-away")
            }
            "library" -> { setJobs(Fixtures.library()); fakeLibrary.reset() }
            "library-spaces" -> {
                setJobs(Fixtures.library()); fakeLibrary.reset()
                _settings.update { s ->
                    s.copy(library = s.library.copy(spaces = s.library.spaces + Space("watch-later", "Watch later", SpaceKind.VIDEO, "Watch later", FolderLayout.COLLECTION, SpaceNaming.TITLE, pinned = true)))
                }
                fakeLibrary.addWatchLater()
            }
            "spaces-v2" -> {
                setJobs(Fixtures.library()); fakeLibrary.loadSpacesV2()
                _settings.update { s -> s.copy(library = s.library.copy(spaces = FakeLibraryData.spaces(), musicRoute = "music", otherRoute = "downloads")) }
            }
            "groups-failed" -> setJobs(Fixtures.groupFailed())
            "groups" -> setJobs(Fixtures.groups())
            "groups-library" -> setJobs(Fixtures.groups().filter { it.group?.id != "g-live" })
            "retrying" -> setJobs(Fixtures.retrying())
            "engine-updating" -> { setJobs(Fixtures.queue()); setTools(Fixtures.tools.map { if (it.name == "yt-dlp") it.copy(state = "updating") else it }) }
            "engine-broken" -> { setJobs(Fixtures.queue()); setTools(Fixtures.tools.map { if (it.name == "yt-dlp") it.copy(state = "error", message = "Engine update failed: no connection.") else it }) }
            "live" -> { setJobs(Fixtures.queue()); startTicker() }
            else -> setJobs(Fixtures.queue())
        }
        _cookies.value = scene != "empty"
    }

    /**
     * A believable Activity list: four unread entries (one of them a problem), older read ones, one weekday back. With [away] the unread ones
     * count as "created while you were away".
     */
    private fun seedActivity(away: Boolean) {
        val now = System.currentTimeMillis()
        val minute = 60_000L
        val hour = 60 * minute
        fun add(entry: app.keepfin.ActivityEntry, away: Boolean = false) = fakeActivity.record(entry, away = away)
        fun target(collection: String? = null, group: String? = null, jobs: List<String> = emptyList(), version: String? = null, provider: String? = null) =
            app.keepfin.ActivityTarget(collectionId = collection, groupId = group, jobIds = jobs, version = version, provider = provider)
        val kind = app.keepfin.ActivityKind.entries.associateBy { it.wire }
        add(app.keepfin.ActivityEntry("a-sync", kind.getValue("sync-found"), now - 12 * minute, "Gym Mix got 3 new songs", "All 3 saved", target = target(collection = "gym"), count = 3), away)
        add(app.keepfin.ActivityEntry("a-review", kind.getValue("sync-review"), now - 40 * minute, "Daily run has 28 new songs waiting", "More than 20, so they wait for you", target = target(collection = "daily-run"), count = 28), away)
        add(app.keepfin.ActivityEntry("a-failed", kind.getValue("failed"), now - 2 * hour, "1 song from Open Movies OST failed", "Not available in your region", target = target(group = "g-ost", jobs = listOf("g-ost-10")), count = 1), away)
        add(app.keepfin.ActivityEntry("a-update", kind.getValue("update-ready"), now - 25 * minute, "Keepfin ${FakeUpdates.NEXT_VERSION} is ready to install", target = target(version = FakeUpdates.NEXT_VERSION)), away)
        add(app.keepfin.ActivityEntry("a-batch", kind.getValue("batch-done"), now - 3 * hour, "Road trip finished", "24 of 24 songs saved to Music", read = true, target = target(collection = "roadtrip"), count = 24))
        add(app.keepfin.ActivityEntry("a-updated", kind.getValue("app-updated"), now - 26 * hour, "Keepfin updated to ${app.keepfin.BuildConfig.VERSION_NAME}", "Songs sync with Keep in sync", read = true, target = target(version = app.keepfin.BuildConfig.VERSION_NAME)))
        add(app.keepfin.ActivityEntry("a-signin", kind.getValue("sign-in"), now - 74 * hour, "Sign in to YouTube again", "Private or members-only downloads need it", read = true, target = target(provider = "YouTube")))
    }

    /** Moves the active download forward like a real transfer, for live screenshots. */
    private fun startTicker() {
        ticker = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    Thread.sleep(250)
                    updateJob("j-dl") { job ->
                        val percent = if (job.percent >= 99f) 12f else job.percent + 0.4f
                        val total = job.totalBytes ?: 1L
                        job.copy(percent = percent, downloadedBytes = (total * percent / 100).toLong(), speed = 6_400_000L + (percent * 9000).toLong(), eta = ((100 - percent) * 2.6).toLong())
                    }
                }
            } catch (_: InterruptedException) { }
        }.apply { isDaemon = true; start() }
    }

    override fun inspect(url: String, session: String): InspectResult {
        inspectCalls.add(url)
        val end = System.currentTimeMillis() + inspectDelayMs
        while (System.currentTimeMillis() < end) {
            if (session in cancelled) error("Inspection cancelled.")
            Thread.sleep(10)
        }
        inspectFailure?.let { error(it) }
        inspectSignInSite?.let { throw app.keepfin.SignInNeeded(it, "$it shows this post only to signed-in people.") }
        inspectResults[url]?.let { return it }
        return when {
            "list=" in url && "watch" !in url -> InspectResult.Collection(url, "Blender Open Movies")
            "spotify" in url -> InspectResult.Media(Fixtures.spotifyTrack(url))
            "playlist-item" in url -> InspectResult.Media(Fixtures.bigBuckBunny(url).copy(playlistUrl = "https://www.youtube.com/playlist?list=PLblender"))
            else -> InspectResult.Media(Fixtures.bigBuckBunny(url))
        }
    }

    override fun cancelInspection(session: String) { cancelledInspections.add(session); cancelled.add(session) }

    override fun discover(url: String, session: String, start: Int, end: Int, onEntry: (PlaylistEntry) -> Unit): PlaylistDiscovery {
        discoverCalls.add(start to end)
        val last = minOf(end, collectionSize)
        val found = mutableListOf<PlaylistEntry>()
        for (position in start..last) {
            if (cancelled.remove(session)) return PlaylistDiscovery(session, url, "Blender Open Movies", found, "cancelled", null, end - start + 1, true)
            Thread.sleep(discoverEntryDelayMs)
            // "PLgone": the third entry is a video whose channel was removed (what discovery reports for it)
            val entry = if ("list=PLgone" in url && position == 3)
                Fixtures.entry(position).copy(title = null, uploader = null, duration = null, unavailable = app.keepfin.core.formats.Unavailable.GONE)
            else Fixtures.entry(position)
            found += entry
            onEntry(entry)
        }
        cancelled.remove(session)
        return PlaylistDiscovery(session, url, "Blender Open Movies", found, "completed", null, end - start + 1, truncated = collectionSize > end)
    }

    override fun cancelDiscovery(session: String) { cancelledDiscoveries.add(session); cancelled.add(session) }

    override fun enqueue(media: MediaInfo, format: FormatRequest, options: DownloadOptions, space: String?) {
        enqueued.add(Triple(media, format, options)); enqueuedSpaces.add(space)
        val id = "j-new-${counter.incrementAndGet()}"
        val label = when (format) {
            is FormatRequest.Video -> listOfNotNull(format.maxHeight?.let { "${it}p" } ?: "Best", format.container.uppercase()).joinToString(" · ")
            is FormatRequest.Audio -> format.target.uppercase()
            is FormatRequest.Gallery -> app.keepfin.PhotoPosts.label(format, media)
        }
        val kind = when (format) { is FormatRequest.Audio -> MediaKind.AUDIO; is FormatRequest.Gallery -> MediaKind.PHOTOS; is FormatRequest.Video -> MediaKind.VIDEO }
        _jobs.update { listOf(Fixtures.job(id, media.title, media.thumbnail, JobStatus.QUEUED, label = label, kind = kind, url = media.url, duration = media.duration)) + it }
    }

    override fun enqueueEntries(session: String, entries: List<PlaylistEntry>, preset: DownloadPreset, options: DownloadOptions, group: JobGroup?, space: String?, sync: app.keepfin.SyncRequest?, findVideo: Boolean, playlist: String?): Int {
        enqueuedSyncs.add(sync); enqueuedFindVideo.add(findVideo); enqueuedPlaylists.add(playlist)
        enqueuedEntries.add(entries to preset); enqueuedSpaces.add(space)
        enqueuedGroups.add(group)
        _jobs.update { current -> entries.map { e -> Fixtures.job("j-e-${counter.incrementAndGet()}", e.title ?: "Item", e.thumbnail, JobStatus.QUEUED, label = preset.label, url = e.url, duration = e.duration).copy(group = group) } + current }
        return entries.size
    }

    override fun downloadAnyway(id: String) { actions.add("anyway:$id"); updateJob(id) { it.copy(status = JobStatus.QUEUED, existing = null, force = true) } }
    override fun cancel(id: String) { actions.add("cancel:$id"); updateJob(id) { it.copy(status = JobStatus.CANCELLED, speed = null, eta = null) } }
    override fun pause(id: String) { actions.add("pause:$id"); updateJob(id) { it.copy(status = JobStatus.PAUSED, speed = null, eta = null) } }
    override fun resume(id: String) { actions.add("resume:$id"); updateJob(id) { it.copy(status = JobStatus.QUEUED) } }
    override fun retry(id: String) { actions.add("retry:$id"); updateJob(id) { it.copy(status = JobStatus.QUEUED, error = null, attempt = it.attempt + 1) } }
    private val removedJobs = LinkedHashMap<String, Job>()
    override fun remove(id: String) { actions.add("remove:$id"); _jobs.value.firstOrNull { it.id == id }?.let { removedJobs[id] = it }; _jobs.update { list -> list.filterNot { it.id == id } } }
    override fun restoreRemoved(ids: List<String>): List<String> {
        val back = ids.mapNotNull { removedJobs.remove(it) }
        if (back.isNotEmpty()) actions.add("restore:${back.joinToString { it.id }}")
        _jobs.update { list -> (list + back).sortedByDescending { it.createdAt } }
        return back.map { it.id }
    }
    override fun clearHistory(): List<String> {
        actions.add("clearHistory")
        val gone = _jobs.value.filterNot { it.status.active || it.status == JobStatus.PAUSED }
        gone.forEach { removedJobs[it.id] = it }
        _jobs.update { list -> list.filter { it.status.active || it.status == JobStatus.PAUSED } }
        return gone.map { it.id }
    }
    override fun deleteFile(id: String) { actions.add("delete:$id"); _jobs.update { list -> list.filterNot { it.id == id } } }
    override fun file(id: String): File = File(outputFolder, "$id.mp4")
    override fun retryPublicSave(id: String) { actions.add("save:$id"); updateJob(id) { it.copy(saveError = null) } }

    override fun updateSettings(transform: (EngineSettings) -> EngineSettings) { settingsUpdates.incrementAndGet(); _settings.update(transform) }
    override fun importCookies(text: String) { _cookies.value = true }
    override fun importSignInSession(): Int { _cookies.value = true; return 12 }
    override fun clearCookies() { _cookies.value = false }
    override fun setMusicChoice(sourceUrl: String, matchedUrl: String, title: String?, durationSec: Double?) { }
    override fun musicAlternates(sourceUrl: String, exclude: List<String>): List<MusicAlternate> = emptyList()

    /** What the next "Get the music video" finds (tests set it), how long the look takes, and what was asked and queued. */
    @Volatile var musicVideoLookup: app.keepfin.MusicVideoLookup = app.keepfin.MusicVideoLookup.None
    @Volatile var musicVideoDelayMs = 0L
    val musicVideoLookups = java.util.concurrent.CopyOnWriteArrayList<Pair<List<String>, String>>()
    val musicVideosQueued = java.util.concurrent.CopyOnWriteArrayList<Pair<app.keepfin.MusicVideoMatch, String>>()
    override fun findMusicVideo(artists: List<String>, title: String): app.keepfin.MusicVideoLookup {
        musicVideoLookups.add(artists to title)
        if (musicVideoDelayMs > 0) Thread.sleep(musicVideoDelayMs)
        return musicVideoLookup
    }
    override fun enqueueMusicVideo(video: app.keepfin.MusicVideoMatch, songId: String, space: String?) { musicVideosQueued.add(video to songId) }
    /** Songs queued from Capture with their video (link of the song, video), in order. */
    val capturedWithVideo = java.util.concurrent.CopyOnWriteArrayList<Pair<String, app.keepfin.MusicVideoMatch>>()
    /** The playlist ("Add to playlist") passed with each enqueue / enqueueEntries call, in order (null = none). */
    val enqueuedPlaylists = CopyOnWriteArrayList<String?>()
    override fun enqueue(media: MediaInfo, format: FormatRequest, options: DownloadOptions, space: String?, musicVideo: app.keepfin.MusicVideoMatch?, playlist: String?) {
        enqueuedPlaylists.add(playlist)
        enqueue(media, format, options, space, musicVideo)
    }
    override fun enqueue(media: MediaInfo, format: FormatRequest, options: DownloadOptions, space: String?, musicVideo: app.keepfin.MusicVideoMatch?) {
        if (musicVideo != null) capturedWithVideo.add(media.url to musicVideo)
        enqueue(media, format, options, space)
    }
    override fun updateTools(force: Boolean) {
        toolUpdates.incrementAndGet()
        _tools.update { list -> list.map { if (it.name == "yt-dlp") it.copy(state = "updating") else it } }
        Thread {
            Thread.sleep(1200)
            _tools.update { list -> list.map { if (it.name == "yt-dlp") it.copy(state = "ready", checkedAt = System.currentTimeMillis()) else it } }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        /** Entry point used by StudioEngine.installDebugFixtures through reflection. */
        @JvmStatic fun fixtures(context: Context, scene: String): FakeEngine = FakeEngine(context).apply { applyScene(scene) }
    }
}

/** Synthetic, clearly illustrative fixture data (Blender open movies). */
object Fixtures {
    private const val MB = 1024L * 1024L
    fun thumb(id: String) = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    val tools = listOf(
        ToolStatus("yt-dlp", "ready", "2026.09.27", System.currentTimeMillis() - 3 * 3600_000L, null),
        ToolStatus("ffmpeg", "ready", "7.1", null, null),
        ToolStatus("python", "ready", "3.11", null, null),
    )

    fun job(
        id: String, title: String, thumbnail: String?, status: JobStatus,
        label: String = "1080p60 · MP4", kind: MediaKind = MediaKind.VIDEO, url: String = "https://www.youtube.com/watch?v=$id",
        percent: Float = 0f, speed: Long? = null, eta: Long? = null, downloaded: Long? = null, total: Long? = null,
        phase: String? = null, error: String? = null, saveError: String? = null, publicPath: String? = null,
        fileSize: Long? = null, duration: Double? = 600.0, provider: String = "YouTube", ageMinutes: Long = 5, match: MusicMatch? = null,
    ) = Job(
        id = id, url = url, title = title, thumbnail = thumbnail, uploader = "Blender Studio", duration = duration, provider = provider,
        label = label, kind = kind, status = status, percent = percent, speed = speed, eta = eta, downloadedBytes = downloaded,
        totalBytes = total, expectedSize = total, fileSize = fileSize, phase = phase, error = error, file = null,
        publicUri = null, publicPath = publicPath, saveError = saveError,
        createdAt = System.currentTimeMillis() - ageMinutes * 60_000L - 60_000L,
        completedAt = if (status == JobStatus.COMPLETED || status == JobStatus.FAILED || status == JobStatus.CANCELLED) System.currentTimeMillis() - ageMinutes * 60_000L else null,
        attempt = 1, match = match,
    )

    fun queue(): List<Job> = listOf(
        job("j-dl", "Big Buck Bunny 60fps 4K – Official Blender Foundation Short Film", thumb("aqz-KE-bpKQ"), JobStatus.DOWNLOADING,
            label = "2160p60 · MP4", percent = 42f, speed = 6_800_000, eta = 38, downloaded = 280 * MB, total = 665 * MB, duration = 634.0),
        job("j-proc", "Sintel – Open Movie by Blender Foundation", thumb("eRsGyueVLvQ"), JobStatus.PROCESSING,
            label = "1080p · MKV", percent = 100f, phase = "Merging video and audio", downloaded = 512 * MB, total = 512 * MB, duration = 888.0),
        job("j-paused", "Cosmos Laundromat – First Cycle", thumb("Y-rmzh0PI3c"), JobStatus.PAUSED,
            label = "1440p · MP4", percent = 63f, downloaded = 410 * MB, total = 650 * MB, duration = 731.0),
        job("j-queued", "Tears of Steel – Blender Foundation", thumb("R6MlUcmOul8"), JobStatus.QUEUED, label = "720p · MP4", total = 180 * MB, duration = 734.0),
        job("j-failed", "Members-only studio stream replay", thumb("mN0zPOpADL4"), JobStatus.FAILED, label = "1080p · MP4",
            error = "Sign in required: this video is for channel members. Add your account in Settings › Downloads › Website sign-in.", ageMinutes = 12),
        job("j-saveerr", "Caminandes 3: Llamigos", thumb("SkVqJ1SGeL0"), JobStatus.COMPLETED, label = "1080p · MP4",
            saveError = "Storage is full. Free some space, then save again.", fileSize = 96 * MB, ageMinutes = 25, duration = 150.0),
        job("j-done1", "Spring – Blender Open Movie", thumb("WhWc3b3KhnY"), JobStatus.COMPLETED, label = "2160p · MP4",
            publicPath = "/storage/emulated/0/Movies/Keepfin/Spring – Blender Open Movie.mp4", fileSize = 312 * MB, ageMinutes = 40, duration = 464.0),
        job("j-audio", "Agent 327: Operation Barbershop (soundtrack)", thumb("mN0zPOpADL4"), JobStatus.COMPLETED, label = "MP3 · 320 kbps",
            kind = MediaKind.AUDIO, publicPath = "/storage/emulated/0/Music/Keepfin/Agent 327 soundtrack.mp3", fileSize = 9 * MB,
            provider = "YouTube Music", ageMinutes = 90, duration = 236.0,
            match = MusicMatch("spotify", "https://open.spotify.com/track/fixture", "https://music.youtube.com/watch?v=fixture", "Agent 327 – Theme", .92)),
        job("j-cancel", "Glass Half – Blender animated short", thumb("lqiN98z6Dak"), JobStatus.CANCELLED, label = "720p · MP4", ageMinutes = 200, duration = 190.0),
    )

    private val blenderList = JobGroup("g-live", "Blender Open Movies", "https://www.youtube.com/playlist?list=PLblender", "Blender Studio", thumb("aqz-KE-bpKQ"), "playlist")
    private val doneList = JobGroup("g-done", "Open Movies · Soundtracks", "https://music.youtube.com/playlist?list=OLAKblender", "Blender Studio", thumb("mN0zPOpADL4"), "album")

    /** A collection mid-download (8 of 12, 1 failed), one finished collection and a single download. */
    fun groups(): List<Job> {
        val live = (1..12).map { n ->
            val (name, id) = movies[(n - 1) % movies.size]
            val status = when { n <= 8 -> JobStatus.COMPLETED; n == 9 -> JobStatus.DOWNLOADING; n == 10 -> JobStatus.QUEUED; n == 11 -> JobStatus.FAILED; else -> JobStatus.QUEUED }
            job("g-live-$n", "$name – part $n", thumb(id), status, label = "1080p · MP4", percent = if (status == JobStatus.DOWNLOADING) 58f else if (status == JobStatus.COMPLETED) 100f else 0f,
                speed = if (status == JobStatus.DOWNLOADING) 5_200_000L else null, eta = if (status == JobStatus.DOWNLOADING) 21L else null,
                downloaded = if (status == JobStatus.DOWNLOADING) 120 * MB else null, total = 200 * MB, fileSize = if (status == JobStatus.COMPLETED) 200 * MB else null,
                error = if (status == JobStatus.FAILED) "The site rejected the download engine (it may be outdated)." else null,
                publicPath = if (status == JobStatus.COMPLETED) "/storage/emulated/0/Movies/Keepfin/$name.mp4" else null, ageMinutes = 30L - n).copy(group = blenderList)
        }
        val done = (1..5).map { n ->
            job("g-done-$n", "Track $n – Open Movies OST", thumb("mN0zPOpADL4"), JobStatus.COMPLETED, label = "MP3 · 320 kbps", kind = MediaKind.AUDIO, provider = "YouTube Music",
                publicPath = "/storage/emulated/0/Music/Keepfin/Track $n.mp3", fileSize = 9 * MB, ageMinutes = 120L - n, duration = 200.0).copy(group = doneList)
        }
        return live + done + job("j-single", "Spring – Blender Open Movie", thumb("WhWc3b3KhnY"), JobStatus.COMPLETED, label = "2160p · MP4",
            publicPath = "/storage/emulated/0/Movies/Keepfin/Spring.mp4", fileSize = 312 * MB, ageMinutes = 200, duration = 464.0)
    }

    /** A finished-looking collection with a region failure and a member waiting for an automatic retry, plus two active downloads. */
    fun groupFailed(): List<Job> {
        val list = JobGroup("g-ost", "Open Movies OST", "https://music.youtube.com/playlist?list=OLAKblender", "Blender Studio", thumb("mN0zPOpADL4"), "playlist")
        val saved = (1..9).map { n ->
            job("g-ost-$n", "Track $n – Open Movies", thumb(movies[(n - 1) % movies.size].second), JobStatus.COMPLETED, label = "MP3 · 320 kbps", kind = MediaKind.AUDIO, provider = "YouTube Music",
                publicPath = "/storage/emulated/0/Music/Keepfin/Track $n.mp3", fileSize = 9 * MB, ageMinutes = 40L - n, duration = 200.0).copy(group = list, space = "music")
        }
        val failed = job("g-ost-10", "Night Bus", thumb("SkVqJ1SGeL0"), JobStatus.FAILED, label = "MP3 · 320 kbps", kind = MediaKind.AUDIO, provider = "YouTube Music",
            error = "Not available in your region", ageMinutes = 20).copy(group = list, space = "music")
        val waiting = job("g-ost-11", "Salt Flats", thumb("lqiN98z6Dak"), JobStatus.QUEUED, label = "MP3 · 320 kbps", kind = MediaKind.AUDIO, provider = "YouTube Music",
            error = "YouTube limited downloads", ageMinutes = 18).copy(group = list, space = "music", retryCount = 1, retryAt = System.currentTimeMillis() + 3 * 60_000)
        return listOf(
            job("j-dl", "Big Buck Bunny 60fps 4K – Official Blender Foundation Short Film", thumb("aqz-KE-bpKQ"), JobStatus.DOWNLOADING,
                label = "2160p60 · MP4", percent = 42f, speed = 6_800_000, eta = 38, downloaded = 280 * MB, total = 665 * MB, duration = 634.0),
            job("j-queued", "Tears of Steel – Blender Foundation", thumb("R6MlUcmOul8"), JobStatus.QUEUED, label = "720p · MP4", total = 180 * MB, duration = 734.0),
        ) + saved + failed + waiting
    }

    /** One job waiting to retry and one that is updating the engine first. */
    fun retrying(): List<Job> = listOf(
        job("j-retry", "Sintel – Open Movie by Blender Foundation", thumb("eRsGyueVLvQ"), JobStatus.QUEUED, label = "1080p · MKV", percent = 31f, total = 512 * MB, phase = "Retrying")
            .copy(retryCount = 2, retryAt = System.currentTimeMillis() + 12_000),
        job("j-retry-engine", "Tears of Steel – Blender Foundation", thumb("R6MlUcmOul8"), JobStatus.QUEUED, label = "720p · MP4", total = 180 * MB, phase = app.keepfin.MediaEngine.ENGINE_PHASE)
            .copy(retryCount = 2, retryAt = System.currentTimeMillis() + 60_000),
        job("j-dl", "Big Buck Bunny 60fps 4K – Official Blender Foundation Short Film", thumb("aqz-KE-bpKQ"), JobStatus.DOWNLOADING,
            label = "2160p60 · MP4", percent = 42f, speed = 6_800_000, eta = 38, downloaded = 280 * MB, total = 665 * MB, duration = 634.0),
    )

    fun library(): List<Job> = queue().filter { !it.status.active && it.status != JobStatus.PAUSED } + listOf(
        job("j-lib1", "Charge – Blender Open Movie", thumb("UXqq0ZvbOnk"), JobStatus.COMPLETED, label = "2160p · MP4",
            publicPath = "/storage/emulated/0/Movies/Keepfin/Charge.mp4", fileSize = 540 * MB, ageMinutes = 60 * 26, duration = 236.0),
        job("j-lib2", "Sintel – Open Movie by Blender Foundation", thumb("eRsGyueVLvQ"), JobStatus.COMPLETED, label = "1080p · MKV",
            publicPath = "/storage/emulated/0/Movies/Keepfin/Sintel.mkv", fileSize = 512 * MB, ageMinutes = 60 * 50, duration = 888.0),
        job("j-lib3", "Big Buck Bunny – soundtrack", thumb("aqz-KE-bpKQ"), JobStatus.COMPLETED, label = "Opus · Best", kind = MediaKind.AUDIO,
            publicPath = "/storage/emulated/0/Music/Keepfin/Big Buck Bunny.opus", fileSize = 6 * MB, ageMinutes = 60 * 72, duration = 600.0),
    )

    fun bigBuckBunny(url: String) = MediaInfo(
        url = url, webpageUrl = url, title = "Big Buck Bunny 60fps 4K – Official Blender Foundation Short Film",
        uploader = "Blender", duration = 634.6, thumbnail = thumb("aqz-KE-bpKQ"), extractor = "youtube", provider = "YouTube",
        isLive = false, music = null,
        video = listOf(
            VideoOption("v2160", 2160, 3840, 60, false, "vp9", "webm", "315", false, 1_210 * MB, true, 15_800.0),
            VideoOption("v2160av1", 2160, 3840, 60, false, "av1", "mp4", "701", false, 880 * MB, true, 11_200.0),
            VideoOption("v1440", 1440, 2560, 60, false, "vp9", "webm", "308", false, 520 * MB, true, 6_800.0),
            VideoOption("v1080", 1080, 1920, 60, false, "h264", "mp4", "299", false, 145 * MB, false, 1_900.0),
            VideoOption("v1080vp9", 1080, 1920, 60, false, "vp9", "webm", "303", false, 132 * MB, true, 1_700.0),
            VideoOption("v720", 720, 1280, 60, false, "h264", "mp4", "298", false, 72 * MB, false, 950.0),
            VideoOption("v480", 480, 854, 30, false, "h264", "mp4", "135", false, 31 * MB, true, 410.0),
            VideoOption("v360", 360, 640, 30, false, "h264", "mp4", "18", true, 22 * MB, false, 290.0),
        ),
        audio = listOf(
            AudioOption("a251", "251", "opus", "webm", 160.0, 12 * MB, true, "en", "medium"),
            AudioOption("a140", "140", "aac", "m4a", 129.5, 10 * MB, false, "en", "medium"),
        ),
        subtitles = listOf(SubtitleTrack("en", "English", false), SubtitleTrack("de", "Deutsch", false), SubtitleTrack("es", "Español", false), SubtitleTrack("en", "English (auto)", true)),
        chapters = 0, playlistUrl = null,
    )

    fun spotifyTrack(url: String) = MediaInfo(
        url = url, webpageUrl = url, title = "Agent 327 – Theme", uploader = "Blender Studio", duration = 236.0, thumbnail = thumb("mN0zPOpADL4"),
        extractor = "spotify", provider = "Spotify", isLive = false, music = MusicMetadata("Blender Studio", "Agent 327 OST", "Agent 327 – Theme", 2017),
        video = emptyList(),
        audio = listOf(AudioOption("a251", "251", "opus", "webm", 160.0, 4 * MB, true, null, null), AudioOption("a140", "140", "aac", "m4a", 128.0, 3 * MB + 700_000, false, null, null)),
        subtitles = emptyList(), chapters = 0, playlistUrl = null,
        match = MusicMatch("spotify", url, "https://music.youtube.com/watch?v=fixture", "Agent 327 – Theme (Official Audio)", .92),
    )

    private val movies = listOf(
        "Big Buck Bunny" to "aqz-KE-bpKQ", "Sintel" to "eRsGyueVLvQ", "Tears of Steel" to "R6MlUcmOul8", "Cosmos Laundromat" to "Y-rmzh0PI3c",
        "Spring" to "WhWc3b3KhnY", "Agent 327" to "mN0zPOpADL4", "Caminandes 3" to "SkVqJ1SGeL0", "Glass Half" to "lqiN98z6Dak", "Charge" to "UXqq0ZvbOnk",
    )

    fun entry(position: Int): PlaylistEntry {
        val (name, id) = movies[(position - 1) % movies.size]
        return PlaylistEntry(
            url = "https://www.youtube.com/watch?v=$id&index=$position", title = if (position <= movies.size) name else "$name (part ${(position - 1) / movies.size + 1})",
            thumbnail = thumb(id), uploader = "Blender Studio", duration = 120.0 + (position * 37) % 600, position = position,
        )
    }
}
