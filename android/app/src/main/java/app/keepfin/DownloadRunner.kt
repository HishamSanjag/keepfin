package app.keepfin

import android.util.Log
import app.keepfin.core.music.Match
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/**
 * Executes one job: optional music matching, a yt-dlp run built only from the job's structured intent, progress
 * parsing, automatic fallbacks (fresh extraction when cached info is stale, native downloader when aria2c fails),
 * then public publishing. Pause/cancel are observed through the job status; this class never mutates other jobs.
 */
internal class DownloadRunner(private val engine: MediaEngine) {
    private data class Attempt(val useInfo: Boolean, val aria2c: Boolean, val cover: Boolean = true, val coverRetried: Boolean = false, val sourceCover: File? = null)
    private class Stopped : RuntimeException()
    private val photos = PhotoDownload(engine)

    /** Outcome of looking for another upload after an "unavailable" failure. */
    private sealed interface Recovery {
        object NotRestricted : Recovery
        class Failed(val raw: String) : Recovery
        /** [outcome] null means the alternate downloaded; otherwise a different failure that the normal retry logic handles. */
        class Done(val attempt: Attempt, val outcome: String?) : Recovery
    }

    fun run(initial: JobRecord) {
        val id = initial.id
        var record = initial
        var sourceCover: File? = null
        try {
            (record.format as? FormatRequest.Gallery)?.let { photos.run(record, it); return }
            // "Already in <space>": a recording that is already there completes the job without downloading.
            engine.existingMatch(record)?.let { EngineAfterSteps(engine, id, it).finish(); return }
            if (Providers.musicSource(record.job.url) != null) {
                record = match(record) ?: return
                engine.existingMatch(record)?.let { EngineAfterSteps(engine, id, it).finish(); return }
            }
            engine.reserveTemplate(id)
            record = engine.record(id) ?: return
            val settings = engine.settings.value
            engine.toolsUpdater.ensureInit()
            sourceCover = prepareSourceCover(record)
            var attempt = Attempt(useInfo = engine.inspector.freshInfo(record.downloadUrl) != null, aria2c = settings.turbo && engine.toolsUpdater.aria2cAvailable, sourceCover = sourceCover)
            var transient = 0
            var restrictionChecked = false
            while (true) {
                var outcome: String = execute(engine.record(id) ?: return, attempt) ?: run { finish(id); return }
                if (engine.stopped(id)) { stoppedCleanup(id); return }
                // A site that asks for a sign-in gets one try with Keepfin's own (the first try went without it).
                if (engine.useSignIn(id, outcome)) { attempt = attempt.copy(useInfo = false); continue }
                // "Video unavailable" can be a regional restriction YouTube never lifts: check once, and for songs use another upload.
                if (!restrictionChecked && Restriction.isUnavailable(outcome)) {
                    restrictionChecked = true
                    when (val recovery = recoverRestricted(engine.record(id) ?: return, attempt)) {
                        Recovery.NotRestricted -> {}
                        is Recovery.Failed -> { engine.failOrRetry(id, recovery.raw); return }
                        is Recovery.Done -> {
                            sourceCover = recovery.attempt.sourceCover ?: sourceCover
                            outcome = recovery.outcome ?: run { finish(id); return }
                            attempt = recovery.attempt
                        }
                    }
                    if (engine.stopped(id)) { stoppedCleanup(id); return }
                }
                Log.w(TAG, "job $id attempt $attempt failed: ${outcome.lineSequence().filter { it.isNotBlank() }.toList().takeLast(14).joinToString(" | ") { it.take(300) }}")
                // Throttling (rate limit, bot check) is never answered with an immediate second request: back off instead.
                if (ErrorMessages.throttled(outcome)) { engine.failOrRetry(id, outcome); return }
                val retryable = ErrorMessages.retryable(outcome)
                if (ErrorMessages.transient(outcome) && transient++ < 2) {
                    // Provider hiccups ("page needs to be reloaded", 5xx) usually succeed on a second extraction.
                    engine.inspector.forgetInfo((engine.record(id) ?: return).downloadUrl)
                    engine.progress(id, ProgressParser.Snapshot(0f, null, null, null, null, "Retrying", false))
                    Thread.sleep(2000L * transient)
                    if (engine.stopped(id)) { stoppedCleanup(id); return }
                    attempt = attempt.copy(useInfo = false)
                    continue
                }
                val refreshOnly = attempt.useInfo
                // The provider intermittently serves a truncated cover image ("Decoding error: Invalid data" in the converter),
                // which fails the whole post-processing step after the media itself downloaded. Retry once with a fresh cover
                // download; if that also fails, keep the media and drop the cover.
                val coverFailed = attempt.cover && record.options.embedThumbnail &&
                    outcome.contains("Conversion failed", ignoreCase = true)
                val coverOnly = coverFailed && attempt.coverRetried
                val coverFresh = coverFailed && !attempt.coverRetried
                attempt = when {
                    coverFresh -> { engine.discardCoverImages(engine.record(id) ?: record); attempt.copy(coverRetried = true) }
                    coverOnly -> attempt.copy(cover = false)
                    attempt.useInfo -> { engine.inspector.forgetInfo((engine.record(id) ?: return).downloadUrl); attempt.copy(useInfo = false) }
                    attempt.aria2c && retryable -> attempt.copy(aria2c = false)
                    else -> { engine.failOrRetry(id, outcome); return }
                }
                Log.i(TAG, "job $id retrying with $attempt after: ${outcome.lineSequence().lastOrNull { it.isNotBlank() }?.take(200)}")
                engine.progress(id, ProgressParser.Snapshot(0f, null, null, null, null, if (coverOnly) "Retrying without the embedded cover" else if (coverFresh) "Retrying the cover art" else if (refreshOnly) "Refreshing media info" else "Retrying with the standard downloader", false))
            }
        } catch (_: Stopped) {
            stoppedCleanup(id)
        } catch (error: Exception) {
            if (engine.stopped(id)) stoppedCleanup(id) else engine.failOrRetry(id, error.message, crashed = true)
        } finally {
            sourceCover?.delete()
        }
    }

