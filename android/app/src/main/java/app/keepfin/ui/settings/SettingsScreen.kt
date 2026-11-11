package app.keepfin.ui.settings

import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Check
import app.keepfin.ui.StartPage
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import app.keepfin.ui.components.LocalSettingsHighlightTag
import app.keepfin.ui.components.SettingsExpander
import app.keepfin.ui.components.highlightColor
import kotlinx.coroutines.delay
import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Description
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.material.icons.outlined.Cookie
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.EngineSettings
import app.keepfin.ToolStatus
import app.keepfin.KeepfinApplication
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.SubPage
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.NavRow
import app.keepfin.ui.components.PresetPicker
import app.keepfin.ui.components.RowSeparator
import app.keepfin.ui.components.SettingsGroup
import app.keepfin.ui.components.SpecBadge
import app.keepfin.ui.components.SupportingText
import app.keepfin.ui.components.SwitchRow
import app.keepfin.ui.downloads.OptionsSheet
import app.keepfin.core.start.Start
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.theme.ThemeMode
import app.keepfin.ui.theme.supportsDynamicColor
import app.keepfin.ui.theme.tabular

@Composable
fun SettingsScreen(vm: StudioViewModel, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val settings by vm.engine.settings.collectAsStateWithLifecycle()
    val tools by vm.engine.tools.collectAsStateWithLifecycle()
    val hasCookies by vm.engine.hasCookies.collectAsStateWithLifecycle()
    var optionsSheet by remember { mutableStateOf(false) }
    SettingsHost(vm, contentPadding, modifier) { category, padding ->
        CategoryPage(vm, category, padding, settings, tools, hasCookies) { optionsSheet = true }
    }
    if (optionsSheet) {
        OptionsSheet(media = null, options = settings.options, onOptions = { value -> vm.updateDefaultOptions { value } }, onSaveDefault = null) { optionsSheet = false }
    }
}

private class Section(val key: String, val content: @Composable () -> Unit)

/** One category screen: its groups in the order of the plan, scrolled to (and lit up at) the place a link or a search result asked for. */
@Composable
private fun CategoryPage(
    vm: StudioViewModel, category: SettingsCategory, padding: PaddingValues,
    settings: EngineSettings, tools: List<ToolStatus>, hasCookies: Boolean, onOptions: () -> Unit,
) {
    val anchor = vm.settingsAnchor?.takeIf { it.target.category == category }
    var speedOpen by rememberSaveable { mutableStateOf(false) }
    val sections: List<Section> = when (category) {
        SettingsCategory.DOWNLOADS -> listOf(
            Section(SettingsGroups.DOWNLOADS) { DownloadsGroup(vm, settings, onOptions) },
            Section(SettingsGroups.SIGN_IN) { AccountsGroup(vm, settings, hasCookies) },
            Section(SettingsGroups.SPEED) { SpeedGroup(vm, settings, speedOpen) { speedOpen = !speedOpen } },
        )
        SettingsCategory.LIBRARY -> listOf(
            Section(SettingsGroups.MODE) { ModeGroup(vm, settings) },
            Section(SettingsGroups.LIBRARY) { LibraryGroup(vm, settings) },
        )
        SettingsCategory.STORAGE -> listOf(
            Section(SettingsGroups.STORAGE) { StorageGroup(vm) },
            Section(SettingsGroups.TRASH) { app.keepfin.ui.library.TrashSettingsRow(vm) },
            Section(SettingsGroups.START_OVER) { StartOverGroup(vm) },
        )
        SettingsCategory.DEVICES -> listOf(Section(SettingsGroups.DEVICES) { app.keepfin.ui.devices.DevicesPage(vm, openAdvanced = anchor?.target?.group == SettingsGroups.DEVICES_ADVANCED) })
        SettingsCategory.APPEARANCE -> listOf(
            // where the app opens comes first: it is the choice people look for most here
            Section(SettingsGroups.START) { StartGroup(vm) },
            Section(SettingsGroups.APPEARANCE) { AppearanceGroup(vm) },
        )
        SettingsCategory.NOTIFICATIONS -> listOf(Section(SettingsGroups.NOTIFICATIONS) { NotificationsGroup(vm) })
        SettingsCategory.HELP -> listOf(Section(SettingsGroups.HELP) { HelpGroup(vm) })
        // openSettings shows the Support screen itself; this list is only what a stale saved page would land on
        SettingsCategory.SUPPORT -> listOf(Section(SettingsGroups.SUPPORT) { SupportGroup(vm) })
        SettingsCategory.ABOUT -> listOf(
            // what this is, keeping it current, then starting over: one level, no second About page
            Section(SettingsGroups.ABOUT_APP) { AboutGroup(vm) },
            Section(SettingsGroups.UPDATES) { UpdatesGroup(vm, settings, tools) },
            Section(SettingsGroups.START_OVER) { StartOverGroup(vm) },
        )
    }
    val listState = rememberLazyListState()
    val reduced = Studio.reducedMotion
    LaunchedEffect(anchor?.nonce, category) {
        val a = anchor ?: return@LaunchedEffect
        val group = a.target.group
        if (group == SettingsGroups.SPEED) speedOpen = true
        val key = if (group == SettingsGroups.DEVICES_ADVANCED) SettingsGroups.DEVICES else group
        val index = sections.indexOfFirst { it.key == key }
        if (index > 0) { if (reduced) listState.scrollToItem(index) else listState.animateScrollToItem(index) }
        delay(2000)
        vm.clearSettingsAnchor()
    }
    CompositionLocalProvider(LocalSettingsHighlightTag provides anchor?.target?.tag) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().testTag(UiTags.SETTINGS_LIST),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = padding.calculateBottomPadding() + 24.dp),
        ) {
            items(sections, key = { it.key }) { section ->
                val lit = anchor != null && anchor.target.tag == null && (anchor.target.group == section.key ||
                    (section.key == SettingsGroups.DEVICES && anchor.target.group == SettingsGroups.DEVICES_ADVANCED))
                Box(Modifier.background(highlightColor(lit), RoundedCornerShape(16.dp))) { section.content() }
            }
        }
    }
}

