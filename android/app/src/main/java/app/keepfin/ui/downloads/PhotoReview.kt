package app.keepfin.ui.downloads

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.keepfin.MediaInfo
import app.keepfin.PhotoPosts
import app.keepfin.ui.devices.DownloadTarget
import app.keepfin.core.gallery.GalleryItem
import app.keepfin.ui.FormatChoice
import app.keepfin.ui.UiTags
import androidx.compose.ui.graphics.Color
import app.keepfin.ui.components.MediaThumb
import app.keepfin.ui.theme.Studio

/** The Download button of a photo post: "Download 6 photos", or "Download 2 photos, 8 videos" for a mixed post. */
internal fun photoDownloadLabel(media: MediaInfo, choice: FormatChoice): String {
    val count = media.gallery?.count ?: 0
    return "Download " + PhotoPosts.countLabel(media, PhotoPosts.chosen(count, choice.leftOut))
}

/** A photo post's preview (the approved grid): everything chosen, a tap leaves a photo out, Download says how many. */
@Composable
internal fun ColumnScope.PhotoPreview(
    media: MediaInfo, choice: FormatChoice, onChoice: (FormatChoice) -> Unit, saveTo: SaveToUi?, adding: Boolean, onDownload: () -> Unit, onClose: () -> Unit,
    targets: List<DownloadTarget> = emptyList(), onDownloadOn: (DownloadTarget) -> Unit = {},
) {
    val gallery = media.gallery ?: return
    val chosen = PhotoPosts.chosen(gallery.count, choice.leftOut)
    PreviewLayout(
        header = PreviewHeader(
            cover = { PhotoCover(media) }, title = media.title,
            facts = listOfNotNull(media.provider, media.uploader?.takeIf { it.isNotBlank() }).joinToString(" · "),
        ),
        onClose = onClose, mainLabel = "Photos · ${gallery.count}", extras = null,
        footer = {
            PreviewFooter(
                saveTo = saveTo, playlist = null, targets = targets, onDownloadOn = onDownloadOn,
                label = photoDownloadLabel(media, choice), onDownload = onDownload, enabled = chosen.isNotEmpty(), adding = adding,
            )
        },
    ) { PhotoMain(media, choice, onChoice) }
}

@Composable
private fun PhotoCover(media: MediaInfo) {
    Box(Modifier.width(88.dp).aspectRatio(1f).clip(MaterialTheme.shapes.medium).testTag(UiTags.REVIEW_ARTWORK)) {
        MediaThumb(media.thumbnail, Modifier.fillMaxSize(), shape = MaterialTheme.shapes.medium)
    }
}

/** The main tab of a photo post: the line that says what is chosen, then every file in a 3-column grid. */
@Composable
fun PhotoMain(media: MediaInfo, choice: FormatChoice, onChoice: (FormatChoice) -> Unit) {
    val gallery = media.gallery ?: return
    val left = choice.leftOut.toSet()
    val chosen = PhotoPosts.chosen(gallery.count, left).size
    LazyVerticalGrid(
        columns = GridCells.Fixed(3), modifier = Modifier.fillMaxSize().testTag(UiTags.PHOTO_GRID),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                PhotoPosts.note(chosen, gallery.count, gallery.truncated), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp).testTag(UiTags.PHOTO_NOTE).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        items(gallery.items, key = { it.position }) { item -> PhotoTile(item, item.position !in left) { onChoice(choice.toggled(item.position)) } }
    }
}

/** One file of the post: a square that is a 48dp-safe switch (the whole tile), with a check while it is chosen. */
@Composable
private fun PhotoTile(item: GalleryItem, chosen: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.medium)
            .toggleable(value = chosen, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = PhotoPosts.tileDescription(item.position, item.photo, chosen) }
            .testTag(UiTags.photoTile(item.position)),
    ) {
        // A photo is its own picture; a video shows the post's cover for it (when there is one), always with a play mark.
        val picture = if (item.photo) item.url else item.cover
        if (picture != null) MediaThumb(picture, Modifier.fillMaxSize().alpha(if (chosen) 1f else .4f), shape = MaterialTheme.shapes.medium)
        else Box(Modifier.fillMaxSize().alpha(if (chosen) 1f else .4f).background(MaterialTheme.colorScheme.surfaceContainerHighest))
        if (!item.photo) Box(
            Modifier.align(Alignment.BottomStart).padding(6.dp).size(24.dp).clip(CircleShape).background(Color.Black.copy(alpha = .6f)),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.White) }
        Box(
            Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape)
                .background(if (chosen) Studio.colors.action else MaterialTheme.colorScheme.surface.copy(alpha = .7f)),
            contentAlignment = Alignment.Center,
        ) { if (chosen) Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = Studio.colors.onAction) }
    }
}
