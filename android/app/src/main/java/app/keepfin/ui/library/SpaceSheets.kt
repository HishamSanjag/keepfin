package app.keepfin.ui.library

import app.keepfin.ui.components.FilterPill
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import app.keepfin.ui.StudioSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.AddPicker
import app.keepfin.LibraryCollection
import androidx.paging.compose.itemKey
import app.keepfin.ItemOrder
import app.keepfin.ItemQuery
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.RuleText
import app.keepfin.ScopedTag
import app.keepfin.Space
import app.keepfin.SpaceKind
import app.keepfin.TagLogic
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.SelectBox
import app.keepfin.ui.theme.Studio

// ---- Add to a space: the library picker -------------------------------------------------------------

private enum class AddTab(val label: String, val tag: String) { COLLECTIONS("Collections", "collections"), SONGS("Songs", "songs"), VIDEOS("Videos", "videos") }

/**
 * "Add" on a folder space opens the library first: Collections, Songs and Videos with search and multi-select. Items go into the space
 * by Move (the default, files move into the space's folder) or Copy (the original stays). "Device files" is the secondary way in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFromLibrarySheet(ui: LibraryUi, space: Space, onDismiss: () -> Unit) {
    val snapshot by ui.vm.lib.library.collectAsStateWithLifecycle()
    val busy = ui.vm.libraryAdding
    val tabs = remember(space.kind) {
        when (space.kind) {
            SpaceKind.MUSIC -> listOf(AddTab.COLLECTIONS, AddTab.SONGS)
            SpaceKind.VIDEO -> listOf(AddTab.COLLECTIONS, AddTab.VIDEOS)
            SpaceKind.MIXED -> AddTab.entries.toList()
        }
    }
    var tab by rememberSaveable { mutableStateOf(tabs.first()) }
    var query by rememberSaveable { mutableStateOf("") }
    var pickedItems by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var pickedCollections by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var copy by rememberSaveable { mutableStateOf(false) }
    val canCopy = Build.VERSION.SDK_INT >= 29
    val terms = remember(query) { LibraryViews.terms(query) }
    val spaceNames = remember(ui.vm.engine.settings.value.library.spaces) { ui.vm.engine.settings.value.library.spaces.associate { it.id to it.name } }

    // collections that hold something outside this space, found in the index in one pass
    val outside by rememberBrowsed(ui.browse, space.id to snapshot.collections.size, emptyMap<String, Int>()) { it.outsideCounts(space.id) }
    val collections = remember(snapshot.collections, outside, terms) {
        snapshot.collections.filter { (outside[it.id] ?: 0) > 0 }
            .filter { c -> terms.all { c.name.lowercase().contains(it) } }.sortedBy { LibraryViews.fold(it.name) }
    }
    val songsQuery = remember(space.id, query) { ItemQuery(songs = true, excludeSpaces = listOf(space.id), text = query, order = ItemOrder.TITLE) }
    val videosQuery = remember(space.id, query) { ItemQuery(kinds = setOf(LibraryItemKind.VIDEO), excludeSpaces = listOf(space.id), text = query, order = ItemOrder.TITLE) }
    val songs = rememberItemPaging(ui.browse, songsQuery)
    val videos = rememberItemPaging(ui.browse, videosQuery)
    val songCount by rememberCount(ui.browse, songsQuery)
    val videoCount by rememberCount(ui.browse, videosQuery)
    // what the picks add: the picked items plus the items of the picked collections, minus those already in this space
    val picks by rememberBrowsed(ui.browse, Triple(pickedItems, pickedCollections, space.id), Pair(emptyList<LibraryItem>(), emptyMap<LibraryItemKind, Int>())) { b ->
        val all = LinkedHashSet<String>(pickedItems)
        snapshot.collections.filter { it.id in pickedCollections }.forEach { all.addAll(it.itemIds) }
        val resolvedItems = b.byIds(all.toList()).filter { it.space != space.id }
        val picked = b.byIds(pickedItems).groupingBy { it.kind }.eachCount()
        Pair(resolvedItems, picked)
    }
    val resolvedItems = picks.first
    val resolved = remember(resolvedItems) { resolvedItems.map { it.id } }
    val summary = AddPicker.summary(pickedCollections.size, resolvedItems)
    val verb = if (copy) "Copy" else "Move"
    StudioSheet(onDismissRequest = { if (busy == null) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag(UiTags.ADD_SHEET)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Add to ${space.name}", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
            }
            // The other two ways in next to the library picker below: a link (the normal review, saved to this space) and files on the device.
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onDismiss(); ui.sheet(LibrarySheet.AddFromLink(space)) }, enabled = busy == null,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(UiTags.ADD_LINK),
                ) { Text("From a link", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                OutlinedButton(
                    onClick = { onDismiss(); ui.addFiles(space.id) }, enabled = busy == null,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(UiTags.ADD_DEVICE),
                ) { Text("Device files", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
            }
            SearchField(query, { query = it }, "Search your library", Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(UiTags.ADD_SEARCH))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                tabs.forEach { option ->
                    val count = when (option) {
                        AddTab.COLLECTIONS -> pickedCollections.size
                        AddTab.SONGS -> picks.second[LibraryItemKind.AUDIO] ?: 0
                        AddTab.VIDEOS -> picks.second[LibraryItemKind.VIDEO] ?: 0
                    }
                    FilterPill(option.label + if (count > 0) " · $count" else "", tab == option, { tab = option }, Modifier.testTag(UiTags.addTab(option.tag)))
                }
            }
            val empty = when (tab) { AddTab.COLLECTIONS -> collections.isEmpty(); AddTab.SONGS -> songCount == 0; AddTab.VIDEOS -> videoCount == 0 }
            LazyColumn(Modifier.heightIn(max = 300.dp).testTag("add-list")) {
                if (empty) item("empty") {
                    Text(
                        if (terms.isNotEmpty()) "Nothing matches “${query.trim()}”." else "Everything in your library is already in ${space.name}.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp),
                    )
                }
                when (tab) {
                    AddTab.COLLECTIONS -> items(collections, key = { "c-" + it.id }) { c ->
                        val inside = outside[c.id] ?: 0
                        PickRow(
                            c.name, listOfNotNull(spaceNames[c.space], app.keepfin.CollectionText.kindLabel(c).takeIf { c.kind != app.keepfin.CollectionKind.PLAYLIST }, "$inside ${if (inside == 1) "item" else "items"}").joinToString(" · "),
                            c.id in pickedCollections, UiTags.addRow(c.id),
                        ) { pickedCollections = if (c.id in pickedCollections) pickedCollections - c.id else pickedCollections + c.id }
                    }
                    AddTab.SONGS -> items(songs.itemCount, key = songs.itemKey { "s-" + it.id }) { index ->
                        val song = songs[index]
                        if (song != null) PickRow(song.title, listOfNotNull(spaceNames[song.space], song.artist).joinToString(" · "), song.id in pickedItems, UiTags.addRow(song.id)) {
                            pickedItems = if (song.id in pickedItems) pickedItems - song.id else pickedItems + song.id
                        }
                    }
                    AddTab.VIDEOS -> items(videos.itemCount, key = videos.itemKey { "v-" + it.id }) { index ->
                        val video = videos[index]
                        if (video != null) PickRow(video.title, listOfNotNull(spaceNames[video.space], video.duration?.let(LibraryViews::mmss)).joinToString(" · "), video.id in pickedItems, UiTags.addRow(video.id)) {
                            pickedItems = if (video.id in pickedItems) pickedItems - video.id else pickedItems + video.id
                        }
                    }
                }
            }
            if (busy != null) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                    Text(busy, fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("How to add", style = MaterialTheme.typography.labelLarge)
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        HowCard("Move here", "Files move to ${folderPath(space)}. Each item lives in one folder space.", !copy, true, UiTags.ADD_MOVE) { copy = false }
                        HowCard(
                            "Copy here", if (canCopy) "Keeps the original too. Uses more storage." else "Copying needs Android 10 or newer.",
                            copy, canCopy, UiTags.ADD_COPY,
                        ) { copy = true }
                    }
                    Text(
                        if (summary.isEmpty()) "Pick collections, songs or videos." else summary,
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("add-summary"),
                    )
                    Button(
                        onClick = { ui.vm.libraryActions.addFromLibrary(pickedItems, pickedCollections, space.id, copy, resolved.size) { ui.selection.clear(); onDismiss() } },
                        enabled = resolved.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(UiTags.ADD_CONFIRM),
                        colors = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction),
                    ) { Text(if (resolved.isEmpty()) verb else "$verb ${resolved.size}", fontWeight = FontWeight.SemiBold) }
                }
            }
        }
    }
}

/**
 * "Add › From a link": a link field with Paste. Submitting runs the normal Downloads flow (inspection, then the playlist review with Keep in sync) with
 * Save to already on this space, so the review screen is the one place that decides quality and what to download.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddFromLinkSheet(ui: LibraryUi, space: Space, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var problem by rememberSaveable { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val go = {
        if (app.keepfin.ui.Links.extract(text).isEmpty()) problem = "Paste a full link that starts with https://"
        else { onDismiss(); ui.vm.addLink(text, space.id) }
    }
    StudioSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag(UiTags.LINK_SHEET)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Add to ${space.name} from a link", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text("Paste a song, video or playlist link. You choose what to download next, and it is saved to ${space.name}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = text, onValueChange = { text = it; problem = null }, modifier = Modifier.fillMaxWidth().testTag(UiTags.ADD_LINK_FIELD),
                label = { Text("Link") }, isError = problem != null, supportingText = problem?.let { message -> { Text(message) } },
                keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { go() }), minLines = 1, maxLines = 4,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { app.keepfin.ui.readClipboard(context)?.takeIf { it.isNotBlank() }?.let { text = it.trim(); problem = null } ?: run { problem = "The clipboard has no text to paste." } },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.LINK_PASTE),
                ) { Text("Paste") }
                Button(
                    onClick = { go() }, enabled = text.isNotBlank(), modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(UiTags.LINK_GO),
                    colors = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction),
                ) { Text("Review link", fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}

/**
 * "Move to space…" / "Copy to space…": the folder spaces you can put a collection or a song in (not the one it is already in).
 * Picking one runs the same add as the space's "Add" sheet, with a progress line here and a result notice after.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpacePickerSheet(ui: LibraryUi, sheet: LibrarySheet.ToSpace, onDismiss: () -> Unit) {
    val settings by ui.vm.engine.settings.collectAsStateWithLifecycle()
    val snapshot by ui.vm.lib.library.collectAsStateWithLifecycle()
    val busy = ui.vm.libraryAdding
    val canCopy = Build.VERSION.SDK_INT >= 29
    val spaces = remember(settings.library.spaces, sheet.fromSpace) { settings.library.folderSpaces.filter { it.id != sheet.fromSpace } }
    // how many items an add would act on: those of the picked items and collections that are not in the target space already
    val held by rememberBrowsed(ui.browse, sheet, Pair(0, emptyMap<String, Int>())) { b ->
        val all = LinkedHashSet<String>(sheet.itemIds)
        snapshot.collections.filter { it.id in sheet.collectionIds }.forEach { all.addAll(it.itemIds) }
        val found = b.byIds(all.toList())
        Pair(found.size, found.groupingBy { it.space }.eachCount())
    }
    val verb = if (sheet.copy) "Copy" else "Move"
    StudioSheet(onDismissRequest = { if (busy == null) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag(UiTags.SPACE_PICKER)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text("$verb to space…", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() })
            Text(
                if (sheet.copy) (if (canCopy) "“${sheet.title}” stays where it is and a copy goes into the space you pick." else "Copying needs Android 10 or newer.")
                else "“${sheet.title}” moves into the space's folder. Each item lives in one folder space.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            if (spaces.isEmpty()) Text("There is no other folder space yet. Create one from Library › Edit.", modifier = Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            spaces.forEach { space ->
                val count = held.first - (held.second[space.id] ?: 0)
                val enabled = busy == null && count > 0 && (!sheet.copy || canCopy)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button) {
                        ui.vm.libraryActions.addFromLibrary(sheet.itemIds, sheet.collectionIds, space.id, sheet.copy, count) { ui.selection.clear(); onDismiss() }
                    }.padding(horizontal = 24.dp).testTag(UiTags.spacePick(space.id)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(space.icon(), contentDescription = null, tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(space.name, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
                        Text(if (count == 0) "Already there" else folderPath(space), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (busy != null) Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                Text(busy, fontWeight = FontWeight.SemiBold)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            Spacer(Modifier.size(12.dp))
        }
    }
}

private fun folderPath(space: Space) = when (space.kind) {
    SpaceKind.MUSIC -> "Music/${space.folder}"
    SpaceKind.VIDEO -> "Movies/${space.folder}"
    SpaceKind.MIXED -> "${space.folder}"
}

@Composable
private fun PickRow(title: String, subtitle: String, picked: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(picked, role = Role.Checkbox, onClick = onClick).padding(horizontal = 24.dp, vertical = 6.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SelectBox(picked)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HowCard(title: String, body: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp), color = if (selected) colors.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).testTag(tag),
    ) {
        Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = .38f))
                Text(body, fontSize = 12.sp, lineHeight = 16.sp, color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
            }
        }
    }
}

// ---- Tags: filter and edit --------------------------------------------------------------------------

/** The tag filter of a space: which tags are picked and whether an item needs any of them or all. */
class TagFilter(val tags: List<String> = emptyList(), val all: Boolean = false) {
    val active get() = tags.isNotEmpty()
    fun apply(items: List<LibraryItem>): List<LibraryItem> = TagLogic.filter(items, tags.toSet(), all)
}

