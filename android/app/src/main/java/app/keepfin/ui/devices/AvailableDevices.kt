package app.keepfin.ui.devices

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.keepfin.net.DevicesState
import app.keepfin.net.DiscoveredUi
import app.keepfin.net.formatAddress
import app.keepfin.ui.UiPreferences
import app.keepfin.ui.components.Pane
import app.keepfin.ui.components.RowSeparator
import app.keepfin.ui.theme.Studio
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ---- the choice between the circle and the list ----------------------------------------------------------------------------

/** Most devices the circle can hold without names touching; more than this always means the list. */
internal const val CIRCLE_SLOTS = 6

/** The circle is the automatic view up to this many devices (and only while the text is not large). */
internal const val CIRCLE_AUTO_MAX = 5

/** Text scaled past this is "large": the list is easier to read and tap. */
internal const val LARGE_TEXT_SCALE = 1.3f

enum class AvailableView(val wire: String) { CIRCLE("circle"), LIST("list") }

/**
 * Which view Available devices uses. Until the person chooses ([choice] null) it is the circle for [CIRCLE_AUTO_MAX] devices or fewer
 * with text that is not large, and the list otherwise. A chosen list always holds; a chosen circle holds up to [CIRCLE_SLOTS] devices.
 */
internal fun availableView(count: Int, fontScale: Float, choice: String?): AvailableView = when (choice) {
    "list" -> AvailableView.LIST
    "circle" -> if (count <= CIRCLE_SLOTS) AvailableView.CIRCLE else AvailableView.LIST
    else -> if (count <= CIRCLE_AUTO_MAX && fontScale <= LARGE_TEXT_SCALE) AvailableView.CIRCLE else AvailableView.LIST
}

/** Where the person's Circle / List choice is kept (null until they choose). */
interface DevicesViewStore {
    val choice: String?
    fun choose(value: String)
}

/** Kept in the app's UI preferences so it survives restarts. */
class PrefsViewStore(private val prefs: UiPreferences) : DevicesViewStore {
    override val choice: String? get() = prefs.devicesView
    override fun choose(value: String) = prefs.chooseDevicesView(value)
}

/** Kept only while the page is open (previews and tests). */
class MemoryViewStore(initial: String? = null) : DevicesViewStore {
    private var current: String? by mutableStateOf(initial)
    override val choice: String? get() = current
    override fun choose(value: String) { current = value }
}

@Composable
internal fun rememberMemoryViewStore(): DevicesViewStore = remember { MemoryViewStore() }

/**
 * One fixed slot of the circle per found device, from its id alone, so a device keeps its place while others come and go (a taken slot
 * moves the later id to the next free one). Slot 0 is at the top; slots go clockwise.
 */
internal fun circleSlots(hints: List<String>, slots: Int = CIRCLE_SLOTS): Map<String, Int> {
    val taken = HashSet<Int>()
    val out = LinkedHashMap<String, Int>()
    for (hint in hints.sorted().take(slots)) {
        var s = stableHash(hint) % slots
        while (s in taken) s = (s + 1) % slots
        taken += s
        out[hint] = s
    }
    return out
}

private fun stableHash(text: String): Int {
    var h = 17
    for (c in text) h = h * 31 + c.code
    return h and 0x7fffffff
}

/** "Home network" or "via Tailscale": how a found device is reached. */
internal fun reachWords(d: DiscoveredUi): String = if (d.route == "tailscale") "via Tailscale" else "Home network"

internal fun platformIcon(platform: String?): ImageVector = when (platform) {
    "android", "ios" -> Icons.Outlined.PhoneAndroid
    "windows", "macos", "linux" -> Icons.Outlined.Computer
    else -> Icons.Outlined.Devices
}

// ---- the section -----------------------------------------------------------------------------------------------------------

