package app.keepfin.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import kotlin.math.PI

/**
 * A spring token shared with the desktop app: (response in seconds, damping ratio), stiffness = (2π / response)², mass 1.
 * See "Motion system" in DESIGN.md and dev-docs/design/mockups/motion.html.
 */
data class MotionSpec(val response: Float, val damping: Float) {
    val stiffness: Float get() = Motion.stiffness(response)
}

object Motion {
    /** Press feedback, content swaps, small controls. */
    val quick = MotionSpec(0.24f, 0.90f)
    /** Morphs, covers, rows (a hair of overshoot at most). */
    val snappy = MotionSpec(0.34f, 0.86f)
    /** Containers, lyrics. */
    val smooth = MotionSpec(0.46f, 0.94f)
    /** Mini player to full player. */
    val sheet = MotionSpec(0.42f, 0.90f)
    /** Front and back edges of tabs and switch knobs. */
    val lead = MotionSpec(0.20f, 0.92f)
    val trail = MotionSpec(0.44f, 1.0f)
    /** Progress: critically damped, never passes the engine's last reported value. */
    val truth = MotionSpec(0.32f, 1.0f)

    /** (2π / response)². */
    fun stiffness(response: Float): Float { val w = (2.0 * PI / response).toFloat(); return w * w }
}

/** The token as a Compose spring. */
fun <T> MotionSpec.spring(visibilityThreshold: T? = null): SpringSpec<T> = spring(dampingRatio = damping, stiffness = stiffness, visibilityThreshold = visibilityThreshold)

/** The token as a spring, or an instant jump when the user turned animations off. Drags still follow the finger either way. */
fun <T> MotionSpec.orSnap(reduced: Boolean, visibilityThreshold: T? = null): AnimationSpec<T> = if (reduced) snap() else spring(visibilityThreshold)

/** [orSnap] reading the current reduced-motion setting. */
@Composable
fun <T> MotionSpec.animationSpec(visibilityThreshold: T? = null): AnimationSpec<T> = orSnap(Studio.reducedMotion, visibilityThreshold)
