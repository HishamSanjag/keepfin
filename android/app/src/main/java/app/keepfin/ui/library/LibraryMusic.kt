package app.keepfin.ui.library

import app.keepfin.ui.home.ArtistFollowButton
import androidx.compose.material.icons.outlined.LibraryAdd
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import app.keepfin.TagLogic
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.text.font.FontWeight
import app.keepfin.ui.Format
import app.keepfin.ui.components.FilterPill
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.ItemOrder
import app.keepfin.ItemQuery
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.Space
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.EmptyArt
import app.keepfin.ui.components.EmptyState
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.theme.tabular

private fun smartIcon(kind: LibraryViews.Smart) = when (kind) {
    LibraryViews.Smart.RECENTLY_ADDED -> Icons.Outlined.NewReleases
    LibraryViews.Smart.RECENTLY_PLAYED -> Icons.Outlined.Schedule
    LibraryViews.Smart.MOST_PLAYED -> Icons.Outlined.TrendingUp
    LibraryViews.Smart.NEVER_PLAYED -> Icons.Outlined.AutoAwesome
    LibraryViews.Smart.FAVORITES -> Icons.Outlined.Favorite
}

/** A space routes to the music layout or the video / mixed layout by its kind. */
@Composable
fun SpaceScreen(ui: LibraryUi, space: Space, padding: PaddingValues) {
    if (space.kind == app.keepfin.SpaceKind.MUSIC) MusicSpace(ui, space, padding) else MediaSpace(ui, space, "", ItemQuery(spaces = listOf(space.id)), padding)
}

/** The sort the user chose for a space, as the order the index reads. */
internal fun LibraryViews.Sort.order(): ItemOrder = when (this) {
    LibraryViews.Sort.ADDED -> ItemOrder.ADDED
    LibraryViews.Sort.TITLE -> ItemOrder.TITLE
    LibraryViews.Sort.ARTIST -> ItemOrder.ARTIST
    LibraryViews.Sort.DURATION -> ItemOrder.DURATION
    LibraryViews.Sort.SIZE -> ItemOrder.SIZE
    LibraryViews.Sort.MOST_PLAYED -> ItemOrder.MOST_PLAYED
}

/** A query narrowed by a tag filter. */
internal fun ItemQuery.withTags(filter: TagFilter): ItemQuery =
    if (!filter.active) this else if (filter.all) copy(tagsAll = filter.tags) else copy(tagsAny = filter.tags)

