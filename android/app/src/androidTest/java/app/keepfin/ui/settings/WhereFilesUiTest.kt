package app.keepfin.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.copies.CopiesUi
import app.keepfin.copies.CopyDraft
import app.keepfin.copies.CopyFolderCheck
import app.keepfin.copies.CopyHoldsUi
import app.keepfin.copies.CopyPreview
import app.keepfin.copies.CopyReviewUi
import app.keepfin.copies.CopyVersionUi
import app.keepfin.copies.ReviewAction
import app.keepfin.ui.copies.CopiesActions
import app.keepfin.ui.copies.CopiesContent
import app.keepfin.ui.copies.CopiesTags
import app.keepfin.ui.devices.saveNode
import app.keepfin.ui.theme.KeepfinTheme
import app.keepfin.ui.theme.ThemeMode
import app.keepfin.where.PlaceKind
import app.keepfin.where.SingleKind
import app.keepfin.where.WhereFilesUi
import app.keepfin.where.WherePlace
import app.keepfin.where.WhereSingle
import app.keepfin.where.WhereTap
import app.keepfin.where.WhereWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Settings > Storage > Where your files are, over fake state: the rows and their words, where a tap goes, the warnings, Keep a copy... and the one-place sentence. */
@RunWith(AndroidJUnit4::class)
class WhereFilesUiTest {
    @get:Rule val rule = createComposeRule()

    private class Rec : CopiesActions {
        override fun sync(placeId: String) {}
        override fun pause(placeId: String) {}
        override fun syncAll() {}
        override fun remove(placeId: String) {}
        override fun resolve(reviewId: String, action: ReviewAction) {}
        override fun edit(placeId: String, name: String?, holds: CopyHoldsUi?, everyHours: Int?, auto: Boolean?, exports: Triple<Boolean, Boolean, Boolean>?) {}
        override fun reconnect(placeId: String, treeUri: String): String? = null
        override fun checkFolder(treeUri: String) = CopyFolderCheck(true, null, "Card")
        override fun preview(draft: CopyDraft, previousId: String?) = CopyPreview("p1", "ready")
        override fun cancelPreview(placeId: String) {}
        override fun add(placeId: String) {}
        override fun versions(placeId: String) = emptyList<CopyVersionUi>()
        override fun restore(placeId: String, entryId: String) {}
        override fun cleanup(placeId: String) {}
    }

    private val several = WhereFilesUi(
        ready = true,
        places = listOf(
            WherePlace("this", PlaceKind.THIS_DEVICE, "android", "This phone", "Everything", 13_314_398_618, "up to date", null),
            WherePlace("device:d1", PlaceKind.DEVICE, "windows", "Desk PC", "Music, Favourites", 3_000_000_000, "up to date 2 min ago", WhereTap.Device("d1")),
            WherePlace("copy:c1", PlaceKind.DRIVE, null, "Backup card", "Road trip and 2 more", 900_000_000, "last copied 3 days ago · not plugged in", WhereTap.Copy("c1")),
            WherePlace("copy:c2", PlaceKind.CLOUD, null, "Google Drive", "Everything", 5_000_000_000, "copying · 40 of 210", WhereTap.Copy("c2")),
        ),
        singles = listOf(
            WhereSingle(SingleKind.SONGS, 3, "this phone", true, setOf("music")),
            WhereSingle(SingleKind.VIDEOS, 12, "Desk PC", false, setOf("movies")),
        ),
    )

    private val one = WhereFilesUi(true, listOf(WherePlace("this", PlaceKind.THIS_DEVICE, "android", "This phone", "Everything", 1_000_000, "up to date", null)), emptyList(), onePlaceOnly = true)

