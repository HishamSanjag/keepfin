package app.keepfin

import app.keepfin.ui.player.FrameCapture
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameNameTest {
    @Test fun nameIsTitleAndClock() {
        assertEquals("Tears of Steel 0-02-15.png", FrameCapture.fileName("Tears of Steel", 135_400))
        assertEquals("Long film 1-05-09.png", FrameCapture.fileName("Long film", (3600 + 5 * 60 + 9) * 1000L))
    }

    @Test fun illegalCharactersBecomeSpaces() {
        assertEquals("A B C D E F G H I 0-00-00.png", FrameCapture.fileName("A/B:C*D?E\"F<G>H|I", 0))
        assertEquals("Frame 0-00-07.png", FrameCapture.fileName("  ", 7_000))
    }
}
