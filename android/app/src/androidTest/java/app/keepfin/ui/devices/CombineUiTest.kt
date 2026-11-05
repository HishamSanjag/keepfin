package app.keepfin.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.copies.CopiesUi
import app.keepfin.copies.PeerChoiceUi
import app.keepfin.merge.CombineDryRun
import app.keepfin.merge.CombineState
import app.keepfin.merge.ReviewRow
import app.keepfin.merge.SeparatePreview
import app.keepfin.net.PairedDeviceUi
import app.keepfin.net.PairedLibraryUi
import app.keepfin.ui.theme.KeepfinTheme
import app.keepfin.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

internal class FakeDeviceCombine(initial: CombineState = CombineState()) : DeviceCombine {
    val flow = MutableStateFlow(initial)
    override val state: StateFlow<CombineState> get() = flow
    val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
    var reviewResult: String? = null
    var startResult: String? = null
    var cancelResult: String? = null
    var preview: SeparatePreview? = SeparatePreview(brought = 12, heldByPeer = 3, addedSince = 2, peerReachable = true, keepUntil = null)
    override fun review(deviceId: String): String? { calls += "review:$deviceId"; return reviewResult }
    override fun start(deviceId: String, keepMine: Boolean): String? { calls += "start:$deviceId:$keepMine"; return startResult }
    override fun pause() { calls += "pause" }
    override fun resume() { calls += "resume" }
    override fun cancel(): String? { calls += "cancel"; return cancelResult }
    override fun separatePreview(): SeparatePreview? = preview
    override fun separate(keepOnPeer: Boolean, anyway: Boolean): String? { calls += "separate:keep=$keepOnPeer:anyway=$anyway"; return null }
    override fun keepCombined(): String? { calls += "keepCombined"; return null }
}

internal class CombineController(val base: FakeController, override val combine: DeviceCombine, override val copies: DeviceCopies? = null) : DevicesController by base

@RunWith(AndroidJUnit4::class)
class CombineUiTest {
    @get:Rule val rule = createComposeRule()

    private fun laptop(platform: String = "windows") = PairedDeviceUi(
        deviceId = "dev1", fingerprint = "1111 2222 3333 4444 5555", name = "Work laptop", platform = platform, pairedAt = 1, lastSeen = System.currentTimeMillis() - 60_000, online = true,
        libraries = listOf(PairedLibraryUi("lib1", "Family music", listOf("metadata.read", "metadata.write"))), unreachableSince = null, address = "192.168.1.20",
        jobsAllowed = true, jobSpaces = emptyList(), shareable = emptyList(), allowMobileFiles = false,
    )

    private val ids = listOf("s1-id-0001", "s2-id-0002", "s3-id-0003", "s4-id-0004", "s5-id-0005")

    private fun dry(items: Long = 210, other: Long = 1326, rows: Boolean = true) = CombineDryRun(
        items = items, added = 120, linked = 60, keptDuplicates = 3, missingFiles = 2, differing = 5, playlists = 4, spaces = 3, reviewTotal = 9, otherItems = other,
        review = if (!rows) emptyList() else listOf(
            ReviewRow("duplicateInS", ids[0], "t1", null, "Midnight Drive"),
            ReviewRow("missingFile", ids[1], null, null, "Old demo"),
            ReviewRow("differingField", ids[2], "t3", "title", "Rain"),
            ReviewRow("sameNamePlaylist", ids[3], "t4", null, "Road trip"),
            ReviewRow("manyMatches", ids[4], "t5", null, "Intro"),
        ),
    )

    private fun st(
        stage: String, started: Boolean = true, paused: Boolean = false, error: String? = null, done: Long = 0, total: Long = -1, keepUntil: Long? = null,
        preview: CombineDryRun? = null, waiting: Boolean = false, peer: Boolean = false,
    ) = CombineState(
        mergeId = "m1", stage = stage, done = done, total = total, paused = paused, waitingForFolder = waiting, peerUnreachable = peer, error = error, keepUntil = keepUntil,
        peerDeviceId = "dev1", libraryName = "Family music", started = started, preview = preview,
    )

    private val ready get() = st("joined", started = false, preview = dry())
    private val inAMonth get() = System.currentTimeMillis() + 29L * 24 * 3600 * 1000

