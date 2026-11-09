package app.keepfin.core.copies

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CopyActionsTest {
    @Test
    fun theActionsAreNamedTheSameOnBothApps() {
        assertEquals("Free up space…", CopyActions.FREE_UP)
        assertEquals("Remove copy…", CopyActions.REMOVE)
        assertEquals("Free up space on Backup?", CopyActions.freeUpTitle("Backup"))
        assertEquals("Remove Backup?", CopyActions.removeTitle("Backup"))
    }

    @Test
    fun nothingToFreeOnlyWhenNothingIsKept() {
        assertTrue(CopyActions.nothingToFree(0, 0))
        assertFalse(CopyActions.nothingToFree(1, 0))
        assertFalse(CopyActions.nothingToFree(0, 1))
    }

    @Test
    fun summaryCountsVersionsAndDeletedFiles() {
        assertEquals("This deletes 1 earlier version from this backup.", CopyActions.freeUpSummary(1, 0))
        assertEquals("This deletes 3 earlier versions and the 2 files kept after you deleted them from your library from this backup.", CopyActions.freeUpSummary(3, 2))
        assertEquals("This deletes 1 file kept after you deleted it from your library from this backup.", CopyActions.freeUpSummary(0, 1))
        assertEquals("This deletes 1,200 earlier versions from this backup.", CopyActions.freeUpSummary(1200, 0))
    }
}