/**
 * "Filter by tags": Any / All, the tags in scope with counts (used at least twice, plus your own), "+ Your tag", then
 * Save as smart space and Play N. Changes apply to the list behind the sheet straight away.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagFilterSheet(
    title: String,
    ui: LibraryUi,
    base: ItemQuery,
    filter: TagFilter,
    onFilter: (TagFilter) -> Unit,
    onPlay: () -> Unit,
    onSaveSmart: (TagFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    // the tags in scope with their counts (used twice or more, plus the user's own), and every tag there is
    val shown by rememberBrowsed(ui.browse, base, emptyList<ScopedTag>()) { it.tags(base, 2) }
    val allTags by rememberBrowsed(ui.browse, base, emptyList<ScopedTag>()) { it.tags(base, 1) }
    val everyTag = remember(allTags) { allTags.associate { it.tag to it.count } }
    val hidden = (everyTag.size - shown.size).coerceAtLeast(0)
    val songsOnly by rememberCount(ui.browse, base.withTags(filter).copy(kinds = emptySet(), songs = true))
    var adding by rememberSaveable { mutableStateOf(false) }
    var custom by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf<String?>(null) }
    val extra = filter.tags.filter { t -> shown.none { it.tag == t } }
    StudioSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag(UiTags.TAGS_SHEET)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text("Filter by tags", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                }
                TextButton(onClick = { onFilter(TagFilter(emptyList(), filter.all)) }, enabled = filter.active, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.TAGS_CLEAR)) { Text("Clear") }
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)) {
                listOf(false to "Any of these", true to "All of these").forEachIndexed { index, (all, label) ->
                    SegmentedButton(
                        selected = filter.all == all, onClick = { onFilter(TagFilter(filter.tags, all)) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2), modifier = Modifier.heightIn(min = 48.dp).testTag(if (all) UiTags.TAGS_ALL else UiTags.TAGS_ANY),
                    ) { Text(label) }
                }
            }
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
                if (shown.isEmpty() && extra.isEmpty()) Text(
                    "No tag is shared by two or more items yet. Tags come from the source site when you download, or you can add your own with Tags… on a selection.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    (shown + extra.map { ScopedTag(it, everyTag[it] ?: 0, false) }).forEach { scoped ->
                        val on = scoped.tag in filter.tags
                        TagChipPill(scoped.tag, scoped.count, on, UiTags.sheetTag(scoped.tag)) {
                            onFilter(TagFilter(if (on) filter.tags - scoped.tag else filter.tags + scoped.tag, filter.all))
                        }
                    }
                    if (!adding) TagChipPill("+ Your tag", null, false, UiTags.TAGS_YOUR) { adding = true }
                }
                if (adding) {
                    OutlinedTextField(
                        custom, { custom = it.take(40); note = null }, singleLine = true, label = { Text("Tag") },
                        supportingText = note?.let { n -> { Text(n) } }, isError = note != null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        trailingIcon = {
                            TextButton(onClick = {
                                val tag = TagLogic.clean(custom)
                                when {
                                    tag == null -> note = "Use a short word, without a link."
                                    everyTag[tag] == null -> note = "No item here has the tag “$tag”."
                                    else -> { onFilter(TagFilter((filter.tags + tag).distinct(), filter.all)); custom = ""; adding = false }
                                }
                            }, modifier = Modifier.heightIn(min = 48.dp).testTag("tags-your-add")) { Text("Add") }
                        },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("tags-your-field"),
                    )
                }
                Text(
                    "Tags come from the source site and from you." + if (hidden > 0) " Hidden: $hidden ${if (hidden == 1) "tag" else "tags"} used only once." else "",
                    fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onSaveSmart(filter); onDismiss() }, enabled = filter.active, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(UiTags.TAGS_SAVE_SMART),
                ) { Text("Save as smart space", fontSize = 13.sp, maxLines = 1) }
                Button(
                    onClick = { onPlay(); onDismiss() }, enabled = songsOnly > 0,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(UiTags.TAGS_PLAY),
                    colors = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction),
                ) { Text("Play $songsOnly ${if (songsOnly == 1) "song" else "songs"}", fontSize = 13.sp, maxLines = 1, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** A tag chip: 32dp tall inside a 48dp target, tonal when picked, with the count after the name. */
