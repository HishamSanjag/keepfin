package app.keepfin.ui.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import app.keepfin.Space
import app.keepfin.SpaceKind
import app.keepfin.ui.UiTags

/**
 * What the Save to chip needs: where the download goes now, the spaces to choose from and what to do on a pick.
 * [automatic] is set when links route to different spaces (a batch), so the chip can say so and offer to go back.
 */
class SaveToUi(
    val label: String,
    val currentId: String?,
    val spaces: List<Space>,
    val automatic: Boolean,
    val onPick: (String) -> Unit,
    val onAutomatic: (() -> Unit)?,
    val onChangeDefaults: () -> Unit,
)

/** "Save to: Music ▾". Changing it affects this download only; the menu ends with "Change defaults…". */
@Composable
fun SaveToChip(state: SaveToUi, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        AssistChip(
            onClick = { open = true },
            label = { Text("Save to: ${state.label}", maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(iconFor(state.spaces.firstOrNull { it.id == state.currentId }?.kind), contentDescription = null, modifier = Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.SAVE_TO).semantics { contentDescription = "Save to ${state.label}. Choose a space" },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (state.onAutomatic != null) DropdownMenuItem(
                text = { Text("By link type") }, leadingIcon = { if (state.automatic) Icon(Icons.Outlined.Check, null) else Icon(Icons.Outlined.Folder, null) },
                onClick = { open = false; state.onAutomatic.invoke() }, modifier = Modifier.testTag(UiTags.saveToOption("auto")),
            )
            state.spaces.forEach { space ->
                DropdownMenuItem(
                    text = { Text(space.name) },
                    leadingIcon = { if (space.id == state.currentId && !state.automatic) Icon(Icons.Outlined.Check, null) else Icon(iconFor(space.kind), null) },
                    onClick = { open = false; state.onPick(space.id) }, modifier = Modifier.testTag(UiTags.saveToOption(space.id)),
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Change defaults…") }, leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                onClick = { open = false; state.onChangeDefaults() }, modifier = Modifier.testTag(UiTags.SAVE_TO_CHANGE_DEFAULTS),
            )
        }
    }
}

private fun iconFor(kind: SpaceKind?) = when (kind) { SpaceKind.MUSIC -> Icons.Outlined.MusicNote; SpaceKind.VIDEO -> Icons.Outlined.Movie; else -> Icons.Outlined.Folder }
