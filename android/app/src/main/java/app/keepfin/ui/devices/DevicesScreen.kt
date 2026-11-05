package app.keepfin.ui.devices

import android.app.Application
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import app.keepfin.core.net.NetAction
import app.keepfin.folder.SafFolderAccess
import app.keepfin.net.DevicesState
import app.keepfin.net.DiscoveredUi
import app.keepfin.net.PairedDeviceUi
import app.keepfin.ui.settings.SettingsCategory
import app.keepfin.ui.settings.SettingsGroups
import app.keepfin.ui.settings.SettingsTarget
import app.keepfin.net.formatAddress
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.components.Pane
import app.keepfin.ui.components.RowSeparator
import app.keepfin.ui.components.SettingsExpander
import app.keepfin.ui.components.SettingsGroup
import app.keepfin.ui.components.SwitchRow
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object DevicesTags {
    const val PAGE = "devices-page"
    const val NEEDS_FOLDER = "devices-needs-folder"
    const val ENABLE = "devices-enable"
    const val DISABLE = "devices-disable"
    const val STATUS = "devices-status"
    const val ADVANCED = "devices-advanced"
    const val THIS_DEVICE = "devices-this-device"
    const val NAME = "devices-name"
    const val RENAME = "devices-rename"
    const val ADD = "devices-add"
    const val ADD_SCREEN = "devices-add-screen"
    const val DETAILS = "devices-details"
    const val BACK = "devices-back"
    const val ADDRESS = "devices-address"
    const val PAIR = "devices-pair"
    const val SCAN = "devices-scan"
    const val SHOW_QR = "devices-show-qr"
    const val QR = "devices-qr"
    const val CODE = "devices-code"
    const val TYPE_CODE = "devices-type-code"
    const val TYPE_CODE_GO = "devices-type-code-go"
    const val CLOSE_ADD = "devices-close-add"
    const val SAS = "devices-sas"
    const val SAS_MATCH = "devices-sas-match"
    const val SAS_DIFFER = "devices-sas-differ"
    const val APPROVE = "devices-approve"
    const val DECLINE = "devices-decline"
    const val PAIRING_DONE = "devices-pairing-done"
    const val PAIRING_ERROR = "devices-pairing-error"
    const val PAIRING_WAITING = "devices-pairing-waiting"
    const val PAIRING_CANCEL = "devices-pairing-cancel"
    const val INCOMING_DIALOG = "devices-incoming-dialog"
    const val PAIRING_RESULT_OK = "devices-pairing-result-ok"
    const val REMOVE = "devices-remove"
    const val REMOVE_CONFIRM = "devices-remove-confirm"
    const val KEEP_REACHABLE = "devices-keep-reachable"
    const val MOBILE_FILES = "devices-mobile-files"
    const val GET_LIBRARY = "devices-get-library"
    const val JOIN_STATE = "devices-join-state"
    const val ALLOW_JOB = "devices-allow-job"
    const val DECLINE_JOB = "devices-decline-job"
    const val SWITCH = "devices-switch"
    const val PROBLEM = "devices-problem"
    const val YOURS = "devices-yours"
    const val HOW = "devices-how"
    const val SYNC = "devices-sync"
    const val NEARBY = "devices-nearby"
    const val NEARBY_LOOKING = "devices-nearby-looking"
    const val NEARBY_EMPTY = "devices-nearby-empty"
    const val AWAY = "devices-away"
    const val AWAY_STATUS = "devices-away-status"
    const val AWAY_STEPS = "devices-away-steps"
    const val AWAY_GET = "devices-away-get"
    const val AVAILABLE = "devices-available"
    const val CIRCLE = "devices-circle"
    const val LIST = "devices-list"
    const val VIEW_CIRCLE = "devices-view-circle"
    const val VIEW_LIST = "devices-view-list"
    const val SEARCH = "devices-search"
    const val NOT_SEEING = "devices-not-seeing"
    const val OFF = "devices-off"
    const val PAIR_DIALOG = "devices-pair-dialog"
    const val PAIRING_CONNECTING = "devices-pairing-connecting"
    const val GUIDE = "devices-guide"
    const val GUIDE_CLOSE = "devices-guide-close"
    const val TAILSCALE_HOW = "devices-tailscale-how"
    const val SEARCH_STOP = "devices-search-stop"
    const val SEARCH_AGAIN = "devices-search-again"
    const val SEARCH_STATUS = "devices-search-status"
    const val PAIRING_SETUP_NOTE = "devices-pairing-setup-note"
    fun pill(id: String) = "devices-pill-$id"
    fun syncNow(id: String) = "devices-sync-now-$id"
    fun unpair(id: String) = "devices-unpair-$id"
    fun chevron(id: String) = "devices-chevron-$id"
    fun route(id: String) = "devices-route-$id"
    fun nearbyPair(hint: String) = "devices-nearby-pair-$hint"
    fun paired(id: String) = "devices-paired-$id"
    fun discovered(hint: String) = "devices-discovered-$hint"
    fun access(deviceId: String, action: String) = "devices-access-$deviceId-$action"
}

