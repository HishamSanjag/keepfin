package app.keepfin

import android.app.ApplicationExitInfo
import org.junit.Assert.*
import org.junit.Test

class ExitReportsTest {
    private fun exit(time: Long, reason: Int, description: String? = null, trace: String? = null) = ExitRecord(time, reason, description, trace)

    @Test fun onlyNewNativeCrashesAndNotRespondingExitsAreKeptOldestFirst() {
        val records = listOf(
            exit(500, ApplicationExitInfo.REASON_ANR),
            exit(400, ApplicationExitInfo.REASON_USER_REQUESTED),
            exit(300, ApplicationExitInfo.REASON_CRASH_NATIVE),
            // a Kotlin/Java crash is already written by CrashLog's own handler: not recorded twice
            exit(200, ApplicationExitInfo.REASON_CRASH),
        )
        assertEquals(listOf(300L, 500L), ExitReports.fresh(records, lastSeen = 100).map { it.timestamp })
        assertTrue(ExitReports.fresh(records, lastSeen = 500).isEmpty())
    }

    @Test fun anEntryNamesTheReasonAndCarriesTheTrace() {
        val text = ExitReports.entry(exit(0, ApplicationExitInfo.REASON_CRASH_NATIVE, "signal 11", "backtrace:\n#00 pc 1234\n"))
        assertTrue(text.startsWith("Native crash at 1970-01-01T00:00:00Z"))
        assertTrue(text.contains("signal 11"))
        assertTrue(text.endsWith("#00 pc 1234"))
    }

    @Test fun anEntryWithoutDetailsIsOneLine() {
        assertEquals("Not responding (ANR) at 1970-01-01T00:00:00Z", ExitReports.entry(exit(0, ApplicationExitInfo.REASON_ANR, " ")).trim())
    }
}