    private fun showStep(combine: FakeDeviceCombine, otherIsPc: Boolean = true, closed: () -> Unit = {}) {
        rule.setContent { KeepfinTheme(ThemeMode.LIGHT) { CombineStepDialog("dev1", "Work laptop", otherIsPc, combine, closed) } }
    }

    private fun press(tag: String) = rule.onNodeWithTag(tag).performClick()
    private fun tap(tag: String) = rule.onNodeWithTag(tag).performScrollTo().performClick()

    // ---- the Combine step ------------------------------------------------------------------------------------------------------

    @Test fun theStepAsksForTheReviewAndShowsALookingStateWhileItRuns() {
        val c = FakeDeviceCombine(CombineState())
        showStep(c)
        rule.waitUntil(5_000) { c.calls.contains("review:dev1") }
        c.flow.value = st("planned", started = false)
        rule.onNodeWithText("Combine libraries").assertIsDisplayed()
        rule.onNodeWithTag(CombineTags.WAIT).assertIsDisplayed()
        rule.onNodeWithText("Looking at both libraries…").assertIsDisplayed()
        rule.onNodeWithTag(CombineTags.WAIT_NOTE).assertTextContains("Nothing changes yet.", substring = true)
        rule.onNodeWithTag(CombineTags.LATER).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(CombineTags.REVIEW).assertDoesNotExist()
        c.flow.value = st("planned", started = false, done = 400, total = 1200)
        rule.onNodeWithText("400 of 1,200").assertIsDisplayed()
        // the review arrives: the question and the two choices
        c.flow.value = ready
        rule.onNodeWithTag(CombineTags.QUESTION).assertIsDisplayed()
    }

    @Test fun aReviewThatIsAlreadyThereIsUsedAsItIsWithoutAskingAgain() {
        val c = FakeDeviceCombine(ready)
        showStep(c)
        rule.onNodeWithTag(CombineTags.QUESTION).assertIsDisplayed()
        rule.waitForIdle()
        assertTrue("no second review was asked for", c.calls.none { it.startsWith("review") })
    }

    @Test fun theQuestionNamesBothLibrariesAndTheDefaultFollowsTheRules() {
        // the other device is a PC: its library stays, whatever the sizes
        run {
            val c = FakeDeviceCombine(st("joined", started = false, preview = dry(items = 5000, other = 10)))
            showStep(c, otherIsPc = true)
            rule.onNodeWithTag(CombineTags.QUESTION).assertTextEquals("Work laptop has 10 items. This phone has 5,000 items. Combine them into one library that both keep up to date?")
            rule.onNodeWithText("Keep the name, spaces and settings of").assertIsDisplayed()
            rule.onNodeWithTag(CombineTags.KEEP_THEIRS).assertIsSelected()
            rule.onNodeWithTag(CombineTags.KEEP_MINE).assertIsNotSelected()
            rule.onNodeWithText("Work laptop’s library").assertIsDisplayed()
            rule.onNodeWithText("This phone’s library").assertIsDisplayed()
        }
    }

    @Test fun anotherPhoneWithTheBiggerLibraryKeepsItsLibrary() {
        val c = FakeDeviceCombine(st("joined", started = false, preview = dry(items = 5000, other = 10)))
        showStep(c, otherIsPc = false)
        rule.onNodeWithTag(CombineTags.KEEP_MINE).assertIsSelected()
        rule.onNodeWithTag(CombineTags.KEEP_THEIRS).assertIsNotSelected()
    }

    @Test fun anotherPhoneWithTheSmallerLibraryKeepsTheOthersLibrary() {
        val c = FakeDeviceCombine(st("joined", started = false, preview = dry(items = 50, other = 1326)))
        showStep(c, otherIsPc = false)
        rule.onNodeWithTag(CombineTags.KEEP_THEIRS).assertIsSelected()
    }

    @Test fun whenTheOtherSizeIsUnknownTheSentenceSaysSoAndTheOtherLibraryStays() {
        val c = FakeDeviceCombine(st("joined", started = false, preview = dry(items = 210, other = -1)))
        showStep(c, otherIsPc = false)
        rule.onNodeWithTag(CombineTags.QUESTION).assertTextEquals("Work laptop and this phone each have their own library. This phone has 210 items. Combine them into one library that both keep up to date?")
        rule.onNodeWithTag(CombineTags.KEEP_THEIRS).assertIsSelected()
    }

