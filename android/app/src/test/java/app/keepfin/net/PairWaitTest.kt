package app.keepfin.net

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Waiting for the other device to open "Add a device" (retry on "not pairing"), with a fake clock so nothing waits two minutes. */
class PairWaitTest {
    private var clock = 0L
    private val sleeps = ArrayList<Long>()
    private var cancelAt = Int.MAX_VALUE

    private fun wait(retry: Long = 2000, total: Long = 120_000) = PairWait(retry, total, { clock }) { ms ->
        sleeps += ms
        clock += ms
        sleeps.size >= cancelAt
    }

    private fun notPairing() = AskError("That device is not accepting new devices.", "not-pairing")

    @Test fun retriesEveryTwoSecondsUntilTheOtherSideOpensThenPairs() {
        val calls = AtomicInteger()
        var announced = 0
        val result = wait().run("Work PC", { announced++ }, { false }) {
            if (calls.incrementAndGet() < 4) throw notPairing()
            "paired"
        }
        assertEquals("paired", result)
        assertEquals(4, calls.get())
        assertEquals(listOf(2000L, 2000L, 2000L), sleeps)
        assertEquals("announced once", 1, announced)
    }

    @Test fun anImmediateAnswerNeverShowsTheWaitingStage() {
        var announced = 0
        assertEquals(7, wait().run("PC", { announced++ }, { false }) { 7 })
        assertEquals(0, announced)
        assertTrue(sleeps.isEmpty())
    }

    @Test fun otherErrorsEndAtOnce() {
        val calls = AtomicInteger()
        try {
            wait().run("PC", {}, { false }) { calls.incrementAndGet(); throw AskError("busy", "busy") }
            fail()
        } catch (e: AskError) {
            assertEquals("busy", e.code)
        }
        assertEquals(1, calls.get())
    }

    @Test fun cancellingStopsTheRetriesWithoutAnError() {
        cancelAt = 2
        val calls = AtomicInteger()
        try {
            wait().run("PC", {}, { false }) { calls.incrementAndGet(); throw notPairing() }
            fail()
        } catch (e: AskError) {
            assertEquals(PairWait.CANCELLED, e.code)
        }
        assertEquals(2, calls.get())
    }

    @Test fun cancelledBeforeTheNextTryDoesNotTryAgain() {
        val calls = AtomicInteger()
        var cancelled = false
        try {
            wait().run("PC", {}, { cancelled }) { calls.incrementAndGet(); cancelled = true; throw notPairing() }
            fail()
        } catch (e: AskError) {
            assertEquals(PairWait.CANCELLED, e.code)
        }
        assertEquals(1, calls.get())
    }

    @Test fun afterTwoMinutesItFailsInWordsNamingTheDevice() {
        try {
            wait().run("Work PC", {}, { false }) { throw notPairing() }
            fail()
        } catch (e: AskError) {
            assertEquals("Add a device was not opened on Work PC in time. Try again.", e.message)
            assertEquals("peer-timeout", e.code)
        }
        assertTrue("stopped at the deadline", clock in 120_000L..122_000L)
        assertFalse(sleeps.isEmpty())
    }

    @Test fun anUnknownNameReadsAsTheOtherDevice() {
        try {
            wait(total = 100).run(PairWait.FALLBACK_NAME, {}, { false }) { throw notPairing() }
            fail()
        } catch (e: AskError) {
            assertEquals("Add a device was not opened on the other device in time. Try again.", e.message)
        }
    }
}
