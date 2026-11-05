package app.keepfin.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onRoot
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.copies.CopiesUi
import app.keepfin.copies.CopyCardUi
import app.keepfin.copies.CopyDraft
import app.keepfin.copies.CopyHoldsUi
import app.keepfin.copies.CopyPlanLine
import app.keepfin.copies.CopyPreview
import app.keepfin.copies.CopyProgressUi
import app.keepfin.copies.CopyStatus
import app.keepfin.copies.PeerCardUi
import app.keepfin.copies.PeerChoiceUi
import app.keepfin.core.copies.CopyMode
import app.keepfin.net.DevicesState
import app.keepfin.net.PairedDeviceUi
import app.keepfin.net.PairedLibraryUi
import app.keepfin.net.PairingUi
import app.keepfin.ui.theme.KeepfinTheme
import app.keepfin.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

internal class FakeDeviceCopies(initial: CopiesUi) : DeviceCopies {
    val flow = MutableStateFlow(initial)
    override val state: StateFlow<CopiesUi> get() = flow
    val calls = ArrayList<String>()
    val drafts = ArrayList<CopyDraft>()
    var next: CopyPreview = CopyPreview("p1", "ready", null, true, listOf(CopyPlanLine("Copy to the device", 120, 2L * 1024 * 1024 * 1024)), 120, 2L * 1024 * 1024 * 1024, 0, emptyList(), 0, 50L * 1024 * 1024 * 1024, true, 4)
    override fun refresh() { calls += "refresh" }
    override fun preview(draft: CopyDraft, previousId: String?): CopyPreview { drafts += draft; calls += "preview:$previousId"; return next }
    override fun cancelPreview(placeId: String) { calls += "cancel:$placeId" }
    override fun add(placeId: String) { calls += "add:$placeId" }
}

/** The page's fake with a copies engine. */
internal class SetupController(val base: FakeController, override val copies: DeviceCopies) : DevicesController by base

@RunWith(AndroidJUnit4::class)
class DeviceSetupUiTest {
    @get:Rule val rule = createComposeRule()

    private fun laptop() = PairedDeviceUi(
        deviceId = "dev1", fingerprint = "1111 2222 3333 4444 5555", name = "Work laptop", platform = "windows", pairedAt = 1, lastSeen = System.currentTimeMillis() - 60_000, online = true,
        libraries = listOf(PairedLibraryUi("lib1", "Family music", listOf("metadata.read", "metadata.write"))), unreachableSince = null, address = "192.168.1.20",
        jobsAllowed = true, jobSpaces = emptyList(), shareable = emptyList(), allowMobileFiles = false,
    )

    private val spaces = listOf("s1" to "Music", "s2" to "Videos", "s3" to "Downloads")
    private fun ui(allowed: Boolean = true, places: List<CopyCardUi> = emptyList()) =
        CopiesUi(ready = true, places = places, spaces = spaces, devices = listOf(PeerChoiceUi("dev1", "Work laptop", true, allowed, if (allowed) null else "Work laptop does not let this phone keep a copy on it.")))

    private fun asking(stage: String) = PairingUi("pr1", "scan", "asking", stage, "Work laptop", "1111 2222 3333 4444 5555", null, null, 0L)

    private var reviewed = 0

