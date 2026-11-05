package app.keepfin.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.keepfin.DownloadOptions
import app.keepfin.MediaInfo
import app.keepfin.SubtitleOptions
import app.keepfin.ui.Format
import app.keepfin.ui.StudioSheet
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.SwitchRow

/** The languages, automatic captions and "in the file or as files" choices, shared by the Extras tab and Settings › Default options. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SubtitleDetails(media: MediaInfo?, options: DownloadOptions, onOptions: (DownloadOptions) -> Unit) {
    val subs = options.subtitles
    fun setSubs(value: SubtitleOptions) = onOptions(options.copy(subtitles = value))
    var languages by rememberSaveable(subs.languages) { mutableStateOf(subs.languages) }
    OutlinedTextField(
        value = languages,
        onValueChange = { languages = it; if (it.isNotBlank()) setSubs(subs.copy(languages = it.trim())) },
        label = { Text("Languages") }, supportingText = { Text("Comma separated, e.g. en.*, ar, es") },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
    val available = media?.subtitles?.filter { !it.auto }?.map { it.lang }?.distinct()?.take(10).orEmpty()
    if (available.isNotEmpty()) {
        FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val current = subs.languages.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            available.forEach { lang ->
                val on = lang in current
                FilterChip(selected = on, onClick = {
                    val next = if (on) current - lang else current + lang
                    setSubs(subs.copy(languages = next.joinToString(",").ifEmpty { "en.*" }))
                }, label = { Text(lang) }, modifier = Modifier.heightIn(min = 48.dp))
            }
        }
    }
    SwitchRow("Include automatic captions", subs.auto, { setSubs(subs.copy(auto = it)) })
    SwitchRow("Embed in the video", subs.embed, { setSubs(subs.copy(embed = it)) }, subtitle = "Otherwise saved as separate files in Download/Keepfin")
}

@Composable
internal fun TimeField(label: String, value: Double?, modifier: Modifier, onValue: (Double?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(value?.let { Format.duration(it) } ?: "") }
    val invalid = text.isNotBlank() && Format.parseTime(text) == null
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; val parsed = Format.parseTime(it); if (it.isBlank() || parsed != null) onValue(parsed) },
        label = { Text(label) }, placeholder = { Text(if (label == "Start") "0:00" else "end") },
        isError = invalid, singleLine = true, modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
    )
}

/** Settings › Default options in a sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OptionsSheet(
    media: MediaInfo?,
    options: DownloadOptions,
    onOptions: (DownloadOptions) -> Unit,
    onSaveDefault: ((DownloadOptions) -> Unit)?,
    onClose: () -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    StudioSheet(onDismissRequest = onClose, sheetState = state, modifier = Modifier.testTag(UiTags.OPTIONS_SHEET)) {
        LazyColumn(Modifier.fillMaxWidth()) {
            item {
                Text("Download options", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            item { OptionsEditor(media, options, onOptions) }
            item {
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    if (onSaveDefault != null) TextButton(onClick = { onSaveDefault(options) }) { Text("Use as default") }
                    Button(onClick = onClose) { Text("Done") }
                }
            }
        }
    }
}

/** Settings › Default options. */
@Composable
fun OptionsEditor(media: MediaInfo?, options: DownloadOptions, onOptions: (DownloadOptions) -> Unit, showTrim: Boolean = true) {
    val subs = options.subtitles
    Column {
        SwitchRow("Subtitles", subs.enabled, { onOptions(options.copy(subtitles = subs.copy(enabled = it))) }, subtitle = "Download subtitle tracks in your languages", tag = "option-subtitles")
        if (subs.enabled) SubtitleDetails(media, options, onOptions)
        SwitchRow("Embed thumbnail", options.embedThumbnail, { onOptions(options.copy(embedThumbnail = it)) }, subtitle = "Cover art for audio files")
        SwitchRow("Embed metadata", options.embedMetadata, { onOptions(options.copy(embedMetadata = it)) }, subtitle = "Title, artist and description")
        SwitchRow("Embed chapters", options.embedChapters, { onOptions(options.copy(embedChapters = it)) })
        SwitchRow("Save thumbnail file", options.writeThumbnail, { onOptions(options.copy(writeThumbnail = it)) })
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("SponsorBlock", style = MaterialTheme.typography.bodyLarge)
            Text("Skip sponsor segments on YouTube videos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("off" to "Off", "mark" to "Mark", "remove" to "Remove").forEachIndexed { index, (value, label) ->
                    SegmentedButton(selected = options.sponsorBlock == value, onClick = { onOptions(options.copy(sponsorBlock = value)) },
                        shape = SegmentedButtonDefaults.itemShape(index, 3)) { Text(label) }
                }
            }
        }
        if (showTrim) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Trim", style = MaterialTheme.typography.bodyLarge)
                Text("Download only part of the media (m:ss)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TimeField("Start", options.sectionStart, Modifier.weight(1f)) { onOptions(options.copy(sectionStart = it)) }
                    TimeField("End", options.sectionEnd, Modifier.weight(1f)) { onOptions(options.copy(sectionEnd = it)) }
                }
            }
        }
    }
}
