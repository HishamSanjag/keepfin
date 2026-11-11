package app.keepfin.ui.media

import org.junit.Assert.assertEquals
import org.junit.Test

class CardSizesTest {
    @Test fun cardsPerRowCountsWhatFitsWithTheGaps() {
        assertEquals(2, cardsPerRow(328f, 148f, 12f))
        assertEquals(3, cardsPerRow(328f, 100f, 12f))
        assertEquals(1, cardsPerRow(200f, 220f, 12f))
    }

    @Test fun aPhotoKeepsItsShapeWithinACalmRange() {
        assertEquals(216f, photoWidth(120f, 4000, 2000), 0.01f)
        assertEquals(120f, photoWidth(120f, null, 3000), 0.01f)
        assertEquals(72f, photoWidth(120f, 100, 1000), 0.01f)
        assertEquals(216f, photoWidth(120f, 5000, 1000), 0.01f)
    }
}
