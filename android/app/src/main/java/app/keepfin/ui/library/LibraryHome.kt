package app.keepfin.ui.library

import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.CollectionText
import app.keepfin.HubLogic
import app.keepfin.ItemOrder
import app.keepfin.ItemQuery
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.Space
import app.keepfin.SpaceKind
import app.keepfin.ui.Destination
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.EmptyArt
import app.keepfin.ui.components.EmptyState
import app.keepfin.ui.components.RowTextAction
import app.keepfin.ui.components.ScreenTitle
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.theme.spaceTone
import app.keepfin.ui.theme.tabular

private const val ROW_LIMIT = 12

/**
 * Library home, the Spaces hub: a "Library" title with search, the spaces as tonal cards (Music, Videos, Downloads, then
 * your own), then the shelves Continue watching, Recently added, Recently played, Favorites and Collections.
 */
@Composable
fun HomeScreen(ui: LibraryUi, spaces: List<Space>, padding: PaddingValues) {
    val snapshot by ui.vm.lib.library.collectAsStateWithLifecycle()
    val shelves = rememberLibraryShelves(ui)
    val total = shelves.total
    val continueItems = shelves.continueItems
    val recent = shelves.recent
    val played = shelves.played
    val favorites = shelves.favorites
    val bottom = padding.calculateBottomPadding() + 24.dp
    val noSelection = remember { emptyList<LibraryItem>() }
    var tileMenu by remember { mutableStateOf<String?>(null) }
    SideEffect { ui.selection.publish(noSelection) }
    val hasMusicSpace = remember(spaces) { spaces.any { it.kind == SpaceKind.MUSIC } }
    val shelfCollections = shelves.collections
    val coverById = remember(shelves.covers) { shelves.covers.associateBy { it.id } }
    val news = remember(snapshot.collections) { HubLogic.newCount(snapshot.collections) }
    val musicVideos by rememberMusicVideos(ui.browse)

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
    // pinned: the title (or the selection bar) stays while the page scrolls
    if (ui.selection.mode) SelectionTopBar(ui) else app.keepfin.ui.TabHeader(ui.vm, "Library", listState)
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(UiTags.LIB_LIST), state = listState, contentPadding = PaddingValues(bottom = bottom)) {
        item("spaces-head", contentType = "head") {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Spaces", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).semantics { heading() })
                RowTextAction("Edit", { ui.vm.openLibraryRoot(LibraryRoute.Manage) }, Modifier.testTag(UiTags.LIB_SPACES_EDIT))
            }
        }
        item("spaces", contentType = "spaces") { SpacesGrid(ui, spaces) }
        // not read yet: the spaces show, the shelves follow (never the empty state first)
        if (total == null) return@LazyColumn
        if (total == 0) {
            item("empty", contentType = "empty") { EmptyState(
                art = EmptyArt.LIBRARY, title = "Your library is empty",
                body = "Everything Keepfin saves lands here, organised in spaces. Songs from YouTube Music, Spotify, Apple Music and Deezer go to Music; the rest goes to Downloads.",
                modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp).testTag(UiTags.LIB_EMPTY),
                action = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { ui.vm.navigate(Destination.DOWNLOADS) }, modifier = Modifier.heightIn(min = 48.dp).testTag("empty-paste"),
                            colors = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction)) { Text("Paste a link") }
                        OutlinedButton(onClick = { ui.addFiles((spaces.firstOrNull { it.id == "downloads" } ?: spaces.firstOrNull())?.id ?: "downloads") }, modifier = Modifier.heightIn(min = 48.dp).testTag("empty-add-files")) {
                            Icon(Icons.Outlined.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add files")
                        }
                    }
                },
            ) }
            return@LazyColumn
        }
        if (continueItems.isNotEmpty()) shelf("continue", "Continue watching", { ui.vm.openLibraryDetail(LibraryRoute.Section(HomeSection.CONTINUE)) }) {
            items(continueItems, key = { it.id }) { item -> ContinueCard(item, ui, continueItems) }
        }
        if (recent.isNotEmpty()) itemShelf("recent", "Recently added", recent, ui, { ui.vm.openLibraryDetail(LibraryRoute.Section(HomeSection.RECENTLY_ADDED)) })
        if (snapshot.collections.isNotEmpty() || hasMusicSpace) shelf(
            "collections", "Collections", { ui.vm.openLibraryDetail(LibraryRoute.Collections) }, Modifier.testTag(UiTags.LIB_SHELF_COLLECTIONS),
            badge = news.takeIf { it > 0 }?.let { "$it new" },
        ) {
            items(shelfCollections, key = { "co-" + it.id }) { c ->
                val cover = coverById[c.coverItemId ?: c.itemIds.firstOrNull()]
                CoverCard(
                    c.name, collectionCardLine(c, c.itemIds.size),
                    cover?.artModel(), c.name, LibraryItemKind.AUDIO, Modifier.width(120.dp).testTag(UiTags.collectionRow(c.id)),
                    onLongClick = if (c.kind != app.keepfin.CollectionKind.OWN) ({ tileMenu = c.id }) else null,
                    longClickLabel = "Move or copy to a space",
                    popup = { if (tileMenu == c.id) DropdownMenu(true, { tileMenu = null }) { CollectionSpaceItems(c, ui) { tileMenu = null } } },
                ) { ui.vm.openLibraryDetail(collectionRoute(c)) }
            }
            item("co-new") { PlaylistTile("New playlist", "Name it, add items", Icons.Outlined.Add, Modifier.testTag("playlist-create")) { ui.sheet(LibrarySheet.NewPlaylist(emptyList())) } }
        }
        musicVideoShelf(musicVideos, ui) { ui.vm.openLibraryDetail(LibraryRoute.MusicVideos) }
        if (played.isNotEmpty()) itemShelf("played", "Recently played", played, ui, { ui.vm.openLibraryDetail(LibraryRoute.Section(HomeSection.RECENTLY_PLAYED)) })
        if (favorites.isNotEmpty()) itemShelf("favorites", "Favorites", favorites, ui, { ui.vm.openLibraryDetail(LibraryRoute.Section(HomeSection.FAVORITES)) })
    }
    }
}
/** What Library home shows below the spaces; [total] is null until the first count. */
internal class LibraryShelves(
    val total: Int?, val continueItems: List<LibraryItem>, val recent: List<LibraryItem>, val played: List<LibraryItem>, val favorites: List<LibraryItem>,
    val collections: List<app.keepfin.LibraryCollection>, val covers: List<LibraryItem>,
)

