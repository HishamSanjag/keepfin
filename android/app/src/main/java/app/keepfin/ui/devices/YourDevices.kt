package app.keepfin.ui.devices

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import app.keepfin.core.net.NetAction
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.keepfin.net.DevicesState
import app.keepfin.net.PairedDeviceUi
import app.keepfin.ui.components.Pane
import app.keepfin.ui.components.RowSeparator
import app.keepfin.ui.components.SwitchRow
import app.keepfin.ui.theme.Studio

// ---- words for a paired device -----------------------------------------------------------------------------------------------

/** "Up to date", "Syncing…", "Connected" (nothing shared yet) or "Offline". */
internal fun statusWord(p: PairedDeviceUi): String = when {
    !p.online -> "Offline"
    p.libraries.isEmpty() -> "Connected"
    p.lastSync == null -> "Syncing…"
    else -> "Up to date"
}

/** "Windows PC", "Android phone": what kind of device it is. */
internal fun platformName(platform: String): String = when (platform) {
    "windows" -> "Windows PC"
    "android" -> "Android phone"
    "macos" -> "Mac"
    "linux" -> "Linux PC"
    "ios" -> "iPhone"
    else -> "Device"
}

/** Second line of a device's row: "Windows PC · via Tailscale", or offline "Windows PC · last seen 5 minutes ago" (the chip already says Offline). */
internal fun reachLine(p: PairedDeviceUi): String {
    val kind = platformName(p.platform)
    if (p.online) return "$kind · " + if ((p.route.takeIf { it != "none" } ?: "lan") == "tailscale") "Tailscale" else "Home network"
    val seen = p.lastSeen ?: return "$kind · not seen yet"
    return "$kind · last seen ${DateUtils.getRelativeTimeSpanString(seen, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)}"
}

private fun clock(at: Long): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(at))

/**
 * One sentence for the expanded card. The chip on the row already says Connected / Up to date / Offline, so this says what happened:
 * "3 changes synced 21:04", "Checked 21:04", "Nothing shared yet", "Not reachable since 11:02 PM · Keepfin reconnects by itself".
 */
internal fun statusSentence(p: PairedDeviceUi): String {
    if (!p.online) {
        val since = p.unreachableSince ?: p.lastSeen
        return (if (since != null) "Not reachable since ${clock(since)}" else "Not reachable") + " · Keepfin reconnects by itself"
    }
    if (p.libraries.isEmpty()) return "Nothing shared yet"
    val last = p.lastSync ?: return "Catching up with the library…"
    val changes = last.pulled + last.pushed
    return if (changes > 0) "${if (changes == 1) "1 change" else "$changes changes"} synced ${clock(last.at)}" else "Checked ${clock(last.at)}"
}

private class Chip(val action: NetAction, val label: String, val hint: String)

private val CHIPS = listOf(
    Chip(NetAction.InventoryRead, "See files", "Lets it see which files you hold. Needed for it to keep a copy."),
    Chip(NetAction.MediaRead, "Play from here", "Lets it stream or copy your music and videos."),
    Chip(NetAction.CopyMutate, "Keep a copy here", "Lets it write copies of its library into your Keepfin folder."),
    Chip(NetAction.Jobs, "Download here", "Lets it ask this phone to download links."),
    Chip(NetAction.MembershipAdmin, "Add devices to the library", "Lets it add and remove devices in the library."),
)