// ---- music space: Songs / Albums / Artists / Playlists ------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MusicSpace(ui: LibraryUi, space: Space, padding: PaddingValues) {
    val vm = ui.vm
    val base = remember(space.id) { ItemQuery(spaces = listOf(space.id), songs = true) }
    // counts and tags arrive together: until then nothing is drawn under the toolbar (no empty state for a frame, no row pushing the list)
    val head by rememberSpaceHead(ui.browse, base)
    val songCount = head?.counts?.total
    var query by rememberSaveable(space.id) { mutableStateOf("") }
    val tab = vm.musicTab(space.id)
    val sort = LibraryViews.Sort.of(vm.prefs.librarySort(space.id))
    val grid = vm.prefs.libraryGrid(space.id + ":" + tab.name, tab == MusicTab.ALBUMS)
    val snapshot by vm.lib.library.collectAsStateWithLifecycle()
    var tagList by rememberSaveable(space.id) { mutableStateOf(emptyList<String>()) }
    var tagAll by rememberSaveable(space.id) { mutableStateOf(false) }
    var tagSheet by rememberSaveable(space.id) { mutableStateOf(false) }
    val tagFilter = remember(tagList, tagAll) { TagFilter(tagList, tagAll) }
    val songs = remember(base, tagFilter, query, sort) { base.withTags(tagFilter).copy(text = query, order = sort.order()) }
    val visibleCount by rememberCount(ui.browse, songs)
    val subtitle = remember(songCount, snapshot.collections) { songCount?.let { spaceSubtitle(it, snapshot.collections.filter { c -> c.space == space.id }, "song") } }
    val noSelection = remember { emptyList<LibraryItem>() }
    SideEffect { if (!(tab == MusicTab.SONGS || query.isNotBlank())) ui.selection.publish(noSelection) }

    Column(Modifier.fillMaxSize()) {
        SpaceToolbar(
            ui, space, space.name, subtitle, query, { query = it },
            sort = if (tab == MusicTab.SONGS || tab == MusicTab.ALBUMS) sort else null, onSort = { vm.prefs.chooseLibrarySort(space.id, it.name) },
            grid = if (tab == MusicTab.SONGS || tab == MusicTab.ALBUMS) grid else null, onGrid = { vm.prefs.chooseLibraryGrid(space.id + ":" + tab.name, it) },
            canSelect = tab == MusicTab.SONGS || query.isNotBlank(),
        )
        val known = head ?: return@Column
        if (songCount == 0) { SpaceEmpty(ui, space, Modifier.weight(1f)); return@Column }
        if (query.isBlank() && !ui.selection.mode) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag("music-tabs"), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                MusicTab.entries.forEach { option ->
                    FilterPill(option.label, tab == option, { vm.chooseMusicTab(space.id, option); ui.selection.clear() }, Modifier.testTag(UiTags.libTab(option.name.lowercase())))
                }
            }
            if (tab == MusicTab.SONGS) TagChipRow(known.tags, tagFilter, { tagList = it.tags; tagAll = it.all }) { tagSheet = true }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                query.isNotBlank() && visibleCount == 0 -> EmptyState(art = EmptyArt.SEARCH, title = "Nothing matches", body = "No song in ${space.name} matches “${query.trim()}”.")
                query.isNotBlank() -> PagedItemsColumn(ui, songs, padding)
                tab == MusicTab.SONGS && visibleCount == 0 -> EmptyState(art = EmptyArt.SEARCH, title = "No song has these tags", body = "Change the tag filter to see more of ${space.name}.")
                tab == MusicTab.SONGS -> if (grid) PagedCoverGrid(ui, songs, padding, if (LocalCompact.current) 3 else 2) { _ ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "play-buttons") { PlayButtons(ui, Songs.Rows(songs, visibleCount)) }
                } else PagedItemsColumn(ui, songs, padding, header = { _ -> playButtons(ui, Songs.Rows(songs, visibleCount)) })
                tab == MusicTab.ALBUMS -> AlbumsView(ui, space, sort, grid, padding)
                tab == MusicTab.ARTISTS -> ArtistsView(ui, space, padding)
                else -> CollectionsContent(ui, space, padding)
            }
        }
    }
    if (tagSheet) TagFilterSheet(
        title = space.name, ui = ui, base = base, filter = tagFilter, onFilter = { tagList = it.tags; tagAll = it.all },
        onPlay = { ui.playQuery(base.withTags(tagFilter)) },
        onSaveSmart = { f -> ui.sheet(LibrarySheet.NewSmart(app.keepfin.TagSmart.suggestedName(f.tags.toSet()), app.keepfin.TagSmart.rules(f.tags.toSet(), f.all, space = space.id))) },
        onDismiss = { tagSheet = false },
    )
}
/** "240 songs · 6 collections · following 2": the header line of a space. */
internal fun spaceSubtitle(items: List<LibraryItem>, collections: List<app.keepfin.LibraryCollection>, noun: String): String = spaceSubtitle(items.size, collections, noun)

internal fun spaceSubtitle(count: Int, collections: List<app.keepfin.LibraryCollection>, noun: String): String {
    val following = collections.count { it.follow?.mode == app.keepfin.FollowMode.AUTO && app.keepfin.CollectionLogic.followable(it) }
    return listOfNotNull(
        "$count ${if (count == 1) noun else noun + "s"}",
        "${collections.size} ${if (collections.size == 1) "collection" else "collections"}".takeIf { collections.isNotEmpty() },
        "following $following".takeIf { following > 0 },
    ).joinToString(" · ")
}

/**
 * The tag chip row of a space: "Tags ▾" opens the filter sheet, then the picked tags (tap to drop) and the most used [tags] (read with
 * the page's [SpaceHead], so the row is there from the first frame the list is).
 */
