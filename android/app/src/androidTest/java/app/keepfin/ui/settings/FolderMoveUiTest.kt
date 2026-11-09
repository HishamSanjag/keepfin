package app.keepfin.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.OutsideSpace
import app.keepfin.move.BringChoice
import app.keepfin.move.BringInUi
import app.keepfin.move.BringStage
import app.keepfin.move.MoveCheck
import app.keepfin.move.MoveProgress
import app.keepfin.move.MoveStage
import app.keepfin.move.MoveUi
import app.keepfin.ui.theme.KeepfinTheme
import app.keepfin.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The screens of Change location and Bring them in, over states and recorded calls (the real moves are tested in FolderMoveInstrumentedTest). */
@RunWith(AndroidJUnit4::class)
class FolderMoveUiTest {
    @get:Rule val rule = createComposeRule()

    private val calls = ArrayList<String>()
    private var ui by mutableStateOf(MoveUi())

    private fun showMove() {
        rule.setContent {
            KeepfinTheme(mode = ThemeMode.LIGHT) {
                MoveFolderDialog(
                    ui, "Internal storage > Keepfin", { calls += "pick" }, { calls += "back" }, { calls += "start" }, { calls += "stop" }, { calls += "resume" },
                    { calls += "delete" }, { calls += "keep" }, { calls += "close" },
                )
            }
        }
    }

    private val check = MoveCheck("tree", "Memory card > Keepfin", 120, 2_000_000_000, 8_000_000_000, null)

    @Test fun whereAsksForAFolderAndSaysWhyOneCannotBeUsed() {
        showMove()
        rule.onNodeWithText("Where should your Keepfin folder go?").assertIsDisplayed()
        rule.onNodeWithText("Everything in Internal storage > Keepfin moves there", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Want a second copy instead? Set up a backup in Copies.").assertIsDisplayed()
        rule.onNodeWithTag(MoveTags.PICK).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("pick"), calls)
        rule.runOnUiThread { ui = MoveUi(check = MoveCheck("t", "Internal storage > Keepfin > Sub", 0, 0, null, "The new place cannot be inside your Keepfin folder.")) }
        rule.onNodeWithTag(MoveTags.PROBLEM).assertIsDisplayed()
        rule.onNodeWithText("The new place cannot be inside your Keepfin folder.").assertIsDisplayed()
        rule.onNodeWithText("Choose another folder").assertIsDisplayed()
        rule.runOnUiThread { ui = MoveUi(error = "Keepfin lost access to a folder. Choose it again in Storage.") }
        rule.onNodeWithText("Keepfin lost access", substring = true).assertIsDisplayed()
    }

