package app.keepfin.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.EngineSettings
import app.keepfin.Space
import app.keepfin.ui.library.icon
import app.keepfin.SpaceKind
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.NavRow
import app.keepfin.ui.components.RowSeparator
import app.keepfin.ui.components.SettingsGroup
import app.keepfin.ui.components.SupportingText
import app.keepfin.ui.components.SwitchRow
import app.keepfin.ui.library.SpaceEditorSheet

/** Settings › Library: one switch first, then Open media with, Where downloads go, Spaces and the extras. One helper sentence per row. */
@Composable
fun LibraryGroup(vm: StudioViewModel, settings: EngineSettings) {
    val library = settings.library
    val spaceCounts by app.keepfin.ui.library.rememberBrowsed(vm.lib.browse, Unit, emptyMap<String, app.keepfin.Counts>()) { it.spaceCounts() }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var explain by rememberSaveable { mutableStateOf(false) }
    val adopt = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) vm.spaces.adoptEarlierFiles() else vm.say("Without access to your audio and video, Keepfin can't find earlier files.")
    }
    SettingsGroup("", tag = UiTags.setting("library")) {
        if (!library.enabled) {
            SupportingText("The Library tab shows your download history. Choose “Keepfin Complete” in Keepfin mode above to see your spaces, the player and Save to again.")
            return@SettingsGroup
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("Open media with", style = MaterialTheme.typography.bodyLarge)
            Text("Another app opens files in whatever app you pick on your phone. “Open in another app” stays in every menu.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(true to "Keepfin player", false to "Another app").forEachIndexed { index, (builtIn, label) ->
                    SegmentedButton(
                        selected = library.builtInPlayer == builtIn, onClick = { vm.updateSettings { it.copy(library = it.library.copy(builtInPlayer = builtIn)) } },
                        shape = SegmentedButtonDefaults.itemShape(index, 2), modifier = Modifier.testTag(UiTags.setting("player-${if (builtIn) "keepfin" else "other"}")),
                    ) { Text(label, maxLines = 1) }
                }
            }
            if (library.builtInPlayer) Text("Recommended: background playback, lock screen controls, queue, lyrics and resume.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
        RowSeparator()
        Column(Modifier.padding(top = 8.dp)) {
            Text("Where downloads go", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp))
            Text("Save to on any download overrides this once. Music services and YouTube Music links go to the music space; everything else goes to the other.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            SpaceDropdown("Music links go to", library.musicRoute, library.folderSpaces, "route-music") { id -> vm.updateSettings { it.copy(library = it.library.copy(musicRoute = id)) } }
            SpaceDropdown("Everything else goes to", library.otherRoute, library.folderSpaces, "route-other") { id -> vm.updateSettings { it.copy(library = it.library.copy(otherRoute = id)) } }
        }
        RowSeparator()
        Text("Spaces", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp))
        Text("A space is a place for one kind of media with its own folder. Tap one to change its name, kind or folder.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
        library.spaces.forEach { space ->
            val count = spaceCounts[space.id]?.total ?: 0
            NavRow(
                space.name, subtitle = if (space.smart) "Smart space · gathers items by rules" else "${space.kind.name.lowercase().replaceFirstChar { it.uppercase() }} · ${space.folder} · $count items",
                icon = space.icon(),
                trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) }, modifier = Modifier.testTag(UiTags.spaceRow(space.id)),
            ) { editing = space.id }
        }
        NavRow("Add a space", subtitle = "Watch later, Podcasts, Lectures… anything with its own folder.", icon = Icons.Outlined.Add, modifier = Modifier.testTag("space-add")) { editing = "" }
        RowSeparator()
        SwitchRow(
            "Volume normalisation", library.normalize, { value -> vm.updateSettings { it.copy(library = it.library.copy(normalize = value)) } },
            subtitle = "Plays loud and quiet songs at a similar level. It only turns loud songs down; it never boosts.", tag = UiTags.setting("normalize"),
        )
        RowSeparator()
        SwitchRow(
            "Import tags from sources", library.importTags, { value -> vm.updateSettings { it.copy(library = it.library.copy(importTags = value)) } },
            subtitle = "Take genre, tags and categories from a download's source for the tag filter. Items you already have are not changed.", tag = UiTags.setting("import-tags"),
        )
        RowSeparator()
        SwitchRow(
            "Show new releases on Home", settings.newReleasesOnHome, { value -> vm.updateSettings { it.copy(newReleasesOnHome = value) } },
            subtitle = "A small card on Home tells you when artists you follow have new releases. Off removes it for good; you can still follow artists from Library, Artists.",
            tag = UiTags.SETTING_HOME_RELEASES,
        )
        RowSeparator()
        NavRow(
            "Find my earlier Keepfin files", subtitle = "After reinstalling, look for files an earlier install saved. Needs access to your audio and video, asked only here.",
            icon = Icons.Outlined.Search, modifier = Modifier.testTag("find-earlier"),
        ) { explain = true }
    }
    editing?.let { id ->
        val space = library.spaces.firstOrNull { it.id == id }
        if (id.isEmpty() || space != null) SpaceEditorSheet(vm, space, onDismiss = { editing = null })
    }
    if (explain) {
        AlertDialog(
            onDismissRequest = { explain = false }, title = { Text("Find earlier files?") },
            text = { Text("Android hides files saved by an earlier install of Keepfin. To find them again, Keepfin needs to read your audio and video. It only looks in your space folders and never uploads anything. You can turn the access off in Android settings afterwards.") },
            confirmButton = {
                Button(onClick = {
                    explain = false
                    val needed = vm.lib.earlierFilesPermissions()
                    if (needed.isEmpty()) vm.spaces.adoptEarlierFiles() else adopt.launch(needed.toTypedArray())
                }, modifier = Modifier.testTag("find-earlier-continue")) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { explain = false }) { Text("Not now") } },
        )
    }
}

@Composable
private fun SpaceDropdown(label: String, current: String, spaces: List<Space>, tag: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        NavRow(
            label, subtitle = spaces.firstOrNull { it.id == current }?.name ?: "Not set", modifier = Modifier.testTag(UiTags.setting(tag)),
            trailing = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
        ) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            spaces.forEach { space -> DropdownMenuItem(text = { Text(space.name) }, onClick = { open = false; onPick(space.id) }, modifier = Modifier.testTag(UiTags.setting("$tag-${space.id}"))) }
        }
    }
}
