package app.keepfin.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.keepfin.MediaInfo
import app.keepfin.VideoOption
import app.keepfin.core.choice.ChoiceMedia
import app.keepfin.core.formats.Size
import app.keepfin.ui.Format
import app.keepfin.ui.FormatChoice
import app.keepfin.ui.forChoice
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.SpecBadge
import app.keepfin.ui.theme.tabular
import java.util.Locale

/**
 * What to save, as a Format row and one list under it, with nothing behind a fold. Video lists every size the link has, best first
 * (resolution, fps, HDR, codec, size); a lossy audio format lists its qualities with sizes; Original lists the source streams; FLAC and
 * WAV have no list. A row is always selected and the Download button names the size of the selected row. Nothing here promises more
 * than the engine found.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SizeList(media: MediaInfo, choice: FormatChoice, onChoice: (FormatChoice) -> Unit, audio: Boolean) {
    val core = remember(media) { media.forChoice() }
    val containers = if (audio) emptyList() else choice.containers(core)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("Format", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (audio) audioTargets(media).forEach { target ->
                FilterChip(
                    selected = choice.audioTarget == target, onClick = { onChoice(choice.withTarget(target)) },
                    label = { Text(audioName(target)) }, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.audioTarget(target)),
                )
            } else containers.forEach { offer ->
                FilterChip(
                    selected = choice.container == offer.value, onClick = { onChoice(choice.copy(container = offer.value)) }, enabled = offer.allowed,
                    label = { Text(if (offer.value == "webm") "WebM" else offer.value.uppercase(Locale.US)) }, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.container(offer.value)),
                )
            }
        }
        containers.firstNotNullOfOrNull { it.reason }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(UiTags.CONTAINER_REASON))
        }
        if (audio) AudioList(media, core, choice, onChoice) else VideoList(media, core, choice, onChoice)
    }
}

private fun audioTargets(media: MediaInfo) = listOf("original", "mp3", "m4a", "opus", "flac", "wav").filter { it != "original" || media.audio.isNotEmpty() }

private fun audioName(target: String) = when (target) { "original" -> "Original"; "opus" -> "Opus"; else -> target.uppercase(Locale.US) }

/** What each audio format is, in one honest line (nothing is promised about players or devices). */
private fun audioMeaning(target: String): String = when (target) {
    "flac" -> "Lossless container. Quality cannot exceed the source stream."
    "wav" -> "Uncompressed. Quality cannot exceed the source stream."
    else -> "Keeps the source stream without re-encoding."
}

@Composable
private fun VideoList(media: MediaInfo, core: ChoiceMedia, choice: FormatChoice, onChoice: (FormatChoice) -> Unit) {
    if (media.video.isEmpty()) {
        Text("This link doesn't list its formats; the best available video and audio are saved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        return
    }
    val sorted = media.video.sortedWith(compareByDescending<VideoOption> { it.height }.thenByDescending { it.fps ?: 0 }.thenByDescending { it.hdr })
    val selected = choice.video(media)
    Column(Modifier.selectableGroup().testTag(UiTags.SIZES_LIST)) {
        sorted.forEachIndexed { index, option -> VideoRow(option, index == 0, option.id == selected?.id) { onChoice(choice.withVideo(core, option.id)) } }
    }
}

@Composable
private fun VideoRow(option: VideoOption, best: Boolean, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).testTag(UiTags.quality(option.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 12.dp))
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Format.videoLabel(option), style = MaterialTheme.typography.bodyLarge.tabular(), maxLines = 1, softWrap = false)
            if (best) SpecBadge("Best", emphasized = true)
            Format.resolutionBadge(option.height)?.let { SpecBadge(it, emphasized = option.height >= 2160) }
            if (option.hdr) SpecBadge("HDR", emphasized = true)
            SpecBadge(Format.codec(option.codec))
        }
        SizeText(Format.size(option.size, option.sizeApprox))
    }
}

@Composable
private fun SizeText(size: String?) {
    Text(
        size ?: "size unknown", style = MaterialTheme.typography.bodyMedium.tabular(), textAlign = TextAlign.End, maxLines = 1, softWrap = false,
        color = if (size == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 8.dp),
    )
}

/** The size shown for the current choice, worded like the rows ("about 71 MB", "8.6 MB"); null when unknown. */
internal fun choiceSize(media: MediaInfo, choice: FormatChoice): String? = sizeWords(choice.size(media.forChoice()))

private fun sizeWords(size: Size): String? = size.bytes?.let { (if (size.approx) "about " else "") + Format.bytes(it) }

@Composable
private fun AudioList(media: MediaInfo, core: ChoiceMedia, choice: FormatChoice, onChoice: (FormatChoice) -> Unit) {
    when {
        choice.audioTarget in FormatChoice.LOSSY -> Column(Modifier.selectableGroup().testTag(UiTags.SIZES_LIST)) {
            FormatChoice.QUALITIES.forEach { quality ->
                val selected = choice.audioQuality == quality
                AudioRow(
                    if (quality == "best") "Best" else "$quality kbps", audioName(choice.audioTarget), sizeWords(choice.copy(audioQuality = quality).size(core)), quality == "best", selected,
                    Modifier.testTag(UiTags.audioQuality(quality)),
                ) { onChoice(choice.copy(audioQuality = quality)) }
            }
        }
        choice.audioTarget == "original" && media.audio.isNotEmpty() -> {
            val selected = choice.audioStream(media)
            Column(Modifier.selectableGroup().testTag(UiTags.SIZES_LIST)) {
                media.audio.forEachIndexed { index, stream ->
                    AudioRow(
                        stream.abr?.let { "${it.toInt()} kbps" } ?: "Audio", listOfNotNull(Format.codec(stream.codec), stream.language).joinToString(" · "), Format.size(stream.size, stream.sizeApprox), index == 0,
                        stream.formatId == selected?.formatId, Modifier.testTag(UiTags.audioStream(stream.formatId)),
                    ) { onChoice(choice.copy(audioFormatId = stream.formatId)) }
                }
            }
        }
        else -> Text(audioMeaning(choice.audioTarget), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
    }
}

@Composable
private fun AudioRow(title: String, detail: String, size: String?, best: Boolean, selected: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 52.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge.tabular(), maxLines = 1, softWrap = false, modifier = Modifier.width(84.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            if (best) SpecBadge("Best", emphasized = true)
            if (detail.isNotBlank()) SpecBadge(detail)
        }
        SizeText(size)
    }
}