@Composable
fun TagChipRow(tags: List<app.keepfin.ScopedTag>, filter: TagFilter, onFilter: (TagFilter) -> Unit, onOpen: () -> Unit) {
    val top = remember(tags, filter.tags) { tags.filter { it.tag !in filter.tags }.take(6) }
    if (top.isEmpty() && !filter.active) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp).testTag("tag-chip-row"),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        TagChipPill(if (filter.active) "Tags (${filter.tags.size}) ▾" else "Tags ▾", null, filter.active, UiTags.TAGS_CHIP, onOpen)
        filter.tags.forEach { tag ->
            TagChipPill("$tag ✕", null, true, UiTags.tagChip(tag)) { onFilter(TagFilter(filter.tags - tag, filter.all)) }
        }
        top.forEach { scoped -> TagChipPill(scoped.tag, null, false, UiTags.tagChip(scoped.tag)) { onFilter(TagFilter(filter.tags + scoped.tag, filter.all)) } }
    }
}

fun LazyListScope.playButtons(ui: LibraryUi, songs: List<LibraryItem>) {
    // Selection mode has its own action bar; Play all / Shuffle would compete with it.
    if (songs.isEmpty() || ui.selection.mode) return
    item("play-buttons", contentType = "buttons") { PlayButtons(ui, songs) }
}

fun LazyListScope.playButtons(ui: LibraryUi, songs: Songs) {
    if (songs.isEmpty || ui.selection.mode) return
    item("play-buttons", contentType = "buttons") { PlayButtons(ui, songs) }
}

@Composable
fun PlayButtons(ui: LibraryUi, songs: List<LibraryItem>, modifier: Modifier = Modifier) = PlayButtons(ui, Songs.Fixed(songs), modifier)