/** Settings > Devices. The service starts only when this page is opened (it is made off the main thread: the device key may be made now). */
@Composable
fun DevicesPage(vm: StudioViewModel, openAdvanced: Boolean = false) {
    val app = LocalContext.current.applicationContext as Application
    var controller by remember { mutableStateOf<DevicesController?>(null) }
    LaunchedEffect(Unit) {
        controller = withContext(Dispatchers.IO) { ServiceDevicesController(app, vm.engine).also { it.state; it.copies } }
    }
    val c = controller
    if (c == null) {
        SettingsGroup("", tag = DevicesTags.PAGE) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Getting ready…", fontSize = 14.sp)
            }
        }
    } else DevicesScreen(
        c, openAdvanced, remember(vm.prefs) { PrefsViewStore(vm.prefs) },
        onReviewCopies = { vm.openSettings(SettingsTarget(SettingsCategory.STORAGE, SettingsGroups.STORAGE)) },
        where = vm.whereSource, onOpenCopy = vm::whereOpenCopy, onKeepCopy = vm::whereKeepCopy,
        openDevice = (vm.whereIntent as? app.keepfin.where.WhereIntent.Device)?.deviceId, onDeviceShown = vm::whereConsumed,
    )
}

internal val ACTION_LABELS: List<Triple<NetAction, String, String>> = listOf(
    Triple(NetAction.MetadataRead, "Read the library", "Lets it see your list: titles, playlists and history."),
    Triple(NetAction.MetadataWrite, "Send changes to it", "Lets it add and edit things in the library."),
    Triple(NetAction.InventoryRead, "See which files you hold", "Needed for it to keep a copy."),
    Triple(NetAction.MediaRead, "Play and copy your files", "Lets it stream or copy your music and videos."),
    Triple(NetAction.CopyMutate, "Keep a copy on this phone", "Lets it write copies of its library into your Keepfin folder."),
    Triple(NetAction.Jobs, "Download for it", "Lets it ask this phone to download links."),
    Triple(NetAction.MembershipAdmin, "Manage devices", "Lets it add and remove devices in the library."),
)

/** A few words on what a paired device may do, for its row: "Reads the library · Plays your files". */
internal fun accessSummary(p: PairedDeviceUi): String {
    val granted = p.libraries.flatMap { it.actions }.toSet()
    val words = listOfNotNull(
        "Reads the library".takeIf { NetAction.MetadataRead.wire in granted },
        "Sends changes".takeIf { NetAction.MetadataWrite.wire in granted },
        "Plays your files".takeIf { NetAction.MediaRead.wire in granted },
        "Keeps a copy here".takeIf { NetAction.CopyMutate.wire in granted },
        "Downloads for it".takeIf { NetAction.Jobs.wire in granted },
    )
    return when {
        p.libraries.isEmpty() -> "No shared library yet"
        words.isEmpty() -> "No access"
        words.size <= 2 -> words.joinToString(" · ")
        else -> words.take(2).joinToString(" · ") + " · +${words.size - 2}"
    }
}


/** How long the page looks for other devices after it opens, or after "Search again". */
internal const val SEARCH_MS = 60_000L

/**
 * Devices: the switch first (off is one calm state), then Scan / Show my code, Available devices (a circle or a list), Your devices
 * as cards that open, How devices work, libraries other devices offer, Away from home and Advanced last. Adding by address and the
 * phone's own code are a screen of their own inside this page. Pairing with a found device happens in a dialog over the page.
 */
