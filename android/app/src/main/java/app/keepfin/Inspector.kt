package app.keepfin

import android.os.SystemClock
import app.keepfin.core.errors.Errors
import app.keepfin.core.formats.Title
import app.keepfin.core.gallery.Gallery
import app.keepfin.core.gallery.GalleryRead
import app.keepfin.core.formats.Unavailable
import app.keepfin.core.music.Match
import app.keepfin.core.signin.SignIn
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Inspection and collection discovery. Single items are inspected once with `-J`; the raw info JSON is cached so
 * downloads can start with `--load-info-json` instead of a second extraction. Collections are discovered with
 * bounded flat pages streamed line by line. Cookie changes bump the epoch, fencing in-flight results.
 */
internal class Inspector(private val engine: MediaEngine) {
    data class Authorized(val entry: PlaylistEntry, val source: SourceTags?)

    private class Session(val url: String, val epoch: Int) {
        var title: String? = null
        val entries = LinkedHashMap<String, Authorized>()
        val queued = HashSet<String>()
        var truncated = false
        var limit = 0
    }

    private val lock = Any()
    private val epoch = AtomicInteger()
    private val cache = LinkedHashMap<String, Pair<InspectResult, Long>>()
    private val sessions = LinkedHashMap<String, Session>()
    private val infoDir = File(engine.context.cacheDir, "inspect-info")
    val resolver = MusicResolver(::searchFlat, chosen = engine.musicChoices::get)

    fun currentEpoch() = epoch.get()

    fun invalidate() {
        epoch.incrementAndGet()
        synchronized(lock) {
            cache.clear()
            sessions.keys.forEach { engine.metadata.cancel(it) }
            sessions.clear()
        }
        infoDir.deleteRecursively()
    }

    private fun key(url: String) = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Raw info JSON from a recent inspection (< 30 minutes) for `--load-info-json`. */
    fun freshInfo(url: String): File? = File(infoDir, key(url) + ".json").takeIf { it.isFile && System.currentTimeMillis() - it.lastModified() < INFO_TTL_MS }

    /** The cached info JSON of [url] whatever its age (a long download outlives the 30 minutes): only for reading chapters after a download. */
    fun anyInfo(url: String): File? = File(infoDir, key(url) + ".json").takeIf { it.isFile }

    /** Cached info for [url], extracting it first when missing (null if that fails: callers fall back to a plain run). */
    fun ensureInfo(url: String): File? {
        freshInfo(url)?.let { return it }
        // YouTube answers intermittently ("The page needs to be reloaded"); a couple of spaced tries are cheap, then the caller falls back.
        repeat(3) { attempt ->
            try { inspectMedia(url, url, "prep-${UUID.randomUUID()}", 45_000); freshInfo(url)?.let { return it } }
            catch (error: Exception) { if (ErrorMessages.throttled(error.message)) return null }
            if (attempt < 2) Thread.sleep(3_000)
        }
        return null
    }

    /** One extraction of [url] that fills the info cache. Null on success, else the raw failure text (never throws). */
    fun extract(url: String): String? = try {
        inspectMedia(url, url, "prep-${UUID.randomUUID()}", 30_000); null
    } catch (error: Exception) { error.message ?: "Inspection failed." }

    fun forgetInfo(url: String) { File(infoDir, key(url) + ".json").delete() }

    private fun request(url: String, cookies: File?): YoutubeDLRequest = YoutubeDLRequest(url).apply {
        addOption("--ignore-config"); addOption("--no-warnings")
        cookies?.let { addOption("--cookies", it.absolutePath) }
        engine.useEngineCache(this)
    }

    /** Whether the inspection running on this thread uses Keepfin's sign-in: requests go signed out first and the retry turns it on. */
    private val signedIn = ThreadLocal.withInitial { false }

    private fun <T> withCookies(block: (File?) -> T): T {
        val snapshot = if (signedIn.get()) engine.cookieSnapshot() else null
        try { return block(snapshot) } finally { snapshot?.delete() }
    }

