package app.keepfin.ui.library

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.keepfin.ItemOrder
import app.keepfin.ItemQuery
import app.keepfin.LibraryBrowse
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.core.spaces.MusicVideoRow
import app.keepfin.core.spaces.MusicVideoRows
import app.keepfin.core.spaces.VideoSource
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.EmptyArt
import app.keepfin.ui.components.EmptyState
import app.keepfin.ui.player.MusicVideoLengths
import app.keepfin.ui.player.MusicVideoLogic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val SHELF_LIMIT = 12

// A song's music video shown on its own (dev-docs/plans/MUSIC-VIDEOS.md, "A video is usable on its own"): the core lists the rows and words them,
// the file stays the song's companion, a tap plays the video alone on the video screen. These are the shelf, the "See all" page and the row.

/** A video row and the song behind it (its cover is the song's). */
internal class MusicVideoEntry(val row: MusicVideoRow, val item: LibraryItem)

/** The rows of [items] (songs that keep a video), in the core's order: newest first. */
internal fun musicVideoEntries(items: List<LibraryItem>): List<MusicVideoEntry> {
    val byId = items.associateBy { it.id }
    val sources = items.map { VideoSource(it.id, it.kind.wire, it.title, it.artist ?: it.albumArtist, it.musicVideo, addedAt = it.addedAt) }
    return MusicVideoRows.list(sources).mapNotNull { row -> byId[row.itemId]?.let { MusicVideoEntry(row, it) } }
}

/** The music video rows (those whose title, artist or album match [text] when given), read off the main thread and again when the library changes. */
@Composable
internal fun rememberMusicVideos(browse: LibraryBrowse, text: String = "", limit: Int = 500): State<List<MusicVideoEntry>> {
    val query = remember(text) { ItemQuery(musicVideos = true, text = text, order = ItemOrder.ADDED) }
    val items by rememberTop(browse, query, limit)
    return remember(items) { mutableStateOf(musicVideoEntries(items)) }
}

/** "3:35" for the row, read from the file once; null while unread or unreadable. */
@Composable
private fun rememberVideoLength(ui: LibraryUi, id: String): String? {
    val context = LocalContext.current
    val seconds by produceState<Double?>(null, id) {
        value = withContext(Dispatchers.IO) { runCatching { ui.vm.lib.musicVideoFacts(id)?.let { MusicVideoLengths.of(context, id, it) } }.getOrNull() }
    }
    return MusicVideoLogic.length(seconds)
}

/** The shelf on Library home and on Home's Videos filter: only when there are rows. [onSeeAll] opens the page with all of them. */
internal fun LazyListScope.musicVideoShelf(entries: List<MusicVideoEntry>, ui: LibraryUi, onSeeAll: () -> Unit) {
    if (entries.isEmpty()) return
    shelf("music-videos", MusicVideoRows.SHELF, onSeeAll, Modifier.testTag(UiTags.MUSIC_VIDEO_SHELF)) {
        items(entries.take(SHELF_LIMIT), key = { "mv-" + it.row.id }) { entry ->
            CoverCard(
                entry.row.title, listOfNotNull(MusicVideoRows.LABEL, entry.row.artist).joinToString(" · "), entry.item.artModel(), entry.row.title, LibraryItemKind.AUDIO,
                Modifier.width(120.dp).testTag(UiTags.musicVideoRow(entry.row.id)),
            ) { ui.vm.playMusicVideo(entry.item.id) }
        }
    }
}

/** One row: cover, title, then the label, the artist and the length; a tap plays the video alone, the menu has Play, Go to song and Delete video. */
@Composable
internal fun MusicVideoRowItem(entry: MusicVideoEntry, ui: LibraryUi, modifier: Modifier = Modifier) {
    val row = entry.row
    val length = rememberVideoLength(ui, entry.item.id)
    var menu by remember { mutableStateOf(false) }
    val line = listOfNotNull(MusicVideoRows.LABEL, row.artist, length).joinToString(" · ")
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(UiTags.musicVideoRow(row.id)).semantics(mergeDescendants = true) { contentDescription = "${row.title}, $line" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = 64.dp).clickable(role = Role.Button, onClickLabel = "Play") { ui.vm.playMusicVideo(entry.item.id) }.padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtTile(entry.item.artModel(), row.title, LibraryItemKind.AUDIO, Modifier.size(48.dp), MaterialTheme.shapes.small)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).clearAndSetSemantics { }) {
                Text(row.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).testTag(UiTags.musicVideoRow(row.id) + "-menu")) {
            Icon(Icons.Outlined.MoreVert, contentDescription = "More actions for ${row.title}")
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Play") }, leadingIcon = { Icon(Icons.Outlined.PlayArrow, null) },
                    onClick = { menu = false; ui.vm.playMusicVideo(entry.item.id) }, modifier = Modifier.heightIn(min = 48.dp).testTag("mv-play"),
                )
                DropdownMenuItem(
                    text = { Text(MusicVideoRows.GO_TO_SONG) }, leadingIcon = { Icon(Icons.Outlined.Album, null) },
                    onClick = { menu = false; ui.vm.goToSong(entry.item) }, modifier = Modifier.heightIn(min = 48.dp).testTag("mv-go-to-song"),
                )
                // the same confirmation as the song's Remove music video; the song stays
                DropdownMenuItem(
                    text = { Text(MusicVideoRows.DELETE, color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = MaterialTheme.colorScheme.error) },
                    onClick = { menu = false; ui.sheet(LibrarySheet.RemoveMusicVideo(entry.item)) }, modifier = Modifier.heightIn(min = 48.dp).testTag("mv-delete"),
                )
            }
        }
    }
}

/** "See all" of the shelf: every music video, newest first. */
@Composable
fun MusicVideosScreen(ui: LibraryUi, padding: PaddingValues) {
    val entries by rememberMusicVideos(ui.browse)
    Column(Modifier.fillMaxSize()) {
        DetailHeader(ui, MusicVideoRows.SHELF, if (entries.size == 1) "1 video" else "${entries.size} videos")
        if (entries.isEmpty()) EmptyState(art = EmptyArt.LIBRARY, title = "No music videos yet", body = "A song's music video appears here once you keep it.", modifier = Modifier.weight(1f))
        else LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(UiTags.MUSIC_VIDEOS_LIST), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
            items(entries, key = { it.row.id }) { MusicVideoRowItem(it, ui) }
        }
    }
}
