package app.keepfin.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.ui.Links
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.EmptyArt
import app.keepfin.ui.components.EmptyState
import app.keepfin.ui.components.FilterPill
import app.keepfin.ui.components.RowTextAction
import app.keepfin.ui.components.ScreenTitle
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import app.keepfin.ItemOrder
import app.keepfin.ItemQuery
import app.keepfin.core.spaces.MusicVideoRows
import app.keepfin.ui.library.MusicVideoRowItem
import app.keepfin.ui.library.rememberAlbums
import app.keepfin.ui.library.rememberMusicVideos
import app.keepfin.ui.library.rememberArtists
import app.keepfin.ui.library.rememberBrowsed
import app.keepfin.ui.library.rememberCount
import app.keepfin.ui.library.rememberItem
import app.keepfin.ui.library.rememberItemPaging
import app.keepfin.ui.library.rememberPlayingId
import app.keepfin.ui.library.rememberTop
import app.keepfin.ui.library.LibraryViews
import androidx.compose.runtime.LaunchedEffect
import app.keepfin.ui.library.ArtTile
import app.keepfin.ui.library.ItemRow
import app.keepfin.ui.library.LibraryRoute
import app.keepfin.ui.library.LibraryUi
import app.keepfin.ui.library.LocalSelectable
import app.keepfin.ui.library.SearchField
import app.keepfin.ui.library.artModel
import app.keepfin.ui.library.collectionCardLine
import app.keepfin.ui.library.collectionRoute
import app.keepfin.ui.library.subtitleOf
import app.keepfin.ui.theme.tabular

private const val GROUP_LIMIT = 5

/**
 * Search: your library first (songs, videos, photos, albums, artists, playlists, with a filter row), then "Search YouTube for ..."
 * below, which opens the download flow on YouTube's results. A pasted link goes straight to capture.
 */