    /**
     * For matched Spotify/Apple Music/Deezer tracks: downloads the source's album cover (limits in [CoverArt]) so it can replace the
     * video frame. Needs the cached extraction info to inject it; any failure returns null and the cropped video cover is kept.
     */
    private fun prepareSourceCover(record: JobRecord): File? {
        if (record.source == null || !record.options.embedThumbnail || record.format !is FormatRequest.Audio) return null
        val cover = coverUrl(record) ?: return null
        if (engine.inspector.ensureInfo(record.downloadUrl) == null) return null
        return CoverArt.fetch(cover, File(engine.context.cacheDir, "source-cover"), record.id)
    }

    /** The source's artwork; for another upload of a song, the original song's own cover (a YouTube image is fine there). */
    private fun coverUrl(record: JobRecord): String? {
        val thumbnail = record.job.thumbnail
        if (CoverArt.isSourceArtwork(thumbnail)) return thumbnail
        if (record.job.match?.source != Restriction.SOURCE) return null
        return Restriction.videoId(record.job.url)?.let(Restriction::coverUrl)
    }

    /**
     * After "Video unavailable": proves a regional restriction from the anonymous watch page. Songs are then downloaded from
     * another upload (up to [Restriction.MAX_ALTERNATES] in this same attempt, no backoff), tagged and covered as the original.
     */
    private fun recoverRestricted(record: JobRecord, attempt: Attempt): Recovery {
        val id = record.id
        val originalId = Restriction.videoId(record.job.url) ?: return Recovery.NotRestricted
        val probe = Restriction.fetch(originalId)
        if (Restriction.verdict(probe) != Restriction.Verdict.RESTRICTED || probe == null) return Recovery.NotRestricted
        if (engine.stopped(id)) throw Stopped()
        val music = Restriction.isMusicLike(record.job.url, probe.author, record.job.group?.url)
        if (!music) return Recovery.Failed(Restriction.failure(false, probe.title, emptyList()))
        engine.mutate(id) { it.copy(job = it.job.copy(phase = "Finding another upload")) }
        val own = record.source ?: SourceTags(record.job.title, Restriction.artistOf(record.job.uploader) ?: Restriction.artistOf(probe.author), null)
        val track = Restriction.sourceTrack(probe, record.job.url, own, record.job.duration)
        val failedAlternate = record.job.match?.takeIf { it.source == Restriction.SOURCE }?.matchedUrl?.let(Restriction::videoId)
        val picks = engine.inspector.resolver.alternates(track, setOfNotNull(originalId, failedAlternate))
        if (engine.stopped(id)) throw Stopped()
        val tried = ArrayList<String>()
        for (pick in picks) {
            val matchedUrl = Providers.validateUrl(pick.candidate.url)
            val updated = engine.mutate(id, persist = true) {
                if (!it.status.active) null
                else it.copy(source = own, job = it.job.copy(phase = "Trying another upload", match = alternateMatch(it, matchedUrl, pick), url = alternateUrl(it, matchedUrl)))
            } ?: throw Stopped()
            val label = "${pick.candidate.title} (${pick.candidate.url}, ${(pick.confidence * 100).toInt()}%)"
            val error = engine.inspector.extract(updated.downloadUrl)
            if (engine.stopped(id)) throw Stopped()
            if (error != null && Restriction.isUnavailable(error)) { tried += "$label: unavailable too"; continue }
            val cover = prepareSourceCover(updated)
            val next = Attempt(useInfo = engine.inspector.freshInfo(updated.downloadUrl) != null, aria2c = attempt.aria2c, sourceCover = cover)
            val outcome = execute(updated, next)
            if (outcome == null) return Recovery.Done(next, null)
            if (engine.stopped(id)) throw Stopped()
            if (Restriction.isUnavailable(outcome)) { tried += "$label: unavailable too"; cover?.delete(); continue }
            return Recovery.Done(next, outcome)
        }
        // Nothing worked: the job goes back to the song the user asked for, not to the last alternate that was tried.
        engine.mutate(id, persist = true) { it.copy(job = it.job.copy(url = record.job.url, match = record.job.match, phase = null)) }
        return Recovery.Failed(Restriction.failure(true, probe.title, tried))
    }

