package app.keepfin.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.DeviceMusicState
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.components.NavRow
import app.keepfin.ui.components.RowSeparator
import app.keepfin.ui.components.SwitchRow
import app.keepfin.ui.library.rememberShowDeviceMusic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings › Library rows for "Music on this phone": the switch, the folders left out and the hidden songs. */
@Composable
fun DeviceMusicRows(vm: StudioViewModel) {
    val state by vm.lib.deviceMusic.collectAsStateWithLifecycle()
    var denied by rememberSaveable { mutableStateOf(false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    var hidden by remember { mutableIntStateOf(0) }
    val show = rememberShowDeviceMusic(vm) { denied = true }
    LaunchedEffect(state) { hidden = withContext(Dispatchers.IO) { vm.lib.hiddenDeviceCount() } }
    val on = state !is DeviceMusicState.Off
    RowSeparator()
    SwitchRow(
        "Music on this phone", on, { value -> if (value) show() else vm.libraryActions.setDeviceMusic(false) },
        subtitle = if (denied && !on) app.keepfin.ui.library.DEVICE_MUSIC_DENIED else "Lists the music already on this phone, where it is. Nothing is copied or uploaded.",
        tag = "setting-device-music",
    )
    if (on) {
        NavRow("Folders left out", subtitle = "Music in these folders stays out of Keepfin.", icon = Icons.Outlined.FolderOff, modifier = Modifier.testTag("device-music-left-out")) { picking = true }
        if (hidden > 0) NavRow(
            "Show hidden songs again", subtitle = if (hidden == 1) "1 song you removed from Keepfin" else "$hidden songs you removed from Keepfin",
            icon = Icons.Outlined.VisibilityOff, modifier = Modifier.testTag("device-music-unhide"),
        ) { vm.libraryActions.unhideDevice() }
    }
    if (picking) LeftOutDialog(vm) { picking = false }
}

@Composable
private fun LeftOutDialog(vm: StudioViewModel, onDismiss: () -> Unit) {
    var folders by remember { mutableStateOf<List<String>?>(null) }
    var left by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { folders = vm.lib.deviceFolders(); left = vm.lib.deviceLeftOut().toSet() }
    }
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Folders left out") },
        text = {
            val list = folders
            if (list == null) Text("Looking…")
            else if (list.isEmpty()) Text("No music folders found yet.")
            else LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(list, key = { it }) { path ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { left = if (path in left) left - path else left + path }.padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = path in left, onCheckedChange = { left = if (it) left + path else left - path })
                        Text(path, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.libraryActions.setDeviceLeftOut(left.toList()); onDismiss() }, modifier = Modifier.heightIn(min = 48.dp).testTag("device-music-left-out-save")) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}