    private fun showPage(c: DevicesController) {
        rule.setContent {
            KeepfinTheme(ThemeMode.LIGHT) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) { DevicesScreen(c, onReviewCopies = { reviewed++ }) }
            }
        }
    }

    private fun tap(tag: String) = rule.onNodeWithTag(tag).performScrollTo().performClick()

    /** The sheet's bottom buttons are outside its scrolling part. */
    private fun press(tag: String) = rule.onNodeWithTag(tag).performClick()

    private fun openSheet(copies: FakeDeviceCopies) {
        val base = FakeController(state(paired = listOf(laptop())))
        showPage(SetupController(base, copies))
        tap(DevicesTags.chevron("dev1"))
        tap(SetupTags.cardSetUp("dev1"))
    }

    @Test fun setupIsOfferedRightAfterAPairingTheAskingSideMade() {
        val copies = FakeDeviceCopies(ui())
        val base = FakeController(state(paired = listOf(laptop()), pairing = asking("done")))
        showPage(SetupController(base, copies))
        rule.onNodeWithText("Work laptop is connected").assertIsDisplayed()
        rule.onNodeWithText("Choose what stays up to date.").assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.PROMPT_LATER).assertIsDisplayed()
        tap(SetupTags.PROMPT_SET_UP)
        rule.onNodeWithTag(SetupTags.STEP1).assertIsDisplayed()
        assertTrue(base.calls.contains("dismissPairing"))
    }

    @Test fun laterJustCloses() {
        val base = FakeController(state(paired = listOf(laptop()), pairing = asking("done")))
        showPage(SetupController(base, FakeDeviceCopies(ui())))
        tap(SetupTags.PROMPT_LATER)
        rule.onNodeWithTag(SetupTags.SHEET).assertDoesNotExistCompat()
    }

    @Test fun withoutACopiesEngineThePairingEndsAsBefore() {
        val base = FakeController(state(paired = listOf(laptop()), pairing = asking("done")))
        showPage(base)
        rule.onNodeWithText("Connected to Work laptop").assertIsDisplayed()
    }

    @Test fun stepOneDefaultsAndExplanations() {
        openSheet(FakeDeviceCopies(ui()))
        rule.onNodeWithTag(SetupTags.LIST_NOTE).assertIsDisplayed()
        rule.onNodeWithText("Your library list (names, playlists, what you played) stays in step by itself. It is small.").assertIsDisplayed()
        // Music and Videos on, Downloads off
        rule.onNodeWithTag(SetupTags.space("s1")).assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.space("s3")).assertIsDisplayed()
        rule.onNodeWithText("Both ways").assertIsDisplayed()
        rule.onNodeWithText("Only to Work laptop").assertIsDisplayed()
        rule.onNodeWithText("Keep it up to date by itself").assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.NEXT).assertIsEnabled()
        rule.onNodeWithTag(SetupTags.LATER).assertIsDisplayed()
    }

    @Test fun notAllowedExplainsAndBlocksNext() {
        val copies = FakeDeviceCopies(ui(allowed = false))
        openSheet(copies)
        rule.onNodeWithTag(SetupTags.NOT_ALLOWED).assertIsDisplayed()
        rule.onNodeWithText("Work laptop does not let this phone keep a copy on it.").assertIsDisplayed()
        rule.onNodeWithText("On Work laptop, open Devices, open this phone and turn on Keep a copy here.").assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.NEXT).assertIsNotEnabled()
        tap(SetupTags.CHECK_AGAIN)
        rule.waitForIdle()
        // the engine re-reads; once the device allows it, Next opens
        copies.flow.value = ui(allowed = true)
        rule.onNodeWithTag(SetupTags.NEXT).assertIsEnabled()
        assertTrue(copies.calls.contains("refresh"))
    }

    @Test fun reviewShowsSizesAndThatNothingIsRemoved() {
        val copies = FakeDeviceCopies(ui())
        openSheet(copies)
        press(SetupTags.NEXT)
        rule.waitUntil(5_000) { copies.calls.any { it.startsWith("preview") } }
        rule.onNodeWithTag(SetupTags.TO_DEVICE).assertIsDisplayed()
        rule.onNodeWithText("To Work laptop: 120 files · 2.0 GB").assertIsDisplayed()
        rule.onNodeWithText("To this phone: 4 files from Work laptop").assertIsDisplayed()
        rule.onNodeWithText("Enough room on Work laptop (50.0 GB free)").assertIsDisplayed()
        rule.onNodeWithText("The first sync only adds files. Nothing is removed or replaced.").assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.DIFFERS).assertDoesNotExistCompat()
        rule.onNodeWithTag(SetupTags.START).assertIsEnabled()
        // Music and Videos, both ways, on the device's address
        val d = copies.drafts.last()
        assertEquals("peer:dev1", d.treeUri)
        assertEquals(CopyMode.TwoWay, d.mode)
        assertEquals(setOf("s1", "s2"), d.holds.spaces)
    }

    @Test fun differencesAppearOnlyWhenThereAreAnd_startSendsTheAddForThePreviewedPlace() {
        val copies = FakeDeviceCopies(ui())
        copies.next = copies.next.copy(placeId = "place-9", differences = (1..7).map { "Music/a$it.flac" to "different" }, differencesMore = 3)
        openSheet(copies)
        press(SetupTags.NEXT)
        rule.waitUntil(5_000) { copies.calls.any { it.startsWith("preview") } }
        rule.onNodeWithText("Files that differ: Keepfin keeps both until you choose").assertIsDisplayed()
        rule.onNodeWithText("and 5 more").assertIsDisplayed()
        press(SetupTags.START)
        rule.waitUntil(5_000) { copies.calls.contains("add:place-9") }
        rule.onNodeWithTag(SetupTags.SHEET).assertDoesNotExistCompat()
    }

    @Test fun notEnoughRoomDisablesStart() {
        val copies = FakeDeviceCopies(ui())
        copies.next = copies.next.copy(enough = false, freeBytes = 1024L * 1024)
        openSheet(copies)
        press(SetupTags.NEXT)
        rule.waitUntil(5_000) { copies.calls.any { it.startsWith("preview") } }
        rule.onNodeWithTag(SetupTags.PROBLEM).assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.START).assertIsNotEnabled()
    }

    @Test fun aBusyPreviewIsExplainedAndOffersCheckAgain() {
        val copies = FakeDeviceCopies(ui())
        copies.next = CopyPreview("p1", "busy", null)
        openSheet(copies)
        press(SetupTags.NEXT)
        rule.waitUntil(5_000) { copies.calls.any { it.startsWith("preview") } }
        rule.onNodeWithText("Another sync is running. Wait for it to finish, then press Check again.").assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.START).assertIsNotEnabled()
        copies.next = copies.next.copy(status = "ready")
        tap(SetupTags.RETRY)
        rule.waitUntil(5_000) { copies.calls.count { it.startsWith("preview") } >= 2 }
    }

    @Test fun laterBeforeStartDropsThePreview() {
        val copies = FakeDeviceCopies(ui())
        openSheet(copies)
        press(SetupTags.NEXT)
        rule.waitUntil(5_000) { copies.calls.any { it.startsWith("preview") } }
        press(SetupTags.BACK)
        press(SetupTags.LATER)
        rule.waitUntil(5_000) { copies.calls.contains("cancel:p1") }
    }

    @Test fun theCardShowsTheCopyAndItsReviewLink() {
        val card = CopyCardUi(
            "c1", "Copy on Work laptop", CopyMode.TwoWay, CopyHoldsUi(), "", true, CopyStatus.SYNCING,
            progress = CopyProgressUi(120, 800, 1024L * 1024 * 1024, 3L * 1024 * 1024 * 1024, null), review = 2,
            peer = PeerCardUi("dev1", "Work laptop", true),
        )
        val copies = FakeDeviceCopies(ui(places = listOf(card)))
        val base = FakeController(state(paired = listOf(laptop())))
        showPage(SetupController(base, copies))
        tap(DevicesTags.chevron("dev1"))
        rule.onNodeWithTag(SetupTags.copyStatus("dev1")).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Copying 120 of 800 files · 2.0 GB left").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.reviewLink("dev1")).performScrollTo().assertIsDisplayed().performClick()
        assertEquals(1, reviewed)
        // a device with a copy no longer offers the setup
        rule.onNodeWithTag(SetupTags.cardSetUp("dev1")).assertDoesNotExistCompat()
    }

    @Test fun twoSeparateLibrariesGetOneSentenceAndOkInsteadOfCopyChoices() {
        val copies = FakeDeviceCopies(
            CopiesUi(ready = true, places = emptyList(), spaces = spaces, devices = listOf(PeerChoiceUi("dev1", "Work laptop", true, false, "Work laptop does not share this library with you."))),
        )
        openSheet(copies)
        rule.onNodeWithTag(SetupTags.DIFFERENT).assertIsDisplayed()
        rule.onNodeWithText("Work laptop and this phone each have their own library.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("coming in an update", substring = true).assertIsDisplayed()
        rule.onNodeWithText("These libraries stay separate for now").assertIsDisplayed()
        rule.onNodeWithText("Continue").assertIsDisplayed()
        rule.onNodeWithTag(SetupTags.STEP1).assertDoesNotExist()
        rule.onNodeWithTag(SetupTags.NEXT).assertDoesNotExist()
        press(SetupTags.DIFFERENT_OK)
        rule.onNodeWithTag(SetupTags.SHEET).assertDoesNotExist()
        assertTrue("nothing was previewed", copies.calls.none { it.startsWith("preview") })
    }

    private fun shotDifferent(dark: Boolean) {
        org.junit.Assume.assumeTrue(androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        val copies = FakeDeviceCopies(CopiesUi(ready = true, places = emptyList(), spaces = spaces, devices = listOf(PeerChoiceUi("dev1", "Work laptop", true, false, "Work laptop does not share this library with you."))))
        rule.setContent {
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, 1.15f)) {
                KeepfinTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                    Box(Modifier.androidx_width360().background(MaterialTheme.colorScheme.background).padding(16.dp)) {
                        androidx.compose.material3.Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) {
                            androidx.compose.foundation.layout.Column(Modifier.padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                                androidx.compose.material3.Text("These libraries stay separate for now", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                                DifferentLibrariesContent("Work laptop") { }
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        saveNode("devices-different-${if (dark) "dark" else "light"}", rule.onRoot())
        Thread.sleep(700)
    }
    @Test fun shotDifferentLight() = shotDifferent(false)
    @Test fun shotDifferentDark() = shotDifferent(true)
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistCompat() = this.assertDoesNotExist()

private fun Modifier.androidx_width360() = this.then(Modifier.width(360.dp))
