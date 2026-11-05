package app.keepfin

import android.util.Log
import app.keepfin.core.after.Facts
import java.io.File

/**
 * The phone's follow-up steps of a finished download, on the real engine ([AfterDownloadSteps]; the order and the meaning of failures are the
 * core's plan). The step bodies are what used to be spread over [DownloadRunner] and [MediaEngine]: lyrics, the save into the library, the music
 * video beside its song, the playlist, the cover and the notification.
 */
internal class EngineAfterSteps(private val engine: MediaEngine, private val id: String, private val existing: ExistingMatch? = null) : AfterDownloadSteps {
    /** The song item a music video is kept beside, read with the plan's facts. */
    private var songItem: String? = null

    /** Runs every step for the finished job. */
    fun finish(): Unit = AfterDownloadRunner.run(facts() ?: return, this, item = existing?.itemId)

    /** Runs the saving steps only: "Save again", and a music video whose song is done. */
    fun saveAgain(): Unit = AfterDownloadRunner.run(facts() ?: return, this, only = AfterDownloadRunner.SAVING)

    private fun facts(): Facts? {
        val record = engine.record(id) ?: return null
        val folder = engine.folderLibrary?.takeIf { engine.settings.value.library.enabled }
        var video = "none"
        if (folder != null && (record.attachTo != null || record.attachToJob != null)) {
            when (val fate = MusicVideoLink.fate(record, record.attachToJob?.let(engine::record)) { engine.songItem(folder, it) }) {
                MusicVideoLink.Fate.Wait -> video = "wait"
                is MusicVideoLink.Fate.Attach -> { video = "attach"; songItem = fate.itemId }
                MusicVideoLink.Fate.Ordinary -> video = "ordinary"
            }
        }
        return Facts(
            audio = record.job.kind == MediaKind.AUDIO, hasFile = existing == null, existing = existing != null, video = video,
            playlist = record.joinPlaylist != null, lyrics = engine.settings.value.lyrics, library = if (folder != null) "folder" else "legacy",
        )
    }

    private fun cancelled() = engine.record(id)?.status == JobStatus.CANCELLED

    // ---- the steps -----------------------------------------------------------------------------------------

    override fun lyrics() {
        val record = engine.record(id) ?: return
        val file = record.job.file?.let(::File) ?: return
        if (engine.stopped(id)) return
        val stem = engine.stem(record.outputTemplate, file) ?: file.nameWithoutExtension
        val before = file.length()
        val result = LyricsStep.run(record, file, stem, engine.settings.value.lyrics, engine.mediaTools) { LyricsLookup.find(it, cancelled = { engine.stopped(id) }) }
        Log.i(TAG, "${id.take(8)} lyrics: $result")
        if (result == LyricsStep.Result.EMBEDDED && file.length() != before) engine.mutate(id, persist = true) { if (it.job.file == null) null else it.copy(job = it.job.copy(fileSize = file.length())) }
    }

    /** Publishes the private original (+ sidecars): into the Keepfin folder library, else to MediaStore. Never claims success for a failed save. */
    override fun save(): String? = synchronized(engine.publishingLock) {
        val record = engine.record(id) ?: return null
        if (record.job.publicUri != null) {
            if (record.job.publicPath?.startsWith("${engine.publicDownloads.folder}/") == true) engine.repairGallery()
            return null
        }
        val file = File(record.job.file ?: return null)
        require(engine.inOutput(file) && file.isFile) { "The downloaded file is unavailable." }
        val stem = engine.stem(record.outputTemplate, file) ?: file.nameWithoutExtension
        val sidecars = engine.stemFiles(file.parentFile, record.outputTemplate, file).filter {
            it != file && it.extension.lowercase() in setOf("vtt", "srt", "ass", "lrc", "jpg", "jpeg", "png", "webp")
        }
        val folder = engine.folderLibrary
        if (folder != null && engine.settings.value.library.enabled) saveToFolder(folder, record, file, sidecars, stem) else saveToMediaStore(record, file, sidecars, stem)
    }

