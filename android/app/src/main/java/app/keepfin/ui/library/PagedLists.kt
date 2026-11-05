package app.keepfin.ui.library

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import app.keepfin.AlbumGroup
import app.keepfin.ArtistGroup
import app.keepfin.ItemQuery
import app.keepfin.LibraryBrowse
import app.keepfin.LibraryItem
import app.keepfin.ui.UiTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The list shapes of the paged library (decision D-A4): rows and grids over a Paging list, and groups (albums, artists) loaded in
// pages by a cursor. A screen says WHICH rows (an ItemQuery); these draw them and keep the selection and the "playing" mark.

/** The id of the song that plays now, read without making the whole list follow the player's other changes. */
@Composable
fun rememberPlayingId(ui: LibraryUi): State<String?> {
    val player = ui.vm.player.ui.collectAsStateWithLifecycle()
    return remember { derivedStateOf { player.value.current?.id } }
}

/** Items of [query] as a column of [ItemRow]s. [header] adds rows above (play buttons, tag chips...). */
@Composable
fun PagedItemsColumn(
    ui: LibraryUi,
    query: ItemQuery,
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    publishSelection: Boolean = true,
    subtitle: (LibraryItem) -> String = ::subtitleOf,
    header: LazyListScope.(count: Int) -> Unit = {},
    footer: LazyListScope.() -> Unit = {},
) {
    val paging = rememberItemPaging(ui.browse, query)
    val count by rememberCount(ui.browse, query)
    val playing = rememberPlayingId(ui)
    // read in composition (not inside the effect), so the page recomposes and publishes again when the count arrives
    val published = count
    if (publishSelection) SideEffect { ui.selection.publishQuery(query, published) }
    LazyColumn(modifier.fillMaxSize().testTag(UiTags.LIB_LIST), state = listState, contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
        header(count)
        items(paging.itemCount, key = paging.itemKey { it.id }, contentType = paging.itemContentType { "item" }) { index ->
            val item = paging[index]
            if (item != null) ItemRow(item, ui, subtitle(item), playing = playing.value == item.id, onClick = { ui.playFrom(query, item) })
        }
        footer()
    }
}

/** Items of [query] as a grid of cover cards (songs). */
@Composable
fun PagedCoverGrid(ui: LibraryUi, query: ItemQuery, padding: PaddingValues, columns: Int, header: LazyGridScope.(count: Int) -> Unit = {}) {
    val paging = rememberItemPaging(ui.browse, query)
    val count by rememberCount(ui.browse, query)
    val published = count
    SideEffect { ui.selection.publishQuery(query, published) }
    LazyVerticalGrid(
        GridCells.Fixed(columns), Modifier.fillMaxSize().testTag(UiTags.LIB_LIST),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = padding.calculateBottomPadding() + 24.dp),
    ) {
        header(count)
        items(paging.itemCount, key = paging.itemKey { it.id }) { index ->
            val song = paging[index]
            if (song != null) {
                val picked = song.id in ui.selection.ids
                CoverCard(
                    song.title, (if (ui.vm.isVideoSong(song)) app.keepfin.core.spaces.Songs.VIDEO_TAG + " · " else "") + (song.artist ?: song.album.orEmpty()), song.artModel(), song.album ?: song.title, song.kind, Modifier.testTag(UiTags.libItem(song.id)),
                    selected = if (ui.selection.mode) picked else null, onLongClick = { ui.selection.start(song.id) },
                ) { if (ui.selection.mode) ui.selection.toggle(song.id) else ui.playFrom(query, song) }
            }
        }
    }
}

/** Items of [query] as a grid of posters (videos and mixed spaces). */
@Composable
fun PagedPosterGrid(ui: LibraryUi, query: ItemQuery, padding: PaddingValues, columns: Int, modifier: Modifier = Modifier, header: LazyGridScope.(count: Int) -> Unit = {}) {
    val paging = rememberItemPaging(ui.browse, query)
    val count by rememberCount(ui.browse, query)
    val published = count
    SideEffect { ui.selection.publishQuery(query, published) }
    LazyVerticalGrid(
        GridCells.Fixed(columns), modifier.fillMaxSize().testTag(UiTags.LIB_LIST),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = padding.calculateBottomPadding() + 24.dp),
    ) {
        header(count)
        items(paging.itemCount, key = paging.itemKey { it.id }) { index ->
            val item = paging[index]
            if (item != null) PosterCard(item, ui, Modifier) { ui.playFrom(query, item) }
        }
    }
}

