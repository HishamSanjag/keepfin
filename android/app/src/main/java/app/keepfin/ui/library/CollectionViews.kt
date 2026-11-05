package app.keepfin.ui.library

import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import app.keepfin.ui.components.FilterPill
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import app.keepfin.ui.StudioSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import app.keepfin.ui.theme.animationSpec
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.CollectionKind
import app.keepfin.CollectionLogic
import app.keepfin.CollectionText
import app.keepfin.Follow
import app.keepfin.FollowMode
import app.keepfin.FollowUpdate
import app.keepfin.LibraryCollection
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.RuleText
import app.keepfin.Space
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.EmptyArt
import app.keepfin.ui.components.EmptyState
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.theme.tabular

/** Where a collection opens: your own lists keep their editable page, downloaded ones get the collection detail. */
fun collectionRoute(c: LibraryCollection): LibraryRoute =
    if (c.kind == CollectionKind.OWN) LibraryRoute.UserPlaylist(c.id) else LibraryRoute.Collection(c.space, c.id)

/** The line under a collection card on home: what is new, else its kind and size. */
fun collectionCardLine(c: LibraryCollection, present: Int): String {
    val pending = c.follow?.pending?.count ?: 0
    return when {
        c.newItemIds.isNotEmpty() -> "${c.newItemIds.size} new"
        pending > 0 -> "Review $pending"
        else -> CollectionText.kindLabel(c) + " · " + present
    }
}

private fun noun(items: List<LibraryItem>, plural: Boolean = true): String {
    val kinds = items.map { it.kind }.distinct()
    return when (kinds.singleOrNull()) {
        LibraryItemKind.AUDIO -> if (plural) "songs" else "song"
        LibraryItemKind.VIDEO -> if (plural) "videos" else "video"
        LibraryItemKind.IMAGE -> if (plural) "photos" else "photo"
        else -> if (plural) "items" else "item"
    }
}

private fun playable(items: List<LibraryItem>) = items.filter { it.kind == LibraryItemKind.AUDIO || it.kind == LibraryItemKind.VIDEO }

// ---- lists of collections ---------------------------------------------------------------------------

/** One collection in a list: cover, name with a "N new" badge, kind · count · follow status, and a play button. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionRow(c: LibraryCollection, ui: LibraryUi, modifier: Modifier = Modifier) {
    // the cover and the kind of its items come from a few members, read when the row appears
    val sampleIds = remember(c.coverItemId, c.itemIds.firstOrNull(), c.itemIds.size) { (listOfNotNull(c.coverItemId) + c.itemIds.take(8)).distinct() }
    val present by rememberByIds(ui.browse, sampleIds)
    val n = noun(present, plural = false)
    val badge = when {
        c.newItemIds.isNotEmpty() -> "${c.newItemIds.size} new"
        (c.follow?.pending?.count ?: 0) > 0 -> "Review ${c.follow?.pending?.count}"
        else -> null
    }
    val compact = LocalCompact.current
    var menu by remember { mutableStateOf(false) }
    Box {
    Row(
        modifier.fillMaxWidth().heightIn(min = if (compact) 64.dp else 72.dp)
            .combinedClickable(
                role = Role.Button, onClickLabel = "Open ${c.name}", onClick = { ui.vm.openLibraryDetail(collectionRoute(c)) },
                onLongClickLabel = "Move or copy to a space", onLongClick = { if (c.kind != CollectionKind.OWN) menu = true },
            )
            .semantics { if (c.kind != CollectionKind.OWN) customActions = collectionSpaceActions(ui, c) }
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp).testTag(UiTags.collectionRow(c.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val cover = present.firstOrNull { it.id == c.coverItemId } ?: present.firstOrNull()
        ArtTile(cover?.artModel(), c.name, cover?.kind ?: LibraryItemKind.AUDIO, Modifier.size(if (compact) 48.dp else 56.dp), MaterialTheme.shapes.small)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (badge != null) Text(
                    badge, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 7.dp, vertical = 1.dp),
                )
            }
            Text(CollectionText.rowSubtitle(c, c.itemIds.size, n), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        IconButton(
            onClick = { ui.playIds(c.itemIds) }, enabled = c.itemIds.isNotEmpty(),
            modifier = Modifier.size(48.dp).testTag(UiTags.collectionPlay(c.id)),
        ) { Icon(Icons.Outlined.PlayArrow, contentDescription = "Play ${c.name}") }
    }
    DropdownMenu(menu, { menu = false }) { CollectionSpaceItems(c, ui) { menu = false } }
    }
}

/** Move / Copy to space… for a downloaded collection (your own lists are not offered). */
@Composable
fun CollectionSpaceItems(c: LibraryCollection, ui: LibraryUi, close: () -> Unit) {
    DropdownMenuItem(
        text = { Text("Move to space…") }, leadingIcon = { Icon(Icons.Outlined.DriveFileMove, null) },
        onClick = { close(); ui.sheet(LibrarySheet.ToSpace(listOf(c.id), emptyList(), false, c.space, c.name)) }, modifier = Modifier.testTag("collection-move-space"),
    )
    DropdownMenuItem(
        text = { Text("Copy to space…") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) },
        onClick = { close(); ui.sheet(LibrarySheet.ToSpace(listOf(c.id), emptyList(), true, c.space, c.name)) }, modifier = Modifier.testTag("collection-copy-space"),
    )
}

