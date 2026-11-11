package app.keepfin.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.keepfin.Job
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.core.home.ReleaseCard
import app.keepfin.core.layout.CardKind
import app.keepfin.core.layout.SectionPref
import app.keepfin.core.spaces.MusicVideoRows
import app.keepfin.ui.Destination
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.library.ArtTile
import app.keepfin.ui.library.HomeSection
import app.keepfin.ui.library.LibraryRoute
import app.keepfin.ui.library.LibrarySheet
import app.keepfin.ui.library.LibraryUi
import app.keepfin.ui.library.MusicVideoEntry
import app.keepfin.ui.library.MusicVideoCard
import app.keepfin.ui.library.artModel
import app.keepfin.ui.library.collectionRoute
import app.keepfin.ui.media.CardActions
import app.keepfin.ui.media.ItemCard
import app.keepfin.ui.media.MediaCard
import app.keepfin.ui.media.SectionEdit
import app.keepfin.ui.media.SectionHead
import app.keepfin.ui.media.cardKindOf
import app.keepfin.ui.media.dimmed
import app.keepfin.ui.media.emptySection
import app.keepfin.ui.media.section
import app.keepfin.ui.media.sectionHeader

/** Everything Home's sections draw, read once per frame by [HomeTab]. */
internal class HomeContent(
    val vm: StudioViewModel,
    val ui: LibraryUi,
    val kind: HomeKind,
    val shelves: HomeShelves,
    val active: List<Job>,
    val activeCount: Int,
    val musicVideos: List<MusicVideoEntry>,
    val releaseCard: ReleaseCard?,
    val explainMixes: Boolean,
)

private const val ROW_LIMIT = 12

/** One of Home's sections by its id, in the place the saved layout gives it. A section with nothing to show draws nothing. */
internal fun LazyListScope.homeSection(c: HomeContent, pref: SectionPref, edit: SectionEdit?) {
    val recentVideos = c.shelves.recent.filter { it.kind == LibraryItemKind.VIDEO }
    val recentPhotos = c.shelves.recent.filter { it.kind == LibraryItemKind.IMAGE }
    when (pref.id) {
        "downloading" -> downloadingSection(c, edit)
        "jump-back-in" -> section(
            pref.id, "Jump back in", c.shelves.quick, CardKind.Tile, pref.style, { it.key },
            SectionHead(modifier = Modifier.testTag(UiTags.HOME_GRID)), edit,
        ) { QuickTileCard(it, c.ui, c.vm) }
        "continue" -> itemSection(c, pref, edit, "Continue watching", c.shelves.resume, HomeSection.CONTINUE) { item ->
            ItemCard(item, c.ui, overlay = { RemoveFromContinue(item, c.vm) }) { c.vm.play(c.shelves.resume, c.shelves.resume.indexOf(item)) }
        }
        "mixes" -> mixesSection(c.vm, c.shelves.listening, pref.style, edit, c.explainMixes)
        "new-releases" -> if (c.releaseCard != null && (c.kind == HomeKind.ALL || c.kind == HomeKind.MUSIC)) {
            sectionHeader(pref.id, "New from artists you follow", edit = edit)
            item("new-releases", contentType = "new-releases") { Box(edit.dimmed()) { NewReleasesCard(c.vm, c.releaseCard) } }
        } else emptySection(pref.id, "New from artists you follow", edit)
        "added-videos" -> itemSection(c, pref, edit, "New videos", recentVideos, HomeSection.RECENTLY_ADDED)
        "added-music" -> itemSection(c, pref, edit, "New music", c.shelves.recentMusic, HomeSection.RECENTLY_ADDED)
        "music-videos" -> section(
            pref.id, MusicVideoRows.SHELF, c.musicVideos.take(ROW_LIMIT), CardKind.Square, pref.style, { "mv-" + it.row.id },
            SectionHead({ c.vm.openLibraryDeep(LibraryRoute.MusicVideos) }, modifier = Modifier.testTag(UiTags.MUSIC_VIDEO_SHELF)), edit,
        ) { MusicVideoCard(it, c.ui) }
        "photos" -> itemSection(c, pref, edit, "New photos", recentPhotos, HomeSection.RECENTLY_ADDED)
    }
}

/** A section of library items, each opening as a play of the whole row. */
private fun LazyListScope.itemSection(
    c: HomeContent, pref: SectionPref, edit: SectionEdit?, title: String, all: List<LibraryItem>, seeAll: HomeSection,
    card: @Composable (LibraryItem) -> Unit = { item -> ItemCard(item, c.ui) { c.vm.play(all, all.indexOf(item)) } },
) {
    val shown = all.take(ROW_LIMIT)
    if (shown.isEmpty()) {
        emptySection(pref.id, title, edit)
        return
    }
    section(
        pref.id, title, shown, cardKindOf(shown.first()), pref.style, { it.id },
        SectionHead({ c.vm.openLibraryDeep(LibraryRoute.Section(seeAll)) }, modifier = Modifier.testTag(UiTags.homeRow(pref.id))), edit, card = card,
    )
}

private fun LazyListScope.downloadingSection(c: HomeContent, edit: SectionEdit?) {
    if (c.active.isEmpty()) {
        emptySection("downloading", "Downloading now", edit)
        return
    }
    val label = if (c.activeCount > c.active.size) "All downloads (${c.activeCount})" else "All downloads"
    sectionHeader("downloading", "Downloading now", SectionHead({ c.vm.navigate(Destination.DOWNLOADS) }, label, modifier = Modifier.testTag(UiTags.homeRow("downloading"))), edit)
    items(c.active, key = { "dl-" + it.id }, contentType = { "download" }) { job -> DownloadingRow(job) { c.vm.openFromNotification("Downloads", job.id) } }
}

/** A tile of "Jump back in": a video resumes, a song plays, a collection opens. */
@Composable
private fun QuickTileCard(tile: QuickTile, ui: LibraryUi, vm: StudioViewModel) {
    val title: String
    val model: Any?
    val seed: String
    val kind: LibraryItemKind
    val actions: CardActions
    when (tile) {
        is QuickTile.Item -> {
            title = tile.item.title; model = tile.item.artModel(); seed = tile.item.album ?: tile.item.title; kind = tile.item.kind
            actions = CardActions(
                { vm.play(listOf(tile.item), 0) }, if (tile.item.kind == LibraryItemKind.IMAGE) "View" else "Play",
                { ui.sheet(LibrarySheet.Actions(tile.item)) }, "More actions",
            )
        }
        is QuickTile.Collection -> {
            title = tile.collection.name; model = tile.cover?.artModel(); seed = tile.collection.name; kind = LibraryItemKind.AUDIO
            actions = CardActions({ vm.openLibraryDeep(collectionRoute(tile.collection)) }, "Open")
        }
    }
    MediaCard(CardKind.Tile, title, null, actions, Modifier.testTag("home-quick-${tile.key}")) { ArtTile(model, seed, kind, Modifier.fillMaxSize(), iconSize = 22.dp) }
}

/** The cross on a Continue watching card: takes it off the row (a 48 dp target drawn as a small circle in the corner). */
@Composable
private fun BoxScope.RemoveFromContinue(item: LibraryItem, vm: StudioViewModel) {
    IconButton(onClick = { vm.removeFromContinue(item) }, modifier = Modifier.align(Alignment.TopEnd).testTag("continue-remove-${item.id}")) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = .55f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Close, contentDescription = "Remove ${item.title} from Continue watching", tint = Color.White, modifier = Modifier.size(18.dp))
        }
    }
}
