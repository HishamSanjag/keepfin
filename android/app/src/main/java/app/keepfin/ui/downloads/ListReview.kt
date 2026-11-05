package app.keepfin.ui.downloads

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.keepfin.CollectionKind
import app.keepfin.CollectionLogic
import app.keepfin.DownloadPreset
import app.keepfin.PlaylistEntry
import app.keepfin.Providers
import app.keepfin.core.formats.Unavailable
import app.keepfin.ui.CollectionReview
import app.keepfin.ui.Format
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.EmptyArt
import app.keepfin.ui.components.EmptyState
import app.keepfin.ui.components.FilterPill
import app.keepfin.ui.components.MediaThumb
import app.keepfin.ui.components.SpecBadge
import app.keepfin.ui.isAudio
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.theme.tabular

/** The header of a playlist, album or channel: cover, title, and the counts while the list is still coming. */
internal fun listHeader(vm: StudioViewModel, review: CollectionReview): PreviewHeader {
    val art = review.entries.firstNotNullOfOrNull { it.thumbnail }
    val kind = when {
        CollectionLogic.kindOf(review.url) == CollectionKind.CHANNEL -> "Channel"
        review.music -> "Album or playlist"
        else -> "Playlist"
    }
    return PreviewHeader(
        cover = { MediaThumb(art, if (review.music) Modifier.size(88.dp) else Modifier.width(128.dp).aspectRatio(16f / 9f), audio = review.music, shape = MaterialTheme.shapes.medium) },
        title = review.title ?: "Collection",
        facts = listOf(Providers.name(review.url), kind, countsLine(review)).joinToString(" · "),
        tag = UiTags.COLLECTION_HEADER,
        action = if (review.discovering) ({
            TextButton(onClick = vm.collections::stopDiscovery, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.StopCircle, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Stop searching")
            }
        }) else null,
    )
}

/** "12 songs · 36 min" or "182 videos"; while more is coming, what has been found so far. */
private fun countsLine(review: CollectionReview): String {
    val n = review.entries.size
    val noun = if (review.music) (if (n == 1) "song" else "songs") else (if (n == 1) "video" else "videos")
    val known = review.entries.mapNotNull { it.duration }
    val length = if (n > 0 && known.size == n) lengthWords(known.sum()) else null
    val base = when {
        review.discovering -> "$n found · still searching…"
        review.truncated -> "$n $noun loaded · more available"
        else -> "$n $noun"
    }
    return if (length != null && !review.discovering && !review.truncated) "$base · $length" else base
}

private fun lengthWords(seconds: Double): String {
    val minutes = Math.round(seconds / 60.0).toInt()
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

/** The main tab of a collection: what to keep, Keep in sync, quality for all, then the entries to pick from. */
@Composable
fun ListMain(vm: StudioViewModel, review: CollectionReview, targetName: String?) {
    val canBoth = vm.collections.offersCollectionVideos(review)
    val audio = review.preset.isAudio()
    val mode = if (!audio) PreviewMode.VIDEO else if (canBoth && review.findVideo) PreviewMode.BOTH else PreviewMode.AUDIO
    fun pick(next: PreviewMode) {
        review.findVideo = next == PreviewMode.BOTH
        review.preset = when {
            next == PreviewMode.VIDEO -> vm.engine.settings.value.defaultPreset.takeIf { !it.isAudio() } ?: DownloadPreset.BEST
            audio -> review.preset
            else -> DownloadPreset.MP3
        }
    }
    LazyColumn(Modifier.fillMaxWidth().testTag(UiTags.COLLECTION_LIST), contentPadding = PaddingValues(bottom = 16.dp)) {
        item("mode", contentType = "mode") {
            FormatQualityRow(listOfNotNull(PreviewMode.VIDEO, PreviewMode.AUDIO, PreviewMode.BOTH.takeIf { canBoth }), mode, ::pick, review.preset, { review.preset = it }, "collection-preset")
            if (mode == PreviewMode.BOTH) Text(
                "Music videos are kept where there is one. They are large, about 50 to 100 MB each.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp).testTag(UiTags.COLLECTION_VIDEO_NOTE),
            )
        }
        review.error?.takeIf { review.entries.isNotEmpty() }?.let { message ->
            item("error", contentType = "error") {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (vm.collections.syncOffered(review)) item("sync", contentType = "sync") { KeepInSync(review) }
        item("quality", contentType = "quality") {
            Text("Formats resolve per item when each download starts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        }
        if (review.entries.isEmpty() && !review.discovering) item("empty", contentType = "empty") {
            EmptyState(
                art = EmptyArt.COLLECTION,
                title = if (review.error != null) "Couldn't list this collection" else "Nothing to download here",
                body = review.error ?: "This link has no downloadable items. It may be empty, private or need you to sign in.",
            )
        } else {
            if (review.existing.isNotEmpty() && targetName != null) item("existing", contentType = "existing") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(UiTags.COLLECTION_EXISTING_NOTE), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Studio.colors.success, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${review.existing.size} already in $targetName stay${if (review.existing.size == 1) "s" else ""} unselected.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    TextButton(onClick = { vm.collections.selectAll(true) }, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.COLLECTION_SELECT_ANYWAY)) { Text("Select all anyway") }
                }
            }
            item("selection", contentType = "selection") { SelectionBar(vm, review) }
            items(review.entries, key = { it.url }, contentType = { "entry" }) { entry ->
                EntryRow(entry, review.selected[entry.url] == true, review.music, existingIn = if (entry.url in review.existing) targetName else null) { vm.collections.toggleEntry(entry) }
            }
            if (review.entries.isEmpty()) item("searching") {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Listing items…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (review.truncated && !review.discovering) item("more", contentType = "more") {
                OutlinedButton(onClick = vm.collections::loadMore, modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp).testTag(UiTags.COLLECTION_LOAD_MORE)) {
                    Text("Load ${StudioViewModel.COLLECTION_PAGE} more")
                }
            }
        }
    }
}

/**
 * The tonal selection bar: the count, and one outlined button that is Select all until everything is chosen, then Select none.
 * "Everything" is every item the review would select by default (items already in the library stay out unless chosen). Select all
 * never reaches past what is loaded: while more is available the button and the line under the bar say how many it covers.
 */
@Composable
private fun SelectionBar(vm: StudioViewModel, review: CollectionReview) {
    val everything = remember(review.entries.size, review.existing.size) {
        review.entries.filter { it.unavailable == null }.let { usable -> usable.filter { it.url !in review.existing }.ifEmpty { usable } }
    }
    val allSelected = everything.isNotEmpty() && everything.all { review.selected[it.url] == true }
    val partial = review.truncated || review.discovering
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${review.selectedCount} of ${review.entries.size} selected", style = MaterialTheme.typography.titleSmall.tabular(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
                OutlinedButton(
                    onClick = { if (allSelected) vm.collections.selectAll(false) else if (review.existing.isEmpty()) vm.collections.selectAll(true) else vm.collections.selectNew() },
                    enabled = review.entries.isNotEmpty(), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.heightIn(min = 48.dp).testTag(if (allSelected) UiTags.COLLECTION_SELECT_NONE else UiTags.COLLECTION_SELECT_ALL),
                ) {
                    Text(
                        if (allSelected) "Select none" else if (partial) "Select all ${review.entries.size} loaded" else "Select all",
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    )
                }
            }
            if (partial) Text(
                if (review.discovering) "Select all covers the ${review.entries.size} found so far." else "Select all covers the ${review.entries.size} loaded. Load more to add the rest.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.testTag(UiTags.COLLECTION_SCOPE),
            )
        }
    }
}

