package app.keepfin.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import app.keepfin.net.PeerPlayback

/** "From <device>" while the current item streams from a paired device; null for files on this phone. */
@Composable
fun fromDeviceLabel(entry: QueueEntry?): String? {
    if (entry?.remote != true) return null
    val name by PeerPlayback.streamingFrom.collectAsState()
    return "From ${name ?: "another device"}"
}
