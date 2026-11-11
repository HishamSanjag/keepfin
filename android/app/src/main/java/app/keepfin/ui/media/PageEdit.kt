package app.keepfin.ui.media

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.keepfin.core.layout.CardPrefs
import app.keepfin.core.layout.CardSizes
import app.keepfin.core.layout.CardText
import app.keepfin.core.layout.Page
import app.keepfin.core.layout.PageLayout
import app.keepfin.core.layout.PageLayouts
import app.keepfin.core.layout.SectionPref
import app.keepfin.core.layout.SectionStyle
import app.keepfin.ui.UiPreferences
import kotlin.math.roundToInt

/** What one section's edit tools can do, handed to its header. Null when the page is not being edited. */
class SectionEdit(
    val visible: Boolean,
    val style: SectionStyle,
    val canStyle: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val onToggle: () -> Unit,
    val onStyle: (SectionStyle) -> Unit,
    val onMove: (delta: Int) -> Unit,
)

/** Edit mode of Home or Library: whether the page is being edited, and the changes to its saved layout. */
@Stable
class PageEditor(private val page: Page, private val prefs: UiPreferences) {
    var editing by mutableStateOf(false)

    val layout: PageLayout get() = if (page == Page.Home) prefs.homeLayout else prefs.libraryLayout

    /** The sections to draw, in order: hidden ones only while editing (dimmed). */
    val sections: List<SectionPref> get() = layout.sections.filter { it.visible || editing }

    private fun save(value: PageLayout) = if (page == Page.Home) prefs.chooseHomeLayout(value) else prefs.chooseLibraryLayout(value)

    fun edit(pref: SectionPref): SectionEdit? {
        if (!editing) return null
        val at = layout.sections.indexOfFirst { it.id == pref.id }
        return SectionEdit(
            visible = pref.visible, style = pref.style, canStyle = PageLayouts.canStyle(page, pref.id),
            canMoveUp = at > 0, canMoveDown = at < layout.sections.lastIndex,
            onToggle = { save(PageLayouts.toggle(layout, pref.id)) },
            onStyle = { save(PageLayouts.setStyle(layout, pref.id, it)) },
            onMove = { save(PageLayouts.step(layout, pref.id, it)) },
        )
    }

    fun reset() {
        save(PageLayouts.defaults(page))
        prefs.chooseCardPrefs(CardPrefs())
    }
}

@Composable
fun rememberPageEditor(page: Page, prefs: UiPreferences): PageEditor {
    val editor = remember(page, prefs) { PageEditor(page, prefs) }
    BackHandler(enabled = editor.editing) { editor.editing = false }
    return editor
}

/** The pencil of a page's top bar: "Edit Home" / "Edit Library" turns edit mode on, and the same button turns it off. */
@Composable
fun RowScope.EditPageButton(editor: PageEditor, name: String) {
    IconButton(onClick = { editor.editing = !editor.editing }, modifier = Modifier.testTag("edit-page")) {
        if (editor.editing) Icon(Icons.Outlined.Check, contentDescription = "Done editing $name")
        else Icon(Icons.Outlined.Edit, contentDescription = "Edit $name")
    }
}

private val TextChoices = listOf(CardText.Full to "Title and info", CardText.Title to "Title", CardText.None to "None")

/** The bar of edit mode, above the bottom navigation: Card size, Text under cards, Reset and Done. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditBar(prefs: UiPreferences, editor: PageEditor, modifier: Modifier = Modifier) {
    val card = prefs.cardPrefs
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth().testTag("edit-bar")) {
        Column {
            HorizontalDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Card size", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(80.dp))
                    Slider(
                        value = card.scale.toFloat(), onValueChange = { prefs.chooseCardPrefs(card.copy(scale = CardSizes.clampScale((it * 100).roundToInt() / 100.0))) },
                        valueRange = CardSizes.MIN_SCALE.toFloat()..CardSizes.MAX_SCALE.toFloat(), steps = 8,
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Card size" }.testTag("card-size"),
                    )
                    Text("${(card.scale * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp).width(44.dp))
                }
                // The label sits above the control so each choice gets a third of the width; a long choice wraps instead of clipping.
                Text("Text", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    TextChoices.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = card.text == value, onClick = { prefs.chooseCardPrefs(card.copy(text = value)) },
                            shape = SegmentedButtonDefaults.itemShape(index, TextChoices.size), icon = {},
                            modifier = Modifier.heightIn(min = 48.dp).testTag("card-text-${value.wire}"),
                        ) { Text(label, maxLines = 2, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, modifier = Modifier.testTag("card-text-label-${value.wire}")) }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = editor::reset, modifier = Modifier.heightIn(min = 48.dp).testTag("edit-reset")) { Text("Reset") }
                    Button(onClick = { editor.editing = false }, modifier = Modifier.heightIn(min = 48.dp).testTag("edit-done")) { Text("Done") }
                }
            }
        }
    }
}
