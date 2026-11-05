package app.keepfin.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.keepfin.DownloadPreset
import app.keepfin.ui.BatchItem
import app.keepfin.ui.Capture
import app.keepfin.ui.Format
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.MediaThumb
import app.keepfin.ui.isAudio

/** The header of several links: how many, and how far the reading has come. */
internal fun linksHeader(state: Capture.Batch): PreviewHeader {
    val ready = state.items.count { it.media != null }
    val failed = state.items.count { it.error != null }
    return PreviewHeader(
        cover = {
            Box(Modifier.size(88.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Link, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        title = "${state.items.size} links",
        facts = listOfNotNull("$ready ready", "$failed could not be read".takeIf { failed > 0 }, "reading…".takeIf { state.running }).joinToString(" · "),
        tag = UiTags.BATCH,
    )
}

/** The main tab of several links: one choice of quality for all, then each link on its own line. */
@Composable
fun LinksMain(vm: StudioViewModel, state: Capture.Batch) {
    val ready = state.items.count { it.media != null }
    val failed = state.items.count { it.error != null }
    val audio = vm.batchPreset.isAudio()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        if (state.running) LinearProgressIndicator(
            progress = { (ready + failed).toFloat() / state.items.size.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), drawStopIndicator = {},
        )
        FormatQualityRow(listOf(PreviewMode.VIDEO, PreviewMode.AUDIO), if (audio) PreviewMode.AUDIO else PreviewMode.VIDEO, { next ->
            if ((next == PreviewMode.AUDIO) != audio) vm.batchPreset = if (next == PreviewMode.AUDIO) DownloadPreset.MP3 else vm.engine.settings.value.defaultPreset.takeIf { !it.isAudio() } ?: DownloadPreset.BEST
        }, vm.batchPreset, { vm.batchPreset = it }, "preset-picker")
        state.items.forEach { item -> LinkRow(item, onRetry = vm::retryFailedBatch, enabled = !state.running) }
        if (state.running || failed > 0) Text(
            if (state.running) "Ready links can be added now; the rest keep reading." else "Links that could not be read stay here to try again.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        TextButton(onClick = vm::clearDraft, modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp)) { Text("Dismiss all") }
    }
}

@Composable
private fun LinkRow(item: BatchItem, onRetry: () -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        MediaThumb(item.media?.thumbnail, Modifier.width(80.dp).aspectRatio(16f / 9f), audio = item.media?.video?.isEmpty() == true)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.media?.title ?: Format.host(item.url), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val detail = when {
                item.media != null -> listOfNotNull(item.media.provider, Format.duration(item.media.duration)).joinToString(" · ")
                item.error != null -> item.error
                else -> "Reading…"
            }
            Text(
                detail, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (item.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { if (item.media == null) liveRegion = LiveRegionMode.Polite },
            )
        }
        if (item.error != null) OutlinedButton(onClick = onRetry, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
    }
}
