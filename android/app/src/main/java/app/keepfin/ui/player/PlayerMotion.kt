package app.keepfin.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import app.keepfin.ui.theme.Motion
import app.keepfin.ui.theme.orSnap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The one number behind the mini player becoming the full player: [p] is 0 for the bar and 1 for the full player. The surface's
 * bounds, the artwork's rectangle, the staged fade-ins and the dimmed library behind all read it in draw/graphicsLayer blocks,
 * so a drag simply sets it and a release springs it with the finger's velocity (sheet spring).
 */
@Stable
class PlayerMotion(private val scope: CoroutineScope, initial: Float = 0f) {
    val p = Animatable(initial)

    /** Window-space rectangles measured by the bar and the full player; Zero until measured. */
    var miniBar by mutableStateOf(Rect.Zero)
    var miniArt by mutableStateOf(Rect.Zero)
    var cover by mutableStateOf(Rect.Zero)

    /** Distance the top edge travels between the closed bar and the open player. */
    val travel: Float get() = miniBar.top.coerceAtLeast(400f)

    /** Latest value set by a drag; read synchronously (the animatable follows asynchronously). */
    var dragValue = initial
        private set

    /** True while the artwork is flying between the bar and the cover; each end hides its own copy meanwhile. */
    fun flying(): Boolean = p.value > 0.001f && p.value < 0.999f && cover != Rect.Zero && miniArt != Rect.Zero

    fun beginDrag() { dragValue = p.value }

    /** Moves the surface with the finger; [delta] is a fraction of [travel]. */
    fun dragBy(delta: Float) {
        dragValue = (dragValue + delta).coerceIn(0f, 1f)
        val value = dragValue
        scope.launch { p.snapTo(value) }
    }

    /** Springs to open (1) or closed (0) carrying the release velocity (pixels per second, downward positive). */
    fun settle(open: Boolean, reduced: Boolean, velocityPx: Float? = null) {
        scope.launch {
            val target = if (open) 1f else 0f
            if (reduced) p.snapTo(target)
            else p.animateTo(target, Motion.sheet.orSnap<Float>(false, 0.002f), initialVelocity = velocityPx?.let { -it / travel } ?: p.velocity)
        }
    }
}

val LocalPlayerMotion = staticCompositionLocalOf<PlayerMotion?> { null }

@Composable
fun rememberPlayerMotion(initial: Float = 0f): PlayerMotion {
    val scope = rememberCoroutineScope()
    return remember(scope) { PlayerMotion(scope, initial) }
}

/** Fades and lifts an element in while the player opens: it is fully shown at [to] and hidden at [from]. Transform and opacity only. */
fun Modifier.stage(motion: PlayerMotion, from: Float, to: Float, riseDp: Float = 14f): Modifier = graphicsLayer {
    val t = ((motion.p.value - from) / (to - from)).coerceIn(0f, 1f)
    alpha = t
    translationY = (1f - t) * riseDp * density
}

/**
 * A vertical drag that reports its velocity on release. Only gestures that nothing below has consumed and that pass touch slop
 * on the vertical axis start it, so taps, buttons, the seek bar and scrolling lists keep working.
 */
fun Modifier.verticalFling(onStart: () -> Unit = {}, onDrag: (Float) -> Unit, onEnd: (velocity: Float, cancelled: Boolean) -> Unit): Modifier = composed {
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val tracker = VelocityTracker()
            tracker.addPointerInputChange(down)
            val slop = awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                change.consume(); tracker.addPointerInputChange(change); start(); drag(over)
            }
            if (slop != null) {
                val finished = verticalDrag(slop.id) { change ->
                    val delta = change.positionChange().y
                    tracker.addPointerInputChange(change); change.consume(); drag(delta)
                }
                end(if (finished) tracker.calculateVelocity().y else 0f, !finished)
            }
        }
    }
}
