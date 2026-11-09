package app.keepfin.ui.player

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Cinema: a wide 16:9 stage (the cover, or the picture in its place) with the line being sung laid over it, the song and its controls
 * under it, and a side panel that is a bottom panel in portrait: Up next or Lyrics. When the screen is too short (large text, small
 * phone) the page scrolls and the panel keeps a readable height instead of being squeezed away.
 */
@Composable
internal fun ColumnScope.CinemaBody(s: FullScope, side: Pane, setSide: (Pane) -> Unit) {
    val vm = s.vm
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().testTag(PlayerStyleTags.body(PlayerStyles.CINEMA))) {
        val short = maxHeight < 560.dp
        val panel: @Composable (Modifier) -> Unit = { mod ->
            Surface(shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 8.dp, modifier = mod.fillMaxWidth().testTag("player-controls")) {
                Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) { SidePick(side, setSide, Modifier.fillMaxWidth()) }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (side == Pane.QUEUE) QueuePane(vm, s.state) else LyricsPane(vm, s.current?.id, s.item, s.reduced)
                    }
                }
            }
        }
        val top: @Composable () -> Unit = {
            FullCover(s, wide = true, overlay = { CurrentLyricOverlay(vm, s.current?.id) })
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                SongTitleRow(s, Modifier.stage(s.motion, 0.35f, 0.75f), compact = true)
                SeekRow(vm, Modifier.stage(s.motion, 0.4f, 0.8f))
                Transport(vm, s.state, Modifier.stage(s.motion, 0.45f, 0.85f))
                ToolsRow(s, Modifier.stage(s.motion, 0.5f, 0.9f))
            }
        }
        if (short) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            top()
            panel(Modifier.height(360.dp))
        } else Column(Modifier.fillMaxSize()) {
            top()
            panel(Modifier.weight(1f).heightIn(min = 160.dp))
        }
    }
}
