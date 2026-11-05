package app.keepfin.ui.setup

import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.ui.semantics.contentDescription
import android.app.Activity
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.keepfin.setup.Choice
import app.keepfin.setup.ConsentRequest
import app.keepfin.setup.FolderKind
import app.keepfin.setup.Gate
import app.keepfin.setup.InventoryUi
import app.keepfin.setup.PlanUi
import app.keepfin.setup.SetupActions
import app.keepfin.setup.SetupRequest
import app.keepfin.setup.SetupStep
import app.keepfin.setup.SetupTexts
import app.keepfin.setup.SetupUi
import app.keepfin.setup.SpaceRow
import app.keepfin.ui.Format
import app.keepfin.ui.theme.Studio

/** The test tags of the first start and the setup. */
object SetupTags {
    const val ROOT = "setup-root"
    const val OTHERS = "setup-others"
    const val OTHERS_TOGGLE = "setup-others-toggle"
    const val OTHERS_LOOK = "setup-others-look"
    const val RECOMMENDED = "setup-recommended"
    const val ANOTHER = "setup-another"
    const val CONFIRM_STEPS = "setup-confirm-steps"
    const val INNER = "setup-inner"
    const val EARLIER_LOOK = "setup-earlier-look"
    const val EARLIER_BRING = "setup-earlier-bring"
    const val EARLIER_NOT_NOW = "setup-earlier-not-now"
    const val EARLIER_FOUND = "setup-earlier-found"
    const val HAVE_FOLDER = "setup-have-folder"
    const val CREATE_FOLDER = "setup-create-folder"
    const val CONFIRM_CREATE = "setup-confirm-create"
    const val OPEN_EXISTING = "setup-open-existing"
    const val CONTINUE = "setup-continue"
    const val BACK = "setup-back"
    const val REVIEW = "setup-review"
    const val WHERE_CARD = "setup-where-card"
    const val SUMMARY = "setup-summary"
    const val SPACES = "setup-spaces"
    const val CHANGE_PLACE = "setup-change-place"
    const val START_RUN = "setup-start-run"
    const val ALLOW_FILES = "setup-allow-files"
    const val CONSENT_ALLOW = "setup-consent-allow"
    const val CONSENT_KEEP = "setup-consent-keep"
    const val DONE = "setup-done"
    const val PROGRESS = "setup-progress"
    const val ERROR = "setup-error"
    const val STEPPER = "setup-stepper"
    const val FOLDER_TREE = "setup-folder-tree"
    const val MORE = "setup-more-options"
    const val BREADCRUMB = "setup-breadcrumb"
    const val SPACE_BAR = "setup-space-bar"
    const val DIAGRAM = "setup-diagram"
    const val REPORT_TILES = "setup-report-tiles"
    fun choice(spaceId: String, move: Boolean) = "setup-choice-$spaceId-${if (move) "move" else "leave"}"
    fun source(spaceId: String) = "setup-source-$spaceId"
}

/**
 * The first start and the setup of an earlier library, shown instead of the app (dev-docs/plans/KEEPFIN-FOLDER-ANDROID-SPEC.md sections 5 and 6).
 * Pure over [ui] and [actions] so that tests drive it with fakes.
 */
