package app.keepfin.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertTextContains
import app.keepfin.net.LastSyncUi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.core.net.NetAction
import app.keepfin.folder.FolderAccess
import app.keepfin.net.DevicesState
import app.keepfin.net.DiscoveredUi
import app.keepfin.net.IncomingJobRow
import app.keepfin.net.JoinUi
import app.keepfin.net.LibraryInfoUi
import app.keepfin.net.NetworkUi
import app.keepfin.net.EngineJobPort
import app.keepfin.net.OfferUi
import app.keepfin.net.PairedDeviceUi
import app.keepfin.net.PairedLibraryUi
import app.keepfin.net.PairingUi
import app.keepfin.net.QrUi
import app.keepfin.net.RemoteJobRowUi
import app.keepfin.net.RemoveResult
import app.keepfin.net.ShareableUi
import app.keepfin.net.ThisDevice
import app.keepfin.ui.theme.KeepfinTheme
import app.keepfin.ui.theme.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

internal class FakeController(initial: DevicesState) : DevicesController {
    val flow = MutableStateFlow(initial)
    override val state: StateFlow<DevicesState> get() = flow
    val calls = ArrayList<String>()
    var askError: String? = null
    var access: List<NetAction> = emptyList()
    override fun enable() { calls += "enable" }
    override fun disable() { calls += "disable" }
    override fun setKeepReachable(on: Boolean) { calls += "reachable:$on" }
    override fun rename(name: String) { calls += "rename:$name" }
    override fun openPairing() { calls += "openPairing" }
    override fun closePairing() { calls += "closePairing" }
    override fun showQr() { calls += "showQr" }
    override fun startLooking() { calls += "startLooking" }
    override fun stopLooking() { calls += "stopLooking" }
    override fun beginAsk(text: String): String? { calls += "ask:$text"; return askError }
    override fun decide(id: String, match: Boolean) { calls += "decide:$id:$match" }
    override fun dismissPairing() { calls += "dismissPairing" }
    override fun remove(deviceId: String): RemoveResult { calls += "remove:$deviceId"; return RemoveResult(listOf("Library"), emptyList()) }
    override fun undoPair(deviceId: String): RemoveResult { calls += "undo:$deviceId"; return RemoveResult(listOf("Library"), emptyList()) }
    override fun syncNow(deviceId: String) { calls += "syncNow:$deviceId" }
    override fun setAccess(deviceId: String, libraryId: String, actions: List<NetAction>) { calls += "access:$deviceId"; access = actions }
    override fun setAllowMobileFiles(deviceId: String, allow: Boolean) { calls += "mobile:$allow" }
    override fun shareLibrary(deviceId: String, libraryId: String) { calls += "share:$libraryId" }
    override fun joinLibrary(deviceId: String, libraryId: String, target: FolderAccess, treeUri: String): String? = null
    override fun cancelJoin() { calls += "cancelJoin" }
    override fun dismissJoin() { calls += "dismissJoin" }
    override fun allowJob(jobId: String) { calls += "allow:$jobId" }
    override fun declineJob(jobId: String) { calls += "decline:$jobId" }
    override fun dismissRemoteJob(requestId: String) { calls += "dismissJob:$requestId" }
}

internal fun state(
    enabled: Boolean = true,
    paired: List<PairedDeviceUi> = emptyList(),
    pairing: PairingUi? = null,
    pairingUntil: Long? = null,
    qr: QrUi? = null,
    discovered: List<DiscoveredUi> = emptyList(),
    offers: List<OfferUi> = emptyList(),
    join: JoinUi? = null,
    remote: List<RemoteJobRowUi> = emptyList(),
    incoming: List<IncomingJobRow> = emptyList(),
    notes: List<String> = emptyList(),
    portError: String? = null,
    network: NetworkUi = NetworkUi(lan = true, tailscaleAddress = null),
    scanning: Boolean = false,
    libraries: List<LibraryInfoUi> = listOf(LibraryInfoUi("lib1", "Family music", true, false)),
) = DevicesState(
    available = true, thisDevice = ThisDevice("Pixel", "ab12 cd34 ef56 7890 1234", "android"), enabled = enabled, port = if (enabled) 47215 else null, portError = portError, libraries = libraries,
    paired = paired, discovered = discovered, pairingUntil = pairingUntil, pairing = pairing, qr = qr, notes = notes, offers = offers, join = join, remoteJobs = remote, incomingJobs = incoming,
    keepReachable = false, metered = false, network = network, scanning = scanning,
)

private fun laptop(online: Boolean = true, unreachableSince: Long? = null) = PairedDeviceUi(
    deviceId = "dev1", fingerprint = "1111 2222 3333 4444 5555", name = "Work laptop", platform = "windows", pairedAt = 1, lastSeen = System.currentTimeMillis() - 60_000, online = online,
    libraries = listOf(PairedLibraryUi("lib1", "Family music", listOf("metadata.read", "metadata.write"))), unreachableSince = unreachableSince, address = "192.168.1.20",
    jobsAllowed = true, jobSpaces = listOf("music" to "Music"), shareable = listOf(ShareableUi("lib2", "Podcasts")), allowMobileFiles = false,
)

@RunWith(AndroidJUnit4::class)
class DevicesUiTest {
    @get:Rule val rule = createComposeRule()

