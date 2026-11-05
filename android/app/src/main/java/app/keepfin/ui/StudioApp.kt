package app.keepfin.ui

import android.Manifest
import android.app.Activity
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.textclassifier.TextClassifier
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.Job
import app.keepfin.JobStatus
import app.keepfin.KeepfinApplication
import app.keepfin.ui.downloads.ClipboardHint
import app.keepfin.ui.downloads.DownloadsScreen
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import app.keepfin.ui.jobs.GroupActions
import app.keepfin.ui.jobs.JobActions
import app.keepfin.ui.library.LibraryScreen
import app.keepfin.ui.library.banded
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.calculateEndPadding
import app.keepfin.ui.library.LibraryTab
import androidx.compose.runtime.CompositionLocalProvider
import app.keepfin.ui.player.LocalPlayerMotion
import app.keepfin.ui.player.PlayerOverlay
import app.keepfin.ui.player.rememberPlayerMotion
import app.keepfin.ui.player.MiniPlayer
import app.keepfin.ui.player.PhotoViewer
import app.keepfin.ui.player.VideoPlayerScreen
import androidx.activity.result.IntentSenderRequest
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Snackbar
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.IconButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarVisuals
import app.keepfin.ui.library.LibrarySort
import app.keepfin.ui.library.LibraryTopBar
import app.keepfin.ui.settings.SupportScreen
import app.keepfin.ui.settings.SettingsScreen
import app.keepfin.ui.settings.SignInScreen
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.updates.UpdateDialog
import app.keepfin.ui.updates.UpdateSheet
import app.keepfin.ui.updates.WhatsNewSheet
import app.keepfin.ui.theme.KeepfinTheme
import app.keepfin.ui.theme.tabular
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun StudioApp(vm: StudioViewModel) {
    KeepfinTheme(mode = vm.prefs.theme, dynamicColor = vm.prefs.dynamicColor, pureBlack = vm.prefs.pureBlack) {
        val view = LocalView.current
        val dark = Studio.colors.dark
        if (!view.isInEditMode) SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    // The full player is always dark (a wash of the artwork), so its bars keep light icons.
                    isAppearanceLightStatusBars = !dark && !vm.fullPlayer
                    isAppearanceLightNavigationBars = !dark && !vm.fullPlayer
                }
            }
        }
        val setup = vm.setup
        val gate = if (setup != null) setup.ui.collectAsStateWithLifecycle().value else null
        // The first start and the setup of an earlier library replace the app until they are done or skipped.
        if (setup != null && gate != null && gate.gate != app.keepfin.setup.Gate.NONE) app.keepfin.ui.setup.SetupFlow(
            gate, setup,
            // the start pages choose the mode; having offered notifications there, the first download does not ask a second time
            onMode = vm::chooseMode,
            onNotificationsOffered = { vm.prefs.notificationPermissionAsked = true },
        )
        else StudioScaffold(vm)
        IncomingPairingHost(vm)
    }
}

/** Another device asking to connect shows over every screen; one that connected by a scanned code shows "<name> connected" with Undo (see [app.keepfin.ui.devices.IncomingPairingLayer]). */
@Composable
private fun IncomingPairingHost(vm: StudioViewModel) {
    val pairing by app.keepfin.net.NetRuntime.incomingPairing.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val io = kotlinx.coroutines.Dispatchers.IO
    app.keepfin.ui.devices.IncomingPairingLayer(
        pairing,
        decide = { id, match -> scope.launch(io) { runCatching { app.keepfin.net.NetRuntime.peek()?.decide(id, match) } } },
        say = { text, label, action -> vm.say(text, label, action) },
        undo = { deviceId -> scope.launch(io) { runCatching { app.keepfin.net.NetRuntime.peek()?.undoPair(deviceId) } } },
        finish = { scope.launch(io) { runCatching { app.keepfin.net.NetRuntime.peek()?.dismissPairing() } } },
    )
}