@Composable
internal fun SetupFlow(
    ui: SetupUi, actions: SetupActions, modifier: Modifier = Modifier,
    /** The start pages ask the app to save the mode (Keepfin Complete or Just downloads) when Start is tapped. */
    onMode: (complete: Boolean) -> Unit = {},
    /** The permissions page offered notifications, so the first download does not ask again right after. */
    onNotificationsOffered: () -> Unit = {},
    /** Tests of the detailed steps open on them; the app always opens on the start pages. */
    startInAdvanced: Boolean = false,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> actions.folderPicked(uri?.toString()) }
    val writeStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> actions.writePermissionResult(granted) }
    // what the flow wants launched: the system picker, opened inside the document it names, or the storage permission of Android 9 and older
    LaunchedEffect(ui.request) {
        when (val request = ui.request) {
            is SetupRequest.Picker -> { actions.requestHandled(); picker.launch(DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", request.documentId)) }
            SetupRequest.WritePermission -> { actions.requestHandled(); writeStorage.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) }
            null -> {}
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { actions.permissionsChanged() }
    val sender = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result -> actions.consentResult(result.resultCode == Activity.RESULT_OK) }
    val writePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> actions.consentResult(granted) }
    val askConsent = {
        when (val request = actions.consentRequest()) {
            is ConsentRequest.Sender -> sender.launch(IntentSenderRequest.Builder(request.intentSender).build())
            is ConsentRequest.Permission -> writePermission.launch(request.permission)
            null -> actions.consentResult(false)
        }
    }
    // First start: the five start pages (Welcome, How, Mode, Folder, Permissions). "Advanced setup" opens today's detailed Welcome and
    // Where steps instead, until a folder is chosen; the files of an earlier Keepfin (EARLIER) and every other step are unchanged.
    val startStep = ui.gate == Gate.FIRST_START && (ui.step == SetupStep.WELCOME || ui.step == SetupStep.WHERE)
    var advanced by rememberSaveable { mutableStateOf(startInAdvanced) }
    var startPage by rememberSaveable { mutableIntStateOf(0) }
    var complete by rememberSaveable { mutableStateOf(true) }
    val onStartPages = startStep && (!advanced || ui.startReady)
    BackHandler(enabled = startStep && advanced && !ui.startReady && !startInAdvanced) {
        if (ui.step == SetupStep.WHERE) actions.back() else advanced = false
    }
    Surface(modifier.fillMaxSize().testTag(SetupTags.ROOT), color = MaterialTheme.colorScheme.background) {
        if (onStartPages) StartPages(
            ui, actions, startPage, { startPage = it }, complete, { complete = it }, onAdvanced = { advanced = true },
            onMode = onMode, onNotificationsOffered = onNotificationsOffered,
        ) else Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // the welcome is a centred moment of its own (no step bar); every other step reads from the top
            val welcome = ui.step == SetupStep.WELCOME
            BoxWithConstraints(Modifier.weight(1f)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = if (welcome) Arrangement.spacedBy(12.dp, Alignment.CenterVertically) else Arrangement.spacedBy(12.dp)) {
                if (!welcome) Header(ui)
                when (ui.step) {
                    SetupStep.WELCOME -> Welcome(ui, actions)
                    SetupStep.WHAT_YOU_HAVE -> WhatYouHave(ui, actions) { permissions.launch(it.toTypedArray()) }
                    // the setup of an earlier library is one screen from the place to Start; first start keeps its own Where
                    SetupStep.WHERE -> if (ui.gate == Gate.SETUP) PlanScreen(ui, actions) { permissions.launch(it.toTypedArray()) } else Where(ui, actions)
                    SetupStep.MOVE_OR_LEAVE, SetupStep.REVIEW -> PlanScreen(ui, actions) { permissions.launch(it.toTypedArray()) }
                    SetupStep.RUNNING -> Running(ui)
                    SetupStep.CONSENT -> ConsentStep(ui)
                    SetupStep.STOPPED -> Stopped(ui)
                    SetupStep.REPORT -> ReportStep(ui)
                    SetupStep.EARLIER -> Earlier(ui, actions) { permissions.launch(it.toTypedArray()) }
                }
                ui.error?.let { Alert(it, tone = Tone.Error) }
            }
            }
            Actions(ui, actions, askConsent) { permissions.launch(it.toTypedArray()) }
        }
    }
}

internal enum class Tone { Info, Warning, Error }

/** The Keepfin mark, the step name and a row of dots that shows how far along the flow is. */
@Composable
private fun Header(ui: SetupUi) {
    // files an earlier Keepfin saved (first start or Settings) have their own, shorter list
    val earlier = ui.earlier != null
    val steps = when {
        ui.gate == Gate.FIRST_START && !earlier -> listOf("Welcome", "Where")
        else -> listOf(if (earlier) "Earlier files" else "Welcome", "Your plan", "Bringing it over", "Done")
    }
    val index = when (ui.step) {
        SetupStep.WELCOME, SetupStep.WHAT_YOU_HAVE, SetupStep.EARLIER -> 0
        SetupStep.WHERE, SetupStep.MOVE_OR_LEAVE, SetupStep.REVIEW -> 1
        SetupStep.RUNNING, SetupStep.CONSENT, SetupStep.STOPPED -> 2
        SetupStep.REPORT -> 3
    }.coerceAtMost(steps.lastIndex)
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KeepfinMark(36.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Keepfin", fontSize = 13.sp, color = colors.onSurface, fontWeight = FontWeight.SemiBold)
            Text("Step ${index + 1} of ${steps.size}: ${steps[index]}", fontSize = 12.sp, color = colors.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                steps.forEachIndexed { i, _ ->
                    Surface(Modifier.weight(1f).height(4.dp), shape = RoundedCornerShape(2.dp), color = if (i <= index) colors.primary else colors.surfaceContainerHighest) {}
                }
            }
        }
    }
}