/**
 * Library home's shelves: a few rows each, off the main thread, again when the library changes. The app shell keeps these reads
 * running while another tab shows, so Library opens already filled.
 */
@Composable
internal fun rememberLibraryShelves(ui: LibraryUi): LibraryShelves {
    val snapshot by ui.vm.lib.library.collectAsStateWithLifecycle()
    val browse = ui.browse
    val total by rememberTotal(browse)
    val continueItems by rememberTop(browse, sectionQuery(HomeSection.CONTINUE), ROW_LIMIT)
    val recent by rememberTop(browse, sectionQuery(HomeSection.RECENTLY_ADDED), ROW_LIMIT)
    val played by rememberTop(browse, sectionQuery(HomeSection.RECENTLY_PLAYED), ROW_LIMIT)
    val favorites by rememberTop(browse, sectionQuery(HomeSection.FAVORITES), ROW_LIMIT)
    val collections = remember(snapshot.collections) { HubLogic.shelf(snapshot.collections).take(ROW_LIMIT) }
    val covers by rememberByIds(browse, collections.mapNotNull { it.coverItemId ?: it.itemIds.firstOrNull() })
    // the space cards' counts (read here too, so the cards open with their numbers)
    val settings by ui.vm.engine.settings.collectAsStateWithLifecycle()
    rememberSpaceCounts(browse)
    rememberSmartCounts(ui, remember(settings.library.spaces) { app.keepfin.SpaceLogic.homeSpaces(app.keepfin.SpaceLogic.withDefaults(settings.library.spaces)) })
    return LibraryShelves(total, continueItems, recent, played, favorites, collections, covers)
}

internal fun LazyListScope.shelf(key: String, title: String, onSeeAll: () -> Unit, headModifier: Modifier = Modifier, badge: String? = null, content: LazyListScope.() -> Unit) {
    item("head-$key", contentType = "head") {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp).then(headModifier), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (badge != null) Text(
                badge, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 8.dp, vertical = 2.dp).testTag(UiTags.LIB_NEW_BADGE),
            )
            Spacer(Modifier.weight(1f))
            RowTextAction("See all", onSeeAll, Modifier.testTag("see-all-$key"))
        }
    }
    item("row-$key", contentType = "row") {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun PlaylistTile(name: String, detail: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // Same outer size as a CoverCard (120 dp wide, 6 dp padding) so a shelf that ends with this tile keeps one height.
    Column(modifier.width(120.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick).padding(6.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(32.dp))
        }
        Text(name, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(detail, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The rows a Home shelf stands for; its "See all" page lists them all. */
internal fun sectionQuery(section: HomeSection): ItemQuery = when (section) {
    HomeSection.CONTINUE -> ItemQuery(resumable = true, order = ItemOrder.RECENT)
    HomeSection.RECENTLY_ADDED -> ItemQuery(kinds = setOf(LibraryItemKind.VIDEO, LibraryItemKind.AUDIO, LibraryItemKind.IMAGE), order = ItemOrder.ADDED)
    HomeSection.RECENTLY_PLAYED -> ItemQuery(everPlayed = true, order = ItemOrder.RECENT)
    HomeSection.FAVORITES -> ItemQuery(favorite = true, order = ItemOrder.ADDED)
}

/** "See all" for a Home shelf: a plain list of everything in it. */
@Composable
fun SectionScreen(ui: LibraryUi, section: HomeSection, padding: PaddingValues) {
    val (query, chips) = kindFiltered(ui, section, remember(section) { sectionQuery(section) })
    val count by rememberCount(ui.browse, query)
    Column(Modifier.fillMaxSize()) {
        DetailHeader(ui, section.title, "$count items") { if (count > 0) ListActionsMenu(ui, query, count, "section-menu", section.title, full = false) }
        if (count == 0) EmptyState(art = EmptyArt.LIBRARY, title = "Nothing here yet", body = "Items appear here as you use your library.", modifier = Modifier.weight(1f))
        else { chips(); PagedItemsColumn(ui, query, padding) }
    }
}