/**
 * Devices found nearby that are not paired yet: a circle around this phone, or a searchable list. Connect starts the asking flow.
 * Under it one line says what the search is doing (with Stop / Search again) and holds "Not seeing it?", whose help carries Add by
 * address and Type a code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AvailableSection(
    state: DevicesState, store: DevicesViewStore, searching: Boolean, finished: Boolean, onStop: () -> Unit, onSearchAgain: () -> Unit,
    controller: DevicesController, run: (() -> String?) -> Unit, onConnect: (DiscoveredUi) -> Unit, onAddByAddress: () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val view = availableView(state.discovered.size, fontScale, store.choice)
    var help by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().testTag(DevicesTags.AVAILABLE)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Available devices", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = view == AvailableView.CIRCLE, onClick = { store.choose("circle") },
                    shape = SegmentedButtonDefaults.itemShape(0, 2), modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.VIEW_CIRCLE),
                    icon = {},
                ) { Text("Circle", fontSize = 13.sp, maxLines = 1) }
                SegmentedButton(
                    selected = view == AvailableView.LIST, onClick = { store.choose("list") },
                    shape = SegmentedButtonDefaults.itemShape(1, 2), modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.VIEW_LIST),
                    icon = {},
                ) { Text("List", fontSize = 13.sp, maxLines = 1) }
            }
        }
        Pane(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag(DevicesTags.NEARBY), contentPadding = PaddingValues(top = 8.dp, bottom = 4.dp)) {
            if (view == AvailableView.CIRCLE) DeviceCircle(state, searching, onConnect) else DeviceList(state, onConnect)
            AvailableStatus(state, searching, finished, onStop, onSearchAgain, help, { help = !help }, controller, run, onAddByAddress)
        }
    }
}

/**
 * One line directly under the devices: what the search is doing ("Looking…", "Nothing found yet", "Search stopped"), Stop or Search
 * again, and "Not seeing it?" whose help holds the way to add a device by address or by a typed code.
 */
@Composable
private fun AvailableStatus(
    state: DevicesState, searching: Boolean, finished: Boolean, onStop: () -> Unit, onSearchAgain: () -> Unit, help: Boolean, toggleHelp: () -> Unit,
    controller: DevicesController, run: (() -> String?) -> Unit, onAddByAddress: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // what the search is doing, on a full-width line of its own
        Row(Modifier.fillMaxWidth().padding(top = 4.dp).testTag(DevicesTags.SEARCH_STATUS), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (searching) Box(Modifier.requiredSize(16.dp).testTag(DevicesTags.NEARBY_LOOKING), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.requiredSize(16.dp), strokeWidth = 2.dp, trackColor = MaterialTheme.colorScheme.outlineVariant)
            }
            Text(
                when {
                    searching -> if (state.network.tailscale) "Looking on this network and Tailscale…" else "Looking on this network…"
                    finished -> "Search finished · ${state.discovered.size} found"
                    else -> "Search stopped"
                },
                fontSize = 13.sp, lineHeight = 17.sp, color = muted, maxLines = 2, modifier = Modifier.weight(1f),
            )
        }
        // then the two actions, each a 48 dp target
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (searching) TextButton(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.SEARCH_STOP), contentPadding = LinePadding) { Text("Stop", fontSize = 13.sp) }
            else TextButton(onClick = onSearchAgain, modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.SEARCH_AGAIN), contentPadding = LinePadding) { Text("Search again", fontSize = 13.sp) }
            TextButton(onClick = toggleHelp, modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.NOT_SEEING), contentPadding = LinePadding) { Text("Not seeing it?", fontSize = 13.sp) }
        }
        if (help) Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag(DevicesTags.NEARBY_EMPTY), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Open Keepfin → Devices on the other device and turn Devices on.", fontSize = 13.sp, lineHeight = 18.sp, color = muted)
            Text("Make sure both are on the same Wi-Fi.", fontSize = 13.sp, lineHeight = 18.sp, color = muted)
            Text("Away from home, turn on Tailscale on both (the ? at the top has the steps).", fontSize = 13.sp, lineHeight = 18.sp, color = muted)
            TypeCodeField(controller, run)
            TextButton(onClick = onAddByAddress, modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.ADD), contentPadding = LinePadding) { Text("Add by address") }
        }
    }
}

private val LinePadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)

// ---- the circle ------------------------------------------------------------------------------------------------------------

