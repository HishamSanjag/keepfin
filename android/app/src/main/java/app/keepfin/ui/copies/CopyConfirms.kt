package app.keepfin.ui.copies

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.keepfin.copies.CopyCardUi
import app.keepfin.copies.CopyVersionStats
import app.keepfin.core.copies.CopyActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Tags of the two questions a copy asks (the words are the shared core's [CopyActions], the same as on Windows). */
object CopyConfirmTags {
    const val FREE_UP_DIALOG = "copies-free-up-dialog"
    const val FREE_UP_CONFIRM = "copies-free-up-confirm"
    const val REMOVE = "copies-remove"
    const val REMOVE_DIALOG = "copies-remove-dialog"
    const val REMOVE_CONFIRM = "copies-remove-confirm"
    const val CANCEL = "copies-confirm-cancel"
}

/**
 * Free up space… on a backup: says what would be deleted (earlier versions, files kept after you deleted them from your library) and
 * that the library is not touched; with nothing to free it says so and only offers OK. Asks once, in its own dialog.
 */
@Composable
internal fun FreeUpDialog(card: CopyCardUi, actions: CopiesActions, onConfirm: () -> Unit, onClose: () -> Unit) {
    var stats by remember { mutableStateOf<CopyVersionStats?>(null) }
    LaunchedEffect(card.id) { stats = withContext(Dispatchers.IO) { runCatching { actions.versionStats(card.id) }.getOrDefault(CopyVersionStats(0, 0)) } }
    val s = stats
    val nothing = s != null && CopyActions.nothingToFree(s.versions, s.deletedFiles)
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag(CopyConfirmTags.FREE_UP_DIALOG),
        title = { Text(CopyActions.freeUpTitle(card.name)) },
        text = {
            Column {
                when {
                    s == null -> Text("Looking…")
                    nothing -> Text(CopyActions.NOTHING_TO_FREE)
                    else -> {
                        Text(CopyActions.freeUpSummary(s.versions, s.deletedFiles))
                        Text(CopyActions.FREE_UP_KEEPS)
                    }
                }
            }
        },
        confirmButton = {
            if (nothing) TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag(CopyConfirmTags.CANCEL)) { Text(CopyActions.OK) }
            else TextButton(onClick = onConfirm, enabled = s != null, modifier = Modifier.heightIn(min = 48.dp).testTag(CopyConfirmTags.FREE_UP_CONFIRM)) {
                Text(CopyActions.FREE_UP_CONFIRM, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = if (nothing) null else ({ TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag(CopyConfirmTags.CANCEL)) { Text(CopyActions.CANCEL) } }),
    )
}

/**
 * Remove copy…: Keepfin stops keeping the copy up to date, and its files stay where they are (a phone has no Recycle Bin, so there is
 * no "also move its files" choice; the PC offers that only for a copy in a folder).
 */
@Composable
internal fun RemoveCopyDialog(card: CopyCardUi, onConfirm: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        modifier = Modifier.testTag(CopyConfirmTags.REMOVE_DIALOG),
        title = { Text(CopyActions.removeTitle(card.name)) },
        text = {
            Column {
                Text(CopyActions.REMOVE_LEAD)
                Text(CopyActions.REMOVE_FILES_STAY)
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp).testTag(CopyConfirmTags.REMOVE_CONFIRM)) {
                Text(CopyActions.REMOVE_CONFIRM, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp).testTag(CopyConfirmTags.CANCEL)) { Text(CopyActions.CANCEL) } },
    )
}
