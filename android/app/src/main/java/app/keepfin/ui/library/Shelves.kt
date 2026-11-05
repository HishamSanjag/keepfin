package app.keepfin.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.keepfin.core.playback.Playback
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.ui.UiTags
import app.keepfin.ui.theme.tabular

/** The two artwork shapes a shelf can have. A shelf never mixes them (dev-docs/plans/MAIN-REDESIGN.md section 3). */
enum class ShelfShape { VIDEO, SQUARE }

/** One shelf's worth of items, all of the same [shape]. */
data class ShelfGroup(val shape: ShelfShape, val items: List<LibraryItem>) {
    /** "videos" for 16:9 shelves; "music", "photos" or "music and photos" for the square ones. */
    val noun: String get() = when (shape) {
        ShelfShape.VIDEO -> "videos"
        ShelfShape.SQUARE -> {
            val photos = items.any { it.kind == LibraryItemKind.IMAGE }
            val music = items.any { it.kind != LibraryItemKind.IMAGE }
            if (photos && music) "music and photos" else if (photos) "photos" else "music"
        }
    }
}

object Shelves {
    fun shapeOf(item: LibraryItem): ShelfShape = if (item.kind == LibraryItemKind.VIDEO) ShelfShape.VIDEO else ShelfShape.SQUARE

    /** Mixed content split into at most two shelves, in order of first appearance; each keeps the incoming order. */
    fun split(items: List<LibraryItem>): List<ShelfGroup> {
        if (items.isEmpty()) return emptyList()
        val byShape = items.groupBy(::shapeOf)
        return byShape.entries.map { (shape, list) -> ShelfGroup(shape, list) }
    }
}

/**
 * A home shelf for [items] that keeps one shape per row: video items in a 16:9 row, music and photos in a square row. When both
 * kinds are present two shelves are drawn ("Recently added videos", "Recently added music"); the first keeps [key] and its
 * test tag so existing selectors keep working.
 */
internal fun LazyListScope.itemShelf(
    key: String, title: String, items: List<LibraryItem>, ui: LibraryUi, onSeeAll: () -> Unit, headModifier: Modifier = Modifier,
) {
    val groups = Shelves.split(items)
    groups.forEachIndexed { index, group ->
        val groupKey = if (index == 0) key else "$key-${group.shape.name.lowercase()}"
        val groupTitle = if (groups.size > 1) "$title ${group.noun}" else title
        shelf(groupKey, groupTitle, onSeeAll, if (index == 0) headModifier else Modifier) {
            items(group.items, key = { "$groupKey-${it.id}" }) { item -> ShelfCard(item, ui, items) }
        }
    }
}

/** Shared by every Continue card, so no card allocates a Brush while it composes or scrolls. */
private val ContinueScrim = Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = .78f))

/** "4 min left" for a started video ("0:11 left" under a minute), else null. */
internal fun timeLeftLabel(item: LibraryItem): String? =
    if (!item.watched && (item.position ?: 0.0) > 5.0 && item.duration != null) Playback.timeLeft(item.duration - (item.position ?: 0.0)) else null

/**
 * Continue watching: a wide 16:9 card with the artwork, an outlined play button, a progress bar and the time left over it,
 * and the title and details under it. Tapping anywhere resumes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ContinueCard(item: LibraryItem, ui: LibraryUi, list: List<LibraryItem>) {
    val progress = LibraryViews.progress(item)
    val left = timeLeftLabel(item)
    Column(
        Modifier.width(220.dp).clip(RoundedCornerShape(12.dp))
            .combinedClickable(role = Role.Button, onClickLabel = "Resume", onLongClickLabel = "More actions", onLongClick = { ui.sheet(LibrarySheet.Actions(item)) }, onClick = { ui.vm.play(list, list.indexOf(item)) })
            .testTag(UiTags.libItem(item.id)),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            ItemArt(item, Modifier.fillMaxSize(), RoundedCornerShape(12.dp), iconSize = 32.dp)
            // The scrim keeps white text and the play button readable over any artwork.
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(ContinueScrim))
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = .42f)).border(1.5.dp, Color.White, CircleShape).testTag("continue-play-${item.id}"),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                Box(Modifier.weight(1f))
                if (left != null) Text(left, style = MaterialTheme.typography.labelMedium.tabular(), color = Color.White, maxLines = 1, modifier = Modifier.testTag("continue-left-${item.id}"))
            }
            if (progress > 0f && !item.watched) LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 6.dp).height(4.dp).clip(CircleShape)
                    .semantics { contentDescription = "Watched ${(progress * 100).toInt()} percent" }.testTag("continue-progress-${item.id}"),
                drawStopIndicator = {}, trackColor = Color.White.copy(alpha = .35f),
            )
            // ✕ takes the card off Continue watching (a 48dp target drawn as a small circle in the corner)
            IconButton(onClick = { ui.vm.removeFromContinue(item) }, modifier = Modifier.align(Alignment.TopEnd).testTag("continue-remove-${item.id}")) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = .55f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Close, contentDescription = "Remove ${item.title} from Continue watching", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(subtitleOf(item), style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A 16:9 video card for the other shelves: artwork with duration, a thin progress bar when started, title and details under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShelfPoster(item: LibraryItem, ui: LibraryUi, list: List<LibraryItem>) {
    val progress = LibraryViews.progress(item)
    Column(
        Modifier.width(180.dp).clip(RoundedCornerShape(12.dp))
            .combinedClickable(role = Role.Button, onClickLabel = "Play", onLongClickLabel = "More actions", onLongClick = { ui.sheet(LibrarySheet.Actions(item)) }, onClick = { ui.vm.play(list, list.indexOf(item)) })
            .testTag(UiTags.libItem(item.id)),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            ItemArt(item, Modifier.fillMaxSize(), RoundedCornerShape(12.dp), iconSize = 28.dp)
            app.keepfin.ui.Format.duration(item.duration)?.let { app.keepfin.ui.components.ArtworkBadge(it, Modifier.align(Alignment.BottomEnd).padding(8.dp)) }
            if (progress > 0f && !item.watched) LinearProgressIndicator(
                progress = { progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp).semantics { contentDescription = "Watched ${(progress * 100).toInt()} percent" },
                drawStopIndicator = {}, trackColor = Color.Black.copy(alpha = .3f),
            )
        }
        Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(timeLeftLabel(item) ?: subtitleOf(item), style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A card for an item in a shelf of one shape: a 16:9 poster for videos, a square cover for songs and photos. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ShelfCard(item: LibraryItem, ui: LibraryUi, list: List<LibraryItem>) {
    if (item.kind == LibraryItemKind.VIDEO) ShelfPoster(item, ui, list)
    else CoverCard(
        item.title, subtitleOf(item).substringBefore(" · ").ifBlank { item.kind.wire }, item.artModel(), item.album ?: item.title, item.kind,
        Modifier.width(120.dp).testTag(UiTags.libItem(item.id)),
        onLongClick = { ui.sheet(LibrarySheet.Actions(item)) }, longClickLabel = "More actions",
    ) { ui.vm.play(list, list.indexOf(item)) }
}
