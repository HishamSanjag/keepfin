package app.keepfin.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.foundation.layout.height
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import app.keepfin.ui.theme.Motion
import app.keepfin.ui.theme.spring
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.graphics.TransformOrigin
import kotlin.math.abs
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.PlaybackItems
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.Destination
import app.keepfin.ui.library.ArtTile
import app.keepfin.ui.library.ExternalOpen
import app.keepfin.ui.library.LibraryRoute
import app.keepfin.ui.library.LibrarySheet
import app.keepfin.ui.library.LibrarySheets
import app.keepfin.ui.library.LibraryUi
import app.keepfin.ui.library.LibraryViews
import app.keepfin.ui.library.OrderSync
import app.keepfin.ui.library.Selection
import app.keepfin.ui.theme.Studio
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import app.keepfin.ui.theme.tabular
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Pane { COVER, LYRICS, QUEUE }

private const val NO_LYRICS = "No lyrics for this song"

/** The lines the lyrics preview shows at this position: the one being sung and the next, skipping the empty lines of instrumental gaps. */
internal fun previewLines(lyrics: app.keepfin.Lyrics, positionMs: Long): List<String> {
    val at = if (lyrics.synced) lyrics.lines.indexOfLast { (it.timeMs ?: Long.MAX_VALUE) <= positionMs }.coerceAtLeast(0) else 0
    return lyrics.lines.asSequence().drop(at).map { it.text }.filter { it.isNotBlank() }.take(2).toList()
}

/**
 * The lyrics under the controls: the line being sung and the next one. Tap to open the full lyrics. The card has the same size for
 * every song, with lyrics or without, so the cover never moves when the song changes; hiding it gives the space back for all songs.
 */
@Composable
private fun LyricsCard(vm: StudioViewModel, id: String?, onOpen: () -> Unit) {
    // cleared first, so a new song never shows the last one's line, and "No lyrics" waits until the answer is in
    var loading by remember(id) { mutableStateOf(true) }
    val lyrics by produceState<app.keepfin.Lyrics?>(null, id, vm.songVideo.lyricsStamp) {
        value = null
        value = if (id == null) null else withContext(Dispatchers.IO) { runCatching { vm.lib.lyrics(id) }.getOrNull() }
        loading = false
    }
    val shown = lyrics
    val position by vm.player.position.collectAsStateWithLifecycle()
    val lines by remember(shown) { derivedStateOf { shown?.let { previewLines(it, position.positionMs) }.orEmpty() } }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .85f),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(MaterialTheme.shapes.large)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Open the full lyrics", onClick = onOpen).testTag(UiTags.FULL_LYRICS_CARD),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 16.dp, top = 10.dp, bottom = 10.dp)) {
                Text("Lyrics", style = MaterialTheme.typography.labelSmall, color = muted)
                Text(
                    lines.firstOrNull() ?: if (shown == null && !loading) NO_LYRICS else "♪", style = MaterialTheme.typography.titleMedium,
                    color = if (lines.isEmpty()) muted else Color.Unspecified, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                // drawn even when empty: it holds the card's height at every font size
                Text(lines.getOrNull(1).orEmpty(), style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { vm.prefs.chooseLyricsPreview(false) }, modifier = Modifier.size(48.dp).testTag(UiTags.FULL_LYRICS_HIDE)) {
                Icon(Icons.Outlined.Close, contentDescription = "Hide the lyrics preview", tint = muted)
            }
        }
    }
}

