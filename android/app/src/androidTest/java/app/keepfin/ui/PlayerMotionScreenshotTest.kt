package app.keepfin.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.down
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.up
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.ui.theme.ThemeMode
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Device screenshots of the motion phase 1 player. Run with `-Pandroid.testInstrumentationRunnerArguments.screenshots=true`; needs animations on for the mid-transition frame. */
@RunWith(AndroidJUnit4::class)
class PlayerMotionScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Before fun only() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        StudioEngine.override = null
    }

    // The bars on the playing row never stop animating, so the test clock is driven by hand instead of waiting for idle.
    private fun shot(name: String, advanceMs: Long = 1_000) {
        rule.mainClock.advanceTimeBy(advanceMs)
        Thread.sleep(700)
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/$name.png").close()
        Thread.sleep(600)
    }

    @Test fun light() = screens(false)
    @Test fun dark() = screens(true)

    private fun screens(dark: Boolean) {
        val mode = if (dark) "dark" else "light"
        val h = Harness("library")
        h.prefs.chooseTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.onNodeWithTag(UiTags.spaceCard("music")).performClick()
        rule.waitForTag(UiTags.libItem("content://media/external/audio/media/102"))
        rule.onNodeWithTag(UiTags.libItem("content://media/external/audio/media/102")).performClick()
        rule.mainClock.autoAdvance = false
        repeat(40) { if (!rule.exists(UiTags.MINI_PLAYER)) { rule.mainClock.advanceTimeBy(250); Thread.sleep(50) } }
        shot("pm-mini-$mode")

        // A frame part-way through the sheet spring.
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performClick()
        rule.mainClock.advanceTimeBy(48)
        shot("pm-transition-$mode", advanceMs = 100)
        shot("pm-full-$mode")

        rule.onNodeWithTag(UiTags.FULL_SEEK).performTouchInput { down(percentOffset(0.4f, 0.5f)); moveTo(percentOffset(0.45f, 0.5f)) }
        shot("pm-seek-pressed-$mode")
        rule.onNodeWithTag(UiTags.FULL_SEEK).performTouchInput { up() }
        rule.mainClock.advanceTimeBy(600)

        rule.onNodeWithTag(UiTags.FULL_LYRICS_TOGGLE).performClick()
        rule.mainClock.advanceTimeBy(800)
        h.vm.player.seekTo(16_000)
        shot("pm-lyrics-$mode", advanceMs = 1_500)
    }
}
