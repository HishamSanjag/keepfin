package app.keepfin.ui

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.setup.ConsentRequest
import app.keepfin.setup.Gate
import app.keepfin.setup.SetupActions
import app.keepfin.setup.SetupStep
import app.keepfin.setup.SetupUi
import app.keepfin.setup.Choice
import app.keepfin.ui.setup.SetupFlow
import app.keepfin.ui.theme.KeepfinTheme
import app.keepfin.ui.theme.ThemeMode
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in screenshots of the Cobalt Atelier screens (-Pandroid.testInstrumentationRunnerArguments.screenshots=true), saved to
 * /data/local/tmp/atelier-*.png: Library home, a space page, Downloads (presets, all formats, queue), mini and full player, lyrics,
 * Settings and a setup step, in light and dark.
 */
@RunWith(AndroidJUnit4::class)
class AtelierScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Before fun only() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        StudioEngine.override = null
    }
    @After fun tearDown() { StudioEngine.override = null }

    private fun shot(name: String, dark: Boolean, settleMs: Long = 900) {
        Thread.sleep(settleMs)
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/atelier-$name-${if (dark) "dark" else "light"}.png").close()
        Thread.sleep(700)
    }

    private val song = "content://media/external/audio/media/102"

    private fun theme(h: Harness, dark: Boolean) = h.prefs.chooseTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT)

    @Test fun libraryLight() = library(false)
    @Test fun libraryDark() = library(true)

    private fun library(dark: Boolean) {
        val h = Harness("library"); theme(h, dark)
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.waitForTag(UiTags.LIB_LIST)
        shot("lib-home", dark)
        rule.onNodeWithTag(UiTags.LIB_LIST).performScrollToNode(hasTestTag(UiTags.LIB_SHELF_COLLECTIONS))
        shot("lib-home-shelves", dark)
    }

    @Test fun spaceLight() = space(false)
    @Test fun spaceDark() = space(true)

    private fun space(dark: Boolean) {
        val h = Harness("library"); theme(h, dark)
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.onNodeWithTag(UiTags.spaceCard("music")).performClick()
        rule.waitForTag(UiTags.libItem(song))
        shot("space-music", dark)
        rule.onNodeWithTag(UiTags.LIB_VIEW_TOGGLE).performClick()
        shot("space-music-grid", dark)
        rule.onNodeWithTag("library-back").performClick()
        rule.waitForTag(UiTags.spaceCard("videos"))
        rule.onNodeWithTag(UiTags.spaceCard("videos")).performClick()
        rule.waitForIdle()
        shot("space-videos", dark)
    }

    @Test fun downloadsLight() = downloads(false)
    @Test fun downloadsDark() = downloads(true)

    private fun downloads(dark: Boolean) {
        val h = Harness("empty"); theme(h, dark)
        h.show(rule)
        rule.waitForTag(UiTags.LINK_FIELD)
        shot("dl-empty", dark)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput("https://www.youtube.com/watch?v=aqz-KE-bpKQ")
        rule.waitForTag(UiTags.REVIEW)
        rule.waitForIdle()
        shot("dl-inspect", dark)
        rule.onNodeWithTag(UiTags.quality("v720")).performScrollTo()
        shot("dl-allformats", dark)
    }

    @Test fun queueLight() = queue(false)
    @Test fun queueDark() = queue(true)

    private fun queue(dark: Boolean) {
        val h = Harness("default"); theme(h, dark)
        h.show(rule)
        rule.waitForTag(UiTags.job("j-dl"))
        shot("dl-queue", dark)
        rule.onNodeWithTag(UiTags.QUEUE).performScrollToNode(hasTestTag(UiTags.job("j-done1")))
        shot("dl-queue-finished", dark)
    }

    @Test fun playerLight() = player(false)
    @Test fun playerDark() = player(true)

    private fun player(dark: Boolean) {
        val h = Harness("library"); theme(h, dark)
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.onNodeWithTag(UiTags.spaceCard("music")).performClick()
        rule.waitForTag(UiTags.libItem(song))
        rule.onNodeWithTag(UiTags.libItem(song)).performClick()
        rule.mainClock.autoAdvance = false
        repeat(40) { if (!rule.exists(UiTags.MINI_PLAYER)) { rule.mainClock.advanceTimeBy(250); Thread.sleep(50) } }
        rule.mainClock.advanceTimeBy(1_000)
        shot("mini-on-space", dark)
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performClick()
        rule.mainClock.advanceTimeBy(1_500)
        shot("full", dark, 600)
        rule.onNodeWithTag(UiTags.FULL_LYRICS_TOGGLE).performClick()
        rule.mainClock.advanceTimeBy(800)
        h.vm.player.seekTo(16_000)
        rule.mainClock.advanceTimeBy(1_500)
        shot("lyrics", dark, 600)
    }

    @Test fun settingsLight() = settings(false)
    @Test fun settingsDark() = settings(true)

    private fun settings(dark: Boolean) {
        val h = Harness("library"); theme(h, dark)
        h.vm.navigate(Destination.SETTINGS)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_OVERVIEW)
        shot("settings", dark)
    }

    private class NoActions : SetupActions {
        override fun start() {}
        override fun back() {}
        override fun folderPicked(uri: String?) {}
        override fun openFolder() {}
        override fun createFolder() {}
        override fun setChoice(spaceId: String, choice: Choice) {}
        override fun review() {}
        override fun begin() {}
        override fun resume() {}
        override fun finish() {}
        override fun permissionsChanged() {}
        override fun consentRequest(): ConsentRequest? = null
        override fun consentResult(granted: Boolean) {}
    }

    @Test fun setupLight() = setup(false)
    @Test fun setupDark() = setup(true)

    private fun setup(dark: Boolean) {
        rule.setContent { KeepfinTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) { SetupFlow(SetupUi(gate = Gate.FIRST_START, step = SetupStep.WELCOME), NoActions()) } }
        rule.waitForIdle()
        shot("setup", dark)
    }
}