/** The expanded music player: cover, seek, transport, favorite, queue, lyrics, sleep timer, speed and equalizer. */
@Composable
fun FullPlayer(vm: StudioViewModel, motionOrNull: PlayerMotion? = null) {
    val motion = motionOrNull ?: rememberPlayerMotion(1f)
    val reduced = Studio.reducedMotion
    val density = LocalDensity.current
    val closeDistance = with(density) { 110.dp.toPx() }
    val closeVelocity = with(density) { 800.dp.toPx() }
    // Lyrics and the queue scroll on their own: a swipe down that they cannot use (at the top of the list) moves the player
    // instead, the way it does on the cover, and its release closes or springs back with the same rules.
    val sheetScroll = remember(motion, closeDistance, closeVelocity, reduced) {
        object : NestedScrollConnection {
            var dragging = false
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                // partly pulled down: moving back up raises the player before the list scrolls again
                if (!dragging || source != NestedScrollSource.UserInput || available.y >= 0f) return Offset.Zero
                motion.dragBy(-available.y / motion.travel)
                if (motion.dragValue >= 1f) dragging = false
                return Offset(0f, available.y)
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y <= 0f) return Offset.Zero
                if (!dragging) { dragging = true; motion.beginDrag() }
                motion.dragBy(-available.y / motion.travel)
                return Offset(0f, available.y)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (!dragging) return Velocity.Zero
                dragging = false
                val moved = (1f - motion.dragValue) * motion.travel
                val close = moved > closeDistance || available.y > closeVelocity
                if (close) vm.fullPlayer = false
                motion.settle(!close, reduced, available.y)
                return available
            }
        }
    }
    val state by vm.player.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val current = state.current
    var pane by rememberSaveable { mutableStateOf(Pane.COVER) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var librarySheet by remember { mutableStateOf<LibrarySheet?>(null) }
    var menu by remember { mutableStateOf(false) }
    val libraryUi = remember(vm, context) { LibraryUi(vm, context, { librarySheet = it }, Selection(), { }) }
    val item: LibraryItem? = app.keepfin.ui.library.rememberItem(vm.lib.browse, current?.id).value
    val art = rememberArt(vm, current)
    // The player closes itself when the queue empties.
    LaunchedEffect(current == null) { if (current == null) vm.fullPlayer = false }

    // The overlay paints the surface; dragging down anywhere that nothing else claims follows the finger and releases with its velocity.
    // The player is always dark: a blurred, darkened wash of the artwork, with the controls on an opaque layer, so every pair keeps AA.
    app.keepfin.ui.theme.KeepfinTheme(mode = app.keepfin.ui.theme.ThemeMode.DARK) {
    val scheme = MaterialTheme.colorScheme
    MaterialTheme(colorScheme = scheme.copy(onSurface = OnWash, onBackground = OnWash, onSurfaceVariant = OnWashDim), typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
    Surface(
        Modifier.fillMaxSize().testTag(UiTags.FULL_PLAYER).nestedScroll(sheetScroll).verticalFling(
            onStart = { motion.beginDrag() },
            onDrag = { delta -> motion.dragBy(-delta / motion.travel) },
            onEnd = { velocity, cancelled ->
                val moved = (1f - motion.dragValue) * motion.travel
                val close = !cancelled && (moved > closeDistance || velocity > closeVelocity)
                if (close) vm.fullPlayer = false
                motion.settle(!close, reduced, velocity)
            },
        ),
        color = Color.Transparent, contentColor = OnWash,
    ) {
        Box(Modifier.fillMaxSize()) {
        ArtworkWash(art, Modifier.fillMaxSize().graphicsLayer { alpha = (motion.p.value * 2f - 0.4f).coerceIn(0f, 1f) }.testTag("player-wash"))
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).stage(motion, 0.25f, 0.6f), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.fullPlayer = false }, modifier = Modifier.size(48.dp).testTag(UiTags.FULL_COLLAPSE)) { Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Close the player") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when (pane) { Pane.COVER -> "Now playing"; Pane.LYRICS -> "Lyrics"; Pane.QUEUE -> "Queue" },
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() },
                    )
                    item?.let { Text(vm.spaceName(it.space).orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                }
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp).testTag("full-menu")) { Icon(Icons.Outlined.MoreVert, contentDescription = "More player actions") }
                    DropdownMenu(menu, { menu = false }) {
                        if (item != null) {
                            DropdownMenuItem(text = { Text("Add to playlist") }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null) }, onClick = { menu = false; librarySheet = LibrarySheet.AddToPlaylist(listOf(item.id)) })
                            if (!item.album.isNullOrBlank()) DropdownMenuItem(text = { Text("Go to album") }, leadingIcon = { Icon(Icons.AutoMirrored.Outlined.QueueMusic, null) }, onClick = {
                                menu = false; vm.fullPlayer = false; vm.navigate(Destination.LIBRARY)
                                vm.openLibraryRoot(LibraryRoute.Space(item.space)); vm.openLibraryDetail(LibraryRoute.Album(item.space, PlaybackItems.albumKey(item).orEmpty()))
                            })
                            // as on Windows: the artist's page from the player
                            if (!(item.artist ?: item.albumArtist).isNullOrBlank()) DropdownMenuItem(
                                text = { Text("Go to artist") }, leadingIcon = { Icon(Icons.Outlined.Person, null) },
                                onClick = { menu = false; vm.fullPlayer = false; vm.openArtistOf(item) }, modifier = Modifier.testTag("full-menu-artist"),
                            )
                            DropdownMenuItem(text = { Text("Open in another app") }, leadingIcon = { Icon(Icons.Outlined.MoreVert, null) }, onClick = { menu = false; vm.openExternally(item) })
                            DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Outlined.MoreVert, null) }, onClick = { menu = false; ExternalOpen.share(context, item)?.let(vm::say) })
                        }
                        DropdownMenuItem(
                            text = { Text(if (vm.prefs.lyricsPreview) "Hide lyrics preview" else "Show lyrics preview") }, leadingIcon = { Icon(Icons.Outlined.Lyrics, null) },
                            onClick = { menu = false; vm.prefs.chooseLyricsPreview(!vm.prefs.lyricsPreview) }, modifier = Modifier.testTag(UiTags.FULL_MENU_LYRICS_PREVIEW),
                        )
                        DropdownMenuItem(text = { Text("Clear queue") }, leadingIcon = { Icon(Icons.Outlined.Close, null) }, onClick = { menu = false; vm.player.clearQueue(); vm.fullPlayer = false })
                    }
                }
            }
            // a song that keeps its music video, or a video kept as a song, has a picture: Song | Video sits on the cover's corner (dev-docs/plans/FULL-PLAYER.md)
            val hasPicture = item != null && current != null && (item.musicVideo == true || vm.isVideoSong(item))
            val pictureOn = hasPicture && (current?.video == true || vm.songPicture == current?.id)
            // the remembered Video choice was asked for but the file is not on this phone: the song plays, and it says so
            LaunchedEffect(current?.id, current?.resolved, current?.musicVideo) {
                if (item?.musicVideo != true || current == null || !current.resolved || current.musicVideo || !vm.prefs.musicVideo) return@LaunchedEffect
                delay(1_000)
                val now = vm.player.ui.value.current
                if (now != null && now.resolved && !now.musicVideo && now.id == current.id && vm.prefs.musicVideo) vm.musicVideoMissing()
            }
            Box(Modifier.weight(1f).fillMaxWidth().stage(motion, 0.3f, 0.75f, riseDp = 0f)) {
                when (pane) {
                    Pane.COVER -> {
                        // Swipe the cover left/right for next/previous, down to close. Lyrics and the queue scroll, so they keep their own gestures.
                        val swipe = rememberSwipeState(Studio.reducedMotion)
                        val actions = SwipeActions(state.hasPrevious, state.hasNext, vm.player::previousItem, vm.player::next, { vm.fullPlayer = false }, onEdge = vm::sayQueueEdge)
                        Box(Modifier.fillMaxSize().playerSwipe(swipe, actions, vertical = false).testTag("cover-swipe")) {
                            CoverPane(art, current, motion, Modifier.swipeOffset(swipe), picture = if (pictureOn && vm.video == null) vm.player.player else null, pictureOn = pictureOn,
                                switch = if (hasPicture) { { place ->
                                    SongVideoSwitch(
                                        video = pictureOn, onPicture = true, modifier = place,
                                        onChange = { toVideo -> if (item?.musicVideo == true) vm.chooseSide(toVideo) else if (item != null) { if (toVideo) vm.showVideoSongPicture(item) else vm.listenToVideoSong(item) } },
                                    )
                                } } else null,
                                onFullScreen = vm::openPictureFullScreen)
                        }
                    }
                    Pane.LYRICS -> LyricsPane(vm, current?.id, item, reduced)
                    Pane.QUEUE -> QueuePane(vm, state)
                }
            }
            // The controls sit on an opaque layer, whatever the artwork is.
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 8.dp,
                modifier = Modifier.fillMaxWidth().testTag("player-controls"),
            ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp)) {
                if (current != null) {
                    Row(Modifier.stage(motion, 0.35f, 0.75f), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(current.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOfNotNull(current.artist, item?.album).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            fromDeviceLabel(current)?.let { from ->
                                Text(from, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(UiTags.PLAYER_FROM_DEVICE))
                            }
                        }
                        if (item != null) IconToggleButton(checked = item.favorite, onCheckedChange = { vm.libraryActions.favorite(item) }, modifier = Modifier.size(48.dp).testTag(UiTags.FULL_FAVORITE)) {
                            Icon(if (item.favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, contentDescription = if (item.favorite) "Remove from favorites" else "Add to favorites",
                                tint = if (item.favorite) app.keepfin.ui.theme.Studio.colors.accentText else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp).testTag("full-error")) }
                SeekRow(vm, Modifier.stage(motion, 0.4f, 0.8f))
                Transport(vm, state, Modifier.stage(motion, 0.45f, 0.85f))
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).stage(motion, 0.5f, 0.9f), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { sheet = "speed" }, modifier = Modifier.heightIn(min = 48.dp).testTag("full-speed")) {
                        Icon(Icons.Outlined.Speed, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(speedLabel(state.speed), style = MaterialTheme.typography.labelLarge.tabular())
                    }
                    SleepButton(vm) { sheet = "sleep" }
                    IconButton(onClick = { sheet = "eq" }, modifier = Modifier.size(48.dp).testTag("full-eq")) { Icon(Icons.Outlined.Equalizer, contentDescription = "Equalizer") }
                    if (item != null) IconButton(onClick = { librarySheet = LibrarySheet.Info(item) }, modifier = Modifier.size(48.dp).testTag(UiTags.FULL_SONG_INFO)) { Icon(Icons.Outlined.Info, contentDescription = "Song info") }
                    FilledTonalIconToggleButton(checked = pane == Pane.LYRICS, onCheckedChange = { pane = if (it) Pane.LYRICS else Pane.COVER }, modifier = Modifier.size(48.dp).testTag(UiTags.FULL_LYRICS_TOGGLE)) {
                        Icon(Icons.Outlined.Lyrics, contentDescription = "Lyrics")
                    }
                    FilledTonalIconToggleButton(checked = pane == Pane.QUEUE, onCheckedChange = { pane = if (it) Pane.QUEUE else Pane.COVER }, modifier = Modifier.size(48.dp).testTag(UiTags.FULL_QUEUE_TOGGLE)) {
                        Icon(Icons.AutoMirrored.Outlined.QueueMusic, contentDescription = "Queue")
                    }
                }
                if (pane == Pane.COVER && vm.prefs.lyricsPreview) LyricsCard(vm, current?.id) { pane = Pane.LYRICS }
            }
            }
        }
        }
    }
    }
    }
    when (sheet) {
        "speed" -> SpeedSheet(vm.player, state.speed) { sheet = null }
        "sleep" -> SleepSheet(vm.player) { sheet = null }
        "eq" -> EqualizerSheet(vm.player) { sheet = null }
    }
    LibrarySheets(libraryUi, librarySheet) { librarySheet = null }
}

