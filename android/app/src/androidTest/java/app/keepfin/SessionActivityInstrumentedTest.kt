package app.keepfin

import android.content.Intent
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The system media card (lock screen, control center) must open Keepfin on the player, like the notification does. */
@RunWith(AndroidJUnit4::class)
class SessionActivityInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun sessionActivityIntentTargetsMainActivityWithThePlayerExtraAndReusesTheTask() {
        val intent = PlaybackService.sessionActivityIntent(context)
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(context.packageName, intent.component?.packageName)
        assertTrue(intent.getBooleanExtra(MainActivity.EXTRA_OPEN_PLAYER, false))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertEquals("Reuses the running task: no new-task flag", 0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertNotNull(PlaybackService.sessionActivity(context))
    }

    @Test fun theLiveSessionPublishesTheActivityToControllersAndSystemUi() {
        val future = PlaybackClient.connect(context)
        try {
            val controller = future.get(20, TimeUnit.SECONDS)
            val activity = controller.sessionActivity
            assertNotNull("The session carries a PendingIntent for the media card tap", activity)
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { MediaController.releaseFuture(future) }
        }
    }
}
