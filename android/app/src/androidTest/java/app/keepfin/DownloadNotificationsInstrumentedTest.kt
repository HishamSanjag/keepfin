package app.keepfin

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Restores app preferences and removes only notifications created by this test scope. */
internal class DownloadNotificationTestState(private val context: Context) : AutoCloseable {
    private val prefs = context.getSharedPreferences("notifications", Context.MODE_PRIVATE)
    private val saved = prefs.all.toMap()
    private val owned = mutableSetOf<String>()
    val notifications = (context.applicationContext as KeepfinApplication).notifications
    val manager = context.getSystemService(NotificationManager::class.java)
    init {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
            ).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() } }
        }
        mapOf("enabled" to true, "progress" to true, "completed" to true, "failed" to true,
            "cancelled" to true, "sound" to false, "previews" to false, "private" to true).forEach {
            notifications.configure(it.key, it.value)
        }
        notifications.channels()
        assertTrue("Android notification permission must allow the real posting checks", notifications.allowed())
    }
    fun own(id: String): String { owned.add(id); return id }
    fun fake(status: JobStatus, attempt: Int = 1) = own(testJob(status, attempt = attempt).id).let { testJob(status, it, attempt) }
    fun find(id: String) = manager.activeNotifications.firstOrNull { it.tag == id && it.id == 2 }
    fun await(id: String): Notification {
        repeat(100) { find(id)?.let { return it.notification }; Thread.sleep(50) }
        fail("Missing posted notification for $id")
        error("Unreachable")
    }
    override fun close() {
        owned.forEach { manager.cancel(it, 2); manager.cancel(it, 77) }
        val editor = prefs.edit().clear()
        saved.forEach { (key, value) -> when (value) {
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        editor.commit()
    }
}


@RunWith(AndroidJUnit4::class)
class DownloadNotificationsInstrumentedTest {
    @Test fun postedProgressUsesNativePercentageProcessingAndCancelControls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DownloadNotificationTestState(context).use { state ->
            val job = state.fake(JobStatus.DOWNLOADING).copy(percent = 42f, speed = 2_000_000)
            val id = job.id
            state.manager.notify(id, 77, state.notifications.progress(listOf(job)))
            fun current(matches: (Notification) -> Boolean = { true }): Notification {
                repeat(100) {
                    state.manager.activeNotifications.firstOrNull { it.tag == id && it.id == 77 }?.notification?.let { if (matches(it)) return it }
                    Thread.sleep(50)
                }
                fail("Android did not publish the expected progress notification update")
                error("Unreachable")
            }
            var notice = current()
            assertEquals("keepfin-progress-v2", notice.channelId)
            assertEquals(42, notice.extras.getInt(Notification.EXTRA_PROGRESS))
            assertEquals(100, notice.extras.getInt(Notification.EXTRA_PROGRESS_MAX))
            assertFalse(notice.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
            assertTrue(notice.flags and Notification.FLAG_ONGOING_EVENT != 0)
            assertTrue(notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("42%"))
            assertEquals(listOf("Pause", "Cancel"), notice.actions.map { it.title.toString() })
            assertEquals(context.packageName, notice.actions.last().actionIntent.creatorPackage)
            // Aggregate across concurrent jobs: average percent and the number of active downloads.
            val second = state.fake(JobStatus.DOWNLOADING).copy(percent = 62f, speed = 1_000_000)
            state.manager.notify(id, 77, state.notifications.progress(listOf(job, second)))
            notice = current { it.extras.getInt(Notification.EXTRA_PROGRESS) == 52 }
            assertTrue(notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString().startsWith("2 downloads"))
            val processing = job.copy(status = JobStatus.PROCESSING, phase = "Merging", file = "/storage/test.mp4")
            state.manager.notify(id, 77, state.notifications.progress(listOf(processing)))
            notice = current { it.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) }
            assertTrue("Processing must not pretend to have exact progress", notice.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
            assertEquals("Merging", notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals(listOf("Cancel"), notice.actions.map { it.title.toString() })
            state.notifications.configure("progress", false)
            state.manager.notify(id, 77, state.notifications.progress(listOf(processing)))
            notice = current { it.channelId == "keepfin-background-v2" }
            assertEquals("keepfin-background-v2", notice.channelId)
            assertEquals("Keepfin is working", notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertTrue("Mandatory service status must not expose the hidden media title", notice.actions.isNullOrEmpty())
        }
    }

    @Test fun terminalPreferencesChannelsAndDismissedEventsAreRespected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DownloadNotificationTestState(context).use { state ->
            val job = state.fake(JobStatus.FAILED)
            val id = job.id
            state.notifications.finished(job)
            val quiet = state.await(id)
            assertEquals("keepfin-failed-quiet", quiet.channelId)
            assertNull(state.manager.getNotificationChannel(quiet.channelId).sound)
            assertEquals(Notification.VISIBILITY_PRIVATE, quiet.visibility)
            assertEquals("Retry", quiet.actions.single().title.toString())
            state.manager.cancel(id, 2)
            state.notifications.finished(job)
            Thread.sleep(100)
            assertNull("Polling must not resurrect a notification the user dismissed", state.find(id))
            state.notifications.configure("sound", true)
            state.notifications.configure("private", false)
            state.notifications.finished(job.copy(attempt = job.attempt + 1))
            val audible = state.await(id)
            assertEquals("keepfin-failed-sound", audible.channelId)
            assertNotNull(state.manager.getNotificationChannel(audible.channelId).sound)
            assertEquals(NotificationManager.IMPORTANCE_DEFAULT, state.manager.getNotificationChannel(audible.channelId).importance)
            assertEquals(Notification.VISIBILITY_PUBLIC, audible.visibility)
            for (key in listOf("failed", "enabled")) {
                val muted = state.fake(JobStatus.FAILED)
                state.notifications.configure(key, false)
                state.notifications.finished(muted)
                state.notifications.configure(key, true)
                state.notifications.finished(muted)
                Thread.sleep(100)
                assertNull("Muted events must not replay when notifications are re-enabled", state.find(muted.id))
            }
            val completed = state.fake(JobStatus.COMPLETED)
            state.notifications.configure("completed", false)
            state.notifications.finished(completed)
            assertNull(state.find(completed.id))
            assertEquals(NotificationManager.IMPORTANCE_LOW, state.manager.getNotificationChannel("keepfin-progress-v2").importance)
            assertEquals(NotificationManager.IMPORTANCE_LOW, state.manager.getNotificationChannel("keepfin-background-v2").importance)
        }
    }

    @Test fun savedButNotPublishedCompletionIsReportedAsAProblem() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        DownloadNotificationTestState(context).use { state ->
            val job = state.fake(JobStatus.COMPLETED).copy(publicPath = null, saveError = "Automatic save failed: storage unavailable. The downloaded file is safe in the app.")
            state.notifications.finished(job)
            val notice = state.await(job.id)
            assertEquals("keepfin-failed-quiet", notice.channelId)
            assertEquals("Download ready · save needs attention", notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        }
    }

    @Test fun failureNotificationRetryActionRunsTheRealEngineAndSurvivesForegroundRemoval() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val engine = (context.applicationContext as KeepfinApplication).engineApi as MediaEngine
        FixtureServer().use { server ->
            DownloadNotificationTestState(context).use { state ->
                ActivityScenario.launch(MainActivity::class.java).use {
                    engine.updateSettings { it.copy(wifiOnly = false, organization = "flat") }
                    val url = server.url("notification-retry-${UUID.randomUUID()}.mp4")
                    val media = engine.mediaOf(url)
                    server.serveMedia = false
                    val before = engine.jobs.value.map { it.id }.toSet()
                    engine.enqueue(media, FormatRequest.Video(), DownloadOptions(embedThumbnail = false))
                    val id = state.own(engine.newestJobId(before))
                    val failed = engine.await(id, 60_000)
                    assertEquals("The local 404 must become a real failed engine job", JobStatus.FAILED, failed.status)
                    val notice = state.await(id)
                    server.serveMedia = true
                    notice.actions.single { it.title.toString() == "Retry" }.actionIntent.send()
                    val done = engine.await(id, 120_000) { it.status == JobStatus.COMPLETED || (it.status == JobStatus.FAILED && it.attempt > failed.attempt) }
                    assertEquals(done.error, JobStatus.COMPLETED, done.status)
                    assertTrue("Retry starts a new notification delivery attempt", done.attempt > failed.attempt)
                    for (attempt in 0 until 100) {
                        if (state.find(id)?.notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "Download complete" &&
                            state.manager.activeNotifications.none { it.id == 1 && it.tag == null }) break
                        Thread.sleep(100)
                    }
                    assertEquals("Download complete", state.await(id).extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                    assertFalse("Terminal notification survives independently after foreground notification is removed",
                        state.manager.activeNotifications.any { it.id == 1 && it.tag == null })
                    // The transfer is deliberately held so the actual posted Cancel action can win.
                    server.hold = true
                    val beforeCancel = engine.jobs.value.map { it.id }.toSet()
                    engine.enqueue(media.copy(title = "Cancel via notification"), FormatRequest.Video(), DownloadOptions(embedThumbnail = false))
                    val cancelId = state.own(engine.newestJobId(beforeCancel))
                    state.manager.notify(cancelId, 77, state.notifications.progress(listOf(engine.job(cancelId))))
                    for (attempt in 0 until 100) {
                        if (state.manager.activeNotifications.any { it.tag == cancelId && it.id == 77 }) break
                        Thread.sleep(50)
                    }
                    val progress = state.manager.activeNotifications.single { it.tag == cancelId && it.id == 77 }.notification
                    progress.actions.single { it.title.toString() == "Cancel" }.actionIntent.send()
                    val cancelled = engine.await(cancelId, 30_000)
                    assertEquals("The notification action must cancel the real engine transfer", JobStatus.CANCELLED, cancelled.status)
                    assertEquals("Download cancelled", state.await(cancelId).extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                    server.hold = false
                    engine.awaitIdle(30_000)
                    for (attempt in 0 until 100) {
                        if (state.manager.activeNotifications.none { it.id == 1 && it.tag == null }) break
                        Thread.sleep(100)
                    }
                }
            }
        }
    }
}
