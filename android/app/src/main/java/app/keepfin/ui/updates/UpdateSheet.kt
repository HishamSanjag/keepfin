package app.keepfin.ui.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import app.keepfin.core.versions.Platform
import app.keepfin.core.versions.Releases
import app.keepfin.ui.Format
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import app.keepfin.ui.StudioSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.settings.ReleaseNotesBody
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.theme.tabular
import kotlinx.coroutines.launch

/**
 * The update banner on Downloads: "Keepfin 0.5.1 is available", the first line of the release notes, Update (opens the
 * update sheet) and a close button that hides it for this version. It exists only while a newer release is known.
 */
@Composable
fun UpdateBanner(vm: StudioViewModel, modifier: Modifier = Modifier) {
    val version = vm.updatesFlow.updateBannerVersion ?: return
    val state = vm.updateState
    val colors = MaterialTheme.colorScheme
    val highlight = remember(state.notes) { updateHighlights(state.notes, 1).firstOrNull() }
    val title = when (state.phase) {
        "downloading" -> "Downloading Keepfin $version"
        "ready" -> "Keepfin $version is ready to install"
        else -> "Keepfin $version is available"
    }
    val action = if (state.phase == "ready") "Install" else "Update"
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.primaryContainer)
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 6.dp).testTag(UiTags.UPDATE_BANNER),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Outlined.FileDownload, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onPrimaryContainer, maxLines = 2, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Text(highlight ?: "See what is new before you update", fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Button(
            onClick = vm.updatesFlow::showUpdateSheet, contentPadding = PaddingValues(horizontal = 14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction),
            modifier = Modifier.height(36.dp).testTag(UiTags.UPDATE_BANNER_ACTION),
        ) { Text(action, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
        IconButton(onClick = vm.updatesFlow::hideUpdateBanner, modifier = Modifier.size(40.dp).testTag(UiTags.UPDATE_BANNER_HIDE)) {
            Icon(Icons.Outlined.Close, contentDescription = "Hide for this version", tint = colors.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

/** Up to [max] bullets worth announcing: the Android section when the notes are split by platform, else every bullet (the shared core decides, like the desktop dialog). */
fun updateHighlights(notes: String, max: Int = 3): List<String> = Releases.highlights(notes, Platform.ANDROID, max)

/**
 * The one-time announcement of a new release: "Keepfin X is available", two or three highlights, its size, and a promise that
 * downloads and library stay. Update now starts the in-app update; Later asks again next launch; Skip this version never asks
 * again for that version. It waits behind What's new.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateDialog(vm: StudioViewModel) {
    val version = vm.updatesFlow.updateDialogVersion ?: return
    val state = vm.updateState
    val bullets = remember(state.notes) { updateHighlights(state.notes, 3) }
    val colors = MaterialTheme.colorScheme
    val detail = if (state.size > 0) "${Format.bytes(state.size)} · your downloads and library stay." else "Your downloads and library stay."
    BasicAlertDialog(onDismissRequest = vm.updatesFlow::updateLater, modifier = Modifier.testTag(UiTags.UPDATE_DIALOG)) {
        Surface(shape = RoundedCornerShape(28.dp), color = colors.surface, tonalElevation = 6.dp, modifier = Modifier.width(328.dp)) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(colors.primaryContainer).align(Alignment.CenterHorizontally), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null, tint = colors.primary, modifier = Modifier.size(22.dp))
                }
                Text(
                    "Keepfin $version is available", fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().semantics { heading() },
                )
                if (bullets.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    bullets.forEach { bullet ->
                        Row {
                            Text("•", fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.width(16.dp))
                            Text(bullet, fontSize = 14.sp, lineHeight = 21.sp, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
                        }
                    }
                }
                Text(detail, fontSize = 13.sp, color = colors.onSurfaceVariant)
                Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = vm.updatesFlow::updateNow, colors = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(UiTags.UPDATE_NOW),
                    ) { Text("Update now", fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = vm.updatesFlow::skipUpdate, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.UPDATE_SKIP)) {
                            Text("Skip this version", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant)
                        }
                        TextButton(onClick = vm.updatesFlow::updateLater, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.UPDATE_LATER)) {
                            Text("Later", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpdateSheet(vm: StudioViewModel) {
    if (!vm.updateSheet) return
    val state = vm.updateState
    val current = vm.updates.currentVersion
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close: () -> Unit = { scope.launch { sheetState.hide() }.invokeOnCompletion { vm.updatesFlow.hideUpdateSheet() } }
    val available = state.hasUpdate(current)
    StudioSheet(onDismissRequest = vm.updatesFlow::hideUpdateSheet, sheetState = sheetState, modifier = Modifier.testTag(UiTags.UPDATE_SHEET)) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val title = when {
                state.phase == "ready" && available -> "Version ${state.version} is ready to install"
                state.phase == "downloading" -> "Downloading version ${state.version}"
                available -> "Version ${state.version} is available"
                state.phase == "checking" -> "Checking for updates…"
                state.phase == "error" -> "Could not check for updates"
                else -> "You're on v$current"
            }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
            Text(
                if (available) "You're on v$current. Updates install in place; settings and files stay."
                else if (state.phase == "current") "This is the latest release."
                else "Updates are fetched from the public Keepfin releases.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.message.isNotEmpty()) {
                Text(state.message, style = MaterialTheme.typography.bodySmall, color = if (state.phase == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (available && state.notes.isNotBlank()) {
                Text("Release notes", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                Column(Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.34f).dp).verticalScroll(rememberScrollState())) {
                    ReleaseNotesBody(state.notes)
                }
            }
            if (state.phase == "downloading") {
                LinearProgressIndicator(progress = { state.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), drawStopIndicator = {})
                Text("${state.percent}%", style = MaterialTheme.typography.labelMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            val primary = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction)
            when {
                state.phase == "downloading" -> OutlinedButton(onClick = vm.updates::cancel, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Cancel download") }
                state.phase == "ready" && available -> Button(
                    onClick = { installUpdate(context, vm); close() }, colors = primary,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(UiTags.UPDATE_INSTALL),
                ) { Text("Install update") }
                available || (state.phase == "error" && state.version.isNotEmpty() && isNewer(state.version, current)) -> Button(
                    onClick = vm.updates::download, colors = primary,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(UiTags.UPDATE_DOWNLOAD),
                ) { Text("Download update") }
                else -> FilledTonalButton(
                    onClick = vm.updatesFlow::checkForUpdates, enabled = state.phase != "checking",
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(UiTags.UPDATE_CHECK),
                ) { Text("Check now") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { vm.updatesFlow.hideUpdateSheet(); vm.updatesFlow.showWhatsNew() }, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.UPDATE_WHATS_NEW)) {
                    Text("What's new in v$current")
                }
            }
        }
    }
}

/** Shown once after the app is updated; reopenable from the update sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(vm: StudioViewModel) {
    val version = vm.whatsNew ?: return
    val context = LocalContext.current
    val notes = remember(version) { runCatching { vm.updates.installedNotes(context) }.getOrDefault("Release notes are unavailable.") }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    StudioSheet(onDismissRequest = vm.updatesFlow::dismissWhatsNew, sheetState = sheetState, modifier = Modifier.testTag(UiTags.WHATS_NEW_SHEET)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
            Text("What's new in v$version", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Column(Modifier.padding(top = 12.dp).heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.55f).dp).verticalScroll(rememberScrollState())) {
                ReleaseNotesBody(notes)
            }
            TextButton(
                onClick = { scope.launch { sheetState.hide() }.invokeOnCompletion { vm.updatesFlow.dismissWhatsNew(); vm.open(app.keepfin.ui.SubPage.GUIDE) } },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 4.dp).testTag(UiTags.WHATS_NEW_GUIDE),
            ) { Text("See all features") }
            Button(
                onClick = { scope.launch { sheetState.hide() }.invokeOnCompletion { vm.updatesFlow.dismissWhatsNew() } },
                colors = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp).testTag("whats-new-done"),
            ) { Text("Got it") }
        }
    }
}

/** Hands a downloaded, validated APK to the system installer; never while downloads are running. */
fun installUpdate(context: Context, vm: StudioViewModel) {
    try {
        require(vm.engine.jobs.value.none { it.status.active }) { "Finish or cancel downloads before installing the update." }
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            vm.say("Allow updates from Keepfin, return here, then tap Install update.")
            return
        }
        val file = vm.updates.installFile()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) { vm.say(e.message ?: "Cannot install the update.") }
}
