package app.keepfin.ui

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.PlaybackItems
import app.keepfin.ui.library.LibraryRoute
import app.keepfin.ui.settings.SettingsCategory
import app.keepfin.ui.theme.ThemeMode
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 0.5.5 screenshots. Run with `-Pandroid.testInstrumentationRunnerArguments.screenshots=true`; files land in /data/local/tmp/r055-*.png. */
@RunWith(AndroidJUnit4::class)
class RefreshScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Before fun only() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        StudioEngine.override = null
    }

    private fun shot(name: String, dark: Boolean) {
        Thread.sleep(1_000)
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/r055-$name-${if (dark) "dark" else "light"}.png").close()
        Thread.sleep(600)
    }

    private fun harness(scene: String, dark: Boolean) = Harness(scene).also { it.prefs.chooseTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) }
    private val waltz = "content://media/external/audio/media/104"

    @Test fun homeLight() = home(false)
    @Test fun homeDark() = home(true)
    @Test fun searchLight() = search(false)
    @Test fun searchDark() = search(true)
    @Test fun albumLight() = album(false)
    @Test fun albumDark() = album(true)
    @Test fun sheetLight() = sheet(false)
    @Test fun sheetDark() = sheet(true)
    @Test fun playerLight() = player(false)
    @Test fun playerDark() = player(true)
    @Test fun downloaderOnlyLight() = downloaderOnly(false)
    @Test fun downloaderOnlyDark() = downloaderOnly(true)

    private fun home(dark: Boolean) {
        val h = harness("spaces-v2", dark)
        h.vm.navigate(Destination.HOME)
        h.show(rule)
        rule.waitForTag(UiTags.HOME_GRID)
        shot("home", dark)
    }

    private fun search(dark: Boolean) {
        val h = harness("spaces-v2", dark)
        h.vm.navigate(Destination.SEARCH)
        h.show(rule)
        rule.onNodeWithTag(UiTags.SEARCH_FIELD).performTextInput("blender")
        rule.waitForTag(UiTags.SEARCH_LIBRARY)
        shot("search", dark)
    }

    private fun album(dark: Boolean) {
        val h = harness("spaces-v2", dark)
        val key = PlaybackItems.albumKey(h.engine.fakeLibrary.item("content://media/external/audio/media/101")!!)!!
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.runOnIdle { h.vm.openLibraryDeep(LibraryRoute.Album("music", key)) }
        rule.waitForTag(UiTags.HEADER_BAND)
        shot("album", dark)
    }

    private fun sheet(dark: Boolean) {
        val h = harness("spaces-v2", dark)
        h.vm.navigate(Destination.SEARCH)
        h.show(rule)
        rule.onNodeWithTag(UiTags.SEARCH_FIELD).performTextInput("Cosmos")
        rule.waitForTag(UiTags.libMenu(waltz))
        rule.onNodeWithTag(UiTags.libMenu(waltz)).performClick()
        rule.waitForTag(UiTags.ACTION_SHEET)
        shot("actions", dark)
    }

    private fun player(dark: Boolean) {
        val h = harness("spaces-v2", dark)
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.runOnIdle { h.vm.play(listOf(h.engine.fakeLibrary.item("content://media/external/audio/media/103")!!), 0) }
        rule.waitForTag(UiTags.MINI_PLAYER)
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performClick()
        rule.waitForTag(UiTags.FULL_PLAYER)
        shot("player", dark)
    }

    private fun downloaderOnly(dark: Boolean) {
        val h = harness("live", dark)
        h.libraryOff()
        h.show(rule)
        rule.waitForTag(UiTags.nav(Destination.DOWNLOADS))
        shot("downloader-only", dark)
    }
}
