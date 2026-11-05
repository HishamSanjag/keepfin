package app.keepfin.ui.devices

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.keepfin.net.EngineJobPort
import app.keepfin.net.IncomingJobRow
import app.keepfin.net.RemoteJobRowUi
import app.keepfin.ui.Harness
import app.keepfin.ui.StudioApp
import app.keepfin.ui.StudioEngine
import app.keepfin.ui.UiTags
import app.keepfin.ui.exists
import app.keepfin.ui.waitForTag
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** "Asked of this phone" and "Sent to other devices" are sections of the Downloads screen now, with the same behaviour as before. */
@RunWith(AndroidJUnit4::class)
class DeviceJobsOnDownloadsUiTest {
    @get:Rule val rule = createComposeRule()

    @After fun tearDown() { StudioEngine.override = null }

    private fun show(h: Harness, source: DeviceJobsSource?) {
        rule.setContent { CompositionLocalProvider(LocalDeviceJobs provides source) { StudioApp(h.vm) } }
    }

    @Test fun theJobsShowOnTheDownloadsScreenAndAllowAndDeclineStillWork() {
        val h = Harness("empty")
        val row = IncomingJobRow("job1", "Work laptop", "A long documentary", "consent", 0.0, EngineJobPort.Consent("size", 3L * 1024 * 1024 * 1024), null)
        val sent = RemoteJobRowUi("r2", "dev1", "Work laptop", "https://example.com/w", "completed", 100.0, "Another", "mp3", null, null, 2)
        val c = FakeController(state(incoming = listOf(row), remote = listOf(sent)))
        show(h, DeviceJobsSource(c.flow, c))
        rule.waitForTag(UiTags.DEVICE_JOBS)
        rule.onNodeWithText("Asked of this phone").assertIsDisplayed()
        rule.onNodeWithText("A long documentary").assertIsDisplayed()
        rule.onNodeWithText("This is large", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(DevicesTags.ALLOW_JOB).performClick()
        rule.waitUntil(5_000) { c.calls.contains("allow:job1") }
        rule.onNodeWithTag(DevicesTags.DECLINE_JOB).performClick()
        rule.waitUntil(5_000) { c.calls.contains("decline:job1") }
        rule.onNodeWithText("Sent to other devices").assertIsDisplayed()
        rule.onNodeWithText("Dismiss").performClick()
        rule.waitUntil(5_000) { c.calls.contains("dismissJob:r2") }
    }

    @Test fun nothingShowsWithoutDeviceJobs() {
        val h = Harness("empty")
        val c = FakeController(state())
        show(h, DeviceJobsSource(c.flow, c))
        rule.waitForTag(UiTags.LINK_FIELD)
        assertFalse(rule.exists(UiTags.DEVICE_JOBS))
        assertTrue(rule.exists(UiTags.LINK_FIELD))
    }

    @Test fun nothingShowsWhenDevicesAreNotRunning() {
        val h = Harness("empty")
        show(h, null)
        rule.waitForTag(UiTags.LINK_FIELD)
        assertFalse(rule.exists(UiTags.DEVICE_JOBS))
    }
}
