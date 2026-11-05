package app.keepfin.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import app.keepfin.Space
import app.keepfin.SpaceColor
import app.keepfin.SpaceLogic
import app.keepfin.SpaceTones
import app.keepfin.ToneSpec

/**
 * Tonal colour of a space: [container] fills its card, [accent] fills the icon tile, [onAccent] is the icon on it.
 * The eight palette colors (cobalt, violet, teal, olive, amber, rust, berry, slate) are defined in [SpaceTones] with a light and a
 * dark tone each; their contrast is checked in SpacesUiLogicTest.
 */
@Immutable
data class SpaceTone(val container: Color, val accent: Color, val onAccent: Color)

private fun ToneSpec.tone() = SpaceTone(Color(container), Color(accent), Color(onAccent))

fun spaceToneOf(color: SpaceColor, dark: Boolean): SpaceTone {
    val palette = SpaceTones.palettes.getValue(color)
    return (if (dark) palette.dark else palette.light).tone()
}

/** The tone of [space]. A space without a stored color gets its built-in tone, or the rotation by [index] among custom spaces. */
@Composable
fun spaceTone(space: Space, index: Int = 0): SpaceTone = spaceToneOf(space.color ?: SpaceLogic.defaultColor(space, index.coerceAtLeast(0)), Studio.colors.dark)

@Composable
fun spaceTone(color: SpaceColor): SpaceTone = spaceToneOf(color, Studio.colors.dark)
