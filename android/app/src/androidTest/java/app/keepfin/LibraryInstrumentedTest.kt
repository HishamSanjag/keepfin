package app.keepfin

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** The playback service on the device: sleep timer state. */
@RunWith(AndroidJUnit4::class)
class LibraryInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun connect(): MediaController = PlaybackClient.connect(context).get(20, TimeUnit.SECONDS)

    private fun waitFor(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) { if (condition()) return; Thread.sleep(100) }
        fail("Timed out waiting for $what")
    }

    @Test fun sleepTimerAndEqualizerStateAreExposed() {
        val controller = connect()
        try {
            onMain { controller.setMediaItem(MediaItem.Builder().setMediaId("x").build()) }
            PlaybackControls.setSleepTimer(15)
            waitFor("sleep timer") { PlaybackControls.sleepTimer.value.active }
            assertTrue(PlaybackControls.sleepTimer.value.remainingMs in 1..15 * 60_000L)
            PlaybackControls.cancelSleepTimer()
            waitFor("sleep timer cancelled") { !PlaybackControls.sleepTimer.value.active }
        } finally { onMain { controller.release() } }
    }
}
