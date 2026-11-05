package app.keepfin.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.keepfin.MediaInfo
import app.keepfin.Restriction
import app.keepfin.SongOfLink
import app.keepfin.core.spaces.MusicVideoRows
import app.keepfin.core.spaces.PreviewWords
import app.keepfin.ui.Format
import app.keepfin.ui.FormatChoice
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.ArtworkBadge
import app.keepfin.ui.components.MediaThumb
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.theme.tabular

/** A music link (a song, or a video matched to one) has square cover art. */
internal fun isSong(media: MediaInfo) = media.music != null || media.match != null || media.video.isEmpty() || media.provider.contains("Music", ignoreCase = true)

/** What Both (a song with its video beside it) can be for this link, and why it is off when it is. */
sealed interface BothOffer {
    /** Not for this link: a plain file, the library is off, or a video that is not a song. */
    data object Off : BothOffer
    /** A music link whose official video is still being looked for. */
    data object Looking : BothOffer
    /** No official video was found (or the look failed). */
    data object Missing : BothOffer
    /** [own] is the link itself (a YouTube link); otherwise [video] is the official video that was found. */
    class Found(val title: String, val thumbnail: String?, val details: String?, val own: Boolean) : BothOffer
}

/** Everything the one-link preview shows and does. */
class SingleUi(
    val media: MediaInfo,
    val choice: FormatChoice,
    val onChoice: (FormatChoice) -> Unit,
    val both: BothOffer,
    /** Both is chosen (the song is kept with its video). */
    val bothOn: Boolean,
    val onBoth: (Boolean) -> Unit,
    /** The song you have behind this link: a video joins it. */
    val songOfLink: SongOfLink?,
    val existingIn: String?,
    val onPlayExisting: () -> Unit,
    /** The music space a video goes to as a song you can also watch. */
    val keptAsSongIn: String?,
    val onOpenPlaylist: (String) -> Unit,
)

/** The cover: wide for a video, square for a song, with the LIVE and length badges. */
@Composable
internal fun SingleCover(media: MediaInfo) {
    val square = isSong(media)
    Box((if (square) Modifier.size(88.dp) else Modifier.width(128.dp).aspectRatio(16f / 9f)).clip(MaterialTheme.shapes.medium).testTag(UiTags.REVIEW_ARTWORK)) {
        MediaThumb(media.thumbnail, Modifier.fillMaxSize(), audio = media.video.isEmpty(), shape = MaterialTheme.shapes.medium)
        if (media.isLive) ArtworkBadge("LIVE", Modifier.align(Alignment.BottomStart).padding(4.dp), live = true)
        if (!square) Format.duration(media.duration)?.let { ArtworkBadge(it, Modifier.align(Alignment.BottomEnd).padding(4.dp)) }
    }
}

/** One line of facts: where it is from, what is special about it, who made it and how long it is. */
internal fun factsOf(media: MediaInfo): String {
    val best = media.video.maxOfOrNull { it.height } ?: 0
    val by = media.music?.artist ?: media.uploader
    val languages = media.subtitles.map { it.lang }.distinct().size
    return listOfNotNull(
        media.provider, "Live".takeIf { media.isLive }, if (best >= 4320) "8K" else if (best >= 2160) "4K" else null, "HDR".takeIf { media.video.any { it.hdr } },
        by?.takeIf { it.isNotBlank() }, media.music?.album, media.music?.releaseYear?.toString(),
        if (isSong(media)) Format.duration(media.duration) else null,
        if (media.chapters > 0) "${media.chapters} chapters" else null, if (languages > 0) "$languages subtitle ${if (languages == 1) "language" else "languages"}" else null,
    ).joinToString(" · ")
}

/** The main tab of one link: Video | Audio | Both, the notes that matter, then the sizes. */
@Composable
fun SingleMain(ui: SingleUi) {
    val media = ui.media
    val choice = ui.choice
    val hasVideo = media.video.isNotEmpty()
    val mode = when { hasVideo && !choice.audioMode -> PreviewMode.VIDEO; ui.bothOn -> PreviewMode.BOTH; else -> PreviewMode.AUDIO }
    fun pick(next: PreviewMode) {
        ui.onBoth(next == PreviewMode.BOTH)
        ui.onChoice(choice.copy(audioMode = next != PreviewMode.VIDEO))
    }
    val modes = listOfNotNull(PreviewMode.VIDEO.takeIf { hasVideo }, PreviewMode.AUDIO, PreviewMode.BOTH.takeIf { ui.both != BothOffer.Off })
    val joined = if (mode != PreviewMode.AUDIO) ui.songOfLink else null
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        if (modes.size > 1) ModePicker(modes, mode, ::pick) { it != PreviewMode.BOTH || ui.both is BothOffer.Found }
        if (mode == PreviewMode.AUDIO) when (ui.both) {
            BothOffer.Looking -> Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Hint(PreviewWords.BOTH_LOOKING, UiTags.CAPTURE_VIDEO_LOOKING, Modifier)
            }
            BothOffer.Missing -> Hint(PreviewWords.BOTH_NONE, UiTags.BOTH_OFF_REASON, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            else -> {}
        }
        media.match?.let { match ->
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Text(Restriction.matchCaption(match), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                match.matchedTitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        media.playlistUrl?.let { playlist ->
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Part of a playlist", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { ui.onOpenPlaylist(playlist) }, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.OPEN_PLAYLIST)) {
                    Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Open whole playlist")
                }
            }
        }
        if (joined != null) Hint(if (joined.hasVideo) MusicVideoRows.HAS_VIDEO_NOTE else MusicVideoRows.JOIN_NOTE, UiTags.CAPTURE_SONG_NOTE, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        else if (ui.existingIn != null) ExistingNotice(ui.existingIn, ui.onPlayExisting, Modifier.padding(horizontal = 16.dp))
        val found = ui.both as? BothOffer.Found
        if (mode == PreviewMode.BOTH && found != null) {
            if (!found.own) FoundVideo(found)
            Hint(if (found.own) PreviewWords.BOTH_LINK else PreviewWords.BOTH_FOUND, UiTags.CAPTURE_LINK_VIDEO, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        SizeList(media, choice, ui.onChoice, audio = mode != PreviewMode.VIDEO)
        if (ui.keptAsSongIn != null && ui.songOfLink == null) Hint(
            "Kept in ${ui.keptAsSongIn} as a song you can also watch: one file with the picture and the sound.",
            UiTags.KEPT_AS_SONG, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Hint(text: String, tag: String, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.testTag(tag))
}

/** Both with an official video: its picture, title, channel, views and length. */
@Composable
private fun FoundVideo(found: BothOffer.Found) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(UiTags.CAPTURE_VIDEO), verticalAlignment = Alignment.CenterVertically) {
        MediaThumb(found.thumbnail, Modifier.width(96.dp).aspectRatio(16f / 9f), audio = false)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(found.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            found.details?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(UiTags.CAPTURE_VIDEO_DETAILS)) }
        }
    }
}

/** The link is already in the library: say so and offer Play; the footer's action becomes an explicit "Download anyway". */
@Composable
fun ExistingNotice(space: String, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(bottom = 8.dp).testTag(UiTags.CAPTURE_EXISTING), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Studio.colors.success, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("Already in $space", style = MaterialTheme.typography.titleSmall)
            Text("Downloading again keeps a second copy.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onPlay, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.CAPTURE_PLAY_EXISTING)) { Text("Play") }
    }
}
