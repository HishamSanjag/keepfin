package app.keepfin.ui.copies

import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import app.keepfin.copies.CopyProblemUi
import app.keepfin.copies.CopyProgressUi
import app.keepfin.copies.CopyReviewUi
import app.keepfin.copies.CopyStatus
import app.keepfin.copies.CopyVersionUi
import app.keepfin.copies.ReviewAction
import app.keepfin.copies.SyncReportUi
import app.keepfin.core.copies.CopyMode
import app.keepfin.core.copies.ProblemGroup
import app.keepfin.core.copies.copyProblemShort
import app.keepfin.ui.theme.KeepfinTheme
import app.keepfin.ui.theme.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Settings > Storage > Copies over fake state: the cards, Needs review and the Add a copy steps. No file or provider is touched. */
@RunWith(AndroidJUnit4::class)
class CopiesUiTest {
    @get:Rule val rule = createComposeRule()

    private class Recorder : CopiesActions {
        // Sync now and Try now call the actions on a background thread
        val calls: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        override fun sync(placeId: String) { calls += "sync:$placeId" }
        override fun pause(placeId: String) { calls += "pause:$placeId" }
        override fun syncAll() { calls += "syncAll" }
        override fun remove(placeId: String) { calls += "remove:$placeId" }
        override fun resolve(reviewId: String, action: ReviewAction) { calls += "resolve:$reviewId:$action" }
        override fun edit(placeId: String, name: String?, holds: CopyHoldsUi?, everyHours: Int?, auto: Boolean?, exports: Triple<Boolean, Boolean, Boolean>?) { calls += "edit:$placeId:auto=$auto:exports=$exports" }
        override fun reconnect(placeId: String, treeUri: String): String? = null
        override fun checkFolder(treeUri: String) = CopyFolderCheck(true, null, "Card")
        override fun preview(draft: CopyDraft, previousId: String?) = CopyPreview("p1", "ready")
        override fun cancelPreview(placeId: String) { calls += "cancel:$placeId" }
        override fun add(placeId: String) { calls += "add:$placeId" }
        override fun versions(placeId: String) = listOf(CopyVersionUi("v1", "a.mp3", "Music/a.mp3", 1_700_000_000_000, "replaced", false))
        override fun restore(placeId: String, entryId: String) { calls += "restore:$entryId" }
        override fun cleanup(placeId: String) { calls += "cleanup:$placeId" }
        override fun setCompanions(placeId: String, on: Boolean) { calls += "companions:$placeId:$on" }
    }

    private fun card(id: String, status: CopyStatus, mode: CopyMode = CopyMode.Backup, connected: Boolean = true) = CopyCardUi(
        id, "Card $id", mode, CopyHoldsUi(all = true), "Card 43D6-1A23 / Keepfin", connected, status, lastVerifiedAt = 1_700_000_000_000, kept = 120, waiting = 3, freeBytes = 4_000_000_000,
        progress = if (status == CopyStatus.SYNCING) CopyProgressUi(3, 10, 100, 1000, "Music/a.mp3") else null,
    )

