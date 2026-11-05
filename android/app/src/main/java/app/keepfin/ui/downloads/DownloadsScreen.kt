package app.keepfin.ui.downloads

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.keepfin.ui.AggregateSpeed
import app.keepfin.ui.components.FilterPill
import app.keepfin.ui.components.ScreenTitle
import app.keepfin.ui.updates.UpdateBanner
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.Job
import app.keepfin.JobStatus
import app.keepfin.ui.BatchItem
import app.keepfin.ui.Capture
import app.keepfin.ui.CollectionReview
import app.keepfin.ui.Destination
import app.keepfin.ui.DownloadsFilter
import app.keepfin.ui.library.HistoryTools
import app.keepfin.ui.library.KindFilter
import app.keepfin.ui.library.LibrarySort
import app.keepfin.ui.library.StatusFilter
import app.keepfin.ui.library.historyEntries
import app.keepfin.ui.library.libraryEntries
import androidx.compose.material3.FilterChip
import app.keepfin.ui.Format
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.SubPage
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.EmptyArt
import app.keepfin.ui.components.EmptyState
import app.keepfin.ui.components.MediaThumb
import app.keepfin.ui.components.Pane
import app.keepfin.ui.components.PresetPicker
import app.keepfin.ui.components.RowSeparator
import app.keepfin.ui.components.SectionHeader
import app.keepfin.ui.jobs.GroupActions
import app.keepfin.ui.jobs.GroupChoices
import app.keepfin.ui.jobs.choose
import app.keepfin.ui.jobs.isOpen
import app.keepfin.ui.jobs.GroupHeader
import app.keepfin.ui.jobs.JobActions
import app.keepfin.ui.jobs.QueueEntry
import app.keepfin.ui.jobs.RevealEffect
import app.keepfin.ui.jobs.entryKeys
import app.keepfin.ui.jobs.groupMemberItems
import app.keepfin.ui.jobs.orderedChildren
import app.keepfin.ui.jobs.JobRow
import app.keepfin.ui.theme.Studio
import kotlinx.coroutines.delay

/**
 * Entries split for the Downloads screen: active work first, then problems, then everything saved, newest first. A kept-together
 * collection is one entry (with all its members), placed by its overall state; independent jobs are single entries.
 */
data class QueueSections(val active: List<QueueEntry>, val attention: List<QueueEntry>, val saved: List<QueueEntry>) {
    companion object {
        private val running = setOf(JobStatus.DOWNLOADING, JobStatus.PROCESSING, JobStatus.PREPARING)
        private fun open(job: Job) = job.status.active || job.status == JobStatus.PAUSED
        private fun problem(job: Job) = job.status == JobStatus.FAILED || (job.status == JobStatus.COMPLETED && job.saveError != null)
        private fun finished(job: Job) = job.completedAt ?: job.createdAt

        fun of(jobs: List<Job>): QueueSections {
            val groups = jobs.filter { it.group != null }.groupBy { it.group!!.id }
                .map { (_, children) -> QueueEntry.Group(children.first().group!!, orderedChildren(children)) }
            val singles = jobs.filter { it.group == null }.map { QueueEntry.Single(it) }
            fun rank(entry: QueueEntry): Int = when (entry) {
                is QueueEntry.Single -> if (entry.job.status in running) 0 else if (entry.job.status == JobStatus.PAUSED) 1 else 2
                is QueueEntry.Group -> if (entry.children.any { it.status in running }) 0 else if (entry.children.all { it.status == JobStatus.PAUSED }) 1 else 2
            }
            fun created(entry: QueueEntry) = when (entry) { is QueueEntry.Single -> entry.job.createdAt; is QueueEntry.Group -> entry.children.minOf { it.createdAt } }
            fun done(entry: QueueEntry) = when (entry) { is QueueEntry.Single -> finished(entry.job); is QueueEntry.Group -> entry.children.maxOf(::finished) }
            val all = singles + groups
            // A collection counts as in progress only while a member is really working or waiting for a slot. Members that just wait for an
            // automatic retry do not hold it there: it shows with its failures as soon as nothing is downloading.
            fun working(job: Job) = job.status in running || (open(job) && job.retryAt == null)
            val active = all.filter { e -> when (e) { is QueueEntry.Single -> open(e.job); is QueueEntry.Group -> e.children.any(::working) } }
                .sortedWith(compareBy<QueueEntry> { rank(it) }.thenBy { created(it) })
            val rest = all - active.toSet()
            val attention = rest.filter { e -> when (e) { is QueueEntry.Single -> problem(e.job); is QueueEntry.Group -> e.children.any { problem(it) || it.retryAt != null } } }
                .sortedByDescending(::done)
            val saved = (rest - attention.toSet()).filter { e -> when (e) { is QueueEntry.Single -> e.job.status == JobStatus.COMPLETED; is QueueEntry.Group -> e.children.any { it.status == JobStatus.COMPLETED } } }
                .sortedByDescending(::done)
            return QueueSections(active, attention, saved)
        }
    }
}

