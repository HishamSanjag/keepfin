package app.keepfin.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.ui.theme.ThemeMode
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device screenshots of the Activity centre. Run with `-Pandroid.testInstrumentationRunnerArguments.screenshots=true`; files land in /data/local/tmp/activity-*.png. */
@RunWith(AndroidJUnit4::class)
class ActivityScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Before fun only() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        StudioEngine.override = null
    }

    private fun shot(name: String) {
        Thread.sleep(900)
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/activity-$name.png").close()
        Thread.sleep(600)
    }

    private fun harness(scene: String, dark: Boolean) = Harness(scene).also { it.prefs.chooseTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) }

    @Test fun topBarLight() = topBar(false)
    @Test fun topBarDark() = topBar(true)
    @Test fun pageLight() = page(false)
    @Test fun pageDark() = page(true)
    @Test fun problemsLight() = problems(false)
    @Test fun problemsDark() = problems(true)
    @Test fun emptyLight() = empty(false)
    @Test fun emptyDark() = empty(true)
    @Test fun awayLight() = away(false)
    @Test fun awayDark() = away(true)

    private fun mode(dark: Boolean) = if (dark) "dark" else "light"

    private fun topBar(dark: Boolean) {
        val h = harness("activity", dark)
        h.show(rule)
        rule.waitForTag(UiTags.ACTIVITY_BADGE)
        shot("topbar-${mode(dark)}")
    }

    private fun page(dark: Boolean) {
        val h = harness("activity", dark)
        h.vm.openActivity()
        h.show(rule)
        rule.waitForTag(UiTags.activityEntry("a-signin"))
        shot("page-${mode(dark)}")
    }

    private fun problems(dark: Boolean) {
        val h = harness("activity", dark)
        h.vm.openActivity()
        h.show(rule)
        rule.waitForTag(UiTags.activityChip("problems"))
        rule.onNodeWithTag(UiTags.activityChip("problems")).performClick()
        rule.waitForTag(UiTags.activityEntry("a-failed"))
        shot("problems-${mode(dark)}")
    }

    private fun empty(dark: Boolean) {
        val h = harness("empty", dark)
        h.vm.openActivity()
        h.show(rule)
        rule.waitForTag(UiTags.ACTIVITY_EMPTY)
        shot("empty-${mode(dark)}")
    }

    private fun away(dark: Boolean) {
        val h = harness("activity-away", dark)
        h.show(rule)
        rule.waitUntil(10_000) { rule.hasText("happened while you were away") }
        shot("away-${mode(dark)}")
    }
}