@Composable
private fun DeviceCircle(state: DevicesState, searching: Boolean, onConnect: (DiscoveredUi) -> Unit) {
    val slots = remember(state.discovered.map { it.hint }) { circleSlots(state.discovered.map { it.hint }) }
    // keyboard and screen-reader order follows the circle clockwise from the top
    val ordered = state.discovered.filter { it.hint in slots }.sortedBy { slots.getValue(it.hint) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // at most 300 dp across, so on a phone Your devices still shows under the circle
        val side = min(maxWidth.value, 300f).dp
        val nodeWidth = (side * 0.28f).coerceIn(72.dp, 96.dp)
        // a node is about 92 dp tall at normal text; the ring is an ellipse tall enough that neighbours never touch
        val nodeHeight = (52f + 44f * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
        val rx = (side - nodeWidth) / 2 - 2.dp
        // one square: the ring, the places and the height all come from [side], so it is round at every width and font scale
        val ry = rx
        val accent = Studio.colors.accentText
        val ring = MaterialTheme.colorScheme.outline
        // Only what is drawn takes room: the rings, and the nodes that exist. With nothing found the box is just the rings, with no empty band.
        val ringR = rx * 1.15f
        var top = -ringR
        var bottom = ringR
        for (d in ordered) {
            val y = ry * sin(Math.toRadians(-90.0 + 360.0 / CIRCLE_SLOTS * slots.getValue(d.hint))).toFloat()
            top = minOf(top, y - nodeHeight / 2)
            bottom = maxOf(bottom, y + nodeHeight / 2)
        }
        val shift = -(top + bottom) / 2
        // when large text makes the places too tall for a round ring of this width, the list is the honest view
        if (nodeHeight > rx) { DeviceList(state, onConnect); return@BoxWithConstraints }
        Box(Modifier.fillMaxWidth().height(bottom - top + 4.dp).testTag(DevicesTags.CIRCLE), contentAlignment = Alignment.Center) {
            Box(Modifier.offset(y = shift)) { Sweep(ringR, accent, ring, searching) }
            Box(Modifier.offset(y = shift)) { CenterNode(state) }
            for (d in ordered) {
                val angle = Math.toRadians(-90.0 + 360.0 / CIRCLE_SLOTS * slots.getValue(d.hint))
                CircleNode(
                    d, nodeWidth, onConnect,
                    Modifier.offset(x = rx * cos(angle).toFloat(), y = ry * sin(angle).toFloat() + shift),
                )
            }
        }
    }
}
/** Faint rings, and a slow sweep around them while a search runs (after Stop or the minute the circle stands still), unless the system asks for no motion. */
@Composable
private fun Sweep(radius: Dp, accent: Color, ring: Color, searching: Boolean) {
    val reduced = Studio.reducedMotion || !searching
    val dark = Studio.colors.dark
    val angle = if (reduced) 0f else {
        val transition = rememberInfiniteTransition(label = "sweep")
        transition.animateFloat(0f, 360f, infiniteRepeatable(tween(7000, easing = LinearEasing), RepeatMode.Restart), label = "angle").value
    }
    Canvas(Modifier.size(radius * 2)) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = size.minDimension / 2
        drawCircle(ring.copy(alpha = if (dark) 0.5f else 1f), radius = r, center = c, style = Stroke((if (dark) 1f else 1.5f).dp.toPx()))
        drawCircle(ring.copy(alpha = if (dark) 0.3f else 0.6f), radius = r * 0.72f, center = c, style = Stroke((if (dark) 1f else 1.5f).dp.toPx()))
        if (!reduced) rotate(angle, c) {
            drawCircle(Brush.sweepGradient(listOf(Color.Transparent, accent.copy(alpha = 0.16f)), c), radius = r, center = c)
        }
    }
}

@Composable
private fun CenterNode(state: DevicesState) {
    val phone = state.thisDevice.platform == "android"
    Column(Modifier.width(88.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(platformIcon(state.thisDevice.platform), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
        }
        Text(if (phone) "This phone" else "This device", fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun CircleNode(d: DiscoveredUi, width: Dp, onConnect: (DiscoveredUi) -> Unit, modifier: Modifier) {
    Column(
        modifier.width(width).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(Studio.colors.raised)
            .clickable(role = Role.Button, onClickLabel = "Connect to ${d.name}") { onConnect(d) }
            .padding(4.dp).testTag(DevicesTags.nearbyPair(d.hint)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Icon(platformIcon(null), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        Text(d.name, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth().testTag(DevicesTags.discovered(d.hint)))
        Text(reachWords(d), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Connect", fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, color = Studio.colors.accentText)
    }
}

// ---- the list --------------------------------------------------------------------------------------------------------------

@Composable
private fun DeviceList(state: DevicesState, onConnect: (DiscoveredUi) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = state.discovered.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) || it.host.contains(query.trim(), ignoreCase = true) }
    Column(Modifier.fillMaxWidth().testTag(DevicesTags.LIST)) {
        OutlinedTextField(
            query, { query = it }, label = { Text("Find a device") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(DevicesTags.SEARCH),
        )
        Spacer(Modifier.size(4.dp))
        if (state.discovered.isNotEmpty() && shown.isEmpty()) Text("No device matches “${query.trim()}”.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        shown.forEachIndexed { index, d ->
            FoundRow(d) { onConnect(d) }
            if (index < shown.lastIndex) RowSeparator(Modifier.padding(start = 16.dp))
        }
    }
}

@Composable
private fun FoundRow(d: DiscoveredUi, onConnect: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp).testTag(DevicesTags.discovered(d.hint)), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Icon(platformIcon(null), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Text(d.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
            Text("${reachWords(d)} · ${formatAddress(d.host, d.port)}", fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalButton(onClick = onConnect, modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.nearbyPair(d.hint))) { Text("Connect") }
    }
}
