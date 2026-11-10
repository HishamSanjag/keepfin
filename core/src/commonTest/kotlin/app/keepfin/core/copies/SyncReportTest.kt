package app.keepfin.core.copies

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncReportTest {
    @Test
    fun nothingToDoStillSaysSo() {
        assertEquals(SyncReport("Everything is up to date", "Nothing needed copying.", false), syncReport(1, 0, 0, emptyList(), emptyList()))
        assertEquals(SyncReport("Everything is up to date", "All 2 copies already match your library.", false), syncReport(2, 0, 0, emptyList(), emptyList()))
    }

    @Test
    fun changesAreCounted() {
        assertEquals(SyncReport("Copy synced", "1 file updated.", false), syncReport(1, 1, 0, emptyList(), emptyList()))
        assertEquals(SyncReport("Copies synced", "12 files updated across 2 copies.", false), syncReport(2, 12, 0, emptyList(), emptyList()))
    }

    @Test
    fun problemsComeFirst() {
        assertEquals(SyncReport("3 files could not be copied", "5 files updated. Keepfin tries again at every sync.", true), syncReport(1, 5, 3, emptyList(), emptyList()))
    }

    @Test
    fun copiesThatWereNotReachedAreNamed() {
        assertEquals(SyncReport("Everything is up to date", "Nothing needed copying. Backup could not be reached.", true), syncReport(1, 0, 0, listOf("Backup"), emptyList()))
        assertEquals(SyncReport("Backup could not be reached", "Check that it is plugged in or online, then try again.", true), syncReport(0, 0, 0, listOf("Backup"), emptyList()))
        assertEquals(SyncReport("Dropbox could not be reached", "Keepfin will try again by itself. If it keeps failing, check your internet connection.", true), syncReport(0, 0, 0, listOf("Dropbox"), emptyList(), cloud = true))
        assertEquals(SyncReport("No copy could be synced", "Media, USB and Backup could not be reached.", true), syncReport(0, 0, 0, listOf("Media", "USB", "Backup"), emptyList()))
    }

    @Test
    fun aCopyWaitingForAnOkSaysSo() {
        assertEquals(SyncReport("A copy needs your OK", "Media needs your OK first.", true), syncReport(0, 0, 0, emptyList(), listOf("Media")))
        assertEquals(SyncReport("Copies synced", "2 files updated across 2 copies. Media needs your OK first.", true), syncReport(2, 2, 0, emptyList(), listOf("Media")))
    }
}
