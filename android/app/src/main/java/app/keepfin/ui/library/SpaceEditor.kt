package app.keepfin.ui.library

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import app.keepfin.ui.StudioSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.keepfin.FolderLayout
import app.keepfin.SmartRules
import app.keepfin.Space
import app.keepfin.SpaceColor
import app.keepfin.SpaceIcon
import app.keepfin.SpaceKind
import app.keepfin.SpaceLogic
import app.keepfin.SpaceNaming
import app.keepfin.SpaceTones
import app.keepfin.SpaceType
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.SwitchRow
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.theme.spaceTone
import java.util.UUID

private fun SpaceKind.label() = when (this) { SpaceKind.MUSIC -> "Music"; SpaceKind.VIDEO -> "Videos"; SpaceKind.MIXED -> "Mixed" }

fun SpaceKind.blurb() = when (this) {
    SpaceKind.MUSIC -> "Songs, albums, artists and collections. Feeds the music player."
    SpaceKind.VIDEO -> "A poster grid with Continue watching."
    SpaceKind.MIXED -> "Everything by folder: videos, songs and photos."
}

private fun FolderLayout.label() = when (this) {
    FolderLayout.FLAT -> "One folder"
    FolderLayout.COLLECTION -> "A folder for each playlist or album"
    FolderLayout.PLATFORM -> "A folder for each site"
    FolderLayout.ARTIST_ALBUM -> "Artist and album folders"
}

private fun SpaceNaming.label() = when (this) {
    SpaceNaming.TITLE -> "Title"
    SpaceNaming.TITLE_UPLOADER -> "Title - Channel"
    SpaceNaming.UPLOADER_TITLE -> "Channel - Title"
    SpaceNaming.DATE_TITLE -> "Date - Title"
    SpaceNaming.ARTIST_TITLE -> "Artist - Title"
}

