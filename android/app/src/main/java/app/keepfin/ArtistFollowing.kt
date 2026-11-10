package app.keepfin

import android.util.Log
import app.keepfin.core.home.FeedRelease
import app.keepfin.core.home.ReleaseFeed
import app.keepfin.core.music.ArtistReleaseItem
import app.keepfin.core.music.ArtistReleases
import app.keepfin.core.music.MusicMatching
import app.keepfin.core.music.MusicVideos
import app.keepfin.core.music.VideoCandidate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** An artist the person follows. [seen] are the release links already read (the first check only records them). */
data class FollowedArtist(
    val id: String,
    val name: String,
    /** The folded first credited artist ([ReleaseFeed.artistKey]); one follow per key. */
    val key: String,
    /** The artist's YouTube Music channel page once resolved (the check reads its channel id); null until the first check finds it. */
    val url: String? = null,
    val checkedOnce: Boolean = false,
    val seen: List<String> = emptyList(),
    val checkedAt: Long? = null,
    val error: String? = null,
)

/** A release found for a followed artist; [id] is its link. [foundAt] is when Keepfin first saw it. */
data class ArtistRelease(
    val id: String,
    val artistId: String,
    val title: String,
    val kind: String?,
    val url: String,
    val thumbnail: String?,
    val foundAt: Long,
    val kept: Boolean = false,
)

/**
 * [seenReleases] are the release ids the person has seen (the x on Home's card, Mark all seen): the card returns only for newer ones.
 * Older files without the list load as empty.
 */
data class FollowingState(
    val artists: List<FollowedArtist> = emptyList(),
    val releases: List<ArtistRelease> = emptyList(),
    val checking: Boolean = false,
    val seenReleases: List<String> = emptyList(),
) {
    fun isFollowing(name: String?): Boolean = ReleaseFeed.artistKey(name)?.let { key -> artists.any { it.key == key } } == true
    fun artistOf(name: String?): FollowedArtist? = ReleaseFeed.artistKey(name)?.let { key -> artists.firstOrNull { it.key == key } }
    companion object { val NONE: StateFlow<FollowingState> = MutableStateFlow(FollowingState()).asStateFlow() }
}

/** What a check of followed artists needs from the engine (a small fake stands in for tests). */
internal interface FollowingHost {
    /** The artist's YouTube Music channel id (UC...), or null when no result is surely this artist. Throws when the search fails. */
    fun resolveChannel(name: String, key: String): String?
    /** The artist's latest releases, newest first: one request to the artist's YouTube Music page by channel id. Throws when it cannot be read. */
    fun listReleases(channelId: String): List<ArtistReleaseItem>
    /** Queues every track of the release link through the normal download path with the person's defaults; returns how many were queued. */
    fun keep(url: String): Int
    fun metered(): Boolean
}

internal interface FollowingStore {
    fun read(): String?
    fun write(text: String)
}

internal class FileFollowingStore(private val file: File) : FollowingStore {
    override fun read(): String? = try { if (file.isFile) file.readText() else null } catch (_: Exception) { null }
    override fun write(text: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) { file.delete(); check(temp.renameTo(file)) { "following not saved" } }
    }
}

/**
 * Followed artists and their new releases (Home's "New from artists you follow"). Reuses the follow check's network path: the artist's channel is found
 * with the YouTube Music search (never guessed) and its releases are read from the artist's YouTube Music page (the shared core's [ArtistReleases]). The first check of an artist records what
 * already exists; later checks add what is new, dated by when it was found. Nothing downloads except through [keep], one release per tap.
 */
