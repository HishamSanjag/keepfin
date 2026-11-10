package app.keepfin.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.keepfin.LibraryItemKind
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.RowTextAction
import app.keepfin.ui.library.ArtTile
import app.keepfin.ui.library.artModel

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
private fun EmptyCard(title: String, body: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, colors.outlineVariant, RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
    }
}

/**
 * Home's "Made from your listening": mix tiles built from the person's own plays, nothing leaves the device. Hidden for the Videos and
 * Photos chips and on a brand-new library, which has its own empty state.
 */
internal fun LazyListScope.mixSections(vm: StudioViewModel, mixes: List<HomeMix>, allMixes: Boolean, onAllMixes: () -> Unit) {
    if (mixes.isNotEmpty()) {
        item("mixes-head", contentType = "head") {
            SectionHead("Made from your listening") {
                if (mixes.size > MIXES_FIRST) RowTextAction(if (allMixes) "Show less" else "See all", onAllMixes, Modifier.testTag(UiTags.HOME_MIXES_ALL))
            }
        }
        item("mixes", contentType = "mixes") {
            MixGrid(vm, if (allMixes) mixes else mixes.take(MIXES_FIRST), Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag(UiTags.HOME_MIXES))
        }
    } else {
        item("mixes-empty-head", contentType = "head") { SectionHead("Made from your listening") }
        item("mixes-empty", contentType = "empty-card") {
            EmptyCard(
                "Nothing played yet",
                "Play a few songs and Keepfin builds On repeat, Forgotten favourites and more from what you play. It all stays on this device.",
                Modifier.testTag(UiTags.HOME_NO_LISTENING),
            )
        }
    }
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
