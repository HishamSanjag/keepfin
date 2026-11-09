package app.keepfin.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.Job
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.ui.Destination
import app.keepfin.ui.Format
import app.keepfin.ui.SettingsButton
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.activity.ActivityBell
import app.keepfin.ui.components.EmptyArt
import app.keepfin.ui.components.EmptyState
import app.keepfin.ui.components.FilterPill
import app.keepfin.ui.components.ScreenTitle
import app.keepfin.ui.library.musicVideoShelf
import app.keepfin.ui.library.ArtTile
import app.keepfin.ui.library.HomeSection
import app.keepfin.ui.library.LibraryRoute
import app.keepfin.ui.library.LibrarySheet
import app.keepfin.ui.library.LibraryUi
import app.keepfin.ui.library.LibraryViews
import app.keepfin.ItemQuery
import app.keepfin.ui.library.rememberBrowsed
import app.keepfin.ui.library.rememberCount
import app.keepfin.ui.library.ShelfCard
import app.keepfin.ui.library.ContinueCard
import app.keepfin.ui.library.itemShelf
import app.keepfin.ui.library.artModel
import app.keepfin.ui.library.collectionRoute
import app.keepfin.ui.library.shelf
import app.keepfin.ui.theme.tabular
import java.util.Calendar

/**
 * Home's shelves: a few rows per shelf from the index (off the main thread), never the whole library; the shapes are HomeLogic's.
 * Null until the first read. The app shell keeps this read running while another tab shows, so Home opens already filled.
 */
@Composable
internal fun rememberHomeShelves(vm: StudioViewModel, ui: LibraryUi): androidx.compose.runtime.State<HomeShelves?> {
    val snapshot by vm.lib.library.collectAsStateWithLifecycle()
    val kind = HomeKind.of(vm.prefs.homeFilter)
    val musicSpaces = vm.musicSpaceIds().toSet()
    return rememberBrowsed(ui.browse, Triple(kind, snapshot.collections, musicSpaces), null) { b -> HomeShelves.read(b, snapshot.collections, kind, System.currentTimeMillis(), musicSpaces) }
}

private fun greeting(hour: Int) = when (hour) { in 5..11 -> "Good morning"; in 12..17 -> "Good afternoon"; in 18..22 -> "Good evening"; else -> "Hello" }

/**
 * Home (full mode): the paste/search box, the All / Videos / Music / Photos chips, then rows that each hide when empty: a quick
 * grid, Continue watching, Made from your listening, Downloading now and Recently added. Videos, music and photos have equal
 * weight; a new library shows one friendly empty state under the box.
 */
