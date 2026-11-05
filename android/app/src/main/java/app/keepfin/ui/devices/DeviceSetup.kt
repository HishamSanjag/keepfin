package app.keepfin.ui.devices

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.copies.CopiesManager
import app.keepfin.copies.CopiesUi
import app.keepfin.copies.CopyCardUi
import app.keepfin.copies.CopyDraft
import app.keepfin.copies.CopyHoldsUi
import app.keepfin.copies.CopyPreview
import app.keepfin.copies.CopyStatus
import app.keepfin.copies.PeerAddress
import app.keepfin.core.copies.CopyMode
import app.keepfin.ui.Format
import app.keepfin.ui.components.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// The setup after pairing (dev-docs/plans/DEVICES-REDESIGN.md, Phase 4): what stays up to date, then review and start. It is built on a copy
// kept on the paired device, which the copies engine already runs; this file only chooses, previews and starts it.

/** What the setup asks of the copies engine. [ManagerDeviceCopies] is the real one; tests pass a fake. Every call may block: call off the main thread. */
interface DeviceCopies {
    val state: StateFlow<CopiesUi>
    fun refresh()
    fun preview(draft: CopyDraft, previousId: String?): CopyPreview
    fun cancelPreview(placeId: String)
    fun add(placeId: String)
}

internal class ManagerDeviceCopies(private val m: CopiesManager) : DeviceCopies {
    override val state: StateFlow<CopiesUi> get() = m.state
    override fun refresh() = m.refresh()
    override fun preview(draft: CopyDraft, previousId: String?) = m.preview(draft, previousId)
    override fun cancelPreview(placeId: String) = m.cancelPreview(placeId)
    override fun add(placeId: String) = m.add(placeId)
}

object SetupTags {
    const val PROMPT = "setup-prompt"
    const val PROMPT_SET_UP = "setup-prompt-set-up"
    const val PROMPT_LATER = "setup-prompt-later"
    const val SHEET = "setup-sheet"
    const val STEP1 = "setup-step1"
    const val STEP2 = "setup-step2"
    const val LIST_NOTE = "setup-list-note"
    const val FAVOURITES = "setup-favourites"
    const val BOTH_WAYS = "setup-both-ways"
    const val ONLY_TO = "setup-only-to"
    const val AUTO = "setup-auto"
    const val NOT_ALLOWED = "setup-not-allowed"
    const val CHECK_AGAIN = "setup-check-again"
    const val LATER = "setup-later"
    const val NEXT = "setup-next"
    const val BACK = "setup-back"
    const val START = "setup-start"
    const val CHECKING = "setup-checking"
    const val TO_DEVICE = "setup-to-device"
    const val TO_HERE = "setup-to-here"
    const val ROOM = "setup-room"
    const val ADDS_ONLY = "setup-adds-only"
    const val DIFFERS = "setup-differs"
    const val PROBLEM = "setup-problem"
    const val RETRY = "setup-retry"
    const val ERROR = "setup-error"
    const val DIFFERENT = "setup-different-libraries"
    const val DIFFERENT_OK = "setup-different-ok"
    fun space(id: String) = "setup-space-$id"
    fun cardSetUp(deviceId: String) = "setup-card-$deviceId"
    fun copyStatus(deviceId: String) = "setup-copy-status-$deviceId"
    fun reviewLink(deviceId: String) = "setup-review-$deviceId"
}

/** The copies engine of the running app, made off the main thread (the manager opens files and may start); null until then and where there is none. */
@Composable
internal fun rememberAppCopies(): DeviceCopies? {
    val application = androidx.compose.ui.platform.LocalContext.current.applicationContext as? app.keepfin.KeepfinApplication ?: return null
    val made = androidx.compose.runtime.produceState<DeviceCopies?>(null, application) {
        value = withContext(Dispatchers.IO) {
            runCatching { app.keepfin.copies.CopiesRuntime.of(application)?.also { it.start() }?.let { ManagerDeviceCopies(it) } }.getOrNull()
        }
    }
    return made.value
}

// ---- pure logic ------------------------------------------------------------------------------------------------------------

