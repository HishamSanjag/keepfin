@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.keepfin.ui.setup

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Login
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.keepfin.core.start.Start
import app.keepfin.core.start.StartLine
import app.keepfin.core.start.StartPage
import app.keepfin.core.start.StartPermission
import app.keepfin.core.start.StartWords
import app.keepfin.setup.EarlierFiles
import app.keepfin.setup.FolderKind
import app.keepfin.setup.PickMode
import app.keepfin.setup.SetupActions
import app.keepfin.setup.SetupTexts
import app.keepfin.setup.SetupUi
import app.keepfin.ui.DownloaderPicture
import app.keepfin.ui.Format
import app.keepfin.ui.LibraryPicture
import app.keepfin.ui.theme.Studio
import kotlinx.coroutines.launch

/** Test tags of the start pages. */
object StartTags {
    const val ROOT = "start-root"
    const val PROGRESS = "start-progress"
    const val SKIP = "start-skip"
    const val PRIMARY = "start-primary"
    const val MODE_COMPLETE = "start-mode-complete"
    const val MODE_DOWNLOADS = "start-mode-downloads"
    const val FOLDER_CARD = "start-folder-card"
    const val HAVE_FOLDER = "start-have-folder"
    const val ADVANCED = "start-advanced"
    const val CHANGE = "start-change"
    const val GRANT_MEDIA = "start-grant-media"
    const val GRANT_NOTIFICATIONS = "start-grant-notifications"
    const val GRANTED_MEDIA = "start-granted-media"
    const val GRANTED_NOTIFICATIONS = "start-granted-notifications"
    const val SITES = "start-sites"
    const val SIGN_IN_TIP = "start-sign-in-tip"
    fun page(page: StartPage) = "start-page-${page.name.lowercase()}"
}

private val featureIcons = listOf(Icons.Outlined.Download, Icons.Outlined.PlayArrow, Icons.Outlined.Sync, Icons.Outlined.VideoLibrary)

/**
 * The first start of the phone (dev-docs/plans/START-PAGES.md): five swipeable pages with a segmented progress bar on top and one button
 * at the bottom. Every word comes from the shared core ([Start.words]). The pages are Welcome, How it works, the mode, the Keepfin
 * folder (required) and the permissions. Choosing the folder makes the profile ([SetupUi.startReady]); the app opens when Start is tapped.
 */
@Composable
internal fun StartPages(
    ui: SetupUi,
    actions: SetupActions,
    page: Int,
    onPage: (Int) -> Unit,
    complete: Boolean,
    onComplete: (Boolean) -> Unit,
    onAdvanced: () -> Unit,
    onMode: (complete: Boolean) -> Unit,
    onNotificationsOffered: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pages = remember { Start.pages(phone = true) }
    val words = remember { Start.words(phone = true) }
    val pager = rememberPagerState(initialPage = page.coerceIn(0, pages.lastIndex)) { pages.size }
    val scope = rememberCoroutineScope()
    val reduced = Studio.reducedMotion
    val current = pages[pager.currentPage.coerceIn(0, pages.lastIndex)]
    LaunchedEffect(pager) { snapshotFlow { pager.settledPage }.collect(onPage) }

    fun goTo(index: Int) {
        val target = index.coerceIn(0, pages.lastIndex)
        scope.launch { if (reduced) pager.scrollToPage(target) else pager.animateScrollToPage(target) }
    }

    // Choosing the folder (here or in the advanced steps) makes it ready: the start goes on to the permissions
    val folderIndex = pages.indexOf(StartPage.FOLDER)
    LaunchedEffect(ui.startReady) {
        if (ui.startReady && pager.currentPage == folderIndex) goTo(folderIndex + 1)
    }

    // Swiping forward never passes the folder page before a folder is chosen; swiping back is always possible
    val forwardBlocked by rememberUpdatedState(!Start.canGoOn(current, ui.startReady))
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val blocker = remember(rtl) {
        object : NestedScrollConnection {
            private fun forward(x: Float) = forwardBlocked && (if (rtl) x > 0f else x < 0f)
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = if (forward(available.x)) Offset(available.x, 0f) else Offset.Zero
            override suspend fun onPreFling(available: Velocity): Velocity = if (forward(available.x)) Velocity(available.x, 0f) else Velocity.Zero
        }
    }

    BackHandler(enabled = pager.currentPage > 0 && !ui.busy) { goTo(pager.currentPage - 1) }

    Column(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().testTag(StartTags.ROOT)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            ProgressBar(pager.currentPage, pages.size, Modifier.weight(1f))
            // the space stays when Skip intro is gone, so the bar does not change length between pages
            Box(Modifier.width(112.dp).heightIn(min = 48.dp), contentAlignment = Alignment.CenterEnd) {
                if (Start.introduces(current)) TextButton(
                    onClick = { goTo(pages.indexOf(Start.skipTo)) }, modifier = Modifier.heightIn(min = 48.dp).testTag(StartTags.SKIP),
                ) { Text(words.skipIntro) }
            }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth().nestedScroll(blocker), beyondViewportPageCount = 1) { index ->
            val kind = pages[index]
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp).testTag(StartTags.page(kind)),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (kind) {
                    StartPage.WELCOME -> WelcomePage(words)
                    StartPage.HOW -> HowPage(words)
                    StartPage.MODE -> ModePage(words, complete, onComplete)
                    StartPage.FOLDER -> FolderPage(words, ui, actions, onAdvanced)
                    StartPage.PERMISSIONS -> PermissionsPage(words, complete, onNotificationsOffered)
                }
            }
        }
        PrimaryButton(words, current, ui, actions, complete, onNext = { goTo(pager.currentPage + 1) }, onStart = {
            onMode(complete)
            actions.finishStart(complete)
        })
    }
}

