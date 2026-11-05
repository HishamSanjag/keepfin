package app.keepfin.ui.jobs

import androidx.compose.animation.animateColorAsState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import app.keepfin.ui.theme.Studio
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.semantics.heading
import app.keepfin.ui.components.RowSeparator
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.ClearAll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.keepfin.GroupSummary
import app.keepfin.Job
import app.keepfin.JobGroup
import app.keepfin.JobStatus
import app.keepfin.MediaKind
import app.keepfin.RetryPolicy
import app.keepfin.ui.Format
import app.keepfin.ui.RevealEvent
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.ArtworkBadge
import app.keepfin.ui.components.MediaThumb
import app.keepfin.ui.theme.tabular
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One item of a list: an independent job or a kept-together collection with all its members. */
sealed interface QueueEntry {
    val key: String
    data class Single(val job: Job) : QueueEntry { override val key get() = job.id }
    data class Group(val group: JobGroup, val children: List<Job>) : QueueEntry { override val key get() = groupKey(group.id) }
}

fun groupKey(id: String) = "group:$id"

/** Members of a group in queue (creation) order, which is the playlist order. */
fun orderedChildren(children: List<Job>) = children.sortedBy { it.createdAt }

/** True while the member is actually working: downloading, preparing or processing (a retry wait or a queue slot is not). */
fun Job.isRunning() = status == JobStatus.DOWNLOADING || status == JobStatus.PREPARING || status == JobStatus.PROCESSING
/** A member that did not end up saved: failed, cancelled, downloaded but not saved, or waiting for an automatic retry. */
fun Job.needsAttention() = status == JobStatus.FAILED || status == JobStatus.CANCELLED || (status == JobStatus.COMPLETED && saveError != null) ||
    (status == JobStatus.QUEUED && retryAt != null)

/** An expanded collection lists what needs attention first, then what is active, then the saved members in playlist order. */
class GroupMembers(val attention: List<Job>, val active: List<Job>, val saved: List<Job>) {
    /** Item keys in display order, including the sub-headers, so reveal can find a member's index. */
    fun keys(groupKey: String): List<String> = buildList {
        if (attention.isNotEmpty()) { add("$groupKey:attention"); attention.forEach { add(it.id) } }
        if (active.isNotEmpty()) { add("$groupKey:active"); active.forEach { add(it.id) } }
        if (saved.isNotEmpty()) { add("$groupKey:saved"); saved.forEach { add(it.id) } }
    }
}

fun groupMembers(children: List<Job>): GroupMembers {
    val ordered = orderedChildren(children)
    val attention = ordered.filter { it.needsAttention() }
    val active = ordered.filter { (it.status.active || it.status == JobStatus.PAUSED) && it !in attention }
    return GroupMembers(attention, active, ordered - attention.toSet() - active.toSet())
}

/** What the person chose for each collection: "+key" opened, "-key" closed. A collection they never touched follows [defaultOpen]. */
typealias GroupChoices = List<String>

/** A collection starts open while anything in it is active or did not end up saved, and closed once everything is finished. */
fun defaultOpen(children: List<Job>) = children.any { it.status.active || it.status == JobStatus.PAUSED || it.needsAttention() }

fun QueueEntry.Group.isOpen(choices: GroupChoices) = when {
    "+$key" in choices -> true
    "-$key" in choices -> false
    else -> defaultOpen(children)
}

fun GroupChoices.choose(key: String, open: Boolean): GroupChoices = this - "+$key" - "-$key" + (if (open) "+$key" else "-$key")

/** Every key an entry contributes to the list, in order: the header, then its members when it is open. */
fun entryKeys(entry: QueueEntry, choices: GroupChoices): List<String> = buildList {
    add(entry.key)
    if (entry is QueueEntry.Group && entry.isOpen(choices)) addAll(groupMembers(entry.children).keys(entry.key))
}

/**
 * The member rows of an open collection: "Needs attention" first (failed, cancelled, unsaved or waiting for a retry), then what is
 * in progress, then "Saved · n" in playlist order. Every member is an ordinary [JobRow], the same as a download on its own.
 */
