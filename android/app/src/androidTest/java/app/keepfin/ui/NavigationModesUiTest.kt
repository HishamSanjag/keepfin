package app.keepfin.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.ui.settings.SettingsCategory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** 0.5.5 navigation and modes: Keepfin mode, the bottom bar, "+ Download", the paste box and the copied-link notice. */
@RunWith(AndroidJUnit4::class)
class NavigationModesUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private val link = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"

    private fun freshPrefs() = UiPreferences(ApplicationProvider.getApplicationContext(), "ui-test-" + UUID.randomUUID())

    // ---- first run and the mode setting ---------------------------------------------------------------

    @Test fun settingsSwitchesModeBothWays() {
        val h = Harness("empty")
        h.openSettings(SettingsCategory.LIBRARY)
        h.show(rule)
        rule.waitForTag(UiTags.SETTING_MODE_DOWNLOADER)
        rule.onNodeWithTag(UiTags.SETTING_MODE_DOWNLOADER).performClick()
        rule.waitUntil(5_000) { !h.engine.settings.value.library.enabled }
        rule.waitUntil(5_000) { !rule.exists(UiTags.nav(Destination.HOME)) }
        rule.onNodeWithTag(UiTags.SETTING_MODE_FULL).performClick()
        rule.waitUntil(5_000) { h.engine.settings.value.library.enabled }
        rule.waitForTag(UiTags.nav(Destination.HOME))
        rule.waitForTag(UiTags.nav(Destination.SEARCH))
        rule.waitForTag(UiTags.nav(Destination.LIBRARY))
    }

    // ---- the bar --------------------------------------------------------------------------------------

    @Test fun fullModeBarIsHomeSearchLibraryDownloadsAndSettingsIsATopBarIcon() {
        val h = Harness("empty")
        h.vm.navigate(Destination.HOME)
        h.show(rule)
        for (d in listOf(Destination.HOME, Destination.SEARCH, Destination.LIBRARY, Destination.DOWNLOADS)) rule.waitForTag(UiTags.nav(d))
        rule.waitForTag(UiTags.ACTIVITY_BELL)
        // On Home the Settings tag belongs to the top-bar icon, not to the bar.
        rule.onNodeWithTag(UiTags.nav(Destination.SETTINGS)).performClick()
        rule.waitUntil(5_000) { h.vm.destination == Destination.SETTINGS }
    }

    @Test fun downloaderOnlyShowsNoHomeSearchLibraryOrPlayer() {
        val h = Harness("library")
        h.libraryOff()
        h.vm.navigate(Destination.HOME)
        h.show(rule)
        rule.waitForTag(UiTags.nav(Destination.DOWNLOADS))
        rule.waitForTag(UiTags.nav(Destination.SETTINGS))
        for (d in listOf(Destination.HOME, Destination.SEARCH, Destination.LIBRARY)) assertFalse("no ${d.name} tab", rule.exists(UiTags.nav(d)))
        assertEquals("Home needs the library", Destination.DOWNLOADS, h.vm.destination)
        assertFalse(rule.exists(UiTags.MINI_PLAYER))
        assertFalse(rule.exists(UiTags.FAB_DOWNLOAD))
        assertFalse(rule.exists(UiTags.HOME_SCREEN))
        h.vm.navigate(Destination.SEARCH)
        rule.waitForIdle()
        assertEquals(Destination.DOWNLOADS, h.vm.destination)
        rule.onNodeWithTag(UiTags.ACTIVITY_BELL).assertIsDisplayed()
    }

    @Test fun downloadsShowsTheActiveCountOnItsBarItem() {
        val h = Harness("live")
        h.vm.navigate(Destination.HOME)
        h.show(rule)
        rule.waitForTag(UiTags.DOWNLOADS_BADGE)
        val active = h.engine.jobs.value.count { it.status.active }
        assertTrue(active > 0)
        rule.onNodeWithTag(UiTags.DOWNLOADS_BADGE, useUnmergedTree = true).assertIsDisplayed()
    }

    // ---- "+ Download" and the box ---------------------------------------------------------------------

    @Test fun noFloatingDownloadButtonOnHomeOrLibraryTheDownloadsTabIsTheWayIn() {
        val h = Harness("empty")
        h.vm.navigate(Destination.HOME)
        h.show(rule)
        rule.waitForTag(UiTags.HOME_SCREEN)
        assertFalse("Home has no Download button", rule.exists(UiTags.FAB_DOWNLOAD))
        rule.runOnIdle { h.vm.navigate(Destination.LIBRARY) }
        rule.waitForTag(UiTags.LIB_LIST)
        assertFalse("Library has no Download button", rule.exists(UiTags.FAB_DOWNLOAD))
        rule.onNodeWithTag(UiTags.nav(Destination.DOWNLOADS)).performClick()
        rule.waitUntil(5_000) { h.vm.destination == Destination.DOWNLOADS }
        rule.waitForTag(UiTags.LINK_FIELD)
    }

    @Test fun everyTopLevelTabShowsTheSameHeaderActionsInTheSameOrder() {
        val h = Harness("empty")
        h.vm.navigate(Destination.HOME)
        h.show(rule)
        var reference: Pair<Float, Float>? = null
        for (d in listOf(Destination.HOME, Destination.SEARCH, Destination.LIBRARY, Destination.DOWNLOADS)) {
            rule.runOnIdle { h.vm.navigate(d) }
            rule.waitForTag(UiTags.ACTIVITY_BELL)
            rule.waitForTag(UiTags.nav(Destination.SETTINGS))
            val bell = rule.onNodeWithTag(UiTags.ACTIVITY_BELL).fetchSemanticsNode().boundsInRoot
            val gear = rule.onNodeWithTag(UiTags.nav(Destination.SETTINGS)).fetchSemanticsNode().boundsInRoot
            assertTrue("${d.name}: the bell comes before the gear", bell.right <= gear.left + 1f)
            assertFalse("${d.name}: no help icon in the header", rule.exists("library-help"))
            assertFalse("${d.name}: no search icon in the header", rule.exists(UiTags.LIB_SEARCH_TOGGLE))
            // Same position on every tab: the bell and the gear keep their x offsets.
            val now = bell.left to gear.left
            val ref = reference
            if (ref == null) reference = now else {
                assertEquals("${d.name}: bell x", ref.first.toDouble(), now.first.toDouble(), 2.0)
                assertEquals("${d.name}: gear x", ref.second.toDouble(), now.second.toDouble(), 2.0)
            }
        }
    }

    @Test fun theBoxSendsLinksToCaptureAndWordsToSearch() {
        val h = Harness("empty")
        h.vm.navigate(Destination.HOME)
        h.show(rule)
        rule.waitForTag(UiTags.HOME_BOX)
        rule.onNodeWithTag(UiTags.HOME_BOX).performTextInput("lofi beats")
        rule.onNodeWithTag(UiTags.HOME_BOX_GO).performClick()
        rule.waitUntil(5_000) { h.vm.destination == Destination.SEARCH }
        assertEquals("lofi beats", h.vm.searchQuery)
        assertTrue("words never inspect", h.engine.inspectCalls.isEmpty())
        rule.runOnIdle { h.vm.navigate(Destination.HOME) }
        rule.waitForTag(UiTags.HOME_BOX)
        rule.onNodeWithTag(UiTags.HOME_BOX).performTextInput(link)
        rule.onNodeWithTag(UiTags.HOME_BOX_GO).performClick()
        rule.waitUntil(5_000) { h.vm.destination == Destination.DOWNLOADS }
        rule.waitUntil(5_000) { h.engine.inspectCalls.contains(link) }
    }

    // ---- the copied-link notice -----------------------------------------------------------------------

    @Test fun theCopiedLinkIsOfferedOnceAndDownloadStartsCapture() {
        val h = Harness("empty")
        h.openSettings(SettingsCategory.APPEARANCE)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_LIST)
        assertTrue(h.vm.offerCopiedLink("clip@1") { link })
        rule.waitUntil(5_000) { rule.hasText("Download the copied link?") }
        assertFalse("the same clip is never offered again", h.vm.offerCopiedLink("clip@1") { link })
        rule.onNodeWithText("Download").performClick()
        rule.waitUntil(5_000) { h.vm.destination == Destination.DOWNLOADS }
        rule.waitUntil(5_000) { h.engine.inspectCalls.contains(link) }
        // A different clip is a new offer; the hash survives a restart of the screen.
        assertTrue(h.vm.offerCopiedLink("clip@2") { link })
        assertEquals(false, h.prefs.lastOfferedClip == null)
    }

    @Test fun theModeRowIsReachableInSettings() {
        val h = Harness("empty")
        h.openSettings(SettingsCategory.LIBRARY)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_LIST)
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(UiTags.SETTING_MODE_FULL))
        rule.onNodeWithTag(UiTags.SETTING_MODE_FULL).assertIsDisplayed()
    }
}
