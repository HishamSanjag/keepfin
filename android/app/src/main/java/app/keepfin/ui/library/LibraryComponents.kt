package app.keepfin.ui.library

import androidx.compose.material.icons.outlined.ContentCopy
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.sp
import app.keepfin.ui.components.SelectBox
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.keepfin.LibraryItem
import app.keepfin.ItemQuery
import app.keepfin.LibraryBrowse
import app.keepfin.LibraryItemKind
import app.keepfin.Space
import app.keepfin.ui.Format
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.studioImageLoader
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.theme.tabular
import coil3.compose.AsyncImage
import coil3.request.ImageRequest as CoilRequest
import coil3.video.videoFramePercent
import java.io.File

/** What a library item shows as artwork: its cover or poster file, else the cover inside the song or video (or a frame) / the photo itself. */
fun LibraryItem.artModel(): Any? = art?.takeIf { it.isNotBlank() }?.let(::File)
    ?: when (kind) {
        LibraryItemKind.VIDEO, LibraryItemKind.AUDIO -> uri.takeIf { it.isNotBlank() }?.let { app.keepfin.ui.components.MediaCover(Uri.parse(it), modifiedAt) }
        LibraryItemKind.IMAGE -> Uri.parse(uri)
        else -> null
    }

/** The picture for a group of items (an album, a playlist): an item's own cover file first, else the first cover read from a file. */
fun List<LibraryItem>.coverModel(): Any? = firstOrNull { !it.art.isNullOrBlank() }?.artModel() ?: firstNotNullOfOrNull { it.artModel() }

fun LibraryItemKind.icon(): ImageVector = when (this) {
    LibraryItemKind.AUDIO -> Icons.Outlined.MusicNote
    LibraryItemKind.VIDEO -> Icons.Outlined.Movie
    LibraryItemKind.IMAGE -> Icons.Outlined.Image
    LibraryItemKind.OTHER -> Icons.Outlined.Folder
}

private val tints = listOf(0xFF456FEA, 0xFF7C5CFF, 0xFF1FA67A, 0xFFE08A2E, 0xFFD2557A, 0xFF2E9FC5).map(::Color)

