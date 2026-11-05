package app.keepfin.ui.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.Job
import app.keepfin.JobStatus
import app.keepfin.MediaKind
import app.keepfin.ui.Destination
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.EmptyArt
import app.keepfin.ui.components.EmptyState
import app.keepfin.ui.components.RowSeparator
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
import app.keepfin.ui.jobs.isRunning
import app.keepfin.ui.jobs.orderedChildren
import app.keepfin.ui.jobs.JobRow
import app.keepfin.ui.jobs.JobTile
import app.keepfin.ui.theme.Studio
import kotlinx.coroutines.delay

enum class KindFilter(val label: String) { ALL("All"), VIDEO("Video"), AUDIO("Audio") }
enum class StatusFilter(val label: String, val status: JobStatus?) {
    ANY("Any state", null), COMPLETED("Completed", JobStatus.COMPLETED), FAILED("Failed", JobStatus.FAILED), CANCELLED("Cancelled", JobStatus.CANCELLED)
}
enum class LibrarySort(val label: String) { NEWEST("Newest first"), OLDEST("Oldest first"), TITLE("Title A–Z"), LARGEST("Largest first") }

/** Pure filtering so tests and the screen agree on what the Library shows. */
fun libraryItems(jobs: List<Job>, query: String, kind: KindFilter, status: StatusFilter, sort: LibrarySort): List<Job> {
    val terms = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val filtered = jobs.filter { job ->
        (!job.status.active && job.status != JobStatus.PAUSED) &&
            (kind == KindFilter.ALL || (kind == KindFilter.AUDIO) == (job.kind == MediaKind.AUDIO)) &&
            (status.status == null || job.status == status.status) &&
            terms.all { term -> listOfNotNull(job.title, job.uploader, job.provider, job.url).any { it.lowercase().contains(term) } }
    }
    fun finished(job: Job) = job.completedAt ?: job.createdAt
    return when (sort) {
        LibrarySort.NEWEST -> filtered.sortedByDescending(::finished)
        LibrarySort.OLDEST -> filtered.sortedBy(::finished)
        LibrarySort.TITLE -> filtered.sortedBy { it.title.lowercase() }
        LibrarySort.LARGEST -> filtered.sortedByDescending { it.fileSize ?: it.totalBytes ?: 0L }
    }
}

/**
 * Finished-list entries: finished jobs plus one card per kept-together collection. A collection appears as soon as none of
 * its members is downloading, so failures stay visible even while other members wait (including for an automatic retry).
 * Order follows [libraryItems]; a collection sits where its first matching member would.
 */
fun libraryEntries(jobs: List<Job>, query: String, kind: KindFilter, status: StatusFilter, sort: LibrarySort): List<QueueEntry> {
    val workingGroups = jobs.filter { it.group != null && it.isRunning() }.map { it.group!!.id }.toSet()
    val matching = libraryItems(jobs, query, kind, status, sort).filter { it.group?.id !in workingGroups }
    val seen = HashSet<String>()
    return matching.mapNotNull { job ->
        val group = job.group
        if (group == null) QueueEntry.Single(job)
        else if (seen.add(group.id)) QueueEntry.Group(group, orderedChildren(jobs.filter { it.group?.id == group.id }))
        else null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryTopBar(vm: StudioViewModel, scroll: TopAppBarScrollBehavior, sort: LibrarySort, onSort: (LibrarySort) -> Unit) {
    var sortMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val grid = vm.prefs.libraryGrid
    TopAppBar(
        title = { Text("Library") },
        scrollBehavior = scroll,
        actions = {
            app.keepfin.ui.activity.ActivityBell(vm)
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = "Sort: ${sort.label}") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    LibrarySort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            leadingIcon = { RadioButton(selected = option == sort, onClick = null) },
                            onClick = { sortMenu = false; onSort(option) },
                        )
                    }
                }
            }
            IconButton(onClick = { vm.prefs.chooseLibraryGrid(!grid) }, modifier = Modifier.testTag(UiTags.LIBRARY_VIEW_TOGGLE)) {
                Icon(if (grid) Icons.Outlined.ViewAgenda else Icons.Outlined.GridView, contentDescription = if (grid) "Show as list" else "Show as grid")
            }
            Box {
                IconButton(onClick = { more = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More library actions") }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(
                        text = { Text("Clear history…") }, leadingIcon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                        onClick = { more = false; confirmClear = true }, modifier = Modifier.testTag(UiTags.LIBRARY_CLEAR),
                    )
                }
            }
        },
    )
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear download history?") },
            text = { Text("Finished, failed and cancelled entries leave this list. Saved files stay in Movies/Keepfin and Music/Keepfin.") },
            confirmButton = { Button(onClick = { confirmClear = false; vm.jobs.clearHistory() }) { Text("Clear history") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } },
        )
    }
}