@Composable
private fun StudioScaffold(vm: StudioViewModel) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(vm) {
        // Only the newest notice shows: a new one replaces the current one instead of queueing behind it.
        var showing: kotlinx.coroutines.Job? = null
        val a11y = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        vm.messages.collect { message ->
            showing?.cancel()
            // ATOMIC: a notice replaced before it even started still runs its finally (its onGone)
            showing = scope.launch(start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
                val buttons = message.actionLabel != null || message.secondLabel != null
                // Keepfin keeps the clock: the system's would hand a notice with buttons to the accessibility "time to take action"
                // setting, which on some phones never ends, so every notice with Undo stayed until it was closed.
                val visuals = StudioSnackbarVisuals(
                    message.text, message.actionLabel, message.secondLabel, message.secondAction,
                    withDismissAction = true, duration = SnackbarDuration.Indefinite,
                )
                try {
                    val result = kotlinx.coroutines.withTimeoutOrNull(noticeMillis(a11y, buttons)) { snackbar.showSnackbar(visuals) }
                    if (result == SnackbarResult.ActionPerformed) visuals.pressed.set(true)
                } finally {
                    // The button sets `pressed` the moment it is tapped, so a newer notice that cancels this one right then
                    // neither loses the Undo nor runs onGone (Delete for good would erase what Undo just asked back).
                    if (visuals.pressed.get()) message.action?.invoke() else message.onGone?.invoke()
                }
            }
        }
    }
    FirstDownloadPermissions(vm)

    // Files from before a reinstall need the system's consent to move, rename, edit or delete.
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result -> vm.libraryActions.accessResult(result.resultCode == Activity.RESULT_OK) }
    LaunchedEffect(vm.access) { vm.access?.let { accessLauncher.launch(IntentSenderRequest.Builder(it.sender).build()) } }
    val settingsState = vm.engine.settings.collectAsStateWithLifecycle()
    val libraryOn = settingsState.value.library.enabled
    val playerState = vm.player.ui.collectAsStateWithLifecycle()
    val miniVisible by remember { derivedStateOf { libraryOn && playerState.value.current?.let { !(it.video && vm.video != null) } == true } }

    var pendingCopy by remember { mutableStateOf<Job?>(null) }
    val copyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val job = pendingCopy
        pendingCopy = null
        if (uri != null && job != null) vm.jobs.saveCopy(job, uri)
    }
    var confirmDelete by remember { mutableStateOf<Job?>(null) }
    val actions = remember(vm, libraryOn) {
        JobActions(
            // With the library on, a finished song or video plays in Keepfin; anything else (or a file the library lacks) opens in another app.
            open = { job -> if (!vm.playJob(job)) FileActions.open(context, vm.engine, job)?.let(vm::say) },
            openExternal = { job -> FileActions.open(context, vm.engine, job)?.let(vm::say) },
            inApp = libraryOn,
            share = { job -> FileActions.share(context, vm.engine, job)?.let(vm::say) },
            saveCopy = { job -> pendingCopy = job; copyLauncher.launch(FileActions.suggestedName(vm.engine, job)) },
            showSource = { job -> FileActions.showSource(context, job.url)?.let(vm::say) },
            pause = vm.jobs::pause, resume = vm.jobs::resume, cancel = vm.jobs::cancel, retry = vm.jobs::retry,
            retrySave = vm.jobs::retrySave,
            remove = vm.jobs::remove,
            delete = { job -> confirmDelete = job },
            playExisting = { job -> job.existing?.let(vm::playExisting) },
            downloadAnyway = vm.jobs::downloadAnyway,
            spaceName = { id -> vm.spaceName(id) },
        )
    }
    val groupActions = remember(vm) {
        GroupActions(
            pauseAll = vm.collections::pauseGroup, resumeAll = vm.collections::resumeGroup, cancelAll = vm.collections::cancelGroup, retryFailed = vm.collections::retryGroupFailed,
            remove = vm.collections::removeGroup,
            removeFailed = vm.jobs::removeGroupFailed,
            openSource = { group -> FileActions.showSource(context, group.url)?.let(vm::say) },
            copyLink = { group -> FileActions.copyLink(context, group.url)?.let(vm::say) },
            shareLink = { group -> FileActions.shareLink(context, group.title, group.url)?.let(vm::say) },
            spaceName = { id -> vm.spaceName(id) },
        )
    }
    val clipboard = rememberClipboardHint()
    val paste: () -> Unit = {
        val text = readClipboard(context)
        if (text.isNullOrBlank()) vm.say("The clipboard has no text to paste.")
        else if (Links.extract(text).isEmpty()) vm.say("The clipboard doesn't contain a link.")
        else vm.loadLink(text)
    }

    BackHandler(enabled = vm.subPage != null || vm.destination != Destination.DOWNLOADS || vm.fullPlayer || vm.video != null || vm.photos != null) { vm.back() }

    val motion = rememberPlayerMotion()
    CompositionLocalProvider(LocalPlayerMotion provides motion) {
    Box(Modifier.fillMaxSize()) {
        val reduced = Studio.reducedMotion
        val page = vm.subPage
        // The library sits back a little (and is dimmed by the overlay) while the full player is open.
        AnimatedContent(
            targetState = page, label = "page",
            modifier = Modifier.graphicsLayer { val scale = 1f - 0.05f * motion.p.value.coerceIn(0f, 1f); scaleX = scale; scaleY = scale },
            transitionSpec = { fadeIn(tween(if (reduced) 0 else 200)) togetherWith fadeOut(tween(if (reduced) 0 else 150)) },
        ) { target ->
            when {
                target == SubPage.SUPPORT -> SupportScreen(vm, onBack = vm::closeSubPage)
                target == SubPage.GUIDE -> app.keepfin.ui.settings.GuideScreen(vm, onBack = vm::closeSubPage)
                target == SubPage.SIGN_IN -> SignInScreen(vm, onClose = vm::closeSubPage)
                target == SubPage.ACTIVITY -> app.keepfin.ui.activity.ActivityScreen(vm, onBack = vm::closeSubPage)
                else -> MainShell(vm, actions, groupActions, clipboard, paste)
            }
        }
        val wide = LocalConfiguration.current.screenWidthDp >= 600
        // Players sit above everything but the snackbar. Playback is a Library feature: off means no player surfaces at all.
        if (libraryOn) {
            // One surface that grows out of the mini bar: open from the first sign of motion until it has fully settled shut.
            LaunchedEffect(vm.fullPlayer && miniVisible, reduced) { motion.settle(vm.fullPlayer && miniVisible, reduced) }
            val showPlayer by remember { derivedStateOf { (vm.fullPlayer && miniVisible) || motion.p.value > 0.001f } }
            if (showPlayer) PlayerOverlay(vm, motion)
            vm.video?.let { VideoPlayerScreen(vm, it) }
            vm.photos?.let { PhotoViewer(vm, it) }
        }
        val covered = vm.video != null || vm.photos != null || (vm.fullPlayer && miniVisible)
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding()
                .padding(bottom = if (covered) 16.dp else (if (page == null && !wide) 80.dp else 0.dp) + (if (miniVisible && page == null) 120.dp else 0.dp)),
        ) { data -> StudioSnackbar(data) }
    }
    }

    CopiedLinkOffer(vm)
    app.keepfin.ui.library.MusicVideoSheetHost(vm)
    UpdateSheet(vm)
    WhatsNewSheet(vm)
    // The once-per-version update announcement waits behind What's new and Meet your library.
    UpdateDialog(vm)
    CrashReportDialog()

    confirmDelete?.let { job ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this file?") },
            text = { Text("“${job.title}” is removed from ${Format.savedFolder(job)} and from Keepfin. This can't be undone.") },
            confirmButton = {
                Button(
                    onClick = { confirmDelete = null; vm.jobs.deleteFile(job.id, job.title) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                ) { Text("Delete file") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Keep") } },
        )
    }
}

/** After an unexpected stop: says so once and offers the details to copy (they stay on the phone unless the user sends them). */
@Composable
private fun CrashReportDialog() {
    val context = androidx.compose.ui.platform.LocalContext.current
    var report by remember { mutableStateOf(app.keepfin.CrashLog.report(context)) }
    val details = report ?: return
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    fun close() { app.keepfin.CrashLog.dismiss(context); report = null }
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text("Keepfin closed unexpectedly") },
        text = { Text("Sorry about that. Copy the details and send them to the developer so it can be fixed. They stay on this phone unless you send them.") },
        confirmButton = {
            Button(onClick = {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(details))
                android.widget.Toast.makeText(context, "Details copied", android.widget.Toast.LENGTH_SHORT).show()
                close()
            }, modifier = Modifier.testTag("crash-copy")) { Text("Copy details") }
        },
        dismissButton = { TextButton(onClick = ::close) { Text("Close") } },
    )
}