fun LazyListScope.groupMemberItems(
    entry: QueueEntry.Group,
    actions: JobActions,
    reveal: String?,
    lastRowSeparator: Boolean,
) {
    val members = groupMembers(entry.children)
    val sections = listOf(
        Triple("attention", "Needs attention", members.attention),
        Triple("active", "In progress · ${members.active.size}", members.active),
        Triple("saved", "Saved · ${members.saved.size}", members.saved),
    ).filter { it.third.isNotEmpty() }
    sections.forEachIndexed { sectionIndex, (name, title, jobs) ->
        item("${entry.key}:$name", contentType = "member-head") {
            Text(
                title, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                color = if (name == "attention") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)
                    .semantics { heading() }.then(if (name == "attention") Modifier.testTag(UiTags.groupAttention(entry.group.id)) else Modifier),
            )
        }
        itemsIndexed(jobs, key = { _, job -> job.id }, contentType = { _, _ -> "job" }) { index, job ->
            val last = sectionIndex == sections.lastIndex && index == jobs.lastIndex
            Column {
                JobRow(job, actions, highlighted = job.id == reveal, attention = name == "attention")
                if (!last || lastRowSeparator) RowSeparator(inset = 140)
            }
        }
    }
}


/** "29 saved · 2 failed" once no member is working; the progress wording ("8 of 12 done") while something is. */
fun groupSummaryText(children: List<Job>, summary: GroupSummary): String {
    val problems = summary.failed + summary.cancelled
    val retrying = children.any { it.status == JobStatus.QUEUED && it.retryAt != null }
    if (summary.removed > 0 && summary.active == 0 && !children.any { it.isRunning() }) return if (summary.saved == 0 && problems == 0) summary.removedText() else groupSummaryParts(children, summary).joinToString(" · ") { it.first }
    if ((problems == 0 && !retrying) || children.any { it.isRunning() }) return summary.text()
    return groupSummaryParts(children, summary).joinToString(" · ") { it.first }
}

/** The pieces of [groupSummaryText] with their meaning, so the card can colour saved and failed counts. */
fun groupSummaryParts(children: List<Job>, summary: GroupSummary): List<Pair<String, String>> {
    val waiting = children.count { it.status == JobStatus.QUEUED }
    return listOfNotNull(
        ("${summary.saved} saved" to "saved").takeIf { summary.saved > 0 }, ("${summary.removed} removed" to "removed").takeIf { summary.removed > 0 }, ("${summary.failed} failed" to "failed").takeIf { summary.failed > 0 },
        ("${summary.cancelled} cancelled" to "cancelled").takeIf { summary.cancelled > 0 }, ("$waiting waiting" to "waiting").takeIf { waiting > 0 },
        ("${summary.paused} paused" to "paused").takeIf { summary.paused > 0 },
    )
}

/** Callbacks for a collection card. Created once per screen. */
@Stable
class GroupActions(
    val pauseAll: (String) -> Unit,
    val resumeAll: (String) -> Unit,
    val cancelAll: (String) -> Unit,
    val retryFailed: (String) -> Unit,
    val remove: (String) -> Unit,
    /** Removes every failed, cancelled and unsaved item of a collection from the list and from history. Never deletes media. */
    val removeFailed: (String) -> Unit = {},
    val openSource: (JobGroup) -> Unit,
    val copyLink: (JobGroup) -> Unit,
    val shareLink: (JobGroup) -> Unit,
    /** Display name of a space id, for "Saved to Music" on the card. */
    val spaceName: (String?) -> String? = { null },
)

/** Ticks once a second while [enabled]; used by retry countdowns without touching the engine. */
@Composable
fun rememberSecondClock(enabled: Boolean): Long {
    val now by produceState(System.currentTimeMillis(), enabled) {
        if (!enabled) return@produceState
        while (true) { value = System.currentTimeMillis(); delay(1000) }
    }
    return now
}

/** "Retrying in 12 s (2 of 3)" while a retry waits, else null. */
@Composable
fun retryStatus(job: Job): String? {
    val at = job.retryAt
    val now = rememberSecondClock(enabled = at != null && job.status == JobStatus.QUEUED)
    if (at == null || job.status != JobStatus.QUEUED) return null
    return RetryPolicy.waitingText(((at - now) + 999) / 1000, job.retryCount, job.phase == app.keepfin.MediaEngine.ENGINE_PHASE)
}

/**
 * Header of a kept-together collection, laid out like a [JobRow]: the same 16:9 picture (with the item count where a download shows
 * its length), title, "YouTube Music playlist · Saved to Music", the counts ("29 saved · 2 failed"), Retry failed, aggregate
 * progress while active, the group menu and a chevron. Tapping the header opens or closes its members, which the list renders as
 * ordinary rows so each recomposes alone; this header only aggregates.
 */
