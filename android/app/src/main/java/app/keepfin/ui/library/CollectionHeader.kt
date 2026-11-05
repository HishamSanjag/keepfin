package app.keepfin.ui.library

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.ui.theme.tabular
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.studioImageLoader
import app.keepfin.ui.theme.Studio
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import coil3.video.videoFramePercent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The colour of a piece of artwork, for the band behind a header and the full player's background: the average of its pixels with
 * the more colourful ones counting more (a grey cover does not turn the page muddy). [fallback] shows until the artwork decodes and
 * whenever it has none. No palette library: a 24 x 24 sample is enough for a soft tint.
 */
@Composable
fun rememberArtworkColor(model: Any?, fallback: Color): Color {
    val context = LocalContext.current
    val loader = studioImageLoader()
    val sampled by produceState<Color?>(null, model) {
        value = if (model == null) null else withContext(Dispatchers.IO) {
            runCatching {
                val request = ImageRequest.Builder(context).data(model).size(Size(24, 24)).allowHardware(false).videoFramePercent(0.1).build()
                val image = loader.execute(request).image ?: return@runCatching null
                ArtworkColors.average(image.toBitmap(24, 24))
            }.getOrNull()
        }
    }
    return sampled ?: fallback
}

/** Sampling of a bitmap, pure enough to unit test with a generated bitmap. */
object ArtworkColors {
    /** Saturation-weighted average, so a vivid cover wins over its grey borders; null for an empty or fully transparent bitmap. */
    fun average(bitmap: Bitmap): Color? {
        val w = bitmap.width; val h = bitmap.height
        if (w == 0 || h == 0) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return average(pixels)
    }

    fun average(pixels: IntArray): Color? {
        var r = 0.0; var g = 0.0; var b = 0.0; var total = 0.0
        for (p in pixels) {
            val a = (p ushr 24) and 0xFF
            if (a < 128) continue
            val pr = (p shr 16) and 0xFF; val pg = (p shr 8) and 0xFF; val pb = p and 0xFF
            val weight = 0.15 + (maxOf(pr, pg, pb) - minOf(pr, pg, pb)) / 255.0
            r += pr * weight; g += pg * weight; b += pb * weight; total += weight
        }
        if (total == 0.0) return null
        return Color((r / total / 255.0).toFloat(), (g / total / 255.0).toFloat(), (b / total / 255.0).toFloat())
    }
}

/**
 * A header's colour band: a tint sampled from the cover (or [fallback], the space colour) fading into the page. The text on it keeps the
 * page's own contrast because the tint never goes above 45 % opacity over the page colour.
 */
@Composable
fun HeaderBand(cover: Any?, fallback: Color, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shared = LocalBandTint.current
    val sampled = rememberArtworkColor(if (shared == null) cover else null, fallback)
    val page = MaterialTheme.colorScheme.background
    val top = bandColor(shared ?: sampled)
    Column(modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(top, page)))) {
        // On a banded page the band starts at the very top of the screen; this keeps the content clear of the status bar and top bar.
        if (LocalBandTop.current > 0.dp) Spacer(Modifier.height(LocalBandTop.current))
        content()
    }
}

/** The band colour at its top: the artwork colour at a soft opacity (never above 45 %, so the page text keeps its contrast). */
@Composable
fun bandColor(tint: Color): Color = tint.copy(alpha = if (Studio.colors.dark) .42f else .32f)

/** Height of the status bar plus the top bar that overlays a banded page; the header band adds it as top space. */
val LocalBandTop = androidx.compose.runtime.compositionLocalOf { 0.dp }

/** The artwork colour of a banded page, resolved once by [BandedDetail] and shared by its header band and its top bar. */
val LocalBandTint = androidx.compose.runtime.compositionLocalOf<Color?> { null }

/**
 * A collection page whose top bar merges with the header: the artwork gradient starts under the status bar, the bar is transparent over
 * it, and as the list scrolls the bar fills smoothly with the same colour laid over the page colour (so back, the title and the menu
 * stay readable) while the title fades in. [listState] is the page list's, so the bar follows its scroll. A selection swaps the bar
 * for the selection bar.
 */