@Composable
fun DevicesScreen(
    controller: DevicesController, openAdvanced: Boolean = false, viewStore: DevicesViewStore = rememberMemoryViewStore(), onReviewCopies: (() -> Unit)? = null,
    /** Where your files are, below Your devices (null: not shown). A drive or cloud row opens its copy through [onOpenCopy]; Keep a copy... through [onKeepCopy]. */
    where: app.keepfin.where.WhereFilesSource? = null, onOpenCopy: (String) -> Unit = {}, onKeepCopy: (Set<String>) -> Unit = {},
    /** A device to show open (a tap in Storage's Where your files are); [onDeviceShown] says it was used. */
    openDevice: String? = null, onDeviceShown: () -> Unit = {},
) {
    val state by controller.state.collectAsStateWithLifecycle()
    var openRequest by remember { mutableStateOf<Pair<String, Long>?>(null) }
    LaunchedEffect(openDevice) { if (openDevice != null) { openRequest = openDevice to System.nanoTime(); onDeviceShown() } }
    val deviceCopies = controller.copies
    val copiesUi = deviceCopies?.state?.collectAsStateWithLifecycle()?.value
    // the device whose setup (what stays up to date) is open
    var setupFor by rememberSaveable { mutableStateOf<String?>(null) }
    // combining two libraries: the review (from the card), and Separate again
    val combine = controller.combine
    val combineUi = combine?.state?.collectAsStateWithLifecycle()?.value
    var reviewFor by rememberSaveable { mutableStateOf<String?>(null) }
    var separateFor by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    // "home" or "add"
    var view by rememberSaveable { mutableStateOf("home") }
    fun run(block: () -> String?) {
        scope.launch {
            val error = withContext(Dispatchers.IO) { runCatching(block).getOrElse { it.message ?: "That did not work." } }
            message = error
        }
    }
    // a page that cannot show any more goes back to the home view
    val shown = if (!state.enabled || state.portError != null) "home" else view
    BackHandler(enabled = shown != "home") {
        if (shown == "add") run { controller.closePairing(); null }
        view = "home"
    }
    // While this page is open and the app is in front, look for other devices (DNS-SD and the scan) for [SEARCH_MS], or until the
    // person presses Stop; never in the background. "Search again" starts another round. Paired devices reconnect on their own (not here).
    val lookingOn = state.enabled && state.portError == null
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var stopped by rememberSaveable { mutableStateOf(false) }
    var expired by rememberSaveable { mutableStateOf(false) }
    val halted = stopped || expired
    val searching = lookingOn && !halted
    LaunchedEffect(lookingOn, lifecycle, halted) {
        if (!lookingOn || halted) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            withContext(Dispatchers.IO) { runCatching { controller.startLooking() } }
            try {
                delay(SEARCH_MS)
                expired = true
                awaitCancellation()
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { runCatching { controller.stopLooking() } }
            }
        }
    }
    var guide by rememberSaveable { mutableStateOf<String?>(null) }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (text != null) run { controller.beginAsk(text) }
    }
    val asking = state.pairing?.takeIf { it.role == "asking" && state.enabled }
    var renaming by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag(DevicesTags.PAGE)) {
        if (shown == "add") AddDeviceScreen(state, controller, ::run) { run { controller.closePairing(); null }; view = "home" }
        else {
            GuideButton { guide = "top" }
            DevicesHeader(state, controller, ::run, onRename = { renaming = true }) { guide = "tailscale" }
            if (state.enabled && state.portError == null) {
                if (state.network.none) ProblemCard()
                message?.let { Notice(it, error = true) }
                for (note in state.notes) Notice(note)
                ScanActions(
                    onScan = { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the code on the other device").setBeepEnabled(false).setOrientationLocked(false)) },
                    onShowCode = { run { controller.openPairing(); controller.showQr(); null }; view = "add" },
                )
                AvailableSection(
                    state, viewStore, searching = searching, finished = expired && !stopped,
                    onStop = { stopped = true },
                    onSearchAgain = { stopped = false; expired = false },
                    controller = controller, run = ::run,
                    onConnect = { d -> run { controller.beginAsk(formatAddress(d.host, d.port)) } },
                    onAddByAddress = { run { controller.openPairing(); null }; view = "add" },
                )
                YourDevicesSection(
                    state, controller, ::run, copiesUi, if (deviceCopies != null) ({ id: String -> setupFor = id }) else null, onReviewCopies,
                    combine, combineUi, { id -> separateFor = id }, { id -> reviewFor = id }, openRequest,
                )
                if (where != null) {
                    val whereUi by where.state.collectAsStateWithLifecycle()
                    SectionHeading(app.keepfin.where.WhereWords.TITLE)
                    Pane(Modifier.padding(horizontal = 16.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
                        app.keepfin.ui.settings.WhereFilesBody(
                            whereUi,
                            onOpen = { tap ->
                                when (tap) {
                                    is app.keepfin.where.WhereTap.Device -> openRequest = tap.deviceId to System.nanoTime()
                                    is app.keepfin.where.WhereTap.Copy -> onOpenCopy(tap.placeId)
                                }
                            },
                            onKeepCopy = onKeepCopy,
                        )
                    }
                }
                OffersSection(state, controller, ::run)
                if (state.metered) Notice("You are on mobile data. The library list still syncs; files wait for Wi-Fi unless you allow mobile data for a device.")
                AdvancedSection(state, controller, ::run, openAdvanced)
            } else message?.let { Notice(it, error = true) }
        }
    }
    if (asking != null) {
        AskingPairingDialog(
            asking, controller::decide, controller::dismissPairing,
            onSetUp = if (deviceCopies != null && state.enabled) ({ p -> pairedBy(state.paired, p.peerFingerprint, p.peerName)?.let { setupFor = it.deviceId } }) else null,
        )
    }
    guide?.let { at -> DevicesGuide(at = at, state = state, onClose = { guide = null }) }
    if (renaming) RenameDialog(state.thisDevice.name, onSave = { name -> run { controller.rename(name); null }; renaming = false }) { renaming = false }
    val setupDevice = setupFor?.let { id -> state.paired.firstOrNull { it.deviceId == id } }
    if (deviceCopies != null && setupDevice != null) {
        DeviceSetupSheet(setupDevice.deviceId, setupDevice.name, deviceCopies, combine = combine, devicePlatform = setupDevice.platform) { setupFor = null }
    }
    val reviewDevice = reviewFor?.let { id -> state.paired.firstOrNull { it.deviceId == id } }
    if (combine != null && reviewDevice != null) CombineStepDialog(reviewDevice.deviceId, reviewDevice.name, isPc(reviewDevice.platform), combine) { reviewFor = null }
    val separateDevice = separateFor?.let { id -> state.paired.firstOrNull { it.deviceId == id } }
    if (combine != null && separateDevice != null) SeparateDialog(separateDevice.name, combine) { separateFor = null }
}

/** A computer rather than a phone: the library on a PC is usually the bigger, kept one. */
internal fun isPc(platform: String): Boolean = platform == "windows" || platform == "macos" || platform == "linux"

/** The switch, the name your other devices see (with Rename), and when Devices is off the one calm state that says so. */
@Composable
private fun DevicesHeader(state: DevicesState, controller: DevicesController, run: (() -> String?) -> Unit, onRename: () -> Unit, openTailscaleGuide: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Pane(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(DevicesTags.STATUS), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)) {
        SwitchRow(
            "Devices", state.enabled, { on -> run { if (on) controller.enable() else controller.disable(); null } },
            subtitle = visibilityLine(state), tag = DevicesTags.SWITCH,
        )
        if (state.enabled) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp).testTag(DevicesTags.NAME), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your devices see this phone as", fontSize = 13.sp, color = colors.onSurfaceVariant)
                Text(state.thisDevice.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            TextButton(onClick = onRename, modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.RENAME)) { Text("Rename") }
        }
        if (state.enabled && state.portError == null) TailscaleLine(state, openTailscaleGuide)
        when {
            // off: the switch above is the one way to turn it on; this only says what that does
            !state.enabled -> Text(
                "Turn it on so your devices can find each other, keep your library in step and send each other downloads. Only devices you pair can connect.",
                fontSize = 13.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).testTag(DevicesTags.OFF),
            )
            state.portError != null -> Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusLine(reachable = false, title = "This phone can't be reached by your devices", detail = state.portError)
                OutlinedButton(onClick = { run { controller.disable(); null } }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(DevicesTags.DISABLE)) { Text("Turn off Devices") }
            }
        }
    }
}