@Composable
fun PlayButtons(ui: LibraryUi, songs: Songs, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { ui.play(songs) }, modifier = Modifier.weight(1f).testTag(UiTags.LIB_PLAY_ALL),
            colors = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction),
        ) { Icon(Icons.Outlined.PlayArrow, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Play all", fontWeight = FontWeight.SemiBold) }
        FilledTonalButton(onClick = { ui.shuffle(songs) }, modifier = Modifier.weight(1f).testTag(UiTags.LIB_SHUFFLE)) {
            Icon(Icons.Outlined.Shuffle, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Shuffle", fontWeight = FontWeight.SemiBold)
        }
    }
}
/** A list of songs with Now playing marks; optional track numbers, disc headers and drag-to-reorder (playlists). */
@Composable
fun SongsColumn(
    ui: LibraryUi,
    songs: List<LibraryItem>,
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    numbered: Boolean = false,
    discHeaders: Boolean = false,
    reorder: PlaylistReorder? = null,
    listState: androidx.compose.foundation.lazy.LazyListState = androidx.compose.foundation.lazy.rememberLazyListState(),
    header: LazyListScope.() -> Unit,
) {
    val player by ui.vm.player.ui.collectAsStateWithLifecycle()
    val currentId = player.current?.id
    // Drag to reorder works on this one list for the life of the page: a drag moves rows here, the new order is saved once when the finger
    // lifts, and the songs coming back from the library replace it only between drags (never the order from before the save).
    val order = remember(reorder != null) { mutableStateListOf<LibraryItem>().also { it.addAll(songs) } }
    val sync = remember(reorder != null) { OrderSync() }
    LaunchedEffect(songs, sync.dragging, sync.pending) {
        if (reorder == null || sync.dragging) return@LaunchedEffect
        if (!sync.accepts(songs.map { it.id })) return@LaunchedEffect
        if (order.toList() != songs) { order.clear(); order.addAll(songs) }
    }
    LaunchedEffect(sync.pending) { if (sync.pending != null) { kotlinx.coroutines.delay(4_000); sync.pending = null } }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val f = order.indexOfFirst { it.id == from.key }
        val t = order.indexOfFirst { it.id == to.key }
        if (f >= 0 && t >= 0) order.add(t, order.removeAt(f))
    }
    fun commit() {
        sync.dragging = false
        val ids = order.map { it.id }
        if (reorder != null && ids != songs.map { it.id }) { sync.pending = ids; reorder.onCommit(ids) }
    }
    val multipleDiscs = discHeaders && songs.map { it.disc ?: 1 }.distinct().size > 1
    LazyColumn(modifier.fillMaxSize().testTag(UiTags.LIB_LIST), state = listState, contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
        header()
        val list: List<LibraryItem> = if (reorder != null) order else songs
        var lastDisc: Int? = null
        list.forEachIndexed { index, song ->
            if (multipleDiscs && (song.disc ?: 1) != lastDisc) {
                lastDisc = song.disc ?: 1
                item("disc-$lastDisc", contentType = "disc") {
                    Text("Disc $lastDisc", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() })
                }
            }
            item(song.id, contentType = "song") {
                if (reorder == null) {
                    Box {
                        ItemRow(
                            song, ui, subtitleOf(song), leadingNumber = if (numbered) (song.track ?: (index + 1)).toString() else null,
                            playing = currentId == song.id, onClick = { ui.vm.play(list, index) },
                        )
                    }
                    return@item
                }
                ReorderableItem(reorderState, key = song.id) { dragged ->
                    val lift by androidx.compose.animation.core.animateDpAsState(if (dragged) 6.dp else 0.dp, label = "lift")
                    val handle = Modifier.draggableHandle(
                        onDragStarted = { sync.dragging = true; haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) },
                        onDragStopped = { commit() },
                    )
                    ItemRow(
                        song, ui, subtitleOf(song),
                        modifier = Modifier.graphicsLayer { shadowElevation = lift.toPx() }
                            .then(if (dragged) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier),
                        leadingNumber = if (numbered) (song.track ?: (index + 1)).toString() else null,
                        playing = currentId == song.id, onClick = { ui.vm.play(list, index) },
                        extra = {
                            Box(
                                Modifier.size(48.dp).testTag("playlist-handle-${song.id}")
                                    .semantics {
                                        contentDescription = "Reorder ${song.title}"
                                        customActions = listOf(
                                            CustomAccessibilityAction("Move up") { val i = order.indexOfFirst { it.id == song.id }; if (i > 0) { order.add(i - 1, order.removeAt(i)); commit(); true } else false },
                                            CustomAccessibilityAction("Move down") { val i = order.indexOfFirst { it.id == song.id }; if (i in 0 until order.lastIndex) { order.add(i + 1, order.removeAt(i)); commit(); true } else false },
                                        )
                                    }
                                    .then(handle),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Outlined.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        },
                    )
                }
            }
        }
    }
}

/** Drag to reorder on a collection of yours; "Remove from" is in each row's ⋮ and in the selection bar. */
class PlaylistReorder(val onCommit: (List<String>) -> Unit)

// ---- albums -------------------------------------------------------------------------------------------------

