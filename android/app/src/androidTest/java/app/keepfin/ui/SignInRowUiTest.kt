package app.keepfin.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.JobStatus
import app.keepfin.ui.preview.Fixtures
import app.keepfin.ui.settings.SettingsCategory
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A download that used a sign-in a site asked for says so quietly on its row; Settings can switch the automatic use off. */
@RunWith(AndroidJUnit4::class)
class SignInRowUiTest {
    @get:Rule val rule = createComposeRule()

    @After fun tearDown() { StudioEngine.override = null }

    @Test fun theRowSaysSignedInAndAnUnsignedRowDoesNot() {
        val h = Harness("empty")
        h.engine.setJobs(listOf(
            Fixtures.job("signed", "Harbour photos", null, JobStatus.DOWNLOADING, percent = 30f).copy(signedIn = "Signed in"),
            Fixtures.job("plain", "Open clip", null, JobStatus.DOWNLOADING, percent = 30f),
        ))
        h.show(rule)
        rule.waitForTag(UiTags.job("signed"))
        rule.onNodeWithTag(UiTags.job("signed")).assertExists()
        assertTrue(rule.rowSays(UiTags.job("signed"), "Signed in"))
        assertFalse(rule.rowSays(UiTags.job("plain"), "Signed in"))
    }

    @Test fun theSwitchIsOnByDefaultAndTurnsTheAutomaticUseOff() {
        val h = Harness("library")
        assertTrue(h.engine.settings.value.autoSignIn)
        h.openSettings(SettingsCategory.DOWNLOADS)
        h.show(rule)
        rule.waitForTag(UiTags.SETTINGS_LIST)
        rule.onNodeWithTag(UiTags.SETTINGS_LIST).performScrollToNode(hasTestTag(UiTags.AUTO_SIGN_IN_SWITCH))
        rule.onNodeWithText("Use a sign-in when a site asks for one").assertExists()
        rule.onNodeWithTag(UiTags.AUTO_SIGN_IN_SWITCH).assertIsOn().performClick()
        rule.waitUntil(5_000) { !h.engine.settings.value.autoSignIn }
        rule.onNodeWithTag(UiTags.AUTO_SIGN_IN_SWITCH).assertIsOff()
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.rowSays(tag: String, text: String): Boolean =
    onNodeWithTag(tag).fetchSemanticsNode().let { root ->
        fun texts(node: androidx.compose.ui.semantics.SemanticsNode): List<String> =
            (node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()) + node.children.flatMap(::texts)
        texts(root).any { it.contains(text) }
    }