    fun inspect(input: String, session: String, timeoutMs: Long = 45_000): InspectResult {
        val url = Providers.validateUrl(input)
        synchronized(lock) {
            cache[url]?.takeIf { SystemClock.elapsedRealtime() - it.second < CACHE_MS }?.let { return it.first }
        }
        val started = epoch.get()
        val result = try { inspectOnce(url, session, timeoutMs) } catch (error: Exception) {
            if (engine.metadata.cancelled(session)) throw error
            // a site that asks for a sign-in gets one try with Keepfin's own; the media remembers it, so the download goes straight to it
            val source = engine.signInFor(url, error.message.orEmpty(), alreadySigned = false, signInWall = error is SignInNeeded) ?: throw error
            signedIn.set(true)
            try { inspectOnce(url, session, timeoutMs).let { if (it is InspectResult.Media) InspectResult.Media(it.media.copy(signedIn = SignIn.notice(source))) else it } }
            finally { signedIn.set(false) }
        }
        check(started == epoch.get()) { "Cookies changed during inspection. Inspect this link again." }
        synchronized(lock) {
            while (cache.size >= 50) cache.remove(cache.keys.first())
            cache[url] = result to SystemClock.elapsedRealtime()
        }
        return result
    }

    private fun inspectOnce(url: String, session: String, timeoutMs: Long): InspectResult {
        return when {
            Providers.musicSource(url) != null -> inspectMusic(url, session, timeoutMs)
            Providers.isGalleryHost(url) -> inspectGalleryHost(url, session, timeoutMs)
            Providers.isCollectionUrl(url) -> InspectResult.Collection(url, null)
            else -> try { inspectMedia(url, url, session, timeoutMs) } catch (error: Exception) {
                if (engine.metadata.cancelled(session)) throw error
                when {
                    Restriction.isUnavailable(error.message) -> inspectRestricted(url, session, timeoutMs, error)
                    Errors.suggestsGallery(error.message.orEmpty()) -> inspectPhotosAfter(url, session, error)
                    else -> throw error
                }
            }
        }
    }

    private fun inspectMedia(url: String, cacheKey: String, session: String, timeoutMs: Long): InspectResult = withCookies { cookies ->
        val request = request(url, cookies)
        request.addOption("--dump-single-json"); request.addOption("--no-playlist")
        request.addOption("--flat-playlist"); request.addOption("--playlist-end", "1")
        val text = engine.metadata.execute(request, session, timeoutMs, engine.toolsUpdater::ensureInit)
        val info = JSONObject(text)
        if (Formats.isCollection(info)) return@withCookies InspectResult.Collection(url, info.optString("title").takeIf { it.isNotBlank() })
        SizeFiller.fill(info)
        val media = Formats.mediaInfo(url, info, single = true)
        try {
            infoDir.mkdirs()
            val target = File(infoDir, key(cacheKey) + ".json")
            val temporary = File(infoDir, target.name + ".tmp")
            temporary.writeText(text)
            temporary.renameTo(target)
        } catch (_: Exception) { /* The info cache only speeds up downloads. */ }
        InspectResult.Media(media)
    }

    /** A post the site shows signed out, read from the download engine's pages; null for anything else so the usual route goes on. */
    private fun inspectPublic(url: String, session: String): InspectResult? =
        readPublic(url, session)?.let { InspectResult.Media(PhotoPosts.media(it, url)) }

    /** The signed-out read of [url] under [session] (no cookies); null when it fails or is not a post. Throws only when [session] was cancelled. */
    fun readPublic(url: String, session: String): GalleryRead.Post? {
        val request = request(url, null).also { r -> PublicPhotos.OPTIONS.forEach { r.addOption(it) } }
        val output = try { engine.metadata.executeAll(request, session, PublicPhotos.TIMEOUT_MS, engine.toolsUpdater::ensureInit) }
            catch (error: Exception) { if (engine.metadata.cancelled(session)) throw error; return null }
        return PublicPhotos.read(output, url)
    }

    /** A photo post: the gallery engine lists its files and the shared core reads the listing. */
    private fun inspectPhotos(url: String, session: String): InspectResult = withCookies { cookies ->
        val listing = engine.galleryEngine.inspect(url, cookies?.absolutePath, cancelled = { engine.metadata.cancelled(session) })
        check(!engine.metadata.cancelled(session)) { "Inspection cancelled. Ready items are kept." }
        when (val read = Gallery.read(listing, url)) {
            is GalleryRead.Post -> InspectResult.Media(PhotoPosts.media(read, url))
            is GalleryRead.Failed -> throw PhotoPosts.failure(read.message, url)
            GalleryRead.Empty -> throw IllegalStateException("This link has no downloadable video or images.")
        }
    }

