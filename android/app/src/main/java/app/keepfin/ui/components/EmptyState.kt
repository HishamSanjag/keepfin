package app.keepfin.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.keepfin.R
import app.keepfin.ui.UiTags

/** Which small badge sits next to the Keepfin emblem in the illustration. */
enum class EmptyArt { DOWNLOADS, LIBRARY, SEARCH, COLLECTION }

/**
 * One consistent empty page: the Keepfin emblem, a title, one helpful line, an optional hint
 * and one action. Centred in the space it is given (scrolls instead of clipping at large font scales).
 */
@Composable
fun EmptyState(
    art: EmptyArt,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth().testTag(UiTags.EMPTY_STATE)) {
        // Inside a lazy list item or another scroller the height is unbounded: a nested scroller (or min = Infinity) would crash.
        val bounded = constraints.hasBoundedHeight
        val layoutMod = if (bounded) Modifier.heightIn(min = maxHeight).verticalScroll(rememberScrollState()) else Modifier.heightIn(min = 240.dp)
        Column(
            Modifier.fillMaxWidth().then(layoutMod).padding(horizontal = 32.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            EmptyIllustration(art, Modifier.size(136.dp))
            Spacer(Modifier.height(20.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(6.dp))
            Text(
                body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 360.dp),
            )
            if (action != null) { Spacer(Modifier.height(20.dp)); action() }
            if (hint != null) {
                Spacer(Modifier.height(10.dp))
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    }
}

/** The full Keepfin emblem on a transparent background, with a small per-page badge. */
@Composable
fun EmptyIllustration(art: EmptyArt, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier, contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.brand_emblem), contentDescription = null, modifier = Modifier.fillMaxWidth(.86f))
        Canvas(Modifier.fillMaxSize()) {
            val unit = size.minDimension / 512f
            withTransform({ scale(unit, unit, pivot = Offset.Zero) }) { badge(art, ink, surface, primary) }
        }
    }
}

/** Badge in the lower-right of the 512 grid; kept simple so the emblem stays the hero. */
private fun DrawScope.badge(art: EmptyArt, ink: Color, surface: Color, primary: Color) {
    val centre = Offset(404f, 404f)
    drawCircle(surface, radius = 68f, center = centre)
    drawCircle(ink.copy(alpha = .35f), radius = 68f, center = centre, style = Stroke(6f))
    val stroke = Stroke(18f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (art) {
        EmptyArt.DOWNLOADS -> {
            drawLine(primary, Offset(404f, 372f), Offset(404f, 424f), 18f, StrokeCap.Round)
            drawPath(Path().apply { moveTo(382f, 404f); lineTo(404f, 428f); lineTo(426f, 404f) }, primary, style = stroke)
        }
        EmptyArt.LIBRARY -> {
            drawRoundRect(ink, Offset(374f, 380f), Size(60f, 44f), androidx.compose.ui.geometry.CornerRadius(8f), style = Stroke(12f))
            drawPath(Path().apply { moveTo(396f, 392f); lineTo(414f, 402f); lineTo(396f, 412f); close() }, ink)
        }
        EmptyArt.SEARCH -> {
            drawCircle(ink, radius = 20f, center = Offset(398f, 398f), style = Stroke(12f))
            drawLine(ink, Offset(412f, 412f), Offset(432f, 432f), 12f, StrokeCap.Round)
        }
        EmptyArt.COLLECTION -> {
            drawLine(ink, Offset(376f, 386f), Offset(432f, 386f), 12f, StrokeCap.Round)
            drawLine(ink, Offset(376f, 404f), Offset(432f, 404f), 12f, StrokeCap.Round)
            drawLine(ink, Offset(376f, 422f), Offset(412f, 422f), 12f, StrokeCap.Round)
        }
    }
}

/** Convenience for lists that need a placeholder of a computed height (e.g. the rest of the viewport). */
fun Modifier.emptyHeight(height: Dp): Modifier = this.heightIn(min = height).height(height)
