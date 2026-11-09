@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.keepfin.ui.copies

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.ui.semantics.stateDescription
import app.keepfin.ui.components.highlightColor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.KeepfinApplication
import app.keepfin.copies.CopiesManager
import app.keepfin.copies.CopiesRuntime
import app.keepfin.copies.CopiesUi
import app.keepfin.copies.CopyCardUi
import app.keepfin.copies.CopyDraft
import app.keepfin.copies.CopyFolderCheck
import app.keepfin.copies.CopyHoldsUi
import app.keepfin.copies.CopyPreview
import app.keepfin.copies.CopyReviewUi
import app.keepfin.copies.CopyStatus
import app.keepfin.copies.CopyVersionUi
import app.keepfin.copies.PeerAddress
import app.keepfin.copies.ReviewAction
import app.keepfin.cloud.SignInStep
import app.keepfin.core.copies.COPY_PROBLEM_RETRY
import app.keepfin.core.copies.CopyMode
import app.keepfin.core.copies.copyProblemTitle
import app.keepfin.core.spaces.keepfinFolderFor
import app.keepfin.copies.CopyProblemUi
import app.keepfin.ui.Format
import app.keepfin.ui.components.RowSeparator
import app.keepfin.ui.components.SettingsGroup
import app.keepfin.ui.components.SupportingText
import app.keepfin.ui.components.SwitchRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object CopiesTags {
    const val GROUP = "copies-group"
    const val ADD = "copies-add"
    const val CARD = "copies-card"
    const val SYNC = "copies-sync"
    const val SYNC_ALL = "copies-sync-all"
    const val REPORT = "copies-report"
    const val PAUSE = "copies-pause"
    const val REVIEW = "copies-review"
    const val WIZARD = "copies-wizard"
    const val PICK = "copies-pick"
    const val NAME = "copies-name"
    const val NEXT = "copies-next"
    const val CONFIRM = "copies-confirm"
    const val CANCEL = "copies-cancel"
    const val RESTORE = "copies-restore"
    const val CLEANUP = "copies-cleanup"
    const val EMPTY = "copies-empty"
    const val PRESELECT = "copies-preselect"

    /** The Music videos switch of a copy's settings and of the wizard's "What should it hold?" step. */
    const val MUSIC_VIDEOS = "copies-music-videos"
    const val WIZARD_MUSIC_VIDEOS = "copies-wizard-music-videos"
    const val WIZARD_COMPANIONS = "copies-wizard-companions"

    /** The wizard's note that Keepfin uses a folder as it is, for a folder that already holds other things. */
    const val FOLDER_NOTE = "copies-folder-note"

    /** The wizard's "A paired device" choice, one row per device (`DEVICE_ROW-<deviceId>`), and the card's device line. */
    const val DEVICE = "copies-device"
    const val DEVICE_ROW = "copies-device-row"
    const val DEVICE_LINE = "copies-device-line"

    /** The alert for files that could not be copied, its Try again button, and the toggle that lists the files. */
    const val PROBLEM = "copies-problem"
    const val PROBLEM_RETRY = "copies-problem-retry"
    const val PROBLEM_FILES = "copies-problem-files"

    /** The block shown while a copy is being removed (or could not be) and its Try again button. */
    const val REMOVAL = "copies-removal"
    const val REMOVE_RETRY = "copies-remove-retry"
}

private const val MUSIC_VIDEOS_NOTE = "Each song's music video goes with it."
private const val COMPANIONS_NOTE = "The files beside each song and video go with it"

/** What the screens do. The manager implements them on a phone; tests pass fakes. */
interface CopiesActions {
    fun sync(placeId: String)
    fun pause(placeId: String)
    fun syncAll()
    fun remove(placeId: String)
    fun resolve(reviewId: String, action: ReviewAction)
    fun edit(placeId: String, name: String? = null, holds: CopyHoldsUi? = null, everyHours: Int? = null, auto: Boolean? = null, exports: Triple<Boolean, Boolean, Boolean>? = null)
    fun reconnect(placeId: String, treeUri: String): String?
    fun checkFolder(treeUri: String): CopyFolderCheck
    fun preview(draft: CopyDraft, previousId: String?): CopyPreview
    fun cancelPreview(placeId: String)
    fun add(placeId: String)
    fun versions(placeId: String): List<CopyVersionUi>
    fun restore(placeId: String, entryId: String)
    fun cleanup(placeId: String)

