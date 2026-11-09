package app.keepfin.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.ui.player.PlayerStyles
import app.keepfin.ui.theme.ThemeMode
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** GitHub #34: the three full-player styles on the phone: switching from the player and from Settings, the saved choice, and the controls in each. */
@RunWith(AndroidJUnit4::class)
class PlayerStylesUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private val song = "content://media/external/audio/media/102"
    private val prefsName = "ui-test-" + UUID.randomUUID()

    private fun open(style: String? = null, theme: ThemeMode = ThemeMode.DARK): Harness {
        val prefs = UiPreferences(ApplicationProvider.getApplicationContext(), prefsName)
        prefs.chooseTheme(theme)
        if (style != null) prefs.choosePlayerStyle(style)
        val h = Harness("library", prefs = prefs)
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.onNodeWithTag(UiTags.spaceCard("music")).performClick()
        rule.waitForTag(UiTags.libItem(song))
        rule.onNodeWithTag(UiTags.libItem(song)).performClick()
        rule.waitForTag(UiTags.MINI_PLAYER)
        rule.onNodeWithTag(UiTags.MINI_PLAYER).performClick()
        rule.waitForTag(UiTags.FULL_PLAYER)
        rule.waitUntil(5_000) { h.vm.fullPlayer }
        return h
    }

    private fun pick(id: String) {
        rule.onNodeWithTag("player-style-button").performClick()
        rule.onNodeWithTag("player-style-item-$id").performClick()
        rule.waitUntil(5_000) { rule.exists("player-style-body-$id") }
    }

    /** What every style must keep: the transport, the seek bar, favorites, speed, sleep timer, equalizer and the style button. */
    private fun assertControls(style: String) {
        for (tag in listOf(UiTags.FULL_PLAY, UiTags.FULL_SEEK, "full-previous", "full-next", "full-shuffle", "full-repeat", UiTags.FULL_FAVORITE, "full-speed", "full-sleep", "full-eq", UiTags.FULL_COLLAPSE, "player-style-button")) {
            rule.waitForTag(tag)
            check(rule.exists(tag)) { "$style lacks $tag" }
        }
    }

    @Test fun stageIsTheDefaultAndShowsItsControls() {
        open()
        rule.waitForTag("player-style-body-stage")
        assertControls("stage")
        rule.onNodeWithTag(UiTags.FULL_LYRICS_TOGGLE).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.FULL_QUEUE_TOGGLE).assertIsDisplayed()
    }

    @Test fun theStyleButtonSwitchesToCinemaAndAfterglowAndBack() {
        open()
        pick("cinema")
        assertControls("cinema")
        rule.onNodeWithTag("player-side-queue").assertIsDisplayed()
        rule.onNodeWithTag("player-side-lyrics").assertIsDisplayed()
        pick("afterglow")
        assertControls("afterglow")
        rule.onNodeWithTag("player-visualizer").assertExists()
        rule.onNodeWithTag("player-glow").assertExists()
        pick("stage")
        assertControls("stage")
    }

    @Test fun lyricsAndQueueStayReachableInCinemaAndAfterglow() {
        open("cinema")
        rule.waitForTag("player-style-body-cinema")
        rule.onNodeWithTag("player-side-queue").performClick()
        rule.waitForTag("queue-list")
        rule.onNodeWithTag("player-side-lyrics").performClick()
        rule.waitUntil(5_000) { !rule.exists("queue-list") }
        pick("afterglow")
        rule.onNodeWithTag("player-side-queue").performClick()
        rule.waitForTag("queue-list")
        rule.onNodeWithTag("player-side-lyrics").performClick()
        rule.waitUntil(5_000) { !rule.exists("queue-list") }
    }

    @Test fun playPauseWorksInEveryStyle() {
        val h = open()
        for (id in PlayerStyles.all.map { it.id }) {
            pick(id)
            val before = h.vm.player.ui.value.playing
            rule.onNodeWithTag(UiTags.FULL_PLAY).performClick()
            rule.waitUntil(5_000) { h.vm.player.ui.value.playing != before }
            rule.onNodeWithTag(UiTags.FULL_PLAY).performClick()
            rule.waitUntil(5_000) { h.vm.player.ui.value.playing == before }
        }
    }

    @Test fun theChoiceSurvivesARestart() {
        val first = open()
        pick("afterglow")
        assertEquals("afterglow", first.prefs.playerStyle)
        // a new preferences object over the same file is what a restarted app reads
        val again = UiPreferences(ApplicationProvider.getApplicationContext(), prefsName)
        assertEquals("afterglow", again.playerStyle)
    }

    @Test fun anUnknownSavedStyleOpensTheDefault() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        context.getSharedPreferences(prefsName, android.content.Context.MODE_PRIVATE).edit().putString("playerStyle", "hologram").commit()
        assertEquals("stage", UiPreferences(context, prefsName).playerStyle)
    }

    @Test fun settingsAppearanceOffersTheSameThreeStyles() {
        val h = Harness("library", prefs = UiPreferences(ApplicationProvider.getApplicationContext(), prefsName))
        h.openSettings(app.keepfin.ui.settings.SettingsCategory.APPEARANCE)
        h.show(rule)
        rule.waitForTag(UiTags.setting("player-style-stage"))
        rule.onNodeWithTag(UiTags.setting("player-style-stage")).assertIsSelected()
        rule.onNodeWithTag(UiTags.setting("player-style-cinema")).performClick()
        rule.waitUntil(5_000) { h.prefs.playerStyle == "cinema" }
        rule.onNodeWithTag(UiTags.setting("player-style-cinema")).assertIsSelected()
        rule.onNodeWithTag(UiTags.setting("player-style-afterglow")).assertExists()
    }

    /** The mean brightness (0..1) of the player's backdrop, sampled on a grid. */
    private fun washBrightness(): Float {
        val px = rule.onNodeWithTag("player-wash").captureToImage().toPixelMap()
        var sum = 0f; var n = 0
        for (x in 0 until px.width step 40) for (y in 0 until px.height step 40) { val c = px[x, y]; sum += (c.red + c.green + c.blue) / 3f; n++ }
        return sum / n
    }

    private fun followsTheTheme(theme: ThemeMode, light: Boolean) {
        open(theme = theme)
        for (id in PlayerStyles.all.map { it.id }) {
            pick(id)
            Thread.sleep(1_200)
            rule.waitForIdle()
            val b = washBrightness()
            assertTrue("$id in $theme has a backdrop brightness of $b", if (light) b > 0.7f else b < 0.3f)
            assertControls(id)
        }
    }

    @Test fun everyStyleIsLightInTheLightTheme() = followsTheTheme(ThemeMode.LIGHT, light = true)
    @Test fun everyStyleIsDarkInTheDarkTheme() = followsTheTheme(ThemeMode.DARK, light = false)
}