@Composable
fun LibraryScreen(
    vm: StudioViewModel,
    actions: JobActions,
    groupActions: GroupActions,
    sort: LibrarySort,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val jobs by vm.engine.jobs.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(KindFilter.ALL) }
    var status by rememberSaveable { mutableStateOf(StatusFilter.ANY) }
    val entries = remember(jobs, query, kind, status, sort) { libraryEntries(jobs, query, kind, status, sort) }
    val items = remember(entries) { entries.flatMap { e -> when (e) { is QueueEntry.Single -> listOf(e.job); is QueueEntry.Group -> e.children.filter { !it.status.active && it.status != JobStatus.PAUSED } } } }
    var groupChoices by rememberSaveable { mutableStateOf<GroupChoices>(emptyList()) }
    val hasHistory = remember(jobs) { jobs.any { !it.status.active && it.status != JobStatus.PAUSED } }
    val reveal = vm.reveal
    val listState = rememberLazyListState()
    RevealEffect(
        event = reveal, listState = listState,
        indexOf = { target ->
            if (vm.prefs.libraryGrid) -1 else entries.flatMap { entryKeys(it, groupChoices) }.indexOf(target)
        },
        onShown = { target -> if (target.startsWith("group:")) groupChoices = groupChoices.choose(target, true) },
        onConsume = vm::consumeReveal,
    )
    Column(modifier.fillMaxSize()) {
        LibraryControls(query, { query = it }, kind, { kind = it }, status, { status = it })
        when {
            !hasHistory -> {
                LibraryEmpty(EmptyArt.LIBRARY, "Your library is empty", "Finished downloads appear here with where they were saved. Clearing history never deletes saved files.", "Go to Downloads", Modifier.weight(1f)) { vm.navigate(Destination.DOWNLOADS) }
            }
            items.isEmpty() -> {
                LibraryEmpty(EmptyArt.SEARCH, "Nothing matches", "Try another search, or clear the filters to see everything you've saved.", "Clear filters", Modifier.weight(1f)) { query = ""; kind = KindFilter.ALL; status = StatusFilter.ANY }
            }
            vm.prefs.libraryGrid -> LazyVerticalGrid(
                columns = GridCells.Adaptive(168.dp), contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
                modifier = Modifier.fillMaxSize().testTag(UiTags.LIBRARY_LIST),
            ) {
                items(items, key = { it.id }, contentType = { "tile" }) { job -> JobTile(job, actions) }
            }
            else -> LazyColumn(
                state = listState, contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
                modifier = Modifier.fillMaxSize().testTag(UiTags.LIBRARY_LIST),
            ) {
                historyEntries(entries, actions, groupActions, reveal?.target, groupChoices) { key, open -> groupChoices = groupChoices.choose(key, open) }
            }
        }
    }
}

