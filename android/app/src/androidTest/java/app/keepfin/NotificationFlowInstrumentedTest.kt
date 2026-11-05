package app.keepfin

import android.app.Notification
import android.app.NotificationManager
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.ui.StudioEngine
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    .use { ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> String(stream.readBytes()) } }

private fun serviceIsForeground(): Boolean {
    val dump = shell("dumpsys activity services app.keepfin")
    val record = dump.substringAfter("DownloadService", "")
    return record.contains("isForeground=true")
}

private fun wakeLockHeld() = shell("dumpsys power").lineSequence().any { "keepfin:downloads" in it && "PARTIAL_WAKE_LOCK" in it }

private fun waitFor(timeoutMs: Long, step: Long = 50, condition: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) { if (condition()) return true; Thread.sleep(step) }
    return condition()
}

/**
 * Fresh-install behaviour of progress and completion notifications, the foreground service, collection and burst
 * collapsing, and the automatic-retry pipeline against the real engine and a local fixture server.
 */
@RunWith(AndroidJUnit4::class)
class NotificationFlowInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine get() = (context.applicationContext as KeepfinApplication).engineApi as MediaEngine

    /** Defaults exactly as on a fresh install: no stored preferences. */
    private fun freshDefaults(state: DownloadNotificationTestState) {
        context.getSharedPreferences("notifications", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        state.notifications.channels()
    }

    @Test fun freshInstallPostsVisibleProgressImmediatelyAndAnAudibleCompletion() {
        FixtureServer().use { server ->
            DownloadNotificationTestState(context).use { state ->
                freshDefaults(state)
                assertTrue("Notifications are allowed on a fresh install with the permission granted", state.notifications.allowed())
                engine.updateSettings { it.copy(wifiOnly = false, organization = "flat") }
                val media = engine.mediaOf(server.url("fresh-${UUID.randomUUID()}.mp4"))
                server.hold = true
                val before = engine.jobs.value.map { it.id }.toSet()
                val enqueuedAt = System.currentTimeMillis()
                StudioEngine.withServiceWork(context) { engine.enqueue(media, FormatRequest.Video(), DownloadOptions(embedThumbnail = false)) }
                val id = state.own(engine.newestJobId(before))
                // Progress: the foreground notification is posted within about a second of the enqueue and is visible.
                assertTrue("Foreground progress notification must be posted within ~1 s", waitFor(1_500) { state.manager.activeNotifications.any { it.id == 1 && it.tag == null } })
                val postedAfter = System.currentTimeMillis() - enqueuedAt
                val progress = state.manager.activeNotifications.single { it.id == 1 && it.tag == null }.notification
                assertEquals("keepfin-progress-v2", progress.channelId)
                assertTrue(progress.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
                assertTrue("Progress is not blocked", state.manager.getNotificationChannel("keepfin-progress-v2").importance >= NotificationManager.IMPORTANCE_LOW)
                assertTrue(progress.extras.getCharSequence(Notification.EXTRA_TITLE).toString().isNotBlank())
                assertTrue("Posted in ${postedAfter} ms", postedAfter < 2_500)
                // Completion: a non-silent notification (default importance with sound) on the default channel.
                server.hold = false
                val done = engine.await(id, 120_000)
                assertEquals(done.error, JobStatus.COMPLETED, done.status)
                val completion = state.await(id)
                assertEquals("keepfin-completed-sound", completion.channelId)
                val channel = state.manager.getNotificationChannel(completion.channelId)
                assertTrue("Completion must not be a silent, minimised notification", channel.importance >= NotificationManager.IMPORTANCE_DEFAULT)
                assertNotNull(channel.sound)
                assertEquals("Download complete", completion.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            }
        }
    }

    @Test fun serviceIsForegroundImmediatelyAfterEnqueueHoldsLocksAndStopsWhenIdle() {
        FixtureServer().use { server ->
            DownloadNotificationTestState(context).use { state ->
                engine.updateSettings { it.copy(wifiOnly = false, organization = "flat") }
                val media = engine.mediaOf(server.url("service-${UUID.randomUUID()}.mp4"))
                server.hold = true
                val before = engine.jobs.value.map { it.id }.toSet()
                StudioEngine.withServiceWork(context) { engine.enqueue(media, FormatRequest.Video(), DownloadOptions(embedThumbnail = false)) }
                val id = state.own(engine.newestJobId(before))
                assertTrue("DownloadService must be a foreground service right after enqueue", waitFor(2_000) { serviceIsForeground() })
                assertTrue("A partial wake lock is held while a job is active", waitFor(3_000, 200) { wakeLockHeld() })
                server.hold = false
                engine.await(id, 120_000)
                assertTrue("The service stops once nothing is active or waiting to retry", waitFor(15_000, 250) { !serviceIsForeground() })
                assertTrue("Locks are released when idle", waitFor(5_000, 250) { !wakeLockHeld() })
            }
        }
    }

    private fun groupJob(group: JobGroup, n: Int, status: JobStatus) = testJob(status, "group-${group.id}-$n").copy(title = "Track $n", group = group,
        publicPath = if (status == JobStatus.COMPLETED) "Music/Keepfin/t$n.mp3" else null)

    @Test fun aFiveItemCollectionPostsOneNotificationNotFive() {
        DownloadNotificationTestState(context).use { state ->
            freshDefaults(state)
            val group = JobGroup(UUID.randomUUID().toString(), "Open Movies OST", "https://music.youtube.com/playlist?list=x")
            val ids = (1..5).map { "group-${group.id}-$it" }
            ids.forEach { state.own(it) }
            state.own("group-${group.id}")
            val finals = listOf(JobStatus.COMPLETED, JobStatus.COMPLETED, JobStatus.COMPLETED, JobStatus.COMPLETED, JobStatus.FAILED)
            for (step in 1..5) {
                val queue = (1..5).map { n -> groupJob(group, n, if (n <= step) finals[n - 1] else JobStatus.DOWNLOADING) }
                state.notifications.finished(queue[step - 1], jobs = { queue })
                if (step < 5) assertTrue("No notification while members are still downloading (step $step)", state.manager.activeNotifications.none { it.tag?.contains(group.id) == true })
            }
            val posted = state.manager.activeNotifications.filter { it.tag?.contains(group.id) == true }
            assertEquals("Exactly one notification for the whole collection", 1, posted.size)
            val notice = posted.single()
            assertEquals("group-${group.id}", notice.tag)
            assertEquals("Open Movies OST downloaded", notice.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals("4 of 5 saved, 1 failed", notice.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals("Retry failed", notice.notification.actions.single().title.toString())
            // Replaying the same terminal state never re-notifies.
            state.manager.cancel("group-${group.id}", 2)
            val queue = (1..5).map { n -> groupJob(group, n, finals[n - 1]) }
            state.notifications.finished(queue[4], jobs = { queue })
            Thread.sleep(150)
            assertTrue(state.manager.activeNotifications.none { it.tag?.contains(group.id) == true })
        }
    }

    @Test fun finishedSinglesInABurstCollapseIntoOneSummary() {
        DownloadNotificationTestState(context).use { state ->
            freshDefaults(state)
            val jobs = (1..3).map { state.fake(JobStatus.COMPLETED) }
            jobs.forEach { state.notifications.finished(it) }
            val children = state.manager.activeNotifications.filter { it.id == 2 && it.notification.group == DownloadNotifications.FINISHED_GROUP }
            assertTrue(children.size >= 3)
            // Posting is asynchronous; give the system a moment to list the summary.
            val deadline = System.currentTimeMillis() + 3_000
            var summaries = emptyList<android.service.notification.StatusBarNotification>()
            while (System.currentTimeMillis() < deadline) {
                summaries = state.manager.activeNotifications.filter { it.id == DownloadNotifications.SUMMARY_ID && it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0 }
                if (summaries.isNotEmpty()) break
                Thread.sleep(50)
            }
            assertEquals("One summary folds the burst so the phone alerts once", 1, summaries.size)
            val title = summaries.single().notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
            assertTrue(title, title.endsWith("downloads finished"))
            state.manager.cancel(DownloadNotifications.SUMMARY_ID)
            jobs.forEach { state.manager.cancel(it.id, 2) }
        }
    }

    /** Junk bytes download fine but fail audio conversion quickly: a retryable processing failure, unlike an HTTP 5xx (which the engine itself retries for minutes). */
    private fun enqueueFailingConversion(server: FixtureServer, state: DownloadNotificationTestState, name: String): String {
        engine.updateSettings { it.copy(wifiOnly = false, organization = "flat") }
        val media = engine.mediaOf(server.url("$name-${UUID.randomUUID()}.mp3"))
        server.garbage = true
        val before = engine.jobs.value.map { it.id }.toSet()
        StudioEngine.withServiceWork(context) { engine.enqueue(media, FormatRequest.Audio("mp3", "best"), DownloadOptions(embedThumbnail = false)) }
        return state.own(engine.newestJobId(before))
    }

    @Test fun transientFailuresRetryQuietlyWithoutAFailureNotification() {
        FixtureServer().use { server ->
            DownloadNotificationTestState(context).use { state ->
                val id = enqueueFailingConversion(server, state, "transient")
                val waiting = engine.await(id, 120_000) { it.retryAt != null || it.status == JobStatus.FAILED }
                assertEquals("The first failure schedules an automatic retry instead of failing: ${waiting.error}", JobStatus.QUEUED, waiting.status)
                assertEquals(1, waiting.retryCount)
                assertTrue("Backoff is about 5 s", waiting.retryAt!! - System.currentTimeMillis() in -1_000..6_000)
                assertEquals("Retrying", waiting.phase)
                assertNull("No failure notification while a retry is pending", state.find(id))
                assertTrue("The service stays alive while a retry waits", serviceIsForeground())
                engine.cancel(id)
            }
        }
    }

    @Test fun exhaustedRetriesFailOnceThenManualRetryResetsTheCount() {
        FixtureServer().use { server ->
            DownloadNotificationTestState(context).use { state ->
                val id = enqueueFailingConversion(server, state, "exhausted")
                val seen = mutableSetOf<Int>()
                val failed = engine.await(id, 280_000) { job ->
                    seen.add(job.retryCount)
                    assertTrue("No failure notification before the last attempt", job.status == JobStatus.FAILED || state.find(id) == null)
                    job.status == JobStatus.FAILED
                }
                assertEquals(RetryPolicy.MAX, failed.retryCount)
                assertTrue("All three automatic retries were scheduled: $seen", seen.containsAll(listOf(0, 1, 2, 3)))
                assertEquals("Download failed", state.await(id).extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                // A manual retry starts over with a fresh budget.
                StudioEngine.withServiceWork(context) { engine.retry(id) }
                val again = engine.job(id)
                assertEquals(0, again.retryCount)
                assertNull(again.retryAt)
                assertTrue(again.attempt > failed.attempt)
                engine.cancel(id)
            }
        }
    }

    @Test fun permanentFailuresDoNotRetry() {
        FixtureServer().use { server ->
            DownloadNotificationTestState(context).use { state ->
                engine.updateSettings { it.copy(wifiOnly = false, organization = "flat") }
                val media = engine.mediaOf(server.url("permanent-${UUID.randomUUID()}.mp4"))
                server.failStatus = 404; server.serveMedia = false
                val before = engine.jobs.value.map { it.id }.toSet()
                StudioEngine.withServiceWork(context) { engine.enqueue(media, FormatRequest.Video(), DownloadOptions(embedThumbnail = false)) }
                val id = state.own(engine.newestJobId(before))
                val failed = engine.await(id, 90_000)
                assertEquals(JobStatus.FAILED, failed.status)
                assertEquals(0, failed.retryCount)
                assertEquals("Download failed", state.await(id).extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            }
        }
    }
}