/** The bar items of each mode: full mode is Home, Search, Library, Downloads; downloader-only is Downloads and Settings. */
internal fun navItems(libraryOn: Boolean): List<Destination> =
    if (libraryOn) listOf(Destination.HOME, Destination.SEARCH, Destination.LIBRARY, Destination.DOWNLOADS)
    else listOf(Destination.DOWNLOADS, Destination.SETTINGS)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainShell(vm: StudioViewModel, actions: JobActions, groupActions: GroupActions, clipboard: ClipboardHint?, paste: () -> Unit) {
    val wide = LocalConfiguration.current.screenWidthDp >= 600
    val settings by vm.engine.settings.collectAsStateWithLifecycle()
    val libraryOn = settings.library.enabled
    // Switching to downloader-only (or a saved Home/Search from before) lands on Downloads: those pages need the library.
    val destination = if (!libraryOn && vm.destination.needsLibrary) Destination.DOWNLOADS else vm.destination
    LaunchedEffect(libraryOn, vm.destination) { if (!libraryOn && vm.destination.needsLibrary) vm.navigate(Destination.DOWNLOADS) }
    var librarySort by rememberSaveable { mutableStateOf(LibrarySort.NEWEST) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val items = navItems(libraryOn)
    val sheets = remember { app.keepfin.ui.library.LibrarySheetState() }
    val libraryUi = app.keepfin.ui.library.rememberLibraryUi(vm, sheets)
    Row(Modifier.fillMaxSize()) {
        if (wide) {
            NavigationRail(header = { Spacer(Modifier.size(8.dp)) }) {
                items.forEach { item ->
                    NavigationRailItem(
                        selected = destination == item, onClick = { vm.navigate(item) },
                        icon = { DestinationIcon(vm, item, destination == item) }, label = { Text(item.label) },
                        modifier = Modifier.testTag(UiTags.nav(item)),
                    )
                }
            }
        }
        Scaffold(
            modifier = Modifier.weight(1f).nestedScroll(scroll.nestedScrollConnection),
            topBar = {
                // Downloads, Home, the Library home and Settings draw their own 26sp titles; only the plain history keeps a top bar.
                if (destination == Destination.LIBRARY && !libraryOn) LibraryTopBar(vm, scroll, librarySort) { librarySort = it }
            },
            bottomBar = {
                // A selection in the Library swaps the bottom navigation for its own action bar.
                if (!(destination == Destination.LIBRARY && libraryOn && vm.librarySelecting)) Column {
                    // The mini player sits above the bottom navigation on every tab while the library is on.
                    if (libraryOn) MiniPlayer(vm)
                    if (!wide) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        items.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item, onClick = { vm.navigate(item) },
                                icon = { DestinationIcon(vm, item, destination == item) }, label = { Text(item.label, maxLines = 1) },
                                colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer, selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    selectedTextColor = Studio.colors.accentText,
                                ),
                                modifier = Modifier.testTag(UiTags.nav(item)),
                            )
                        }
                    }
                }
            },
            contentWindowInsets = if (wide) WindowInsets(0) else androidx.compose.material3.ScaffoldDefaults.contentWindowInsets,
        ) { padding ->
            val bottom = PaddingValues(bottom = 16.dp)
            // Content ends where the navigation bar begins, so nothing scrolls underneath it.
            // Banded library pages draw their colour under the status bar themselves, so the top inset is left for them to use.
            val ltr = androidx.compose.ui.platform.LocalLayoutDirection.current
            val edgeTop = libraryOn && destination == Destination.LIBRARY && vm.subPage == null && vm.libraryRoute.banded()
            val inset = if (edgeTop) PaddingValues(start = padding.calculateStartPadding(ltr), end = padding.calculateEndPadding(ltr), bottom = padding.calculateBottomPadding()) else padding
            // Home and Library keep reading their shelves in the background while another tab shows, so switching draws them at once.
            if (libraryOn) {
                if (destination != Destination.HOME) app.keepfin.ui.home.rememberHomeShelves(vm, libraryUi)
                if (destination != Destination.LIBRARY) app.keepfin.ui.library.rememberLibraryShelves(libraryUi)
            }
            Box(Modifier.padding(inset).consumeWindowInsets(inset).imePadding().fillMaxSize()) {
                when (destination) {
                    Destination.HOME -> app.keepfin.ui.home.HomeTab(vm, libraryUi, bottom)
                    Destination.SEARCH -> app.keepfin.ui.search.SearchTab(vm, libraryUi, bottom)
                    Destination.DOWNLOADS -> DownloadsScreen(vm, actions, groupActions, clipboard, paste, bottom)
                    Destination.LIBRARY -> if (libraryOn) LibraryTab(vm, libraryUi, bottom) else LibraryScreen(vm, actions, groupActions, librarySort, bottom)
                    Destination.SETTINGS -> SettingsScreen(vm, bottom)
                }
            }
        }
    }
    // One host for the action sheet and every other library sheet, whichever page raised it.
    if (libraryOn) app.keepfin.ui.library.LibrarySheets(libraryUi, sheets.sheet) { sheets.sheet = null }
}

