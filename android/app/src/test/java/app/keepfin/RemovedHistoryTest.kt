package app.keepfin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Records removed from the download history stay available to Undo for a while, and only then are cleaned up. */
class RemovedHistoryTest {
    @Test fun heldRecordsComeBackOnceAndInTheOrderAsked() {
        val held = RemovedHistory<String>(keepMs = 1_000)
        held.hold("a", "record a", now = 0)
        held.hold("b", "record b", now = 10)
        assertEquals(listOf("record b", "record a"), held.take(listOf("b", "a", "zzz"), now = 500))
        assertTrue("taken records are not held any more", held.take(listOf("a"), now = 501).isEmpty())
    }

    @Test fun recordsPastTheirTimeAreHandedBackForCleaningAndCannotBeTaken() {
        val held = RemovedHistory<String>(keepMs = 1_000)
        held.hold("a", "record a", now = 0)
        held.hold("b", "record b", now = 900)
        assertEquals(listOf("record a"), held.expire(now = 1_500))
        assertFalse(held.isHeld("a"))
        assertTrue(held.isHeld("b"))
        // too late for b as well, even if the cleanup has not run yet
        assertTrue(held.take(listOf("b"), now = 2_000).isEmpty())
    }

    @Test fun theOldestAreCleanedWhenMoreThanTheLimitAreHeld() {
        val held = RemovedHistory<Int>(keepMs = 1_000, limit = 2)
        assertTrue(held.hold("1", 1, 0).isEmpty())
        assertTrue(held.hold("2", 2, 0).isEmpty())
        assertEquals(listOf(1), held.hold("3", 3, 0))
        assertEquals(listOf(2, 3), held.take(listOf("1", "2", "3"), 1))
    }
}
