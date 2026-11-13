package app.keepfin.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.theme.Studio

/** The words shown after the person refused the audio permission. */
const val DEVICE_MUSIC_DENIED = "Keepfin can't see your music without that permission. You can allow it in Android settings."

/**
 * Asks for the audio permission when it is missing, then turns "Music on this phone" on. [onDenied] runs when the person refuses
 * (the feature stays as it was). Returns the function to call from a button.
 */
@Composable
fun rememberShowDeviceMusic(vm: StudioViewModel, onDenied: () -> Unit): () -> Unit {
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) vm.libraryActions.setDeviceMusic(true) else onDenied()
    }
    return {
        val needed = vm.lib.deviceMusicPermissions()
        if (needed.isEmpty()) vm.libraryActions.setDeviceMusic(true) else ask.launch(needed.toTypedArray())
    }
}

/** Library home's card for "Music on this phone": shown until the person turns it on. */
@Composable
fun DeviceMusicCard(vm: StudioViewModel, modifier: Modifier = Modifier) {
    var denied by remember { mutableStateOf(false) }
    val show = rememberShowDeviceMusic(vm) { denied = true }
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("device-music-card"),
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Text("Music on this phone", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                "See the music already on this phone, where it is. Nothing is copied.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (denied) Text(DEVICE_MUSIC_DENIED, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("device-music-denied"))
            Spacer(Modifier.size(4.dp))
            Button(
                onClick = show, modifier = Modifier.heightIn(min = 48.dp).testTag("device-music-show"),
                colors = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction),
            ) { Text("Show my music") }
        }
    }
}
