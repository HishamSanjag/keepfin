package app.keepfin.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.keepfin.ui.theme.Motion
import app.keepfin.ui.theme.animationSpec

/** Corner points (x0,y0..x3,y3) of the two quads in a 24-unit box: play is two quads forming a triangle, pause is two bars. */
internal object PlayPauseShape {
    val PAUSE = arrayOf(floatArrayOf(6f, 5f, 10f, 5f, 10f, 19f, 6f, 19f), floatArrayOf(14f, 5f, 18f, 5f, 18f, 19f, 14f, 19f))
    val PLAY = arrayOf(floatArrayOf(7f, 5f, 12f, 7.92f, 12f, 16.08f, 7f, 19f), floatArrayOf(12f, 7.92f, 19f, 12f, 19f, 12f, 12f, 16.08f))

    /** The quad [k] (0 or 1) with t = 0 for play and 1 for pause. */
    fun quad(k: Int, t: Float): FloatArray = FloatArray(8) { PLAY[k][it] + (PAUSE[k][it] - PLAY[k][it]) * t }
}

/**
 * One shape that morphs between play and pause on the snappy spring (a hair of overshoot, as in the motion study); with reduced
 * motion it switches instantly. Replaces swapping two icons.
 */
@Composable
fun PlayPauseIcon(playing: Boolean, tint: Color, contentDescription: String?, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val t by animateFloatAsState(if (playing) 1f else 0f, Motion.snappy.animationSpec<Float>(), label = "play-pause")
    Canvas(modifier.size(size).then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)) {
        val unit = this.size.minDimension / 24f
        val amount = t.coerceIn(-0.04f, 1.04f)
        val path = Path()
        for (k in 0..1) {
            val q = PlayPauseShape.quad(k, amount)
            path.moveTo(q[0] * unit, q[1] * unit); path.lineTo(q[2] * unit, q[3] * unit); path.lineTo(q[4] * unit, q[5] * unit); path.lineTo(q[6] * unit, q[7] * unit); path.close()
        }
        drawPath(path, tint)
    }
}