/** The "?" at the top right of the page: one guide for finding, pairing, syncing and Tailscale. */
@Composable
private fun GuideButton(onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClick, modifier = Modifier.size(48.dp).testTag(DevicesTags.HOW)) {
            Icon(Icons.AutoMirrored.Outlined.HelpOutline, contentDescription = "How devices work", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One line under the switch: "Also on Tailscale", or "Tailscale: not running · How?" (the guide opens at its Tailscale part). Nothing when Tailscale is the only network. */
@Composable
private fun TailscaleLine(state: DevicesState, openGuide: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        state.network.tailscale && state.network.lan ->
            Text("Also on Tailscale", fontSize = 13.sp, color = muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(DevicesTags.AWAY_STATUS))
        !state.network.tailscale -> Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tailscale: not running", fontSize = 13.sp, color = muted, modifier = Modifier.weight(1f).testTag(DevicesTags.AWAY_STATUS))
            TextButton(onClick = openGuide, modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.TAILSCALE_HOW)) { Text("How?") }
        }
    }
}

/** Scan a code (another device's, or a PC's) and, for phone to phone, show this phone's own code. Side by side. */
@Composable
private fun ScanActions(onScan: () -> Unit, onShowCode: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onScan, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(DevicesTags.SCAN)) {
            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text("Scan")
        }
        OutlinedButton(onClick = onShowCode, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(DevicesTags.SHOW_QR)) { Text("Show my code") }
    }
}

