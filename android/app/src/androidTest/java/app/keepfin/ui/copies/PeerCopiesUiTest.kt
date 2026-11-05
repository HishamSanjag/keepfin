package app.keepfin.ui.copies

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.copies.CopiesUi
import app.keepfin.copies.CopyCardUi
import app.keepfin.copies.CopyDraft
import app.keepfin.copies.CopyFolderCheck
import app.keepfin.copies.CopyHoldsUi
import app.keepfin.copies.CopyPreview
import app.keepfin.copies.CopyStatus
import app.keepfin.copies.CopyVersionUi
import app.keepfin.copies.PeerCardUi
import app.keepfin.copies.PeerChoiceUi
import app.keepfin.copies.ReviewAction
import app.keepfin.core.copies.CopyMode
import app.keepfin.ui.theme.KeepfinTheme
import app.keepfin.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Copies on a paired device: the wizard's "A paired device" choice (only when devices exist) and the card's device line. No file or network is touched. */
@RunWith(AndroidJUnit4::class)
class PeerCopiesUiTest {
    @get:Rule val rule = createComposeRule()

    private class Recorder : CopiesActions {
        // Try now and the device checks call the actions on a background thread
        val calls: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        override fun sync(placeId: String) { calls += "sync:$placeId" }
        override fun pause(placeId: String) { calls += "pause:$placeId" }
        override fun syncAll() { calls += "syncAll" }
        override fun remove(placeId: String) { calls += "remove:$placeId" }
        override fun resolve(reviewId: String, action: ReviewAction) { calls += "resolve:$reviewId:$action" }
        override fun edit(placeId: String, name: String?, holds: CopyHoldsUi?, everyHours: Int?, auto: Boolean?, exports: Triple<Boolean, Boolean, Boolean>?) { calls += "edit:$placeId" }
        override fun reconnect(placeId: String, treeUri: String): String? = null
        override fun checkFolder(treeUri: String): CopyFolderCheck { calls += "check:$treeUri"; return CopyFolderCheck(true, null, "Anna's phone") }
        override fun preview(draft: CopyDraft, previousId: String?): CopyPreview { calls += "preview:${draft.treeUri}"; return CopyPreview("p1", "ready") }
        override fun cancelPreview(placeId: String) { calls += "cancel:$placeId" }
        override fun add(placeId: String) { calls += "add:$placeId" }
        override fun versions(placeId: String) = emptyList<CopyVersionUi>()
        override fun restore(placeId: String, entryId: String) { calls += "restore:$entryId" }
        override fun cleanup(placeId: String) { calls += "cleanup:$placeId" }
    }

    private fun show(ui: CopiesUi, actions: CopiesActions) {
        rule.setContent {
            KeepfinTheme(mode = ThemeMode.LIGHT) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) { CopiesContent(ui, actions) }
            }
        }
    }

    private val devices = listOf(
        PeerChoiceUi("dev-anna", "Anna's phone", online = true, allowed = true),
        PeerChoiceUi("dev-desk", "Desk PC", online = true, allowed = false, why = "On Desk PC, turn on \"Keep a copy on this device\" for this device in Settings > Devices."),
    )

    @Test fun theWizardOffersAPairedDeviceOnlyWhenThereAreDevices() {
        show(CopiesUi(true), Recorder())
        rule.onNodeWithTag(CopiesTags.ADD).performClick()
        rule.onNodeWithTag(CopiesTags.WIZARD).assertIsDisplayed()
        assertTrue("no devices, no choice", rule.onAllNodesWithTag(CopiesTags.DEVICE).fetchSemanticsNodes().isEmpty())
    }

    @Test fun aDeviceThatAllowsItCanBeChosenAndOneThatDoesNotSaysWhy() {
        val rec = Recorder()
        show(CopiesUi(true, devices = devices), rec)
        rule.onNodeWithTag(CopiesTags.ADD).performClick()
        rule.onNodeWithTag(CopiesTags.DEVICE).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(CopiesTags.NEXT).assertIsNotEnabled()
        rule.onNodeWithTag(CopiesTags.DEVICE).performClick()
        rule.onNodeWithTag(CopiesTags.DEVICE_ROW + "-dev-anna").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(CopiesTags.DEVICE_ROW + "-dev-desk").assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Connected").assertExists()
        rule.onNodeWithText("On Desk PC, turn on \"Keep a copy on this device\" for this device in Settings > Devices.").assertExists()
        // the device that allows it is chosen: it is checked, named and the wizard can go on
        rule.onNodeWithTag(CopiesTags.DEVICE_ROW + "-dev-desk").assertIsNotEnabled()
        rule.onNodeWithTag(CopiesTags.DEVICE_ROW + "-dev-anna").performClick()
        rule.waitUntil(3000) { rec.calls.contains("check:peer:dev-anna") }
        rule.waitUntil(3000) { runCatching { rule.onNodeWithTag(CopiesTags.NEXT).assertIsEnabled() }.isSuccess }
    }

    private fun card(waiting: String?, connected: Boolean) = CopyCardUi(
        "a", "Copy on Anna's phone", CopyMode.Backup, CopyHoldsUi(all = true), "Anna's phone", connected, if (connected) CopyStatus.UP_TO_DATE else CopyStatus.DISCONNECTED,
        statusText = waiting, lastVerifiedAt = 1_700_000_000_000, kept = 12, waiting = 0,
        peer = PeerCardUi("dev-anna", "Anna's phone", online = connected, waiting = waiting),
    )

    @Test fun theCardNamesTheDeviceAndShowsWhetherItIsReachable() {
        val rec = Recorder()
        show(CopiesUi(true, listOf(card(null, true))), rec)
        rule.onNodeWithText("On Anna's phone", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(CopiesTags.DEVICE_LINE, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Anna's phone is connected", useUnmergedTree = true).assertExists()
        // a device keeps what it replaces in its own trash: no Restore or Clean up here, and no playlist-file exports
        assertTrue(rule.onAllNodesWithTag(CopiesTags.RESTORE).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithTag(CopiesTags.CLEANUP).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodesWithText("Playlist files").fetchSemanticsNodes().isEmpty())
    }

    @Test fun aDeviceThatIsAwayIsAWaitNotAnError() {
        val rec = Recorder()
        val words = "Anna's phone is not connected right now. The copy continues when it is."
        show(CopiesUi(true, listOf(card(words, false))), rec)
        rule.onNodeWithTag(CopiesTags.DEVICE_LINE, useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText(words, useUnmergedTree = true)[0].assertExists()
        // "Try now", not "Choose its folder"
        rule.onNodeWithTag(CopiesTags.SYNC).performScrollTo().performClick()
        rule.waitUntil(3000) { rec.calls.isNotEmpty() }
        assertEquals(listOf("sync:a"), rec.calls.toList())
        assertTrue(rule.onAllNodesWithText("Choose its folder").fetchSemanticsNodes().isEmpty())
    }
}
