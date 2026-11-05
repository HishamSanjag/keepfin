package app.keepfin.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.ui.preview.FakeUpdates
import app.keepfin.ui.settings.AttentionItem
import app.keepfin.ui.settings.HomeRow
import app.keepfin.ui.settings.SettingsCategory
import app.keepfin.ui.settings.SettingsGroups
import app.keepfin.ui.settings.SettingsHomeContent
import app.keepfin.ui.settings.SettingsTarget
import app.keepfin.ui.theme.KeepfinTheme
import app.keepfin.ui.theme.ThemeMode
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in screenshots of the Settings screens (-Pandroid.testInstrumentationRunnerArguments.screenshots=true): saved to /data/local/tmp/settings-*.png. */
@RunWith(AndroidJUnit4::class)
class SettingsScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @After fun tearDown() { StudioEngine.override = null }

    private fun shot(name: String, dark: Boolean) {
        Thread.sleep(900)
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/settings-$name-${if (dark) "dark" else "light"}.png").close()
        Thread.sleep(700)
    }

    private fun on() = assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")

    private fun categories(dark: Boolean) {
        on()
        val h = Harness("library", updates = FakeUpdates())
        h.prefs.chooseTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        h.vm.navigate(Destination.SETTINGS)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_OVERVIEW)
        shot("home", dark)
        rule.onNodeWithTag(UiTags.SETTINGS_SEARCH).performClick()
        rule.waitForTag(UiTags.SETTINGS_SEARCH_FIELD)
        rule.onNodeWithTag(UiTags.SETTINGS_SEARCH_FIELD).performTextInput("reach")
        shot("search-reach", dark)
        rule.runOnIdle { h.vm.searchSettings(false) }
        for (category in listOf(SettingsCategory.DOWNLOADS, SettingsCategory.LIBRARY, SettingsCategory.APPEARANCE, SettingsCategory.NOTIFICATIONS, SettingsCategory.ABOUT)) {
            rule.runOnIdle { h.vm.openSettings(SettingsTarget(category)) }
            rule.waitForTag(UiTags.SETTINGS_LIST)
            shot(category.name.lowercase(), dark)
        }
        // the Speed and connections group opened from a search result, with its row lit
        rule.runOnIdle { h.vm.openSettings(SettingsTarget(SettingsCategory.DOWNLOADS, SettingsGroups.SPEED, "setting-turbo")) }
        rule.waitForTag(UiTags.setting("turbo"))
        shot("downloads-speed-open", dark)
    }

    @Test fun categoriesLight() = categories(false)
    @Test fun categoriesDark() = categories(true)

    private fun sevenRows(dark: Boolean) {
        on()
        val rows = listOf(
            HomeRow(SettingsCategory.DOWNLOADS, "Best quality · YouTube Music as audio"),
            HomeRow(SettingsCategory.LIBRARY, "On · 3 spaces · Keepfin player"),
            HomeRow(SettingsCategory.STORAGE, "Internal storage > Keepfin · 412 GB free · 2 copies"),
            HomeRow(SettingsCategory.DEVICES, "Hisham's PC connected"),
            HomeRow(SettingsCategory.APPEARANCE, "System theme · Material You"),
            HomeRow(SettingsCategory.NOTIFICATIONS, "Download alerts on"),
            HomeRow(SettingsCategory.ABOUT, "Keepfin 1.0.0 is available", dot = true),
        )
        val attention = listOf(
            AttentionItem("storage", "The copy “USB drive” is not connected", "Fix", SettingsTarget(SettingsCategory.STORAGE)),
            AttentionItem("update", "Keepfin 1.0.0 is ready to install", "Update", SettingsTarget(SettingsCategory.ABOUT)),
        )
        rule.setContent { KeepfinTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) { SettingsHomeContent(rows, attention, PaddingValues(), {}, {}, {}) } }
        shot("home-seven-with-attention", dark)
    }

    @Test fun sevenRowsLight() = sevenRows(false)
    @Test fun sevenRowsDark() = sevenRows(true)
}
