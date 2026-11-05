package app.keepfin.setup

import android.content.Context
import android.content.ContextWrapper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import app.keepfin.LibrarySettings
import app.keepfin.folder.ProfileStore
import app.keepfin.proto.ProbeSupport
import app.keepfin.ui.setup.SetupFlow
import app.keepfin.ui.setup.SetupTags
import app.keepfin.ui.theme.KeepfinTheme
import app.keepfin.ui.theme.ThemeMode
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The recommended place on a real emulator: Keepfin makes Documents/Keepfin and the system picker opens inside it. */
@RunWith(AndroidJUnit4::class)
class RecommendedPlaceInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val p = ProbeSupport("KeepfinRecommended")
    private val root = File(p.ctx.filesDir, "test-recommended-" + UUID.randomUUID()).apply { mkdirs() }
    private val isolated: Context = object : ContextWrapper(p.ctx) {
        override fun getFilesDir() = root
    }

    private class FakeEngine : SetupEngine {
        @Volatile var attached = false
        override fun legacyLibrarySettings() = LibrarySettings()
        override fun legacySettingsJson(): String? = null
        override fun attachFolderLibrary() { attached = true }
    }

    @Before fun clean() { p.shell("rm -rf /sdcard/Documents/Keepfin"); p.releaseAll("Keepfin") }

    @After fun after() {
        p.releaseAll("Keepfin")
        p.shell("rm -rf /sdcard/Documents/Keepfin")
        root.deleteRecursively()
    }

    private fun waitFor(what: String, seconds: Long = 15, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + seconds * 1000
        while (System.currentTimeMillis() < end) { if (condition()) return; runCatching { rule.waitForIdle() }; Thread.sleep(100) }
        runCatching { p.device.takeScreenshot(File(p.ctx.getExternalFilesDir(null), "fail.png")) }
        val out = java.io.ByteArrayOutputStream(); runCatching { p.device.dumpWindowHierarchy(out) }
        throw AssertionError("timed out waiting for $what; screen: " + Regex("text=\"([^\"]+)\"").findAll(out.toString()).map { it.groupValues[1] }.distinct().take(25).joinToString(" | "))
    }

    /** The notes in the hidden data folder, counted on the disk (MediaStore does not list hidden folders to every query). */
    private fun readmeRows(): Int = p.shell("ls -a /sdcard/Documents/Keepfin").lines().count { it.startsWith("README") }

    @Test fun theRecommendedButtonMakesDocumentsKeepfinAndAsksForThePickerInsideIt() {
        val setup = StorageSetup(isolated, FakeEngine())
        setup.chooseRecommended()
        waitFor("the picker request") { setup.ui.value.request != null }
        assertEquals(SetupRequest.Picker("primary:Documents/Keepfin"), setup.ui.value.request)
        assertTrue("the folder exists on the disk with its note", p.shell("ls -a /sdcard/Documents/Keepfin").contains("README.txt"))
        assertEquals(1, readmeRows())
        // pressing it again (a reinstall finds the folder) reuses the folder and adds nothing
        setup.requestHandled()
        setup.chooseRecommended()
        waitFor("the second request") { setup.ui.value.request != null }
        assertEquals(SetupRequest.Picker("primary:Documents/Keepfin"), setup.ui.value.request)
        assertEquals(1, readmeRows())
    }

    @Test fun anEarlierNotNowNoLongerSkipsTheFolder() {
        isolated.getSharedPreferences("storage-setup", android.content.Context.MODE_PRIVATE).edit().putBoolean("skipped", true).commit()
        val setup = StorageSetup(isolated, FakeEngine())
        assertEquals(Gate.FIRST_START, setup.ui.value.gate)
        assertFalse(isolated.getSharedPreferences("storage-setup", android.content.Context.MODE_PRIVATE).contains("skipped"))
    }

    @Test fun theOtherButtonsOpenAtTheTopOfTheStorage() {
        val setup = StorageSetup(isolated, FakeEngine())
        setup.chooseAnother()
        assertEquals(SetupRequest.Picker("primary:"), setup.ui.value.request)
        setup.requestHandled()
        setup.chooseExisting()
        assertEquals(SetupRequest.Picker("primary:"), setup.ui.value.request)
        // nothing was made for them
        assertTrue(p.shell("ls /sdcard/Documents").lines().none { it.trim() == "Keepfin" })
    }

    @Test fun endToEndTheRecommendedButtonThePickerAndTheNextStep() {
        val engine = FakeEngine()
        val setup = StorageSetup(isolated, engine)
        assertEquals(Gate.FIRST_START, setup.ui.value.gate)
        rule.setContent {
            val ui = setup.ui.collectAsState()
            KeepfinTheme(mode = ThemeMode.LIGHT) { SetupFlow(ui.value, setup, startInAdvanced = true) }
        }
        rule.onNodeWithTag(SetupTags.RECOMMENDED).performClick()
        waitFor("the screen to take the request; state=${setup.ui.value}", 20) { setup.ui.value.request == null && !setup.ui.value.busy }
        // the system picker opens inside Documents/Keepfin; confirm it like a person would
        val use = p.device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)use this folder"))), 20_000)
        assertNotNull("the picker offers Use this folder", use)
        use.click()
        val allow = p.device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?i)allow"))), 10_000)
        assertNotNull("Android asks Allow", allow)
        allow.click()
        // the folder is chosen: the profile is recorded and the start goes on to its last page; the app opens only when Start is tapped
        waitFor("the profile", 30) { ProfileStore(File(root, "profiles.json")).current() != null }
        waitFor("the start to be ready", 30) { setup.ui.value.startReady && !setup.ui.value.busy }
        assertEquals(Gate.FIRST_START, setup.ui.value.gate)
        assertFalse("the library is not attached before Start", engine.attached)
        setup.finishStart(complete = false)
        waitFor("the app to open", 30) { setup.ui.value.gate == Gate.NONE && engine.attached }
        val profile = ProfileStore(File(root, "profiles.json")).current()
        assertNotNull(profile)
        assertTrue(profile!!.folderTreeUri, profile.folderTreeUri.contains("Documents%2FKeepfin"))
        assertTrue(p.shell("ls -a /sdcard/Documents/Keepfin/.keepfin").contains("folder.json"))
        assertEquals("the hint note was not duplicated", 1, readmeRows())
    }
}
