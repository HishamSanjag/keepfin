package app.keepfin.ui.updates

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.keepfin.ui.settings.ReleaseNotesBody
import app.keepfin.ui.theme.Studio
import app.keepfin.ui.theme.tabular
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object WhatsNewTags {
    const val NEXT = "whats-new-next"
    const val FULL_LIST = "whats-new-full-list"
    const val COUNTER = "whats-new-counter"
    fun dot(index: Int) = "whats-new-dot-$index"
}

/**
 * The pages of What's new: one top change per page, then the full notes. The pager holds the space; the caller sets the
 * height so it stays the same across pages. [onGuide] opens the feature guide and [onDone] closes the sheet.
 */
@Composable
fun WhatsNewPager(
    version: String, pages: List<WhatsNewPage>, notes: String, pictureOf: suspend (String) -> ImageBitmap?,
    state: PagerState, onGuide: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val reduced = Studio.reducedMotion
    val last = pages.size
    val go: (Int) -> Unit = { target -> scope.launch { if (reduced) state.scrollToPage(target) else state.animateScrollToPage(target) } }
    val colors = MaterialTheme.colorScheme
    val onLast = state.currentPage >= last
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "What's new in $version", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Studio.colors.accentText,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Text(
                if (onLast) "Full list" else "${state.currentPage + 1} of $last", fontSize = 13.sp, color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium.tabular(),
                modifier = Modifier.testTag(WhatsNewTags.COUNTER).semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        HorizontalPager(state, Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Top) { index ->
            if (index < last) FeaturePage(pages[index], pictureOf)
            else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Text("Everything in $version", fontSize = 25.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp).semantics { heading() })
                ReleaseNotesBody(notes)
            }
        }
        Dots(state, last, go)
        val primary = ButtonDefaults.buttonColors(containerColor = Studio.colors.action, contentColor = Studio.colors.onAction)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onLast) {
                OutlinedButton(onGuide, Modifier.weight(1f).heightIn(min = 48.dp).testTag(app.keepfin.ui.UiTags.WHATS_NEW_GUIDE)) { Text("See all features", maxLines = 1) }
                Button(onDone, Modifier.weight(1f).heightIn(min = 48.dp).testTag("whats-new-done"), colors = primary) { Text("Got it", maxLines = 1) }
            } else {
                OutlinedButton({ go(last) }, Modifier.weight(1f).heightIn(min = 48.dp).testTag(WhatsNewTags.FULL_LIST)) { Text("See the full list", maxLines = 1) }
                Button({ go(state.currentPage + 1) }, Modifier.weight(1f).heightIn(min = 48.dp).testTag(WhatsNewTags.NEXT), colors = primary) { Text("Next", maxLines = 1) }
            }
        }
    }
}

@Composable
private fun FeaturePage(page: WhatsNewPage, pictureOf: suspend (String) -> ImageBitmap?) {
    val colors = MaterialTheme.colorScheme
    // null while loading, then the picture or Missing; a page without a picture shows no empty frame
    val picture by produceState<Any?>(initialValue = null, page.id) { value = withContext(Dispatchers.IO) { pictureOf(page.id) } ?: Missing }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (picture !== Missing) {
            val shape = RoundedCornerShape(16.dp)
            Box(Modifier.fillMaxWidth().height(236.dp).clip(shape).background(colors.surfaceContainerHigh).border(1.dp, Studio.colors.separator, shape)) {
                (picture as? ImageBitmap)?.let { Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
            }
        }
        Text(page.title, fontSize = 25.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        Text(page.text, fontSize = 15.sp, lineHeight = 22.sp, color = colors.onSurfaceVariant)
    }
}

private object Missing

/** One dot per page and one for the full list; each is a 48dp target. */
@Composable
private fun Dots(state: PagerState, pages: Int, go: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        repeat(pages + 1) { index ->
            val current = state.currentPage == index
            val label = if (index == pages) "Full list" else "Page ${index + 1}"
            Box(
                Modifier.size(48.dp).clickable(onClickLabel = label) { go(index) }.testTag(WhatsNewTags.dot(index))
                    .semantics { contentDescription = label; role = Role.Tab },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.height(8.dp).width(if (current) 22.dp else 8.dp).clip(CircleShape).background(if (current) Studio.colors.action else MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}
