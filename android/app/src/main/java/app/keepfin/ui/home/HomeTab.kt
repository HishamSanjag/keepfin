package app.keepfin.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.Job
import app.keepfin.core.layout.Page
import app.keepfin.ui.Format
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.TabHeader
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.EmptyArt
import app.keepfin.ui.components.EmptyState
import app.keepfin.ui.components.FilterPill
import app.keepfin.ui.library.LibraryUi
import app.keepfin.ui.library.rememberBrowsed
import app.keepfin.ui.library.rememberMusicVideos
import app.keepfin.ui.library.rememberTotal
import app.keepfin.ui.media.EditBar
import app.keepfin.ui.media.EditPageButton
import app.keepfin.ui.media.rememberPageEditor
import app.keepfin.ui.theme.tabular
import java.util.Calendar

/**
 * Home's shelves: a few rows per shelf from the index (off the main thread), never the whole library; the shapes are HomeLogic's.
 * Null until the first read. The app shell keeps this read running while another tab shows, so Home opens already filled.
 */
@Composable
internal fun rememberHomeShelves(vm: StudioViewModel, ui: LibraryUi): State<HomeShelves?> {
    val snapshot by vm.lib.library.collectAsStateWithLifecycle()
    val kind = HomeKind.of(vm.prefs.homeFilter)
    val musicSpaces = vm.musicSpaceIds().toSet()
    return rememberBrowsed(ui.browse, Triple(kind, snapshot.collections, musicSpaces), null) { b -> HomeShelves.read(b, snapshot.collections, kind, System.currentTimeMillis(), musicSpaces) }
}

private fun greeting(hour: Int) = when (hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; in 18..22 -> "Good evening"; else -> "Hello" }

/**
 * Home (full mode): the paste/search box, the All / Videos / Music / Photos chips, then the sections of the saved layout (Edit Home
 * changes which show and in what order), each hiding when empty. Videos, music and photos have equal weight; a new library shows one
 * friendly empty state under the box.
 */
@Composable
fun HomeTab(vm: StudioViewModel, ui: LibraryUi, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val snapshot by vm.lib.library.collectAsStateWithLifecycle()
    val jobs by vm.engine.jobs.collectAsStateWithLifecycle()
    val kind = HomeKind.of(vm.prefs.homeFilter)
    val read by rememberHomeShelves(vm, ui)
    val shelves = read ?: HomeShelves.EMPTY
    val total by rememberTotal(ui.browse)
    // a song's music video is a video: it shows under All and Videos, never Music or Photos
    val musicVideos by rememberMusicVideos(ui.browse, limit = if (kind == HomeKind.ALL || kind == HomeKind.VIDEOS) 500 else 0)
    val active by remember(jobs) { derivedStateOf { jobs.filter { it.status.active }.take(3) } }
    val activeCount by remember(jobs) { derivedStateOf { jobs.count { it.status.active } } }
    val greet = remember { greeting(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    val releaseCard = rememberReleaseCard(vm)
    // Until the first read finishes there is nothing to say yet: no empty state that the real shelves then replace.
    val loading = read == null || total == null || (snapshot.scanning && total == 0 && snapshot.scannedAt == null)
    val nothing = !loading && shelves.quick.isEmpty() && shelves.resume.isEmpty() && shelves.listening.isEmpty() && shelves.recent.isEmpty() &&
        shelves.recentMusic.isEmpty() && active.isEmpty() && musicVideos.isEmpty()
    val content = HomeContent(
        vm, ui, kind, shelves, active, activeCount, musicVideos, releaseCard,
        explainMixes = (kind == HomeKind.ALL || kind == HomeKind.MUSIC) && !loading && !nothing,
    )
    val editor = rememberPageEditor(Page.Home, vm.prefs)

    val listState = rememberLazyListState()
    Column(modifier.fillMaxSize()) {
        // pinned: the title stays while the page scrolls, and its search button appears once the box below has scrolled away
        TabHeader(vm, greet, listState, Modifier.testTag(UiTags.HOME_SCREEN), searchAfter = 0, extra = { EditPageButton(editor, "Home") })
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(UiTags.HOME_LIST), state = listState, contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())) {
            item("box", contentType = "box") { BigBox(vm, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            item("chips", contentType = "chips") {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(HomeKind.entries, key = { it.wire }) { option ->
                        FilterPill(option.label, option == kind, { vm.prefs.chooseHomeFilter(option.wire) }, Modifier.testTag(UiTags.homeChip(option.wire)))
                    }
                }
            }
            editor.sections.forEach { pref -> homeSection(content, pref, editor.edit(pref)) }
            if (nothing) item("empty", contentType = "empty") {
                val (title, body) = when (kind) {
                    HomeKind.ALL -> "Nothing here yet" to "Paste a link above to download your first video, song or photo. Everything you save shows up here."
                    HomeKind.VIDEOS -> "No videos yet" to "Paste a video link above. Videos you save show up here, with Continue watching."
                    HomeKind.MUSIC -> "No music yet" to "Paste a song, album or playlist link above. Your music shows up here."
                    HomeKind.PHOTOS -> "No photos yet" to "Paste a link to a post or an image above. Photos you save show up here."
                }
                EmptyState(art = EmptyArt.LIBRARY, title = title, body = body, modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp).testTag(UiTags.HOME_EMPTY))
            }
        }
        if (editor.editing) EditBar(vm.prefs, editor)
        ReviewSheet(vm)
    }
}

/** An active transfer: title, a progress bar and what it is doing. Tapping opens Downloads on it. */
@Composable
internal fun DownloadingRow(job: Job, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "Open in Downloads", onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag("home-download-${job.id}"),
    ) {
        Text(job.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.size(6.dp))
        val progress = (job.percent / 100f).coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { progress }, modifier = Modifier.fillMaxWidth().heightIn(min = 4.dp).semantics { contentDescription = "Downloaded ${(progress * 100).toInt()} percent" },
            drawStopIndicator = {},
        )
        Text(
            listOfNotNull(job.phase ?: job.status.wire.replaceFirstChar { it.uppercase() }, "${job.percent.toInt()}%", job.speed?.takeIf { it > 0 }?.let { Format.speed(it) }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall.tabular(), color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
        )
    }
}
