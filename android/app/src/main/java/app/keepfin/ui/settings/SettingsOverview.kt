package app.keepfin.ui.settings

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.EngineSettings
import app.keepfin.KeepfinApplication
import app.keepfin.copies.CopiesRuntime
import app.keepfin.copies.CopiesUi
import app.keepfin.copies.CopyStatus
import app.keepfin.net.DevicesState
import app.keepfin.net.NetRuntime
import app.keepfin.net.PairingNotifier
import app.keepfin.setup.StorageStatus
import app.keepfin.ui.Format
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.RowSeparator
import app.keepfin.ui.components.ScreenTitle
import app.keepfin.ui.theme.Studio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun SettingsCategory.icon(): ImageVector = when (this) {
    SettingsCategory.DOWNLOADS -> Icons.Outlined.Download
    SettingsCategory.LIBRARY -> Icons.Outlined.VideoLibrary
    SettingsCategory.STORAGE -> Icons.Outlined.Folder
    SettingsCategory.DEVICES -> Icons.Outlined.Devices
    SettingsCategory.APPEARANCE -> Icons.Outlined.Palette
    SettingsCategory.NOTIFICATIONS -> Icons.Outlined.NotificationsNone
    SettingsCategory.HELP -> Icons.AutoMirrored.Outlined.HelpOutline
    SettingsCategory.SUPPORT -> Icons.Outlined.FavoriteBorder
    SettingsCategory.ABOUT -> Icons.Outlined.Info
}