@Composable
private fun DownloadsGroup(vm: StudioViewModel, settings: EngineSettings, onOptions: () -> Unit) {
    SettingsGroup("", tag = UiTags.setting("downloads")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            PresetPicker(settings.defaultPreset, { preset -> vm.updateSettings { it.copy(defaultPreset = preset) } }, Modifier.fillMaxWidth(), label = "Default quality", tag = UiTags.setting("preset"))
            Spacer(Modifier.size(12.dp))
            Text("YouTube Music links", style = MaterialTheme.typography.bodyLarge)
            Text("Open as audio like other music services, or as video. You can still switch per download.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(6.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag(UiTags.YTMUSIC_AUDIO)) {
                listOf(true to "Audio (default)", false to "Video").forEachIndexed { index, (audio, label) ->
                    SegmentedButton(
                        selected = settings.ytMusicAudio == audio, onClick = { vm.updateSettings { it.copy(ytMusicAudio = audio) } },
                        shape = SegmentedButtonDefaults.itemShape(index, 2), modifier = Modifier.testTag(UiTags.setting("ytmusic-${if (audio) "audio" else "video"}")),
                    ) { Text(label) }
                }
            }
        }
        RowSeparator()
        NavRow("Default download options", subtitle = defaultOptionsSummary(settings), icon = Icons.Outlined.Tune,
            trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) }, onClick = onOptions)
        RowSeparator()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Working files", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.size(6.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("platform" to "Folder per site", "flat" to "One folder").forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = settings.organization == value, onClick = { vm.updateSettings { it.copy(organization = value) } },
                        shape = SegmentedButtonDefaults.itemShape(index, 2), modifier = Modifier.testTag(UiTags.setting("organization-$value")),
                    ) { Text(label) }
                }
            }
        }
        RowSeparator()
        SwitchRow("Add lyrics to songs", settings.lyrics, { value -> vm.updateSettings { it.copy(lyrics = value) } },
            subtitle = "Looks up lyrics on LRCLIB, a free lyrics library, and saves them in the song file.", tag = UiTags.LYRICS_SWITCH)
        if (settings.library.enabled) {
            RowSeparator()
            SwitchRow("Keep music videos automatically", settings.keepMusicVideos, { value -> vm.updateSettings { it.copy(keepMusicVideos = value) } },
                subtitle = "Songs you download as audio also get their official music video, where there is one. Videos are large.", tag = UiTags.KEEP_VIDEOS_SWITCH)
        }
        RowSeparator()
        SupportingText(if (settings.library.enabled) "Each space decides where its files go; change them in Library below. Public copies stay after uninstalling."
            else "Videos save to Movies/Keepfin, audio to Music/Keepfin, subtitles and artwork to Download/Keepfin. Public copies stay after uninstalling.")
    }
}