fun speedLabel(speed: Float): String = (if (speed == speed.toInt().toFloat()) "%.1f" else "%.2f").format(java.util.Locale.ROOT, speed).trimEnd('0').trimEnd('.').let { "$it×" }

@Composable
private fun CoverPane(
    art: Any?, current: QueueEntry?, motion: PlayerMotion, modifier: Modifier = Modifier,
    /** The playback to draw in the cover's place while the entry plays its video side; null shows the cover. */
    picture: androidx.media3.common.Player? = null, pictureOn: Boolean = false,
    /** The Song | Video pill for the cover's corner (a song that has a picture), given the modifier that places it. */
    switch: (@Composable (Modifier) -> Unit)? = null, onFullScreen: () -> Unit = {},
) {
    // The rectangle the artwork flies to; forgotten when this pane leaves so a closing player does not fly to a pane that is not shown.
    DisposableEffect(motion) { onDispose { motion.cover = Rect.Zero } }
    BoxWithConstraints(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight)
        Box(
            Modifier.size(side).onGloballyPositioned { motion.cover = it.boundsInRoot() }.graphicsLayer { if (motion.flying()) alpha = 0f }
                .shadow(8.dp, MaterialTheme.shapes.large, clip = false),
        ) {
            if (pictureOn) {
                // the picture in the cover's place: same size, same corners, the one playback (no second player)
                Box(Modifier.fillMaxSize().clip(MaterialTheme.shapes.large).background(Color.Black).testTag(UiTags.PLAYER_PICTURE)) {
                    if (picture != null) androidx.media3.ui.compose.ContentFrame(picture, Modifier.fillMaxSize(), androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW, ContentScale.Fit, false) { Box(Modifier.fillMaxSize().background(Color.Black)) }
                }
            } else ArtTile(
                art, current?.title.orEmpty(), LibraryItemKind.AUDIO, Modifier.fillMaxSize(),
                MaterialTheme.shapes.large, iconSize = 96.dp, fixedPx = PLAYER_ART_PX,
            )
            switch?.invoke(Modifier.align(Alignment.TopStart).padding(8.dp))
            if (pictureOn) IconButton(
                onClick = onFullScreen,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(48.dp).testTag(UiTags.PLAYER_PICTURE_FULLSCREEN),
            ) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = .6f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Fullscreen, contentDescription = "Full screen", tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

/**
 * The seek bar. Pressing grows the track from 4dp to 10dp (quick spring), scales the thumb in and shows a time bubble that follows the
 * finger; releasing commits the seek and everything springs back. Only transform, opacity and a single canvas change per frame.
 */
@Composable
private fun SeekRow(vm: StudioViewModel, modifier: Modifier = Modifier) {
    val position by vm.player.position.collectAsStateWithLifecycle()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = position.durationMs.coerceAtLeast(1L)
    val fraction = dragging ?: (position.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val shownMs = (fraction * duration).toLong()
    val enabled = position.durationMs > 0
    val reduced = Studio.reducedMotion
    val scope = rememberCoroutineScope()
    val press = remember { Animatable(0f) }
    val latestDuration by rememberUpdatedState(duration)
    val latestFraction by rememberUpdatedState(fraction)
    val primary = MaterialTheme.colorScheme.primary
    val rail = MaterialTheme.colorScheme.onSurface.copy(alpha = .24f)
    val thumbPad = 10.dp
    val textMeasurer = rememberTextMeasurer()
    val bubble = textMeasurer.measure(LibraryViews.mmss(shownMs / 1000.0), MaterialTheme.typography.labelMedium.tabular())
    val bubbleColor = MaterialTheme.colorScheme.inverseSurface
    val bubbleText = MaterialTheme.colorScheme.inverseOnSurface
    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().height(48.dp).testTag(UiTags.FULL_SEEK)
                .semantics {
                    stateDescription = "${LibraryViews.mmss(shownMs / 1000.0)} of ${LibraryViews.mmss(position.durationMs / 1000.0)}"
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f, steps = 19)
                    if (enabled) setProgress { value -> vm.player.seekTo((value.coerceIn(0f, 1f) * latestDuration).toLong()); true }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val pad = thumbPad.toPx()
                    fun at(x: Float) = ((x - pad) / (size.width - 2 * pad).coerceAtLeast(1f)).coerceIn(0f, 1f)
                    // Only a sideways drag seeks and a tap jumps there; a vertical swipe starting on the bar is left to the player
                    // (closing it), so the 48dp band no longer swallows swipes down.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        scope.launch { if (reduced) press.snapTo(1f) else press.animateTo(1f, Motion.quick.spring()) }
                        var target: Float? = null
                        try {
                            dragging = at(down.position.x)
                            val slop = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ -> change.consume(); dragging = at(change.position.x) }
                            if (slop != null) {
                                if (horizontalDrag(slop.id) { change -> change.consume(); dragging = at(change.position.x) }) target = dragging
                            } else {
                                // cancelled: lifted where it was pressed (a tap), or the movement went to someone else (the player's close)
                                val up = currentEvent.changes.firstOrNull { it.id == down.id }
                                if (up != null && up.changedToUp() && !up.isConsumed) { up.consume(); target = dragging }
                            }
                        } finally {
                            dragging = null
                            scope.launch { if (reduced) press.snapTo(0f) else press.animateTo(0f, Motion.quick.spring()) }
                        }
                        target?.let { vm.player.seekTo((it * latestDuration).toLong()) }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(Modifier.fillMaxWidth().height(48.dp)) {
                val pad = thumbPad.toPx()
                val width = size.width - 2 * pad
                val grown = press.value
                val heightPx = (4.dp.toPx() + (10.dp.toPx() - 4.dp.toPx()) * grown).coerceAtLeast(1f)
                val x = pad + width * latestFraction
                val y = size.height / 2f
                drawRoundRect(rail, Offset(pad, y - heightPx / 2f), Size(width, heightPx), CornerRadius(heightPx / 2f))
                drawRoundRect(primary, Offset(pad, y - heightPx / 2f), Size((x - pad).coerceAtLeast(0f), heightPx), CornerRadius(heightPx / 2f))
                if (grown > 0f) drawCircle(primary, thumbPad.toPx() * grown.coerceIn(0f, 1.1f), Offset(x, y))
                // The time bubble rides above the finger; it is drawn, so following costs no relayout.
                if (grown > 0.01f) {
                    val bw = bubble.size.width + 16.dp.toPx(); val bh = bubble.size.height + 8.dp.toPx()
                    val bx = (x - bw / 2f).coerceIn(0f, (size.width - bw).coerceAtLeast(0f)); val by = -bh - 4.dp.toPx() + (1f - grown) * 6.dp.toPx()
                    val a = grown.coerceIn(0f, 1f)
                    drawRoundRect(bubbleColor, Offset(bx, by), Size(bw, bh), CornerRadius(8.dp.toPx()), alpha = a)
                    drawText(bubble, color = bubbleText, topLeft = Offset(bx + 8.dp.toPx(), by + 4.dp.toPx()), alpha = a)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(LibraryViews.mmss(shownMs / 1000.0), style = MaterialTheme.typography.labelMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(LibraryViews.mmss(position.durationMs / 1000.0), style = MaterialTheme.typography.labelMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Transport(vm: StudioViewModel, state: PlayerUi, modifier: Modifier = Modifier) {
    val port = vm.player
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        IconToggleButton(checked = state.shuffle, onCheckedChange = port::setShuffle, modifier = Modifier.size(48.dp).testTag("full-shuffle")) {
            Icon(Icons.Outlined.Shuffle, contentDescription = if (state.shuffle) "Shuffle on" else "Shuffle off", tint = if (state.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = port::previous, modifier = Modifier.size(56.dp).testTag("full-previous")) { Icon(Icons.Outlined.SkipPrevious, contentDescription = "Previous song", modifier = Modifier.size(34.dp)) }
        FilledIconButton(
            onClick = port::toggle, modifier = Modifier.size(68.dp).testTag(UiTags.FULL_PLAY),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction),
        ) { PlayPauseIcon(state.playing, LocalContentColor.current, if (state.playing) "Pause" else "Play", size = 38.dp) }
        IconButton(onClick = port::next, enabled = state.hasNext, modifier = Modifier.size(56.dp).testTag("full-next")) { Icon(Icons.Outlined.SkipNext, contentDescription = "Next song", modifier = Modifier.size(34.dp)) }
        IconButton(
            onClick = { port.setRepeat(when (state.repeat) { Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF }) },
            modifier = Modifier.size(48.dp).testTag("full-repeat"),
        ) {
            Icon(
                if (state.repeat == Player.REPEAT_MODE_ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
                contentDescription = when (state.repeat) { Player.REPEAT_MODE_ALL -> "Repeat all"; Player.REPEAT_MODE_ONE -> "Repeat one"; else -> "Repeat off" },
                tint = if (state.repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SleepButton(vm: StudioViewModel, onClick: () -> Unit) {
    val sleep by vm.player.sleepTimer.collectAsStateWithLifecycle()
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 48.dp).testTag("full-sleep")) {
        Icon(Icons.Outlined.Bedtime, if (sleep.active) null else "Sleep timer", Modifier.size(18.dp), tint = if (sleep.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        if (sleep.active) { Spacer(Modifier.width(6.dp)); Text(if (sleep.endOfTrack) "End of song" else sleepClock(sleep.remainingMs), style = MaterialTheme.typography.labelLarge.tabular()) }
    }
}

fun sleepClock(ms: Long): String { val total = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(java.util.Locale.ROOT, total / 60, total % 60) }

// ---- lyrics -------------------------------------------------------------------------------------------------

@Composable
private fun LyricsPane(vm: StudioViewModel, id: String?, item: app.keepfin.LibraryItem?, reduced: Boolean) {
    // looked at again when "Find lyrics" has found some
    val lyrics by produceState<LyricsState>(LyricsState.Loading, id, vm.songVideo.lyricsStamp) {
        value = LyricsState.Loading
        value = if (id == null) LyricsState.None else withContext(Dispatchers.IO) { runCatching { vm.lib.lyrics(id) }.getOrNull() }?.let { LyricsState.Ready(it) } ?: LyricsState.None
    }
    when (val shown = lyrics) {
        LyricsState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
        LyricsState.None -> Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Lyrics, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(12.dp))
            Text(NO_LYRICS, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Text(
                "Keepfin shows lyrics saved inside the file or in a .lrc file with the same name next to it.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp),
            )
            if (item != null && item.kind == app.keepfin.LibraryItemKind.AUDIO && !item.remote) androidx.compose.material3.OutlinedButton(
                onClick = { vm.songVideo.findLyrics(item) }, modifier = Modifier.padding(top = 12.dp).heightIn(min = 48.dp).testTag(UiTags.FIND_LYRICS),
            ) { Text(app.keepfin.core.lyrics.Lyrics.FIND) }
        }
        is LyricsState.Ready -> Column(Modifier.fillMaxSize()) {
            if (!shown.lyrics.synced) Text(
                app.keepfin.core.lyrics.Lyrics.UNTIMED, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp).testTag(UiTags.LYRICS_UNTIMED),
            )
            Box(Modifier.weight(1f)) { LyricsList(vm, shown.lyrics, reduced) }
        }
    }
}

private sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    data class Ready(val lyrics: app.keepfin.Lyrics) : LyricsState
}

/**
 * Synced lyrics: the active line glides to the vertical centre on the smooth spring while each line's opacity (0.3 to 1) and scale
 * (0.94 to 1) follow its distance from the active one. Scrolling is a spring-driven scroll of the list; the per-line look is a
 * graphicsLayer reading one animated number, so a frame never relays out or recomposes the lines.
 */
@Composable
private fun LyricsList(vm: StudioViewModel, lyrics: app.keepfin.Lyrics, reduced: Boolean) {
    val position by vm.player.position.collectAsStateWithLifecycle()
    val list = rememberLazyListState()
    val index by remember(lyrics) {
        derivedStateOf {
            if (!lyrics.synced) -1 else lyrics.lines.indexOfLast { (it.timeMs ?: Long.MAX_VALUE) <= position.positionMs }
        }
    }
    // The fractional "active line" every line measures its distance from.
    val focus = remember(lyrics) { Animatable(index.coerceAtLeast(0).toFloat()) }
    var placed by remember(lyrics) { mutableStateOf(false) }
    LaunchedEffect(index, lyrics) {
        if (index < 0) return@LaunchedEffect
        // Bring the line into the list's window first when a seek jumped far; then glide its centre to the middle of the pane.
        if (list.layoutInfo.visibleItemsInfo.none { it.index == index }) list.scrollToItem(index)
        val snap = reduced || !placed
        placed = true
        launch { if (snap) focus.snapTo(index.toFloat()) else focus.animateTo(index.toFloat(), Motion.smooth.spring()) }
        val info = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index } ?: return@LaunchedEffect
        val viewportCenter = (list.layoutInfo.viewportStartOffset + list.layoutInfo.viewportEndOffset) / 2f
        val delta = info.offset + info.size / 2f - viewportCenter
        if (snap) list.scrollBy(delta) else list.animateScrollBy(delta, Motion.smooth.spring())
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Half a pane of padding either side lets the first and last lines reach the centre too.
        LazyColumn(
            state = list, modifier = Modifier.fillMaxSize().testTag("lyrics-list"),
            contentPadding = PaddingValues(start = 28.dp, end = 28.dp, top = maxHeight / 2, bottom = maxHeight / 2), verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(lyrics.lines) { i, line ->
                val active = i == index
                Text(
                    line.text.ifBlank { "\u266a" }, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                        .graphicsLayer {
                            if (lyrics.synced) {
                                val near = (1f - abs(i - focus.value)).coerceIn(0f, 1f)
                                alpha = 0.7f + 0.3f * near
                                val scale = 0.94f + 0.06f * near
                                scaleX = scale; scaleY = scale
                                transformOrigin = TransformOrigin(0f, 0.5f)
                            }
                        }
                        .then(if (lyrics.synced && line.timeMs != null) Modifier.clickable { vm.player.seekTo(line.timeMs) } else Modifier)
                        .semantics { if (active) stateDescription = "Current line" },
                )
            }
        }
    }
}

// ---- queue --------------------------------------------------------------------------------------------------

@Composable
private fun QueuePane(vm: StudioViewModel, state: PlayerUi) {
    val port = vm.player
    val list = rememberLazyListState()
    LaunchedEffect(state.index.coerceAtLeast(0) to (state.queue.size > 0)) { if (state.index >= 0) list.scrollToItem(state.index.coerceAtLeast(0)) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.queue.size == 1) "1 song" else "${state.queue.size} songs", style = MaterialTheme.typography.titleSmall.tabular(), modifier = Modifier.weight(1f))
            TextButton(onClick = port::clearUpcoming, enabled = state.hasUpcoming, modifier = Modifier.heightIn(min = 48.dp).testTag("queue-clear")) { Text("Clear upcoming") }
        }
        // Duplicates of one song stay distinct rows by occurrence.
        val seen = HashMap<String, Int>()
        val keys = state.queue.map { entry -> entry.id + "#" + ((seen.merge(entry.id, 1, Int::plus) ?: 1) - 1) }
        val entries = keys.zip(state.queue).toMap()
        // The drag moves rows in this copy; the player gets one move when the finger lifts (its queue answers a moment later, so moving
        // it on every step made the next step start from an old position)
        val order = remember { mutableStateListOf<String>().also { it.addAll(keys) } }
        val sync = remember { OrderSync() }
        var draggedKey by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(keys, sync.dragging, sync.pending) {
            if (sync.dragging || !sync.accepts(keys)) return@LaunchedEffect
            if (order.toList() != keys) { order.clear(); order.addAll(keys) }
        }
        LaunchedEffect(sync.pending) { if (sync.pending != null) { kotlinx.coroutines.delay(4_000); sync.pending = null } }
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        val reorderState = rememberReorderableLazyListState(list) { from, to ->
            val f = order.indexOf(from.key)
            val t = order.indexOf(to.key)
            if (f >= 0 && t >= 0) order.add(t, order.removeAt(f))
        }
        fun drop() {
            sync.dragging = false
            val key = draggedKey ?: return
            draggedKey = null
            // a move the player has not answered yet counts: this one starts from the order the player will have after it
            val base = sync.pending ?: keys
            val from = base.indexOf(key)
            val to = order.indexOf(key)
            if (from < 0 || to < 0 || from == to) return
            sync.pending = base.toMutableList().apply { add(to, removeAt(from)) }
            port.move(from, to)
        }
        LazyColumn(state = list, modifier = Modifier.weight(1f).testTag("queue-list"), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(order.toList(), key = { it }) { rowKey ->
                val entry = entries[rowKey] ?: return@items
                val index = keys.indexOf(rowKey)
                val playing = index == state.index
                ReorderableItem(reorderState, key = rowKey) { dragged ->
                val handle = Modifier.draggableHandle(
                    onDragStarted = { sync.dragging = true; draggedKey = rowKey; haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) },
                    onDragStopped = { drop() },
                )
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp)
                        .then(if (dragged) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier)
                        .clickable(onClickLabel = "Play this song") { if (index >= 0) port.skipTo(index) }
                        .padding(start = 24.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).testTag("queue-row-${order.indexOf(rowKey)}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArtTile(rememberArt(vm, entry), entry.title, LibraryItemKind.AUDIO, Modifier.size(44.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (playing) app.keepfin.ui.theme.Studio.colors.accentText else MaterialTheme.colorScheme.onSurface)
                        Text(entry.artist.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = { if (index >= 0) port.removeAt(index) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Close, contentDescription = "Remove ${entry.title} from the queue") }
                    Box(
                        Modifier.size(48.dp).semantics {
                            contentDescription = "Reorder ${entry.title}"
                            customActions = listOf(
                                CustomAccessibilityAction("Move up") { if (index > 0) { port.move(index, index - 1); true } else false },
                                CustomAccessibilityAction("Move down") { if (index in 0 until state.queue.lastIndex) { port.move(index, index + 1); true } else false },
                            )
                        }.then(handle),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                }
            }
        }
    }
}
