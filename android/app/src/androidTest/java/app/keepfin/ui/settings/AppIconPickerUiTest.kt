package app.keepfin.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.AppIconCatalog
import app.keepfin.AppIconChoice
import app.keepfin.AppIconController
import app.keepfin.AppIcons
import app.keepfin.ui.theme.KeepfinTheme
import app.keepfin.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The App icon picker in Settings, Appearance: lists every generated design, selection persists, help text. */
@RunWith(AndroidJUnit4::class)
class AppIconPickerUiTest {
    @get:Rule val rule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val catalog by lazy { AppIconCatalog.parse(context.assets.open("app-icons.json").bufferedReader().use { it.readText() }) }

    private class Recorder(override val catalog: AppIconCatalog) : AppIconController {
        var now = catalog.defaultChoice
        val chosen = mutableListOf<AppIconChoice>()
        override fun current() = now
        override fun choose(choice: AppIconChoice): Boolean { chosen += choice; now = choice; return true }
    }

    @Test fun rendersEveryGeneratedDesignWithTheHelpTextAndSelectionMovesWhenPicked() {
        val fake = Recorder(catalog)
        rule.setContent { KeepfinTheme(mode = ThemeMode.entries.first(), dynamicColor = false, pureBlack = false) { AppIconPicker(fake) } }
        rule.onNodeWithText("App icon").assertIsDisplayed()
        rule.onNodeWithText(APP_ICON_HELP).assertIsDisplayed()
        rule.onNodeWithText(APP_ICON_NOTE).assertExists()
        for (variant in catalog.variants) rule.onNodeWithTag(AppIconTags.option(variant.id)).assertExists()
        rule.onNodeWithTag(AppIconTags.option(catalog.mainId)).assertIsSelected()
        val other = catalog.variants.first { it.id != catalog.mainId }
        rule.onNodeWithTag(AppIconTags.option(other.id)).performClick()
        rule.waitForIdle()
        assertEquals(listOf(other.id), fake.chosen.map { it.id })
        rule.onNodeWithTag(AppIconTags.option(other.id)).assertIsSelected()
        rule.onNodeWithTag(AppIconTags.option(catalog.mainId)).assertIsNotSelected()
    }

    @Test fun staysUsableAtDoubleFontScale() {
        val fake = Recorder(catalog)
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                KeepfinTheme(mode = ThemeMode.entries.first(), dynamicColor = false, pureBlack = false) { AppIconPicker(fake) }
            }
        }
        for (variant in catalog.variants) rule.onNodeWithTag(AppIconTags.option(variant.id)).assertExists()
        rule.onNodeWithText(APP_ICON_HELP).assertExists()
    }

    @Test fun theRealControllerRemembersTheChoiceAndRestoresIt() {
        val icons = AppIcons.load(context)!!
        val before = icons.current()
        try {
            val other = icons.catalog.variants.first { it.id != icons.catalog.mainId }
            assertTrue(icons.choose(AppIconChoice(other.id, icons.catalog.accent)))
            assertEquals(other.id, AppIcons.load(context)!!.current().id)
        } finally { icons.choose(before) }
    }
}
