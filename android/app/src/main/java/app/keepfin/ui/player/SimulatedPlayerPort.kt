package app.keepfin.ui.player

import androidx.media3.common.Player
import app.keepfin.EqualizerBand
import app.keepfin.EqualizerState
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.SleepTimerState
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

/**
 * Playback state without sound, used only while a fake engine is installed (debug fixtures and Compose UI tests; release builds
 * never select it). Fixture library items have no real files, so this port models the queue, clock, shuffle, repeat, speed,
 * sleep timer and equalizer state so every player surface can be exercised and photographed. It never claims audio is playing
 * outside those fixtures. Real playback is covered by the instrumented playback smoke test against [ControllerPlayerPort].
 */
class SimulatedPlayerPort(private val lookup: (String) -> LibraryItem?) : PlayerPort {
    companion object {
        /** False: the position stays where it is while "playing" (UI tests that are not about the position set it). */
        @Volatile var ticks: Boolean = true
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val uiFlow = MutableStateFlow(PlayerUi(connected = true))
    private val positionFlow = MutableStateFlow(PlayPosition())
    private val tracksFlow = MutableStateFlow(MediaTracks())
    private val sleepFlow = MutableStateFlow(SleepTimerState())
    private val eqFlow = MutableStateFlow(
        EqualizerState(
            available = true, enabled = false,
            bands = listOf(60, 230, 910, 3600, 14000).mapIndexed { i, hz -> EqualizerBand(i, hz, -1500, 1500, 0) },
            presets = listOf("Normal", "Classical", "Dance", "Folk", "Heavy Metal", "Hip Hop", "Jazz", "Pop", "Rock"), preset = 0,
            bassBoostSupported = true, bassBoost = 0,
        ),
    )
    private var clock: Job? = null
    private var sleepJob: Job? = null
    private var order: List<QueueEntry> = emptyList()

    override val ui: StateFlow<PlayerUi> = uiFlow.asStateFlow()
    override val position: StateFlow<PlayPosition> = positionFlow.asStateFlow()
    override val tracks: StateFlow<MediaTracks> = tracksFlow.asStateFlow()
    override val sleepTimer: StateFlow<SleepTimerState> = sleepFlow.asStateFlow()
    override val equalizer: StateFlow<EqualizerState> = eqFlow.asStateFlow()
    override val player: Player? = null

    private var wantVideo = false
    /** The last queue was made to be watched (as the service does): a video kept as a song is only a picture then. */
    private var watching = false

    private fun entry(id: String): QueueEntry? = lookup(id)?.let {
        val side = wantVideo && it.musicVideo && it.kind == LibraryItemKind.AUDIO
        QueueEntry(it.id, it.title, it.artist ?: it.albumArtist, it.duration?.let { d -> (d * 1000).toLong() }, side || (it.kind == LibraryItemKind.VIDEO && watching), it.art, it.remote, musicVideo = side)
    }

    override fun setMusicVideo(on: Boolean) { wantVideo = on }

    override fun playMusicVideo(id: String) {
        val before = wantVideo
        wantVideo = true
        playNow(listOf(id))
        wantVideo = before
    }

    override fun switchSide(musicVideo: Boolean, startMs: Long) {
        val s = uiFlow.value
        if (s.queue.isEmpty()) return
        wantVideo = musicVideo
        val queue = s.queue.mapNotNull { entry(it.id) }
        order = queue
        uiFlow.value = s.copy(queue = queue, index = s.index.coerceIn(0, queue.lastIndex))
        load(uiFlow.value.index, startMs)
    }

    private fun load(index: Int, startMs: Long) {
        val current = uiFlow.value.queue.getOrNull(index)
        positionFlow.value = PlayPosition(startMs, current?.durationMs ?: 0L, 0L)
    }

    override fun connectIfSaved() = Unit

    override fun playNow(ids: List<String>, index: Int, startMs: Long, play: Boolean, watch: Boolean) {
        watching = watch
        val queue = ids.mapNotNull(::entry)
        if (queue.isEmpty()) { uiFlow.update { it.copy(error = "This file is no longer in your library.") }; return }
        order = queue
        uiFlow.value = uiFlow.value.copy(queue = queue, index = index.coerceIn(0, queue.lastIndex), playing = play, ended = false, error = null)
        load(uiFlow.value.index, startMs)
        if (play) startClock() else clock?.cancel()
    }

    override fun playNext(ids: List<String>) {
        val add = ids.mapNotNull(::entry)
        if (add.isEmpty()) return
        uiFlow.update { s -> if (s.queue.isEmpty()) s.copy(queue = add, index = 0) else s.copy(queue = s.queue.take(s.index + 1) + add + s.queue.drop(s.index + 1)) }
    }

    override fun addToQueue(ids: List<String>) {
        val add = ids.mapNotNull(::entry)
        if (add.isEmpty()) return
        uiFlow.update { s -> if (s.queue.isEmpty()) s.copy(queue = add, index = 0) else s.copy(queue = s.queue + add) }
    }

    private fun startClock() {
        clock?.cancel()
        // a test that is not about the position holds the clock still: a 4 Hz state change keeps Compose from ever being idle
        if (!ticks) return
        clock = scope.launch {
            while (isActive && uiFlow.value.playing) {
                delay(250)
                val state = uiFlow.value
                val speed = state.speed
                val now = positionFlow.value
                val limit = state.current?.durationMs ?: Long.MAX_VALUE
                val next = now.positionMs + (250 * speed).toLong()
                if (next >= limit) {
                    when {
                        state.repeat == Player.REPEAT_MODE_ONE -> load(state.index, 0)
                        state.hasNext -> next()
                        else -> { uiFlow.update { it.copy(playing = false, ended = true) }; positionFlow.value = now.copy(positionMs = limit) }
                    }
                } else positionFlow.value = now.copy(positionMs = next)
            }
        }
    }

    override fun play() {
        if (uiFlow.value.queue.isEmpty()) return
        uiFlow.update { it.copy(playing = true, ended = false) }
        startClock()
    }

    override fun pause() { uiFlow.update { it.copy(playing = false) }; clock?.cancel() }

    override fun next() {
        val s = uiFlow.value
        if (s.queue.isEmpty()) return
        val index = if (s.index < s.queue.lastIndex) s.index + 1 else if (s.repeat == Player.REPEAT_MODE_ALL) 0 else return
        uiFlow.update { it.copy(index = index) }
        load(index, 0)
        if (uiFlow.value.sleepAtEnd()) pause()
    }

    private fun PlayerUi.sleepAtEnd() = sleepFlow.value.endOfTrack.also { if (it) sleepFlow.value = SleepTimerState() }

    override fun previous() {
        val s = uiFlow.value
        if (positionFlow.value.positionMs > 3000 || s.index <= 0) { seekTo(0); return }
        uiFlow.update { it.copy(index = s.index - 1) }
        load(s.index - 1, 0)
    }

    override fun previousItem() {
        val s = uiFlow.value
        val index = if (s.index > 0) s.index - 1 else if (s.repeat == Player.REPEAT_MODE_ALL && s.queue.isNotEmpty()) s.queue.lastIndex else return
        uiFlow.update { it.copy(index = index) }
        load(index, 0)
    }

    override fun seekTo(ms: Long) { positionFlow.update { it.copy(positionMs = ms.coerceAtLeast(0L)) } }
    override fun skipTo(index: Int) { if (index in uiFlow.value.queue.indices) { uiFlow.update { it.copy(index = index, playing = true) }; load(index, 0); startClock() } }

    override fun removeAt(index: Int) {
        uiFlow.update { s ->
            if (index !in s.queue.indices) s else {
                val queue = s.queue.toMutableList().also { it.removeAt(index) }
                s.copy(queue = queue, index = if (queue.isEmpty()) -1 else if (index < s.index) s.index - 1 else s.index.coerceAtMost(queue.lastIndex))
            }
        }
    }

    override fun move(from: Int, to: Int) {
        uiFlow.update { s ->
            if (from !in s.queue.indices || to !in s.queue.indices) s else {
                val queue = s.queue.toMutableList().also { it.add(to, it.removeAt(from)) }
                val current = s.current
                s.copy(queue = queue, index = queue.indexOf(current).coerceAtLeast(0))
            }
        }
    }

    override fun clearQueue() { clock?.cancel(); uiFlow.value = PlayerUi(connected = true); positionFlow.value = PlayPosition() }

    override fun clearUpcoming() {
        // the simulated queue is already in play order (shuffle reorders it), so upcoming is everything after the current song
        uiFlow.update { s -> if (s.index !in s.queue.indices) s else s.copy(queue = s.queue.take(s.index + 1)) }
    }

    override fun setShuffle(on: Boolean) {
        uiFlow.update { s ->
            val current = s.current
            val queue = if (on) (listOfNotNull(current) + s.queue.filter { it !== current }.shuffled()) else order.filter { o -> s.queue.any { it.id == o.id } }.ifEmpty { s.queue }
            s.copy(shuffle = on, queue = queue, index = if (current == null) s.index else queue.indexOfFirst { it.id == current.id }.coerceAtLeast(0))
        }
    }

    override fun setRepeat(mode: Int) { uiFlow.update { it.copy(repeat = mode) } }
    override fun setSpeed(speed: Float) { uiFlow.update { it.copy(speed = speed) } }
    override fun selectAudio(id: String) { tracksFlow.update { t -> t.copy(audio = t.audio.map { it.copy(selected = it.id == id) }) } }
    override fun selectText(id: String?) { tracksFlow.update { t -> t.copy(text = t.text.map { it.copy(selected = it.id == id) }, textDisabled = id == null) } }

    override fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        if (minutes <= 0) { sleepFlow.value = SleepTimerState(); return }
        val end = System.currentTimeMillis() + minutes * 60_000L
        sleepJob = scope.launch {
            while (isActive) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) { pause(); sleepFlow.value = SleepTimerState(); return@launch }
                sleepFlow.value = SleepTimerState(true, left, false)
                delay(1000)
            }
        }
    }

    override fun setSleepAtEndOfTrack() { sleepJob?.cancel(); sleepFlow.value = SleepTimerState(true, 0L, true) }
    override fun cancelSleepTimer() { sleepJob?.cancel(); sleepFlow.value = SleepTimerState() }
    override fun setEqualizerEnabled(enabled: Boolean) { eqFlow.update { it.copy(enabled = enabled) } }
    override fun setEqualizerPreset(preset: Int) {
        val shapes = listOf(
            listOf(0, 0, 0, 0, 0), listOf(300, 0, -100, 200, 400), listOf(600, 300, 0, 200, 500), listOf(-300, 0, 200, 300, 100), listOf(500, 100, 400, 200, -200),
            listOf(500, 300, 0, 100, 300), listOf(300, 100, -100, 100, 400), listOf(-100, 200, 400, 100, -200), listOf(400, 200, -100, 200, 500),
        )
        eqFlow.update { s -> s.copy(preset = preset, bands = s.bands.mapIndexed { i, b -> b.copy(level = shapes[preset.coerceIn(0, shapes.lastIndex)][i]) }) }
    }
    override fun setEqualizerBand(band: Int, level: Int) { eqFlow.update { s -> s.copy(preset = -1, bands = s.bands.map { if (it.index == band) it.copy(level = level.coerceIn(it.minLevel, it.maxLevel)) else it }) } }
    override fun setBassBoost(strength: Int) { eqFlow.update { it.copy(bassBoost = strength.coerceIn(0, 1000)) } }

    override fun release() { clock?.cancel(); sleepJob?.cancel(); scope.cancel() }
}
