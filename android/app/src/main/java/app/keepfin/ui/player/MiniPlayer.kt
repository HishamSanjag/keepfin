package app.keepfin.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.style.TextAlign
import app.keepfin.ui.theme.tabular
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.LibraryItemKind
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.library.ArtTile
import app.keepfin.ui.library.artModel
import app.keepfin.ui.theme.Studio

/** Artwork model for a queue entry: the library item's cover first, else the session's artwork uri. */
@Composable
fun rememberArt(vm: StudioViewModel, entry: QueueEntry?): Any? {
    // the item is read from the index off the main thread; the session's artwork shows until it arrives
    val item by app.keepfin.ui.library.rememberItem(vm.lib.browse, entry?.id)
    return remember(entry?.id, entry?.art, item?.art, item?.uri) {
        entry?.let { item?.artModel() ?: it.art?.takeIf(String::isNotBlank)?.let(android.net.Uri::parse) }
    }
}

/** Above the bottom navigation on every tab while a song is queued; tap or drag up to open the full player. */
@Composable
fun MiniPlayer(vm: StudioViewModel, modifier: Modifier = Modifier) {
    val state by vm.player.ui.collectAsStateWithLifecycle()
    val current = state.current
    val reduced = Studio.reducedMotion
    val swipe = rememberSwipeState(reduced)
    val motion = LocalPlayerMotion.current
    val density = LocalDensity.current
    // A bar that was swiped away starts fresh the next time a song plays.
    LaunchedEffect(current?.id) { if (swipe.y.value != 0f) swipe.reset() }
    if (current == null || (current.video && vm.video != null)) return
    val art = rememberArt(vm, current)
    // Left/right skip songs, down hides the bar like the close button (it stops playback and clears the queue).
    val actions = SwipeActions(state.hasPrevious, state.hasNext, vm.player::previousItem, vm.player::next, vm.player::clearQueue, "Stop and close the player", downThresholdDp = 40, onEdge = vm::sayQueueEdge)
    val barHeight = remember { floatArrayOf(0f) }
    val travelled = remember { floatArrayOf(0f) }
    val downThreshold = with(density) { 40.dp.toPx() }
    val openDistance = with(density) { 48.dp.toPx() }
    val openVelocity = with(density) { 700.dp.toPx() }
    // Up opens the player under the finger; down is the existing "close the bar" swipe.
    val vertical = Modifier.verticalFling(
        onStart = { travelled[0] = 0f; motion?.beginDrag() },
        onDrag = { delta ->
            travelled[0] += delta
            if (motion != null && (delta < 0f || travelled[0] < 0f)) motion.dragBy(-delta / motion.travel)
            swipe.setY(travelled[0].coerceAtLeast(0f))
        },
        onEnd = { velocity, cancelled ->
            val total = travelled[0]
            travelled[0] = 0f
            if (total < 0f && motion != null) {
                val open = !cancelled && (-total > openDistance || velocity < -openVelocity)
                if (open) vm.fullPlayer = true
                motion.settle(open, reduced, velocity)
                swipe.setY(0f)
            } else if (cancelled) swipe.settle() else swipe.endY(barHeight[0], downThreshold, actions)
        },
    )
    // Direction B: a raised bar (the one place with real elevation) floating above the navigation, a very soft wash of the artwork's
    // colour, 48dp artwork, an outlined round play/pause, and the seek bar under the controls with aligned times.
    val tint = app.keepfin.ui.library.rememberArtworkColor(art, androidx.compose.ui.graphics.Color.Transparent)
    Surface(
        modifier.padding(horizontal = 8.dp, vertical = 6.dp).fillMaxWidth().playerSwipe(swipe, actions, vertical = false).then(vertical).swipeOffset(swipe).testTag(UiTags.MINI_PLAYER)
            .onSizeChanged { barHeight[0] = it.height.toFloat() }
            .onGloballyPositioned { motion?.miniBar = it.boundsInRoot() },
        shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 0.dp, shadowElevation = 12.dp,
    ) {
        // The content of the bar gives way to the player's while the surface grows; the artwork flies instead of fading.
        Column(Modifier.background(tint.copy(alpha = if (Studio.colors.dark) .16f else .12f)).graphicsLayer { alpha = if (motion == null) 1f else (1f - motion.p.value * 3f).coerceIn(0f, 1f) }) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).height(60.dp).clickable(role = Role.Button, onClickLabel = "Open the player") { vm.fullPlayer = true }
                        .semantics(mergeDescendants = true) { contentDescription = "Now playing, ${current.title}${current.artist?.let { " by $it" } ?: ""}" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ArtTile(
                        art, current.title, LibraryItemKind.AUDIO,
                        Modifier.size(48.dp).onGloballyPositioned { motion?.miniArt = it.boundsInRoot() }.graphicsLayer { if (motion?.flying() == true) alpha = 0f },
                        MaterialTheme.shapes.small, fixedPx = PLAYER_ART_PX,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(current.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val line = state.error ?: listOfNotNull(current.artist, fromDeviceLabel(current)).joinToString(" · ")
                        Text(line, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = vm.player::previous, enabled = state.hasPrevious, modifier = Modifier.size(48.dp).testTag(UiTags.MINI_PREV)) {
                    Icon(Icons.Outlined.SkipPrevious, contentDescription = "Previous song")
                }
                IconButton(onClick = vm.player::toggle, modifier = Modifier.size(48.dp).testTag(UiTags.MINI_PLAY)) {
                    // The outlined round button of direction B: a ring around the play/pause mark.
                    Box(Modifier.size(40.dp).border(1.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape), contentAlignment = Alignment.Center) {
                        PlayPauseIcon(state.playing, LocalContentColor.current, if (state.playing) "Pause" else "Play", size = 22.dp)
                    }
                }
                IconButton(onClick = vm.player::next, enabled = state.hasNext, modifier = Modifier.size(48.dp).testTag(UiTags.MINI_NEXT)) {
                    Icon(Icons.Outlined.SkipNext, contentDescription = "Next song")
                }
            }
            // Seek bar with elapsed and total aligned either side; the stop button ends the row (stops playback, closes the bar).
            Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 12.dp, end = 0.dp), verticalAlignment = Alignment.CenterVertically) {
                MiniSeek(vm, Modifier.weight(1f))
                IconButton(onClick = vm.player::clearQueue, modifier = Modifier.size(48.dp).testTag(UiTags.MINI_CLOSE)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Stop and close the player", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** The mini bar's seek bar: elapsed, a thin seekable track, total. Tap or drag to seek; times use aligned (tabular) figures. */
@Composable
private fun MiniSeek(vm: StudioViewModel, modifier: Modifier = Modifier) {
    val position by vm.player.position.collectAsStateWithLifecycle()
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = position.durationMs.coerceAtLeast(1L)
    val fraction = dragging ?: (position.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    val enabled = position.durationMs > 0
    val shownMs = (fraction * duration).toLong()
    val latestDuration by androidx.compose.runtime.rememberUpdatedState(duration)
    val accent = MaterialTheme.colorScheme.primary
    val rail = MaterialTheme.colorScheme.onSurface.copy(alpha = .24f)
    val timeStyle = MaterialTheme.typography.labelSmall.tabular()
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(app.keepfin.ui.library.LibraryViews.mmss(shownMs / 1000.0), style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(36.dp).testTag(UiTags.MINI_ELAPSED))
        Box(
            Modifier.weight(1f).height(48.dp).testTag(UiTags.MINI_SEEK)
                .semantics {
                    stateDescription = "${app.keepfin.ui.library.LibraryViews.mmss(shownMs / 1000.0)} of ${app.keepfin.ui.library.LibraryViews.mmss(position.durationMs / 1000.0)}"
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f, steps = 19)
                    if (enabled) setProgress { value -> vm.player.seekTo((value.coerceIn(0f, 1f) * latestDuration).toLong()); true }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset -> vm.player.seekTo(((offset.x / size.width).coerceIn(0f, 1f) * latestDuration).toLong()) }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> dragging = (offset.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = { dragging?.let { vm.player.seekTo((it * latestDuration).toLong()) }; dragging = null },
                        onDragCancel = { dragging = null },
                    ) { change, _ -> change.consume(); dragging = (change.position.x / size.width).coerceIn(0f, 1f) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Canvas(Modifier.fillMaxWidth().height(12.dp).padding(horizontal = 8.dp)) {
                val h = 3.dp.toPx(); val y = size.height / 2f
                drawRoundRect(rail, Offset(0f, y - h / 2f), Size(size.width, h), CornerRadius(h / 2f))
                drawRoundRect(accent, Offset(0f, y - h / 2f), Size(size.width * fraction, h), CornerRadius(h / 2f))
                if (dragging != null) drawCircle(accent, 6.dp.toPx(), Offset(size.width * fraction, y))
            }
        }
        Text(app.keepfin.ui.library.LibraryViews.mmss(position.durationMs / 1000.0), style = timeStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.width(36.dp).testTag(UiTags.MINI_TOTAL))
    }
}