/** The trailing actions every top-level tab shows, in this order: activity (the bell, with its badge), then settings (the gear). */
@Composable
fun TabActions(vm: StudioViewModel) {
    app.keepfin.ui.activity.ActivityBell(vm)
    SettingsButton(vm)
}

/** The Settings icon of the top bar (full mode; downloader-only mode has Settings in the bar). Keeps the old `nav-settings` tag. */
@Composable
fun SettingsButton(vm: StudioViewModel, modifier: Modifier = Modifier) {
    val libraryOn = vm.engine.settings.collectAsStateWithLifecycle().value.library.enabled
    if (!libraryOn) return
    IconButton(onClick = { vm.navigate(Destination.SETTINGS) }, modifier = modifier.size(48.dp).testTag(UiTags.nav(Destination.SETTINGS))) {
        Icon(Icons.Outlined.Settings, contentDescription = "Settings")
    }
}

/** Navigation icon; the Downloads badge reads only the derived active count, not every progress tick. */
@Composable
private fun DestinationIcon(vm: StudioViewModel, item: Destination, selected: Boolean) {
    val icon = when (item) {
        Destination.HOME -> if (selected) Icons.Filled.Home else Icons.Outlined.Home
        Destination.SEARCH -> if (selected) Icons.Filled.Search else Icons.Outlined.Search
        Destination.DOWNLOADS -> if (selected) Icons.Filled.Download else Icons.Outlined.Download
        Destination.LIBRARY -> if (selected) Icons.Filled.VideoLibrary else Icons.Outlined.VideoLibrary
        Destination.SETTINGS -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
    }
    if (item != Destination.DOWNLOADS) { Icon(icon, contentDescription = null); return }
    val jobs = vm.engine.jobs.collectAsStateWithLifecycle()
    val active by remember { derivedStateOf { jobs.value.count { it.status.active } } }
    BadgedBox(badge = { if (active > 0) Badge(Modifier.testTag(UiTags.DOWNLOADS_BADGE)) { Text("$active") } }) { Icon(icon, contentDescription = null) }
}