    /** Sites that mostly carry images: the gallery engine first, then the download engine (Imgur gifv and Tumblr videos are videos). */
    private fun inspectGalleryHost(url: String, session: String, timeoutMs: Long): InspectResult = try {
        inspectPhotos(url, session)
    } catch (error: Exception) {
        if (engine.metadata.cancelled(session) || error is SignInNeeded) throw error
        try { inspectMedia(url, url, session, timeoutMs) } catch (_: Exception) { throw error }
    }

    /** The download engine found no video at [url]: its photos, if any. A sign-in wall there is a better answer than "no video". */
    private fun inspectPhotosAfter(url: String, session: String, original: Exception): InspectResult = try {
        inspectPhotos(url, session)
    } catch (error: Exception) {
        if (engine.metadata.cancelled(session) || error is SignInNeeded) throw error
        throw original
    }

    /**
     * "Video unavailable" for a song YouTube won't play anonymously in this region: the watch page proves it, and another upload
     * of the same recording is inspected instead. The result keeps the original title, artist and cover.
     */
    private fun inspectRestricted(url: String, session: String, timeoutMs: Long, original: Exception): InspectResult {
        val id = Restriction.videoId(url) ?: throw original
        val probe = Restriction.fetch(id)
        if (Restriction.verdict(probe) != Restriction.Verdict.RESTRICTED || probe == null) throw original
        if (!Restriction.isMusicLike(url, probe.author)) throw IllegalStateException(Restriction.OTHER_MESSAGE)
        check(!engine.metadata.cancelled(session)) { "Inspection cancelled. Ready items are kept." }
        val track = Restriction.sourceTrack(probe, url, null, null)
        val picks = resolver.alternates(track, setOf(id))
        for (pick in picks) {
            check(!engine.metadata.cancelled(session)) { "Inspection cancelled. Ready items are kept." }
            val alternate = try { inspectMedia(Providers.validateUrl(pick.candidate.url), pick.candidate.url, session, timeoutMs) as? InspectResult.Media }
                catch (error: Exception) { if (engine.metadata.cancelled(session) || error.message?.contains("cancelled") == true) throw error; null }
                ?: continue
            return InspectResult.Media(alternate.media.copy(
                url = url, webpageUrl = url, title = track.title, uploader = track.artist ?: alternate.media.uploader,
                duration = track.duration ?: alternate.media.duration, thumbnail = track.cover ?: alternate.media.thumbnail,
                music = MusicMetadata(track.artist, null, track.title, null), playlistUrl = null,
                match = MusicMatch(Restriction.SOURCE, url, pick.candidate.url, pick.candidate.title, pick.confidence),
            ))
        }
        throw IllegalStateException(Restriction.MUSIC_MESSAGE)
    }

    private fun inspectMusic(url: String, session: String, timeoutMs: Long): InspectResult {
        val collection = resolver.resolve(url, 1, 200)
        check(!engine.metadata.cancelled(session)) { "Inspection cancelled. Ready items are kept." }
        if (collection.kind != "track") return InspectResult.Collection(url, collection.title)
        val track = collection.tracks.firstOrNull() ?: error("This music link has no playable track.")
        // The recordings come lazily: a later one is looked for only when the earlier ones cannot be inspected.
        var failure: Exception? = null
        var chosen: Pick? = null
        var matched: InspectResult.Media? = null
        var found = false
        for (candidate in resolver.picks(track, 3)) {
            found = true
            check(!engine.metadata.cancelled(session)) { "Inspection cancelled. Ready items are kept." }
            // A transient provider error on one candidate ("page needs to be reloaded") falls through to the next-best match.
            try {
                matched = inspectMedia(Providers.validateUrl(candidate.match.candidate.url), candidate.match.candidate.url, session, timeoutMs) as? InspectResult.Media
                if (matched != null) { chosen = candidate; break }
            } catch (error: Exception) {
                if (engine.metadata.cancelled(session) || error.message?.contains("cancelled") == true) throw error
                failure = error
            }
        }
        if (!found) error("Couldn't find “${track.title}” on YouTube Music.")
        check(!engine.metadata.cancelled(session)) { "Inspection cancelled. Ready items are kept." }
        val match = chosen ?: throw (failure ?: IllegalStateException("The YouTube Music match is not a single track."))
        check(matched != null)
        val media = matched.media
        return InspectResult.Media(media.copy(
            url = url, webpageUrl = match.match.candidate.url, title = track.title, uploader = track.artist ?: media.uploader,
            duration = track.duration ?: media.duration, thumbnail = track.cover ?: media.thumbnail, provider = "YouTube Music",
            music = MusicMetadata(track.artist, track.album, track.title, track.year), playlistUrl = null,
            match = match.toMusicMatch(collection.source, url),
        ))
    }

