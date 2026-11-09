package app.keepfin.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.keepfin.ArtistRelease
import app.keepfin.FollowedArtist
import app.keepfin.FollowingState
import app.keepfin.LibraryItemKind
import app.keepfin.core.home.FeedRelease
import app.keepfin.core.home.ReleaseFeed
import app.keepfin.ui.StudioSheet
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.RowTextAction
import app.keepfin.ui.library.ArtTile
import app.keepfin.ui.library.artModel
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.theme.tabular

/** How many mixes the grid shows before See all. */
private const val MIXES_FIRST = 4

@Composable
private fun SectionHead(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
        action?.invoke()
    }
}

@Composable
private fun EmptyCard(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        action?.invoke()
    }
}

/**
 * Home's "Made from your listening" (mix tiles built from the person's own plays, nothing leaves the device), the Following row and
 * "New releases" (a dated feed with a one-tap Keep). Hidden for the Videos and Photos chips; [showEmpty] false on a brand-new library,
 * which has its own empty state.
 */
internal fun LazyListScope.followSections(vm: StudioViewModel, mixes: List<HomeMix>, topArtists: List<String>, showEmpty: Boolean, allMixes: Boolean, onAllMixes: () -> Unit) {
    if (mixes.isNotEmpty()) {
        item("mixes-head", contentType = "head") {
            SectionHead("Made from your listening") {
                if (mixes.size > MIXES_FIRST) RowTextAction(if (allMixes) "Show less" else "See all", onAllMixes, Modifier.testTag(UiTags.HOME_MIXES_ALL))
            }
        }
        item("mixes", contentType = "mixes") {
            MixGrid(vm, if (allMixes) mixes else mixes.take(MIXES_FIRST), Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(UiTags.HOME_MIXES))
        }
    } else if (showEmpty) {
        item("mixes-empty-head", contentType = "head") { SectionHead("Made from your listening") }
        item("mixes-empty", contentType = "empty-card") {
            EmptyCard(
                "Nothing played yet",
                "Play a few songs and Keepfin builds On repeat, Forgotten favourites and more from what you play. It all stays on this device.",
                Modifier.testTag(UiTags.HOME_NO_LISTENING),
            )
        }
    }
    item("following-section", contentType = "following") { FollowingSection(vm, topArtists, showEmpty) }
}

