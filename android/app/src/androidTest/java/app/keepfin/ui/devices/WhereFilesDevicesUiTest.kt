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
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.net.PairedDeviceUi
import app.keepfin.net.PairedLibraryUi
import app.keepfin.ui.settings.WhereTags
import app.keepfin.ui.theme.KeepfinTheme
import app.keepfin.ui.theme.ThemeMode
import app.keepfin.where.ItemPlace
import app.keepfin.where.PlaceKind
import app.keepfin.where.SingleKind
import app.keepfin.where.WhereFilesSource
import app.keepfin.where.WhereFilesUi
import app.keepfin.where.WherePlace
import app.keepfin.where.WhereSingle
import app.keepfin.where.WhereTap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Settings > Devices > Where your files are, below Your devices. */
@RunWith(AndroidJUnit4::class)
class WhereFilesDevicesUiTest {
    @get:Rule val rule = createComposeRule()

    private class Source(ui: WhereFilesUi) : WhereFilesSource {
        override val state: StateFlow<WhereFilesUi> = MutableStateFlow(ui)
        override fun placesOf(itemId: String): List<ItemPlace> = emptyList()
    }

    private fun laptop() = PairedDeviceUi(
        "dev1", "1111 2222 3333 4444 5555", "Desk PC", "windows", 1, System.currentTimeMillis() - 60_000, true,
        listOf(PairedLibraryUi("lib1", "Family music", listOf("metadata.read"), "a-pc")), null, "192.168.1.20", true, emptyList(), emptyList(), false,
    )

    private val several = WhereFilesUi(
        true,
        listOf(
            WherePlace("this", PlaceKind.THIS_DEVICE, "android", "This phone", "Everything", 13_314_398_618, "up to date", null),
            WherePlace("device:dev1", PlaceKind.DEVICE, "windows", "Desk PC", "Music, Favourites", 3_000_000_000, "up to date 2 min ago", WhereTap.Device("dev1")),
            WherePlace("copy:c1", PlaceKind.DRIVE, null, "Backup card", "Everything", 900_000_000, "last copied 3 days ago · not plugged in", WhereTap.Copy("c1")),
        ),
        listOf(WhereSingle(SingleKind.SONGS, 3, "this phone", true, setOf("music")), WhereSingle(SingleKind.VIDEOS, 12, "Desk PC", false, setOf("movies"))),
    )

    private val one = WhereFilesUi(true, listOf(WherePlace("this", PlaceKind.THIS_DEVICE, "android", "This phone", "Everything", 1_000_000, "up to date", null)), emptyList(), onePlaceOnly = true)

    private fun show(ui: WhereFilesUi, opened: MutableList<String> = ArrayList(), kept: MutableList<Set<String>> = ArrayList(), dark: Boolean = false, scale: Float = 1f) {
        val c = FakeController(state(paired = listOf(laptop())))
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                KeepfinTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) {
                        DevicesScreen(c, where = Source(ui), onOpenCopy = { opened += it }, onKeepCopy = { kept += it })
                    }
                }
            }
        }
    }

    @Test fun theSectionSitsBelowYourDevicesWithTheRowsAndWords() {
        show(several)
        rule.onNodeWithTag(WhereTags.SECTION).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Where your files are").assertExists()
        rule.onNodeWithText("Music, Favourites").assertExists()
        rule.onNodeWithText("2.8 GB · up to date 2 min ago").assertExists()
        rule.onNodeWithText("858 MB · last copied 3 days ago · not plugged in").assertExists()
        val yours = rule.onNodeWithTag(DevicesTags.YOURS).getUnclippedBoundsInRoot().top.value
        val where = rule.onNodeWithTag(WhereTags.SECTION).getUnclippedBoundsInRoot().top.value
        assertTrue("below Your devices", where > yours)
    }

    @Test fun tappingADeviceRowOpensItsCardAndACopyRowOpensItInStorage() {
        val opened = ArrayList<String>()
        show(several, opened)
        rule.onNodeWithTag(DevicesTags.DETAILS).assertDoesNotExist()
        rule.onNodeWithTag(WhereTags.row("device:dev1")).performScrollTo().performClick()
        rule.onNodeWithTag(DevicesTags.DETAILS).assertExists()
        rule.onNodeWithTag(WhereTags.row("copy:c1")).performScrollTo().performClick()
        assertEquals(listOf("c1"), opened)
    }

    @Test fun theWarningAndKeepACopySendTheSpaceToChoose() {
        val kept = ArrayList<Set<String>>()
        show(several, kept = kept)
        rule.onNodeWithText("3 songs are only on this phone").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("12 videos are only on Desk PC").assertExists()
        rule.onNodeWithTag(WhereTags.keep(0)).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(setOf("music")), kept)
    }

    @Test fun onePlaceHasTheSentenceAndNoWarning() {
        val kept = ArrayList<Set<String>>()
        show(one, kept = kept)
        rule.onNodeWithText("Everything is on this phone only. Keep a copy on another device, a drive or the cloud.").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(WhereTags.ONE_PLACE_KEEP).performScrollTo().performClick()
        assertEquals(listOf(emptySet<String>()), kept)
    }

    private fun large(dark: Boolean) {
        show(several, dark = dark, scale = 1.3f)
        for (id in listOf("this", "device:dev1", "copy:c1")) rule.onNodeWithTag(WhereTags.row(id)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag(WhereTags.keep(0)).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }

    @Test fun largeTextShowsEverythingInLight() = large(false)
    @Test fun largeTextShowsEverythingInDark() = large(true)

    private fun shot(name: String, ui: WhereFilesUi, dark: Boolean) {
        show(ui, dark = dark, scale = 1.15f)
        rule.waitForIdle()
        saveNode("where-devices-$name-" + if (dark) "dark" else "light", rule.onNodeWithTag(WhereTags.SECTION).performScrollTo())
    }

    @Test fun shotSectionLight() = shot("section", several, false)
    @Test fun shotSectionDark() = shot("section", several, true)
    @Test fun shotOnePlaceLight() = shot("one-place", one, false)
}
