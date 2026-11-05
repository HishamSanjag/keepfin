package app.keepfin.ui

import android.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.JobStatus
import app.keepfin.ui.preview.Fixtures
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** The Downloads list on All: every saved download, each with its own picture. */
@RunWith(AndroidJUnit4::class)
class DownloadsListUiTest {
    @get:Rule val rule = createComposeRule()

    @After fun tearDown() { StudioEngine.override = null }

    @Test fun allListsEverySavedDownloadWithoutSeeAll() {
        val h = Harness("empty")
        h.engine.setJobs((1..8).map { Fixtures.job("s$it", "Saved $it", null, JobStatus.COMPLETED, percent = 100f, ageMinutes = it * 10L) })
        h.show(rule)
        rule.scrollListTo(UiTags.QUEUE, UiTags.job("s8"))
        rule.waitForTag(UiTags.job("s8"))
        assertFalse("No button sends people to another tab", rule.hasText("See all"))
    }

    @Test fun aSavedVideoShowsTheCoverInsideItsFile() {
        val h = Harness("empty")
        // green frames throughout, a red cover embedded: a YouTube video's own picture versus a scene from it
        val video = File(h.app.cacheDir, "cover-${UUID.randomUUID()}.mp4").apply {
            writeBytes(InstrumentationRegistry.getInstrumentation().context.assets.open("green-video-red-cover.mp4").use { it.readBytes() })
        }
        h.engine.setJobs(listOf(Fixtures.job("j-cover", "Taxi Driver", null, JobStatus.COMPLETED, percent = 100f).copy(file = video.path)))
        h.show(rule)
        rule.waitForTag(UiTags.jobThumb("j-cover"))

        fun centre(): Int = rule.onNodeWithTag(UiTags.jobThumb("j-cover"), useUnmergedTree = true).captureToImage().asAndroidBitmap()
            .let { it.getPixel(it.width / 2, it.height / 2) }
        fun red(c: Int) = Color.red(c) > 180 && Color.green(c) < 100
        fun green(c: Int) = Color.green(c) > 90 && Color.red(c) < 80
        rule.waitUntil(10_000) { centre().let { red(it) || green(it) } }
        val shown = centre()
        assertTrue("the embedded cover (red), not a frame (green): #${Integer.toHexString(shown)}", red(shown))
        video.delete()
    }
}