@Composable
fun SearchTab(vm: StudioViewModel, ui: LibraryUi, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val snapshot by vm.lib.library.collectAsStateWithLifecycle()
    val playing = rememberPlayingId(ui)
    val browse = ui.browse
    val query = vm.searchQuery
    var filter by rememberSaveable { mutableStateOf(SearchFilter.ALL) }
    val typed = query.isNotBlank()
    val isLink = remember(query) { Links.extract(query).isNotEmpty() }
    val shown = if (filter == SearchFilter.ALL) GROUP_LIMIT else Int.MAX_VALUE
    // each group is its own query on the index: the first rows for the overview, a Paging list behind "See all"
    // songs: audio, and videos kept in a music space (Songs.isSong), as on Windows
    val songsQ = remember(query) { ItemQuery(text = query, songs = true, order = ItemOrder.ADDED) }
    // a video kept in a music space is a song: it is under Songs only
    val musicSpaces = vm.musicSpaceIds()
    val videosQ = remember(query, musicSpaces) { ItemQuery(text = query, kinds = setOf(LibraryItemKind.VIDEO), excludeSpaces = musicSpaces, order = ItemOrder.ADDED) }
    val photosQ = remember(query) { ItemQuery(text = query, kinds = setOf(LibraryItemKind.IMAGE), order = ItemOrder.ADDED) }
    val albumsQ = remember(query) { ItemQuery(text = query, textColumns = "album artist") }
    val artistsQ = remember(query) { ItemQuery(text = query, textColumns = "artist") }
    val songCount by rememberCount(browse, if (typed) songsQ else ItemQuery(ids = emptySet()))
    val videoCount by rememberCount(browse, if (typed) videosQ else ItemQuery(ids = emptySet()))
    val photoCount by rememberCount(browse, if (typed) photosQ else ItemQuery(ids = emptySet()))
    val albumCount by rememberBrowsed(browse, albumsQ to typed, 0) { if (typed) it.albumCount(albumsQ) else 0 }
    val artistCount by rememberBrowsed(browse, artistsQ to typed, 0) { if (typed) it.artistCount(artistsQ) else 0 }
    val terms = remember(query) { LibraryViews.terms(query) }
    val playlists = remember(snapshot.collections, terms) { if (terms.isEmpty()) emptyList() else snapshot.collections.filter { c -> terms.all { LibraryViews.fold(c.name).contains(it) } } }
    val focus = filter
    val songRows by rememberTop(browse, songsQ, if (typed && focus == SearchFilter.ALL) shown else 0)
    val videoRows by rememberTop(browse, videosQ, if (typed && focus == SearchFilter.ALL) shown else 0)
    // a song's music video is a video too: it shows with the Videos
    val musicVideos by rememberMusicVideos(browse, query, if (typed && (focus == SearchFilter.ALL || focus == SearchFilter.VIDEOS)) 500 else 0)
    val photoRows by rememberTop(browse, photosQ, if (typed && focus == SearchFilter.ALL) shown else 0)
    val songsPaged = if (typed && focus == SearchFilter.SONGS) rememberItemPaging(browse, songsQ) else null
    val videosPaged = if (typed && focus == SearchFilter.VIDEOS) rememberItemPaging(browse, videosQ) else null
    val photosPaged = if (typed && focus == SearchFilter.PHOTOS) rememberItemPaging(browse, photosQ) else null
    val albumRows = if (typed && (focus == SearchFilter.ALL || focus == SearchFilter.ALBUMS)) rememberAlbums(ui, albumsQ) else null
    val artistRows = if (typed && (focus == SearchFilter.ALL || focus == SearchFilter.ARTISTS)) rememberArtists(ui, artistsQ) else null
    val total = songCount + videoCount + musicVideos.size + photoCount + albumCount + artistCount + playlists.size
    fun countOf(f: SearchFilter) = when (f) {
        SearchFilter.ALL -> total
        SearchFilter.VIDEOS -> videoCount + musicVideos.size
        SearchFilter.SONGS -> songCount
        SearchFilter.PHOTOS -> photoCount
        SearchFilter.ALBUMS -> albumCount
        SearchFilter.ARTISTS -> artistCount
        SearchFilter.PLAYLISTS -> playlists.size
    }
    CompositionLocalProvider(LocalSelectable provides false) {
        // pinned: the title and the field stay while the results scroll
        Column(modifier.fillMaxSize()) {
        app.keepfin.ui.TabHeader(vm, "Search", null, search = false)
        SearchField(query, vm::typeSearch, "Search your library, or paste a link", Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag(UiTags.SEARCH_FIELD))
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(UiTags.SEARCH_SCREEN), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
            if (!typed) {
                item("hint", contentType = "empty") {
                    EmptyState(
                        art = EmptyArt.SEARCH, title = "Find anything you saved",
                        body = "Songs, videos, photos, albums, artists and playlists in your library show up as you type. You can search YouTube from here too.",
                        modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp).testTag(UiTags.EMPTY_STATE),
                    )
                }
                return@LazyColumn
            }
            if (isLink) item("link", contentType = "online") {
                OnlineRow("Download this link", "Opens the quality picker", Icons.Outlined.Download, Modifier.testTag(UiTags.SEARCH_ONLINE + "-link")) { vm.routeBox(query) }
            }
            item("chips", contentType = "chips") {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(SearchFilter.entries, key = { it.wire }) { option ->
                        FilterPill(option.label, option == filter, { filter = option }, Modifier.testTag(UiTags.searchChip(option.wire)))
                    }
                }
            }
            item("lib-head", contentType = "head") {
                Text("In your library", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 2.dp).testTag(UiTags.SEARCH_LIBRARY).semantics { heading() })
            }
            val any = countOf(filter) > 0
            if (!any) item("none", contentType = "note") {
                Text(
                    if (total == 0) "Nothing in your library matches “${query.trim()}”." else "No ${filter.label.lowercase()} match “${query.trim()}”.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag(UiTags.SEARCH_NONE),
                )
            }
            if (filter == SearchFilter.ALL || filter == SearchFilter.SONGS) itemRowsGroup("songs", "Songs", songCount, songRows, songsPaged, shown, { filter = SearchFilter.SONGS }) { item ->
                ItemRow(item, ui, subtitleOf(item), playing = playing.value == item.id, onClick = { ui.playFrom(songsQ, item) })
            }
            if (filter == SearchFilter.ALL || filter == SearchFilter.VIDEOS) itemRowsGroup("videos", "Videos", videoCount, videoRows, videosPaged, shown, { filter = SearchFilter.VIDEOS }) { item ->
                ItemRow(item, ui, subtitleOf(item), onClick = { ui.vm.play(listOf(item), 0) })
            }
            if (filter == SearchFilter.ALL || filter == SearchFilter.VIDEOS) itemGroup("musicvideos", MusicVideoRows.SHELF, musicVideos.size, musicVideos.take(shown), shown, { filter = SearchFilter.VIDEOS }, key = { it.row.id }) { _, entry ->
                MusicVideoRowItem(entry, ui)
            }
            if (filter == SearchFilter.ALL || filter == SearchFilter.PHOTOS) itemRowsGroup("photos", "Photos", photoCount, photoRows, photosPaged, shown, { filter = SearchFilter.PHOTOS }) { item ->
                ItemRow(item, ui, subtitleOf(item), onClick = { ui.playFrom(photosQ, item) })
            }
            if (albumRows != null) itemGroup("albums", "Albums", albumCount, albumRows.items.take(shown), shown, { filter = SearchFilter.ALBUMS }, key = { it.key }) { _, album ->
                CardRow(
                    album.title, album.artist + " · " + album.tracks + (if (album.tracks == 1) " song" else " songs"), album.cover?.artModel(), album.title, LibraryItemKind.AUDIO,
                    Modifier.testTag("search-album-${album.routeKey}"),
                ) { vm.openLibraryDeep(LibraryRoute.Album(album.space, album.routeKey)) }
            }
            if (artistRows != null) itemGroup("artists", "Artists", artistCount, artistRows.items.take(shown), shown, { filter = SearchFilter.ARTISTS }, key = { it.key }) { _, artist ->
                val space = artist.cover?.space.orEmpty()
                CardRow(artist.name, artist.songs.let { if (it == 1) "1 song" else "$it songs" }, artist.cover?.artModel(), artist.name, LibraryItemKind.AUDIO, Modifier.testTag("search-artist-${artist.name}")) {
                    vm.openLibraryDeep(LibraryRoute.Artist(space, artist.name))
                }
            }
            if (filter == SearchFilter.ALL || filter == SearchFilter.PLAYLISTS) itemGroup("playlists", "Playlists", playlists.size, playlists.take(shown), shown, { filter = SearchFilter.PLAYLISTS }, key = { it.id }) { _, c ->
                CollectionCardRow(c, ui, vm)
            }
            item("online-head", contentType = "head") {
                Text("On YouTube", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 2.dp).semantics { heading() })
            }
            item("online", contentType = "online") {
                OnlineRow(
                    "Search YouTube for “${query.trim()}”", "Opens the results in the download flow, where you pick what to save",
                    Icons.AutoMirrored.Outlined.OpenInNew, Modifier.testTag(UiTags.SEARCH_ONLINE),
                ) { vm.routeBox(SearchLogic.youtubeSearchLink(query)) }
            }
            // a long group ends with its growing list asking for the next page
            if (albumRows != null && filter == SearchFilter.ALBUMS) item("albums-end") { LaunchedEffect(albumRows.items.size) { if (albumRows.hasMore) albumRows.wantMore = true } }
            if (artistRows != null && filter == SearchFilter.ARTISTS) item("artists-end") { LaunchedEffect(artistRows.items.size) { if (artistRows.hasMore) artistRows.wantMore = true } }
        }
        }
    }
}

