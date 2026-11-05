package app.keepfin.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import app.keepfin.ui.components.FilterPill
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.ItemOrder
import app.keepfin.ItemQuery
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.Space
import app.keepfin.ui.Format
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.EmptyArt
import app.keepfin.ui.components.EmptyState
import app.keepfin.ui.components.NavRow
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.theme.tabular

/** The quick filters of a space (folder and smart spaces): All and the kinds it holds. */
internal enum class ContentFilter(val label: String) { ALL("All"), VIDEO("Videos"), AUDIO("Audio"), IMAGE("Photos") }

internal fun ContentFilter.kind(): LibraryItemKind? = when (this) {
    ContentFilter.ALL -> null
    ContentFilter.VIDEO -> LibraryItemKind.VIDEO
    ContentFilter.AUDIO -> LibraryItemKind.AUDIO
    ContentFilter.IMAGE -> LibraryItemKind.IMAGE
}

/**
 * Video and mixed spaces: folder cards first, then posters with progress bars and watched marks; a list view is one tap away.
 * [base] is what the page stands on: the whole space, or a folder of it and everything below.
 */
@Composable
fun MediaSpace(ui: LibraryUi, space: Space, path: String, base: ItemQuery, padding: PaddingValues, showTitle: Boolean = true) {
    val vm = ui.vm
    var query by rememberSaveable(space.id, path) { mutableStateOf("") }
    var filter by rememberSaveable(space.id) { mutableStateOf(ContentFilter.ALL) }
    val sort = LibraryViews.Sort.of(vm.prefs.librarySort(space.id))
    val grid = vm.prefs.libraryGrid(space.id, true)
    val playing = rememberPlayingId(ui)

    val snapshot by vm.lib.library.collectAsStateWithLifecycle()
    val tabs = showTitle && path.isEmpty()
    val tab = if (tabs) vm.musicTab(space.id) else MusicTab.SONGS
    var tagList by rememberSaveable(space.id, path) { mutableStateOf(emptyList<String>()) }
    var tagAll by rememberSaveable(space.id, path) { mutableStateOf(false) }
    var tagSheet by rememberSaveable(space.id, path) { mutableStateOf(false) }
    val tagFilter = remember(tagList, tagAll) { TagFilter(tagList, tagAll) }
    // counts and tags arrive together: until then nothing is drawn under the toolbar (no empty state for a frame, no row pushing the list)
    val head by rememberSpaceHead(ui.browse, base)
    val counts = head?.counts ?: app.keepfin.Counts()
    val kinds = remember(counts) { ContentFilter.entries.filter { it != ContentFilter.ALL && counts.of(it.kind()!!) > 0 } }
    val effectiveFilter = if (filter != ContentFilter.ALL && filter !in kinds) ContentFilter.ALL else filter
    val searching = query.isNotBlank()
    val filteredBase = remember(base, effectiveFilter, tagFilter) { base.withTags(tagFilter).let { b -> effectiveFilter.kind()?.let { b.copy(kinds = setOf(it)) } ?: b } }
    // what is listed: with a search, every match in the page's scope; without one, the files directly in this folder
    val listed = remember(filteredBase, path, searching, query, sort) {
        if (searching) filteredBase.copy(text = query, order = sort.order()) else filteredBase.copy(subtree = null, folder = path, order = sort.order())
    }
    val listedCount by rememberCount(ui.browse, listed)
    val folders by rememberBrowsed(ui.browse, filteredBase to path, emptyList<app.keepfin.FolderGroup>()) { if (searching) emptyList() else it.folders(space.id, path) }
    val continueItems by rememberTop(ui.browse, filteredBase.copy(subtree = null, resumable = true, order = ItemOrder.RECENT, kinds = emptySet()), 10)
    val showContinue = !searching && path.isEmpty()
    val compact = LocalCompact.current
    val subtitle = remember(head, counts, snapshot.collections) { if (head == null) null else if (tabs) spaceSubtitle(counts.total, snapshot.collections.filter { it.space == space.id }, space.countLabel(1).substringAfter(' ')) else "${space.countLabel(counts.total)} · ${Format.bytes(counts.bytes)}" }
    SideEffect { ui.selection.publishQuery(listed, listedCount) }

    Column(Modifier.fillMaxSize()) {
        SpaceToolbar(
            ui, space, if (showTitle) space.name else null, subtitle, query, { query = it },
            sort = sort, onSort = { vm.prefs.chooseLibrarySort(space.id, it.name) }, grid = grid, onGrid = { vm.prefs.chooseLibraryGrid(space.id, it) },
            canSelect = listedCount > 0,
        )
        val known = head ?: return@Column
        if (tabs && counts.total > 0 && !ui.selection.mode && !searching) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("media-tabs"), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                listOf(MusicTab.SONGS to (if (space.kind == app.keepfin.SpaceKind.VIDEO) "Videos" else "Items"), MusicTab.COLLECTIONS to "Collections").forEach { (option, label) ->
                    FilterPill(label, tab == option, { vm.chooseMusicTab(space.id, option); ui.selection.clear() }, Modifier.testTag(UiTags.libTab(option.name.lowercase())))
                }
            }
        }
        if (kinds.size > 1 && !ui.selection.mode && tab == MusicTab.SONGS) KindChips(effectiveFilter, kinds) { filter = it }
        if (tab == MusicTab.SONGS && !ui.selection.mode && !searching) TagChipRow(known.tags, tagFilter, { tagList = it.tags; tagAll = it.all }) { tagSheet = true }
        val filteredEmpty = listedCount == 0 && folders.isEmpty() && continueItems.isEmpty()
        when {
            counts.total == 0 -> SpaceEmpty(ui, space, Modifier.weight(1f))
            tab == MusicTab.COLLECTIONS -> Box(Modifier.weight(1f).fillMaxWidth()) { CollectionsContent(ui, space, padding) }
            tagFilter.active && filteredEmpty && !searching -> EmptyState(art = EmptyArt.SEARCH, title = "Nothing has these tags", body = "Change the tag filter to see more of ${space.name}.", modifier = Modifier.weight(1f))
            searching && listedCount == 0 -> EmptyState(art = EmptyArt.SEARCH, title = "Nothing matches", body = "No item in ${space.name} matches “${query.trim()}”.", modifier = Modifier.weight(1f))
            filteredEmpty -> EmptyState(art = EmptyArt.LIBRARY, title = "Nothing here", body = "This folder is empty.", modifier = Modifier.weight(1f))
            else -> {
                if (grid) PagedPosterGrid(ui, listed, padding, if (compact) 2 else 1, Modifier.weight(1f)) { _ ->
                    if (folders.isNotEmpty()) {
                        if (continueItems.isNotEmpty() && showContinue || listedCount > 0) item(span = { GridItemSpan(maxLineSpan) }, key = "folders-head") { Text("Folders", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 4.dp).semantics { heading() }) }
                        items(folders, key = { "folder-" + it.path }) { card -> FolderCardView(card) { vm.openLibraryDetail(LibraryRoute.Folder(space.id, card.path)) } }
                    }
                    if (showContinue && continueItems.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "continue-head") { Text("Continue watching", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 4.dp).semantics { heading() }) }
                        item(span = { GridItemSpan(maxLineSpan) }, key = "continue-row") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { items(continueItems, key = { it.id }) { item -> PosterCard(item, ui, Modifier.width(180.dp)) { vm.play(continueItems, continueItems.indexOf(item)) } } }
                        }
                    }
                    if (listedCount > 0 && folders.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "loose-head") { Text("Files", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 4.dp).semantics { heading() }) }
                } else PagedItemsColumn(ui, listed, padding, Modifier.weight(1f), publishSelection = false, header = { _ ->
                    if (showContinue && continueItems.isNotEmpty()) {
                        item("continue-head") { Text("Continue watching", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp).semantics { heading() }) }
                        item("continue-row") {
                            LazyRow(contentPadding = PaddingValues(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(continueItems, key = { it.id }) { item -> PosterCard(item, ui, Modifier.width(180.dp)) { vm.play(continueItems, continueItems.indexOf(item)) } }
                            }
                        }
                    }
                    items(folders, key = { "folder-" + it.path }) { card ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { vm.openLibraryDetail(LibraryRoute.Folder(space.id, card.path)) }.padding(horizontal = 16.dp, vertical = 6.dp).testTag("folder-${card.path}"), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(card.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (card.count == 1) "1 item" else "${card.count} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                })
            }
        }
    }
    if (tagSheet) TagFilterSheet(
        title = space.name, ui = ui, base = base, filter = tagFilter, onFilter = { tagList = it.tags; tagAll = it.all },
        onPlay = { ui.playQuery(base.withTags(tagFilter)) },
        onSaveSmart = { f -> ui.sheet(LibrarySheet.NewSmart(app.keepfin.TagSmart.suggestedName(f.tags.toSet()), app.keepfin.TagSmart.rules(f.tags.toSet(), f.all, null))) },
        onDismiss = { tagSheet = false },
    )
}

