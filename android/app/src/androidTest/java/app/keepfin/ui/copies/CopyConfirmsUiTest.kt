package app.keepfin.ui.copies

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
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
import app.keepfin.copies.CopyVersionStats
import app.keepfin.copies.CopyVersionUi
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

/**
 * GitHub #27: a copy on the phone says what the PC says. "Free up space…" and "Remove copy…" ask once, in a dialog of their own, with the
 * words of the shared core (core/copies/CopyActions.kt); Add a copy has the lyrics switch.
 */
@RunWith(AndroidJUnit4::class)
class CopyConfirmsUiTest {
    @get:Rule val rule = createComposeRule()

    private class Recorder(val stats: CopyVersionStats = CopyVersionStats(3, 2)) : CopiesActions {
        val calls: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        val drafts: MutableList<CopyDraft> = java.util.Collections.synchronizedList(ArrayList())
        override fun sync(placeId: String) {}
        override fun pause(placeId: String) {}
        override fun syncAll() {}
        override fun remove(placeId: String) { calls += "remove:$placeId" }
        override fun resolve(reviewId: String, action: ReviewAction) {}
        override fun edit(placeId: String, name: String?, holds: CopyHoldsUi?, everyHours: Int?, auto: Boolean?, exports: Triple<Boolean, Boolean, Boolean>?) {}
        override fun reconnect(placeId: String, treeUri: String): String? = null
        override fun checkFolder(treeUri: String) = CopyFolderCheck(true, null, "Card")
        override fun preview(draft: CopyDraft, previousId: String?): CopyPreview { drafts += draft; return CopyPreview("p1", "ready") }
        override fun cancelPreview(placeId: String) {}
        override fun add(placeId: String) {}
        override fun versions(placeId: String) = emptyList<CopyVersionUi>()
        override fun restore(placeId: String, entryId: String) {}
        override fun cleanup(placeId: String) { calls += "cleanup:$placeId" }
        override fun versionStats(placeId: String) = stats
    }

    private fun backup() = CopyCardUi("a", "Backup card", CopyMode.Backup, CopyHoldsUi(all = true), "Card / Keepfin", true, CopyStatus.UP_TO_DATE, lastVerifiedAt = 1_700_000_000_000, kept = 10)

    private fun show(rec: CopiesActions, ui: CopiesUi = CopiesUi(true, listOf(backup()))) {
        rule.setContent { KeepfinTheme(mode = ThemeMode.LIGHT) { Column(androidx.compose.ui.Modifier.verticalScroll(rememberScrollState())) { CopiesContent(ui, rec) } } }
    }

    @Test fun theCardUsesTheWindowsActionNames() {
        show(Recorder())
        rule.onNodeWithText("Free up space…").performScrollTo().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Remove copy…").performScrollTo().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Clean up").assertDoesNotExistCompat()
        rule.onNodeWithText("Stop keeping this copy").assertDoesNotExistCompat()
    }

    @Test fun freeUpSpaceSaysWhatWillBeDeletedAndOnlyThenDoesIt() {
        val rec = Recorder()
        show(rec)
        rule.onNodeWithTag(CopiesTags.CLEANUP).performScrollTo().performClick()
        rule.onNodeWithText("Free up space on Backup card?").assertExists()
        rule.waitUntil(3000) { runCatching { rule.onNodeWithText("This deletes", substring = true).assertExists() }.isSuccess }
        rule.onNodeWithText("This deletes 3 earlier versions and the 2 files kept after you deleted them from your library from this backup.").assertExists()
        rule.onNodeWithText("The backup keeps its copy of everything in your library. Nothing in your library is touched.").assertExists()
        assertTrue("nothing happens before the person confirms", rec.calls.isEmpty())
        rule.onNodeWithTag(CopyConfirmTags.CANCEL).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(CopyConfirmTags.FREE_UP_CONFIRM).assertHeightIsAtLeast(48.dp).performClick()
        rule.waitUntil(3000) { "cleanup:a" in rec.calls }
    }

    @Test fun freeUpSpaceWithNothingKeptSaysSoAndOnlyOffersOk() {
        val rec = Recorder(CopyVersionStats(0, 0))
        show(rec)
        rule.onNodeWithTag(CopiesTags.CLEANUP).performScrollTo().performClick()
        rule.waitUntil(3000) { runCatching { rule.onNodeWithText("There is nothing to free up", substring = true).assertExists() }.isSuccess }
        rule.onNodeWithTag(CopyConfirmTags.FREE_UP_CONFIRM).assertDoesNotExistCompat()
        rule.onNodeWithText("OK").performClick()
        assertTrue(rec.calls.isEmpty())
    }

    @Test fun removeCopyStatesWhatHappensAndOnlyThenDoesIt() {
        val rec = Recorder()
        show(rec)
        rule.onNodeWithTag(CopyConfirmTags.REMOVE).performScrollTo().performClick()
        rule.onNodeWithText("Remove Backup card?").assertExists()
        rule.onNodeWithText("Keepfin stops keeping this copy up to date.").assertExists()
        rule.onNodeWithText("Its files stay where they are.").assertExists()
        assertTrue(rec.calls.isEmpty())
        rule.onNodeWithTag(CopyConfirmTags.CANCEL).performClick()
        assertTrue("cancel changes nothing", rec.calls.isEmpty())
        rule.onNodeWithTag(CopyConfirmTags.REMOVE).performScrollTo().performClick()
        rule.onNodeWithTag(CopyConfirmTags.REMOVE_CONFIRM).assertHeightIsAtLeast(48.dp).performClick()
        rule.waitUntil(3000) { "remove:a" in rec.calls }
    }

    @Test fun addACopyHasTheLyricsSwitchOnAndRecordsTurningItOff() {
        val rec = Recorder()
        show(rec, CopiesUi(true, emptyList(), spaces = listOf("music" to "Music"), devices = listOf(PeerChoiceUi("dev-anna", "Anna", online = true, allowed = true))))
        rule.onNodeWithTag(CopiesTags.ADD).performClick()
        // a paired device needs no system picker
        rule.onNodeWithTag(CopiesTags.DEVICE).performClick()
        rule.onNodeWithTag(CopiesTags.DEVICE_ROW + "-dev-anna").performClick()
        rule.waitUntil(3000) { runCatching { rule.onNodeWithTag(CopiesTags.NEXT).assertIsEnabled() }.isSuccess }
        rule.onNodeWithTag(CopiesTags.NEXT).performClick()
        rule.onNodeWithText("Lyrics, subtitles and info files").assertExists()
        rule.onNodeWithTag(CopiesTags.WIZARD_COMPANIONS).assertIsOn().performClick()
        rule.onNodeWithTag(CopiesTags.WIZARD_COMPANIONS).assertIsOff()
        rule.onNodeWithTag(CopiesTags.NEXT).performClick() // how it stays up to date
        rule.waitUntil(3000) { runCatching { rule.onNodeWithTag(CopiesTags.NEXT).assertIsEnabled() }.isSuccess }
        rule.onNodeWithTag(CopiesTags.NEXT).performClick() // the preview
        rule.waitUntil(5000) { rec.drafts.isNotEmpty() }
        assertEquals(false, rec.drafts.last().companions)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = assertDoesNotExist()
}