@Composable
fun GroupHeader(
    group: JobGroup,
    children: List<Job>,
    expanded: Boolean,
    onToggle: () -> Unit,
    actions: GroupActions,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val summary = remember(children) { GroupSummary.of(children) }
    val reduced = app.keepfin.ui.theme.Studio.reducedMotion
    val highlight by animateColorAsState(
        if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = .10f) else Color.Transparent,
        animationSpec = tween(if (reduced) 0 else 220), label = "group-highlight",
    )
    val colors = MaterialTheme.colorScheme
    val running = summary.active > 0
    val live = children.any { it.isRunning() || (it.status.active && it.retryAt == null) || it.status == JobStatus.PAUSED }
    val label = "${group.title}, ${groupSummaryText(children, summary)}"
    val working = children.any { it.isRunning() }
    val listing = !((summary.failed + summary.cancelled == 0 && children.none { it.status == JobStatus.QUEUED && it.retryAt != null }) || working)
    val first = children.firstOrNull()
    val where = actions.spaceName(first?.space)?.let { "Saved to $it" }
    val provider = first?.provider
    Column(
        modifier.fillMaxWidth().background(highlight)
            .clickable(onClickLabel = if (expanded) "Collapse" else "Expand", onClick = onToggle)
            .testTag(UiTags.group(group.id))
            .semantics(mergeDescendants = false) { contentDescription = label; stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.width(112.dp).aspectRatio(16f / 9f)) {
                MediaThumb(group.thumbnail, Modifier.fillMaxSize(), audio = children.isNotEmpty() && children.all { it.kind == MediaKind.AUDIO })
                ArtworkBadge("${children.size} items", Modifier.align(Alignment.BottomEnd).padding(4.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f).padding(top = 1.dp)) {
                Text(group.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(listOfNotNull(provider, kindLabel(group.kind).lowercase()).joinToString(" ").replaceFirstChar { it.uppercase() }.takeIf { provider != null }
                        ?: kindLabel(group.kind), where).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val parts = groupSummaryParts(children, summary)
                Text(
                    if (listing) buildAnnotatedString {
                        parts.forEachIndexed { index, (text, kind) ->
                            if (index > 0) append(" · ")
                            val tone = when (kind) { "saved" -> Studio.colors.success; "failed" -> colors.error; else -> colors.onSurfaceVariant }
                            withStyle(SpanStyle(color = tone, fontWeight = if (kind == "saved" || kind == "failed") FontWeight.SemiBold else FontWeight.Normal)) { append(text) }
                        }
                    } else AnnotatedString(summary.text()),
                    style = MaterialTheme.typography.bodySmall.tabular(), color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp).testTag(UiTags.groupCounts(group.id)),
                )
            }
            GroupMenu(group, summary, actions)
            IconButton(
                onClick = onToggle,
                modifier = Modifier.semantics { contentDescription = if (expanded) "Collapse ${group.title}" else "Expand ${group.title}" }.testTag(UiTags.groupToggle(group.id)),
            ) { Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null) }
        }
        if (summary.failed > 0) Row(Modifier.fillMaxWidth().padding(end = 12.dp), horizontalArrangement = Arrangement.End) {
            FilledTonalButton(
                onClick = { actions.retryFailed(group.id) }, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.groupRetryButton(group.id)),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = colors.errorContainer, contentColor = colors.onErrorContainer),
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Retry failed")
            }
        }
        if (live) {
            val track = colors.surfaceContainerHighest
            val bar = Modifier.fillMaxWidth().height(4.dp).padding(end = 12.dp)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { summary.percent / 100f }, modifier = bar.semantics { stateDescription = "${summary.percent} percent" },
                color = if (running) colors.primary else colors.outline, trackColor = track, strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {},
            )
            Spacer(Modifier.height(6.dp))
            val speed = children.filter { it.status == JobStatus.DOWNLOADING }.sumOf { it.speed ?: 0L }
            val done = children.sumOf { if (it.status == JobStatus.COMPLETED) (it.fileSize ?: it.downloadedBytes ?: 0L) else (it.downloadedBytes ?: 0L) }
            val sizes = children.map { it.totalBytes ?: it.expectedSize ?: it.fileSize }
            val total = if (sizes.all { it != null }) sizes.sumOf { it ?: 0L } else null
            val left = listOfNotNull("${summary.percent}%", if (total != null && total > 0) Format.progressBytes(done.coerceAtMost(total), total) else done.takeIf { it > 0 }?.let(Format::bytes)).joinToString(" · ")
            Row(Modifier.fillMaxWidth().padding(end = 12.dp)) {
                Text(left, style = MaterialTheme.typography.bodySmall.tabular(), color = colors.onSurfaceVariant, maxLines = 1, modifier = Modifier.weight(1f).testTag(UiTags.groupProgress(group.id)))
                Format.speed(speed)?.let { Text(it, style = MaterialTheme.typography.bodySmall.tabular(), color = colors.onSurfaceVariant, maxLines = 1) }
            }
        }
    }
}

