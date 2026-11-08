package app.keepfin.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the app's own startup and main journeys: every tab, the Downloads list scrolled, and the player when one is on screen.
 * Run `gradlew :app:generateBaselineProfile` from android/ (see dev-docs/guides/BUILD-COMMANDS.md).
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    /**
     * A fresh install (the plugin uninstalls the app after each run) opens on the first-run intro, folder choice and permission steps.
     * Click through them until the tab bar shows; "Not now" declines moving earlier files, so nothing on the device is changed.
     */
    private fun androidx.benchmark.macro.MacrobenchmarkScope.passOnboarding() {
        val steps = listOf(
            By.text("Skip intro"), By.text("Next"), By.textStartsWith("Use Documents"), By.text("USE THIS FOLDER"),
            By.text("ALLOW"), By.text("Start using Keepfin"), By.text("Not now"),
        )
        repeat(20) {
            device.waitForIdle()
            if (device.hasObject(By.text("Library")) && device.hasObject(By.text("Downloads")) && device.hasObject(By.text("Home"))) return
            steps.firstNotNullOfOrNull { device.findObject(it) }?.click()
            Thread.sleep(1_000)
        }
    }

    @Test
    fun generate() = rule.collect(packageName = "app.keepfin", includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        passOnboarding()
        device.wait(Until.hasObject(By.text("Downloads")), 10_000)
        // Two rounds, so each tab is composed cold and again on a later visit.
        repeat(2) {
            for (tab in listOf("Home", "Search", "Library", "Downloads", "Settings")) {
                device.findObject(By.text(tab))?.click()
                device.waitForIdle()
                Thread.sleep(600)
            }
        }
        device.findObject(By.text("Downloads"))?.click()
        device.waitForIdle()
        device.findObject(By.scrollable(true))?.let { list ->
            list.setGestureMargin(device.displayWidth / 5)
            repeat(2) { list.fling(Direction.DOWN); device.waitForIdle() }
            repeat(2) { list.fling(Direction.UP); device.waitForIdle() }
        }
        // The player opens from the mini player, which exists only while something plays or is queued.
        device.findObject(By.descStartsWith("Now playing"))?.click()
        device.waitForIdle()
    }
}