/** A square cover for songs, a 16:9 picture for videos; the checkbox on the left, "In <space>" under items already saved there. */
@Composable
private fun EntryRow(entry: PlaylistEntry, selected: Boolean, music: Boolean, existingIn: String? = null, onToggle: () -> Unit) {
    val gone = entry.unavailable != null
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .toggleable(value = selected, enabled = !gone, role = Role.Checkbox, onValueChange = { onToggle() })
            .testTag(UiTags.entry(entry.url))
            .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = null, enabled = !gone, modifier = Modifier.padding(horizontal = 12.dp))
        MediaThumb(entry.thumbnail, (if (music) Modifier.size(56.dp) else Modifier.width(96.dp).aspectRatio(16f / 9f)).alpha(if (gone) 0.5f else 1f).testTag(UiTags.entryArtwork(entry.url)), audio = music)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (gone) Unavailable.LABEL else entry.title ?: "Item ${entry.position}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (gone) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            )
            val meta = if (gone) listOf("#${entry.position}", entry.unavailable!!) else listOfNotNull("#${entry.position}", entry.artist ?: entry.uploader, entry.album, Format.duration(entry.duration))
            Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (gone) 3 else 1, overflow = TextOverflow.Ellipsis)
            if (existingIn != null) SpecBadge("In $existingIn", emphasized = true, modifier = Modifier.padding(top = 4.dp).testTag(UiTags.entryExisting(entry.url)))
        }
    }
}

/** Hours stored by the follow settings (1, 6, 12, 24, 168) with the words the choices use. */
private val syncChoices = listOf(1 to "Every hour", 6 to "6 hours", 12 to "12 hours", 24 to "Day", 168 to "Week")

/**
 * "Keep in sync" on the main tab of a list that can be followed: off by default. On, the collection this download creates follows its source
 * automatically at the interval and the Wi-Fi rule shown here. Following is opt-in, so nothing is checked unless this is on (or the person
 * turns it on later in Follow settings).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeepInSync(review: CollectionReview) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(UiTags.SYNC_ROW)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = review.sync, role = Role.Switch, onValueChange = { review.sync = it }).testTag(UiTags.SYNC_SWITCH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Keep in sync", style = MaterialTheme.typography.titleSmall)
                Text("New ones download by themselves", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = review.sync, onCheckedChange = null)
        }
        if (review.sync) Column(Modifier.fillMaxWidth().testTag(UiTags.SYNC_OPTIONS)) {
            Text("Check every", style = MaterialTheme.typography.bodyMedium)
            FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                syncChoices.forEach { (hours, label) ->
                    FilterPill(label, review.syncHours == hours, { review.syncHours = hours }, Modifier.testTag(UiTags.syncEvery(hours)))
                }
            }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = review.syncWifiOnly, role = Role.Switch, onValueChange = { review.syncWifiOnly = it }).testTag(UiTags.SYNC_WIFI),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Only on Wi-Fi", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = review.syncWifiOnly, onCheckedChange = null)
            }
        }
    }
}