    @Test fun laterDropsTheReviewAndCloses() {
        val c = FakeDeviceCombine(ready)
        var closed = 0
        showStep(c) { closed++ }
        press(CombineTags.LATER)
        rule.waitUntil(5_000) { closed == 1 && c.calls.contains("cancel") }
        assertTrue(c.calls.none { it.startsWith("start") })
    }

    @Test fun theReviewListsWhatHappensWithNamesAndNeverIds() {
        val c = FakeDeviceCombine(ready)
        showStep(c)
        press(CombineTags.REVIEW)
        rule.onNodeWithText("Review · Work laptop").assertIsDisplayed()
        rule.onNodeWithTag(CombineTags.PROMISE).assertTextEquals("Your files stay where they are. Nothing is deleted.")
        // before -> after: this phone, the other library, and the one library (1,326 + 120 added + 3 kept twice)
        rule.onNodeWithTag(CombineTags.LIB_MINE).assertContentDescriptionContains("This phone: 210 items")
        rule.onNodeWithTag(CombineTags.LIB_OTHER).assertContentDescriptionContains("Work laptop: 1,326 items")
        rule.onNodeWithTag(CombineTags.LIB_RESULT).assertContentDescriptionContains("One library: 1,449 items")
        // tiles: each one's description is the whole sentence
        rule.onNodeWithTag(CombineTags.tile("added")).assertContentDescriptionContains("120 items are added")
        rule.onNodeWithTag(CombineTags.tile("linked")).assertContentDescriptionContains("60 items are already in Work laptop’s library (same file): counted once")
        rule.onNodeWithTag(CombineTags.tile("twice")).assertContentDescriptionContains("3 items are the same file twice here: both are kept")
        rule.onNodeWithText("kept twice").assertExists()
        rule.onNodeWithText("already there").assertExists()
        // what comes along, as chips
        rule.onNodeWithText("Comes along").assertExists()
        for (chip in listOf("4 playlists", "3 spaces", "favourites", "play counts")) rule.onNodeWithText(chip).assertExists()
        // what to check
        rule.onNodeWithText("To check · 9").assertExists()
        rule.onNodeWithText("5 items have a different title or detail: Work laptop’s stays").assertExists()
        rule.onNodeWithText("2 files could not be found on this phone: they come along without a file").assertExists()
        rule.onNodeWithTag(CombineTags.DAYS).assertTextEquals("You can separate them again for 30 days.")
        // the details are folded away until asked for
        rule.onNodeWithTag(CombineTags.DETAILS).assertDoesNotExist()
        tap(CombineTags.DETAILS_TOGGLE)
        rule.onNodeWithTag(CombineTags.DETAILS_TOGGLE).assertTextEquals("Hide the details")
        rule.onNodeWithText("“Midnight Drive” is the same file twice on this phone. Both are kept.").assertExists()
        rule.onNodeWithText("“Old demo” could not be found on this phone. It comes along without a file.").assertExists()
        rule.onNodeWithText("“Rain”: the title is different. Work laptop’s stays.").assertExists()
        rule.onNodeWithText("A playlist called “Road trip” is on both. Both are kept.").assertExists()
        rule.onNodeWithText("“Intro” matches several items in Work laptop’s library. It is counted once, with the oldest.").assertExists()
        rule.onNodeWithTag(CombineTags.MORE).assertTextEquals("and 4 more")
        for (id in ids) assertEquals("an id is never shown: $id", 0, rule.onAllNodesWithText(id, substring = true).fetchSemanticsNodes().size)
    }

    @Test fun theOtherSizeUnknownLeavesTheTotalOpen() {
        val c = FakeDeviceCombine(st("joined", started = false, preview = dry(other = -1)))
        showStep(c)
        press(CombineTags.REVIEW)
        rule.onNodeWithTag(CombineTags.LIB_OTHER).assertContentDescriptionContains("Work laptop: not known yet")
        rule.onNodeWithTag(CombineTags.LIB_RESULT).assertContentDescriptionContains("One library: not known yet")
    }

