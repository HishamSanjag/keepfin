package app.keepfin.ui

import androidx.media3.common.Player
import app.keepfin.ui.player.PlayerUi
import app.keepfin.ui.player.QueueEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Swipes and the mini player's Previous button ask [PlayerUi.hasNext] / [PlayerUi.hasPrevious]. */
class PlayerUiTest {
    private val queue = List(5) { QueueEntry("s$it", "Song $it", null, null, video = false) }

    @Test fun withShuffleThePlayersOwnOrderDecidesNotTheListPosition() {
        // Shuffled: the playing song is last in the list, but the shuffled order still has songs before and after it.
        val ui = PlayerUi(queue = queue, index = 4, shuffle = true, nextIndex = 1, previousIndex = 2)
        assertTrue(ui.hasNext)
        assertTrue(ui.hasPrevious)
        // First in the list, yet in the middle of the shuffled order.
        assertTrue(PlayerUi(queue = queue, index = 0, shuffle = true, nextIndex = 3, previousIndex = 2).hasPrevious)
    }

    @Test fun theRealEndsStillStop() {
        val ui = PlayerUi(queue = queue, index = 4, shuffle = true, nextIndex = -1, previousIndex = 3)
        assertFalse(ui.hasNext)
        assertFalse(PlayerUi(queue = queue, index = 0, nextIndex = 1, previousIndex = -1).hasPrevious)
    }

    @Test fun withoutPlayerIndexesListOrderAndRepeatApply() {
        assertTrue(PlayerUi(queue = queue, index = 0).hasNext)
        assertFalse(PlayerUi(queue = queue, index = 4).hasNext)
        assertFalse(PlayerUi(queue = queue, index = 0).hasPrevious)
        assertTrue(PlayerUi(queue = queue, index = 4, repeat = Player.REPEAT_MODE_ALL).hasNext)
    }
}
