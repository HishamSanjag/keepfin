package app.keepfin.ui.player

import androidx.compose.ui.graphics.Color
import app.keepfin.LibraryItem
import app.keepfin.ui.StudioViewModel
import kotlin.math.pow

/**
 * The full player's styles (GitHub #34), the phone's copy of the PC's registry (gui/web/player/full/layouts.ts): the same ids, names,
 * summaries and default, in display order. A new style is one body composable in its own file, one entry here and one branch in
 * [FullPlayer]; a style arranges the shared parts and never re-implements playback.
 */
data class PlayerStyle(val id: String, val name: String, /** One plain line, shown in the style menu and in Settings. */ val summary: String)

object PlayerStyles {
    const val STAGE = "stage"
    const val CINEMA = "cinema"
    const val AFTERGLOW = "afterglow"
    const val DEFAULT = STAGE

    val all: List<PlayerStyle> = listOf(
        PlayerStyle(STAGE, "Stage", "A big cover on the left, large lyrics on the right, one control bar along the bottom."),
        PlayerStyle(CINEMA, "Cinema", "A wide stage with the current lyric over the picture, and a side panel for the queue and lyrics."),
        PlayerStyle(AFTERGLOW, "Afterglow", "A glow from the cover’s colours, big karaoke lyrics, a visualizer and the next songs below."),
    )

    /** A saved id that is not a style (renamed, removed, hand-edited) falls back to the default. */
    fun byId(id: String?): PlayerStyle = all.firstOrNull { it.id == id } ?: all.first { it.id == DEFAULT }

    /** WCAG relative luminance of a colour. */
    internal fun luminance(c: Color): Float {
        fun ch(v: Float) = if (v <= 0.03928f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
        return 0.2126f * ch(c.red) + 0.7152f * ch(c.green) + 0.0722f * ch(c.blue)
    }

    /** The colour scaled (keeping its hue) until its luminance is at most [max], as the PC's `darkenedTint`: the glow can never get bright enough to cost the lyrics their contrast. */
    internal fun darkened(color: Color, max: Float = 0.16f): Color {
        if (luminance(color) <= max) return color
        var low = 0f; var high = 1f
        repeat(14) { val mid = (low + high) / 2; if (luminance(Color(color.red * mid, color.green * mid, color.blue * mid)) > max) high = mid else low = mid }
        return Color(color.red * low, color.green * low, color.blue * low)
    }
}

/** Everything a style arranges: the song on screen and the player's state. Playback itself stays in the port behind [vm]. */
internal class FullScope(
    val vm: StudioViewModel,
    val state: PlayerUi,
    val current: QueueEntry?,
    val item: LibraryItem?,
    val art: Any?,
    val motion: PlayerMotion,
    val reduced: Boolean,
    /** A song that keeps its music video, or a video kept as a song, has a picture. */
    val hasPicture: Boolean,
    val pictureOn: Boolean,
    val openSheet: (String) -> Unit,
    val openInfo: (LibraryItem) -> Unit,
)