enum class SetupDirection { BOTH_WAYS, ONLY_TO }

/** What the person chose in step 1. [spaces] holds space ids. */
data class SetupChoices(
    val spaces: Set<String> = emptySet(),
    val favourites: Boolean = false,
    val direction: SetupDirection = SetupDirection.BOTH_WAYS,
    val auto: Boolean = true,
)

private val DEFAULT_ON = setOf("music", "videos", "video")

/** Music and Videos are on to begin with; any other space (Downloads, the person's own) is off. */
internal fun defaultChoices(spaces: List<Pair<String, String>>): SetupChoices =
    SetupChoices(spaces = spaces.filter { it.second.trim().lowercase() in DEFAULT_ON }.map { it.first }.toSet())

/** Something is chosen to keep up to date. When the library lists no spaces, everything is kept ([everything]). */
internal fun hasSelection(c: SetupChoices, everything: Boolean) = everything || c.spaces.isNotEmpty() || c.favourites

/** The copy to preview and add: on the paired device, both ways or only to it, no mirror. */
internal fun draftFor(deviceId: String, deviceName: String, c: SetupChoices, everything: Boolean = false) = CopyDraft(
    treeUri = PeerAddress.make(deviceId),
    name = "Copy on $deviceName",
    mode = if (c.direction == SetupDirection.BOTH_WAYS) CopyMode.TwoWay else CopyMode.Backup,
    holds = if (everything) CopyHoldsUi(all = true) else CopyHoldsUi(all = false, spaces = c.spaces, playlists = emptySet(), favourites = c.favourites),
    everyHours = null,
    auto = c.auto,
)

private fun files(n: Int) = if (n == 1) "1 file" else "$n files"

/** Step 2 in words. Nothing here is a tool name; every state says what to do. */
data class ReviewSummary(
    val ready: Boolean,
    /** A state that is not ready, in one sentence with what to do; null when ready. */
    val problem: String?,
    val toDevice: String?,
    val toHere: String?,
    val room: String?,
    val roomOk: Boolean,
    val differs: List<String>,
    val differsMore: Int,
    /** A held first plan (the deletion guard): Start still works and Keepfin asks before it acts. As on Windows and the Add a copy wizard. */
    val note: String? = null,
)

internal fun reviewSummary(p: CopyPreview, deviceName: String, direction: SetupDirection): ReviewSummary {
    val problem = when (p.status) {
        "ready" -> null
        "held" -> null
        "disconnected" -> "$deviceName is not connected right now. Open Keepfin on it, then press Check again."
        "busy" -> "Another sync is running. Wait for it to finish, then press Check again."
        else -> (p.reason?.takeIf { it.isNotBlank() } ?: "Keepfin can't start this copy right now.") + " Press Check again to try once more."
    }
    if (problem != null) return ReviewSummary(false, problem, null, null, null, true, emptyList(), 0)
    val toDevice = if (p.copyFiles > 0) "To $deviceName: ${files(p.copyFiles)} · ${Format.bytes(p.copyBytes)}" else "Nothing to send: $deviceName already has these files."
    val toHere = if (direction == SetupDirection.BOTH_WAYS) {
        if (p.importsToLibrary > 0) "To this phone: ${files(p.importsToLibrary)} from $deviceName" else "Nothing to bring here: this phone already has what $deviceName has."
    } else null
    val free = p.freeBytes?.let { Format.bytes(it) }
    val room = when {
        p.enough == false -> "$deviceName does not have enough room" + (free?.let { " ($it free)" } ?: "") + ". Free some space on it, then press Check again."
        p.enough == true -> "Enough room on $deviceName" + (free?.let { " ($it free)" } ?: "")
        else -> null
    }
    val shown = p.differences.take(5).map { (path, why) -> "${path.substringAfterLast('/')}: $why" }
    val more = (p.differences.size - shown.size).coerceAtLeast(0) + p.differencesMore
    val note = if (p.status == "held") (p.reason?.takeIf { it.isNotBlank() } ?: "This is a large change.") + " Keepfin will ask you before it starts." else null
    return ReviewSummary(p.enough != false, if (p.enough == false) room else null, toDevice, toHere, room, p.enough != false, shown, more, note)
}

