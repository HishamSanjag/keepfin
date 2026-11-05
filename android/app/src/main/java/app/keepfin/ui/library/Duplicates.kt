package app.keepfin.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.LibraryItem
import app.keepfin.ui.Format
import app.keepfin.ui.components.EmptyArt
import app.keepfin.ui.components.EmptyState
import app.keepfin.ui.theme.Studio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object DuplicatesTags {
    const val LIST = "duplicates-list"
    const val REMOVE_ALL = "duplicates-remove-all"
    const val CONFIRM = "duplicates-confirm"
    const val PROGRESS = "duplicates-progress"
    fun group(firstId: String) = "duplicates-group-$firstId"
    fun keep(id: String) = "duplicates-keep-$id"
    fun removeOthers(firstId: String) = "duplicates-remove-$firstId"
}

/**
 * Files that are in the library more than once, exactly the same content (the owner's choice, 2026-10-22): one card per file with its
 * copies, a copy without a numbered name ("Song (2).mp3") chosen to stay, else the oldest, unless another is picked, "Remove the
 * other…" per card and "Remove all extra copies". Removing
 * asks first; collections that list a removed copy get the kept one in its place ([app.keepfin.ui.StudioViewModel.removeDuplicates]).
 */
@Composable
internal fun DuplicatesScreen(ui: LibraryUi, route: LibraryRoute.Duplicates, padding: PaddingValues) {
    val version by ui.browse.changes.collectAsStateWithLifecycle()
    var groups by remember { mutableStateOf<List<List<LibraryItem>>?>(null) }
    LaunchedEffect(route, version) { groups = withContext(Dispatchers.IO) { runCatching { ui.browse.duplicates(route.query, route.ids) }.getOrDefault(emptyList()) } }
    // the copy each card keeps, by the card's first (oldest) id; the oldest stays unless another is chosen
    val kept = remember(route) { mutableStateMapOf<String, String>() }
    var confirm by remember { mutableStateOf<Map<String, String>?>(null) }
    // copies leave the page as soon as their files are gone (a few at a time); a copy that could not be removed stays
    var removed by remember(route) { mutableStateOf(emptySet<String>()) }
    val busy = ui.vm.removingDuplicates || ui.vm.deleting
    val list = groups?.mapNotNull { g -> g.filter { it.id !in removed }.takeIf { it.size > 1 } }
    val extras = list?.sumOf { it.size - 1 } ?: 0
    // the copy that stays unless another is chosen: one whose file name is not numbered ("Song (2).mp3"), else the oldest
    fun keepOf(g: List<LibraryItem>) = kept[g.first().id]?.takeIf { id -> g.any { it.id == id } } ?: (g.firstOrNull { !numberedCopy(it) } ?: g.first()).id
    Column(Modifier.fillMaxSize()) {
        DetailHeader(ui, "Duplicates", list?.let { if (it.isEmpty()) route.title else "${route.title} · $extras extra ${if (extras == 1) "copy" else "copies"}" })
        Box(Modifier.weight(1f).fillMaxWidth()) {
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp) }
            list.isEmpty() -> EmptyState(EmptyArt.LIBRARY, "No duplicates", "Nothing here is the same file twice.")
            else -> LazyColumn(Modifier.fillMaxSize().testTag(DuplicatesTags.LIST), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item("intro") {
                    Text(
                        "These files are here more than once, byte for byte the same. Choose the copy to keep; the others are deleted from the Keepfin folder, and playlists keep the song.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(list, key = { it.first().id }) { group ->
                    val keep = keepOf(group)
                    DuplicateCard(group, keep, enabled = !busy, onKeep = { kept[group.first().id] = it }, onRemoveOthers = { confirm = group.filter { it.id != keep }.associate { it.id to keep } })
                }
            }
        }
        }
        // one tap for everything, always in reach (it used to sit below the last card)
        if (!list.isNullOrEmpty() || busy) Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { list?.let { l -> confirm = l.flatMap { g -> val keep = keepOf(g); g.filter { it.id != keep }.map { it.id to keep } }.toMap() } },
                enabled = !busy && !list.isNullOrEmpty(),
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 8.dp).heightIn(min = 48.dp).testTag(DuplicatesTags.REMOVE_ALL),
            ) {
                if (busy) {
                    val p = ui.vm.duplicatesProgress
                    if (p != null && p.second > 0) CircularProgressIndicator(progress = { p.first.toFloat() / p.second }, modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.foundation.layout.Spacer(Modifier.size(10.dp))
                    Text(if (p != null) "Removing ${p.first} of ${p.second}…" else "Removing…", modifier = Modifier.testTag(DuplicatesTags.PROGRESS))
                } else Text("Remove all extra copies ($extras)")
            }
        }
    }
    confirm?.let { plan ->
        val n = plan.size
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (n == 1) "Remove 1 extra copy?" else "Remove $n extra copies?") },
            text = { Text("${if (n == 1) "The file is" else "The files are"} deleted from the Keepfin folder. The copy you chose stays, with its place in your playlists.") },
            confirmButton = { TextButton(onClick = { confirm = null; ui.vm.libraryActions.removeDuplicates(plan) { gone -> removed = removed + gone } }, modifier = Modifier.testTag(DuplicatesTags.CONFIRM)) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

private const val MAX_COPY_ROWS = 20

private val NUMBERED = Regex("""\(\d+\)\.[^./]+$""")

/** A copy made under a numbered name ("Song (2).mp3"), from its file address. */
internal fun numberedCopy(item: LibraryItem): Boolean = NUMBERED.containsMatchIn(android.net.Uri.decode(item.uri))

@Composable
private fun DuplicateCard(group: List<LibraryItem>, keep: String, enabled: Boolean, onKeep: (String) -> Unit, onRemoveOthers: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val head = group.first()
    Surface(shape = MaterialTheme.shapes.large, color = Studio.colors.raised, modifier = Modifier.fillMaxWidth().testTag(DuplicatesTags.group(head.id))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ItemArt(head, Modifier.size(44.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(head.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                    Text("${group.size} copies · ${Format.bytes(head.size)} each", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
            // a file copied hundreds of times would make one huge card: the first copies are listed, the rest counted
            val shown = group.take(MAX_COPY_ROWS).let { first -> if (first.any { it.id == keep }) first else first + group.filter { it.id == keep } }
            shown.forEachIndexed { i, copy ->
                if (i > 0) HorizontalDivider(color = Studio.colors.separator)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = copy.id == keep, role = Role.RadioButton) { onKeep(copy.id) }.testTag(DuplicatesTags.keep(copy.id)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = copy.id == keep, onClick = null)
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        // the file name tells copies apart ("Song (2).mp3"); they are often in the same folder
                        Text(android.net.Uri.decode(copy.uri).substringAfterLast('/').substringAfterLast(':'), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(copy.folder.ifBlank { "Top of the space" }, "added ${java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(copy.addedAt))}", if (copy.id == keep) "stays" else "removed").joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
            if (group.size > shown.size) Text("and ${group.size - shown.size} more copies, removed with the others", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            OutlinedButton(onClick = onRemoveOthers, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).align(Alignment.End).testTag(DuplicatesTags.removeOthers(head.id))) {
                Text(if (group.size == 2) "Remove the other copy" else "Remove the other ${group.size - 1}")
            }
        }
    }
}