    /** The engine's flat search of [target], reading [playlistEnd] entries; returns the raw JSON for the shared matcher to read. */
    private fun searchFlat(target: String, playlistEnd: Int): String = withCookies { cookies ->
        val request = YoutubeDLRequest(target).apply {
            addOption("--ignore-config"); addOption("--no-warnings")
            cookies?.let { addOption("--cookies", it.absolutePath) }
            engine.useEngineCache(this)
            addOption("--flat-playlist"); addOption("--dump-single-json"); addOption("--playlist-end", playlistEnd.toString())
        }
        engine.metadata.execute(request, "music-search-${UUID.randomUUID()}", 40_000, engine.toolsUpdater::ensureInit)
    }

    private fun entryUrl(entry: JSONObject): String? {
        val candidates = listOf(entry.optString("url"), entry.optString("webpage_url"))
        candidates.firstOrNull { it.startsWith("https://") || it.startsWith("http://") }?.let { return runCatching { Providers.validateUrl(it) }.getOrNull() }
        val id = entry.optString("id")
        if (entry.optString("ie_key").startsWith("Youtube") && id.matches(Regex("[A-Za-z0-9_-]{11}"))) return "https://www.youtube.com/watch?v=$id"
        return null
    }

    private fun thumbnail(entry: JSONObject): String? = Formats.thumbnail(entry)

    private fun session(session: String, url: String): Session = synchronized(lock) {
        sessions[session]?.let { existing ->
            require(existing.url == url) { "This playlist session belongs to another link." }
            return existing
        }
        while (sessions.size >= 8) { val oldest = sessions.keys.first(); engine.metadata.cancel(oldest); sessions.remove(oldest) }
        Session(url, epoch.get()).also { sessions[session] = it }
    }

    private fun authorize(session: Session, entry: PlaylistEntry, source: SourceTags?): Boolean = synchronized(lock) {
        check(session.epoch == epoch.get()) { "Cookies changed. Inspect the playlist again." }
        if (session.entries.containsKey(entry.url) || entry.url in session.queued) false
        else { session.entries[entry.url] = Authorized(entry, source); true }
    }

