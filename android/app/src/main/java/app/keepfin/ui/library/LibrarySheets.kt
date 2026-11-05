package app.keepfin.ui.library

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import app.keepfin.ui.StudioSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.LibraryItem
import app.keepfin.TagEdit
import app.keepfin.ui.Format
import app.keepfin.ui.UiTags
import app.keepfin.ui.theme.Studio

/** Hosts whichever sheet or dialog the Library raised. */
@Composable
fun LibrarySheets(ui: LibraryUi, sheet: LibrarySheet?, onDismiss: () -> Unit) {
    when (sheet) {
        null -> Unit
        is LibrarySheet.Actions -> ItemActionSheet(ui, sheet.item, sheet.from, onDismiss)
        is LibrarySheet.AddToPlaylist -> AddToPlaylistSheet(ui, sheet.ids, onDismiss)
        is LibrarySheet.NewPlaylist -> NameDialog("New playlist", "Playlist name", "", "Create", onDismiss) { name ->
            ui.vm.libraryActions.createPlaylist(name, sheet.ids, sheet.space); ui.selection.clear(); onDismiss()
        }
        is LibrarySheet.MoveTo -> MoveToSheet(ui, sheet.ids, onDismiss)
        is LibrarySheet.Rename -> RenameDialog(ui, sheet.item, onDismiss)
        is LibrarySheet.EditInfo -> EditInfoSheet(ui, sheet.item, onDismiss)
        is LibrarySheet.Delete -> DeleteDialog(ui, sheet, onDismiss)
        is LibrarySheet.RenamePlaylist -> NameDialog("Rename playlist", "Playlist name", sheet.name, "Rename", onDismiss) { name -> ui.vm.libraryActions.renamePlaylist(sheet.id, name, sheet.ids); onDismiss() }
        is LibrarySheet.DeletePlaylist -> AlertDialog(
            onDismissRequest = onDismiss, title = { Text("Delete “${sheet.name}”?") },
            text = { Text("The playlist is removed. The items in it stay in your library.") },
            confirmButton = {
                Button(onClick = { ui.vm.libraryActions.deletePlaylist(sheet.id); onDismiss(); ui.vm.openLibraryRoot(LibraryRoute.Playlists) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) { Text("Delete playlist") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
        )
        is LibrarySheet.RemoveMusicVideo -> AlertDialog(
            onDismissRequest = onDismiss, title = { Text(app.keepfin.core.spaces.MusicVideoRows.DELETE_ASK) },
            text = { Text("The video file beside “${sheet.item.title}” is deleted from your Keepfin folder and from your copies. The song stays. This can't be undone.") },
            confirmButton = {
                Button(onClick = { ui.vm.removeMusicVideo(sheet.item); onDismiss() }, modifier = Modifier.testTag("remove-music-video-confirm"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) { Text("Remove music video") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Keep") } },
        )
        is LibrarySheet.EditSpace -> SpaceEditorSheet(ui.vm, sheet.space, onDismiss)
        is LibrarySheet.RemoveSpace -> AlertDialog(
            onDismissRequest = onDismiss, title = { Text("Remove “${sheet.space.name}”?") },
            text = { Text("The space leaves your library, but its files stay on your phone and in the same folder. Add the folder as a space again any time to bring them back.") },
            confirmButton = {
                Button(
                    onClick = { onDismiss(); ui.vm.spaces.removeSpace(sheet.space.id, sheet.space.name) }, modifier = Modifier.heightIn(min = 48.dp).testTag("space-remove-confirm"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text("Remove space") }
            },
            dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep") } },
        )
        is LibrarySheet.Info -> InfoDialog(ui.vm.whereSource, sheet.item, onDismiss)
        is LibrarySheet.NewSmart -> SpaceEditorSheet(ui.vm, null, onDismiss, preset = sheet)
        is LibrarySheet.AddFromLibrary -> AddFromLibrarySheet(ui, sheet.space, onDismiss)
        is LibrarySheet.AddFromLink -> AddFromLinkSheet(ui, sheet.space, onDismiss)
        is LibrarySheet.ToSpace -> SpacePickerSheet(ui, sheet, onDismiss)
        is LibrarySheet.Follow -> FollowSheet(ui, sheet.collectionId, onDismiss)
        is LibrarySheet.Tags -> TagsSheet(ui, sheet.ids, onDismiss)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SheetFrame(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    StudioSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag(UiTags.LIB_SHEET)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() })
            content()
        }
    }
}

@Composable
internal fun NameDialog(title: String, label: String, initial: String, confirm: String, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(title) },
        text = {
            OutlinedTextField(name, { name = it.take(80) }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("name-field"),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
        },
        confirmButton = { Button(onClick = { onSubmit(name) }, enabled = name.isNotBlank(), modifier = Modifier.testTag("name-confirm")) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddToPlaylistSheet(ui: LibraryUi, ids: List<String>, onDismiss: () -> Unit) {
    val snapshot by ui.vm.lib.library.collectAsStateWithLifecycle()
    val playlists = snapshot.playlists
    var creating by rememberSaveable { mutableStateOf(false) }
    if (creating) {
        NameDialog("New playlist", "Playlist name", "", "Create and add", { creating = false }) { name -> ui.vm.libraryActions.createPlaylist(name, ids); ui.selection.clear(); onDismiss() }
    }
    SheetFrame("Add to playlist", onDismiss) {
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            item("new") {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { creating = true }.padding(horizontal = 24.dp).testTag("playlist-new"), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Text("New playlist…", style = MaterialTheme.typography.bodyLarge, color = app.keepfin.ui.theme.Studio.colors.accentText)
                }
            }
            items(playlists, key = { it.id }) { playlist ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { ui.vm.libraryActions.addToPlaylist(playlist.id, playlist.name, ids); ui.selection.clear(); onDismiss() }
                        .padding(horizontal = 24.dp).testTag("playlist-${playlist.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${playlist.itemIds.size} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.size(12.dp))
    }
}

@Composable
private fun MoveToSheet(ui: LibraryUi, ids: List<String>, onDismiss: () -> Unit) {
    val settings by ui.vm.engine.settings.collectAsStateWithLifecycle()
    val inSpaces by rememberBrowsed(ui.browse, ids, emptySet<String>()) { it.spacesOf(ids) }
    SheetFrame("Move to…", onDismiss) {
        Text(
            "Files move into the space's folder. Songs stay under Music, videos under Movies.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
        )
        settings.library.folderSpaces.forEach { space ->
            val here = inSpaces.isNotEmpty() && inSpaces.all { it == space.id }
            Row(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled = !here) { ui.vm.libraryActions.moveItems(ids, space.id); ui.selection.clear(); onDismiss() }
                    .padding(horizontal = 24.dp).testTag("move-${space.id}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Folder, contentDescription = null, tint = if (here) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(space.name, style = MaterialTheme.typography.bodyLarge, color = if (here) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
                    Text(if (here) "Already here" else space.folder, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.size(12.dp))
    }
}

private fun displayStem(context: android.content.Context, item: LibraryItem): String {
    val name = runCatching {
        context.contentResolver.query(Uri.parse(item.uri), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()
    return (name ?: item.title).substringBeforeLast('.', name ?: item.title)
}

@Composable
private fun RenameDialog(ui: LibraryUi, item: LibraryItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val initial = remember(item.id) { displayStem(context, item) }
    NameDialog("Rename file", "File name", initial, "Rename", onDismiss) { name -> ui.vm.libraryActions.renameItem(item.id, name); onDismiss() }
}

@Composable
private fun DeleteDialog(ui: LibraryUi, sheet: LibrarySheet.Delete, onDismiss: () -> Unit) {
    val many = sheet.ids.size > 1
    // How many of the files are in the Keepfin folder (they go to its trash and Undo brings them back); read off the main thread
    val inFolder by androidx.compose.runtime.produceState<Int?>(null, sheet.ids) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { ui.vm.lib.trashableCount(sheet.ids) }.getOrDefault(0) }
    }
    // Delete for good: skip keeping them in the Keepfin trash (Undo still works while its notice shows)
    var forGood by remember(sheet.ids) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss, modifier = Modifier.testTag(UiTags.LIB_CONFIRM_DELETE),
        title = { Text(if (many) "Delete ${sheet.ids.size} files?" else "Delete this file?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    when (val folder = inFolder ?: 0) {
                        0 -> (if (many) "These files are" else "“${sheet.title}” is") + " outside your Keepfin folder, so there is no trash for ${if (many) "them" else "it"}: deleting erases ${if (many) "them" else "it"} for good. Your download history stays."
                        sheet.ids.size -> if (forGood) (if (many) "These files are" else "“${sheet.title}” is") + " erased from your phone once the Undo notice closes. Your download history stays."
                            else (if (many) "These files go" else "“${sheet.title}” goes") + " to the Keepfin trash and leave your library. Undo can bring ${if (many) "them" else "it"} back right after, and the trash keeps ${if (many) "them" else "it"} for 30 days. Your download history stays."
                        else -> if (forGood) "$folder of these files are erased once the Undo notice closes; the others are removed from your phone for good now. Your download history stays."
                            else "$folder of these files go to the Keepfin trash (Undo can bring them back right after). The others are removed from your phone for good. Your download history stays."
                    },
                )
                if ((inFolder ?: 0) > 0) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(forGood, role = androidx.compose.ui.semantics.Role.Checkbox) { forGood = it }
                            .testTag("delete-for-good"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Checkbox(checked = forGood, onCheckedChange = null)
                        Column(Modifier.weight(1f)) {
                            Text("Delete for good", style = MaterialTheme.typography.bodyLarge)
                            Text("Don't keep ${if (many) "them" else "it"} in the Keepfin trash", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { ui.vm.libraryActions.deleteItems(sheet.ids, forGood); onDismiss() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.heightIn(min = 48.dp).testTag("delete-confirm"),
            ) { Text(if (many) "Delete ${sheet.ids.size} files" else "Delete file") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep") } },
    )
}

@Composable
internal fun InfoDialog(where: app.keepfin.where.WhereFilesSource?, item: LibraryItem, onDismiss: () -> Unit) {
    // "On: this phone, Desk PC, Google Drive": read from the library's placements off the main thread
    val line by androidx.compose.runtime.produceState<String?>(null, item.id, where) {
        value = if (where == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { app.keepfin.where.WhereWords.itemLine(where.placesOf(item.id)) }.getOrNull() }
    }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text(item.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOfNotNull(
                    item.artist?.let { "Artist" to it }, item.album?.let { "Album" to it }, item.year?.let { "Year" to "$it" }, item.genre?.let { "Genre" to it },
                    item.duration?.let { "Duration" to LibraryViews.mmss(it) }, "Size" to Format.bytes(item.size), "Folder" to item.folder.ifBlank { "Top level" },
                ).forEach { (k, v) ->
                    Row { Text(k, Modifier.width(88.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v, style = MaterialTheme.typography.bodyMedium) }
                }
                line?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp).testTag("where-item-line")) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Title, artist, album, album artist, track, disc, year, genre and cover, written into the file. */
@Composable
private fun EditInfoSheet(ui: LibraryUi, item: LibraryItem, onDismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(item.title) }
    var artist by rememberSaveable { mutableStateOf(item.artist.orEmpty()) }
    var album by rememberSaveable { mutableStateOf(item.album.orEmpty()) }
    var albumArtist by rememberSaveable { mutableStateOf(item.albumArtist.orEmpty()) }
    var track by rememberSaveable { mutableStateOf(item.track?.toString().orEmpty()) }
    var disc by rememberSaveable { mutableStateOf(item.disc?.toString().orEmpty()) }
    var year by rememberSaveable { mutableStateOf(item.year?.toString().orEmpty()) }
    var genre by rememberSaveable { mutableStateOf(item.genre.orEmpty()) }
    var cover by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) cover = uri.toString() }
    val numbersOk = listOf(track, disc, year).all { it.isBlank() || it.toIntOrNull() != null }
    SheetFrame("Edit info", onDismiss) {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Changes are written into the file's tags, so other players show them too.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("edit-title"))
            OutlinedTextField(artist, { artist = it }, label = { Text("Artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(album, { album = it }, label = { Text("Album") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(albumArtist, { albumArtist = it }, label = { Text("Album artist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val number = KeyboardOptions(keyboardType = KeyboardType.Number)
                OutlinedTextField(track, { track = it.filter(Char::isDigit).take(3) }, label = { Text("Track") }, singleLine = true, keyboardOptions = number, modifier = Modifier.weight(1f))
                OutlinedTextField(disc, { disc = it.filter(Char::isDigit).take(2) }, label = { Text("Disc") }, singleLine = true, keyboardOptions = number, modifier = Modifier.weight(1f))
                OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, label = { Text("Year") }, singleLine = true, keyboardOptions = number, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(genre, { genre = it }, label = { Text("Genre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            // a cover is written into MP3, M4A and FLAC songs only; a video keeps its picture
            if (item.kind == app.keepfin.LibraryItemKind.AUDIO) OutlinedButton(onClick = { picker.launch("image/*") }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (cover == null) "Choose cover…" else "Cover chosen")
            }
        }
        Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
            Button(
                enabled = title.isNotBlank() && numbersOk, modifier = Modifier.heightIn(min = 48.dp).testTag("edit-save"),
                onClick = {
                    fun changed(now: String, before: String?) = now.trim().takeIf { it != before.orEmpty() }
                    ui.vm.libraryActions.editInfo(
                        item.id,
                        TagEdit(
                            title = changed(title, item.title), artist = changed(artist, item.artist), album = changed(album, item.album), albumArtist = changed(albumArtist, item.albumArtist),
                            track = track.toIntOrNull()?.takeIf { it != item.track }, disc = disc.toIntOrNull()?.takeIf { it != item.disc }, year = year.toIntOrNull()?.takeIf { it != item.year },
                            genre = changed(genre, item.genre), coverUri = cover,
                        ),
                    )
                    onDismiss()
                },
            ) { Text("Save") }
        }
    }
}
