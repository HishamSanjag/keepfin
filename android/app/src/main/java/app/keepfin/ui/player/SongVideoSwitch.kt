package app.keepfin.ui.player

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.keepfin.ui.UiTags

/**
 * The Song | Video switch of a song that keeps its music video (dev-docs/plans/MUSIC-VIDEOS.md). [video] is the side that plays. Two segments of
 * 48dp, so each is a full touch target; [onPicture] draws it for the black video screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongVideoSwitch(video: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, onPicture: Boolean = false) {
    // over a picture: solid white with dark text for the side that plays, a dark veil for the other, so both read over any frame
    val colors = if (onPicture) SegmentedButtonDefaults.colors(
        activeContainerColor = Color.White, activeContentColor = Color(0xFF111318), activeBorderColor = Color.White,
        inactiveContainerColor = Color.Black.copy(alpha = .6f), inactiveContentColor = Color.White, inactiveBorderColor = Color.White.copy(alpha = .5f),
    ) else SegmentedButtonDefaults.colors()
    SingleChoiceSegmentedButtonRow(modifier.testTag(UiTags.SIDE_SWITCH).semantics { contentDescription = "Song or music video" }) {
        SegmentedButton(
            selected = !video, onClick = { onChange(false) }, shape = SegmentedButtonDefaults.itemShape(0, 2), colors = colors,
            modifier = Modifier.heightIn(min = 48.dp).widthIn(min = if (onPicture) 64.dp else 88.dp).testTag(UiTags.SIDE_SONG), label = { Text("Song") },
        )
        SegmentedButton(
            selected = video, onClick = { onChange(true) }, shape = SegmentedButtonDefaults.itemShape(1, 2), colors = colors,
            modifier = Modifier.heightIn(min = 48.dp).widthIn(min = if (onPicture) 64.dp else 88.dp).testTag(UiTags.SIDE_VIDEO), label = { Text("Video") },
        )
    }
}
