package app.keepfin.ui.player

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.session.MediaController
import app.keepfin.EqualizerState
import app.keepfin.LibraryItemKind
import app.keepfin.PlaybackClient
import app.keepfin.PlaybackControls
import app.keepfin.PlaybackItems
import app.keepfin.SleepTimerState
import app.keepfin.net.PeerPlayback
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/**
 * Production [PlayerPort]: a [MediaController] bound to [app.keepfin.PlaybackService]. The service keeps playing in the
 * background; this class only mirrors its state for Compose and forwards commands. Nothing is simulated.
 */
class ControllerPlayerPort(private val context: Context) : PlayerPort {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val uiFlow = MutableStateFlow(PlayerUi())
    private val positionFlow = MutableStateFlow(PlayPosition())
    private val tracksFlow = MutableStateFlow(MediaTracks())
    override val ui: StateFlow<PlayerUi> = uiFlow.asStateFlow()
    override val position: StateFlow<PlayPosition> = positionFlow.asStateFlow()
    override val tracks: StateFlow<MediaTracks> = tracksFlow.asStateFlow()
    override val sleepTimer: StateFlow<SleepTimerState> = PlaybackControls.sleepTimer
    override val equalizer: StateFlow<EqualizerState> = PlaybackControls.equalizer

    private var controller: MediaController? = null
    private var connecting: ListenableFuture<MediaController>? = null
    private val waiting = ArrayList<(MediaController) -> Unit>()
    private var ticker: Job? = null
    private var error: String? = null
    private var closing = false