/** The rows of a Paging list as a plain list, for the actions that need the loaded part (Select all reads ids from the index instead). */
fun LazyPagingItems<LibraryItem>.snapshotItems(): List<LibraryItem> = itemSnapshotList.items

// ---- groups loaded by a cursor ----------------------------------------------------------------------------------

/** A growing list read page by page: [load] gets the cursor of the last row (null for the first page). Reloads when the library changes. */
@Composable
fun <T> rememberGrowing(browse: LibraryBrowse, key: Any?, pageSize: Int, cursorOf: (T) -> String, load: (after: String?, limit: Int) -> List<T>): GrowingList<T> {
    val state = remember(browse, key) { GrowingList<T>() }
    val version by browse.changes.collectAsStateWithLifecycle()
    LaunchedEffect(browse, key, version) {
        val first = if (browse.cheap) load(null, pageSize) else withContext(Dispatchers.IO) { load(null, pageSize) }
        // keep what was loaded below the first page when the library changed under a long list
        val keep = if (state.items.size > pageSize) state.items.size else pageSize
        var all = first
        while (all.size < keep && all.size % pageSize == 0 && all.isNotEmpty()) {
            val next = if (browse.cheap) load(cursorOf(all.last()), pageSize) else withContext(Dispatchers.IO) { load(cursorOf(all.last()), pageSize) }
            if (next.isEmpty()) break
            all = all + next
        }
        state.set(all, all.size >= pageSize && all.size % pageSize == 0)
    }
    LaunchedEffect(browse, key, state.wantMore) {
        if (state.wantMore && state.hasMore && state.items.isNotEmpty()) {
            val after = cursorOf(state.items.last())
            val next = if (browse.cheap) load(after, pageSize) else withContext(Dispatchers.IO) { load(after, pageSize) }
            state.append(next, next.size >= pageSize)
        }
    }
    return state
}

class GrowingList<T> {
    var items by mutableStateOf<List<T>>(emptyList())
        private set
    var hasMore by mutableStateOf(false)
        private set
    var loaded by mutableStateOf(false)
        private set
    var wantMore by mutableStateOf(false)

    fun set(list: List<T>, more: Boolean) { items = list; hasMore = more; loaded = true; wantMore = false }
    fun append(list: List<T>, more: Boolean) { items = items + list; hasMore = more; wantMore = false }
}

/** Asks for the next page when the user scrolls near the end of the list. */
@Composable
fun <T> GrowingList<T>.EndWatcher(state: LazyListState) {
    val nearEnd by remember(state) { derivedStateOf { val info = state.layoutInfo; info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 8 } }
    LaunchedEffect(nearEnd, items.size) { if (nearEnd && hasMore) wantMore = true }
}

@Composable
fun <T> GrowingList<T>.GridEndWatcher(state: androidx.compose.foundation.lazy.grid.LazyGridState) {
    val nearEnd by remember(state) { derivedStateOf { val info = state.layoutInfo; info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: 0) >= info.totalItemsCount - 8 } }
    LaunchedEffect(nearEnd, items.size) { if (nearEnd && hasMore) wantMore = true }
}

@Composable
fun rememberAlbums(ui: LibraryUi, query: ItemQuery, byArtist: Boolean = false): GrowingList<AlbumGroup> =
    rememberGrowing(ui.browse, query to byArtist, 60, { it.cursor }) { after, limit -> ui.browse.albums(query, after, limit, byArtist) }

@Composable
fun rememberArtists(ui: LibraryUi, query: ItemQuery): GrowingList<ArtistGroup> =
    rememberGrowing(ui.browse, query, 80, { it.cursor }) { after, limit -> ui.browse.artists(query, after, limit) }
