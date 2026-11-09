package app.keepfin.ui.devices

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.DownloadOptions
import app.keepfin.FormatRequest
import app.keepfin.net.NetRuntime
import org.json.JSONObject

/** A paired device that takes downloads from this phone ("Download on..."). */
class DownloadTarget(val deviceId: String, val name: String)

object DownloadOnTags {
    const val BUTTON = "download-on"
    fun device(id: String) = "download-on-$id"
}

/** The paired devices that are connected and let this phone send them downloads; empty while devices are off. */
@Composable
fun rememberDownloadTargets(): List<DownloadTarget> {
    val service = NetRuntime.peek() ?: return emptyList()
    val state by service.state.collectAsStateWithLifecycle()
    return state.paired.filter { it.online && it.jobsAllowed }.map { DownloadTarget(it.deviceId, it.name) }
}

/** "Download on…" beside Save to: one entry per device. */
@Composable
fun DownloadOnRow(targets: List<DownloadTarget>, onPick: (DownloadTarget) -> Unit, modifier: Modifier = Modifier) {
    if (targets.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Row(modifier) {
        TextButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp).testTag(DownloadOnTags.BUTTON)) { Text("Download on…") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (t in targets) DropdownMenuItem(text = { Text(t.name) }, onClick = { open = false; onPick(t) }, modifier = Modifier.heightIn(min = 48.dp).testTag(DownloadOnTags.device(t.deviceId)))
        }
    }
}

/** What the other device needs to know about the format: structured intent only (the exact format ids belong to this phone's inspection). */
fun formatJson(request: FormatRequest): JSONObject = when (request) {
    is FormatRequest.Gallery -> JSONObject().put("type", "gallery").apply { request.items?.let { put("items", org.json.JSONArray(it)) } }
    is FormatRequest.Audio -> JSONObject().put("type", "audio").put("target", request.target).put("quality", request.quality)
    is FormatRequest.Video -> JSONObject().put("type", "video").put("codec", request.codec).put("container", request.container)
        .apply { request.maxHeight?.let { put("maxHeight", it) }; request.fps?.let { put("fps", it) } }
}

fun optionsJson(o: DownloadOptions): JSONObject = JSONObject()
    .put("subtitles", JSONObject().put("enabled", o.subtitles.enabled).put("languages", o.subtitles.languages).put("auto", o.subtitles.auto).put("embed", o.subtitles.embed))
    .put("embedThumbnail", o.embedThumbnail).put("embedMetadata", o.embedMetadata).put("embedChapters", o.embedChapters).put("sponsorBlock", o.sponsorBlock)

/** One link to send to another device, with the format this phone chose for it. */
class SendJob(val url: String, val request: FormatRequest, val musicVideo: Boolean = false)

/** Sends [jobs] to [target] (kept on disk and resent until the device answers). Blocking: call off the main thread. */
fun sendDownloads(target: DownloadTarget, jobs: List<SendJob>, options: DownloadOptions) {
    val service = NetRuntime.peek() ?: throw IllegalStateException("Devices are off.")
    for (job in jobs) service.sendJob(target.deviceId, job.url, formatJson(job.request), optionsJson(options), musicVideo = job.musicVideo)
}
