package app.keepfin.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.ui.library.LibraryRoute
import app.keepfin.ui.settings.SettingsCategory
import app.keepfin.ui.theme.ThemeMode
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Takes real device screenshots of the Spaces v2 screens for review. Skipped unless run with
 * `-Pandroid.testInstrumentationRunnerArguments.screenshots=true`. Pictures land in the app's files/shots folder:
 * `adb exec-out run-as app.keepfin cat files/shots/<name>.png`.
 */
@RunWith(AndroidJUnit4::class)
class SpacesV2ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Before fun only() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
        StudioEngine.override = null
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        Thread.sleep(900)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val dir = File(instrumentation.targetContext.filesDir, "shots").also { it.mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Closes an open sheet with the system Back key, as a user would. */
    private fun closeSheet() {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        rule.waitForIdle(); Thread.sleep(700)
    }

    private fun start(dark: Boolean, open: LibraryRoute? = null, settings: SettingsCategory? = null): Harness {
        val h = Harness("spaces-v2")
        h.prefs.chooseTheme(if (dark) ThemeMode.DARK else ThemeMode.LIGHT)
        if (settings != null) h.openSettings(settings) else {
            h.vm.navigate(Destination.LIBRARY)
            if (open != null) h.vm.openLibraryRoot(open)
        }
        h.show(rule)
        return h
    }

    @Test fun lightScreens() = screens(dark = false)
    @Test fun darkScreens() = screens(dark = true)

    private fun screens(dark: Boolean) {
        val mode = if (dark) "dark" else "light"
        // 1 Library home, 2 edit in place.
        val h = start(dark)
        rule.waitForTag(UiTags.LIB_SPACES_GRID)
        shot("01-home-$mode")
        rule.onNodeWithTag(UiTags.LIB_LIST).performScrollToNode(hasTestTag(UiTags.LIB_SHELF_COLLECTIONS))
        shot("01b-home-collections-$mode")
        h.vm.openLibraryRoot(LibraryRoute.Manage)
        rule.waitForTag(UiTags.LIB_SPACE_NEW)
        shot("02-hub-edit-$mode")
        rule.onNodeWithTag(UiTags.LIB_SPACES_GRID).performScrollToNode(hasTestTag(UiTags.setting(UiTags.SETTINGS_ROUTE_MUSIC)))
        shot("02b-hub-edit-routes-$mode")
        // 3 All spaces.
        h.vm.openLibraryRoot(LibraryRoute.Home)
        rule.waitForTag(UiTags.LIB_ALL_SPACES)
        rule.onNodeWithTag(UiTags.LIB_ALL_SPACES).performClick()
        rule.waitForTag(UiTags.LIB_ALL_SPACES_LIST)
        shot("03-all-spaces-$mode")
        // 4 New space sheet (folder, then smart with a color picked).
        h.vm.openLibraryRoot(LibraryRoute.Manage)
        rule.waitForTag(UiTags.LIB_SPACE_NEW)
        rule.onNodeWithTag(UiTags.LIB_SPACE_NEW).performClick()
        rule.waitForTag(UiTags.SPACE_EDITOR)
        rule.onNodeWithTag(UiTags.SPACE_NAME).performTextInput("Evening focus")
        shot("04-new-space-folder-$mode")
        rule.onNodeWithTag(UiTags.SPACE_TYPE_SMART).performClick()
        rule.onNodeWithTag(UiTags.colorSwatch("teal")).performClick()
        shot("04b-new-space-smart-$mode")
        rule.onNodeWithTag(UiTags.SPACE_NEXT).performClick()
        // 5 Smart rules editor.
        rule.waitForTag(UiTags.RULES_EDITOR)
        rule.onNodeWithTag(UiTags.RULES_ADD).performClick()
        rule.onNodeWithTag(UiTags.ruleType("tag")).performClick()
        rule.waitForTag("rules-picker")
        rule.onNodeWithTag(UiTags.pickOption("lofi")).performClick()
        rule.onNodeWithTag(UiTags.pickOption("chill")).performClick()
        shot("05a-rules-picker-$mode")
        rule.onNodeWithTag("rules-picker-done").performClick()
        rule.onNodeWithTag(UiTags.RULES_ADD).performClick()
        rule.onNodeWithTag(UiTags.ruleType("favorite")).performClick()
        rule.onNodeWithTag(UiTags.RULES_ONLY_SONGS).performClick()
        shot("05-rules-editor-$mode")
        h.vm.back()
        h.vm.openLibraryRoot(LibraryRoute.Space("lofi"))
        rule.waitForTag(UiTags.SMART_SPACE)
        shot("05b-smart-space-$mode")
        // 6 Inside a space: tags row, collections tab.
        h.vm.openLibraryRoot(LibraryRoute.Space("music"))
        rule.waitForTag(UiTags.TAGS_CHIP)
        shot("06-space-music-$mode")
        rule.onNodeWithTag(UiTags.tagChip("pop")).performClick()
        shot("06b-space-music-filtered-$mode")
        rule.onNodeWithTag(UiTags.libTab("collections")).performClick()
        rule.waitForTag(UiTags.collectionRow("khaleeji"))
        shot("06c-space-collections-$mode")
        h.vm.openLibraryRoot(LibraryRoute.Space("videos"))
        rule.waitForTag(UiTags.libTab("collections"))
        shot("06d-space-videos-$mode")
        // 7 Tag filter sheet.
        h.vm.chooseMusicTab("music", app.keepfin.ui.library.MusicTab.SONGS)
        h.vm.openLibraryRoot(LibraryRoute.Space("music"))
        rule.waitForTag(UiTags.TAGS_CHIP)
        rule.onNodeWithTag(UiTags.TAGS_CHIP).performClick()
        rule.waitForTag(UiTags.TAGS_SHEET)
        rule.onNodeWithTag(UiTags.sheetTag("arabic")).performClick()
        rule.onNodeWithTag(UiTags.sheetTag("chill")).performClick()
        shot("07-tag-sheet-$mode")
        closeSheet()
        // 8 Add to a space: library picker.
        h.vm.openLibraryRoot(LibraryRoute.Space("workout"))
        rule.waitForTag(UiTags.LIB_OVERFLOW)
        rule.onNodeWithTag(UiTags.LIB_OVERFLOW).performClick()
        rule.onNodeWithTag(UiTags.LIB_ADD_FILES).performClick()
        rule.waitForTag(UiTags.ADD_SHEET)
        rule.onNodeWithTag(UiTags.addRow("roadtrip")).performClick()
        shot("08-add-sheet-$mode")
        closeSheet()
        // 9 Collection detail, followed with news and one removed upstream; then a held-back check.
        h.vm.openLibraryRoot(LibraryRoute.Collection("workout", "gym"))
        rule.waitForTag(UiTags.COLLECTION_DETAIL)
        shot("09-collection-$mode")
        rule.onNodeWithTag(UiTags.LIB_LIST).performScrollToNode(hasTestTag(UiTags.libItem("content://media/external/audio/media/504")))
        shot("09b-collection-removed-$mode")
        h.vm.openLibraryRoot(LibraryRoute.Collection("workout", "daily-run"))
        rule.waitForTag(UiTags.FOLLOW_REVIEW)
        shot("09c-collection-review-$mode")
        // 10 Follow settings sheet.
        h.vm.openLibraryRoot(LibraryRoute.Collection("workout", "gym"))
        rule.waitForTag(UiTags.FOLLOW_CARD)
        rule.onNodeWithTag(UiTags.FOLLOW_CARD).performClick()
        rule.waitForTag(UiTags.FOLLOW_SHEET)
        shot("10-follow-sheet-$mode")
        closeSheet()
        // 11 Settings pages and 12 the update channel.
        h.vm.navigate(Destination.SETTINGS)
        rule.runOnIdle { h.vm.chooseSettingsPage(SettingsCategory.APPEARANCE) }
        rule.waitForTag(UiTags.SETTINGS_START_PAGE)
        shot("11-settings-general-$mode")
        rule.runOnIdle { h.vm.chooseSettingsPage(SettingsCategory.LIBRARY) }
        rule.waitForTag(UiTags.SETTINGS_LIST)
        rule.onNodeWithTag(UiTags.SETTINGS_LIST).performScrollToNode(hasTestTag(UiTags.setting("import-tags")))
        shot("11b-settings-library-$mode")
        rule.runOnIdle { h.vm.chooseSettingsPage(SettingsCategory.ABOUT) }
        rule.waitForTag(UiTags.setting("app-channel"))
        shot("12-settings-updates-$mode")
    }
}