@Composable
private fun Title(text: String, hint: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        if (hint != null) Text(hint, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun Alert(text: String, title: String? = null, tone: Tone = Tone.Info, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    val container = when (tone) { Tone.Info -> colors.primaryContainer; Tone.Warning -> colors.tertiaryContainer; Tone.Error -> colors.errorContainer }
    val content = when (tone) { Tone.Info -> colors.onPrimaryContainer; Tone.Warning -> colors.onTertiaryContainer; Tone.Error -> colors.onErrorContainer }
    Surface(modifier.fillMaxWidth().testTag(if (tone == Tone.Error) SetupTags.ERROR else "setup-alert"), shape = RoundedCornerShape(14.dp), color = container, contentColor = content) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (title != null) Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(text, fontSize = 14.sp, lineHeight = 20.sp)
            actions()
        }
    }
}

@Composable
internal fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = Studio.colors.raised,
        border = if (Studio.colors.dark) null else BorderStroke(1.dp, Studio.colors.separator),
    ) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() } }
}

private fun spaceIcon(s: SpaceRow): ImageVector {
    val key = (s.id + " " + s.name).lowercase()
    return when {
        "music" in key || "audio" in key || "song" in key -> Icons.Outlined.MusicNote
        "video" in key || "movie" in key -> Icons.Outlined.Movie
        "download" in key -> Icons.Outlined.Download
        "photo" in key || "image" in key || "picture" in key -> Icons.Outlined.Image
        else -> Icons.Outlined.Folder
    }
}

// ---------------------------------------------------------------------------------------------------- welcome and where

/** The big button, its one-sentence promise and the two things Android will ask. Used on the Welcome and the Where step. */
@Composable
private fun RecommendedChoice(ui: SetupUi, actions: SetupActions) {
    Button(
        onClick = actions::chooseRecommended, enabled = !ui.busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(SetupTags.RECOMMENDED),
    ) {
        Icon(Icons.Outlined.CreateNewFolder, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.size(10.dp))
        Text(SetupTexts.RECOMMENDED, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
    Text(SetupTexts.RECOMMENDED_HINT, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    ConfirmSteps(Modifier.testTag(SetupTags.CONFIRM_STEPS))
}

/**
 * First start, kept to what most people need: one sentence, one button that makes the folder in the recommended place, one
 * quiet line on what Android asks next. Another place and an existing Keepfin folder wait behind "Other options".
 */
@Composable
private fun Welcome(ui: SetupUi, actions: SetupActions) {
    var more by rememberSaveable { mutableStateOf(false) }
    val folder = ui.folder
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KeepfinLogo(112.dp)
        Spacer(Modifier.height(4.dp))
        Text(SetupTexts.WELCOME_TITLE, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
        Text(SetupTexts.WELCOME_HINT, fontSize = 16.sp, lineHeight = 22.sp, color = muted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 340.dp))
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = actions::chooseRecommended, enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp).heightIn(min = 56.dp).testTag(SetupTags.RECOMMENDED),
        ) { Text(SetupTexts.WELCOME_START, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
        Text(SetupTexts.WELCOME_CONFIRM, fontSize = 13.sp, lineHeight = 18.sp, color = muted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp).testTag(SetupTags.CONFIRM_STEPS))
        if (more || folder != null) {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = actions::chooseAnother, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp).heightIn(min = 48.dp).testTag(SetupTags.ANOTHER)) { Text(SetupTexts.ANOTHER) }
            OutlinedButton(onClick = actions::chooseExisting, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp).heightIn(min = 48.dp).testTag(SetupTags.HAVE_FOLDER)) { Text(SetupTexts.HAVE_FOLDER) }
            Text(SetupTexts.HAVE_FOLDER_HINT, fontSize = 13.sp, lineHeight = 18.sp, color = muted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp))
        } else {
            TextButton(onClick = { more = true }, modifier = Modifier.heightIn(min = 48.dp).testTag(SetupTags.MORE)) { Text(SetupTexts.WELCOME_MORE) }
        }
    }
    if (folder != null) when (folder.kind) {
        FolderKind.EXISTING -> Alert(folder.label, "Found a Keepfin folder") {
            Button(onClick = actions::openFolder, enabled = !ui.busy, modifier = Modifier.heightIn(min = 48.dp).testTag(SetupTags.OPEN_EXISTING)) { Text("Open it") }
        }
        FolderKind.NEWER -> Alert("${folder.label} was made by a newer version of Keepfin. Update Keepfin to change it. You can open it to read it.", "Made by a newer Keepfin", Tone.Warning) {
            OutlinedButton(onClick = actions::openFolder, enabled = !ui.busy, modifier = Modifier.heightIn(min = 48.dp).testTag(SetupTags.OPEN_EXISTING)) { Text("Open read-only") }
        }
        FolderKind.NEW -> if (folder.inner != null) {
            Alert("${folder.label} holds a Keepfin folder called Keepfin. Choose that one.", SetupTexts.INNER_FOUND, Tone.Info) {
                Button(onClick = actions::chooseInner, enabled = !ui.busy, modifier = Modifier.heightIn(min = 48.dp).testTag(SetupTags.INNER)) { Text("Choose it") }
            }
        } else Alert("${folder.label} does not hold a Keepfin folder. Choose another one, or use the recommended place.", "No Keepfin folder there", Tone.Warning)
    }
}

