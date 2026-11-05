package app.keepfin.ui.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.keepfin.net.PairingNotifier
import app.keepfin.net.PairingUi

private val ButtonPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)

/**
 * The steps of one pairing. Both people tap Codes match (or "They don't match") for the numbers method. Whoever taps first sees the code
 * with "Now tap Codes match on <name> too.", "Waiting for <name>…" and only Cancel. A QR or typed code has no numbers and no tap.
 */
@Composable
fun PairingContent(p: PairingUi, decide: (String, Boolean) -> Unit, dismiss: () -> Unit, cancel: () -> Unit, showDismiss: Boolean = true) {
    val answering = p.role == "answering"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (p.stage) {
            "connecting" -> {
                Text("Connecting to ${p.peerName}…", fontSize = 15.sp, modifier = Modifier.testTag(DevicesTags.PAIRING_CONNECTING))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            "comparing" -> {
                var tapped by remember(p.id) { mutableStateOf(false) }
                SasText(p.sas ?: "")
                if (tapped) ConfirmedWaiting(p, decide) else {
                    Text(
                        "Check that both devices show this code, then tap Codes match on both.",
                        fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("Fingerprint of ${p.peerName}: ${p.peerFingerprint}", fontSize = 12.sp, style = TabularDigits, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // stacked, full width: the primary on top, equal 48 dp targets
                    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { tapped = true; decide(p.id, true) }, contentPadding = ButtonPadding, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(DevicesTags.SAS_MATCH)) { Text("Codes match") }
                        OutlinedButton(onClick = { decide(p.id, false) }, contentPadding = ButtonPadding, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(DevicesTags.SAS_DIFFER)) { Text("They don't match") }
                    }
                }
            }
            "approving" -> {
                Text("Fingerprint: ${p.peerFingerprint}", fontSize = 12.sp, style = TabularDigits, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { decide(p.id, true) }, contentPadding = ButtonPadding, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(DevicesTags.APPROVE)) { Text("Connect") }
                    OutlinedButton(onClick = { decide(p.id, false) }, contentPadding = ButtonPadding, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(DevicesTags.DECLINE)) { Text("Decline") }
                }
            }
            "waitingForPeer", "waiting" -> {
                if (p.sas != null) {
                    SasText(p.sas)
                    ConfirmedWaiting(p, decide)
                } else {
                    Text("Waiting for ${p.peerName} to accept…", fontSize = 15.sp, modifier = Modifier.testTag(DevicesTags.PAIRING_WAITING))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (p.stage == "waitingForPeer") TextButton(onClick = cancel, modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.PAIRING_CANCEL)) { Text("Cancel") }
                }
            }
            "done" -> {
                Text(if (p.autoApproved) "${p.peerName} connected" else "Connected to ${p.peerName}", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.testTag(DevicesTags.PAIRING_DONE))
                if (showDismiss) TextButton(onClick = { dismiss() }, modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.PAIRING_RESULT_OK)) { Text("OK") }
            }
            else -> {
                Text(p.error ?: "Connecting did not finish.", fontSize = 14.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(DevicesTags.PAIRING_ERROR))
                if (showDismiss) TextButton(onClick = { dismiss() }, modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.PAIRING_RESULT_OK)) { Text("OK") }
            }
        }
    }
}

@Composable
private fun SasText(sas: String) {
    Text(
        groupSas(sas), fontSize = 36.sp, style = TabularDigits, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.testTag(DevicesTags.SAS).semantics { contentDescription = "Code to compare: ${sas.toCharArray().joinToString(" ")}" },
    )
}

/** This person already tapped Codes match; the other device still has to. Only Cancel (= decline) is left. */
@Composable
private fun ConfirmedWaiting(p: PairingUi, decide: (String, Boolean) -> Unit) {
    Text("You confirmed the code on this phone.", fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    Text("On ${p.peerName}, tap Codes match to finish.", fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("Waiting for ${p.peerName}…", fontSize = 15.sp, modifier = Modifier.testTag(DevicesTags.PAIRING_WAITING))
    LinearProgressIndicator(Modifier.fillMaxWidth())
    TextButton(onClick = { decide(p.id, false) }, modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.SAS_DIFFER)) { Text("Cancel") }
}

/** A dialog that scrolls (large text, small screens) and, while a step cannot be left, ignores taps outside and the back button. */
@Composable
internal fun PairDialogShell(title: String, onDismiss: (() -> Unit)?, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = { onDismiss?.invoke() },
        properties = DialogProperties(dismissOnBackPress = onDismiss != null, dismissOnClickOutside = onDismiss != null),
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp, modifier = Modifier.testTag(DevicesTags.PAIR_DIALOG)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
                content()
            }
        }
    }
}

