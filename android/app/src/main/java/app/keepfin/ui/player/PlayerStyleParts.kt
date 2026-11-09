package app.keepfin.ui.player

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.ui.UiTags
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.library.rememberArtworkColor
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.theme.tabular
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The title block every style shows: title, artist and album, where the song is playing from, the heart, and the error of a song that cannot play. */
@Composable
internal fun SongTitleRow(s: FullScope, modifier: Modifier = Modifier, compact: Boolean = false) {
    val current = s.current ?: return
    val item = s.item
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(current.title, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(current.artist, item?.album).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                fromDeviceLabel(current)?.let { from ->
                    Text(from, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(UiTags.PLAYER_FROM_DEVICE))
                }
            }
            if (item != null) IconToggleButton(checked = item.favorite, onCheckedChange = { s.vm.libraryActions.favorite(item) }, modifier = Modifier.size(48.dp).testTag(UiTags.FULL_FAVORITE)) {
                Icon(if (item.favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, contentDescription = if (item.favorite) "Remove from favorites" else "Add to favorites",
                    tint = if (item.favorite) Studio.colors.accentText else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        s.state.error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp).testTag("full-error")) }
    }
}

/** Speed, sleep timer, equalizer and song info; a style adds its own toggles through [extra]. */
@Composable
internal fun ToolsRow(s: FullScope, modifier: Modifier = Modifier, extra: @Composable RowScope.() -> Unit = {}) {
    val item = s.item
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { s.openSheet("speed") }, modifier = Modifier.heightIn(min = 48.dp).testTag("full-speed")) {
            Icon(Icons.Outlined.Speed, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(speedLabel(s.state.speed), style = MaterialTheme.typography.labelLarge.tabular())
        }
        SleepButton(s.vm) { s.openSheet("sleep") }
        IconButton(onClick = { s.openSheet("eq") }, modifier = Modifier.size(48.dp).testTag("full-eq")) { Icon(Icons.Outlined.Equalizer, contentDescription = "Equalizer") }
        if (item != null) IconButton(onClick = { s.openInfo(item) }, modifier = Modifier.size(48.dp).testTag(UiTags.FULL_SONG_INFO)) { Icon(Icons.Outlined.Info, contentDescription = "Song info") }
        extra()
    }
}

/** The "Player style" button of every style's header: a menu of the registry, a check on the chosen style and each style's summary. */
@Composable
internal fun PlayerStyleButton(vm: StudioViewModel, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp).testTag(PlayerStyleTags.BUTTON)) { Icon(Icons.Outlined.Palette, contentDescription = "Player style") }
        DropdownMenu(open, { open = false }) {
            PlayerStyles.all.forEach { style ->
                val chosen = style.id == vm.prefs.playerStyle
                DropdownMenuItem(
                    text = {
                        Column(Modifier.widthIn(max = 260.dp)) {
                            Text(style.name, style = MaterialTheme.typography.titleSmall)
                            Text(style.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    trailingIcon = { if (chosen) Icon(Icons.Filled.Check, contentDescription = null) },
                    onClick = { open = false; vm.prefs.choosePlayerStyle(style.id) },
                    modifier = Modifier.testTag(PlayerStyleTags.item(style.id)).semantics { selected = chosen; stateDescription = if (chosen) "Current style" else "" },
                )
            }
        }
    }
}

internal object PlayerStyleTags {
    const val BUTTON = "player-style-button"
    fun item(id: String) = "player-style-item-$id"
    /** The marker of each style's body, so tests can tell which one is drawn. */
    fun body(id: String) = "player-style-body-$id"
    const val SIDE_QUEUE = "player-side-queue"
    const val SIDE_LYRICS = "player-side-lyrics"
    const val VISUALIZER = "player-visualizer"
    const val GLOW = "player-glow"
}

/** An "Up next | Lyrics" style two-way choice, 48dp tall; [view] is the pane shown. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SidePick(view: Pane, onPick: (Pane) -> Unit, modifier: Modifier = Modifier, queueFirst: Boolean = true) {
    val options = if (queueFirst) listOf(Pane.QUEUE to "Up next", Pane.LYRICS to "Lyrics") else listOf(Pane.LYRICS to "Lyrics", Pane.QUEUE to "Up next")
    SingleChoiceSegmentedButtonRow(modifier) {
        options.forEachIndexed { index, (pane, label) ->
            SegmentedButton(
                selected = view == pane, onClick = { onPick(pane) }, shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.heightIn(min = 48.dp).testTag(if (pane == Pane.QUEUE) PlayerStyleTags.SIDE_QUEUE else PlayerStyleTags.SIDE_LYRICS),
            ) { Text(label, maxLines = 1) }
        }
    }
}

/** The lyrics of one song, read off the main thread; null while loading or when it has none. */
@Composable
private fun rememberLyricsOf(vm: StudioViewModel, id: String?): androidx.compose.runtime.State<app.keepfin.Lyrics?> =
    produceState<app.keepfin.Lyrics?>(null, id, vm.songVideo.lyricsStamp) {
        value = null
        value = if (id == null) null else withContext(Dispatchers.IO) { runCatching { vm.lib.lyrics(id) }.getOrNull() }
    }

/**
 * Cinema's line being sung, laid over the lower part of the stage on a dark veil so it reads on any cover or frame. Only this small
 * composable follows the position (through a derived value), so the line changing is the only recomposition it causes. Nothing is
 * drawn for a song without timed lyrics.
 */
@Composable
internal fun BoxScope.CurrentLyricOverlay(vm: StudioViewModel, id: String?) {
    val lyrics by rememberLyricsOf(vm, id)
    val position = vm.player.position.collectAsStateWithLifecycle()
    val line by remember(lyrics) {
        derivedStateOf { lyrics?.takeIf { it.synced }?.let { previewLines(it, position.value.positionMs).firstOrNull() }.orEmpty() }
    }
    if (line.isEmpty()) return
    Box(Modifier.matchParentSize().drawBehind { drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .78f)), startY = size.height * .45f)) }.testTag("cinema-line-veil"))
    Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("♪", color = Color(0xFFFFD6AA), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Text(line, color = OnWash, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("cinema-line"))
    }
}

