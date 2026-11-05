package app.keepfin.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import app.keepfin.ui.StudioSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.ui.theme.tabular

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSheet(title: String, tag: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    StudioSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag(tag)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() })
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeedSheet(port: PlayerPort, speed: Float, options: List<Float> = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f), onDismiss: () -> Unit) {
    PlayerSheet("Playback speed", "speed-sheet", onDismiss) {
        Text("Pitch stays natural at every speed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp))
        Text(speedLabel(speed), style = MaterialTheme.typography.displaySmall.tabular(), modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        Slider(
            value = speed, onValueChange = { port.setSpeed((Math.round(it * 20) / 20f)) }, valueRange = 0.25f..2f, steps = 34,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { stateDescription = "Speed ${speedLabel(speed)}" },
        )
        FlowRow(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { value ->
                FilterChip(speed == value, { port.setSpeed(value) }, label = { Text(speedLabel(value)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("speed-$value"))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepSheet(port: PlayerPort, endLabel: String = "End of song", endSummary: String = "Playback pauses when this song ends.", onDismiss: () -> Unit) {
    val sleep by port.sleepTimer.collectAsStateWithLifecycle()
    PlayerSheet("Sleep timer", "sleep-sheet", onDismiss) {
        Text(
            if (sleep.active) (if (sleep.endOfTrack) endSummary else "Playback pauses in ${sleepClock(sleep.remainingMs)}.") else "Pause playback automatically.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        FlowRow(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 45, 60).forEach { minutes ->
                FilterChip(false, { port.setSleepTimer(minutes); onDismiss() }, label = { Text("$minutes min") }, modifier = Modifier.heightIn(min = 48.dp).testTag("sleep-$minutes"))
            }
            FilterChip(sleep.endOfTrack, { port.setSleepAtEndOfTrack(); onDismiss() }, label = { Text(endLabel) }, modifier = Modifier.heightIn(min = 48.dp).testTag("sleep-end"))
        }
        if (sleep.active) TextButton(onClick = { port.cancelSleepTimer(); onDismiss() }, modifier = Modifier.padding(horizontal = 16.dp).heightIn(min = 48.dp).testTag("sleep-cancel")) { Text("Turn off the timer") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerSheet(port: PlayerPort, onDismiss: () -> Unit) {
    val eq by port.equalizer.collectAsStateWithLifecycle()
    PlayerSheet("Equalizer", "eq-sheet", onDismiss) {
        if (!eq.available && !eq.bassBoostSupported) {
            Text(
                "The equalizer is available once a song is playing, and only on phones that provide one. Audio output (speaker, headphones, Bluetooth) is chosen in your phone's quick settings.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag("eq-unavailable"),
            )
            return@PlayerSheet
        }
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            if (eq.available) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Equalizer", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = eq.enabled, onCheckedChange = port::setEqualizerEnabled, modifier = Modifier.testTag("eq-switch"))
                }
                if (eq.presets.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    eq.presets.forEachIndexed { index, name -> FilterChip(eq.preset == index, { port.setEqualizerPreset(index) }, label = { Text(name) }, enabled = eq.enabled, modifier = Modifier.heightIn(min = 48.dp)) }
                }
                eq.bands.forEach { band ->
                    val label = if (band.centerHz >= 1000) "${band.centerHz / 1000} kHz" else "${band.centerHz} Hz"
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, style = MaterialTheme.typography.labelMedium.tabular(), modifier = Modifier.width(64.dp))
                        Slider(
                            value = band.level.toFloat(), onValueChange = { port.setEqualizerBand(band.index, it.toInt()) }, valueRange = band.minLevel.toFloat()..band.maxLevel.toFloat(),
                            enabled = eq.enabled, modifier = Modifier.weight(1f).testTag("eq-band-${band.index}").semantics { stateDescription = "$label ${band.level / 100} decibels" },
                        )
                        Text("${band.level / 100} dB", style = MaterialTheme.typography.labelSmall.tabular(), modifier = Modifier.width(48.dp))
                    }
                }
            }
            if (eq.bassBoostSupported) {
                Text("Bass boost", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                Slider(
                    value = eq.bassBoost.toFloat(), onValueChange = { port.setBassBoost(it.toInt()) }, valueRange = 0f..1000f, modifier = Modifier.testTag("eq-bass").semantics { stateDescription = "${eq.bassBoost / 10} percent" },
                )
            }
            Spacer(Modifier.size(8.dp))
            Text("Audio output follows your phone: choose speaker, headphones or Bluetooth in quick settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