private fun defaultOptionsSummary(settings: EngineSettings): String {
    val o = settings.options
    return listOfNotNull(
        "Subtitles ${o.subtitles.languages}".takeIf { o.subtitles.enabled },
        "SponsorBlock ${o.sponsorBlock}".takeIf { o.sponsorBlock != "off" },
        "Embed thumbnail".takeIf { o.embedThumbnail }, "metadata".takeIf { o.embedMetadata }, "chapters".takeIf { o.embedChapters },
    ).joinToString(", ").ifEmpty { "No extras" }
}

/** Speed and connections: parallel downloads, fragments, multi-connection transfers and Wi-Fi only, in one collapsed group. */
@Composable
private fun SpeedGroup(vm: StudioViewModel, settings: EngineSettings, expanded: Boolean, onToggle: () -> Unit) {
    SettingsExpander(
        "Speed and connections",
        "${settings.concurrency} at a time · Turbo ${if (settings.turbo) "on" else "off"} · ${if (settings.wifiOnly) "Wi-Fi only" else "Wi-Fi and mobile data"}",
        expanded, onToggle, tag = UiTags.setting("speed"),
    ) {
        StepSlider("Parallel downloads", settings.concurrency, 1..4, "How many downloads run at the same time", UiTags.setting("concurrency")) { value ->
            vm.updateSettings { it.copy(concurrency = value) }
        }
        RowSeparator()
        StepSlider("Fragments per download", settings.fragments, 1..16, "Parallel pieces for streaming (HLS/DASH) sources", UiTags.setting("fragments")) { value ->
            vm.updateSettings { it.copy(fragments = value) }
        }
        RowSeparator()
        SwitchRow("Turbo transfers", settings.turbo, { value -> vm.updateSettings { it.copy(turbo = value) } },
            subtitle = "Multi-connection transfers. Off by default; can help when a site throttles each connection, but is often no faster on good networks. Falls back automatically.", tag = UiTags.setting("turbo"))
        RowSeparator()
        SwitchRow("Download on Wi-Fi only", settings.wifiOnly, { value -> vm.updateSettings { it.copy(wifiOnly = value) } },
            subtitle = "Queued downloads wait while you're on mobile data", tag = UiTags.setting("wifi"))
        SupportingText("Downloads use your phone's connection. Keepfin has no server of its own.")
    }
}

