package app.keepfin.ui.setup

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.setup.Choice
import app.keepfin.setup.ConsentRequest
import app.keepfin.setup.ConsentUi
import app.keepfin.setup.FolderKind
import app.keepfin.setup.Gate
import app.keepfin.setup.InventoryUi
import app.keepfin.setup.MigrationReport
import app.keepfin.setup.PickedFolder
import app.keepfin.setup.PlanUi
import app.keepfin.setup.ProgressUi
import app.keepfin.setup.ReportEntry
import app.keepfin.setup.SetupActions
import app.keepfin.setup.SetupStep
import app.keepfin.setup.SetupTexts
import app.keepfin.setup.SetupUi
import app.keepfin.setup.SpaceRow
import app.keepfin.setup.StorageStatus
import app.keepfin.ui.settings.StorageContent
import app.keepfin.ui.settings.StorageTags
import app.keepfin.ui.theme.KeepfinTheme
import app.keepfin.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The first start, the setup screens and Settings > Storage over fake state; no file or provider is touched. */
@RunWith(AndroidJUnit4::class)
class SetupUiTest {
    @get:Rule val rule = createComposeRule()

    private class Recorder : SetupActions {
        val calls = ArrayList<String>()
        override fun start() { calls += "start" }
        override fun back() { calls += "back" }
        override fun chooseRecommended() { calls += "recommended" }
        override fun chooseAnother() { calls += "another" }
        override fun chooseExisting() { calls += "existing" }
        override fun chooseInner() { calls += "inner" }
        override fun requestHandled() { calls += "handled" }
        override fun writePermissionResult(granted: Boolean) { calls += "write:$granted" }
        override fun includeOthers(include: Boolean) { calls += "others:$include" }
        override fun findEarlier() { calls += "findEarlier" }
        override fun bringEarlier() { calls += "bring" }
        override fun skipEarlier() { calls += "skipEarlier" }
        override fun finishStart(complete: Boolean) { calls += "finishStart:$complete" }
        override fun folderPicked(uri: String?) { calls += "picked:$uri" }
        override fun openFolder() { calls += "open" }
        override fun createFolder() { calls += "create" }
        override fun setChoice(spaceId: String, choice: Choice) { calls += "choice:$spaceId:${choice.wire}" }
        override fun review() { calls += "review" }
        override fun begin() { calls += "begin" }
        override fun resume() { calls += "resume" }
        override fun finish() { calls += "finish" }
        override fun permissionsChanged() { calls += "permissions" }
        override fun consentRequest(): ConsentRequest? { calls += "consentRequest"; return null }
        override fun consentResult(granted: Boolean) { calls += "consent:$granted" }
    }

    private val inventory = InventoryUi(
        listOf(
            SpaceRow("music", "Music", 120, 900_000_000, 20, Choice.MOVE),
            SpaceRow("videos", "Videos", 6, 3_000_000_000, 0, Choice.LEAVE),
            SpaceRow("downloads", "Downloads", 0, 0, 0, Choice.MOVE),
        ),
        items = 126, bytes = 3_900_000_000, playlists = 4, downloaded = 2, favourites = 31,
    )

