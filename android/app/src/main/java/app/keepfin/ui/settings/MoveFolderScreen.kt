package app.keepfin.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.keepfin.move.MoveStage
import app.keepfin.move.MoveUi
import app.keepfin.ui.Format

object MoveTags {
    const val CHANGE = "move-change"
    const val WINDOW = "move-window"
    const val PICK = "move-pick"
    const val PROBLEM = "move-problem"
    const val BACK = "move-back"
    const val START = "move-start"
    const val NO_ROOM = "move-no-room"
    const val PROGRESS = "move-progress"
    const val STOP = "move-stop"
    const val STOP_CONFIRM = "move-stop-confirm"
    const val CONTINUE = "move-continue"
    const val DONE_TITLE = "move-done-title"
    const val OLD = "move-old"
    const val DELETE_OLD = "move-delete-old"
    const val DELETE_CONFIRM = "move-delete-confirm"
    const val KEEP_OLD = "move-keep-old"
    const val CLOSE = "move-close"
    const val BANNER = "move-banner"
    const val BANNER_OPEN = "move-banner-open"
}

/** "Change location…" of the Keepfin folder: Where, Review, the run and Done, in one full-screen window (the PC's Move window). */
@Composable
internal fun MoveFolderDialog(
    ui: MoveUi, currentLabel: String, onPick: () -> Unit, onBackToWhere: () -> Unit, onStart: () -> Unit, onStop: () -> Unit, onResume: () -> Unit,
    onDeleteOld: () -> Unit, onKeepOld: () -> Unit, onClose: () -> Unit,
) {
    val running = ui.stage == MoveStage.RUNNING
    BackHandler { if (!running) onClose() }
    Dialog(onDismissRequest = { if (!running) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize().testTag(MoveTags.WINDOW), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Move your Keepfin folder", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    when {
                        ui.stage == MoveStage.DONE -> DoneBody(ui, onDeleteOld, onKeepOld)
                        ui.stage != MoveStage.NONE -> RunBody(ui, onStop, onResume)
                        ui.check?.problem == null && ui.check != null -> ReviewBody(ui)
                        else -> WhereBody(ui, currentLabel)
                    }
                }
                Actions(ui, onPick, onBackToWhere, onStart, onClose)
            }
        }
    }
}

@Composable
private fun Actions(ui: MoveUi, onPick: () -> Unit, onBackToWhere: () -> Unit, onStart: () -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val check = ui.check
        when {
            ui.stage == MoveStage.DONE -> Button(onClick = onClose, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(MoveTags.CLOSE)) { Text("Done") }
            ui.stage != MoveStage.NONE -> if (ui.stage != MoveStage.RUNNING) OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(MoveTags.CLOSE)) { Text("Close") }
            check != null && check.problem == null -> {
                OutlinedButton(onClick = onBackToWhere, enabled = !ui.checking, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(MoveTags.BACK)) { Text("Back") }
                Button(onClick = onStart, enabled = check.fits, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(MoveTags.START)) { Text("Start") }
            }
            else -> {
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(MoveTags.CLOSE)) { Text("Cancel") }
                Button(onClick = onPick, enabled = !ui.checking, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(MoveTags.PICK)) { Text(if (check?.problem != null) "Choose another folder" else "Choose folder") }
            }
        }
    }
}

@Composable
private fun WhereBody(ui: MoveUi, currentLabel: String) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text("Where should your Keepfin folder go?", fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    Text("Everything in $currentLabel moves there: your files, playlists and history.", fontSize = 14.sp, lineHeight = 21.sp, color = muted)
    Text("Pick an empty folder, or make a new one in the picker. It can be on this phone or on a memory card. Folders in cloud apps such as Google Drive or Dropbox cannot be used.", fontSize = 14.sp, lineHeight = 21.sp, color = muted)
    Text("Want a second copy instead? Set up a backup in Copies.", fontSize = 14.sp, lineHeight = 21.sp, color = muted)
    if (ui.checking) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Text("Looking at the folder and counting your files…", fontSize = 14.sp)
    }
    (ui.check?.problem ?: ui.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.testTag(MoveTags.PROBLEM)) }
}

@Composable
private fun ReviewBody(ui: MoveUi) {
    val check = ui.check ?: return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text("The plan", fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
    Text("Check this over. Nothing happens until you press Start.", fontSize = 14.sp, color = muted)
    Place("From", ui.fromLabel)
    Text("Copied to the new place and every file checked. Your Keepfin folder stays where it is until everything is checked; Keepfin asks before it deletes anything.", fontSize = 14.sp, lineHeight = 21.sp)
    Place("To", check.label)
    Text("To copy: ${Format.bytes(check.bytes)} in ${check.files} files" + (check.freeBytes?.let { " · ${Format.bytes(it)} free" } ?: ""), fontSize = 14.sp, color = muted)
    Text("Keepfin pauses your library while it copies, then opens it from the new place. Downloads and edits wait until it is done. The move carries on with the screen off.", fontSize = 14.sp, lineHeight = 21.sp, color = muted)
    if (!check.fits) Text("This does not fit. There is not enough free space there. Choose a place with more room.", color = MaterialTheme.colorScheme.error, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.testTag(MoveTags.NO_ROOM))
    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, modifier = Modifier.testTag(MoveTags.PROBLEM)) }
}

