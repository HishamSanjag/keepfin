package app.keepfin.ui.devices

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.net.DevicesState
import app.keepfin.net.IncomingJobRow
import app.keepfin.net.NetRuntime
import app.keepfin.net.RemoteJobRowUi
import app.keepfin.net.isTerminalJob
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The state and actions behind "Download on…" jobs. Production reads the running devices service; tests provide their own. */
class DeviceJobsSource(val state: kotlinx.coroutines.flow.StateFlow<DevicesState>, val controller: DevicesController)

val LocalDeviceJobs = compositionLocalOf<DeviceJobsSource?> { null }

/**
 * "Asked of this phone" and "Sent to other devices" on the Downloads screen: the downloads other devices asked this phone for (with the
 * large ones that wait for a yes) and the ones this phone sent to them. Nothing shows without a running devices service or without any.
 */
@Composable
fun DeviceJobsHost(vm: StudioViewModel) {
    val provided = LocalDeviceJobs.current
    val context = LocalContext.current
    val service = if (provided == null) NetRuntime.peek() else null
    val real = remember(service) { service?.let { svc -> DeviceJobsSource(svc.state, ServiceDevicesController(context.applicationContext as Application, vm.engine)) } }
    val source = provided ?: real ?: return
    val state by source.state.collectAsStateWithLifecycle()
    DeviceJobsSection(state, source.controller)
}

@Composable
fun DeviceJobsSection(state: DevicesState, controller: DevicesController) {
    if (state.remoteJobs.isEmpty() && state.incomingJobs.isEmpty()) return
    val scope = rememberCoroutineScope()
    fun run(block: () -> String?) { scope.launch { withContext(Dispatchers.IO) { runCatching(block) } } }
    Column(Modifier.fillMaxWidth().testTag(UiTags.DEVICE_JOBS)) {
        if (state.incomingJobs.isNotEmpty()) {
            Heading("Asked of this phone")
            for (j in state.incomingJobs) IncomingRow(j, controller, ::run)
        }
        if (state.remoteJobs.isNotEmpty()) {
            Heading("Sent to other devices")
            for (j in state.remoteJobs) RemoteRow(j, controller, ::run)
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

private fun stateWords(state: String): String = when (state) {
    "sending" -> "Waiting for the device"
    "refused" -> "Refused"
    "queued" -> "Waiting its turn"
    "preparing" -> "Getting ready"
    "downloading" -> "Downloading"
    "processing" -> "Finishing"
    "completed" -> "Done"
    "failed" -> "Failed"
    "cancelled" -> "Stopped"
    "paused" -> "Paused"
    "consent" -> "Waiting for you"
    else -> state
}

@Composable
private fun IncomingRow(j: IncomingJobRow, controller: DevicesController, run: (() -> String?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(j.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2)
        Text("From ${j.deviceName} · ${stateWords(j.state)}${if (j.state == "downloading") " · ${j.percent.toInt()}%" else ""}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        j.error?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
        val consent = j.consent
        if (consent != null) {
            val size = consent.bytes?.let { app.keepfin.ui.Format.bytes(it) }
            Text(
                if (consent.reason == "metered") "You are on mobile data${size?.let { ", and this is about $it" } ?: ""}. Allow it?" else "This is large (about ${size ?: "an unknown size"}). Allow it?",
                fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { run { controller.allowJob(j.jobId); null } }, modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.ALLOW_JOB)) { Text("Allow") }
                OutlinedButton(onClick = { run { controller.declineJob(j.jobId); null } }, modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.DECLINE_JOB)) { Text("Decline") }
            }
        } else if (j.state == "downloading") LinearProgressIndicator(progress = { (j.percent / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

@Composable
private fun RemoteRow(j: RemoteJobRowUi, controller: DevicesController, run: (() -> String?) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(j.title ?: j.url, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2)
        Text("On ${j.deviceName} · ${stateWords(j.state)}${if (j.state == "downloading") " · ${j.percent.toInt()}%" else ""}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        j.error?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
        if (j.state == "downloading") LinearProgressIndicator(progress = { (j.percent / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        if (isTerminalJob(j.state)) TextButton(onClick = { run { controller.dismissRemoteJob(j.requestId); null } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Dismiss") }
    }
}
