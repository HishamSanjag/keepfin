package app.keepfin.ui.setup

import android.Manifest
import android.os.Build
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import app.keepfin.core.start.Start
import app.keepfin.core.start.StartPage
import app.keepfin.setup.FolderKind
import app.keepfin.setup.Gate
import app.keepfin.setup.PickMode
import app.keepfin.setup.PickedFolder
import app.keepfin.setup.SetupActions
import app.keepfin.setup.SetupStep
import app.keepfin.setup.SetupUi
import app.keepfin.ui.theme.KeepfinTheme
import app.keepfin.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The phone's start pages (dev-docs/plans/START-PAGES.md) over a fake setup; the permission prompt of the Grant test is Android's own. */
@RunWith(AndroidJUnit4::class)
class StartPagesUiTest {
    @get:Rule val rule = createComposeRule()
    private val words = Start.words(phone = true)

    /** Records what the pages ask for and, like the real setup, makes the folder when asked. */
    private class FakeSetup(initial: SetupUi) : SetupActions {
        val state = MutableStateFlow(initial)
        val calls = ArrayList<String>()
        var mode: Boolean? = null
        override fun start() { calls += "start" }
        override fun back() { calls += "back" }
        override fun chooseRecommended() { calls += "recommended" }
        override fun chooseAnother() { calls += "another" }
        override fun chooseExisting() { calls += "existing" }
        override fun folderPicked(uri: String?) {}
        override fun openFolder() { calls += "open"; state.value = state.value.copy(startReady = true) }
        override fun createFolder() { calls += "create"; state.value = state.value.copy(startReady = true) }
        override fun setChoice(spaceId: String, choice: app.keepfin.setup.Choice) {}
        override fun review() {}
        override fun begin() {}
        override fun resume() {}
        override fun finish() {}
        override fun permissionsChanged() {}
        override fun consentRequest(): app.keepfin.setup.ConsentRequest? = null
        override fun consentResult(granted: Boolean) {}
        override fun finishStart(complete: Boolean) { calls += "finishStart:$complete" }
    }

    private fun first() = SetupUi(gate = Gate.FIRST_START, step = SetupStep.WELCOME)
    private fun ready() = first().copy(
        startReady = true,
        folder = PickedFolder("content://x", "Internal storage > Documents > Keepfin", FolderKind.NEW, freeBytes = 48_000_000_000, mode = PickMode.RECOMMENDED),
    )

