package app.keepfin.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.LibraryItemKind
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.library.ArtTile
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The full player as one surface that grows out of the mini bar. A single progress value ([PlayerMotion.p]) drives the clip
 * rectangle (bar bounds to the whole window), the background, the artwork's flight from the bar to the cover and the dim over the
 * library; the player's own controls fade in on staggered stages. Everything is read in draw/graphicsLayer blocks, so a frame of
 * the transition does not recompose the player.
 */
@Composable
fun PlayerOverlay(vm: StudioViewModel, motion: PlayerMotion) {
    val barColor = MaterialTheme.colorScheme.surfaceContainerHigh
    val fullColor = MaterialTheme.colorScheme.surface
    Box(Modifier.fillMaxSize()) {
        // The library behind is dimmed (and scaled by the caller) while the player is open.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = (motion.p.value * 0.4f).coerceIn(0f, 0.4f) }.background(Color.Black))
        Box(
            Modifier.fillMaxSize()
                // Nothing below the growing surface may react to touches: being a touch target is enough, because overlapping
                // siblings do not share touches. It must not consume them: the player's own drags (close, skip) wait for touch
                // slop and give up when an ancestor has consumed the first small moves, so slow swipes failed and only fast ones worked.
                .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                .drawWithContent {
                    val t = motion.p.value.coerceIn(0f, 1f)
                    val bar = motion.miniBar.takeIf { it != Rect.Zero } ?: Rect(0f, size.height - 64.dp.toPx(), size.width, size.height)
                    val left = lerp(bar.left, 0f, t); val top = lerp(bar.top, 0f, t)
                    val right = lerp(bar.right, size.width, t); val bottom = lerp(bar.bottom, size.height, t)
                    val radius = 24.dp.toPx() * sin(PI * t).toFloat()
                    val path = Path().apply { addRoundRect(RoundRect(left, top, right, bottom, CornerRadius(radius))) }
                    clipPath(path) {
                        drawRect(lerp(barColor, fullColor, t), alpha = (t * 6f).coerceIn(0f, 1f))
                        this@drawWithContent.drawContent()
                    }
                },
        ) { FullPlayer(vm, motion) }
        FlyingArt(vm, motion)
    }
}

/** One decode size for the player's cover in the bar, in flight and on the cover pane: the three share one cached picture. */
internal const val PLAYER_ART_PX = 1024

/** The cover artwork travelling between its place in the bar and on the cover pane. Only present while it is in flight. */
@Composable
private fun FlyingArt(vm: StudioViewModel, motion: PlayerMotion) {
    val flying by remember(motion) { derivedStateOf { motion.flying() } }
    if (!flying) return
    val state by vm.player.ui.collectAsStateWithLifecycle()
    val current = state.current ?: return
    val art = rememberArt(vm, current)
    val t = motion.p.value.coerceIn(0f, 1f)
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(lerp(8f, 16f, t).dp)
    Box(
        Modifier.fillMaxSize().layout { measurable, constraints ->
            val progress = motion.p.value.coerceIn(0f, 1f)
            val a = motion.miniArt; val c = motion.cover
            val w = lerp(a.width, c.width, progress).roundToInt().coerceAtLeast(1)
            val h = lerp(a.height, c.height, progress).roundToInt().coerceAtLeast(1)
            val placeable = measurable.measure(Constraints.fixed(w, h))
            layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.place(lerp(a.left, c.left, progress).roundToInt(), lerp(a.top, c.top, progress).roundToInt())
            }
        },
    ) {
        ArtTile(art, current.title, LibraryItemKind.AUDIO, Modifier.fillMaxSize(), shape, iconSize = 48.dp, fixedPx = PLAYER_ART_PX)
    }
}