    /** A Spotify/Apple/Deezer match keeps its source and points at the new upload; anything else records "another upload". */
    private fun alternateMatch(record: JobRecord, matchedUrl: String, pick: Match): MusicMatch {
        val existing = record.job.match
        return if (existing != null && existing.source != Restriction.SOURCE) existing.copy(matchedUrl = matchedUrl, matchedTitle = pick.candidate.title, confidence = pick.confidence)
        else MusicMatch(Restriction.SOURCE, existing?.sourceUrl ?: record.job.url, matchedUrl, pick.candidate.title, pick.confidence)
    }

    /** Matched Spotify jobs already run on the matched link, so it moves; a YouTube song keeps the link the user asked for. */
    private fun alternateUrl(record: JobRecord, matchedUrl: String): String {
        val existing = record.job.match
        return if (existing != null && existing.source != Restriction.SOURCE) matchedUrl else record.job.url
    }

    private fun stoppedCleanup(id: String) {
        val record = engine.record(id) ?: return
        if (record.status == JobStatus.CANCELLED) engine.cleanupPartials(record)
    }

    private fun match(record: JobRecord): JobRecord? {
        val id = record.id
        engine.mutate(id) { it.copy(job = it.job.copy(phase = "Matching on YouTube Music")) }
        var source = record.source ?: SourceTags(record.job.title, record.job.uploader, null)
        var cover = record.job.thumbnail
        // Spotify playlist pages list no artwork or year per track; the single-track page has both. Failure keeps what we have.
        if (Providers.musicSource(record.job.url) == "spotify" && !CoverArt.isSourceArtwork(cover)) {
            runCatching { engine.inspector.resolver.resolve(record.job.url, 1, 1).tracks.firstOrNull() }.getOrNull()?.let { detail ->
                if (CoverArt.isSourceArtwork(detail.cover)) cover = detail.cover
                if (source.year == null && detail.year != null) source = source.copy(year = detail.year)
            }
        }
        val track = SourceTrack(source.title, listOfNotNull(source.artist), source.album, record.job.duration, record.job.url, cover, source.track, source.year, source.trackCount)
        val pick = engine.inspector.resolver.picks(track, 1).firstOrNull() ?: throw IllegalStateException("Couldn't find “${source.title}” on YouTube Music.")
        if (engine.stopped(id)) throw Stopped()
        val matchedUrl = Providers.validateUrl(pick.match.candidate.url)
        return engine.mutate(id, persist = true) {
            if (!it.status.active) null
            else it.copy(source = source, job = it.job.copy(url = matchedUrl, phase = null, provider = "YouTube Music", thumbnail = cover,
                match = pick.toMusicMatch(it.job.match?.source ?: Providers.musicSource(record.job.url) ?: "spotify", record.job.url)))
        } ?: throw Stopped()
    }

