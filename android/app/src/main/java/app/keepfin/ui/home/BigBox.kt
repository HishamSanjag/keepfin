package app.keepfin.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.readClipboard
import app.keepfin.ui.theme.Studio

/**
 * Home's box: "Paste a link to download, or search". A link starts capture (the quality picker on Downloads), words open Search.
 * Pasting from the clipboard button routes immediately; typing waits for Go.
 */
@Composable
fun BigBox(vm: StudioViewModel, modifier: Modifier = Modifier, autoFocus: Boolean = false, tag: String = UiTags.HOME_BOX, hint: String = "Paste a link to download, or search", onRouted: () -> Unit = {}) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    if (autoFocus) androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    fun go(value: String) { if (vm.routeBox(value) != app.keepfin.ui.BoxRoute.NOTHING) { text = ""; onRouted() } }
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(CircleShape).background(Studio.colors.raised)
            .border(1.dp, Studio.colors.separator, CircleShape).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Outlined.Link, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) Text(hint, fontSize = 15.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = text, onValueChange = { text = it }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { go(text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag(tag).semantics { contentDescription = hint },
            )
        }
        if (text.isNotEmpty()) {
            IconButton(onClick = { text = "" }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, contentDescription = "Clear", modifier = Modifier.size(20.dp)) }
            IconButton(onClick = { go(text) }, modifier = Modifier.size(48.dp).testTag(UiTags.HOME_BOX_GO)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = "Go", tint = colors.primary)
            }
        } else IconButton(
            onClick = {
                val clip = readClipboard(context)
                if (clip.isNullOrBlank()) vm.say("The clipboard has no text to paste.") else go(clip)
            },
            modifier = Modifier.size(48.dp),
        ) { Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste from the clipboard") }
    }
}