    @Test fun reviewShowsThePlanAndStartNeedsRoom() {
        ui = MoveUi(check = check, fromLabel = "Internal storage > Keepfin")
        showMove()
        rule.onNodeWithText("The plan").assertIsDisplayed()
        rule.onNodeWithText("Internal storage > Keepfin").assertIsDisplayed()
        rule.onNodeWithText("Memory card > Keepfin").assertIsDisplayed()
        rule.onNodeWithText("Copied to the new place and every file checked.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Nothing happens until you press Start.", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(MoveTags.START).assertIsEnabled().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag(MoveTags.BACK).performClick()
        assertEquals(listOf("start", "back"), calls)
        rule.runOnUiThread { ui = ui.copy(check = check.copy(freeBytes = 1_000_000_000)) }
        rule.onNodeWithTag(MoveTags.NO_ROOM).assertIsDisplayed()
        rule.onNodeWithTag(MoveTags.START).assertIsNotEnabled()
    }

    @Test fun theRunShowsProgressAndStopAsksFirst() {
        ui = MoveUi(stage = MoveStage.RUNNING, toLabel = "Memory card > Keepfin", progress = MoveProgress("copying", 12, 120, 500_000_000, 2_000_000_000, "Music/Rock/a.mp3"))
        showMove()
        rule.onNodeWithText("Copying and checking your files").assertIsDisplayed()
        rule.onNodeWithText("12 of 120 files", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Music/Rock/a.mp3").assertIsDisplayed()
        rule.onNodeWithText("even with the screen off", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(MoveTags.STOP).performClick()
        assertTrue("Stop alone changes nothing", calls.isEmpty())
        rule.onNodeWithText("Stop now? Your Keepfin folder stays where it is", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(MoveTags.STOP_CONFIRM).performClick()
        assertEquals(listOf("stop"), calls)
        rule.runOnUiThread { ui = ui.copy(progress = MoveProgress("switching", 0, 0, 0, 0, null)) }
        rule.onNodeWithText("Opening the new place").assertIsDisplayed()
        assertEquals("no Stop while the profile switches", 0, rule.onAllNodesWithTag(MoveTags.STOP).fetchSemanticsNodes().size)
    }

    @Test fun anInterruptedMoveOffersContinueAndAFailedOneSaysTheFolderIsWhereItWas() {
        ui = MoveUi(stage = MoveStage.INTERRUPTED, message = "The move was paused. Your Keepfin folder is still where it was.")
        showMove()
        rule.onNodeWithText("The move is paused").assertIsDisplayed()
        rule.onNodeWithTag(MoveTags.CONTINUE).performClick()
        assertEquals(listOf("resume"), calls)
        rule.runOnUiThread { ui = MoveUi(stage = MoveStage.FAILED, message = "There is not enough room in the new place.") }
        rule.onNodeWithText("The move stopped").assertIsDisplayed()
        rule.onNodeWithText("There is not enough room in the new place.").assertIsDisplayed()
        rule.onNodeWithText("Your Keepfin folder is still where it was.").assertIsDisplayed()
        rule.onNodeWithText("Try again").assertIsDisplayed()
    }

    @Test fun afterTheMoveTheOldFolderIsOnlyDeletedAfterAConfirmation() {
        ui = MoveUi(stage = MoveStage.DONE, oldPending = true, fromLabel = "Internal storage > Keepfin", toLabel = "Memory card > Keepfin")
        showMove()
        rule.onNodeWithTag(MoveTags.DONE_TITLE).assertIsDisplayed()
        rule.onNodeWithText("Every file was checked in the new place.").assertIsDisplayed()
        rule.onNodeWithText("Keepfin never deletes it by itself.", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(MoveTags.DELETE_OLD).assertHeightIsAtLeast(48.dp).performClick()
        assertTrue("the first tap only asks", calls.isEmpty())
        rule.onNodeWithText("This cannot be undone.", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(MoveTags.DELETE_CONFIRM).performClick()
        assertEquals(listOf("delete"), calls)
        rule.runOnUiThread { ui = ui.copy(oldPending = false, note = "The old folder was deleted.") }
        rule.onNodeWithText("The old folder was deleted.").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag(MoveTags.DELETE_OLD).fetchSemanticsNodes().size)
    }

    @Test fun keepingTheOldFolderIsOneTapAndSaysWhatThatMeans() {
        ui = MoveUi(stage = MoveStage.DONE, oldPending = true, fromLabel = "Internal storage > Keepfin", toLabel = "Memory card > Keepfin")
        showMove()
        rule.onNodeWithTag(MoveTags.KEEP_OLD).performClick()
        assertEquals(listOf("keep"), calls)
        rule.runOnUiThread { ui = ui.copy(oldPending = false, note = "The old folder stays where it is. It still holds a copy of your library, so do not open it as your Keepfin folder.") }
        rule.onNodeWithText("do not open it as your Keepfin folder", substring = true).assertIsDisplayed()
    }

    // ---- Storage: the entry, the notice and the banners

    private var bring by mutableStateOf(BringInUi())
    private val outside = listOf(OutsideSpace("music", "Music", 12, "Music"), OutsideSpace("videos", "Videos", 3, "Videos"))

    private fun showLocation(move: MoveUi, spaces: List<OutsideSpace> = emptyList()) {
        rule.setContent {
            KeepfinTheme(mode = ThemeMode.LIGHT) {
                FolderLocationContent(move, spaces, bring, { calls += "change" }, { calls += "openMove" }, { calls += "openBring" }, { calls += "resumeMove" }, { calls += "resumeBring" })
            }
        }
    }

    @Test fun storageOffersChangeLocationAndTheOutsideNotice() {
        showLocation(MoveUi(), outside)
        rule.onNodeWithTag(MoveTags.CHANGE).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("2 spaces are outside your Keepfin folder.").assertIsDisplayed()
        rule.onNodeWithTag(BringTags.OPEN).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("change", "openBring"), calls)
    }

    @Test fun oneOutsideSpaceIsNamed() {
        showLocation(MoveUi(), outside.take(1))
        rule.onNodeWithText("Music is outside your Keepfin folder.").assertIsDisplayed()
    }

    @Test fun anUnfinishedMoveAndAnOldFolderShowTheirOwnBanners() {
        showLocation(MoveUi(stage = MoveStage.INTERRUPTED, message = "Moving your Keepfin folder stopped before it finished. Your Keepfin folder is still where it was."))
        rule.onNodeWithText("A move of your Keepfin folder is not finished").assertIsDisplayed()
        rule.onNodeWithText("Continue").performClick()
        assertEquals(listOf("resumeMove"), calls)
    }

    @Test fun theOldFolderBannerStaysUntilTheDecision() {
        showLocation(MoveUi(stage = MoveStage.DONE, oldPending = true, fromLabel = "Internal storage > Keepfin"))
        rule.onNodeWithText("The old folder is still at Internal storage > Keepfin.", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(MoveTags.BANNER_OPEN).performClick()
        assertEquals(listOf("openMove"), calls)
    }

    // ---- Bring them in

    private fun showBring(onStart: (List<BringChoice>) -> Unit = {}) {
        rule.setContent {
            KeepfinTheme(mode = ThemeMode.LIGHT) {
                BringInDialog(bring, onStart, { calls += "resume" }, { calls += "stop" }, { calls += "ask" }, { calls += "close" })
            }
        }
    }

    @Test fun bringInChoosesMoveInOrLeavePerSpaceWithMoveInPreselected() {
        bring = BringInUi(spaces = outside)
        var started: List<BringChoice>? = null
        showBring { started = it }
        rule.onNodeWithText("Bring these spaces into your Keepfin folder").assertIsDisplayed()
        rule.onNodeWithText("Each space saves its new files in your Keepfin folder. Choose what happens to the files it already has.").assertIsDisplayed()
        rule.onNodeWithText("Into Music · 12 files").assertIsDisplayed()
        rule.onNodeWithTag(BringTags.leave("videos")).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("Move in checks each copy before the original goes.", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(BringTags.START).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(BringChoice("music", true), BringChoice("videos", false)), started)
    }

    @Test fun aSingleSpaceIsNamedInTheTitle() {
        bring = BringInUi(spaces = outside.take(1))
        showBring()
        rule.onNodeWithText("Bring Music into your Keepfin folder").assertIsDisplayed()
    }

    @Test fun theRunShowsProgressThenTheResultAndAndroidsQuestion() {
        bring = BringInUi(stage = BringStage.RUNNING, spaces = outside, done = 4, total = 15, current = "Song.mp3")
        showBring()
        rule.onNodeWithText("4 of 15 files").assertIsDisplayed()
        rule.onNodeWithText("Song.mp3").assertIsDisplayed()
        rule.onNodeWithText("even with the screen off", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(BringTags.STOP).performClick()
        rule.runOnUiThread { bring = BringInUi(stage = BringStage.DONE, moved = 14, failed = listOf("Old.mp3 could not be read.")) }
        rule.onNodeWithText("Brought 14 files into your Keepfin folder. 1 file stayed where it was.").assertIsDisplayed()
        rule.onNodeWithText("Old.mp3 could not be read.").assertIsDisplayed()
        rule.onNodeWithTag(BringTags.CLOSE).performClick()
        assertEquals(listOf("stop", "close"), calls)
    }

    @Test fun anInterruptedRunOffersToContinue() {
        bring = BringInUi(stage = BringStage.INTERRUPTED, spaces = outside)
        showBring()
        rule.onNodeWithText("What was brought in is checked and stays.", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(BringTags.CONTINUE).performClick()
        assertEquals(listOf("resume"), calls)
    }
}