/** What the clipboard offers on resume. [label] is null when Android hides the text until a paste. */
data class ClipboardHint(val label: String?)

@Composable
fun DownloadsScreen(
    vm: StudioViewModel,
    actions: JobActions,
    groupActions: GroupActions,
    clipboard: ClipboardHint?,
    onPaste: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val jobs by vm.engine.jobs.collectAsStateWithLifecycle()
    val settings by vm.engine.settings.collectAsStateWithLifecycle()
    val libraryOn = settings.library.enabled
    val filter = if (libraryOn) vm.downloadsFilter else DownloadsFilter.ALL
    val sections = remember(jobs) { QueueSections.of(jobs) }
    val listState = rememberLazyListState()
    val reveal = vm.reveal
    var groupChoices by rememberSaveable { mutableStateOf<GroupChoices>(emptyList()) }
    // The Finished view is the old History: search, kind and status filters, sort.
    var historyQuery by rememberSaveable { mutableStateOf("") }
    var historyKind by rememberSaveable { mutableStateOf(KindFilter.ALL) }
    var historyStatus by rememberSaveable { mutableStateOf(StatusFilter.ANY) }
    var historySort by rememberSaveable { mutableStateOf(LibrarySort.NEWEST) }
    val history = remember(jobs, historyQuery, historyKind, historyStatus, historySort, filter) {
        if (filter == DownloadsFilter.FINISHED) libraryEntries(jobs, historyQuery, historyKind, historyStatus, historySort) else emptyList()
    }
    val failedEntries = remember(jobs, filter) {
        if (filter != DownloadsFilter.FAILED) emptyList<QueueEntry>() else {
            val problems = jobs.filter { it.status == JobStatus.FAILED || (it.status == JobStatus.COMPLETED && it.saveError != null) || it.status == JobStatus.CANCELLED }
            // A collection with a failure shows whole, so its counts and Saved list stay truthful.
            val problemGroups = problems.mapNotNull { it.group?.id }.toSet()
            val groups = jobs.filter { it.group?.id in problemGroups }.groupBy { it.group!!.id }.map { (_, children) -> QueueEntry.Group(children.first().group!!, orderedChildren(children)) }
            (problems.filter { it.group == null }.map { QueueEntry.Single(it) } + groups).sortedByDescending { e ->
                when (e) { is QueueEntry.Single -> e.job.completedAt ?: e.job.createdAt; is QueueEntry.Group -> e.children.maxOf { it.completedAt ?: it.createdAt } }
            }
        }
    }
    RevealEffect(
        event = reveal, listState = listState,
        indexOf = { target ->
            val keys = buildList {
                add("banner"); add("capture"); if (vm.capture is Capture.Empty) add("works-with"); add("state"); add("engine"); add("device-jobs"); add("collection")
                if (libraryOn) add("filters")
                when (filter) {
                    DownloadsFilter.ALL -> {
                        if (sections.active.isNotEmpty()) { add("h-active"); addEntries(sections.active, groupChoices) }
                        if (sections.attention.isNotEmpty()) { add("h-attention"); addEntries(sections.attention, groupChoices) }
                        if (sections.saved.isNotEmpty()) { add("h-saved"); addEntries(sections.saved, groupChoices) }
                    }
                    DownloadsFilter.ACTIVE -> if (sections.active.isNotEmpty()) { add("h-active"); addEntries(sections.active, groupChoices) } else add("none")
                    DownloadsFilter.FAILED -> if (failedEntries.isNotEmpty()) { add("h-attention"); addEntries(failedEntries, groupChoices) } else add("none")
                    DownloadsFilter.FINISHED -> { add("history-tools"); addEntries(history, groupChoices) }
                }
            }
            keys.indexOf(target)
        },
        onShown = { target -> if (target.startsWith("group:")) groupChoices = groupChoices.choose(target, true) },
        onConsume = vm::consumeReveal,
    )
    val density = androidx.compose.ui.platform.LocalDensity.current
    // The empty picture fills what is left under the rows above it, measured, so an empty page fits the screen and does not scroll.
    val emptyHeight by remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo
            val empty = info.visibleItemsInfo.firstOrNull { it.key == "empty" } ?: return@derivedStateOf 320.dp
            with(density) { (info.viewportEndOffset - info.afterContentPadding - empty.offset).toDp().coerceAtLeast(320.dp) }
        }
    }
    Column(modifier) {
    // pinned: the title stays while the page scrolls, and its search button appears once the link box has scrolled away
    app.keepfin.ui.TabHeader(vm, "Downloads", listState, Modifier.testTag(UiTags.DOWNLOADS_TITLE), searchAfter = 1) {
        // Large font scales drop the aggregate speed so the title never wraps; rows still show speeds.
        if (density.fontScale <= 1.15f) AggregateSpeed(vm)
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
    LazyColumn(
        state = listState,
        // Keep the last rows reachable above the floating Download bar while it is shown.
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
        modifier = Modifier.fillMaxSize().testTag(UiTags.QUEUE),
    ) {
        item("banner", contentType = "banner") {
            if (vm.updatesFlow.updateBannerVersion != null) UpdateBanner(vm, Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 12.dp))
        }
        item("capture", contentType = "capture") {
            CapturePane(
                draft = vm.draft, onDraftChange = vm::onDraftChange, onPaste = onPaste, onSubmit = vm::inspect,
                onClear = vm::clearDraft, clipboard = clipboard,
                modifier = Modifier.padding(horizontal = 16.dp).padding(top = 4.dp, bottom = 4.dp),
            )
        }
        if (vm.capture is Capture.Empty) item("works-with", contentType = "works-with") { WorksWith(compact = jobs.isNotEmpty(), Modifier.padding(horizontal = 20.dp).padding(bottom = 4.dp)) }
        item("state", contentType = "capture-state") {
            CaptureState(vm, Modifier.padding(horizontal = 16.dp))
        }
        item("engine", contentType = "engine") { app.keepfin.ui.EngineNotice(vm) }
        // Download on...: what other devices asked of this phone and what this phone sent them (shows nothing without any)
        item("device-jobs", contentType = "device-jobs") { app.keepfin.ui.devices.DeviceJobsHost(vm) }
        item("collection", contentType = "collection-summary") {
            val review = vm.collection
            if (review != null && !vm.collectionOpen) {
                CollectionSummary(review, onOpen = { vm.collectionOpen = true }, onClose = vm.collections::closeCollection, modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp))
            }
        }
        if (libraryOn) item("filters", contentType = "filters") {
            DownloadFilters(vm, filter, sections.active.size, jobs.count { it.status == JobStatus.FAILED || (it.status == JobStatus.COMPLETED && it.saveError != null) })
        }
        val toggle: (String, Boolean) -> Unit = { key, open -> groupChoices = groupChoices.choose(key, open) }
        when (filter) {
            DownloadsFilter.ALL -> {
                entrySection("h-active", "In progress", sections.active, actions, groupActions, reveal?.target, groupChoices, toggle, detail = "${sections.active.size}")
                entrySection("h-attention", "Needs attention", sections.attention, actions, groupActions, reveal?.target, groupChoices, toggle)
                entrySection("h-saved", "Saved", sections.saved, actions, groupActions, reveal?.target, groupChoices, toggle, detail = "${sections.saved.size}")
            }
            DownloadsFilter.ACTIVE -> {
                entrySection("h-active", "In progress", sections.active, actions, groupActions, reveal?.target, groupChoices, toggle, detail = "${sections.active.size}")
                if (sections.active.isEmpty() && jobs.isNotEmpty()) item("none", contentType = "note") { FilterNote("Nothing is downloading", "Active downloads and anything waiting to start show up here.") }
            }
            DownloadsFilter.FAILED -> {
                entrySection("h-attention", "Needs attention", failedEntries, actions, groupActions, reveal?.target, groupChoices, toggle)
                if (failedEntries.isEmpty() && jobs.isNotEmpty()) item("none", contentType = "note") { FilterNote("No failed downloads", "Failed, cancelled and unsaved downloads show up here with Retry.") }
            }
            DownloadsFilter.FINISHED -> {
                item("history-tools", contentType = "history-tools") {
                    HistoryTools(vm, historyQuery, { historyQuery = it }, historyKind, { historyKind = it }, historyStatus, { historyStatus = it }, historySort, { historySort = it })
                }
                if (history.isEmpty() && jobs.isNotEmpty()) item("none", contentType = "note") {
                    val searching = historyQuery.isNotBlank() || historyKind != KindFilter.ALL || historyStatus != StatusFilter.ANY
                    FilterNote(if (searching) "Nothing matches" else "No finished downloads yet", if (searching) "Try another search, or clear the filters." else "Downloads that finish appear here. Clearing history never deletes saved files.")
                }
                historyEntries(history, actions, groupActions, reveal?.target, groupChoices, toggle)
            }
        }
        if (jobs.isEmpty() && vm.capture is Capture.Empty && (vm.collection == null || vm.collectionOpen)) {
            item("empty", contentType = "empty") { EmptyQueue(onPaste, Modifier.height(emptyHeight)) }
        }
    }
    }
    // The preview of a link: one sheet over this page, in every case (see PreviewHost).
    PreviewHost(vm)
    }
}

