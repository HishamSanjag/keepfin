package app.keepfin.ui.player

import androidx.media3.common.Player
import app.keepfin.EqualizerState
import app.keepfin.SleepTimerState
import kotlinx.coroutines.flow.StateFlow

/** One entry of the play queue as the UI shows it. [art] is a content:// or file path the image loader understands. */
data class QueueEntry(
    val id: String,
    val title: String,
    val artist: String?,
    val durationMs: Long?,
    val video: Boolean,
    val art: String? = null,
    /** Not on this phone: it streams from a paired device. */
    val remote: Boolean = false,
    /** Plays a song's music video file (`Song.video.mp4`): the same song id, its picture. [video] is true too. */
    val musicVideo: Boolean = false,
    /** False while the controller only holds a placeholder the playback service has not resolved yet (nothing about it is known). */
    val resolved: Boolean = true,
)

/** Everything the mini player, full player and video screen read about playback. Position lives in [PlayPosition]. */
data class PlayerUi(
    val queue: List<QueueEntry> = emptyList(),
    val index: Int = -1,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val ended: Boolean = false,
    val shuffle: Boolean = false,
    /** [Player.REPEAT_MODE_OFF], [Player.REPEAT_MODE_ALL] or [Player.REPEAT_MODE_ONE]. */
    val repeat: Int = Player.REPEAT_MODE_OFF,
    val speed: Float = 1f,
    /** A readable reason when the current item cannot play; cleared when something else starts. */
    val error: String? = null,
    val connected: Boolean = false,
    /**
     * The queue positions the player itself would move to (shuffle and repeat included), or -1 when there is none. Null when the
     * port cannot tell, which falls back to list order. With shuffle on the playing song can sit at either end of the list while
     * the shuffled order still has songs to go, so list order alone would wrongly block skipping.
     */
    val nextIndex: Int? = null,
    val previousIndex: Int? = null,
    /** Whether any song would still play after the current one (repeat ignored). Null when the port cannot tell: list order. */
    val upcoming: Boolean? = null,
) {
    val hasUpcoming: Boolean get() = upcoming ?: (index in 0 until queue.lastIndex)
    val current: QueueEntry? get() = queue.getOrNull(index)
    val hasNext: Boolean get() = nextIndex?.let { it >= 0 } ?: (index in 0 until queue.lastIndex || (repeat == Player.REPEAT_MODE_ALL && queue.isNotEmpty()))
    val hasPrevious: Boolean get() = previousIndex?.let { it >= 0 } ?: (index > 0 || repeat == Player.REPEAT_MODE_ALL)
}

/** Which queue positions come after the current one in play order ("Clear upcoming"). */
object UpcomingQueue {
    /**
     * The positions reached by following [nextOf] (the player's next position with repeat off, shuffle order included, -1 for none)
     * from [current] until it ends. Bounded by [count] so a cycle can never loop.
     */
    fun indices(current: Int, count: Int, nextOf: (Int) -> Int): List<Int> {
        if (current !in 0 until count) return emptyList()
        val out = ArrayList<Int>()
        var at = nextOf(current)
        while (at in 0 until count && at != current && out.size < count) { out.add(at); at = nextOf(at) }
        return out
    }
}

/** Playback clock, separate from [PlayerUi] so only progress readers recompose on a tick. */
data class PlayPosition(val positionMs: Long = 0L, val durationMs: Long = 0L, val bufferedMs: Long = 0L)

data class TrackChoice(val id: String, val label: String, val selected: Boolean)

/** Audio and subtitle tracks of the current media, plus its picture size. */
data class MediaTracks(
    val audio: List<TrackChoice> = emptyList(),
    val text: List<TrackChoice> = emptyList(),
    val textDisabled: Boolean = true,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
)

/**
 * The UI's whole view of playback. The production implementation drives the Media3 service through a MediaController
 * ([ControllerPlayerPort]); a fake engine (debug fixtures, UI tests) gets [SimulatedPlayerPort], which never touches audio.
 * All calls happen on the main thread.
 */
interface PlayerPort {
    val ui: StateFlow<PlayerUi>
    val position: StateFlow<PlayPosition>
    val tracks: StateFlow<MediaTracks>
    val sleepTimer: StateFlow<SleepTimerState>
    val equalizer: StateFlow<EqualizerState>

    /** The live player a video surface attaches to; null when playback is simulated or not connected yet. */
    val player: Player?

    /** Replaces the queue with [ids] (library item ids) and starts at [index] / [startMs]. [watch]: the video player asked (Watch video). */
    fun playNow(ids: List<String>, index: Int = 0, startMs: Long = 0L, play: Boolean = true, watch: Boolean = false)

    /** From now on queues are made with this side: true plays each song that keeps a music video as that video (the choice is kept by [app.keepfin.ui.UiPreferences]). */
    fun setMusicVideo(on: Boolean) {}

    /**
     * Replaces the queue with the same songs, made with the side [musicVideo], the current one continuing at [startMs] (its own place on that side).
     * The rest of the queue keeps its order; a shuffled queue is shuffled again. Playing stays playing, paused stays paused.
     */
    fun switchSide(musicVideo: Boolean, startMs: Long) {}

    /** A song's music video on its own: a queue of just that video, from its start. Neither the Song | Video choice nor the other songs change. */
    fun playMusicVideo(id: String) {}
    fun playNext(ids: List<String>)
    fun addToQueue(ids: List<String>)

    fun play()
    fun pause()
    fun toggle() { if (ui.value.playing) pause() else play() }
    fun next()
    /** The Previous button: restarts the song after its first few seconds, otherwise goes to the previous song. */
    fun previous()
    /** Straight to the previous song, never a restart (swipes). */
    fun previousItem() = previous()
    fun seekTo(ms: Long)
    fun seekBy(ms: Long) { seekTo((position.value.positionMs + ms).coerceIn(0L, position.value.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)) }
    fun skipTo(index: Int)
    fun removeAt(index: Int)
    fun move(from: Int, to: Int)
    fun clearQueue()
    /** Removes every song that would play after the current one (shuffle order respected). The current song keeps playing untouched. */
    fun clearUpcoming()
    fun setShuffle(on: Boolean)
    fun setRepeat(mode: Int)
    fun setSpeed(speed: Float)
    fun selectAudio(id: String)
    /** Null turns subtitles off. */
    fun selectText(id: String?)

    fun setSleepTimer(minutes: Int)
    fun setSleepAtEndOfTrack()
    fun cancelSleepTimer()
    fun setEqualizerEnabled(enabled: Boolean)
    fun setEqualizerPreset(preset: Int)
    fun setEqualizerBand(band: Int, level: Int)
    fun setBassBoost(strength: Int)

    /** Connects to the playback service ahead of use, for example to restore the last queue paused. */
    fun connectIfSaved()
    fun release()
}