@Composable
fun BandedDetail(
    ui: LibraryUi, title: String, subtitle: String?, cover: Any?, spaceId: String?, listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}, content: @Composable () -> Unit,
) {
    val tint = rememberArtworkColor(cover, rememberSpaceColor(ui, spaceId))
    val page = MaterialTheme.colorScheme.background
    val solid = bandColor(tint).compositeOver(page)
    val density = androidx.compose.ui.platform.LocalDensity.current
    // The bar is the status bar plus one 48 dp row (back, title, menu). Known before it is measured, so the header is drawn at its place
    // from the first frame instead of under the bar and then jumping down; the measured height corrects it (a large font scale).
    val statusTop = WindowInsets.statusBars.getTop(density)
    var barPx by remember { mutableStateOf(statusTop + with(density) { 48.dp.roundToPx() }) }
    val fullPx = with(density) { 128.dp.toPx() }
    val fraction = remember { androidx.compose.runtime.derivedStateOf { if (listState.firstVisibleItemIndex > 0) 1f else (listState.firstVisibleItemScrollOffset / fullPx).coerceIn(0f, 1f) } }
    Box(modifier.fillMaxSize()) {
        androidx.compose.runtime.CompositionLocalProvider(LocalBandTop provides with(density) { barPx.toDp() }, LocalBandTint provides tint) { content() }
        if (ui.selection.mode) SelectionTopBar(ui, Modifier.align(Alignment.TopCenter))
        else Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().onSizeChanged { barPx = it.height }.testTag(UiTags.BAND_BAR)
                .drawBehind { drawRect(solid.copy(alpha = fraction.value)) }
                .statusBarsPadding().padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackButton(ui)
            Column(Modifier.weight(1f).graphicsLayer { alpha = fraction.value }) {
                Text(title, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            trailing()
        }
    }
}

/** The cover of a header: one image, or a 2 x 2 mosaic of up to four different covers (playlists and spaces without artwork of their own). */
@Composable
fun CoverMosaic(covers: List<LibraryItem>, seed: String, size: Dp, modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.medium) {
    val distinct = remember(covers) { covers.filter { !it.art.isNullOrBlank() }.distinctBy { it.art }.take(4) }
    if (distinct.size < 4) {
        val one = covers.firstOrNull { !it.art.isNullOrBlank() } ?: covers.firstOrNull { it.artModel() != null } ?: covers.firstOrNull()
        ArtTile(one?.artModel(), seed, one?.kind ?: LibraryItemKind.AUDIO, modifier.size(size).testTag(UiTags.HEADER_COVER), shape, iconSize = size / 3)
        return
    }
    Column(modifier.size(size).clip(shape).testTag(UiTags.HEADER_COVER)) {
        for (row in 0..1) Row(Modifier.weight(1f)) {
            for (col in 0..1) {
                val item = distinct[row * 2 + col]
                ArtTile(item.artModel(), item.album ?: item.title, item.kind, Modifier.weight(1f).fillMaxWidth().heightIn(min = 0.dp), RoundedCornerShapeNone, iconSize = size / 8)
            }
        }
    }
}

private val RoundedCornerShapeNone: Shape = androidx.compose.ui.graphics.RectangleShape

/** The space colour of [spaceId]: the band's colour until the artwork's own colour is known, and when there is no artwork. */
@Composable
fun rememberSpaceColor(ui: LibraryUi, spaceId: String?): Color {
    val library by ui.vm.engine.settings.collectAsStateWithLifecycle()
    val spaces = remember(library.library.spaces) { app.keepfin.SpaceLogic.withDefaults(library.library.spaces) }
    val index = spaces.indexOfFirst { it.id == spaceId }
    val tone = if (index >= 0) app.keepfin.ui.theme.spaceTone(spaces[index], index) else app.keepfin.ui.theme.spaceTone(app.keepfin.SpaceColor.COBALT)
    return tone.accent
}

/**
 * The header every music collection page shares: a colour band sampled from the cover, a large cover (a 2 x 2 mosaic for playlists
 * and spaces without artwork of their own, a circle for an artist), the kind, the title, the numbers, then [HeaderActions].
 */
@Composable
fun CollectionHeaderBlock(
    ui: LibraryUi,
    title: String,
    kind: String?,
    meta: String,
    songs: List<LibraryItem>,
    spaceId: String?,
    modifier: Modifier = Modifier,
    circle: Boolean = false,
    playable: List<LibraryItem> = songs,
    extra: (@Composable RowScope.() -> Unit)? = null,
    actions: Songs = Songs.Fixed(playable),
) {
    val sample = remember(songs) { songs.coverModel() }
    HeaderBand(sample, rememberSpaceColor(ui, spaceId), modifier.testTag(UiTags.HEADER_BAND)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverMosaic(songs, title, 132.dp, shape = if (circle) CircleShape else MaterialTheme.shapes.medium)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (kind != null) Text(kind.uppercase(), fontSize = 11.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text(title, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                Text(meta, style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HeaderActions(ui, actions, Modifier.padding(bottom = 4.dp), extra)
    }
}

/**
 * Play, Shuffle, the optional Follow / Keep in sync button and ⋯ (Play next, Add to queue, Add to playlist, Select): the row under every
 * header. [songs] is the playable list; with nothing to play the buttons stay but are disabled.
 */
@Composable
fun HeaderActions(ui: LibraryUi, songs: List<LibraryItem>, modifier: Modifier = Modifier, extra: (@Composable RowScope.() -> Unit)? = null) =
    HeaderActions(ui, Songs.Fixed(songs), modifier, extra)

@Composable
fun HeaderActions(ui: LibraryUi, songs: Songs, modifier: Modifier = Modifier, extra: (@Composable RowScope.() -> Unit)? = null) {
    if (ui.selection.mode) return
    val any = !songs.isEmpty
    // a fixed list knows its kinds; a query is a list of songs, so its audio is its count
    val audio = when (songs) { is Songs.Fixed -> songs.items.any { it.kind == LibraryItemKind.AUDIO || it.kind == LibraryItemKind.VIDEO }; is Songs.Rows -> songs.count > 0 }
    var more by remember { mutableStateOf(false) }
    // Play and Shuffle share what is left and always keep their whole labels: their icons go at large font scales, and Sync is an icon button.
    val bigFont = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.15f
    val pad = PaddingValues(horizontal = 12.dp)
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = { ui.play(songs) }, enabled = any, contentPadding = pad, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(UiTags.LIB_PLAY_ALL),
            colors = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction),
        ) {
            if (!bigFont) { Icon(Icons.Outlined.PlayArrow, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            Text("Play", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
        FilledTonalButton(onClick = { ui.shuffle(songs) }, enabled = audio, contentPadding = pad, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(UiTags.LIB_SHUFFLE)) {
            if (!bigFont) { Icon(Icons.Outlined.Shuffle, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)) }
            Text("Shuffle", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        }
        extra?.invoke(this)
        Box {
            IconButton(onClick = { more = true }, modifier = Modifier.size(48.dp).testTag(UiTags.HEADER_MORE)) { Icon(Icons.Outlined.MoreVert, contentDescription = "More actions") }
            DropdownMenu(more, { more = false }) {
                DropdownMenuItem(text = { Text("Play next") }, enabled = audio, leadingIcon = { Icon(Icons.Outlined.SkipNext, null) }, onClick = { more = false; ui.playNext(songs) }, modifier = Modifier.testTag("header-more-next"))
                DropdownMenuItem(text = { Text("Add to queue") }, enabled = audio, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.QueueMusic, null) }, onClick = { more = false; ui.addToQueue(songs) }, modifier = Modifier.testTag("header-more-queue"))
                DropdownMenuItem(text = { Text("Add to playlist…") }, enabled = audio, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null) }, onClick = { more = false; ui.addToPlaylist(songs) }, modifier = Modifier.testTag("header-more-playlist"))
                DropdownMenuItem(text = { Text("Select") }, enabled = any, leadingIcon = { Icon(Icons.Outlined.Checklist, null) }, onClick = { more = false; ui.selection.mode = true }, modifier = Modifier.testTag("header-more-select"))
            }
        }
    }
}
/** "Keep in sync" for a collection that came from a link; opens the follow settings. An icon button, so Play and Shuffle keep their labels. */
@Composable
fun RowScope.KeepInSyncButton(onClick: () -> Unit) {
    androidx.compose.material3.OutlinedIconButton(onClick = onClick, modifier = Modifier.size(48.dp).testTag(UiTags.HEADER_FOLLOW)) {
        Icon(Icons.Outlined.Sync, contentDescription = "Keep in sync", modifier = Modifier.size(20.dp))
    }
}