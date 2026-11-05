package app.keepfin.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.ui.library.LibraryRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Favorites is a kept space like Downloads, Music and Videos: a new library has it right after Videos, the Library home shows it,
 * it can be renamed but never removed, and with nothing hearted it says how to fill it. Pictures for review are taken only with
 * `-Pandroid.testInstrumentationRunnerArguments.screenshots=true` (pictures in /data/local/tmp on the device).
 */
@RunWith(AndroidJUnit4::class)
class FavoritesKeptUiTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private fun shot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("screenshots") != "true") return
        rule.waitForIdle()
        Thread.sleep(900)
        // screencap into /data/local/tmp: it survives the app being uninstalled after the run (adb pull /data/local/tmp/<name>.png)
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /data/local/tmp/$name.png").close()
        Thread.sleep(600)
    }

    @Test fun aNewLibraryShowsFavoritesWithTheOthersAndOffersNoRemove() {
        val h = Harness("empty")
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.waitForTag(UiTags.LIB_SPACES_GRID)
        assertEquals(listOf("downloads", "music", "videos", "favorites"), h.engine.settings.value.library.spaces.map { it.id })
        for (id in listOf("downloads", "music", "videos", "favorites")) rule.onNodeWithTag(UiTags.spaceCard(id)).assertExists()
        shot("fav-01-library-home")
        // it is a smart space with a heart and its own rules; opening it with nothing hearted explains itself
        // (the fake library has two hearted songs: take the hearts off first)
        h.vm.lib.browse.top(app.keepfin.ItemQuery(favorite = true), 100).forEach { h.vm.lib.setFavorite(it.id, false) }
        h.vm.openLibraryRoot(LibraryRoute.Space("favorites"))
        rule.waitForTag(UiTags.SMART_SPACE)
        shot("fav-02-favorites-state")
        rule.waitUntil(8_000) { rule.hasText("No favorites yet") }
        assertTrue(rule.hasText("No favorites yet"))
        assertTrue(rule.hasText("Tap the heart on any song or video to add it here."))
        shot("fav-02-favorites-empty")
        // the menu offers Edit but no Remove, for Favorites as for Music
        rule.onNodeWithTag(UiTags.LIB_OVERFLOW).performClick()
        rule.waitForTag(UiTags.LIB_SPACE_MENU_EDIT)
        assertFalse("a kept space cannot be removed", rule.exists(UiTags.LIB_SPACE_MENU_REMOVE))
        // and removing it any other way is refused
        h.vm.spaces.removeSpace("favorites", "Favorites")
        h.vm.spaces.removeSpace("music", "Music")
        rule.waitForIdle()
        assertEquals(listOf("downloads", "music", "videos", "favorites"), h.engine.settings.value.library.spaces.map { it.id })
    }
}
