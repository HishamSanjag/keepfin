package app.keepfin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/*
 * The two pictures of "How do you want to use Keepfin?": a small drawing of the app in each mode, from theme colours only, so
 * they read in light and dark and stay sharp at any size. No words inside (nothing to translate or misread); the option's text
 * says what they show. Drawn on a 160 x 100 grid scaled to fit.
 */

private class Palette(val frame: Color, val window: Color, val bar: Color, val accent: Color, val tint: List<Color>)

@Composable
private fun palette(): Palette {
    val c = MaterialTheme.colorScheme
    return Palette(
        frame = c.outlineVariant,
        window = c.surfaceContainerHighest,
        bar = c.onSurfaceVariant.copy(alpha = 0.35f),
        accent = c.primary,
        tint = listOf(0.25f, 0.4f, 0.6f).map { c.primary.copy(alpha = it).compositeOver(c.surfaceContainerHighest) },
    )
}

/** Draws on a 160 x 100 grid, centred and scaled to fit the canvas. */
private inline fun DrawScope.grid(block: DrawScope.(u: Float) -> Unit) {
    val u = minOf(size.width / 160f, size.height / 100f)
    val dx = (size.width - 160f * u) / 2f
    val dy = (size.height - 100f * u) / 2f
    drawContext.transform.translate(dx, dy)
    block(u)
    drawContext.transform.translate(-dx, -dy)
}

private fun DrawScope.box(u: Float, x: Float, y: Float, w: Float, h: Float, r: Float, color: Color) =
    drawRoundRect(color, Offset(x * u, y * u), Size(w * u, h * u), CornerRadius(r * u, r * u))

private fun DrawScope.window(u: Float, p: Palette) {
    box(u, 1f, 1f, 158f, 98f, 8f, p.window)
    drawRoundRect(p.frame, Offset(1f * u, 1f * u), Size(158f * u, 98f * u), CornerRadius(8f * u, 8f * u), style = Stroke(1.2f * u))
}

/** Keepfin Complete: a rail, a grid of covers and a player bar. */
@Composable
fun LibraryPicture(modifier: Modifier = Modifier) {
    val p = palette()
    Canvas(modifier) {
        grid { u ->
            window(u, p)
            // the rail with three places
            box(u, 1f, 1f, 22f, 98f, 8f, p.tint[0])
            for (i in 0..2) drawCircle(if (i == 0) p.accent else p.bar, 3f * u, Offset(12f * u, (18f + i * 14f) * u))
            // the header
            box(u, 32f, 10f, 46f, 6f, 3f, p.bar)
            // a 3 x 2 grid of covers
            for (row in 0..1) for (col in 0..2) box(u, 32f + col * 40f, 22f + row * 26f, 34f, 21f, 4f, p.tint[(row + col) % 3])
            // the player bar
            box(u, 28f, 76f, 126f, 18f, 5f, p.tint[0])
            box(u, 32f, 79f, 12f, 12f, 3f, p.tint[2])
            box(u, 48f, 81f, 44f, 4f, 2f, p.bar)
            box(u, 48f, 87f, 28f, 3f, 1.5f, p.bar)
            val play = Path().apply {
                moveTo(138f * u, 80f * u); lineTo(146f * u, 85f * u); lineTo(138f * u, 90f * u); close()
            }
            drawPath(play, p.accent)
        }
    }
}

/** Just downloads: a link box with a Download pill and three downloads in progress; no rail, no player. */
@Composable
fun DownloaderPicture(modifier: Modifier = Modifier) {
    val p = palette()
    Canvas(modifier) {
        grid { u ->
            window(u, p)
            // the link box and Download
            drawRoundRect(p.frame, Offset(10f * u, 10f * u), Size(104f * u, 16f * u), CornerRadius(8f * u, 8f * u), style = Stroke(1.2f * u))
            drawCircle(p.bar, 3f * u, Offset(19f * u, 18f * u))
            box(u, 27f, 16f, 54f, 4f, 2f, p.bar)
            box(u, 118f, 10f, 32f, 16f, 8f, p.accent)
            // three downloads: thumbnail, two lines, progress
            listOf(1f, 0.6f, 0.25f).forEachIndexed { i, done ->
                val y = 34f + i * 21f
                box(u, 10f, y, 22f, 16f, 3f, p.tint[i])
                box(u, 38f, y + 1f, 60f, 4f, 2f, p.bar)
                box(u, 38f, y + 7f, 36f, 3f, 1.5f, p.bar)
                box(u, 38f, y + 12f, 112f, 3f, 1.5f, p.frame)
                box(u, 38f, y + 12f, 112f * done, 3f, 1.5f, p.accent)
            }
        }
    }
}
