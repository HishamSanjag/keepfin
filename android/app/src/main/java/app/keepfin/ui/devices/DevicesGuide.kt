package app.keepfin.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.keepfin.net.DevicesState

/**
 * The one guide for the Devices page (opened by the "?" at the top): how devices find each other, pair and stay in step, and what to
 * do away from home with Tailscale. [at] is "tailscale" to open scrolled to the Tailscale part, anything else for the top.
 */
@Composable
fun DevicesGuide(at: String, state: DevicesState, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = true)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp, modifier = Modifier.testTag(DevicesTags.GUIDE)) {
            GuideBody(at, state, onClose)
        }
    }
}

/** The guide's content, apart from its dialog (tests draw it on its own). */
@Composable
internal fun GuideBody(at: String, state: DevicesState, onClose: () -> Unit) {
    val scroll = rememberScrollState()
    var awayTop by remember { mutableIntStateOf(0) }
    LaunchedEffect(at, awayTop) { if (at == "tailscale" && awayTop > 0) scroll.scrollTo(awayTop) }
    val uri = LocalUriHandler.current
    Column(Modifier.padding(top = 24.dp, start = 24.dp, end = 24.dp, bottom = 8.dp)) {
        Text("How devices work", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
        Column(Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HowStep(1, "Find each other", "Open Keepfin on both devices. On the same Wi-Fi they show up under Available devices within a few seconds. Away from home, Tailscale on both does the same.")
            HowStep(2, "Pair once", "Press Connect on one device. The other one shows \"wants to connect\" with a code. Check that both show the same code, then tap Codes match on both. Only paired devices are let in, and nothing is shared before that.")
            HowStep(3, "Stay in step", "While both are connected, a change on one shows on the other within seconds. A device that was off catches up when it connects again.")
            HowFact("Syncs by itself", "Your library: what you have, titles and tags, playlists and favourites.")
            HowFact("Stays where it is", "The files. Each one stays on the device that saved it; the other lists it as \"On <device>\" and plays it over the network when you allow \"Play files from this device\" on the device that has them.")
            HowFact("Files on both", "Add a copy: Settings → Storage → Copies → A paired device.")
            HowFact("Where it goes", "Straight from one device to the other, on your network or through Tailscale. No account and no server in between.")
            Column(
                Modifier.padding(top = 8.dp).onGloballyPositioned { awayTop = it.positionInParent().y.toInt() }.testTag(DevicesTags.AWAY),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Away from home", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
                val on = state.network.tailscaleAddress
                Text(
                    if (on != null) "Tailscale is on: $on. Paired devices can reconnect from anywhere." else "Tailscale is not running on this phone.",
                    fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                )
                Column(Modifier.testTag(DevicesTags.AWAY_STEPS), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("1. Install Tailscale on both devices (tailscale.com/download).", fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("2. Sign in with the same account on both.", fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("3. Keep it on. Paired devices then reconnect anywhere.", fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { runCatching { uri.openUri("https://tailscale.com/download") } }, modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.AWAY_GET)) { Text("Get Tailscale") }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.GUIDE_CLOSE)) { Text("Close") }
        }
    }
}

@Composable
private fun HowStep(number: Int, title: String, body: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text("$number", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(body, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HowFact(title: String, body: String) {
    Column {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text(body, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
