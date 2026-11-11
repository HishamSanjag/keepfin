package app.keepfin.ui.media

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.keepfin.core.layout.CardKind
import app.keepfin.core.layout.SectionStyle
import app.keepfin.ui.UiTags
import app.keepfin.ui.components.RowTextAction

/** A grid section wraps to at most this many rows, then offers See all. */
private const val GRID_ROWS = 3

private const val DIMMED = .45f

/** A hidden section, drawn only while editing, is dimmed. */
internal fun SectionEdit?.dimmed(): Modifier = if (this?.visible == false) Modifier.alpha(DIMMED) else Modifier

/** What a section's header says besides its title: the See all action (its label and tag) and a small badge ("3 new"). */
class SectionHead(
    val onSeeAll: (() -> Unit)? = null,
    val seeAllLabel: String = "See all",
    val seeAllTag: String? = null,
    val badge: String? = null,
    val modifier: Modifier = Modifier,
)

/** The header of a Home, Library or Search section: its title, a badge, See all, and in edit mode the section's tools in place of See all. */
internal fun LazyListScope.sectionHeader(key: String, title: String, head: SectionHead = SectionHead(), edit: SectionEdit? = null) {
    item("head-$key", contentType = "head") {
        Row(
            Modifier.fillMaxWidth().then(edit.dimmed()).padding(start = 16.dp, end = 8.dp, top = 20.dp).then(head.modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            if (head.badge != null) Text(
                head.badge, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(start = 8.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp).testTag(UiTags.LIB_NEW_BADGE),
            )
            Spacer(Modifier.weight(1f))
            if (edit != null) SectionTools(title, edit)
            else if (head.onSeeAll != null) RowTextAction(head.seeAllLabel, head.onSeeAll, Modifier.testTag(head.seeAllTag ?: "see-all-$key"))
        }
    }
}

/** The eye (show or hide), Row or Grid (where the section allows it) and the menu with Move up and Move down; each a 48 dp target. */
@Composable
private fun SectionTools(title: String, edit: SectionEdit) {
    IconButton(onClick = edit.onToggle) {
        if (edit.visible) Icon(Icons.Outlined.Visibility, contentDescription = "Hide $title") else Icon(Icons.Outlined.VisibilityOff, contentDescription = "Show $title")
    }
    if (edit.canStyle) {
        val row = edit.style == SectionStyle.Row
        IconButton(onClick = { edit.onStyle(if (row) SectionStyle.Grid else SectionStyle.Row) }) {
            Icon(if (row) Icons.Outlined.GridView else Icons.Outlined.ViewCarousel, contentDescription = if (row) "Show $title as a grid" else "Show $title as a row")
        }
    }
    var menu by remember { mutableStateOf(false) }
    IconButton(onClick = { menu = true }) {
        Icon(Icons.Outlined.MoreVert, contentDescription = "More for $title")
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("Move up") }, enabled = edit.canMoveUp, onClick = { menu = false; edit.onMove(-1) })
            DropdownMenuItem(text = { Text("Move down") }, enabled = edit.canMoveDown, onClick = { menu = false; edit.onMove(1) })
        }
    }
}

/** An empty section draws nothing, except while editing: its header with the edit tools and a muted line, so it can still be moved or hidden. */
internal fun LazyListScope.emptySection(key: String, title: String, edit: SectionEdit?) {
    if (edit == null) return
    sectionHeader(key, title, edit = edit)
    item("empty-$key", contentType = "empty-note") {
        Text(
            "Nothing to show here yet. You can still move or hide it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = edit.dimmed().padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * A section of cards: the [sectionHeader] and a body that is one sideways row (snapping, no scrolling into empty space) or a grid that
 * wraps to at most three rows, then See all. A grid with nowhere to go for See all opens in place instead. [extra] ends the cards.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
internal fun <T> LazyListScope.section(
    key: String,
    title: String,
    items: List<T>,
    kind: CardKind,
    style: SectionStyle,
    itemKey: (T) -> Any,
    head: SectionHead = SectionHead(),
    edit: SectionEdit? = null,
    extra: (@Composable () -> Unit)? = null,
    card: @Composable (T) -> Unit,
) {
    if (items.isEmpty() && extra == null) {
        emptySection(key, title, edit)
        return
    }
    sectionHeader(key, title, head, edit)
    item("body-$key", contentType = style) {
        val dim = edit.dimmed()
        if (style == SectionStyle.Row) {
            val state = rememberLazyListState()
            LazyRow(
                dim, state = state, flingBehavior = rememberSnapFlingBehavior(state),
                contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(CardGap),
            ) {
                items(items, key = itemKey) { card(it) }
                if (extra != null) item("extra-$key") { extra() }
            }
        } else BoxWithConstraints(dim.fillMaxWidth().padding(horizontal = 16.dp)) {
            val perRow = cardsPerRow(maxWidth.value, rememberCardWidth(kind).value, CardGap.value)
            var open by rememberSaveable(key) { mutableStateOf(false) }
            val limit = perRow * GRID_ROWS
            val shown = if (open) items else items.take(limit)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(CardGap), verticalArrangement = Arrangement.spacedBy(CardGap)) {
                    shown.forEach { key(itemKey(it)) { card(it) } }
                    extra?.invoke()
                }
                if (head.onSeeAll == null && items.size > limit) RowTextAction(if (open) "Show less" else "See all", { open = !open }, Modifier.testTag("expand-$key"))
            }
        }
    }
}