/**
 * New or edit space. Name, then the type (Folder stores files and receives downloads; Smart stores nothing and gathers items by
 * rules), eight colors and about ten icons. Folder spaces add the folder fields (subfolders, file names and the rest under "More
 * options"); a smart space's Next leads to its rules. [preset] opens the sheet as a new smart space with rules already filled in
 * (Save as smart space in the tag filter).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SpaceEditorSheet(vm: StudioViewModel, space: Space?, onDismiss: () -> Unit, preset: LibrarySheet.NewSmart? = null) {
    val creating = space == null
    val existing = vm.engine.settings.value.library.spaces
    val base = space ?: Space(
        UUID.randomUUID().toString(), preset?.name.orEmpty(), SpaceKind.MIXED, "", FolderLayout.COLLECTION, SpaceNaming.TITLE, skipExisting = false,
        type = if (preset != null) SpaceType.SMART else SpaceType.FOLDER, rules = preset?.rules,
        color = SpaceLogic.nextColor(existing), pinned = existing.count { it.isPinned } < SpaceLogic.HOME_MAX,
    )
    var name by rememberSaveable { mutableStateOf(base.name) }
    var type by rememberSaveable { mutableStateOf(base.type) }
    var color by rememberSaveable { mutableStateOf(base.color ?: SpaceLogic.defaultColor(base, 0)) }
    var icon by rememberSaveable { mutableStateOf(base.icon ?: if (preset != null) SpaceIcon.STAR else SpaceLogic.defaultIcon(base)) }
    var kind by rememberSaveable { mutableStateOf(base.kind) }
    var folder by rememberSaveable { mutableStateOf(base.folder) }
    var layout by rememberSaveable { mutableStateOf(base.layout) }
    var naming by rememberSaveable { mutableStateOf(base.naming) }
    var skip by rememberSaveable { mutableStateOf(base.skipExisting) }
    var scan by rememberSaveable { mutableStateOf(base.scanFolder) }
    var ignore by rememberSaveable { mutableStateOf(base.ignore.joinToString(", ")) }
    var moveExisting by rememberSaveable { mutableStateOf(false) }
    var more by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var saving by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var iconTouched by rememberSaveable { mutableStateOf(false) }
    val smart = type == SpaceType.SMART
    val folderChanged = !creating && !smart && folder.trim() != base.folder
    val layouts = FolderLayout.entries.filter { it != FolderLayout.ARTIST_ALBUM || kind == SpaceKind.MUSIC }
    val tone = spaceTone(color)

    fun draft(): Space = if (smart) base.copy(
        name = name.trim(), type = SpaceType.SMART, kind = SpaceKind.MIXED, folder = "", color = color, icon = icon, rules = base.rules ?: SmartRules(),
    ) else base.copy(
        name = name.trim(), type = SpaceType.FOLDER, kind = kind, folder = folder.trim(), layout = layout, naming = naming, skipExisting = skip, scanFolder = scan,
        ignore = ignore.split(',').map { it.trim() }.filter { it.isNotEmpty() }, color = color, icon = icon, rules = null,
    )

    StudioSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag(UiTags.SPACE_EDITOR)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(if (creating) "New space" else "Edit space", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).semantics { heading() })
            Column(Modifier.weight(1f, fill = false).heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(40); error = null }, label = { Text("Name") }, singleLine = true, isError = error != null, modifier = Modifier.fillMaxWidth().testTag(UiTags.SPACE_NAME))

                Text("Type", style = MaterialTheme.typography.labelLarge)
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypeCard("Folder", "Has its own folder. Downloads and moved files go here.", !smart, creating || !smart, UiTags.SPACE_TYPE_FOLDER) { type = SpaceType.FOLDER }
                    TypeCard("Smart", "Gathers items from all spaces by tags, collections or source. No files move.", smart, creating || smart, UiTags.SPACE_TYPE_SMART) {
                        type = SpaceType.SMART
                        if (!iconTouched && base.icon == null) icon = SpaceIcon.STAR
                    }
                }

                Text("Color", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                Text("Eight colors, each tuned for light and dark themes so text stays readable.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    SpaceColor.entries.forEach { option ->
                        ColorSwatch(option, option == color) { color = option }
                    }
                }

                Text("Icon", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    SpaceIcon.entries.forEach { option ->
                        IconSwatch(option, option == icon, tone.accent, tone.onAccent) { icon = option; iconTouched = true }
                    }
                }

                if (!smart) {
                    Text("What goes here", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SpaceKind.entries.forEachIndexed { index, option ->
                            SegmentedButton(selected = kind == option, onClick = { kind = option; if (option != SpaceKind.MUSIC && layout == FolderLayout.ARTIST_ALBUM) layout = FolderLayout.COLLECTION; if (!iconTouched && base.icon == null) icon = SpaceLogic.defaultIcon(base.copy(kind = option)) },
                                shape = SegmentedButtonDefaults.itemShape(index, SpaceKind.entries.size), modifier = Modifier.heightIn(min = 48.dp).testTag("kind-${option.wire}")) { Text(option.label()) }
                        }
                    }
                    Text(kind.blurb(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(folder, { folder = it; error = null }, label = { Text("Folder") }, singleLine = true, isError = error != null, modifier = Modifier.fillMaxWidth().testTag(UiTags.SPACE_FOLDER))
                    Text(
                        "Saved as Music/${folder.trim().ifEmpty { "<folder>" }} for songs, Movies/${folder.trim().ifEmpty { "<folder>" }} for videos and Download/${folder.trim().ifEmpty { "<folder>" }} for the rest.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (folderChanged) SwitchRow("Move existing files", moveExisting, { moveExisting = it }, subtitle = "Otherwise files in the old folder leave this space but stay on your phone.", modifier = Modifier.padding(horizontal = 0.dp))
                } else if (!creating) {
                    OutlinedButton(onClick = { onDismiss(); vm.openRules(draft(), false) }, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.SPACE_EDIT_RULES)) { Text("Edit rules") }
                }
                error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("space-error")) }

                if (!smart) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { more = !more }.testTag(UiTags.SPACE_MORE), verticalAlignment = Alignment.CenterVertically) {
                        Text("More options", style = MaterialTheme.typography.titleSmall, color = app.keepfin.ui.theme.Studio.colors.accentText, modifier = Modifier.weight(1f))
                        Icon(if (more) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = if (more) "Hide more options" else "Show more options", tint = MaterialTheme.colorScheme.primary)
                    }
                    if (more) {
                        Text("Subfolders", style = MaterialTheme.typography.labelLarge)
                        Column(Modifier.selectableGroup()) {
                            layouts.forEach { option ->
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(layout == option, role = Role.RadioButton) { layout = option }, verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = layout == option, onClick = null, modifier = Modifier.padding(end = 12.dp))
                                    Text(option.label(), style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                        Text("File names", style = MaterialTheme.typography.labelLarge)
                        Column(Modifier.selectableGroup()) {
                            SpaceNaming.entries.filter { it != SpaceNaming.ARTIST_TITLE || kind != SpaceKind.VIDEO }.forEach { option ->
                                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(naming == option, role = Role.RadioButton) { naming = option }, verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = naming == option, onClick = null, modifier = Modifier.padding(end = 12.dp))
                                    Text(option.label(), style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                        SwitchRow("Skip songs already in this space", skip, { skip = it }, subtitle = "Before downloading, Keepfin checks for the same recording. Nothing is skipped silently.")
                        SwitchRow("Show other files in this folder", scan, { scan = it }, subtitle = "Also list files that were already in this folder. Good for an existing, well-kept music folder.")
                        OutlinedTextField(ignore, { ignore = it }, label = { Text("Ignored folders") }, supportingText = { Text("Sub-folder names to leave out, separated by commas. Folders with a .nomedia file are always left out.") }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!creating && !base.kept) TextButton(onClick = { confirmRemove = true }, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.SPACE_REMOVE)) {
                    Text("Remove space", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                val next = smart && creating
                Button(
                    enabled = name.isNotBlank() && (smart || folder.isNotBlank()) && !saving,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(if (next) UiTags.SPACE_NEXT else UiTags.SPACE_SAVE),
                    colors = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction),
                    onClick = {
                        if (next) {
                            val taken = existing.any { it.id != base.id && it.name.equals(name.trim(), ignoreCase = true) }
                            if (taken) { error = "Another space already has this name."; return@Button }
                            onDismiss(); vm.openRules(draft(), true)
                            return@Button
                        }
                        saving = true
                        val saved = draft()
                        vm.spaces.saveSpace(saved, moveExisting && folderChanged) { problem ->
                            saving = false
                            if (problem == null) { vm.say(if (creating) "Added the space “${saved.name}”" else "Saved “${saved.name}”"); onDismiss() } else error = problem
                        }
                    },
                ) { Text(if (next) "Next" else if (creating) "Add space" else "Save") }
            }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false }, title = { Text("Remove “${base.name}”?") },
            text = { Text(if (base.smart) "The smart space is removed. Nothing in your library changes." else "The space leaves your library, but its files stay on your phone and in the same folder. Add the folder as a space again any time to bring them back.") },
            confirmButton = {
                Button(onClick = { confirmRemove = false; vm.spaces.removeSpace(base.id, base.name); onDismiss() }, modifier = Modifier.heightIn(min = 48.dp).testTag("space-remove-confirm"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError)) { Text("Remove space") }
            },
            dismissButton = { OutlinedButton(onClick = { confirmRemove = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep") } },
        )
    }
}

@Composable
private fun TypeCard(title: String, body: String, selected: Boolean, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp), color = if (selected) colors.primaryContainer else Color0,
        border = if (selected) BorderStroke(1.5.dp, colors.primary) else BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).testTag(tag),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) colors.onSurface else colors.onSurface.copy(alpha = .38f))
                Text(body, fontSize = 12.sp, lineHeight = 17.sp, color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
            }
        }
    }
}

private val Color0 = androidx.compose.ui.graphics.Color.Transparent

@Composable
private fun ColorSwatch(color: SpaceColor, selected: Boolean, onClick: () -> Unit) {
    val tone = spaceTone(color)
    val label = SpaceTones.label(color)
    Box(
        Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "$label color" }.testTag(UiTags.colorSwatch(color.wire)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(tone.accent)
                .then(if (selected) Modifier.padding(0.dp) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = tone.onAccent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun IconSwatch(icon: SpaceIcon, selected: Boolean, accent: androidx.compose.ui.graphics.Color, onAccent: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = "${SpaceTones.label(icon)} icon" }.testTag(UiTags.iconSwatch(icon.wire)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (selected) accent else colors.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) { Icon(icon.vector(), contentDescription = null, tint = if (selected) onAccent else colors.onSurfaceVariant, modifier = Modifier.size(22.dp)) }
    }
}