    /**
     * Folder mode: the finished download (still in app-private storage) is copied into the space folder of the Keepfin folder (staged, verified,
     * renamed) and added to the library. A failure keeps the private file and says so; nothing is claimed that did not happen.
     */
    private fun saveToFolder(folder: app.keepfin.library.repo.LibraryRepository, record: JobRecord, file: File, sidecars: List<File>, stem: String): String? {
        val library = engine.settings.value.library
        val space = library.folderSpaces.firstOrNull { it.id == record.job.space } ?: library.folderSpaces.firstOrNull { it.id == library.otherRoute } ?: library.folderSpaces.first()
        val job = record.job
        val context = saveContext(record)
        // chapters: an .info.json next to the file, else the info the download started from
        val chapters = if (job.kind == MediaKind.VIDEO) (ChapterParser.fromFile(File(file.parentFile, file.nameWithoutExtension + ".info.json"), job.duration)
            ?: ChapterParser.fromFile(engine.inspector.anyInfo(record.downloadUrl), job.duration)) else null
        val saved = folder.saveDownload(job, record.source, space.id, SpaceLogic.subfolder(space, context), SpaceLogic.fileStem(space, context).ifBlank { stem }, file, sidecars, chapters)
        val kept = engine.mutate(id, persist = true) {
            if (it.status == JobStatus.CANCELLED) null
            else it.copy(job = it.job.copy(publicUri = saved.uri, publicPath = saved.path, saveError = null, filesRemoved = false, phase = null), publicFiles = listOf(saved.uri) + saved.sidecarUris)
        }
        return saved.itemId.takeIf { kept != null }
    }

    /** No Keepfin folder yet (a download that finishes before setup): the file is published to MediaStore and not indexed. */
    private fun saveToMediaStore(record: JobRecord, file: File, sidecars: List<File>, stem: String): String? {
        val destination = destination(record)
        val result = engine.publicDownloads.publish(listOf(file) + sidecars, destination?.second ?: stem, destination?.first, stem) { cancelled() }
        val kept = engine.mutate(id, persist = true) {
            if (it.status == JobStatus.CANCELLED) null
            else it.copy(job = it.job.copy(publicUri = result.getValue(file).uri.toString(), publicPath = result.getValue(file).path, saveError = null, filesRemoved = false),
                publicFiles = result.values.map { saved -> saved.uri.toString() })
        }
        if (kept == null) { engine.publicDownloads.discard(result.values); return null }
        // no Keepfin folder yet: the file is public but not in any library
        return null
    }

    /**
     * A finished music video download becomes the video of its song ([JobRecord.attachTo]): copied beside it in the Keepfin folder and recorded,
     * never a library item of its own. Returns the song's item. Null when the song cannot take it (nothing was changed; the plan saves it as an
     * ordinary download).
     */
    override fun attachVideo(): String? = synchronized(engine.publishingLock) {
        val record = engine.record(id) ?: return null
        val folder = engine.folderLibrary ?: return null
        val songId = songItem ?: return null
        val file = File(record.job.file ?: return null)
        require(engine.inOutput(file) && file.isFile) { "The downloaded file is unavailable." }
        val attached = folder.attachMusicVideo(songId, file) ?: return null
        folder.rememberJobItem(id, attached.itemId)
        engine.mutate(id, persist = true) {
            if (it.status == JobStatus.CANCELLED) null
            else it.copy(job = it.job.copy(publicUri = attached.uri, publicPath = attached.path, saveError = null, filesRemoved = false, phase = null), publicFiles = listOf(attached.uri))
        }
        songId
    }

    /** The song is still coming: the video stays private until it is done (settled by [MediaEngine.settleVideos]). */
    override fun waitForSong() { engine.mutate(id, persist = true) { it.copy(job = it.job.copy(phase = MusicVideoLink.WAITING)) } }