@Composable
internal fun Notice(text: String, error: Boolean = false) {
    Text(
        text, fontSize = 13.sp, lineHeight = 18.sp, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
internal fun SectionHeading(text: String) {
    Text(
        text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** A back arrow and the name of a screen inside Devices. */
@Composable
private fun SubHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp).testTag(DevicesTags.BACK)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to Devices") }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f).semantics { heading() })
    }
}

// ---- the status card ---------------------------------------------------------------------------------------------------------

/** Where this phone can be found, in a few words: "Visible on Home network". */
internal fun visibilityLine(state: DevicesState): String {
    if (!state.enabled) return "Off. Other devices cannot find this phone."
    // Tailscale on top of a home network has its own line under the switch ("Also on Tailscale")
    return when {
        state.network.lan -> "Visible on Home network"
        state.network.tailscale -> "Visible on Tailscale"
        else -> "Not on a network"
    }
}


/** Shown only when the phone has neither Wi-Fi nor Tailscale: nothing can find it and it can find nothing. */
@Composable
private fun ProblemCard() {
    Pane(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag(DevicesTags.PROBLEM), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
        StatusLine(
            reachable = false, title = "No Wi-Fi and no Tailscale",
            detail = "Connect this phone to your Wi-Fi, or turn on Tailscale, so your devices can find each other.",
        )
    }
}

@Composable
internal fun StatusLine(reachable: Boolean, title: String, detail: String?, modifier: Modifier = Modifier, neutralWhenOff: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val off = if (neutralWhenOff) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error
        Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(if (reachable) Color(0xFF2E9E5B) else off))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
            if (detail != null) Text(detail, fontSize = 13.sp, lineHeight = 18.sp, color = if (reachable || neutralWhenOff) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }
    }
}

// ---- advanced ------------------------------------------------------------------------------------------------------------------