private fun MutableList<String>.addEntries(entries: List<QueueEntry>, expanded: GroupChoices) {
    entries.forEach { entry -> addAll(entryKeys(entry, expanded)) }
}

/** Renders singles and kept-together collections; an expanded collection contributes its members as individual lazy rows. */
private fun LazyListScope.entrySection(
    key: String,
    title: String,
    entries: List<QueueEntry>,
    actions: JobActions,
    groupActions: GroupActions,
    reveal: String?,
    expanded: GroupChoices,
    onToggle: (String, Boolean) -> Unit,
    detail: String? = null,
) {
    if (entries.isEmpty()) return
    item(key, contentType = "header") {
        SectionHeader(title, Modifier.padding(start = 16.dp, end = 8.dp), detail = detail)
    }
    entries.forEachIndexed { index, entry ->
        when (entry) {
            is QueueEntry.Single -> item(entry.job.id, contentType = "job") {
                Column {
                    JobRow(entry.job, actions, highlighted = entry.job.id == reveal)
                    if (index < entries.lastIndex) RowSeparator(inset = 140)
                }
            }
            is QueueEntry.Group -> {
                val open = entry.isOpen(expanded)
                item(entry.key, contentType = "group") {
                    Column {
                        GroupHeader(entry.group, entry.children, open, { onToggle(entry.key, !open) }, groupActions, highlighted = entry.key == reveal)
                        if (!open && index < entries.lastIndex) RowSeparator(inset = 140)
                    }
                }
                if (open) groupMemberItems(entry, actions, reveal, lastRowSeparator = index < entries.lastIndex)
            }
        }
    }
}

