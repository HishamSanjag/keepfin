package app.keepfin.ui.player

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The registry is the computer's (gui/web/player/full/layouts.ts): same ids, names and default; an unknown id is the default. */
class PlayerStylesTest {
    @Test fun sameStylesNamesAndDefaultAsTheComputer() {
        assertEquals(listOf("stage", "cinema", "afterglow"), PlayerStyles.all.map { it.id })
        assertEquals(listOf("Stage", "Cinema", "Afterglow"), PlayerStyles.all.map { it.name })
        assertEquals("stage", PlayerStyles.DEFAULT)
        assertTrue(PlayerStyles.all.all { it.summary.isNotBlank() })
    }

    @Test fun anUnknownOrMissingIdFallsBackToTheDefault() {
        assertEquals("stage", PlayerStyles.byId(null).id)
        assertEquals("stage", PlayerStyles.byId("hologram").id)
        assertEquals("afterglow", PlayerStyles.byId("afterglow").id)
    }

    @Test fun theGlowNeverGetsBrighterThanTheCap() {
        for (c in listOf(Color.White, Color(0xFFFFE000), Color(0xFF00FFAA), Color(0xFF2855CE), Color.Black)) {
            assertTrue(PlayerStyles.luminance(PlayerStyles.darkened(c, 0.16f)) <= 0.161f)
        }
        // a dark colour is left alone
        val dark = Color(0xFF101830)
        assertEquals(dark, PlayerStyles.darkened(dark, 0.16f))
    }
}