    fun discover(input: String, sessionId: String, start: Int, end: Int, onEntry: (PlaylistEntry) -> Unit): PlaylistDiscovery {
        val url = Providers.validateUrl(input)
        require(start >= 1 && end >= start && end - start < 1000) { "Choose a valid playlist range." }
        val session = session(sessionId, url)
        var state = "completed"
        var error: String? = null
        var truncated = false
        var rawFailure: String? = null
        if (Providers.musicSource(url) != null) {
            try {
                val collection = resolver.resolve(url, start, end)
                synchronized(lock) { session.title = collection.title }
                val tracks = if (collection.source == "deezer") collection.tracks else collection.tracks.drop(start - 1).take(end - start + 1)
                tracks.forEachIndexed { index, track ->
                    val entry = PlaylistEntry(track.url, track.title, track.artist, track.album, track.cover, track.artist, track.duration, start + index)
                    if (authorize(session, entry, track.tags())) onEntry(entry)
                }
                truncated = collection.total?.let { it > end } ?: (collection.source != "deezer" && collection.tracks.size > end)
            } catch (e: IllegalStateException) { if (e.message?.startsWith("Cookies changed") == true) throw e; state = "failed"; error = ErrorMessages.friendly(e.message) }
            catch (e: Exception) { state = "failed"; error = ErrorMessages.friendly(e.message) }
        } else for (pass in 0..1) {
            // signed out first; a page that failed asking for a sign-in (and loaded nothing) is read once more with Keepfin's own
            if (pass == 1) {
                if (rawFailure == null || session.entries.isNotEmpty() || engine.signInFor(url, rawFailure, alreadySigned = false) == null) break
                state = "completed"; error = null; truncated = false
            }
            signedIn.set(pass == 1)
            try { withCookies { cookies ->
            val request = request(url, cookies)
            request.addOption("--flat-playlist"); request.addOption("--lazy-playlist"); request.addOption("--dump-json")
            request.addOption("--playlist-start", start.toString()); request.addOption("--playlist-end", (end + 1).toString())
            var position = start - 1
            try {
                engine.metadata.execute(request, sessionId, PAGE_DEADLINE_MS, engine.toolsUpdater::ensureInit) { line ->
                    if (!line.trimStart().startsWith("{")) return@execute
                    val info = JSONObject(line)
                    position = info.optInt("playlist_index", position + 1).takeIf { it > position } ?: (position + 1)
                    if (position > end) { truncated = true; return@execute }
                    synchronized(lock) { if (session.title == null) session.title = (info.optString("playlist_title").ifBlank { info.optString("playlist") }).ifBlank { null } }
                    val entryUrl = entryUrl(info) ?: return@execute
                    val rawTitle = info.optString("title").take(2000).ifBlank { null }
                    val entryUploader = (info.optString("channel").ifBlank { info.optString("uploader") }).take(500).ifBlank { null }
                    val duration = info.optDouble("duration", 0.0).takeIf { it.isFinite() && it > 0 }
                    // private, deleted or gone entries stay in the list with the reason (never selected), as on the desktop
                    val unavailable = Unavailable.reason(rawTitle, entryUploader, duration, info.optString("ie_key") == "Youtube" || Providers.isYouTubeHost(entryUrl))
                    // Posts use their whole caption as the title: entries get the same clean title as single items.
                    val title = rawTitle?.takeIf { unavailable == null }?.let { Title.clean(it, entryUploader, Providers.name(entryUrl, info.optString("ie_key").ifBlank { null })) }
                    val entry = PlaylistEntry(entryUrl, title, null, null, thumbnail(info), entryUploader, duration, position, unavailable)
                    if (authorize(session, entry, null)) onEntry(entry)
                }
            } catch (e: Exception) {
                if (e.message?.startsWith("Cookies changed") == true) throw e
                val message = e.message.orEmpty()
                rawFailure = message
                state = if (message.contains("cancelled")) "cancelled" else "failed"
                error = if (message.contains("timed out")) "Discovery stopped after ${PAGE_DEADLINE_MS / 1000} s. Loaded items are kept; try Load more or retry."
                    else if (state == "cancelled") "Discovery cancelled. Loaded items are kept." else ErrorMessages.friendly(message)
            }
            } } finally { signedIn.set(false) }
        }
        check(session.epoch == epoch.get()) { "Cookies changed. Inspect the playlist again." }
        return synchronized(lock) {
            session.truncated = truncated; session.limit = maxOf(session.limit, end)
            val all = session.entries.values.map { it.entry }
            if (all.isEmpty() && state == "completed") { state = "failed"; error = "No downloadable items were found in this collection." }
            PlaylistDiscovery(sessionId, url, session.title, all.sortedBy { it.position }, state, error, session.limit, truncated)
        }
    }

    fun cancelDiscovery(session: String) = engine.metadata.cancel(session)

    /** What a discovery session found: the source link, its title and the keys of every discovered entry (queued, deselected or still pending). */
    class SessionInfo(val url: String, val title: String?, val keys: List<String>)

    fun sessionInfo(sessionId: String): SessionInfo? = synchronized(lock) {
        sessions[sessionId]?.let { s -> SessionInfo(s.url, s.title, (s.entries.keys + s.queued).map(CollectionLogic::itemKey).distinct()) }
    }

    /** Forgets a finished session (follow checks create a fresh one every time). */
    fun dropSession(sessionId: String) { synchronized(lock) { sessions.remove(sessionId) } }

    /** Entries that the session discovered and that are not queued yet; unknown URLs are skipped. */
    fun authorized(sessionId: String, urls: List<String>): List<Authorized> = synchronized(lock) {
        val session = sessions[sessionId] ?: return emptyList()
        check(session.epoch == epoch.get()) { "Cookies changed. Inspect the playlist again." }
        urls.distinct().mapNotNull { session.entries[it] }
    }

    fun consume(sessionId: String, urls: Collection<String>) = synchronized(lock) {
        sessions[sessionId]?.let { session -> urls.forEach { session.entries.remove(it); session.queued.add(it) } }
    }

    companion object {
        const val CACHE_MS = 10 * 60_000L
        const val INFO_TTL_MS = 30 * 60_000L
        const val PAGE_DEADLINE_MS = 60_000L
    }
}