/** The rows of the finished-download history: singles and kept-together collections, an open collection contributing its members. */
fun androidx.compose.foundation.lazy.LazyListScope.historyEntries(
    entries: List<QueueEntry>,
    actions: JobActions,
    groupActions: GroupActions,
    reveal: String?,
    groupChoices: GroupChoices,
    onToggle: (String, Boolean) -> Unit,
) {
    val last = entries.lastIndex
    entries.forEachIndexed { index, entry ->
        when (entry) {
            is QueueEntry.Single -> item(entry.job.id, contentType = "job") {
                Column {
                    JobRow(entry.job, actions, highlighted = entry.job.id == reveal)
                    if (index < last) RowSeparator(inset = 140)
                }
            }
            is QueueEntry.Group -> {
                val open = entry.isOpen(groupChoices)
                item(entry.key, contentType = "group") {
                    Column {
                        GroupHeader(entry.group, entry.children, open, { onToggle(entry.key, !open) }, groupActions, highlighted = entry.key == reveal)
                        if (!open && index < last) RowSeparator(inset = 140)
                    }
                }
                if (open) groupMemberItems(entry, actions, reveal, lastRowSeparator = index < last)
            }
        }
    }
}

@Composable
internal fun LibraryControls(
    query: String, onQuery: (String) -> Unit,
    kind: KindFilter, onKind: (KindFilter) -> Unit,
    status: StatusFilter, onStatus: (StatusFilter) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            placeholder = { Text("Search titles, creators, sites") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, contentDescription = "Clear search") } },
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Studio.colors.raised, unfocusedContainerColor = Studio.colors.raised, unfocusedBorderColor = Studio.colors.separator,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(UiTags.LIBRARY_SEARCH),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            KindFilter.entries.forEach { option ->
                FilterChip(selected = kind == option, onClick = { onKind(option) }, label = { Text(option.label) }, modifier = Modifier.testTag(UiTags.filter(option.name.lowercase())))
            }
            VerticalDivider(Modifier.height(24.dp).padding(horizontal = 4.dp), color = Studio.colors.separator)
            StatusFilter.entries.drop(1).forEach { option ->
                FilterChip(
                    selected = status == option, onClick = { onStatus(if (status == option) StatusFilter.ANY else option) },
                    label = { Text(option.label) }, modifier = Modifier.testTag(UiTags.filter(option.name.lowercase())),
                )
            }
        }
    }
}

@Composable
private fun LibraryEmpty(art: EmptyArt, title: String, body: String, action: String, modifier: Modifier = Modifier, onAction: () -> Unit) {
    EmptyState(
        art = art, title = title, body = body, modifier = modifier,
        action = {
            if (art == EmptyArt.LIBRARY) androidx.compose.material3.Button(
                onClick = onAction, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.EMPTY_ACTION),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction),
            ) { Text(action) }
            else androidx.compose.material3.OutlinedButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.EMPTY_ACTION)) { Text(action) }
        },
    )
}

/** Search, filters, sort and Clear history for the Finished view inside Downloads. */
@Composable
fun HistoryTools(
    vm: StudioViewModel,
    query: String, onQuery: (String) -> Unit,
    kind: KindFilter, onKind: (KindFilter) -> Unit,
    status: StatusFilter, onStatus: (StatusFilter) -> Unit,
    sort: LibrarySort, onSort: (LibrarySort) -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        LibraryControls(query, onQuery, kind, onKind, status, onStatus)
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                TextButton(onClick = { sortMenu = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("history-sort")) {
                    Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text(sort.label)
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    LibrarySort.entries.forEach { option ->
                        DropdownMenuItem(text = { Text(option.label) }, leadingIcon = { RadioButton(selected = option == sort, onClick = null) }, onClick = { sortMenu = false; onSort(option) })
                    }
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            TextButton(onClick = { confirmClear = true }, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.LIBRARY_CLEAR)) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text("Clear history…")
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear download history?") },
            text = { Text("Finished, failed and cancelled entries leave this list. Saved files stay on your phone and in your Library.") },
            confirmButton = { Button(onClick = { confirmClear = false; vm.jobs.clearHistory() }) { Text("Clear history") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Keep") } },
        )
    }
}