/** Five segments, filled up to the current page; read out as "Page n of 5". */
@Composable
private fun ProgressBar(current: Int, count: Int, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.heightIn(min = 48.dp).testTag(StartTags.PROGRESS).clearAndSetSemantics { contentDescription = "Page ${current + 1} of $count" },
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            Surface(Modifier.weight(1f).height(4.dp), shape = RoundedCornerShape(2.dp), color = if (i <= current) colors.primary else colors.surfaceContainerHighest) {}
        }
    }
}

@Composable
private fun PageHead(eyebrow: String, title: String, body: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(eyebrow.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp, color = MaterialTheme.colorScheme.primary)
        Text(title, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        if (body != null) Text(body, fontSize = 16.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun WelcomePage(words: StartWords) {
    KeepfinLogo(96.dp)
    PageHead(words.welcomeEyebrow, words.welcomeTitle, words.welcomeBody)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        words.features.forEachIndexed { i, line ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                Icon(featureIcons.getOrElse(i) { Icons.Outlined.Folder }, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(24.dp))
                LineText(line)
            }
        }
    }
    // the sites it works with, by name, and the music services it matches on YouTube Music
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(words.sitesTitle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(
            Modifier.testTag(StartTags.SITES), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            words.sites.forEach { site ->
                Surface(shape = CircleShape, color = Color.Transparent, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                    Text(site, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                }
            }
            Text(words.sitesMore, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
        }
        Text(words.musicLinks, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LineText(line: StartLine, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(line.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp)
        Text(line.body, fontSize = 14.sp, lineHeight = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HowPage(words: StartWords) {
    PageHead(words.howEyebrow, words.howTitle)
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        words.steps.forEachIndexed { i, line ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                Surface(Modifier.size(32.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                    Box(contentAlignment = Alignment.Center) { Text("${i + 1}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                }
                LineText(line, Modifier.weight(1f).padding(top = 4.dp))
            }
        }
    }
    // signing in is not a start step; this says where it lives for the videos that need it
    Surface(Modifier.fillMaxWidth().testTag(StartTags.SIGN_IN_TIP), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.AutoMirrored.Outlined.Login, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            LineText(words.signIn, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ModePage(words: StartWords, complete: Boolean, onComplete: (Boolean) -> Unit) {
    PageHead(words.modeEyebrow, words.modeTitle)
    ModeCard(words.complete, complete, StartTags.MODE_COMPLETE, badge = words.recommended, picture = { LibraryPicture(it) }) { onComplete(true) }
    ModeCard(words.downloads, !complete, StartTags.MODE_DOWNLOADS, badge = null, picture = { DownloaderPicture(it) }) { onComplete(false) }
    Text(words.modeLater, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ModeCard(line: StartLine, selected: Boolean, tag: String, badge: String?, picture: @Composable (Modifier) -> Unit, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.medium, color = if (selected) colors.primaryContainer else colors.surfaceContainerHigh,
        border = if (selected) BorderStroke(2.dp, colors.primary) else BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).semantics { this.selected = selected; role = Role.RadioButton }.testTag(tag),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            picture(Modifier.fillMaxWidth().height(96.dp))
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioButton(selected, onClick = null)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(line.title, style = MaterialTheme.typography.titleSmall, color = if (selected) colors.onPrimaryContainer else colors.onSurface, modifier = Modifier.weight(1f, fill = false))
                        if (badge != null) Badge(badge)
                    }
                    Text(line.body, style = MaterialTheme.typography.bodySmall, color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ the folder

/** The place the recommended button makes, before anything is chosen. */
private const val RECOMMENDED_PLACE = "Documents › Keepfin"

@Composable
private fun FolderPage(words: StartWords, ui: SetupUi, actions: SetupActions, onAdvanced: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val folder = ui.folder
    PageHead(words.folderEyebrow, words.folderTitle, words.folderHint)
    // "I already have a Keepfin folder" that turned out to hold none is a hint, not a chosen folder
    val noneThere = folder != null && folder.mode == PickMode.EXISTING && folder.kind == FolderKind.NEW && folder.inner == null
    Card(Modifier.testTag(StartTags.FOLDER_CARD)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Folder, contentDescription = null, tint = colors.primary, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f)) {
                if (folder == null || noneThere) {
                    Text(RECOMMENDED_PLACE, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp, modifier = Modifier.testTag(SetupTags.BREADCRUMB))
                    Text(words.recommended, fontSize = 13.sp, color = colors.onSurfaceVariant)
                } else {
                    Text(folder.label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 21.sp, modifier = Modifier.testTag(SetupTags.BREADCRUMB))
                    folder.freeBytes?.let { Text("${Format.bytes(it)} free", fontSize = 13.sp, color = colors.onSurfaceVariant) }
                }
            }
            if (!ui.startReady) TextButton(onClick = actions::chooseAnother, enabled = !ui.busy, modifier = Modifier.heightIn(min = 48.dp).testTag(StartTags.CHANGE)) { Text("Change") }
        }
    }
    if (folder != null && !noneThere) when (folder.kind) {
        FolderKind.NEW -> if (folder.inner != null) {
            Alert("${folder.label} holds a Keepfin folder called Keepfin. Choose that one.", SetupTexts.INNER_FOUND, Tone.Info) {
                Button(onClick = actions::chooseInner, enabled = !ui.busy, modifier = Modifier.heightIn(min = 48.dp).testTag(SetupTags.INNER)) { Text("Choose it") }
            }
        } else Alert(
            buildString {
                append("Keepfin adds Music, Videos and Downloads and its own hidden data inside.")
                if (folder.existingEntries > 0) append(" This folder already holds ${folder.existingEntries} ${if (folder.existingEntries == 1) "item" else "items"}; they stay untouched.")
            },
            "This folder will be your Keepfin folder",
        )
        FolderKind.EXISTING -> Alert("${folder.label} already holds a Keepfin library. It is opened, and your library, playlists and history come back.", "Found a Keepfin folder")
        FolderKind.NEWER -> Alert("${folder.label} was made by a newer version of Keepfin. Update Keepfin to change it. You can open it to read it.", "Made by a newer Keepfin", Tone.Warning) {
            OutlinedButton(onClick = actions::openFolder, enabled = !ui.busy, modifier = Modifier.heightIn(min = 48.dp).testTag(SetupTags.OPEN_EXISTING)) { Text("Open read-only") }
        }
    }
    if (noneThere) Alert("${folder!!.label} does not hold a Keepfin folder. Choose another one, or use the recommended place.", "No Keepfin folder there", Tone.Warning)
    if (ui.pickCancelled && folder == null) Alert(SetupTexts.PICK_CANCELLED, "Nothing was chosen", Tone.Warning)
    ui.error?.let { Alert(it, tone = Tone.Error) }
    if (!ui.startReady) {
        TextButton(onClick = actions::chooseExisting, enabled = !ui.busy, modifier = Modifier.heightIn(min = 48.dp).testTag(StartTags.HAVE_FOLDER)) { Text(words.haveFolder) }
        Column(Modifier.clickable(enabled = !ui.busy, role = Role.Button, onClick = onAdvanced).testTag(StartTags.ADVANCED).padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${words.advanced} ›", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = colors.primary, modifier = Modifier.heightIn(min = 24.dp))
            Text(words.advancedHint, fontSize = 13.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
        }
    }
    Text(words.folderLater, fontSize = 13.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
}

// ------------------------------------------------------------------------------------------------ the permissions

private fun mediaGranted(context: Context) = EarlierFiles.missingPermissions(context).isEmpty()

private fun notificationsGranted(context: Context) =
    Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@Composable
private fun PermissionsPage(words: StartWords, complete: Boolean, onNotificationsOffered: () -> Unit) {
    val context = LocalContext.current
    // read again when the person comes back from Android's own screens (an app can be allowed there too)
    var check by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) check++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val media = remember(check) { mediaGranted(context) }
    val notifications = remember(check) { notificationsGranted(context) }
    // Android shows a permission prompt twice at most; after two refusals the Grant button opens the app's own settings instead
    var mediaRefusals by rememberSaveable { mutableIntStateOf(0) }
    var notificationRefusals by rememberSaveable { mutableIntStateOf(0) }
    val mediaLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { !it }) mediaRefusals++
        check++
    }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) notificationRefusals++
        onNotificationsOffered()
        check++
    }
    fun openSettings() = runCatching {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    PageHead(words.permissionsEyebrow, words.permissionsTitle)
    Start.permissions(complete).forEach { permission ->
        when (permission) {
            StartPermission.MEDIA -> PermissionRow(
                words, Icons.Outlined.LibraryMusic, words.media, media, StartTags.GRANT_MEDIA, StartTags.GRANTED_MEDIA,
            ) { if (mediaRefusals >= 2) openSettings() else mediaLauncher.launch(EarlierFiles.missingPermissions(context).toTypedArray()) }
            StartPermission.NOTIFICATIONS -> PermissionRow(
                words, Icons.Outlined.Notifications, words.notifications, notifications, StartTags.GRANT_NOTIFICATIONS, StartTags.GRANTED_NOTIFICATIONS,
            ) { if (notificationRefusals >= 2) openSettings() else notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
    Text(words.permissionsLater, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PermissionRow(words: StartWords, icon: ImageVector, line: StartLine, granted: Boolean, grantTag: String, grantedTag: String, onGrant: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Card {
        // one row, as on the design: what it is on the left, Grant (or Granted) at the end
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = colors.primary, modifier = Modifier.size(24.dp))
            LineText(line, Modifier.weight(1f))
            if (granted) Row(Modifier.testTag(grantedTag).heightIn(min = 48.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
                Text(words.granted, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = colors.primary)
            } else FilledTonalButton(onClick = onGrant, modifier = Modifier.heightIn(min = 48.dp).testTag(grantTag)) { Text(words.grant) }
        }
    }
}

// ------------------------------------------------------------------------------------------------ the one button

@Composable
private fun PrimaryButton(
    words: StartWords, page: StartPage, ui: SetupUi, actions: SetupActions, complete: Boolean,
    onNext: () -> Unit, onStart: () -> Unit,
) {
    val folder = ui.folder
    val chosen = folder != null && !(folder.mode == PickMode.EXISTING && folder.kind == FolderKind.NEW) && folder.kind != FolderKind.NEWER && folder.inner == null
    val label: String
    val onClick: () -> Unit
    when (page) {
        StartPage.WELCOME, StartPage.HOW, StartPage.MODE -> { label = words.next; onClick = onNext }
        StartPage.FOLDER -> when {
            ui.startReady -> { label = words.next; onClick = onNext }
            chosen && folder!!.kind == FolderKind.NEW -> { label = words.next; onClick = actions::createFolder }
            chosen -> { label = words.next; onClick = actions::openFolder }
            else -> { label = "Use $RECOMMENDED_PLACE"; onClick = actions::chooseRecommended }
        }
        StartPage.PERMISSIONS -> { label = words.start; onClick = onStart }
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Button(onClick = onClick, enabled = !ui.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).testTag(StartTags.PRIMARY)) {
            if (ui.busy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(10.dp)) }
            Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