/** [query] narrowed by the kind chips (shown when it holds two or more kinds): the query to list, and the chips to draw above it. */
@Composable
internal fun kindFiltered(ui: LibraryUi, key: Any, query: ItemQuery): Pair<ItemQuery, @Composable () -> Unit> {
    val counts by rememberCounts(ui.browse, query)
    var filter by rememberSaveable(key) { mutableStateOf(ContentFilter.ALL) }
    val kinds = remember(counts) { ContentFilter.entries.filter { it != ContentFilter.ALL && counts.of(it.kind()!!) > 0 } }
    val chosen = if (filter in kinds) filter else ContentFilter.ALL
    val listed = remember(query, chosen) { chosen.kind()?.let { query.copy(kinds = setOf(it)) } ?: query }
    return listed to { if (kinds.size > 1 && !ui.selection.mode) KindChips(chosen, kinds) { filter = it } }
}

@Composable
internal fun KindChips(selected: ContentFilter, kinds: List<ContentFilter>, onSelect: (ContentFilter) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag(UiTags.LIB_FILTER_CHIPS), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        (listOf(ContentFilter.ALL) + kinds).forEach { option ->
            FilterPill(option.label, selected == option, { onSelect(option) }, Modifier.testTag("kind-filter-${option.name.lowercase()}"))
        }
    }
}

