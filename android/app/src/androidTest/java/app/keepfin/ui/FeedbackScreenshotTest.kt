package app.keepfin.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.ui.library.LibraryRoute
import app.keepfin.ui.theme.ThemeMode
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Device screenshots of the beta 2 feedback screens (guide, space "+ Add", Move to space). Run with `-Pandroid.testInstrumentationRunnerArguments.screenshots=true`. */
@RunWith(AndroidJUnit4::class)
class FeedbackScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Before fun only() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        StudioEngine.override = null
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(900)
        // Written by the shell so the pictures survive the test app being uninstalled afterwards.
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/$name.png").close()
        Thread.sleep(600)
    }

    @Test fun light() = screens(false)
    @Test fun dark() = screens(true)

    private fun screens(dark: Boolean) {
        val mode = if (dark) "dark" else "light"
        val h = Harness("spaces-v2")
        h.prefs.chooseTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        h.openSettings(app.keepfin.ui.settings.SettingsCategory.HELP)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_LIST)
        rule.scrollListTo(UiTags.SETTINGS_LIST, UiTags.SETTINGS_GUIDE_ROW)
        shot("fb-settings-guide-row-$mode")
        rule.onNodeWithTag(UiTags.SETTINGS_GUIDE_ROW).performClick()
        rule.waitForTag(UiTags.GUIDE)
        rule.onNodeWithTag(UiTags.guideCard("paste")).performClick()
        rule.onNodeWithTag(UiTags.guideCard("share")).performClick()
        shot("fb-guide-$mode")
        rule.onNodeWithTag(UiTags.GUIDE_SEARCH).performTextInput("playlist")
        shot("fb-guide-search-$mode")
        h.vm.navigate(Destination.LIBRARY)
        h.vm.openLibraryRoot(LibraryRoute.Space("workout"))
        rule.waitForTag(UiTags.LIB_SPACE_ADD)
        shot("fb-space-topbar-add-$mode")
        h.vm.openLibraryRoot(LibraryRoute.Collection("workout", "gym"))
        rule.waitForTag(UiTags.COLLECTION_DETAIL)
        rule.onNodeWithTag("collection-menu").performClick()
        shot("fb-collection-menu-$mode")
        rule.onNodeWithTag("collection-move-space").performClick()
        rule.waitForTag(UiTags.SPACE_PICKER)
        shot("fb-move-to-space-$mode")
    }
}