@Composable
internal fun AggregateSpeed(vm: StudioViewModel) {
    val jobs = vm.engine.jobs.collectAsStateWithLifecycle()
    val speed by remember { derivedStateOf { jobs.value.filter { it.status == JobStatus.DOWNLOADING }.sumOf { it.speed ?: 0L } } }
    if (speed > 0) {
        Text(
            "↓ ${Format.speed(speed)}", style = MaterialTheme.typography.labelLarge.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp).testTag(UiTags.AGGREGATE_SPEED),
        )
    }
}

/**
 * Engine state on the main screen only when it matters: the engine is updating while a download waits, or it is broken
 * (with a "Fix" action that runs the update). Healthy engines show nothing; details live in Settings → Updates.
 */
@Composable
internal fun EngineNotice(vm: StudioViewModel, modifier: Modifier = Modifier) {
    val tools by vm.engine.tools.collectAsStateWithLifecycle()
    val jobs by vm.engine.jobs.collectAsStateWithLifecycle()
    val ytdlp = tools.firstOrNull { it.name == "yt-dlp" } ?: return
    val waiting = remember(jobs) { jobs.any { it.status == JobStatus.QUEUED || it.status == JobStatus.PREPARING } }
    val updating = ytdlp.state == "updating"
    val broken = ytdlp.state == "error"
    if (!(updating && waiting) && !broken) return
    Row(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).testTag(UiTags.ENGINE_NOTICE),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (updating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        else Icon(Icons.Outlined.WarningAmber, contentDescription = null, tint = Studio.colors.warning, modifier = Modifier.size(18.dp))
        Text(
            if (updating) "Updating the download engine. Waiting downloads start right after." else "The download engine needs attention. Downloads may fail until it is fixed.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
        )
        if (broken) androidx.compose.material3.TextButton(onClick = { vm.engine.updateTools(true) }, modifier = Modifier.testTag(UiTags.ENGINE_FIX)) { Text("Fix") }
    }
}