/** What a device may do in one library, as small toggles in a wrapping row; the lead label is "<name> can" (or the library's name when there are several). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionChips(p: PairedDeviceUi, lib: app.keepfin.net.PairedLibraryUi, lead: String, controller: DevicesController, run: (() -> String?) -> Unit) {
    Text(lead, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
    Text("Tap to allow or stop each one.", fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(0.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        for (c in CHIPS) {
            val on = c.action.wire in lib.actions
            FilterChip(
                selected = on,
                onClick = {
                    val next = ACTION_LABELS.map { it.first }.filter { a -> if (a == c.action) !on else a.wire in lib.actions }
                    run { controller.setAccess(p.deviceId, lib.libraryId, next); null }
                },
                label = { Text(c.label, fontSize = 13.sp) },
                leadingIcon = if (on) ({ Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer, selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer, labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.semantics { contentDescription = "${c.label}. ${c.hint}" }.testTag(DevicesTags.access(p.deviceId, c.action.wire)),
            )
        }
    }
}

/** Files over mobile data, and sharing a library this phone holds with the device. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExtraChips(p: PairedDeviceUi, controller: DevicesController, run: (() -> String?) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = p.allowMobileFiles, onClick = { run { controller.setAllowMobileFiles(p.deviceId, !p.allowMobileFiles); null } },
            label = { Text("Files over mobile data", fontSize = 13.sp) },
            leadingIcon = if (p.allowMobileFiles) ({ Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }) else null,
            modifier = Modifier.semantics { contentDescription = "Files over mobile data. Files wait for Wi-Fi unless this is on. The library list always syncs." }.testTag(DevicesTags.MOBILE_FILES),
        )
        for (s in p.shareable) AssistChip(onClick = { run { controller.shareLibrary(p.deviceId, s.libraryId); null } }, label = { Text("Share ${s.name}", fontSize = 13.sp) })
    }
}
/** The small pill on the right: "Sync" while the device is reachable, "Retry" when it is offline. */
internal fun pillLabel(p: PairedDeviceUi): String = if (p.online) "Sync" else "Retry"

// ---- the section -----------------------------------------------------------------------------------------------------------------

/** One card per paired device, each collapsed to a row; several may be open at once. */
@Composable
internal fun YourDevicesSection(
    state: DevicesState, controller: DevicesController, run: (() -> String?) -> Unit,
    copies: app.keepfin.copies.CopiesUi? = null, onSetUp: ((String) -> Unit)? = null, onReview: (() -> Unit)? = null,
    combine: DeviceCombine? = null, combineState: app.keepfin.merge.CombineState? = null, onSeparate: ((String) -> Unit)? = null, onReviewCombine: ((String) -> Unit)? = null,
    /** A device to show open (a tap in Where your files are). */
    openRequest: Pair<String, Long>? = null,
) {
    var open by rememberSaveable { mutableStateOf(emptyList<String>()) }
    androidx.compose.runtime.LaunchedEffect(openRequest) { if (openRequest != null && openRequest.first !in open) open = open + openRequest.first }
    SectionHeading("Your devices")
    if (state.paired.isEmpty()) Notice("No devices yet. Connect one from Available devices above.")
    else Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(DevicesTags.YOURS), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (p in state.paired) {
            val expanded = p.deviceId in open
            PairedCard(
                p, expanded, { open = if (expanded) open - p.deviceId else open + p.deviceId }, state, controller, run, copyOn(copies, p.deviceId), onSetUp, onReview,
                combine, combineFor(combineState, p.deviceId), onSeparate, onReviewCombine,
            )
        }
    }
}