    override fun joinPlaylist(itemId: String?) {
        val record = engine.record(id) ?: return
        if (record.status == JobStatus.CANCELLED) return
        val library = engine.libraryApi
        try { PlaylistJoin.join(record.joinPlaylist, listOfNotNull(itemId), library::collection, library::collectionAdd) } catch (error: Exception) { Log.w(TAG, "playlist not joined", error) }
    }

    /** Durable artwork from the local media when the provider thumbnail was never cached. */
    override fun cover() {
        val record = engine.record(id) ?: return
        if (engine.thumbnails.loadJob(id) != null) return
        val file = record.job.file?.let(::File)
        LocalMediaPreview.load(engine.context, record.job.publicUri?.let(android.net.Uri::parse), file, 320)?.let {
            try { engine.rememberThumbnail(id, it) } finally { it.recycle() }
        }
    }

    override fun notifyDone() = engine.complete(id)

    override fun notifyExisting() {
        val match = existing ?: return
        val record = engine.mutate(id, persist = true) {
            if (!it.status.active) null else it.copy(job = it.job.copy(status = JobStatus.COMPLETED, percent = 100f, speed = null, eta = null, phase = null, error = null, errorDetail = null,
                completedAt = System.currentTimeMillis(), existing = match, retryAt = null, file = null, publicUri = null, publicPath = null, saveError = null))
        } ?: return
        engine.notifyFinished(record.job)
    }

    override fun settleVideos() { engine.record(id)?.let(engine::settleLater) }

    /** The job completes as "Not saved": the file stays safe in the app, and Save again tries once more. */
    override fun markNotSaved(error: Throwable) {
        Log.w(TAG, "save failed", error)
        val why = when (error) {
            is app.keepfin.folder.FolderError.NeedsAccess -> "Keepfin needs access to its folder again. Open Settings > Storage and choose it."
            is app.keepfin.folder.FolderError.Unavailable -> "The Keepfin folder is not available (is the memory card in?)."
            is app.keepfin.folder.FolderError.DiskFull -> "The Keepfin folder is full."
            else -> (error.message ?: "storage unavailable").let { if (it.endsWith(".")) it else "$it." }
        }
        engine.mutate(id, persist = true) {
            if (it.status == JobStatus.CANCELLED) null else it.copy(job = it.job.copy(saveError = "Automatic save failed: $why The downloaded file is safe in the app.", phase = null))
        }
    }

    // ---- placement ------------------------------------------------------------------------------------------

    private fun saveContext(record: JobRecord): SaveContext {
        val job = record.job
        val group = job.group
        return SaveContext(
            title = record.source?.title ?: job.title, uploader = job.uploader, artist = record.source?.artist, album = record.source?.album,
            url = job.match?.takeIf { it.source != Restriction.SOURCE }?.sourceUrl ?: job.url, groupTitle = group?.title, groupKind = group?.kind,
            audio = job.kind == MediaKind.AUDIO, dateMillis = System.currentTimeMillis(),
        )
    }

    /** Space placement and public file stem of a job, or null (legacy folders) while the library is off or the job has no space. */
    private fun destination(record: JobRecord): Pair<PublicDownloads.Placement, String>? {
        val library = engine.settings.value.library
        if (!library.enabled) return null
        val space = library.folderSpaces.firstOrNull { it.id == record.job.space } ?: return null
        val context = saveContext(record)
        val sub = SpaceLogic.subfolder(space, context)
        return PublicDownloads.Placement(
            SpaceLogic.folderPath(StorageRoot.MUSIC, space, sub), SpaceLogic.folderPath(StorageRoot.MOVIES, space, sub), SpaceLogic.folderPath(StorageRoot.DOWNLOAD, space, sub),
        ) to SpaceLogic.fileStem(space, context)
    }

    private companion object { const val TAG = "KeepfinRunner" }
}
