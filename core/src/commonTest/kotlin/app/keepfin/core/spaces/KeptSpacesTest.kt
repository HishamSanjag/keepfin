package app.keepfin.core.spaces

import app.keepfin.core.library.LibraryMapping
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeptSpacesTest {
    @Test fun keptSpacesAreTheThreeFolderBuiltInsAndFavorites() {
        assertEquals(listOf("downloads", "music", "videos", "favorites"), Spaces.KEPT_IDS)
        assertTrue(Spaces.isKept("favorites"))
        assertTrue(Spaces.isKept("music"))
        assertFalse(Spaces.isKept("podcasts"))
        // only the three hold files: Favorites is not a folder built-in
        assertFalse("favorites" in Spaces.BUILTIN_IDS)
    }

    @Test fun missingKeptListsWhatALibraryLacksInKeptOrder() {
        assertEquals(emptyList(), Spaces.missingKept(listOf("downloads", "music", "videos", "favorites")))
        assertEquals(listOf("favorites"), Spaces.missingKept(listOf("downloads", "music", "videos", "podcasts")))
        assertEquals(listOf("downloads", "videos", "favorites"), Spaces.missingKept(listOf("music")))
        assertEquals(Spaces.KEPT_IDS, Spaces.missingKept(emptyList()))
    }

    @Test fun favoritesKeepsTheWireIdItAlwaysHad() {
        // the same value on Windows (gui/desktop/store/mapping.test.ts): two devices that both add Favorites make one space
        assertEquals("b11c64ea-bcff-5c05-80b4-ab6ed166cc38", LibraryMapping.spaceWireId("favorites"))
    }
}
