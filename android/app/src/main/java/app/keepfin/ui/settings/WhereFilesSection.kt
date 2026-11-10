package app.keepfin.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import app.keepfin.ui.Format
import app.keepfin.ui.components.RowSeparator
import app.keepfin.where.PlaceKind
import app.keepfin.where.WhereFilesUi
import app.keepfin.where.WherePlace
import app.keepfin.where.WhereTap
import app.keepfin.where.WhereSafety
import app.keepfin.where.WhereWords

object WhereTags {
    const val SECTION = "where-files"
    const val LOADING = "where-loading"
    const val ONE_PLACE = "where-one-place"
    const val ONE_PLACE_KEEP = "where-one-place-keep"
    const val MUSIC_VIDEOS = "where-music-videos"
    const val SAFETY = "where-safety"
    const val SAFETY_KEEP = "where-safety-keep"
    fun row(id: String) = "where-row-$id"
    fun single(index: Int) = "where-single-$index"
    fun keep(index: Int) = "where-keep-$index"
}

/** The group in Settings > Storage. */
@Composable
internal fun WhereFilesGroup(ui: WhereFilesUi, onOpen: (WhereTap) -> Unit, onKeepCopy: (Set<String>) -> Unit, safety: Boolean = false) {
    app.keepfin.ui.components.SettingsGroup(WhereWords.TITLE, tag = "where-files-group") { WhereFilesBody(ui, onOpen, onKeepCopy, safety) }
}

internal fun placeIcon(p: WherePlace): ImageVector = when (p.kind) {
    PlaceKind.THIS_DEVICE -> Icons.Outlined.PhoneAndroid
    PlaceKind.CLOUD -> Icons.Outlined.Cloud
    PlaceKind.DRIVE -> Icons.Outlined.Usb
    PlaceKind.DEVICE -> when (p.platform) {
        "android", "ios" -> Icons.Outlined.PhoneAndroid
        "windows", "macos", "linux" -> Icons.Outlined.Computer
        else -> Icons.Outlined.Devices
    }
}

/**
 * The rows and warnings of Where your files are. The same body sits in Settings > Storage and in Settings > Devices; the caller puts it in a
 * group or a pane. [onKeepCopy] gets the spaces to preselect in Add a copy. With [safety] (Settings > Storage) the shared core's one
 * sentence ("Your library is in 3 places", "12 files have only one copy") leads the group and replaces the per-kind warnings, as on Windows.
 */
@Composable
fun WhereFilesBody(ui: WhereFilesUi, onOpen: (WhereTap) -> Unit, onKeepCopy: (Set<String>) -> Unit, safety: Boolean = false) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (!ui.ready) {
        Text("Looking at where your files are…", fontSize = 14.sp, color = muted, modifier = Modifier.padding(16.dp).testTag(WhereTags.LOADING))
        return
    }
    Column(Modifier.fillMaxWidth().testTag(WhereTags.SECTION)) {
        if (safety) WhereSafety.of(ui)?.let { LibrarySafetyCard(it) { onKeepCopy(WhereSafety.spacesToKeep(ui)) } }
        for ((i, p) in ui.places.withIndex()) {
            if (i > 0) RowSeparator(inset = 64)
            PlaceRow(p, onOpen)
        }
        if (ui.musicVideoBytes > 0L) Text("Music videos · ${Format.bytes(ui.musicVideoBytes)}", fontSize = 13.sp, color = muted, modifier = Modifier.padding(start = 64.dp, end = 16.dp, top = 4.dp, bottom = 8.dp).testTag(WhereTags.MUSIC_VIDEOS))
        if (!safety && (ui.singles.isNotEmpty() || ui.onePlaceOnly)) RowSeparator()
        for ((i, s) in (if (safety) emptyList() else ui.singles).withIndex()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(WhereTags.single(i)), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 2.dp).size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.sentence, fontSize = 14.sp, lineHeight = 20.sp)
                    if (s.onThisPhone) TextButton(onClick = { onKeepCopy(s.spaceIds) }, modifier = Modifier.heightIn(min = 48.dp).testTag(WhereTags.keep(i))) { Text(WhereWords.KEEP) }
                }
            }
        }
        if (ui.onePlaceOnly && !safety) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(WhereWords.ONE_PLACE, fontSize = 14.sp, lineHeight = 20.sp, color = muted, modifier = Modifier.testTag(WhereTags.ONE_PLACE))
                OutlinedButton(onClick = { onKeepCopy(emptySet()) }, modifier = Modifier.heightIn(min = 48.dp).testTag(WhereTags.ONE_PLACE_KEEP)) { Text(WhereWords.KEEP) }
            }
        }
    }
}

@Composable
private fun PlaceRow(p: WherePlace, onOpen: (WhereTap) -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val tap = p.tap
    val base = Modifier.fillMaxWidth().heightIn(min = 64.dp)
    Row(
        (if (tap != null) base.clickable(role = Role.Button, onClickLabel = "Open ${p.name}") { onOpen(tap) } else base)
            .padding(horizontal = 16.dp, vertical = 8.dp).semantics(mergeDescendants = true) { contentDescription = "${p.name}${p.type?.let { ", $it" } ?: ""}. Holds ${p.holds}. ${p.sizeAndState}" }
            .testTag(WhereTags.row(p.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            val mark = if (p.kind == PlaceKind.CLOUD) providerMark(p.platform) else null
        if (mark != null) Icon(mark, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(20.dp))
        else Icon(placeIcon(p), contentDescription = null, tint = muted, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.name, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f, fill = false))
                p.type?.let {
                    Text(it, fontSize = 12.sp, color = muted, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest).padding(horizontal = 6.dp, vertical = 1.dp))
                }
            }
            Text(p.holds, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(p.sizeAndState, fontSize = 13.sp, lineHeight = 18.sp, color = muted)
        }
        if (tap != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = muted)
    }
}