internal class ArtistFollowing(
    private val host: FollowingHost,
    private val store: FollowingStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val flow = MutableStateFlow(load())
    val state: StateFlow<FollowingState> = flow.asStateFlow()

    /** Follows [name]; the same artist (any spelling) twice is one follow. Null when the name has no artist in it. */
    fun follow(name: String): FollowedArtist? = synchronized(lock) {
        val clean = name.trim()
        val key = ReleaseFeed.artistKey(clean) ?: return null
        flow.value.artists.firstOrNull { it.key == key }?.let { return it }
        val artist = FollowedArtist(UUID.randomUUID().toString(), clean.removeSuffix(" - Topic").trim(), key)
        commit(flow.value.copy(artists = flow.value.artists + artist))
        artist
    }

    fun unfollow(id: String) = synchronized(lock) {
        val now = flow.value
        if (now.artists.none { it.id == id }) return
        val releases = now.releases.filterNot { it.artistId == id }
        commit(now.copy(artists = now.artists.filterNot { it.id == id }, releases = releases, seenReleases = pruned(now.seenReleases, releases)))
    }

    /** The x on Home's card and "Mark all seen": every release now listed counts as seen, so the card returns only for newer ones. */
    fun markSeen() = synchronized(lock) {
        val now = flow.value
        val feed = now.releases.map { r -> FeedRelease(r.id, now.artists.firstOrNull { it.id == r.artistId }?.key ?: "", r.title, r.foundAt, r.kept) }
        commit(now.copy(seenReleases = ReleaseFeed.markSeen(feed, now.seenReleases.toSet(), clock())))
    }

    /** The seen list never outgrows the releases still known. */
    private fun pruned(seen: List<String>, releases: List<ArtistRelease>): List<String> {
        val known = releases.mapTo(HashSet()) { it.id }
        return seen.filter { it in known }
    }

    /** True when any artist is followed (the hourly scan skips the engine otherwise). */
    fun any(): Boolean = flow.value.artists.isNotEmpty()

    fun due(): List<FollowedArtist> = flow.value.artists.filter { it.checkedAt == null || clock() - it.checkedAt >= CHECK_EVERY_MS }

    /** Checks the due artists (not on a metered network), or [id] now. Blocking: off the main thread. Returns how many new releases were found. */
    fun check(id: String?, manual: Boolean): Int {
        val wanted = if (id != null) flow.value.artists.filter { it.id == id } else if (manual) flow.value.artists else if (host.metered()) emptyList() else due()
        var found = 0
        synchronized(lock) { commitChecking(true) }
        try { for (artist in wanted) found += checkOne(artist.id) } finally { synchronized(lock) { commitChecking(false) } }
        return found
    }

    private fun checkOne(id: String): Int {
        val artist = flow.value.artists.firstOrNull { it.id == id } ?: return 0
        val now = clock()
        var channel = artist.url?.let(ArtistReleases::channelId)
        if (channel == null) {
            channel = try { host.resolveChannel(artist.name, artist.key) } catch (error: Exception) {
                warn("artist search failed", error)
                return fail(id, "Keepfin couldn't reach YouTube Music right now. It will try again later.")
            } ?: return fail(id, "Keepfin couldn't find ${artist.name} on YouTube Music.")
        }
        val url = ArtistReleases.channelUrl(channel)
        val found = try { host.listReleases(channel).take(CAP) } catch (error: Exception) {
            warn("release listing failed", error)
            return fail(id, CHECK_FAILED, url)
        }
        val releases = found.associateBy { it.link }
        val freshIds = ReleaseFeed.fresh(artist.seen.toSet(), releases.keys.toList(), firstCheck = !artist.checkedOnce)
        val added = freshIds.map { link ->
            val release = releases.getValue(link)
            ArtistRelease(link, id, release.title, release.kind, link, release.thumbnail, now)
        }
        synchronized(lock) {
            val cur = flow.value
            if (cur.artists.none { it.id == id }) return 0
            val keep = cur.releases.filter { it.foundAt >= now - PRUNE_MS }
            val merged = (added + keep).distinctBy { it.id }
            commit(cur.copy(
                artists = cur.artists.map { if (it.id == id) it.copy(url = url, checkedOnce = true, seen = (it.seen + releases.keys).distinct(), checkedAt = now, error = null) else it },
                releases = merged, seenReleases = pruned(cur.seenReleases, merged),
            ))
        }
        return added.size
    }

    private fun fail(id: String, message: String, url: String? = null): Int {
        synchronized(lock) {
            val cur = flow.value
            commit(cur.copy(artists = cur.artists.map { if (it.id == id) it.copy(checkedAt = clock(), error = message, url = url ?: it.url) else it }))
        }
        return 0
    }

    /** Keeps a release: downloads it, and only then marks it kept (a failure leaves it in the feed). Blocking. */
    fun keep(releaseId: String): Int {
        val release = flow.value.releases.firstOrNull { it.id == releaseId } ?: return 0
        val queued = host.keep(release.url)
        synchronized(lock) { commit(flow.value.copy(releases = flow.value.releases.map { if (it.id == releaseId) it.copy(kept = true) else it })) }
        return queued
    }

    private fun commitChecking(on: Boolean) { if (flow.value.checking != on) flow.value = flow.value.copy(checking = on) }

    private fun commit(next: FollowingState) {
        flow.value = next
        try { store.write(encode(next)) } catch (error: Exception) { warn("following not saved", error) }
    }

    private fun load(): FollowingState = store.read()?.let { runCatching { decode(it) }.getOrNull() } ?: FollowingState()

    companion object {
        private const val TAG = "KeepfinFollow"

        /** A log line; plain JVM tests have no Android log, and a lost line must never break a check. */
        private fun warn(message: String, error: Throwable) { try { Log.w(TAG, message, error) } catch (_: Throwable) {} }
        const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L
        const val PRUNE_MS = 60 * 24 * 60 * 60 * 1000L
        private const val CAP = 200

        /** What a failed check of one artist says: a plain sentence, never the raw reason (the log holds that) or a channel id. */
        const val CHECK_FAILED = "Keepfin couldn't check this artist right now. It will try again later."

        /** The channel id of the search-result artist that is surely [key] (the shared core decides); null rather than a guess. */
        fun pickChannel(candidates: List<VideoCandidate>, key: String): String? =
            ReleaseFeed.pickChannel(candidates.flatMap { it.artists }.map { it.name to it.browseId }, key)

        fun encode(state: FollowingState): String = JSONObject()
            .put("v", 1)
            .put("artists", JSONArray(state.artists.map { a ->
                JSONObject().put("id", a.id).put("name", a.name).put("key", a.key).put("url", a.url ?: JSONObject.NULL).put("checkedOnce", a.checkedOnce)
                    .put("seen", JSONArray(a.seen)).put("checkedAt", a.checkedAt ?: JSONObject.NULL).put("error", a.error ?: JSONObject.NULL)
            }))
            .put("releases", JSONArray(state.releases.map { r ->
                JSONObject().put("id", r.id).put("artistId", r.artistId).put("title", r.title).put("kind", r.kind ?: JSONObject.NULL).put("url", r.url)
                    .put("thumbnail", r.thumbnail ?: JSONObject.NULL).put("foundAt", r.foundAt).put("kept", r.kept)
            }))
            .put("seenReleases", JSONArray(state.seenReleases)).toString()

        fun decode(text: String): FollowingState {
            val root = JSONObject(text)
            fun JSONObject.str(name: String): String? = if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
            val artists = root.optJSONArray("artists")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
                    // the first follow check read a channel's releases tab, which most channels lack: such an artist was never read, so it is checked afresh
                    val legacy = o.str("url")?.let(ArtistReleases::isLegacyAddress) == true
                    FollowedArtist(o.getString("id"), o.getString("name"), o.getString("key"), o.str("url"), o.optBoolean("checkedOnce"),
                        o.optJSONArray("seen")?.let { s -> (0 until s.length()).map { s.getString(it) } } ?: emptyList(),
                        if (o.isNull("checkedAt") || legacy) null else o.optLong("checkedAt"), if (legacy) null else o.str("error"))
                }
            } ?: emptyList()
            val releases = root.optJSONArray("releases")?.let { arr ->
                (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
                    ArtistRelease(o.getString("id"), o.getString("artistId"), o.getString("title"), o.str("kind"), o.getString("url"), o.str("thumbnail"), o.getLong("foundAt"), o.optBoolean("kept"))
                }
            } ?: emptyList()
            val seen = root.optJSONArray("seenReleases")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
            return FollowingState(artists, releases, seenReleases = seen)
        }
    }
}

