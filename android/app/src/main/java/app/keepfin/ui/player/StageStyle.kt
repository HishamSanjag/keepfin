package app.keepfin.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.keepfin.ui.UiTags
import app.keepfin.ui.theme.Studio

/**
 * The cover with the swipes every style keeps: left and right for next and previous, down to close. [wide] is Cinema's 16:9 stage and
 * [overlay] what is drawn over it. The Song | Video pill and the picture in the cover's place come with it, so every style has them.
 */
@Composable
internal fun FullCover(s: FullScope, modifier: Modifier = Modifier, wide: Boolean = false, overlay: @Composable BoxScope.() -> Unit = {}) {
    val vm = s.vm
    val item = s.item
    val swipe = rememberSwipeState(Studio.reducedMotion)
    val actions = SwipeActions(s.state.hasPrevious, s.state.hasNext, vm.player::previousItem, vm.player::next, { vm.fullPlayer = false }, onEdge = vm::sayQueueEdge)
    Box(modifier.playerSwipe(swipe, actions, vertical = false).testTag("cover-swipe")) {
        CoverPane(
            s.art, s.current, s.motion, Modifier.swipeOffset(swipe), picture = if (s.pictureOn && vm.video == null) vm.player.player else null, pictureOn = s.pictureOn,
            switch = if (s.hasPicture) { { place ->
                SongVideoSwitch(
                    video = s.pictureOn, onPicture = true, modifier = place,
                    onChange = { toVideo -> if (item?.musicVideo == true) vm.chooseSide(toVideo) else if (item != null) { if (toVideo) vm.showVideoSongPicture(item) else vm.listenToVideoSong(item) } },
                )
            } } else null,
            onFullScreen = vm::openPictureFullScreen, wide = wide, overlay = overlay,
        )
    }
}

/**
 * Stage, the default: a big square cover, then one opaque control layer with the title, seek bar, transport and tools; the lyrics card
 * under them, and the cover swaps for the full lyrics or the queue with the two toggles. (On the PC the lyrics sit beside the cover;
 * in portrait they take the cover's place.)
 */
@Composable
internal fun ColumnScope.StageBody(s: FullScope, pane: Pane, setPane: (Pane) -> Unit) {
    val vm = s.vm
    Box(Modifier.weight(1f).fillMaxWidth().stage(s.motion, 0.3f, 0.75f, riseDp = 0f).testTag(PlayerStyleTags.body(PlayerStyles.STAGE))) {
        when (pane) {
            Pane.COVER -> FullCover(s, Modifier.fillMaxSize())
            Pane.LYRICS -> LyricsPane(vm, s.current?.id, s.item, s.reduced)
            Pane.QUEUE -> QueuePane(vm, s.state)
        }
    }
    // The controls sit on an opaque layer, whatever the artwork is.
    Surface(
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth().testTag("player-controls"),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp)) {
            SongTitleRow(s, Modifier.stage(s.motion, 0.35f, 0.75f))
            SeekRow(vm, Modifier.stage(s.motion, 0.4f, 0.8f))
            Transport(vm, s.state, Modifier.stage(s.motion, 0.45f, 0.85f))
            ToolsRow(s, Modifier.stage(s.motion, 0.5f, 0.9f)) {
                FilledTonalIconToggleButton(checked = pane == Pane.LYRICS, onCheckedChange = { setPane(if (it) Pane.LYRICS else Pane.COVER) }, modifier = Modifier.size(48.dp).testTag(UiTags.FULL_LYRICS_TOGGLE)) {
                    Icon(Icons.Outlined.Lyrics, contentDescription = "Lyrics")
                }
                FilledTonalIconToggleButton(checked = pane == Pane.QUEUE, onCheckedChange = { setPane(if (it) Pane.QUEUE else Pane.COVER) }, modifier = Modifier.size(48.dp).testTag(UiTags.FULL_QUEUE_TOGGLE)) {
                    Icon(Icons.AutoMirrored.Outlined.QueueMusic, contentDescription = "Queue")
                }
            }
            if (pane == Pane.COVER && vm.prefs.lyricsPreview) LyricsCard(vm, s.current?.id) { setPane(Pane.LYRICS) }
        }
    }
}
