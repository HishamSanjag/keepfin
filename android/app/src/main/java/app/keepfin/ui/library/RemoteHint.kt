package app.keepfin.ui.library

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.keepfin.net.NetRuntime
import app.keepfin.net.PeerPlayback
import app.keepfin.net.PlayChoice
import app.keepfin.net.PlaySource
import app.keepfin.ui.UiTags

/** Where an item that is not on this phone would play from now; follows the devices as they come and go. */
@Composable
fun rememberRemoteChoice(): PlayChoice {
    val service = remember { NetRuntime.peek() }
    val state = service?.state?.collectAsState()?.value
    return remember(state) { PeerPlayback.choiceFor() }
}

/** The small "On <device>" line (or "Not on this phone") under an item whose file is not on this phone. */
@Composable
fun RemoteHint(itemId: String, modifier: Modifier = Modifier) {
    val text = PlaySource.hint(rememberRemoteChoice()) ?: return
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(top = 1.dp).testTag(UiTags.libRemoteHint(itemId)),
    )
}
