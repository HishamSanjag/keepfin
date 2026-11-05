package app.keepfin.ui.devices

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.net.PairingUi
import app.keepfin.ui.theme.KeepfinTheme
import app.keepfin.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The dialog that shows another device's pairing request over any screen: the numbers, the answers, then the result. */
@RunWith(AndroidJUnit4::class)
class IncomingPairingDialogTest {
    @get:Rule val rule = createComposeRule()

    private fun ui(stage: String, method: String = "sas", sas: String? = "123456", error: String? = null) =
        PairingUi("p1", method, "answering", stage, "Work laptop", "1111 2222 3333 4444 5555", sas, error, System.currentTimeMillis() + 100_000)

    @Test fun showsTheNumbersAndSendsBothAnswers() {
        val calls = ArrayList<String>()
        rule.setContent { KeepfinTheme(ThemeMode.LIGHT) { IncomingPairingDialog(ui("comparing")) { id, match -> calls += "$id:$match" } } }
        rule.onNodeWithTag(DevicesTags.INCOMING_DIALOG).assertIsDisplayed()
        rule.onNodeWithText("123 456").assertIsDisplayed()
        rule.onNodeWithText("Work laptop wants to connect").assertIsDisplayed()
        rule.onNodeWithText("Check that both devices show this code, then tap Codes match on both.").assertIsDisplayed()
        rule.onNodeWithText("Codes match").assertIsDisplayed()
        rule.onNodeWithText("They don't match").assertIsDisplayed()
        // the two answers are stacked at full width
        val match = rule.onNodeWithTag(DevicesTags.SAS_MATCH).getUnclippedBoundsInRoot()
        val decline = rule.onNodeWithTag(DevicesTags.SAS_DIFFER).getUnclippedBoundsInRoot()
        assertTrue("stacked, Codes match on top: $match vs $decline", match.bottom <= decline.top)
        assertEquals("same width", match.right.value - match.left.value, decline.right.value - decline.left.value, 1f)
        rule.onNodeWithTag(DevicesTags.SAS_MATCH).assertHeightIsAtLeast(48.dp).performClick()
        // after the tap: the waiting line and only Cancel (= decline)
        rule.onNodeWithText("You confirmed the code on this phone.").assertIsDisplayed()
        rule.onNodeWithText("On Work laptop, tap Codes match to finish.").assertIsDisplayed()
        rule.onNodeWithText("Waiting for Work laptop…").assertIsDisplayed()
        rule.onNodeWithTag(DevicesTags.SAS_MATCH).assertDoesNotExist()
        rule.onNodeWithTag(DevicesTags.SAS_DIFFER).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("p1:true", "p1:false"), calls)
    }

    @Test fun aRequestFromAnotherDeviceWithoutAWindowShowsTheSameDialog() {
        val unrequested = ui("comparing").copy(unrequested = true)
        rule.setContent { KeepfinTheme(ThemeMode.LIGHT) { IncomingPairingDialog(unrequested) { _, _ -> } } }
        rule.onNodeWithText("Work laptop wants to connect").assertIsDisplayed()
        rule.onNodeWithText("Codes match").assertIsDisplayed()
    }

    @Test fun aDeviceThatConnectedByAScanIsNeverAskedAndGetsAnUndoNotice() {
        val said = ArrayList<String>()
        val undone = ArrayList<String>()
        var finished = 0
        var undo: (() -> Unit)? = null
        val scanned = ui("done", "qr", null).copy(autoApproved = true, peerDeviceId = "dev-laptop")
        rule.setContent {
            KeepfinTheme(ThemeMode.LIGHT) {
                IncomingPairingLayer(scanned, { _, _ -> }, say = { text, label, action -> said += "$text|$label"; undo = action }, undo = { undone += it }, finish = { finished++ })
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag(DevicesTags.INCOMING_DIALOG).assertDoesNotExist()
        assertEquals(listOf("Work laptop connected|Undo"), said)
        assertEquals(1, finished)
        // Undo removes the device the scan just paired
        undo!!.invoke()
        assertEquals(listOf("dev-laptop"), undone)
    }

    @Test fun aScanThatIsStillWaitingIsNotAnnounced() {
        val said = ArrayList<String>()
        val pending = ui("waiting", "qr", null).copy(autoApproved = true, peerDeviceId = "dev-laptop")
        rule.setContent { KeepfinTheme(ThemeMode.LIGHT) { IncomingPairingLayer(pending, { _, _ -> }, say = { t, _, _ -> said += t }, undo = {}, finish = {}) } }
        rule.waitForIdle()
        assertTrue(said.isEmpty())
        rule.onNodeWithTag(DevicesTags.INCOMING_DIALOG).assertDoesNotExist()
    }

    @Test fun aScannedCodeAsksAllowOrDecline() {
        val calls = ArrayList<String>()
        rule.setContent { KeepfinTheme(ThemeMode.LIGHT) { IncomingPairingDialog(ui("approving", "qr", null)) { id, match -> calls += "$id:$match" } } }
        rule.onNodeWithText("Work laptop wants to connect").assertIsDisplayed()
        rule.onNodeWithTag(DevicesTags.APPROVE).performClick()
        rule.onNodeWithTag(DevicesTags.DECLINE).performClick()
        assertEquals(listOf("p1:true", "p1:false"), calls)
    }

    @Test fun afterTheDecisionTheResultShowsOnceAndCloses() {
        var current by mutableStateOf<PairingUi?>(ui("comparing"))
        rule.setContent { KeepfinTheme(ThemeMode.LIGHT) { IncomingPairingDialog(current) { _, _ -> } } }
        rule.onNodeWithTag(DevicesTags.SAS).assertIsDisplayed()
        current = ui("done", sas = null)
        rule.waitForIdle()
        rule.onNodeWithTag(DevicesTags.PAIRING_DONE).assertIsDisplayed()
        rule.onNodeWithTag(DevicesTags.PAIRING_RESULT_OK).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag(DevicesTags.INCOMING_DIALOG).assertDoesNotExist()
    }

    @Test fun aFinishedPairingThisDialogNeverShowedStaysHidden() {
        rule.setContent { KeepfinTheme(ThemeMode.LIGHT) { IncomingPairingDialog(ui("failed", sas = null, error = "The pairing took too long.")) { _, _ -> } } }
        rule.onNodeWithTag(DevicesTags.INCOMING_DIALOG).assertDoesNotExist()
    }

    @Test fun anAskingPairingIsNeverShownHere() {
        rule.setContent { KeepfinTheme(ThemeMode.LIGHT) { IncomingPairingDialog(ui("comparing").let { it.copy(role = "asking") }) { _, _ -> } } }
        rule.onNodeWithTag(DevicesTags.INCOMING_DIALOG).assertDoesNotExist()
    }

    @Test fun theAnsweringSideIsOfferedTheSetupToo() {
        var current by mutableStateOf<PairingUi?>(ui("comparing").copy(peerDeviceId = "dev-laptop"))
        val offered = ArrayList<String>()
        rule.setContent { KeepfinTheme(ThemeMode.LIGHT) { IncomingPairingDialog(current, onSetUp = { offered += "${it.peerDeviceId}:${it.peerName}" }, decide = { _, _ -> }) } }
        current = ui("done", sas = null).copy(peerDeviceId = "dev-laptop")
        rule.waitForIdle()
        rule.onNodeWithText("Work laptop is connected").assertIsDisplayed()
        rule.onNodeWithText("Choose what stays up to date.").assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.PROMPT_LATER).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(SetupTags.PROMPT_SET_UP).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("dev-laptop:Work laptop"), offered)
        rule.onNodeWithTag(DevicesTags.INCOMING_DIALOG).assertDoesNotExist()
    }

    @Test fun laterOnTheAnsweringSideJustCloses() {
        var current by mutableStateOf<PairingUi?>(ui("comparing").copy(peerDeviceId = "dev-laptop"))
        rule.setContent { KeepfinTheme(ThemeMode.LIGHT) { IncomingPairingDialog(current, onSetUp = { throw AssertionError("Later must not set up") }, decide = { _, _ -> }) } }
        current = ui("done", sas = null).copy(peerDeviceId = "dev-laptop")
        rule.waitForIdle()
        rule.onNodeWithTag(SetupTags.PROMPT_LATER).performClick()
        rule.onNodeWithTag(DevicesTags.INCOMING_DIALOG).assertDoesNotExist()
    }

    @Test fun setUpOnTheAnsweringSideOpensTheSetupSheetForThatDevice() {
        var current by mutableStateOf<PairingUi?>(ui("comparing").copy(peerDeviceId = "dev1"))
        val spaces = listOf("s1" to "Music", "s2" to "Videos")
        val copies = FakeDeviceCopies(app.keepfin.copies.CopiesUi(ready = true, places = emptyList(), spaces = spaces, devices = listOf(app.keepfin.copies.PeerChoiceUi("dev1", "Work laptop", true, true, null))))
        rule.setContent { KeepfinTheme(ThemeMode.LIGHT) { IncomingPairingLayer(current, { _, _ -> }, say = { _, _, _ -> }, undo = {}, finish = {}, copies = copies) } }
        current = ui("done", sas = null).copy(peerDeviceId = "dev1")
        rule.waitForIdle()
        rule.onNodeWithTag(SetupTags.PROMPT_SET_UP).performClick()
        rule.onNodeWithTag(SetupTags.STEP1).assertIsDisplayed()
    }

    @Test fun theAnsweringSideOfTwoSeparateLibrariesSaysSoInsteadOfOfferingCopies() {
        var current by mutableStateOf<PairingUi?>(ui("comparing").copy(peerDeviceId = "dev1"))
        val copies = FakeDeviceCopies(
            app.keepfin.copies.CopiesUi(ready = true, places = emptyList(), spaces = emptyList(), devices = listOf(app.keepfin.copies.PeerChoiceUi("dev1", "Work laptop", true, false, "Work laptop does not share this library with you."))),
        )
        rule.setContent { KeepfinTheme(ThemeMode.LIGHT) { IncomingPairingLayer(current, { _, _ -> }, say = { _, _, _ -> }, undo = {}, finish = {}, copies = copies) } }
        current = ui("done", sas = null).copy(peerDeviceId = "dev1")
        rule.waitForIdle()
        rule.onNodeWithTag(SetupTags.PROMPT_SET_UP).performClick()
        rule.onNodeWithTag(SetupTags.DIFFERENT).assertIsDisplayed()
        rule.onNodeWithText("Work laptop and this phone each have their own library. Combining them into one library is coming in an update. Until then you can play files from Work laptop and send it downloads.").assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.STEP1).assertDoesNotExist()
        rule.onNodeWithTag(SetupTags.DIFFERENT_OK).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag(SetupTags.SHEET).assertDoesNotExist()
    }

    private fun shotAnswering(dark: Boolean) {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        rule.setContent {
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, 1.15f)) {
                KeepfinTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) { IncomingPairingDialog(ui("comparing")) { _, _ -> } }
            }
        }
        Thread.sleep(900)
        saveNode("devices-answering-${if (dark) "dark" else "light"}", rule.onNodeWithTag(DevicesTags.PAIR_DIALOG))
        Thread.sleep(700)
    }
    @Test fun shotAnsweringLight() = shotAnswering(false)
    @Test fun shotAnsweringDark() = shotAnswering(true)
}