/** A playlist found by a search: its cover and line come from a few members. */
@Composable
private fun CollectionCardRow(c: app.keepfin.LibraryCollection, ui: LibraryUi, vm: StudioViewModel) {
    val cover by rememberItem(ui.browse, c.coverItemId ?: c.itemIds.firstOrNull())
    CardRow(c.name, collectionCardLine(c, c.itemIds.size), cover?.artModel(), c.name, LibraryItemKind.AUDIO, Modifier.testTag("search-playlist-${c.id}")) {
        vm.openLibraryDeep(collectionRoute(c))
    }
}

/** A group of item rows: the first [shown] rows of the overview, or a Paging list when the group has the page to itself. */
private fun LazyListScope.itemRowsGroup(
    name: String, title: String, count: Int, rows: List<LibraryItem>, paged: LazyPagingItems<LibraryItem>?, shown: Int, seeAll: () -> Unit, row: @Composable (LibraryItem) -> Unit,
) {
    if (count == 0) return
    item("h-$name", contentType = "head") {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).semantics { heading() })
            if (count > shown) RowTextAction("See all $count", seeAll, Modifier.testTag("search-seeall-$name"))
        }
    }
    if (paged != null) items(paged.itemCount, key = paged.itemKey { "$name-" + it.id }, contentType = paged.itemContentType { name }) { index -> paged[index]?.let { row(it) } }
    else items(rows, key = { "$name-" + it.id }, contentType = { name }) { row(it) }
}

private fun <T> LazyListScope.itemGroup(
    name: String, title: String, count: Int, list: List<T>, shown: Int, seeAll: () -> Unit, key: ((T) -> Any)? = null, row: @Composable (Int, T) -> Unit,
) {
    if (list.isEmpty() && count == 0) return
    item("h-$name", contentType = "head") {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).semantics { heading() })
            if (count > shown) RowTextAction("See all $count", seeAll, Modifier.testTag("search-seeall-$name"))
        }
    }
    itemsIndexed(list, key = { index, it -> "$name-" + (key?.invoke(it) ?: (it as? LibraryItem)?.id ?: index) }, contentType = { _, _ -> name }) { index, it -> row(index, it) }
}
@Composable
private fun CardRow(title: String, subtitle: String, model: Any?, seed: String, kind: LibraryItemKind, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtTile(model, seed, kind, Modifier.size(44.dp), MaterialTheme.shapes.small)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun OnlineRow(title: String, body: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
