package app.keepfin

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import app.keepfin.net.withPeerPlayback
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject

/**
 * Background music and video playback (Media3 MediaLibraryService + ExoPlayer).
 *
 * UI contract:
 *  - Connect with [PlaybackClient.connect]; send `MediaItem`s built with [PlaybackItems.reference] (id only). The service resolves
 *    each id to a full item (uri, tags, cover, sidecar subtitles). Use setMediaItems/addMediaItem/addMediaItems(index, ...) for
 *    Play now / Play next / Add to queue; shuffle, repeat, speed and seeking are the standard Player calls.
 *  - Video: attach the controller to a PlayerSurface. When the video screen leaves, pause (or release the surface) unless
 *    picture-in-picture is active; the service keeps the song session alive but never plays video in the background.
 *  - Sleep timer, equalizer and bass boost: [PlaybackControls] (same process; state flows).
 *  - Progress is recorded into [LibraryApi.recordPlayback] about every 10 s, on pause, on transition and at the end.
 *  - The queue, index, position, shuffle and repeat survive restarts: the system's resumption request restores them paused.
 */
class PlaybackService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private var session: MediaLibrarySession? = null
    internal lateinit var effects: PlaybackEffects
    private val handler = Handler(Looper.getMainLooper())
    private val library: LibraryApi get() = (application as KeepfinApplication).engineApi.libraryApi
    private val settings: EngineSettings get() = (application as KeepfinApplication).engineApi.settings.value
    private val prefs by lazy { getSharedPreferences("playback-state", Context.MODE_PRIVATE) }

    private var sleepEndsAt = 0L
    private var sleepAtEnd = false
    private var lastRecordedId: String? = null
    /** The item playing is a song's music video: its time is not the song's, so nothing is recorded for it (no resume position, no play). */
    private var onMusicVideo = false

    private val ticker = object : Runnable {
        override fun run() {
            recordProgress(false)
            persist()
            handler.postDelayed(this, PROGRESS_MS)
        }
    }
    private val sleepTick = object : Runnable {
        override fun run() {
            if (sleepEndsAt <= 0) return
            val left = sleepEndsAt - System.currentTimeMillis()
            if (left <= 0) { cancelSleep(); player.pause(); return }
            PlaybackControls.publishSleep(SleepTimerState(true, left, false))
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .withPeerPlayback(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true).setWakeMode(C.WAKE_MODE_LOCAL).build()
        effects = PlaybackEffects(this) { PlaybackControls.publishEqualizer(it) }
        effects.attach(player.audioSessionId)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) = effects.attach(audioSessionId)
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // The previous item finished when playback moved on by itself.
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && !onMusicVideo) lastRecordedId?.let { library.recordPlayback(it, null, true) }
                lastRecordedId = mediaItem?.mediaId
                onMusicVideo = isMusicVideo(mediaItem)
                applyGain(mediaItem)
                if (sleepAtEnd && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) { cancelSleep(); player.pause() }
                persist()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(ticker)
                if (isPlaying) handler.postDelayed(ticker, PROGRESS_MS) else { recordProgress(false); persist() }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { recordProgress(true); if (sleepAtEnd) cancelSleep() }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // A song deleted or moved while it sat in the queue must skip itself, not leave the player stuck in an error state.
                val current = player.currentMediaItem ?: return
                // a music video that cannot be played (its file is gone or unreadable) never takes its song out of the queue: the song plays instead
                if (isMusicVideo(current)) {
                    val song = resolve(current.mediaId)
                    if (song != null) { player.replaceMediaItem(player.currentMediaItemIndex, song); player.prepare(); return }
                }
                val missing = !current.mediaId.startsWith("keepfin:") && library.item(current.mediaId) == null
                if (missing || error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND) {
                    Log.w(TAG, "skipping an unplayable queue item: ${error.errorCodeName}")
                    player.removeMediaItem(player.currentMediaItemIndex)
                    if (player.mediaItemCount > 0) player.prepare()
                }
            }
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = persist()
            override fun onRepeatModeChanged(repeatMode: Int) = persist()
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                // The queue became empty (the player was closed, the last song removed): the saved queue goes too, so the next start
                // does not bring back a player that was closed, and the service stops, so its media session (the card in the
                // phone's media controls) ends once the app lets go of it.
                if (player.mediaItemCount == 0) { forgetQueue(); stopSelf() } else persist()
            }
        })
        val provider = DefaultMediaNotificationProvider.Builder(this).setChannelId(CHANNEL).setChannelName(R.string.playback_channel).build()
        provider.setSmallIcon(R.drawable.ic_notification)
        // Tapping the system media card (lock screen, control center) opens Keepfin on the player, like the notification does.
        session = MediaLibrarySession.Builder(this, player, Callback()).setSessionActivity(sessionActivity(this)).build()
        setMediaNotificationProvider(provider)
        PlaybackControls.service = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // Music keeps playing after the app is swiped away; a paused or idle session stops the service.
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recordProgress(false); persist()
        if (PlaybackControls.service === this) PlaybackControls.service = null
        effects.release()
        session?.release(); session = null
        player.release()
        PlaybackControls.publishSleep(SleepTimerState())
        super.onDestroy()
    }

    // ---- sleep timer -------------------------------------------------------------------------------

    internal fun startSleepTimer(minutes: Int) {
        cancelSleep()
        if (minutes <= 0) return
        sleepEndsAt = System.currentTimeMillis() + minutes * 60_000L
        handler.post(sleepTick)
    }

    internal fun startSleepAtEndOfTrack() {
        cancelSleep()
        sleepAtEnd = true
        player.repeatMode = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else player.repeatMode
        PlaybackControls.publishSleep(SleepTimerState(true, 0L, true))
    }

    internal fun cancelSleep() {
        sleepEndsAt = 0L; sleepAtEnd = false
        handler.removeCallbacks(sleepTick)
        PlaybackControls.publishSleep(SleepTimerState())
    }

    // ---- bookkeeping -----------------------------------------------------------------------------

    private fun applyGain(item: MediaItem?) {
        val gain = item?.mediaMetadata?.extras?.takeIf { it.containsKey(PlaybackItems.EXTRA_GAIN) }?.getDouble(PlaybackItems.EXTRA_GAIN)
        val extras = item?.mediaMetadata?.extras
        // loudness applies to songs: audio, and a video kept in a music space (its own gain only, never an invented one)
        val audio = extras?.getString(PlaybackItems.EXTRA_KIND) == LibraryItemKind.AUDIO.wire || item?.mediaMetadata?.mediaType == MediaMetadata.MEDIA_TYPE_MUSIC
        player.volume = if (settings.library.normalize && audio) PlaybackRules.volumeFor(gain) else 1f
    }

    private fun isMusicVideo(item: MediaItem?) = item?.mediaMetadata?.extras?.getBoolean(PlaybackItems.EXTRA_MUSIC_VIDEO) == true

    private fun recordProgress(finished: Boolean) {
        val id = player.currentMediaItem?.mediaId ?: return
        if (id.startsWith("keepfin:") || isMusicVideo(player.currentMediaItem)) return
        val seconds = player.currentPosition.coerceAtLeast(0L) / 1000.0
        try { library.recordPlayback(id, if (finished) null else seconds, finished) } catch (error: Exception) { Log.w(TAG, "progress not recorded", error) }
    }

    private fun persist() {
        if (player.mediaItemCount == 0) return
        try {
            val ids = JSONArray().also { array -> for (i in 0 until player.mediaItemCount) array.put(player.getMediaItemAt(i).mediaId) }
            prefs.edit().putString(KEY_STATE, JSONObject().put("ids", ids).put("index", player.currentMediaItemIndex)
                .put("position", player.currentPosition.coerceAtLeast(0L)).put("shuffle", player.shuffleModeEnabled).put("repeat", player.repeatMode).toString()).apply()
        } catch (error: Exception) { Log.w(TAG, "queue not saved", error) }
    }

    private fun forgetQueue() {
        try { prefs.edit().remove(KEY_STATE).apply() } catch (error: Exception) { Log.w(TAG, "saved queue not cleared", error) }
    }

    /**
     * [watch]: Watch video asked for the picture, so a video kept in a music space is a video this time (its metadata and loudness).
     * [video]: the song's music video is wanted; a song that keeps one then plays that file (the same item id, a video item). A song whose
     * file is missing on this device plays as the song.
     */
    private fun resolve(id: String, watch: Boolean = false, video: Boolean = false): MediaItem? {
        val item = library.item(id) ?: return null
        val musicVideo = if (video && item.musicVideo && item.kind == LibraryItemKind.AUDIO) try { library.musicVideoUri(id) } catch (error: Exception) { null } else null
        return PlaybackItems.toMediaItem(this, item, if (item.kind == LibraryItemKind.VIDEO) library.subtitles(id) else emptyList(), asSong = !watch && isVideoSong(item), musicVideoUri = musicVideo)
    }

    // ---- browsing ----------------------------------------------------------------------------------

    private fun musicSpaces() = settings.library.spaces.filter { it.kind != SpaceKind.VIDEO }

    /** A video kept in a music space is a song ([app.keepfin.core.spaces.Songs.isVideoSong]). */
    private fun isVideoSong(item: LibraryItem) =
        app.keepfin.core.spaces.Songs.isVideoSong(item.kind.wire, settings.library.spaces.firstOrNull { it.id == item.space }?.kind?.wire)
    private fun isSong(item: LibraryItem) = item.kind == LibraryItemKind.AUDIO || isVideoSong(item)

    /** Auto and Bluetooth browse the library through bounded queries: at most [MAX_CHILDREN] rows of any list are read. */
    private fun children(parent: String): List<MediaItem> {
        val snapshot = library.library.value
        val browse = library.browse
        fun playable(list: List<LibraryItem>) = list.take(MAX_CHILDREN).map { PlaybackItems.toMediaItem(this, it, asSong = isVideoSong(it)) }
        fun query(space: String? = null, order: ItemOrder = ItemOrder.TITLE, favorite: Boolean = false, folder: String? = null, album: String? = null) =
            ItemQuery(spaces = listOfNotNull(space), songs = true, order = order, favorite = favorite, folder = folder, album = album)
        return when {
            !settings.library.enabled -> emptyList()
            parent == PlaybackItems.ROOT -> musicSpaces().map { PlaybackItems.folderItem(PlaybackItems.space(it.id), it.name) } +
                listOf(PlaybackItems.folderItem(PlaybackItems.PLAYLISTS, "Playlists"), PlaybackItems.folderItem(PlaybackItems.FAVORITES, "Favorites"),
                    PlaybackItems.folderItem(PlaybackItems.RECENT, "Recently added"))
            parent == PlaybackItems.FAVORITES -> playable(browse.top(query(order = ItemOrder.ADDED, favorite = true), MAX_CHILDREN))
            parent == PlaybackItems.RECENT -> playable(browse.top(query(order = ItemOrder.ADDED), MAX_CHILDREN))
            parent == PlaybackItems.PLAYLISTS -> snapshot.playlists.map { PlaybackItems.folderItem(PlaybackItems.playlist(it.id), it.name, "${it.itemIds.size} songs") }
            parent.startsWith("keepfin:playlist:") -> snapshot.playlists.firstOrNull { it.id == parent.removePrefix("keepfin:playlist:") }
                ?.let { p -> playable(browse.byIds(p.itemIds.distinct().take(MAX_CHILDREN)).filter(::isSong)) }.orEmpty()
            parent.startsWith("keepfin:space:") -> {
                val id = parent.removePrefix("keepfin:space:")
                listOf(PlaybackItems.folderItem(PlaybackItems.songs(id), "Songs"), PlaybackItems.folderItem(PlaybackItems.albums(id), "Albums"),
                    PlaybackItems.folderItem(PlaybackItems.folders(id), "Folders"))
            }
            parent.startsWith("keepfin:songs:") -> playable(browse.top(query(space = parent.removePrefix("keepfin:songs:")), MAX_CHILDREN))
            parent.startsWith("keepfin:albums:") -> {
                val space = parent.removePrefix("keepfin:albums:")
                browse.albums(ItemQuery(spaces = listOf(space)), null, MAX_CHILDREN).map { a ->
                    PlaybackItems.folderItem(PlaybackItems.album(space, a.routeKey), a.title, a.artist,
                        PlaybackItems.artwork(this, a.cover?.art), androidx.media3.common.MediaMetadata.MEDIA_TYPE_ALBUM)
                }
            }
            parent.startsWith("keepfin:album:") -> {
                val rest = parent.removePrefix("keepfin:album:")
                val space = rest.substringBefore(':')
                val key = rest.substringAfter(':')
                playable(browse.top(query(space = space, order = ItemOrder.TRACK, album = key), MAX_CHILDREN))
            }
            parent.startsWith("keepfin:folders:") -> {
                val space = parent.removePrefix("keepfin:folders:")
                browse.folders(space, "").map { PlaybackItems.folderItem(PlaybackItems.folder(space, it.path), it.path) }
            }
            parent.startsWith("keepfin:folder:") -> {
                val rest = parent.removePrefix("keepfin:folder:")
                val space = rest.substringBefore(':')
                val folder = rest.substringAfter(':')
                playable(browse.top(query(space = space, folder = folder), MAX_CHILDREN))
            }
            else -> emptyList()
        }
    }
    private inner class Callback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(PlaybackItems.folderItem(PlaybackItems.ROOT, "Keepfin"), params))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?):
            ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val all = children(parentId)
            val from = (page.toLong() * pageSize).coerceAtMost(all.size.toLong()).toInt()
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(all.subList(from, minOf(all.size, from + pageSize))), params))
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> {
            val item = if (mediaId == PlaybackItems.ROOT) PlaybackItems.folderItem(PlaybackItems.ROOT, "Keepfin") else resolve(mediaId)
            return Futures.immediateFuture(if (item != null) LibraryResult.ofItem(item, null) else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
        }

        /** Id-only items (and browse-tree ids) become playable items with full metadata. Folders expand to their songs. Items deleted meanwhile are dropped. */
        private fun expand(requested: MediaItem): List<MediaItem> = when {
            requested.localConfiguration != null -> listOf(requested)
            requested.mediaId.startsWith("keepfin:") -> children(requested.mediaId).filter { it.mediaMetadata.isPlayable == true }
            else -> listOfNotNull(resolve(requested.mediaId, watch = requested.requestMetadata.extras?.getBoolean(PlaybackItems.EXTRA_WATCH) == true,
                video = requested.requestMetadata.extras?.getBoolean(PlaybackItems.EXTRA_SIDE_VIDEO) == true))
        }

        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
            Futures.immediateFuture(mediaItems.flatMapTo(ArrayList()) { expand(it) })

        /**
         * Same expansion for a new queue, but the start index follows the item the user picked even when earlier items were deleted
         * (a stale index past the shortened queue would throw inside the player).
         */
        override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long):
            ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val out = ArrayList<MediaItem>()
            var start = C.INDEX_UNSET
            var position = startPositionMs
            mediaItems.forEachIndexed { index, requested ->
                val expanded = expand(requested)
                if (index == startIndex) { if (expanded.isNotEmpty()) start = out.size else position = 0L }
                out.addAll(expanded)
            }
            if (out.isEmpty()) return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(out, C.INDEX_UNSET, C.TIME_UNSET))
            if (start == C.INDEX_UNSET && startIndex != C.INDEX_UNSET) start = out.size.coerceAtMost(startIndex.coerceAtLeast(0)).coerceAtMost(out.lastIndex)
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(out, start, if (start == C.INDEX_UNSET) C.TIME_UNSET else position))
        }

        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val saved = prefs.getString(KEY_STATE, null)
            try {
                val json = JSONObject(saved ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume")))
                val array = json.getJSONArray("ids")
                val items = (0 until array.length()).mapNotNull { resolve(array.getString(it)) }
                if (items.isEmpty()) return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
                val index = json.optInt("index").coerceIn(0, items.lastIndex)
                handler.post { player.shuffleModeEnabled = json.optBoolean("shuffle"); player.repeatMode = json.optInt("repeat") }
                return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(items, index, json.optLong("position")))
            } catch (error: Exception) { return Futures.immediateFailedFuture(error) }
        }
    }

    companion object {
        /** The intent behind the media card tap: MainActivity (reused, never a new stack) with the open-player extra. */
        fun sessionActivityIntent(context: Context): android.content.Intent =
            android.content.Intent(context, MainActivity::class.java)
                .setFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)

        fun sessionActivity(context: Context): android.app.PendingIntent =
            android.app.PendingIntent.getActivity(
                context, REQUEST_SESSION_ACTIVITY, sessionActivityIntent(context),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private const val REQUEST_SESSION_ACTIVITY = 4107
        private const val TAG = "KeepfinPlayback"
        private const val CHANNEL = "keepfin-playback"
        private const val KEY_STATE = "queue"
        private const val PROGRESS_MS = 10_000L
        private const val MAX_CHILDREN = 500
    }
}

/** Connects to [PlaybackService]. Release the controller with `MediaController.releaseFuture` when the screen is done. */
object PlaybackClient {
    fun connect(context: Context): ListenableFuture<MediaController> {
        val app = context.applicationContext
        return MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
    }
}
