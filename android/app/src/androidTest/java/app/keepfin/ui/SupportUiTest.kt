package app.keepfin.ui

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.ui.components.LocalRowObserver
import app.keepfin.ui.settings.LocalExternalLinks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Collections

@RunWith(AndroidJUnit4::class)
class SupportUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private fun show(h: Harness, opened: MutableList<String>, ok: Boolean = true) {
        rule.setContent {
            CompositionLocalProvider(LocalExternalLinks provides { _: Context, url: String -> opened.add(url); ok }) { StudioApp(h.vm) }
        }
    }

    @Test fun opensFromSettingsAndEachRowOpensItsFixedLink() {
        val h = Harness("default")
        // Settings › Support Keepfin opens the Support screen itself
        h.openSettings("SUPPORT")
        val opened = Collections.synchronizedList(mutableListOf<String>())
        show(h, opened)
        rule.waitForTag(UiTags.SUPPORT_SCREEN)
        rule.onNodeWithTag(UiTags.SUPPORT_PROFILE).performClick()
        rule.onNodeWithTag(UiTags.SUPPORT_SPONSORS).performClick()
        rule.onNodeWithTag(UiTags.SUPPORT_KOFI).performClick()
        rule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(UiTags.SUPPORT_PAYPAL))
        rule.onNodeWithTag(UiTags.SUPPORT_PAYPAL).performClick()
        assertEquals(
            listOf("https://github.com/HishamSanjag", "https://github.com/sponsors/HishamSanjag", "https://ko-fi.com/hishamsanjag", "https://paypal.me/hishamsanjagpaypal"),
            opened.toList(),
        )
    }

    @Test fun helpLinksToSupportAndAMissingBrowserShowsAMessage() {
        val h = Harness("default")
        h.openSettings("SUPPORT")
        val opened = Collections.synchronizedList(mutableListOf<String>())
        show(h, opened, ok = false)
        rule.waitForTag(UiTags.SUPPORT_SCREEN)
        rule.onNodeWithTag(UiTags.SUPPORT_KOFI).performClick()
        rule.waitUntil(5_000) { rule.hasText("No browser is available") }
        assertEquals(listOf("https://ko-fi.com/hishamsanjag"), opened.toList())
    }
}
