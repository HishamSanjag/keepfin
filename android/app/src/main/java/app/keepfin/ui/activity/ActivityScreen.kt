package app.keepfin.ui.activity

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.snap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.ActivityAction
import app.keepfin.ActivityEntry
import app.keepfin.ActivityFilter
import app.keepfin.ActivityKind
import app.keepfin.ActivityLogic
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.FilterPill
import app.keepfin.ui.components.MediaThumb
import app.keepfin.ui.components.RowTextAction
import app.keepfin.ui.theme.Motion
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.theme.spring
import app.keepfin.ui.theme.tabular
import java.text.DateFormat
import java.util.Date

/** "Activity" or "Activity, 3 unread": what the bell says to TalkBack. */
fun activityBellLabel(unread: Int) = if (unread > 0) "Activity, $unread unread" else "Activity"

/** The bell for the top bars of Downloads and Library: opens the Activity page, with an unread count (99+ above 99). */
@Composable
fun ActivityBell(vm: StudioViewModel, modifier: Modifier = Modifier) {
    val entries by vm.activityFeed.entries.collectAsStateWithLifecycle()
    val unread by remember { derivedStateOf { ActivityLogic.unreadCount(entries) } }
    IconButton(
        onClick = vm::openActivity,
        modifier = modifier.size(48.dp).testTag(UiTags.ACTIVITY_BELL).semantics { contentDescription = activityBellLabel(unread) },
    ) {
        BadgedBox(badge = {
            if (unread > 0) Badge(Modifier.testTag(UiTags.ACTIVITY_BADGE).clearAndSetSemantics { }) { Text(if (unread > 99) "99+" else "$unread") }
        }) { Icon(Icons.Outlined.Notifications, contentDescription = null) }
    }
}

