package app.keepfin

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.keepfin.core.gallery.Gallery
import app.keepfin.core.gallery.GalleryRead
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the real gallery engine on the device against a public photo post (needs network). Emulator only. */
@RunWith(AndroidJUnit4::class)
class GalleryEngineTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    // The download engine extracts the interpreter's packages on init, exactly as ToolsUpdater.ensureInit does.
    private val engine = GalleryEngine(context) { YoutubeDL.getInstance().init(context); FFmpeg.getInstance().init(context) }

    @Test fun packageInstallsAndTheEngineStarts() {
        val output = engine.run(listOf("--version"), 60)
        assertEquals("stderr: ${output.stderr}", 0, output.exit)
        assertTrue(output.stdout, output.stdout.trim().startsWith("1."))
    }

    @Test fun readsAPublicPinterestPin() {
        val url = "https://www.pinterest.com/pin/99360735500167749/"
        val read = Gallery.read(engine.inspect(url), url)
        assertTrue("expected a post, got $read", read is GalleryRead.Post)
        assertTrue((read as GalleryRead.Post).count >= 1)
    }
}
