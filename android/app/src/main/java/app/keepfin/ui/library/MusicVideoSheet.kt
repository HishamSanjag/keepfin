package app.keepfin.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.keepfin.MusicVideoLookup
import app.keepfin.ui.StudioSheet
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.MediaThumb
import app.keepfin.ui.player.MusicVideoLogic

/**
 * "Get the music video" (dev-docs/plans/MUSIC-VIDEOS.md): Keepfin looks for the song's official music video and shows what it found, with its
 * picture, so the person confirms before anything is downloaded. Nothing is guessed: no match is said as such, and a lookup that could not be
 * made is told apart from "none" and can be tried again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicVideoSheetHost(vm: StudioViewModel) {
    val sheet = vm.musicVideoSheet ?: return
    StudioSheet(
        onDismissRequest = vm::closeMusicVideoSheet, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(UiTags.MUSIC_VIDEO_SHEET),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Music video", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            when (val look = sheet.lookup) {
                null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Text("Looking for the official music video…", style = MaterialTheme.typography.bodyLarge)
                }
                is MusicVideoLookup.Found -> {
                    val video = look.video
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                        MediaThumb(video.thumbnail, Modifier.width(132.dp).aspectRatio(16f / 9f))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(video.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(video.channel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val details = listOfNotNull(MusicVideoLogic.views(video.views), MusicVideoLogic.length(video.durationSec)).joinToString(" · ")
                            if (details.isNotEmpty()) Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                        OutlinedButton(onClick = vm::closeMusicVideoSheet, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.MUSIC_VIDEO_CANCEL)) { Text("Cancel") }
                        Button(onClick = vm::downloadMusicVideo, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.MUSIC_VIDEO_DOWNLOAD)) { Text("Download") }
                    }
                }
                MusicVideoLookup.None -> {
                    Text("No official music video was found for ${sheet.title}.", style = MaterialTheme.typography.bodyLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Button(onClick = vm::closeMusicVideoSheet, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.MUSIC_VIDEO_CLOSE)) { Text("Close") }
                    }
                }
                is MusicVideoLookup.Failed -> {
                    Text(look.message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                        OutlinedButton(onClick = vm::closeMusicVideoSheet, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.MUSIC_VIDEO_CLOSE)) { Text("Close") }
                        Button(onClick = vm::retryMusicVideo, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.MUSIC_VIDEO_RETRY)) { Text("Try again") }
                    }
                }
            }
        }
    }
}
