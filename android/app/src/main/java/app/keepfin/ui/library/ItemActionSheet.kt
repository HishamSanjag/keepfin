package app.keepfin.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.PlaylistRemove
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VideocamOff
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import app.keepfin.ui.StudioSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.PlaybackItems
import app.keepfin.ui.UiTags

/**
 * One entry of the item action sheet. [why] is shown under a disabled entry so nobody has to guess why it does nothing.
 * [name] is stable (tests and the Windows app use the same words).
 */
data class ItemAction(
    val name: String,
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val why: String? = null,
    val danger: Boolean = false,
    val run: () -> Unit = {},
)

/** Music services whose songs Keepfin matched on YouTube Music; "Pick another match" belongs to these. */
private val matchedSources = listOf("spotify:", "apple-music:", "deezer:")

/**
 * The actions of one item, in order. The same list opens from Home, Search, the Library, an album page and the player, so there is
 * one place to learn it. Entries that do not apply to the item stay in the list, disabled with the reason.
 */
fun itemActions(ui: LibraryUi, item: LibraryItem, from: OwnCollection? = null): List<ItemAction> {
    val vm = ui.vm
    val one = listOf(item)
    val song = item.kind == LibraryItemKind.AUDIO
    val sound = song || item.kind == LibraryItemKind.VIDEO
    val notSound = "Photos and other files can't do this."
    val artist = (item.artist ?: item.albumArtist)?.takeIf { it.isNotBlank() }
    val all = buildList {
        add(ItemAction("play", if (item.kind == LibraryItemKind.IMAGE) "View" else "Play", Icons.Outlined.PlayArrow) { vm.play(one, 0) })
        if (vm.isVideoSong(item)) add(ItemAction("watch", app.keepfin.core.spaces.Songs.WATCH, Icons.Outlined.Videocam) { vm.watchVideoSong(item) })
        else if (item.kind == LibraryItemKind.VIDEO) add(ItemAction("audio", "Play as audio", Icons.Outlined.Headphones) { vm.playAudio(one) })
        // a song keeps its official music video beside it (dev-docs/plans/MUSIC-VIDEOS.md)
        if (song && !item.remote) {
            if (item.musicVideo) {
                add(ItemAction("watchmusicvideo", "Watch music video", Icons.Outlined.Videocam) { vm.watchMusicVideo(item) })
                add(ItemAction("removemusicvideo", "Remove music video", Icons.Outlined.VideocamOff) { ui.sheet(LibrarySheet.RemoveMusicVideo(item)) })
            } else add(ItemAction("getmusicvideo", "Get the music video", Icons.Outlined.Videocam) { vm.getMusicVideo(item) })
        }
        // lyrics are looked up once when a song finishes: one that missed them can look again
        if (song && !item.remote && !item.hasLyrics) add(ItemAction("findlyrics", app.keepfin.core.lyrics.Lyrics.FIND, Icons.Outlined.Lyrics) { vm.songVideo.findLyrics(item) })
        add(ItemAction("next", "Play next", Icons.Outlined.SkipNext, enabled = sound, why = notSound.takeIf { !sound }) { vm.playNext(one) })
        add(ItemAction("queue", "Add to queue", Icons.AutoMirrored.Outlined.QueueMusic, enabled = sound, why = notSound.takeIf { !sound }) { vm.addToQueue(one) })
        add(ItemAction("playlist", "Add to playlist", Icons.AutoMirrored.Outlined.PlaylistAdd, enabled = sound, why = notSound.takeIf { !sound }) { ui.sheet(LibrarySheet.AddToPlaylist(listOf(item.id))) })
        // only on the page of one of your collections: takes it out of that collection, the file stays
        if (from != null) add(ItemAction("uncollect", "Remove from “${from.name}”", Icons.Outlined.PlaylistRemove) { vm.libraryActions.removeFromCollection(from.id, one.map { it.id }) })
        add(
            ItemAction(
                "favorite", if (item.favorite) "Remove from favorites" else "Add to favorites", if (item.favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                enabled = sound, why = notSound.takeIf { !sound },
            ) { vm.libraryActions.favorite(item) },
        )
        if (LibraryViews.continueWatching(one).isNotEmpty()) add(ItemAction("uncontinue", "Remove from Continue watching", Icons.Outlined.Close) { vm.removeFromContinue(item) })
        val hasAlbum = PlaybackItems.albumKey(item) != null
        add(ItemAction("album", "Go to album", Icons.Outlined.Album, enabled = hasAlbum, why = "No album tag".takeIf { !hasAlbum }) { vm.openAlbumOf(item) })
        add(ItemAction("artist", "Go to artist", Icons.Outlined.Person, enabled = artist != null, why = "No artist tag".takeIf { artist == null }) { vm.openArtistOf(item) })
        // follow the artist: their new releases show on Home (nothing downloads until you keep one)
        if (song && artist != null) {
            val followed = vm.engine.following.value.isFollowing(artist)
            add(ItemAction("follow", if (followed) "Unfollow $artist" else "Follow $artist", if (followed) Icons.Outlined.PersonRemove else Icons.Outlined.PersonAdd) { vm.following.toggle(artist) })
        }
        add(ItemAction("showfile", "Show file", Icons.Outlined.FindInPage) { ui.sheet(LibrarySheet.Info(item)) })
        add(ItemAction("edit", "Edit info", Icons.Outlined.Edit, enabled = sound, why = "Photos and other files have no tags to edit. Use Rename for the file name.".takeIf { !sound }) { ui.sheet(LibrarySheet.EditInfo(item)) })
        add(ItemAction("move", "Move to space…", Icons.Outlined.DriveFileMove) { ui.sheet(LibrarySheet.MoveTo(listOf(item.id))) })
        add(ItemAction("copy", "Copy to space…", Icons.Outlined.ContentCopy) { ui.sheet(LibrarySheet.ToSpace(emptyList(), listOf(item.id), true, item.space, item.title)) })
        if (song && matchedSources.any { item.sourceId?.startsWith(it) == true }) {
            add(ItemAction("match", "Pick another match", Icons.Outlined.SwapHoriz, enabled = false, why = "Choosing a different recording comes in a later update."))
        }
        add(ItemAction("rename", "Rename", Icons.Outlined.DriveFileRenameOutline) { ui.sheet(LibrarySheet.Rename(item)) })
        add(ItemAction("external", "Open in another app", Icons.AutoMirrored.Outlined.OpenInNew) { vm.openExternally(item) })
        add(ItemAction("share", "Share", Icons.Outlined.Share) { ExternalOpen.share(ui.context, item)?.let(vm::say) })
        add(ItemAction("delete", "Delete file…", Icons.Outlined.DeleteOutline, danger = true) { ui.sheet(LibrarySheet.Delete(listOf(item.id), item.title)) })
    }
    if (!app.keepfin.isDeviceItem(item.id)) return all
    // music already on the phone stays where it is: no moving, copying, renaming or tag editing; it can be taken out of Keepfin
    val kept = all.filter { it.name !in deviceHides }
    val remove = ItemAction("removefromkeepfin", "Remove from Keepfin", Icons.Outlined.PlaylistRemove) { vm.libraryActions.hideDevice(listOf(item.id)) }
    return kept.take(kept.size - 1) + remove + kept.last()
}

private val deviceHides = setOf("getmusicvideo", "findlyrics", "move", "copy", "edit", "rename", "match", "watchmusicvideo", "removemusicvideo")

/** The bottom sheet every ⋯ and long-press opens: the item's art and title on top, then [itemActions]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemActionSheet(ui: LibraryUi, item: LibraryItem, from: OwnCollection?, onDismiss: () -> Unit) {
    StudioSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(UiTags.ACTION_SHEET),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ItemArt(item, Modifier.size(48.dp), iconSize = 22.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                    Text(subtitleOf(item), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            itemActions(ui, item, from).forEach { action ->
                ActionRow(action) { onDismiss(); action.run() }
            }
        }
    }
}

@Composable
private fun ActionRow(action: ItemAction, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val tint = when { !action.enabled -> colors.onSurface.copy(alpha = .38f); action.danger -> colors.error; else -> colors.onSurface }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(enabled = action.enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 6.dp)
            .testTag(UiTags.itemAction(action.name))
            .semantics(mergeDescendants = true) { if (!action.enabled) { disabled(); stateDescription = action.why ?: "Not available" } },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(action.icon, contentDescription = null, tint = if (action.enabled && !action.danger) colors.onSurfaceVariant else tint)
        Column(Modifier.weight(1f)) {
            Text(action.label, style = MaterialTheme.typography.bodyLarge, color = tint)
            if (!action.enabled && action.why != null) Text(action.why, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}
