package app.keepfin.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.keepfin.DownloadOptions
import app.keepfin.MediaInfo
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.SwitchRow

/** How the downloads of a collection show while they run (Extras › In the queue); the choice is remembered across sessions. */
class QueueChoice(val keepTogether: Boolean, val onChange: (Boolean) -> Unit)

/**
 * The Extras tab: one group per thing, in plain words, the same groups as on Windows. [media] is the single link (null for a collection
 * or a set of links); [video] is false for audio, which has no subtitles; [cut] is for one link only. [onSaveDefault] makes the extras shown the
 * person's defaults.
 */
@Composable
fun ColumnScope.ExtrasGroups(
    options: DownloadOptions,
    onOptions: (DownloadOptions) -> Unit,
    onSaveDefault: (DownloadOptions) -> Unit,
    media: MediaInfo? = null,
    video: Boolean = true,
    cut: Boolean = false,
    queue: QueueChoice? = null,
) {
    val subs = options.subtitles
    if (queue != null) Group("In the queue", "How the downloads show while they run.", "queue") { Grouping(queue) }
    if (video) Group("Subtitles", "Saved beside the video, or inside it.", "subtitles", padded = false) {
        val tracks = media?.subtitles?.filter { !it.auto }?.map { it.lang }?.distinct()?.size ?: 0
        SwitchRow(
            "Save subtitles", subs.enabled, { onOptions(options.copy(subtitles = subs.copy(enabled = it))) },
            subtitle = if (tracks > 0) "$tracks ${if (tracks == 1) "language" else "languages"} available" else "Download subtitle tracks in your languages",
            tag = "option-subtitles",
        )
        if (subs.enabled) SubtitleDetails(media, options, onOptions)
    }
    if (cut) Group("Cut a part", "Keep only the part you want (m:ss). Leave both empty for all of it.", "cut") {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TimeField("Start", options.sectionStart, Modifier.weight(1f)) { onOptions(options.copy(sectionStart = it)) }
            TimeField("End", options.sectionEnd, Modifier.weight(1f)) { onOptions(options.copy(sectionEnd = it)) }
        }
    }
    Group("Sponsor parts", "Parts other viewers marked as sponsored on YouTube. Mark adds chapters; Remove cuts them out.", "sponsor") {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("off" to "Keep", "mark" to "Mark", "remove" to "Remove").forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = options.sponsorBlock == value, onClick = { onOptions(options.copy(sponsorBlock = value)) },
                    shape = SegmentedButtonDefaults.itemShape(index, 3), modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(label) }
            }
        }
    }
    Group("What goes in the file", ExtrasModel.inTheFile(options), "file") {
        Pills(
            Pill("Cover", options.embedThumbnail) { onOptions(options.copy(embedThumbnail = it)) },
            Pill("Tags", options.embedMetadata) { onOptions(options.copy(embedMetadata = it)) },
            Pill("Chapters", options.embedChapters) { onOptions(options.copy(embedChapters = it)) },
        )
    }
    Group("Also save beside the file", "Extra files next to the download.", "beside") {
        Pills(Pill("Cover image", options.writeThumbnail) { onOptions(options.copy(writeThumbnail = it)) })
    }
    TextButton(onClick = { onSaveDefault(options) }, modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp).testTag(UiTags.EXTRAS_DEFAULT)) {
        Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Use these extras as my default")
    }
}

@Composable
private fun Group(name: String, note: String?, section: String, padded: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp).testTag(UiTags.reviewSection(section))) {
        Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() })
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp).padding(top = 2.dp))
        Spacer(Modifier.height(8.dp))
        Column(if (padded) Modifier.padding(horizontal = 16.dp) else Modifier, content = content)
    }
}

/** Choices that can each be on or off: a row of pressed or unpressed chips. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pills(vararg items: Pill) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { pill ->
            FilterChip(
                selected = pill.on, onClick = { pill.set(!pill.on) }, label = { Text(pill.label) }, modifier = Modifier.heightIn(min = 48.dp),
                leadingIcon = { if (pill.on) Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        }
    }
}

private class Pill(val label: String, val on: Boolean, val set: (Boolean) -> Unit)

/** "Keep together" (default) or "Separate items", with a line saying what each does. */
@Composable
private fun Grouping(queue: QueueChoice) {
    Column(Modifier.fillMaxWidth().testTag(UiTags.COLLECTION_GROUPING)) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = queue.keepTogether, onClick = { queue.onChange(true) }, shape = SegmentedButtonDefaults.itemShape(0, 2),
                modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.COLLECTION_KEEP_TOGETHER),
            ) { Text("Keep together") }
            SegmentedButton(
                selected = !queue.keepTogether, onClick = { queue.onChange(false) }, shape = SegmentedButtonDefaults.itemShape(1, 2),
                modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.COLLECTION_SEPARATE),
            ) { Text("Separate items") }
        }
        Text(
            if (queue.keepTogether) "One collection card in Downloads and Library, with group actions. Files are saved the same way."
            else "Every item is queued on its own, like single downloads. Files are saved the same way.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
        )
    }
}