@Composable
private fun MixGrid(vm: StudioViewModel, mixes: List<HomeMix>, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        mixes.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { MixTile(vm, it, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** A mix: tapping plays it. */
@Composable
private fun MixTile(vm: StudioViewModel, mix: HomeMix, modifier: Modifier) {
    val cover = remember(mix) { mix.songs.firstOrNull { it.art != null }?.artModel() ?: mix.songs.firstOrNull()?.artModel() }
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.heightIn(min = 64.dp).testTag(UiTags.homeMix(mix.mix.id))) {
        Row(
            Modifier.heightIn(min = 64.dp).clickable(role = Role.Button, onClickLabel = "Play ${mix.mix.title}") { vm.play(mix.songs, 0) },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ArtTile(cover, mix.mix.title, LibraryItemKind.AUDIO, Modifier.size(64.dp), MaterialTheme.shapes.medium, iconSize = 22.dp)
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(mix.mix.title, fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(mix.mix.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun FollowingSection(vm: StudioViewModel, topArtists: List<String>, showEmpty: Boolean) {
    val following = vm.following
    val state by following.state.collectAsState()
    val now = remember(state) { System.currentTimeMillis() }
    val withNew = remember(state, now) { ReleaseFeed.artistsWithNew(state.releases.map { it.feed(state) }, now) }
    Column {
        if (state.artists.isNotEmpty()) {
            SectionHead("Following", Modifier.testTag(UiTags.HOME_FOLLOWING)) { RowTextAction("Manage", { following.managing = true }, Modifier.testTag(UiTags.HOME_MANAGE)) }
            val ordered = remember(state, withNew) { state.artists.sortedByDescending { it.key in withNew } }
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(ordered, key = { it.id }) { artist -> FollowingAvatar(artist, artist.key in withNew) { following.managing = true } }
            }
        }
        if (state.artists.isNotEmpty() || showEmpty) SectionHead("New releases")
        if (state.artists.isEmpty()) {
            if (showEmpty) EmptyCard(
                "Follow artists, get their new releases", "Open any artist and press Follow. Nothing downloads on its own: you choose what to keep.",
                Modifier.testTag(UiTags.HOME_NO_FOLLOWING),
            ) {
                if (topArtists.isNotEmpty()) FilledTonalButton(
                    onClick = { topArtists.forEach(following::follow) }, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.HOME_FOLLOW_TOP),
                ) { Text("Follow your top ${topArtists.size} ${if (topArtists.size == 1) "artist" else "artists"}") }
            }
        } else NewReleases(vm, state, now)
    }
}

private fun ArtistRelease.feed(state: FollowingState) = FeedRelease(id, state.artists.firstOrNull { it.id == artistId }?.key ?: "", title, foundAt, kept)

private fun avatarColor(name: String): Color = Color.hsv(((name.hashCode() and 0x7fffffff) % 360).toFloat(), 0.45f, 0.72f)

/** A round avatar with the artist's initial; the dot marks an artist with something in the feed. */
@Composable
internal fun ArtistAvatar(name: String, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(avatarColor(name)), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase(), color = Color.White, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FollowingAvatar(artist: FollowedArtist, hasNew: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.width(72.dp).heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Manage who you follow", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = artist.name + if (hasNew) ", new release" else "" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            ArtistAvatar(artist.name, 52.dp)
            if (hasNew) Box(Modifier.align(Alignment.TopEnd).size(10.dp).clip(CircleShape).background(Studio.colors.accentText))
        }
        Text(artist.name, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun NewReleases(vm: StudioViewModel, state: FollowingState, now: Long) {
    val groups = remember(state, now) { ReleaseFeed.group(state.releases.map { it.feed(state) }, now) }
    val byId = remember(state) { state.releases.associateBy { it.id } }
    val keeping = vm.following.keeping
    Column(Modifier.testTag(UiTags.HOME_RELEASES)) {
        if (groups.isEmpty()) {
            Text(
                "Nothing new yet. Keepfin looks for new releases about once a day.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        groups.forEach { group ->
            Text(
                group.label.uppercase(), fontSize = 11.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp).semantics { heading() },
            )
            group.ids.mapNotNull(byId::get).forEach { release ->
                ReleaseRow(release, state.artists.firstOrNull { it.id == release.artistId }?.name.orEmpty(), now, release.id in keeping) { vm.following.keep(release) }
            }
        }
    }
}

@Composable
private fun ReleaseRow(release: ArtistRelease, artist: String, now: Long, busy: Boolean, onKeep: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 8.dp).testTag("home-release-${release.id}"), verticalAlignment = Alignment.CenterVertically) {
        ArtTile(release.thumbnail, release.title, LibraryItemKind.AUDIO, Modifier.size(56.dp), MaterialTheme.shapes.small, iconSize = 22.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(release.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(artist.takeIf { it.isNotEmpty() }, release.kind, ReleaseFeed.ageWords(release.foundAt, now)).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall.tabular(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        FilledTonalButton(
            onClick = onKeep, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.homeKeep(release.id)),
        ) { Text(if (busy) "Keeping…" else "Keep it", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) }
    }
}


/** The Following manager: who is followed with their state, Unfollow and Check now, Check all, and following a name typed in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowingSheet(vm: StudioViewModel) {
    val following = vm.following
    if (!following.managing) return
    val state by following.state.collectAsState()
    var typed by remember { mutableStateOf("") }
    val now = remember(state) { System.currentTimeMillis() }
    val inFeed = remember(state, now) { ReleaseFeed.group(state.releases.map { it.feed(state) }, now).flatMap { it.ids }.toSet() }
    StudioSheet(onDismissRequest = { following.managing = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag(UiTags.FOLLOWING_SHEET)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (state.artists.isEmpty()) "Following" else "Following · ${state.artists.size}", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                if (state.artists.size > 1) IconButton(onClick = { following.check(null) }, modifier = Modifier.size(48.dp).testTag(UiTags.FOLLOWING_CHECK_ALL)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Check all for new releases")
                }
                OutlinedButton(onClick = { following.managing = false }, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.FOLLOWING_DONE)) { Text("Done") }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    typed, { typed = it }, singleLine = true, placeholder = { Text("Find an artist to follow") },
                    modifier = Modifier.weight(1f).testTag("following-field"),
                )
                TextButton(
                    onClick = { following.follow(typed); typed = "" }, enabled = typed.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp).testTag("following-add"),
                ) { Text("Follow") }
            }
            if (state.artists.isEmpty()) {
                EmptyCard("Nobody yet", "Type an artist above and press Follow, or open an artist and press Follow. Nothing downloads on its own.", Modifier.padding(horizontal = 0.dp))
            }
            state.artists.forEach { artist ->
                val news = state.releases.count { it.artistId == artist.id && it.id in inFeed }
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("following-row-${artist.id}"), verticalAlignment = Alignment.CenterVertically) {
                    ArtistAvatar(artist.name, 44.dp)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(artist.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when {
                                artist.error != null -> artist.error
                                news > 0 -> if (news == 1) "1 new release" else "$news new releases"
                                !artist.checkedOnce -> "Not checked yet"
                                else -> "Up to date"
                            },
                            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = if (artist.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { following.check(artist.id) }, modifier = Modifier.size(48.dp).testTag(UiTags.followingCheck(artist.id))) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Check ${artist.name} now")
                    }
                    OutlinedButton(
                        onClick = { following.unfollow(artist.id) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.followingUnfollow(artist.id)).semantics { contentDescription = "Following ${artist.name}. Tap to unfollow." },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp),
                    ) {
                        Icon(Icons.Filled.Check, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Following", maxLines = 1, softWrap = false)
                    }
                }
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

/** The Follow button of an artist page: an icon button beside Play and Shuffle (they keep their whole labels), a check once followed. */
@Composable
fun androidx.compose.foundation.layout.RowScope.ArtistFollowButton(vm: StudioViewModel, name: String) {
    val state by vm.following.state.collectAsState()
    val following = state.isFollowing(name)
    androidx.compose.material3.OutlinedIconButton(
        onClick = { vm.following.toggle(name) },
        modifier = Modifier.size(48.dp).testTag(UiTags.ARTIST_FOLLOW).semantics { contentDescription = if (following) "Following $name. Tap to unfollow." else "Follow $name" },
    ) { Icon(if (following) Icons.Filled.Check else Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(20.dp)) }
}
