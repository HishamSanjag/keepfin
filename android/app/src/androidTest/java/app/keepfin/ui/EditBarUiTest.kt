package app.keepfin.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.core.layout.Page
import app.keepfin.ui.media.EditBar
import app.keepfin.ui.media.PageEditor
import app.keepfin.ui.theme.KeepfinTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** The bar of edit mode: the Text choices show whole on a 360dp phone, also with larger text. */
@RunWith(AndroidJUnit4::class)
class EditBarUiTest {
    @get:Rule val rule = createComposeRule()

    private fun check(scale: Float) {
        val prefs = UiPreferences(ApplicationProvider.getApplicationContext(), "ui-test-" + UUID.randomUUID())
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                KeepfinTheme { Box(Modifier.width(360.dp)) { EditBar(prefs, PageEditor(Page.Home, prefs)) } }
            }
        }
        for ((wire, words) in listOf("full" to listOf("Title", "and", "info"), "title" to listOf("Title"), "none" to listOf("None"))) {
            val node = rule.onNodeWithTag("card-text-label-$wire", useUnmergedTree = true).fetchSemanticsNode()
            val results = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            val layout = results.first()
            // centred text reports a wider paragraph than its lines, so height overflow and whole words are checked instead of hasVisualOverflow
            assertFalse("$wire is cut off at font scale $scale", layout.didOverflowHeight)
            // no word is broken in the middle: every line holds whole words
            assertEquals(words, (0 until layout.lineCount).flatMap { layout.layoutInput.text.text.substring(layout.getLineStart(it), layout.getLineEnd(it)).trim().split(" ") })
        }
    }

    @Test fun textChoicesAreWholeAtNormalSize() = check(1f)
    @Test fun textChoicesAreWholeAtLargerText() = check(1.3f)
}