/** Shows a multi-line draft on one line without changing the text: new lines read as spaces. */
private val OneLine = androidx.compose.ui.text.input.VisualTransformation { text ->
    androidx.compose.ui.text.input.TransformedText(
        androidx.compose.ui.text.AnnotatedString(text.text.replace('\n', ' ')), androidx.compose.ui.text.input.OffsetMapping.Identity,
    )
}

@Composable
fun CapturePane(
    draft: String,
    onDraftChange: (String) -> Unit,
    onPaste: () -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    clipboard: ClipboardHint?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        // One 56dp pill line: link icon, the text, a clear button once there is text, and Paste. Several links pasted at once stay in
        // the draft (one per line) and are inspected as a batch.
        val colors = MaterialTheme.colorScheme
        Row(
            Modifier.fillMaxWidth().height(56.dp).clip(MaterialTheme.shapes.medium).background(Studio.colors.raised)
                .border(1.dp, Studio.colors.separator, MaterialTheme.shapes.medium).padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Outlined.Link, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (draft.isEmpty()) Text("Paste a link", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(
                    value = draft, onValueChange = onDraftChange, singleLine = true, visualTransformation = OneLine,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onGo = { onSubmit() }),
                    modifier = Modifier.fillMaxWidth().testTag(UiTags.LINK_FIELD).semantics { contentDescription = "Paste a link" },
                )
            }
            if (draft.isNotEmpty()) IconButton(onClick = onClear, modifier = Modifier.size(40.dp).testTag(UiTags.CLEAR_LINK)) {
                Icon(Icons.Outlined.Close, contentDescription = "Clear link", modifier = Modifier.size(20.dp))
            }
            Button(
                onClick = onPaste, contentPadding = PaddingValues(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceContainerHighest, contentColor = colors.onSurface),
                modifier = Modifier.height(40.dp).testTag(UiTags.PASTE).semantics { contentDescription = "Paste link from clipboard" },
            ) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Paste", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun CaptureState(vm: StudioViewModel, modifier: Modifier) {
    when (val state = vm.capture) {
        Capture.Empty -> Unit
        // Reading, the result and a failure are the preview sheet over the page (see PreviewHost) and add nothing to this list; closed, a one-line summary stays.
        is Capture.Single -> if (!vm.reviewOpen) ReviewSummary(state.media.title, state.media.thumbnail, isSong(state.media), state.media.video.isEmpty(), "Ready to download", vm::openReview, vm::clearDraft, modifier.padding(bottom = 12.dp))
        is Capture.Batch -> if (!vm.reviewOpen) ReviewSummary("${state.items.size} links", null, false, false, "Ready to download", vm::openReview, vm::clearDraft, modifier.padding(bottom = 12.dp))
        is Capture.Inspecting, is Capture.Failed -> Unit
    }
}