@Composable
private fun AdvancedSection(state: DevicesState, controller: DevicesController, run: (() -> String?) -> Unit, openAdvanced: Boolean) {
    var open by rememberSaveable { mutableStateOf(openAdvanced) }
    SettingsExpander("Advanced", "Port, fingerprint and background access", open, { open = !open }, modifier = Modifier.padding(horizontal = 16.dp), tag = DevicesTags.ADVANCED) {
        SwitchRow(
            "Reachable when Keepfin is closed", state.keepReachable, { on -> run { controller.setKeepReachable(on); null } },
            subtitle = "Keeps a small notification so your other devices can sync with this phone in the background. Uses a little battery.", tag = DevicesTags.KEEP_REACHABLE,
        )
        RowSeparator()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(DevicesTags.THIS_DEVICE), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.thisDevice.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text("Fingerprint ${state.thisDevice.fingerprint}", fontSize = 12.sp, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                state.port?.let { Text("Listening on port $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        RowSeparator()
        TextButton(
            onClick = { run { controller.disable(); null } }, modifier = Modifier.padding(horizontal = 8.dp).heightIn(min = 48.dp).testTag(DevicesTags.DISABLE),
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Turn off devices") }
    }
}

/** The name your other devices show for this phone. Paired devices show the new name at once when connected, the others when they next connect. */
@Composable
private fun RenameDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    val name = text.trim()
    AlertDialog(
        onDismissRequest = onDismiss, title = { Text("Name of this phone") },
        text = {
            OutlinedTextField(
                text, { text = it.take(40) }, singleLine = true, label = { Text("Name") },
                supportingText = { Text("Your other devices show this name.") },
                modifier = Modifier.fillMaxWidth().testTag("devices-rename-field"),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name) }, enabled = name.isNotEmpty() && name != current, modifier = Modifier.heightIn(min = 48.dp).testTag("devices-rename-save")) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}

// ---- paired devices ------------------------------------------------------------------------------------------------------


/** "Home network", "Tailscale" or "Not reachable". */
internal fun routeLabel(route: String): String = when (route) {
    "lan" -> "Home network"
    "tailscale" -> "Tailscale"
    else -> "Not reachable"
}

/** What the row says about the connection: "Connected · Home network", "Last seen 5 minutes ago · Not reachable". */
internal fun statusOf(p: PairedDeviceUi): String {
    val now = System.currentTimeMillis()
    return when {
        p.online -> "Connected · ${routeLabel(p.route.takeIf { it != "none" } ?: "lan")}"
        p.unreachableSince != null -> "Not reachable since ${DateUtils.getRelativeTimeSpanString(p.unreachableSince, now, DateUtils.MINUTE_IN_MILLIS)}"
        p.lastSeen != null -> "Last seen ${DateUtils.getRelativeTimeSpanString(p.lastSeen, now, DateUtils.MINUTE_IN_MILLIS)} · Not reachable"
        else -> "Not connected yet · Not reachable"
    }
}


// ---- libraries a paired device offers ------------------------------------------------------------------------------------

@Composable
private fun OffersSection(state: DevicesState, controller: DevicesController, run: (() -> String?) -> Unit) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val target = pending
        pending = null
        if (uri != null && target != null) {
            run {
                try {
                    context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                } catch (e: SecurityException) {
                    // the open below reports a missing grant
                }
                controller.joinLibrary(target.first, target.second, SafFolderAccess.open(context.contentResolver, uri), uri.toString())
            }
        }
    }
    val join = state.join
    if (state.offers.isEmpty() && join == null) return
    SectionHeading("Libraries on other devices")
    for (o in state.offers) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            Text("${o.name} on ${o.deviceName}", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("Get its list on this phone in a new, empty folder. Files stay where they are until you copy them or play them from there.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = { pending = o.deviceId to o.libraryId; picker.launch(null) }, enabled = o.online && join?.stage.let { it == null || it == "done" || it == "failed" },
                modifier = Modifier.padding(top = 6.dp).heightIn(min = 48.dp).testTag(DevicesTags.GET_LIBRARY),
            ) { Text(if (o.online) "Get this library" else "Not connected") }
        }
    }
    if (join != null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag(DevicesTags.JOIN_STATE), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${join.name} from ${join.deviceName}", fontSize = 15.sp, fontWeight = FontWeight.Medium)
            when (join.stage) {
                "failed" -> {
                    Text(join.error ?: "The library could not be taken in.", fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                    Text(if (join.resumable) "Choose the same folder again to continue where it stopped." else "Choose another folder and try again.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { controller.dismissJoin() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("OK") }
                }
                "done" -> {
                    Text("The library was taken in. Its files stay on ${join.deviceName} until you copy or play them.", fontSize = 13.sp)
                    TextButton(onClick = { controller.dismissJoin() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("OK") }
                }
                else -> {
                    Text(if (join.stage == "pulling") "Taking in the library (${join.segments} parts)" else if (join.stage == "asking") "Asking ${join.deviceName} to add this phone" else "Preparing the folder", fontSize = 13.sp)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick = { controller.cancelJoin() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Stop") }
                }
            }
        }
    }
}

// ---- Add a device ----------------------------------------------------------------------------------------------------------

/** The Add a device screen: found devices, an address, a code to scan or show, and the waiting / comparing card. */
@Composable
private fun AddDeviceScreen(state: DevicesState, controller: DevicesController, run: (() -> String?) -> Unit, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(DevicesTags.ADD_SCREEN)) {
        SubHeader("Add a device", onDone)
        if (state.pairingUntil == null) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Getting ready…", fontSize = 14.sp)
            }
            return@Column
        }
        var address by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
            val text = result.contents
            if (text != null) run { controller.beginAsk(text) }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Show this phone's code for another device to scan, or type a code or the address of the other device. You then check that the same code shows on both. This phone accepts new devices for two minutes.", fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                address, { address = it; error = null }, label = { Text("Enter the code or address shown on your other device") }, placeholder = { Text("K7QPM-4X2TA, 192.168.1.20 or my-pc.tailnet.ts.net") },
                singleLine = true, isError = error != null, supportingText = { error?.let { Text(it) } },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().testTag(DevicesTags.ADDRESS),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        run { val e = controller.beginAsk(address); error = e; null }
                    }, enabled = address.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.PAIR),
                ) { Text("Pair") }
                OutlinedButton(
                    onClick = { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Scan the code on the other device").setBeepEnabled(false).setOrientationLocked(false)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.SCAN),
                ) { Text("Scan a code") }
            }
            OutlinedButton(onClick = { run { controller.showQr(); null } }, modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.SHOW_QR)) { Text("Show my code") }
            state.qr?.let { qr ->
                Text("Scan this on the other device. It works once and expires in two minutes.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                QrImage(qr.link)
                qr.code?.let { code ->
                    Text("No camera? Type this code on the other device", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    PairCodeText(code)
                }
            }
            TextButton(onClick = onDone, modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.CLOSE_ADD)) { Text("Done adding") }
        }
    }
}