@Composable
private fun StepSlider(title: String, value: Int, range: IntRange, subtitle: String, tag: String, onCommit: (Int) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(local.toInt().toString(), style = MaterialTheme.typography.titleMedium.tabular(), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        }
        Slider(
            value = local, onValueChange = { local = it }, onValueChangeFinished = { onCommit(local.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1,
            modifier = Modifier.testTag(tag).semantics { stateDescription = "${local.toInt()}" },
        )
    }
}

@Composable
private fun AccountsGroup(vm: StudioViewModel, settings: EngineSettings, hasCookies: Boolean) {
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importCookies) }
    var confirm by remember { mutableStateOf(false) }
    SettingsGroup("Website sign-in", tag = UiTags.setting("accounts")) {
        SwitchRow("Use a sign-in when a site asks for one", settings.autoSignIn, { value -> vm.updateSettings { it.copy(autoSignIn = value) } },
            subtitle = "Downloads stay signed out. Only when a site says it needs a sign-in does Keepfin try yours, and the download's row says so.", tag = UiTags.AUTO_SIGN_IN_SWITCH)
        RowSeparator()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (hasCookies) "Signed in" else "Not signed in", style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (hasCookies) "Your saved session is used for private, age-restricted and members-only media."
                    else "Sign in to download private playlists, Watch Later, Liked videos and members-only media.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (hasCookies) SpecBadge("Cookies saved", color = Studio.colors.success)
        }
        RowSeparator()
        NavRow("Sign in to a site", subtitle = "YouTube, Instagram, X, Facebook, TikTok, Reddit, Vimeo, SoundCloud", icon = Icons.AutoMirrored.Outlined.Login,
            modifier = Modifier.testTag(UiTags.SIGN_IN_ROW), trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) }) {
            vm.open(SubPage.SIGN_IN)
        }
        RowSeparator()
        NavRow("Import cookies.txt", subtitle = "Netscape format, exported from a browser", icon = Icons.Outlined.Cookie) {
            importer.launch(arrayOf("text/*", "application/octet-stream"))
        }
        RowSeparator()
        NavRow("Clear sign-in", subtitle = "Removes saved cookies from Keepfin", icon = Icons.AutoMirrored.Outlined.Logout, enabled = hasCookies,
            modifier = Modifier.testTag(UiTags.setting("clear-sign-in"))) { confirm = true }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Clear sign-in?") },
            text = { Text("Saved cookies and the in-app browser session are removed. Downloads that need an account will ask you to sign in again.") },
            confirmButton = { Button(onClick = { confirm = false; vm.clearSignIn() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}

/**
 * One "Updates" section with two clearly separated parts: the Keepfin app (version, check, what's new, release notes, releases) and the download
 * engine, which updates itself (last checked, version as secondary text, a quiet "Check now", the channel de-emphasised).
 */
@Composable
private fun UpdatesGroup(vm: StudioViewModel, settings: EngineSettings, tools: List<ToolStatus>) {
    val snapshot = vm.updateState
    val version = vm.updates.currentVersion
    val engine = tools.firstOrNull { it.name == "yt-dlp" }
    val engineUpdating = engine?.state == "updating"
    val context = LocalContext.current
    var notes by remember { mutableStateOf<Pair<String, String>?>(null) }
    notes?.let { (v, body) -> ReleaseNotesDialog("What's new · $v", body) { notes = null } }
    SettingsGroup("", tag = UiTags.setting("updates")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag(UiTags.UPDATES_APP)) {
            Text("Keepfin", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.size(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Version $version", style = MaterialTheme.typography.bodyLarge)
                app.keepfin.ui.updates.BetaBadge(version)
            }
            Text(
                when (snapshot.phase) {
                    "checking" -> "Checking for updates…"
                    "available" -> "Version ${snapshot.version} is available"
                    "downloading" -> "Downloading version ${snapshot.version} · ${snapshot.percent}%"
                    "ready" -> "Version ${snapshot.version} is ready to install"
                    "error" -> "Could not check for updates"
                    "current" -> snapshot.message.ifBlank { "You're up to date" }
                    else -> "Keepfin updates are checked when you open the app."
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            Spacer(Modifier.size(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(onClick = vm.updatesFlow::checkOrShowUpdate, enabled = snapshot.phase != "checking", modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.UPDATE_CHECK)) {
                    Text(if (snapshot.phase in setOf("downloading", "ready")) "Show update" else "Check for updates", maxLines = 1)
                }
                OutlinedButton(onClick = vm.updatesFlow::showWhatsNew, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.UPDATE_WHATS_NEW)) { Text("What's new", maxLines = 1) }
            }
        }
        app.keepfin.ui.updates.UpdateChannelPicker(vm)
        RowSeparator()
        if (snapshot.notes.isNotEmpty()) {
            NavRow("Release notes · ${snapshot.version}", icon = Icons.Outlined.Description, modifier = Modifier.testTag("update-release-notes")) { notes = snapshot.version to snapshot.notes }
            RowSeparator()
        }
        NavRow("View releases", subtitle = "github.com/HishamSanjag/keepfin", icon = Icons.AutoMirrored.Outlined.OpenInNew) {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(app.keepfin.UpdateRelease.PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: Exception) { vm.say("No browser is available.") }
        }
        RowSeparator()
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag(UiTags.UPDATES_ENGINE)) {
            Text("Download engine — updates automatically", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(4.dp))
            Text(
                when {
                    engineUpdating -> "Updating now…"
                    engine?.state == "error" -> "Needs attention"
                    else -> engine?.checkedAt?.takeIf { it > 0 }?.let { "Last checked ${app.keepfin.ui.Format.relativeDay(it).lowercase()}" } ?: "Checks once a day in the background"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (engine?.state == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            engine?.message?.takeIf { it.isNotBlank() && !engineUpdating }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (engine.state == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            engine?.version?.takeIf { it.isNotBlank() }?.let {
                Text("Engine version $it", style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(4.dp))
            TextButton(onClick = vm::updateTools, enabled = !engineUpdating, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.ENGINE_CHECK_NOW)) {
                if (engineUpdating) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)); Text("Updating…") }
                else Text(if (engine?.state == "error") "Fix now" else "Check now")
            }
        }
        RowSeparator()
        SwitchRow("Update automatically", settings.autoUpdate, { value -> vm.updateSettings { it.copy(autoUpdate = value) } },
            subtitle = "Checks at most once a day in the background", tag = UiTags.setting("auto-update"))
        RowSeparator()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Engine channel", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(6.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("stable" to "Stable", "nightly" to "Early access").forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = settings.channel == value, onClick = { vm.updateSettings { it.copy(channel = value) } },
                        shape = SegmentedButtonDefaults.itemShape(index, 2), modifier = Modifier.testTag(UiTags.setting("channel-$value")),
                    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                }
            }
            Text("Early access gets site fixes first; Stable is tested longer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun NotificationsGroup(vm: StudioViewModel) {
    val context = LocalContext.current
    val notifications = remember(context) { (context.applicationContext as KeepfinApplication).notifications }
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val allowed = remember(tick) { notifications.allowed() }
    val blocked = remember(tick) { runCatching { notifications.blockedCategories() }.getOrDefault(emptyList()) }
    val master = remember(tick) { notifications.enabled("enabled") }
    SettingsGroup("", tag = UiTags.setting("notifications")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.NotificationsActive, contentDescription = null, tint = if (allowed) Studio.colors.success else Studio.colors.warning)
            Spacer(Modifier.width(16.dp))
            Text(
                if (allowed) "Android allows notifications from Keepfin." else "Android is blocking notifications. Allow them to see progress and completion alerts.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        if (blocked.isNotEmpty()) SupportingText("Blocked in Android channel settings: ${blocked.joinToString()}.")
        RowSeparator()
        listOf(
            "enabled" to "Download notifications", "progress" to "Progress and processing", "completed" to "Completed downloads",
            "failed" to "Failures and save problems", "cancelled" to "Cancelled downloads", "sound" to "Play alert sounds",
            "previews" to "Media previews in alerts", "private" to "Hide details on lock screen",
        ).forEach { (key, title) ->
            val checked = remember(tick, key) { notifications.enabled(key) }
            SwitchRow(title, checked, { notifications.configure(key, it); tick++ }, enabled = key == "enabled" || master, tag = "notification-$key")
        }
        SupportingText("Alerts are silent by default. Android keeps a small status notification while downloads run, even when progress alerts are off.")
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                notifications.channels()
                val needsPermission = Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                if (needsPermission && !allowed) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, modifier = Modifier.weight(1f).testTag("notification-system-settings")) { Text(if (allowed) "System settings" else "Allow", maxLines = 1) }
            OutlinedButton(
                onClick = { vm.say(runCatching { notifications.test(); "Test notification sent" }.getOrElse { it.message ?: "Android could not send the alert." }) },
                enabled = master && allowed, modifier = Modifier.weight(1f).testTag("notification-test"),
            ) { Text("Send test", maxLines = 1) }
        }
    }
}

@Composable
private fun AppearanceGroup(vm: StudioViewModel) {
    SettingsGroup("", tag = UiTags.setting("appearance")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("Theme", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.size(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = vm.prefs.theme == mode, onClick = { vm.prefs.chooseTheme(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size), modifier = Modifier.testTag(UiTags.setting("theme-${mode.wire}")),
                    ) { Text(mode.label) }
                }
            }
        }
        RowSeparator()
        PlayerStyleRow(vm)
        RowSeparator()
        SwitchRow("Pure black", vm.prefs.pureBlack, vm.prefs::choosePureBlack,
            subtitle = "A true black background in the dark theme, easier on OLED screens", tag = UiTags.SETTING_PURE_BLACK)
        if (supportsDynamicColor()) {
            RowSeparator()
            SwitchRow("Material You colors", vm.prefs.dynamicColor, vm.prefs::chooseDynamicColor,
                subtitle = "Use Material You colors from your wallpaper instead of the Keepfin blue", tag = UiTags.setting("dynamic-color"))
        }
        RowSeparator()
        AppIconPicker()
        SupportingText("Text size follows your Android font size and display settings.")
    }
}

