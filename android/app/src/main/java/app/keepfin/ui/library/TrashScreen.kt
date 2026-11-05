package app.keepfin.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.SpaceLogic
import app.keepfin.TrashEntry
import app.keepfin.ui.Format
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.EmptyArt
import app.keepfin.ui.components.EmptyState
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.theme.tabular

/** "Deleted today · 30 days left", "Deleted yesterday · 29 days left", "Deleted 12 days ago · 18 days left". */
internal fun trashLine(deletedAt: Long, daysLeft: Int, now: Long = System.currentTimeMillis()): String {
    val ago = ((now - deletedAt) / 86_400_000L).coerceAtLeast(0)
    val deleted = when (ago) { 0L -> "Deleted today"; 1L -> "Deleted yesterday"; else -> "Deleted $ago days ago" }
    return "$deleted · $daysLeft ${if (daysLeft == 1) "day" else "days"} left"
}

/** Three days or fewer left: the line gets an amber warning mark as well as its words. */
internal fun trashUrgent(daysLeft: Int) = daysLeft <= 3

/**
 * The Keepfin trash: files deleted from the Keepfin folder wait here for 30 days. Each row has a visible Restore and a ⋮ menu with
 * "Delete for good…"; Select (or a long press) offers both for many at once; "Empty trash…" erases everything after asking with the
 * count and size. Restore asks nothing; the notice says what came back.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TrashScreen(ui: LibraryUi, padding: PaddingValues) {
    val vm = ui.vm
    val version by ui.browse.changes.collectAsStateWithLifecycle()
    val settings by vm.engine.settings.collectAsStateWithLifecycle()
    val names = remember(settings.library.spaces) { SpaceLogic.withDefaults(settings.library.spaces).associate { it.id to it.name } }
    LaunchedEffect(version) { vm.libraryActions.refreshTrash() }
    val entries = vm.trashEntries
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var eraseOf by remember { mutableStateOf<List<TrashEntry>?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    // what left the trash (restored or erased elsewhere) leaves the selection
    val present = entries?.mapTo(HashSet()) { it.id }
    LaunchedEffect(present) {
        if (present != null && selected.any { it !in present }) selected = selected.filterTo(HashSet()) { it in present }
        if (present != null && present.isEmpty()) selecting = false
    }
    BackHandler(enabled = selecting) { selecting = false; selected = emptySet() }
    val keeps = vm.lib.keepsTrash
    val chosen = entries.orEmpty().filter { it.id in selected }

    Column(Modifier.fillMaxSize().testTag("trash-page")) {
        if (selecting) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selecting = false; selected = emptySet() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, contentDescription = "Cancel selection") }
                    Text(
                        if (selected.isEmpty()) "Select files" else "${selected.size} selected",
                        style = MaterialTheme.typography.titleMedium.tabular().copy(fontSize = 18.sp), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    val all = !entries.isNullOrEmpty() && selected.size == entries.size
                    OutlinedButton(
                        onClick = { selected = if (all) emptySet() else entries.orEmpty().mapTo(HashSet()) { it.id } }, enabled = !entries.isNullOrEmpty(),
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary), contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(if (all) "Select none" else "Select all", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                }
            }
        } else {
            val count = entries?.size ?: 0
            DetailHeader(
                ui, "Trash",
                if (!keeps || entries == null || count == 0) null else "$count ${if (count == 1) "file" else "files"} · ${Format.bytes(entries.sumOf { it.size })}",
            ) {
                if (count > 0) {
                    TextButton(onClick = { selecting = true }, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.TRASH_SELECT)) { Text("Select") }
                    TextButton(onClick = { confirmEmpty = true }, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.TRASH_EMPTY_ALL)) { Text("Empty trash…") }
                }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !keeps -> EmptyState(
                    EmptyArt.LIBRARY, "Trash belongs to the Keepfin folder",
                    "Files you delete from your Keepfin folder wait here for 30 days.",
                )
                entries == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp) }
                entries.isEmpty() -> EmptyState(EmptyArt.LIBRARY, "Trash is empty", "Files deleted from your Keepfin folder appear here for 30 days.")
                else -> LazyColumn(Modifier.fillMaxSize().testTag(UiTags.TRASH_LIST), contentPadding = PaddingValues(bottom = 16.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        TrashRow(
                            entry, names[entry.space], selecting, entry.id in selected,
                            onToggle = { selected = if (entry.id in selected) selected - entry.id else selected + entry.id },
                            onSelect = { selecting = true; selected = selected + entry.id },
                            onRestore = { vm.libraryActions.restoreFromTrash(listOf(entry)) },
                            onErase = { eraseOf = listOf(entry) },
                        )
                        HorizontalDivider(color = Studio.colors.separator)
                    }
                }
            }
        }
        if (selecting) Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { vm.libraryActions.restoreFromTrash(chosen); selecting = false; selected = emptySet() }, enabled = chosen.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(UiTags.TRASH_BULK_RESTORE),
                ) { Text("Restore") }
                OutlinedButton(
                    onClick = { eraseOf = chosen }, enabled = chosen.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(UiTags.TRASH_BULK_ERASE),
                ) { Text("Delete for good…", maxLines = 1) }
            }
        }
    }

    // The confirm dialogs stay open, with a spinner, while the work runs, and close when it ends.
    val busy = vm.trashBusy
    LaunchedEffect(busy) { if (busy == null) { eraseOf = null; confirmEmpty = false; selecting = false; selected = emptySet() } }
    @Composable fun confirm(title: String, body: String, action: String, onConfirm: () -> Unit, onCancel: () -> Unit) = AlertDialog(
        onDismissRequest = { if (busy == null) onCancel() },
        title = { Text(title) },
        text = {
            if (busy == null) Text(body)
            else Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                Text(busy)
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm, enabled = busy == null,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.TRASH_CONFIRM),
            ) { Text(action) }
        },
        dismissButton = { TextButton(onClick = onCancel, enabled = busy == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
    eraseOf?.let { list ->
        confirm(
            if (list.size == 1) "Delete “${list[0].title}” for good?" else "Delete ${list.size} files for good?",
            "${if (list.size == 1) "The file is" else "The files are"} erased from your Keepfin folder (${Format.bytes(list.sumOf { it.size })}). This can't be undone.",
            "Delete for good", { vm.libraryActions.eraseFromTrash(list) }, { eraseOf = null },
        )
    }
    if (confirmEmpty) {
        val all = entries.orEmpty()
        confirm(
            "Empty the trash?", "Erase ${all.size} ${if (all.size == 1) "file" else "files"} (${Format.bytes(all.sumOf { it.size })}) for good? This can't be undone.",
            "Empty trash", { vm.libraryActions.emptyTrash(all.size) }, { confirmEmpty = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashRow(
    entry: TrashEntry, spaceName: String?, selecting: Boolean, selected: Boolean,
    onToggle: () -> Unit, onSelect: () -> Unit, onRestore: () -> Unit, onErase: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val urgent = trashUrgent(entry.daysLeft)
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp)
            .combinedClickable(onClick = { if (selecting) onToggle() }, onLongClick = { if (!selecting) onSelect() else onToggle() })
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp).testTag(UiTags.trashRow(entry.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(spaceName, Format.bytes(entry.size)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (urgent) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Studio.colors.warning, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    trashLine(entry.deletedAt, entry.daysLeft), style = MaterialTheme.typography.bodySmall.tabular(),
                    color = if (urgent) Studio.colors.warning else colors.onSurfaceVariant, fontWeight = if (urgent) FontWeight.SemiBold else null,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!selecting) {
            OutlinedButton(
                onClick = onRestore, contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Restore ${entry.title}" }.testTag(UiTags.trashRestore(entry.id)),
            ) { Text("Restore", maxLines = 1) }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).semantics { contentDescription = "More actions for ${entry.title}" }.testTag(UiTags.trashMore(entry.id))) {
                    Icon(Icons.Outlined.MoreVert, contentDescription = null)
                }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete for good…") }, onClick = { menu = false; onErase() },
                        modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.TRASH_MENU_ERASE),
                    )
                }
            }
        }
    }
}

/** Settings › Storage & copies › Trash: "N files · size" (or "Empty"); a tap opens the Trash page, and Back returns to Settings. */
@Composable
fun TrashSettingsRow(vm: app.keepfin.ui.StudioViewModel) {
    val snapshot by vm.lib.library.collectAsStateWithLifecycle()
    LaunchedEffect(snapshot.scannedAt) { vm.libraryActions.refreshTrash() }
    if (!vm.libraryEnabled || !vm.lib.keepsTrash) return
    val entries = vm.trashEntries.orEmpty()
    app.keepfin.ui.components.SettingsGroup("Trash") {
        app.keepfin.ui.components.NavRow(
            "Trash", Modifier.testTag(UiTags.LIB_TRASH_ROW), icon = Icons.Outlined.DeleteOutline,
            subtitle = if (entries.isEmpty()) "Empty" else "${entries.size} ${if (entries.size == 1) "file" else "files"} · ${Format.bytes(entries.sumOf { it.size })}",
        ) { vm.openLibraryDeep(LibraryRoute.Trash) }
    }
}