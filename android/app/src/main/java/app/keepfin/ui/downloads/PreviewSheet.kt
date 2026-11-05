package app.keepfin.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.keepfin.core.spaces.PreviewWords
import app.keepfin.ui.StudioSheet
import app.keepfin.ui.UiTags
import app.keepfin.ui.devices.DownloadOnRow
import app.keepfin.ui.devices.DownloadTarget
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.theme.tabular
import kotlinx.coroutines.launch

/** What the top of a preview shows: the cover, the title, one line of facts and an optional action beside them. */
class PreviewHeader(
    val cover: @Composable () -> Unit,
    val title: String,
    val facts: String? = null,
    val tag: String? = null,
    val action: (@Composable () -> Unit)? = null,
)

/** The Extras tab: how many groups were changed from the defaults, and what is in it. */
class PreviewExtras(val changed: Int, val content: @Composable ColumnScope.() -> Unit)

/**
 * The one sheet every download preview opens in (dev-docs/plans/DOWNLOAD-PREVIEW.md): full height from the start, the platform's bottom sheet.
 * It stays composed while a link is read and while its result shows, so it never closes and reopens. [content] gets a `close` that
 * hides the sheet with its motion and then calls [onClose]; swipe and Back go to [onClose] directly. Closing only hides the review: the
 * caller keeps the link and the choices.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewSheet(onClose: () -> Unit, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    StudioSheet(
        onDismissRequest = onClose, sheetState = state, contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        modifier = Modifier.testTag(UiTags.REVIEW_SHEET),
    ) {
        Column(Modifier.fillMaxSize().testTag(UiTags.REVIEW)) {
            content { scope.launch { state.hide() }.invokeOnCompletion { onClose() } }
        }
    }
}

/**
 * The same layout in every case: the header, the main tab ([mainLabel]) and Extras ([extras], none for a case without them), the body of
 * the tab you are on, and the footer above the navigation bar. A case without a [mainLabel] (a link being read) has no tabs.
 */
@Composable
fun ColumnScope.PreviewLayout(
    header: PreviewHeader,
    onClose: () -> Unit,
    mainLabel: String?,
    extras: PreviewExtras?,
    footer: @Composable ColumnScope.() -> Unit,
    main: @Composable () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val onExtras = extras != null && tab == 1
    PreviewTop(header, onClose)
    if (mainLabel != null) PrimaryTabRow(selectedTabIndex = if (onExtras) 1 else 0) {
        Tab(selected = !onExtras, onClick = { tab = 0 }, text = { Text(mainLabel, maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.testTag(UiTags.PREVIEW_TAB_MAIN))
        if (extras != null) Tab(
            selected = onExtras, onClick = { tab = 1 }, text = { Text(PreviewWords.extrasLabel(extras.changed), maxLines = 1) },
            modifier = Modifier.testTag(UiTags.PREVIEW_TAB_EXTRAS),
        )
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        if (extras != null && tab == 1) ScrollBody(extras.content) else main()
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 16.dp, vertical = 8.dp).testTag(UiTags.PREVIEW_FOOTER), content = footer)
    }
}

/** A tab body that scrolls as one column. */
@Composable
fun ScrollBody(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp), content = content)
}

@Composable
private fun PreviewTop(header: PreviewHeader, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
        header.cover()
        Column(Modifier.weight(1f).padding(start = 14.dp).then(if (header.tag != null) Modifier.testTag(header.tag) else Modifier)) {
            Text(header.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            if (header.facts != null) Text(
                header.facts, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
            )
            header.action?.invoke()
        }
        IconButton(onClick = onClose, modifier = Modifier.testTag(UiTags.REVIEW_CLOSE)) { Icon(Icons.Outlined.Close, contentDescription = "Close") }
    }
}

/**
 * The bottom of a preview, always in the same order: where it saves ([saveTo], when the library is on), the playlist it joins ([playlist], also
 * with the library on), Download on… (when a device takes downloads), then Download with the size or the count in [label].
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColumnScope.PreviewFooter(
    saveTo: SaveToUi?,
    playlist: PlaylistUi?,
    targets: List<DownloadTarget>,
    onDownloadOn: (DownloadTarget) -> Unit,
    label: String,
    onDownload: () -> Unit,
    enabled: Boolean = true,
    adding: Boolean = false,
    tag: String = UiTags.DOWNLOAD,
) {
    // the chips keep their own width and wrap to a second line when they do not fit: a name is never cut off to make room
    if (saveTo != null || playlist != null || targets.isNotEmpty()) FlowRow(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.Center, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (saveTo != null) SaveToChip(saveTo)
        if (playlist != null) AddToPlaylistChip(playlist)
        DownloadOnRow(targets, onDownloadOn)
    }
    Button(
        onClick = onDownload, enabled = enabled && !adding,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(tag),
        colors = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction),
        shape = MaterialTheme.shapes.medium,
    ) {
        if (adding) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Studio.colors.onAction)
        else Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.labelLarge.tabular(), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The three things a preview can keep. Both is a song with its video beside it (a collection: the songs with their videos). */
enum class PreviewMode(val label: String, val tag: String) {
    VIDEO("Video", UiTags.TAB_VIDEO), AUDIO("Audio", UiTags.TAB_AUDIO), BOTH("Both", UiTags.TAB_BOTH)
}

/** Video | Audio | Both as one platform segmented row at the top of the main tab; [enabled] turns a choice off (Both when no video was found). */
@Composable
fun ModePicker(modes: List<PreviewMode>, selected: PreviewMode, onPick: (PreviewMode) -> Unit, enabled: (PreviewMode) -> Boolean = { true }) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        modes.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = selected == mode, onClick = { onPick(mode) }, enabled = enabled(mode), shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                modifier = Modifier.heightIn(min = 48.dp).testTag(mode.tag),
            ) { Text(mode.label) }
        }
    }
}