/** A collection folder of a video or mixed space (or, for music spaces, a collection playlist). */
@Composable
fun FolderScreen(ui: LibraryUi, space: Space, path: String, padding: PaddingValues) {
    val base = remember(space.id, path) { ItemQuery(spaces = listOf(space.id), subtree = path) }
    if (space.kind == app.keepfin.SpaceKind.MUSIC) {
        // a folder of songs is a bounded list (an album or a playlist folder), ordered by natural title
        val songs by rememberBrowsed<List<LibraryItem>?>(ui.browse, base, null) { b -> b.top(base.copy(kinds = emptySet(), songs = true), 5000).sortedWith(LibraryViews.naturalTitles()) }
        SongsDetail(ui, path.substringAfterLast('/'), songs ?: return, padding, empty = "No songs are in this folder.")
        return
    }
    val counts by rememberCounts(ui.browse, base)
    val playable by rememberBrowsed(ui.browse, base, emptyList<LibraryItem>()) { b -> b.top(base.copy(kinds = setOf(LibraryItemKind.VIDEO, LibraryItemKind.AUDIO)), 2000).sortedWith(LibraryViews.naturalTitles()) }
    Column(Modifier.fillMaxSize()) {
        // While items are selected, the toolbar of the list below shows the selection bar instead.
        if (!ui.selection.mode) DetailHeader(ui, path.substringAfterLast('/'), if (counts.total == 1) "1 item" else "${counts.total} items") {
            if (playable.isNotEmpty()) androidx.compose.material3.TextButton(onClick = { ui.vm.play(playable, 0) }, modifier = Modifier.heightIn(min = 48.dp).testTag("play-folder")) {
                Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Play folder")
            }
        }
        MediaSpace(ui, space, path, base, padding, showTitle = false)
    }
}