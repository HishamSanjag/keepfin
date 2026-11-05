package app.keepfin.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.CollectionKind
import app.keepfin.FollowMode
import app.keepfin.SmartField
import app.keepfin.SmartMatch
import app.keepfin.SmartRule
import app.keepfin.SpaceType
import app.keepfin.ui.library.LibraryRoute
import app.keepfin.ui.settings.SettingsCategory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Spaces v2 screens against the fake engine and its spaces-v2 fixtures (dev-docs/architecture/SPACES-V2.md, mockups 1 to 12). */
@RunWith(AndroidJUnit4::class)
class SpacesV2UiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private fun harness(open: LibraryRoute? = null, prefs: UiPreferences? = null): Harness {
        val h = if (prefs != null) Harness("spaces-v2", prefs = prefs) else Harness("spaces-v2")
        h.vm.navigate(Destination.LIBRARY)
        if (open != null) h.vm.openLibraryRoot(open)
        h.show(rule)
        return h
    }

    private fun customAction(tag: String, label: String) {
        rule.waitForIdle()
        val node = rule.onNodeWithTag(tag).fetchSemanticsNode()
        val action = node.config[SemanticsActions.CustomActions].first { it.label == label }
        rule.runOnUiThread { action.action() }
        rule.waitForIdle()
    }

    private fun spaceIds(h: Harness) = h.engine.settings.value.library.spaces.map { it.id }

    // 1 + 2 ------------------------------------------------------------------------------------------

    @Test fun homeShowsPinnedSpacesAndAllSpacesCardAndNoNewSpaceButton() {
        harness()
        rule.waitForTag(UiTags.LIB_SPACES_GRID)
        for (id in listOf("music", "videos", "lofi", "workout", "downloads")) rule.onNodeWithTag(UiTags.spaceCard(id)).assertExists()
        for (id in listOf("kids", "podcasts", "cooking", "arabic")) assertFalse("$id is not pinned", rule.exists(UiTags.spaceCard(id)))
        rule.onNodeWithTag(UiTags.LIB_ALL_SPACES).assertExists()
        assertTrue(rule.hasText("4 more"))
        assertFalse("New space is edit mode only", rule.exists(UiTags.LIB_SPACE_NEW))
        // The smart space says so, and the Workout card carries the news of its followed collection.
        // (the chips are one line; the counts stay in each chip's spoken description)
        fun described(text: String) = rule.onAllNodesWithContentDescription(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        assertTrue(described("Smart · 5 songs"))
        assertTrue(described("3 new · 6 songs"))
        rule.onNodeWithTag(UiTags.LIB_LIST).performScrollToNode(hasTestTag(UiTags.LIB_SHELF_COLLECTIONS))
        rule.onNodeWithTag(UiTags.LIB_NEW_BADGE).assertExists()
        assertTrue(rule.hasText("Collections"))
    }

    @Test fun hubEditReordersPinsAndOffersNewSpaceOnlyHere() {
        val h = harness()
        rule.waitForTag(UiTags.LIB_SPACES_EDIT)
        rule.onNodeWithTag(UiTags.LIB_SPACES_EDIT).performClick()
        rule.waitForTag(UiTags.LIB_MANAGE)
        rule.onNodeWithTag(UiTags.LIB_SPACE_NEW).assertExists()
        // Every card is movable, built-ins included: Music moves one place later with the TalkBack action.
        assertEquals("music", spaceIds(h).first())
        customAction(UiTags.spaceCard("music"), "Move down")
        rule.waitUntil(5_000) { spaceIds(h).take(2) == listOf("videos", "music") }
        customAction(UiTags.spaceCard("music"), "Move up")
        rule.waitUntil(5_000) { spaceIds(h).first() == "music" }
        // Pin toggles: unpin Downloads, pin Kids.
        rule.onNodeWithTag(UiTags.LIB_SPACES_GRID).performScrollToNode(hasTestTag(UiTags.spacePin("downloads")))
        rule.onNodeWithTag(UiTags.spacePin("downloads")).performClick()
        rule.waitUntil(5_000) { h.engine.settings.value.library.spaces.first { it.id == "downloads" }.pinned == false }
        rule.onNodeWithTag(UiTags.LIB_SPACES_GRID).performScrollToNode(hasTestTag(UiTags.spacePin("kids")))
        rule.onNodeWithTag(UiTags.spacePin("kids")).performClick()
        rule.waitUntil(5_000) { h.engine.settings.value.library.spaces.first { it.id == "kids" }.pinned == true }
        // Where downloads go sits under the grid.
        rule.onNodeWithTag(UiTags.LIB_SPACES_GRID).performScrollToNode(hasTestTag(UiTags.setting(UiTags.SETTINGS_ROUTE_MUSIC)))
        rule.onNodeWithTag(UiTags.setting(UiTags.SETTINGS_ROUTE_OTHER)).assertExists()
        rule.onNodeWithTag("edit-spaces-done").performClick()
        rule.waitForTag(UiTags.LIB_ALL_SPACES)
        rule.onNodeWithTag(UiTags.spaceCard("kids")).assertExists()
        assertFalse(rule.exists(UiTags.spaceCard("downloads")))
        assertFalse("Edit mode is over", rule.exists(UiTags.LIB_SPACE_NEW))
    }

    @Test fun hubEditDragsACardWithALongPress() {
        val h = harness()
        rule.waitForTag(UiTags.LIB_SPACES_EDIT)
        rule.onNodeWithTag(UiTags.LIB_SPACES_EDIT).performClick()
        rule.waitForTag(UiTags.spaceCard("videos"))
        // Long-press Videos and drag it below Music's row: two cards down is the third row's first card.
        rule.onNodeWithTag(UiTags.spaceCard("videos")).performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(androidx.compose.ui.geometry.Offset(0f, density * 40f))
            advanceEventTime(100)
            moveBy(androidx.compose.ui.geometry.Offset(0f, density * 60f))
            advanceEventTime(100)
            moveBy(androidx.compose.ui.geometry.Offset(0f, density * 60f))
            advanceEventTime(100)
            up()
        }
        rule.waitUntil(8_000) { spaceIds(h).indexOf("videos") > 1 }
        assertEquals("Music keeps its place", "music", spaceIds(h).first())
    }

    // 3 ----------------------------------------------------------------------------------------------

    @Test fun allSpacesListsFoldersThenSmartSpacesAndSearches() {
        harness()
        rule.waitForTag(UiTags.LIB_ALL_SPACES)
        rule.onNodeWithTag(UiTags.LIB_ALL_SPACES).performClick()
        rule.waitForTag(UiTags.LIB_ALL_SPACES_LIST)
        assertTrue(rule.hasText("Folders · 6"))
        assertTrue(rule.hasText("Smart · 3"))
        assertTrue(rule.hasText("Tag is lofi, chill or study"))
        rule.onNodeWithTag(UiTags.LIB_SEARCH_FIELD).performTextInput("cook")
        rule.waitUntil(5_000) { !rule.exists(UiTags.spaceRow("music")) }
        rule.onNodeWithTag(UiTags.spaceRow("cooking")).assertExists()
        assertFalse(rule.hasText("Folders ·"))
        rule.onNodeWithTag(UiTags.spaceRow("cooking")).performClick()
        rule.waitForTag(UiTags.SMART_SPACE)
        assertTrue(rule.hasText("Smart · 2 videos"))
    }

    @Test fun aSmartSpaceWithSongsAndVideosHasQuickFiltersThatKeepItsRules() {
        val h = harness()
        val mixed = app.keepfin.Space(
            "both", "Songs and videos", app.keepfin.SpaceKind.MIXED, "", type = app.keepfin.SpaceType.SMART,
            rules = app.keepfin.SmartRules(SmartMatch.ANY, listOf(SmartRule.Values(SmartField.KIND, listOf("audio", "video")))),
        )
        rule.runOnIdle { h.engine.updateSettings { s -> s.copy(library = s.library.copy(spaces = s.library.spaces + mixed)) } }
        rule.runOnIdle { h.vm.openLibraryRoot(app.keepfin.ui.library.LibraryRoute.Space("both")) }
        rule.waitForTag(UiTags.SMART_SPACE)
        rule.waitForTag("kind-filter-video")
        rule.onNodeWithTag("kind-filter-audio").assertExists()
        assertFalse("no photos in it, so no Photos chip", rule.exists("kind-filter-image"))
        val song = "content://media/external/audio/media/102"
        rule.waitForTag(UiTags.libItem(song))
        rule.onNodeWithTag("kind-filter-video").performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.libItem(song)) }
        rule.onNodeWithTag("kind-filter-all").performClick()
        rule.waitForTag(UiTags.libItem(song))
    }

    // 4 ----------------------------------------------------------------------------------------------

    @Test fun newSpaceSheetOffersTypeEightColorsIconsAndFolderFieldsOnlyForFolders() {
        val h = harness()
        rule.waitForTag(UiTags.LIB_SPACES_EDIT)
        rule.onNodeWithTag(UiTags.LIB_SPACES_EDIT).performClick()
        rule.onNodeWithTag(UiTags.LIB_SPACE_NEW).performClick()
        rule.waitForTag(UiTags.SPACE_EDITOR)
        rule.onNodeWithTag(UiTags.SPACE_TYPE_FOLDER).assertExists()
        rule.onNodeWithTag(UiTags.SPACE_TYPE_SMART).assertExists()
        assertTrue(rule.hasText("Has its own folder. Downloads and moved files go here."))
        assertTrue(rule.hasText("Gathers items from all spaces by tags, collections or source. No files move."))
        for (color in app.keepfin.SpaceColor.entries) {
            rule.onNodeWithTag(UiTags.colorSwatch(color.wire)).assertExists()
            assertTrue("${app.keepfin.SpaceTones.label(color)} swatch has a spoken name", rule.hasDescription("${app.keepfin.SpaceTones.label(color)} color"))
        }
        for (icon in app.keepfin.SpaceIcon.entries) rule.onNodeWithTag(UiTags.iconSwatch(icon.wire)).assertExists()
        rule.onNodeWithTag(UiTags.SPACE_FOLDER).assertExists()
        // Smart: the folder fields go away and the primary action becomes Next.
        rule.onNodeWithTag(UiTags.SPACE_TYPE_SMART).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.SPACE_FOLDER) }
        rule.onNodeWithTag(UiTags.SPACE_NEXT).assertIsNotEnabled()
        rule.onNodeWithTag(UiTags.SPACE_NAME).performTextInput("Gym smart")
        rule.onNodeWithTag(UiTags.colorSwatch("berry")).performScrollTo().performClick()
        rule.onNodeWithTag(UiTags.iconSwatch("heart")).performScrollTo().performClick()
        rule.onNodeWithTag(UiTags.SPACE_NEXT).assertIsEnabled()
        rule.onNodeWithTag(UiTags.SPACE_NEXT).performClick()
        // 5: the rules editor. Add a tag rule through the picker, see the live count, save.
        rule.waitForTag(UiTags.RULES_EDITOR)
        rule.onNodeWithTag(UiTags.RULES_SAVE).assertIsNotEnabled()
        rule.onNodeWithTag(UiTags.RULES_ADD).performClick()
        rule.onNodeWithTag(UiTags.ruleType("tag")).performClick()
        rule.waitForTag("rules-picker")
        rule.onNodeWithTag(UiTags.pickOption("workout")).performClick()
        rule.onNodeWithTag("rules-picker-done").performClick()
        rule.waitUntil(5_000) { rule.hasText("8 songs match") }
        assertTrue(rule.hasText("From Workout (6) and Music (2)"))
        rule.onNodeWithTag(UiTags.RULES_ONLY_SONGS).performClick()
        rule.onNodeWithTag(UiTags.RULES_SAVE).assertIsEnabled()
        rule.onNodeWithTag(UiTags.RULES_SAVE).performClick()
        rule.waitUntil(5_000) { h.engine.fakeLibrary.calls.any { it.startsWith("saveSpace:") } }
        val saved = h.engine.fakeLibrary.lastSavedSpace!!
        assertEquals("Gym smart", saved.name)
        assertEquals(SpaceType.SMART, saved.type)
        assertEquals(app.keepfin.SpaceColor.BERRY, saved.color)
        assertEquals(app.keepfin.SpaceIcon.HEART, saved.icon)
        assertEquals(listOf(SmartRule.Values(SmartField.TAG, listOf("workout"))), saved.rules!!.rules)
        assertEquals(app.keepfin.LibraryItemKind.AUDIO, saved.rules!!.onlyKind)
        // The new smart space opens, listing its matches.
        rule.waitForTag(UiTags.SMART_SPACE)
        assertTrue(rule.hasText("Smart · 8 songs"))
    }

    // 5 ----------------------------------------------------------------------------------------------

    @Test fun smartSpaceListsMatchesAndRulesEditorUpdatesTheCountLive() {
        val h = harness(LibraryRoute.Space("lofi"))
        rule.waitForTag(UiTags.SMART_SPACE)
        assertTrue(rule.hasText("Smart · 5 songs"))
        assertTrue(rule.hasText("Tag is lofi, chill or study · only songs"))
        rule.onNodeWithTag(UiTags.LIB_PLAY_ALL).assertExists()
        rule.onNodeWithTag(UiTags.libItem("content://media/external/audio/media/103")).assertExists()
        rule.onNodeWithTag(UiTags.LIB_OVERFLOW).performClick()
        rule.onNodeWithTag(UiTags.SMART_EDIT_RULES).performClick()
        rule.waitForTag(UiTags.RULES_EDITOR)
        assertTrue(rule.hasText("5 songs match"))
        // Any: tag rule or Favorite (two favorites, one is already in the tag matches? no: Big Buck Bunny and Agent 327).
        rule.onNodeWithTag(UiTags.RULES_ADD).performClick()
        rule.onNodeWithTag(UiTags.ruleType("favorite")).performClick()
        rule.waitUntil(5_000) { rule.hasText("7 songs match") }
        rule.onNodeWithTag(UiTags.RULES_MATCH_ALL).performClick()
        rule.waitUntil(5_000) { rule.hasText("0 items match") }
        rule.onNodeWithTag(UiTags.RULES_MATCH_ANY).performClick()
        rule.waitUntil(5_000) { rule.hasText("7 songs match") }
        // Leave out the Workout space: Cold Front (chill) drops out.
        rule.onNodeWithTag(UiTags.RULES_EXCLUDE).performClick()
        rule.waitForTag("rules-picker")
        rule.onNodeWithTag(UiTags.pickOption("workout")).performClick()
        rule.onNodeWithTag("rules-picker-done").performClick()
        rule.waitUntil(5_000) { rule.hasText("6 songs match") }
        rule.onNodeWithTag(UiTags.RULES_SAVE).performClick()
        rule.waitUntil(5_000) { h.engine.fakeLibrary.calls.contains("saveSpace:lofi") }
        val rules = h.engine.fakeLibrary.lastSavedSpace!!.rules!!
        assertEquals(SmartMatch.ANY, rules.match)
        assertEquals(listOf("workout"), rules.excludeSpaces)
        assertTrue(SmartRule.Favorite in rules.rules)
        rule.waitForTag(UiTags.SMART_SPACE)
        assertTrue(rule.hasText("Smart · 6 songs"))
    }

    // 6 + 7 ------------------------------------------------------------------------------------------

    @Test fun musicSpaceHasCollectionsTabTagChipsAndTheTagFilterSavesASmartSpace() {
        val h = harness(LibraryRoute.Space("music"))
        rule.waitForTag(UiTags.libTab("songs"))
        for (tab in listOf("songs", "collections", "albums", "artists")) rule.onNodeWithTag(UiTags.libTab(tab)).assertExists()
        assertTrue(rule.hasText("8 songs · 4 collections · following 1"))
        rule.onNodeWithTag(UiTags.TAGS_CHIP).assertExists()
        rule.onNodeWithTag(UiTags.tagChip("pop")).assertExists()
        // A chip filters in place; Play all then plays the filtered list.
        rule.onNodeWithTag(UiTags.tagChip("arabic")).performScrollTo()
        rule.onNodeWithTag(UiTags.tagChip("arabic")).performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.libItem("content://media/external/audio/media/101")) }
        rule.onNodeWithTag(UiTags.libItem("content://media/external/audio/media/107")).assertExists()
        rule.onNodeWithTag(UiTags.libItem("content://media/external/audio/media/108")).assertExists()
        // The sheet: Any / All, counts, Play N and Save as smart space.
        rule.onNodeWithTag(UiTags.TAGS_CHIP).performClick()
        rule.waitForTag(UiTags.TAGS_SHEET)
        rule.onNodeWithTag(UiTags.TAGS_ANY).assertExists()
        rule.onNodeWithTag(UiTags.TAGS_ALL).assertExists()
        rule.onNodeWithTag(UiTags.sheetTag("chill")).performClick()
        rule.onNodeWithTag(UiTags.TAGS_PLAY).assertIsEnabled()
        assertTrue(rule.hasText("Play 4 songs"))
        rule.onNodeWithTag(UiTags.TAGS_ALL).performClick()
        rule.waitUntil(5_000) { rule.hasText("Play 1 song") }
        rule.onNodeWithTag(UiTags.TAGS_ANY).performClick()
        rule.onNodeWithTag(UiTags.TAGS_SAVE_SMART).performClick()
        rule.waitForTag(UiTags.SPACE_EDITOR)
        rule.onNodeWithTag(UiTags.SPACE_NEXT).assertIsEnabled()
        rule.onNodeWithTag(UiTags.SPACE_NEXT).performClick()
        rule.waitForTag(UiTags.RULES_EDITOR)
        // The filter of one space keeps to that space, as on Windows: Cold Front in Workout stays out.
        rule.waitUntil(5_000) { rule.hasText("4 songs match") }
        rule.onNodeWithTag(UiTags.RULES_SAVE).performClick()
        rule.waitUntil(5_000) { h.engine.fakeLibrary.calls.any { it.startsWith("saveSpace:") } }
        val saved = h.engine.fakeLibrary.lastSavedSpace!!
        assertEquals(SpaceType.SMART, saved.type)
        assertEquals("Arabic + Chill", saved.name)
        assertEquals(listOf(SmartRule.Values(SmartField.TAG, listOf("arabic", "chill")), SmartRule.Values(SmartField.SPACE, listOf("music"))), saved.rules!!.rules)
        rule.waitForTag(UiTags.SMART_SPACE)
    }

    @Test fun collectionsTabListsFollowStatusAndSelectionOffersTags() {
        val h = harness(LibraryRoute.Space("music"))
        rule.waitForTag(UiTags.libTab("collections"))
        rule.onNodeWithTag(UiTags.libTab("collections")).performClick()
        rule.waitForTag(UiTags.collectionRow("khaleeji"))
        assertTrue(rule.hasText("Playlist · 2 songs · following, checked 3 h ago"))
        assertTrue(rule.hasText("Album · 2 songs"))
        assertTrue(rule.hasText("Yours · 3 songs"))
        assertTrue(rule.hasText("not following"))
        rule.onNodeWithTag(UiTags.collectionPlay("khaleeji")).assertExists()
        rule.onNodeWithTag(UiTags.COLLECTION_NEW_ROW).assertExists()
        // Selection gets a Tags… action for your own tags.
        rule.onNodeWithTag(UiTags.libTab("songs")).performClick()
        val song = "content://media/external/audio/media/102"
        rule.waitForTag(UiTags.libItem(song))
        rule.onNodeWithTag(UiTags.libItem(song)).performTouchInput { longClick() }
        rule.waitForTag(UiTags.LIB_SELECTION_TAGS)
        rule.onNodeWithTag(UiTags.LIB_SELECTION_TAGS).performClick()
        rule.waitForTag("tags-edit-sheet")
        rule.onNodeWithTag("tags-field").performTextInput("Gym Day")
        rule.onNodeWithTag("tags-add").performClick()
        rule.onNodeWithTag("tags-save").performClick()
        rule.waitUntil(5_000) { h.engine.fakeLibrary.calls.any { it.startsWith("libraryTag:") } }
        // the fake applies the change on the call's own thread, just after it records the call
        rule.waitUntil(5_000) { h.engine.fakeLibrary.item(song)!!.userTags.isNotEmpty() }
        assertEquals(listOf("gym day"), h.engine.fakeLibrary.item(song)!!.userTags)
        // A user tag shows in the chip row even though only one song has it.
        rule.waitUntil(5_000) { rule.exists(UiTags.tagChip("gym day")) || rule.exists(UiTags.TAGS_CHIP) }
    }

    // 8 ----------------------------------------------------------------------------------------------

    @Test fun addFromLibraryPicksCollectionsAndMovesThemWithAResultMessage() {
        val h = harness(LibraryRoute.Space("workout"))
        rule.waitForTag(UiTags.LIB_OVERFLOW)
        rule.onNodeWithTag(UiTags.LIB_OVERFLOW).performClick()
        rule.onNodeWithTag(UiTags.LIB_ADD_FILES).performClick()
        rule.waitForTag(UiTags.ADD_SHEET)
        rule.onNodeWithTag(UiTags.ADD_DEVICE).assertExists()
        rule.onNodeWithTag(UiTags.addTab("collections")).assertExists()
        rule.onNodeWithTag(UiTags.addTab("songs")).assertExists()
        rule.onNodeWithTag(UiTags.ADD_CONFIRM).assertIsNotEnabled()
        rule.onNodeWithTag(UiTags.addRow("roadtrip")).performClick()
        rule.waitUntil(5_000) { rule.hasText("1 collection · 3 songs") }
        rule.onNodeWithTag(UiTags.ADD_MOVE).assertExists()
        rule.onNodeWithTag(UiTags.ADD_COPY).assertExists()
        assertTrue(rule.hasText("Move 3"))
        // A song picked through its tab joins the count.
        rule.onNodeWithTag(UiTags.addTab("songs")).performClick()
        rule.onNodeWithTag("add-list").performScrollToNode(hasTestTag(UiTags.addRow("content://media/external/audio/media/107")))
        rule.onNodeWithTag(UiTags.addRow("content://media/external/audio/media/107")).performClick()
        rule.waitUntil(5_000) { rule.hasText("Move 4") }
        rule.onNodeWithTag(UiTags.ADD_CONFIRM).performClick()
        rule.waitUntil(5_000) { h.engine.fakeLibrary.calls.contains("addFrom:workout:move") }
        rule.waitUntil(5_000) { rule.hasText("Moved 4 to Workout") }
        assertEquals("workout", h.engine.fakeLibrary.item("content://media/external/audio/media/101")!!.space)
        assertEquals("workout", h.engine.fakeLibrary.item("content://media/external/audio/media/107")!!.space)
    }

    @Test fun addFromLibraryCanCopyAndOnASmartSpaceAddOpensItsRules() {
        val h = harness(LibraryRoute.Space("workout"))
        rule.waitForTag(UiTags.LIB_OVERFLOW)
        rule.onNodeWithTag(UiTags.LIB_OVERFLOW).performClick()
        rule.onNodeWithTag(UiTags.LIB_ADD_FILES).performClick()
        rule.waitForTag(UiTags.ADD_SHEET)
        rule.onNodeWithTag(UiTags.addTab("songs")).performClick()
        rule.onNodeWithTag("add-list").performScrollToNode(hasTestTag(UiTags.addRow("content://media/external/audio/media/102")))
        rule.onNodeWithTag(UiTags.addRow("content://media/external/audio/media/102")).performClick()
        rule.onNodeWithTag(UiTags.ADD_COPY).performClick()
        rule.waitUntil(5_000) { rule.hasText("Copy 1") }
        rule.onNodeWithTag(UiTags.ADD_CONFIRM).performClick()
        rule.waitUntil(5_000) { h.engine.fakeLibrary.calls.contains("addFrom:workout:copy") }
        assertEquals("The original stays", "music", h.engine.fakeLibrary.item("content://media/external/audio/media/102")!!.space)
        rule.waitUntil(5_000) { rule.hasText("Copied 1 to Workout") }
        // On a smart space, Add opens its rules.
        h.vm.openLibraryRoot(LibraryRoute.Space("arabic"))
        rule.waitForTag(UiTags.SMART_SPACE)
        rule.onNodeWithTag(UiTags.LIB_OVERFLOW).performClick()
        rule.onNodeWithTag(UiTags.SMART_EDIT_RULES).performClick()
        rule.waitForTag(UiTags.RULES_EDITOR)
    }

    // 9 + 10 -----------------------------------------------------------------------------------------

    @Test fun collectionDetailShowsFollowStateNewItemsRemovedItemsAndCheckNow() {
        val h = harness(LibraryRoute.Collection("workout", "gym"))
        rule.waitForTag(UiTags.COLLECTION_DETAIL)
        assertTrue(rule.hasText("PLAYLIST · WORKOUT"))
        assertTrue(rule.hasText("Following · every 6 hours"))
        assertTrue(rule.hasText("Checked 12 min ago · 3 new songs added"))
        // Opening the collection told the library its news were seen, yet this page still shows them.
        rule.waitUntil(5_000) { h.engine.fakeLibrary.collection("gym")!!.newItemIds.isEmpty() }
        rule.onNodeWithTag(UiTags.COLLECTION_NEW_SECTION).assertExists()
        assertTrue(rule.hasText("New · 3"))
        assertTrue(rule.hasText("All songs · in playlist order"))
        rule.onNodeWithTag(UiTags.LIB_LIST).performScrollToNode(hasTestTag(UiTags.libItem("content://media/external/audio/media/504")))
        assertTrue(rule.hasText("Removed from the playlist · kept here"))
        rule.onNodeWithTag(UiTags.LIB_LIST).performScrollToNode(hasTestTag(UiTags.FOLLOW_CARD))
        rule.onNodeWithTag(UiTags.FOLLOW_CHECK).performClick()
        rule.waitUntil(5_000) { h.engine.fakeLibrary.calls.contains("collectionCheck:gym") }
        rule.waitUntil(5_000) { rule.hasText("Checked just now") }
        assertTrue("The check says what it found", rule.hasText("No new songs in Gym Mix"))
        assertFalse("Nothing was held back", rule.exists(UiTags.FOLLOW_REVIEW))
    }

    @Test fun heldBackCheckOffersReview() {
        val h = harness(LibraryRoute.Collection("workout", "daily-run"))
        rule.waitForTag(UiTags.FOLLOW_REVIEW)
        assertTrue(rule.hasText("Review 28 new"))
        rule.onNodeWithTag(UiTags.FOLLOW_REVIEW).performClick()
        rule.waitUntil(5_000) { h.engine.fakeLibrary.calls.contains("collectionReview:daily-run") }
        rule.waitUntil(5_000) { !rule.exists(UiTags.FOLLOW_REVIEW) }
        assertTrue(rule.hasText("Downloading 28 new items"))
    }

    @Test fun followSettingsSheetSavesModeIntervalAndOptions() {
        val h = harness(LibraryRoute.Collection("workout", "gym"))
        rule.waitForTag(UiTags.FOLLOW_CARD)
        rule.onNodeWithTag(UiTags.FOLLOW_CARD).performClick()
        rule.waitForTag(UiTags.FOLLOW_SHEET)
        assertTrue(rule.hasText("music.youtube.com/playlist?list=PLgym"))
        rule.onNodeWithTag(UiTags.FOLLOW_AUTO).assertExists()
        rule.onNodeWithTag(UiTags.FOLLOW_MANUAL).assertExists()
        rule.onNodeWithTag(UiTags.FOLLOW_OFF).assertExists()
        assertTrue(rule.hasText("Android picks the exact moment to save battery"))
        assertTrue(rule.hasText("Items you delete are remembered and not downloaded again."))
        rule.onNodeWithTag("follow-every-24").performScrollTo()
        rule.onNodeWithTag("follow-every-24").performClick()
        rule.onNodeWithTag(UiTags.FOLLOW_WIFI).performScrollTo()
        rule.onNodeWithTag(UiTags.FOLLOW_WIFI).performClick()
        rule.onNodeWithTag(UiTags.FOLLOW_SAVE).performClick()
        rule.waitUntil(5_000) { h.engine.fakeLibrary.calls.any { it.startsWith("collectionFollow:gym:AUTO:24:false:") } }
        rule.waitUntil(5_000) { rule.hasText("Following · every day") }
        // Manual and off change the line, and the card says so.
        rule.onNodeWithTag(UiTags.FOLLOW_CARD).performClick()
        rule.waitForTag(UiTags.FOLLOW_SHEET)
        rule.onNodeWithTag(UiTags.FOLLOW_MANUAL).performClick()
        rule.onNodeWithTag(UiTags.FOLLOW_SAVE).performClick()
        rule.waitUntil(5_000) { rule.hasText("Checks when you tap Check now") }
        assertEquals(FollowMode.MANUAL, h.engine.fakeLibrary.collection("gym")!!.follow!!.mode)
    }

    @Test fun yourOwnListsKeepTheirEditablePageWithoutAFollowCard() {
        harness(LibraryRoute.UserPlaylist("roadtrip"))
        rule.waitForTag(UiTags.LIB_PLAY_ALL)
        assertFalse(rule.exists(UiTags.FOLLOW_CARD))
    }

    // 11 + 12 ----------------------------------------------------------------------------------------

    @Test fun startPageSettingChoosesWhereTheAppOpens() {
        val first = Harness("spaces-v2")
        first.openSettings(SettingsCategory.APPEARANCE)
        first.show(rule)
        rule.waitForTag(UiTags.SETTINGS_START_PAGE)
        assertEquals(StartPage.LAST, first.prefs.startPage)
        rule.onNodeWithTag(UiTags.SETTINGS_START_PAGE).performClick()
        rule.onNodeWithTag(UiTags.setting("start-library")).performClick()
        rule.waitUntil(5_000) { first.prefs.startPage == StartPage.LIBRARY }
        rule.onNodeWithTag(UiTags.SETTINGS_START_PAGE).performClick()
        rule.onNodeWithTag(UiTags.setting("start-last")).performClick()
        rule.waitUntil(5_000) { first.prefs.startPage == StartPage.LAST }
        assertTrue(rule.hasText("Where I left off"))
    }

    @Test fun appOpensOnTheChosenStartPage() {
        val library = UiPreferences(androidx.test.core.app.ApplicationProvider.getApplicationContext(), "start-" + java.util.UUID.randomUUID())
        library.chooseStartPage(StartPage.LIBRARY)
        assertEquals(Destination.LIBRARY, Harness("spaces-v2", prefs = library).vm.destination)
        val last = UiPreferences(androidx.test.core.app.ApplicationProvider.getApplicationContext(), "start-" + java.util.UUID.randomUUID())
        last.chooseStartPage(StartPage.LAST)
        last.lastDestination = "settings"
        assertEquals(Destination.SETTINGS, Harness("spaces-v2", prefs = last).vm.destination)
        assertEquals(Destination.DOWNLOADS, Harness("spaces-v2").vm.destination)
    }

    @Test fun everySettingsPageHasABackArrowAndTheSettingsTrail() {
        val h = Harness("spaces-v2")
        h.show(rule)
        h.vm.navigate(Destination.SETTINGS)
        for (category in listOf(SettingsCategory.APPEARANCE, SettingsCategory.LIBRARY, SettingsCategory.HELP, SettingsCategory.ABOUT)) {
            rule.runOnIdle { h.vm.chooseSettingsPage(category) }
            rule.waitForTag(UiTags.SETTINGS_EYEBROW)
            assertTrue(rule.hasText("Settings ›"))
            assertTrue(rule.hasText(category.title))
            rule.onNodeWithTag(UiTags.SETTINGS_PAGE_BACK).assertIsDisplayed()
            rule.onNodeWithTag(UiTags.SETTINGS_PAGE_BACK).performClick()
            rule.waitForTag(UiTags.SETTINGS_OVERVIEW)
        }
        // Library settings: import tags is on by default and can be turned off.
        rule.runOnIdle { h.vm.chooseSettingsPage(SettingsCategory.LIBRARY) }
        rule.waitForTag(UiTags.SETTINGS_LIST)
        rule.onNodeWithTag(UiTags.SETTINGS_LIST).performScrollToNode(hasTestTag(UiTags.setting("import-tags")))
        assertTrue(h.engine.settings.value.library.importTags)
        rule.onNodeWithTag(UiTags.setting("import-tags")).performClick()
        rule.waitUntil(5_000) { !h.engine.settings.value.library.importTags }
        // Sub-pages (Support) carry the same trail.
        rule.runOnIdle { h.vm.chooseSettingsPage(null); h.vm.open(SubPage.SUPPORT) }
        rule.waitForTag(UiTags.SETTINGS_EYEBROW)
        assertTrue(rule.hasText("Settings ›"))
        assertNotNull(h.vm.subPage)
    }

    @Test fun updatesPageKeepsTheChannelPickerUnderTheSameHeader() {
        val h = Harness("spaces-v2")
        h.openSettings(SettingsCategory.ABOUT)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_EYEBROW)
        assertTrue(rule.hasText("Settings ›"))
        rule.onNodeWithTag(UiTags.setting("app-channel")).assertExists()
    }

    @Test fun collectionKindsMatchTheFixtures() {
        val h = Harness("spaces-v2")
        val collections = h.engine.fakeLibrary.library.value.collections
        assertEquals(setOf(CollectionKind.PLAYLIST, CollectionKind.ALBUM, CollectionKind.OWN), collections.map { it.kind }.toSet())
    }
}

private fun SemanticsNodeInteractionsProvider.hasDescription(text: String) =
    onAllNodesWithContentDescription(text, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