    private fun show(ui: SetupUi, actions: SetupActions = Recorder(), dark: Boolean = false) {
        rule.setContent { KeepfinTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) { SetupFlow(ui, actions, startInAdvanced = true) } }
    }

    @Test fun firstStartOffersTheRecommendedPlaceFirstThenAnotherPlaceAnExistingFolderAndNotNow() {
        val r = Recorder()
        show(SetupUi(gate = Gate.FIRST_START, step = SetupStep.WELCOME), r)
        rule.onNodeWithText("Welcome to Keepfin").assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.RECOMMENDED).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText(SetupTexts.WELCOME_START).assertIsDisplayed()
        // one quiet line says where the folder goes and what Android will ask, before anything is launched
        rule.onNodeWithTag(SetupTags.CONFIRM_STEPS).assertIsDisplayed()
        rule.onNodeWithText("Documents › Keepfin", substring = true).assertIsDisplayed()
        rule.onNodeWithText("tap Use this folder, then Allow", substring = true).assertIsDisplayed()
        // the other two ways wait behind Other options
        rule.onNodeWithTag(SetupTags.ANOTHER).assertDoesNotExist()
        rule.onNodeWithTag(SetupTags.HAVE_FOLDER).assertDoesNotExist()
        rule.onNodeWithTag(SetupTags.MORE).assertIsDisplayed().performClick()
        rule.onNodeWithTag(SetupTags.ANOTHER).assertIsDisplayed()
        rule.onNodeWithText("I have a Keepfin folder").assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.RECOMMENDED).performClick()
        rule.onNodeWithTag(SetupTags.ANOTHER).performClick()
        rule.onNodeWithTag(SetupTags.HAVE_FOLDER).performScrollTo().performClick()
        // the folder steps have no Not now: the folder is required
        rule.onAllNodesWithText("Not now").assertCountEquals(0)
        assertEquals(listOf("recommended", "another", "existing"), r.calls)
    }

    @Test fun whereOffersTheSameButtonsBeforeAnythingIsChosen() {
        val r = Recorder()
        show(SetupUi(gate = Gate.FIRST_START, step = SetupStep.WHERE), r)
        rule.onNodeWithTag(SetupTags.RECOMMENDED).performClick()
        rule.onNodeWithTag(SetupTags.ANOTHER).performClick()
        rule.onNodeWithTag(SetupTags.CONFIRM_STEPS).assertExists()
        assertEquals(listOf("recommended", "another"), r.calls)
    }

    @Test fun aKeepfinFolderInsideTheChosenOneIsOfferedByItself() {
        val r = Recorder()
        val folder = PickedFolder("content://x", "Internal storage > Backup", FolderKind.NEW, inner = "primary:Backup/Keepfin")
        show(SetupUi(gate = Gate.FIRST_START, step = SetupStep.WELCOME, folder = folder), r)
        rule.onNodeWithText("Found a Keepfin folder inside").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.INNER).performScrollTo().performClick()
        assertEquals(listOf("inner"), r.calls)
    }

    @Test fun theFlowLaunchesThePickerInsideTheRequestedFolder() {
        val launched = ArrayList<Any?>()
        val registry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I : Any?, O : Any?> onLaunch(requestCode: Int, contract: androidx.activity.result.contract.ActivityResultContract<I, O>, input: I, options: androidx.core.app.ActivityOptionsCompat?) {
                launched += input
            }
        }
        val owner = object : androidx.activity.result.ActivityResultRegistryOwner { override val activityResultRegistry = registry }
        val r = Recorder()
        rule.setContent {
            androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) {
                KeepfinTheme(mode = ThemeMode.LIGHT) { SetupFlow(SetupUi(gate = Gate.FIRST_START, step = SetupStep.WELCOME, request = app.keepfin.setup.SetupRequest.Picker("primary:Documents/Keepfin")), r, startInAdvanced = true) }
            }
        }
        rule.waitForIdle()
        assertEquals(listOf("handled"), r.calls)
        assertEquals("content://com.android.externalstorage.documents/document/primary%3ADocuments%2FKeepfin", launched.single().toString())
    }

    @Test fun anExistingFolderIsOpenedNotCreated() {
        val r = Recorder()
        val folder = PickedFolder("content://x", "Internal storage > Keepfin", FolderKind.EXISTING)
        show(SetupUi(gate = Gate.FIRST_START, step = SetupStep.WELCOME, folder = folder), r)
        rule.onNodeWithText("Found a Keepfin folder").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.OPEN_EXISTING).performScrollTo().performClick()
        assertEquals(listOf("open"), r.calls)
    }

    @Test fun whereCreatesAfterAPickAndExplainsForbiddenPlacesOnlyWhenOneIsRefused() {
        val r = Recorder()
        show(SetupUi(gate = Gate.FIRST_START, step = SetupStep.WHERE, folder = PickedFolder("content://x", "Internal storage > Keepfin", FolderKind.NEW, existingEntries = 2, freeBytes = 5_000_000_000)), r)
        rule.onNodeWithText("Where should Keepfin keep everything?").assertIsDisplayed()
        rule.onNodeWithText("Choose a different place").assertIsDisplayed()
        // the rule about the places Android refuses is not repeated on every visit
        rule.onNodeWithText("Android does not let apps use", substring = true).assertDoesNotExist()
        rule.onNodeWithTag(SetupTags.CONFIRM_CREATE).performClick()
        assertEquals(listOf("create"), r.calls)
    }

    @Test fun aRefusedPlaceSaysWhichPlacesAndroidRefuses() {
        show(SetupUi(gate = Gate.FIRST_START, step = SetupStep.WHERE, error = SetupTexts.FORBIDDEN))
        rule.onNodeWithTag(SetupTags.ERROR).assertIsDisplayed()
        rule.onNodeWithText("Android does not let apps use", substring = true).assertExists()
    }

    @Test fun cancellingThePickerSaysAFolderIsNeeded() {
        show(SetupUi(gate = Gate.FIRST_START, step = SetupStep.WHERE, pickCancelled = true))
        rule.onNodeWithText("Nothing was chosen").assertIsDisplayed()
        rule.onNodeWithText("Choose one to go on", substring = true).assertExists()
        rule.onAllNodesWithText("Not now").assertCountEquals(0)
        rule.onNodeWithTag(SetupTags.CONFIRM_CREATE).assertDoesNotExist()
    }

    @Test fun anUnsupportedPlaceShowsItsErrorAndKeepsGoingPossible() {
        show(SetupUi(gate = Gate.FIRST_START, step = SetupStep.WHERE, error = "Keepfin can keep its folder on this phone or its memory card for now. Pick a folder there."))
        rule.onNodeWithTag(SetupTags.ERROR).assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.RECOMMENDED).assertIsEnabled()
    }

    @Test fun setupShowsWhatYouHaveThenLetsEachSpaceMoveOrLeave() {
        val r = Recorder()
        show(SetupUi(gate = Gate.SETUP, step = SetupStep.WHAT_YOU_HAVE, inventory = inventory), r)
        // Someone updating first reads what changed and what the steps do.
        rule.onNodeWithText(SetupTexts.UPGRADE_TITLE).assertIsDisplayed()
        rule.onNodeWithText("One folder for everything").assertExists()
        // what you have is one line here; the spaces are chosen on the plan screen
        rule.onNodeWithTag(SetupTags.SUMMARY).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("You have 126 files", substring = true).assertExists()
        rule.onNodeWithText("31 favourites", substring = true).assertExists()
        rule.onNodeWithTag(SetupTags.source("music")).assertDoesNotExist()
        rule.onNodeWithTag(SetupTags.CONTINUE).performClick()
        assertEquals(listOf("start"), r.calls)
    }

    private val folder8 = PickedFolder("content://x", "Internal storage > Documents > Keepfin", FolderKind.NEW, freeBytes = 8_000_000_000)
    private val plan = PlanUi(moveCount = 120, moveBytes = 900_000_000, leaveCount = 6, foreignMoves = 20, fits = true, freeBytes = 8_000_000_000, permissions = emptyList())
    private fun planUi(p: PlanUi = plan, inv: InventoryUi = inventory) = SetupUi(gate = Gate.SETUP, step = SetupStep.REVIEW, folder = folder8, inventory = inv, plan = p)

    @Test fun thePlanSetsMoveOrKeepPerSpaceOnOneLine() {
        val r = Recorder()
        show(planUi(), r)
        rule.onNodeWithText("Set up your Keepfin folder").assertIsDisplayed()
        rule.onNodeWithText("120 · 858 MB", substring = true).assertExists()
        rule.onNodeWithTag(SetupTags.choice("videos", true)).performScrollTo().performClick()
        rule.onNodeWithTag(SetupTags.choice("music", false)).performScrollTo().performClick()
        rule.onNodeWithText("Android asks before removing the 20 from before a reinstall", substring = true).assertExists()
        assertEquals(listOf("choice:videos:move", "choice:music:leave"), r.calls)
    }

    @Test fun thePlanStartsWhenItFitsAndAccessIsGranted() {
        val r = Recorder()
        show(planUi(), r)
        rule.onNodeWithText("Copies 120 files (858 MB)", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.START_RUN).assertIsEnabled().performClick()
        assertEquals(listOf("begin"), r.calls)
    }

    @Test fun thePlanBlocksStartWhenItDoesNotFit() {
        show(planUi(plan.copy(fits = false)))
        rule.onNodeWithText("There is not enough room", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.START_RUN).assertIsNotEnabled()
    }

    @Test fun thePlanAsksForAccessBeforeStarting() {
        show(planUi(plan.copy(permissions = listOf("android.permission.READ_MEDIA_AUDIO"))))
        rule.onNodeWithTag(SetupTags.ALLOW_FILES).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.START_RUN).assertIsNotEnabled()
    }

    @Test fun runningShowsProgressAndAStoppedRunOffersContinue() {
        val r = Recorder()
        show(SetupUi(gate = Gate.SETUP, step = SetupStep.RUNNING, running = true, progress = ProgressUi("copying", 40, 120, 300_000_000, 900_000_000, "song40.mp3")), r)
        rule.onNodeWithText("Copying your files").assertIsDisplayed()
        rule.onNodeWithText("40 of 120 files", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.CONTINUE).assertDoesNotExist()
    }

    @Test fun aStoppedRunContinuesFromTheJournal() {
        val r = Recorder()
        show(SetupUi(gate = Gate.SETUP, step = SetupStep.RUNNING, running = false, stopped = "Setup is paused. It carries on where it stopped."), r)
        rule.onNodeWithText("Setup is paused", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.CONTINUE).performClick()
        assertEquals(listOf("resume"), r.calls)
    }

    @Test fun consentCanBeRefusedWithoutLosingAnything() {
        val r = Recorder()
        show(SetupUi(gate = Gate.SETUP, step = SetupStep.CONSENT, consent = ConsentUi(20, 1, 1)), r)
        rule.onNodeWithText("20 files can be removed").assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.CONSENT_KEEP).performClick()
        rule.onNodeWithTag(SetupTags.CONSENT_ALLOW).performClick()
        assertEquals(listOf("consent:false", "consentRequest", "consent:false"), r.calls) // the fake offers no request, so the app treats it as a refusal
    }

    @Test fun theReportListsWhatStayed() {
        val r = Recorder()
        val report = MigrationReport("run", 118, 6, listOf(ReportEntry("Lost song", "The file is no longer on this phone.")), listOf(ReportEntry("Old song", "You chose to keep the original.")), 900_000_000, 2, 1, emptyList(), 4, 2, 31)
        show(SetupUi(gate = Gate.SETUP, step = SetupStep.REPORT, report = report), r)
        rule.onNodeWithText("118 moved, 6 left where they are").assertIsDisplayed()
        rule.onNodeWithText("1 could not be brought over").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("1 old copies stay where they are").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.DONE).performClick()
        assertEquals(listOf("finish"), r.calls)
    }

    // ---- the visual steps

    @Test fun welcomeIsShortAndOneTapAway() {
        val r = Recorder()
        show(SetupUi(gate = Gate.FIRST_START, step = SetupStep.WELCOME), r)
        // one prominent button; no folder picture or hint cards on the first screen
        rule.onNodeWithTag(SetupTags.FOLDER_TREE).assertDoesNotExist()
        rule.onAllNodesWithText("Not now").assertCountEquals(0)
        rule.onNodeWithTag(SetupTags.MORE).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText(SetupTexts.HAVE_FOLDER_HINT).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.HAVE_FOLDER).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test fun aChosenFolderShowsTheOtherOptionsWithoutAskingAgain() {
        show(SetupUi(gate = Gate.FIRST_START, step = SetupStep.WELCOME, folder = PickedFolder("content://x", "Internal storage > Old", FolderKind.NEW)))
        rule.onNodeWithTag(SetupTags.ANOTHER).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.MORE).assertDoesNotExist()
    }

    @Test fun beforeAPlaceIsChosenThePlanOffersTheRecommendedOneAndWaits() {
        val r = Recorder()
        show(SetupUi(gate = Gate.SETUP, step = SetupStep.WHERE, inventory = inventory), r)
        rule.onNodeWithTag(SetupTags.WHERE_CARD).assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.RECOMMENDED).performClick()
        rule.onNodeWithTag(SetupTags.ANOTHER).performClick()
        rule.onNodeWithTag(SetupTags.START_RUN).assertIsNotEnabled()
        assertEquals(listOf("recommended", "another"), r.calls)
    }

    @Test fun thePlanShowsThePlaceWithItsFreeSpaceAndChange() {
        val r = Recorder()
        show(planUi(), r)
        rule.onNodeWithTag(SetupTags.BREADCRUMB).assertIsDisplayed()
        rule.onNodeWithText("Internal storage > Documents > Keepfin").assertExists()
        rule.onNodeWithText("free", substring = true).assertExists()
        rule.onNodeWithTag(SetupTags.CHANGE_PLACE).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("another"), r.calls)
    }

    @Test fun thePlanMarksEachSpacesChoice() {
        show(planUi())
        rule.onNodeWithTag(SetupTags.choice("music", true)).performScrollTo().assertIsSelected()
        rule.onNodeWithTag(SetupTags.choice("videos", false)).performScrollTo().assertIsSelected()
        rule.onNodeWithTag(SetupTags.choice("music", true)).assertHeightIsAtLeast(48.dp)
    }

    @Test fun thePlanSaysWhatStartDoesInOneSentence() {
        show(planUi())
        rule.onNodeWithTag(SetupTags.REVIEW).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("checks every one, then removes the old copies", substring = true).assertExists()
        rule.onNodeWithText("6 stay where they are", substring = true).assertExists()
    }

    @Test fun runningShowsTheStepperAtTheRightPhase() {
        show(SetupUi(gate = Gate.SETUP, step = SetupStep.RUNNING, running = true, progress = ProgressUi("committing", 120, 120, 900_000_000, 900_000_000, null)))
        rule.onNodeWithTag(SetupTags.STEPPER).assertIsDisplayed()
        rule.onNodeWithText("Writing your library").assertIsDisplayed()
        rule.onNodeWithText("Tidy").assertExists()
    }

    @Test fun theReportShowsBeforeAndAfterTiles() {
        val report = MigrationReport("run", 118, 6, emptyList(), emptyList(), 900_000_000, 2, 0, emptyList(), 4, 2, 31)
        show(SetupUi(gate = Gate.SETUP, step = SetupStep.REPORT, report = report))
        rule.onNodeWithTag(SetupTags.REPORT_TILES).assertIsDisplayed()
        rule.onNodeWithText("moved into the Keepfin folder").assertExists()
        rule.onNodeWithText("favourites", useUnmergedTree = true).performScrollTo().assertExists()
    }

    @Test fun theFlowWorksAtLargeFontAndNarrowWidth() {
        rule.setContent {
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, 1.6f)) {
                KeepfinTheme(mode = ThemeMode.LIGHT) { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.width(320.dp)) { SetupFlow(SetupUi(gate = Gate.SETUP, step = SetupStep.MOVE_OR_LEAVE, inventory = inventory), Recorder()) } }
            }
        }
        rule.onNodeWithTag(SetupTags.choice("music", true)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.CONTINUE).assertIsDisplayed()
    }

    @Test fun moveOrLeaveSkipsEmptyParts() {
        show(SetupUi(gate = Gate.SETUP, step = SetupStep.MOVE_OR_LEAVE, inventory = inventory))
        rule.onNodeWithTag("setup-space-music").assertExists()
        rule.onNodeWithTag("setup-space-downloads").assertDoesNotExist()
    }

    @Test fun otherFilesAreOfferedOnThePlanAndCanBeLeftOut() {
        val r = Recorder()
        show(planUi(inv = inventory.copy(others = 240, otherBytes = 5_000_000_000)), r)
        rule.onNodeWithText("240 other files Keepfin saved before").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.OTHERS_TOGGLE).performScrollTo().assertIsOn().performClick()
        assertEquals(listOf("others:false"), r.calls)
    }

    @Test fun theOfferOfOtherFilesIsShownWithoutScrollingOnASmallPhone() {
        rule.setContent {
            KeepfinTheme(mode = ThemeMode.LIGHT) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(360.dp, 640.dp)) {
                    SetupFlow(planUi(inv = inventory.copy(others = 240, otherBytes = 5_000_000_000)), Recorder())
                }
            }
        }
        rule.onNodeWithText("240 other files Keepfin saved before").assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.OTHERS_TOGGLE).assertIsDisplayed()
    }

    @Test fun otherFilesAskForTheMusicAndVideoPermissionFirst() {
        show(planUi(inv = inventory.copy(otherPermissions = listOf("android.permission.READ_MEDIA_AUDIO"))))
        rule.onNodeWithTag(SetupTags.OTHERS_LOOK).performScrollTo().assertIsDisplayed()
    }

    @Test fun anInterruptedRunContinuesByItselfAndSaysSo() {
        show(SetupUi(gate = Gate.SETUP, step = SetupStep.RUNNING, running = true, resumed = true))
        rule.onNodeWithText("Continuing…").assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.CONTINUE).assertDoesNotExist()
    }

    @Test fun earlierFilesAskThenOfferToBringThemIn() {
        val r = Recorder()
        show(SetupUi(gate = Gate.FIRST_START, step = SetupStep.EARLIER, earlier = app.keepfin.setup.EarlierUi(needsPermission = true, permissions = listOf("android.permission.READ_MEDIA_AUDIO"))), r)
        rule.onNodeWithText("Files Keepfin saved before").assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.EARLIER_LOOK).assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.EARLIER_NOT_NOW).performClick()
        assertEquals(listOf("skipEarlier"), r.calls)
    }

    @Test fun foundEarlierFilesCanBeBroughtIn() {
        val r = Recorder()
        show(SetupUi(gate = Gate.FIRST_START, step = SetupStep.EARLIER, earlier = app.keepfin.setup.EarlierUi(false, files = 240, bytes = 5_000_000_000, places = listOf("Music" to 200, "Videos" to 40))), r)
        rule.onNodeWithTag(SetupTags.EARLIER_FOUND).assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.EARLIER_BRING).assertIsDisplayed()
        rule.onNodeWithText("Bring in 240 files Keepfin saved before").assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.EARLIER_BRING).performClick()
        assertEquals(listOf("bring"), r.calls)
    }

    @Test fun settingsOffersToBringInEarlierFiles() {
        val calls = ArrayList<String>()
        rule.setContent { KeepfinTheme(mode = ThemeMode.LIGHT) { StorageContent(StorageStatus.Ok("Internal storage > Documents > Keepfin", 5_000_000_000, 4), null, {}, {}, onEarlier = { calls += "earlier" }) } }
        rule.onNodeWithTag(StorageTags.EARLIER).performClick()
        assertEquals(listOf("earlier"), calls)
    }

    // ---- Settings > Storage

    private fun storage(status: StorageStatus?, message: String? = null, calls: MutableList<String> = ArrayList()) {
        rule.setContent {
            KeepfinTheme(mode = ThemeMode.LIGHT) {
                StorageContent(status, message, { calls += "check" }, { calls += "repick" })
            }
        }
    }

    @Test fun storageShowsThePathAndFreeSpace() {
        val calls = ArrayList<String>()
        storage(StorageStatus.Ok("Internal storage > Keepfin", 5_000_000_000, 4), calls = calls)
        rule.onNodeWithText("Internal storage > Keepfin").assertIsDisplayed()
        rule.onNodeWithText("4.7 GB free", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(StorageTags.CHECK).performClick()
        assertEquals(listOf("check"), calls)
    }

    @Test fun storageNeedsAccessAgainOffersTheSameFolder() {
        val calls = ArrayList<String>()
        storage(StorageStatus.NeedsAccess("Internal storage > Keepfin"), calls = calls)
        rule.onNodeWithText("Keepfin needs access to this folder again").assertIsDisplayed()
        rule.onNodeWithText("Nothing is missing", substring = true).assertExists()
        rule.onNodeWithTag(StorageTags.REPICK).performClick()
        assertEquals(listOf("repick"), calls)
    }

    @Test fun storageUnavailableSaysNothingIsMissing() {
        storage(StorageStatus.Unavailable("Memory card > Keepfin"))
        rule.onNodeWithText("The Keepfin folder is not available").assertIsDisplayed()
        rule.onNodeWithText("Nothing is missing", substring = true).assertExists()
    }

    @Test fun storageOffersToFindLinkedFilesAndShowsTheResult() {
        val calls = ArrayList<String>()
        var found by mutableStateOf<String?>(null)
        rule.setContent {
            KeepfinTheme(mode = ThemeMode.LIGHT) {
                StorageContent(StorageStatus.Ok("Internal storage > Keepfin", 5_000_000_000, 4), null, {}, {}, linkedCount = 12, linkedMessage = found, onFindLinked = { calls += "find"; found = "Found 10 linked files. 2 could not be found; they stay in your library and can be found again later." })
            }
        }
        rule.onNodeWithText("12 linked files need to be found again").assertIsDisplayed()
        rule.onNodeWithTag(StorageTags.FIND_LINKED).performClick()
        assertEquals(listOf("find"), calls)
        rule.onNodeWithText("Found 10 linked files.", substring = true).assertIsDisplayed()
    }

    @Test fun storageHidesLinkedFilesWhenThereIsNothingToFind() {
        storage(StorageStatus.Ok("Internal storage > Keepfin", 5_000_000_000, 4))
        rule.onNodeWithTag(StorageTags.LINKED).assertDoesNotExist()
    }

    @Test fun storageWithoutAFolderHasNoSetUpButton() {
        storage(StorageStatus.NoFolder)
        rule.onNodeWithText("No Keepfin folder yet").assertIsDisplayed()
        rule.onAllNodesWithText("Set up the Keepfin folder").assertCountEquals(0)
    }

    // ---- screenshots (opt-in: -Pandroid.testInstrumentationRunnerArguments.screenshots=true)

    private fun shot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        Thread.sleep(700)
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/kf-$name.png").close()
        Thread.sleep(500)
    }

    @Test fun screenshots() {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        var dark by androidx.compose.runtime.mutableStateOf(false)
        var screen by androidx.compose.runtime.mutableStateOf<@androidx.compose.runtime.Composable () -> Unit>({})
        rule.setContent { KeepfinTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) { screen() } }
        for (isDark in listOf(false, true)) {
            val mode = if (isDark) "dark" else "light"
            val states = mapOf(
                "first-start" to SetupUi(gate = Gate.FIRST_START, step = SetupStep.WELCOME),
                "first-start-where" to SetupUi(gate = Gate.FIRST_START, step = SetupStep.WHERE, folder = PickedFolder("content://x", "Internal storage > Keepfin", FolderKind.NEW, existingEntries = 2, freeBytes = 48_000_000_000)),
                "earlier-found" to SetupUi(gate = Gate.FIRST_START, step = SetupStep.EARLIER, earlier = app.keepfin.setup.EarlierUi(false, files = 240, bytes = 5_000_000_000, places = listOf("Music" to 200, "Videos" to 40))),
                "what-you-have" to SetupUi(gate = Gate.SETUP, step = SetupStep.WHAT_YOU_HAVE, inventory = inventory),
                "where" to SetupUi(gate = Gate.SETUP, step = SetupStep.WHERE, inventory = inventory, folder = PickedFolder("content://x", "Internal storage > Keepfin", FolderKind.NEW, freeBytes = 8_000_000_000)),
                "plan-review" to SetupUi(gate = Gate.SETUP, step = SetupStep.REVIEW, plan = plan, inventory = inventory, folder = folder8),
                "move-or-leave" to SetupUi(gate = Gate.SETUP, step = SetupStep.MOVE_OR_LEAVE, inventory = inventory),
                "running" to SetupUi(gate = Gate.SETUP, step = SetupStep.RUNNING, running = true, progress = ProgressUi("copying", 40, 120, 300_000_000, 900_000_000, "song40.mp3")),
                "consent" to SetupUi(gate = Gate.SETUP, step = SetupStep.CONSENT, consent = app.keepfin.setup.ConsentUi(118, 1, 1)),
                "report" to SetupUi(gate = Gate.SETUP, step = SetupStep.REPORT, report = MigrationReport("run", 118, 6, listOf(ReportEntry("Lost song", "The file is no longer on this phone.")), listOf(ReportEntry("Old song", "You chose to keep the original.")), 900_000_000, 2, 1, emptyList(), 4, 2, 31)),
            )
            for ((name, ui) in states) {
                rule.runOnUiThread { dark = isDark; screen = { SetupFlow(ui, Recorder()) } }
                rule.waitForIdle()
                shot("$name-$mode")
            }
            rule.runOnUiThread { dark = isDark; screen = { StorageContent(StorageStatus.Ok("Internal storage > Keepfin", 5_000_000_000, 4), null, {}, {}) } }
            rule.waitForIdle()
            shot("storage-$mode")
        }
    }
}