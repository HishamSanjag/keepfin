package app.keepfin.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.keepfin.core.copies.Safety

/** The top of Settings > Storage: is every file kept in more than one place? The sentence is the shared core's, the same as on Windows. */
@Composable
internal fun LibrarySafetyCard(safety: Safety, onKeepCopy: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (icon, tint) = when (safety.tone) {
        "safe" -> Icons.Outlined.CheckCircle to scheme.primary
        "warn" -> Icons.Outlined.WarningAmber to scheme.error
        else -> Icons.Outlined.Info to scheme.onSurfaceVariant
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag(WhereTags.SAFETY),
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.padding(top = 2.dp).size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(safety.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = scheme.onSurface)
            Text(safety.detail, fontSize = 14.sp, lineHeight = 20.sp, color = scheme.onSurfaceVariant)
            if (safety.keepCopy) Button(onClick = onKeepCopy, modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp).testTag(WhereTags.SAFETY_KEEP)) { Text(app.keepfin.where.WhereWords.KEEP) }
        }
    }
}
