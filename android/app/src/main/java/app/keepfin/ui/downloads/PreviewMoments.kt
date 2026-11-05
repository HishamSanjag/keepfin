package app.keepfin.ui.downloads

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.keepfin.ui.Format
import app.keepfin.ui.UiTags
import app.keepfin.ui.theme.Studio

/** A link (or several) being read, or one that could not be read: what the preview shows until there is a result. */
class ReviewReading(val urls: List<String>, val error: String?, val onCancel: () -> Unit, val onRetry: () -> Unit, val onSignIn: () -> Unit)

private val signInHint = Regex("sign in|signin|cookies|login|private|members", RegexOption.IGNORE_CASE)

/**
 * The result's layout with quiet placeholders (cover, title lines, the first rows), the reading line and Cancel. A failure replaces them
 * with its message, Try again, and Sign in when signing in could help. It is the same sheet the result fills, so nothing closes and reopens.
 */
@Composable
fun ColumnScope.ReadingMoment(reading: ReviewReading, onClose: () -> Unit) {
    val pulse = if (Studio.reducedMotion) 1f else {
        val value by rememberInfiniteTransition(label = "reading").animateFloat(0.55f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
        value
    }
    val block = MaterialTheme.colorScheme.surfaceContainerHighest
    val failed = reading.error != null
    val title = if (failed) Format.host(reading.urls.first()) else if (reading.urls.size > 1) "Reading ${reading.urls.size} links…" else "Reading this link…"
    PreviewLayout(
        header = PreviewHeader(
            cover = { Box(Modifier.width(128.dp).aspectRatio(16f / 9f).alpha(pulse).clip(MaterialTheme.shapes.medium).background(block)) },
            title = title,
        ),
        onClose = onClose, mainLabel = null, extras = null,
        footer = {
            Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!failed) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        if (reading.urls.size > 1) "Reading ${reading.urls.size} links…" else "Reading formats from ${Format.host(reading.urls.first())}…",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, maxLines = 2,
                    )
                    TextButton(onClick = reading.onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.CANCEL_INSPECTION)) { Text("Cancel") }
                } else {
                    Spacer(Modifier.weight(1f))
                    if (signInHint.containsMatchIn(reading.error.orEmpty())) OutlinedButton(onClick = reading.onSignIn, modifier = Modifier.heightIn(min = 48.dp)) { Text("Sign in") }
                    if (reading.urls.first().isNotBlank()) FilledTonalButton(onClick = reading.onRetry, modifier = Modifier.heightIn(min = 48.dp)) { Text("Try again") }
                }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().testTag(UiTags.INSPECTING)) {
            if (!failed) {
                Column(Modifier.padding(16.dp)) {
                    repeat(2) {
                        Box(Modifier.fillMaxWidth(if (it == 0) .9f else .6f).height(18.dp).alpha(pulse).clip(MaterialTheme.shapes.extraSmall).background(block))
                        Spacer(Modifier.height(8.dp))
                    }
                }
                repeat(2) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(56.dp).alpha(pulse).clip(MaterialTheme.shapes.medium).background(block))
                }
            } else Column(Modifier.padding(16.dp).testTag(UiTags.CAPTURE_ERROR)) {
                Text("Couldn't read this link", style = MaterialTheme.typography.titleSmall)
                Text(reading.error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
}
