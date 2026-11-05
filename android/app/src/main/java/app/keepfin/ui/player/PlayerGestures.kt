package app.keepfin.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.composed
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs

/** What a swipe on the player can do. Null callbacks (and false flags) leave that direction alone and the surface snaps back. */
class SwipeActions(
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onDown: (() -> Unit)? = null,
    /** TalkBack names for the three actions. */
    val downLabel: String = "Close the player",
    /** How far a downward drag must travel to count; the slim mini bar needs less than the big cover. */
    val downThresholdDp: Int = 96,
    /** A full swipe toward a side with no song (true = past the last, false = before the first), so the UI can say so. */
    val onEdge: ((next: Boolean) -> Unit)? = null,
)

/**
 * Finger-following offsets of a swipeable player surface. The surface follows the finger; a swipe past the threshold runs the action
 * (the old content slides out, the new slides in), a shorter one springs back. With reduced motion the surface still follows the finger
 * (direct manipulation) but commits and snaps back without any animation.
 */
@Stable
class SwipeState(private val scope: CoroutineScope, private val reduced: Boolean) {
    val x = Animatable(0f)
    val y = Animatable(0f)

    /** Distance the finger has travelled in the current horizontal gesture; the swipe is judged on this, never on the surface offset. */
    private var gestureDx = 0f

    /** A new horizontal gesture takes over wherever the surface is (maybe still sliding from the last swipe) and counts from zero. */
    fun beginX() {
        gestureDx = 0f
        scope.launch(start = CoroutineStart.UNDISPATCHED) { x.stop() }
    }

    fun dragX(delta: Float, canLeft: Boolean, canRight: Boolean) {
        // A direction with nothing behind it resists: the surface only follows a third of the finger.
        val next = gestureDx + delta
        val allowed = if (next < 0f && !canLeft || next > 0f && !canRight) delta / 3f else delta
        gestureDx = next
        // Undispatched, so the offset is up to date when the finger lifts a moment later (a quick flick has only a few events).
        scope.launch(start = CoroutineStart.UNDISPATCHED) { x.snapTo(x.value + allowed) }
    }

    /** Places the surface at [value] pixels down (callers that own the vertical drag). */
    fun setY(value: Float) { scope.launch { y.snapTo(value) } }

    fun dragY(delta: Float, enabled: Boolean) {
        if (!enabled) return
        scope.launch { y.snapTo((y.value + delta).coerceAtLeast(0f)) }
    }

    /**
     * Returns what the swipe did: true when it skipped, false when it hit an end, null when it was too short. A swipe counts when it
     * travelled [thresholdPx], or when it is a quick flick: at least [minPx] and released at [flingPx] per second the same way.
     */
    fun endX(widthPx: Float, thresholdPx: Float, actions: SwipeActions, velocityPx: Float = 0f, flingPx: Float = Float.MAX_VALUE, minPx: Float = 0f): Boolean? {
        val v = gestureDx
        gestureDx = 0f
        val toNext = v < 0f
        val allowed = if (toNext) actions.hasNext else actions.hasPrevious
        val flick = abs(v) >= minPx && abs(velocityPx) >= flingPx && (velocityPx < 0f) == toNext
        if (abs(v) < thresholdPx && !flick) { settle(); return null }
        if (!allowed) { settle(); actions.onEdge?.invoke(toNext); return false }
        // Skip at once, then animate: a new swipe that starts mid-animation cancels only the animation, never the skip, so
        // quick repeated swipes each count.
        if (toNext) actions.onNext() else actions.onPrevious()
        val sign = if (toNext) -1f else 1f
        scope.launch {
            if (reduced) { x.snapTo(0f); return@launch }
            x.animateTo(sign * widthPx, tween(110))
            x.snapTo(-sign * widthPx * 0.35f)
            x.animateTo(0f, tween(170))
        }
        return true
    }

    fun endY(heightPx: Float, thresholdPx: Float, actions: SwipeActions) {
        scope.launch {
            val down = actions.onDown
            if (down == null || y.value < thresholdPx) { settle(); return@launch }
            if (!reduced) y.animateTo(heightPx, tween(140))
            down()
        }
    }

    fun settle() {
        scope.launch {
            if (reduced) { x.snapTo(0f); y.snapTo(0f) }
            else { launch { x.animateTo(0f, tween(180)) }; y.animateTo(0f, tween(180)) }
        }
    }

    suspend fun reset() { x.snapTo(0f); y.snapTo(0f) }
}

@Composable
fun rememberSwipeState(reduced: Boolean): SwipeState {
    val scope = rememberCoroutineScope()
    return remember(scope, reduced) { SwipeState(scope, reduced) }
}

/**
 * With [vertical] false only the horizontal swipes are handled (the caller owns vertical drags, e.g. to open or close the player).
 * Horizontal swipes skip songs (left = next, right = previous); a swipe down runs [SwipeActions.onDown]. Gestures claim a drag only
 * once it passes touch slop on their own axis, so taps, the seek slider and lists beside the surface are untouched. Also names the
 * same three actions for TalkBack; the transport buttons stay.
 */
fun Modifier.playerSwipe(state: SwipeState, actions: SwipeActions, vertical: Boolean = true): Modifier = composed {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    val latest by rememberUpdatedState(actions)
    val horizontalThreshold = with(density) { 88.dp.toPx() }
    val flingPx = with(density) { 1000.dp.toPx() }
    val flickMinPx = with(density) { 24.dp.toPx() }
    val tracker = remember { VelocityTracker() }
    val verticalThreshold = with(density) { actions.downThresholdDp.dp.toPx() }
    this
        .semantics {
            customActions = buildList {
                if (actions.hasNext) add(CustomAccessibilityAction("Next song") { actions.onNext(); true })
                if (actions.hasPrevious) add(CustomAccessibilityAction("Previous song") { actions.onPrevious(); true })
                actions.onDown?.let { down -> add(CustomAccessibilityAction(actions.downLabel) { down(); true }) }
            }
        }
        .onSizeChanged { size = it }
        .pointerInput(Unit) {
            detectHorizontalDragGestures(
                onDragStart = { tracker.resetTracking(); state.beginX() },
                onDragEnd = {
                    // A tick when a swipe skips; a heavier buzz when there is nothing on that side.
                    when (state.endX(size.width.toFloat(), horizontalThreshold, latest, tracker.calculateVelocity().x, flingPx, flickMinPx)) {
                        true -> haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        false -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        null -> Unit
                    }
                },
                onDragCancel = { state.settle() },
            ) { change, amount -> tracker.addPointerInputChange(change); change.consume(); state.dragX(amount, latest.hasNext, latest.hasPrevious) }
        }
        .then(if (!vertical) Modifier else Modifier.pointerInput(verticalThreshold) {
            detectVerticalDragGestures(
                onDragEnd = { state.endY(size.height.toFloat(), verticalThreshold, latest) },
                onDragCancel = { state.settle() },
            ) { change, amount -> change.consume(); state.dragY(amount, latest.onDown != null) }
        })
}

/** Moves the content with the finger and fades it a little as it leaves. */
fun Modifier.swipeOffset(state: SwipeState): Modifier = graphicsLayer {
    translationX = state.x.value
    translationY = state.y.value
    alpha = 1f - (abs(state.x.value) / size.width.coerceAtLeast(1f) * 0.6f + state.y.value / size.height.coerceAtLeast(1f) * 0.6f).coerceIn(0f, 0.7f)
}
