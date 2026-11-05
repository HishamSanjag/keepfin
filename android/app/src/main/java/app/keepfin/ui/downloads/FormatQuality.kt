package app.keepfin.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.keepfin.DownloadPreset
import app.keepfin.ui.UiTags
import app.keepfin.ui.isAudio

/**
 * Format and Quality for all as two compact dropdowns on one line, for a collection or several links: the list of items stays the main
 * thing on screen. [modes] are the formats on offer (Video, Audio, and Both when the collection can keep music videos).
 */
@Composable
fun FormatQualityRow(modes: List<PreviewMode>, mode: PreviewMode, onMode: (PreviewMode) -> Unit, preset: DownloadPreset, onPreset: (DownloadPreset) -> Unit, qualityTag: String) {
    val presets = DownloadPreset.entries.filter { it.isAudio() == (mode != PreviewMode.VIDEO) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Dropdown("Format", mode.label, modes.map { it.label }, { onMode(modes[it]) }, Modifier.weight(1f), UiTags.FORMAT_FOR_ALL)
        Dropdown("Quality for all", preset.label, presets.map { it.label }, { onPreset(presets[it]) }, Modifier.weight(1f), qualityTag)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dropdown(label: String, value: String, options: List<String>, onPick: (Int) -> Unit, modifier: Modifier, tag: String) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, singleLine = true, label = { Text(label, maxLines = 1) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).testTag(tag),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, text ->
                DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onPick(index) }, contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding, modifier = Modifier.testTag("$tag-$text"))
            }
        }
    }
}