@Composable
private fun Place(label: String, place: String) {
    Column {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(place, fontSize = 15.sp)
    }
}

@Composable
private fun RunBody(ui: MoveUi, onStop: () -> Unit, onResume: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val failed = ui.stage == MoveStage.FAILED
    val paused = ui.stage == MoveStage.INTERRUPTED
    var confirm by remember { mutableStateOf(false) }
    val p = ui.progress
    Text(
        if (failed) "The move stopped" else if (paused) "The move is paused" else "Moving your Keepfin folder", fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
    )
    if (ui.toLabel.isNotEmpty()) Place("To", ui.toLabel)
    Column(Modifier.fillMaxWidth().testTag(MoveTags.PROGRESS), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.stage == MoveStage.RUNNING) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    when (p?.phase) { "switching" -> "Opening the new place"; "finishing" -> "Checking the last changes"; "copying" -> "Copying and checking your files"; else -> "Getting ready…" },
                    fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                )
            }
            if (p != null && (p.phase == "copying" || p.phase == "finishing") && p.total > 0) {
                LinearProgressIndicator(progress = { if (p.bytesTotal > 0) (p.bytesDone.toFloat() / p.bytesTotal).coerceIn(0f, 1f) else p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                Text("${p.done} of ${p.total} files" + if (p.bytesTotal > 0) " · ${Format.bytes(p.bytesDone)} of ${Format.bytes(p.bytesTotal)}" else "", fontSize = 13.sp, color = muted)
                p.current?.let { Text(it, fontSize = 13.sp, color = muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
            }
            Text("Leave this window open or switch away: the move carries on in the background, even with the screen off. If Keepfin is closed, it carries on next time.", fontSize = 13.sp, lineHeight = 19.sp, color = muted)
        } else {
            ui.message?.let { Text(it, fontSize = 14.sp, lineHeight = 21.sp, color = if (failed) MaterialTheme.colorScheme.error else muted) }
            if (failed) Text("Your Keepfin folder is still where it was.", fontSize = 14.sp, color = muted)
        }
        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
    }
    if (ui.stage != MoveStage.RUNNING || p?.phase != "switching") {
        if (confirm) {
            Text("Stop now? Your Keepfin folder stays where it is, and what was copied into the new place is removed.", fontSize = 14.sp, lineHeight = 21.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirm = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep going") }
                Button(onClick = { confirm = false; onStop() }, modifier = Modifier.heightIn(min = 48.dp).testTag(MoveTags.STOP_CONFIRM)) { Text("Stop and keep it where it was") }
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.stage != MoveStage.RUNNING) Button(onClick = onResume, modifier = Modifier.heightIn(min = 48.dp).testTag(MoveTags.CONTINUE)) { Text(if (failed) "Try again" else "Continue") }
            OutlinedButton(onClick = { confirm = true }, modifier = Modifier.heightIn(min = 48.dp).testTag(MoveTags.STOP)) { Text("Stop") }
        }
    }
}

@Composable
private fun DoneBody(ui: MoveUi, onDeleteOld: () -> Unit, onKeepOld: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var confirm by remember { mutableStateOf(false) }
    Text("Your Keepfin folder has moved", fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.testTag(MoveTags.DONE_TITLE))
    Text("Every file was checked in the new place.", fontSize = 14.sp, color = muted)
    Place("Now in", ui.toLabel)
    if (ui.oldPending) Column(Modifier.fillMaxWidth().testTag(MoveTags.OLD), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("The old folder is still at ${ui.fromLabel}. It holds a full copy of everything, so it uses the same space again. Keepfin never deletes it by itself.", fontSize = 14.sp, lineHeight = 21.sp)
        if (confirm) {
            Text("Delete the old folder? Everything in it is already in the new place and was checked. This cannot be undone.", fontSize = 14.sp, lineHeight = 21.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { confirm = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                Button(onClick = { confirm = false; onDeleteOld() }, enabled = !ui.deleting, modifier = Modifier.heightIn(min = 48.dp).testTag(MoveTags.DELETE_CONFIRM)) { Text("Delete the old folder") }
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { confirm = true }, enabled = !ui.deleting, modifier = Modifier.heightIn(min = 48.dp).testTag(MoveTags.DELETE_OLD)) { Text("Delete the old folder") }
            OutlinedButton(onClick = onKeepOld, enabled = !ui.deleting, modifier = Modifier.heightIn(min = 48.dp).testTag(MoveTags.KEEP_OLD)) { Text("Keep it") }
        }
        if (ui.deleting) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Deleting the old folder…", fontSize = 14.sp)
        }
    }
    ui.note?.let { Text(it, fontSize = 14.sp, lineHeight = 21.sp, color = muted) }
    ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.testTag(MoveTags.PROBLEM)) }
    Spacer(Modifier.size(4.dp))
}
