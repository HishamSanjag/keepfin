package app.keepfin.move

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules of a move and the words of bringing spaces in. */
class MovePlanningTest {
    @Test fun aFolderInsideTheCurrentOneOrAboveItIsRefusedWithThePcWords() {
        assertEquals("The new place cannot be inside your Keepfin folder.", MovePlanning.problem("primary:Keepfin", "primary:Keepfin/Sub", false))
        assertEquals("The new place cannot be inside your Keepfin folder.", MovePlanning.problem("primary:Keepfin", "primary:Keepfin", false))
        assertEquals("Choose a folder that is not above your Keepfin folder.", MovePlanning.problem("primary:Stuff/Keepfin", "primary:Stuff", false))
    }

    @Test fun aFolderThatIsNotEmptyOrIsAPlaceAndroidForbidsIsRefused() {
        assertEquals(MovePlanning.NOT_EMPTY, MovePlanning.problem("primary:Keepfin", "primary:Other", true))
        assertEquals("Choose an empty folder, or a new one.", MovePlanning.NOT_EMPTY)
        assertNotNull(MovePlanning.problem("primary:Keepfin", "primary:", false))
        assertNotNull(MovePlanning.problem("primary:Keepfin", "primary:Download", false))
        assertNotNull(MovePlanning.problem("primary:Keepfin", "primary:Android/data/x", false))
    }

    @Test fun anEmptyFolderOnAnotherVolumeIsFine() {
        assertNull(MovePlanning.problem("primary:Keepfin", "1234-ABCD:Keepfin", false))
        assertNull(MovePlanning.problem("primary:Keepfin", "primary:Moved/Keepfin", false))
        // a name that only starts like the current one is another folder
        assertNull(MovePlanning.problem("primary:Keepfin", "primary:Keepfin2", false))
    }

    @Test fun theFilesMustFitWithRoomToSpare() {
        assertTrue(MovePlanning.fits(1000, null))
        assertTrue(MovePlanning.fits(1000, 1000 + MovePlanning.MARGIN_BYTES))
        assertFalse(MovePlanning.fits(1000, 1000 + MovePlanning.MARGIN_BYTES - 1))
    }

    @Test fun recordsAreToldApartByWhetherTheyEverChange() {
        assertTrue(MovePlanning.isImmutableRecord(".keepfin/ops/a.seg"))
        assertTrue(MovePlanning.isImmutableRecord(".keepfin/libraries/x/checkpoints/p.part"))
        assertTrue(MovePlanning.isImmutableRecord(".keepfin/trash/id/song.mp3"))
        assertFalse(MovePlanning.isImmutableRecord(".keepfin/folder.json"))
        assertTrue(MovePlanning.isMutableRecord(".keepfin/folder.json"))
        assertFalse(MovePlanning.isMutableRecord(".keepfin/.nomedia"))
        assertFalse(MovePlanning.isMutableRecord("Music/a.mp3"))
        assertFalse(MovePlanning.isImmutableRecord("Music/ops/a.mp3"))
    }

    @Test fun aFileIsNotCopiedAgainOnlyWhenItIsTheSameAndUnchangedSinceTheMoveBegan() {
        assertTrue(MovePlanning.alreadyCopied("Music/a.mp3", 10, 500, 10, 1000))
        assertFalse("changed after the move began", MovePlanning.alreadyCopied("Music/a.mp3", 10, 2000, 10, 1000))
        assertFalse("another size", MovePlanning.alreadyCopied("Music/a.mp3", 10, 500, 9, 1000))
        assertTrue(MovePlanning.alreadyCopied(".keepfin/ops/a.seg", 10, 9999, 10, 1000))
        assertFalse("rewritten records are compared by content", MovePlanning.alreadyCopied(".keepfin/folder.json", 10, 0, 10, 1000))
        assertTrue("the system fills .nomedia itself", MovePlanning.alreadyCopied(".keepfin/.nomedia", 0, 0, 40, 1000))
    }

    @Test fun theTopLevelNameDecidesWhatAStopRemoves() {
        assertEquals("Music", MovePlanning.topOf("Music/Rock/a.mp3"))
        assertEquals("README.txt", MovePlanning.topOf("README.txt"))
    }

    @Test fun bringingInSpeaksLikeThePc() {
        assertEquals("Brought 3 files into your Keepfin folder.", BringInWords.summary(3, 0, 0, 0))
        assertEquals("Brought 1 file into your Keepfin folder.", BringInWords.summary(1, 0, 0, 0))
        assertEquals("Brought 2 files into your Keepfin folder. 1 file stayed where it was.", BringInWords.summary(2, 0, 1, 0))
        assertEquals("New files now go into your Keepfin folder.", BringInWords.summary(0, 0, 0, 0))
        assertTrue(BringInWords.summary(2, 0, 0, 1).contains("Android asks before it removes the originals"))
    }
}