/**
 * Afterglow's backdrop: the cover's own colour (capped in brightness like the PC's, so the lyrics keep their contrast) as a soft glow
 * from the top left and the accent from the bottom right, under a veil. It drifts slowly; with reduced motion it stays still. The drift
 * is read in the draw phase only, so it costs no recomposition.
 */
@Composable
internal fun AfterglowGlow(art: Any?, reduced: Boolean, modifier: Modifier = Modifier, light: Boolean = false) {
    val canvas = if (light) MaterialTheme.colorScheme.surface else Color(0xFF0D0F13)
    // as the PC's light treatment: the glow at 55%, then the canvas colour as the veil
    val strength = if (light) .55f else 1f
    val sampled = rememberArtworkColor(art, Color(0xFF6F8FF0))
    val tint = remember(sampled) { PlayerStyles.darkened(sampled, 0.16f) }
    val accent = Studio.colors.accentText
    val drift = if (reduced) null else rememberInfiniteTransition(label = "glow").animateFloat(0f, 1f, infiniteRepeatable(tween(32_000, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
    Box(
        modifier.background(canvas).drawBehind {
            val t = drift?.value ?: 0f
            val reach = maxOf(size.width, size.height)
            val a = Offset(size.width * (.22f + .04f * t), size.height * (.24f + .03f * t))
            val b = Offset(size.width * (.88f - .04f * t), size.height * (.92f - .03f * t))
            drawRect(Brush.radialGradient(listOf(tint.copy(alpha = .7f * strength), Color.Transparent), center = a, radius = reach * .62f))
            drawRect(Brush.radialGradient(listOf(accent.copy(alpha = .38f * .5f * strength), Color.Transparent), center = b, radius = reach * .5f))
            drawRect((if (light) canvas else Color.Black).copy(alpha = .4f))
        },
    ) { Box(Modifier.fillMaxSize().testTag(PlayerStyleTags.GLOW)) }
}

/**
 * The bars under Afterglow's cover. The phone does not read the sound (that needs the microphone permission, which a music player must
 * not ask for), so they do not move: a resting shape, bright while a song plays and dim while it is paused. Nothing here is drawn from
 * invented data.
 */
@Composable
internal fun RestingBars(playing: Boolean, modifier: Modifier = Modifier) {
    val color = Studio.colors.accentText.copy(alpha = if (playing) .9f else .4f)
    Canvas(modifier.fillMaxWidth().height(36.dp).testTag(PlayerStyleTags.VISUALIZER)) {
        val bars = BAR_SHAPE.size
        val gap = 4.dp.toPx()
        val bar = (size.width - gap * (bars - 1)) / bars
        BAR_SHAPE.forEachIndexed { i, share ->
            val h = (share * size.height).coerceAtLeast(2.dp.toPx())
            drawRoundRect(color, Offset(i * (bar + gap), size.height - h), Size(bar, h), CornerRadius(minOf(bar / 2f, 2.dp.toPx())))
        }
    }
}

/** The PC's resting heights, as a share of the full height. */
private val BAR_SHAPE = listOf(
    .3f, .5f, .75f, .45f, .65f, .9f, .55f, .35f, .7f, .85f, .6f, .4f, .75f, .95f, .65f, .45f, .3f, .55f, .8f, .7f,
    .5f, .35f, .65f, .9f, .75f, .55f, .4f, .6f, .85f, .7f, .45f, .3f, .5f, .75f, .65f, .4f, .55f, .8f, .6f, .35f,
)
