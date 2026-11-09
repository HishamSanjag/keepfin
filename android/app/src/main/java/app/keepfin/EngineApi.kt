package app.keepfin

import kotlinx.coroutines.flow.StateFlow

/**
 * The only surface the Compose UI, service and notifications use to talk to the engine.
 * Blocking calls (inspect, discover…) must be invoked off the main thread.
 * Flows update at most ~4 times per second per job while transferring.
 */
interface EngineApi {
    /** All jobs, newest first. Structural equality lets Compose skip unchanged rows. */
    val jobs: StateFlow<List<Job>>
    val settings: StateFlow<EngineSettings>
    val tools: StateFlow<List<ToolStatus>>
    val hasCookies: StateFlow<Boolean>

    /** Absolute app-private output folder (originals before public publishing). */
    val outputFolder: String

    /**
     * Spaces, gallery items, playlists and playback bookkeeping. Settings for the library live in [EngineSettings.library]
     * (change them through [updateSettings]; spaces through [LibraryApi.saveSpace]).
     */
    val libraryApi: LibraryApi

    /** The Activity list (dev-docs/architecture/ACTIVITY.md). The engine and application layer record into it; the UI only reads, marks read and removes. */
    val activity: ActivityFeed

    /** The artists followed and the releases found for them (Home's "New from artists you follow"). */
    val following: StateFlow<FollowingState> get() = FollowingState.NONE
    /** Follows [name] and checks for releases in the background. Nothing downloads until [keepRelease]. */
    fun followArtist(name: String) {}
    fun unfollowArtist(id: String) {}
    /** Checks [id], or every followed artist when null, in the background. */
    fun checkFollowing(id: String?) {}
    /** Downloads the release [id] with the default settings and marks it kept. Blocking: off the main thread. Returns how many downloads were queued. */
    fun keepRelease(id: String): Int = 0

    // Inspection (blocking, cancellable through cancelInspection(session)).
    fun inspect(url: String, session: String): InspectResult
    fun cancelInspection(session: String)

    // Collections: progressive discovery. Entries stream through onEntry; returns when finished.
    fun discover(url: String, session: String, start: Int, end: Int, onEntry: (PlaylistEntry) -> Unit): PlaylistDiscovery
    fun cancelDiscovery(session: String)

    // Queue.
    /** [space] null = the default for the link ([LibraryApi.defaultSpaceFor]); ignored while the library is off. */
    fun enqueue(media: MediaInfo, format: FormatRequest, options: DownloadOptions, space: String? = null)
    /**
     * [enqueue] plus the song's official music video, queued as its own download linked to the song's job: it is kept beside the song when both have finished
     * (either first), and as an ordinary download if the song fails or is cancelled. The song succeeds or fails apart from its video.
     */
    fun enqueue(media: MediaInfo, format: FormatRequest, options: DownloadOptions, space: String?, musicVideo: MusicVideoMatch?) { enqueue(media, format, options, space) }
    /** [enqueue] with the playlist chosen in the review ("Add to playlist"): the saved item joins it. Null = none. */
    fun enqueue(media: MediaInfo, format: FormatRequest, options: DownloadOptions, space: String?, musicVideo: MusicVideoMatch?, playlist: String?) { enqueue(media, format, options, space, musicVideo) }
    /** [sync] != null (playlists and channels only) turns "Keep in sync" on for the collection these entries create or join: automatic checks with those options. */
    /** [group] != null keeps the created jobs together as one collection; null queues independent items. [space] null = default per link. */
    /** [findVideo] (music collections kept as audio): each song, once saved, also gets its official music video where there is one. */
    fun enqueueEntries(session: String, entries: List<PlaylistEntry>, preset: DownloadPreset, options: DownloadOptions, group: JobGroup? = null, space: String? = null, sync: SyncRequest? = null, findVideo: Boolean = false, playlist: String? = null): Int
    /** The official music video of the song [title] by [artists], or why there is none. Blocking (a network request): call off the main thread. */
    fun findMusicVideo(artists: List<String>, title: String): MusicVideoLookup = MusicVideoLookup.Failed("Looking for music videos isn't available here.")
    /**
     * Queues [video] (up to 1080p, mp4) to be kept beside the song [songId] when it finishes: it becomes that song's video, never a library item of
     * its own. If the song is gone or already has a video by then, the file is kept as an ordinary download. [space] is where an ordinary download goes.
     */
    fun enqueueMusicVideo(video: MusicVideoMatch, songId: String, space: String? = null) { throw UnsupportedOperationException("Music videos aren't available here.") }
    /** "Download anyway" for a job that completed as "Already in <space>": queues it again, skipping the existing-recording check. */
    fun downloadAnyway(id: String)
    fun cancel(id: String)
    fun pause(id: String)
    fun resume(id: String)
    fun retry(id: String)
    /** Removes history only; never deletes the saved media. Rejects active jobs. */
    fun remove(id: String)
    /**
     * Undo of [remove]: brings the records of [ids] back as they were (same place in the list, artwork and partial files kept for
     * about a minute and a half after the removal). Returns the ids that came back; a record that was cleared for good is not among them.
     */
    fun restoreRemoved(ids: List<String>): List<String> = emptyList()
    /** Removes all terminal history entries; files remain. Returns their ids: [restoreRemoved] brings them back for a minute or so (Undo). */
    fun clearHistory(): List<String>
    /** Deletes the saved media (public copy + private original) and removes the entry. */
    fun deleteFile(id: String)

    // Files.
    fun file(id: String): java.io.File
    fun retryPublicSave(id: String)

    // Settings & auth.
    fun updateSettings(transform: (EngineSettings) -> EngineSettings)
    fun importCookies(text: String)
    /**
     * Exports android.webkit.CookieManager cookies for the supported sign-in domains (YouTube/Google,
     * Instagram, X, Facebook, TikTok, Reddit, Vimeo, SoundCloud) into the engine's Netscape cookies file.
     * Called by the in-app sign-in screen after the user signs in. Returns the number of cookies saved.
     */
    fun importSignInSession(): Int
    fun clearCookies()
    /** Uses this YouTube / YouTube Music recording for a Spotify, Apple Music or Deezer link from now on. Throws [IllegalArgumentException] for an invalid link or recording. */
    fun setMusicChoice(sourceUrl: String, matchedUrl: String, title: String? = null, durationSec: Double? = null)
    /** Other uploads of the song behind a music-service link (not [exclude]d recordings), best first, at most three. Blocking. */
    fun musicAlternates(sourceUrl: String, exclude: List<String> = emptyList()): List<MusicAlternate>

    // Engine tools (yt-dlp self-update).
    /** Runs on a background thread; progress reflected in [tools]. */
    fun updateTools(force: Boolean)
}