/**
 * Asks for notification (Android 13+) and legacy storage (Android 8–9) access when the first download
 * starts: the moment those permissions become meaningful. Asked once; Settings can ask again.
 */
@Composable
private fun FirstDownloadPermissions(vm: StudioViewModel) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(vm) {
        vm.engine.jobs.map { jobs -> jobs.any { it.status.active } }.distinctUntilChanged().collect { active ->
            if (!active || StudioEngine.isFake) return@collect
            val wanted = buildList {
                val notifications = runCatching { (context.applicationContext as KeepfinApplication).notifications.enabled("enabled") }.getOrDefault(false)
                if (Build.VERSION.SDK_INT >= 33 && notifications && !vm.prefs.notificationPermissionAsked &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    @Suppress("DEPRECATION") add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            if (wanted.isNotEmpty()) {
                vm.prefs.notificationPermissionAsked = true
                launcher.launch(wanted.toTypedArray())
            }
        }
    }
}

/** Offers the clipboard link when the window regains focus, without reading private text on Android 12+. */
@Composable
private fun rememberClipboardHint(): ClipboardHint? {
    val context = LocalContext.current
    val focused = LocalWindowInfo.current.isWindowFocused
    var hint by remember { mutableStateOf<ClipboardHint?>(null) }
    LaunchedEffect(focused) {
        if (focused) hint = runCatching { clipboardHint(context) }.getOrNull()
    }
    return hint
}

/**
 * "Download the copied link?" once per new clipboard link, when the app comes to the front. Downloads already offers the same link as a
 * chip, so it stays quiet there. On Android 12+ the text is not read to decide (that would raise Android's paste notice); the clip's
 * timestamp identifies it and the text is read only if the user taps Download.
 */
@Composable
private fun CopiedLinkOffer(vm: StudioViewModel) {
    val context = LocalContext.current
    val focused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focused, vm.destination) {
        if (!focused || !vm.prefs.offerCopiedLinks || vm.destination == Destination.DOWNLOADS || vm.capture !is Capture.Empty) return@LaunchedEffect
        val key = runCatching { copiedLinkKey(context) }.getOrNull() ?: return@LaunchedEffect
        vm.offerCopiedLink(key) { readClipboard(context) }
    }
}

/** Identifies a clipboard link without reading private text on Android 12+; null when the clipboard holds no link. */
private fun copiedLinkKey(context: Context): String? {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
    if (!manager.hasPrimaryClip()) return null
    val description = manager.primaryClipDescription ?: return null
    if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) && !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)) return null
    if (Build.VERSION.SDK_INT >= 31) {
        if (description.classificationStatus != ClipDescription.CLASSIFICATION_COMPLETE) return null
        return if (description.getConfidenceScore(TextClassifier.TYPE_URL) >= 0.5f) "clip@${description.timestamp}" else null
    }
    val text = manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return null
    return Links.extract(text).firstOrNull()
}

