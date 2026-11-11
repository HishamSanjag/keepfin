package app.keepfin.ui.media

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.core.layout.CardKind
import app.keepfin.core.playback.Playback
import app.keepfin.core.spaces.Songs
import app.keepfin.ui.Format
import app.keepfin.ui.UiTags
import app.keepfin.ui.library.ItemArt
import app.keepfin.ui.library.ItemMenu
import app.keepfin.ui.library.LibrarySheet
import app.keepfin.ui.library.LibraryUi
import app.keepfin.ui.library.LibraryViews
import app.keepfin.ui.library.subtitleOf

/** The card shape of a library item: videos are wide, photos keep their own shape, everything else is a square cover. */
internal fun cardKindOf(item: LibraryItem): CardKind = when (item.kind) {
    LibraryItemKind.VIDEO -> CardKind.Video
    LibraryItemKind.IMAGE -> CardKind.Photo
    else -> CardKind.Square
}

/** "4 min left" for a started video ("0:11 left" under a minute), else null. */
internal fun timeLeftLabel(item: LibraryItem): String? =
    if (!item.watched && (item.position ?: 0.0) > 5.0 && item.duration != null) Playback.timeLeft(item.duration - (item.position ?: 0.0)) else null

private fun infoOf(item: LibraryItem, videoSong: Boolean): String? = when (item.kind) {
    LibraryItemKind.VIDEO -> timeLeftLabel(item) ?: subtitleOf(item)
    LibraryItemKind.IMAGE -> subtitleOf(item)
    else -> (if (videoSong) Songs.VIDEO_TAG + " · " else "") + (item.artist ?: item.album.orEmpty())
}

/**
 * A library item as a card. In a [selectable] page (a full grid) a long press starts a selection and the card has its menu; elsewhere
 * a long press opens the item's actions.
 */
@Composable
fun ItemCard(
    item: LibraryItem,
    ui: LibraryUi,
    modifier: Modifier = Modifier,
    selectable: Boolean = false,
    overlay: (@Composable BoxScope.() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val selection = ui.selection
    val selecting = selectable && selection.mode
    val kind = cardKindOf(item)
    val progress = if (kind == CardKind.Video) LibraryViews.progress(item) else 0f
    MediaCard(
        kind = kind, title = item.title, info = infoOf(item, ui.vm.isVideoSong(item)),
        actions = CardActions(
            onClick = { if (selecting) selection.toggle(item.id) else onClick() },
            onLongClick = { if (selectable) selection.start(item.id) else ui.sheet(LibrarySheet.Actions(item)) },
            longClickLabel = if (selectable) "Select" else "More actions",
        ),
        modifier = modifier.testTag(UiTags.libItem(item.id)),
        aspect = photoWidth(1f, item.width, item.height),
        marks = CardMarks(progress = if (item.watched) 0f else progress, badge = Format.duration(item.duration).takeIf { kind == CardKind.Video }, watched = kind == CardKind.Video && item.watched),
        selected = if (selecting) item.id in selection.ids else null,
        overlay = overlay,
        trailing = if (selectable && !selecting) ({ ItemMenu(item, ui) }) else null,
    ) { ItemArt(item, Modifier.fillMaxSize(), iconSize = 32.dp) }
}
