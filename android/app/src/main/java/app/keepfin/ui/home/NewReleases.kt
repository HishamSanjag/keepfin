package app.keepfin.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.ArtistRelease
import app.keepfin.LibraryItemKind
import app.keepfin.core.home.ReleaseFeed
import app.keepfin.ui.StudioSheet
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.library.ArtTile
import app.keepfin.ui.theme.tabular

/**
 * Home's one slim card (GitHub #51): shown only while the setting is on and artists you follow have releases you have not seen. Review
 * opens the sheet, x marks them seen (the card returns only for newer ones) and "Don't show on Home" turns it off for good. Shows nothing otherwise.
 */
@Composable
internal fun NewReleasesCard(vm: StudioViewModel, modifier: Modifier = Modifier) {
    val state by vm.following.state.collectAsState()
    val settings by vm.engine.settings.collectAsStateWithLifecycle()
    val now = remember(state) { System.currentTimeMillis() }
    val card = remember(state, settings.newReleasesOnHome, now) { FollowCard.card(state, settings.newReleasesOnHome, now) }
    if (!card.show) return
    val byId = remember(state) { state.releases.associateBy { it.id } }
    val names = remember(state, card) { FollowCard.namesLine(FollowCard.names(state, card)) }
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp)).testTag(UiTags.RELEASE_CARD),
    ) {
        Column(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 0.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy((-12).dp)) {
                    card.ids.take(ReleaseFeed.CARD_COVERS).forEach { id ->
                        ArtTile(byId[id]?.thumbnail, byId[id]?.title ?: id, LibraryItemKind.AUDIO, Modifier.size(36.dp).border(2.dp, colors.surfaceContainerHigh, MaterialTheme.shapes.small), MaterialTheme.shapes.small, iconSize = 16.dp)
                    }
                }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(card.title, fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (names.isNotEmpty()) Text(names, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(onClick = { vm.following.reviewing = true }, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.RELEASE_REVIEW)) { Text("Review", maxLines = 1, softWrap = false) }
                IconButton(onClick = vm.following::markSeen, modifier = Modifier.size(48.dp).testTag(UiTags.RELEASE_DISMISS)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Dismiss new releases", modifier = Modifier.size(18.dp))
                }
            }
            TextButton(onClick = vm.following::hideOnHome, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.RELEASE_HIDE)) {
                Text("Don’t show on Home", fontSize = 13.sp, maxLines = 1, color = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ReleaseRow(release: ArtistRelease, artist: String, now: Long, busy: Boolean, onKeep: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(vertical = 8.dp).testTag("home-release-${release.id}"), verticalAlignment = Alignment.CenterVertically) {
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

/** The Review sheet: the unseen releases (This week, Earlier this month) with Keep it each, Mark all seen and Manage who you follow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewSheet(vm: StudioViewModel) {
    val following = vm.following
    if (!following.reviewing) return
    val state by following.state.collectAsState()
    val now = remember(state) { System.currentTimeMillis() }
    val groups = remember(state, now) { FollowCard.review(state, now) }
    val byId = remember(state) { state.releases.associateBy { it.id } }
    val keeping = following.keeping
    StudioSheet(onDismissRequest = { following.reviewing = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag(UiTags.RELEASES_SHEET)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("New releases", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
                OutlinedIconButton(onClick = { following.reviewing = false }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                }
            }
            if (groups.isEmpty()) Text("Nothing new right now.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
            groups.forEach { group ->
                Text(
                    group.label.uppercase(), fontSize = 11.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp).semantics { heading() },
                )
                group.ids.mapNotNull(byId::get).forEach { release ->
                    ReleaseRow(release, state.artists.firstOrNull { it.id == release.artistId }?.name.orEmpty(), now, release.id in keeping) { following.keep(release) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = following::manage, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.REVIEW_MANAGE)) { Text("Manage who you follow", maxLines = 1) }
                OutlinedButton(
                    onClick = { following.reviewing = false; following.markSeen() }, modifier = Modifier.heightIn(min = 48.dp).testTag(UiTags.REVIEW_MARK_ALL),
                ) { Text("Mark all seen", maxLines = 1, softWrap = false) }
            }
        }
    }
}

/** The Follow button of an artist page: an icon button beside Play and Shuffle (they keep their whole labels), a check once followed. */
@Composable
fun RowScope.ArtistFollowButton(vm: StudioViewModel, name: String) {
    val state by vm.following.state.collectAsState()
    val following = state.isFollowing(name)
    OutlinedIconButton(
        onClick = { vm.following.toggle(name) },
        modifier = Modifier.size(48.dp).testTag(UiTags.ARTIST_FOLLOW).semantics { contentDescription = if (following) "Following $name. Tap to unfollow." else "Follow $name" },
    ) { Icon(if (following) Icons.Filled.Check else Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(20.dp)) }
}

/** The one-tap toggle of an artist in the artist list: "+ Follow", or "✓ Following" (tap to unfollow). A 48dp target. */
@Composable
fun FollowToggle(vm: StudioViewModel, name: String, modifier: Modifier = Modifier) {
    val state by vm.following.state.collectAsState()
    val following = state.isFollowing(name)
    val tag = modifier.heightIn(min = 48.dp).width(112.dp).testTag(UiTags.artistToggle(name)).semantics { contentDescription = if (following) "Following $name. Tap to unfollow." else "Follow $name" }
    if (following) FilledTonalButton(onClick = { vm.following.toggle(name) }, modifier = tag, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
        Text("✓ Following", fontSize = 13.sp, maxLines = 1, softWrap = false)
    } else OutlinedButton(onClick = { vm.following.toggle(name) }, modifier = tag, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)) {
        Text("+ Follow", fontSize = 13.sp, maxLines = 1, softWrap = false)
    }
}