@Composable
private fun DiscoveredRow(d: DiscoveredUi, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp).testTag(DevicesTags.discovered(d.hint)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(d.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(formatAddress(d.host, d.port), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Pair", color = app.keepfin.ui.theme.Studio.colors.accentText, fontSize = 14.sp)
    }
}


/**
 * "Type a code": for a phone with no camera or a code read from the other screen. Accepts the code the other device shows under its
 * QR (any case, with or without the dash) or an address; the service decides which it is.
 */
@Composable
internal fun TypeCodeField(controller: DevicesController, run: (() -> String?) -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            text, { text = it; error = null }, label = { Text("Enter the code or address shown on your other device") }, placeholder = { Text("K7QPM-4X2TA") },
            singleLine = true, isError = error != null, supportingText = { error?.let { Text(it) } },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Ascii, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth().testTag(DevicesTags.TYPE_CODE),
        )
        Button(onClick = { run { val e = controller.beginAsk(text); error = e; null } }, enabled = text.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.TYPE_CODE_GO)) { Text("Connect") }
    }
}

/** The code that does what the QR does for someone without a camera: large, spaced, tabular. */
@Composable
internal fun PairCodeText(code: String) {
    Text(
        code, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, style = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum"), letterSpacing = 2.sp, // not Monospace: some phones map it to a handwriting font
        modifier = Modifier.testTag(DevicesTags.CODE).semantics { contentDescription = "Code to type on the other device: " + code.replace("-", " dash ").toList().joinToString(" ") },
    )
}
/** The six numbers are shown as "123 456". */
fun groupSas(sas: String): String = if (sas.length == 6) sas.substring(0, 3) + " " + sas.substring(3) else sas

/** A QR code drawn from the pairing link: black modules on white, with a quiet zone, whatever the theme. */
@Composable
fun QrImage(link: String, modifier: Modifier = Modifier) {
    val matrix = remember(link) {
        QRCodeWriter().encode(link, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2))
    }
    Canvas(modifier.size(220.dp).background(Color.White).testTag(DevicesTags.QR).semantics { contentDescription = "Pairing code to scan on the other device" }) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + .5f, cell + .5f))
        }
    }
}

/** When the library last synced with it: "Library in step · checked 21:04", "3 changes synced 21:04", "Syncs when it is back". */
internal fun syncOf(p: PairedDeviceUi): String {
    if (p.libraries.isEmpty()) return "Nothing to sync until a library is shared"
    if (!p.online) return "Syncs when it is back"
    val last = p.lastSync ?: return "Syncing…"
    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(last.at))
    val changes = last.pulled + last.pushed
    return if (changes > 0) "${if (changes == 1) "1 change" else "$changes changes"} synced $time" else "Library in step · checked $time"
}

@Composable
internal fun SyncLine(p: PairedDeviceUi) {
    val synced = p.online && p.lastSync != null
    Text(
        syncOf(p), fontSize = 12.sp,
        color = if (synced) Color(0xFF2E9E5B) else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag(DevicesTags.SYNC),
    )
}