internal fun clockOf(at: Long): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at))

/** The copy on a device in one sentence: "Copying 120 of 800 files · 2.1 GB left", "Up to date · checked 21:04", "Waiting for X", "3 files need a decision". */
internal fun copyStatusSentence(c: CopyCardUi, deviceName: String): String {
    val peer = c.peer
    if (peer != null && !peer.online) return "Waiting for $deviceName"
    val p = c.progress
    return when {
        c.status == CopyStatus.SYNCING || p != null -> {
            if (p == null) "Copying…" else {
                val left = (p.totalBytes - p.doneBytes).takeIf { p.totalBytes > 0 && it > 0 }
                "Copying ${p.done} of ${files(p.total)}" + (left?.let { " · ${Format.bytes(it)} left" } ?: "")
            }
        }
        c.review > 0 -> if (c.review == 1) "1 file needs a decision" else "${c.review} files need a decision"
        c.status == CopyStatus.PROBLEM -> c.problem?.short ?: "Some files were not copied"
        c.status == CopyStatus.PAUSED -> "Paused"
        c.status == CopyStatus.REFUSED -> c.statusText?.takeIf { it.isNotBlank() } ?: "This copy can't be kept up to date right now"
        c.status == CopyStatus.HELD -> "Waiting for you to confirm the first sync"
        c.status == CopyStatus.UP_TO_DATE -> "Up to date" + (c.lastVerifiedAt?.let { " · checked ${clockOf(it)}" } ?: "")
        else -> peer?.waiting?.takeIf { it.isNotBlank() } ?: "Getting ready"
    }
}

/** The other device holds a library this phone does not share (the usual case today): a copy cannot be set up between two separate libraries. */
internal fun hasOwnLibrary(choice: app.keepfin.copies.PeerChoiceUi?): Boolean = choice?.why?.contains("does not share this library") == true

/** The body of the small "These libraries stay separate for now" dialog: the explanation and Continue. */
@Composable
internal fun DifferentLibrariesContent(name: String, onContinue: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(differentLibrariesText(name), fontSize = 14.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(SetupTags.DIFFERENT))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(SetupTags.DIFFERENT_OK)) { Text("Continue") }
    }
}

/** The step-1 sentence for two separate libraries, with what does work meanwhile. */
internal fun differentLibrariesText(name: String): String =
    "$name and this phone each have their own library. Combining them into one library is coming in an update. Until then you can play files from $name and send it downloads."

/** The copy this phone keeps on [deviceId], if there is one. */
internal fun copyOn(ui: CopiesUi?, deviceId: String): CopyCardUi? = ui?.places?.firstOrNull { it.peer?.deviceId == deviceId }

private fun norm(s: String) = s.filter { !it.isWhitespace() }.lowercase()

/** The paired device a just-finished pairing made: by its fingerprint, else by name. */
internal fun pairedBy(paired: List<app.keepfin.net.PairedDeviceUi>, fingerprint: String, name: String): app.keepfin.net.PairedDeviceUi? =
    paired.firstOrNull { fingerprint.isNotBlank() && norm(it.fingerprint) == norm(fingerprint) } ?: paired.firstOrNull { it.name == name }

// ---- the prompt right after pairing -----------------------------------------------------------------------------------------

/** "<name> is connected · Choose what stays up to date" with Set up and Later, side by side. */
@Composable
internal fun SetupPromptContent(name: String, onSetUp: () -> Unit, onLater: () -> Unit) {
    Column(Modifier.testTag(SetupTags.PROMPT), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Choose what stays up to date.", fontSize = 15.sp, lineHeight = 21.sp)
        Text("Your library list stays in step by itself. You pick which files $name keeps copies of.", fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSetUp, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(SetupTags.PROMPT_SET_UP)) { Text("Set up") }
            OutlinedButton(onClick = onLater, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(SetupTags.PROMPT_LATER)) { Text("Later") }
        }
    }
}

// ---- the setup --------------------------------------------------------------------------------------------------------------

