package app.keepfin.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.keepfin.ui.theme.Motion
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.theme.spring
import kotlin.math.PI
import kotlin.math.sin

/**
 * Three small bars for the song that is current: they move only while it plays and settle to dots when paused. With reduced motion
 * they are static (bars while playing, dots when paused). Drawn in the draw phase, so animating never recomposes or relays out the row.
 *
 * The motion is an infinite transition (not a loop of animations in a coroutine), so a UI test sees it as the endless animation it is
 * and does not wait for it to finish; it runs only while the song plays.
 */
@Composable
fun NowPlayingBars(playing: Boolean, tint: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    val reduced = Studio.reducedMotion
    val settle = remember { Animatable(if (playing) 1f else 0f) }
    LaunchedEffect(playing, reduced) {
        if (reduced) settle.snapTo(if (playing) 1f else 0f) else settle.animateTo(if (playing) 1f else 0f, Motion.snappy.spring<Float>())
    }
    val phase: State<Float> = if (playing && !reduced) {
        rememberInfiniteTransition(label = "now-playing").animateFloat(0f, 1f, infiniteRepeatable<Float>(tween(1520, easing = LinearEasing)), label = "phase")
    } else remember { mutableFloatStateOf(0f) }
    Canvas(modifier.size(size)) {
        val bar = this.size.width / 7f
        val total = 5 * bar
        val startX = (this.size.width - total) / 2f
        val tall = this.size.height * 0.85f
        val base = (this.size.height + tall) / 2f
        for (index in 0 until 3) {
            val level = if (reduced) STATIC[index] else {
                val wave = 0.5f + 0.5f * sin(2.0 * PI * (phase.value * CYCLES[index] + OFFSET[index])).toFloat()
                0.2f + 0.8f * wave
            }
            val h = bar + (tall - bar) * (level * settle.value).coerceIn(0f, 1f)
            drawRoundRect(tint, Offset(startX + index * 2 * bar, base - h), Size(bar, h), CornerRadius(bar / 2f))
        }
    }
}

private val CYCLES = floatArrayOf(2f, 3f, 2f)
private val OFFSET = floatArrayOf(0f, 0.3f, 0.65f)
private val STATIC = floatArrayOf(0.55f, 1f, 0.7f)