    override val player: Player? get() = controller

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) || events.contains(Player.EVENT_TIMELINE_CHANGED)) error = null
            publish(player, rebuildQueue = true)
            if (events.containsAny(Player.EVENT_TRACKS_CHANGED, Player.EVENT_VIDEO_SIZE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) publishTracks(player)
            if (events.containsAny(Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_PLAYBACK_STATE_CHANGED, Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) publishPosition(player)
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) restartTicker(player)
            // the player was closed and is empty now: let go of the service so it ends with its media session (the next play reconnects)
            if (closing && player.mediaItemCount == 0) disconnect()
        }

        override fun onPlayerError(failure: PlaybackException) {
            val remote = controller?.currentMediaItem?.mediaMetadata?.extras?.getBoolean(PeerPlayback.EXTRA_REMOTE) == true
            val peerMessage = if (remote) PeerPlayback.takeFailure() else null
            error = peerMessage ?: when (failure.errorCode) {
                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "This file is no longer on your phone."
                PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "Keepfin can't play this file on this phone."
                else -> "This file couldn't be played."
            }
            controller?.let { publish(it, rebuildQueue = false) }
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        controller?.let { block(it); return }
        waiting.add(block)
        if (connecting != null) return
        val future = PlaybackClient.connect(app)
        connecting = future
        future.addListener({
            connecting = null
            val connected = try { future.get() } catch (failure: Exception) {
                Log.w(TAG, "playback service unavailable", failure)
                waiting.clear()
                uiFlow.update { it.copy(error = "Playback isn't available right now. Try again.") }
                return@addListener
            }
            controller = connected
            connected.addListener(listener)
            publish(connected, rebuildQueue = true)
            publishTracks(connected)
            publishPosition(connected)
            if (connected.isPlaying) restartTicker(connected)
            val queued = waiting.toList(); waiting.clear()
            queued.forEach { it(connected) }
        }, ContextCompat.getMainExecutor(app))
    }

    private fun entry(item: MediaItem): QueueEntry {
        val meta = item.mediaMetadata
        val kind = meta.extras?.getString(PlaybackItems.EXTRA_KIND)
        return QueueEntry(
            id = item.mediaId, title = (meta.displayTitle ?: meta.title)?.toString().orEmpty().ifBlank { "Untitled" },
            artist = (meta.artist ?: meta.albumArtist)?.toString(), durationMs = meta.durationMs,
            // a video kept in a music space and listened to is described as music: the song player is its player
            video = kind == LibraryItemKind.VIDEO.wire && meta.mediaType != androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC, art = meta.artworkUri?.toString(),
            remote = meta.extras?.getBoolean(PeerPlayback.EXTRA_REMOTE) == true,
            musicVideo = meta.extras?.getBoolean(PlaybackItems.EXTRA_MUSIC_VIDEO) == true, resolved = item.localConfiguration != null,
        )
    }

    private fun publish(player: Player, rebuildQueue: Boolean) {
        val queue = if (rebuildQueue || uiFlow.value.queue.size != player.mediaItemCount) List(player.mediaItemCount) { entry(player.getMediaItemAt(it)) } else uiFlow.value.queue
        uiFlow.value = PlayerUi(
            queue = queue, index = if (player.mediaItemCount == 0) -1 else player.currentMediaItemIndex,
            playing = player.isPlaying, buffering = player.playbackState == Player.STATE_BUFFERING,
            ended = player.playbackState == Player.STATE_ENDED, shuffle = player.shuffleModeEnabled, repeat = player.repeatMode,
            speed = player.playbackParameters.speed, error = error, connected = true,
            nextIndex = if (player.mediaItemCount == 0) -1 else player.nextMediaItemIndex,
            previousIndex = if (player.mediaItemCount == 0) -1 else player.previousMediaItemIndex,
            upcoming = player.mediaItemCount > 0 && player.currentTimeline.getNextWindowIndex(player.currentMediaItemIndex, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled) != C.INDEX_UNSET,
        )
    }

    private fun publishPosition(player: Player) {
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        positionFlow.value = PlayPosition(player.currentPosition.coerceAtLeast(0L), duration, player.bufferedPosition.coerceAtLeast(0L))
    }

    private fun restartTicker(player: Player) {
        ticker?.cancel()
        if (!player.isPlaying) return
        ticker = scope.launch {
            while (isActive) { publishPosition(player); delay(TICK_MS) }
        }
    }

    private fun label(format: androidx.media3.common.Format, ordinal: Int): String {
        val language = format.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).takeIf(String::isNotBlank) }
        return listOfNotNull(format.label, language, format.channelCount.takeIf { it > 2 }?.let { "$it ch" }).distinct().joinToString(" · ").ifBlank { "Track ${ordinal + 1}" }
    }

    private fun publishTracks(player: Player) {
        val audio = ArrayList<TrackChoice>()
        val text = ArrayList<TrackChoice>()
        player.currentTracks.groups.forEachIndexed { g, group ->
            for (t in 0 until group.length) {
                if (!group.isTrackSupported(t)) continue
                when (group.type) {
                    C.TRACK_TYPE_AUDIO -> audio.add(TrackChoice("$g:$t", label(group.getTrackFormat(t), audio.size), group.isTrackSelected(t)))
                    C.TRACK_TYPE_TEXT -> text.add(TrackChoice("$g:$t", label(group.getTrackFormat(t), text.size), group.isTrackSelected(t)))
                }
            }
        }
        val size = player.videoSize
        tracksFlow.value = MediaTracks(audio, text, player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) || text.none { it.selected }, size.width, size.height)
    }

    private fun savedQueue(): JSONObject? = try {
        app.getSharedPreferences("playback-state", Context.MODE_PRIVATE).getString("queue", null)?.let(::JSONObject)
    } catch (_: Exception) { null }

    override fun connectIfSaved() {
        val saved = savedQueue() ?: return
        val array = saved.optJSONArray("ids") ?: return
        if (array.length() == 0) return
        withController { c ->
            if (c.mediaItemCount > 0) return@withController
            val ids = List(array.length()) { array.getString(it) }
            c.setMediaItems(ids.map(PlaybackItems::reference), saved.optInt("index").coerceIn(0, ids.lastIndex), saved.optLong("position"))
            c.shuffleModeEnabled = saved.optBoolean("shuffle")
            c.repeatMode = saved.optInt("repeat")
            c.prepare()
            c.pause()
        }
    }

    override fun playNow(ids: List<String>, index: Int, startMs: Long, play: Boolean, watch: Boolean) {
        if (ids.isEmpty()) return
        withController { c ->
            error = null
            c.setMediaItems(ids.map { PlaybackItems.reference(it, watch, wantVideo) }, index.coerceIn(0, ids.lastIndex), startMs)
            c.prepare()
            if (play) c.play()
        }
    }

    /** The side new queues are made with: a song that keeps a music video then plays as that video. */
    private var wantVideo = false

    override fun setMusicVideo(on: Boolean) { wantVideo = on }

    override fun playMusicVideo(id: String) {
        withController { c ->
            error = null
            c.setMediaItems(listOf(PlaybackItems.reference(id, musicVideo = true)), 0, 0L)
            c.prepare()
            c.play()
        }
    }

    override fun switchSide(musicVideo: Boolean, startMs: Long) {
        wantVideo = musicVideo
        withController { c ->
            val n = c.mediaItemCount
            if (n == 0) return@withController
            error = null
            // the service makes each item again for the side asked: one command, the current song at its own place on that side
            c.setMediaItems(List(n) { PlaybackItems.reference(c.getMediaItemAt(it).mediaId, musicVideo = musicVideo) }, c.currentMediaItemIndex, startMs)
            c.prepare()
        }
    }

    override fun playNext(ids: List<String>) {
        if (ids.isEmpty()) return
        withController { c ->
            if (c.mediaItemCount == 0) { playNow(ids); return@withController }
            c.addMediaItems(c.currentMediaItemIndex + 1, ids.map { PlaybackItems.reference(it, musicVideo = wantVideo) })
        }
    }

    override fun addToQueue(ids: List<String>) {
        if (ids.isEmpty()) return
        withController { c -> if (c.mediaItemCount == 0) playNow(ids) else c.addMediaItems(ids.map { PlaybackItems.reference(it, musicVideo = wantVideo) }) }
    }

    override fun play() { withController { error = null; if (it.playbackState == Player.STATE_IDLE) it.prepare(); if (it.playbackState == Player.STATE_ENDED) it.seekToDefaultPosition(0); it.play() } }
    override fun pause() { controller?.pause() }
    override fun next() { controller?.seekToNextMediaItem() }
    override fun previous() { controller?.seekToPrevious() }
    override fun previousItem() { controller?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() } }
    override fun seekTo(ms: Long) { controller?.let { it.seekTo(ms); publishPosition(it) } }
    override fun skipTo(index: Int) { controller?.let { if (index in 0 until it.mediaItemCount) { it.seekToDefaultPosition(index); it.play() } } }
    override fun removeAt(index: Int) { controller?.let { if (index in 0 until it.mediaItemCount) it.removeMediaItem(index) } }
    override fun move(from: Int, to: Int) { controller?.let { if (from in 0 until it.mediaItemCount && to in 0 until it.mediaItemCount) it.moveMediaItem(from, to) } }
    /** Closes the player: the queue empties, and the service forgets the queue it saved for the next start (see PlaybackService). */
    override fun clearQueue() { controller?.let { closing = true; it.stop(); it.clearMediaItems() }; error = null }

    private fun disconnect() {
        closing = false
        ticker?.cancel()
        controller?.let { it.removeListener(listener); it.release() }
        controller = null
    }
    override fun clearUpcoming() {
        val c = controller ?: return
        val timeline = c.currentTimeline
        val shuffle = c.shuffleModeEnabled
        val gone = UpcomingQueue.indices(c.currentMediaItemIndex, c.mediaItemCount) { timeline.getNextWindowIndex(it, Player.REPEAT_MODE_OFF, shuffle).let { n -> if (n == C.INDEX_UNSET) -1 else n } }
        // highest first, so the positions still to remove do not move
        // in runs of neighbours, so a long queue is a few commands, not one per song
        val i = gone.sorted().asReversed()
        var k = 0
        while (k < i.size) {
            var j = k
            while (j + 1 < i.size && i[j + 1] == i[j] - 1) j++
            c.removeMediaItems(i[j], i[k] + 1)
            k = j + 1
        }
    }
    override fun setShuffle(on: Boolean) { controller?.shuffleModeEnabled = on }
    override fun setRepeat(mode: Int) { controller?.repeatMode = mode }
    override fun setSpeed(speed: Float) { controller?.setPlaybackSpeed(speed) }

    override fun selectAudio(id: String) = select(C.TRACK_TYPE_AUDIO, id)

    override fun selectText(id: String?) {
        val c = controller ?: return
        if (id == null) c.trackSelectionParameters = c.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        else select(C.TRACK_TYPE_TEXT, id)
    }

    private fun select(type: Int, id: String) {
        val c = controller ?: return
        val (g, t) = id.split(':').let { it[0].toIntOrNull() to it.getOrNull(1)?.toIntOrNull() }
        val group = c.currentTracks.groups.getOrNull(g ?: return) ?: return
        if (group.type != type || t == null || t !in 0 until group.length) return
        c.trackSelectionParameters = c.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, t)).build()
    }

    override fun setSleepTimer(minutes: Int) = PlaybackControls.setSleepTimer(minutes)
    override fun setSleepAtEndOfTrack() = PlaybackControls.setSleepAtEndOfTrack()
    override fun cancelSleepTimer() = PlaybackControls.cancelSleepTimer()
    override fun setEqualizerEnabled(enabled: Boolean) = PlaybackControls.setEqualizerEnabled(enabled)
    override fun setEqualizerPreset(preset: Int) = PlaybackControls.setEqualizerPreset(preset)
    override fun setEqualizerBand(band: Int, level: Int) = PlaybackControls.setEqualizerBand(band, level)
    override fun setBassBoost(strength: Int) = PlaybackControls.setBassBoost(strength)

    override fun release() {
        ticker?.cancel()
        controller?.let { it.removeListener(listener); it.release() }
        controller = null
        connecting?.cancel(true); connecting = null
        scope.cancel()
    }

    private companion object {
        const val TAG = "KeepfinPlayer"
        const val TICK_MS = 400L
    }
}