@Composable
private fun Where(ui: SetupUi, actions: SetupActions) {
    Title(SetupTexts.WHERE_TITLE, SetupTexts.WHERE_HINT)
    val folder = ui.folder
    if (folder == null) {
        RecommendedChoice(ui, actions)
        Breadcrumb("Internal storage > Documents > Keepfin", Modifier.testTag(SetupTags.BREADCRUMB))
        OutlinedButton(onClick = actions::chooseAnother, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(SetupTags.ANOTHER)) { Text(SetupTexts.ANOTHER) }
        Text("${SetupTexts.ANOTHER_HINT} A memory card works as well.", fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f)) {
                Text("Your Keepfin folder", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Keepfin keeps everything here.", fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 21.sp)
            }
        }
        // the place it will be, as a breadcrumb
        Breadcrumb(folder.label, Modifier.testTag(SetupTags.BREADCRUMB))
        val needed = ui.inventory?.spaces?.filter { it.choice == Choice.MOVE }?.sumOf { it.bytes }
        val free = folder.freeBytes
        if (free != null) {
            if (needed != null && needed > 0) SpaceBar(needed, free, fits = needed <= free, modifier = Modifier.testTag(SetupTags.SPACE_BAR), format = Format::bytes)
            else Text("${Format.bytes(free)} free", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = actions::chooseAnother, enabled = !ui.busy, modifier = Modifier.heightIn(min = 48.dp).testTag(SetupTags.ANOTHER)) { Text("Choose a different place") }
    }
    // which places Android refuses is said when one is refused (the error), not on every visit
    if (folder != null) when (folder.kind) {
        FolderKind.NEW -> Alert(
            buildString {
                append(folder.label)
                append(". Keepfin adds Music, Videos and Downloads and its own hidden data inside.")
                if (folder.existingEntries > 0) append(" This folder already holds ${folder.existingEntries} ${if (folder.existingEntries == 1) "item" else "items"}; they stay untouched.")
                folder.freeBytes?.let { append(" ${Format.bytes(it)} free.") }
            },
            "This folder will be your Keepfin folder",
        )
        FolderKind.EXISTING -> Alert("${folder.label} already holds a Keepfin library. It is opened, and your library, playlists and history come back.", "Found a Keepfin folder")
        FolderKind.NEWER -> Alert("${folder.label} was made by a newer version of Keepfin. Update Keepfin to change it.", "Made by a newer Keepfin", Tone.Warning)
    }
    if (ui.pickCancelled && folder == null) Alert(SetupTexts.PICK_CANCELLED_HINT, "Nothing was chosen", Tone.Warning)
}

/** Files an earlier Keepfin saved in Music, Movies and Download: ask to look, then offer to bring them in. */
@Composable
private fun Earlier(ui: SetupUi, actions: SetupActions, askPermissions: (List<String>) -> Unit) {
    Title(SetupTexts.EARLIER_TITLE, if (ui.earlier?.files?.let { it > 0 } == true) "Keepfin found files an earlier Keepfin saved. You can bring them into your Keepfin folder." else null)
    val e = ui.earlier ?: return
    if (e.needsPermission) {
        Alert(SetupTexts.EARLIER_ASK, "Look for them?", Tone.Info)
    } else if (e.files > 0) {
        Card(Modifier.testTag(SetupTags.EARLIER_FOUND)) {
            Text("${e.files} ${if (e.files == 1) "file" else "files"}, ${Format.bytes(e.bytes)}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            e.places.forEach { (name, count) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(placeIcon(name), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Text(name, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    CountChip("$count")
                }
            }
            Text("Next you choose whether they move into the Keepfin folder or stay where they are. Nothing is removed until its copy has been checked.", fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else if (ui.busy) {
        Text("Looking for your files…", fontSize = 14.sp)
        LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp))
    }
    e.message?.let { Alert(it, null, Tone.Info) }
}