private fun kindLabel(kind: String) = when (kind) { "album" -> "Album"; "channel" -> "Channel"; "mix" -> "Mix"; else -> "Playlist" }

@Composable
private fun GroupMenu(group: JobGroup, summary: GroupSummary, actions: GroupActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "More actions for ${group.title}" }.testTag(UiTags.groupMenu(group.id))) {
            Icon(Icons.Outlined.MoreVert, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable fun item(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tag: String, action: () -> Unit) = DropdownMenuItem(
                text = { Text(label) }, leadingIcon = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = { open = false; action() }, modifier = Modifier.testTag(tag),
            )
            if (summary.active > 0) item("Pause all", Icons.Outlined.Pause, UiTags.GROUP_PAUSE) { actions.pauseAll(group.id) }
            if (summary.paused > 0) item("Resume all", Icons.Outlined.PlayArrow, UiTags.GROUP_RESUME) { actions.resumeAll(group.id) }
            if (summary.failed > 0) item("Retry failed", Icons.Outlined.Refresh, UiTags.GROUP_RETRY) { actions.retryFailed(group.id) }
            if (summary.failed + summary.cancelled > 0) item("Remove failed", Icons.Outlined.ClearAll, UiTags.GROUP_REMOVE_FAILED) { actions.removeFailed(group.id) }
            if (summary.active > 0 || summary.paused > 0) item("Cancel all", Icons.Outlined.Close, UiTags.GROUP_CANCEL) { actions.cancelAll(group.id) }
            if (group.url.isNotBlank()) {
                item("Open source link", Icons.AutoMirrored.Outlined.OpenInNew, UiTags.GROUP_SOURCE) { actions.openSource(group) }
                item("Copy link", Icons.Outlined.ContentCopy, UiTags.GROUP_COPY) { actions.copyLink(group) }
                item("Share link", Icons.Outlined.Share, UiTags.GROUP_SHARE) { actions.shareLink(group) }
            }
            if (summary.active == 0 && summary.paused == 0) item("Remove from history", Icons.Outlined.ClearAll, UiTags.GROUP_REMOVE) { actions.remove(group.id) }
        }
    }
}

/**
 * Consumes a one-shot [RevealEvent]: waits (up to 3 s) until its target exists, scrolls there once, highlights for about
 * 1.5 s and then asks the owner to forget it. Keyed on the event only, so list changes (progress ticks, new rows) never
 * re-scroll, and any user drag cancels a pending scroll.
 */
@Composable
fun RevealEffect(
    event: RevealEvent?,
    listState: LazyListState,
    indexOf: (String) -> Int,
    onShown: (String) -> Unit,
    onConsume: (RevealEvent) -> Unit,
) {
    val latestIndexOf by rememberUpdatedState(indexOf)
    val latestShown by rememberUpdatedState(onShown)
    val latestConsume by rememberUpdatedState(onConsume)
    LaunchedEffect(event?.seq) {
        val current = event ?: return@LaunchedEffect
        val index = withTimeoutOrNull(3000) { snapshotFlow { latestIndexOf(current.target) }.first { it >= 0 } }
        if (index != null) {
            latestShown(current.target)
            coroutineScope {
                val scroll = launch { listState.animateScrollToItem(index) }
                val watcher = launch { listState.interactionSource.interactions.first { it is DragInteraction.Start }; scroll.cancel() }
                scroll.join()
                watcher.cancel()
            }
            delay(1500)
        }
        latestConsume(current)
    }
}