    private fun show(c: FakeController, dark: Boolean = false, fontScale: Float? = null, widthDp: Int? = null) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides (if (fontScale == null) base else Density(base.density, fontScale))) {
                KeepfinTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                    // The app shows this page on the Settings background; without it, dark-theme screenshots draw light text on a light window.
                    Box((if (widthDp != null) Modifier.width(widthDp.dp) else Modifier).fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) { DevicesScreen(c) }
                }
            }
        }
    }

    private fun tap(tag: String) = rule.onNodeWithTag(tag).performScrollTo().performClick()

    /** "Add by address" and "Type a code" live inside "Not seeing it?". */
    private fun openHelp() = tap(DevicesTags.NOT_SEEING)
    private fun openAdd() { openHelp(); tap(DevicesTags.ADD) }

    @Test fun theNameOtherDevicesSeeIsUnderTheSwitchWithRename() {
        val c = FakeController(state())
        show(c)
        rule.onNode(hasTestTag(DevicesTags.NAME) and hasAnyDescendant(hasText("Pixel"))).assertIsDisplayed()
        rule.onNodeWithTag(DevicesTags.RENAME).performClick()
        rule.onNodeWithTag("devices-rename-save").assertIsNotEnabled() // the same name is no change
        rule.onNodeWithTag("devices-rename-field").performTextReplacement("Living room phone")
        rule.onNodeWithTag("devices-rename-save").performClick()
        rule.waitUntil(5_000) { "rename:Living room phone" in c.calls }
    }

    @Test fun offIsOneCalmStateThatSaysSoAndTurnsDevicesOn() {
        val c = FakeController(state(enabled = false))
        show(c)
        rule.onNodeWithTag(DevicesTags.STATUS).assertIsDisplayed()
        rule.onNodeWithTag(DevicesTags.OFF).assertIsDisplayed()
        rule.onNodeWithText("Only devices you pair can connect.", substring = true).assertExists()
        // one way to turn it on (the switch), not a second button that does the same
        rule.onNodeWithText("Turn on Devices").assertDoesNotExist()
        // nothing else: no scan buttons, no available devices, no paired cards
        for (tag in listOf(DevicesTags.THIS_DEVICE, DevicesTags.NAME, DevicesTags.ADD, DevicesTags.AVAILABLE, DevicesTags.YOURS, DevicesTags.SCAN, DevicesTags.SHOW_QR, DevicesTags.AWAY)) rule.onNodeWithTag(tag).assertDoesNotExist()
        tap(DevicesTags.SWITCH)
        rule.waitUntil(5_000) { c.calls.contains("enable") }
    }

    @Test fun theSwitchComesFirstThenAvailableYourDevicesAndTheRestBelow() {
        val c = FakeController(state(paired = listOf(laptop(), oldTablet), offers = listOf(OfferUi("dev1", "Work laptop", "libX", "Shared films", true))))
        show(c)
        rule.onNodeWithTag(DevicesTags.STATUS).assertIsDisplayed()
        rule.onNodeWithText("Visible on Home network").assertExists()
        rule.onNodeWithTag(DevicesTags.SWITCH).assertExists()
        // the guide and Away from home are no longer on the page: one "?" at the top opens them; the address entry is in "Not seeing it?", the typed code has its own field
        rule.onNodeWithTag(DevicesTags.AWAY).assertDoesNotExist()
        rule.onNodeWithTag(DevicesTags.ADD).assertDoesNotExist()
        rule.onNodeWithTag(DevicesTags.TYPE_CODE).assertExists()
        fun top(tag: String) = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().top.value
        val order = listOf(DevicesTags.HOW, DevicesTags.STATUS, DevicesTags.AVAILABLE, DevicesTags.paired("dev1"), DevicesTags.paired("dev12"), DevicesTags.GET_LIBRARY, DevicesTags.ADVANCED).map(::top)
        assertEquals("the ? at the top, the switch, available devices, your devices, libraries on other devices, then Advanced", order.sorted(), order)
        assertEquals(7, order.toSet().size)
        rule.onNodeWithTag(DevicesTags.PROBLEM).assertDoesNotExist()
        rule.onNodeWithTag(DevicesTags.THIS_DEVICE).assertDoesNotExist()
        rule.onNodeWithTag(DevicesTags.KEEP_REACHABLE).assertDoesNotExist()
    }

    @Test fun scanAndShowMyCodeSitSideBySideAndShowMyCodeOpensThePhoneCode() {
        val c = FakeController(state(pairingUntil = System.currentTimeMillis() + 100_000, qr = QrUi("keepfin://pair?h=192.168.1.5&p=47215&k=00&t=00", System.currentTimeMillis() + 100_000)))
        show(c)
        val scan = rule.onNodeWithTag(DevicesTags.SCAN).assertIsDisplayed().assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        val code = rule.onNodeWithTag(DevicesTags.SHOW_QR).assertIsDisplayed().assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        assertTrue("side by side", scan.right <= code.left && scan.top < code.bottom && code.top < scan.bottom)
        rule.onNodeWithText("Show my code").assertExists()
        tap(DevicesTags.SHOW_QR)
        rule.waitUntil(5_000) { c.calls.contains("openPairing") && c.calls.contains("showQr") }
        rule.onNodeWithTag(DevicesTags.ADD_SCREEN).assertIsDisplayed()
        rule.onNodeWithTag(DevicesTags.QR).performScrollTo().assertIsDisplayed()
    }

    // ---- Available devices: the circle, the list and the choice between them ----------------------------------------------

    private fun found(n: Int) = (1..n).map { DiscoveredUi("hint%02d00000000000".format(it), "Device $it", "192.168.1.${30 + it}", 47215, if (it == 2) "tailscale" else "lan") }

    @Test fun theViewRuleIsTheCircleForFiveOrFewerWithoutLargeTextAndTheListOtherwiseUntilTheyChoose() {
        assertEquals(AvailableView.CIRCLE, availableView(0, 1f, null))
        assertEquals(AvailableView.CIRCLE, availableView(5, 1.3f, null))
        assertEquals(AvailableView.LIST, availableView(6, 1f, null))
        assertEquals(AvailableView.LIST, availableView(2, 1.31f, null))
        // a choice wins over the automatic rule; a circle that cannot hold them all falls back to the list
        assertEquals(AvailableView.LIST, availableView(1, 1f, "list"))
        assertEquals(AvailableView.CIRCLE, availableView(6, 1.5f, "circle"))
        assertEquals(AvailableView.LIST, availableView(7, 1f, "circle"))
    }

    @Test fun twoDevicesAreACircleAroundThisPhoneEachOneAConnectButton() {
        val c = FakeController(state(discovered = found(2)))
        show(c)
        rule.onNodeWithTag(DevicesTags.CIRCLE).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(DevicesTags.LIST).assertDoesNotExist()
        rule.onNodeWithText("This phone").assertExists()
        rule.onNodeWithText("Device 1").assertExists()
        rule.onNodeWithText("Home network").assertExists()
        rule.onNodeWithText("via Tailscale").assertExists()
        assertTrue("a Connect label on each device", count("Connect") >= 2)
        rule.onNodeWithTag(DevicesTags.nearbyPair("hint0100000000000")).performScrollTo().assertHeightIsAtLeast(48.dp)
        tap(DevicesTags.nearbyPair("hint0100000000000"))
        rule.waitUntil(5_000) { c.calls.contains("ask:192.168.1.31") }
        tap(DevicesTags.nearbyPair("hint0200000000000"))
        rule.waitUntil(5_000) { c.calls.contains("ask:192.168.1.32") }
    }

    @Test fun moreThanFiveOrLargeTextShowASearchableList() {
        show(FakeController(state(discovered = found(6))))
        rule.onNodeWithTag(DevicesTags.LIST).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(DevicesTags.CIRCLE).assertDoesNotExist()
        rule.onNodeWithTag(DevicesTags.SEARCH).assertExists()
        rule.onNodeWithText("Find a device").assertExists()
    }

    @Test fun largeTextShowsTheListEvenForTwoDevices() {
        show(FakeController(state(discovered = found(2))), fontScale = 1.4f)
        rule.onNodeWithTag(DevicesTags.LIST).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(DevicesTags.CIRCLE).assertDoesNotExist()
    }

    @Test fun theListFiltersByNameAndConnectsFromARow() {
        val c = FakeController(state(discovered = found(6)))
        show(c)
        tap(DevicesTags.SEARCH)
        rule.onNodeWithTag(DevicesTags.SEARCH).performTextInput("Device 3")
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(DevicesTags.discovered("hint0400000000000")).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag(DevicesTags.discovered("hint0300000000000")).assertExists()
        rule.onNodeWithTag(DevicesTags.nearbyPair("hint0300000000000")).assertHeightIsAtLeast(48.dp)
        tap(DevicesTags.nearbyPair("hint0300000000000"))
        rule.waitUntil(5_000) { c.calls.contains("ask:192.168.1.33") }
    }

    @Test fun theChoiceBetweenCircleAndListIsRememberedAndOverridesTheRule() {
        val store = MemoryViewStore()
        val c = FakeController(state(discovered = found(2)))
        rule.setContent { KeepfinTheme(ThemeMode.LIGHT) { Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { DevicesScreen(c, viewStore = store) } } }
        rule.onNodeWithTag(DevicesTags.CIRCLE).assertExists()
        assertEquals(null, store.choice)
        tap(DevicesTags.VIEW_LIST)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(DevicesTags.LIST).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("list", store.choice)
        rule.onNodeWithTag(DevicesTags.CIRCLE).assertDoesNotExist()
        tap(DevicesTags.VIEW_CIRCLE)
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(DevicesTags.CIRCLE).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("circle", store.choice)
        // a stored choice is used straight away, even when the rule would say otherwise
        val chosen = MemoryViewStore("list")
        assertEquals(AvailableView.LIST, availableView(2, 1f, chosen.choice))
    }

    @Test fun theChoiceSurvivesInThePhonePreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("test-devices-view", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val first = app.keepfin.ui.UiPreferences(context, "test-devices-view")
        assertEquals(null, first.devicesView)
        PrefsViewStore(first).choose("list")
        assertEquals("list", app.keepfin.ui.UiPreferences(context, "test-devices-view").devicesView)
        first.chooseDevicesView("sideways")
        assertEquals("an unknown value is ignored", "list", first.devicesView)
        context.getSharedPreferences("test-devices-view", android.content.Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun circlePlacesAreFixedByDeviceIdAndNeverJump() {
        val ids = listOf("c3", "a1", "b2")
        val before = circleSlots(ids)
        assertEquals("order of arrival does not matter", before, circleSlots(ids.reversed()))
        assertEquals("every device has its own slot", ids.size, before.values.toSet().size)
        assertTrue(before.values.all { it in 0 until CIRCLE_SLOTS })
        // a newcomer whose place is free leaves everyone else where they were
        val newcomer = (0..200).map { "n$it" }.first { circleSlots(listOf(it)).getValue(it) !in before.values }
        val after = circleSlots(ids + newcomer)
        for ((id, slot) in before) assertEquals(slot, after.getValue(id))
    }

    @Test fun nothingFoundSaysSoAndNotSeeingItOpensTheTroubleshootingWithAddAndTypeACode() {
        show(FakeController(state(scanning = false)))
        tap(DevicesTags.SEARCH_STOP)
        rule.onNodeWithText("Search stopped").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(DevicesTags.NEARBY_EMPTY).assertDoesNotExist()
        rule.onNodeWithTag(DevicesTags.NOT_SEEING).assertHeightIsAtLeast(48.dp)
        tap(DevicesTags.NOT_SEEING)
        rule.onNodeWithTag(DevicesTags.NEARBY_EMPTY).assertIsDisplayed()
        rule.onNodeWithText("Make sure both are on the same Wi-Fi.").assertExists()
        rule.onNodeWithText("Open Keepfin → Devices on the other device", substring = true).assertExists()
        rule.onNodeWithTag(DevicesTags.ADD).performScrollTo().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(DevicesTags.TYPE_CODE).performScrollTo().assertExists()
    }

    @Test fun theStatusLineSitsDirectlyUnderTheDevicesWithNotSeeingItOnTheSameLine() {
        show(FakeController(state(discovered = found(2))))
        val circle = rule.onNodeWithTag(DevicesTags.CIRCLE).performScrollTo().getUnclippedBoundsInRoot()
        val status = rule.onNodeWithTag(DevicesTags.SEARCH_STATUS).getUnclippedBoundsInRoot()
        val notSeeing = rule.onNodeWithTag(DevicesTags.NOT_SEEING).getUnclippedBoundsInRoot()
        assertTrue("the line is right under the circle (gap ${status.top.value - circle.bottom.value} dp)", status.top.value - circle.bottom.value in -48f..40f)
        // the status has a full-width line of its own, the actions are a row below it
        assertTrue("the actions sit below the status", notSeeing.top >= status.bottom - 1f.dp)
        val stop = rule.onNodeWithTag(DevicesTags.SEARCH_STOP).getUnclippedBoundsInRoot()
        assertTrue("Stop and Not seeing it? share a row", stop.top < notSeeing.bottom && notSeeing.top < stop.bottom)
        assertTrue("the status line is the card width", status.right.value - status.left.value > 250f)
    }

    @Test fun anEmptyCircleHasNoBandUnderIt() {
        show(FakeController(state()))
        val circle = rule.onNodeWithTag(DevicesTags.CIRCLE).performScrollTo().getUnclippedBoundsInRoot()
        // just the rings (about 230 dp across) and a margin, not the room three devices would need
        assertTrue("the empty circle is ${circle.bottom.value - circle.top.value} dp high", circle.bottom.value - circle.top.value < 260f)
    }

    @Test fun whileLookingTheStatusLineSaysLookingWithAStopButton() {
        show(FakeController(state(scanning = true)))
        rule.onNodeWithTag(DevicesTags.NEARBY_LOOKING).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Looking on this network…").assertExists()
        rule.onNodeWithText("Search stopped").assertDoesNotExist()
        rule.onNodeWithTag(DevicesTags.SEARCH_STOP).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(DevicesTags.SEARCH_AGAIN).assertDoesNotExist()
    }

    @Test fun lookingMentionsTailscaleOnlyWhenItIsRunning() {
        show(FakeController(state(network = NetworkUi(true, "100.101.102.103"))))
        rule.onNodeWithText("Looking on this network and Tailscale…").performScrollTo().assertExists()
    }

    @Test fun stopEndsTheSearchAndSearchAgainStartsItAgain() {
        val c = FakeController(state())
        show(c)
        rule.waitUntil(5_000) { c.calls.count { it == "startLooking" } == 1 }
        tap(DevicesTags.SEARCH_STOP)
        rule.waitUntil(5_000) { c.calls.contains("stopLooking") }
        rule.onNodeWithTag(DevicesTags.NEARBY_LOOKING).assertDoesNotExist()
        tap(DevicesTags.SEARCH_AGAIN)
        rule.waitUntil(5_000) { c.calls.count { it == "startLooking" } == 2 }
        rule.onNodeWithTag(DevicesTags.SEARCH_STOP).assertExists()
    }

    @Test fun theSearchStopsBySelfAfterSixtySecondsAndOffersSearchAgain() {
        val c = FakeController(state())
        show(c)
        rule.waitUntil(5_000) { c.calls.contains("startLooking") }
        rule.mainClock.advanceTimeBy(1_000)
        assertFalse("still looking at one second", c.calls.contains("stopLooking"))
        rule.onNodeWithTag(DevicesTags.SEARCH_STOP).assertExists()
        rule.mainClock.advanceTimeBy(SEARCH_MS)
        rule.waitUntil(5_000) { c.calls.contains("stopLooking") }
        rule.onNodeWithTag(DevicesTags.SEARCH_AGAIN).assertExists()
        rule.onNodeWithTag(DevicesTags.SEARCH_STOP).assertDoesNotExist()
        rule.onNodeWithText("Search finished · 0 found").assertExists()
    }

    @Test fun leavingThePageStopsTheSearch() {
        val c = FakeController(state())
        val shown = androidx.compose.runtime.mutableStateOf(true)
        rule.setContent { KeepfinTheme(ThemeMode.LIGHT) { Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { if (shown.value) DevicesScreen(c) } } }
        rule.waitUntil(5_000) { c.calls.contains("startLooking") }
        shown.value = false
        rule.waitUntil(5_000) { c.calls.contains("stopLooking") }
    }

    @Test fun theGuideOpensFromTheQuestionMarkAndHoldsAwayFromHome() {
        show(FakeController(state()))
        rule.onNodeWithTag(DevicesTags.GUIDE).assertDoesNotExist()
        rule.onNodeWithTag(DevicesTags.HOW).assertHeightIsAtLeast(48.dp)
        val b = rule.onNodeWithTag(DevicesTags.HOW).getUnclippedBoundsInRoot()
        assertTrue("at the top right", b.top.value < 60f)
        tap(DevicesTags.HOW)
        rule.onNodeWithTag(DevicesTags.GUIDE).assertIsDisplayed()
        rule.onNodeWithText("Find each other").assertExists()
        rule.onNodeWithTag(DevicesTags.AWAY).assertExists()
        rule.onNodeWithText("Tailscale is not running on this phone.").assertExists()
        rule.onNodeWithTag(DevicesTags.AWAY_STEPS).assertExists()
        rule.onNodeWithText("1. Install Tailscale on both devices", substring = true).assertExists()
        rule.onNodeWithTag(DevicesTags.AWAY_GET).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(DevicesTags.GUIDE_CLOSE).performClick()
        rule.onNodeWithTag(DevicesTags.GUIDE).assertDoesNotExist()
    }

    @Test fun theTailscaleLineUnderTheSwitchOpensTheSameGuide() {
        show(FakeController(state()))
        rule.onNodeWithText("Tailscale: not running").assertExists()
        tap(DevicesTags.TAILSCALE_HOW)
        rule.onNodeWithTag(DevicesTags.GUIDE).assertIsDisplayed()
        rule.onNodeWithTag(DevicesTags.AWAY).assertExists()
    }

    @Test fun whenTailscaleIsRunningTheLineSaysAlsoOnTailscaleAndTheGuideSaysSo() {
        show(FakeController(state(network = NetworkUi(true, "100.101.102.103"))))
        rule.onNodeWithText("Also on Tailscale").assertExists()
        rule.onNodeWithTag(DevicesTags.TAILSCALE_HOW).assertDoesNotExist()
        tap(DevicesTags.HOW)
        rule.onNodeWithText("Tailscale is on: 100.101.102.103", substring = true).assertExists()
    }

    @Test fun everySectionSharesTheSameLeftAndRightMargins() {
        val c = FakeController(state(paired = listOf(laptop(), oldTablet), offers = listOf(OfferUi("dev1", "Work laptop", "libX", "Shared films", true)), discovered = found(2)))
        show(c, widthDp = 360)
        val sections = listOf(DevicesTags.STATUS, DevicesTags.NEARBY, DevicesTags.paired("dev1"), DevicesTags.ADVANCED)
        val bounds = sections.map { rule.onNodeWithTag(it).performScrollTo().getUnclippedBoundsInRoot() }
        // the paired row is the clickable inside its card; the card and every other section start and end at the same 16 dp margins
        val card = rule.onNodeWithTag(DevicesTags.YOURS).performScrollTo().getUnclippedBoundsInRoot()
        for ((tag, b) in sections.zip(bounds)) {
            if (tag == DevicesTags.paired("dev1")) continue
            assertEquals("$tag left", 16f, b.left.value, 0.6f)
            assertEquals("$tag right", 344f, b.right.value, 0.6f)
        }
        assertEquals("your devices left", 16f, card.left.value, 0.6f)
        assertEquals("your devices right", 344f, card.right.value, 0.6f)
    }

    @Test fun deviceRowsAreTheSameHeightWhateverTheyAreSaying() {
        val c = FakeController(state(paired = listOf(laptop(), oldTablet, laptop(online = false, unreachableSince = System.currentTimeMillis() - 90_000).let { PairedDeviceUi("dev3", it.fingerprint, "A device with a rather long name that goes on and on", "android", 1, it.lastSeen, true, it.libraries, null, null, false, emptyList(), emptyList(), false) })))
        show(c, fontScale = 1.15f, widthDp = 360)
        val heights = listOf("dev1", "dev12", "dev3").map { rule.onNodeWithTag(DevicesTags.paired(it)).performScrollTo().getUnclippedBoundsInRoot().let { b -> b.bottom.value - b.top.value } }
        // rows of the same kind are the same height; a name too long for one line grows its row to two lines instead of being cut
        assertTrue("row heights $heights", Math.abs(heights[0] - heights[1]) < 1f && heights[2] >= heights[0])
    }

    @Test fun theStatusIsAChipAndTheExpandedSentenceDoesNotRepeatIt() {
        val synced = laptop().let { PairedDeviceUi(it.deviceId, it.fingerprint, it.name, it.platform, it.pairedAt, it.lastSeen, true, it.libraries, null, it.address, true, emptyList(), emptyList(), false, "lan", LastSyncUi(1_700_000_000_000, 3, 0)) }
        val empty = laptop().let { PairedDeviceUi("dev9", it.fingerprint, "New phone", "android", 1, null, true, emptyList(), null, null, false, emptyList(), emptyList(), false) }
        assertEquals("Up to date", statusWord(synced))
        assertTrue(statusSentence(synced), statusSentence(synced).startsWith("3 changes synced"))
        assertEquals("Connected", statusWord(empty))
        assertEquals("Nothing shared yet", statusSentence(empty))
        for (p in listOf(synced, empty, laptop(), oldTablet)) {
            val word = statusWord(p)
            assertFalse("${statusSentence(p)} repeats $word", statusSentence(p).contains(word, ignoreCase = true))
        }
        show(FakeController(state(paired = listOf(synced))))
        rule.onNodeWithTag(DevicesTags.pill("dev1"), useUnmergedTree = true).assertTextContains("Up to date")
        tap(DevicesTags.paired("dev1"))
        rule.onNodeWithTag(DevicesTags.SYNC).performScrollTo().assertTextContains("3 changes synced", substring = true)
    }

    @Test fun permissionsOnAreFilledWithACheckAndOffAreOutlinedUnderTheirLabel() {
        val c = FakeController(state(paired = listOf(syncedLaptop().let { PairedDeviceUi(it.deviceId, it.fingerprint, it.name, it.platform, it.pairedAt, it.lastSeen, true, listOf(PairedLibraryUi("lib1", "Family music", listOf("media.read"))), null, it.address, true, emptyList(), emptyList(), false, "lan", it.lastSync) })))
        show(c)
        tap(DevicesTags.paired("dev1"))
        rule.onNodeWithTag(DevicesTags.access("dev1", "media.read")).performScrollTo().assertIsSelected()
        rule.onNodeWithTag(DevicesTags.access("dev1", "copy.mutate")).assertIsNotSelected()
        val label = rule.onNodeWithText("Allowed for Work laptop").getUnclippedBoundsInRoot()
        val hint = rule.onNodeWithText("Tap to allow or stop each one.").getUnclippedBoundsInRoot()
        val chip = rule.onNodeWithTag(DevicesTags.access("dev1", "media.read")).getUnclippedBoundsInRoot()
        assertTrue("label, then hint, then chips", label.bottom <= hint.top + 1f.dp && hint.bottom <= chip.top + 1f.dp)
        rule.onNodeWithText("Add devices to the library").assertExists()
    }

    @Test fun theFingerprintAndTheCodesAreNotInAMonospaceFont() {
        // the one style the page uses for fingerprints and codes: the normal font with tabular digits (some phones draw Monospace as handwriting)
        assertEquals(null, TabularDigits.fontFamily)
        assertEquals("tnum", TabularDigits.fontFeatureSettings)
        val fingerprint = laptop().fingerprint
        show(FakeController(state(paired = listOf(laptop()))))
        tap(DevicesTags.paired("dev1"))
        val node = rule.onNodeWithText("Fingerprint $fingerprint", substring = true).performScrollTo().fetchSemanticsNode()
        val layouts = ArrayList<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        val family = layouts.single().layoutInput.style.fontFamily
        assertTrue("the fingerprint font is $family", family == null || family != androidx.compose.ui.text.font.FontFamily.Monospace)
    }

    // ---- connecting to a found device -----------------------------------------------------------------------------------------

    private fun asking(stage: String, sas: String? = null, error: String? = null, name: String = "Kitchen tablet") =
        PairingUi("w1", "sas", "asking", stage, name, "1111 2222 3333 4444 5555", sas, error, System.currentTimeMillis() + 100_000)

    @Test fun connectShowsTheCodeThenCodesMatchThenWaitsWithOnlyCancel() {
        val c = FakeController(state(discovered = found(2)))
        show(c)
        tap(DevicesTags.nearbyPair("hint0100000000000"))
        rule.waitUntil(5_000) { c.calls.contains("ask:192.168.1.31") }
        c.flow.value = state(discovered = found(2), pairing = asking("connecting", name = "Device 1"))
        rule.waitUntil(5_000) { count("Connecting to Device 1…") > 0 }
        c.flow.value = state(discovered = found(2), pairing = asking("comparing", "123456", name = "Device 1"))
        rule.waitUntil(5_000) { count("Check that both devices show this code, then tap Codes match on both.") > 0 }
        rule.onNodeWithText("123 456").assertIsDisplayed()
        // the asking side keeps the Codes match button: the numbers method is confirmed on both devices
        val match = rule.onNodeWithTag(DevicesTags.SAS_MATCH).assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        val differ = rule.onNodeWithTag(DevicesTags.SAS_DIFFER).assertHeightIsAtLeast(48.dp).getUnclippedBoundsInRoot()
        assertTrue("stacked, Codes match on top", match.bottom <= differ.top)
        assertEquals("same width", match.right.value - match.left.value, differ.right.value - differ.left.value, 1f)
        assertEquals("same left edge", match.left.value, differ.left.value, 1f)
        rule.onNodeWithTag(DevicesTags.SAS_MATCH).performClick()
        rule.waitUntil(5_000) { c.calls.contains("decide:w1:true") }
        // the service now reports the waiting stage with the code still known
        c.flow.value = state(discovered = found(2), pairing = asking("waiting", "123456", name = "Device 1"))
        rule.waitUntil(5_000) { count("On Device 1, tap Codes match to finish.") > 0 }
        rule.onNodeWithText("Waiting for Device 1…").assertIsDisplayed()
        rule.onNodeWithText("123 456").assertIsDisplayed()
        rule.onNodeWithTag(DevicesTags.SAS_MATCH).assertDoesNotExist()
        rule.onNodeWithTag(DevicesTags.SAS_DIFFER).assertHeightIsAtLeast(48.dp).assertTextContains("Cancel")
        // the other device confirmed: this side is paired
        c.flow.value = state(discovered = found(2), pairing = asking("done", name = "Device 1"))
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(DevicesTags.PAIRING_DONE).fetchSemanticsNodes().isNotEmpty() || count("Device 1 is connected") > 0 }
    }

    @Test fun theAskingSideSaysInOneSentenceWhenTheOtherPersonDeclined() {
        val c = FakeController(state(pairing = asking("failed", error = "Device 1 did not confirm, so nothing was paired.", name = "Device 1")))
        show(c)
        rule.onNodeWithText("Device 1 did not confirm, so nothing was paired.").assertIsDisplayed()
        rule.onNodeWithTag(DevicesTags.PAIRING_RESULT_OK).assertHeightIsAtLeast(48.dp).performClick()
        rule.waitUntil(5_000) { c.calls.contains("dismissPairing") }
    }

    @Test fun aQrOrTypedCodePairingStillSaysWaitingForTheOtherDeviceToAccept() {
        val c = FakeController(state(pairing = asking("waiting", name = "Device 1")))
        show(c)
        rule.onNodeWithText("Waiting for Device 1 to accept…").assertIsDisplayed()
    }

    @Test fun cancellingTheCodeAnswersNo() {
        val c = FakeController(state(pairing = asking("comparing", "123456")))
        show(c)
        rule.onNodeWithTag(DevicesTags.SAS_DIFFER).performClick()
        rule.waitUntil(5_000) { c.calls.contains("decide:w1:false") }
    }

    @Test fun waitingForTheOtherDeviceNoLongerTellsWhereToOpenAddADeviceAndCanBeCancelled() {
        val c = FakeController(state(pairingUntil = System.currentTimeMillis() + 100_000, pairing = asking("waitingForPeer", name = "Work PC")))
        show(c)
        rule.onNodeWithTag(DevicesTags.PAIRING_WAITING).assertIsDisplayed()
        rule.onNodeWithText("Waiting for Work PC to accept…").assertExists()
        rule.onNodeWithText("Open Settings", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Add a device on", substring = true).assertDoesNotExist()
        rule.onNodeWithTag(DevicesTags.PAIRING_CANCEL).assertHeightIsAtLeast(48.dp).performClick()
        rule.waitUntil(5_000) { c.calls.contains("dismissPairing") }
    }

    @Test fun aFailedPairingSaysWhyAndCanBeDismissed() {
        val c = FakeController(state(pairing = asking("failed", error = "You said the codes do not match, so nothing was connected.", name = "Work laptop")))
        show(c)
        rule.onNodeWithTag(DevicesTags.PAIRING_ERROR).assertExists()
        rule.onNodeWithText("You said the codes do not match", substring = true).assertExists()
        rule.onNodeWithTag(DevicesTags.PAIRING_RESULT_OK).assertHeightIsAtLeast(48.dp).performClick()
        rule.waitUntil(5_000) { c.calls.contains("dismissPairing") }
    }

    @Test fun anAnsweringRequestIsNotShownOnThePageItIsTheAppLevelDialog() {
        val answering = PairingUi("p2", "sas", "answering", "comparing", "Work laptop", "1111 2222 3333 4444 5555", "123456", null, System.currentTimeMillis() + 100_000)
        show(FakeController(state(pairing = answering)))
        rule.onNodeWithTag(DevicesTags.SAS).assertDoesNotExist()
    }

    // ---- Your devices ----------------------------------------------------------------------------------------------------------

    @Test fun statusWordsAndPillsFollowTheDevice() {
        assertEquals("Offline", statusWord(laptop(online = false)))
        assertEquals("Syncing…", statusWord(laptop()))
        assertEquals("Up to date", statusWord(laptop().let { PairedDeviceUi(it.deviceId, it.fingerprint, it.name, it.platform, it.pairedAt, it.lastSeen, true, it.libraries, null, it.address, true, emptyList(), emptyList(), false, "lan", LastSyncUi(1, 0, 0)) }))
        assertEquals("Sync", pillLabel(laptop()))
        assertEquals("Retry", pillLabel(laptop(online = false)))
        assertTrue(reachLine(laptop(online = false)).startsWith("Windows PC · last seen"))
        assertEquals("Android phone · not seen yet", reachLine(oldTablet))
        assertEquals("Windows PC · Home network", reachLine(laptop().let { PairedDeviceUi(it.deviceId, it.fingerprint, it.name, it.platform, it.pairedAt, it.lastSeen, true, it.libraries, null, it.address, true, emptyList(), emptyList(), false, "lan") }))
    }

    @Test fun eachPairedDeviceIsACollapsedRowWithAStatusChipAndExpandsOnTap() {
        val c = FakeController(state(paired = listOf(laptop(), oldTablet)))
        show(c)
        // collapsed: name, how reached, status and the pill; no details
        rule.onNodeWithTag(DevicesTags.paired("dev1")).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("Work laptop").assertExists()
        rule.onNodeWithTag(DevicesTags.pill("dev1"), useUnmergedTree = true).assertTextContains("Syncing…")
        rule.onNodeWithTag(DevicesTags.pill("dev12"), useUnmergedTree = true).assertTextContains("Offline")
        rule.onNodeWithTag(DevicesTags.chevron("dev1")).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(DevicesTags.MOBILE_FILES).assertDoesNotExist()
        rule.onNodeWithTag(DevicesTags.unpair("dev1")).assertDoesNotExist()
        // tapping the row opens it, several can be open, and it closes again
        tap(DevicesTags.paired("dev1"))
        rule.onNodeWithTag(DevicesTags.MOBILE_FILES).performScrollTo().assertExists()
        rule.onNodeWithTag(DevicesTags.syncNow("dev1")).performScrollTo().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(DevicesTags.unpair("dev1")).assertHeightIsAtLeast(48.dp)
        tap(DevicesTags.chevron("dev12"))
        rule.onNodeWithTag(DevicesTags.unpair("dev12")).performScrollTo().assertExists()
        rule.onNodeWithTag(DevicesTags.unpair("dev1")).assertExists()
        tap(DevicesTags.paired("dev1"))
        rule.onNodeWithTag(DevicesTags.unpair("dev1")).assertDoesNotExist()
        rule.onNodeWithTag(DevicesTags.unpair("dev12")).assertExists()
    }

    @Test fun anExpandedCardIsCompactStatusSentencePermissionChipsAndUnpair() {
        val c = FakeController(state(paired = listOf(laptop(), oldTablet)))
        show(c)
        tap(DevicesTags.paired("dev1"))
        // the one button sits on the status sentence's line
        rule.onNodeWithTag(DevicesTags.SYNC).performScrollTo().assertTextContains("Catching up with the library…")
        rule.onNodeWithTag(DevicesTags.syncNow("dev1")).assertHeightIsAtLeast(48.dp).assertTextContains("Sync now")
        // permissions are small toggles led by "<name> can"
        rule.onNodeWithText("Allowed for Work laptop").assertExists()
        for (label in listOf("See files", "Play from here", "Keep a copy here", "Download here", "Add devices to the library")) rule.onNodeWithText(label).assertExists()
        rule.onNodeWithText("Reads the library", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Unpair").assertExists()
        rule.onNodeWithTag(DevicesTags.unpair("dev1")).assertHeightIsAtLeast(48.dp)
        // while it is still syncing there is nothing to press
        rule.onNodeWithTag(DevicesTags.syncNow("dev1")).assertIsNotEnabled()
        rule.onNodeWithText("Tap to allow or stop each one.").assertExists()
        // an offline device offers Retry and says it reconnects by itself
        tap(DevicesTags.paired("dev12"))
        rule.onNodeWithTag(DevicesTags.syncNow("dev12")).performScrollTo().assertTextContains("Retry")
        rule.onNodeWithText("Keepfin reconnects by itself", substring = true).assertExists()
    }
    private fun syncedLaptop() = laptop().let { PairedDeviceUi(it.deviceId, it.fingerprint, it.name, it.platform, it.pairedAt, it.lastSeen, true, it.libraries, null, it.address, true, emptyList(), emptyList(), false, "lan", LastSyncUi(1_700_000_000_000, 0, 0)) }

    @Test fun syncNowSyncsAnOnlineDeviceAndRetriesAnOfflineOne() {
        val c = FakeController(state(paired = listOf(syncedLaptop(), oldTablet)))
        show(c)
        tap(DevicesTags.paired("dev1"))
        tap(DevicesTags.syncNow("dev1"))
        rule.waitUntil(5_000) { c.calls.contains("syncNow:dev1") }
        tap(DevicesTags.paired("dev12"))
        tap(DevicesTags.syncNow("dev12"))
        rule.waitUntil(5_000) { c.calls.contains("syncNow:dev12") }
    }

    @Test fun yourDevicesShowTheRouteEachOneIsReachedOn() {
        val viaTailscale = laptop().let { PairedDeviceUi(it.deviceId, it.fingerprint, "Tail PC", it.platform, it.pairedAt, it.lastSeen, true, it.libraries, null, "100.101.102.103", false, emptyList(), emptyList(), false, route = "tailscale") }
        val home = laptop().let { PairedDeviceUi(it.deviceId + "b", it.fingerprint, "Home PC", it.platform, it.pairedAt, it.lastSeen, true, it.libraries, null, "192.168.1.20", false, emptyList(), emptyList(), false, route = "lan") }
        show(FakeController(state(paired = listOf(viaTailscale, home, oldTablet))))
        assertTrue(count("Tailscale") >= 1)
        rule.onNodeWithTag(DevicesTags.paired("dev1")).assertTextContains("Tailscale", substring = true)
        rule.onNodeWithTag(DevicesTags.paired("dev1b")).assertTextContains("Home network", substring = true)
        rule.onNodeWithTag(DevicesTags.paired("dev12")).assertTextContains("Offline", substring = true)
        assertEquals("Home network", routeLabel("lan"))
        assertEquals("Tailscale", routeLabel("tailscale"))
        assertEquals("Not reachable", routeLabel("none"))
    }

    @Test fun anExpandedCardKeepsTheSyncLineAndWhatIsShared() {
        assertEquals("Reads the library · Sends changes", accessSummary(laptop()))
        assertEquals("No shared library yet", accessSummary(oldTablet))
        val c = FakeController(state(paired = listOf(laptop())))
        show(c)
        tap(DevicesTags.paired("dev1"))
        rule.onNodeWithTag(DevicesTags.SYNC).performScrollTo().assertExists()
        rule.onNodeWithText("Fingerprint 1111 2222 3333 4444 5555", substring = true).assertExists()
        tap(DevicesTags.access("dev1", "media.read"))
        rule.waitUntil(5_000) { c.calls.contains("access:dev1") }
        assertTrue("media.read was added and the existing ones kept", c.access.containsAll(listOf(NetAction.MetadataRead, NetAction.MetadataWrite, NetAction.MediaRead)))
        c.flow.value = state(paired = listOf(PairedDeviceUi("dev1", "1111 2222 3333 4444 5555", "Work laptop", "windows", 1, null, true, listOf(PairedLibraryUi("lib1", "Family music", c.access.map { it.wire })), null, "192.168.1.20", true, emptyList(), emptyList(), false)))
        tap(DevicesTags.access("dev1", "media.read"))
        rule.waitUntil(5_000) { c.access.none { it == NetAction.MediaRead } }
        assertTrue("the other grants stay", c.access.containsAll(listOf(NetAction.MetadataRead, NetAction.MetadataWrite)))
        tap(DevicesTags.MOBILE_FILES)
        rule.waitUntil(5_000) { c.calls.contains("mobile:true") }
    }

    @Test fun anOfflineDeviceSaysWhenItWasSeenAndSinceWhen() {
        val c = FakeController(state(paired = listOf(laptop(online = false, unreachableSince = System.currentTimeMillis() - 3_600_000))))
        show(c)
        rule.onNodeWithTag(DevicesTags.paired("dev1")).assertTextContains("last seen", substring = true)
        tap(DevicesTags.paired("dev1"))
        rule.onNodeWithText("Not reachable since", substring = true).assertExists()
    }

    @Test fun unpairingExplainsWhatItMeansAndCopiesStayBeforeItHappens() {
        val c = FakeController(state(paired = listOf(laptop())))
        show(c)
        tap(DevicesTags.paired("dev1"))
        tap(DevicesTags.unpair("dev1"))
        rule.onNodeWithText("Unpair Work laptop?").assertExists()
        rule.onNodeWithText("Copies already on either device stay.", substring = true).assertExists()
        rule.onNodeWithText("revoked from now on", substring = true).assertExists()
        rule.onNodeWithText("Anything it already copied stays with it.", substring = true).assertExists()
        rule.onNodeWithText("Remove", substring = true).assertDoesNotExist()
        rule.onNodeWithTag(DevicesTags.REMOVE_CONFIRM).performClick()
        rule.waitUntil(5_000) { c.calls.contains("remove:dev1") }
    }

    @Test fun aLibraryThisPhoneDoesNotAdministerIsFlaggedInTheUnpairNote() {
        val c = FakeController(state(paired = listOf(laptop()), libraries = listOf(LibraryInfoUi("lib1", "Family music", signed = true, admin = false))))
        show(c)
        tap(DevicesTags.paired("dev1"))
        tap(DevicesTags.unpair("dev1"))
        rule.onNodeWithText("does not administer every shared library", substring = true).assertExists()
    }

    @Test fun theQrCodeIsDrawn() {
        val c = FakeController(state(pairingUntil = System.currentTimeMillis() + 100_000, qr = QrUi("keepfin://pair?h=192.168.1.5&p=47215&k=00&t=00", System.currentTimeMillis() + 100_000)))
        show(c)
        openAdd()
        rule.onNodeWithTag(DevicesTags.QR).performScrollTo().assertIsDisplayed()
    }

    @Test fun showMyCodeShowsTheTypedCodeUnderTheQr() {
        val soon = System.currentTimeMillis() + 100_000
        val c = FakeController(state(pairingUntil = soon, qr = QrUi("keepfin://pair?h=192.168.1.5&p=47215&k=00&t=00", soon, "K7QPM-4X2TA")))
        show(c)
        tap(DevicesTags.SHOW_QR)
        rule.waitUntil(5_000) { c.calls.contains("showQr") }
        rule.onNodeWithTag(DevicesTags.QR).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("No camera? Type this code on the other device").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(DevicesTags.CODE).performScrollTo().assertIsDisplayed().assertTextContains("K7QPM-4X2TA")
    }

    @Test fun aQrWithoutACodeShowsNoCodeLine() {
        val soon = System.currentTimeMillis() + 100_000
        val c = FakeController(state(pairingUntil = soon, qr = QrUi("keepfin://pair?h=192.168.1.5&p=47215&k=00&t=00", soon)))
        show(c)
        openAdd()
        rule.onNodeWithTag(DevicesTags.QR).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(DevicesTags.CODE).assertDoesNotExist()
    }

    @Test fun theTypeACodeFieldSendsWhatWasTypedAndShowsTheAnswer() {
        val c = FakeController(state())
        c.askError = "No device showing this code was found. Check the code, and that both devices are on the same network or Tailscale."
        show(c)
        rule.onNodeWithTag(DevicesTags.TYPE_CODE_GO).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag(DevicesTags.TYPE_CODE).performScrollTo().performTextInput("k7qpm 4x2ta")
        tap(DevicesTags.TYPE_CODE_GO)
        rule.waitUntil(5_000) { c.calls.contains("ask:k7qpm 4x2ta") }
        rule.waitUntil(5_000) { rule.onAllNodesWithText("No device showing this code was found.", substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(DevicesTags.TYPE_CODE_GO).assertHeightIsAtLeast(48.dp)
    }

    @Test fun aMistypedCodeIsExplainedAndNeverSent() {
        val c = FakeController(state())
        show(c)
        rule.onNodeWithTag(DevicesTags.TYPE_CODE).performScrollTo().performTextInput("7Q4MX-2XKD")
        tap(DevicesTags.TYPE_CODE_GO)
        rule.waitUntil(5_000) { rule.onAllNodesWithText("That code is too short. It has 10 characters, like 7Q4MX-2XKDP.").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag(DevicesTags.TYPE_CODE).performTextInput("PU")
        tap(DevicesTags.TYPE_CODE_GO)
        rule.waitUntil(5_000) { rule.onAllNodesWithText("That is not a Keepfin code", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("nothing reached the engine", c.calls.none { it.startsWith("ask:") })
    }

    @Test fun theAddScreenFieldTakesACodeOrAnAddress() {
        val soon = System.currentTimeMillis() + 100_000
        val c = FakeController(state(pairingUntil = soon))
        show(c)
        openAdd()
        rule.onNodeWithTag(DevicesTags.ADDRESS).performScrollTo().performTextInput("K7QPM-4X2TA")
        tap(DevicesTags.PAIR)
        rule.waitUntil(5_000) { c.calls.contains("ask:K7QPM-4X2TA") }
        rule.onAllNodesWithText("Enter the code or address shown on your other device", substring = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }
    @Test fun theSwitchAtTheTopTurnsDevicesOffAndOn() {
        val c = FakeController(state())
        show(c)
        tap(DevicesTags.SWITCH)
        rule.waitUntil(5_000) { c.calls.contains("disable") }
    }

    @Test fun anOffPhoneSaysSoAndTheSwitchTurnsItOn() {
        val off = FakeController(state(enabled = false))
        show(off)
        rule.onNodeWithText("Off. Other devices cannot find this phone.").assertExists()
        tap(DevicesTags.SWITCH)
        rule.waitUntil(5_000) { off.calls.contains("enable") }
    }

    @Test fun theVisibilityLineNamesWhereThisPhoneCanBeFound() {
        val both = state(network = NetworkUi(true, "100.101.102.103"))
        assertEquals("Visible on Home network", visibilityLine(both)) // Tailscale on top has its own line under the switch
        assertEquals("Visible on Tailscale", visibilityLine(state(network = NetworkUi(false, "100.101.102.103"))))
        assertEquals("Visible on Home network", visibilityLine(state()))
        assertEquals("Not on a network", visibilityLine(state(network = NetworkUi(false, null))))
        assertEquals("Off. Other devices cannot find this phone.", visibilityLine(state(enabled = false)))
        show(FakeController(both))
        rule.onNodeWithText("Visible on Home network").assertExists()
        rule.onNodeWithText("Also on Tailscale").assertExists()
    }

    @Test fun theProblemCardAppearsOnlyWithNeitherWifiNorTailscale() {
        show(FakeController(state(network = NetworkUi(false, null))))
        rule.onNodeWithTag(DevicesTags.PROBLEM).assertIsDisplayed()
        rule.onNodeWithText("No Wi-Fi and no Tailscale").assertExists()
        fun top(tag: String) = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().top.value
        assertTrue("the problem card sits between this device and Your devices", top(DevicesTags.STATUS) < top(DevicesTags.PROBLEM) && top(DevicesTags.PROBLEM) < top(DevicesTags.NEARBY))
    }

    @Test fun tailscaleAloneIsNotAProblem() {
        show(FakeController(state(network = NetworkUi(false, "100.101.102.103"))))
        rule.onNodeWithTag(DevicesTags.PROBLEM).assertDoesNotExist()
    }

    @Test fun lookingIsNotShownOnceTheSearchWasStopped() {
        show(FakeController(state(scanning = false)))
        tap(DevicesTags.SEARCH_STOP)
        rule.onNodeWithTag(DevicesTags.NEARBY_LOOKING).assertDoesNotExist()
    }

    @Test fun addByAddressOrCodeOpensTheAddScreenAndKeepsThePairingWindowBehaviour() {
        val c = FakeController(state(pairingUntil = System.currentTimeMillis() + 100_000))
        show(c)
        openAdd()
        rule.waitUntil(5_000) { c.calls.contains("openPairing") }
        rule.onNodeWithTag(DevicesTags.ADD_SCREEN).assertIsDisplayed()
        rule.onNodeWithTag(DevicesTags.ADDRESS).assertExists()
    }

    @Test fun thePageLooksForDevicesWhileItIsOpen() {
        val c = FakeController(state())
        show(c)
        rule.waitUntil(5_000) { c.calls.contains("startLooking") }
    }

    @Test fun nothingIsLookedForWhileDevicesAreOff() {
        val off = FakeController(state(enabled = false))
        show(off)
        rule.waitForIdle()
        Thread.sleep(300)
        assertFalse("devices are off: nothing to look for", off.calls.contains("startLooking"))
    }

    @Test fun reachableWhenKeepfinIsClosedLivesInAdvanced() {
        val c = FakeController(state())
        show(c)
        rule.onNodeWithTag(DevicesTags.KEEP_REACHABLE).assertDoesNotExist()
        rule.onNodeWithText("Advanced").performScrollTo().performClick()
        tap(DevicesTags.KEEP_REACHABLE)
        rule.waitUntil(5_000) { c.calls.contains("reachable:true") }
    }
    @Test fun advancedHoldsRenameFingerprintPortAndTurnOff() {
        val c = FakeController(state())
        show(c)
        rule.onNodeWithText("Advanced").performScrollTo().performClick()
        rule.onNodeWithTag(DevicesTags.THIS_DEVICE).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Listening on port 47215").assertExists()
        rule.onNodeWithText("Rename").performScrollTo().performClick()
        rule.onNodeWithText("Name of this phone").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        tap(DevicesTags.DISABLE)
        rule.waitUntil(5_000) { c.calls.contains("disable") }
    }

    @Test fun anOpenAdvancedAnchorExpandsItForSearchResults() {
        val c = FakeController(state())
        rule.setContent { KeepfinTheme(ThemeMode.LIGHT) { Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { DevicesScreen(c, openAdvanced = true) } } }
        rule.onNodeWithTag(DevicesTags.THIS_DEVICE).assertExists()
    }

    @Test fun aPortInUseIsExplainedInWords() {
        val c = FakeController(state(portError = "Another program is using port 47215. Close it, then turn devices off and on again."))
        show(c)
        rule.onNodeWithText("Another program is using port 47215", substring = true).assertExists()
        rule.onNodeWithTag(DevicesTags.THIS_DEVICE).assertDoesNotExist()
    }

    @Test fun thisPhoneAndAnEmptyListAreShown() {
        val c = FakeController(state())
        show(c)
        rule.onNodeWithText("Advanced").performScrollTo().performClick()
        assertTrue("the name shows in Advanced", count("Pixel") >= 1)
        rule.onNodeWithText("Fingerprint ab12 cd34 ef56 7890 1234", substring = true).assertExists()
        rule.onNodeWithText("No devices yet", substring = true).assertExists()
    }

    @Test fun addingByAddressRunsThePairingAndShowsMistakesInWords() {
        val c = FakeController(state(pairingUntil = System.currentTimeMillis() + 100_000))
        show(c)
        openAdd()
        rule.waitUntil(5_000) { c.calls.contains("openPairing") }
        rule.onNodeWithTag(DevicesTags.ADD_SCREEN).assertIsDisplayed()
        tap(DevicesTags.ADDRESS)
        rule.onNodeWithTag(DevicesTags.ADDRESS).performTextInput("192.168.1.20")
        tap(DevicesTags.PAIR)
        rule.waitUntil(5_000) { c.calls.contains("ask:192.168.1.20") }
        c.askError = "That is not an address."
        tap(DevicesTags.PAIR)
        rule.waitUntil(5_000) { count("That is not an address.") > 0 }
    }

    private fun count(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().size
    @Test fun aLibraryOfferAndTheJoinProgressAreShown() {
        val offer = OfferUi("dev1", "Work laptop", "libX", "Shared films", true)
        val c = FakeController(state(paired = listOf(laptop()), offers = listOf(offer)))
        show(c)
        rule.onNodeWithText("Shared films on Work laptop").assertExists()
        rule.onNodeWithTag(DevicesTags.GET_LIBRARY).performScrollTo().assertIsDisplayed()
        c.flow.value = state(paired = listOf(laptop()), join = JoinUi("dev1", "Work laptop", "libX", "Shared films", "pulling", 12, null, true))
        rule.waitUntil(5_000) { count("Taking in the library (12 parts)") > 0 }
        c.flow.value = state(paired = listOf(laptop()), join = JoinUi("dev1", "Work laptop", "libX", "Shared films", "failed", 12, "That device is not connected right now.", true))
        rule.waitUntil(5_000) { count("Choose the same folder again to continue") > 0 }
    }

    // "Asked of this phone" / "Sent to other devices" live on the Downloads screen now; the section itself is checked here, and in
    // DeviceJobsOnDownloadsUiTest inside the real screen.
    @Test fun aLargeDownloadAskedOfThisPhoneWaitsForYou() {
        val row = IncomingJobRow("job1", "Work laptop", "A long documentary", "consent", 0.0, EngineJobPort.Consent("size", 3L * 1024 * 1024 * 1024), null)
        val c = FakeController(state(incoming = listOf(row)))
        rule.setContent { KeepfinTheme(ThemeMode.LIGHT) { Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { DeviceJobsSection(c.flow.value, c) } } }
        rule.onNodeWithText("Asked of this phone").assertExists()
        rule.onNodeWithText("A long documentary").assertExists()
        rule.onNodeWithText("This is large", substring = true).assertExists()
        tap(DevicesTags.ALLOW_JOB)
        rule.waitUntil(5_000) { c.calls.contains("allow:job1") }
        tap(DevicesTags.DECLINE_JOB)
        rule.waitUntil(5_000) { c.calls.contains("decline:job1") }
    }

    @Test fun downloadsSentToOtherDevicesShowTheirProgress() {
        val row = RemoteJobRowUi("r1", "dev1", "Work laptop", "https://example.com/v", "downloading", 42.0, "Some video", "720p", null, null, 1)
        val done = RemoteJobRowUi("r2", "dev1", "Work laptop", "https://example.com/w", "completed", 100.0, "Another", "mp3", null, null, 2)
        val c = FakeController(state(remote = listOf(row, done)))
        rule.setContent { KeepfinTheme(ThemeMode.LIGHT) { Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { DeviceJobsSection(c.flow.value, c) } } }
        rule.onNodeWithText("Sent to other devices").assertExists()
        rule.onNodeWithText("On Work laptop · Downloading · 42%").assertExists()
        rule.onNodeWithText("Dismiss").performScrollTo().performClick()
        rule.waitUntil(5_000) { c.calls.contains("dismissJob:r2") }
    }

    @Test fun theDevicesPageNoLongerListsDeviceJobs() {
        val row = IncomingJobRow("job1", "Work laptop", "A long documentary", "consent", 0.0, EngineJobPort.Consent("size", 3L * 1024 * 1024 * 1024), null)
        val done = RemoteJobRowUi("r2", "dev1", "Work laptop", "https://example.com/w", "completed", 100.0, "Another", "mp3", null, null, 2)
        show(FakeController(state(paired = listOf(laptop()), incoming = listOf(row), remote = listOf(done))))
        rule.onNodeWithText("Asked of this phone").assertDoesNotExist()
        rule.onNodeWithText("Sent to other devices").assertDoesNotExist()
        rule.onNodeWithText("A long documentary").assertDoesNotExist()
    }

    @Test fun theNewPageFitsAtOnePointThreeFontScaleOnANarrowPhone() {
        val c = FakeController(state(paired = listOf(laptop(), oldTablet), discovered = found(3), scanning = true, network = NetworkUi(true, "100.101.102.103")))
        show(c, fontScale = 1.3f, widthDp = 360)
        openHelp()
        for (tag in listOf(DevicesTags.HOW, DevicesTags.SWITCH, DevicesTags.SCAN, DevicesTags.SHOW_QR, DevicesTags.nearbyPair("hint0100000000000"), DevicesTags.paired("dev1"), DevicesTags.chevron("dev1"), DevicesTags.SEARCH_STOP, DevicesTags.NOT_SEEING, DevicesTags.ADD, DevicesTags.TYPE_CODE_GO)) {
            rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            val b = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$tag stays inside the screen width (${b.right})", b.left.value >= 0f && b.right.value <= 360f)
        }
        for (tag in listOf(DevicesTags.STATUS, DevicesTags.YOURS, DevicesTags.NEARBY)) {
            val b = rule.onNodeWithTag(tag).performScrollTo().getUnclippedBoundsInRoot()
            assertTrue("$tag stays inside the screen width (${b.right})", b.left.value >= 0f && b.right.value <= 360f)
        }
        rule.onNodeWithText("Also on Tailscale").performScrollTo().assertIsDisplayed()
    }

    @Test fun circleNodesDoNotOverlapEachOtherOnANarrowPhone() {
        show(FakeController(state(discovered = found(5))), widthDp = 320)
        val boxes = (1..5).map { rule.onNodeWithTag(DevicesTags.nearbyPair("hint%02d00000000000".format(it))).performScrollTo().getUnclippedBoundsInRoot() }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) {
            val a = boxes[i]; val b = boxes[j]
            val apart = a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top
            assertTrue("devices ${i + 1} and ${j + 1} overlap: $a vs $b", apart)
            assertTrue("device ${i + 1} stays inside the screen", a.left.value >= 0f && a.right.value <= 320f)
        }
    }

    @Test fun everyActionIsAtLeastFortyEightDpHighEvenAtTwiceTheFontSizeOnANarrowPhone() {
        val c = FakeController(state(paired = listOf(laptop()), discovered = found(2), pairingUntil = System.currentTimeMillis() + 100_000))
        show(c, fontScale = 2f, widthDp = 320)
        openHelp()
        for (tag in listOf(DevicesTags.ADD, DevicesTags.HOW, DevicesTags.SWITCH, DevicesTags.SCAN, DevicesTags.SHOW_QR, DevicesTags.paired("dev1"), DevicesTags.chevron("dev1"), DevicesTags.VIEW_LIST, DevicesTags.nearbyPair("hint0100000000000"), DevicesTags.SEARCH_STOP, DevicesTags.NOT_SEEING)) {
            rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        tap(DevicesTags.ADD)
        for (tag in listOf(DevicesTags.PAIR, DevicesTags.SCAN, DevicesTags.SHOW_QR, DevicesTags.CLOSE_ADD, DevicesTags.BACK)) {
            rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
    }

    @Test fun theCodeDialogFitsAtTwiceTheFontSizeOnANarrowPhone() {
        val c = FakeController(state(pairing = asking("comparing", "123456", name = "Kitchen tablet")))
        show(c, fontScale = 2f, widthDp = 320)
        rule.onNodeWithTag(DevicesTags.SAS).assertExists()
        rule.onNodeWithTag(DevicesTags.SAS_MATCH).assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(DevicesTags.SAS_DIFFER).assertHeightIsAtLeast(48.dp)
    }

    @Test fun addADeviceClosesThePairingWindowWhenYouLeaveIt() {
        val c = FakeController(state(pairingUntil = System.currentTimeMillis() + 100_000))
        show(c)
        openAdd()
        tap(DevicesTags.CLOSE_ADD)
        rule.waitUntil(5_000) { c.calls.contains("closePairing") }
        rule.onNodeWithTag(DevicesTags.STATUS).assertIsDisplayed()
        rule.onNodeWithTag(DevicesTags.ADD_SCREEN).assertDoesNotExist()
    }

    private fun shot(name: String, s: DevicesState, dark: Boolean, steps: () -> Unit = {}) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        show(FakeController(s), dark = dark, fontScale = 1.15f)
        steps()
        Thread.sleep(900)
        val file = "devices-$name-${if (dark) "dark" else "light"}"
        saveNode(file, rule.onAllNodes(isRoot()).onFirst())
        for (tag in listOf(DevicesTags.GUIDE, DevicesTags.PAIR_DIALOG)) {
            if (rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()) saveNode("$file-dialog", rule.onNodeWithTag(tag))
        }
        Thread.sleep(700)
    }

    private val open get() = System.currentTimeMillis() + 100_000
    private val numbers get() = PairingUi("p1", "sas", "asking", "comparing", "Work laptop", "1111 2222 3333 4444 5555", "123456", null, open)
    private val oldTablet get() = laptop(online = false, unreachableSince = System.currentTimeMillis() - 7_200_000).let {
        PairedDeviceUi(it.deviceId + "2", it.fingerprint, "Old tablet", "android", 1, null, false, emptyList(), it.unreachableSince, null, false, emptyList(), emptyList(), false)
    }
    private val qrState get() = state(pairingUntil = open, qr = QrUi("keepfin://pair?h=192.168.1.5&p=47215&k=3d5f1b0c2a4e6f8091a3b5c7d9e1f30415263748596a7b8c9dafbecfd0e1f203&t=00112233445566778899aabbccddeeff", open))
    private val listState get() = state(paired = listOf(laptop(), oldTablet), offers = listOf(OfferUi("dev1", "Work laptop", "libX", "Shared films", true)))

    private val nearbyState get() = state(
        paired = listOf(laptop(), oldTablet), scanning = true, network = NetworkUi(true, "100.101.102.103"),
        discovered = listOf(DiscoveredUi("abcdef0123456789", "Kitchen tablet", "192.168.1.31", 47215), DiscoveredUi("0123456789abcdef", "Desk PC", "100.101.102.103", 47215)),
    )
    private val emptyNearbyState get() = state(paired = listOf(laptop()), scanning = true)
    private val problemState get() = state(paired = listOf(oldTablet), network = NetworkUi(false, null))

    @Test fun shotHomeLight() = shot("home", nearbyState, false)
    @Test fun shotHomeDark() = shot("home", nearbyState, true)
    @Test fun shotNearbyLight() = shot("nearby", nearbyState, false) { rule.onNodeWithTag(DevicesTags.NEARBY).performScrollTo() }
    @Test fun shotNearbyDark() = shot("nearby", nearbyState, true) { rule.onNodeWithTag(DevicesTags.NEARBY).performScrollTo() }
    @Test fun shotNearbyEmptyLight() = shot("nearby-empty", emptyNearbyState, false) { rule.onNodeWithTag(DevicesTags.NEARBY).performScrollTo() }
    @Test fun shotNearbyEmptyDark() = shot("nearby-empty", emptyNearbyState, true) { rule.onNodeWithTag(DevicesTags.NEARBY).performScrollTo() }
    private fun shotBody(name: String, dark: Boolean, body: @androidx.compose.runtime.Composable () -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1.15f)) {
                KeepfinTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                    Box(Modifier.width(360.dp).background(MaterialTheme.colorScheme.background).androidx_padding16()) {
                        androidx.compose.material3.Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 6.dp) { body() }
                    }
                }
            }
        }
        rule.waitForIdle()
        saveNode("devices-$name-${if (dark) "dark" else "light"}", rule.onRoot())
    }
    private fun Modifier.androidx_padding16() = this.then(Modifier.padding(16.dp))
    @Test fun shotGuideLight() = shotBody("guide", false) { GuideBody("top", state(), {}) }
    @Test fun shotGuideDark() = shotBody("guide", true) { GuideBody("top", state(), {}) }
    @Test fun shotCircleLight() = shot("circle", nearbyState, false) { rule.onNodeWithTag(DevicesTags.NEARBY).performScrollTo() }
    @Test fun shotCircleDark() = shot("circle", nearbyState, true) { rule.onNodeWithTag(DevicesTags.NEARBY).performScrollTo() }
    private val askingWaiting get() = PairingUi("p1", "sas", "asking", "waiting", "Work laptop", "1111 2222 3333 4444 5555", "123456", null, open)
    private val askingDialog: @androidx.compose.runtime.Composable () -> Unit = {
        androidx.compose.foundation.layout.Column(Modifier.padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            androidx.compose.material3.Text("Connect to Work laptop", style = MaterialTheme.typography.headlineSmall)
            PairingContent(askingWaiting, { _, _ -> }, {}, {})
        }
    }
    @Test fun shotAskingWaitingLight() = shotBody("asking-waiting", false, askingDialog)
    @Test fun shotAskingWaitingDark() = shotBody("asking-waiting", true, askingDialog)
    @Test fun shotProblemLight() = shot("problem", problemState, false)
    @Test fun shotProblemDark() = shot("problem", problemState, true)
    @Test fun shotListLight() = shot("list", listState, false)
    @Test fun shotListDark() = shot("list", listState, true)
    @Test fun shotOffLight() = shot("off", state(enabled = false), false)
    @Test fun shotOffDark() = shot("off", state(enabled = false), true)
    @Test fun shotAddLight() = shot("add", state(pairingUntil = open, discovered = listOf(DiscoveredUi("abcdef0123456789", "Kitchen tablet", "192.168.1.31", 47215))), false) { openAdd() }
    @Test fun shotAddDark() = shot("add", state(pairingUntil = open, discovered = listOf(DiscoveredUi("abcdef0123456789", "Kitchen tablet", "192.168.1.31", 47215))), true) { openAdd() }
    @Test fun shotNumbersLight() = shot("numbers", state(pairing = numbers, pairingUntil = open), false)
    @Test fun shotNumbersDark() = shot("numbers", state(pairing = numbers, pairingUntil = open), true)
    @Test fun shotQrLight() = shot("qr", qrState, false) { openAdd() }
    @Test fun shotQrDark() = shot("qr", qrState, true) { openAdd() }
    @Test fun shotDetailsLight() = shot("details", listState, false) { tap(DevicesTags.paired("dev1")) }
    @Test fun shotDetailsDark() = shot("details", listState, true) { tap(DevicesTags.paired("dev1")) }
}