    private fun show(setup: FakeSetup, dark: Boolean = false, onMode: (Boolean) -> Unit = { setup.mode = it }) {
        rule.setContent {
            val ui by setup.state.collectAsState()
            KeepfinTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) { SetupFlow(ui, setup, onMode = onMode) }
        }
    }

    private fun page(n: Int) = rule.onNodeWithContentDescription("Page $n of 5")
    private fun tag(t: String) = rule.onNodeWithTag(t)
    private fun next() { tag(StartTags.PRIMARY).performClick(); rule.waitForIdle() }

    private fun waitForPage(n: Int) {
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription("Page $n of 5").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun theFirstPageShowsTheWordsFromTheSharedCoreAndOneNextButton() {
        show(FakeSetup(first()))
        page(1).assertIsDisplayed()
        rule.onNodeWithText(words.welcomeTitle).assertIsDisplayed()
        rule.onNodeWithText(words.features[0].title).assertIsDisplayed()
        tag(StartTags.SKIP).assertIsDisplayed()
        tag(StartTags.PRIMARY).assertIsDisplayed().assertHeightIsAtLeast(52.dp)
        rule.onNodeWithText(words.next).assertIsDisplayed()
        // one button only: the old Not now and Other options are not here
        rule.onAllNodesWithText("Not now").assertCountEquals(0)
    }

    @Test fun skipIntroGoesToTheModePageAndIsGoneThere() {
        show(FakeSetup(first()))
        tag(StartTags.SKIP).performClick()
        waitForPage(3)
        rule.onNodeWithText(words.modeTitle).assertIsDisplayed()
        tag(StartTags.SKIP).assertDoesNotExist()
    }

    @Test fun nextWalksFromWelcomeToHowAndSkipIsStillThere() {
        show(FakeSetup(first()))
        next()
        waitForPage(2)
        rule.onNodeWithText(words.howTitle).assertIsDisplayed()
        rule.onNodeWithText(words.steps[0].title).assertIsDisplayed()
        tag(StartTags.SKIP).assertIsDisplayed()
    }

    @Test fun theSystemBackGestureGoesToThePreviousPage() {
        show(FakeSetup(first()))
        tag(StartTags.SKIP).performClick()
        waitForPage(3)
        Espresso.pressBack()
        waitForPage(2)
    }

    @Test fun completeIsSelectedAndRecommendedByDefaultAndJustDownloadsCanBePicked() {
        show(FakeSetup(first()))
        tag(StartTags.SKIP).performClick()
        waitForPage(3)
        tag(StartTags.MODE_COMPLETE).assertIsSelected()
        tag(StartTags.MODE_DOWNLOADS).assertIsNotSelected()
        tag(StartTags.MODE_COMPLETE).assert(androidx.compose.ui.test.hasText(words.recommended))
        tag(StartTags.MODE_DOWNLOADS).assert(androidx.compose.ui.test.hasText(words.recommended).not())
        rule.onNodeWithText(Start.COMPLETE).assertIsDisplayed()
        tag(StartTags.MODE_DOWNLOADS).performClick()
        tag(StartTags.MODE_DOWNLOADS).assertIsSelected()
        tag(StartTags.MODE_COMPLETE).assertIsNotSelected()
        rule.onNodeWithText(words.modeLater).assertIsDisplayed()
    }

    @Test fun theFolderPageBlocksNextAndSwipingUntilAFolderIsChosen() {
        val setup = FakeSetup(first())
        show(setup)
        tag(StartTags.SKIP).performClick(); waitForPage(3)
        next(); waitForPage(4)
        rule.onNodeWithText(words.folderTitle).assertIsDisplayed()
        rule.onNodeWithText(words.haveFolder).assertIsDisplayed()
        // the one button makes the recommended folder; it is not a way past the page
        rule.onNodeWithText("Use Documents › Keepfin").assertIsDisplayed()
        tag(StartTags.PRIMARY).performClick()
        assertTrue(setup.calls.contains("recommended"))
        tag(StartTags.page(StartPage.FOLDER)).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        page(4).assertIsDisplayed()
        // once the folder is made the start goes on by itself to the permissions
        rule.runOnUiThread { setup.state.value = ready() }
        waitForPage(5)
        rule.onNodeWithText(words.permissionsTitle).assertIsDisplayed()
    }

    @Test fun aPickedFolderIsMadeByNextAndTheLinksAreHereToo() {
        val picked = first().copy(folder = PickedFolder("content://x", "Internal storage > Photos", FolderKind.NEW, existingEntries = 3, freeBytes = 5_000_000_000, mode = PickMode.ANOTHER))
        val setup = FakeSetup(picked)
        show(setup)
        tag(StartTags.SKIP).performClick(); waitForPage(3)
        next(); waitForPage(4)
        rule.onNodeWithText("Internal storage > Photos").assertIsDisplayed()
        tag(StartTags.CHANGE).performClick()
        assertTrue(setup.calls.contains("another"))
        tag(StartTags.HAVE_FOLDER).performClick()
        assertTrue(setup.calls.contains("existing"))
        next()
        assertTrue("Next makes the folder", setup.calls.contains("create"))
        waitForPage(5)
    }

    @Test fun advancedSetupOpensTodaysDetailedWelcomeAndTheFolderChoiceComesBack() {
        val setup = FakeSetup(first())
        show(setup)
        tag(StartTags.SKIP).performClick(); waitForPage(3)
        next(); waitForPage(4)
        rule.onNodeWithText(words.advancedHint).assertIsDisplayed()
        tag(StartTags.ADVANCED).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(SetupTags.RECOMMENDED).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Create my Keepfin folder").assertIsDisplayed()
        // the system back gesture returns to the pages
        Espresso.pressBack()
        waitForPage(4)
        // a folder made in the advanced steps carries on to the permissions
        tag(StartTags.ADVANCED).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(SetupTags.RECOMMENDED).fetchSemanticsNodes().isNotEmpty() }
        rule.runOnUiThread { setup.state.value = ready() }
        waitForPage(5)
    }

    @Test fun justDownloadsShowsOnlyTheNotificationsRow() {
        show(FakeSetup(ready()))
        tag(StartTags.SKIP).performClick(); waitForPage(3)
        tag(StartTags.MODE_DOWNLOADS).performClick()
        next(); waitForPage(4)
        next(); waitForPage(5)
        rule.onNodeWithText(words.notifications.title).assertIsDisplayed()
        rule.onAllNodesWithTag(StartTags.GRANT_MEDIA).assertCountEquals(0)
        rule.onAllNodesWithTag(StartTags.GRANTED_MEDIA).assertCountEquals(0)
        rule.onNodeWithText(words.permissionsLater).assertIsDisplayed()
    }

    @Test fun completeShowsBothRowsAndStartWorksWithoutGranting() {
        val setup = FakeSetup(ready())
        show(setup)
        tag(StartTags.SKIP).performClick(); waitForPage(3)
        next(); waitForPage(4)
        next(); waitForPage(5)
        rule.onNodeWithText(words.media.title).assertIsDisplayed()
        rule.onNodeWithText(words.notifications.title).assertIsDisplayed()
        rule.onNodeWithText(words.start).assertIsDisplayed()
        tag(StartTags.PRIMARY).performClick()
        assertEquals(true, setup.mode)
        assertTrue(setup.calls.contains("finishStart:true"))
    }

    @Test fun startAfterJustDownloadsSavesThatModeAndSkipsTheEarlierFilesSearch() {
        val setup = FakeSetup(ready())
        show(setup)
        tag(StartTags.SKIP).performClick(); waitForPage(3)
        tag(StartTags.MODE_DOWNLOADS).performClick()
        next(); waitForPage(4)
        next(); waitForPage(5)
        tag(StartTags.PRIMARY).performClick()
        assertEquals(false, setup.mode)
        assertTrue(setup.calls.contains("finishStart:false"))
    }

    /** Needs the notification permission to be missing before the run (the runner revokes it with adb); then Android's own prompt answers. */
    @Test fun grantAsksAndroidOnceAndTheRowShowsGranted() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        assumeTrue(ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
        show(FakeSetup(ready()))
        tag(StartTags.SKIP).performClick(); waitForPage(3)
        tag(StartTags.MODE_DOWNLOADS).performClick()
        next(); waitForPage(4)
        next(); waitForPage(5)
        tag(StartTags.GRANT_NOTIFICATIONS).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        tag(StartTags.GRANT_NOTIFICATIONS).performClick()
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val allow = device.wait(Until.findObject(By.res("com.android.permissioncontroller:id/permission_allow_button")), 10_000)
        assertTrue("Android shows its own prompt", allow != null)
        allow!!.click()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag(StartTags.GRANTED_NOTIFICATIONS).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText(words.granted).assertIsDisplayed()
        rule.onAllNodesWithTag(StartTags.GRANT_NOTIFICATIONS).assertCountEquals(0)
    }

    @Test fun darkAndLargeFontStillReachTheButton() {
        val setup = FakeSetup(first())
        rule.setContent {
            val ui by setup.state.collectAsState()
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, 2f)) {
                KeepfinTheme(mode = ThemeMode.DARK) { SetupFlow(ui, setup) }
            }
        }
        tag(StartTags.PRIMARY).assertIsDisplayed()
        tag(StartTags.SKIP).assertIsDisplayed()
    }
}
