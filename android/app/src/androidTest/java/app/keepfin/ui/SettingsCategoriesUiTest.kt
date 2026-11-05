package app.keepfin.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.copies.CopiesUi
import app.keepfin.copies.CopyCardUi
import app.keepfin.copies.CopyHoldsUi
import app.keepfin.copies.CopyReviewUi
import app.keepfin.copies.CopyStatus
import app.keepfin.core.copies.CopyMode
import app.keepfin.net.NetRuntime
import app.keepfin.net.PairingUi
import app.keepfin.setup.StorageStatus
import app.keepfin.ui.settings.AttentionBlock
import app.keepfin.ui.settings.AttentionItem
import app.keepfin.ui.settings.HomeRow
import app.keepfin.ui.settings.SettingsCategory
import app.keepfin.ui.settings.SettingsEntry
import app.keepfin.ui.settings.SettingsFacts
import app.keepfin.ui.settings.SettingsGroups
import app.keepfin.ui.settings.SettingsHomeContent
import app.keepfin.ui.settings.SettingsIndex
import app.keepfin.ui.settings.SettingsRedirect
import app.keepfin.ui.settings.SettingsSearchContent
import app.keepfin.ui.settings.SettingsTarget
import app.keepfin.ui.settings.SettingsVisibility
import app.keepfin.ui.settings.StartOverTags
import app.keepfin.ui.settings.attentionItems
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.unit.dp
import app.keepfin.ui.theme.KeepfinTheme
import app.keepfin.ui.theme.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Settings home (eight categories with live summaries and "Needs your attention"), search, the redirect map and the category pages. */
@RunWith(AndroidJUnit4::class)
class SettingsCategoriesUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null; NetRuntime.setIncomingForTests(null) }

    private val shownOnThisBuild = listOf(SettingsCategory.DOWNLOADS, SettingsCategory.LIBRARY, SettingsCategory.APPEARANCE, SettingsCategory.NOTIFICATIONS, SettingsCategory.HELP, SettingsCategory.ABOUT)

    @Test fun homeListsTheCategoriesInOrderWithLiveSummaries() {
        val h = Harness("library")
        h.vm.navigate(Destination.SETTINGS)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_OVERVIEW)
        rule.onNodeWithText("Best quality · YouTube Music as audio").assertExists()
        rule.onNodeWithText("On · 4 spaces · Keepfin player").assertExists()
        rule.onNodeWithText("System theme", substring = true).assertExists()
        rule.onNodeWithText("Version", substring = true).assertExists()
        rule.onNodeWithText("Help").assertExists()
        rule.onNodeWithText("Support Keepfin").assertExists()
        rule.onNodeWithText("About Keepfin").assertExists()
        // Storage and Devices (the Keepfin folder) are not listed without a real engine behind it; the others keep the plan's order.
        assertFalse(rule.exists(UiTags.settingsCategory("STORAGE")))
        assertFalse(rule.exists(UiTags.settingsCategory("DEVICES")))
        val tops = shownOnThisBuild.map { rule.onNodeWithTag(UiTags.settingsCategory(it.name)).getUnclippedBoundsInRoot().top.value }
        assertEquals("rows run top to bottom in the plan's order", tops.sorted(), tops)
        assertEquals(tops.size, tops.toSet().size)
    }

    @Test fun allEightRowsAreInThePlansOrderWhenTheBuildHasStorageAndDevices() {
        val rows = SettingsCategory.entries.map { HomeRow(it, "summary of ${it.title}") }
        rule.setContent { KeepfinTheme(ThemeMode.LIGHT) { SettingsHomeContent(rows, emptyList(), PaddingValues(), {}, {}, {}) } }
        assertEquals(
            listOf("Downloads", "Library & playback", "Storage & copies", "Devices", "Appearance", "Notifications", "Help", "Support Keepfin", "About Keepfin"),
            SettingsCategory.entries.map { it.title },
        )
        val tops = SettingsCategory.entries.map { rule.onNodeWithTag(UiTags.settingsCategory(it.name)).getUnclippedBoundsInRoot().top.value }
        assertEquals(tops.sorted(), tops)
        rule.onNodeWithText("summary of Devices").assertIsDisplayed()
    }

    @Test fun eachCategoryOpensItsOwnTitledPageAndBackReturnsToTheHome() {
        val h = Harness("library")
        h.vm.navigate(Destination.SETTINGS)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_OVERVIEW)
        for (category in shownOnThisBuild) {
            rule.onNodeWithTag(UiTags.settingsCategory(category.name)).performClick()
            rule.waitForTag(UiTags.SETTINGS_PAGE_BACK)
            assertEquals(category, h.vm.settingsPage)
            assertTrue(rule.onAllNodesWithText(category.title).fetchSemanticsNodes().isNotEmpty())
            rule.waitForTag(UiTags.SETTINGS_LIST)
            rule.onNodeWithTag(UiTags.SETTINGS_PAGE_BACK).performClick()
            rule.waitForTag(UiTags.SETTINGS_OVERVIEW)
        }
    }

    @Test fun systemBackReturnsToTheHomeBeforeLeavingSettings() {
        val h = Harness("library")
        h.vm.navigate(Destination.SETTINGS)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_OVERVIEW)
        rule.onNodeWithTag(UiTags.settingsCategory("DOWNLOADS")).performClick()
        rule.waitForTag(UiTags.SETTINGS_LIST)
        assertEquals(SettingsCategory.DOWNLOADS, h.vm.settingsPage)
        rule.runOnIdle { assertTrue(h.vm.back()) }
        rule.waitForTag(UiTags.SETTINGS_OVERVIEW)
        assertNull(h.vm.settingsPage)
        assertEquals(Destination.SETTINGS, h.vm.destination)
        rule.runOnIdle { assertTrue(h.vm.back()) }
        assertEquals(Destination.DOWNLOADS, h.vm.destination)
    }

    @Test fun everyExistingSettingStillLivesOnItsCategoryPage() {
        val h = Harness("library")
        h.vm.navigate(Destination.SETTINGS)
        h.show(rule)
        val expected = mapOf(
            SettingsCategory.APPEARANCE to listOf("theme-dark", "start-page", "offer-copied-links"),
            SettingsCategory.DOWNLOADS to listOf("preset", "ytmusic-audio", "organization-flat", "lyrics", "clear-sign-in"),
            SettingsCategory.LIBRARY to listOf("mode-full", "player-other"),
            SettingsCategory.NOTIFICATIONS to listOf("notification-enabled"),
            SettingsCategory.ABOUT to listOf("auto-update", "channel-stable"),
        )
        for ((category, tags) in expected) {
            rule.runOnIdle { h.vm.chooseSettingsPage(category) }
            rule.waitForTag(UiTags.SETTINGS_LIST)
            for (tag in tags) {
                val full = if (tag.startsWith("notification-")) tag else UiTags.setting(tag)
                rule.scrollListTo(UiTags.SETTINGS_LIST, full)
                rule.onNodeWithTag(full).assertExists()
            }
        }
    }

    @Test fun keepfinModeIsSetOnlyInLibraryAndPlayback() {
        val h = Harness("library")
        h.openSettings(SettingsCategory.APPEARANCE)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_LIST)
        assertFalse("the General copy is gone", rule.exists(UiTags.SETTING_MODE_FULL))
        rule.runOnIdle { h.vm.chooseSettingsPage(SettingsCategory.LIBRARY) }
        rule.waitForTag(UiTags.SETTING_MODE_FULL)
        rule.waitForTag(UiTags.SETTING_MODE_DOWNLOADER)
        assertFalse("one place only: no second switch for the same choice", rule.exists(UiTags.setting("library-enabled")))
    }

    @Test fun notificationsAreTheirOwnCategoryNotPartOfAppearance() {
        val h = Harness("library")
        h.openSettings(SettingsCategory.APPEARANCE)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_LIST)
        assertFalse(rule.exists("notification-enabled"))
        rule.runOnIdle { h.vm.chooseSettingsPage(SettingsCategory.NOTIFICATIONS) }
        rule.waitForTag("notification-enabled")
    }

    @Test fun speedAndConnectionsIsCollapsedInDownloadsAndOpensOnTap() {
        val h = Harness("library")
        h.openSettings(SettingsCategory.DOWNLOADS)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_LIST)
        rule.scrollListTo(UiTags.SETTINGS_LIST, UiTags.setting("speed"))
        assertFalse("collapsed by default", rule.exists(UiTags.setting("turbo")))
        rule.onNodeWithText("Speed and connections").performClick()
        rule.scrollListTo(UiTags.SETTINGS_LIST, UiTags.setting("turbo"))
        rule.onNodeWithTag(UiTags.setting("turbo")).assertExists()
        rule.onNodeWithTag(UiTags.setting("wifi")).assertExists()
        rule.onNodeWithText("Website sign-in").assertExists()
    }

    @Test fun summariesAreLive() {
        val h = Harness("library")
        h.vm.navigate(Destination.SETTINGS)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_OVERVIEW)
        rule.runOnIdle { h.prefs.chooseTheme(ThemeMode.DARK) }
        rule.onNodeWithText("Dark theme", substring = true).assertExists()
        rule.runOnIdle { h.engine.updateSettings { it.copy(library = it.library.copy(enabled = false)) } }
        rule.onNodeWithText("Off · plain downloader").assertExists()
    }

    @Test fun tapTheSettingsTabAgainReturnsToTheHome() {
        // Settings is a bar tab in downloader-only mode (in full mode it is the top-bar icon, which only the other tabs show).
        val h = Harness("library")
        h.libraryOff()
        h.openSettings("NETWORK")
        h.show(rule)
        rule.waitForTag(UiTags.setting("wifi"))
        rule.onNodeWithTag(UiTags.nav(Destination.SETTINGS)).performClick()
        rule.waitForTag(UiTags.SETTINGS_OVERVIEW)
    }

    // ---- the redirect map ------------------------------------------------------------------------------

    @Test fun everyOldDestinationStillOpensTheRightCategoryAndGroup() {
        val expected = mapOf(
            "GENERAL" to SettingsTarget(SettingsCategory.APPEARANCE),
            "PERFORMANCE" to SettingsTarget(SettingsCategory.DOWNLOADS, SettingsGroups.SPEED),
            "NETWORK" to SettingsTarget(SettingsCategory.DOWNLOADS, SettingsGroups.SPEED),
            "ACCOUNTS" to SettingsTarget(SettingsCategory.DOWNLOADS, SettingsGroups.SIGN_IN),
            "UPDATES" to SettingsTarget(SettingsCategory.ABOUT, SettingsGroups.UPDATES),
            "SUPPORT" to SettingsTarget(SettingsCategory.SUPPORT),
            "HELP" to SettingsTarget(SettingsCategory.HELP),
            "GUIDE" to SettingsTarget(SettingsCategory.HELP, SettingsGroups.HELP),
            "ABOUT" to SettingsTarget(SettingsCategory.ABOUT),
            "LIBRARY" to SettingsTarget(SettingsCategory.LIBRARY),
            "STORAGE" to SettingsTarget(SettingsCategory.STORAGE),
            "DEVICES" to SettingsTarget(SettingsCategory.DEVICES),
            "DOWNLOADS" to SettingsTarget(SettingsCategory.DOWNLOADS),
        )
        for ((old, target) in expected) assertEquals(old, target, SettingsRedirect.resolve(old))
        assertNull(SettingsRedirect.resolve("NOPE"))
        assertNull(SettingsRedirect.resolve(null))
    }

    @Test fun anOldDestinationOpensTheNewPageAndShowsTheGroup() {
        val h = Harness("library")
        h.show(rule)
        rule.runOnIdle { assertTrue(h.vm.openSettings("PERFORMANCE")) }
        assertEquals(Destination.SETTINGS, h.vm.destination)
        assertEquals(SettingsCategory.DOWNLOADS, h.vm.settingsPage)
        rule.waitForTag(UiTags.setting("turbo"))
        rule.runOnIdle { assertTrue(h.vm.openSettings("UPDATES")) }
        assertEquals(SettingsCategory.ABOUT, h.vm.settingsPage)
        rule.waitForTag(UiTags.UPDATES_APP)
    }

    @Test fun savedStateFromAnOlderVersionRestoresToTheNewCategory() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val h = Harness("library")
        val vm = StudioViewModel(app, h.engine, SavedStateHandle(mapOf("settingsPage" to "ACCOUNTS")), h.prefs, h.updates)
        assertEquals(SettingsCategory.DOWNLOADS, vm.settingsPage)
        val general = StudioViewModel(app, h.engine, SavedStateHandle(mapOf("settingsPage" to "GENERAL")), h.prefs, h.updates)
        assertEquals(SettingsCategory.APPEARANCE, general.settingsPage)
        val unknown = StudioViewModel(app, h.engine, SavedStateHandle(mapOf("settingsPage" to "GONE")), h.prefs, h.updates)
        assertNull(unknown.settingsPage)
    }

    // ---- search ----------------------------------------------------------------------------------------

    private fun showSearch(visibility: SettingsVisibility, opened: MutableList<SettingsEntry>) {
        rule.setContent { KeepfinTheme(ThemeMode.LIGHT) { SettingsSearchContent(visibility, PaddingValues(), {}, { opened += it }) } }
    }

    @Test fun searchFindsReachableWhenKeepfinIsClosedAndOpensDevices() {
        val opened = ArrayList<SettingsEntry>()
        showSearch(SettingsVisibility(), opened)
        rule.onNodeWithTag(UiTags.SETTINGS_SEARCH_FIELD).performTextInput("reachable")
        rule.onNodeWithText("Reachable when Keepfin is closed").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.settingsSearchResult(0)).performClick()
        assertEquals(1, opened.size)
        assertEquals(SettingsCategory.DEVICES, opened[0].category)
        assertEquals("devices-keep-reachable", opened[0].tag)
        assertEquals(SettingsGroups.DEVICES, opened[0].group)
    }

    @Test fun searchHidesCategoriesThisBuildDoesNotHave() {
        val opened = ArrayList<SettingsEntry>()
        showSearch(SettingsVisibility(storage = false, devices = false), opened)
        rule.onNodeWithTag(UiTags.SETTINGS_SEARCH_FIELD).performTextInput("reachable")
        rule.onNodeWithTag(UiTags.SETTINGS_SEARCH_EMPTY).assertIsDisplayed()
    }

    @Test fun searchMatchesKeywordsNotOnlyTitles() {
        assertTrue(SettingsIndex.search("oled").any { it.title == "Pure black" })
        assertTrue(SettingsIndex.search("cellular").any { it.title == "Download on Wi-Fi only" })
        assertTrue(SettingsIndex.search("sponsorblock").any { it.title == "Default download options" })
        assertEquals(emptyList<SettingsEntry>(), SettingsIndex.search("   "))
        // every entry's category and group exist
        for (entry in SettingsIndex.entries) assertNotNull(entry.title, SettingsCategory.entries.firstOrNull { it == entry.category })
    }

    @Test fun choosingASearchResultOpensTheCategoryAndScrollsToTheSetting() {
        val h = Harness("library")
        h.vm.navigate(Destination.SETTINGS)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_OVERVIEW)
        rule.onNodeWithTag(UiTags.SETTINGS_SEARCH).performClick()
        rule.waitForTag(UiTags.SETTINGS_SEARCH_FIELD)
        rule.onNodeWithTag(UiTags.SETTINGS_SEARCH_FIELD).performTextInput("turbo")
        rule.onNodeWithTag(UiTags.settingsSearchResult(0)).performClick()
        assertEquals(SettingsCategory.DOWNLOADS, h.vm.settingsPage)
        // the Speed group opens by itself and the row is on screen
        rule.waitForTag(UiTags.setting("turbo"))
        rule.onNodeWithTag(UiTags.setting("turbo")).assertIsDisplayed()
        assertFalse(h.vm.settingsSearching)
    }

    @Test fun searchBackReturnsToTheHome() {
        val h = Harness("library")
        h.vm.navigate(Destination.SETTINGS)
        h.show(rule)
        rule.onNodeWithTag(UiTags.SETTINGS_SEARCH).performClick()
        rule.waitForTag(UiTags.SETTINGS_SEARCH_FIELD)
        rule.runOnIdle { assertTrue(h.vm.back()) }
        rule.waitForTag(UiTags.SETTINGS_OVERVIEW)
    }

    // ---- needs your attention ---------------------------------------------------------------------------

    private fun place(id: String, status: CopyStatus) = CopyCardUi(id, "Card $id", CopyMode.Backup, CopyHoldsUi(), "SD card", status != CopyStatus.DISCONNECTED, status)

    @Test fun nothingIsShownWhenThereIsNothingToFix() {
        assertEquals(emptyList<AttentionItem>(), attentionItems(SettingsFacts()))
        assertEquals(emptyList<AttentionItem>(), attentionItems(SettingsFacts(storage = StorageStatus.Ok("Internal storage > Keepfin", 5_000_000_000, 4), copies = CopiesUi(ready = true, places = listOf(place("a", CopyStatus.UP_TO_DATE))))))
        rule.setContent { KeepfinTheme(ThemeMode.LIGHT) { AttentionBlock(emptyList(), {}) } }
        assertFalse(rule.exists(UiTags.SETTINGS_ATTENTION))
    }

    @Test fun everyRealProblemGetsOneLineWithAFixThatGoesToTheRightPlace() {
        val review = CopyReviewUi("r1", "a", "Card a", "conflict", "Song", "changed on both sides", emptyList())
        val items = attentionItems(
            SettingsFacts(
                storage = StorageStatus.Unavailable("Memory card > Keepfin"),
                copies = CopiesUi(ready = true, places = listOf(place("a", CopyStatus.REFUSED), place("b", CopyStatus.DISCONNECTED)), review = listOf(review)),
                updateVersion = "9.9.9", pairingFrom = "Work PC",
            ),
        )
        assertEquals(listOf("storage", "copy-a", "copy-b", "review", "update", "pairing"), items.map { it.id })
        assertEquals(SettingsCategory.STORAGE, items[0].target.category)
        assertEquals(SettingsCategory.ABOUT, items.first { it.id == "update" }.target.category)
        assertEquals(SettingsCategory.DEVICES, items.first { it.id == "pairing" }.target.category)
        val fixed = ArrayList<String>()
        rule.setContent { KeepfinTheme(ThemeMode.LIGHT) { AttentionBlock(items, onFix = { fixed += it.id }) } }
        rule.onNodeWithTag(UiTags.SETTINGS_ATTENTION).assertIsDisplayed()
        rule.onNodeWithText("Keepfin 9.9.9 is ready to install").assertExists()
        rule.onNodeWithTag(UiTags.settingsAttentionFix("update")).performClick()
        assertEquals(listOf("update"), fixed)
    }

    @Test fun startOverOffersBothResetsAndEachWaitsForDeleteToBeTyped() {
        val h = Harness("library")
        rule.setContent { KeepfinTheme(ThemeMode.LIGHT) { app.keepfin.ui.settings.StartOverGroup(h.vm) } }
        rule.onNodeWithTag(app.keepfin.ui.settings.StartOverTags.RESET).assertHeightIsAtLeast(48.dp)
        for (button in listOf(app.keepfin.ui.settings.StartOverTags.RESET, app.keepfin.ui.settings.StartOverTags.DELETE_ALL)) {
            rule.onNodeWithTag(button).performClick()
            // the confirm button waits for DELETE (any case); nothing happens before
            rule.onNodeWithTag(app.keepfin.ui.settings.StartOverTags.CONFIRM).assertIsNotEnabled()
            rule.onNodeWithTag(app.keepfin.ui.settings.StartOverTags.FIELD).performTextInput("delet")
            rule.onNodeWithTag(app.keepfin.ui.settings.StartOverTags.CONFIRM).assertIsNotEnabled()
            rule.onNodeWithTag(app.keepfin.ui.settings.StartOverTags.FIELD).performTextInput("e")
            rule.onNodeWithTag(app.keepfin.ui.settings.StartOverTags.CONFIRM).assertIsEnabled()
            rule.onNodeWithText("Cancel").performClick()
            rule.onNodeWithTag(app.keepfin.ui.settings.StartOverTags.CONFIRM).assertDoesNotExist()
        }
        rule.onNodeWithText("This cannot be undone", substring = true).assertExists()
    }

    @Test fun helpHoldsTheGuideOnlyAndSupportIsItsOwnScreen() {
        val h = Harness("library")
        h.openSettings(SettingsCategory.HELP)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_LIST)
        rule.onNodeWithTag(UiTags.SETTINGS_GUIDE_ROW).assertExists()
        // Support Keepfin is apart from Help (as on Windows): its own entry, which opens the Support screen
        assertFalse(rule.exists(UiTags.SUPPORT_ROW))
        rule.runOnIdle { h.vm.openSettings(app.keepfin.ui.settings.SettingsTarget(SettingsCategory.SUPPORT)) }
        rule.waitForTag(UiTags.SUPPORT_SCREEN)
        assertFalse(rule.exists(UiTags.UPDATE_CHECK))
        assertFalse(rule.exists(StartOverTags.RESET))
    }

    @Test fun aboutKeepfinIsOnePageWithTheVersionUpdatesAndStartOver() {
        val h = Harness("library")
        h.openSettings(SettingsCategory.ABOUT)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_LIST)
        assertTrue(rule.onAllNodesWithText("Version", substring = true).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("Downloads run on this phone. No account, server or tracking.").assertExists()
        rule.scrollListTo(UiTags.SETTINGS_LIST, UiTags.UPDATE_CHECK)
        rule.onNodeWithTag(UiTags.UPDATE_CHECK).assertExists()
        rule.scrollListTo(UiTags.SETTINGS_LIST, StartOverTags.RESET)
        rule.onNodeWithTag(StartOverTags.RESET).assertExists()
        // no second level: the old "About Keepfin ›" row and its duplicate updates block are gone
        rule.onNodeWithText("release notes and credits", substring = true).assertDoesNotExist()
        rule.onNodeWithText("App updates").assertDoesNotExist()
    }

    @Test fun storageAndCopiesEndsWithStartOver() {
        val h = Harness("library")
        h.openSettings(SettingsCategory.STORAGE)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_LIST)
        rule.scrollListTo(UiTags.SETTINGS_LIST, StartOverTags.RESET)
        rule.onNodeWithTag(StartOverTags.RESET).assertExists()
        rule.onNodeWithTag(StartOverTags.DELETE_ALL).assertExists()
    }

    @Test fun startOverIsFoundBySearchInBothStorageAndAbout() {
        val places = SettingsIndex.search("wipe").filter { it.title == "Start over" }.map { it.category }
        assertEquals(listOf(SettingsCategory.STORAGE, SettingsCategory.ABOUT).sorted(), places.sorted())
    }

    @Test fun onlyAWaitingPairingRequestOfThisPhoneCounts() {
        val asking = PairingUi("p", "sas", "asking", "comparing", "Work PC", "", "123456", null, 0)
        val answering = asking.copy(role = "answering")
        NetRuntime.setIncomingForTests(answering)
        assertEquals("answering+comparing needs a person", app.keepfin.net.PairingNotifier.needsPerson(answering), true)
        assertEquals(false, app.keepfin.net.PairingNotifier.needsPerson(asking))
        assertEquals(false, app.keepfin.net.PairingNotifier.needsPerson(answering.copy(stage = "done")))
    }

    @Test fun anUpdateReadyShowsOnTheHomeAndFixOpensTheUpdates() {
        val h = Harness("library", updates = app.keepfin.ui.preview.FakeUpdates.scene("available"))
        h.vm.navigate(Destination.SETTINGS)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_ATTENTION)
        rule.onNodeWithTag(UiTags.settingsAttentionFix("update")).performClick()
        assertEquals(SettingsCategory.ABOUT, h.vm.settingsPage)
        rule.waitForTag(UiTags.UPDATES_APP)
    }

    @Test fun noAttentionBlockOnTheHomeWhenUpToDate() {
        val h = Harness("library")
        h.vm.navigate(Destination.SETTINGS)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_OVERVIEW)
        assertFalse(rule.exists(UiTags.SETTINGS_ATTENTION))
    }

    @Test fun collectionReviewSelectAllTogglesToSelectNone() {
        val h = Harness("empty")
        h.show(rule)
        rule.onNodeWithTag(UiTags.LINK_FIELD).performTextInput("https://www.youtube.com/playlist?list=PLblender")
        rule.waitForTag(UiTags.COLLECTION)
        rule.waitUntil(10_000) { h.vm.collection?.entries?.size == 60 && h.vm.collection?.discovering == false }
        // A playlist opens with every entry selected, so the control already reads "Select none".
        rule.waitUntil(5_000) { h.vm.collection?.selectedCount == 60 }
        rule.waitForTag(UiTags.COLLECTION_SELECT_NONE)
        rule.onNodeWithText("Select none").assertExists()
        rule.onNodeWithTag(UiTags.COLLECTION_SELECT_NONE).performClick()
        rule.waitUntil(5_000) { h.vm.collection?.selectedCount == 0 }
        rule.waitForTag(UiTags.COLLECTION_SELECT_ALL)
        assertFalse(rule.exists(UiTags.COLLECTION_SELECT_NONE))
        // And Select all brings every entry back.
        rule.onNodeWithTag(UiTags.COLLECTION_SELECT_ALL).performClick()
        rule.waitUntil(5_000) { h.vm.collection?.selectedCount == 60 }
    }
}