private fun placeIcon(name: String): ImageVector = when (name.lowercase()) {
    "music" -> Icons.Outlined.MusicNote
    "videos" -> Icons.Outlined.Movie
    else -> Icons.Outlined.Download
}

// ---------------------------------------------------------------------------------------------------- setup of an earlier library

private val gainIcons = listOf(Icons.Outlined.Folder, Icons.Outlined.Sync, Icons.Outlined.Link)

@Composable
private fun WhatYouHave(ui: SetupUi, actions: SetupActions, askPermissions: (List<String>) -> Unit) {
    // People who update land here first: say what changed and what the steps do before showing any numbers.
    Title(SetupTexts.UPGRADE_TITLE, SetupTexts.UPGRADE_HINT)
    FolderTreePicture()
    Card {
        SetupTexts.UPGRADE_GAINS.forEachIndexed { index, (title, text) ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Icon(gainIcons.getOrElse(index) { Icons.Outlined.Folder }, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text(text, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    // what you have, in one line: the details (per space) are on the plan screen, where they are chosen
    val inventory = ui.inventory
    Text(
        when {
            inventory != null -> "You have ${inventory.items} ${if (inventory.items == 1) "file" else "files"} (${Format.bytes(inventory.bytes)}). ${counts(inventory)}"
            ui.busy -> "Looking at your library…"
            else -> "Your library could not be read."
        },
        fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(SetupTags.SUMMARY),
    )
}

private fun counts(i: InventoryUi): String {
    val parts = ArrayList<String>()
    if (i.playlists > 0) parts += "${i.playlists} ${if (i.playlists == 1) "playlist" else "playlists"}"
    if (i.downloaded > 0) parts += "${i.downloaded} downloaded ${if (i.downloaded == 1) "collection" else "collections"}"
    if (i.favourites > 0) parts += "${i.favourites} ${if (i.favourites == 1) "favourite" else "favourites"}"
    return if (parts.isEmpty()) "Spaces, history and settings come along." else parts.joinToString(", ") + " come along."
}

/** A small capital label over a block of the plan screen ("WHERE", "WHAT MOVES IN"). */
@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
}

/**
 * The setup of an earlier library on one screen (owner's choice A, 2026-10-22): where the Keepfin folder is, what moves in (one line
 * per space with Move | Keep in place), one line on what happens, and Start. Notes appear only when they apply (no room, access).
 */
@Composable
private fun PlanScreen(ui: SetupUi, actions: SetupActions, askPermissions: (List<String>) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val earlier = ui.earlier != null
    Title(if (earlier) "Bring them into your Keepfin folder" else "Set up your Keepfin folder", "Nothing happens until you press Start.")

    if (!earlier) {
        SectionLabel("Where")
        val folder = ui.folder
        Card(Modifier.testTag(SetupTags.WHERE_CARD)) {
            if (folder == null) {
                Text("Keepfin keeps your library in one folder you choose.", fontSize = 14.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant)
                Button(onClick = actions::chooseRecommended, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(SetupTags.RECOMMENDED)) { Text("Use Documents › Keepfin") }
                TextButton(onClick = actions::chooseAnother, enabled = !ui.busy, modifier = Modifier.heightIn(min = 48.dp).testTag(SetupTags.ANOTHER)) { Text(SetupTexts.ANOTHER) }
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Folder, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(folder.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp, modifier = Modifier.testTag(SetupTags.BREADCRUMB))
                    folder.freeBytes?.let { Text("${Format.bytes(it)} free", fontSize = 13.sp, color = colors.onSurfaceVariant) }
                }
                TextButton(onClick = actions::chooseAnother, enabled = !ui.busy, modifier = Modifier.heightIn(min = 48.dp).testTag(SetupTags.CHANGE_PLACE)) { Text("Change") }
            }
        }
        if (folder?.kind == FolderKind.EXISTING) Alert("Your library, playlists and history in it come back.", "This is a Keepfin folder already")
        if (folder?.kind == FolderKind.NEWER) Alert("It was made by a newer version of Keepfin. Update Keepfin, or choose another place.", "Made by a newer Keepfin", Tone.Warning)
        if (ui.pickCancelled && folder == null) Alert(SetupTexts.PICK_CANCELLED_HINT, "Nothing was chosen", Tone.Warning)
    }

    val inventory = ui.inventory ?: return
    SectionLabel("What moves in")
    Card(Modifier.testTag(SetupTags.SPACES)) {
        val spaces = inventory.spaces.filter { it.items > 0 }
        spaces.forEachIndexed { i, s ->
            if (i > 0) androidx.compose.material3.HorizontalDivider(color = Studio.colors.separator)
            SpaceLine(s, actions)
        }
        if (inventory.others > 0) {
            androidx.compose.material3.HorizontalDivider(color = Studio.colors.separator)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.History, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f)) {
                    Text("${inventory.others} other ${if (inventory.others == 1) "file" else "files"} Keepfin saved before", fontSize = 15.sp)
                    Text("${Format.bytes(inventory.otherBytes)} your library did not list", fontSize = 13.sp, color = colors.onSurfaceVariant)
                }
                androidx.compose.material3.Switch(checked = inventory.includeOthers, onCheckedChange = { actions.includeOthers(it) }, modifier = Modifier.testTag(SetupTags.OTHERS_TOGGLE))
            }
        } else if (inventory.otherPermissions.isNotEmpty()) {
            androidx.compose.material3.HorizontalDivider(color = Studio.colors.separator)
            TextButton(onClick = { askPermissions(inventory.otherPermissions) }, modifier = Modifier.heightIn(min = 48.dp).testTag(SetupTags.OTHERS_LOOK)) { Text("Look for other files Keepfin saved before") }
        }
    }

    val plan = ui.plan ?: return
    // what Start does, in one or two sentences
    Column(Modifier.fillMaxWidth().testTag(SetupTags.REVIEW), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val what = buildString {
            if (plan.moveCount > 0) append("Copies ${plan.moveCount} ${if (plan.moveCount == 1) "file" else "files"} (${Format.bytes(plan.moveBytes)}), checks every one, then removes the old copies.")
            else append("Nothing is copied.")
            if (plan.leaveCount > 0) append(" ${plan.leaveCount} stay where they are.")
            if (plan.foreignMoves > 0) append(" Android asks before removing the ${plan.foreignMoves} from before a reinstall.")
        }
        Text(what, fontSize = 14.sp, lineHeight = 20.sp, color = colors.onSurfaceVariant)
        Text("You can leave Keepfin while it works; it carries on next time.", fontSize = 13.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
    }
    if (!plan.fits) Alert("Choose Keep in place for some spaces, or change the place to one with more room.", "There is not enough room (${plan.freeBytes?.let { Format.bytes(it) + " free" } ?: "unknown free space"})", Tone.Error)
    if (plan.permissions.isNotEmpty()) {
        Alert("Keepfin must be allowed to read your music and videos from before the reinstall to copy them.", "Allow access first", Tone.Warning) {
            Button(onClick = { askPermissions(plan.permissions) }, modifier = Modifier.heightIn(min = 48.dp).testTag(SetupTags.ALLOW_FILES)) { Text("Allow access") }
        }
    }
}