/** This phone asked a found device: connecting, the code to check, waiting for it to accept, and the result. */
@Composable
fun AskingPairingDialog(p: PairingUi, decide: (String, Boolean) -> Unit, dismiss: () -> Unit, onSetUp: ((PairingUi) -> Unit)? = null) {
    val finished = p.stage == "done" || p.stage == "failed"
    // a finished pairing offers the setup (what stays up to date) when the copies engine is there
    if (p.stage == "done" && onSetUp != null) {
        PairDialogShell("${p.peerName} is connected", dismiss) {
            SetupPromptContent(p.peerName, onSetUp = { dismiss(); onSetUp(p) }, onLater = dismiss)
        }
        return
    }
    val title = when (p.stage) {
        "done" -> "Connected"
        "failed" -> "Could not connect"
        else -> "Connect to ${p.peerName}"
    }
    // a step that waits for the other device can be left; the code step is answered with its buttons
    val leave = if (finished || p.stage == "waitingForPeer") dismiss else null
    PairDialogShell(title, leave) { PairingContent(p, decide, dismiss = dismiss, cancel = dismiss) }
}

/**
 * Another device is asking to connect to this phone: "<name> wants to connect" with the code over whatever screen is open, so nothing
 * depends on Settings -> Devices being visible. One dialog; it goes when the request is decided, fails or expires, after a short
 * result that the person closes with OK. A device that connected by a scanned code is not asked (see [IncomingPairingLayer]).
 */
@Composable
fun IncomingPairingDialog(pairing: PairingUi?, onSetUp: ((PairingUi) -> Unit)? = null, decide: (String, Boolean) -> Unit) {
    var shownId by remember { mutableStateOf<String?>(null) }
    var closedId by remember { mutableStateOf<String?>(null) }
    val p = pairing?.takeIf { it.role == "answering" } ?: return
    val waitingForPerson = PairingNotifier.needsPerson(p)
    if (waitingForPerson) shownId = p.id
    // a result is shown only for a request this dialog showed, and only until it is closed
    val show = waitingForPerson || (p.id == shownId && p.id != closedId && (p.stage == "done" || p.stage == "failed"))
    if (!show) return
    // the result of a request this person accepted offers the same setup as the asking side: what stays up to date
    if (!waitingForPerson && p.stage == "done" && onSetUp != null) {
        PairDialogShell("${p.peerName} is connected", { closedId = p.id }) {
            Column(Modifier.testTag(DevicesTags.INCOMING_DIALOG)) {
                SetupPromptContent(p.peerName, onSetUp = { closedId = p.id; onSetUp(p) }, onLater = { closedId = p.id })
            }
        }
        return
    }
    val title = when {
        waitingForPerson -> "${p.peerName} wants to connect"
        p.stage == "failed" -> "Could not connect"
        else -> "Connected"
    }
    PairDialogShell(title, if (waitingForPerson) null else ({ closedId = p.id })) {
        Column(Modifier.testTag(DevicesTags.INCOMING_DIALOG)) {
            PairingContent(p, decide, dismiss = { closedId = p.id }, cancel = { closedId = p.id })
        }
    }
}

/**
 * The app-level host for requests that come in: the dialog above, and for a device that connected by a scanned code (nobody is asked
 * here) a "<name> connected" notice with Undo from [say], then the pairing view closes through [finish]. [undo] removes that device.
 */
@Composable
fun IncomingPairingLayer(
    pairing: PairingUi?,
    decide: (String, Boolean) -> Unit,
    say: (text: String, actionLabel: String, action: () -> Unit) -> Unit,
    undo: (deviceId: String) -> Unit,
    finish: () -> Unit,
    /** The copies engine for the setup after pairing; the app's own by default, null where this build has none (then only "connected" is said). */
    copies: DeviceCopies? = rememberAppCopies(),
) {
    // the device whose setup (what stays up to date) is open, from a request this phone accepted
    var setup by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    IncomingPairingDialog(pairing, decide = decide, onSetUp = if (copies != null) ({ p -> if (p.peerDeviceId.isNotEmpty()) setup = p.peerDeviceId to p.peerName }) else null)
    val open = setup
    if (copies != null && open != null) DeviceSetupSheet(open.first, open.second, copies) { setup = null }
    val connectedByScan = pairing?.takeIf { it.role == "answering" && it.autoApproved && it.stage == "done" }
    LaunchedEffect(connectedByScan?.id) {
        val p = connectedByScan ?: return@LaunchedEffect
        say("${p.peerName} connected", "Undo") { undo(p.peerDeviceId) }
        finish()
    }
}