private fun collectionSpaceActions(ui: LibraryUi, c: LibraryCollection) = listOf(
    CustomAccessibilityAction("Move ${c.name} to a space") { ui.sheet(LibrarySheet.ToSpace(listOf(c.id), emptyList(), false, c.space, c.name)); true },
    CustomAccessibilityAction("Copy ${c.name} to a space") { ui.sheet(LibrarySheet.ToSpace(listOf(c.id), emptyList(), true, c.space, c.name)); true },
)

/** The Collections tab of a space: its collections, "+ New playlist", then folders and smart lists (music spaces). */
@Composable
fun CollectionsContent(ui: LibraryUi, space: Space, padding: PaddingValues) {
    val snapshot by ui.vm.lib.library.collectAsStateWithLifecycle()
    val collections = remember(snapshot.collections, space.id) {
        snapshot.collections.filter { it.space == space.id }.sortedWith(compareByDescending<LibraryCollection> { it.newItemIds.size + (it.follow?.pending?.count ?: 0) > 0 }.thenBy { LibraryViews.fold(it.name) })
    }
    val music = space.kind == app.keepfin.SpaceKind.MUSIC
    // the folders no collection stands for: a collection lives in one folder, so its first item says which
    val firstIds = remember(collections) { collections.mapNotNull { it.itemIds.firstOrNull() } }
    val kinds = remember(music) { if (music) setOf(LibraryItemKind.AUDIO, LibraryItemKind.VIDEO) else emptySet() }
    val allFolders by rememberBrowsed(ui.browse, space.id to kinds, emptyList<app.keepfin.FolderGroup>()) { it.folderList(space.id, kinds) }
    val covered by rememberBrowsed(ui.browse, firstIds, emptySet<String>()) { b -> b.byIds(firstIds).mapTo(HashSet()) { it.folder.trim('/') } }
    val folders = remember(allFolders, covered) { allFolders.filter { it.path !in covered }.map { it.path to it } }
    val smartCounts by rememberBrowsed(ui.browse, space.id, emptyMap<LibraryViews.Smart, Int>()) { b ->
        if (!music) emptyMap() else LibraryViews.Smart.entries.associateWith { b.count(smartQuery(it, listOf(space.id))) }
    }
    LazyColumn(Modifier.fillMaxSize().testTag(UiTags.LIB_LIST), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
        items(collections, key = { "collection-" + it.id }) { c -> CollectionRow(c, ui) }
        item("new-collection") {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { ui.sheet(LibrarySheet.NewPlaylist(emptyList(), space.id)) }.padding(horizontal = 16.dp).testTag(UiTags.COLLECTION_NEW_ROW),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(48.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Column {
                    Text("+ New playlist", style = MaterialTheme.typography.bodyLarge, color = app.keepfin.ui.theme.Studio.colors.accentText)
                    Text("Name it, then add items from anywhere", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (folders.isNotEmpty()) {
            item("folders-head") { SmallHeading("Folders") }
            items(folders, key = { "folder-" + it.first }) { (path, group) ->
                SimpleRow(Icons.Outlined.Folder, path, space.countLabel(group.count), Modifier.testTag("folder-$path")) { ui.vm.openLibraryDetail(LibraryRoute.Folder(space.id, path)) }
            }
        }
        if (music) {
            item("smart-head") { SmallHeading("Quick lists") }
            items(LibraryViews.Smart.entries, key = { "smart-" + it.name }) { kind ->
                val count = smartCounts[kind] ?: 0
                SimpleRow(smartListIcon(kind), kind.label, if (count == 0) kind.hint else if (count == 1) "1 song" else "$count songs", Modifier.testTag("smart-${kind.name}")) {
                    ui.vm.openLibraryDetail(LibraryRoute.SmartList(kind, space.id))
                }
            }
        }
    }
}

@Composable
private fun SmallHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() })
}

@Composable
private fun SimpleRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun smartListIcon(kind: LibraryViews.Smart) = when (kind) {
    LibraryViews.Smart.RECENTLY_ADDED -> Icons.Outlined.Add
    LibraryViews.Smart.RECENTLY_PLAYED -> Icons.Outlined.Sync
    LibraryViews.Smart.MOST_PLAYED -> Icons.Outlined.Tune
    LibraryViews.Smart.NEVER_PLAYED -> Icons.Outlined.Folder
    LibraryViews.Smart.FAVORITES -> Icons.AutoMirrored.Outlined.PlaylistPlay
}

/** "See all" of the Collections shelf: every collection of the library, news first. */
@Composable
fun CollectionsScreen(ui: LibraryUi, spaces: List<Space>, padding: PaddingValues) {
    val snapshot by ui.vm.lib.library.collectAsStateWithLifecycle()
    val shelf = remember(snapshot.collections) { app.keepfin.HubLogic.shelf(snapshot.collections) }
    var sort by rememberSaveable { mutableStateOf(app.keepfin.CollectionSort.SHELF) }
    var filter by rememberSaveable { mutableStateOf(app.keepfin.CollectionFilter.ALL) }
    var menu by remember { mutableStateOf(false) }
    val list = remember(shelf, sort, filter) { app.keepfin.CollectionListLogic.apply(shelf, sort, filter) }
    Column(Modifier.fillMaxSize()) {
        DetailHeader(ui, "Collections", "${list.size} ${if (list.size == 1) "collection" else "collections"}") {
            IconButton(onClick = { ui.sheet(LibrarySheet.NewPlaylist(emptyList())) }, modifier = Modifier.size(48.dp).testTag("collections-new")) {
                Icon(Icons.Outlined.Add, contentDescription = "New playlist")
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).testTag("collections-menu")) { Icon(Icons.Outlined.MoreVert, contentDescription = "Sort and filter collections") }
                DropdownMenu(menu, { menu = false }) {
                    Text("Sort by", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    app.keepfin.CollectionSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) }, leadingIcon = { RadioButton(option == sort, onClick = null) },
                            onClick = { menu = false; sort = option }, modifier = Modifier.testTag("collections-sort-${option.name}"),
                        )
                    }
                    androidx.compose.material3.HorizontalDivider()
                    Text("Show", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    app.keepfin.CollectionFilter.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) }, leadingIcon = { RadioButton(option == filter, onClick = null) },
                            onClick = { menu = false; filter = option }, modifier = Modifier.testTag("collections-filter-${option.name}"),
                        )
                    }
                }
            }
        }
        if (list.isEmpty() && shelf.isNotEmpty()) EmptyState(art = EmptyArt.COLLECTION, title = "No ${filter.label.lowercase()} here", body = "Choose All in the menu to see every collection.", modifier = Modifier.weight(1f).testTag("collections-filtered-empty"))
        else if (list.isEmpty()) EmptyState(art = EmptyArt.COLLECTION, title = "No collections yet", body = "Playlists and albums you download land here, and you can make your own lists.", modifier = Modifier.weight(1f))
        else LazyColumn(Modifier.fillMaxSize().testTag(UiTags.LIB_LIST), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
            items(list, key = { "collection-" + it.id }) { c -> CollectionRow(c, ui) }
        }
    }
}

