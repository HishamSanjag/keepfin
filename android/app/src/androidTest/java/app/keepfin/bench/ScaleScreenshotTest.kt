package app.keepfin.bench

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.ui.Destination
import app.keepfin.ui.Harness
import app.keepfin.ui.StudioEngine
import app.keepfin.ui.UiTags
import app.keepfin.ui.theme.ThemeMode
import app.keepfin.ui.waitForTag
import java.io.File
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screenshots of the paged Library and Search on the 200,000-item library. Run with `-Pandroid.testInstrumentationRunnerArguments.screenshots=true`
 * after ScaleBenchmarkTest.a_generate; files land in /data/local/tmp/paged-*.png.
 */
@RunWith(AndroidJUnit4::class)
class ScaleScreenshotTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "bench-$BENCH_ITEMS")
    private var stack: BenchStack? = null

    @After fun tearDown() { stack?.close(); StudioEngine.override = null }

    private fun shot(name: String) {
        Thread.sleep(1200)
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/paged-$name.png").close()
        Thread.sleep(700)
    }

    private fun open(dark: Boolean): Harness {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        check(File(root, "generated").exists()) { "run ScaleBenchmarkTest.a_generate first" }
        val s = BenchStack(root).open().also { stack = it }
        val h = Harness("library")
        h.prefs.chooseTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        h.engine.fakeLibrary.browseOverride = s.repo.browse
        return h
    }

    private fun library(dark: Boolean) {
        val h = open(dark)
        h.show(rule)
        rule.onNodeWithTag(UiTags.nav(Destination.LIBRARY)).performClick()
        rule.waitForTag(UiTags.spaceCard("music"), 60_000)
        shot("library-home-${if (dark) "dark" else "light"}")
        rule.onNodeWithTag(UiTags.spaceCard("music")).performClick()
        rule.waitForTag(UiTags.LIB_LIST, 60_000)
        shot("library-songs-${if (dark) "dark" else "light"}")
        repeat(40) { rule.onNodeWithTag(UiTags.LIB_LIST).performTouchInput { swipeUp(startY = height * 0.85f, endY = height * 0.15f, durationMillis = 120) } }
        shot("library-songs-scrolled-${if (dark) "dark" else "light"}")
    }

    private fun search(dark: Boolean) {
        val h = open(dark)
        h.show(rule)
        rule.onNodeWithTag(UiTags.nav(Destination.SEARCH)).performClick()
        rule.waitForTag(UiTags.SEARCH_FIELD, 60_000)
        rule.onNodeWithTag(UiTags.SEARCH_FIELD).performTextInput("night")
        rule.waitForTag(UiTags.SEARCH_LIBRARY, 60_000)
        shot("search-${if (dark) "dark" else "light"}")
    }

    @Test fun libraryLight() = library(false)
    @Test fun libraryDark() = library(true)
    @Test fun searchLight() = search(false)
    @Test fun searchDark() = search(true)
}
