package app.keepfin

import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * LIVE check of a converted size (network, a real provider). Skipped unless run with `-e liveProbe true`; output goes to logcat tag KeepfinConvertLive.
 * Reports whether the bundled converter has the H.264 encoder, then inspects a reel, downloads its "360p converted" size and reports the real output size.
 */
@RunWith(AndroidJUnit4::class)
class ConvertLiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine get() = (context.applicationContext as KeepfinApplication).engineApi as MediaEngine
    private fun log(message: String) { Log.i("KeepfinConvertLive", message) }

    @Test fun converterHasTheH264Encoder() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveProbe") == "true")
        val out = engine.mediaTools.run(listOf("-encoders"), 60)
        log("ENCODERS exit=${out?.exit} libx264=${out?.text?.contains("libx264")} aac=${out?.text?.lineSequence()?.any { it.contains(" aac ") }}")
        out?.text?.lineSequence()?.filter { it.contains("264") || it.contains(" aac ") }?.forEach { log("  enc> ${it.trim()}") }
        assertTrue(out?.text?.contains("libx264") == true)
    }

    /** Offline: the real bundled converter makes a smaller copy of a generated portrait clip (no network). */
    @Test fun theBundledConverterMakesTheSmallerCopy() {
        val dir = File(context.cacheDir, "convert-check").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "Reel.mp4")
        val made = engine.mediaTools.run(listOf("-f", "lavfi", "-i", "testsrc2=size=540x960:rate=30:duration=6", "-f", "lavfi", "-i", "sine=frequency=440:duration=6",
            "-c:v", "libx264", "-b:v", "1500k", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "96k", "-shortest", "-y", file.absolutePath), 120)
        log("MAKE exit=${made?.exit} bytes=${file.length()}")
        assertTrue(file.length() > 0)
        val before = file.length()
        val percents = ArrayList<Int>()
        ConvertStep.run(engine.mediaTools, file, 360, null, { false }) { percents += it }
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        retriever.release()
        log("CONVERTED ${w}x$h bytes $before -> ${file.length()} progress=$percents files=${dir.list()?.toList()}")
        assertTrue(file.length() < before)
        assertTrue(w == "360")
    }

    @Test fun reelDownloadsAsAConvertedSize() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveProbe") == "true")
        engine.updateSettings { it.copy(wifiOnly = false) }
        val url = "https://www.instagram.com/reels/DdV1kbBIIF2/"
        val media = engine.mediaOf(url)
        log("OPTIONS " + media.video.joinToString { "${it.height}p/${it.formatId}/${it.size}/approx=${it.sizeApprox}/from=${it.convertedFrom}" })
        val option = media.video.firstOrNull { it.convertedFrom != null && it.height == 360 } ?: media.video.firstOrNull { it.convertedFrom != null }
        if (option == null) { log("NO CONVERTED OPTION"); return }
        val request = FormatRequest.Video(maxHeight = option.convertedFrom, formatId = option.formatId, scaleHeight = option.height)
        val before = engine.jobs.value.map { it.id }.toSet()
        engine.enqueue(media, request, DownloadOptions())
        val id = engine.jobs.value.first { it.id !in before }.id
        val phases = LinkedHashSet<String>()
        val deadline = System.currentTimeMillis() + 300_000
        while (System.currentTimeMillis() < deadline && engine.jobs.value.first { it.id == id }.status.active) {
            engine.jobs.value.first { it.id == id }.phase?.let { phases += it }
            Thread.sleep(300)
        }
        val job = engine.jobs.value.first { it.id == id }
        log("RESULT status=${job.status} phases=$phases size=${job.fileSize} file=${job.file} public=${job.publicPath} error=${job.error}")
        if (job.status != JobStatus.COMPLETED) { log("  RAW> ${job.errorDetail?.replace("\n", " | ")?.take(1500)}"); return }
        val file = File(job.file!!)
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(file.absolutePath)
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        retriever.release()
        log("OUTPUT ${w}x$h bytes=${file.length()} leftovers=${file.parentFile?.list()?.filter { it.contains("kftmp") }}")
    }
}
