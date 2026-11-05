package app.keepfin.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test

class UpcomingQueueTest {
    /** A shuffled play order 2, 0, 3, 1 over four positions: what follows the current one is read from that order, not from the list. */
    private fun nextIn(order: List<Int>): (Int) -> Int = { at -> order.getOrNull(order.indexOf(at) + 1) ?: -1 }

    @Test fun listOrderTakesEverythingAfterTheCurrentSong() {
        assertEquals(listOf(2, 3), UpcomingQueue.indices(1, 4) { if (it + 1 < 4) it + 1 else -1 })
    }

    @Test fun shuffleOrderIsFollowedNotTheListOrder() {
        val order = listOf(2, 0, 3, 1)
        assertEquals(listOf(3, 1), UpcomingQueue.indices(0, 4, nextIn(order)))
        assertEquals(listOf(0, 3, 1), UpcomingQueue.indices(2, 4, nextIn(order)))
    }

    @Test fun lastSongHasNothingUpcoming() {
        assertEquals(emptyList<Int>(), UpcomingQueue.indices(1, 4, nextIn(listOf(2, 0, 3, 1))))
        assertEquals(emptyList<Int>(), UpcomingQueue.indices(-1, 4) { -1 })
    }

    @Test fun aCycleNeverLoops() {
        assertEquals(listOf(1, 2), UpcomingQueue.indices(0, 3) { (it + 1) % 3 })
    }
}
