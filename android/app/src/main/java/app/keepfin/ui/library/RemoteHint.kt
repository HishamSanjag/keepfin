package app.keepfin.ui.library

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import app.keepfin.where.WhereFilesSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where an item that is not on this phone would play from now: the paired device the library says holds it. Follows the devices as they
 * come and go; null until the placements are read.
 */
@Composable
fun rememberRemoteChoice(itemId: String, where: WhereFilesSource?): PlayChoice? {
    val service = remember { NetRuntime.peek() }
    val state = service?.state?.collectAsState()?.value
    val holders by produceState<Set<String>?>(null, itemId, where) { value = withContext(Dispatchers.IO) { PeerPlayback.holdersOf(where, itemId) } }
    val known = holders
    return remember(state, known) { if (known != null || PeerPlayback.forced != null) PeerPlayback.choiceFor(known) else null }
}

/** The small "On (device)" line, or "Not on this phone. No paired device is known to have it.", under an item whose file is not on this phone. */
@Composable
fun RemoteHint(itemId: String, where: WhereFilesSource?, modifier: Modifier = Modifier) {
    val text = rememberRemoteChoice(itemId, where)?.let(PlaySource::hint) ?: return
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(top = 1.dp).testTag(UiTags.libRemoteHint(itemId)),
    )
}