@Composable
fun TagChipPill(tag: String, count: Int?, on: Boolean, testTag: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Box(
        Modifier.heightIn(min = 48.dp).clickable(role = Role.Checkbox, onClick = onClick).semantics {
            selected = on
            stateDescription = if (on) "Selected" else "Not selected"
            contentDescription = if (count != null) "$tag, $count items" else tag
        }.testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp), color = if (on) colors.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
            border = if (on) null else BorderStroke(1.dp, colors.outlineVariant), modifier = Modifier.heightIn(min = 32.dp),
        ) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tag, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) colors.onPrimaryContainer else colors.onSurface, maxLines = 1)
                if (count != null) Text("$count", fontSize = 12.sp, color = if (on) colors.onPrimaryContainer else colors.onSurfaceVariant)
            }
        }
    }
}

/** Your own tags on the selected items: shows the ones they carry, adds new ones and removes old ones, then Save. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TagsSheet(ui: LibraryUi, ids: List<String>, onDismiss: () -> Unit) {
    val scoped by rememberBrowsed(ui.browse, ids, emptyList<ScopedTag>()) { it.tags(ItemQuery(ids = ids.toSet()), 1) }
    var added by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var removed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var text by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf<String?>(null) }
    val existing = remember(scoped) { scoped.filter { it.user }.map { it.tag } }
    val shownTags = (existing.filter { it !in removed } + added).distinct()
    val fromSource = remember(scoped) { scoped.filter { !it.user }.map { it.tag }.take(12) }
    fun commit() {
        val tag = TagLogic.clean(text)
        if (text.isBlank()) return
        if (tag == null) { note = "Use a short word, without a link."; return }
        added = (added + tag).distinct(); removed = removed - tag; text = ""; note = null
    }
    StudioSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("tags-edit-sheet")) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (ids.size == 1) "Tags" else "Tags for ${ids.size} items", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text("Your own tags show in the tag filter even when only one item has them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                text, { text = it.take(40); note = null }, singleLine = true, label = { Text("Add a tag") }, isError = note != null, supportingText = note?.let { n -> { Text(n) } },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit() }),
                trailingIcon = { TextButton(onClick = ::commit, enabled = text.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag("tags-add")) { Text("Add") } },
                modifier = Modifier.fillMaxWidth().testTag("tags-field"),
            )
            if (shownTags.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                shownTags.forEach { tag ->
                    Row(
                        Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Remove tag $tag") {
                            if (tag in added) added = added - tag else removed = (removed + tag).distinct()
                        }.testTag("tag-remove-$tag"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.heightIn(min = 32.dp)) {
                            Row(Modifier.padding(start = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(tag, fontSize = 13.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Outlined.Close, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            } else Text("No tags of your own yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (fromSource.isNotEmpty()) Text("From the source: " + fromSource.joinToString(", "), fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                Button(
                    onClick = {
                        if (text.isNotBlank()) TagLogic.clean(text)?.let { added = (added + it).distinct() }
                        ui.vm.libraryActions.tagItems(ids, added, removed); ui.selection.clear(); onDismiss()
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("tags-save"),
                    colors = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction),
                ) { Text("Save") }
            }
        }
    }
}

// ---- value picker for smart rules -------------------------------------------------------------------

class PickOption(val value: String, val label: String, val detail: String? = null)

/** A searchable multi-select list used by the smart rules editor; [allowCustom] lets a typed value through. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ValuePickerSheet(title: String, options: List<PickOption>, selected: List<String>, allowCustom: Boolean, onDone: (List<String>) -> Unit, onDismiss: () -> Unit) {
    var picked by rememberSaveable { mutableStateOf(selected) }
    var query by rememberSaveable { mutableStateOf("") }
    val terms = remember(query) { LibraryViews.terms(query) }
    val known = remember(options, picked) { (options + picked.filter { p -> options.none { it.value == p } }.map { PickOption(it, it) }) }
    val shown = remember(known, terms) { if (terms.isEmpty()) known else known.filter { o -> terms.all { o.label.lowercase().contains(it) || o.value.lowercase().contains(it) } } }
    val typed = query.trim()
    StudioSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("rules-picker")) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                Button(
                    onClick = { onDone(picked) }, modifier = Modifier.heightIn(min = 48.dp).testTag("rules-picker-done"),
                    colors = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction),
                ) { Text("Done") }
            }
            SearchField(query, { query = it }, "Filter list", Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("rules-picker-search"))
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                if (allowCustom && typed.isNotEmpty() && known.none { it.label.equals(typed, true) || it.value.equals(typed, true) }) item("custom") {
                    PickRow("Add “$typed”", "Not in your library yet", false, "rules-picker-custom") { picked = (picked + typed).distinct(); query = "" }
                }
                if (shown.isEmpty() && !(allowCustom && typed.isNotEmpty())) item("none") {
                    Text("Nothing to pick yet. It fills in as your library grows.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                }
                items(shown, key = { it.value }) { option ->
                    PickRow(option.label, option.detail.orEmpty(), option.value in picked, UiTags.pickOption(option.value)) {
                        picked = if (option.value in picked) picked - option.value else picked + option.value
                    }
                }
            }
        }
    }
}