/**
 * The full Activity page (dev-docs/architecture/ACTIVITY.md): day groups, filters, 0-2 actions per entry, swipe to remove. Opening it marks everything shown
 * as read; entries that were unread when the page opened keep their dot (and count for the Unread chip) until the user leaves.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(vm: StudioViewModel, onBack: () -> Unit) {
    val entries by vm.activityFeed.entries.collectAsStateWithLifecycle()
    // Ids that are new to the user in this visit: they show a dot while the store already counts them as read.
    val fresh = remember { mutableStateListOf<String>().also { list -> list.addAll(entries.filter { !it.read }.map { it.id }) } }
    LaunchedEffect(entries) {
        val unread = entries.filter { !it.read }.map { it.id }
        if (unread.isNotEmpty()) {
            fresh.addAll(unread.filter { it !in fresh })
            vm.updatesFlow.activityRead(unread)
        }
    }
    val shown = remember(entries, fresh.toList()) { entries.map { if (it.id in fresh) it.copy(read = false) else it } }
    var filter by rememberSaveable { mutableStateOf(ActivityFilter.ALL) }
    val unreadCount = ActivityLogic.unreadCount(shown)
    val problemCount = ActivityLogic.problemCount(shown)
    val visible = ActivityLogic.filter(shown, filter)
    val days = remember(visible) { ActivityLogic.group(visible, System.currentTimeMillis()) }

    Scaffold(
        modifier = Modifier.fillMaxSize().testTag(UiTags.ACTIVITY_SCREEN),
        topBar = {
            TopAppBar(
                title = { Text("Activity", modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(UiTags.ACTIVITY_BACK)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    TextButton(
                        onClick = { vm.updatesFlow.activityReadAll(); fresh.clear() }, enabled = unreadCount > 0,
                        modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.ACTIVITY_MARK_ALL),
                    ) { Text("Mark all read") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterPill("All", filter == ActivityFilter.ALL, { filter = ActivityFilter.ALL }, Modifier.testTag(UiTags.activityChip("all")))
                FilterPill("Unread ($unreadCount)", filter == ActivityFilter.UNREAD, { filter = ActivityFilter.UNREAD }, Modifier.testTag(UiTags.activityChip("unread")))
                FilterPill("Problems ($problemCount)", filter == ActivityFilter.PROBLEMS, { filter = ActivityFilter.PROBLEMS }, Modifier.testTag(UiTags.activityChip("problems")))
            }
            HorizontalDivider(color = Studio.colors.separator)
            if (visible.isEmpty()) {
                EmptyActivity(everything = entries.isEmpty(), filter = filter)
            } else {
                val reduced = Studio.reducedMotion
                val placement: FiniteAnimationSpec<IntOffset> = if (reduced) snap() else Motion.snappy.spring(IntOffset.VisibilityThreshold)
                val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
                LazyColumn(Modifier.fillMaxSize().testTag(UiTags.ACTIVITY_LIST)) {
                    days.forEach { day ->
                        item(key = "day-${day.label}", contentType = "day") {
                            Text(
                                day.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
                            )
                        }
                        items(day.entries, key = { it.id }, contentType = { "entry" }) { entry ->
                            ActivityRow(
                                // Opening an entry (or using any of its actions) clears its dot at once, not only on Mark all read.
                                entry, time = timeFormat.format(Date(entry.at)), onAction = { action -> fresh.remove(entry.id); vm.updatesFlow.activityAction(entry, action) },
                                onOpen = { fresh.remove(entry.id) },
                                onRemove = { vm.updatesFlow.activityRemove(entry.id) }, modifier = Modifier.animateItem(placementSpec = placement),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyActivity(everything: Boolean, filter: ActivityFilter) {
    val text = when {
        everything -> "Nothing yet. Downloads, synced playlists and updates show up here."
        filter == ActivityFilter.UNREAD -> "Nothing unread."
        else -> "No problems. Failed downloads and sign-ins that need you show up here."
    }
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp).testTag(UiTags.ACTIVITY_EMPTY),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Outlined.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f), modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActivityRow(entry: ActivityEntry, time: String, onAction: (ActivityAction) -> Unit, onOpen: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val dismiss = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        if (value == SwipeToDismissBoxValue.Settled) false else { onRemove(); true }
    })
    val actions = remember(entry) { ActivityLogic.actions(entry) }
    SwipeToDismissBox(
        state = dismiss, modifier = modifier.testTag(UiTags.activityEntry(entry.id)),
        backgroundContent = {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 24.dp), contentAlignment = if (dismiss.dismissDirection == SwipeToDismissBoxValue.StartToEnd) Alignment.CenterStart else Alignment.CenterEnd) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Remove", tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
      Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
            EntryArt(entry)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Column(
                    Modifier.fillMaxWidth().clickable(role = Role.Button) { actions.firstOrNull()?.let(onAction) ?: onOpen() }
                        .semantics(mergeDescendants = true) { if (!entry.read) stateDescription = "Unread" },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Text(time, style = MaterialTheme.typography.labelMedium.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    entry.detail?.let { detail ->
                        Text(detail, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (actions.isNotEmpty()) Row(Modifier.offset(x = (-8).dp)) {
                    actions.forEach { action ->
                        RowTextAction(action.label, { onAction(action) }, Modifier.testTag(UiTags.activityAction(entry.id, action.name)))
                    }
                } else Spacer(Modifier.height(6.dp))
            }
            Box(Modifier.padding(start = 8.dp, top = 6.dp).size(8.dp)) {
                if (!entry.read) Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colorScheme.primary).testTag(UiTags.activityDot(entry.id)))
            }
        }
        HorizontalDivider(color = Studio.colors.separator, modifier = Modifier.padding(start = 68.dp))
      }
    }
}

/** Artwork when the entry has any, else a round icon for its kind; problems are tinted so they stand out. */
@Composable
private fun EntryArt(entry: ActivityEntry) {
    if (entry.thumbnail != null) {
        MediaThumb(entry.thumbnail, Modifier.size(40.dp), audio = true, shape = RoundedCornerShape(8.dp))
        return
    }
    val icon = when (entry.kind) {
        ActivityKind.SYNC_FOUND -> Icons.Outlined.Sync
        ActivityKind.SYNC_REVIEW -> Icons.Outlined.PlaylistAdd
        ActivityKind.BATCH_DONE -> Icons.Outlined.CheckCircle
        ActivityKind.FAILED -> Icons.Outlined.ErrorOutline
        ActivityKind.APP_UPDATED -> Icons.Outlined.NewReleases
        ActivityKind.UPDATE_READY -> Icons.Outlined.SystemUpdate
        ActivityKind.SIGN_IN -> Icons.Outlined.Key
    }
    val tint: Color = if (entry.kind.problem) Studio.colors.warning else MaterialTheme.colorScheme.primary
    Box(Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}
