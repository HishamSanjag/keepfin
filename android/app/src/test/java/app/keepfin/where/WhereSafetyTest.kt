package app.keepfin.where

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sentence at the top of Storage is the shared core's, fed with what the phone knows (GitHub #27). */
class WhereSafetyTest {
    private fun place(id: String) = WherePlace(id, PlaceKind.DRIVE, null, id, "Everything", 0, "up to date", null)

    @Test fun nothingIsSaidBeforeThePlacesAreKnown() = assertNull(WhereSafety.of(WhereFilesUi()))

    @Test fun aLibraryOnlyOnThePhoneAsksForACopy() {
        val ui = WhereFilesUi(true, listOf(place("this")), emptyList(), onePlaceOnly = true, files = 12)
        val safety = WhereSafety.of(ui)!!
        assertEquals("warn", safety.tone)
        assertEquals("Your library is only on this phone", safety.title)
        assertTrue(safety.keepCopy)
        assertEquals(emptySet<String>(), WhereSafety.spacesToKeep(ui))
    }

    @Test fun anEmptyLibraryHasNothingToKeepSafe() {
        val safety = WhereSafety.of(WhereFilesUi(true, listOf(place("this")), emptyList(), files = 0))!!
        assertEquals("Nothing to keep safe yet", safety.title)
        assertFalse(safety.keepCopy)
    }

    @Test fun everyFileInTwoPlacesIsSafe() {
        val safety = WhereSafety.of(WhereFilesUi(true, listOf(place("this"), place("copy")), emptyList(), files = 40))!!
        assertEquals("safe", safety.tone)
        assertEquals("Your library is in 2 places", safety.title)
    }

    @Test fun filesWithOneCopyAreNamedAndTheirSpacesPreselected() {
        val ui = WhereFilesUi(
            true, listOf(place("this"), place("copy")),
            listOf(WhereSingle(SingleKind.SONGS, 3, "this phone", true, setOf("music")), WhereSingle(SingleKind.VIDEOS, 12, "Desk PC", false, setOf("movies"))),
            files = 100,
        )
        val safety = WhereSafety.of(ui)!!
        assertEquals("15 files have only one copy", safety.title)
        assertEquals("3 songs are only in your main storage on this phone. 12 videos are only on Desk PC.", safety.detail)
        assertTrue(safety.keepCopy)
        assertEquals(setOf("music"), WhereSafety.spacesToKeep(ui))
    }
}
