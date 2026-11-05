package app.keepfin.ui

import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.PlaybackItems
import app.keepfin.ui.library.LibraryRoute
import app.keepfin.ui.theme.ThemeMode
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device screenshots of the beta 2 changes. Run with `-Pandroid.testInstrumentationRunnerArguments.screenshots=true`; pull /data/local/tmp/b2-*.png. */
@RunWith(AndroidJUnit4::class)
class BetaTwoScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Before fun only() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        StudioEngine.override = null
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(900)
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/b2-$name.png").close()
        Thread.sleep(700)
    }

    @Test fun light() = screens(false)
    @Test fun dark() = screens(true)

    private fun screens(dark: Boolean) {
        val mode = if (dark) "dark" else "light"
        // Downloads: empty, then with transfers.
        run {
            val h = Harness("empty"); h.prefs.chooseTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
            h.vm.navigate(Destination.DOWNLOADS); h.show(rule)
            rule.waitForTag(UiTags.WORKS_WITH); shot("downloads-empty-$mode")
        }
    }

    @Test fun lightRest() = rest(false)
    @Test fun darkRest() = rest(true)

    private fun rest(dark: Boolean) {
        val mode = if (dark) "dark" else "light"
        val h = Harness("library"); h.prefs.chooseTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        val video = "content://media/external/video/media/302"
        val song = "content://media/external/audio/media/104"
        rule.waitForTag(UiTags.LIB_LIST)
        rule.onNodeWithTag(UiTags.LIB_LIST).performScrollToNode(hasTestTag(UiTags.libItem(video)))
        rule.waitForTag(UiTags.libItem(video))
        rule.onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange) and hasAnyDescendant(hasTestTag(UiTags.libItem(video)))).performScrollToNode(hasTestTag(UiTags.libItem(song)))
        shot("recent-boundary-$mode")
        val key = PlaybackItems.albumKey(h.engine.fakeLibrary.item("content://media/external/audio/media/101")!!)!!
        rule.runOnIdle { h.vm.openLibraryDeep(LibraryRoute.Album("music", key)) }
        rule.waitForTag(UiTags.BAND_BAR)
        shot("album-top-$mode")
        rule.onNodeWithTag(UiTags.LIB_LIST).performScrollToIndex(3)
        shot("album-scrolled-$mode")
        rule.runOnIdle { h.vm.back(); h.vm.navigate(Destination.DOWNLOADS) }
        rule.waitForTag(UiTags.WORKS_WITH)
        shot("downloads-transfers-$mode")
    }
    @Test fun lightCollection() = collection(false)
    @Test fun darkCollection() = collection(true)

    private fun collection(dark: Boolean) {
        val mode = if (dark) "dark" else "light"
        val h = Harness("spaces-v2"); h.prefs.chooseTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.runOnIdle { h.vm.openLibraryDeep(LibraryRoute.Collection("workout", "gym")) }
        rule.waitForTag(UiTags.BAND_BAR)
        shot("collection-top-$mode")
        rule.onNodeWithTag(UiTags.LIB_LIST).performScrollToIndex(4)
        shot("collection-scrolled-$mode")
    }

    @Test fun headersLight() = headers(false, 1.0f)
    @Test fun headersDark() = headers(true, 1.0f)
    @Test fun headersLightLarge() = headers(false, 1.3f)
    @Test fun headersDarkLarge() = headers(true, 1.3f)

    private fun headers(dark: Boolean, scale: Float) {
        val mode = (if (dark) "dark" else "light") + (if (scale > 1f) "-x13" else "-x10")
        val h = Harness("spaces-v2"); h.prefs.chooseTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule, fontScale = scale, widthDp = 360)
        val key = PlaybackItems.albumKey(h.engine.fakeLibrary.item("content://media/external/audio/media/101")!!)!!
        val routes = listOf("collection" to LibraryRoute.Collection("workout", "gym"), "album" to LibraryRoute.Album("music", key), "artist" to LibraryRoute.Artist("music", "Blender Studio"))
        for ((name, route) in routes) {
            rule.runOnIdle { h.vm.openLibraryDeep(route) }
            rule.waitForTag(UiTags.LIB_PLAY_ALL)
            shot("header-$name-$mode")
            rule.runOnIdle { h.vm.back() }
        }
    }
}