    private fun show(ui: WhereFilesUi, onOpen: (WhereTap) -> Unit = {}, onKeep: (Set<String>) -> Unit = {}, dark: Boolean = false, scale: Float = 1f) {
        rule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) {
                KeepfinTheme(mode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState())) { WhereFilesGroup(ui, onOpen, onKeep) }
                }
            }
        }
    }

    @Test fun rowsShowNameWhatItHoldsSizeAndState() {
        show(several)
        rule.onNodeWithText("Where your files are").assertExists()
        rule.onNodeWithText("This phone").assertIsDisplayed()
        rule.onNodeWithText("12.4 GB · up to date").assertExists()
        rule.onNodeWithText("Music, Favourites").assertExists()
        rule.onNodeWithText("2.8 GB · up to date 2 min ago").assertExists()
        rule.onNodeWithText("Road trip and 2 more").assertExists()
        rule.onNodeWithText("858 MB · last copied 3 days ago · not plugged in").assertExists()
        rule.onNodeWithText("4.7 GB · copying · 40 of 210").assertExists()
        // this phone is first
        val top = listOf("this", "device:d1", "copy:c1", "copy:c2").map { rule.onNodeWithTag(WhereTags.row(it)).fetchSemanticsNode().positionInRoot.y }
        assertEquals(top.sorted(), top)
    }

    @Test fun musicVideosGetTheirOwnSizeLineOnlyWhenThereAreAny() {
        show(several.copy(musicVideoBytes = 4_400_000_000))
        rule.onNodeWithTag(WhereTags.MUSIC_VIDEOS).performScrollTo().assertIsDisplayed().assertTextContains("Music videos · 4.1 GB")
    }

    @Test fun noMusicVideosNoLine() {
        show(several)
        rule.onNodeWithTag(WhereTags.row("this")).assertIsDisplayed()
        rule.onNodeWithTag(WhereTags.MUSIC_VIDEOS).assertDoesNotExist()
        rule.onNodeWithText("Music videos", substring = true).assertDoesNotExist()
    }

    @Test fun tappingARowOpensThatPlaceAndThisPhoneDoesNotOpenAnything() {
        val taps = ArrayList<WhereTap>()
        show(several, onOpen = { taps += it })
        rule.onNodeWithTag(WhereTags.row("device:d1")).performScrollTo().performClick()
        rule.onNodeWithTag(WhereTags.row("copy:c1")).performScrollTo().performClick()
        rule.onNodeWithTag(WhereTags.row("copy:c2")).performScrollTo().performClick()
        assertEquals(listOf<WhereTap>(WhereTap.Device("d1"), WhereTap.Copy("c1"), WhereTap.Copy("c2")), taps)
        rule.onNodeWithTag(WhereTags.row("this")).assert(hasClickAction().not())
        rule.onNodeWithTag(WhereTags.row("device:d1")).assertHeightIsAtLeast(48.dp)
    }

    @Test fun aWarningPerKindWithKeepACopyOnlyWhereThisPhoneCanDoIt() {
        val kept = ArrayList<Set<String>>()
        show(several, onKeep = { kept += it })
        rule.onNodeWithText("3 songs are only on this phone").assertExists()
        rule.onNodeWithText("12 videos are only on Desk PC").assertExists()
        rule.onNodeWithTag(WhereTags.keep(0)).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(setOf("music")), kept)
        // a file that is only on the PC cannot be copied from this phone: no button on that line
        rule.onNodeWithTag(WhereTags.keep(1)).assertDoesNotExist()
        rule.onNodeWithTag(WhereTags.ONE_PLACE).assertDoesNotExist()
    }

    @Test fun onePlaceSaysEverythingIsOnThisPhoneOnly() {
        val kept = ArrayList<Set<String>>()
        show(one, onKeep = { kept += it })
        rule.onNodeWithTag(WhereTags.ONE_PLACE).assertTextContains("Everything is on this phone only. Keep a copy on another device, a drive or the cloud", substring = true)
        rule.onNodeWithTag(WhereTags.ONE_PLACE_KEEP).performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf(emptySet<String>()), kept)
        rule.onNodeWithTag(WhereTags.single(0)).assertDoesNotExist()
    }

    @Test fun nothingIsWarnedWhenEveryFileIsInTwoPlaces() {
        show(several.copy(singles = emptyList()))
        rule.onNodeWithTag(WhereTags.single(0)).assertDoesNotExist()
        rule.onNodeWithTag(WhereTags.ONE_PLACE).assertDoesNotExist()
    }

    @Test fun keepACopyOpensAddACopyWithTheSpaceAlreadyChosen() {
        val copies = CopiesUi(true, emptyList(), emptyList<CopyReviewUi>(), false, listOf("music" to "Music", "movies" to "Movies"), emptyList())
        rule.setContent {
            KeepfinTheme(mode = ThemeMode.LIGHT) {
                var keep by remember { mutableStateOf<Set<String>?>(null) }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    WhereFilesGroup(several, {}, { keep = it })
                    CopiesContent(copies, Rec(), keep, { keep = null })
                }
            }
        }
        rule.onNodeWithTag(CopiesTags.WIZARD).assertDoesNotExist()
        rule.onNodeWithTag(WhereTags.keep(0)).performScrollTo().performClick()
        rule.onNodeWithTag(CopiesTags.WIZARD).assertIsDisplayed()
        rule.onNodeWithTag(CopiesTags.PRESELECT).assertTextContains("Keeping a copy of Music", substring = true)
    }

    private fun largeText(dark: Boolean) {
        show(several, dark = dark, scale = 1.3f)
        for (id in listOf("this", "device:d1", "copy:c1", "copy:c2")) rule.onNodeWithTag(WhereTags.row(id)).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(64.dp)
        rule.onNodeWithTag(WhereTags.keep(0)).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithText("858 MB · last copied 3 days ago · not plugged in").assertExists()
    }

    @Test fun largeTextKeepsEveryRowAndButtonReachableInLight() = largeText(false)
    @Test fun largeTextKeepsEveryRowAndButtonReachableInDark() = largeText(true)
    @Test fun lookingStateBeforeTheFirstAnswer() {
        show(WhereFilesUi())
        rule.onNodeWithTag(WhereTags.LOADING).assertIsDisplayed()
        assertTrue(rule.onAllNodesWithTag(WhereTags.SECTION).fetchSemanticsNodes().isEmpty())
    }

    // ---- screenshots (pulled with run-as; the brief's folder) ----------------------------------------------------------------------

    private fun shot(name: String, ui: WhereFilesUi, dark: Boolean) {
        show(ui, dark = dark, scale = 1.15f)
        rule.waitForIdle()
        saveNode("where-storage-$name-" + if (dark) "dark" else "light", rule.onNodeWithTag("where-files-group"))
    }

    @Test fun shotSeveralLight() = shot("several", several, false)
    @Test fun shotSeveralDark() = shot("several", several, true)
    @Test fun shotOnePlaceLight() = shot("one-place", one, false)
    @Test fun shotOnePlaceDark() = shot("one-place", one, true)
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTag(tag: String) = onAllNodes(androidx.compose.ui.test.hasTestTag(tag))
}
