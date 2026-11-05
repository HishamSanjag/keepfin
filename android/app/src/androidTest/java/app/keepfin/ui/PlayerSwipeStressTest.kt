package app.keepfin.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Swipes on the player while the previous swipe is still animating. The test clock is driven by hand so the exit/enter animation
 * is really in flight between swipes, as it is on a device with animations on (run with the animation scales at 1; with them at 0
 * the player uses reduced motion and nothing is ever in flight). Each swipe must skip in its own direction, and the surface must
 * still respond afterwards.
 */
@RunWith(AndroidJUnit4::class)
class PlayerSwipeStressTest {
    @get:Rule val rule = createComposeRule()

    @Before fun setUp() { StudioEngine.override = null }
    @After fun tearDown() { StudioEngine.override = null }

    private val song = "content://media/external/audio/media/102"

    private fun pump(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > end) return false
            rule.mainClock.advanceTimeBy(50); Thread.sleep(20)
        }
        return true
    }

    private fun index(h: Harness) = h.vm.player.ui.value.index

    private fun playSong(): Harness {
        val h = Harness("library")
        h.vm.navigate(Destination.LIBRARY)
        h.show(rule)
        rule.onNodeWithTag(UiTags.spaceCard("music")).performClick()
        rule.waitForTag(UiTags.libItem(song))
        rule.onNodeWithTag(UiTags.libItem(song)).performClick()
        // The playing row's bars never stop animating once animations are on, so the clock is driven by hand from here on.
        rule.mainClock.autoAdvance = false
        assertTrue("mini player shows", pump { rule.exists(UiTags.MINI_PLAYER) })
        return h
    }

    /** One quick flick, then [gapMs] of animation before the next one starts. */
    private fun flick(tag: String, left: Boolean, ms: Long = 60, gapMs: Long = 40) {
        rule.onNodeWithTag(tag).performTouchInput { if (left) swipeLeft(durationMillis = ms) else swipeRight(durationMillis = ms) }
        rule.mainClock.advanceTimeBy(gapMs)
    }

    @Test fun miniPlayerSurvivesRapidSwipes() {
        val h = playSong()
        h.vm.player.pause() // the simulated clock runs on the test clock and would end songs by itself
        h.vm.player.skipTo(2)
        assertTrue(pump { index(h) == 2 })
        h.vm.player.pause()
        var expected = 2
        // Same direction repeatedly, then reversals, with a gap far shorter than the 280 ms of the exit/enter animation.
        val moves = listOf(false, false, true, true, true, false, true, true, false, false, true, false)
        moves.forEachIndexed { n, back ->
            val size = h.vm.player.ui.value.queue.size
            val target = if (back) expected - 1 else expected + 1
            if (target !in 0 until size) return@forEachIndexed
            flick(UiTags.MINI_PLAYER, left = !back)
            expected = target
            val ok = pump(1_500) { index(h) == expected }
            assertTrue("swipe #$n (${if (back) "right" else "left"}) must reach index $expected but is ${index(h)}", ok)
        }
        // After the burst the surface must still respond.
        rule.mainClock.advanceTimeBy(1_000)
        val before = index(h)
        flick(UiTags.MINI_PLAYER, left = before < h.vm.player.ui.value.queue.size - 1, gapMs = 400)
        assertTrue("a swipe after the burst still skips", pump(1_500) { index(h) != before })
    }
}