/** Which services Keepfin handles, as quiet plain text under the paste box (names from the shared providers; no logos). One line once there are transfers. */
@Composable
private fun WorksWith(compact: Boolean, modifier: Modifier = Modifier) {
    Text(
        "Works with YouTube, YouTube Music, Spotify, Apple Music, Deezer, SoundCloud, Bandcamp, TikTok, Instagram, Facebook, X, Reddit, Vimeo, Twitch, Pinterest and 1,000+ more sites",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = if (compact) 1 else 3, overflow = TextOverflow.Ellipsis, modifier = modifier.testTag(UiTags.WORKS_WITH),
    )
}

@Composable
private fun EmptyQueue(onPaste: () -> Unit, modifier: Modifier) {
    EmptyState(
        art = EmptyArt.DOWNLOADS, title = "Nothing downloading yet",
        body = "Paste a video, song, playlist or channel link and Keepfin saves it to your phone. Playlists and channels let you choose what to keep.",
        hint = "or share a link from any app", modifier = modifier,
        action = {
            Button(
                onClick = onPaste, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.EMPTY_ACTION),
                colors = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction),
            ) {
                Icon(Icons.Outlined.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Paste link")
            }
        },
    )
}

/** All / Active · n / Finished / Failed · n: one list, filtered. */
@Composable
private fun DownloadFilters(vm: StudioViewModel, selected: DownloadsFilter, active: Int, failed: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).testTag(UiTags.DOWNLOADS_FILTERS),
        horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        DownloadsFilter.entries.forEach { option ->
            val badge = when (option) { DownloadsFilter.ACTIVE -> active; DownloadsFilter.FAILED -> failed; else -> 0 }
            FilterPill(
                if (badge > 0) "${option.label} · $badge" else option.label, selected == option, { vm.chooseDownloadsFilter(option) },
                Modifier.testTag(UiTags.downloadsFilter(option.name)),
            )
        }
    }
}

@Composable
private fun FilterNote(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
