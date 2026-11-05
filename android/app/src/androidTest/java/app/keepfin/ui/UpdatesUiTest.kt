package app.keepfin.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.ui.preview.FakeUpdates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

private val CURRENT = app.keepfin.BuildConfig.VERSION_NAME
private val NEXT get() = FakeUpdates.NEXT_VERSION

@RunWith(AndroidJUnit4::class)
class UpdatesUiTest {
    @Test fun updateChannelSettingDefaultsToStableSwitchesToBetaChecksAgainAndGoesBack() {
        val updates = FakeUpdates()
        val h = Harness("default", updates = updates)
        h.show(rule)
        rule.onNodeWithTag(UiTags.nav(Destination.SETTINGS)).performClick()
        rule.waitForTag(UiTags.settingsCategory("ABOUT"))
        rule.onNodeWithTag(UiTags.settingsCategory("ABOUT")).performClick()
        rule.waitForTag(UiTags.setting("app-channel"))
        rule.onNodeWithText("Update channel").assertExists()
        rule.onNodeWithText("Tested releases. Recommended.").assertExists()
        assertEquals(app.keepfin.UpdateChannel.STABLE, h.prefs.appChannel)
        val before = updates.checks.get()
        rule.onNodeWithTag(UiTags.setting("app-channel-beta")).performClick()
        rule.waitForTag(UiTags.setting("app-channel-note"))
        rule.onNodeWithText("Try new features early. Betas can have bugs.").assertExists()
        assertEquals(app.keepfin.UpdateChannel.BETA, h.prefs.appChannel)
        assertEquals(app.keepfin.UpdateChannel.BETA, updates.channel)
        assertTrue("Switching channel checks again", updates.checks.get() > before)
        rule.onNodeWithTag(UiTags.setting("app-channel-stable")).performClick()
        rule.onNodeWithText("Tested releases. Recommended.").assertExists()
        assertEquals(app.keepfin.UpdateChannel.STABLE, h.prefs.appChannel)
    }

    @Test fun aSkipMadeOnStableDoesNotHideTheSameVersionOnBeta() {
        val h = Harness("default", updates = FakeUpdates.scene("available"))
        h.prefs.skipUpdateVersion(NEXT)
        assertEquals(NEXT, h.prefs.skippedUpdateVersion)
        assertEquals("stable", h.prefs.skippedUpdateChannel)
        h.vm.updatesFlow.chooseAppChannel(app.keepfin.UpdateChannel.BETA)
        assertTrue(h.vm.updatesFlow.updateDialogVersion == null || h.vm.updatesFlow.updateDialogVersion == NEXT)
    }

    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    /** Dismisses the once-per-version popup so a test can look at the banner and the rest of the app. */
    private fun later() { rule.waitForTag(UiTags.UPDATE_DIALOG); rule.onNodeWithTag(UiTags.UPDATE_LATER).performClick(); rule.waitUntil(5_000) { !rule.exists(UiTags.UPDATE_DIALOG) } }

