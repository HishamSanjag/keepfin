package app.keepfin.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.LibraryItem
import app.keepfin.LibraryItemKind
import app.keepfin.ui.devices.saveNode
import app.keepfin.ui.theme.KeepfinTheme
import app.keepfin.ui.theme.ThemeMode
import app.keepfin.where.ItemPlace
import app.keepfin.where.WhereFilesSource
import app.keepfin.where.WhereFilesUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The item's details gain one line: where the file is. */
@RunWith(AndroidJUnit4::class)
class WhereItemLineUiTest {
    @get:Rule val rule = createComposeRule()

    private class Source(val places: List<ItemPlace>) : WhereFilesSource {
        override val state: StateFlow<WhereFilesUi> = MutableStateFlow(WhereFilesUi())
        override fun placesOf(itemId: String) = places
    }

    private val item = LibraryItem("1", "music", "content://x/1", "", LibraryItemKind.AUDIO, "Road song", artist = "Tester", size = 4_000_000, modifiedAt = 1, addedAt = 1)

    private fun show(places: List<ItemPlace>?, scale: Float = 1f, dark: Boolean = false) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                KeepfinTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) { Box { InfoDialog(places?.let { Source(it) }, item) {} } }
            }
        }
    }

    @Test fun listsEveryPlaceAndMarksTheOfflineOnes() {
        show(listOf(ItemPlace("this phone", false, true), ItemPlace("Desk PC", true), ItemPlace("Google Drive", false)))
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasTestTag("where-item-line")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("where-item-line").assertTextEquals("On: this phone, Desk PC (offline), Google Drive")
    }

    @Test fun saysOnlyOnThisPhoneWhenNoOtherPlaceHasIt() {
        show(listOf(ItemPlace("this phone", false, true)), scale = 1.3f)
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasTestTag("where-item-line")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("where-item-line").assertTextEquals("Only on this phone")
    }

    private fun shot(dark: Boolean) {
        show(listOf(ItemPlace("this phone", false, true), ItemPlace("Desk PC", true), ItemPlace("Google Drive", false)), scale = 1.15f, dark = dark)
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasTestTag("where-item-line")).fetchSemanticsNodes().isNotEmpty() }
        saveNode("where-item-line-" + if (dark) "dark" else "light", rule.onNode(androidx.compose.ui.test.isDialog()))
    }

    @Test fun shotLight() = shot(false)
    @Test fun shotDark() = shot(true)
    @Test fun nothingIsAddedWithoutASource() {
        show(null)
        rule.waitForIdle()
        org.junit.Assert.assertTrue(rule.onAllNodes(androidx.compose.ui.test.hasTestTag("where-item-line")).fetchSemanticsNodes().isEmpty())
    }
}
