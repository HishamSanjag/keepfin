package app.keepfin.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.keepfin.LibraryAccess
import app.keepfin.OutsideSpace
import app.keepfin.move.BringChoice
import app.keepfin.move.BringInUi
import app.keepfin.move.BringInWords
import app.keepfin.move.BringStage

object BringTags {
    const val NOTICE = "bring-notice"
    const val OPEN = "bring-open"
    const val WINDOW = "bring-window"
    const val START = "bring-start"
    const val CANCEL = "bring-cancel"
    const val PROGRESS = "bring-progress"
    const val RESULT = "bring-result"
    const val ASK = "bring-ask"
    const val CONTINUE = "bring-continue"
    const val STOP = "bring-stop"
    const val CLOSE = "bring-close"
    fun moveIn(id: String) = "bring-move-$id"
    fun leave(id: String) = "bring-leave-$id"
}

/** "N spaces are outside your Keepfin folder" (the PC's notice), with the button that opens the dialog. */
@Composable
internal fun OutsideNotice(spaces: List<OutsideSpace>, onOpen: () -> Unit) {
    if (spaces.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag(BringTags.NOTICE), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(if (spaces.size == 1) "${spaces[0].name} is outside your Keepfin folder." else "${spaces.size} spaces are outside your Keepfin folder.", fontSize = 15.sp)
        Button(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp).testTag(BringTags.OPEN)) { Text("Bring them in") }
    }
}

/**
 * Bring them in, with the PC's words: each space saves its new files in the Keepfin folder, and its files move in (each copied and checked before the
 * original goes) or Leave. [onAsk] shows Android's question about removing originals it did not make.
 */
@Composable
internal fun BringInDialog(ui: BringInUi, onStart: (List<BringChoice>) -> Unit, onResume: () -> Unit, onStop: () -> Unit, onAsk: (LibraryAccess.NeedsConfirmation) -> Unit, onClose: () -> Unit) {
    val choices = remember { mutableStateMapOf<String, Boolean>() }
    val running = ui.stage == BringStage.RUNNING
    BackHandler { if (!running) onClose() }
    Dialog(onDismissRequest = { if (!running) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize().testTag(BringTags.WINDOW), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        if (ui.spaces.size == 1 && ui.stage == BringStage.NONE) "Bring ${ui.spaces[0].name} into your Keepfin folder" else "Bring these spaces into your Keepfin folder",
                        style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() },
                    )
                    when (ui.stage) {
                        BringStage.NONE -> Choose(ui.spaces, choices)
                        BringStage.RUNNING -> Running(ui)
                        BringStage.INTERRUPTED -> Interrupted(ui)
                        BringStage.DONE -> Done(ui, onAsk)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (ui.stage) {
                        BringStage.NONE -> {
                            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(BringTags.CANCEL)) { Text("Cancel") }
                            Button(
                                onClick = { onStart(ui.spaces.map { BringChoice(it.id, choices[it.id] ?: true) }) }, enabled = ui.spaces.isNotEmpty(),
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(BringTags.START),
                            ) { Text("Start") }
                        }
                        BringStage.RUNNING -> OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(BringTags.STOP)) { Text("Stop") }
                        BringStage.INTERRUPTED -> {
                            OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(BringTags.CLOSE)) { Text("Later") }
                            Button(onClick = onResume, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(BringTags.CONTINUE)) { Text("Continue") }
                        }
                        BringStage.DONE -> Button(onClick = onClose, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(BringTags.CLOSE)) { Text("Done") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Choose(spaces: List<OutsideSpace>, choices: MutableMap<String, Boolean>) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text("Each space saves its new files in your Keepfin folder. Choose what happens to the files it already has.", fontSize = 14.sp, lineHeight = 21.sp)
    for (s in spaces) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(s.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text("Into ${s.folder} · ${s.files} ${if (s.files == 1) "file" else "files"}", fontSize = 13.sp, color = muted)
        val move = choices[s.id] ?: true
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = move, onClick = { choices[s.id] = true }, label = { Text("Move in") }, modifier = Modifier.heightIn(min = 48.dp).testTag(BringTags.moveIn(s.id)))
            FilterChip(selected = !move, onClick = { choices[s.id] = false }, label = { Text("Leave") }, modifier = Modifier.heightIn(min = 48.dp).testTag(BringTags.leave(s.id)))
        }
    }
    Text("Move in checks each copy before the original goes. Android may ask you first. Leave keeps the files where they are.", fontSize = 13.sp, lineHeight = 19.sp, color = muted)
}

@Composable
private fun Running(ui: BringInUi) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().testTag(BringTags.PROGRESS), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text("Copying and checking your files", fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        if (ui.total > 0) {
            LinearProgressIndicator(progress = { ui.done.toFloat() / ui.total }, modifier = Modifier.fillMaxWidth())
            Text("${ui.done} of ${ui.total} files", fontSize = 13.sp, color = muted)
            if (ui.current.isNotEmpty()) Text(ui.current, fontSize = 13.sp, color = muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Text("It carries on in the background, even with the screen off.", fontSize = 13.sp, color = muted)
    }
}

@Composable
private fun Interrupted(ui: BringInUi) {
    Text(ui.error ?: "Bringing your files in stopped before it finished. What was brought in is checked and stays. Continue to bring in the rest.", fontSize = 14.sp, lineHeight = 21.sp)
}

@Composable
private fun Done(ui: BringInUi, onAsk: (LibraryAccess.NeedsConfirmation) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().testTag(BringTags.RESULT), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(BringInWords.summary(ui.moved, ui.kept, ui.failed.size, ui.asks.size), fontSize = 15.sp, lineHeight = 22.sp)
        ui.failed.take(3).forEach { Text(it, fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.error) }
        ui.asks.firstOrNull()?.let { ask ->
            Text("Android needs your OK to remove the originals of ${ask.ids.size} ${if (ask.ids.size == 1) "file" else "files"}. They are already in your Keepfin folder and were checked.", fontSize = 14.sp, lineHeight = 21.sp, color = muted)
            Button(onClick = { onAsk(ask) }, modifier = Modifier.heightIn(min = 48.dp).testTag(BringTags.ASK)) { Text("Remove the originals") }
        }
    }
}