    @Test fun atLargeTextTheResultSitsOnItsOwnRowUnderTheTwoLibraries() {
        val c = FakeDeviceCombine(ready)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1.3f)) { KeepfinTheme(ThemeMode.LIGHT) { CombineStepDialog("dev1", "Work laptop", true, c) {} } }
        }
        press(CombineTags.REVIEW)
        val mine = rule.onNodeWithTag(CombineTags.LIB_MINE).getUnclippedBoundsInRoot()
        val other = rule.onNodeWithTag(CombineTags.LIB_OTHER).getUnclippedBoundsInRoot()
        val result = rule.onNodeWithTag(CombineTags.LIB_RESULT).performScrollTo().getUnclippedBoundsInRoot()
        assertTrue("this phone and the other library side by side", mine.right <= other.left + 1.dp)
        assertTrue("the result is under both", result.top >= maxOf(mine.bottom, other.bottom))
    }

    @Test fun theDetailsCountIsInTheButtonAndBackReturnsToTheChoice() {
        val c = FakeDeviceCombine(ready)
        showStep(c)
        press(CombineTags.REVIEW)
        rule.onNodeWithTag(CombineTags.DETAILS_TOGGLE).assertTextEquals("Show the details (9)")
        press(CombineTags.BACK)
        rule.onNodeWithTag(CombineTags.QUESTION).assertIsDisplayed()
    }

    @Test fun aLineWhoseCountIsZeroIsLeftOut() {
        val p = dry().copy(keptDuplicates = 0, missingFiles = 0, differing = 0, playlists = 1, spaces = 0, added = 1, linked = 0, reviewTotal = 0, review = emptyList())
        val c = FakeDeviceCombine(st("joined", started = false, preview = p))
        showStep(c)
        press(CombineTags.REVIEW)
        rule.onNodeWithTag(CombineTags.tile("added")).assertContentDescriptionContains("1 item is added")
        rule.onNodeWithTag(CombineTags.tile("linked")).assertDoesNotExist()
        rule.onNodeWithTag(CombineTags.tile("twice")).assertDoesNotExist()
        rule.onNodeWithText("1 playlist").assertExists()
        assertEquals(0, rule.onAllNodesWithText("space", substring = true).fetchSemanticsNodes().size)
        // nothing to check: no box at all
        rule.onNodeWithTag(CombineTags.CHECK).assertDoesNotExist()
        rule.onNodeWithTag(CombineTags.DETAILS_TOGGLE).assertDoesNotExist()
    }

    @Test fun combineStartsWithTheChosenLibraryAndCloses() {
        val c = FakeDeviceCombine(ready)
        var closed = 0
        showStep(c) { closed++ }
        press(CombineTags.REVIEW)
        press(CombineTags.COMBINE)
        rule.waitUntil(5_000) { closed == 1 }
        assertEquals(listOf("start:dev1:false"), c.calls.filter { it.startsWith("start") || it.startsWith("cancel") })
    }

    @Test fun choosingThisPhonesLibraryStartsWithKeepMine() {
        val c = FakeDeviceCombine(ready)
        var closed = 0
        showStep(c) { closed++ }
        press(CombineTags.KEEP_MINE)
        rule.onNodeWithTag(CombineTags.KEEP_MINE).assertIsSelected()
        press(CombineTags.REVIEW)
        press(CombineTags.COMBINE)
        rule.waitUntil(5_000) { closed == 1 }
        assertTrue(c.calls.contains("start:dev1:true"))
        assertTrue("closing after Combine does not cancel the review", c.calls.none { it == "cancel" })
    }

    @Test fun aRefusalIsOneSentenceWithTryAgain() {
        val c = FakeDeviceCombine(CombineState())
        c.reviewResult = "A copy is running. Wait for it to finish, then combine."
        showStep(c)
        rule.onNodeWithText("A copy is running. Wait for it to finish, then combine.").assertIsDisplayed()
        c.reviewResult = null
        press(CombineTags.TRY_AGAIN)
        rule.waitUntil(5_000) { c.calls.count { it.startsWith("review") } == 2 }
        // the refusal is gone and the step waits for the review again
        rule.onNodeWithTag(CombineTags.WAIT).assertIsDisplayed()
    }

    @Test fun aReviewThatFailedOffersTryAgain() {
        val c = FakeDeviceCombine(st("planned", started = false, paused = true, error = "Part of the other library could not be read yet. Wait a moment and try again."))
        showStep(c)
        rule.onNodeWithTag(CombineTags.ERROR).assertTextContains("could not be read yet", substring = true)
        rule.onNodeWithTag(CombineTags.TRY_AGAIN).assertIsDisplayed()
    }

    @Test fun aCombineAlreadyUnderWayIsFollowedOnTheCard() {
        val c = FakeDeviceCombine(st("describing", done = 10, total = 100))
        showStep(c)
        rule.onNodeWithTag(CombineTags.UNDER_WAY).assertTextContains("already under way or waiting", substring = true)
        assertTrue(c.calls.none { it.startsWith("review") })
    }

    // ---- the card --------------------------------------------------------------------------------------------------------------

    private fun showPage(combine: FakeDeviceCombine, copies: DeviceCopies? = null) {
        val base = FakeController(state(paired = listOf(laptop())))
        rule.setContent {
            KeepfinTheme(ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) { DevicesScreen(CombineController(base, combine, copies)) }
            }
        }
    }

    private fun line() = rule.onNodeWithTag(CombineTags.cardLine("dev1")).performScrollTo()

    @Test fun theCardShowsProgressWithPauseAndCancelEvenWhenCollapsed() {
        val c = FakeDeviceCombine(st("describing", done = 1230, total = 1450))
        showPage(c)
        line().assertTextEquals("Combining libraries · 1,230 of 1,450")
        rule.onNodeWithTag(CombineTags.cardProgress("dev1")).assertExists()
        rule.onNodeWithTag(CombineTags.cardPause("dev1")).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.waitUntil(5_000) { c.calls.contains("pause") }
        rule.onNodeWithTag(CombineTags.cardCancel("dev1")).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.waitUntil(5_000) { c.calls.contains("cancel") }
        rule.onNodeWithTag(CombineTags.cardSeparate("dev1")).assertDoesNotExist()
    }

    @Test fun pausedByThePersonOffersResumeAndSaysSo() {
        val c = FakeDeviceCombine(st("describing", paused = true, done = 400, total = 1450))
        showPage(c)
        line().assertTextEquals("Combining paused · You paused it.")
        rule.onNodeWithTag(CombineTags.cardPause("dev1")).assertDoesNotExist()
        rule.onNodeWithTag(CombineTags.cardResume("dev1")).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.waitUntil(5_000) { c.calls.contains("resume") }
    }

    @Test fun pausedForTheFolderOrTheOtherDeviceSaysWhy() {
        val c = FakeDeviceCombine(st("describing", paused = true, error = "Waiting for the Keepfin folder", waiting = true))
        showPage(c)
        line().assertTextEquals("Combining paused · Waiting for the Keepfin folder")
        c.flow.value = st("describing", paused = true, error = "The main library's device cannot be reached.", peer = true)
        line().assertTextEquals("Combining paused · Work laptop is not reachable")
        // after a restart the flags are gone but the journal's sentence is not
        c.flow.value = st("describing", paused = true, error = "The main library's device cannot be reached.")
        line().assertTextEquals("Combining paused · Work laptop is not reachable")
        c.flow.value = st("describing", paused = true, error = "Waiting for the Keepfin folder")
        line().assertTextEquals("Combining paused · Waiting for the Keepfin folder")
    }

    @Test fun aFailureIsOneSentenceWithTryAgain() {
        val c = FakeDeviceCombine(st("describing", paused = true, error = "The library changed while it was being combined. Start again."))
        showPage(c)
        line().assertTextEquals("Combining stopped · The library changed while it was being combined. Start again.")
        rule.onNodeWithTag(CombineTags.cardResume("dev1")).performScrollTo().performClick()
        rule.waitUntil(5_000) { c.calls.contains("resume") }
        rule.onNodeWithText("Try again").assertExists()
    }

    @Test fun aReviewWaitingOnTheCardCanBeReviewedOrCancelled() {
        val c = FakeDeviceCombine(ready)
        showPage(c)
        line().assertTextEquals("Ready to combine · nothing has changed yet")
        rule.onNodeWithTag(CombineTags.cardCancel("dev1")).performScrollTo().performClick()
        rule.waitUntil(5_000) { c.calls.contains("cancel") }
        c.flow.value = ready
        rule.onNodeWithTag(CombineTags.cardReview("dev1")).performScrollTo().performClick()
        // the review opens over the page
        rule.onNodeWithTag(CombineTags.QUESTION).assertIsDisplayed()
    }

    @Test fun lookingAtBothLibrariesShowsOnTheCardToo() {
        val c = FakeDeviceCombine(st("planned", started = false, done = 120, total = 900))
        showPage(c)
        line().assertTextEquals("Looking at both libraries · 120 of 900")
    }

    @Test fun whenDoneTheCardSaysUntilWhenAndTheOpenedCardOffersSeparate() {
        val c = FakeDeviceCombine(st("active", keepUntil = inAMonth))
        showPage(c)
        line().assertTextContains("Combined · Separate again until ", substring = true)
        val until = java.text.DateFormat.getDateInstance(java.text.DateFormat.LONG).format(java.util.Date(inAMonth))
        line().assertTextEquals("Combined · Separate again until $until")
        // collapsed: no buttons; opened: Separate libraries… and Keep combined
        rule.onNodeWithTag(CombineTags.cardSeparate("dev1")).assertDoesNotExist()
        tap(DevicesTags.chevron("dev1"))
        rule.onNodeWithTag(CombineTags.cardSeparate("dev1")).performScrollTo().assertHeightIsAtLeast(48.dp).assertIsDisplayed()
        rule.onNodeWithTag(CombineTags.cardKeep("dev1")).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        rule.waitUntil(5_000) { c.calls.contains("keepCombined") }
    }

    @Test fun afterTheThirtyDaysThereIsNoSeparateAnymore() {
        val c = FakeDeviceCombine(st("active", keepUntil = System.currentTimeMillis() - 1000))
        showPage(c)
        line().assertTextEquals("Combined")
        tap(DevicesTags.chevron("dev1"))
        rule.onNodeWithTag(CombineTags.cardSeparate("dev1")).assertDoesNotExist()
    }

    @Test fun nothingShowsOnTheCardWhenNothingIsGoingOn() {
        val c = FakeDeviceCombine(CombineState())
        showPage(c)
        rule.onNodeWithTag(CombineTags.card("dev1")).assertDoesNotExist()
        c.flow.value = st("separated", keepUntil = inAMonth)
        rule.onNodeWithTag(CombineTags.card("dev1")).assertDoesNotExist()
    }

    @Test fun separatingShowsItsOwnLine() {
        val c = FakeDeviceCombine(st("separating"))
        showPage(c)
        line().assertTextEquals("Separating libraries…")
    }

    // ---- Separate again --------------------------------------------------------------------------------------------------------

    private fun openSeparate(c: FakeDeviceCombine) {
        showPage(c)
        tap(DevicesTags.chevron("dev1"))
        tap(CombineTags.cardSeparate("dev1"))
    }

    @Test fun theSeparateDialogSaysWhatGoesBackAndKeepsTheCopiesByDefault() {
        val c = FakeDeviceCombine(st("active", keepUntil = inAMonth))
        openSeparate(c)
        rule.onNodeWithTag(CombineTags.SEP_BACK).assertTextEquals("12 items this phone brought are taken out of Work laptop’s library. This phone goes back to its own library.")
        rule.onNodeWithTag(CombineTags.SEP_ADDED).assertTextEquals("2 items added here since combining come back to this phone’s own library.")
        rule.onNodeWithTag(CombineTags.SEP_HELD).assertTextEquals("Work laptop has copies of 3 items this phone brought.")
        rule.onNodeWithTag(CombineTags.SEP_KEEP).assertIsSelected()
        rule.onNodeWithText("Keep them on Work laptop").assertIsDisplayed()
        rule.onNodeWithTag(CombineTags.SEP_REMOVE).assertIsNotSelected()
        rule.onNodeWithTag(CombineTags.SEP_NOTE).assertTextEquals("Changes you made since combining are not brought back.")
        rule.onNodeWithTag(CombineTags.SEP_FOLDER).assertTextEquals("Nothing is deleted from this phone's folder.")
        rule.onNodeWithTag(CombineTags.SEP_UNREACHABLE).assertDoesNotExist()
        rule.onNodeWithTag(CombineTags.SEP_GO).assertTextEquals("Separate")
        press(CombineTags.SEP_GO)
        rule.waitUntil(5_000) { c.calls.any { it.startsWith("separate") } }
        assertTrue(c.calls.contains("separate:keep=true:anyway=false"))
        rule.onNodeWithTag(CombineTags.SEPARATE).assertDoesNotExist()
    }

    @Test fun removeThemSeparatesWithoutKeepingTheCopies() {
        val c = FakeDeviceCombine(st("active", keepUntil = inAMonth))
        openSeparate(c)
        press(CombineTags.SEP_REMOVE)
        rule.onNodeWithTag(CombineTags.SEP_REMOVE).assertIsSelected()
        press(CombineTags.SEP_GO)
        rule.waitUntil(5_000) { c.calls.any { it.startsWith("separate") } }
        assertTrue(c.calls.contains("separate:keep=false:anyway=false"))
    }

    @Test fun whenNothingIsHeldElsewhereThereIsNoQuestionAndNoAddedLine() {
        val c = FakeDeviceCombine(st("active", keepUntil = inAMonth))
        c.preview = SeparatePreview(brought = 1, heldByPeer = 0, addedSince = 0, peerReachable = true, keepUntil = null)
        openSeparate(c)
        rule.onNodeWithTag(CombineTags.SEP_BACK).assertTextEquals("1 item this phone brought is taken out of Work laptop’s library. This phone goes back to its own library.")
        rule.onNodeWithTag(CombineTags.SEP_ADDED).assertDoesNotExist()
        rule.onNodeWithTag(CombineTags.SEP_HELD).assertDoesNotExist()
        rule.onNodeWithTag(CombineTags.SEP_KEEP).assertDoesNotExist()
    }

    @Test fun anUnreachableDeviceMeansSeparateAnyway() {
        val c = FakeDeviceCombine(st("active", keepUntil = inAMonth))
        c.preview = SeparatePreview(brought = 12, heldByPeer = 3, addedSince = 2, peerReachable = false, keepUntil = null)
        openSeparate(c)
        rule.onNodeWithTag(CombineTags.SEP_UNREACHABLE).assertTextEquals("Work laptop is not reachable. Separate anyway: Work laptop keeps everything this phone brought.")
        rule.onNodeWithTag(CombineTags.SEP_KEEP).assertDoesNotExist()
        rule.onNodeWithTag(CombineTags.SEP_GO).assertTextEquals("Separate anyway")
        press(CombineTags.SEP_GO)
        rule.waitUntil(5_000) { c.calls.any { it.startsWith("separate") } }
        assertTrue(c.calls.contains("separate:keep=true:anyway=true"))
    }

    @Test fun cancelLeavesEverythingAsItWas() {
        val c = FakeDeviceCombine(st("active", keepUntil = inAMonth))
        openSeparate(c)
        press(CombineTags.SEP_CANCEL)
        rule.onNodeWithTag(CombineTags.SEPARATE).assertDoesNotExist()
        assertTrue(c.calls.none { it.startsWith("separate") })
    }

    // ---- in the setup after pairing ----------------------------------------------------------------------------------------------

    @Test fun twoSeparateLibrariesNowOfferToCombineInsteadOfTheComingSoonNote() {
        val copies = FakeDeviceCopies(
            CopiesUi(ready = true, places = emptyList(), spaces = listOf("s1" to "Music"), devices = listOf(PeerChoiceUi("dev1", "Work laptop", true, false, "Work laptop does not share this library with you."))),
        )
        val combine = FakeDeviceCombine(CombineState())
        showPage(combine, copies)
        tap(DevicesTags.chevron("dev1"))
        tap(SetupTags.cardSetUp("dev1"))
        rule.waitUntil(5_000) { combine.calls.contains("review:dev1") }
        rule.onNodeWithText("Combine libraries").assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.DIFFERENT).assertDoesNotExist()
        combine.flow.value = ready
        rule.onNodeWithTag(CombineTags.QUESTION).assertIsDisplayed()
        // Later puts the libraries back as they were
        press(CombineTags.LATER)
        rule.waitUntil(5_000) { combine.calls.contains("cancel") }
        rule.onNodeWithTag(CombineTags.STEP).assertDoesNotExist()
    }

    // ---- screenshots (light and dark, font scale 1.15) -----------------------------------------------------------------------------

    private fun shot(name: String, dark: Boolean, initial: CombineState, steps: () -> Unit = {}, capture: String = CombineTags.STEP, page: Boolean = false, fake: (FakeDeviceCombine) -> Unit = {}) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        val c = FakeDeviceCombine(initial).also(fake)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1.15f)) {
                KeepfinTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                    if (page) {
                        val controller = CombineController(FakeController(state(paired = listOf(laptop()))), c)
                        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) { DevicesScreen(controller) }
                    } else CombineStepDialog("dev1", "Work laptop", true, c) {}
                }
            }
        }
        steps()
        rule.waitForIdle()
        Thread.sleep(900)
        saveNode("combine-$name-${if (dark) "dark" else "light"}", rule.onNodeWithTag(capture))
        Thread.sleep(500)
    }

    private fun expandCard() { tap(DevicesTags.chevron("dev1")) }

    @Test fun shotStepLight() = shot("step", false, ready)
    @Test fun shotStepDark() = shot("step", true, ready)
    @Test fun shotWaitLight() = shot("wait", false, st("planned", started = false, done = 400, total = 1200))
    @Test fun shotWaitDark() = shot("wait", true, st("planned", started = false, done = 400, total = 1200))
    @Test fun shotReviewLight() = shot("review", false, ready, { press(CombineTags.REVIEW); tap(CombineTags.DETAILS_TOGGLE) })
    @Test fun shotReviewDark() = shot("review", true, ready, { press(CombineTags.REVIEW); tap(CombineTags.DETAILS_TOGGLE) })
    @Test fun shotCardRunningLight() = shot("card-running", false, st("describing", done = 1230, total = 1450), capture = CombineTags.card("dev1"), page = true, steps = { line() })
    @Test fun shotCardRunningDark() = shot("card-running", true, st("describing", done = 1230, total = 1450), capture = CombineTags.card("dev1"), page = true, steps = { line() })
    @Test fun shotCardPausedLight() = shot("card-paused", false, st("describing", paused = true, error = "Waiting for the Keepfin folder", done = 400, total = 1450), capture = CombineTags.card("dev1"), page = true, steps = { line() })
    @Test fun shotCardDoneLight() = shot("card-done", false, st("active", keepUntil = inAMonth), capture = CombineTags.card("dev1"), page = true, steps = { expandCard() })
    @Test fun shotCardDoneDark() = shot("card-done", true, st("active", keepUntil = inAMonth), capture = CombineTags.card("dev1"), page = true, steps = { expandCard() })
    @Test fun shotSeparateLight() = shot("separate", false, st("active", keepUntil = inAMonth), page = true, capture = CombineTags.SEPARATE, steps = { expandCard(); tap(CombineTags.cardSeparate("dev1")) })
    @Test fun shotSeparateDark() = shot("separate", true, st("active", keepUntil = inAMonth), page = true, capture = CombineTags.SEPARATE, steps = { expandCard(); tap(CombineTags.cardSeparate("dev1")) })
    @Test fun shotUnreachableLight() = shot(
        "unreachable", false, st("active", keepUntil = inAMonth), page = true, capture = CombineTags.SEPARATE, steps = { expandCard(); tap(CombineTags.cardSeparate("dev1")) },
        fake = { it.preview = SeparatePreview(12, 3, 2, false, null) },
    )
    @Test fun shotUnreachableDark() = shot(
        "unreachable", true, st("active", keepUntil = inAMonth), page = true, capture = CombineTags.SEPARATE, steps = { expandCard(); tap(CombineTags.cardSeparate("dev1")) },
        fake = { it.preview = SeparatePreview(12, 3, 2, false, null) },
    )

    // ---- big text -----------------------------------------------------------------------------------------------------------------

    @Test fun theStepAndTheButtonsStillFitAtTheLargestFontSize() {
        val c = FakeDeviceCombine(ready)
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1.3f)) {
                KeepfinTheme(ThemeMode.LIGHT) { CombineStepDialog("dev1", "Work laptop", true, c) {} }
            }
        }
        rule.onNodeWithTag(CombineTags.REVIEW).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(CombineTags.LATER).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(CombineTags.KEEP_THEIRS).performScrollTo().assertHeightIsAtLeast(48.dp)
        press(CombineTags.REVIEW)
        rule.onNodeWithTag(CombineTags.COMBINE).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }
}
