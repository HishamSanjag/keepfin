package app.keepfin.ui.settings

import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.library.repo.LibraryRepository
import app.keepfin.library.repo.MediaStoreLinked
import app.keepfin.setup.StorageStatus
import app.keepfin.ui.Format
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.components.SettingsGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object StorageTags {
    const val GROUP = "storage-group"
    const val CHECK = "storage-check"
    const val REPICK = "storage-repick"
    const val STATE = "storage-state"
    const val FIND_LINKED = "storage-find-linked"
    const val LINKED = "storage-linked"
    const val EARLIER = "storage-earlier"
}

/** Settings > Storage: where the Keepfin folder is, how much room is left, and what to do when Android lost access or the card is out. */
@Composable
internal fun StorageGroup(vm: StudioViewModel) {
    val setup = vm.setup
    if (setup == null) {
        SettingsGroup("Storage", tag = StorageTags.GROUP) { Text("The Keepfin folder is not available in a preview.", fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
        return
    }
    var status by remember { mutableStateOf<StorageStatus?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) { status = withContext(Dispatchers.IO) { setup.checkStorage() } }
    // linked spaces: files that must be found again on this phone (a reinstall, or a folder opened from another phone)
    val repo = vm.lib as? LibraryRepository
    val snapshot by vm.lib.library.collectAsStateWithLifecycle()
    var linkedCount by remember { mutableIntStateOf(0) }
    var linkedMessage by remember { mutableStateOf<String?>(null) }
    var finding by remember { mutableStateOf(false) }
    // right after setup the new library may still be opening (the count throws until then): 0 for now, counted again once it has scanned
    fun countLinked(): Int = runCatching { repo?.linkedNeedingRelink() ?: 0 }.getOrDefault(0)
    LaunchedEffect(reload, snapshot.problems, snapshot.scannedAt) { linkedCount = withContext(Dispatchers.IO) { countLinked() } }
    fun find() {
        if (repo == null || finding) return
        scope.launch {
            finding = true
            linkedMessage = withContext(Dispatchers.IO) {
                runCatching {
                    if (!repo.linkedReadable()) "Keepfin needs access to music and videos to find these files. Allow it, then try again."
                    else repo.relinkLinked().let { r -> if (r.unmatched == 0) "Found all ${r.matched} linked files." else "Found ${r.matched} linked files. ${r.unmatched} could not be found; they stay in your library and can be found again later." }
                }.getOrElse { "The library is still opening. Try again in a moment." }
            }
            linkedCount = withContext(Dispatchers.IO) { countLinked() }
            finding = false
        }
    }
    val context = LocalContext.current
    val permit = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { find() }
    val onFindLinked = {
        val missing = MediaStoreLinked.missingReadPermissions(context)
        if (missing.isEmpty()) find() else permit.launch(missing.toTypedArray())
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            message = withContext(Dispatchers.IO) { setup.repick(uri) }
            if (message == null) { vm.lib.rescan(); reload++ }
        }
    }
    val initial = remember { DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:") }
    // Where your files are: a tap from Devices lands here (a copy to show, or Keep a copy... with spaces chosen)
    var focusCopy by remember { mutableStateOf<String?>(null) }
    var keepSpaces by remember { mutableStateOf<Set<String>?>(null) }
    val intent = vm.whereIntent
    LaunchedEffect(intent) {
        when (intent) {
            is app.keepfin.where.WhereIntent.Copy -> { focusCopy = intent.placeId; vm.whereConsumed() }
            is app.keepfin.where.WhereIntent.Keep -> { keepSpaces = intent.spaceIds; vm.whereConsumed() }
            else -> Unit
        }
    }
    val where = vm.whereSource
    Column {
        if (status is StorageStatus.Ok && where != null) {
            val whereUi by where.state.collectAsStateWithLifecycle()
            WhereFilesGroup(whereUi, onOpen = { tap ->
                when (tap) {
                    is app.keepfin.where.WhereTap.Device -> vm.whereOpenDevice(tap.deviceId)
                    is app.keepfin.where.WhereTap.Copy -> focusCopy = tap.placeId
                }
            }, onKeepCopy = { keepSpaces = it }, safety = true)
        }
        StorageContent(status, message, onCheck = { status = null; reload++ }, onRepick = { picker.launch(initial) },
            linkedCount = linkedCount, linkedMessage = linkedMessage, finding = finding, onFindLinked = onFindLinked, onEarlier = setup::openEarlier)
        (status as? StorageStatus.Ok)?.let { FolderLocationGroup(it.label) }
        if (status is StorageStatus.Ok) app.keepfin.ui.copies.CopiesGroup(keepSpaces, onKeepHandled = { keepSpaces = null }, focusCopy = focusCopy)
    }
}

/** Pure over the status, so tests drive every state. */
@Composable
internal fun StorageContent(
    status: StorageStatus?, message: String?, onCheck: () -> Unit, onRepick: () -> Unit,
    linkedCount: Int = 0, linkedMessage: String? = null, finding: Boolean = false, onFindLinked: () -> Unit = {}, onEarlier: (() -> Unit)? = null,
) {
    SettingsGroup("Keepfin folder", tag = StorageTags.GROUP) {
        Column(Modifier.fillMaxWidth().padding(16.dp).testTag(StorageTags.STATE), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            when (status) {
                null -> Text("Checking the Keepfin folder…", fontSize = 14.sp, color = muted)
                StorageStatus.NoFolder -> {
                    Text("No Keepfin folder yet", fontSize = 16.sp)
                    Text("Keepfin asks for a folder when it opens. Your library, playlists and history live in it.", fontSize = 14.sp, color = muted)
                }
                is StorageStatus.Ok -> {
                    Text(status.label, fontSize = 16.sp)
                    Text("${status.freeBytes?.let { Format.bytes(it) + " free" } ?: "Free space unknown"} · ${status.entries} items at the top", fontSize = 14.sp, color = muted)
                    OutlinedButton(onClick = onCheck, modifier = Modifier.heightIn(min = 48.dp).testTag(StorageTags.CHECK)) { Text("Check files") }
                    if (onEarlier != null) OutlinedButton(onClick = onEarlier, modifier = Modifier.heightIn(min = 48.dp).testTag(StorageTags.EARLIER)) { Text(app.keepfin.setup.SetupTexts.EARLIER_ENTRY) }
                }
                is StorageStatus.NeedsAccess -> {
                    Text("Keepfin needs access to this folder again", fontSize = 16.sp)
                    Text("${status.label}. Android no longer lets Keepfin use it. Nothing is missing: choose the same folder once more.", fontSize = 14.sp, color = muted)
                    Button(onClick = onRepick, modifier = Modifier.heightIn(min = 48.dp).testTag(StorageTags.REPICK)) { Text("Choose the folder again") }
                }
                is StorageStatus.Unavailable -> {
                    Text("The Keepfin folder is not available", fontSize = 16.sp)
                    Text("${status.label}. If it is on a memory card, put the card back. Nothing is missing.", fontSize = 14.sp, color = muted)
                    OutlinedButton(onClick = onCheck, modifier = Modifier.heightIn(min = 48.dp).testTag(StorageTags.CHECK)) { Text("Check again") }
                }
                is StorageStatus.Problem -> {
                    Text(status.label, fontSize = 16.sp)
                    Text(status.message, fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = onCheck, modifier = Modifier.heightIn(min = 48.dp).testTag(StorageTags.CHECK)) { Text("Check again") }
                    OutlinedButton(onClick = onRepick, modifier = Modifier.heightIn(min = 48.dp).testTag(StorageTags.REPICK)) { Text("Choose the folder again") }
                }
            }
            if (message != null) Text(message, fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
            if (status is StorageStatus.Ok && (linkedCount > 0 || linkedMessage != null)) {
                Column(Modifier.fillMaxWidth().testTag(StorageTags.LINKED), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (linkedCount > 0) {
                        Text("$linkedCount linked files need to be found again", fontSize = 16.sp)
                        Text("These files stayed in your Music, Movies or Download folders. After a reinstall Keepfin looks them up again by their folder and name. Nothing is changed or removed.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onFindLinked, enabled = !finding, modifier = Modifier.heightIn(min = 48.dp).testTag(StorageTags.FIND_LINKED)) { Text(if (finding) "Looking…" else "Find linked files") }
                    }
                    if (linkedMessage != null) Text(linkedMessage, fontSize = 14.sp)
                }
            }
        }
    }
}