/** Cover tile: a tinted placeholder (stable per title) with the artwork fading in over it once decoded. Never reloads on recomposition. */
@Composable
fun ArtTile(
    model: Any?,
    seed: String,
    kind: LibraryItemKind,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.small,
    iconSize: Dp = 24.dp,
    /**
     * A fixed decode size in pixels for artwork that changes size or appears anew while it moves (the player's cover flying
     * between the bar and the cover pane): the memory cache answers on the first frame instead of after layout, so the cover
     * never flashes to the placeholder. Every player tile uses the same value and shares one cached picture.
     */
    fixedPx: Int? = null,
) {
    val context = LocalContext.current
    val loader = studioImageLoader()
    val request = remember(model, fixedPx) {
        model?.let { CoilRequest.Builder(context).data(it).videoFramePercent(0.1).apply { if (fixedPx != null) size(fixedPx) }.build() }
    }
    val base = MaterialTheme.colorScheme.surfaceContainerHighest
    // Designed fallback cover: a calm gradient from a tone picked by the title, with the title's initials (big tiles) or a type glyph.
    val gradient = remember(seed, base) {
        val tone = tints[(seed.hashCode() and 0x7fffffff) % tints.size]
        Brush.linearGradient(listOf(tone.copy(alpha = .46f).compositeOver(base), tone.copy(alpha = .16f).compositeOver(base)))
    }
    val initials = remember(seed) { coverInitials(seed) }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier.clip(shape).background(gradient), contentAlignment = Alignment.Center) {
        val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f)
        if (initials != null && minOf(maxWidth, maxHeight) >= 72.dp) {
            Text(initials, color = ink, fontFamily = app.keepfin.ui.theme.ManropeFamily, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold, fontSize = (minOf(maxWidth, maxHeight).value * 0.3f).sp)
        } else Icon(kind.icon(), contentDescription = null, tint = ink, modifier = Modifier.size(iconSize))
        if (request != null) AsyncImage(model = request, contentDescription = null, imageLoader = loader, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** One or two capital letters for a fallback cover: the first letters of the first two words; null when the title has none. */
internal fun coverInitials(title: String): String? {
    val words = title.split(' ', '-', '_', '.').filter { w -> w.any { it.isLetterOrDigit() } }
    if (words.isEmpty()) return null
    return words.take(2).mapNotNull { w -> w.firstOrNull { it.isLetterOrDigit() } }.joinToString("").uppercase().ifBlank { null }
}

@Composable
fun ItemArt(item: LibraryItem, modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.small, iconSize: Dp = 24.dp) =
    ArtTile(item.artModel(), item.album ?: item.title, item.kind, modifier, shape, iconSize)

// ---- screen-wide handles --------------------------------------------------------------------------------

/** Compact (the default) or Comfortable rows and grids for the page being drawn; chosen per space in its overflow menu. */
val LocalCompact = compositionLocalOf { true }

/**
 * False on surfaces that have no selection bar (Home, Search): there a long-press opens the item action sheet instead of
 * starting a multi-select.
 */
val LocalSelectable = compositionLocalOf { true }

/** One of your own collections, by id and name. */
data class OwnCollection(val id: String, val name: String)

/** The collection of yours a page lists (null elsewhere): its rows' actions then offer "Remove from" it. */
val LocalOwnCollection = compositionLocalOf<OwnCollection?> { null }

/** Multi-select state of the Library; cleared when the route changes. */
@Stable
class Selection {
    var mode by mutableStateOf(false)
    val ids = mutableStateListOf<String>()
    /** What the page shows right now after filters and search: exactly what "Select all" covers. */
    var scope by mutableStateOf<List<LibraryItem>>(emptyList())
        private set
    /** A page that reads its rows in pages publishes its query and how many rows it has: "Select all" then reads the ids of the query. */
    var scopeQuery by mutableStateOf<ItemQuery?>(null)
        private set
    var scopeCount by mutableIntStateOf(0)
        private set
    /** Reads up to [limit] ids of a query; set by the library host (the index answers it). */
    var resolveIds: (ItemQuery, Int) -> List<String> = { _, _ -> emptyList() }
    /** Runs [work] off the main thread and then [done] on it; set by the library host. */
    var background: (work: () -> Unit, done: () -> Unit) -> Unit = { work, done -> work(); done() }
    /** Called by every selectable page with the list it displays; identity-checked so recomposition stays cheap. */
    fun publish(visible: List<LibraryItem>) { if (scope !== visible || scopeQuery != null) { scope = visible; scopeQuery = null; scopeCount = 0 } }
    /** Called by a page that shows [count] rows of [query]. */
    fun publishQuery(query: ItemQuery, count: Int) { if (scopeQuery != query || scopeCount != count) { scopeQuery = query; scopeCount = count; if (scope.isNotEmpty()) scope = emptyList() } }
    val allSelected: Boolean get() = scopeQuery?.let { scopeCount > 0 && ids.size >= scopeCount } ?: (scope.isNotEmpty() && scope.all { it.id in ids })
    /** Select all visible items, or none when they already are all selected. */
    fun toggleAll() {
        if (allSelected) { ids.clear(); return }
        val query = scopeQuery
        if (query == null) scope.forEach { if (it.id !in ids) ids.add(it.id) }
        else {
            var found: List<String> = emptyList()
            background({ found = resolveIds(query, SELECT_ALL_LIMIT) }) { for (id in found) if (id !in ids) ids.add(id) }
        }
    }
    /** True when there is something to select. */
    val hasScope: Boolean get() = scopeQuery?.let { scopeCount > 0 } ?: scope.isNotEmpty()
    /** Drops selected ids whose item left the library (deleted, moved out of reach): counts and bulk actions only cover what exists. */
    fun prune(alive: Set<String>) {
        if (ids.any { it !in alive }) {
            ids.removeAll { it !in alive }
            if (ids.isEmpty()) mode = false
        }
    }
    companion object { const val SELECT_ALL_LIMIT = 100_000 }
    fun toggle(id: String) { if (id in ids) ids.remove(id) else ids.add(id); if (ids.isEmpty() && !mode) mode = false }
    fun start(id: String) { mode = true; if (id !in ids) ids.add(id) }
    fun clear() { ids.clear(); mode = false }
}

/** Sheets and dialogs the Library can raise. */
sealed interface LibrarySheet {
    /** The item action sheet (⋯ and long-press from Home and Search; ⋯ everywhere). */
    data class Actions(val item: LibraryItem, val from: OwnCollection? = null) : LibrarySheet
    data class AddToPlaylist(val ids: List<String>) : LibrarySheet
    data class NewPlaylist(val ids: List<String>, val space: String? = null) : LibrarySheet
    data class MoveTo(val ids: List<String>) : LibrarySheet
    data class Rename(val item: LibraryItem) : LibrarySheet
    data class EditInfo(val item: LibraryItem) : LibrarySheet
    data class Delete(val ids: List<String>, val title: String?) : LibrarySheet
    data class RenamePlaylist(val id: String, val name: String, val ids: List<String>) : LibrarySheet
    data class DeletePlaylist(val id: String, val name: String) : LibrarySheet
    /** Confirms deleting the music video file beside a song (the song stays). */
    data class RemoveMusicVideo(val item: LibraryItem) : LibrarySheet
    data class EditSpace(val space: Space?) : LibrarySheet
    data class RemoveSpace(val space: Space) : LibrarySheet
    data class Info(val item: LibraryItem) : LibrarySheet
    /** The library picker of "Add" on a folder space. */
    data class AddFromLibrary(val space: Space) : LibrarySheet
    /** "From a link" on a folder space: paste a link, then review it with Save to on this space. */
    data class AddFromLink(val space: Space) : LibrarySheet
    /** "Move to space…" / "Copy to space…" for whole collections and single items: pick a folder space, then run libraryAddFrom. */
    data class ToSpace(val collectionIds: List<String>, val itemIds: List<String>, val copy: Boolean, val fromSpace: String?, val title: String) : LibrarySheet
    /** Follow settings of a downloaded collection. */
    data class Follow(val collectionId: String) : LibrarySheet
    /** The user's own tags on the selected items. */
    data class Tags(val ids: List<String>) : LibrarySheet
    /** Create a smart space from a tag filter: opens the New space sheet as a smart space with these rules. */
    data class NewSmart(val name: String, val rules: app.keepfin.SmartRules) : LibrarySheet
}

/** What a play, shuffle or queue action applies to: a list that is already loaded, or the rows of a query (read when the action runs). */
sealed interface Songs {
    class Fixed(val items: List<LibraryItem>) : Songs
    class Rows(val query: ItemQuery, val count: Int) : Songs
}

val Songs.size: Int get() = when (this) { is Songs.Fixed -> items.size; is Songs.Rows -> count }
val Songs.isEmpty: Boolean get() = size == 0

/** At most this many songs go into a queue when a whole list is played. */
const val PLAY_LIMIT = 1000

/** Everything list rows need to act: the view model, the raised sheet, selection and the file picker. */
@Stable
class LibraryUi(
    val vm: StudioViewModel,
    val context: Context,
    val sheet: (LibrarySheet) -> Unit,
    val selection: Selection,
    val addFiles: (String) -> Unit,
) {
    val browse: LibraryBrowse get() = vm.lib.browse

    /** One item by id: a read of the index. Call it from an event handler or a background effect, not while composing. */
    fun item(id: String): LibraryItem? = browse.item(id)
    fun items(ids: List<String>): List<LibraryItem> = browse.byIds(ids)

    /**
     * Plays [item] with the rows of [query] around it as the queue: [QUEUE_BEFORE] before it and [QUEUE_AFTER] from it on. The
     * window is read off the main thread, so a list of 200,000 songs queues the neighbourhood, not the whole list.
     */
    fun playFrom(query: ItemQuery, item: LibraryItem) {
        if (item.kind != LibraryItemKind.AUDIO && item.kind != LibraryItemKind.IMAGE && !vm.isVideoSong(item)) { vm.play(listOf(item), 0); return }
        vm.playWindow(query, item)
    }

    /** Plays (or shuffles) the start of [query]. */
    fun playQuery(query: ItemQuery, shuffle: Boolean = false) = vm.playQuery(query, shuffle)

    /** The songs behind [songs]: the list itself, or up to [PLAY_LIMIT] rows of its query read off the main thread. [then] runs on the main thread. */
    fun withSongs(songs: Songs, then: (List<LibraryItem>) -> Unit) {
        when (songs) {
            is Songs.Fixed -> then(songs.items)
            is Songs.Rows -> {
                val load = { browse.top(songs.query.copy(kinds = setOf(LibraryItemKind.AUDIO, LibraryItemKind.VIDEO)), PLAY_LIMIT) }
                if (browse.cheap) then(load())
                else vm.viewModelScope.launch { then(withContext(Dispatchers.IO) { load() }) }
            }
        }
    }

    /** Plays the first [PLAY_LIMIT] audio and video items among [ids] (a collection's items), read off the main thread. */
    fun playIds(ids: List<String>) {
        val load = { browse.byIds(ids.take(PLAY_LIMIT)).filter { it.kind == LibraryItemKind.AUDIO || it.kind == LibraryItemKind.VIDEO } }
        if (browse.cheap) vm.playAudio(load())
        else vm.viewModelScope.launch { vm.playAudio(withContext(Dispatchers.IO) { load() }) }
    }

    fun play(songs: Songs) = withSongs(songs) { list -> vm.player.setShuffle(false); vm.playAudio(list) }
    fun shuffle(songs: Songs) = withSongs(songs) { list -> vm.shuffle(list) }
    fun playNext(songs: Songs) = withSongs(songs) { list -> vm.playNext(list) }
    fun addToQueue(songs: Songs) = withSongs(songs) { list -> vm.addToQueue(list) }
    fun addToPlaylist(songs: Songs) = withSongs(songs) { list -> sheet(LibrarySheet.AddToPlaylist(list.filter { it.kind == LibraryItemKind.AUDIO || it.kind == LibraryItemKind.VIDEO }.map { it.id })) }

    /** "Add" on a space: the library picker for a folder space, the rules editor for a smart space. */
    fun addTo(space: Space) {
        if (space.smart) vm.openRules(space, false) else sheet(LibrarySheet.AddFromLibrary(space))
    }
}

// ---- rows ---------------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ItemRow(
    item: LibraryItem,
    ui: LibraryUi,
    subtitle: String,
    modifier: Modifier = Modifier,
    leadingNumber: String? = null,
    playing: Boolean = false,
    onClick: () -> Unit,
    extra: @Composable RowScope.() -> Unit = {},
) {
    val selection = ui.selection
    val picked = item.id in selection.ids
    val compact = LocalCompact.current
    val selectable = LocalSelectable.current
    val from = LocalOwnCollection.current
    // a video kept in a music space looks and plays like a song, with a Video tag
    val videoSong = ui.vm.isVideoSong(item)
    val video = item.kind == LibraryItemKind.VIDEO && !videoSong
    val progress = if (video) LibraryViews.progress(item) else 0f
    Column(
        modifier.fillMaxWidth()
            .combinedClickable(
                role = Role.Button, onClickLabel = if (selection.mode) "Select" else "Play",
                onClick = { if (selection.mode) selection.toggle(item.id) else onClick() },
                onLongClickLabel = if (selectable) "Select" else "More actions",
                onLongClick = { if (selectable) selection.start(item.id) else ui.sheet(LibrarySheet.Actions(item, from)) },
            )
            .semantics { if (selection.mode) { selected = picked; stateDescription = if (picked) "Selected" else "Not selected" } }
            .testTag(UiTags.libItem(item.id)),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = if (compact) 56.dp else 64.dp).padding(start = 16.dp, end = 0.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selection.mode) { SelectBox(picked, Modifier.padding(end = 12.dp)) }
            if (leadingNumber != null) {
                Text(leadingNumber, style = MaterialTheme.typography.bodyMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
            } else {
                Box(Modifier.width(if (video) (if (compact) 80.dp else 88.dp) else (if (compact) 44.dp else 52.dp)).aspectRatio(if (video) 16f / 9f else 1f)) {
                    ItemArt(item, Modifier.fillMaxSize())
                    if (playing) {
                        // Only the current song subscribes to the player; the bars move while it plays and settle to dots when paused.
                        val audible = ui.vm.player.ui.collectAsStateWithLifecycle().value.playing
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .45f)), contentAlignment = Alignment.Center) {
                            NowPlayingBars(audible, Color.White, Modifier.semantics { contentDescription = "Now playing" }, size = 24.dp)
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    item.title, style = MaterialTheme.typography.bodyLarge.let { if (compact) it.copy(fontSize = 15.sp, lineHeight = 20.sp) else it },
                    maxLines = if (video) 2 else 1, overflow = TextOverflow.Ellipsis,
                    color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.watched) { Icon(Icons.Outlined.CheckCircle, contentDescription = "Watched", tint = Studio.colors.success, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
                    if (videoSong) {
                        Text(
                            app.keepfin.core.spaces.Songs.VIDEO_TAG, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.testTag(UiTags.videoTag(item.id)).background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.extraSmall).padding(horizontal = 6.dp, vertical = 1.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(subtitle, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (item.remote) RemoteHint(item.id, ui.vm.whereSource)
            }
            extra()
            if (item.favorite) Icon(Icons.Filled.Favorite, contentDescription = "Favorite", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 6.dp).size(16.dp))
            if (!selection.mode) ItemMenu(item, ui) else Spacer(Modifier.width(16.dp))
        }
        if (progress > 0f && !item.watched) LinearProgressIndicator(
            progress = { progress }, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp).height(3.dp).semantics { contentDescription = "Watched ${(progress * 100).toInt()} percent" },
            drawStopIndicator = {},
        )
    }
}

/** The ⋮ button: opens the one item action sheet (the same actions from Home, Search, the Library and the album pages). */
@Composable
fun ItemMenu(item: LibraryItem, ui: LibraryUi, modifier: Modifier = Modifier) {
    val from = LocalOwnCollection.current
    Box(modifier) {
        IconButton(onClick = { ui.sheet(LibrarySheet.Actions(item, from)) }, modifier = Modifier.testTag(UiTags.libMenu(item.id)).semantics { contentDescription = "More actions for ${item.title}" }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = null)
        }
    }
}