    /** Runs yt-dlp once. Returns null on success, else the raw failure text. */
    private fun execute(record: JobRecord, attempt: Attempt): String? {
        val id = record.id
        val template = record.outputTemplate ?: error("Output was not reserved.")
        val settings = engine.settings.value
        var info = if (attempt.useInfo) engine.inspector.freshInfo(record.downloadUrl) else null
        val cover = attempt.sourceCover?.takeIf { attempt.cover && it.isFile }
        var patched: File? = null
        if (info != null && cover != null) {
            try {
                val target = File(cover.parentFile, "${record.id}.info.json")
                target.writeText(CoverArt.patchedInfo(info.readText(), android.net.Uri.fromFile(cover).toString()))
                patched = target
                info = target
            } catch (_: Exception) { File(cover.parentFile, "${record.id}.info.json").delete() }
        }
        val request = if (info != null) YoutubeDLRequest(emptyList<String>()).apply { addOption("--load-info-json", info.absolutePath) } else YoutubeDLRequest(record.downloadUrl)
        if (patched != null) request.addOption("--enable-file-urls")
        engine.useEngineCache(request)
        val youtube = Pacing.isYouTube(record.job.url, record.job.match != null)
        val args = buildList {
            addAll(ArgsPolicy.output(template))
            addAll(ArgsPolicy.download(record.format, if (attempt.cover) record.options else record.options.copy(embedThumbnail = false), youtube, engine.toolsUpdater.mutagen))
            addAll(Pacing.requestArgs(youtube))
            addAll(ArgsPolicy.reliability(settings.fragments))
            if (attempt.aria2c) addAll(ArgsPolicy.aria2c(engine.toolsUpdater.caCertificate))
            if (record.options.embedMetadata) addAll(ArgsPolicy.metadata(if (record.source == null) record.job.title else null))
            record.source?.let { addAll(ArgsPolicy.tags(it.copy(track = it.track))) }
        }
        Log.i(TAG, "${id.take(8)} run attempt=$attempt args=${args.joinToString(" ") { listOfNotNull(it.name, it.value?.take(80)).joinToString(" ") }}")
        args.forEach { if (it.value == null) request.addOption(it.name) else request.addOption(it.name, it.value) }
        val cookies = if (record.job.signedIn != null) engine.cookieSnapshot() else null
        cookies?.let { request.addOption("--cookies", it.absolutePath) }
        val parser = ProgressParser(record.job.expectedSize)
        val process = "dl-$id-${System.nanoTime()}"
        if (!engine.registerProcess(id, process)) { cookies?.delete(); throw Stopped() }
        val tail = ArrayDeque<String>()
        try {
            val response = ToolsUpdater.reading { YoutubeDL.getInstance().execute(request, process) { _, _, line ->
                synchronized(tail) { tail.addLast(line); if (tail.size > 40) tail.removeFirst() }
                if (line.isNotBlank() && !line.startsWith(ArgsPolicy.PROGRESS_PREFIX) && !aria2Noise.containsMatchIn(line)) Log.i(TAG, "${id.take(8)} ${line.take(220)}")
                if (parser.accept(line)) engine.progress(id, parser.snapshot)
                if (engine.stopped(id)) YoutubeDL.getInstance().destroyProcessById(process)
            } }
            if (engine.stopped(id)) throw Stopped()
            val folder = File(template).parentFile!!
            val file = response.out.lineSequence().map { File(it.trim()) }
                .lastOrNull { it.isAbsolute && it.isFile && it.canonicalPath.startsWith(folder.canonicalPath + File.separator) }
                ?: return "Could not locate the downloaded file.\n" + response.err
            // A converted size: the download becomes the smaller copy before anything is saved or published.
            (record.format as? FormatRequest.Video)?.scaleHeight?.let { convertSmaller(id, file, it, record.job.duration) }
            // Tags travel with the job; they only reach the library item while "Import tags" is on.
            val tags = if (settings.library.importTags) TagLogic.fromJson(response.out.lineSequence().firstOrNull { it.startsWith(ArgsPolicy.TAGS_PREFIX) }?.removePrefix(ArgsPolicy.TAGS_PREFIX)) else emptyList()
            engine.mutate(id, persist = true) {
                if (!it.status.active) null
                else it.copy(job = it.job.copy(status = JobStatus.PROCESSING, phase = "Saving to your library", file = file.absolutePath,
                    fileSize = file.length(), percent = 99f, speed = null, eta = null, tags = tags))
            } ?: throw Stopped()
            return null
        } catch (stopped: Stopped) {
            throw stopped
        } catch (error: Exception) {
            if (engine.stopped(id)) throw Stopped()
            val lines = synchronized(tail) { tail.joinToString("\n") }
            return listOfNotNull(error.message, lines).joinToString("\n")
        } finally {
            engine.unregisterProcess(id)
            cookies?.delete()
            patched?.delete()
        }
    }