/** Settings, Appearance, Player style: the same choice as the palette button in the full player header (the PC has both too). */
@Composable
private fun PlayerStyleRow(vm: StudioViewModel) {
    val styles = app.keepfin.ui.player.PlayerStyles.all
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text("Player style", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.size(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            styles.forEachIndexed { index, style ->
                SegmentedButton(
                    selected = vm.prefs.playerStyle == style.id, onClick = { vm.prefs.choosePlayerStyle(style.id) },
                    shape = SegmentedButtonDefaults.itemShape(index, styles.size), modifier = Modifier.testTag(UiTags.setting("player-style-${style.id}")),
                ) { Text(style.name, maxLines = 1) }
            }
        }
        Text(app.keepfin.ui.player.PlayerStyles.byId(vm.prefs.playerStyle).summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

/** Keepfin itself, first on About Keepfin: the logo, the version and channel, what it is and what runs where. */
@Composable
private fun AboutGroup(vm: StudioViewModel) {
    val version = vm.updates.currentVersion
    SettingsGroup("", tag = UiTags.setting("about-app")) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            app.keepfin.ui.setup.KeepfinLogo(96.dp)
            Spacer(Modifier.width(16.dp))
            Column {
                Text("Keepfin", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Version $version", style = MaterialTheme.typography.bodyLarge)
                    app.keepfin.ui.updates.BetaBadge(version)
                }
                Text(
                    "${if (vm.prefs.appChannel == app.keepfin.UpdateChannel.BETA) "Beta" else "Stable"} channel",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            "Capture video and audio links, download on your phone and keep everything in one place.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        SupportingText("Downloads run on this phone. No account, server or tracking.")
    }
}

/** How to use Keepfin and how to support it: the features guide and the support page. */
@Composable
private fun HelpGroup(vm: StudioViewModel) {
    SettingsGroup("", tag = UiTags.setting("help")) {
        NavRow("Features & how-to", subtitle = "What Keepfin can do, with the steps", icon = Icons.AutoMirrored.Outlined.MenuBook,
            modifier = Modifier.testTag(UiTags.SETTINGS_GUIDE_ROW), trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) }) {
            vm.open(SubPage.GUIDE)
        }
    }
}

/** Support Keepfin, apart from Help (as on Windows). */
@Composable
private fun SupportGroup(vm: StudioViewModel) {
    SettingsGroup("", tag = UiTags.setting("support")) {
        NavRow("Support Keepfin", subtitle = "Keepfin is free. Help keep it going.", icon = Icons.Outlined.Favorite,
            modifier = Modifier.testTag(UiTags.SUPPORT_ROW), trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) }) {
            vm.open(SubPage.SUPPORT)
        }
    }
}