@Composable
fun HomeTab(vm: StudioViewModel, ui: LibraryUi, contentPadding: PaddingValues, modifier: Modifier = Modifier) {
    val snapshot by vm.lib.library.collectAsStateWithLifecycle()
    val jobs by vm.engine.jobs.collectAsStateWithLifecycle()
    val kind = HomeKind.of(vm.prefs.homeFilter)
    val read by rememberHomeShelves(vm, ui)
    val shelves = read ?: HomeShelves.EMPTY
    val total by app.keepfin.ui.library.rememberTotal(ui.browse)
    val quick = shelves.quick
    val resume = shelves.resume
    val listening = shelves.listening
    val recent = shelves.recent
    // a song's music video is a video: it shows in the Videos filter, not in All
    val musicVideos by app.keepfin.ui.library.rememberMusicVideos(ui.browse, limit = if (kind == HomeKind.VIDEOS) 500 else 0)
    val active by remember(jobs) { derivedStateOf { jobs.filter { it.status.active }.take(3) } }
    val activeCount by remember(jobs) { derivedStateOf { jobs.count { it.status.active } } }
    val greet = remember { greeting(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    // Until the first read finishes there is nothing to say yet: no empty state that the real shelves then replace.
    val loading = read == null || total == null || (snapshot.scanning && total == 0 && snapshot.scannedAt == null)
    val nothing = !loading && quick.isEmpty() && resume.isEmpty() && listening.isEmpty() && recent.isEmpty() && active.isEmpty() && musicVideos.isEmpty()
    val bottom = contentPadding.calculateBottomPadding()
    val followState by vm.following.state.collectAsStateWithLifecycle()
    var allMixes by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }

    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    Column(modifier.fillMaxSize()) {
    // pinned: the title stays while the page scrolls, and its search button appears once the box below has scrolled away
    app.keepfin.ui.TabHeader(vm, greet, listState, Modifier.testTag(UiTags.HOME_SCREEN), searchAfter = 0)
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(UiTags.HOME_LIST), state = listState, contentPadding = PaddingValues(bottom = bottom)) {
        item("box", contentType = "box") { BigBox(vm, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        item("chips", contentType = "chips") {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                items(HomeKind.entries, key = { it.wire }) { option ->
                    FilterPill(option.label, option == kind, { vm.prefs.chooseHomeFilter(option.wire) }, Modifier.testTag(UiTags.homeChip(option.wire)))
                }
            }
        }
        if (quick.isNotEmpty()) item("quick", contentType = "quick") { QuickGrid(quick, ui, vm, Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(UiTags.HOME_GRID)) }
        if (resume.isNotEmpty()) shelf("continue", "Continue watching", { vm.openLibraryDeep(LibraryRoute.Section(HomeSection.CONTINUE)) }, Modifier.testTag(UiTags.homeRow("continue"))) {
            items(resume.take(12), key = { it.id }) { item -> ContinueCard(item, ui, resume) }
        }
        // Made from your listening (mix tiles), Following and New releases: for the All and Music chips
        if ((kind == HomeKind.ALL || kind == HomeKind.MUSIC) && !loading && (!nothing || followState.artists.isNotEmpty()))
            followSections(vm, listening, shelves.topArtists, showEmpty = !nothing, allMixes = allMixes, onAllMixes = { allMixes = !allMixes })
        if (active.isNotEmpty()) {
            item("head-downloading", contentType = "head") {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp).testTag(UiTags.homeRow("downloading")), verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloading now", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                    app.keepfin.ui.components.RowTextAction(if (activeCount > active.size) "All downloads ($activeCount)" else "All downloads", { vm.navigate(Destination.DOWNLOADS) }, Modifier.testTag("see-all-downloading"))
                }
            }
            items(active, key = { "dl-" + it.id }, contentType = { "download" }) { job -> DownloadingRow(job) { vm.openFromNotification("Downloads", job.id) } }
        }
        musicVideoShelf(musicVideos, ui) { vm.openLibraryDeep(LibraryRoute.MusicVideos) }
        if (recent.isNotEmpty()) itemShelf("recent", "Recently added", recent.take(12), ui, { vm.openLibraryDeep(LibraryRoute.Section(HomeSection.RECENTLY_ADDED)) }, Modifier.testTag(UiTags.homeRow("recent")))
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
    FollowingSheet(vm)
    }
}

/** The quick grid: two columns of 56dp tiles, a video resumes, a song plays, a collection opens. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickGrid(tiles: List<QuickTile>, ui: LibraryUi, vm: StudioViewModel, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { tile -> QuickTileView(tile, ui, vm, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun QuickTileView(tile: QuickTile, ui: LibraryUi, vm: StudioViewModel, modifier: Modifier) {
    val (title, model, seed, kindOf) = when (tile) {
        is QuickTile.Item -> Quad(tile.item.title, tile.item.artModel(), tile.item.album ?: tile.item.title, tile.item.kind)
        is QuickTile.Collection -> Quad(tile.collection.name, tile.cover?.artModel(), tile.collection.name, LibraryItemKind.AUDIO)
    }
    val label = when (tile) {
        is QuickTile.Item -> if (tile.item.kind == LibraryItemKind.IMAGE) "View" else "Play"
        is QuickTile.Collection -> "Open"
    }
    Surface(
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.heightIn(min = 56.dp).testTag("home-quick-${tile.key}"),
    ) {
        Row(
            Modifier.heightIn(min = 56.dp)
                .combinedClickable(
                    role = Role.Button, onClickLabel = label, onLongClickLabel = if (tile is QuickTile.Item) "More actions" else null,
                    onLongClick = if (tile is QuickTile.Item) ({ ui.sheet(LibrarySheet.Actions(tile.item)) }) else null,
                    onClick = {
                        when (tile) {
                            is QuickTile.Item -> vm.play(listOf(tile.item), 0)
                            is QuickTile.Collection -> vm.openLibraryDeep(collectionRoute(tile.collection))
                        }
                    },
                ),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ArtTile(model, seed, kindOf, Modifier.size(56.dp), MaterialTheme.shapes.medium, iconSize = 22.dp)
            Text(
                title, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
        }
    }
}

private data class Quad(val title: String, val model: Any?, val seed: String, val kind: LibraryItemKind)

/** An active transfer: title, a progress bar and what it is doing. Tapping opens Downloads on it. */
@Composable
private fun DownloadingRow(job: Job, onClick: () -> Unit) {
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
