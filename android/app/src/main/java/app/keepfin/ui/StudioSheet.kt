package app.keepfin.ui

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Every bottom sheet of the app: a [ModalBottomSheet] that stops under the status bar. A sheet taller than the screen otherwise
 * reaches the top, where Material pads its content by the part of the status bar it covers; that padding moves the sheet, the move
 * changes the padding, and the sheet jumps up and down while it is dragged to the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest, modifier = modifier.statusBarsPadding(), sheetState = sheetState,
        dragHandle = dragHandle, contentWindowInsets = contentWindowInsets, content = content,
    )
}