// ---- cards (home rows, grids) -----------------------------------------------------------------------------

/** A square cover card for songs and albums. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CoverCard(
    title: String,
    subtitle: String,
    model: Any?,
    seed: String,
    kind: LibraryItemKind,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String = "Select",
    popup: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(
        modifier.clip(MaterialTheme.shapes.medium)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick, onLongClickLabel = if (onLongClick != null) longClickLabel else null)
            .padding(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            ArtTile(model, seed, kind, Modifier.fillMaxSize(), MaterialTheme.shapes.medium, iconSize = 36.dp)
            if (selected != null) SelectBox(selected, Modifier.align(Alignment.TopStart).padding(6.dp))
        }
        Text(title, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        popup?.invoke()
    }
}

/** A 16:9 poster with a progress bar and watched mark, for videos. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PosterCard(
    item: LibraryItem,
    ui: LibraryUi,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val selection = ui.selection
    val picked = item.id in selection.ids
    val progress = LibraryViews.progress(item)
    Column(
        modifier.clip(MaterialTheme.shapes.medium)
            .combinedClickable(
                role = Role.Button,
                onClick = { if (selection.mode) selection.toggle(item.id) else onClick() },
                onLongClickLabel = "Select", onLongClick = { selection.start(item.id) },
            )
            .semantics { if (selection.mode) selected = picked }
            .testTag(UiTags.libItem(item.id))
            .padding(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (item.kind == LibraryItemKind.VIDEO) 16f / 9f else 1f)) {
            ItemArt(item, Modifier.fillMaxSize(), MaterialTheme.shapes.medium, iconSize = 32.dp)
            Format.duration(item.duration)?.takeIf { item.kind == LibraryItemKind.VIDEO }?.let {
                app.keepfin.ui.components.ArtworkBadge(it, Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }
            if (item.watched) Icon(Icons.Outlined.CheckCircle, contentDescription = "Watched", tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp))
            if (selection.mode) SelectBox(picked, Modifier.align(Alignment.TopStart).padding(6.dp))
            if (progress > 0f && !item.watched) LinearProgressIndicator(
                progress = { progress }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp)
                    .semantics { contentDescription = "Watched ${(progress * 100).toInt()} percent" },
                drawStopIndicator = {},
            )
        }
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 6.dp)) {
                Text(item.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(Format.bytes(item.size).takeIf { item.kind != LibraryItemKind.IMAGE }, item.height?.takeIf { item.kind == LibraryItemKind.VIDEO }?.let { "${it}p" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                )
            }
            if (!selection.mode) ItemMenu(item, ui)
        }
    }
}

/** Folder card for video and mixed spaces: a poster of its first video, count and name. */
@Composable
fun FolderCardView(card: app.keepfin.FolderGroup, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.clip(MaterialTheme.shapes.medium).clickable(role = Role.Button, onClick = onClick).padding(6.dp).testTag("folder-${card.path}")) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            val cover = card.cover
            if (cover != null) ItemArt(cover, Modifier.fillMaxSize(), MaterialTheme.shapes.medium, iconSize = 32.dp)
            else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.medium))
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .28f), MaterialTheme.shapes.medium))
            Icon(Icons.Outlined.Folder, contentDescription = null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).size(22.dp))
            app.keepfin.ui.components.ArtworkBadge("${card.count}", Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
        Text(card.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(if (card.count == 1) "1 item" else "${card.count} items", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Drag to reorder over a local copy of a list (collections, the queue): the rows move in the copy while the finger drags, the new order is
 * saved once on release, and the list coming back from the library or the player replaces the copy only when it is safe to: never during
 * a drag, and never with the order from before the save while that save is on its way ([pending], given up after a few seconds).
 */
@Stable
class OrderSync {
    var dragging by mutableStateOf(false)
    var pending by mutableStateOf<List<String>?>(null)

    /** True when [ids] (the source's order now) may replace the local copy; clears [pending] once the source has caught up. */
    fun accepts(ids: List<String>): Boolean {
        val waiting = pending ?: return true
        if (ids == waiting) { pending = null; return true }
        // the same rows in the old order: the save has not come back yet; anything else (a row added or removed) is newer than the drag
        if (ids.size == waiting.size && ids.toSet() == waiting.toSet()) return false
        pending = null
        return true
    }
}