// ---- collection detail ------------------------------------------------------------------------------

/**
 * A downloaded playlist, album or channel: cover, kind and space, the follow card ("Following · every 6 hours", Check now, Review N
 * new), Play and Shuffle, a "New · N" section, then every item in source order. Items removed upstream stay, dimmed with
 * "Removed from the playlist · kept here". Opening the page tells the library its news were seen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectionDetailScreen(ui: LibraryUi, route: LibraryRoute.Collection, padding: PaddingValues) {
    val snapshot by ui.vm.lib.library.collectAsStateWithLifecycle()
    val spaces by ui.vm.engine.settings.collectAsStateWithLifecycle()
    val collection = snapshot.collections.firstOrNull { it.id == route.id }
    // Opening clears the collection's "new" marks; the page keeps showing what was new when it opened.
    var freshIds by remember(route.id) { mutableStateOf<List<String>?>(null) }
    // Items a check adds while the page is open join the section too (they are marked new as they finish downloading).
    val liveNew = collection?.newItemIds.orEmpty()
    if (collection != null && (freshIds == null || liveNew.any { it !in freshIds.orEmpty() })) freshIds = (freshIds.orEmpty() + liveNew).distinct()
    LaunchedEffect(route.id, liveNew) { ui.vm.libraryActions.collectionOpened(route.id) }
    // Only the ids of this collection's running downloads are read here; the strip and rows read progress themselves, so a tick redraws only them.
    val jobs = ui.vm.engine.jobs.collectAsStateWithLifecycle()
    val activeIds by remember(route.id) {
        androidx.compose.runtime.derivedStateOf(androidx.compose.runtime.structuralEqualityPolicy()) { CollectionLogic.downloadProgress(jobs.value, route.id)?.active?.map { it.id }.orEmpty() }
    }
    // a collection is bounded (a playlist, an album, a channel page): its items are read together, off the main thread
    val ids = remember(collection?.itemIds) { collection?.itemIds?.distinct().orEmpty() }
    val read by rememberBrowsed<List<LibraryItem>?>(ui.browse, ids, null) { it.byIds(ids) }
    val all = read.orEmpty()
    val removed = remember(collection) { collection?.removedItemIds.orEmpty().toSet() }
    val fresh = remember(all, freshIds) { all.filter { it.id in freshIds.orEmpty() } }
    val player by ui.vm.player.ui.collectAsStateWithLifecycle()
    SideEffect { ui.selection.publish(all) }
    if (collection == null) {
        Column(Modifier.fillMaxSize()) { DetailHeader(ui, "Collection", null); EmptyState(art = EmptyArt.COLLECTION, title = "This collection is gone", body = "It was removed. Its files are still in your library.") }
        return
    }
    val words = noun(all)
    val spaceName = spaces.library.spaces.firstOrNull { it.id == collection.space }?.name
    val kindWord = when (collection.kind) { CollectionKind.ALBUM -> "album"; CollectionKind.CHANNEL -> "channel"; else -> "playlist" }
    val shown = remember(collection, freshIds) { collection.copy(newItemIds = freshIds.orEmpty()) }
    var menu by remember { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val list = remember(all) { playable(all) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    BandedDetail(
        ui, collection.name, null, (all.firstOrNull { it.id == collection.coverItemId }?.artModel() ?: all.coverModel()), collection.space, listState,
        modifier = Modifier.testTag(UiTags.COLLECTION_DETAIL),
        trailing = {
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("collection-menu")) { Icon(Icons.Outlined.MoreVert, contentDescription = "Collection actions") }
                DropdownMenu(menu, { menu = false }) {
                    if (CollectionLogic.followable(collection)) DropdownMenuItem(
                        text = { Text("Follow settings…") }, leadingIcon = { Icon(Icons.Outlined.Sync, null) },
                        onClick = { menu = false; ui.sheet(LibrarySheet.Follow(collection.id)) }, modifier = Modifier.testTag("collection-menu-follow"),
                    )
                    CollectionSpaceItems(collection, ui) { menu = false }
                    DropdownMenuItem(
                        text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) },
                        onClick = { menu = false; ui.sheet(LibrarySheet.RenamePlaylist(collection.id, collection.name, collection.itemIds)) },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove collection", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; confirmRemove = true }, modifier = Modifier.testTag("collection-remove"),
                    )
                }
            }
        },
    ) {
        LazyColumn(Modifier.fillMaxSize().testTag(UiTags.LIB_LIST), state = listState, contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
            item("head", contentType = "head") {
                val total = LibraryViews.totalDuration(all.filter { it.kind == LibraryItemKind.AUDIO || it.kind == LibraryItemKind.VIDEO })
                val cover = all.firstOrNull { it.id == collection.coverItemId }
                CollectionHeaderBlock(
                    ui, collection.name, listOfNotNull(CollectionText.kindLabel(collection), spaceName).joinToString(" · "),
                    listOfNotNull(RuleText.countLabel(all), LibraryViews.spoken(total).takeIf { total > 0 }).joinToString(" · "),
                    listOfNotNull(cover) + all, collection.space, playable = list,
                    extra = if (CollectionLogic.followable(collection)) ({ KeepInSyncButton { ui.sheet(LibrarySheet.Follow(collection.id)) } }) else null,
                )
            }
            if (CollectionLogic.followable(collection)) item("follow", contentType = "follow") { FollowCard(ui, shown, words.removeSuffix("s")) }
            if (activeIds.isNotEmpty()) item("follow-progress", contentType = "progress") { FollowProgressStrip(jobs, route.id) }
            if (fresh.isNotEmpty() || activeIds.isNotEmpty()) {
                item("new-head", contentType = "head") {
                    Text("New · ${fresh.size + activeIds.size}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp).testTag(UiTags.COLLECTION_NEW_SECTION).semantics { heading() })
                }
                items(activeIds, key = { "active-" + it }, contentType = { "active" }) { id -> ActiveDownloadRow(jobs, id) }
                items(fresh, key = { "new-" + it.id }, contentType = { "item" }) { song ->
                    ItemRow(song, ui, "${subtitleOf(song)} · added recently".removePrefix(" · "), playing = player.current?.id == song.id, onClick = { ui.vm.play(fresh, fresh.indexOf(song)) })
                }
            }
            item("all-head", contentType = "head") {
                Text(
                    "All $words · in $kindWord order", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp).semantics { heading() },
                )
            }
            if (read != null && all.isEmpty()) item("empty") { Text("Its items were moved or deleted.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp)) }
            items(all, key = { "all-" + it.id }, contentType = { "item" }) { song ->
                val gone = song.id in removed
                ItemRow(
                    song, ui, if (gone) "Removed from the $kindWord · kept here" else subtitleOf(song),
                    modifier = if (gone) Modifier.alpha(0.6f) else Modifier, playing = player.current?.id == song.id,
                    onClick = { ui.vm.play(all, all.indexOf(song)) },
                )
            }
        }
    }
    if (confirmRemove) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmRemove = false }, title = { Text("Remove “${collection.name}”?") },
        text = { Text("The collection and its follow settings go away. The files stay in your library.") },
        confirmButton = {
            Button(
                onClick = { confirmRemove = false; ui.vm.libraryActions.deleteCollection(collection.id, collection.name) }, modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text("Remove collection") }
        },
        dismissButton = { TextButton(onClick = { confirmRemove = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep") } },
    )
}

/**
 * "Downloading 2 of 3 new" with one bar for the whole batch, under the follow card while any download of the collection is queued or running.
 * The bar follows the engine's reported values through the truth spring (critically damped: it never gets ahead of them); the strip goes when the batch is done.
 */
