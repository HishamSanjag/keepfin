package app.keepfin.copies

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The periodic check that starts scheduled copies: one unique job, run by WorkManager when its period is up. */
@RunWith(AndroidJUnit4::class)
class CopiesWorkTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @Test fun theScheduleIsOneUniquePeriodicJobAndTheWorkerRunsWhenItsPeriodIsUp() {
        CopiesRuntime.schedule(context)
        CopiesRuntime.schedule(context) // asking again keeps the one job
        val wm = WorkManager.getInstance(context)
        val infos = wm.getWorkInfosForUniqueWork("keepfin-copies").get()
        assertEquals(1, infos.size)
        assertEquals(WorkInfo.State.ENQUEUED, infos[0].state)
        val before = CopiesRuntime.workerRuns.get()
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setPeriodDelayMet(infos[0].id)
        val deadline = System.currentTimeMillis() + 10_000
        while (CopiesRuntime.workerRuns.get() == before && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("the worker ran", CopiesRuntime.workerRuns.get() > before)
        // a periodic job goes back to waiting for the next period
        val after = wm.getWorkInfosForUniqueWork("keepfin-copies").get().single()
        assertTrue(after.state == WorkInfo.State.ENQUEUED || after.state == WorkInfo.State.RUNNING)
    }
}