/** One space on the plan screen: what it is, how much, and Move | Keep in place. */
@Composable
private fun SpaceLine(s: SpaceRow, actions: SetupActions) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().testTag("setup-space-${s.id}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(spaceIcon(s), contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(s.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text("${s.items} · ${Format.bytes(s.bytes)}", fontSize = 13.sp, color = colors.onSurfaceVariant)
        }
        SingleChoiceSegmentedButtonRow {
            listOf(true to "Move", false to "Keep").forEachIndexed { i, (move, label) ->
                SegmentedButton(
                    selected = (s.choice == Choice.MOVE) == move,
                    onClick = { actions.setChoice(s.id, if (move) Choice.MOVE else Choice.LEAVE) },
                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                    icon = {},
                    // the chosen side is filled, so the choice reads at a glance (the default tint is too faint on the dark setup)
                    colors = SegmentedButtonDefaults.colors(activeContainerColor = colors.primary, activeContentColor = colors.onPrimary, activeBorderColor = colors.primary),
                    modifier = Modifier.heightIn(min = 48.dp).testTag(SetupTags.choice(s.id, move))
                        .semantics { contentDescription = if (move) "Move ${s.name} into the Keepfin folder" else "Keep ${s.name} where it is" },
                ) { Text(label, fontSize = 14.sp) }
            }
        }
    }
}