    /** What Free up space… would delete (a fake without it counts the listed versions). */
    fun versionStats(placeId: String): app.keepfin.copies.CopyVersionStats = versions(placeId).let { v -> app.keepfin.copies.CopyVersionStats(v.size, v.count { it.deleted }) }

    /** Lyrics, subtitles, info files and covers go with their items (a screen without it shows the switch and does nothing). */
    fun setCompanions(placeId: String, on: Boolean) {}

    /** Each song's music video goes with it (a screen without it shows the switch and does nothing). */
    fun setMusicVideos(placeId: String, on: Boolean) {}

    /** Cloud accounts (null: this screen offers no cloud path). */
    val cloud: app.keepfin.cloud.CloudActions? get() = null
}

internal class ManagerActions(private val m: CopiesManager) : CopiesActions {
    override fun sync(placeId: String) { m.syncByPerson(listOf(placeId)) }
    override fun pause(placeId: String) = m.pause(placeId)
    override fun syncAll() = m.syncAll()
    override fun remove(placeId: String) = m.remove(placeId)
    override fun resolve(reviewId: String, action: ReviewAction) = m.resolve(reviewId, action)
    override fun edit(placeId: String, name: String?, holds: CopyHoldsUi?, everyHours: Int?, auto: Boolean?, exports: Triple<Boolean, Boolean, Boolean>?) = m.edit(placeId, name, holds, everyHours, auto, exports)
    override fun setCompanions(placeId: String, on: Boolean) = m.setCompanions(placeId, on)
    override fun setMusicVideos(placeId: String, on: Boolean) = m.setMusicVideos(placeId, on)
    override fun reconnect(placeId: String, treeUri: String) = m.reconnect(placeId, treeUri)
    override fun checkFolder(treeUri: String) = m.checkFolder(treeUri)
    override fun preview(draft: CopyDraft, previousId: String?) = m.preview(draft, previousId)
    override fun cancelPreview(placeId: String) = m.cancelPreview(placeId)
    override fun add(placeId: String) = m.add(placeId)
    override fun versions(placeId: String) = m.versions(placeId)
    override fun restore(placeId: String, entryId: String) = m.restore(placeId, entryId)
    override fun cleanup(placeId: String) = m.cleanup(placeId)
    override fun versionStats(placeId: String) = m.versionStats(placeId)
    override val cloud get() = CopiesRuntime.cloud
}

/** Settings > Storage > Copies, wired to the app's manager. Nothing shows without a Keepfin folder. */
@Composable
internal fun CopiesGroup(keepSpaces: Set<String>? = null, onKeepHandled: () -> Unit = {}, focusCopy: String? = null) {
    val app = LocalContext.current.applicationContext as? KeepfinApplication ?: return
    val manager = remember { CopiesRuntime.of(app) } ?: return
    LaunchedEffect(manager) {
        withContext(Dispatchers.IO) { manager.start() }
        CopiesRuntime.schedule(app)
        while (true) {
            withContext(Dispatchers.IO) { runCatching { manager.tick() } }
            kotlinx.coroutines.delay(5_000)
        }
    }
    val ui by manager.state.collectAsStateWithLifecycle()
    CopiesContent(ui, remember(manager) { ManagerActions(manager) }, keepSpaces, onKeepHandled, focusCopy)
}

