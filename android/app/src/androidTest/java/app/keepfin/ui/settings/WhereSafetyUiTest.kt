package app.keepfin.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.ui.theme.KeepfinTheme
import app.keepfin.ui.theme.ThemeMode
import app.keepfin.where.PlaceKind
import app.keepfin.where.SingleKind
import app.keepfin.where.WhereFilesUi
import app.keepfin.where.WherePlace
import app.keepfin.where.WhereSingle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** GitHub #27: Settings > Storage leads with the shared core's one sentence about where the library is kept, and Keep a copy… starts Add a copy. */
@RunWith(AndroidJUnit4::class)
class WhereSafetyUiTest {
    @get:Rule val rule = createComposeRule()

    private val here = WherePlace("this", PlaceKind.THIS_DEVICE, "android", "This phone", "Everything", 1_000_000, "up to date", null)
    private val copy = WherePlace("copy:c1", PlaceKind.DRIVE, null, "Backup card", "Everything", 900_000, "up to date", null)

    private fun show(ui: WhereFilesUi, onKeep: (Set<String>) -> Unit) {
        rule.setContent { KeepfinTheme(mode = ThemeMode.LIGHT) { Column(Modifier.verticalScroll(rememberScrollState())) { WhereFilesGroup(ui, {}, onKeep, safety = true) } } }
    }

    @Test fun aLibraryOnlyOnThePhoneSaysSoAndKeepACopyHasA48dpTarget() {
        var kept: Set<String>? = null
        show(WhereFilesUi(true, listOf(here), emptyList(), onePlaceOnly = true, files = 20)) { kept = it }
        rule.onNodeWithTag(WhereTags.SAFETY).performScrollTo().assertExists()
        rule.onNodeWithTag(WhereTags.SAFETY).assertTextContainsAll("Your library is only on this phone")
        rule.onNodeWithTag(WhereTags.SAFETY_KEEP).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(emptySet<String>(), kept)
        // the older per-kind lines are replaced by the sentence
        rule.onNodeWithTag(WhereTags.ONE_PLACE).assertDoesNotExist()
    }

    @Test fun filesWithOneCopyOfferKeepACopyWithTheirSpaces() {
        var kept: Set<String>? = null
        show(WhereFilesUi(true, listOf(here, copy), listOf(WhereSingle(SingleKind.SONGS, 3, "this phone", true, setOf("music"))), files = 50)) { kept = it }
        rule.onNodeWithTag(WhereTags.SAFETY).assertTextContainsAll("3 files have only one copy")
        rule.onNodeWithTag(WhereTags.single(0)).assertDoesNotExist()
        rule.onNodeWithTag(WhereTags.SAFETY_KEEP).performScrollTo().performClick()
        assertEquals(setOf("music"), kept)
    }

    @Test fun everyFileInTwoPlacesNeedsNoButton() {
        show(WhereFilesUi(true, listOf(here, copy), emptyList(), files = 50)) {}
        rule.onNodeWithTag(WhereTags.SAFETY).assertTextContainsAll("Your library is in 2 places")
        rule.onNodeWithTag(WhereTags.SAFETY_KEEP).assertDoesNotExist()
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTextContainsAll(vararg parts: String) {
        val text = fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
        for (p in parts) org.junit.Assert.assertTrue("\"$text\" should contain \"$p\"", text.contains(p))
    }
}