    private fun finish(id: String) = EngineAfterSteps(engine, id).finish()

    /**
     * Converts the finished download to the chosen smaller size ([ConvertStep]). A failure keeps the download, fails the job with a plain
     * message (no retry: the same conversion would fail the same way) and stops this run; a cancel stops it quietly.
     */
    private fun convertSmaller(id: String, file: File, height: Int, duration: Double?) {
        engine.mutate(id) { if (!it.status.active) null else it.copy(job = it.job.copy(status = JobStatus.PROCESSING, percent = 0f, phase = ConvertStep.PHASE, speed = null, eta = null)) }
        try {
            ConvertStep.run(engine.mediaTools, file, height, duration, { engine.stopped(id) }) { percent ->
                engine.progress(id, ProgressParser.Snapshot(percent.toFloat(), null, null, null, null, ConvertStep.PHASE, true))
            }
        } catch (failed: ConvertFailed) {
            engine.fail(id, ConvertStep.FAILED, failed.raw)
            throw Stopped()
        } catch (_: ConvertCancelled) {
            throw Stopped()
        }
    }

    companion object {
        private const val TAG = "KeepfinRunner"
        /** aria2c summary tables repeat every second; they only feed the progress parser. */
        private val aria2Noise = Regex("^(\\[#|\\s*\\*\\*\\* Download Progress|=====|-----|FILE:)")
    }
}
