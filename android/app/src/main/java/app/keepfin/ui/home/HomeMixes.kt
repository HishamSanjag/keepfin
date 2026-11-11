package app.keepfin.ui.home

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.keepfin.core.layout.CardKind
import app.keepfin.core.layout.SectionStyle
import app.keepfin.ui.StudioViewModel
import app.keepfin.ui.UiTags
import app.keepfin.ui.library.artModel
import app.keepfin.ui.media.CardActions
import app.keepfin.ui.media.CardMarks
import app.keepfin.ui.media.MediaCard
import app.keepfin.ui.media.MixArt
import app.keepfin.ui.media.SectionEdit
import app.keepfin.ui.media.SectionHead
import app.keepfin.ui.media.emptySection
import app.keepfin.ui.media.section
import app.keepfin.ui.media.sectionHeader

private const val TITLE = "Made from your listening"

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
 * Home's "Made from your listening": mix cards built from the person's own plays, nothing leaves the device. With no mixes yet it explains
 * how they appear, when [explainEmpty] (the Videos and Photos chips and a brand-new library say nothing).
 */
internal fun LazyListScope.mixesSection(vm: StudioViewModel, mixes: List<HomeMix>, style: SectionStyle, edit: SectionEdit?, explainEmpty: Boolean) {
    if (mixes.isNotEmpty()) {
        section("mixes", TITLE, mixes, CardKind.Mix, style, { it.mix.id }, SectionHead(modifier = Modifier.testTag(UiTags.HOME_MIXES)), edit) { MixCard(vm, it) }
    } else if (explainEmpty) {
        sectionHeader("mixes", TITLE, edit = edit)
        item("mixes-empty", contentType = "empty-card") {
            EmptyCard(
                "Nothing played yet",
                "Play a few songs and Keepfin builds On repeat, Forgotten favourites and more from what you play. It all stays on this device.",
                Modifier.testTag(UiTags.HOME_NO_LISTENING),
            )
        }
    } else emptySection("mixes", TITLE, edit)
}

/** A mix: tapping plays it. */
@Composable
private fun MixCard(vm: StudioViewModel, mix: HomeMix) {
    val covers = remember(mix) { mix.songs.filter { it.art != null }.take(4).map { it.artModel() } }
    MediaCard(
        CardKind.Mix, mix.mix.title, mix.mix.blurb, CardActions({ vm.play(mix.songs, 0) }, clickLabel = "Play ${mix.mix.title}"),
        Modifier.testTag(UiTags.homeMix(mix.mix.id)), marks = CardMarks(tag = "MIX"),
    ) { MixArt(covers.ifEmpty { listOfNotNull(mix.songs.firstOrNull()?.artModel()) }, mix.mix.title) }
}