    @Test fun bannerShowsOnDownloadsWhenAnUpdateIsAvailableAndTheTopBarHasNoUpdateIcon() {
        val h = Harness("default", updates = FakeUpdates.scene("available"))
        h.show(rule)
        later()
        rule.waitForTag(UiTags.UPDATE_BANNER)
        rule.onNode(androidx.compose.ui.test.hasText("Keepfin $NEXT is available") and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(UiTags.UPDATE_BANNER))).assertExists()
        rule.onNodeWithTag(UiTags.UPDATE_BANNER_ACTION).assertExists()
        rule.onNodeWithContentDescription("App updates", substring = true).assertDoesNotExist()
        rule.onNodeWithContentDescription("Update available", substring = true).assertDoesNotExist()
    }

    @Test fun noBannerAndNoPopupWhenUpToDate() {
        val h = Harness("default", updates = FakeUpdates())
        h.show(rule)
        rule.waitForTag(UiTags.QUEUE)
        rule.onNodeWithTag(UiTags.UPDATE_BANNER).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.UPDATE_DIALOG).assertDoesNotExist()
    }

    @Test fun bannerCanBeHiddenForThisVersionAndStaysHidden() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val name = "ui-test-" + UUID.randomUUID()
        val prefs = UiPreferences(context, name)
        val h = Harness("default", updates = FakeUpdates.scene("available"), prefs = prefs)
        h.show(rule)
        later()
        rule.waitForTag(UiTags.UPDATE_BANNER_HIDE)
        rule.onNodeWithTag(UiTags.UPDATE_BANNER_HIDE).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.UPDATE_BANNER) }
        assertEquals(NEXT, prefs.hiddenBannerVersion)
        // A later launch remembers it; a newer version would show the banner again.
        val second = Harness("default", updates = FakeUpdates.scene("available"), prefs = UiPreferences(context, name))
        assertNull(second.vm.updatesFlow.updateBannerVersion)
    }

    @Test fun popupAnnouncesTheReleaseOnceAndLaterAsksAgainOnlyNextLaunch() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val name = "ui-test-" + UUID.randomUUID()
        val h = Harness("default", updates = FakeUpdates.scene("available"), prefs = UiPreferences(context, name))
        h.show(rule)
        rule.waitForTag(UiTags.UPDATE_DIALOG)
        rule.onNode(androidx.compose.ui.test.hasText("Keepfin $NEXT is available") and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag(UiTags.UPDATE_DIALOG))).assertExists()
        assertTrue(rule.hasText("Spaces on the Library home, easier to switch"))
        assertTrue(rule.hasText("Settings split into simple sections"))
        rule.onNodeWithText("126 MB", substring = true).assertExists()
        rule.onNodeWithText("your downloads and library stay", substring = true).assertExists()
        rule.onNodeWithTag(UiTags.UPDATE_NOW).assertExists()
        rule.onNodeWithTag(UiTags.UPDATE_SKIP).assertExists()
        rule.onNodeWithTag(UiTags.UPDATE_LATER).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.UPDATE_DIALOG) }
        assertNull("Later is not remembered as a skip", h.prefs.skippedUpdateVersion)
        // The next launch asks again.
        assertEquals(NEXT, Harness("default", updates = FakeUpdates.scene("available"), prefs = UiPreferences(context, name)).vm.updatesFlow.updateDialogVersion)
    }

    @Test fun skipThisVersionIsRememberedPerVersion() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val name = "ui-test-" + UUID.randomUUID()
        val prefs = UiPreferences(context, name)
        val h = Harness("default", updates = FakeUpdates.scene("available"), prefs = prefs)
        h.show(rule)
        rule.waitForTag(UiTags.UPDATE_SKIP)
        rule.onNodeWithTag(UiTags.UPDATE_SKIP).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.UPDATE_DIALOG) }
        assertEquals(NEXT, prefs.skippedUpdateVersion)
        assertNull(Harness("default", updates = FakeUpdates.scene("available"), prefs = UiPreferences(context, name)).vm.updatesFlow.updateDialogVersion)
        // The banner and Settings still show it; only the popup is skipped.
        rule.onNodeWithTag(UiTags.UPDATE_BANNER).assertExists()
        // A newer release is announced again.
        val newer = FakeUpdates.available("99.0.0")
        assertEquals("99.0.0", Harness("default", updates = FakeUpdates(state = newer), prefs = UiPreferences(context, name)).vm.updatesFlow.updateDialogVersion)
    }

    @Test fun popupWaitsBehindWhatsNew() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = UiPreferences(context, "ui-test-" + UUID.randomUUID()).also { it.lastSeenVersion = "0.0.1" }
        val h = Harness("default", updates = FakeUpdates(state = FakeUpdates.available(), notes = "# Keepfin\n\n- A brand new interface."), prefs = prefs)
        h.show(rule)
        rule.waitForTag(UiTags.WHATS_NEW_SHEET)
        assertNull("The update popup is queued behind What's new", h.vm.updatesFlow.updateDialogVersion)
        rule.onNodeWithTag(UiTags.UPDATE_DIALOG).assertDoesNotExist()
        rule.onNodeWithTag("whats-new-done").performClick()
        // the update popup comes once What's new is closed
        rule.waitForTag(UiTags.UPDATE_DIALOG)
    }

    @Test fun updateNowStartsTheInAppUpdateFlow() {
        val updates = FakeUpdates.scene("available")
        val h = Harness("default", updates = updates)
        h.show(rule)
        rule.waitForTag(UiTags.UPDATE_NOW)
        rule.onNodeWithTag(UiTags.UPDATE_NOW).performClick()
        rule.waitUntil(5_000) { updates.downloads.get() == 1 }
        rule.waitForTag(UiTags.UPDATE_SHEET)
        rule.waitForTag(UiTags.UPDATE_INSTALL, 15_000)
    }

    @Test fun settingsOverviewMarksUpdatesWithADotWhenAnUpdateExists() {
        val h = Harness("library", updates = FakeUpdates.scene("available"))
        h.vm.navigate(Destination.SETTINGS)
        h.show(rule)
        later()
        rule.waitForTag(UiTags.SETTINGS_OVERVIEW)
        rule.onNodeWithTag(UiTags.UPDATE_DOT, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Keepfin $NEXT is available").assertExists()
        rule.onNodeWithTag(UiTags.settingsCategory("ABOUT")).performClick()
        rule.waitForTag(UiTags.UPDATES_APP)
    }

    @Test fun noDotInSettingsWhenUpToDate() {
        val h = Harness("library", updates = FakeUpdates())
        h.vm.navigate(Destination.SETTINGS)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_OVERVIEW)
        rule.onNodeWithTag(UiTags.UPDATE_DOT, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun sheetDownloadsThenOffersInstall() {
        val updates = FakeUpdates.scene("available")
        val h = Harness("default", updates = updates)
        h.show(rule)
        later()
        rule.onNodeWithTag(UiTags.UPDATE_BANNER_ACTION).performClick()
        rule.waitForTag(UiTags.UPDATE_SHEET)
        rule.onNodeWithText("Version $NEXT is available").assertExists()
        rule.onNodeWithTag(UiTags.RELEASE_NOTES_BODY).assertExists()
        rule.onNodeWithTag(UiTags.UPDATE_DOWNLOAD).performClick()
        rule.waitUntil(5_000) { updates.downloads.get() == 1 }
        rule.waitForTag(UiTags.UPDATE_INSTALL, 15_000)
        rule.onNodeWithText("Version $NEXT is ready to install").assertExists()
    }

    @Test fun upToDateSheetOffersCheckNow() {
        val updates = FakeUpdates()
        val h = Harness("default", updates = updates)
        h.show(rule)
        rule.runOnIdle { h.vm.updatesFlow.showUpdateSheet() }
        rule.waitForTag(UiTags.UPDATE_SHEET)
        rule.onNodeWithText("You're on v$CURRENT").assertExists()
        rule.onNodeWithTag(UiTags.UPDATE_CHECK).performClick()
        rule.waitUntil(5_000) { updates.checks.get() == 1 }
    }

    @Test fun startupChecksOnlyWhenTheLastCheckIsOlderThanSixHours() {
        val stale = FakeUpdates()
        Harness("empty", updates = stale, freshCheck = false)
        // The check runs on IO; give it a moment.
        rule.waitUntil(5_000) { stale.checks.get() == 1 }
        val fresh = FakeUpdates()
        Harness("empty", updates = fresh, freshCheck = true)
        Thread.sleep(400)
        assertEquals(0, fresh.checks.get())
    }

    @Test fun whatsNewShowsOnceAfterAVersionBump() {
        val prefsName = "ui-test-" + UUID.randomUUID()
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.app.Application>()
        val prefs = UiPreferences(context, prefsName).also { it.lastSeenVersion = "0.0.1" }
        val updates = FakeUpdates(notes = "# Keepfin v0.3.12\n\n- A brand new interface.")
        val h = Harness("default", updates = updates, prefs = prefs)
        h.show(rule)
        rule.waitForTag(UiTags.WHATS_NEW_SHEET)
        rule.onNodeWithText("What's new in v$CURRENT").assertExists()
        rule.onNodeWithTag(UiTags.RELEASE_NOTES_BODY).assertExists()
        assertEquals(CURRENT, prefs.lastSeenVersion)
        rule.onNodeWithTag("whats-new-done").performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.WHATS_NEW_SHEET) }
        // A later launch with the same stored version shows nothing.
        val second = Harness("default", updates = FakeUpdates(), prefs = UiPreferences(context, prefsName))
        assertNull(second.vm.whatsNew)
    }

    @Test fun freshInstallShowsNoWhatsNewButRecordsTheVersion() {
        val h = Harness("default", updates = FakeUpdates())
        assertNull(h.vm.whatsNew)
        assertEquals(CURRENT, h.prefs.lastSeenVersion)
        h.show(rule)
        rule.onNodeWithTag(UiTags.WHATS_NEW_SHEET).assertDoesNotExist()
    }

    @Test fun whatsNewCanBeReopenedFromTheUpdateSheet() {
        val h = Harness("default", updates = FakeUpdates())
        h.show(rule)
        rule.runOnIdle { h.vm.updatesFlow.showUpdateSheet() }
        rule.waitForTag(UiTags.UPDATE_WHATS_NEW)
        rule.onNodeWithTag(UiTags.UPDATE_WHATS_NEW).performClick()
        rule.waitForTag(UiTags.WHATS_NEW_SHEET)
        assertNotNull(h.vm.whatsNew)
    }
}
