package app.keepfin.ui.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.CollectionKind
import app.keepfin.core.spaces.PreviewWords
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.library.NameDialog

/** What the "Add to playlist" chip shows and does: the person's own playlists, the one chosen for this review, and the ways to change it. */
class PlaylistUi(val playlists: List<Pair<String, String>>, val chosen: String?, val onPick: (String?) -> Unit, val onCreate: (String) -> Unit)

/**
 * The playlist the finished download(s) of this review join. [chosen] and [onPick] belong to the review (cleared with it, off by default);
 * null while the library is off. A new playlist uses the existing create dialog and is chosen at once.
 */
@Composable
fun rememberPlaylistUi(vm: StudioViewModel, chosen: String?, onPick: (String?) -> Unit): PlaylistUi? {
    val library by vm.lib.library.collectAsStateWithLifecycle()
    if (!vm.libraryEnabled) return null
    val own = library.collections.filter { it.kind == CollectionKind.OWN }.map { it.id to it.name }
    return PlaylistUi(own, chosen, onPick) { name -> vm.libraryActions.createPlaylist(name, emptyList()) { onPick(it) } }
}

/** "Add to playlist ▾", or the chosen playlist's name; its menu is "No playlist", the person's playlists, then "New playlist…". */
@Composable
fun AddToPlaylistChip(state: PlaylistUi, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    val name = state.playlists.firstOrNull { it.first == state.chosen }?.second
    val label = PreviewWords.playlistLabel(name)
    Box(modifier) {
        AssistChip(
            onClick = { open = true },
            label = { Text(label, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.ADD_TO_PLAYLIST).semantics { contentDescription = "$label. Choose a playlist for this download" },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(PreviewWords.PLAYLIST_NONE) }, leadingIcon = { if (name == null) Icon(Icons.Outlined.Check, null) },
                onClick = { open = false; state.onPick(null) }, modifier = Modifier.testTag(UiTags.playlistOption("none")),
            )
            state.playlists.forEach { (id, title) ->
                DropdownMenuItem(
                    text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, leadingIcon = { if (id == state.chosen) Icon(Icons.Outlined.Check, null) },
                    onClick = { open = false; state.onPick(id) }, modifier = Modifier.testTag(UiTags.playlistOption(id)),
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(PreviewWords.PLAYLIST_NEW) }, leadingIcon = { Icon(Icons.Outlined.Add, null) },
                onClick = { open = false; naming = true }, modifier = Modifier.testTag(UiTags.playlistOption("new")),
            )
        }
    }
    if (naming) NameDialog("New playlist", "Playlist name", "", "Create", { naming = false }) { typed -> naming = false; state.onCreate(typed) }
}
