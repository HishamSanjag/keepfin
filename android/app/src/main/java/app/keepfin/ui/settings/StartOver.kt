package app.keepfin.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.keepfin.JobStatus
import app.keepfin.KeepfinApplication
import app.keepfin.copies.CopiesRuntime
import app.keepfin.folder.SafFolderAccess
import app.keepfin.net.NetRuntime
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.devices.ServiceDevicesController
import app.keepfin.ui.components.SettingsGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object StartOverTags {
    const val RESET = "start-over-reset"
    const val DELETE_ALL = "start-over-delete-all"
    const val FIELD = "start-over-field"
    const val CONFIRM = "start-over-confirm"
}

/** The two ways to start over (the owner's choice, 2026-10-22): both end the app, which starts fresh next time. */
internal object StartOver {
    /** Erases everything the app keeps on this phone (settings, history, pairings, copies, caches, folder access). Android ends the app. */
    fun resetApp(context: Context): Boolean = context.getSystemService(ActivityManager::class.java)?.clearApplicationUserData() == true

    /**
     * Stops everything that could write into the Keepfin folder while it is deleted: queued and running downloads are cancelled,
     * playback is cleared, copies are paused and devices are turned off. Each step is best effort; a failing one does not block the rest.
     * Call it off the main thread, after [stopPlayback] on the main thread.
     */
    fun stopWriters(vm: StudioViewModel) {
        runCatching { vm.engine.jobs.value.filter { it.status.active || it.status == JobStatus.PAUSED }.forEach { runCatching { vm.engine.cancel(it.id) } } }
        val app = vm.getApplication<android.app.Application>()
        runCatching { (app as? KeepfinApplication)?.let { CopiesRuntime.of(it)?.pause() } }
        if (NetRuntime.available && vm.setup != null) runCatching {
            val devices = ServiceDevicesController(app, vm.engine)
            devices.setKeepReachable(false)
            devices.disable()
        }
    }

    /** Stops and clears playback so no player keeps a file of the folder open. Main thread (the player controller needs it). */
    fun stopPlayback(vm: StudioViewModel) { runCatching { vm.player.clearQueue() } }

    /**
     * Deletes the Keepfin folder with everything in it (media, the library's data, its trash), then resets the app. Returns an error in
     * words when something failed, saying what state it left: when entries were deleted one by one and it failed midway, part of the
     * folder may already be gone and Keepfin was not reset; when the folder is gone but the reset failed, the folder is deleted and
     * Reset Keepfin should be tried again.
     */
    fun deleteEverything(context: Context, folderTreeUri: String?, reset: (Context) -> Boolean = ::resetApp): String? {
        if (folderTreeUri != null) {
            val tree = Uri.parse(folderTreeUri)
            val cr = context.contentResolver
            val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            // the folder itself in one call; where the storage refuses that, what is inside it, one entry at a time
            val gone = runCatching { DocumentsContract.deleteDocument(cr, root) }.getOrDefault(false)
            if (!gone) {
                try {
                    val access = SafFolderAccess.open(cr, tree)
                    for (entry in access.list("").entries) access.remove(entry.name)
                } catch (e: Exception) {
                    return "The Keepfin folder could not be fully deleted (${e.message ?: "no access"}). Part of it may already be deleted. Keepfin was not reset."
                }
            }
        }
        return if (reset(context)) null
        else if (folderTreeUri != null) "Your Keepfin folder was deleted, but Android did not let Keepfin reset itself. Try Reset Keepfin again."
        else "Android did not let Keepfin reset itself."
    }
}

/** Settings > Storage & copies and About Keepfin > Start over: Reset Keepfin (keeps the files) and Delete everything (also the Keepfin folder). */
@Composable
internal fun StartOverGroup(vm: StudioViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var asking by remember { mutableStateOf<Boolean?>(null) } // false: reset, true: delete everything
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    SettingsGroup("Start over", tag = StartOverTags.RESET + "-group") {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Reset Keepfin erases what this phone keeps: settings, download history, paired devices and copies. Your Keepfin folder stays, so your files and library come back when you open it again.", fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { asking = false }, enabled = !working, modifier = Modifier.heightIn(min = 48.dp).testTag(StartOverTags.RESET)) { Text("Reset Keepfin") }
            Text("Delete everything also deletes your Keepfin folder: every song, video and photo in it, your library and playlists. This cannot be undone.", fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(
                onClick = { asking = true }, enabled = !working, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.heightIn(min = 48.dp).testTag(StartOverTags.DELETE_ALL),
            ) { Text("Delete everything") }
            if (working) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("Deleting…", fontSize = 14.sp)
            }
            error?.let { Text(it, fontSize = 14.sp, color = MaterialTheme.colorScheme.error) }
        }
    }
    val everything = asking ?: return
    var typed by remember(everything) { mutableStateOf("") }
    val ready = typed.trim().equals("DELETE", ignoreCase = true)
    AlertDialog(
        onDismissRequest = { asking = null },
        title = { Text(if (everything) "Delete everything?" else "Reset Keepfin?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (everything) "Your Keepfin folder is deleted with every file in it, then Keepfin starts over as if newly installed. Files an earlier Keepfin saved in Music, Movies and Download stay."
                    else "Keepfin closes and starts over as if newly installed. Your Keepfin folder and its files stay; choose \"I have a Keepfin folder\" to open them again.",
                )
                Text("Type DELETE to confirm.", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                OutlinedTextField(typed, { typed = it }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(StartOverTags.FIELD))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    asking = null
                    working = true
                    error = null
                    scope.launch {
                        val folder = vm.setup?.currentProfile()?.folderTreeUri
                        if (everything) StartOver.stopPlayback(vm)
                        val failed = withContext(Dispatchers.IO) {
                            if (everything) { StartOver.stopWriters(vm); StartOver.deleteEverything(context, folder) }
                            else if (StartOver.resetApp(context)) null else "Android did not let Keepfin reset itself."
                        }
                        working = false
                        error = failed
                    }
                },
                enabled = ready, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error), modifier = Modifier.testTag(StartOverTags.CONFIRM),
            ) { Text(if (everything) "Delete everything" else "Reset") }
        },
        dismissButton = { TextButton(onClick = { asking = null }) { Text("Cancel") } },
    )
}