private fun clipboardHint(context: Context): ClipboardHint? {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
    if (!manager.hasPrimaryClip()) return null
    val description = manager.primaryClipDescription ?: return null
    if (!description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) && !description.hasMimeType(ClipDescription.MIMETYPE_TEXT_URILIST)) return null
    if (Build.VERSION.SDK_INT >= 31) {
        // Reading the clip would show Android's paste notice; the classifier says whether it is a link.
        if (description.classificationStatus != ClipDescription.CLASSIFICATION_COMPLETE) return null
        return if (description.getConfidenceScore(TextClassifier.TYPE_URL) >= 0.5f) ClipboardHint(null) else null
    }
    val text = manager.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return null
    val link = Links.extract(text).firstOrNull() ?: return null
    return ClipboardHint(Format.host(link))
}

internal fun readClipboard(context: Context): String? {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
    return manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}

/** How long a notice stays: 10 s with buttons, 4 s without; a longer accessibility "time to take action" is honoured up to a minute. */
internal fun noticeMillis(a11y: android.view.accessibility.AccessibilityManager?, buttons: Boolean): Long {
    val base = if (buttons) 10_000 else 4_000
    if (a11y == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return base.toLong()
    val content = android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT or android.view.accessibility.AccessibilityManager.FLAG_CONTENT_ICONS or
        (if (buttons) android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS else 0)
    return a11y.getRecommendedTimeoutMillis(base, content).toLong().coerceIn(base.toLong(), 60_000L)
}

/** Snackbar visuals with an optional second button (Saved to Music: Play, Show in Library). */
private class StudioSnackbarVisuals(
    override val message: String,
    override val actionLabel: String?,
    val secondLabel: String?,
    val secondAction: (() -> Unit)?,
    override val withDismissAction: Boolean,
    override val duration: SnackbarDuration,
) : SnackbarVisuals {
    /** Set by the main button as it is tapped: the notice's action runs exactly once, even if a newer notice cancels this one. */
    val pressed = java.util.concurrent.atomic.AtomicBoolean(false)
}

/** Notices: calm tonal surface, always closable with the close button or a sideways swipe. */
@Composable
private fun StudioSnackbar(data: SnackbarData) {
    val visuals = data.visuals
    val density = LocalDensity.current
    var drag by remember(data) { mutableStateOf(0f) }
    val swipe = Modifier
        .graphicsLayer { translationX = drag; alpha = 1f - (kotlin.math.abs(drag) / size.width.coerceAtLeast(1f)).coerceIn(0f, 0.8f) }
        .pointerInput(data) {
            detectHorizontalDragGestures(
                onDragEnd = { if (kotlin.math.abs(drag) > with(density) { 96.dp.toPx() }) data.dismiss() else drag = 0f },
                onDragCancel = { drag = 0f },
            ) { change, amount -> change.consume(); drag += amount }
        }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    if (visuals is StudioSnackbarVisuals && visuals.secondLabel != null) {
        Snackbar(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).then(swipe), shape = shape,
            containerColor = colors.surfaceContainerHighest, contentColor = colors.onSurface, actionContentColor = colors.primary,
            dismissActionContentColor = colors.onSurfaceVariant,
            dismissAction = {
                IconButton(onClick = { data.dismiss() }, modifier = Modifier.testTag("snackbar-close")) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", modifier = Modifier.size(20.dp))
                }
            },
            action = {
                Row {
                    visuals.actionLabel?.let { label ->
                        TextButton(onClick = { (visuals as? StudioSnackbarVisuals)?.pressed?.set(true); data.performAction() }, modifier = Modifier.testTag("snackbar-action")) { Text(label) }
                    }
                    TextButton(onClick = { visuals.secondAction?.invoke(); data.dismiss() }, modifier = Modifier.testTag("snackbar-second")) { Text(visuals.secondLabel) }
                }
            },
        ) { Text(visuals.message, maxLines = 2) }
    } else Snackbar(
        // the material button marks the press too, before the notice resumes
        remember(data) {
            object : SnackbarData by data {
                override fun performAction() { (data.visuals as? StudioSnackbarVisuals)?.pressed?.set(true); data.performAction() }
            }
        },
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).then(swipe), shape = shape,
        containerColor = colors.surfaceContainerHighest, contentColor = colors.onSurface, actionColor = colors.primary,
        dismissActionContentColor = colors.onSurfaceVariant,
    )
}
