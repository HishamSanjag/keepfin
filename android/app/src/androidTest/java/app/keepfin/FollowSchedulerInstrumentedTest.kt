package app.keepfin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** The WorkManager side of following: the hourly scan, "Check now", and the two workers (the rules themselves are in FollowEngineTest). */
@RunWith(AndroidJUnit4::class)
class FollowSchedulerInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun testWorkManager(): WorkManager {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        return WorkManager.getInstance(context)
    }

    @Test fun theHourlyScanIsRegisteredWithANetworkConstraint() {
        val manager = testWorkManager()
        FollowScheduler.schedule(context)
        FollowScheduler.schedule(context)
        val infos = manager.getWorkInfosForUniqueWork(FollowScheduler.PERIODIC).get(10, TimeUnit.SECONDS)
        assertEquals("scheduling twice keeps one schedule", 1, infos.size)
        val info = infos.single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(NetworkType.CONNECTED, info.constraints.requiredNetworkType)
        assertEquals(TimeUnit.HOURS.toMillis(1), info.periodicityInfo!!.repeatIntervalMillis)
    }

    @Test fun checkNowIsOneTimeWorkThatNeedsTheNetwork() {
        val manager = testWorkManager()
        FollowScheduler.checkNow(context, "abc", review = false)
        FollowScheduler.checkNow(context, "abc", review = false)
        val infos = manager.getWorkInfosForUniqueWork("${FollowScheduler.RUN}-abc").get(10, TimeUnit.SECONDS)
        assertEquals("a second tap while one is queued is ignored", 1, infos.size)
        assertNull("one-time work, not periodic", infos.single().periodicityInfo)
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
    }

    @Test fun theScanWorkerStaysQuietWhenNothingFollowsAutomatically() {
        FollowScheduler.remember(context, false)
        val worker = TestListenableWorkerBuilder<FollowScanWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), kotlinx.coroutines.runBlocking { worker.doWork() })
    }

    @Test fun theRunWorkerChecksTheCollectionAndSucceeds() {
        // a collection that is gone checks nothing and still succeeds
        val worker = TestListenableWorkerBuilder<FollowRunWorker>(context, workDataOf(FollowScheduler.KEY_ID to "no-such-collection")).build()
        assertEquals(ListenableWorker.Result.success(), kotlinx.coroutines.runBlocking { worker.doWork() })
        // It also declares the foreground (dataSync) info WorkManager needs to run it expedited on older Android versions.
        val foreground = kotlinx.coroutines.runBlocking { worker.getForegroundInfo() }
        if (android.os.Build.VERSION.SDK_INT >= 29) assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, foreground.foregroundServiceType)
    }
}
