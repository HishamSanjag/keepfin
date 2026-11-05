package app.keepfin

import android.text.style.URLSpan
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.ui.settings.ReleaseNotes
import app.keepfin.ui.settings.ReleaseNotesDialog
import app.keepfin.ui.theme.KeepfinTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReleaseNotesInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun markdownHasRealFormattingAndOnlyHttpsLinks() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val notes = """
            # Improvements

            - **Automatic saving** and *better previews*.
            - Use `Settings` for updates.

            [Release](https://github.com/HishamSanjag/keepfin)
            [Unsafe](javascript:alert(1))
            [Intent](intent://dangerous#Intent;scheme=test;end)
            [Local](file:///sdcard/private)
        """.trimIndent()
        val text = ReleaseNotes.render(context, notes, 0xFF2855CE.toInt(), 0xFF000000.toInt(), 0xFFEEEEEE.toInt())
        assertFalse("Markdown markers must become styled text", text.toString().contains("**Automatic saving**"))
        assertTrue(text.toString().contains("Automatic saving"))
        val spans = text.getSpans(0, text.length, Any::class.java).map { it.javaClass.simpleName }.toSet()
        listOf("HeadingSpan", "BulletListItemSpan", "StrongEmphasisSpan", "EmphasisSpan", "CodeSpan").forEach { assertTrue(it, it in spans) }
        val links = text.getSpans(0, text.length, URLSpan::class.java)
        assertEquals("Dangerous schemes must not be clickable", 1, links.size)
        assertEquals("https://github.com/HishamSanjag/keepfin", links.single().url)
        assertFalse(ReleaseNotes.safeLink("https://user:password@example.com"))
        assertFalse(ReleaseNotes.safeLink("https://example.com\n"))
        assertFalse(ReleaseNotes.safeLink("https:///missing-host"))
        assertFalse(ReleaseNotes.safeLink("http://example.com"))
    }

    @Test fun notesAreBundledForInstalledVersion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val version = context.packageManager.getPackageInfo(context.packageName, 0).versionName
        assertTrue("Build must embed notes matching the installed version", ReleaseNotes.bundledNotes(context).startsWith("# Keepfin v$version"))
    }

    @Test fun dialogIsDismissibleAndRendersNotes() {
        var shown by androidx.compose.runtime.mutableStateOf(true)
        compose.setContent {
            KeepfinTheme { if (shown) ReleaseNotesDialog("What's new · 9.9.9", "# Heading\n\n- one", onDismiss = { shown = false }) }
        }
        compose.onNodeWithText("What's new · 9.9.9").assertExists()
        compose.onNodeWithTag("release-notes-body").assertExists()
        compose.onNodeWithTag("release-notes-close").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("What's new · 9.9.9").assertDoesNotExist()
    }
}
