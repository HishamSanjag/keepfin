package app.keepfin.ui.player

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.keepfin.ui.UiTags
import app.keepfin.ui.theme.tabular

/** What the top-centre pill says: a brightness or volume level, or a plain note ("2×", "Speed 1.5×", "Zoomed to fill"). */
internal sealed interface Hud {
    data class Level(val label: String, val fraction: Float, val volume: Boolean) : Hud
    data class Note(val label: String) : Hud
}

private fun Modifier.pill() = clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = .7f)).padding(horizontal = 18.dp, vertical = 12.dp)

/** The pill is drawn only; the one polite announcement of the settled value comes from [AnnounceRegion]. */
@Composable
internal fun HudView(hud: Hud, modifier: Modifier) {
    Row(modifier.pill().semantics { invisibleToUser() }.testTag(UiTags.VIDEO_HUD), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when (hud) {
            is Hud.Level -> {
                Icon(if (hud.volume) Icons.Outlined.VolumeUp else Icons.Outlined.BrightnessMedium, contentDescription = null, tint = Color.White)
                Text(hud.label, color = Color.White, style = MaterialTheme.typography.titleMedium.tabular())
            }
            is Hud.Note -> Text(hud.label, color = Color.White, style = MaterialTheme.typography.titleMedium.tabular())
        }
    }
}

/** The running total of skips, on the side they went to. */
@Composable
internal fun SkipBubble(run: SkipRun, modifier: Modifier) {
    Row(modifier.pill().semantics { invisibleToUser() }.testTag(UiTags.VIDEO_SKIP_HUD), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(if (run.forward) Icons.Outlined.Forward10 else Icons.Outlined.Replay10, contentDescription = null, tint = Color.White)
        Text(VideoLogic.skipLabel(run), color = Color.White, style = MaterialTheme.typography.titleMedium.tabular())
    }
}

/**
 * The one polite live region of the player: [text] is set once the change has settled, so a screen reader says the final value, not
 * every step. Invisible (transparent) because the visible pills are hidden from accessibility.
 */
@Composable
internal fun AnnounceRegion(text: String, modifier: Modifier = Modifier) {
    Text(text, color = Color.Transparent, style = MaterialTheme.typography.labelSmall, modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("video-announce"))
}

/** "Screen locked" with the 48 dp unlock button; shown for a few seconds after a tap on the locked screen. */
@Composable
internal fun LockedPill(onUnlock: () -> Unit, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(28.dp)).background(Color.Black.copy(alpha = .8f)).padding(start = 18.dp, end = 6.dp).testTag(UiTags.VIDEO_LOCKED_PILL), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text("Screen locked", color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 10.dp))
        IconButton(onClick = onUnlock, modifier = Modifier.size(48.dp).testTag(UiTags.VIDEO_UNLOCK)) { Icon(Icons.Outlined.LockOpen, contentDescription = "Unlock the screen", tint = Color.White) }
    }
}

/**
 * The preview above the seek bar while it is dragged: the frame (when there is one), the target time and the chapter. It takes no height
 * in the layout (it is drawn upwards from the bar) and follows the thumb, kept inside the bar's width.
 */
@Composable
internal fun ScrubBubble(fraction: Float, timeMs: Long, chapter: String?, thumbs: VideoThumbnails?, aspect: Float, modifier: Modifier = Modifier) {
    Column(
        modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(Constraints(maxWidth = constraints.maxWidth))
                layout(constraints.maxWidth, 0) {
                    val x = (constraints.maxWidth * fraction - placeable.width / 2f).toInt().coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
                    placeable.place(IntOffset(x, -placeable.height - 8.dp.roundToPx()))
                }
            }
            .widthIn(max = 184.dp).testTag(UiTags.VIDEO_SCRUB_BUBBLE)
            .semantics { invisibleToUser() },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // as YouTube: a white-edged frame, the chapter under it, the time in a small dark pill
        val frame = thumbs?.frame
        val shape = RoundedCornerShape(8.dp)
        if (frame != null) Image(
            frame, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 176.dp, height = (176f / aspect.coerceIn(0.4f, 3f)).dp).clip(shape).border(2.dp, Color.White, shape),
        )
        val shadow = androidx.compose.ui.graphics.Shadow(Color.Black.copy(alpha = .9f), Offset(0f, 1f), 6f)
        if (!chapter.isNullOrBlank()) Text(
            chapter, color = Color.White, style = MaterialTheme.typography.labelLarge.copy(shadow = shadow), maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            VideoLogic.timeLabel(timeMs), color = Color.White, style = MaterialTheme.typography.labelLarge.tabular(),
            modifier = Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).background(Color(0xE0101218)).padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}
