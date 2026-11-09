package app.keepfin.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.keepfin.LibraryItemKind
import app.keepfin.ui.library.ArtTile

/**
 * Afterglow: the cover's colours glow behind everything, the lyrics are big and fill the middle (the header's Lyrics | Up next swaps
 * them for the whole queue), and a small cover, bars, the seek bar and the transport sit under them. A song that has a picture shows
 * it in a wide frame instead of the small cover, so the Song | Video switch and full screen stay within reach.
 */
@Composable
internal fun ColumnScope.AfterglowBody(s: FullScope, view: Pane) {
    val vm = s.vm
    Box(Modifier.weight(1f).fillMaxWidth().stage(s.motion, 0.3f, 0.75f, riseDp = 0f).testTag(PlayerStyleTags.body(PlayerStyles.AFTERGLOW))) {
        if (view == Pane.QUEUE) QueuePane(vm, s.state) else LyricsPane(vm, s.current?.id, s.item, s.reduced, big = true)
    }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp).testTag("player-controls")) {
        if (s.hasPicture) FullCover(s, wide = true)
        else {
            DisposableEffect(s.motion) { onDispose { s.motion.cover = Rect.Zero } }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.stage(s.motion, 0.35f, 0.75f)) {
                ArtTile(
                    s.art, s.current?.title.orEmpty(), LibraryItemKind.AUDIO,
                    Modifier.size(56.dp).onGloballyPositioned { s.motion.cover = it.boundsInRoot() }.graphicsLayer { if (s.motion.flying()) alpha = 0f },
                    MaterialTheme.shapes.medium, iconSize = 28.dp, fixedPx = PLAYER_ART_PX,
                )
                Spacer(Modifier.width(12.dp))
                SongTitleRow(s, Modifier.weight(1f), compact = true)
            }
        }
        if (s.hasPicture) SongTitleRow(s, Modifier.stage(s.motion, 0.35f, 0.75f), compact = true)
        RestingBars(s.state.playing, Modifier.padding(vertical = 4.dp))
        SeekRow(vm, Modifier.stage(s.motion, 0.4f, 0.8f))
        Transport(vm, s.state, Modifier.stage(s.motion, 0.45f, 0.85f))
        ToolsRow(s, Modifier.stage(s.motion, 0.5f, 0.9f))
    }
}