@Composable
fun CopiesContent(ui: CopiesUi, actions: CopiesActions, keepSpaces: Set<String>? = null, onKeepHandled: () -> Unit = {}, focusCopy: String? = null) {
    val scope = rememberCoroutineScope()
    var wizard by remember { mutableStateOf(false) }
    // Where your files are: Keep a copy... opens Add a copy with the spaces that have files on one place only already chosen
    var preselect by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(keepSpaces) { if (keepSpaces != null) { preselect = keepSpaces; wizard = true; onKeepHandled() } }
    var versionsFor by remember { mutableStateOf<CopyCardUi?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    fun io(block: () -> Unit) = scope.launch { withContext(Dispatchers.IO) { runCatching(block).onFailure { message = it.message } } }
    SettingsGroup("Copies", tag = CopiesTags.GROUP) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!ui.ready) Text("Looking at your copies…", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (ui.places.isEmpty()) {
                Text("No copies yet", fontSize = 16.sp, modifier = Modifier.testTag(CopiesTags.EMPTY))
                Text("A copy keeps your library, or part of it, on a memory card, a USB drive, another folder or a cloud account. It is checked file by file, and nothing is lost when a file changes.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            message?.let { Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.error) }
            for (card in ui.places) {
                CopyCard(card, ui, actions, focused = card.id == focusCopy, onVersions = { versionsFor = card }, onError = { message = it })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { wizard = true }, modifier = Modifier.heightIn(min = 48.dp).testTag(CopiesTags.ADD)) { Text("Add a copy") }
                if (ui.places.size > 1) OutlinedButton(onClick = { io(actions::syncAll) }, enabled = !ui.syncing, modifier = Modifier.heightIn(min = 48.dp).testTag(CopiesTags.SYNC_ALL)) { Text(if (ui.syncing) "Syncing…" else "Sync all") }
            }
            // what the last Sync all or Sync now did, also sent as a notification
            ui.report?.takeIf { !ui.syncing }?.let { r ->
                Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag(CopiesTags.REPORT)) {
                    Text(r.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (r.warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    Text(r.detail, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        actions.cloud?.let { CloudAccountsSection(it, onError = { e -> message = e }) }
        if (ui.review.isNotEmpty()) {
            RowSeparator()
            NeedsReview(ui.review, actions, onError = { message = it })
        }
    }
    if (wizard) AddCopyWizard(ui, actions, preselect, onDone = { wizard = false; preselect = emptySet() })
    versionsFor?.let { VersionsDialog(it, actions, onClose = { versionsFor = null }) }
}

private fun modeWords(m: CopyMode) = when (m) {
    CopyMode.TwoWay -> "Two-way: changes on either side reach the other"
    CopyMode.Backup -> "Backup: keeps earlier versions and what you delete"
    CopyMode.Mirror -> "Mirror: an exact copy; removed files wait 30 days in the trash"
}

private fun holdsWords(h: CopyHoldsUi, ui: CopiesUi): String {
    if (h.all) return "Holds everything"
    val names = ArrayList<String>()
    for (id in h.spaces) names.add(ui.spaces.firstOrNull { it.first == id }?.second ?: "A space")
    if (h.favourites) names.add("Favourites")
    for (id in h.playlists) names.add(ui.playlists.firstOrNull { it.first == id }?.second ?: "A playlist")
    return if (names.isEmpty()) "Holds nothing yet" else "Holds " + names.joinToString(", ")
}

private fun statusWords(c: CopyCardUi): String = if (c.removal?.failed == false) "Removing" else when (c.status) {
    CopyStatus.NEW -> "Not synced yet"
    CopyStatus.PREVIEW -> "Check the first sync"
    CopyStatus.SYNCING -> "Syncing"
    CopyStatus.UP_TO_DATE -> "Up to date"
    CopyStatus.WAITING -> "${c.waiting} files waiting"
    CopyStatus.REVIEW -> "Needs your review"
    CopyStatus.PROBLEM -> c.problem?.short ?: "Some files not copied"
    CopyStatus.HELD -> "A large change is waiting for you"
    CopyStatus.DISCONNECTED -> "Not connected"
    CopyStatus.PAUSED -> "Paused"
    CopyStatus.REFUSED -> "Cannot be used"
}

/** Files that could not be copied: what stopped them in plain words, that Keepfin tries again, and the files behind a toggle. */
@Composable
private fun ProblemBlock(p: CopyProblemUi, canRetry: Boolean, onRetry: () -> Unit) {
    var showFiles by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag(CopiesTags.PROBLEM), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(copyProblemTitle(p.count), fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
        val counted = p.reasons.size > 1 && p.more == 0
        for (r in p.reasons) Text(if (counted) "${r.text} (${r.count} ${if (r.count == 1) "file" else "files"})" else r.text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(COPY_PROBLEM_RETRY, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canRetry) OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag(CopiesTags.PROBLEM_RETRY)) { Text("Try again") }
            TextButton(onClick = { showFiles = !showFiles }, modifier = Modifier.heightIn(min = 48.dp).testTag(CopiesTags.PROBLEM_FILES)) { Text(if (showFiles) "Hide the files" else "Show the files") }
        }
        if (showFiles) {
            for (f in p.files) Text(f, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            if (p.more > 0) Text("and ${p.more} more ${if (p.more == 1) "file" else "files"}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CopyCard(c: CopyCardUi, ui: CopiesUi, actions: CopiesActions, focused: Boolean, onVersions: () -> Unit, onError: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmFreeUp by remember { mutableStateOf(false) }
    fun io(block: () -> Unit) = scope.launch { withContext(Dispatchers.IO) { runCatching(block).onFailure { onError(it.message) } } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch { onError(withContext(Dispatchers.IO) { runCatching { actions.reconnect(c.id, uri.toString()) }.getOrElse { it.message } }) }
    }
    val ctx = LocalContext.current
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(focused) { if (focused) bring.bringIntoView() }
    Column(
        Modifier.fillMaxWidth().bringIntoViewRequester(bring).then(if (focused) Modifier.background(highlightColor(true), androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) else Modifier)
            .testTag(CopiesTags.CARD).semantics(mergeDescendants = true) { contentDescription = "${c.name}. ${statusWords(c)}"; if (focused) stateDescription = "Opened from Where your files are" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(c.name, fontSize = 17.sp, style = MaterialTheme.typography.titleMedium)
        Text(modeWords(c.mode), fontSize = 14.sp)
        Text(holdsWords(c.holds, ui), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (c.folderLabel.isNotEmpty()) {
            // where the copy is and how much of it is there: "Card 1A2B-3C4D / Music · 45 of 95 files"
            val total = c.kept + c.waiting
            val how = if (c.peer != null || total == 0) null else if (c.waiting == 0) "$total files" else "${c.kept} of $total files"
            Text(if (c.peer != null) "On ${c.peer.deviceName}" else listOfNotNull(c.folderLabel, how).joinToString(" · "), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        c.peer?.let { d ->
            Text(
                if (d.waiting == null) "${d.deviceName} is connected" else d.waiting,
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(CopiesTags.DEVICE_LINE),
            )
        }
        val line = buildList {
            add(statusWords(c))
            c.lastVerifiedAt?.let { add("Last checked ${Format.relativeDay(it)}") }
            if (c.status != CopyStatus.WAITING && c.waiting > 0) add("${c.waiting} waiting")
            if (c.kept > 0) add("${c.kept} kept")
            c.freeBytes?.let { add("${Format.bytes(it)} free") }
        }.joinToString(" · ")
        Text(line, fontSize = 14.sp, color = if (c.status == CopyStatus.REVIEW || c.status == CopyStatus.PROBLEM || c.status == CopyStatus.REFUSED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        c.statusText?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val removing = c.removal?.failed == false
        c.removal?.let { rm ->
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag(CopiesTags.REMOVAL), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (rm.failed) {
                    Text("This copy was not removed", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                    Text(rm.text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { confirmRemove = true }, modifier = Modifier.heightIn(min = 48.dp).testTag(CopiesTags.REMOVE_RETRY)) { Text("Try again") }
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text("Removing this copy: ${rm.text}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        c.problem?.takeIf { c.status != CopyStatus.SYNCING }?.let { ProblemBlock(it, c.connected) { io { actions.sync(c.id) } } }
        c.progress?.let { p ->
            LinearProgressIndicator(progress = { if (p.total > 0) p.done.toFloat() / p.total else 0f }, modifier = Modifier.fillMaxWidth())
            Text("${p.done} of ${p.total} files", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val cloudActions = actions.cloud
        val signInAgain = if (cloudActions != null) rememberCloudSignIn(cloudActions, scope) { step -> if (step is SignInStep.Failed) onError(step.message) else { onError(null); io { actions.sync(c.id) } } } else null
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (c.status == CopyStatus.SYNCING) OutlinedButton(onClick = { actions.pause(c.id) }, modifier = Modifier.heightIn(min = 48.dp).testTag(CopiesTags.PAUSE)) { Text("Pause") }
            else if (c.connected) Button(onClick = { io { actions.sync(c.id) } }, enabled = !removing, modifier = Modifier.heightIn(min = 48.dp).testTag(CopiesTags.SYNC)) { Text("Sync now") }
            else if (c.cloud != null && c.cloud.needsSignIn) {
                val provider = cloudActions?.state?.value?.accounts?.firstOrNull { it.id == c.cloud.accountId }?.provider
                if (signInAgain != null && provider != null && provider != "webdav") Button(onClick = { signInAgain(provider, c.cloud.accountId) }, modifier = Modifier.heightIn(min = 48.dp).testTag(CloudTags.SIGN_IN_AGAIN)) { Text("Sign in again") }
                else Text("Sign in again from Cloud accounts below.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (c.cloud != null || c.peer != null) OutlinedButton(onClick = { io { actions.sync(c.id) } }, modifier = Modifier.heightIn(min = 48.dp).testTag(CopiesTags.SYNC)) { Text("Try now") }
            else OutlinedButton(onClick = { picker.launch(null) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose its folder") }
            if (c.mode == CopyMode.Backup && c.connected && c.peer == null) {
                OutlinedButton(onClick = onVersions, modifier = Modifier.heightIn(min = 48.dp).testTag(CopiesTags.RESTORE)) { Text("Restore") }
                OutlinedButton(onClick = { confirmFreeUp = true }, enabled = !removing, modifier = Modifier.heightIn(min = 48.dp).testTag(CopiesTags.CLEANUP)) { Text(app.keepfin.core.copies.CopyActions.FREE_UP) }
            }
        }
        if (c.connected) {
            SwitchRow(
                "Sync by itself", c.auto, { v -> io { actions.edit(c.id, auto = v) } },
                subtitle = if (c.peer != null) "When your library changes, and when the device is back. Files wait for Wi-Fi unless that device is allowed to use mobile data"
                else if (c.cloud != null) "When your library changes. Only on Wi-Fi unless the account allows mobile data" else "When the card or folder is back, and when your library changes",
            )
            SwitchRow("Lyrics, subtitles and info files", c.companions, { v -> io { actions.setCompanions(c.id, v) } }, subtitle = "The files beside each song and video go with it")
            SwitchRow("Music videos", c.musicVideos, { v -> io { actions.setMusicVideos(c.id, v) } }, subtitle = MUSIC_VIDEOS_NOTE, tag = CopiesTags.MUSIC_VIDEOS)
            if (c.cloud == null && c.peer == null) SwitchRow("Playlist files", c.playlistFiles, { v -> io { actions.edit(c.id, exports = Triple(v, c.flat, c.mp3)) } }, subtitle = "A .m3u8 file for each playlist")
            if (c.cloud == null && c.peer == null) SwitchRow("One flat folder", c.flat, { v -> io { actions.edit(c.id, exports = Triple(c.playlistFiles, v, c.mp3)) } }, subtitle = "Every file again in one folder, for simple players")
            if (c.cloud == null && c.peer == null) SwitchRow("MP3 versions", c.mp3, { v -> io { actions.edit(c.id, exports = Triple(c.playlistFiles, c.flat, v)) } }, subtitle = "Songs in other formats, converted once to MP3")
        }
        TextButton(onClick = { confirmRemove = true }, enabled = !removing, modifier = Modifier.heightIn(min = 48.dp).testTag(CopyConfirmTags.REMOVE)) { Text(app.keepfin.core.copies.CopyActions.REMOVE) }
    }
    if (confirmFreeUp) FreeUpDialog(c, actions, onConfirm = { confirmFreeUp = false; io { actions.cleanup(c.id) } }, onClose = { confirmFreeUp = false })
    if (confirmRemove) RemoveCopyDialog(c, onConfirm = { confirmRemove = false; io { actions.remove(c.id) } }, onClose = { confirmRemove = false })
    RowSeparator()
}

private fun actionWords(a: ReviewAction) = when (a) {
    ReviewAction.CONFIRM_DELETE -> "Delete everywhere"
    ReviewAction.RESTORE -> "Keep it"
    ReviewAction.KEEP_BOTH -> "Keep both"
    ReviewAction.DISMISS -> "Got it"
    ReviewAction.SAME_DRIVE -> "Same drive"
    ReviewAction.SEPARATE_COPY -> "Treat as separate copy"
    ReviewAction.CONFIRM_HELD -> "Go ahead"
    ReviewAction.TAKE_MAIN -> "Keep the library's"
    ReviewAction.TAKE_COPY -> "Keep the copy's"
}

@Composable
private fun NeedsReview(items: List<CopyReviewUi>, actions: CopiesActions, onError: (String?) -> Unit) {
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag(CopiesTags.REVIEW), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Needs review", fontSize = 17.sp, style = MaterialTheme.typography.titleMedium)
        for (r in items.take(50)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(r.title, fontSize = 15.sp)
                Text("${r.placeName}. ${r.detail}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (a in r.actions) OutlinedButton(
                        onClick = { scope.launch { withContext(Dispatchers.IO) { runCatching { actions.resolve(r.id, a) }.onFailure { onError(it.message) } } } },
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(actionWords(a)) }
                }
            }
        }
        if (items.size > 50) SupportingText("${items.size - 50} more")
    }
}

@Composable
private fun VersionsDialog(card: CopyCardUi, actions: CopiesActions, onClose: () -> Unit) {
    var list by remember { mutableStateOf<List<CopyVersionUi>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(card.id) { list = withContext(Dispatchers.IO) { runCatching { actions.versions(card.id) }.getOrDefault(emptyList()) } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Earlier versions on ${card.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val l = list
                if (l == null) Text("Looking…") else if (l.isEmpty()) Text("Nothing has been replaced or removed yet.")
                for (v in l.orEmpty()) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(v.name, fontSize = 14.sp)
                        Text((if (v.deleted) "Deleted " else "Replaced ") + Format.relativeDay(v.archivedAt), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { scope.launch { note = withContext(Dispatchers.IO) { runCatching { actions.restore(card.id, v.id); "Restored ${v.name}" }.getOrElse { it.message } } } }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Restore") }
                }
                note?.let { Text(it, fontSize = 13.sp) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

private enum class Step { FOLDER, WHAT, HOW, PREVIEW }

@Composable
private fun AddCopyWizard(ui: CopiesUi, actions: CopiesActions, preselect: Set<String>, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var step by remember { mutableStateOf(Step.FOLDER) }
    var cloudOpen by remember { mutableStateOf(false) }
    var deviceOpen by remember { mutableStateOf(false) }
    // a cloud sign-in found no copy on the account: move on to the next page as soon as the place has been checked
    var carryOn by remember { mutableStateOf(false) }
    var tree by remember { mutableStateOf<String?>(null) }
    var check by remember { mutableStateOf<CopyFolderCheck?>(null) }
    var name by remember { mutableStateOf("") }
    val chosen = preselect.filter { id -> ui.spaces.any { it.first == id } }.toSet()
    var all by remember { mutableStateOf(chosen.isEmpty()) }
    var spaces by remember { mutableStateOf(chosen) }
    var playlists by remember { mutableStateOf(emptySet<String>()) }
    var favourites by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(CopyMode.Backup) }
    var musicVideos by remember { mutableStateOf<Boolean?>(null) }
    var companions by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf<CopyPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            tree = uri.toString()
            scope.launch {
                busy = true
                val c = withContext(Dispatchers.IO) { actions.checkFolder(uri.toString()) }
                check = c
                if (c.ok && name.isBlank()) name = c.suggestedName
                busy = false
            }
        }
    }
    LaunchedEffect(carryOn, check, busy, step) {
        if (carryOn && step == Step.FOLDER && !busy) {
            val c = check
            if (c != null && c.ok) { carryOn = false; step = Step.WHAT } else if (c != null) carryOn = false
        }
    }
    fun draft() = CopyDraft(tree!!, name.trim().ifEmpty { "Copy" }, mode, CopyHoldsUi(all, if (all) emptySet() else spaces, if (all) emptySet() else playlists, !all && favourites), musicVideos = musicVideos, companions = companions)
    fun leave(cancel: Boolean) {
        val id = preview?.placeId
        if (cancel && !id.isNullOrEmpty()) scope.launch(Dispatchers.IO) { runCatching { actions.cancelPreview(id) } }
        onDone()
    }
    Dialog(onDismissRequest = { leave(true) }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        androidx.compose.material3.Surface(Modifier.fillMaxWidth().padding(16.dp).testTag(CopiesTags.WIZARD), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Add a copy", fontSize = 20.sp, style = MaterialTheme.typography.titleLarge)
                when (step) {
                    Step.FOLDER -> {
                        if (chosen.isNotEmpty()) Text(
                            "Keeping a copy of " + chosen.joinToString(", ") { id -> ui.spaces.first { it.first == id }.second } + ". You can change this on the next step.",
                            fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(CopiesTags.PRESELECT),
                        )
                        val cloudActions = actions.cloud
                        Text(
                            if (cloudActions != null) "Choose where the copy goes: a folder on a memory card, a USB drive or anywhere on this phone, or a cloud account. A folder cannot be inside your Keepfin folder."
                            else "Choose where the copy goes: a folder on a memory card, a USB drive or anywhere on this phone. It cannot be inside your Keepfin folder.",
                            fontSize = 14.sp,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { cloudOpen = false; deviceOpen = false; picker.launch(DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:")) }, modifier = Modifier.heightIn(min = 48.dp).testTag(CopiesTags.PICK)) { Text(if (tree == null || cloudOpen || deviceOpen) "Choose a folder" else "Choose another folder") }
                            if (cloudActions != null) OutlinedButton(onClick = { cloudOpen = !cloudOpen; deviceOpen = false; if (cloudOpen) { tree = null; check = null } }, modifier = Modifier.heightIn(min = 48.dp).testTag(CloudTags.CHOICE + "-open")) { Text("A cloud account") }
                            if (ui.devices.isNotEmpty()) OutlinedButton(onClick = { deviceOpen = !deviceOpen; cloudOpen = false; if (deviceOpen) { tree = null; check = null } }, modifier = Modifier.heightIn(min = 48.dp).testTag(CopiesTags.DEVICE)) { Text("A paired device") }
                        }
                        if (deviceOpen && ui.devices.isNotEmpty()) {
                            Text("The copy is kept in the Keepfin folder of another device of yours. That device does the writing and keeps whatever it replaces.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            for (d in ui.devices) {
                                val address = PeerAddress.make(d.deviceId)
                                val choose = {
                                    tree = address
                                    scope.launch {
                                        busy = true
                                        val c = withContext(Dispatchers.IO) { runCatching { actions.checkFolder(address) }.getOrElse { CopyFolderCheck(false, it.message) } }
                                        if (tree == address) { check = c; if (c.ok && name.isBlank()) name = "Copy on ${d.name}" }
                                        busy = false
                                    }
                                    Unit
                                }
                                Row(
                                    Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = tree == address, enabled = d.allowed && !busy, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = choose)
                                        .testTag(CopiesTags.DEVICE_ROW + "-" + d.deviceId),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(tree == address, onClick = null, enabled = d.allowed && !busy)
                                    Column(Modifier.weight(1f)) {
                                        Text(d.name, fontSize = 15.sp)
                                        Text(if (d.allowed) "Connected" else d.why ?: "Not connected right now.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        if (cloudOpen && cloudActions != null) {
                            CloudPlaceChooser(
                                cloudActions,
                                onNoCopyFound = { carryOn = true },
                                onAddress = { address, suggested ->
                                    if (address == null) { tree = null; check = null }
                                    else if (address != tree) {
                                        tree = address
                                        check = null
                                        scope.launch {
                                            busy = true
                                            val c = withContext(Dispatchers.IO) { runCatching { actions.checkFolder(address) }.getOrElse { CopyFolderCheck(false, it.message) } }
                                            if (tree == address) { check = c; if (c.ok && name.isBlank()) name = suggested }
                                            busy = false
                                        }
                                    }
                                },
                                onAttach = { placeId, address ->
                                    scope.launch {
                                        busy = true
                                        val problem = withContext(Dispatchers.IO) { runCatching { actions.reconnect(placeId, address) }.getOrElse { it.message } }
                                        busy = false
                                        if (problem == null) onDone() else error = problem
                                    }
                                },
                            )
                        }
                        check?.let { c ->
                            if (!c.ok) Text(c.problem ?: "This folder cannot be used.", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                            else {
                                Text(if (PeerAddress.isPeer(tree)) "Keepfin will send the files that device is missing, and show you every difference before anything is replaced." else if (c.isCopy) "This folder already holds a copy of your library." else if (c.existingFiles > 0) "This folder already has ${c.existingFiles} items. Keepfin will only add files first, and show you every difference." else "An empty folder: a good place for a copy.", fontSize = 14.sp)
                                // the PC makes a Keepfin folder inside any other folder (core keepfinFolderFor); a phone folder is used as picked, so the note says how to keep the copy apart
                                if (c.existingFiles > 0 && !PeerAddress.isPeer(tree) && keepfinFolderFor(c.suggestedName, c.isCopy) != c.suggestedName) {
                                    Text("Keepfin uses this folder as it is. To keep the copy apart from what is already here, choose or create a folder named Keepfin.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(CopiesTags.FOLDER_NOTE))
                                }
                                c.freeBytes?.let { Text("${Format.bytes(it)} free", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(CopiesTags.NAME))
                            }
                        }
                    }
                    Step.WHAT -> {
                        Text("What should it hold?", fontSize = 16.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) { RadioButton(all, { all = true }); Text("Everything in my library") }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) { RadioButton(!all, { all = false }); Text("Only some of it") }
                        if (!all) {
                            for ((id, n) in ui.spaces) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) { Checkbox(id in spaces, { v -> spaces = if (v) spaces + id else spaces - id }); Text(n) }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) { Checkbox(favourites, { favourites = it }); Text("Favourites") }
                            for ((id, n) in ui.playlists) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) { Checkbox(id in playlists, { v -> playlists = if (v) playlists + id else playlists - id }); Text("Playlist: $n") }
                        }
                        SwitchRow("Lyrics, subtitles and info files", companions, { companions = it }, subtitle = COMPANIONS_NOTE, tag = CopiesTags.WIZARD_COMPANIONS)
                        SwitchRow("Music videos", musicVideos ?: all, { musicVideos = it }, subtitle = MUSIC_VIDEOS_NOTE, tag = CopiesTags.WIZARD_MUSIC_VIDEOS)
                    }
                    Step.HOW -> {
                        Text("How should it stay up to date?", fontSize = 16.sp)
                        for (m in CopyMode.entries) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { RadioButton(mode == m, { mode = m }); Text(modeWords(m), fontSize = 14.sp) }
                    }
                    Step.PREVIEW -> {
                        val p = preview
                        if (busy || p == null) Text("Checking what would happen…") else {
                            if (p.status != "ready" && p.status != "held") Text(p.reason ?: "This copy cannot be started.", color = MaterialTheme.colorScheme.error)
                            else {
                                Text("The first sync only adds files. Nothing is removed or replaced until you confirm.", fontSize = 14.sp)
                                if (p.lines.isEmpty()) Text("Nothing to copy: the folder already matches.", fontSize = 14.sp)
                                for (l in p.lines) Text("${l.label}: ${l.files} files (${Format.bytes(l.bytes)})", fontSize = 14.sp)
                                if (p.enough == false) Text("The copy does not have enough room.", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                                if (p.differences.isNotEmpty()) {
                                    Text("Differences found", fontSize = 15.sp)
                                    for ((path, why) in p.differences) Text("$path: $why", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (p.differencesMore > 0) Text("and ${p.differencesMore} more", fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { if (step == Step.FOLDER) leave(true) else step = Step.entries[step.ordinal - 1] }, modifier = Modifier.heightIn(min = 48.dp).testTag(CopiesTags.CANCEL)) { Text(if (step == Step.FOLDER) "Cancel" else "Back") }
                    if (step != Step.PREVIEW) {
                        Button(
                            enabled = !busy && (step != Step.FOLDER || check?.ok == true) && (step != Step.WHAT || all || spaces.isNotEmpty() || playlists.isNotEmpty() || favourites),
                            onClick = {
                                if (step == Step.HOW) {
                                    step = Step.PREVIEW
                                    scope.launch {
                                        busy = true
                                        error = null
                                        preview = withContext(Dispatchers.IO) { runCatching { actions.preview(draft(), preview?.placeId) }.getOrElse { CopyPreview("", "refused", it.message) } }
                                        busy = false
                                    }
                                } else step = Step.entries[step.ordinal + 1]
                            },
                            modifier = Modifier.heightIn(min = 48.dp).testTag(CopiesTags.NEXT),
                        ) { Text("Next") }
                    } else {
                        val p = preview
                        Button(
                            enabled = !busy && p != null && (p.status == "ready" || p.status == "held") && p.enough != false,
                            onClick = {
                                scope.launch {
                                    busy = true
                                    val e = withContext(Dispatchers.IO) { runCatching { actions.add(p!!.placeId) }.exceptionOrNull() }
                                    busy = false
                                    if (e != null) error = e.message else onDone()
                                }
                            },
                            modifier = Modifier.heightIn(min = 48.dp).testTag(CopiesTags.CONFIRM),
                        ) { Text(if (p?.status == "held") "Confirm and start" else "Start the first sync") }
                    }
                }
            }
        }
    }
}

