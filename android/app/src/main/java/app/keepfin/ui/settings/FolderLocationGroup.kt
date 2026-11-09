package app.keepfin.ui.settings

import android.app.Activity
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.KeepfinApplication
import app.keepfin.OutsideSpace
import app.keepfin.move.BringInUi
import app.keepfin.move.BringStage
import app.keepfin.move.MoveStage
import app.keepfin.move.MoveUi
import app.keepfin.ui.components.SettingsGroup

/** Storage > Keepfin folder location: Change location, the notice for spaces outside the folder, and what an unfinished move or run needs. */
@Composable
internal fun FolderLocationGroup(currentLabel: String) {
    val app = LocalContext.current.applicationContext as KeepfinApplication
    val move by app.folderMove.ui.collectAsStateWithLifecycle()
    val bring by app.bringIn.ui.collectAsStateWithLifecycle()
    var moveOpen by remember { mutableStateOf(false) }
    var bringOpen by remember { mutableStateOf(false) }
    LaunchedEffect(bring.stage, move.stage) { app.bringIn.load() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? -> app.folderMove.check(uri) }
    val start = remember { DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:") }
    val answer = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        bring.asks.firstOrNull()?.let { app.bringIn.answered(it, result.resultCode == Activity.RESULT_OK) }
    }
    FolderLocationContent(
        move, bring.spaces, bring, onChange = { moveOpen = true }, onOpenMove = { moveOpen = true }, onOpenBring = { bringOpen = true },
        onResumeMove = app.folderMove::resume, onResumeBring = app.bringIn::resume,
    )
    if (moveOpen) MoveFolderDialog(
        move, currentLabel, onPick = { picker.launch(start) }, onBackToWhere = app.folderMove::clearCheck, onStart = app.folderMove::start, onStop = app.folderMove::stop,
        onResume = app.folderMove::resume, onDeleteOld = app.folderMove::deleteOld, onKeepOld = app.folderMove::keepOld,
        onClose = { moveOpen = false; app.folderMove.clearCheck(); app.folderMove.dismiss() },
    )
    if (bringOpen) BringInDialog(
        bring, onStart = app.bringIn::start, onResume = app.bringIn::resume, onStop = app.bringIn::requestStop,
        onAsk = { ask -> answer.launch(IntentSenderRequest.Builder(ask.intentSender).build()) }, onClose = { bringOpen = false; app.bringIn.dismiss() },
    )
}

/** Pure over the states, so tests drive every case. */
@Composable
internal fun FolderLocationContent(
    move: MoveUi, outside: List<OutsideSpace>, bring: BringInUi, onChange: () -> Unit, onOpenMove: () -> Unit, onOpenBring: () -> Unit, onResumeMove: () -> Unit, onResumeBring: () -> Unit,
) {
    SettingsGroup("Folder location") {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            Text("Move your whole Keepfin folder to another place on this phone or a memory card. Everything is copied and checked first.", fontSize = 14.sp, lineHeight = 21.sp, color = muted)
            when {
                move.stage == MoveStage.INTERRUPTED || move.stage == MoveStage.FAILED -> Column(Modifier.testTag(MoveTags.BANNER), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (move.stage == MoveStage.FAILED) "The move stopped" else "A move of your Keepfin folder is not finished", fontSize = 16.sp)
                    Text(move.message ?: "Your Keepfin folder is still where it was.", fontSize = 14.sp, color = muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onResumeMove, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (move.stage == MoveStage.FAILED) "Try again" else "Continue") }
                        OutlinedButton(onClick = onOpenMove, modifier = Modifier.heightIn(min = 48.dp).testTag(MoveTags.BANNER_OPEN)) { Text("Details") }
                    }
                }
                move.stage == MoveStage.DONE && move.oldPending -> Column(Modifier.testTag(MoveTags.BANNER), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your Keepfin folder has moved", fontSize = 16.sp)
                    Text("The old folder is still at ${move.fromLabel}. Delete it to free the space, or keep it.", fontSize = 14.sp, color = muted)
                    OutlinedButton(onClick = onOpenMove, modifier = Modifier.heightIn(min = 48.dp).testTag(MoveTags.BANNER_OPEN)) { Text("Old folder…") }
                }
                move.stage == MoveStage.RUNNING -> OutlinedButton(onClick = onOpenMove, modifier = Modifier.heightIn(min = 48.dp).testTag(MoveTags.BANNER_OPEN)) { Text("Moving… show progress") }
                else -> OutlinedButton(onClick = onChange, modifier = Modifier.heightIn(min = 48.dp).testTag(MoveTags.CHANGE)) { Text("Change location…") }
            }
            if (bring.stage == BringStage.INTERRUPTED) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Bringing files in was not finished.", fontSize = 15.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onResumeBring, modifier = Modifier.heightIn(min = 48.dp).testTag(BringTags.CONTINUE)) { Text("Continue") }
                    OutlinedButton(onClick = onOpenBring, modifier = Modifier.heightIn(min = 48.dp)) { Text("Details") }
                }
            } else if (bring.stage == BringStage.RUNNING) OutlinedButton(onClick = onOpenBring, modifier = Modifier.heightIn(min = 48.dp)) { Text("Bringing files in… show progress") }
            else if (outside.isNotEmpty()) OutsideNotice(outside, onOpenBring)
        }
    }
}