/**
 * Step 1 (what stays up to date) and step 2 (review and start) as a full-screen sheet. [onClose] gets true when the copy was started.
 * Closing before Start drops the preview and leaves nothing behind.
 */
@Composable
fun DeviceSetupSheet(
    deviceId: String, deviceName: String, copies: DeviceCopies, combine: DeviceCombine? = null, devicePlatform: String = "", onClose: (started: Boolean) -> Unit,
) {
    val ui by copies.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var step by rememberSaveable { mutableIntStateOf(1) }
    var choices by remember(ui.spaces.isEmpty()) { mutableStateOf(defaultChoices(ui.spaces)) }
    var preview by remember { mutableStateOf<CopyPreview?>(null) }
    var checking by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val everything = ui.spaces.isEmpty()
    val choice = ui.devices.firstOrNull { it.deviceId == deviceId }
    val allowed = choice?.allowed == true
    val ownLibrary = hasOwnLibrary(choice)

    fun leave(started: Boolean) {
        val id = preview?.placeId
        if (!started && !id.isNullOrEmpty()) scope.launch(Dispatchers.IO) { runCatching { copies.cancelPreview(id) } }
        onClose(started)
    }
    BackHandler { if (step == 2 && !starting) step = 1 else if (!starting) leave(false) }

    LaunchedEffect(step, attempt) {
        if (step != 2) return@LaunchedEffect
        checking = true
        error = null
        val previous = preview?.placeId
        preview = withContext(Dispatchers.IO) {
            runCatching { copies.preview(draftFor(deviceId, deviceName, choices, everything), previous) }
                .getOrElse { CopyPreview(previous ?: "", "refused", it.message) }
        }
        checking = false
    }

    if (ownLibrary && combine != null) {
        // two separate libraries: they can be combined into one first (the review, then Combine); the copy setup follows once they are one
        CombineStepDialog(deviceId, deviceName, isPc(devicePlatform), combine) { leave(false) }
        return
    }
    if (ownLibrary) {
        // two separate libraries: a small dialog, not a page
        PairDialogShell("These libraries stay separate for now", { leave(false) }) {
            DifferentLibrariesContent(deviceName) { leave(false) }
        }
        return
    }
    Dialog(onDismissRequest = { if (!starting) leave(false) }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize().testTag(SetupTags.SHEET), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        if (step == 1) "What stays up to date" else "Review and start",
                        style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() },
                    )
                    Text(if (step == 1) "Step 1 of 2 · $deviceName" else "Step 2 of 2 · $deviceName", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (step == 1) StepOne(deviceName, ui, choices, { choices = it }, everything, allowed, choice?.why, onCheckAgain = { scope.launch(Dispatchers.IO) { runCatching { copies.refresh() } } })
                    else StepTwo(deviceName, choices.direction, preview, checking, onCheckAgain = { attempt++ })
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.testTag(SetupTags.ERROR)) }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (step == 1) {
                        OutlinedButton(onClick = { leave(false) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(SetupTags.LATER)) { Text("Later") }
                        Button(
                            onClick = { step = 2 }, enabled = allowed && hasSelection(choices, everything),
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(SetupTags.NEXT),
                        ) { Text("Next") }
                    } else {
                        OutlinedButton(onClick = { step = 1 }, enabled = !starting, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(SetupTags.BACK)) { Text("Back") }
                        val p = preview
                        val summary = p?.let { reviewSummary(it, deviceName, choices.direction) }
                        Button(
                            enabled = !checking && !starting && summary?.ready == true,
                            onClick = {
                                val id = p?.placeId ?: return@Button
                                scope.launch {
                                    starting = true
                                    val e = withContext(Dispatchers.IO) { runCatching { copies.add(id) }.exceptionOrNull() }
                                    starting = false
                                    if (e != null) error = e.message ?: "The copy could not start." else leave(true)
                                }
                            },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(SetupTags.START),
                        ) { Text("Start") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepOne(
    name: String, ui: CopiesUi, c: SetupChoices, onChange: (SetupChoices) -> Unit, everything: Boolean,
    allowed: Boolean, why: String?, onCheckAgain: () -> Unit,
) {
    Column(Modifier.testTag(SetupTags.STEP1), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Your library list (names, playlists, what you played) stays in step by itself. It is small.",
            fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.testTag(SetupTags.LIST_NOTE),
        )
        if (!allowed) {
            Column(Modifier.testTag(SetupTags.NOT_ALLOWED), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(why?.takeIf { it.isNotBlank() } ?: "$name can't keep a copy right now.", fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.error)
                Text("On $name, open Devices, open this phone and turn on Keep a copy here.", fontSize = 14.sp, lineHeight = 20.sp)
                OutlinedButton(onClick = onCheckAgain, modifier = Modifier.heightIn(min = 48.dp).testTag(SetupTags.CHECK_AGAIN)) { Text("Check again") }
            }
        }
        Text("Files to keep up to date", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.semantics { heading() })
        if (everything) Text("Everything in your library.", fontSize = 14.sp)
        for ((id, spaceName) in ui.spaces) {
            val on = id in c.spaces
            CheckRow(spaceName, on, SetupTags.space(id)) { v -> onChange(c.copy(spaces = if (v) c.spaces + id else c.spaces - id)) }
        }
        if (!everything) CheckRow("Favourites", c.favourites, SetupTags.FAVOURITES) { v -> onChange(c.copy(favourites = v)) }
        Text("Which way do files go?", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
        DirectionRow("Both ways", "New files on either device appear on the other.", c.direction == SetupDirection.BOTH_WAYS, SetupTags.BOTH_WAYS) { onChange(c.copy(direction = SetupDirection.BOTH_WAYS)) }
        DirectionRow("Only to $name", "This phone's files go there. Nothing comes back.", c.direction == SetupDirection.ONLY_TO, SetupTags.ONLY_TO) { onChange(c.copy(direction = SetupDirection.ONLY_TO)) }
        SwitchRow("Keep it up to date by itself", c.auto, { onChange(c.copy(auto = it)) }, subtitle = "It syncs when your library changes and when $name is back.", tag = SetupTags.AUTO)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked, onCheckedChange = null)
        Text(label, fontSize = 15.sp)
    }
}

@Composable
private fun DirectionRow(title: String, line: String, selected: Boolean, tag: String, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected, onClick = null)
        Column {
            Text(title, fontSize = 15.sp)
            Text(line, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StepTwo(name: String, direction: SetupDirection, preview: CopyPreview?, checking: Boolean, onCheckAgain: () -> Unit) {
    Column(Modifier.testTag(SetupTags.STEP2), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (checking || preview == null) {
            Text("Checking what would happen…", fontSize = 14.sp, modifier = Modifier.testTag(SetupTags.CHECKING))
            LinearProgressIndicator(Modifier.fillMaxWidth())
            return@Column
        }
        val s = reviewSummary(preview, name, direction)
        s.problem?.let { Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag(SetupTags.PROBLEM)) }
        if (s.problem == null || s.toDevice != null) {
            s.note?.let { Text(it, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            s.toDevice?.let { Text(it, fontSize = 15.sp, modifier = Modifier.testTag(SetupTags.TO_DEVICE)) }
            s.toHere?.let { Text(it, fontSize = 15.sp, modifier = Modifier.testTag(SetupTags.TO_HERE)) }
            s.room?.takeIf { s.problem == null }?.let { Text(it, fontSize = 14.sp, modifier = Modifier.testTag(SetupTags.ROOM)) }
        }
        if (s.ready) Text("The first sync only adds files. Nothing is removed or replaced.", fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.testTag(SetupTags.ADDS_ONLY))
        if (s.ready && s.differs.isNotEmpty()) {
            Column(Modifier.testTag(SetupTags.DIFFERS), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Files that differ: Keepfin keeps both until you choose", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                for (d in s.differs) Text(d, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.differsMore > 0) Text("and ${s.differsMore} more", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!s.ready) TextButton(onClick = onCheckAgain, modifier = Modifier.heightIn(min = 48.dp).testTag(SetupTags.RETRY)) { Text("Check again") }
    }
}
