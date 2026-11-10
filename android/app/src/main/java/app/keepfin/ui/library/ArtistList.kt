package app.keepfin.ui.library

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.keepfin.ArtistGroup
import app.keepfin.ItemQuery
import app.keepfin.LibraryItemKind
import app.keepfin.Space
import app.keepfin.core.home.ReleaseFeed
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.FilterPill
import app.keepfin.ui.components.RowTextAction
import app.keepfin.ui.home.FollowCard
import app.keepfin.ui.home.FollowToggle
import app.keepfin.ui.theme.Studio

/**
 * Music > Artists (GitHub #51): every artist has a one-tap Follow toggle; the All / Following (n) chips choose what is listed. Following
 * lists the artists you follow, those not in the library yet too, with "Checked ... · Check now" and a calm note for a failed check.
 */
@Composable
internal fun ArtistsView(ui: LibraryUi, space: Space, padding: PaddingValues) {
    val following = ui.vm.following
    val state by following.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            FilterPill("All", !following.onlyFollowing, { following.onlyFollowing = false }, Modifier.testTag(UiTags.ARTIST_FILTER_ALL))
            FilterPill("Following (${state.artists.size})", following.onlyFollowing, { following.onlyFollowing = true }, Modifier.testTag(UiTags.ARTIST_FILTER_FOLLOWING))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (following.onlyFollowing) FollowingArtists(ui, space, padding) else AllArtists(ui, space, padding)
        }
    }
}

@Composable
private fun AllArtists(ui: LibraryUi, space: Space, padding: PaddingValues) {
    val artists = rememberArtists(ui, ItemQuery(spaces = listOf(space.id)))
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    artists.EndWatcher(state)
    LazyColumn(Modifier.fillMaxSize().testTag(UiTags.LIB_LIST), state = state, contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
        items(artists.items, key = { it.key }) { artist -> ArtistRow(ui, space, artist.name, artist.cover?.artModel(), counts(artist), failed = false, inLibrary = true) }
    }
}

/** What a row says under the name: "2 albums · 14 songs". */
private fun counts(artist: ArtistGroup): String =
    listOfNotNull(if (artist.albums == 1) "1 album" else "${artist.albums} albums".takeIf { artist.albums > 0 }, if (artist.songs == 1) "1 song" else "${artist.songs} songs").joinToString(" · ")

@Composable
private fun FollowingArtists(ui: LibraryUi, space: Space, padding: PaddingValues) {
    val following = ui.vm.following
    val state by following.state.collectAsState()
    val now = remember(state) { System.currentTimeMillis() }
    // the artists of this space, read once (a few thousand names at most); those followed are picked out by the shared artist key
    val all by rememberBrowsed(ui.browse, space.id, emptyList<ArtistGroup>()) { it.artists(ItemQuery(spaces = listOf(space.id)), null, 5000) }
    val followed = remember(state) { state.artists.map { it.key }.toSet() }
    val inLibrary = remember(all, followed) { all.filter { ReleaseFeed.artistKey(it.name) in followed } }
    val libraryKeys = remember(inLibrary) { inLibrary.mapNotNull { ReleaseFeed.artistKey(it.name) }.toSet() }
    val notYet = remember(state, libraryKeys) { state.artists.filter { it.key !in libraryKeys } }
    val failed = remember(state) { state.artists.filter { it.error != null }.map { it.key }.toSet() }
    LazyColumn(Modifier.fillMaxSize().testTag(UiTags.LIB_LIST), contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp)) {
        item("checked") {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.artists.isEmpty()) "You are not following anyone yet. Tap + Follow on an artist." else FollowCard.checkedWords(state, now) + " ·",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp),
                )
                if (state.artists.isNotEmpty()) RowTextAction(
                    "Check now", { if (!state.checking) following.check(null) }, Modifier.testTag(UiTags.ARTIST_CHECK_NOW),
                    color = if (state.checking) MaterialTheme.colorScheme.onSurfaceVariant else Studio.colors.accentText,
                )
            }
        }
        items(inLibrary, key = { "in-" + it.key }) { artist ->
            ArtistRow(ui, space, artist.name, artist.cover?.artModel(), counts(artist), failed = ReleaseFeed.artistKey(artist.name) in failed, inLibrary = true)
        }
        items(notYet, key = { "not-" + it.id }) { artist ->
            ArtistRow(ui, space, artist.name, null, "Not in your library yet", failed = artist.key in failed, inLibrary = false)
        }
    }
}

@Composable
private fun ArtistRow(ui: LibraryUi, space: Space, name: String, cover: Any?, subtitle: String, failed: Boolean, inLibrary: Boolean) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = if (LocalCompact.current) 56.dp else 64.dp)
            .then(if (inLibrary) Modifier.clickable { ui.vm.openLibraryDetail(LibraryRoute.Artist(space.id, name)) } else Modifier)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp).testTag("artist-$name"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtTile(cover, name, LibraryItemKind.AUDIO, Modifier.size(if (LocalCompact.current) 44.dp else 52.dp), CircleShape)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (failed) Row(Modifier.testTag(UiTags.artistCheckFailed(name)), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(Studio.colors.warning))
                Spacer(Modifier.width(6.dp))
                Text(FollowCard.CHECK_FAILED_NOTE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        FollowToggle(ui.vm, name)
    }
}