    private fun show(ui: CopiesUi, actions: CopiesActions, dark: Boolean = false, scale: Float = 1f) {
        rule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, scale)) {
                KeepfinTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) { CopiesContent(ui, actions) }
                }
            }
        }
    }
    @Test fun anEmptyStateExplainsCopiesAndOffersAdd() {
        show(CopiesUi(ready = true), Recorder())
        rule.onNodeWithTag(CopiesTags.EMPTY).assertIsDisplayed()
        rule.onNodeWithTag(CopiesTags.ADD).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test fun cardsShowModeHoldsStateAndTheRightButton() {
        val rec = Recorder()
        show(CopiesUi(true, listOf(card("a", CopyStatus.WAITING), card("b", CopyStatus.SYNCING, CopyMode.Mirror), card("c", CopyStatus.DISCONNECTED, CopyMode.TwoWay, connected = false))), rec)
        rule.onNodeWithText("Backup: keeps earlier versions and what you delete").assertExists()
        rule.onNodeWithText("Mirror: an exact copy; removed files wait 30 days in the trash").assertExists()
        rule.onNodeWithText("Two-way: changes on either side reach the other").assertExists()
        rule.onNodeWithText("3 files waiting", substring = true).assertExists()
        rule.onNodeWithTag(CopiesTags.PAUSE).assertExists()
        rule.onNodeWithText("Choose its folder").assertExists()
        rule.onAllNodesWithTag(CopiesTags.SYNC)[0].performScrollTo().performClick()
        rule.waitUntil(3000) { rec.calls.isNotEmpty() }
        assertEquals(listOf("sync:a"), rec.calls.toList())
        rule.onNodeWithTag(CopiesTags.RESTORE).assertExists() // only the connected backup offers Restore and Clean up
        rule.onNodeWithTag(CopiesTags.CLEANUP).assertExists()
    }

    @Test fun syncAllSaysWhatItDidWhenItHasFinished() {
        val rec = Recorder()
        val two = listOf(card("a", CopyStatus.UP_TO_DATE), card("b", CopyStatus.UP_TO_DATE, CopyMode.Mirror))
        val ui = androidx.compose.runtime.mutableStateOf(CopiesUi(true, two, syncing = true))
        rule.setContent { KeepfinTheme { androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) { CopiesContent(ui.value, rec) } } }
        // while it runs the button says so and waits; the result is not shown yet
        rule.onNodeWithTag(CopiesTags.SYNC_ALL).assertIsNotEnabled().assertTextEquals("Syncing…")
        rule.onNodeWithTag(CopiesTags.REPORT).assertDoesNotExist()
        ui.value = ui.value.copy(syncing = false, report = SyncReportUi("Everything is up to date", "All 2 copies already match your library.", false, 0))
        rule.onNodeWithTag(CopiesTags.REPORT).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Everything is up to date").assertExists()
        rule.onNodeWithTag(CopiesTags.SYNC_ALL).assertIsEnabled().performClick()
        rule.waitUntil(3000) { "syncAll" in rec.calls }
    }

    @Test fun aCopyHoldsLyricsSubtitlesAndInfoFilesUntilTurnedOff() {
        val rec = Recorder()
        show(CopiesUi(true, listOf(card("a", CopyStatus.WAITING))), rec)
        rule.onNodeWithText("Lyrics, subtitles and info files").performScrollTo().assertIsDisplayed().performClick()
        rule.waitUntil(3000) { "companions:a:false" in rec.calls }
    }

    @Test fun filesThatCouldNotBeCopiedAreAProblemInPlainWordsNotADecision() {
        val rec = Recorder()
        val problem = CopyProblemUi(
            50, listOf(ProblemGroup("This drive does not support how Keepfin saves files.", 50)), List(50) { "Music/Track $it.lrc" }, 0,
            copyProblemShort(50),
        )
        val ui = CopiesUi(true, listOf(card("a", CopyStatus.PROBLEM).copy(kept = 45, waiting = 50, problem = problem)))
        show(ui, rec)
        // the card says where it is and how full it is, and the problem instead of "needs a decision"
        rule.onNodeWithText("Card 43D6-1A23 / Keepfin · 45 of 95 files", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("50 files not copied", substring = true, useUnmergedTree = true).assertExists()
        rule.onAllNodesWithText("decision", substring = true, useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithTag(CopiesTags.PROBLEM, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("50 files could not be copied", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("This drive does not support how Keepfin saves files.", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Keepfin tries again at every sync.", useUnmergedTree = true).assertIsDisplayed()
        // the files are behind a toggle
        rule.onAllNodesWithText("Music/Track 1.lrc", useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithTag(CopiesTags.PROBLEM_FILES, useUnmergedTree = true).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText("Music/Track 1.lrc", useUnmergedTree = true).assertExists()
        // Try again runs the sync
        rule.onNodeWithTag(CopiesTags.PROBLEM_RETRY, useUnmergedTree = true).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.waitUntil(3000) { rec.calls.contains("sync:a") }
    }

    @Test fun needsReviewListsDecisionsAndSendsThem() {        val rec = Recorder()
        val ui = CopiesUi(true, listOf(card("a", CopyStatus.REVIEW, CopyMode.TwoWay)), listOf(CopyReviewUi("r1", "a", "Card a", "copyDeleted", "a.mp3 was removed from the copy", "Keepfin copied it back.", listOf(ReviewAction.CONFIRM_DELETE, ReviewAction.RESTORE))))
        show(ui, rec)
        rule.onNodeWithTag(CopiesTags.REVIEW).assertExists()
        rule.onNodeWithText("a.mp3 was removed from the copy").assertExists()
        rule.onNodeWithText("Delete everywhere").performScrollTo().performClick()
        rule.waitUntil(3000) { rec.calls.contains("resolve:r1:CONFIRM_DELETE") }
    }

    @Test fun theWizardWalksFolderWhatHowAndPreview() {
        val rec = Recorder()
        show(CopiesUi(true, emptyList(), emptyList(), false, listOf("music" to "Music"), listOf("pl" to "Road trip")), rec)
        rule.onNodeWithTag(CopiesTags.ADD).performClick()
        rule.onNodeWithTag(CopiesTags.WIZARD).assertIsDisplayed()
        rule.onNodeWithTag(CopiesTags.NEXT).assertIsNotEnabled() // no folder chosen yet
        rule.onNodeWithTag(CopiesTags.PICK).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(CopiesTags.CANCEL).performClick()
        rule.onAllNodesWithTag(CopiesTags.WIZARD).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
    }

    private val acting = hasClickAction() or isToggleable() or isSelectable()

    private fun assertEveryActionIs48dp(): Int {
        val count = rule.onAllNodes(acting).fetchSemanticsNodes().size
        for (i in 0 until count) {
            val node = rule.onAllNodes(acting)[i]
            runCatching { node.performScrollTo() } // a node scrolled out of view reports no touch area
            assertTarget48(node)
        }
        return count
    }

    /** The touch area (what a finger can hit, not the drawn size) is at least 48 dp each way. */
    private fun assertTarget48(node: SemanticsNode) {
        val d = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val b = node.touchBoundsInRoot
        assertTrue("target too small (${b.width / d} x ${b.height / d} dp): ${node.config}", b.height >= 48f * d - 1f && b.width >= 48f * d - 1f)
    }

    private fun assertTarget48(interaction: androidx.compose.ui.test.SemanticsNodeInteraction): androidx.compose.ui.test.SemanticsNodeInteraction {
        assertTarget48(interaction.fetchSemanticsNode())
        return interaction
    }

    private fun assertNothingCutOff() {
        val width = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.widthPixels
        for (node in rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()) {
            val b = node.boundsInWindow
            assertTrue("cut off: ${node.config}", b.left >= -1f && b.right <= width + 1f)
        }
    }

    private val fullUi = CopiesUi(
        true, listOf(card("a", CopyStatus.WAITING), card("b", CopyStatus.SYNCING, CopyMode.Mirror), card("c", CopyStatus.DISCONNECTED, CopyMode.TwoWay, connected = false)),
        listOf(CopyReviewUi("r1", "a", "Card a", "copyDeleted", "a.mp3 was removed from the copy", "Keepfin copied it back.", listOf(ReviewAction.CONFIRM_DELETE, ReviewAction.RESTORE, ReviewAction.KEEP_BOTH))),
        false, listOf("music" to "Music"), listOf("pl" to "Road trip"),
    )

    @Test fun everyActionOnTheCardsAndTheWizardHasA48dpTarget() {
        show(fullUi, Recorder())
        val onCards = assertEveryActionIs48dp()
        assertTrue("found $onCards actions", onCards >= 12)
        // the confirmation that follows "Stop keeping this copy"
        rule.onAllNodesWithText("Stop keeping this copy")[0].performScrollTo().performClick()
        assertTarget48(rule.onAllNodesWithText("Stop")[0])
        for (i in 0 until rule.onAllNodesWithText("Keep it").fetchSemanticsNodes().size) assertTarget48(rule.onAllNodesWithText("Keep it")[i].also { runCatching { it.performScrollTo() } })
        androidx.test.espresso.Espresso.pressBack()
        // the wizard, step by step
        rule.onNodeWithTag(CopiesTags.ADD).performScrollTo().performClick()
        rule.onNodeWithTag(CopiesTags.WIZARD).assertIsDisplayed()
        assertTrue(assertEveryActionIs48dp() >= 2)
    }

    @Test fun atTwiceTheFontSizeNothingIsCutOff() {
        show(fullUi, Recorder(), scale = 2f)
        for (text in listOf("Sync now", "Restore", "Clean up", "Pause", "Choose its folder", "Delete everywhere", "Keep it", "Keep both")) {
            rule.onAllNodesWithText(text)[0].performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithText("Backup: keeps earlier versions and what you delete").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Needs review").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(CopiesTags.ADD).performScrollTo().assertIsDisplayed()
        assertNothingCutOff()
        rule.onNodeWithTag(CopiesTags.ADD).performClick()
        rule.onNodeWithTag(CopiesTags.WIZARD).assertIsDisplayed()
        for (tag in listOf(CopiesTags.PICK, CopiesTags.NEXT, CopiesTags.CANCEL)) rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        assertNothingCutOff()
    }

    @Test fun screenshotsLightAndDark() {
        val ui = CopiesUi(
            true, listOf(card("a", CopyStatus.WAITING), card("b", CopyStatus.SYNCING, CopyMode.Mirror), card("c", CopyStatus.DISCONNECTED, CopyMode.TwoWay, connected = false)),
            listOf(CopyReviewUi("r1", "a", "Card a", "copyDeleted", "a.mp3 was removed from the copy", "Keepfin copied it back.", listOf(ReviewAction.CONFIRM_DELETE, ReviewAction.RESTORE))),
        )
        val dir = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        val darkState = androidx.compose.runtime.mutableStateOf(false)
        rule.setContent {
            KeepfinTheme(mode = if (darkState.value) ThemeMode.DARK else ThemeMode.LIGHT) {
                androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) { CopiesContent(ui, Recorder()) }
            }
        }
        for ((name, dark) in listOf("light" to false, "dark" to true)) {
            darkState.value = dark
            rule.waitForIdle()
            val bmp = rule.onNodeWithTag(CopiesTags.GROUP).captureToImage().asAndroidBitmap()
            val values = android.content.ContentValues().apply { put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "kf-copies-$name.png"); put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png"); put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/kf-copies") }
            val cr = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
            val uri = cr.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            cr.openOutputStream(uri)!!.use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }}