@Composable
private fun FollowProgressStrip(jobs: androidx.compose.runtime.State<List<app.keepfin.Job>>, collectionId: String) {
    val progress = CollectionLogic.downloadProgress(jobs.value, collectionId) ?: return
    val shown by androidx.compose.animation.core.animateFloatAsState(progress.fraction, app.keepfin.ui.theme.Motion.truth.animationSpec(), label = "follow-progress")
    Surface(
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag(UiTags.FOLLOW_PROGRESS),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(progress.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            androidx.compose.material3.LinearProgressIndicator(
                progress = { shown.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().semantics { stateDescription = "${(progress.fraction * 100).toInt()} percent" },
                color = if (Studio.colors.dark) MaterialTheme.colorScheme.primary else Studio.colors.action, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, drawStopIndicator = {},
            )
        }
    }
}

/** One download of the new batch, in the New section, with its state until the song is in the library. */
@Composable
private fun ActiveDownloadRow(jobs: androidx.compose.runtime.State<List<app.keepfin.Job>>, id: String) {
    val job = jobs.value.firstOrNull { it.id == id } ?: return
    val state = when (job.status) {
        app.keepfin.JobStatus.DOWNLOADING -> "Downloading ${job.percent.toInt().coerceIn(0, 100)}%"
        app.keepfin.JobStatus.PROCESSING -> "Finishing up"
        else -> "Waiting to download"
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 6.dp).semantics(mergeDescendants = true) {}.testTag(UiTags.FOLLOW_ACTIVE_ROW),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(44.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Sync, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(job.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** "Following · every 6 hours", when it was checked, Check now and, when a check held items back, Review N new. */
@Composable
private fun FollowCard(ui: LibraryUi, c: LibraryCollection, noun: String) {
    val follow = c.follow
    val pending = follow?.pending
    Surface(
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag(UiTags.FOLLOW_CARD),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 10.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button, onClickLabel = "Change follow settings") { ui.sheet(LibrarySheet.Follow(c.id)) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Outlined.Sync, contentDescription = null, tint = if (follow?.mode == FollowMode.AUTO) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(CollectionText.followLine(c) ?: "Not following", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        CollectionText.checkedLine(c, noun), fontSize = 12.sp, lineHeight = 16.sp,
                        color = if (follow?.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                Icon(Icons.Outlined.Tune, contentDescription = "Follow settings", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp).size(20.dp))
            }
            if (pending != null) Text(
                "${pending.count} new ${if (pending.count == 1) noun else noun + "s"} found. That is more than you chose to download without asking.",
                fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 36.dp, end = 8.dp, bottom = 6.dp),
            )
            Row(Modifier.fillMaxWidth().padding(start = 36.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { ui.vm.libraryActions.checkCollection(c.id, c.name) }, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.FOLLOW_CHECK),
                ) { Text("Check now", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                if (pending != null) Button(
                    onClick = { ui.vm.libraryActions.reviewCollection(c.id, pending.count) }, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.FOLLOW_REVIEW),
                    colors = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction),
                ) { Text("Review ${pending.count} new", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
            }
        }
    }
}

// ---- follow settings --------------------------------------------------------------------------------

private val intervals = listOf(1, 6, 12, 24, 168)
private val askOptions = listOf(5, 10, 20, 50, 100)

/** Per-collection follow settings: the link, Automatically / When I tap Check now / Don't follow, and the automatic options. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowSheet(ui: LibraryUi, collectionId: String, onDismiss: () -> Unit) {
    val snapshot by ui.vm.lib.library.collectAsStateWithLifecycle()
    val c = snapshot.collections.firstOrNull { it.id == collectionId }
    if (c == null) { onDismiss(); return }
    val start = c.follow ?: Follow()
    var mode by rememberSaveable { mutableStateOf(start.mode) }
    var hours by rememberSaveable { mutableIntStateOf(CollectionLogic.interval(start.intervalHours)) }
    var wifi by rememberSaveable { mutableStateOf(start.wifiOnly) }
    var notify by rememberSaveable { mutableStateOf(start.notify) }
    var ask by rememberSaveable { mutableIntStateOf(start.confirmAbove) }
    val auto = mode == FollowMode.AUTO
    StudioSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag(UiTags.FOLLOW_SHEET)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Follow ${c.name}", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).semantics { heading() })
                Button(
                    onClick = {
                        ui.vm.libraryActions.followCollection(
                            c.id, FollowUpdate(mode = mode, intervalHours = hours, wifiOnly = wifi, notify = notify, confirmAbove = ask),
                            saved = if (mode == FollowMode.OFF) "Not following “${c.name}” any more" else "Saved follow settings for “${c.name}”",
                        )
                        onDismiss()
                    },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.FOLLOW_SAVE),
                    colors = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction),
                ) { Text("Save") }
            }
            Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                c.source?.let { source ->
                    Text("Link", style = MaterialTheme.typography.labelLarge)
                    Text(source.url.removePrefix("https://").removePrefix("http://").removePrefix("www."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModeCard("Automatically", "Check on a schedule and download new items", mode == FollowMode.AUTO, UiTags.FOLLOW_AUTO) { mode = FollowMode.AUTO }
                    ModeCard("When I tap Check now", "Nothing runs in the background", mode == FollowMode.MANUAL, UiTags.FOLLOW_MANUAL) { mode = FollowMode.MANUAL }
                    ModeCard("Don't follow", "Keep the songs and the link, never check", mode == FollowMode.OFF, UiTags.FOLLOW_OFF) { mode = FollowMode.OFF }
                }
                Text("When following automatically", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                Column(Modifier.alpha(if (auto) 1f else .5f)) {
                    Text("Check every", style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        intervals.forEach { h ->
                            FilterPill(CollectionText.intervalLabel(h).replace(" hours", " h").replace(" hour", " h").replace("7 days", "7 d"), hours == h, { if (auto) hours = h }, Modifier.testTag("follow-every-$h"))
                        }
                    }
                    ToggleLine("Only on Wi-Fi", wifi, auto, UiTags.FOLLOW_WIFI) { wifi = it }
                    ToggleLine("Notify me about new items", notify, auto, UiTags.FOLLOW_NOTIFY) { notify = it }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Ask before downloading more than", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        var open by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { if (auto) open = true }, enabled = auto, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.FOLLOW_ASK)) { Text("$ask ▾", fontWeight = FontWeight.SemiBold) }
                            DropdownMenu(open, { open = false }) {
                                (askOptions + ask).distinct().sorted().forEach { n -> DropdownMenuItem(text = { Text("$n at once") }, onClick = { ask = n; open = false }) }
                            }
                        }
                    }
                }
                Text(
                    "Automatic checks run in the background on a schedule. Android picks the exact moment to save battery. Manual only checks when you tap Check now.",
                    fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "Items you delete are remembered and not downloaded again. Items removed from the ${if (c.kind == CollectionKind.ALBUM) "album" else if (c.kind == CollectionKind.CHANNEL) "channel" else "playlist"} stay on your phone.",
                    fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun ModeCard(title: String, body: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp), color = if (selected) colors.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant),
        modifier = Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onClick).testTag(tag),
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(body, fontSize = 12.sp, lineHeight = 16.sp, color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ToggleLine(title: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        androidx.compose.material3.Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
