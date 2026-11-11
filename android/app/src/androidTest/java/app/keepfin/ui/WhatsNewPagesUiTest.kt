package app.keepfin.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.ui.preview.FakeUpdates
import app.keepfin.ui.updates.WhatsNewPage
import app.keepfin.ui.updates.WhatsNewTags
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WhatsNewPagesUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private val pages = listOf(
        WhatsNewPage("one", "First change", "About the first change."),
        WhatsNewPage("two", "Second change", "About the second change."),
        WhatsNewPage("three", "Third change", "About the third change."),
    )

    private fun open(withPages: List<WhatsNewPage>): Harness {
        val updates = FakeUpdates(notes = "# Keepfin\n\n- A plain note line.").also { it.pages = withPages }
        val h = Harness("default", updates = updates)
        h.show(rule)
        rule.runOnIdle { h.vm.updatesFlow.showWhatsNew() }
        rule.waitForTag(UiTags.WHATS_NEW_SHEET)
        return h
    }

    @Test fun pagesSwipeByNextDotsAndFullListThenGotItCloses() {
        val h = open(pages)
        rule.onNodeWithText("First change").assertExists()
        rule.onNodeWithText("1 of 3").assertExists()
        rule.onNodeWithTag(WhatsNewTags.NEXT).performClick()
        rule.waitUntil(5_000) { rule.hasText("About the second change.") }
        rule.onNodeWithText("2 of 3").assertExists()
        rule.onNodeWithTag(WhatsNewTags.dot(2)).performClick()
        rule.waitUntil(5_000) { rule.hasText("About the third change.") }
        rule.onNodeWithTag(WhatsNewTags.dot(0)).performClick()
        rule.waitUntil(5_000) { rule.hasText("About the first change.") }
        rule.onNodeWithTag(WhatsNewTags.FULL_LIST).performClick()
        rule.waitUntil(5_000) { rule.exists(UiTags.RELEASE_NOTES_BODY) }
        rule.onNodeWithText("Everything in ${app.keepfin.BuildConfig.VERSION_NAME}").assertExists()
        rule.onNodeWithTag(UiTags.WHATS_NEW_GUIDE).assertExists()
        rule.onNodeWithTag("whats-new-done").performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.WHATS_NEW_SHEET) }
        assertNull(h.vm.whatsNew)
    }

    @Test fun withoutPagesTheOldSheetShows() {
        open(emptyList())
        rule.onNodeWithTag(UiTags.RELEASE_NOTES_BODY).assertExists()
        rule.onNodeWithTag(UiTags.WHATS_NEW_GUIDE).assertExists()
        rule.onNodeWithTag(WhatsNewTags.NEXT).assertDoesNotExist()
        rule.onNodeWithTag("whats-new-done").performClick()
        rule.waitUntil(5_000) { !rule.exists(UiTags.WHATS_NEW_SHEET) }
    }
}
