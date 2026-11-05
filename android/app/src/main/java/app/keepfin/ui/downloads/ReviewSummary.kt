package app.keepfin.ui.downloads

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.keepfin.ui.CollectionReview
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.MediaThumb
import app.keepfin.ui.components.Pane

/** The closed preview: the sheet was hidden, the link and the choices are kept. Review reopens it; the X discards it. */
@Composable
fun ReviewSummary(
    title: String, thumbnail: String?, square: Boolean, audio: Boolean, note: String,
    onReopen: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier,
) {
    Pane(modifier.testTag(UiTags.REVIEW_SUMMARY), contentPadding = PaddingValues(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MediaThumb(thumbnail, if (square) Modifier.size(48.dp) else Modifier.width(80.dp).aspectRatio(16f / 9f), audio = audio)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onReopen, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.REVIEW_REOPEN)) { Text("Review") }
            IconButton(onClick = onClear) { Icon(Icons.Outlined.Close, contentDescription = "Discard this preview") }
        }
    }
}

/** A collection under review whose sheet was closed: what was found and chosen, kept until its X discards it. */
@Composable
fun CollectionSummary(review: CollectionReview, onOpen: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    Pane(modifier.testTag(UiTags.COLLECTION_RESUME), contentPadding = PaddingValues(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.PlaylistPlay, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(review.title ?: "Collection", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull("${review.entries.size} found", "${review.selectedCount} selected", "searching…".takeIf { review.discovering }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text("Review") }
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, contentDescription = "Close collection") }
        }
    }
}