/** The soft tone behind each category icon: blue for the things you download with, purple for the library, neutral otherwise. */
@Composable
private fun SettingsCategory.tone(): Color {
    val dark = Studio.colors.dark
    return when (this) {
        SettingsCategory.DOWNLOADS, SettingsCategory.ABOUT -> MaterialTheme.colorScheme.primaryContainer
        SettingsCategory.LIBRARY -> if (dark) Color(0xFF332C5A) else Color(0xFFE6E1F5)
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
}

/** Which categories this build offers (Storage and Devices need the Keepfin folder build; Devices also needs a modern TLS stack). */
internal fun StudioViewModel.settingsVisibility(): SettingsVisibility {
    val folder = setup != null
    return SettingsVisibility(storage = folder, devices = folder && NetRuntime.available)
}

/**
 * Settings: the home list of seven categories with live summaries (and "Needs your attention" above it), the search screen, and one
 * screen per category. Which of the three shows comes from the view model so that links from elsewhere in the app can open any of them.
 */
@Composable
fun SettingsHost(vm: StudioViewModel, contentPadding: PaddingValues, modifier: Modifier = Modifier, page: @Composable (SettingsCategory, PaddingValues) -> Unit) {
    val reduced = Studio.reducedMotion
    val key: Any = if (vm.settingsSearching) "search" else vm.settingsPage ?: "home"
    AnimatedContent(
        targetState = key, modifier = modifier.fillMaxSize(), label = "settings-page",
        transitionSpec = {
            val ms = if (reduced) 0 else 220
            if (targetState != "home") (slideInHorizontally(tween(ms)) { it / 8 } + fadeIn(tween(ms))) togetherWith (fadeOut(tween(ms)))
            else fadeIn(tween(ms)) togetherWith (slideOutHorizontally(tween(ms)) { it / 8 } + fadeOut(tween(ms)))
        },
    ) { target ->
        when (target) {
            "home" -> SettingsOverview(vm, contentPadding)
            "search" -> SettingsSearchScreen(vm, contentPadding)
            else -> SettingsPageFrame(vm, target as SettingsCategory) { page(target, contentPadding) }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- what needs attention

/** One real problem and where its fix lives. */
data class AttentionItem(val id: String, val text: String, val fixLabel: String, val target: SettingsTarget)

/** What the attention line reads (existing state only, never invented). */
internal data class SettingsFacts(
    val storage: StorageStatus? = null,
    val copies: CopiesUi? = null,
    val updateVersion: String? = null,
    val pairingFrom: String? = null,
)

/** One line per real problem: the Keepfin folder, a copy, a waiting update, a pairing request. Empty when there is nothing to fix. */
internal fun attentionItems(f: SettingsFacts): List<AttentionItem> = buildList {
    val storageTarget = SettingsTarget(SettingsCategory.STORAGE, SettingsGroups.STORAGE)
    when (val s = f.storage) {
        is StorageStatus.NeedsAccess -> add(AttentionItem("storage", "Keepfin needs access to its folder again", "Fix", storageTarget))
        is StorageStatus.Unavailable -> add(AttentionItem("storage", "The Keepfin folder is not available (${s.label})", "Fix", storageTarget))
        is StorageStatus.Problem -> add(AttentionItem("storage", "The Keepfin folder has a problem: ${s.message}", "Fix", storageTarget))
        else -> {}
    }
    f.copies?.places?.forEach { place ->
        when (place.status) {
            CopyStatus.REFUSED -> add(AttentionItem("copy-${place.id}", "The copy “${place.name}” was refused", "Fix", storageTarget))
            CopyStatus.DISCONNECTED -> add(AttentionItem("copy-${place.id}", "The copy “${place.name}” is not connected", "Fix", storageTarget))
            else -> {}
        }
    }
    val review = f.copies?.review?.size ?: 0
    if (review > 0) add(AttentionItem("review", "$review ${if (review == 1) "item needs" else "items need"} review", "Review", storageTarget))
    f.updateVersion?.let { add(AttentionItem("update", "Keepfin $it is ready to install", "Update", SettingsTarget(SettingsCategory.ABOUT, SettingsGroups.UPDATES))) }
    f.pairingFrom?.let { add(AttentionItem("pairing", "$it wants to pair with this phone", "Open", SettingsTarget(SettingsCategory.DEVICES, SettingsGroups.DEVICES))) }
}

/** The "Needs your attention" block: nothing at all when there are no items. */
@Composable
internal fun AttentionBlock(items: List<AttentionItem>, onFix: (AttentionItem) -> Unit, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    Surface(
        // a warning tint, not the theme's tertiary (green reads as "all good")
        shape = RoundedCornerShape(18.dp), color = Studio.colors.warning.copy(alpha = 0.16f).compositeOver(colors.surface), contentColor = colors.onSurface,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(UiTags.SETTINGS_ATTENTION),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp)) {
            Text("Needs your attention", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            items.forEach { item ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Studio.colors.warning, modifier = Modifier.size(18.dp))
                    Text(item.text, fontSize = 14.sp, lineHeight = 19.sp, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                    TextButton(onClick = { onFix(item) }, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.settingsAttentionFix(item.id))) { Text(item.fixLabel) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------- home

/** What one row of the home list shows. */
internal data class HomeRow(val category: SettingsCategory, val summary: String, val dot: Boolean = false)

internal fun devicesSummary(state: DevicesState?): String = when {
    state == null || !state.enabled -> "Off · sync with your other devices"
    state.portError != null -> "Can't be reached · port in use"
    state.paired.isEmpty() -> "On · no devices yet"
    else -> {
        val online = state.paired.firstOrNull { it.online }
        if (online != null) "${online.name} connected" + (state.paired.size - 1).let { if (it > 0) " · +$it more" else "" }
        else "${state.paired.size} ${if (state.paired.size == 1) "device" else "devices"} · none connected now"
    }
}

internal fun storageSummary(status: StorageStatus?, hasFolder: Boolean, copies: CopiesUi?): String {
    val n = copies?.places?.size ?: 0
    val copiesText = if (n == 0) "no copies" else "$n ${if (n == 1) "copy" else "copies"}"
    return when (status) {
        is StorageStatus.Ok -> listOfNotNull(status.label, status.freeBytes?.let { Format.bytes(it) + " free" }, copiesText).joinToString(" · ")
        is StorageStatus.NeedsAccess -> "Needs access again"
        is StorageStatus.Unavailable -> "Folder not available"
        is StorageStatus.Problem -> "Folder problem"
        StorageStatus.NoFolder -> "No Keepfin folder yet"
        null -> if (hasFolder) "Keepfin folder" else "No Keepfin folder yet"
    }
}

@Composable
private fun SettingsOverview(vm: StudioViewModel, contentPadding: PaddingValues) {
    val settings by vm.engine.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val app = context.applicationContext as? KeepfinApplication
    val notificationsOn = remember(context) { runCatching { app!!.notifications.enabled("enabled") }.getOrDefault(true) }
    val visibility = vm.settingsVisibility()
    val setup = vm.setup

    var storage by remember { mutableStateOf<StorageStatus?>(null) }
    LaunchedEffect(setup) { if (setup != null) storage = withContext(Dispatchers.IO) { runCatching { setup.checkStorage() }.getOrNull() } }
    val copiesManager = remember(app, setup) { if (setup != null && app != null) runCatching { CopiesRuntime.of(app) }.getOrNull() else null }
    LaunchedEffect(copiesManager) { if (copiesManager != null) withContext(Dispatchers.IO) { runCatching { copiesManager.start() } } }
    val copies = copiesManager?.state?.collectAsStateWithLifecycle()?.value
    val devices = remember { NetRuntime.peek()?.state }?.collectAsStateWithLifecycle()?.value
    val incoming by NetRuntime.incomingPairing.collectAsStateWithLifecycle()

    val facts = SettingsFacts(
        storage = storage, copies = copies,
        updateVersion = if (vm.updatesFlow.updateAvailable) vm.updateState.version.ifBlank { "update" } else null,
        pairingFrom = incoming?.takeIf { PairingNotifier.needsPerson(it) && visibility.devices }?.peerName,
    )
    val rows = SettingsCategory.entries.filter { visibility.shows(it) }.map { category ->
        HomeRow(category, summary(vm, category, settings, notificationsOn, storageSummary(storage, setup?.hasFolder() == true, copies), devicesSummary(devices)), dot = category == SettingsCategory.ABOUT && vm.updatesFlow.updateAvailable)
    }
    SettingsHomeContent(rows, attentionItems(facts), contentPadding, onOpen = { vm.openSettings(SettingsTarget(it)) }, onFix = { vm.openSettings(it.target) }, onSearch = { vm.searchSettings(true) })
}

/** The home list. Pure over its rows so tests can drive it without an engine. */
@Composable
internal fun SettingsHomeContent(
    rows: List<HomeRow>, attention: List<AttentionItem>, contentPadding: PaddingValues,
    onOpen: (SettingsCategory) -> Unit, onFix: (AttentionItem) -> Unit, onSearch: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize().testTag(UiTags.SETTINGS_OVERVIEW), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp)) {
        item("title") {
            ScreenTitle("Settings") {
                IconButton(onClick = onSearch, modifier = Modifier.size(48.dp).testTag(UiTags.SETTINGS_SEARCH)) {
                    Icon(Icons.Outlined.Search, contentDescription = "Search settings")
                }
            }
        }
        if (attention.isNotEmpty()) item("attention") { Spacer(Modifier.size(4.dp)); AttentionBlock(attention, onFix); Spacer(Modifier.size(8.dp)) }
        item("list") {
            Spacer(Modifier.size(4.dp))
            Surface(
                shape = RoundedCornerShape(18.dp), color = Studio.colors.raised,
                border = if (Studio.colors.dark) null else BorderStroke(1.dp, Studio.colors.separator),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Column {
                    rows.forEachIndexed { index, row ->
                        CategoryRow(row, onOpen)
                        if (index < rows.lastIndex) RowSeparator(Modifier.padding(start = 66.dp))
                    }
                }
            }
        }
    }
}

private fun summary(vm: StudioViewModel, category: SettingsCategory, settings: EngineSettings, notificationsOn: Boolean, storage: String, devices: String): String = when (category) {
    SettingsCategory.DOWNLOADS -> "${settings.defaultPreset.label} · YouTube Music as ${if (settings.ytMusicAudio) "audio" else "video"}"
    SettingsCategory.LIBRARY -> if (settings.library.enabled) {
        val n = settings.library.spaces.size
        "On · $n ${if (n == 1) "space" else "spaces"} · ${if (settings.library.builtInPlayer) "Keepfin player" else "Another app"}"
    } else "Off · plain downloader"
    SettingsCategory.STORAGE -> storage
    SettingsCategory.DEVICES -> devices
    SettingsCategory.APPEARANCE -> "${vm.prefs.theme.label} theme" + if (vm.prefs.dynamicColor) " · Material You" else ""
    SettingsCategory.NOTIFICATIONS -> if (notificationsOn) "Download alerts on" else "Download alerts off"
    SettingsCategory.HELP -> "Features & how-to"
    SettingsCategory.SUPPORT -> "Keepfin is free. Help keep it going."
    SettingsCategory.ABOUT -> when {
        vm.updatesFlow.updateAvailable -> "Keepfin ${vm.updateState.version} is available"
        vm.updateState.phase == "checking" -> "Checking for updates…"
        else -> "Version ${vm.updates.currentVersion}"
    }
}

@Composable
private fun CategoryRow(row: HomeRow, onOpen: (SettingsCategory) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val category = row.category
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button) { onOpen(category) }
            .testTag(UiTags.settingsCategory(category.name)).padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(category.tone()), contentAlignment = Alignment.Center) {
            Icon(category.icon(), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(category.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.onSurface)
            Text(
                row.summary, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                color = if (row.dot) colors.primary else colors.onSurfaceVariant, fontWeight = if (row.dot) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
        if (row.dot) Box(Modifier.size(8.dp).clip(CircleShape).background(colors.primary).testTag(UiTags.UPDATE_DOT))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.outline, modifier = Modifier.size(20.dp))
    }
}

/** A category page: back arrow and the row's title, then its settings. System back gets the predictive peek back to the overview. */
@Composable
private fun SettingsPageFrame(vm: StudioViewModel, category: SettingsCategory, content: @Composable () -> Unit) {
    var peek by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = true) { progress ->
        try {
            progress.collect { event -> peek = event.progress }
            peek = 0f
            vm.chooseSettingsPage(null)
        } catch (cancelled: CancellationException) {
            peek = 0f
            throw cancelled
        }
    }
    Column(
        Modifier.fillMaxSize().graphicsLayer {
            val p = peek
            translationX = p * 32.dp.toPx()
            scaleX = 1f - p * 0.06f; scaleY = 1f - p * 0.06f
            alpha = 1f - p * 0.25f
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.chooseSettingsPage(null) }, modifier = Modifier.testTag(UiTags.SETTINGS_PAGE_BACK)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back to Settings")
            }
            SettingsHeading(category.title, Modifier.weight(1f))
        }
        content()
    }
}

/**
 * The title of a Settings page: the "Settings ›" trail above the page name, so it is clear you are one level in. The back arrow next
 * to it is the control; the trail is part of what TalkBack reads with the title.
 */
@Composable
fun SettingsHeading(title: String, modifier: Modifier = Modifier) {
    Column(modifier.semantics(mergeDescendants = true) { heading() }) {
        Text("Settings ›", fontSize = 12.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.testTag(UiTags.SETTINGS_EYEBROW))
        Text(title, fontSize = 19.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
