package app.keepfin.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.AlbumGroup
import app.keepfin.ArtistGroup
import app.keepfin.CollectionKind
import app.keepfin.HubLogic
import app.keepfin.ItemOrder
import app.keepfin.ItemQuery
import app.keepfin.LibraryCollection
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.Space
import app.keepfin.SpaceKind
import app.keepfin.SpaceLogic
import app.keepfin.core.layout.Page
import app.keepfin.ui.Destination
import app.keepfin.ui.TabHeader
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.EmptyArt
import app.keepfin.ui.components.EmptyState
import app.keepfin.ui.media.EditBar
import app.keepfin.ui.media.EditPageButton
import app.keepfin.ui.media.rememberPageEditor
import app.keepfin.ui.theme.Studio

private const val ROW_LIMIT = 12

/**
 * Library home: a "Library" title with search and the pencil (Edit Library), then the sections of the saved layout: the spaces as
 * tonal cards (Music, Videos, Downloads, then your own), playlists, albums, artists, videos, photos and collections.
 */
@Composable
fun HomeScreen(ui: LibraryUi, spaces: List<Space>, padding: PaddingValues) {
    val snapshot by ui.vm.lib.library.collectAsStateWithLifecycle()
    val shelves = rememberLibraryShelves(ui)
    val total = shelves.total
    val noSelection = remember { emptyList<LibraryItem>() }
    SideEffect { ui.selection.publish(noSelection) }
    val content = LibraryContent(
        ui, spaces, shelves, news = remember(snapshot.collections) { HubLogic.newCount(snapshot.collections) },
        hasMusicSpace = remember(spaces) { spaces.any { it.kind == SpaceKind.MUSIC } },
        device = ui.vm.lib.deviceMusic.collectAsStateWithLifecycle().value,
    )
    val editor = rememberPageEditor(Page.Library, ui.vm.prefs)

    val listState = rememberLazyListState()
    Column(Modifier.fillMaxSize()) {
        // pinned: the title (or the selection bar) stays while the page scrolls
        if (ui.selection.mode) SelectionTopBar(ui) else TabHeader(ui.vm, "Library", listState, extra = { EditPageButton(editor, "Library") })
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag(UiTags.LIB_LIST), state = listState,
            contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            // not read yet: the spaces show, the sections follow (never the empty state first)
            editor.sections.forEach { pref -> if (pref.id == "spaces" || (total ?: 0) > 0) librarySection(content, pref, editor.edit(pref)) }
            if (total == 0) item("empty", contentType = "empty") { LibraryEmptyState(ui, spaces) }
        }
        if (editor.editing) EditBar(ui.vm.prefs, editor)
    }
}

@Composable
private fun LibraryEmptyState(ui: LibraryUi, spaces: List<Space>) {
    EmptyState(
        art = EmptyArt.LIBRARY, title = "Your library is empty",
        body = "Everything Keepfin saves lands here, organised in spaces. Songs from YouTube Music, Spotify, Apple Music and Deezer go to Music; the rest goes to Downloads.",
        modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp).testTag(UiTags.LIB_EMPTY),
        action = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { ui.vm.navigate(Destination.DOWNLOADS) }, modifier = Modifier.heightIn(min = 48.dp).testTag("empty-paste"),
                    colors = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction),
                ) { Text("Paste a link") }
                OutlinedButton(
                    onClick = { ui.addFiles((spaces.firstOrNull { it.id == "downloads" } ?: spaces.firstOrNull())?.id ?: "downloads") },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("empty-add-files"),
                ) {
                    Icon(Icons.Outlined.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add files")
                }
            }
        },
    )
}

/** What Library home shows below the spaces; [total] is null until the first count. */
internal class LibraryShelves(
    val total: Int?,
    val playlists: List<LibraryCollection>,
    val collections: List<LibraryCollection>,
    val covers: List<LibraryItem>,
    val albums: List<AlbumGroup>,
    val artists: List<ArtistGroup>,
    val videos: List<LibraryItem>,
    val photos: List<LibraryItem>,
) {
    val coverById: Map<String, LibraryItem> = covers.associateBy { it.id }
}

/**
 * Library home's shelves: a few rows each, off the main thread, again when the library changes. The app shell keeps these reads
 * running while another tab shows, so Library opens already filled.
 */
@Composable
internal fun rememberLibraryShelves(ui: LibraryUi): LibraryShelves {
    val snapshot by ui.vm.lib.library.collectAsStateWithLifecycle()
    val browse = ui.browse
    val total by rememberTotal(browse)
    val musicSpaces = ui.vm.musicSpaceIds()
    val videos by rememberTop(browse, remember(musicSpaces) { ItemQuery(kinds = setOf(LibraryItemKind.VIDEO), excludeSpaces = musicSpaces, order = ItemOrder.ADDED) }, ROW_LIMIT)
    val photos by rememberTop(browse, remember { ItemQuery(kinds = setOf(LibraryItemKind.IMAGE), order = ItemOrder.ADDED) }, ROW_LIMIT)
    val albums by rememberBrowsed(browse, "albums", emptyList()) { it.albums(ItemQuery(songs = true), null, ROW_LIMIT) }
    val artists by rememberBrowsed(browse, "artists", emptyList()) { it.artists(ItemQuery(songs = true), null, ROW_LIMIT) }
    val playlists = remember(snapshot.collections) { snapshot.collections.filter { it.kind == CollectionKind.OWN }.sortedByDescending { it.updatedAt }.take(ROW_LIMIT) }
    val collections = remember(snapshot.collections) { HubLogic.shelf(snapshot.collections.filter { it.kind != CollectionKind.OWN }).take(ROW_LIMIT) }
    val covers by rememberByIds(browse, (playlists + collections).mapNotNull { it.coverItemId ?: it.itemIds.firstOrNull() })
    // the space cards' counts (read here too, so the cards open with their numbers)
    val settings by ui.vm.engine.settings.collectAsStateWithLifecycle()
    rememberSpaceCounts(browse)
    rememberSmartCounts(ui, remember(settings.library.spaces) { SpaceLogic.homeSpaces(SpaceLogic.withDefaults(settings.library.spaces)) })
    return LibraryShelves(total, playlists, collections, covers, albums, artists, videos, photos)
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