/** Where the work is in Copy → Check → Save → Tidy: each file is copied and checked, then the library is written, then old copies are tidied. */
private fun phaseIndex(ui: SetupUi): Int = when {
    ui.step == SetupStep.CONSENT -> 3
    ui.progress?.phase == "committing" -> 2
    else -> 0
}

@Composable
private fun Running(ui: SetupUi) {
    Title("Bringing it over", "Nothing is removed until its copy has been checked. You can close Keepfin and come back; it carries on where it stopped.")
    val p = ui.progress
    Card(Modifier.testTag(SetupTags.PROGRESS)) {
        Stepper(listOf("Copy", "Check", "Save", "Tidy"), phaseIndex(ui), Modifier.testTag(SetupTags.STEPPER))
        val label = when {
            ui.stopped != null && !ui.running -> ui.stopped
            p == null -> if (ui.running) (if (ui.resumed) "Continuing…" else "Getting ready…") else "Ready to continue."
            p.phase == "committing" -> "Writing your library"
            else -> "Copying your files"
        }
        Text(label ?: "", fontSize = 16.sp, fontWeight = FontWeight.Medium)
        if (p != null && p.total > 0) {
            LinearProgressIndicator(progress = { (p.done.toFloat() / p.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp))
            Text("${p.done} of ${p.total} files · ${Format.progressBytes(p.bytesDone, p.bytesTotal)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            p.current?.let { Text(it, fontSize = 13.sp, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else if (ui.running) LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp))
    }
}

@Composable
private fun ConsentStep(ui: SetupUi) {
    Title("Remove the old copies?", "Your files are copied and checked. The old copies are still where they were.")
    Card { Stepper(listOf("Copy", "Check", "Save", "Tidy"), 3, Modifier.testTag(SetupTags.STEPPER)) }
    val files = ui.consent?.files ?: 0
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Text("$files ${if (files == 1) "file" else "files"} can be removed", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Text("Android asks you before an app removes files it did not create. If you say no, nothing is lost: the old copies stay and the report lists them.", fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Stopped(ui: SetupUi) {
    Title("Setup stopped", "Nothing was lost. Your earlier library is untouched, and setup carries on from where it stopped.")
    ui.stopped?.let { Alert(it, "What happened", Tone.Warning) }
}

@Composable
private fun ReportStep(ui: SetupUi) {
    Title("Your library is in the Keepfin folder", null)
    val r = ui.report ?: return
    val colors = MaterialTheme.colorScheme
    Card {
        Text("${r.moved} moved, ${r.linked} left where they are", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        // before → after as tiles
        Row(Modifier.fillMaxWidth().testTag(SetupTags.REPORT_TILES), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(Icons.Outlined.Folder, "${r.moved}", "moved into the Keepfin folder", Modifier.weight(1f), check = true)
            StatTile(Icons.Outlined.Link, "${r.linked}", "left where they are, linked", Modifier.weight(1f), check = true)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(Icons.Outlined.PlaylistPlay, "${r.playlists + r.downloaded}", "playlists and collections", Modifier.weight(1f), check = true)
            StatTile(Icons.Outlined.Favorite, "${r.favourites}", "favourites", Modifier.weight(1f), check = true)
        }
        if (r.bytesCopied > 0) Text("${Format.bytes(r.bytesCopied)} copied and checked.", fontSize = 14.sp, color = colors.onSurfaceVariant)
        if (r.sidecarsFailed > 0) Text("${r.sidecarsFailed} subtitle or lyrics files could not be copied.", fontSize = 14.sp, color = colors.onSurfaceVariant)
    }
    if (r.failed.isNotEmpty()) Alert(r.failed.joinToString("\n") { "${it.title}: ${it.reason}" }, "${r.failed.size} could not be brought over", Tone.Warning)
    if (r.kept.isNotEmpty()) Alert(r.kept.joinToString("\n") { "${it.title}: ${it.reason}" }, "${r.kept.size} old copies stay where they are", Tone.Info)
}

// ---------------------------------------------------------------------------------------------------- the buttons at the bottom

@Composable
private fun Actions(ui: SetupUi, actions: SetupActions, askConsent: () -> Unit, askPermissionsFor: (List<String>) -> Unit) {
    val primary = Modifier.heightIn(min = 48.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (ui.step) {
            SetupStep.WELCOME -> Unit
            SetupStep.WHAT_YOU_HAVE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                Button(onClick = actions::start, enabled = ui.inventory != null && !ui.busy, modifier = primary.testTag(SetupTags.CONTINUE)) { Text("Choose the Keepfin folder") }
            }
            // the one-screen plan before a place is chosen: Start waits for it
            SetupStep.WHERE -> if (ui.gate == Gate.SETUP) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = actions::back, modifier = primary.testTag(SetupTags.BACK)) { Text("Back") }
                Spacer(Modifier.weight(1f))
                Button(onClick = {}, enabled = false, modifier = primary.testTag(SetupTags.START_RUN)) { Text("Start") }
            } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = actions::back, modifier = primary.testTag(SetupTags.BACK)) { Text("Back") }
                Spacer(Modifier.weight(1f))
                val folder = ui.folder
                if (folder != null && folder.kind != FolderKind.NEWER) {
                    if (ui.gate == Gate.FIRST_START) {
                        if (folder.kind == FolderKind.NEW) Button(onClick = actions::createFolder, enabled = !ui.busy, modifier = primary.testTag(SetupTags.CONFIRM_CREATE)) { Text("Create my Keepfin folder") }
                        else Button(onClick = actions::openFolder, enabled = !ui.busy, modifier = primary.testTag(SetupTags.OPEN_EXISTING)) { Text("Open it") }
                    } else Button(onClick = actions::start, enabled = !ui.busy, modifier = primary.testTag(SetupTags.CONTINUE)) { Text("Continue") }
                }
            }
            SetupStep.MOVE_OR_LEAVE -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = actions::back, modifier = primary.testTag(SetupTags.BACK)) { Text("Back") }
                Spacer(Modifier.weight(1f))
                // the plan is normally ready before this step shows; Start makes it when it is not
                Button(onClick = actions::review, modifier = primary.testTag(SetupTags.CONTINUE)) { Text("Start") }
            }
            SetupStep.REVIEW -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = actions::back, modifier = primary.testTag(SetupTags.BACK)) { Text("Back") }
                Spacer(Modifier.weight(1f))
                val plan = ui.plan
                Button(onClick = actions::begin, enabled = plan != null && plan.fits && plan.permissions.isEmpty() && !ui.busy, modifier = primary.testTag(SetupTags.START_RUN)) { Text("Start") }
            }
            SetupStep.RUNNING -> if (!ui.running) Button(onClick = actions::resume, enabled = !ui.busy, modifier = primary.fillMaxWidth().testTag(SetupTags.CONTINUE)) { Text("Continue") }
            SetupStep.CONSENT -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { actions.consentResult(false) }, enabled = !ui.busy, modifier = primary.testTag(SetupTags.CONSENT_KEEP)) { Text("Keep the old copies") }
                Spacer(Modifier.weight(1f))
                Button(onClick = askConsent, enabled = !ui.busy, modifier = primary.testTag(SetupTags.CONSENT_ALLOW)) { Text("Remove them") }
            }
            SetupStep.STOPPED -> Button(onClick = actions::resume, enabled = !ui.busy, modifier = primary.fillMaxWidth().testTag(SetupTags.CONTINUE)) { Text("Continue") }
            SetupStep.EARLIER -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = actions::skipEarlier, enabled = !ui.busy, modifier = primary.testTag(SetupTags.EARLIER_NOT_NOW)) { Text(if (ui.earlier?.message != null && ui.earlier.files == 0) "Done" else SetupTexts.NOT_NOW) }
                Spacer(Modifier.weight(1f))
                val e = ui.earlier
                if (e != null && e.needsPermission) Button(onClick = { askPermissionsFor(e.permissions) }, enabled = !ui.busy, modifier = primary.testTag(SetupTags.EARLIER_LOOK)) { Text("Look for them") }
                else if (e != null && e.files > 0) Button(onClick = actions::bringEarlier, enabled = !ui.busy, modifier = primary.testTag(SetupTags.EARLIER_BRING)) { Text("Bring in ${e.files} ${if (e.files == 1) "file" else "files"} Keepfin saved before") }
            }
            SetupStep.REPORT -> Button(onClick = actions::finish, enabled = !ui.busy, modifier = primary.fillMaxWidth().testTag(SetupTags.DONE)) { Text("Done") }
        }
    }
}