@Composable
private fun PairedCard(
    p: PairedDeviceUi, expanded: Boolean, onToggle: () -> Unit, state: DevicesState, controller: DevicesController, run: (() -> String?) -> Unit,
    copy: app.keepfin.copies.CopyCardUi?, onSetUp: ((String) -> Unit)?, onReview: (() -> Unit)?,
    combine: DeviceCombine? = null, combineState: app.keepfin.merge.CombineState? = null, onSeparate: ((String) -> Unit)? = null, onReviewCombine: ((String) -> Unit)? = null,
) {
    var confirm by remember { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Pane(Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
        // every row is the same height: icon, name + one line, the status chip, the chevron
        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = 64.dp).clip(RoundedCornerShape(12.dp))
                    .semantics(mergeDescendants = true) { stateDescription = if (expanded) "Expanded" else "Collapsed" }
                    .clickable(role = Role.Button, onClickLabel = if (expanded) "Collapse ${p.name}" else "Expand ${p.name}", onClick = onToggle)
                    .padding(horizontal = 12.dp, vertical = 8.dp).testTag(DevicesTags.paired(p.deviceId)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    Icon(platformIcon(p.platform), contentDescription = null, tint = muted, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                    Text(reachLine(p), fontSize = 12.sp, lineHeight = 16.sp, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.size(8.dp))
                StatusChip(p)
            }
            IconButton(onClick = onToggle, modifier = Modifier.size(48.dp).testTag(DevicesTags.chevron(p.deviceId))) {
                Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = if (expanded) "Collapse ${p.name}" else "Expand ${p.name}", tint = muted)
            }
        }
        // combining two libraries: on the card whether it is open or not (it also survives a restart)
        if (combine != null && combineState != null) {
            CombineCardBlock(
                p.deviceId, p.name, combineState, expanded, combine, onSeparate = { onSeparate?.invoke(p.deviceId) }, onReview = { onReviewCombine?.invoke(p.deviceId) },
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
        if (expanded) {
            // sync, a divider, then what the device may do: 16 dp between the parts
            val syncing = p.online && p.libraries.isNotEmpty() && p.lastSync == null
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp).testTag(DevicesTags.DETAILS), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(statusSentence(p), fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).testTag(DevicesTags.SYNC))
                        TextButton(
                            onClick = { run { controller.syncNow(p.deviceId); null } }, enabled = !syncing,
                            modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.syncNow(p.deviceId)),
                        ) { Text(if (p.online) "Sync now" else "Retry") }
                    }
                    if (copy != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(copyStatusSentence(copy, p.name), fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f).testTag(SetupTags.copyStatus(p.deviceId)))
                            if (copy.review > 0 && onReview != null) TextButton(onClick = onReview, modifier = Modifier.heightIn(min = 48.dp).testTag(SetupTags.reviewLink(p.deviceId))) { Text("Review") }
                        }
                    } else if (onSetUp != null) {
                        TextButton(onClick = { onSetUp(p.deviceId) }, modifier = Modifier.heightIn(min = 48.dp).testTag(SetupTags.cardSetUp(p.deviceId))) { Text("Choose what stays up to date") }
                    }
                }
                RowSeparator()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (lib in p.libraries) {
                        PermissionChips(p, lib, if (p.libraries.size > 1) "Allowed for ${p.name} · ${lib.name}" else "Allowed for ${p.name}", controller, run)
                    }
                    ExtraChips(p, controller, run)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        // the normal font with tabular digits: some phones draw a monospace font as handwriting
                        Text("Fingerprint ${p.fingerprint}", fontSize = 11.sp, lineHeight = 15.sp, style = TabularDigits, color = muted, maxLines = 2)
                        p.address?.let { Text("Last address $it", fontSize = 11.sp, lineHeight = 15.sp, color = muted) }
                    }
                    TextButton(
                        onClick = { confirm = true }, modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.unpair(p.deviceId)),
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Unpair") }
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false }, title = { Text("Unpair ${p.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This phone forgets ${p.name}. It can no longer sync or ask for downloads. Copies already on either device stay.", fontSize = 14.sp)
                    Text("In libraries both of you share, ${p.name} is revoked from now on: what it writes later is set aside. Anything it already copied stays with it.", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (state.libraries.any { l -> p.libraries.any { it.libraryId == l.libraryId } && !l.admin }) {
                        Text("This phone does not administer every shared library. Unpair the device on the administrator device too.", fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { run { controller.remove(p.deviceId); null }; confirm = false }, modifier = Modifier.heightIn(min = 48.dp).testTag(DevicesTags.REMOVE_CONFIRM),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Unpair") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep") } },
        )
    }
}

/** Normal font with tabular digits (not Monospace: on some phones that is drawn as a handwriting font). */
internal val TabularDigits = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")

/**
 * The status as a small pill on the right of the row: Connected, Syncing…, Up to date or Offline. Always a filled container with its
 * own label colour (and a hairline for the quiet ones), so it reads as a pill in light and dark.
 */
@Composable
private fun StatusChip(p: PairedDeviceUi) {
    val word = statusWord(p)
    val good = p.online && p.lastSync != null
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (good) colors.secondaryContainer else colors.surfaceContainerHighest,
        contentColor = if (good) colors.onSecondaryContainer else colors.onSurfaceVariant,
        border = if (good) null else androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
    ) {
        Text(
            word, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp).testTag(DevicesTags.pill(p.deviceId)),
        )
    }
}