@Composable
private fun AlbumsView(ui: LibraryUi, space: Space, sort: LibraryViews.Sort, grid: Boolean, padding: PaddingValues) {
    val albums = rememberAlbums(ui, ItemQuery(spaces = listOf(space.id)), byArtist = sort == LibraryViews.Sort.ARTIST)
    if (albums.loaded && albums.items.isEmpty()) {
        EmptyState(art = EmptyArt.COLLECTION, title = "No albums yet", body = "Songs with an album tag are grouped here. Use Edit info on a song to add one.")
        return
    }
    if (grid) {
        val state = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
        albums.GridEndWatcher(state)
        LazyVerticalGrid(
            GridCells.Fixed(if (LocalCompact.current) 3 else 2), Modifier.fillMaxSize().testTag(UiTags.LIB_LIST), state = state,
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 6.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            items(albums.items, key = { it.key }) { album ->
                CoverCard(album.title, listOfNotNull(album.artist, album.year?.toString()).joinToString(" · "), album.cover?.artModel(), album.title, LibraryItemKind.AUDIO,
                    Modifier.testTag(UiTags.album(album.routeKey))) { ui.vm.openLibraryDetail(LibraryRoute.Album(space.id, album.routeKey)) }
            }
        }
    } else {
        val state = androidx.compose.foundation.lazy.rememberLazyListState()
        albums.EndWatcher(state)
        LazyColumn(Modifier.fillMaxSize().testTag(UiTags.LIB_LIST), state = state, contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
            items(albums.items, key = { it.key }) { album ->
                val compact = LocalCompact.current
                Row(
                    Modifier.fillMaxWidth().heightIn(min = if (compact) 64.dp else 72.dp).clickable { ui.vm.openLibraryDetail(LibraryRoute.Album(space.id, album.routeKey)) }.padding(horizontal = 16.dp, vertical = 6.dp).testTag(UiTags.album(album.routeKey)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArtTile(album.cover?.artModel(), album.title, LibraryItemKind.AUDIO, Modifier.size(if (compact) 52.dp else 60.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(album.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(album.artist, album.year?.toString(), "${album.tracks} songs").joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** An album page's data: the album and its tracks in disc and track order. */
private class AlbumData(val album: app.keepfin.AlbumGroup, val tracks: List<LibraryItem>)

@Composable
fun AlbumScreen(ui: LibraryUi, route: LibraryRoute.Album, padding: PaddingValues) {
    val read by rememberBrowsed<Loaded<AlbumData>?>(ui.browse, route, null) { browse ->
        Loaded(browse.album(route.space, route.key)?.let { a -> AlbumData(a, browse.top(ItemQuery(spaces = listOf(route.space), album = route.key, songs = true, order = ItemOrder.TRACK), 5000)) })
    }
    val reading = read ?: return
    val loaded = reading.value
    if (loaded == null) {
        Column(Modifier.fillMaxSize()) { DetailHeader(ui, "Album", null); EmptyState(art = EmptyArt.COLLECTION, title = "This album is gone", body = "Its songs were moved or deleted.") }
        return
    }
    val album = loaded.album
    val tracks = loaded.tracks
    SideEffect { ui.selection.publish(tracks) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    BandedDetail(ui, album.title, null, tracks.coverModel(), album.space, listState, trailing = { ListActionsMenu(ui, tracks, "album-menu", album.title) }) {
        SongsColumn(ui, tracks, padding, numbered = true, discHeaders = true, listState = listState) {
            item("album-head", contentType = "head") {
                CollectionHeaderBlock(
                    ui, album.title, if (album.compilation) "Album · Compilation" else "Album · ${album.artist}",
                    listOfNotNull(album.year?.toString(), "${album.tracks} songs", LibraryViews.spoken(album.duration), "${album.discs} discs".takeIf { album.discs > 1 }).joinToString(" · "),
                    tracks, album.space,
                )
            }
        }
    }
}

/**
 * The ⋮ of a list page: Play, Shuffle, Add to queue, Add to playlist… and Select. [full] false (Home "See all" lists, which can hold videos
 * and photos) offers Play all, Shuffle and Select only. Everything reuses the actions of the page buttons.
 */
@Composable
fun ListActionsMenu(ui: LibraryUi, songs: List<LibraryItem>, tag: String, name: String, full: Boolean = true) =
    ListActionsMenu(ui, Songs.Fixed(songs), tag, name, full)

/** The same for the rows of a query. */
@Composable
fun ListActionsMenu(ui: LibraryUi, query: ItemQuery, count: Int, tag: String, name: String, full: Boolean = true) =
    ListActionsMenu(ui, Songs.Rows(query, count), tag, name, full)

/** The most songs "Make a playlist from this" puts in one collection; a longer list says it took the first ones. */
private const val MAKE_COLLECTION_LIMIT = 10_000

@Composable
fun ListActionsMenu(ui: LibraryUi, songs: Songs, tag: String, name: String, full: Boolean = true) {
    var open by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val any = !songs.isEmpty
    val audio = when (songs) { is Songs.Fixed -> songs.items.any { it.kind == LibraryItemKind.AUDIO || it.kind == LibraryItemKind.VIDEO }; is Songs.Rows -> songs.count > 0 }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp).testTag(tag)) { Icon(Icons.Outlined.MoreVert, contentDescription = "More actions for $name") }
        DropdownMenu(open, { open = false }) {
            @Composable fun item(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, itemTag: String, run: () -> Unit) = DropdownMenuItem(
                text = { Text(label) }, enabled = enabled, leadingIcon = { Icon(icon, contentDescription = null) },
                onClick = { open = false; run() }, modifier = Modifier.testTag("$tag-$itemTag"),
            )
            item(if (full) "Play" else "Play all", Icons.Outlined.PlayArrow, any, "play") { ui.play(songs) }
            item("Shuffle", Icons.Outlined.Shuffle, audio, "shuffle") { ui.shuffle(songs) }
            if (full) {
                item("Add to queue", Icons.AutoMirrored.Outlined.QueueMusic, audio, "queue") { ui.addToQueue(songs) }
                item("Add to playlist…", Icons.AutoMirrored.Outlined.PlaylistAdd, audio, "collection") { ui.addToPlaylist(songs) }
                // a folder (or an album, an artist, a smart list) becomes a collection of its own in one tap
                item("Make a playlist from this", Icons.Outlined.LibraryAdd, audio, "make-collection") {
                    when (songs) {
                        is Songs.Fixed -> ui.vm.libraryActions.makeCollection(name, songs.items.map { it.id })
                        is Songs.Rows -> scope.launch {
                            val ids = try { withContext(Dispatchers.IO) { ui.browse.ids(songs.query, MAKE_COLLECTION_LIMIT) } }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { ui.vm.say("Could not read the songs of this list."); return@launch }
                            ui.vm.libraryActions.makeCollection(name, ids, capped = songs.count > MAKE_COLLECTION_LIMIT)
                        }
                    }
                }
            }
            item("Select", Icons.Outlined.Checklist, any, "select") { ui.selection.mode = true }
            // the same file more than once in this list (a collection's items, or what the page's query selects)
            item("Find duplicates", Icons.Outlined.ContentCopy, any, "duplicates") {
                ui.vm.openLibraryDetail(
                    when (songs) {
                        is Songs.Fixed -> LibraryRoute.Duplicates(name, ids = songs.items.map { it.id })
                        is Songs.Rows -> LibraryRoute.Duplicates(name, query = songs.query)
                    },
                )
            }
        }
    }
}

/** Play next, Add to queue and Add to collection for a whole album, playlist or list. */
@Composable
fun BulkActions(ui: LibraryUi, songs: List<LibraryItem>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        androidx.compose.material3.TextButton(onClick = { ui.vm.playNext(songs) }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.Outlined.SkipNext, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Play next") }
        androidx.compose.material3.TextButton(onClick = { ui.vm.addToQueue(songs) }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(Icons.AutoMirrored.Outlined.QueueMusic, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add to queue") }
        androidx.compose.material3.TextButton(onClick = { ui.sheet(LibrarySheet.AddToPlaylist(songs.map { it.id })) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Add to playlist")
        }
    }
}

// ---- artists ------------------------------------------------------------------------------------------------

private class ArtistData(val group: app.keepfin.ArtistGroup, val albums: List<app.keepfin.AlbumGroup>, val songs: List<LibraryItem>)

@Composable
fun ArtistScreen(ui: LibraryUi, route: LibraryRoute.Artist, padding: PaddingValues) {
    val key = remember(route) { app.keepfin.LibraryViewsKeys.fold(route.name) }
    val read by rememberBrowsed<Loaded<ArtistData>?>(ui.browse, route, null) { browse ->
        Loaded(
            browse.artist(key, route.space)?.let { g ->
                ArtistData(g, browse.albumsOfArtist(key, route.space), browse.top(ItemQuery(spaces = listOf(route.space), artist = key, songs = true, order = ItemOrder.ARTIST), 5000))
            },
        )
    }
    val reading = read ?: return
    val loaded = reading.value
    SideEffect { loaded?.let { ui.selection.publish(it.songs) } }
    if (loaded == null) {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(ui, route.name, null)
            EmptyState(art = EmptyArt.COLLECTION, title = "No songs by this artist", body = "They were moved or deleted.")
        }
        return
    }
    val songs = loaded.songs
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    BandedDetail(
        ui, route.name, "${loaded.albums.size} albums · ${loaded.group.songs} songs", songs.coverModel(), route.space, listState,
        trailing = { ListActionsMenu(ui, songs, "artist-menu", route.name) },
    ) {
        SongsColumn(ui, songs, padding, listState = listState) {
            item("artist-head", contentType = "head") {
                CollectionHeaderBlock(
                    ui, route.name, "Artist", "${loaded.albums.size} albums · ${songs.size} songs · ${LibraryViews.spoken(LibraryViews.totalDuration(songs))}",
                    songs, route.space, circle = true,
                    extra = { ArtistFollowButton(ui.vm, route.name) },
                )
            }
            if (loaded.albums.isNotEmpty()) {
                item("albums-head") { Text("Albums", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() }) }
                item("albums-row") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 10.dp)) {
                        items(loaded.albums, key = { it.key }) { album ->
                            CoverCard(album.title, album.year?.toString() ?: "${album.tracks} songs", album.cover?.artModel(), album.title, LibraryItemKind.AUDIO, Modifier.width(144.dp)) {
                                ui.vm.openLibraryDetail(LibraryRoute.Album(route.space, album.routeKey))
                            }
                        }
                    }
                }
                item("songs-head") { Text("Songs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() }) }
            }
        }
    }
}

/** The songs a quick list stands for, in one music space or in every music space. */
internal fun smartQuery(kind: LibraryViews.Smart, spaces: List<String>): ItemQuery {
    val base = ItemQuery(spaces = spaces, kinds = if (kind == LibraryViews.Smart.FAVORITES) setOf(LibraryItemKind.AUDIO, LibraryItemKind.VIDEO) else emptySet(), songs = kind != LibraryViews.Smart.FAVORITES)
    return when (kind) {
        LibraryViews.Smart.RECENTLY_ADDED -> base.copy(order = ItemOrder.ADDED)
        LibraryViews.Smart.RECENTLY_PLAYED -> base.copy(everPlayed = true, order = ItemOrder.RECENT)
        LibraryViews.Smart.MOST_PLAYED -> base.copy(played = true, order = ItemOrder.MOST_PLAYED)
        LibraryViews.Smart.NEVER_PLAYED -> base.copy(unplayed = true, order = ItemOrder.ADDED)
        LibraryViews.Smart.FAVORITES -> base.copy(favorite = true, order = ItemOrder.ADDED)
    }
}

@Composable
fun SmartScreen(ui: LibraryUi, route: LibraryRoute.SmartList, spaces: List<Space>, padding: PaddingValues) {
    // Favorites gather songs and videos from every space; the other quick lists are about music
    val scope = remember(route, spaces) { route.space?.let { listOf(it) } ?: if (route.kind == LibraryViews.Smart.FAVORITES) emptyList() else spaces.filter { it.kind == app.keepfin.SpaceKind.MUSIC }.map { it.id } }
    val query = remember(route, scope) { smartQuery(route.kind, scope) }
    PagedSongsDetail(ui, route.kind.label, query, padding, empty = route.kind.hint)
}

/** Header, Play and Shuffle, and the songs of a query read in pages (smart lists over the whole library). */
@Composable
fun PagedSongsDetail(ui: LibraryUi, title: String, all: ItemQuery, padding: PaddingValues, empty: String) {
    val (query, chips) = kindFiltered(ui, title, all)
    val count by rememberCount(ui.browse, query)
    val covers by rememberTop(ui.browse, query, 4)
    val duration by rememberBrowsed(ui.browse, query, 0.0) { b -> b.top(query, 2000).sumOf { it.duration ?: 0.0 } }
    val noun = if (LibraryItemKind.VIDEO in all.kinds) "item" else "song"
    val subtitle = if (count == 1) "1 $noun" else "$count ${noun}s · ${LibraryViews.spoken(duration)}"
    val actions = Songs.Rows(query, count)
    val menu: @Composable () -> Unit = { if (count > 0) ListActionsMenu(ui, actions, "list-menu", title) }
    SideEffect { ui.selection.publishQuery(query, count) }
    if (count == 0) {
        Column(Modifier.fillMaxSize()) { DetailHeader(ui, title, subtitle, menu); EmptyState(art = EmptyArt.COLLECTION, title = "No ${noun}s yet", body = empty) }
        return
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    BandedDetail(ui, title, subtitle, covers.coverModel(), covers.firstOrNull()?.space, listState, trailing = menu) {
        PagedItemsColumn(ui, query, padding, listState = listState, publishSelection = false, header = { _ ->
            item("detail-head", contentType = "head") {
                CollectionHeaderBlock(ui, title, null, subtitle, covers, covers.firstOrNull()?.space, actions = actions)
            }
            item("kind-chips", contentType = "chips") { chips() }
        })
    }
}

/** Header, Play and Shuffle, and the songs; shared by collection folders and the user's own lists (a bounded list). */
@Composable
fun SongsDetail(ui: LibraryUi, title: String, songs: List<LibraryItem>, padding: PaddingValues, empty: String, reorder: PlaylistReorder? = null, menu: (@Composable () -> Unit)? = null) {
    SideEffect { ui.selection.publish(songs) }
    val subtitle = if (songs.size == 1) "1 song" else "${songs.size} songs · ${LibraryViews.spoken(LibraryViews.totalDuration(songs))}"
    val actions: @Composable () -> Unit = menu ?: { if (songs.isNotEmpty()) ListActionsMenu(ui, songs, "list-menu", title) }
    if (songs.isEmpty()) {
        Column(Modifier.fillMaxSize()) { DetailHeader(ui, title, subtitle, actions); EmptyState(art = EmptyArt.COLLECTION, title = "No songs yet", body = empty) }
        return
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    BandedDetail(ui, title, subtitle, songs.coverModel(), songs.firstOrNull()?.space, listState, trailing = actions) {
        SongsColumn(ui, songs, padding, reorder = reorder, listState = listState) {
            item("detail-head", contentType = "head") {
                CollectionHeaderBlock(
                    ui, title, null, if (songs.size == 1) "1 song" else "${songs.size} songs · ${LibraryViews.spoken(LibraryViews.totalDuration(songs))}",
                    songs, songs.firstOrNull()?.space,
                )
            }
        }
    }
}

@Composable
fun UserPlaylistScreen(ui: LibraryUi, id: String, padding: PaddingValues) {
    val snapshot by ui.vm.lib.library.collectAsStateWithLifecycle()
    val playlist = snapshot.playlists.firstOrNull { it.id == id }
    if (playlist == null) {
        Column(Modifier.fillMaxSize()) { DetailHeader(ui, "Playlist", null); EmptyState(art = EmptyArt.COLLECTION, title = "This playlist is gone", body = "It was deleted.") }
        return
    }
    val ids = remember(playlist.itemIds) { playlist.itemIds.distinct() }
    val loaded by rememberBrowsed<List<LibraryItem>?>(ui.browse, ids, null) { it.byIds(ids) }
    val songs = loaded ?: return
    val reorder = remember(playlist.id, playlist.name) {
        PlaylistReorder(onCommit = { order -> ui.vm.libraryActions.reorderPlaylist(playlist.id, playlist.name, order) })
    }
    CompositionLocalProvider(LocalOwnCollection provides OwnCollection(playlist.id, playlist.name)) {
    SongsDetail(ui, playlist.name, songs, padding, empty = "Add songs from any list with Add to playlist.", reorder = reorder) {
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }, modifier = Modifier.testTag("playlist-menu")) { Icon(Icons.Outlined.MoreVert, contentDescription = "Playlist actions") }
            DropdownMenu(open, { open = false }) {
                DropdownMenuItem(text = { Text("Rename playlist") }, leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) }, onClick = { open = false; ui.sheet(LibrarySheet.RenamePlaylist(playlist.id, playlist.name, playlist.itemIds)) })
                DropdownMenuItem(text = { Text("Delete playlist") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) }, onClick = { open = false; ui.sheet(LibrarySheet.DeletePlaylist(playlist.id, playlist.name)) },
                    modifier = Modifier.testTag("playlist-delete"))
            }
        }
    }
    }
}