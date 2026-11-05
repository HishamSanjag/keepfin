package app.keepfin.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import app.keepfin.ui.components.studioImageLoader
import app.keepfin.ui.theme.Studio
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Size

/** Text on the wash: near-white, and a lighter secondary than the dark theme's, so every pair clears WCAG AA over the darkest scrim. */
internal val OnWash = Color(0xFFF5F7FB)
internal val OnWashDim = Color(0xFFC9CFDB)

/** The darkening laid over the artwork. At this strength even a pure-white cover leaves the wash dark enough for [OnWashDim] (5:1 or better). */
internal const val WASH_SCRIM = 0.72f

/**
 * The full player's background: a blurred, darkened wash of the current artwork that cross-fades when the track changes (instantly
 * with animations off). The artwork is read at 64 px and stretched, which is already a soft blur on every Android version; no real blur is drawn (it is costly per frame). Without artwork it is a calm cobalt-to-ink gradient. The scrim is constant, so contrast of
 * text on the wash does not depend on how bright or busy the cover is.
 */
@Composable
fun ArtworkWash(art: Any?, modifier: Modifier = Modifier) {
    val reduced = Studio.reducedMotion
    val context = LocalContext.current
    val loader = studioImageLoader()
    Box(modifier.background(Color(0xFF0D0F13))) {
        Crossfade(targetState = art, animationSpec = tween(if (reduced) 0 else 800), label = "wash", modifier = Modifier.fillMaxSize()) { model ->
            if (model == null) {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF22346E), Color(0xFF111318)))))
            } else {
                val request = remember(model) { ImageRequest.Builder(context).data(model).size(Size(64, 64)).build() }
                AsyncImage(
                    model = request, contentDescription = null, imageLoader = loader, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = WASH_SCRIM), Color.Black.copy(alpha = 0.86f)))))
    }
}