/** The artist follow on the real engine. */
internal class EngineFollowingHost(private val engine: MediaEngine) : FollowingHost {
    override fun resolveChannel(name: String, key: String): String? {
        val query = MusicVideos.query(listOf(name), "")
        val answer = MusicResolver.post(MusicMatching.songsApiUrl, MusicMatching.songsApiHeaders, MusicVideos.searchBody(query))
        return ArtistFollowing.pickChannel(MusicVideos.parse(answer), key)
    }

    override fun listReleases(channelId: String): List<ArtistReleaseItem> {
        val body = checkNotNull(ArtistReleases.browseBody(channelId)) { "not a channel id" }
        return ArtistReleases.parse(MusicResolver.post(ArtistReleases.browseUrl, MusicMatching.songsApiHeaders, body))
    }

    override fun metered() = engine.follow.metered()

    override fun keep(url: String): Int {
        val session = "keep-${UUID.randomUUID()}"
        try {
            val listing = engine.discover(url, session, 1, 500) { }
            val entries = listing.entries.filter { it.unavailable == null }
            check(entries.isNotEmpty()) { listing.error ?: "Keepfin couldn't read this release right now." }
            val settings = engine.settings.value
            val music = Providers.defaultKind(url) == "audio"
            val preset = if (music && !settings.defaultPreset.wire.startsWith("audio-")) DownloadPreset.MP3 else settings.defaultPreset
            val group = JobGroup(id = UUID.randomUUID().toString(), title = listing.title ?: "Release", url = url, uploader = entries.firstNotNullOfOrNull { it.uploader ?: it.artist },
                thumbnail = entries.firstNotNullOfOrNull { it.thumbnail }, kind = "album")
            // the same "music videos" setting the collection box starts from: a song kept as audio also gets its video where there is one
            return engine.enqueueEntries(session, entries, preset, settings.options, group, null, null, music && settings.keepMusicVideos, null)
        } finally { engine.inspector.dropSession(session) }
    }
}
