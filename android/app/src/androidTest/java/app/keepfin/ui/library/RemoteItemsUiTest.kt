package app.keepfin.ui.library

import android.graphics.Bitmap
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.net.PeerPlayback
import app.keepfin.net.PlayChoice
import app.keepfin.ui.Destination
import app.keepfin.ui.Harness
import app.keepfin.ui.StudioEngine
import app.keepfin.ui.UiTags
import app.keepfin.ui.exists
import app.keepfin.ui.hasText
import app.keepfin.ui.preview.FakeLibrary.FakeSnapshot
import app.keepfin.ui.theme.ThemeMode
import app.keepfin.ui.waitForTag
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Items whose file is not on this phone: the "On <device>" hint, playing from a reachable device, and the plain message when it is away. */
@RunWith(AndroidJUnit4::class)
class RemoteItemsUiTest {
    @get:Rule val rule = createComposeRule()

    private val song = "content://media/external/audio/media/102"
    private val hashed = "sha256:" + "ab".repeat(32)

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null; PeerPlayback.forced = null }

    private fun musicWithARemoteSong(choice: PlayChoice, dark: Boolean? = null): Harness {
        PeerPlayback.forced = choice
        val h = Harness("library")
        if (dark != null) h.prefs.chooseTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        val lib = h.engine.fakeLibrary
        lib.setSnapshot(FakeSnapshot(
            items = lib.items.map { if (it.id == song) it.copy(uri = "", remote = true, contentId = hashed) else it },
            collections = lib.library.value.collections, scannedAt = System.currentTimeMillis(),
        ))
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.onNodeWithTag(UiTags.spaceCard("music")).performClick()
        rule.waitForTag(UiTags.libItem(song))
        return h
    }

    private fun hintText(): String = rule.onNodeWithTag(UiTags.libRemoteHint(song), useUnmergedTree = true).fetchSemanticsNode().config
        .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()

    @Test fun anItemOnAReachableDeviceShowsWhichDeviceAndPlaysWithoutAMessage() {
        val h = musicWithARemoteSong(PlayChoice.Peer("Hisham's PC"))
        rule.waitForTag(UiTags.libRemoteHint(song))
        assertEquals("On Hisham's PC", hintText())
        rule.onNodeWithTag(UiTags.libItem(song)).performClick()
        rule.waitForTag(UiTags.MINI_PLAYER)
        assertFalse("no refusal for a reachable device", rule.hasText("not reachable"))
        assertEquals(1, h.vm.player.ui.value.queue.count { it.remote })
    }

    @Test fun anItemOnAnUnreachableDeviceExplainsWhenItWasSeenInsteadOfFailingSilently() {
        val h = musicWithARemoteSong(PlayChoice.Unreachable("Hisham's PC", System.currentTimeMillis() - 3 * 3_600_000L))
        rule.waitForTag(UiTags.libRemoteHint(song))
        assertEquals("On Hisham's PC (not reachable)", hintText())
        rule.onNodeWithTag(UiTags.libItem(song)).performClick()
        rule.waitUntil(5_000) { rule.hasText("Hisham's PC is not reachable. It was last seen 3 hours ago.") }
        assertFalse("nothing started", rule.exists(UiTags.MINI_PLAYER))
        assertTrue(h.vm.player.ui.value.queue.none { it.remote })
    }

    @Test fun anItemNoDeviceHasSaysItIsNotOnThisPhone() {
        musicWithARemoteSong(PlayChoice.None)
        rule.waitForTag(UiTags.libRemoteHint(song))
        assertEquals("Not on this phone. No paired device is known to have it.", hintText())
        rule.onNodeWithTag(UiTags.libItem(song)).performClick()
        rule.waitUntil(5_000) { rule.hasText("no paired device is known to have it") }
        assertFalse(rule.exists(UiTags.MINI_PLAYER))
    }

    private fun shot(dark: Boolean) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        musicWithARemoteSong(PlayChoice.Peer("Hisham's PC"), dark)
        rule.waitForTag(UiTags.libRemoteHint(song))
        rule.waitForIdle(); Thread.sleep(900)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val dir = File(instrumentation.targetContext.filesDir, "shots").also { it.mkdirs() }
        File(dir, "playfrom-hint-${if (dark) "dark" else "light"}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun lightHintScreenshot() = shot(dark = false)
    @Test fun darkHintScreenshot() = shot(dark = true)
}