/** Library & playback › Keepfin mode: the Library on/off setting under a clearer name (the only place it is set). */
@Composable
private fun ModeGroup(vm: StudioViewModel, settings: EngineSettings) {
    val full = settings.library.enabled
    SettingsGroup("Keepfin mode", tag = UiTags.setting("mode")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(false to Start.DOWNLOADS, true to Start.COMPLETE).forEachIndexed { index, (isFull, label) ->
                    SegmentedButton(
                        selected = full == isFull, onClick = { vm.chooseMode(isFull) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                        modifier = Modifier.testTag(if (isFull) UiTags.SETTING_MODE_FULL else UiTags.SETTING_MODE_DOWNLOADER),
                    ) { Text(label, maxLines = 1) }
                }
            }
            Text(
                if (full) "Home, Search and Library with a built-in player for songs, videos and photos. Nothing on your phone changes if you switch."
                else "Only Downloads: paste a link, pick the quality, find the file in a folder. No library and no player. Nothing on your phone changes if you switch.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** "Open Keepfin on": Downloads, Library or where the user left off. */
@Composable
private fun StartGroup(vm: StudioViewModel) {
    var open by remember { mutableStateOf(false) }
    val current = vm.prefs.startPage
    SettingsGroup("", tag = UiTags.setting("start")) {
        Box {
            NavRow(
                "Open Keepfin on", subtitle = "The page you see when the app starts", modifier = Modifier.testTag(UiTags.SETTINGS_START_PAGE),
                trailing = { Text("${current.label()} ▾", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = app.keepfin.ui.theme.Studio.colors.accentText) },
            ) { open = true }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                StartPage.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label()) }, onClick = { open = false; vm.prefs.chooseStartPage(option) },
                        trailingIcon = { if (option == current) Icon(Icons.Outlined.Check, contentDescription = "Selected") },
                        modifier = Modifier.testTag(UiTags.setting("start-${option.wire}")),
                    )
                }
            }
        }
        RowSeparator()
        SwitchRow(
            "Offer copied links", vm.prefs.offerCopiedLinks, vm.prefs::chooseOfferCopiedLinks,
            subtitle = "When Keepfin opens with a new link copied, ask whether to download it. Other text you copy is never read.", tag = UiTags.setting("offer-copied-links"),
        )
    }
}

private fun StartPage.label() = when (this) { StartPage.DOWNLOADS -> "Downloads"; StartPage.HOME -> "Home"; StartPage.LIBRARY -> "Library"; StartPage.LAST -> "Where I left off" }
