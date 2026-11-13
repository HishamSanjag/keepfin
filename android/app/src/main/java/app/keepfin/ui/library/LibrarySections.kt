package app.keepfin.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.keepfin.CollectionKind
import app.keepfin.DeviceMusicState
import app.keepfin.LibraryCollection
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.Space
import app.keepfin.core.layout.CardKind
import app.keepfin.core.layout.SectionPref
import app.keepfin.ui.UiTags
import app.keepfin.ui.media.CardActions
import app.keepfin.ui.media.ItemCard
import app.keepfin.ui.media.MediaCard
import app.keepfin.ui.media.SectionEdit
import app.keepfin.ui.media.SectionHead
import app.keepfin.ui.media.cardKindOf
import app.keepfin.ui.media.dimmed
import app.keepfin.ui.media.emptySection
import app.keepfin.ui.media.section
import app.keepfin.ui.media.sectionHeader

/** Everything Library home's sections draw, read once per frame by [HomeScreen]. */
internal class LibraryContent(
    val ui: LibraryUi,
    val spaces: List<Space>,
    val shelves: LibraryShelves,
    /** The "3 new" on the Collections header. */
    val news: Int,
    val hasMusicSpace: Boolean,
    /** Where "Music on this phone" stands: its card shows under the spaces until it is on. */
    val device: DeviceMusicState = DeviceMusicState.Ready(0),
)

/** One of Library home's sections by its id, in the place the saved layout gives it. A section with nothing to show draws nothing. */
internal fun LazyListScope.librarySection(c: LibraryContent, pref: SectionPref, edit: SectionEdit?) {
    val ui = c.ui
    val shelves = c.shelves
    val covers = shelves.covers.associateBy { it.id }
    val musicSpace = c.spaces.firstOrNull { it.kind == app.keepfin.SpaceKind.MUSIC }
    when (pref.id) {
        "spaces" -> {
            sectionHeader(pref.id, "Spaces", SectionHead({ ui.vm.openLibraryRoot(LibraryRoute.Manage) }, "Edit", UiTags.LIB_SPACES_EDIT), edit)
            item("spaces", contentType = "spaces") { Box(edit.dimmed()) { SpacesGrid(ui, c.spaces) } }
            if (c.device is DeviceMusicState.Off || c.device is DeviceMusicState.NeedsPermission) item("device-music", contentType = "device-music") { DeviceMusicCard(ui.vm) }
        }
        "playlists" -> if (shelves.playlists.isEmpty() && !c.hasMusicSpace) emptySection(pref.id, "Playlists", edit) else section(
            pref.id, "Playlists", shelves.playlists, CardKind.Square, pref.style, { "pl-" + it.id }, SectionHead({ ui.vm.openLibraryDetail(LibraryRoute.Playlists) }), edit,
            extra = { NewPlaylistCard(ui) },
        ) { CollectionCard(it, covers[it.coverItemId ?: it.itemIds.firstOrNull()], ui) }
        "albums" -> if (musicSpace == null) emptySection(pref.id, "Albums", edit) else section(
            pref.id, "Albums", shelves.albums, CardKind.Square, pref.style, { "al-" + it.key }, SectionHead({ openAlbums(ui, musicSpace) }), edit,
        ) { album -> AlbumCard(album) { ui.vm.openLibraryDetail(LibraryRoute.Album(album.space, album.routeKey)) } }
        "artists" -> if (musicSpace == null) emptySection(pref.id, "Artists", edit) else section(
            pref.id, "Artists", shelves.artists, CardKind.Round, pref.style, { "ar-" + it.key }, SectionHead({ openArtists(ui, musicSpace) }), edit,
        ) { artist -> ArtistCard(artist, Modifier.testTag("artist-card-${artist.name}")) { ui.vm.openLibraryDetail(LibraryRoute.Artist(artist.cover?.space.orEmpty(), artist.name)) } }
        "videos" -> itemSection(c, pref, edit, "Videos", shelves.videos)
        "photos" -> itemSection(c, pref, edit, "Photos", shelves.photos)
        "collections" -> section(
            pref.id, "Collections", shelves.collections, CardKind.Square, pref.style, { "co-" + it.id },
            SectionHead({ ui.vm.openLibraryDetail(LibraryRoute.Collections) }, badge = c.news.takeIf { it > 0 }?.let { "$it new" }, modifier = Modifier.testTag(UiTags.LIB_SHELF_COLLECTIONS)), edit,
        ) { CollectionCard(it, covers[it.coverItemId ?: it.itemIds.firstOrNull()], ui) }
    }
}

private fun LazyListScope.itemSection(c: LibraryContent, pref: SectionPref, edit: SectionEdit?, title: String, items: List<LibraryItem>) {
    if (items.isEmpty()) {
        emptySection(pref.id, title, edit)
        return
    }
    section(
        pref.id, title, items, cardKindOf(items.first()), pref.style, { it.id }, SectionHead({ c.ui.vm.openLibraryDetail(LibraryRoute.Section(HomeSection.RECENTLY_ADDED)) }), edit,
    ) { item -> ItemCard(item, c.ui) { c.ui.vm.play(items, items.indexOf(item)) } }
}

private fun openAlbums(ui: LibraryUi, space: Space) {
    ui.vm.chooseMusicTab(space.id, MusicTab.ALBUMS)
    ui.vm.openLibraryDetail(LibraryRoute.Space(space.id))
}

private fun openArtists(ui: LibraryUi, space: Space) {
    ui.vm.chooseMusicTab(space.id, MusicTab.ARTISTS)
    ui.vm.openLibraryDetail(LibraryRoute.Space(space.id))
}

/** A playlist or collection as a square card; a long press on one that was downloaded offers moving or copying it to a space. */
@Composable
private fun CollectionCard(c: LibraryCollection, cover: LibraryItem?, ui: LibraryUi) {
    var menu by remember { mutableStateOf(false) }
    Box {
        MediaCard(
            CardKind.Square, c.name, collectionCardLine(c, c.itemIds.size),
            CardActions(
                { ui.vm.openLibraryDetail(collectionRoute(c)) },
                onLongClick = if (c.kind != CollectionKind.OWN) ({ menu = true }) else null, longClickLabel = "Move or copy to a space",
            ),
            Modifier.testTag(UiTags.collectionRow(c.id)),
        ) { ArtTile(cover?.artModel(), c.name, LibraryItemKind.AUDIO, Modifier.fillMaxSize(), iconSize = 36.dp) }
        if (menu) DropdownMenu(true, { menu = false }) { CollectionSpaceItems(c, ui) { menu = false } }
    }
}

/** The card that ends the playlists: starts a new one. */
@Composable
private fun NewPlaylistCard(ui: LibraryUi) {
    MediaCard(
        CardKind.Square, "New playlist", "Name it, add items", CardActions({ ui.sheet(LibrarySheet.NewPlaylist(emptyList())) }), Modifier.testTag("playlist-create"),
    ) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
