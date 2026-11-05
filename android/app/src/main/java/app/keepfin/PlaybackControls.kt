package app.keepfin

import android.content.Context
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide handle on the playback session's sleep timer and audio effects, for the Compose UI. The state flows are always
 * readable; the setters take effect through the running [PlaybackService] and are remembered across restarts (equalizer).
 * Safe to call from any thread.
 *
 * Crossfade is intentionally absent on Android (and not offered in its settings): a reliable overlap needs two decoders behind one
 * session, which this build does not ship. [LibrarySettings.crossfade] is stored for the desktop app and has no effect here.
 */
object PlaybackControls {
    private val main = Handler(Looper.getMainLooper())
    internal var service: PlaybackService? = null

    private val sleep = MutableStateFlow(SleepTimerState())
    private val equalizerFlow = MutableStateFlow(EqualizerState())
    val sleepTimer: StateFlow<SleepTimerState> = sleep.asStateFlow()
    val equalizer: StateFlow<EqualizerState> = equalizerFlow.asStateFlow()

    internal fun publishSleep(state: SleepTimerState) { sleep.value = state }
    internal fun publishEqualizer(state: EqualizerState) { equalizerFlow.value = state }

    private fun post(block: PlaybackService.() -> Unit) {
        val target = service ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) target.block() else main.post { service?.block() }
    }

    /** Pauses playback after [minutes] (15, 30, 45 or 60 in the UI). 0 cancels. Needs active playback to matter. */
    fun setSleepTimer(minutes: Int) = post { startSleepTimer(minutes) }

    /** Pauses when the current song ends. */
    fun setSleepAtEndOfTrack() = post { startSleepAtEndOfTrack() }

    fun cancelSleepTimer() = post { cancelSleep() }

    fun setEqualizerEnabled(enabled: Boolean) = post { effects.setEnabled(enabled) }

    /** [preset] is an index into [EqualizerState.presets]. */
    fun setEqualizerPreset(preset: Int) = post { effects.usePreset(preset) }

    /** [level] in millibels within the band's range. */
    fun setEqualizerBand(band: Int, level: Int) = post { effects.setBand(band, level) }

    /** [strength] 0–1000. */
    fun setBassBoost(strength: Int) = post { effects.setBassBoost(strength) }
}

/** Equalizer + bass boost bound to the player's audio session; settings persist in preferences. */
internal class PlaybackEffects(context: Context, private val onChange: (EqualizerState) -> Unit) {
    private val prefs = context.getSharedPreferences("playback-effects", Context.MODE_PRIVATE)
    private var equalizer: Equalizer? = null
    private var bass: BassBoost? = null
    private var session = 0

    fun attach(audioSessionId: Int) {
        release()
        session = audioSessionId
        if (audioSessionId == 0) { onChange(EqualizerState()); return }
        equalizer = try {
            Equalizer(0, audioSessionId).also { eq ->
                eq.enabled = prefs.getBoolean(KEY_ENABLED, false)
                val saved = prefs.getString(KEY_LEVELS, null)?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty()
                if (saved.size == eq.numberOfBands.toInt()) saved.forEachIndexed { band, level -> clampBand(eq, band, level) }
            }
        } catch (error: Throwable) { Log.w(TAG, "equalizer unavailable", error); null }
        bass = try {
            BassBoost(0, audioSessionId).takeIf { it.strengthSupported }?.also { it.setStrength(prefs.getInt(KEY_BASS, 0).coerceIn(0, 1000).toShort()); it.enabled = prefs.getInt(KEY_BASS, 0) > 0 }
        } catch (error: Throwable) { Log.w(TAG, "bass boost unavailable", error); null }
        onChange(state())
    }

    fun release() {
        try { equalizer?.release() } catch (_: Exception) { }
        try { bass?.release() } catch (_: Exception) { }
        equalizer = null; bass = null
    }

    private fun clampBand(eq: Equalizer, band: Int, level: Int) {
        val range = eq.bandLevelRange
        try { eq.setBandLevel(band.toShort(), level.coerceIn(range[0].toInt(), range[1].toInt()).toShort()) } catch (_: Exception) { }
    }

    private fun save(eq: Equalizer) {
        prefs.edit().putString(KEY_LEVELS, (0 until eq.numberOfBands.toInt()).joinToString(",") { eq.getBandLevel(it.toShort()).toString() }).apply()
    }

    fun setEnabled(enabled: Boolean) {
        val eq = equalizer ?: return
        try { eq.enabled = enabled; prefs.edit().putBoolean(KEY_ENABLED, enabled).apply() } catch (error: Exception) { Log.w(TAG, "equalizer toggle failed", error) }
        onChange(state())
    }

    fun usePreset(preset: Int) {
        val eq = equalizer ?: return
        if (preset !in 0 until eq.numberOfPresets) return
        try { eq.usePreset(preset.toShort()); save(eq) } catch (error: Exception) { Log.w(TAG, "preset failed", error) }
        onChange(state())
    }

    fun setBand(band: Int, level: Int) {
        val eq = equalizer ?: return
        if (band !in 0 until eq.numberOfBands) return
        clampBand(eq, band, level)
        save(eq)
        onChange(state())
    }

    fun setBassBoost(strength: Int) {
        val boost = bass ?: return
        val value = strength.coerceIn(0, 1000)
        try { boost.setStrength(value.toShort()); boost.enabled = value > 0; prefs.edit().putInt(KEY_BASS, value).apply() } catch (error: Exception) { Log.w(TAG, "bass boost failed", error) }
        onChange(state())
    }

    fun state(): EqualizerState {
        val eq = equalizer
        if (eq == null && bass == null) return EqualizerState()
        val bands = if (eq == null) emptyList() else {
            val range = eq.bandLevelRange
            (0 until eq.numberOfBands.toInt()).map { EqualizerBand(it, eq.getCenterFreq(it.toShort()) / 1000, range[0].toInt(), range[1].toInt(), eq.getBandLevel(it.toShort()).toInt()) }
        }
        return EqualizerState(
            available = eq != null, enabled = eq?.enabled == true, bands = bands,
            presets = eq?.let { e -> (0 until e.numberOfPresets.toInt()).map { e.getPresetName(it.toShort()) } }.orEmpty(),
            preset = try { eq?.currentPreset?.toInt() ?: -1 } catch (_: Exception) { -1 },
            bassBoostSupported = bass != null, bassBoost = bass?.roundedStrength?.toInt() ?: 0,
        )
    }

    companion object {
        private const val TAG = "KeepfinPlayback"
        private const val KEY_ENABLED = "eqEnabled"
        private const val KEY_LEVELS = "eqLevels"
        private const val KEY_BASS = "bass"
    }
}
